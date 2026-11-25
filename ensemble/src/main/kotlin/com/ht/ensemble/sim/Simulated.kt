package com.ht.ensemble.sim

import com.ht.ensemble.LlmPort
import com.ht.ensemble.LlmReply
import com.ht.ensemble.Prompts
import com.ht.ensemble.Skill
import com.ht.ensemble.Vendor
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Simulated team members that speak the Ensemble protocol — no keys, no network.
 *
 * They answer every prompt kind ([Prompts]) with well-formed, plausible output, take time to do
 * it, and can be made to fail, slow down or hit a usage limit. Use them to try the SDK, to write
 * tests for your own integration, and to benchmark orchestration under virtual time
 * (`kotlinx-coroutines-test`): hours of simulated teamwork finish in milliseconds.
 *
 * ```
 * member(Vendor.CLAUDE, Simulated.port(Vendor.CLAUDE))
 * member(Vendor.GPT, Simulated.port(Vendor.GPT, Simulated.Profile(limitAfter = 3)))   // hits a usage limit
 * ```
 */
object Simulated {
    /**
     * @param latencyMs time per reply, scaled by the vendor's speed skill
     * @param failRate chance of an `HTTP503` with an empty reply
     * @param limitAfter after this many replies every call returns a usage-limit notice (`HTTP429`)
     * @param quality the grade this member's work earns on average (0..10); rework adds to it
     */
    data class Profile(
        val latencyMs: LongRange = 900L..2_600L,
        val failRate: Double = 0.0,
        val limitAfter: Int = Int.MAX_VALUE,
        val quality: Double = 8.2,
        val seed: Int = 7,
        /** Chance a reply is the prompt itself — what a web client returns when it reads the user's own bubble. */
        val echoRate: Double = 0.0,
        /** Chance a long reply stops mid-way, as streaming captures and output limits do. */
        val cutRate: Double = 0.0,
        /** Chance a grade comes back as prose nobody can parse. */
        val unreadableGradeRate: Double = 0.0,
    )

    fun port(vendor: Vendor, profile: Profile = Profile(), name: String = vendor.label): LlmPort = SimPort(vendor, profile, name)

    private class SimPort(private val vendor: Vendor, private val p: Profile, override val name: String) : LlmPort {
        private val rnd = Random(p.seed * 31 + vendor.id.hashCode())
        private val calls = AtomicInteger()

        override suspend fun send(prompt: String, jobId: String): LlmReply {
            val n = calls.incrementAndGet()
            val speed = vendor.score(Skill.SPEED).coerceIn(1, 10)
            val ms = (rnd.nextLong(p.latencyMs.first, p.latencyMs.last + 1) * (14 - speed) / 8.0).toLong()
            delay(ms)
            if (n > p.limitAfter) return LlmReply("You've reached your usage limit. Try again in 3 hours.", "HTTP429", ms)
            if (rnd.nextDouble() < p.failRate) return LlmReply("", "HTTP503:overloaded", ms)
            val kind = Prompts.kindOf(prompt)
            if (kind in WORK && rnd.nextDouble() < p.echoRate) return LlmReply("You\n" + prompt, "SIM", ms)
            if (kind in GRADES && rnd.nextDouble() < p.unreadableGradeRate) return LlmReply("Overall it reads well and covers the brief; a few sections could go deeper.", "SIM", ms)
            val text = answer(prompt)
            if (kind in WORK && text.length > 400 && rnd.nextDouble() < p.cutRate) return LlmReply(text.take(text.length * 2 / 3).trimEnd() + " and the remaining “", "SIM", ms)
            return LlmReply(text, "SIM", ms)
        }

        private fun goal(prompt: String) = Regex("""(?m)^(?:\[ensemble:[a-z-]+]\s*)?(?:Team goal|Goal):\s*(.+)$""").find(prompt)?.groupValues?.get(1)?.trim() ?: "the goal"

