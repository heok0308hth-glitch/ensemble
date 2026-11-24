import com.ht.ensemble.AgentEvent
import com.ht.ensemble.CardStatus
import com.ht.ensemble.Difficulty
import com.ht.ensemble.Ensemble
import com.ht.ensemble.Language
import com.ht.ensemble.Phase
import com.ht.ensemble.Stage
import com.ht.ensemble.Vendor
import com.ht.ensemble.ports.Ports
import com.ht.ensemble.sim.Simulated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Run a team from the terminal.
 *
 *     ./gradlew :samples:cli:run --args="--demo"                       # simulated team, no keys
 *     ./gradlew :samples:cli:run --args="Compare Postgres and SQLite for a mobile app"
 *     ./gradlew :samples:cli:run --args="--project 60 A launch plan for a pottery studio"
 *
 * Members join when their key is set: ANTHROPIC_API_KEY, OPENAI_API_KEY, GEMINI_API_KEY,
 * DEEPSEEK_API_KEY, XAI_API_KEY, OPENROUTER_API_KEY (+ OPENROUTER_MODEL), OLLAMA_MODEL.
 * Flags: --demo  --project <min>  --quick | --deep  --lang <name>  --out <dir>  --plain
 */
fun main(args: Array<String>) = runBlocking<Unit> {
    val flags = Flags(args)
    val log = ConcurrentLinkedDeque<String>()
    val live = !flags.plain && System.console() != null
    val env = System.getenv()

    val team = runCatching {
        Ensemble.team {
            if (flags.demo) {
                val fast = Simulated.Profile(latencyMs = 500L..1_800L)
                listOf(Vendor.CLAUDE, Vendor.GPT, Vendor.GEMINI, Vendor.GROK).forEach { member(it, Simulated.port(it, fast)) }
                member(Vendor.DEEPSEEK, Simulated.port(Vendor.DEEPSEEK, fast), lanes = 2)
            }
            env["ANTHROPIC_API_KEY"]?.let { member(Vendor.CLAUDE, Ports.anthropic(it, env["ANTHROPIC_MODEL"] ?: "claude-sonnet-4-5")) }
            env["OPENAI_API_KEY"]?.let { member(Vendor.GPT, Ports.openAi(it, env["OPENAI_MODEL"] ?: "gpt-5")) }
            env["GEMINI_API_KEY"]?.let { member(Vendor.GEMINI, Ports.gemini(it, env["GEMINI_MODEL"] ?: "gemini-2.5-pro")) }
            env["DEEPSEEK_API_KEY"]?.let { member(Vendor.DEEPSEEK, Ports.deepSeek(it), lanes = 2) }
            env["XAI_API_KEY"]?.let { member(Vendor.GROK, Ports.grok(it)) }
            env["OPENROUTER_API_KEY"]?.let { k -> env["OPENROUTER_MODEL"]?.let { m -> member(Vendor.custom(m.substringBefore('/'), m.substringAfter('/')), Ports.openRouter(k, m)) } }
            env["OLLAMA_MODEL"]?.let { member(Vendor.QWEN.takeIf { _ -> it.startsWith("qwen") } ?: Vendor.LLAMA, Ports.ollama(it), name = "Ollama · $it") }
            flags.lang?.let { language = Language.named(it) }
            onEvent { e ->
                if (live) { if (e !is AgentEvent.Progress) { log.addLast(e.line()); while (log.size > 5) log.pollFirst() } }
                else if (e !is AgentEvent.Progress) println(e.line())
            }
        }
    }.getOrElse {
        System.err.println("No team members. Set an API key (ANTHROPIC_API_KEY, OPENAI_API_KEY, …) or pass --demo.")
        return@runBlocking
    }

    val view = if (live) launch(Dispatchers.Default) { Screen().loop { team.stages.value.values.firstOrNull() to log.toList() } } else null
    val out = flags.out?.let(::File)
    val result = if (flags.minutes != null) team.project(flags.goal, flags.minutes, dir = out ?: File("ensemble-out"))
                 else team.conduct(flags.goal, flags.difficulty)
    view?.cancel()
    if (live) Screen.restore()

    println()
    println(result.text.lines().take(60).joinToString("\n"))
    if (out != null && flags.minutes == null) result.files.list().forEach { p -> File(out, p).apply { parentFile.mkdirs(); writeText(result.files.read(p)!!) } }
    (out ?: if (flags.minutes != null) File("ensemble-out") else null)?.let { println("\nfiles → ${it.absolutePath}") }
}

