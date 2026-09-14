package dev.linguaflow

import org.json.JSONObject
import dev.linguaflow.generated.RuntimeMetricItemDto
import dev.linguaflow.generated.RuntimeMetricReportRequestDto
import java.util.UUID

internal const val LINGUAFLOW_API_ORIGIN = "https://api.linguaflow.dev"
internal const val LINGUAFLOW_ANDROID_SDK_VERSION = "0.1.0"

internal sealed interface BundleDelivery {
  data object NotModified : BundleDelivery
  data class Content(val data: JSONObject, val etag: String?) : BundleDelivery
}

internal data class MissingKeysReport(
  val requestId: String,
  val locale: String,
  val appVersion: String,
  val telemetryToken: String,
  val keys: List<String>,
) {
  fun toJson(): JSONObject = JSONObject()
    .put("requestId", requestId)
    .put("locale", locale)
    .put("appVersion", appVersion)
    .put("keys", keys)
}

internal class DeliveryApi(
  private val config: LinguaFlowConfig,
  private val installationId: String,
  private val integrityProvider: DeviceIntegrityProvider?,
  private val transport: HttpTransport,
) {
  suspend fun manifest(locale: String, explicit: Boolean): LocaleManifest {
    val localeHeader = if (explicit) {
      mapOf("X-LinguaFlow-Locale" to locale)
    } else {
      mapOf("X-LinguaFlow-Device-Locale" to locale)
    }
    val response = transport.get("${bundleUrl()}/manifest", headers(localeHeader))
    return try {
      LocaleManifest.fromJson(JSONObject(response.requireSuccess().body))
    } catch (error: LinguaFlowException) {
      throw error
    } catch (error: RuntimeException) {
      throw LinguaFlowException("Invalid LinguaFlow manifest", cause = error)
    }
  }

  suspend fun bundle(locale: String, etag: String?): BundleDelivery {
    val requestHeaders = buildMap {
      put("X-LinguaFlow-Locale", locale)
      if (etag != null) put("If-None-Match", etag)
    }
    val response = transport.get(bundleUrl(), headers(requestHeaders))
    if (response.status == 304) return BundleDelivery.NotModified
    return try {
      val data = JSONObject(response.requireSuccess().body)
      validateTranslationBundle(data)
      BundleDelivery.Content(data = data, etag = response.header("ETag"))
    } catch (error: LinguaFlowException) {
      throw error
    } catch (error: RuntimeException) {
      throw BundlePayloadException(error)
    }
  }

  suspend fun reportMissingKeys(report: MissingKeysReport) {
    transport.post(
      "$LINGUAFLOW_API_ORIGIN/v1/telemetry/${config.branchKey}/missing-keys",
      report.toJson().toString(),
      headers(
        mapOf(
          "X-LinguaFlow-Locale" to report.locale,
          "X-LinguaFlow-Telemetry-Token" to report.telemetryToken,
        ),
      ),
    ).requireSuccess()
  }

  suspend fun reportRuntimeMetrics(
    requestId: String,
    telemetryToken: String,
    appVersion: String,
    metrics: List<RuntimeMetricItemDto>,
  ) {
    val body = RuntimeContractJson.gson.toJson(
      RuntimeMetricReportRequestDto(UUID.fromString(requestId), appVersion, metrics),
    )
    transport.post(
      "$LINGUAFLOW_API_ORIGIN/v1/telemetry/${config.branchKey}/runtime-metrics",
      body,
      headers(mapOf("X-LinguaFlow-Telemetry-Token" to telemetryToken)),
    ).requireSuccess()
  }

  private fun bundleUrl(): String =
    "$LINGUAFLOW_API_ORIGIN/v1/bundles/${config.branchKey}"

  private suspend fun headers(localeHeaders: Map<String, String>): Map<String, String> = buildMap {
    putAll(localeHeaders)
    put("X-LinguaFlow-Installation-Id", installationId)
    put("X-LinguaFlow-SDK", "android")
    put("X-LinguaFlow-SDK-Version", LINGUAFLOW_ANDROID_SDK_VERSION)
    put("X-LinguaFlow-Contract-Version", LINGUAFLOW_RUNTIME_CONTRACT_VERSION.toString())
    config.overlay?.let { put("X-LinguaFlow-Overlay", it) }
    integrityProvider?.let { put("X-LinguaFlow-Integrity", it.obtainGrant(config.branchKey)) }
  }
}

internal fun validateTranslationBundle(root: JSONObject) {
  data class PendingObject(val value: JSONObject, val depth: Int)
  val pending = ArrayDeque<PendingObject>()
  pending.add(PendingObject(root, 0))
  var valueCount = 0
  while (pending.isNotEmpty()) {
    val current = pending.removeLast()
    if (current.depth > MAX_BUNDLE_DEPTH) throw BundlePayloadException()
    for (key in current.value.keys()) {
      valueCount += 1
      if (valueCount > MAX_BUNDLE_VALUES || key.isEmpty() || key in UNSAFE_OBJECT_KEYS) {
        throw BundlePayloadException()
      }
      when (val child = current.value.get(key)) {
        is String -> Unit
        is JSONObject -> pending.add(PendingObject(child, current.depth + 1))
        else -> throw BundlePayloadException()
      }
    }
  }
}

private const val MAX_BUNDLE_DEPTH = 32
private const val MAX_BUNDLE_VALUES = 100_000
private val UNSAFE_OBJECT_KEYS = setOf("__proto__", "prototype", "constructor")
