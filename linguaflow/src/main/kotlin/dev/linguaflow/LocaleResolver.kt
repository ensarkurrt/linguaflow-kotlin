package dev.linguaflow

internal object LocaleResolver {
  fun resolveOffline(manifest: LocaleManifest, input: String): String {
    val normalized = input.replace('_', '-').lowercase()
    val language = normalized.substringBefore('-')
    manifest.localeMappings[normalized]?.let { return it }
    manifest.localeMappings[language]?.let { return it }
    return manifest.translatedLocales.firstOrNull {
      val candidate = it.lowercase()
      candidate == normalized || candidate.substringBefore('-') == language
    } ?: manifest.fallbackLocale
  }
}
