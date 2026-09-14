package dev.linguaflow

import android.content.Context
import dev.linguaflow.generated.DeliveryManifestResponseDto
import org.json.JSONObject

const val LINGUAFLOW_RUNTIME_CONTRACT_VERSION = 2

@JvmInline
value class LfKey(val path: String)

data class LfMessage(val path: String, val arguments: Map<String, Any>)

enum class BundleSource {
  REMOTE,
  DOWNLOADED,
  BUNDLED,
}

enum class MissingBehavior {
  FALLBACK,
  KEY,
  EMPTY,
  THROW,
}
enum class LocaleResolutionReason(val wireValue: String) {
  SELECTED("selected"),
  MAPPED("mapped"),
  DEVICE("device"),
  FALLBACK("fallback");

  companion object {
    fun fromWire(value: String): LocaleResolutionReason =
      entries.firstOrNull { it.wireValue == value }
        ?: throw LinguaFlowException("Invalid locale resolution reason")
  }
}

data class LinguaFlowConfig(
  val branchKey: String,
  val overlay: String? = null,
  val cacheTtlSeconds: Long = 300,
  val bundledPath: String = "linguaflow",
  val offlineEnabled: Boolean = true,
  val missingBehavior: MissingBehavior = MissingBehavior.FALLBACK,
  val missingKeyTelemetryEnabled: Boolean = false,
  val appVersion: String = "",
) {
  init {
    require(BRANCH_KEY_PATTERN.matches(branchKey)) { "Invalid branch delivery key" }
    require(overlay == null || OVERLAY_PATTERN.matches(overlay)) { "Invalid overlay slug" }
    require(cacheTtlSeconds >= 0) { "TTL cannot be negative" }
    require(!bundledPath.startsWith('/') && !bundledPath.contains("..") && !bundledPath.contains("://")) {
      "Bundled path must be an application-relative asset directory"
    }
  }

  companion object {
    private val BRANCH_KEY_PATTERN = Regex("^br_live_[A-Za-z0-9_-]+$")
    private val OVERLAY_PATTERN = Regex("^[a-z0-9][a-z0-9-]{1,47}$")

    fun fromAsset(context: Context, path: String = ".linguaconfig"): LinguaFlowConfig {
      val json = JSONObject(context.assets.open(path).bufferedReader().use { it.readText() })
      val offline = json.optJSONObject("offline") ?: JSONObject()
      return LinguaFlowConfig(
        branchKey = json.getString("branchKey"),
        overlay = json.optString("overlay").ifBlank { null },
        cacheTtlSeconds = json.optLong("cacheTtlSeconds", 300),
        bundledPath = json.optString("bundledPath", "linguaflow"),
        offlineEnabled = offline.optBoolean("enabled", true),
        missingBehavior = when (offline.optString("missingTranslation", "fallback")) {
          "key" -> MissingBehavior.KEY
          "empty" -> MissingBehavior.EMPTY
          "throw" -> MissingBehavior.THROW
          else -> MissingBehavior.FALLBACK
        },
        missingKeyTelemetryEnabled =
          json.optJSONObject("telemetry")?.optBoolean("missingKeys", false) ?: false,
        appVersion = json.optJSONObject("telemetry")?.optString("appVersion", "") ?: "",
      )
    }
  }
}

