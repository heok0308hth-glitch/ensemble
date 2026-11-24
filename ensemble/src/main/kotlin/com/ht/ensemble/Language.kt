package com.ht.ensemble

/**
 * The language of the deliverable. Instructions to the models are always English (they follow
 * English best); only the output language changes. [AUTO] reads it off the goal, so a goal
 * written in Korean gets a Korean report without any configuration.
 */
class Language private constructor(val name: String) {
    override fun toString(): String = name
    override fun equals(other: Any?): Boolean = other is Language && other.name == name
    override fun hashCode(): Int = name.hashCode()

    /** One sentence appended to every working prompt. */
    fun directive(): String = "Write the deliverable in $name."

    companion object {
        val AUTO = Language("auto")
        val ENGLISH = Language("English")
        val KOREAN = Language("Korean")
        val JAPANESE = Language("Japanese")
        val CHINESE = Language("Chinese")
        val SPANISH = Language("Spanish")
        val FRENCH = Language("French")
        val GERMAN = Language("German")
        val PORTUGUESE = Language("Portuguese")

        /** Any language the models know, by its English name ("Vietnamese", "Polish", …). */
        fun named(englishName: String): Language = Language(englishName.trim().replaceFirstChar { it.uppercase() })

        private val scripts: List<Pair<Regex, String>> = listOf(
            Regex("[가-힣]") to "Korean",
            Regex("[\\u3040-\\u30ff]") to "Japanese",       // kana before Han: Japanese also uses kanji
            Regex("[\\u4e00-\\u9fff]") to "Chinese",
            Regex("[\\u0400-\\u04ff]") to "Russian",
            Regex("[\\u0600-\\u06ff]") to "Arabic",
            Regex("[\\u0590-\\u05ff]") to "Hebrew",
            Regex("[\\u0e00-\\u0e7f]") to "Thai",
            Regex("[\\u0900-\\u097f]") to "Hindi",
            Regex("[\\u0370-\\u03ff]") to "Greek",
        )
        private val stopwords: List<Pair<String, Set<String>>> = listOf(
            "Spanish" to setOf("el", "la", "los", "las", "para", "una", "con", "que", "por", "del", "cómo", "qué"),
            "French" to setOf("le", "les", "des", "une", "pour", "avec", "est", "dans", "sur", "comment", "du", "et"),
            "German" to setOf("der", "die", "das", "und", "für", "mit", "ein", "eine", "nicht", "wie", "ist", "zu"),
            "Portuguese" to setOf("os", "as", "uma", "para", "com", "não", "como", "do", "da", "em", "que", "é"),
            "Italian" to setOf("il", "gli", "una", "per", "con", "che", "come", "della", "di", "è", "non", "nel"),
        )

        /** Detect the language of [text]: script first, then common function words, else English. */
        fun detect(text: String): Language {
            val letters = text.count { it.isLetter() }.coerceAtLeast(1)
            scripts.firstOrNull { (re, _) -> re.findAll(text).count() * 5 >= letters || re.findAll(text).count() >= 4 }
                ?.let { return Language(it.second) }
            val words = Regex("\\p{L}+").findAll(text.lowercase()).map { it.value }.toList()
            if (words.isEmpty()) return ENGLISH
            val best = stopwords.map { (lang, set) -> lang to words.count { it in set } }.maxBy { it.second }
            return if (best.second >= 2 && best.second * 6 >= words.size) Language(best.first) else ENGLISH
        }

        fun resolve(lang: Language, goal: String): Language = if (lang == AUTO) detect(goal) else lang
    }
}