private class Flags(args: Array<String>) {
    private val list = args.toList()
    private fun value(name: String) = list.indexOf(name).takeIf { it >= 0 }?.let { list.getOrNull(it + 1) }
    val demo = "--demo" in list
    val plain = "--plain" in list
    val minutes = list.indexOf("--project").takeIf { it >= 0 }?.let { value("--project")?.toIntOrNull() ?: 30 }
    val difficulty = when { "--quick" in list -> Difficulty.QUICK; "--deep" in list -> Difficulty.DEEP; else -> Difficulty.STANDARD }
    val lang = value("--lang")
    val out = value("--out")
    val goal: String = list.filterIndexed { i, a -> !a.startsWith("--") && list.getOrNull(i - 1) !in setOf("--project", "--lang", "--out") }
        .joinToString(" ").ifBlank { "Compare the three strongest options for a budgeting app's onboarding and recommend one" }
}

/** A small live view: who is doing what, the board, the last moves. Plain ANSI, one brass accent. */
private class Screen {
    private var drawn = 0

    suspend fun loop(read: () -> Pair<Stage?, List<String>>) {
        print(HIDE)
        val started = System.currentTimeMillis()
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val (st, log) = read()
            if (st != null) draw(render(st, log, (System.currentTimeMillis() - started) / 1000))
            delay(120)
        }
    }

    private fun draw(lines: List<String>) {
        val sb = StringBuilder()
        if (drawn > 0) sb.append("\u001b[${drawn}F")
        lines.forEach { sb.append(CLEAR).append(it).append('\n') }
        repeat((drawn - lines.size).coerceAtLeast(0)) { sb.append(CLEAR).append('\n') }
        if (drawn > lines.size) sb.append("\u001b[${drawn - lines.size}F")
        print(sb)
        System.out.flush()
        drawn = lines.size
    }

    private fun render(st: Stage, log: List<String>, secs: Long): List<String> {
        val width = 78
        val pct = (st.progress * 100).toInt()
        val bar = "█".repeat(pct / 5) + "░".repeat(20 - pct / 5)
        val out = ArrayList<String>()
        out += " ${BOLD}ensemble$RESET  ${DIM}${st.goal.take(44)}$RESET"
        out += " ${BRASS}${st.phase.name.padEnd(12)}$RESET $bar ${pct.toString().padStart(3)}%  ${DIM}${secs}s$RESET"
        out += DIM + " " + "─".repeat(width) + RESET
        val members = (listOf(st.center) + st.cards.map { it.who }).filter { it.isNotBlank() && it != "unassigned" && it != "tool" && it != "ensemble" }.distinct()
        for (m in members) {
            val mine = st.cards.filter { it.who == m && it.status in setOf(CardStatus.RUNNING, CardStatus.CHECKING, CardStatus.SWAPPED) }
            val done = st.cards.count { it.who == m && it.status == CardStatus.DONE }
            val (glyph, what) = when {
                m == st.center && mine.isEmpty() -> "$BRASS◆$RESET" to (if (st.phase == Phase.RUNNING) "${DIM}conducting · watching the board$RESET" else st.phase.name.lowercase())
                mine.isNotEmpty() -> "$BRASS●$RESET" to mine.joinToString(" · ") { "${it.id} ${it.doing.take(30)}" }
                else -> "$DIM○$RESET" to "${DIM}idle$RESET"
            }
            val role = if (m == st.center) "${BRASS}center$RESET" else "      "
            out += " $glyph ${m.take(12).padEnd(12)} $role  ${what.take(46)}  ${DIM}✓$done$RESET"
        }
        out += DIM + " " + "─".repeat(width) + RESET
        val board = st.board.takeLast(3)
        if (board.isEmpty()) out += " ${DIM}board  (empty)$RESET"
        board.forEachIndexed { i, n -> out += " ${if (i == 0) "${DIM}board$RESET " else "      "} ${n.take(width - 8)}" }
        out += DIM + " " + "─".repeat(width) + RESET
        log.forEach { out += " ${DIM}${it.take(width)}$RESET" }
        return out
    }

    companion object {
        const val RESET = "\u001b[0m"
        const val BOLD = "\u001b[1m"
        const val DIM = "\u001b[2m"
        const val BRASS = "\u001b[38;5;179m"
        const val CLEAR = "\u001b[2K"
        const val HIDE = "\u001b[?25l"
        fun restore() { print("\u001b[?25h"); System.out.flush() }
    }
}
