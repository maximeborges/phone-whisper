package com.kafkasl.phonewhisper

/**
 * Single source of truth for languages: the list offered in the UI (settings
 * picker + model browser filter) and which languages a given model supports.
 */
object Languages {
    data class Lang(val code: String, val name: String)

    /** Selectable languages, alphabetical by name. */
    val ALL = listOf(
        "ar" to "Arabic", "bg" to "Bulgarian", "zh" to "Chinese", "hr" to "Croatian",
        "cs" to "Czech", "da" to "Danish", "nl" to "Dutch", "en" to "English",
        "et" to "Estonian", "fi" to "Finnish", "fr" to "French", "de" to "German",
        "el" to "Greek", "hi" to "Hindi", "hu" to "Hungarian", "id" to "Indonesian",
        "it" to "Italian", "ja" to "Japanese", "ko" to "Korean", "lv" to "Latvian",
        "lt" to "Lithuanian", "mt" to "Maltese", "fa" to "Persian", "pl" to "Polish",
        "pt" to "Portuguese", "ro" to "Romanian", "ru" to "Russian", "sk" to "Slovak",
        "sl" to "Slovenian", "es" to "Spanish", "sv" to "Swedish", "th" to "Thai",
        "tr" to "Turkish", "uk" to "Ukrainian", "vi" to "Vietnamese",
    ).map { Lang(it.first, it.second) }

    private val NAMES = ALL.associate { it.code to it.name }

    /** Languages of NVIDIA Parakeet-TDT v3 / Canary v2. */
    private val EU25 = setOf(
        "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu", "it",
        "lv", "lt", "mt", "pl", "pt", "ro", "sk", "sl", "es", "sv", "ru", "uk",
    )

    /** Extra codes recognised in repo names (not selectable, e.g. Cantonese). */
    private val NAME_CODES = NAMES.keys + "yue" + "be"

    fun name(code: String): String = NAMES[code] ?: code

    /**
     * Languages supported by a model, inferred from its repo name + architecture.
     * `null` = (practically) any language; empty = unknown.
     */
    fun supported(repoName: String, arch: ModelArch): Set<String>? {
        val n = repoName.lowercase()
        return when {
            arch == ModelArch.WHISPER -> if (".en" in n) setOf("en") else null
            arch == ModelArch.SENSE_VOICE -> setOf("zh", "en", "ja", "ko", "yue")
            arch == ModelArch.DOLPHIN || arch == ModelArch.OMNILINGUAL -> null
            "parakeet" in n && "v3" in n -> EU25
            arch == ModelArch.CANARY && "v2" in n -> EU25
            else -> n.split('-', '_', '.').filter { it in NAME_CODES }.toSet()
        }
    }

    /** Short human label for a model row, e.g. "multilingual" / "en, de, es, fr". */
    fun label(repoName: String, arch: ModelArch): String {
        val n = repoName.lowercase()
        val s = supported(repoName, arch)
        return when {
            arch == ModelArch.OMNILINGUAL -> "1600+ languages"
            s == null -> "multilingual"
            s == EU25 -> "25 European languages (incl. cs)"
            ".en" in n && arch == ModelArch.WHISPER -> "English"
            else -> s.joinToString(", ")
        }
    }
}
