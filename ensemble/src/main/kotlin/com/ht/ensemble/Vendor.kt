package com.ht.ensemble

/**
 * What a model is good at. Scores run 0..10 and are *priors*: the [Scoreboard] bends them
 * with real success rate and latency while a job runs.
 */
enum class Skill(val label: String) {
    PLAN("planning"), RESEARCH("fresh research"), WRITE("long-form writing"), CODE("code"),
    REASON("reasoning & math"), REVIEW("critique"), CREATIVE("ideas"), SPEED("speed"), LONG("long context");

    companion object {
        private val rules: List<Pair<Skill, Regex>> = listOf(
            RESEARCH to Regex("search|research|latest|news|trend|price|source|survey|market|검색|조사|최신|뉴스|찾|트렌드|가격|현황|출처|자료"),
            CODE to Regex("code|program|script|function|bug|api|html|css|javascript|python|kotlin|\\.(kt|py|js|ts|java|go|rs)\\b|코드|프로그램|스크립트|함수|버그|앱"),
            REVIEW to Regex("review|critique|evaluate|audit|counter|fact.?check|proofread|검토|비판|평가|점검|반박|교정|팩트"),
            PLAN to Regex("plan|outline|structure|architecture|strategy|roadmap|계획|분해|구조|목차|설계|전략"),
            CREATIVE to Regex("idea|brainstorm|concept|naming|slogan|story|아이디어|발상|브레인|창의|콘셉트|이름 짓"),
            REASON to Regex("calculat|math|proof|statistic|logic|analy[sz]|계산|수학|증명|통계|논리|분석"),
            LONG to Regex("summari[sz]e|long document|paper|pdf|transcript|요약|긴 문서|논문 읽"),
        )

        /** Guess the skill a task needs from its words (English and Korean keywords). */
        fun of(text: String): Skill {
            val t = text.lowercase()
            return rules.firstOrNull { it.second.containsMatchIn(t) }?.first ?: WRITE
        }
    }
}

/**
 * A model family the team can hire. Two members are "rivals" when their vendors differ, and
 * Ensemble always prefers a rival to review someone's work — the same blind spots rarely
 * repeat across companies.
 *
 * Built-in profiles are honest about weaknesses on purpose; add your own with [Vendor.custom].
 */
class Vendor(
    val id: String,
    val label: String,
    val company: String,
    val skills: Map<Skill, Int>,
    val strong: String,
    val weak: String,
) {
    fun score(s: Skill): Int = skills[s] ?: 5

    override fun equals(other: Any?): Boolean = other is Vendor && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = label

    companion object {
        private fun v(id: String, label: String, company: String, plan: Int, research: Int, write: Int, code: Int, reason: Int,
                      review: Int, creative: Int, speed: Int, long: Int, strong: String, weak: String) =
            Vendor(id, label, company, mapOf(Skill.PLAN to plan, Skill.RESEARCH to research, Skill.WRITE to write, Skill.CODE to code,
                Skill.REASON to reason, Skill.REVIEW to review, Skill.CREATIVE to creative, Skill.SPEED to speed, Skill.LONG to long), strong, weak)

        val CLAUDE: Vendor = v("claude", "Claude", "Anthropic", 9, 6, 9, 9, 8, 9, 8, 5, 9,
            "Most careful long-form writing and code; follows instructions precisely; sharp reviewer.",
            "Web plans hit usage limits fast; weaker at fresh web research; on the slow side.")
        val GPT: Vendor = v("gpt", "ChatGPT", "OpenAI", 8, 8, 8, 8, 9, 8, 8, 6, 7,
            "Strong reasoning and math; tidy structured output; searches the web and cites sources.",
            "Frequent login or human checks on the web; can lose details early in very long inputs.")
        val GEMINI: Vendor = v("gemini", "Gemini", "Google", 7, 9, 7, 7, 8, 7, 7, 8, 10,
            "Best at fresh facts via Google Search; reads extremely long material in one pass.",
            "Tends to be verbose; occasionally breaks strict formats such as JSON.")
        val GROK: Vendor = v("grok", "Grok", "xAI", 6, 8, 6, 7, 7, 6, 8, 8, 6,
            "Real-time news and social reaction; fast; bold ideas.",
            "Uneven formatting and polish; claims need fact-checking.")
        val DEEPSEEK: Vendor = v("deepseek", "DeepSeek", "DeepSeek", 7, 5, 7, 8, 8, 7, 6, 6, 6,
            "Cheap at volume; strong reasoning and code.",
            "Weak on recent events without search; slows or drops at peak hours.")
        val QWEN: Vendor = v("qwen", "Qwen", "Alibaba", 7, 5, 7, 8, 8, 6, 6, 7, 7,
            "Capable open-weight model for code and multilingual text; runs locally.",
            "Local sizes trade quality for speed; no live web access.")
        val MISTRAL: Vendor = v("mistral", "Mistral", "Mistral AI", 6, 5, 7, 7, 6, 6, 6, 8, 6,
            "Fast, efficient European models; good for drafting and summaries.",
            "Less depth on hard reasoning; no live web access.")
        val LLAMA: Vendor = v("llama", "Llama", "Meta", 6, 4, 6, 6, 6, 5, 6, 8, 6,
            "Open weights you can run anywhere, including fully offline.",
            "Smaller local sizes miss nuance; treat as a fast helper, not a lead.")

        val BUILT_IN: List<Vendor> = listOf(CLAUDE, GPT, GEMINI, GROK, DEEPSEEK, QWEN, MISTRAL, LLAMA)

        fun of(id: String?): Vendor? = BUILT_IN.firstOrNull { it.id.equals(id, true) || it.label.equals(id, true) }

        /** Describe your own model. Unlisted skills default to 5. */
        fun custom(id: String, label: String = id, company: String = label, skills: Map<Skill, Int> = emptyMap(),
                          strong: String = "", weak: String = ""): Vendor = Vendor(id, label, company, skills, strong, weak)

        private val LIMIT = Regex(
            "(usage limit|rate limit|limit reached|reached your|hit your|too many requests|quota|try again (in|later)|" +
                "upgrade to (plus|pro)|out of messages|message limit|at capacity|429|사용량|한도|용량 초과|잠시 후 다시|요청이 너무 많|업그레이드)",
            RegexOption.IGNORE_CASE)

        /**
         * Is this reply a usage-limit notice rather than an answer? Only short replies count, so
         * a long essay that happens to mention "quota" is not mistaken for one.
         */
        fun limitHit(text: String, via: String = ""): Boolean =
            via.contains("429") || (text.length < 300 && LIMIT.containsMatchIn(text.take(600)))
    }
}
