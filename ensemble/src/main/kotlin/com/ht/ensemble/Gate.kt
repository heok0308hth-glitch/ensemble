package com.ht.ensemble

/**
 * The save gate: cheap, deterministic checks that run before anything is graded or saved.
 *
 * Models fail in two ways a grader can miss. Sometimes a web or API client returns the
 * instruction instead of the answer (an "echo"). Sometimes it returns an answer that stops
 * mid-sentence (a truncation). Neither should ever reach the workspace, and neither should be
 * scored as if it were the work. Both failures came from a real run: four files that held
 * nothing but their own tickets each scored 8.0.
 */
object Gate {
    data class Verdict(val ok: Boolean, val reasons: List<String>, val echo: Boolean = false, val truncated: Boolean = false)

    /** Phrases that only ever appear in instructions, never in deliverables (English and Korean). */
    private val MARKERS = listOf(
        "[ensemble:", "Output only the finished", "Output the complete", "Your ticket:", "Rubric (10 points each)",
        "A rival from another company grades", "The reviewer asked for", "If the team should know something",
        "[회사 프로젝트", "[티켓 ", "채점 기준(각 10점)", "파일에 들어갈 완성된 내용만", "고친 완성본 전체만 출력",
        "인사·설명·되묻기 금지", "다른 회사 AI가 아래 기준으로 채점", "이 파일의 현재 판",
    )

    /** First lines that are a chat UI's label for the *user's* bubble, captured by mistake. */
    private val USER_LABEL = Regex("^(당신|나|사용자|you|you said|user|me)\\s*[:：]?$", RegexOption.IGNORE_CASE)

    private val CODE = Regex("""\.(kt|kts|java|js|mjs|ts|tsx|jsx|css|json|c|cc|cpp|h|go|rs|swift|dart|gradle)$""")

    /** Did the reply give back (part of) the instruction instead of an answer? */
    fun echo(reply: String, prompt: String? = null): Boolean {
        val t = reply.trim()
        if (t.isEmpty()) return false
        if (USER_LABEL.matches(t.lineSequence().first().trim())) return true
        if (MARKERS.count { t.contains(it) } >= 2) return true
        if (prompt != null) {
            // Only the instruction head counts: prompts also carry material (teammates' results, the draft
            // to revise) that a good answer may legitimately reuse. An echo reproduces the head itself.
            val head = squash(prompt).drop(squash(TAG.find(prompt.take(60))?.value.orEmpty()).length).take(200)
            if (head.length >= 120) {
                val norm = squash(t)
                if (listOf(0, (head.length - 60) / 2, head.length - 60).count { i -> norm.contains(head.substring(i, i + 60)) } >= 2) return true
            }
        }
        return false
    }

    /** Why the text looks cut off, or null when it ends cleanly. */
    fun truncated(text: String, path: String = ""): String? {
        val t = text.trimEnd()
        if (t.isEmpty()) return null
        if (Regex("```").findAll(t).count() % 2 == 1) return "an unclosed code block"
        val code = CODE.containsMatchIn(path.lowercase())
        if (code) {
            val open = t.count { it == '{' } - t.count { it == '}' }
            if (open > 0) return "$open unclosed brace(s)"
            return null
        }
        val last = t.lineSequence().last().trim()
        if (last.startsWith("|") && (!last.endsWith("|") || last.count { it == '|' } < header(t))) return "a half-written table row"
        if (last.count { it == '“' } > last.count { it == '”' }) return "an open quotation"
        if (last.count { it == '(' } > last.count { it == ')' }) return "an open parenthesis"
        if (last.count { it == '"' } % 2 == 1 && !last.startsWith(">")) return "an open quotation"
        if (last.last() in ",:;(“‘-–—") return "a sentence that stops mid-way"
        return null
    }

    private fun header(t: String): Int =
        t.lines().lastOrNull { it.trim().startsWith("|") && it.contains("---") }?.let { sep -> t.lines().getOrNull(t.lines().indexOf(sep) - 1)?.count { it == '|' } } ?: 0

    /**
     * The full check for a file: echo, truncation, leftover protocol (an unparsed board block),
     * and a minimum of substance. [prompt] makes echo detection exact when known.
     */
    fun check(text: String, path: String, prompt: String? = null): Verdict {
        val reasons = ArrayList<String>()
        val isEcho = echo(text, prompt)
        if (isEcho) reasons += "The reply repeated the instruction instead of doing the work."
        val cut = truncated(text, path)
        if (cut != null) reasons += "The reply was cut off ($cut)."
        if (Regex("""(?m)^\s*[\[［]\s*(board|칠판)\s*[\]］]""", RegexOption.IGNORE_CASE).containsMatchIn(text)) reasons += "A team-board block was left in the file."
        val body = text.replace(Regex("\\s+"), "")
        if (body.length < 120) reasons += "There is almost no content (${body.length} characters)."
        return Verdict(reasons.isEmpty(), reasons, isEcho, cut != null)
    }

    /**
     * Join a continuation to the text it continues. Models often repeat the last words, or wrap
     * the rest in a new code fence; both are removed.
     */
    fun join(head: String, more: String, path: String = ""): String {
        var m = more.trim('\n')
        val code = CODE.containsMatchIn(path.lowercase())
        if (code || Regex("```").findAll(head).count() % 2 == 1) m = m.replace(Regex("^```[\\w+#.-]*\\n"), "").replace(Regex("\\n?```\\s*$"), "")
        val tail = head.takeLast(240)
        var cut = 0
        for (n in minOf(tail.length, m.length) downTo 12) if (tail.endsWith(m.take(n))) { cut = n; break }
        m = m.drop(cut)
        return head + m
    }

    private fun squash(s: String) = s.replace(Regex("\\s+"), "")
    private val TAG = Regex("""^\[ensemble:[a-z-]+]""")
}
