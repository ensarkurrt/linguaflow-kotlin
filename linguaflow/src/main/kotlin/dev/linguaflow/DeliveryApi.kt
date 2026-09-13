package dev.linguaflow

import org.json.JSONObject

internal const val LINGUAFLOW_API_ORIGIN = "https://api.linguaflow.dev"
internal const val LINGUAFLOW_ANDROID_SDK_VERSION = "0.1.0"

internal sealed interface BundleDelivery {
  data object NotModified : BundleDelivery
  data class Content(val data: JSONObject, val etag: String?) : BundleDelivery
}

internal data class MissingKeysReport(
  val requestId: String,
  val releaseId: String,
  val locale: String,
  val appVersion: String,
  val keys: List<String>,
) {
  fun toJson(): JSONObject = JSONObject()
    .put("requestId", requestId)
    .put("releaseId", releaseId)
    .put("locale", locale)
    .put("appVersion", appVersion)
    .put("platform", "android")
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
    return LocaleManifest.fromJson(JSONObject(response.requireSuccess().body))
  }

  suspend fun bundle(locale: String, etag: String?): BundleDelivery {
    val requestHeaders = buildMap {
      put("X-LinguaFlow-Locale", locale)
      if (etag != null) put("If-None-Match", etag)
    }
    val response = transport.get(bundleUrl(), headers(requestHeaders))
    if (response.status == 304) return BundleDelivery.NotModified
    return BundleDelivery.Content(
      data = JSONObject(response.requireSuccess().body),
      etag = response.header("ETag"),
    )
  }

  suspend fun reportMissingKeys(report: MissingKeysReport) {
    transport.post(
      "$LINGUAFLOW_API_ORIGIN/v1/telemetry/${config.branchKey}/missing-keys",
      report.toJson().toString(),
      headers(mapOf("X-LinguaFlow-Locale" to report.locale)),
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
