import com.ht.ensemble.AgentEvent
import com.ht.ensemble.Card
import com.ht.ensemble.CardStatus
import com.ht.ensemble.Ensemble
import com.ht.ensemble.LlmPort
import com.ht.ensemble.LlmReply
import com.ht.ensemble.Move
import com.ht.ensemble.Prompts
import com.ht.ensemble.Vendor
import com.ht.ensemble.sim.Simulated
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * Orchestration benchmark under virtual time.
 *
 * Every member is [Simulated]: replies take realistic time (scaled by each vendor's speed), and
 * some scenarios inject usage limits and outages. Because time is virtual, 25 runs of a job that
 * would take ~15 real minutes finish in seconds and are exactly reproducible.
 *
 * It measures the *orchestration* — wall time, how work spreads, recovery from failures — not
 * model quality. Writes docs/bench.md, docs/bench.svg and docs/hero.svg.
 *
 *     ./gradlew :samples:bench:run
 */
private const val RUNS = 25
private const val GOAL = "Compare the three strongest options for a budgeting app's onboarding and recommend one"

private class Run(val minutes: Double, val ok: Boolean, val work: Map<String, Int>, val swaps: Int, val checks: Int, val warnings: Int, val cards: List<Card>, val planEnd: Long)

private val FIVE = listOf(Vendor.CLAUDE, Vendor.GPT, Vendor.GEMINI, Vendor.GROK, Vendor.DEEPSEEK)

/** Web-chat-like reply times: 20–70 s before the vendor's speed factor. */
private val REAL = 20_000L..70_000L

private fun scenario(seed: Int, members: List<Vendor>, profile: (Vendor) -> Simulated.Profile = { Simulated.Profile(REAL, seed = seed) },
                     bias: Boolean = false): Run {
    var result: Run? = null
    runTest {
        val events = ArrayList<AgentEvent>()
        val team = Ensemble.team {
            members.forEach { v ->
                val sim = Simulated.port(v, profile(v))
                member(v, if (bias && v == Vendor.CLAUDE) biased(sim) else sim, lanes = if (v == Vendor.DEEPSEEK) 2 else 1)
            }
            clock = { testScheduler.currentTime }
            onEvent { events += it }
        }
        val r = team.conduct(GOAL, jobId = "B$seed")
        val st = team.stages.value.getValue("B$seed")
        val doers = st.cards.filter { it.status == CardStatus.DONE && it.who in members.map { v -> v.label } }
        val center = st.center
        result = Run(
            minutes = testScheduler.currentTime / 60_000.0,
            ok = r.ok && !r.partial,
            work = doers.filter { it.who != center || members.size < 3 }.groupingBy { it.who }.eachCount(),
            swaps = events.count { it is AgentEvent.Collab && it.move == Move.SWAP },
            checks = st.cards.count { it.peer.isNotBlank() },
            warnings = events.count { it is AgentEvent.Collab && it.move == Move.CHECK },
            cards = st.cards,
            planEnd = events.first { it is AgentEvent.Collab && it.move == Move.DISPATCH }.at,
        )
    }
    return result!!
}

/** The planner names Claude for every task; a market that obeyed would hand Claude 100% of the work. */
private fun biased(inner: LlmPort) = object : LlmPort {
    override val name = inner.name
    override suspend fun send(prompt: String, jobId: String): LlmReply {
        val r = inner.send(prompt, jobId)
        return if (Prompts.kindOf(prompt) == Prompts.PLAN) r.copy(text = r.text.replace(Regex("""\{"id""""), """{"who":"Claude","id"""")) else r
    }
}

private fun stats(xs: List<Double>): Pair<Double, Double> {
    val m = xs.average()
    return m to sqrt(xs.sumOf { (it - m) * (it - m) } / xs.size)
}

private fun f1(d: Double) = String.format(Locale.US, "%.1f", d)

fun main() {
    val rows = linkedMapOf(
        "Solo · Claude alone" to (1..RUNS).map { scenario(it, listOf(Vendor.CLAUDE)) },
        "Ensemble · 5 vendors" to (1..RUNS).map { scenario(it, FIVE) },
        "Ensemble · planner names Claude for all" to (1..RUNS).map { scenario(it, FIVE, bias = true) },
        "Ensemble · GPT limited, Grok fails 25%" to (1..RUNS).map { s ->
            scenario(s, FIVE, { v -> Simulated.Profile(REAL, seed = s, limitAfter = if (v == Vendor.GPT) 2 else Int.MAX_VALUE, failRate = if (v == Vendor.GROK) 0.25 else 0.0) })
        },
        "Solo · Claude hits its limit" to (1..RUNS).map { s ->
            scenario(s, listOf(Vendor.CLAUDE), { Simulated.Profile(REAL, seed = s, limitAfter = 1) })
        },
        "Ensemble · the lead hits its limit" to (1..RUNS).map { s ->
            scenario(s, FIVE, { v -> Simulated.Profile(REAL, seed = s, limitAfter = if (v == Vendor.CLAUDE) 1 else Int.MAX_VALUE) })
        },
    )

    val md = StringBuilder()
    md.appendLine("# Orchestration benchmark")
    md.appendLine()
    md.appendLine("Simulated members, virtual time, $RUNS seeded runs per scenario, `Difficulty.STANDARD`. Goal: *$GOAL*.")
    md.appendLine("This measures the orchestrator — wall time, spread of work, recovery — not model quality. Reproduce with `./gradlew :samples:bench:run`.")
    md.appendLine()
    md.appendLine("| Scenario | Wall time (min) | Fully completed | Workers used | Largest share | Hand-overs | Rival checks |")
    md.appendLine("|---|---|---|---|---|---|---|")
    val summary = LinkedHashMap<String, Double>()
    val completion = LinkedHashMap<String, Int>()
    for ((name, runs) in rows) {
        val done = runs.filter { it.ok }
        val (m, sd) = if (done.isEmpty()) 0.0 to 0.0 else stats(done.map { it.minutes })
        summary[name] = m
        completion[name] = done.size
        val workers = runs.map { it.work.size.toDouble() }.average()
        val share = runs.map { r -> r.work.values.maxOrNull()?.toDouble()?.div(r.work.values.sum().coerceAtLeast(1)) ?: 1.0 }.average()
        md.appendLine("| $name | ${if (done.isEmpty()) "—" else "${f1(m)} ± ${f1(sd)}"} | ${runs.count { it.ok }}/$RUNS | ${f1(workers)} | ${(share * 100).toInt()}% | ${f1(runs.map { it.swaps.toDouble() }.average())} | ${f1(runs.map { it.checks.toDouble() }.average())} |")
    }
    val solo = summary.values.first()
    val team = summary.values.elementAt(1)
    md.appendLine()
    md.appendLine("The five-vendor team finishes in **${f1(team)} min vs ${f1(solo)} min** for the strongest model alone (**${f1(solo / team)}× faster**) while adding rival cross-checks the solo run cannot have.")
    md.appendLine("With a planner that names one model for everything, the market still spreads the work. With members benched by usage limits or failing a quarter of their calls the team still completes — and when the lead itself runs out, another member takes the baton, where the solo run simply stops.")
    File("docs").mkdirs()
    File("docs/bench.md").writeText(md.toString())
    File("docs/bench.svg").writeText(Svg.bars(summary, completion))
    val soloLimit = completion.values.elementAt(4)
    val teamLimit = completion.values.elementAt(5)
    val checks = rows.values.elementAt(1).map { it.checks.toDouble() }.average()
    File("docs/hero.svg").writeText(Svg.hero(rows.values.first().first(), rows.values.elementAt(1).first(), listOf(
        "${f1(solo / team)}×" to "faster than the strongest model alone",
        "$teamLimit/$RUNS" to "survive the lead hitting its limit (solo: $soloLimit/$RUNS)",
        f1(checks) to "rival cross-checks per job (solo: 0)",
    )))
    println(md)
    println("wrote docs/bench.md, docs/bench.svg, docs/hero.svg")
}

/** Hand-written SVG: no chart library, dark ink, one brass accent for the center. */
private object Svg {
    private const val BG = "#0C0D10"
    private const val INK = "#E9E6DF"
    private const val DIM = "#6F6C66"
    private const val LINE = "#24262B"
    private const val BRASS = "#D4A24C"
    private const val FONT = "ui-monospace,SFMono-Regular,Menlo,Consolas,monospace"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun bars(rows: Map<String, Double>, done: Map<String, Int>): String {
        val w = 960
        val rowH = 46
        val left = 400
        val max = rows.values.max()
        val h = 70 + rows.size * rowH + 20
        val sb = StringBuilder("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h" font-family="$FONT"><rect width="$w" height="$h" rx="14" fill="$BG"/>""")
        sb.append("""<text x="28" y="40" fill="$DIM" font-size="12" letter-spacing="2">WALL TIME OF COMPLETED RUNS · MINUTES · $RUNS RUNS EACH</text>""")
        rows.entries.forEachIndexed { i, (name, v) ->
            val y = 64 + i * rowH
            val bw = ((w - left - 130) * v / max).toInt()
            val color = if (i == 0) DIM else if (i == 1) BRASS else INK
            sb.append("""<text x="28" y="${y + 20}" fill="$INK" font-size="13">${esc(name.take(50))}</text>""")
            sb.append("""<rect x="$left" y="${y + 8}" width="$bw" height="16" rx="3" fill="$color" opacity="${if (i >= 2) 0.55 else 1}"/>""")
            val ok = done[name] ?: 0
            val label = if (ok == 0) "failed in $RUNS/$RUNS runs" else "${f1(v)} min" + if (ok < RUNS) " · $ok/$RUNS done" else ""
            sb.append("""<text x="${left + bw + 10}" y="${y + 21}" fill="${if (ok == 0) BRASS else INK}" font-size="13">$label</text>""")
        }
        return sb.append("</svg>").toString()
    }

    /** Two real timelines, drawn as they unfold: the solo model, then the team. Loops every 16 s. */
    fun hero(solo: Run, team: Run, facts: List<Pair<String, String>>): String {
        val w = 1200
        val h = 640
        val left = 150
        val right = 40
        val span = maxOf(solo.minutes, team.minutes) * 60_000
        val px = (w - left - right) / span
        val total = 16.0
        val draw = 11.0
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h" font-family="$FONT">""")
        sb.append("""<rect width="$w" height="$h" rx="18" fill="$BG"/>""")
        sb.append("""<animate id="loop" attributeName="opacity" values="1;1" dur="${total}s" begin="0s;loop.end"/>""")
        sb.append("""<text x="40" y="58" fill="$INK" font-size="26" font-weight="700">ensemble</text>""")
        sb.append("""<text x="185" y="58" fill="$DIM" font-size="15">one goal · five vendors · a real scheduler run (simulated models)</text>""")

        fun lane(run: Run, top: Int, title: String, members: List<String>) {
            val rowH = 30
            sb.append("""<text x="40" y="$top" fill="$DIM" font-size="12" letter-spacing="2">${esc(title)}</text>""")
            members.forEachIndexed { i, m ->
                val y = top + 18 + i * rowH
                sb.append("""<text x="40" y="${y + 15}" fill="$INK" font-size="13">${esc(m)}</text>""")
                sb.append("""<line x1="$left" y1="${y + 26}" x2="${w - right}" y2="${y + 26}" stroke="$LINE"/>""")
            }
            fun bar(row: Int, start: Long, end: Long, accent: Boolean, label: String, short: String) {
                val x = left + start * px
                val bw = maxOf(3.0, (end - start) * px)
                val y = top + 18 + row * rowH + 4
                val b = draw * start / span
                val d = maxOf(0.15, draw * (end - start) / span)
                val id = "c${sb.length}"
                // Base attributes hold the finished picture, so viewers without SMIL still show the whole run.
                sb.append("""<g><rect x="${"%.1f".format(Locale.US, x)}" y="$y" width="${"%.1f".format(Locale.US, bw)}" height="18" rx="3" fill="${if (accent) BRASS else INK}" opacity="${if (accent) 1 else 0.86}">""")
                sb.append("""<set attributeName="width" to="0" begin="loop.begin"/>""")
                sb.append("""<animate id="$id" attributeName="width" from="0" to="${"%.1f".format(Locale.US, bw)}" begin="loop.begin+${"%.2f".format(Locale.US, b)}s" dur="${"%.2f".format(Locale.US, d)}s" fill="freeze"/></rect>""")
                val text = short.takeIf { it.length * 6.7 + 12 < bw }
                if (text != null) sb.append("""<text x="${"%.1f".format(Locale.US, x + 6)}" y="${y + 13}" fill="$BG" font-size="11"><set attributeName="opacity" to="0" begin="loop.begin"/><set attributeName="opacity" to="1" begin="$id.end"/>${esc(text)}</text>""")
                sb.append("</g>")
            }
            val idx = members.withIndex().associate { it.value to it.index }
            val t0 = 0L                                  // virtual time starts at zero with the job
            val center = members.first()
            bar(0, 0, run.planEnd - t0 + 1, true, "plan", "plan")
            for (c in run.cards) {
                if (c.startedAt == 0L || c.endedAt == 0L) continue
                val row = idx[c.who] ?: continue
                val accent = c.who == center && (c.id.startsWith("review") || c.id.startsWith("gap"))
                bar(row, c.startedAt - t0, c.endedAt - t0, accent, c.title.substringBefore(" · "), when {
                    c.id == "final" -> "compose"; c.id.startsWith("review") -> "review"; c.id.startsWith("gap") -> "gaps"; c.id.startsWith("x") -> "extra"; else -> c.id })
            }
            val endX = left + (run.minutes * 60_000 - t0) * px
            sb.append("""<text x="${"%.0f".format(Locale.US, minOf(endX + 8, w - right - 60.0))}" y="${top}" fill="${if (members.size > 1) BRASS else DIM}" font-size="13"><set attributeName="opacity" to="0" begin="loop.begin"/><set attributeName="opacity" to="1" begin="loop.begin+${draw}s"/>${f1(run.minutes)} min</text>""")
        }

        lane(solo, 112, "SOLO · THE STRONGEST MODEL DOES EVERYTHING", listOf("Claude"))
        lane(team, 214, "ENSEMBLE · CENTER PLANS & REVIEWS · MARKET DISPATCHES · RIVALS CHECK", listOf("Claude", "ChatGPT", "Gemini", "Grok", "DeepSeek"))

        val legendY = 450
        sb.append("""<line x1="40" y1="${legendY - 22}" x2="${w - 40}" y2="${legendY - 22}" stroke="$LINE"/>""")
        facts.forEachIndexed { i, (big, small) ->
            val x = 40 + i * 380
            sb.append("""<text x="$x" y="${legendY + 30}" fill="$BRASS" font-size="40" font-weight="700">${esc(big)}</text>""")
            sb.append("""<text x="$x" y="${legendY + 62}" fill="$INK" font-size="13">${esc(small)}</text>""")
        }
        sb.append("""<rect x="40" y="${h - 62}" width="12" height="12" rx="2" fill="$BRASS"/><text x="60" y="${h - 52}" fill="$DIM" font-size="12">center: plan · gap check · review</text>""")
        sb.append("""<rect x="330" y="${h - 62}" width="12" height="12" rx="2" fill="$INK"/><text x="350" y="${h - 52}" fill="$DIM" font-size="12">teammate work · compose</text>""")
        sb.append("""<text x="${w - 40}" y="${h - 52}" fill="$DIM" font-size="12" text-anchor="end">./gradlew :samples:bench:run</text>""")
        return sb.append("</svg>").toString()
    }
}
