package com.ht.ensemble

import com.ht.ensemble.aegis.ApprovalGate
import com.ht.ensemble.tools.CommandCall
import com.ht.ensemble.tools.CommandResult
import com.ht.ensemble.tools.CommandSpec
import com.ht.ensemble.tools.Toolbox
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

/**
 * Ensemble — make several LLMs work as one team.
 *
 * ```kotlin
 * val team = Ensemble.team {
 *     member(Vendor.CLAUDE, Ports.anthropic(System.getenv("ANTHROPIC_API_KEY")))
 *     member(Vendor.GPT, Ports.openAi(System.getenv("OPENAI_API_KEY")))
 *     member(Vendor.GEMINI, Ports.gemini(System.getenv("GEMINI_API_KEY")))
 *     member(Vendor.DEEPSEEK, Ports.deepSeek(System.getenv("DEEPSEEK_API_KEY")), lanes = 2)
 *     onEvent { println(it.line()) }
 * }
 *
 * val brief = team.conduct("Compare the three biggest design competitions closing in December")   // minutes
 * val study = team.project("A go-to-market plan for a specialty coffee subscription", minutes = 90,
 *                          dir = File("out"))                                                     // hours
 * ```
 *
 * No Android dependency: the same code runs on servers, desktops and phones.
 */
