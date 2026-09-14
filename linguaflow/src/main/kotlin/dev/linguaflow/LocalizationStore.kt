package dev.linguaflow

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import org.json.JSONObject

internal data class StoredBundle(
  val releaseId: String,
  val etag: String?,
  val data: JSONObject,
)

internal class LocalizationStore(context: Context, config: LinguaFlowConfig) {
  private val preferences = context.getSharedPreferences("linguaflow", Context.MODE_PRIVATE)
  private val cacheDirectory = context.cacheDir
  private val prefix = "${config.branchKey}:${config.overlay ?: "base"}:"

  var selectedLocale: String?
    get() = preferences.getString(key("selected"), null)?.takeIf(LOCALE_PATTERN::matches)
    set(value) {
      preferences.edit().apply {
        if (value == null) remove(key("selected")) else putString(key("selected"), value)
      }.apply()
    }

  val installationId: String
    get() {
      val storageKey = key("installation")
      return preferences.getString(storageKey, null)?.takeIf(INSTALLATION_ID_PATTERN::matches)
        ?: UUID.randomUUID().toString().also {
          preferences.edit().putString(storageKey, it).apply()
        }
    }

  fun manifest(): LocaleManifest? = runCatching {
    read("manifest")?.let(LocaleManifest::fromJson)
  }.getOrNull()

  fun saveManifest(manifest: LocaleManifest) = write("manifest", manifest.toJson())

  fun bundle(locale: String): StoredBundle? = runCatching {
    read("bundle-$locale")?.let(::decodeStoredBundle)
  }.getOrNull()

  fun saveBundle(locale: String, value: StoredBundle) {
    write(
      "bundle-$locale",
      JSONObject()
        .put("releaseId", value.releaseId)
        .put("etag", value.etag)
        .put("data", value.data),
    )
  }

  private fun key(suffix: String): String = "$prefix$suffix"

  private fun file(suffix: String): File =
    File(cacheDirectory, "linguaflow/${key(suffix).replace(':', '-')}.json")

  private fun read(suffix: String): JSONObject? = runCatching {
    JSONObject(AtomicFile(file(suffix)).readFully().decodeToString())
  }.getOrNull()

  private fun write(suffix: String, value: JSONObject) {
    val atomicFile = AtomicFile(file(suffix))
    atomicFile.baseFile.parentFile?.mkdirs()
    val output = atomicFile.startWrite()
    try {
      output.write(value.toString().encodeToByteArray())
      atomicFile.finishWrite(output)
    } catch (error: Exception) {
      atomicFile.failWrite(output)
      throw error
    }
  }

  private companion object {
    val INSTALLATION_ID_PATTERN = Regex("^[A-Za-z0-9._:-]{8,128}$")
    val LOCALE_PATTERN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9_-]{0,126}[A-Za-z0-9])?$")
  }
}

internal fun decodeStoredBundle(json: JSONObject): StoredBundle {
  val data = json.optJSONObject("data") ?: throw BundlePayloadException()
  validateTranslationBundle(data)
  return StoredBundle(
    releaseId = json.getString("releaseId"),
    etag = json.optString("etag").ifBlank { null },
    data = data,
  )
}