        private fun answer(prompt: String): String {
            val g = goal(prompt)
            return when (Prompts.kindOf(prompt)) {
                Prompts.PLAN, Prompts.REFORMAT -> plan(g)
                Prompts.TASK, Prompts.RESEARCH -> work(prompt, g)
                Prompts.CHECK -> if (rnd.nextDouble() < 0.18) "The growth figure in paragraph 2 has no source; the 2024 number contradicts the table." else "OK"
                Prompts.GAP -> if (prompt.contains("FAILED") || rnd.nextDouble() < 0.35)
                    """{"more":[{"title":"Answer open questions","prompt":"Resolve the open questions on the team board with sources."}]}""" else """{"more":[]}"""
                Prompts.COMPOSE -> "# ${g.replaceFirstChar { it.uppercase() }}\n\n" + paragraph(g, 4) + "\n\n## Recommendation\n\n" + paragraph(g, 2)
                Prompts.REVIEW -> if (rnd.nextDouble() < 0.4) """{"pass":false,"fixes":["Lead with the recommendation","Cite the source for every number"]}""" else """{"pass":true,"fixes":[]}"""
                Prompts.REVISE -> "# ${g.replaceFirstChar { it.uppercase() }} (revised)\n\n**Recommendation first.** " + paragraph(g, 4)
                Prompts.CHARTER -> charter(g)
                Prompts.TICKET -> ticket(prompt, g, rev = 0)
                Prompts.REWORK -> ticket(prompt, g, rev = 1 + rnd.nextInt(2))
                Prompts.GRADE, Prompts.FINAL_QA -> grade(prompt)
                Prompts.STANDUP -> standup(prompt)
                Prompts.CONTINUE -> "quoted figure is the conservative one.” The plan holds under that assumption.\n\n" + paragraph(g, 1)
                Prompts.REPORT -> "# Project report\n\n" + paragraph(g, 3) + "\n\n## Guide to the files\n\nEach file is listed below with what it holds and how to use it."
                else -> paragraph(g, 2)
            }
        }

        private fun plan(g: String) = """{"deliverable":"A decision-ready brief on $g","criteria":["Every number has a source","Recommendation first","Risks named"],
            "tasks":[
             {"id":"t1","title":"Survey the landscape","prompt":"Map the main options for: $g"},
             {"id":"t2","title":"Collect hard numbers","prompt":"Gather current figures with sources for: $g"},
             {"id":"t3","title":"Find counter-evidence","prompt":"Collect the strongest arguments against the obvious choice."},
             {"id":"t4","title":"Draft the core argument","prompt":"Argue for the best option using t1 and t2.","after":["t1","t2"]},
             {"id":"t5","title":"Write the action plan","prompt":"Turn the argument into a four-week plan.","after":["t3","t4"]}],
            "final":{"how":"recommendation, evidence, risks, plan"}}"""

        private fun work(prompt: String, g: String): String {
            val title = Regex("""(?m)^(?:Your task|Task):\s*(.+)$""").find(prompt)?.groupValues?.get(1) ?: "Task"
            val note = when (rnd.nextInt(4)) {
                0 -> "\n\n[board]\nfact: ${vendor.label} verified the latest figures for \"${title.take(30)}\" (source: official report)"
                1 -> "\n\n[board]\ndecision: frame the comparison by cost per outcome\nquestion: can someone confirm the 2026 pricing?"
                2 -> "\n\n[board]\nwarning: two sources disagree on the market size — use the conservative one"
                else -> ""
            }
            return "## $title\n\n" + paragraph(g, 3) + note
        }

        private fun charter(g: String) = """{"title":"${g.take(48).replace("\"", "'")}","summary":"A complete, sourced plan for $g.","audience":"the decision maker",
            "rubric":[{"name":"Evidence","desc":"every claim sourced"},{"name":"Depth","desc":"concrete numbers and cases"},{"name":"Clarity","desc":"skimmable in two minutes"},{"name":"Originality","desc":"an angle competitors miss"}],
            "files":[{"path":"01_research.md","purpose":"market and user evidence"},{"path":"02_strategy.md","purpose":"positioning and choices"},
                     {"path":"03_plan.md","purpose":"90-day plan"},{"path":"04_risks.md","purpose":"risks and mitigations"},{"path":"05_pitch.md","purpose":"one-page pitch"}],
            "milestones":["Evidence","Strategy","Plan","Polish"],
            "backlog":[{"id":"T1","title":"Market research","file":"01_research.md","brief":"Size, segments, competitors, with sources"},
                       {"id":"T2","title":"Risk register","file":"04_risks.md","brief":"Top risks with likelihood and mitigation"},
                       {"id":"T3","title":"Strategy","file":"02_strategy.md","brief":"Positioning from the research","inputs":["01_research.md"],"after":["T1"]}]}"""

