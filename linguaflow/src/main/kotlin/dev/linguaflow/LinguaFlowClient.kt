package dev.linguaflow

import android.content.Context
import android.icu.text.MessageFormat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class LinguaFlowClient(
  private val context: Context,
  val config: LinguaFlowConfig,
  integrityProvider: DeviceIntegrityProvider? = null,
  transport: HttpTransport = UrlConnectionTransport(),
) : AutoCloseable {
  private val store = LocalizationStore(context, config)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val api = DeliveryApi(config, store.installationId, integrityProvider, transport)
  private val missingKeys = MissingKeyReporter(config, context, api, scope) { manifest }
  private val runtimeMetrics = RuntimeMetricReporter(
    config, context, api, scope, { manifest }, integrityProvider != null,
  )
  private val mutableState = MutableStateFlow(LinguaFlowState(selectedLocale = store.selectedLocale))
  private var manifest: LocaleManifest? = null
  private var bundle: JSONObject? = null
  private var lastChecked = 0L

  val state: StateFlow<LinguaFlowState> = mutableState.asStateFlow()

  suspend fun initialize(
    deviceLocale: String = Locale.getDefault().toLanguageTag(),
    force: Boolean = false,
  ) {
    val cacheAge = System.currentTimeMillis() - lastChecked
    if (!force && bundle != null && cacheAge <= config.cacheTtlSeconds * 1_000) return
    mutableState.value = state.value.copy(loading = true)
    try {
      activateRemote(deviceLocale)
    } catch (error: Throwable) {
      if (error is CancellationException) throw error
      if (!config.offlineEnabled || !activateOffline(deviceLocale, error)) throw error
    } finally {
      mutableState.value = state.value.copy(loading = false)
    }
  }

  suspend fun selectLocale(locale: String?) {
    val selected = locale?.trim()?.ifBlank { null }
    store.selectedLocale = selected
    mutableState.value = state.value.copy(selectedLocale = selected)
    initialize(force = true)
  }

  suspend fun refresh() = initialize(force = true)

  fun text(key: LfKey, fallback: String = ""): String =
    render(key.path, emptyMap(), fallback)

  fun text(message: LfMessage, fallback: String = ""): String =
    render(message.path, message.arguments, fallback)

  suspend fun flushMissingKeys() {
    missingKeys.flush()
    runtimeMetrics.flush()
  }

  override fun close() {
    scope.cancel()
  }

  private suspend fun activateRemote(deviceLocale: String) {
    val selected = state.value.selectedLocale
    val remoteManifest = api.manifest(selected ?: deviceLocale, selected != null)
    manifest = remoteManifest
    runtimeMetrics.record(RuntimeMetricKind.delivery_request, RuntimeMetricOutcome.success)
    lastChecked = System.currentTimeMillis()
    store.saveManifest(remoteManifest)

    val effectiveSelection = if (
      selected != null && remoteManifest.reason == LocaleResolutionReason.FALLBACK
    ) {
      store.selectedLocale = null
      null
    } else {
      selected
    }
    val cached = store.bundle(remoteManifest.resolvedLocale)
    if (cached?.releaseId == remoteManifest.releaseId) {
      activate(cached.data, BundleSource.DOWNLOADED, effectiveSelection)
      return
    }

    val delivery = try {
      api.bundle(remoteManifest.resolvedLocale, cached?.etag).also {
        runtimeMetrics.record(RuntimeMetricKind.delivery_request, RuntimeMetricOutcome.success)
      }
    } catch (error: Throwable) {
      if (error is CancellationException) throw error
      val outcome = when {
        error is java.net.SocketTimeoutException -> RuntimeMetricOutcome.timeout
        error is LinguaFlowException && (error.statusCode ?: 0) >= 500 -> RuntimeMetricOutcome.server_error
        else -> RuntimeMetricOutcome.failure
      }
      runtimeMetrics.record(RuntimeMetricKind.delivery_request, outcome)
      runtimeMetrics.record(
        if (error is BundlePayloadException || error is org.json.JSONException) {
          RuntimeMetricKind.bundle_parse
        } else {
          RuntimeMetricKind.bundle_download
        },
        RuntimeMetricOutcome.failure,
      )
      throw error
    }
    when (delivery) {
      BundleDelivery.NotModified -> {
        val current = cached ?: throw LinguaFlowException("Bundle returned 304 without cache")
        activate(current.data, BundleSource.DOWNLOADED, effectiveSelection)
      }
      is BundleDelivery.Content -> {
        runtimeMetrics.record(RuntimeMetricKind.bundle_download, RuntimeMetricOutcome.success)
        runtimeMetrics.record(RuntimeMetricKind.bundle_parse, RuntimeMetricOutcome.success)
        store.saveBundle(
          remoteManifest.resolvedLocale,
          StoredBundle(remoteManifest.releaseId, delivery.etag, delivery.data),
        )
        activate(delivery.data, BundleSource.REMOTE, effectiveSelection)
      }
    }
  }

  private fun activateOffline(deviceLocale: String, error: Throwable): Boolean {
    val offlineManifest = manifest ?: store.manifest() ?: return false
    manifest = offlineManifest
    val locale = LocaleResolver.resolveOffline(
      offlineManifest,
      state.value.selectedLocale ?: deviceLocale,
    )
    store.bundle(locale)?.let {
      activate(it.data, BundleSource.DOWNLOADED, state.value.selectedLocale, error)
      return true
    }
    val bundled = loadBundledAsset(locale) ?: return false
    activate(bundled, BundleSource.BUNDLED, state.value.selectedLocale, error)
    return true
  }

  private fun loadBundledAsset(locale: String): JSONObject? = runCatching {
    val content = context.assets.open("${config.bundledPath}/$locale.json")
      .bufferedReader()
      .use { it.readText() }
    JSONObject(content).also(::validateTranslationBundle)
  }.getOrNull()

  private fun activate(
    data: JSONObject,
    source: BundleSource,
    selected: String?,
    error: Throwable? = null,
  ) {
    bundle = data
    val current = manifest
    mutableState.value = LinguaFlowState(
      ready = true,
      loading = state.value.loading,
      selectedLocale = selected,
      activeLocale = current?.requestedLocale,
      resolvedLocale = current?.resolvedLocale,
      fallbackLocale = current?.fallbackLocale,
      supportedLocales = current?.supportedLocales ?: emptyList(),
      source = source,
      error = error,
    )
  }

  private fun render(path: String, arguments: Map<String, Any>, fallback: String): String {
    var current: Any = bundle ?: return missing(path, fallback)
    for (segment in path.split('.')) {
      current = (current as? JSONObject)?.opt(segment) ?: return missing(path, fallback)
    }
    if (current !is String) return missing(path, fallback)
    val locale = Locale.forLanguageTag(state.value.resolvedLocale ?: "en")
    return try {
      MessageFormat(current, locale).format(arguments).also {
        runtimeMetrics.record(RuntimeMetricKind.icu_format, RuntimeMetricOutcome.success)
      }
    } catch (error: RuntimeException) {
      runtimeMetrics.record(RuntimeMetricKind.icu_format, RuntimeMetricOutcome.failure)
      throw error
    }
  }

  private fun missing(path: String, fallback: String): String {
    missingKeys.record(path)
    return when (config.missingBehavior) {
      MissingBehavior.FALLBACK -> fallback
      MissingBehavior.KEY -> path
      MissingBehavior.EMPTY -> ""
      MissingBehavior.THROW -> throw LinguaFlowException("Missing translation: $path")
    }
  }
}
