package com.ht.ensemble

/** What a board note is about. Warnings sort first: they are what the next teammate must not miss. */
enum class NoteKind(val label: String, private val aliases: List<String>) {
    WARNING("warning", listOf("warning", "warn", "caution", "주의")),
    DECISION("decision", listOf("decision", "decided", "결정")),
    FACT("fact", listOf("fact", "사실")),
    QUESTION("question", listOf("question", "ask", "질문"));

    companion object {
        fun of(word: String): NoteKind? = entries.firstOrNull { k -> k.aliases.any { it.equals(word.trim(), true) } }
    }
}

/**
 * The shared board. Every teammate may end a reply with a short `[board]` block; the desk strips
 * it from the deliverable, pins the notes here, and every teammate who starts *later* reads the
 * digest. Members that are not linked by task dependencies still learn from each other, which
 * cuts duplicated work and contradictions.
 *
 * ```
 * …deliverable…
 * [board]
 * fact: The deadline is Dec 12 (official notice)
 * decision: We target the poster category
 * question: Can someone confirm the entry fee?
 * ```
 */
class Board(private val capacity: Int = 40) {
    data class Note(val who: String, val kind: NoteKind, val text: String)

    private val notes = ArrayList<Note>()

    fun add(who: String, kind: NoteKind, text: String): Boolean = synchronized(notes) {
        val t = text.trim().take(220)
        if (t.length < 4 || notes.any { it.text.equals(t, true) }) return false
        notes += Note(who, kind, t)
        if (notes.size > capacity) notes.removeAt(0)
        true
    }

    fun all(): List<Note> = synchronized(notes) { notes.toList() }
    fun isEmpty(): Boolean = synchronized(notes) { notes.isEmpty() }

    /** The digest teammates receive: warnings → decisions → facts → questions, within [max] chars. */
    fun digest(max: Int = 1400): String {
        val sb = StringBuilder()
        for (n in all().sortedBy { it.kind.ordinal }) {
            val line = "- ${n.kind.label} (${n.who}): ${n.text}\n"
            if (sb.length + line.length > max) break
            sb.append(line)
        }
        return sb.toString().trimEnd()
    }

    companion object {
        private val HEAD = Regex("""(?im)^\s*[\[［【]?\s*(board|team board|칠판)\s*[\]］】]?\s*:?\s*$""")
        private val LINE = Regex("""^\s*[-•*]?\s*([A-Za-z가-힣]+)\s*[:：]\s*(.+)$""")
        private val EMPTY = Regex("(none|n/?a|-|…|없음)", RegexOption.IGNORE_CASE)

        /** Split a reply into (deliverable, notes). Replies without a board block come back unchanged. */
        fun split(raw: String): Pair<String, List<Pair<NoteKind, String>>> {
            val m = HEAD.findAll(raw).lastOrNull() ?: return raw.trim() to emptyList()
            val items = raw.substring(m.range.last + 1).lines().mapNotNull { line ->
                val g = LINE.find(line) ?: return@mapNotNull null
                val kind = NoteKind.of(g.groupValues[1]) ?: return@mapNotNull null
                val text = g.groupValues[2].trim()
                if (text.isBlank() || text.matches(EMPTY)) null else kind to text
            }
            if (items.isEmpty()) return raw.trim() to emptyList()
            return raw.substring(0, m.range.first).trim() to items.take(5)
        }
    }
}

/**
 * The dispatch market. For every task each member places a bid:
 *
 * `bid = rate/10 + HINT·(planner named them) − LOAD·(tasks held / lanes) − CENTER·(is the center, team ≥ 3)`
 *
 * The best model no longer swallows the whole plan: once it holds a task its bid drops, and a
 * similarly able rival wins the next one. With three or more members the center is left out of
 * the pool entirely, so it keeps its attention on planning and review.
 */
object Dispatch {
    const val LOAD = 0.22
    const val CENTER = 0.40
    const val HINT = 0.12

    fun bid(rate: Double, load: Int, lanes: Int, isCenter: Boolean, teamSize: Int, hinted: Boolean): Double =
        rate / 10.0 + (if (hinted) HINT else 0.0) - LOAD * load / lanes.coerceAtLeast(1) - (if (isCenter && teamSize >= 3) CENTER else 0.0)

    /**
     * Assign a member to every item, in order. Items for which [skip] is true (tool calls) get
     * `null`. [hint] is the name the planner suggested — a bonus, never a command.
     */
    fun <T> assign(
        items: List<T>,
        team: List<CrewMember>,
        center: CrewMember?,
        rate: (CrewMember, Skill) -> Double,
        skill: (T) -> Skill,
        hint: (T) -> String = { "" },
        skip: (T) -> Boolean = { false },
    ): List<CrewMember?> {
        if (team.isEmpty()) return items.map { null }
        val pool = if (team.size >= 3 && center != null) team.filter { it.key != center.key } else team
        val loads = HashMap<String, Int>()
        return items.map { item ->
            if (skip(item)) return@map null
            val s = skill(item)
            val m = pool.maxBy { bid(rate(it, s), loads[it.key] ?: 0, it.lanes, it.key == center?.key, team.size, matches(hint(item), it)) }
            loads[m.key] = (loads[m.key] ?: 0) + 1
            m
        }
    }

    /** Does a planner's free-text name ("gpt", "Gemini 2") refer to this member? */
    fun matches(who: String, m: CrewMember): Boolean {
        val w = who.lowercase().trim()
        if (w.isBlank()) return false
        return m.name.lowercase().contains(w) || w.contains(m.name.lowercase()) || w.contains(m.vendor.label.lowercase()) || w.contains(m.vendor.id)
    }
}