data class LocaleManifest(
  val version: Int,
  val releaseId: String,
  val sequence: Int,
  val requestedLocale: String,
  val resolvedLocale: String,
  val reason: LocaleResolutionReason,
  val supportedLocales: List<String>,
  val translatedLocales: List<String>,
  val fallbackLocale: String,
  val localeMappings: Map<String, String>,
  val bundlePath: String,
  val overlays: List<String>,
  val overlay: String?,
  val missingKeyTelemetryEnabled: Boolean,
  val missingKeyTelemetryMaxBatchSize: Int,
  val rolloutCandidateReleaseId: String,
  val rolloutPercentage: Int,
  val rolloutSelection: String,
  val runtimeTelemetryToken: String?,
  val runtimeTelemetryExpiresAt: String?,
) {
  fun toJson(): JSONObject = JSONObject()
    .put("version", version)
    .put("releaseId", releaseId)
    .put("sequence", sequence)
    .put("requestedLocale", requestedLocale)
    .put("resolvedLocale", resolvedLocale)
    .put("reason", reason.wireValue)
    .put("supportedLocales", supportedLocales)
    .put("translatedLocales", translatedLocales)
    .put("fallbackLocale", fallbackLocale)
    .put("localeMappings", JSONObject(localeMappings))
    .put("bundlePath", bundlePath)
    .put("overlays", overlays)
    .put("overlay", overlay)
    .put(
      "missingKeyTelemetry",
      JSONObject()
        .put("candidateReleaseId", rolloutCandidateReleaseId)
        .put("enabled", missingKeyTelemetryEnabled)
        .put("maxBatchSize", missingKeyTelemetryMaxBatchSize),
    )
    .put(
      "rollout",
      JSONObject()
        .put("percentage", rolloutPercentage)
        .put("selection", rolloutSelection),
    )
    .put(
      "runtimeTelemetry",
      runtimeTelemetryToken?.let {
        JSONObject().put("token", it).put("expiresAt", runtimeTelemetryExpiresAt)
      },
    )

  companion object {
    fun fromJson(json: JSONObject): LocaleManifest {
      val declaredVersion = json.getInt("version")
      require(declaredVersion == LINGUAFLOW_RUNTIME_CONTRACT_VERSION) {
        "Unsupported LinguaFlow contract version: $declaredVersion"
      }
      val contract = RuntimeContractJson.gson.fromJson(
        json.toString(),
        DeliveryManifestResponseDto::class.java,
      )
      val version = contract.version.value
      return LocaleManifest(
        version = version,
        releaseId = contract.releaseId,
        sequence = contract.sequence,
        requestedLocale = contract.requestedLocale,
        resolvedLocale = contract.resolvedLocale,
        reason = LocaleResolutionReason.fromWire(contract.reason.value),
        supportedLocales = contract.supportedLocales,
        translatedLocales = contract.translatedLocales,
        fallbackLocale = contract.fallbackLocale,
        localeMappings = contract.localeMappings,
        bundlePath = contract.bundlePath,
        overlays = contract.overlays,
        overlay = contract.overlay,
        missingKeyTelemetryEnabled = contract.missingKeyTelemetry.enabled,
        missingKeyTelemetryMaxBatchSize = contract.missingKeyTelemetry.maxBatchSize,
        rolloutCandidateReleaseId = contract.rollout.candidateReleaseId,
        rolloutPercentage = contract.rollout.percentage,
        rolloutSelection = contract.rollout.selection.value,
        runtimeTelemetryToken = contract.runtimeTelemetry?.token,
        runtimeTelemetryExpiresAt = contract.runtimeTelemetry?.expiresAt?.toString(),
      )
    }
  }
}

data class LinguaFlowState(
  val ready: Boolean = false,
  val loading: Boolean = false,
  val selectedLocale: String? = null,
  val activeLocale: String? = null,
  val resolvedLocale: String? = null,
  val fallbackLocale: String? = null,
  val supportedLocales: List<String> = emptyList(),
  val source: BundleSource? = null,
  val error: Throwable? = null,
)

fun interface DeviceIntegrityProvider {
  suspend fun obtainGrant(branchKey: String): String
}

open class LinguaFlowException(
  message: String,
  val statusCode: Int? = null,
  cause: Throwable? = null,
) : RuntimeException(message, cause)

class BundlePayloadException(cause: Throwable? = null) :
  LinguaFlowException("Invalid LinguaFlow translation bundle", cause = cause)

internal fun org.json.JSONArray.strings() = (0 until length()).map { getString(it) }
internal fun JSONObject.stringsMap() = keys().asSequence().associateWith { getString(it) }