        private fun ticket(prompt: String, g: String, rev: Int): String {
            val file = Regex("""(?m)^File:\s*(\S+)""").find(prompt)?.groupValues?.get(1) ?: "notes.md"
            val tag = if (rev > 0) " (rev $rev)" else ""
            val body = if (file.endsWith(".html") || file.endsWith(".js") || file.endsWith(".css"))
                "```\n<!-- $file$tag -->\n<main id=\"app\">\n  <h1>Ready</h1>\n  <p>Every control on this screen works offline and keeps its state.</p>\n</main>\n<script>\n  const app = document.getElementById('app');\n  app.dataset.state = localStorage.getItem('state') || 'new';\n</script>\n```"
            else "# ${file.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ')}$tag\n\n" + paragraph(g, 3 + rev)
            return body + if (rnd.nextInt(3) == 0) "\n\n[board]\nfact: ${vendor.label} found a benchmark worth reusing in $file" else ""
        }

        private fun grade(prompt: String): String {
            val rev = Regex("""\(rev (\d)\)""").findAll(prompt).map { it.groupValues[1].toInt() }.maxOrNull() ?: 0
            val base = (p.quality + rev * 0.7 + rnd.nextDouble(-0.9, 0.9)).coerceIn(4.0, 9.8)
            val names = Regex("""\{"scores":\{(.*?)}""").find(prompt)?.groupValues?.get(1)?.split(',')?.map { it.substringBefore(':').trim('"', ' ') }?.filter { it.isNotBlank() }
                ?: listOf("overall")
            val scores = names.joinToString(",") { "\"$it\":" + String.format(java.util.Locale.US, "%.1f", (base + rnd.nextDouble(-0.4, 0.4)).coerceIn(0.0, 10.0)) }
            val must = if (base < 8) """"Add sources to the figures in section 2","Make the recommendation concrete"""" else ""
            return """{"scores":{$scores},"must":[$must],"issues":["Tighten the opening"]}"""
        }

        private fun standup(prompt: String): String {
            val lines = prompt.substringAfter("[Files]").substringBefore("\n[").lines().filter { it.startsWith("- ") }
            val missing = lines.filter { it.contains(": missing") }.map { it.removePrefix("- ").substringBefore(' ') }
            val weak = lines.mapNotNull { l -> Regex("""chars · (\d+\.\d)""").find(l)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it < 9.0 }?.let { l.removePrefix("- ").substringBefore(' ') } }
            val todo = (missing.map { it to "Write" } + weak.map { it to "Deepen" }).take(3)
            val tickets = todo.mapIndexed { i, (f, verb) -> """{"id":"N${rnd.nextInt(1000)}$i","title":"$verb $f","file":"$f","brief":"$verb with concrete numbers and cases"}""" }
            return """{"summary":"${missing.size} missing, ${weak.size} below 9","done":${todo.isEmpty()},"decisions":["Prioritise evidence over breadth"],"tickets":[${tickets.joinToString(",")}]}"""
        }

        private fun paragraph(g: String, n: Int) = (1..n).joinToString("\n\n") {
            SENTENCES.shuffled(rnd).take(3).joinToString(" ") { s -> s.replace("{g}", g.take(60)) }
        }
    }

    private val WORK = setOf(Prompts.TICKET, Prompts.REWORK, Prompts.TASK)
    private val GRADES = setOf(Prompts.GRADE, Prompts.FINAL_QA)

    private val SENTENCES = listOf(
        "Here the decisive factor is cost per outcome rather than headline price.",
        "Three options dominate, and they differ mostly in how fast they reach the first result.",
        "Current figures put the leading option ahead by roughly a fifth on total cost (source: public filings).",
        "The obvious choice fails when demand is seasonal, which the data shows for two of the four segments.",
        "A four-week pilot with a single success metric de-risks the decision at a fraction of the full budget.",
        "Competitors cluster around the same message, leaving the practical, evidence-led angle open.",
        "Every claim below links to its source so the brief can be checked line by line.",
        "The main risk is execution capacity, not market demand; the plan staffs for it explicitly.",
    )
}