class Ensemble private constructor(
    private val members: List<CrewMember>,
    private val ports: Map<String, List<LlmPort>>,
    private val tools: ToolPort,
    private val catalog: String,
    private val approver: suspend (ApprovalRequest) -> Boolean,
    private val asker: suspend (String, List<String>) -> String?,
    private val events: (AgentEvent) -> Unit,
    private val language: Language,
    private val client: String?,
    private val notes: List<String>,
    private val guide: String?,
    val score: Scoreboard,
    private val clock: () -> Long,
) {
    /** The outcome of a job: state, final text, the workspace, tokens spent. */
    data class Result(val state: JobState, val text: String, val files: ProjectFiles, val tokens: Int, val jobId: String, val partial: Boolean = false) {
        val ok: Boolean get() = state == JobState.COMPLETED
    }

    private val gate = ApprovalGate()
    private val hub = StageHub()

    /** Live stage of every job this team runs — collect it to draw a UI. */
    val stages: StateFlow<Map<String, Stage>> get() = hub.stages

    /** The team, as the engines see it. */
    val crew: Crew = object : Crew {
        override fun members() = members
        override fun port(m: CrewMember, lane: Int): LlmPort = ports.getValue(m.key).let { it[lane % it.size] }
        override fun report(m: CrewMember, ok: Boolean, ms: Long, text: String, via: String) = score.record(m.key, ok, ms, text, via)
        override fun available(m: CrewMember) = !score.resting(m.key)
        override fun rate(m: CrewMember, s: Skill) = score.adjust(m.vendor.score(s).toDouble(), m.key)
    }

    /**
     * A job of a few minutes: the center plans, the market dispatches, everyone works at once with
     * a shared board and rival cross-checks, then a writer composes and the center reviews.
     */
    suspend fun conduct(goal: String, difficulty: Difficulty = Difficulty.STANDARD, files: ProjectFiles = MemoryFiles(),
                        control: JobControl = JobControl(), jobId: String = newId()): Result {
        val ledger = TokenLedger()
        val out = Conductor(context(jobId, goal), crew, tools, human(), gate, control, events, hub, clock, difficulty, ledger, catalog, files).run()
        return Result(out.state, out.text, files, ledger.total(), jobId, out.partial)
    }

    /**
     * A project of 30 minutes to hours: charter → sprints (write → rival grades → rework) →
     * stand-ups → final QA → report. With a [dir], files land there and progress is checkpointed
     * to `.company.json`; calling again with the same [dir] resumes.
     */
    suspend fun project(goal: String, minutes: Int = 60, dir: File? = null, control: JobControl = JobControl(), jobId: String = newId(),
                        width: () -> Int = { 3 }, pace: suspend () -> Unit = {},
                        deliver: suspend (Company.Delivery) -> List<String> = { emptyList() },
                        inspect: (suspend (ProjectFiles) -> String?)? = null): Result {
        val files: ProjectFiles = dir?.let { DirFiles(it) } ?: MemoryFiles()
        val state = dir?.let { File(it, CHECKPOINT) }
        val ledger = TokenLedger()
        val out = Company(context(jobId, goal), crew, tools, human(), gate, control, events, hub, clock, minutes, ledger, files,
            save = { s -> state?.writeText(s) }, load = { state?.takeIf { it.exists() }?.readText() },
            pace = pace, width = width, deliver = deliver, inspect = inspect).run()
        return Result(out.state, out.text, files, ledger.total(), jobId)
    }

    private fun context(jobId: String, goal: String) =
        JobContext(jobId, goal, SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(clock())), Language.resolve(language, goal), client, notes, guide)

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(11).uppercase()

    private fun human() = object : HumanPort {
        override suspend fun approve(req: ApprovalRequest): ApprovalAnswer =
            if (approver(req)) ApprovalAnswer.Approved(gate.issue(req.digest, clock())) else ApprovalAnswer.Rejected("not approved")
        override suspend fun ask(jobId: String, question: String, choices: List<String>) = asker(question, choices)
    }

    class Builder internal constructor() {
        private val members = ArrayList<CrewMember>()
        private val ports = LinkedHashMap<String, List<LlmPort>>()
        private var tools: ToolPort = Toolbox()
        private var catalog = ""
        private var approver: suspend (ApprovalRequest) -> Boolean = { false }
        private var asker: suspend (String, List<String>) -> String? = { _, c -> c.firstOrNull() }
        private var events: (AgentEvent) -> Unit = {}
        private var client: String? = null
        private var notes: List<String> = emptyList()
        private var guide: String? = null

        /** Output language. [Language.AUTO] (default) follows the language of the goal. */
        var language: Language = Language.AUTO
        /** Live performance tracking; share one between teams to carry what it learned. */
        var scoreboard: Scoreboard = Scoreboard()
        var clock: () -> Long = System::currentTimeMillis

        /**
         * Add a member. With [lanes] > 1 the member takes that many requests at once, rotating over
         * [port] and any [extra] ports (e.g. several API keys).
         */
        fun member(vendor: Vendor, port: LlmPort, name: String = defaultName(vendor), lanes: Int = 1, vararg extra: LlmPort) {
            val key = "${vendor.id}:${members.size}"
            members += CrewMember(key, name, vendor, lanes.coerceAtLeast(1))
            ports[key] = listOf(port) + extra
        }

        private fun defaultName(v: Vendor): String {
            val same = members.count { it.vendor == v }
            return if (same == 0) v.label else "${v.label} ${same + 1}"
        }

        /** Tools the planner may use, and their one-line catalog. Without tools, models research on their own. */
        fun tools(port: ToolPort, catalog: String = (port as? Toolbox)?.catalog().orEmpty()) { tools = port; this.catalog = catalog }

        /** Decide approval for anything with an outside effect. Default: reject everything. */
        fun approve(block: suspend (ApprovalRequest) -> Boolean) { approver = block }
        fun ask(block: suspend (question: String, choices: List<String>) -> String?) { asker = block }
        fun onEvent(block: (AgentEvent) -> Unit) { events = block }

        /** Who the work is for, a few facts about them, and a standing guide (house style, constraints). */
        fun client(name: String? = null, notes: List<String> = emptyList(), guide: String? = null) { client = name; this.notes = notes; this.guide = guide }

        internal fun build(): Ensemble {
            require(members.isNotEmpty()) { "Add at least one member: member(Vendor.X, port)" }
            return Ensemble(members.toList(), ports.toMap(), tools, catalog, approver, asker, events, language, client, notes, guide, scoreboard, clock)
        }
    }

    companion object {
        const val CHECKPOINT = ".company.json"
        fun team(block: Builder.() -> Unit): Ensemble = Builder().apply(block).build()
    }
}

/** A tool port with nothing in it — the planner's catalog is empty and models research themselves. */
object NoTools : ToolPort {
    override fun spec(id: String): CommandSpec? = null
    override suspend fun run(call: CommandCall): CommandResult = CommandResult.fail("no tools")
}
