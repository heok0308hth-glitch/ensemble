package com.ht.ensemble

import com.ht.ensemble.aegis.ApprovalGate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.Locale

/**
 * The company — a project of thirty minutes to several hours, run like a small studio.
 *
 *  **Kickoff.** The PM (center) writes a charter: deliverable, audience, a project-specific
 *  rubric, a file plan, milestones and the first sprint's tickets.
 *
 *  **Sprints.** One ticket = one file. The author writes, a *rival* grades it against the rubric
 *  like a code review, and anything below [bar] goes back with concrete fixes (up to twice).
 *  Only the best graded version lands in the workspace.
 *
 *  **Stand-ups.** The PM reads file status, scores, the board and the clock, then plans the next
 *  sprint: new files, rework of weak ones, depth. The team stops early only when every planned
 *  file exists, quality ≥ [excellent] and the PM agrees; otherwise it keeps polishing until the
 *  time budget, minus a reserve for wrap-up, runs out.
 *
 *  **Final QA.** Two rivals grade the whole project. Short of the bar with time left, one more
 *  polish sprint runs. Then a report (`00_REPORT.md`) with a factual work log.
 *
 *  Progress is checkpointed after every ticket, so a killed process resumes where it stopped.
 */
class Company(
    private val ctx: JobContext,
    crew: Crew,
    tools: ToolPort,
    human: HumanPort,
    gate: ApprovalGate,
    control: JobControl,
    private val emit: (AgentEvent) -> Unit,
    private val hub: StageHub = StageHub(),
    private val clock: () -> Long = System::currentTimeMillis,
    val minutes: Int = 60,
    val ledger: TokenLedger = TokenLedger(),
    private val files: ProjectFiles = MemoryFiles(),
    private val save: (String) -> Unit = {},
    private val load: () -> String? = { null },
    /** Called before each ticket — throttle here (thermal limits, quotas). */
    private val pace: suspend () -> Unit = {},
    /** Tickets in flight at once, 1..4; read before every sprint so it can change mid-project. */
    private val width: () -> Int = { 3 },
    /** Hand the finished project to the user (package, upload…). Return lines to show. */
    private val deliver: suspend (Delivery) -> List<String> = { emptyList() },
    /** For web-app projects: open `app/index.html` and return the errors, or null if it runs clean. */
    private val inspect: (suspend (ProjectFiles) -> String?)? = null,
) : Engine {
    data class Delivery(val title: String, val summary: String, val files: List<String>, val credit: String, val webApp: Boolean)
    data class Criterion(val name: String, val desc: String)
    data class FilePlan(val path: String, val purpose: String)
    data class Charter(val title: String, val summary: String, val audience: String, val rubric: List<Criterion>, val plan: List<FilePlan>, val milestones: List<String>)
    enum class TicketState { TODO, DOING, REVIEW, DONE, FAILED }

    class Ticket(val id: String, val title: String, val file: String, val brief: String, val inputs: List<String>, val after: List<String>, val sprint: Int) {
        @Volatile var state = TicketState.TODO
        @Volatile var who = ""
        @Volatile var score = 0.0
        @Volatile var attempts = 0
        @Volatile var issues: List<String> = emptyList()
        @Volatile var reviewer = ""
    }

    private val desk = CrewDesk(ctx.jobId, crew, hub, control, emit, clock, ledger)
    private var charter: Charter? = null
    private val tickets = Collections.synchronizedList(ArrayList<Ticket>())
    private val decisions = Collections.synchronizedList(ArrayList<String>())
    private val history = Collections.synchronizedList(ArrayList<Pair<Int, Double>>())
    private var sprint = 0
    private var startedAt = 0L
    private var elapsedBefore = 0L
    private var qa = 0.0
    private var qaIssues: List<String> = emptyList()
    private var inspectNote: String? = null
    private var restoredCenter: String? = null

    @Volatile override var progress: Double = 0.0
        private set

    private val budget get() = minutes * 60_000L
    private fun elapsed() = elapsedBefore + (clock() - startedAt)
    private fun left() = budget - elapsed()
    /** Time kept back for final QA and the report. */
    private val reserve get() = maxOf(6 * 60_000L, (budget * 0.15).toLong())
    private val bar = 8.0
    private val excellent = 9.0
    private val maxSprints = 14
    private val chars = 22_000
    private fun w() = width().coerceIn(1, 4)
    private fun isWebApp() = charter?.plan?.any { it.path == APP_ENTRY } == true || files.read(APP_ENTRY) != null

    override suspend fun run(): Outcome {
        startedAt = clock()
        desk.team = desk.crew.members().filter { desk.crew.available(it) }
        if (desk.team.isEmpty()) return Outcome(JobState.FAILED, "No team member is available.", 0)
        val restored = restore()
        val pm = desk.member(restoredCenter.orEmpty()) ?: desk.team.maxBy { desk.crew.rate(it, Skill.PLAN) * 0.6 + desk.crew.rate(it, Skill.REVIEW) * 0.4 }
        desk.center = pm
        hub.put(Stage(ctx.jobId, ctx.goal, pm.name, pm.vendor.label, if (restored) Phase.RESUMING else Phase.KICKOFF, startedAt = clock(), budgetMinutes = minutes))
        if (restored) { hub.edit(ctx.jobId) { it.copy(cards = tickets.map(::card)) }; status() }
        emit(AgentEvent.Started(clock(), ctx.goal, pm.name))
        emit(AgentEvent.Collab(clock(), Move.CENTER, pm.name,
            if (restored) "resuming at sprint $sprint · ${mins(left())} min left"
            else "PM for a ${minutes}-minute project · ${desk.team.size - 1} teammates · every file graded by a rival"))
        return try {
            desk.guarded { work() }
        } catch (e: CrewDesk.Aborted) {
            checkpoint()
            hub.edit(ctx.jobId) { st ->
                st.copy(phase = Phase.STOPPED, cards = st.cards.map { if (it.status in setOf(CardStatus.RUNNING, CardStatus.CHECKING, CardStatus.SWAPPED)) it.copy(status = CardStatus.WAITING, doing = "stopped · resumable") else it })
            }
            emit(AgentEvent.Done(clock(), JobState.ABORTED, "stopped"))
            Outcome(JobState.ABORTED, "Stopped at sprint $sprint; ${tickets.count { it.state == TicketState.DONE }} finished tickets are in the workspace. Run again with the same job id to resume.", tickets.size)
        }
    }

    private suspend fun work(): Outcome {
        val resumed = charter != null
        if (!resumed) kickoff()
        if (resumed && sprint > 0) tickets.filter { it.state == TicketState.TODO }.takeIf { it.isNotEmpty() }?.let { runSprint(it); retro() }
        // The first sprint always runs, however short the budget: no project ends with nothing.
        while (sprint < maxSprints && (sprint == 0 || left() > reserve)) {
            if (sprint == 0) {
                sprint = 1
                runSprint(tickets.filter { it.sprint == 1 && it.state != TicketState.DONE })
                retro()
                continue
            }
            val next = standup() ?: break
            sprint++
            launchSprint(next)
        }
        return finish()
    }

    private suspend fun launchSprint(next: List<Ticket>) {
        next.forEach { tickets += it }
        hub.addCards(ctx.jobId, next.map(::card))
        runSprint(next)
        retro()
    }

    // ───────────── kickoff ─────────────

    private suspend fun kickoff() {
        val pm = desk.center!!
        phase(Phase.KICKOFF, "${pm.name} is writing the charter")
        hub.edit(ctx.jobId) { it.copy(cards = listOf(Card("kickoff", "Project charter", pm.name, pm.vendor.label, CardStatus.RUNNING, "goal · rubric · file plan · sprint 1", clock()))) }
        val parsed = parseCharter(desk.askCenter(pm, kickoffPrompt(), takeOver = true))
            ?: parseCharter(desk.askCenter(desk.center!!, "${Prompts.REFORMAT}\nResend your last answer as one JSON object only. Keys: title, summary, audience, rubric[{name,desc}], files[{path,purpose}], milestones[], backlog[{id,title,file,brief,inputs[],after[]}]", takeOver = true))
        val (c, first) = parsed ?: playbook()
        charter = c
        val assigned = assign(first)
        tickets += assigned
        val lead = desk.center!!
        hub.edit(ctx.jobId) {
            it.copy(note = c.title, cards = listOf(Card("kickoff", "Project charter", lead.name, lead.vendor.label, CardStatus.DONE,
                "${c.plan.size} files · ${c.rubric.size} criteria · ${assigned.size} tickets", clock(), clock())) + assigned.map(::card))
        }
        emit(AgentEvent.Collab(clock(), Move.KICKOFF, lead.name, "“${c.title}” · ${c.plan.size} files · rubric: " + c.rubric.joinToString(", ") { it.name }))
        emit(AgentEvent.Collab(clock(), Move.ASSIGN, lead.name, "sprint 1: " + assigned.joinToString(" · ") { "${it.who} → ${it.title}" }))
        checkpoint()
    }

    private fun kickoffPrompt() = buildString {
        appendLine(Prompts.CHARTER)
        appendLine("You are the PM of this AI company. Start a ${minutes}-minute project for the goal below. The team works in sprints like a real studio, and a rival from another company grades every result.")
        appendLine("Goal: ${ctx.goal}")
        appendLine("Now: ${ctx.now}")
        ctx.client?.let { appendLine("Client: $it") }
        if (ctx.notes.isNotEmpty()) appendLine("Known about the client: " + ctx.notes.take(5).joinToString(" / "))
        ctx.guide?.let { appendLine("Guide: $it") }
        appendLine(); appendLine("[Team — honest strengths and weaknesses]")
        appendLine(Conductor.roster(desk.team.filter { it.key != desk.center?.key || desk.team.size < 3 }))
        appendLine()
        appendLine("Charter rules:")
        appendLine("- files: 3–8 files the project will really leave behind. Split a big document into chapter files (01_research.md, 02_strategy.md). Code uses real paths (src/…).")
        appendLine("- rubric: 4–5 criteria specific to this project — a name and what a 10 looks like. No generic platitudes.")
        appendLine("- If the goal is an app, game or tool: deliver an offline web app. The entry point must be $APP_ENTRY (plus app/style.css, app/main.js if needed). No CDNs or servers — everything inside the files; portrait mobile layout; persist with localStorage. Add the planning documents (.md) as well.")
        appendLine("- milestones: 3–4.")
        appendLine("- backlog: ${w()}–${w() * 2} tickets for sprint 1. One ticket = one file. Foundations first (research, structure, core design).")
        appendLine("  `inputs` lists files to read, `after` lists ticket ids that must finish first. The brief states length, angle and format so the author can start at once.")
        appendLine("- Spread work evenly. `who` may stay empty: the dispatch market assigns by strength and load.")
        appendLine("- Every file is written in ${ctx.language}; file paths stay ASCII.")
        appendLine("Reply with one JSON object only:")
        append("""{"title":"project name","summary":"one paragraph","audience":"who uses it","rubric":[{"name":"","desc":""}],"files":[{"path":"","purpose":""}],"milestones":[""],"backlog":[{"id":"T1","title":"","file":"","brief":"","inputs":[],"after":[]}]}""")
    }

    internal fun parseCharter(raw: String): Pair<Charter, List<Ticket>>? {
        val o = JsonPick.obj(raw) ?: return null
        val rubric = o.optJSONArray("rubric")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { r -> Criterion(r.optString("name").ifBlank { return@mapNotNull null }.take(24), r.optString("desc").take(160)) } }
        }.orEmpty()
        val plan = o.optJSONArray("files")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { f -> FilePlan(ProjectFiles.clean(f.optString("path")).ifBlank { return@mapNotNull null }, f.optString("purpose").take(160)) } }
        }.orEmpty()
        if (plan.isEmpty()) return null
        val c = Charter(o.optString("title").ifBlank { ctx.goal.take(48) }, o.optString("summary"), o.optString("audience"),
            rubric.ifEmpty { DEFAULT_RUBRIC }.take(5), plan.take(10),
            JsonPick.strings(o.optJSONArray("milestones")).ifEmpty { listOf("Foundations", "Core work", "Polish") }.take(4))
        val first = parseTickets(o.optJSONArray("backlog"), 1).ifEmpty {
            c.plan.take(w() * 2).mapIndexed { i, f -> Ticket("T${i + 1}", f.purpose.take(32).ifBlank { f.path }, f.path, f.purpose, emptyList(), emptyList(), 1) }
        }
        return c to first
    }

    private fun parseTickets(a: JSONArray?, sp: Int): List<Ticket> {
        a ?: return emptyList()
        val used = synchronized(tickets) { tickets.map { it.id } }.toMutableSet()
        val out = (0 until minOf(a.length(), w() * 2 + 1)).mapNotNull { i ->
            val t = a.optJSONObject(i) ?: return@mapNotNull null
            val file = ProjectFiles.clean(t.optString("file")).ifBlank { return@mapNotNull null }
            var id = t.optString("id").ifBlank { "S$sp-${i + 1}" }
            if (id in used) id = "S$sp-${i + 1}"
            used += id
            Ticket(id, t.optString("title").ifBlank { file }.take(48), file, t.optString("brief").ifBlank { t.optString("title") },
                JsonPick.strings(t.optJSONArray("inputs")).map(ProjectFiles::clean), JsonPick.strings(t.optJSONArray("after")), sp)
        }
        // Two tickets on one file in the same sprint would clobber each other: chain them.
        val ids = out.map { it.id }.toSet()
        return out.mapIndexed { i, t ->
            val prevSame = out.take(i).lastOrNull { it.file == t.file }?.id
            Ticket(t.id, t.title, t.file, t.brief, t.inputs, (t.after.filter { it in ids && it != t.id } + listOfNotNull(prevSame)).distinct(), sp)
        }
    }

    private fun playbook(): Pair<Charter, List<Ticket>> {
        val g = ctx.goal
        val code = Regex("code|app|program|script|website|html|game|bot|코드|앱|프로그램|스크립트|웹사이트|게임|봇", RegexOption.IGNORE_CASE).containsMatchIn(g)
        val plan = if (code) listOf(FilePlan("01_requirements.md", "features, screens, constraints"), FilePlan("02_design.md", "structure, data, flow"),
            FilePlan("src/main.py", "core implementation"), FilePlan("03_tests.md", "how it was verified"), FilePlan("README.md", "usage"))
        else listOf(FilePlan("01_research.md", "evidence with sources"), FilePlan("02_analysis.md", "key analysis and insight"),
            FilePlan("03_deliverable.md", "the finished piece"), FilePlan("04_review.md", "counter-arguments, risks, fixes"))
        val c = Charter(g.take(48), g, "the client", DEFAULT_RUBRIC, plan, listOf("Foundations", "Core work", "Polish"))
        val first = if (code) listOf(
            Ticket("T1", "Requirements", plan[0].path, "Turn the goal into features, screens and constraints: $g", emptyList(), emptyList(), 1),
            Ticket("T2", "Design", plan[1].path, "Design structure, data and flow from the requirements", listOf(plan[0].path), listOf("T1"), 1))
        else listOf(
            Ticket("T1", "Research", plan[0].path, "Search the web yourself for current evidence, with sources: $g", emptyList(), emptyList(), 1),
            Ticket("T2", "Counter-evidence", plan[3].path, "Opposing evidence, risks and common misconceptions on: $g", emptyList(), emptyList(), 1))
        emit(AgentEvent.Collab(clock(), Move.KICKOFF, desk.center!!.name, "charter unreadable — starting from a default plan"))
        return c to first
    }

    // ───────────── sprint ─────────────

    private fun assign(list: List<Ticket>): List<Ticket> {
        val who = Dispatch.assign(list, desk.team, desk.center, desk.crew::rate, { skillOf(it.title, it.brief + " " + it.file) }, { it.who })
        list.forEachIndexed { i, t -> who[i]?.let { t.who = it.name } }
        return list
    }

    private fun skillOf(title: String, rest: String): Skill = if (CODE_FILE.containsMatchIn(rest)) Skill.CODE else Skill.of("$title $rest")

    private suspend fun runSprint(all: List<Ticket>) {
        val list = all.distinctBy { it.id }
        if (list.isEmpty()) return
        phase(Phase.SPRINT, "sprint $sprint · ${list.size} tickets")
        val sem = Semaphore(w())
        coroutineScope {
            val jobs = HashMap<String, Deferred<Unit>>()
            for (t in list) jobs[t.id] = async(start = CoroutineStart.LAZY) {
                t.after.forEach { jobs[it]?.await() }
                sem.withPermit { desk.pauseGate(); pace(); ticket(t) }
            }
            jobs.values.forEach { it.start() }
            jobs.values.forEach { it.await() }
        }
    }

    private suspend fun ticket(t: Ticket) {
        val skill = skillOf(t.title, t.brief + " " + t.file)
        var author = desk.member(t.who)?.takeIf { desk.crew.available(it) } ?: desk.bestFree(skill, avoidCenter = true)
        t.state = TicketState.DOING
        t.who = author.name
        hub.card(ctx.jobId, t.id) { it.copy(who = author.name, vendor = author.vendor.label, status = CardStatus.RUNNING, doing = "${Conductor.verb(skill)} · ${t.file}", startedAt = clock()) }
        var best: String? = null
        var bestScore = -1.0
        var issues: List<String> = emptyList()
        val maxTry = if (left() < reserve * 2) 1 else 3
        for (attempt in 0 until maxTry) {
            val prompt = if (attempt == 0) writePrompt(t) else reworkPrompt(t, best.orEmpty(), issues)
            val raw = desk.lane(author).withPermit { desk.ask(author, skill, t.id, prompt, minLen = 80) }
            if (raw.isBlank()) break
            hub[ctx.jobId]?.cards?.firstOrNull { it.id == t.id }?.who?.let { n -> desk.member(n)?.let { author = it; t.who = it.name } }
            val content = shape(desk.absorb(raw, author.name, loud = false), t.file)
            t.state = TicketState.REVIEW
            val rv = reviewerFor(author)
            hub.card(ctx.jobId, t.id) { it.copy(status = CardStatus.CHECKING, doing = "${rv.name} is grading", peer = "") }
            val (score, iss) = grade(rv, t, content)
            t.reviewer = rv.name
            if (score > bestScore) { best = content; bestScore = score }
            val pass = score >= bar
            hub.card(ctx.jobId, t.id) { it.copy(peer = "${rv.name} ${fmt(score)} · " + if (pass) "pass" else "${iss.size} fixes") }
            if (pass || attempt == maxTry - 1) break
            issues = iss
            t.attempts++
            t.state = TicketState.DOING
            hub.card(ctx.jobId, t.id) { it.copy(status = CardStatus.RUNNING, doing = "reworking after review (${t.attempts})") }
        }
        val out = best
        if (out == null) {
            t.state = TicketState.FAILED
            hub.card(ctx.jobId, t.id) { it.copy(status = CardStatus.FAILED, doing = "no answer · reassigned at stand-up", endedAt = clock()) }
        } else {
            files.write(t.file, out)
            t.score = bestScore
            t.issues = issues
            t.state = TicketState.DONE
            hub.card(ctx.jobId, t.id) { it.copy(status = CardStatus.DONE, doing = "${t.file} · ${out.length} chars · ${fmt(bestScore)}", endedAt = clock()) }
            emit(AgentEvent.Note(clock(), "${t.who} · ${t.title} → ${t.file} (${fmt(bestScore)}, graded by ${t.reviewer})"))
        }
        status()
        checkpoint()
    }

    /** A rival grades: different vendor, not the PM when possible, the best idle critic. */
    private fun reviewerFor(author: CrewMember): CrewMember {
        val pool = desk.team.filter { it.vendor != author.vendor && desk.crew.available(it) }
        val nonCenter = pool.filter { it.key != desk.center?.key }.ifEmpty { pool }
        return nonCenter.maxByOrNull { Dispatch.bid(desk.crew.rate(it, Skill.REVIEW), desk.busyOf(it), it.lanes, false, desk.team.size, false) }
            ?: desk.center ?: author
    }

    /** (average score, fixes). An unreadable grade counts as the bar so the flow never stalls on format. */
    private suspend fun grade(rv: CrewMember, t: Ticket, content: String): Pair<Double, List<String>> {
        val r = desk.once(rv, gradePrompt(t, content))
        if (r.text.isBlank() || Vendor.limitHit(r.text, r.via)) return bar to emptyList()
        desk.thank(rv)
        return parseGrade(r.text) ?: (bar to emptyList())
    }

    // ───────────── stand-up & retro ─────────────

    private suspend fun standup(): List<Ticket>? {
        val pm = desk.center!!
        desk.pauseGate()
        phase(Phase.STANDUP, "${pm.name} is planning sprint ${sprint + 1}")
        val id = "standup${sprint + 1}"
        hub.addCards(ctx.jobId, listOf(Card(id, "Stand-up · plan sprint ${sprint + 1}", pm.name, pm.vendor.label, CardStatus.CHECKING, "reading status, scores, board", clock(), round = sprint + 1)))
        val o = JsonPick.obj(desk.askCenter(pm, standupPrompt(), takeOver = true))
        o?.optJSONArray("decisions")?.let { JsonPick.strings(it).take(3).forEach { d -> decisions += "S${sprint + 1} · $d" } }
        o?.optJSONArray("files")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.forEach { f ->
                val p = ProjectFiles.clean(f.optString("path"))
                if (p.isNotBlank() && charter!!.plan.none { it.path == p }) charter = charter!!.copy(plan = charter!!.plan + FilePlan(p, f.optString("purpose")))
            }
        }
        var next = parseTickets(o?.optJSONArray("tickets"), sprint + 1)
        val failed = tickets.filter { it.state == TicketState.FAILED && it.sprint == sprint }
        next = next + failed.map { f ->
            Ticket("${f.id}r", "${f.title} (retry)", f.file, f.brief, f.inputs, emptyList(), sprint + 1).also { r ->
                r.who = desk.team.filter { it.name != f.who && it.key != pm.key }.maxByOrNull { desk.crew.rate(it, skillOf(f.title, f.file)) }?.name.orEmpty()
            }
        }
        val done = o?.optBoolean("done", false) == true
        val q = quality()
        val summary = o?.optString("summary").orEmpty().ifBlank { "status check" }
        hub.card(ctx.jobId, id) { it.copy(status = CardStatus.DONE, doing = if (next.isEmpty()) "nothing to add" else "${next.size} tickets · ${summary.take(40)}", endedAt = clock()) }
        emit(AgentEvent.Collab(clock(), Move.STANDUP, pm.name, "$summary · ${mins(left())} min left · quality ${fmt(q)}"))
        val allFiles = charter!!.plan.all { files.read(it.path) != null }
        if (next.isEmpty() || (done && allFiles && q >= excellent)) return null
        // Partition before assigning: assign() fills `who`, so filtering afterwards would enqueue a ticket twice.
        val (open, preset) = next.partition { it.who.isBlank() }
        val assigned = (assign(open) + preset).distinctBy { it.id }
        emit(AgentEvent.Collab(clock(), Move.ASSIGN, pm.name, "sprint ${sprint + 1}: " + assigned.joinToString(" · ") { "${it.who} → ${it.title}" }))
        return assigned
    }

    private fun standupPrompt() = buildString {
        val c = charter!!
        appendLine("${Prompts.STANDUP} You are the PM. Project “${c.title}” — sprint $sprint just ended. About ${mins(left() - reserve)} minutes remain (excluding wrap-up).")
        appendLine("Goal: ${ctx.goal}")
        appendLine("Rubric: " + c.rubric.joinToString(" / ") { "${it.name} (${it.desc})" })
        appendLine(); appendLine("[Files]"); appendLine(fileStatus())
        if (qaIssues.isNotEmpty()) { appendLine("[Final QA findings — must be addressed]"); qaIssues.forEach { appendLine("- $it") } }
        desk.board.digest(1600).takeIf { it.isNotBlank() }?.let { appendLine("[Team board]"); appendLine(it) }
        if (decisions.isNotEmpty()) { appendLine("[Decisions so far]"); decisions.takeLast(6).forEach { appendLine("- $it") } }
        appendLine("[Teammates] " + desk.team.filter { it.key != desk.center?.key }.joinToString(", ") { "${it.name} (done ${desk.creditOf(it)})" })
        appendLine()
        appendLine("Plan ${w()}–${w() * 2} tickets for the next sprint. Priority: (1) planned files that do not exist yet (2) rework low-scoring files using the review findings (3) add depth — cases, numbers, counter-arguments, examples, tests.")
        appendLine("Rework tickets reuse the same `file`; the author writes a better complete version from the current one. Tickets that answer board questions are welcome.")
        appendLine("If time remains and quality is below ${fmt(excellent)}, set done=false and keep polishing. Set done=true only when every file is ≥ ${fmt(excellent)} and nothing worthwhile is left.")
        appendLine("Add to `files` only if a new file is truly needed. One JSON object only:")
        append("""{"summary":"one-line status","done":false,"decisions":["what you decided and why"],"files":[{"path":"","purpose":""}],"tickets":[{"id":"","title":"","file":"","brief":"","inputs":[],"after":[]}]}""")
    }

    private fun retro() {
        val mine = tickets.filter { it.sprint == sprint && it.state == TicketState.DONE }
        val avg = if (mine.isEmpty()) 0.0 else mine.map { it.score }.average()
        history += sprint to avg
        emit(AgentEvent.Collab(clock(), Move.RETRO, desk.center!!.name,
            "sprint $sprint · ${mine.size}/${tickets.count { it.sprint == sprint }} tickets · avg ${fmt(avg)} · ${mins(left())} min left"))
        checkpoint()
    }

    // ───────────── QA & report ─────────────

    private suspend fun finish(): Outcome {
        val c = charter ?: return Outcome(JobState.FAILED, "No charter could be written.", 0)
        if (files.list().isEmpty()) {
            hub.edit(ctx.jobId) { it.copy(phase = Phase.FAILED) }
            emit(AgentEvent.Done(clock(), JobState.FAILED, ""))
            return Outcome(JobState.FAILED, "The team produced no files. Check the connections and resume.", tickets.size)
        }
        inspectApp()
        var polished = false
        while (true) {
            phase(Phase.QA, "two rivals grade the whole project")
            finalQa()
            if (qa >= bar || polished || left() < reserve / 2 || sprint >= maxSprints) break
            polished = true
            val next = standup() ?: break
            sprint++
            launchSprint(next)
        }
        phase(Phase.REPORTING, "writing the project report")
        val writer = desk.bestFree(Skill.WRITE, avoidCenter = true)
        hub.addCards(ctx.jobId, listOf(Card("report", "Project report", writer.name, writer.vendor.label, CardStatus.RUNNING, "guide · decisions · quality", clock())))
        val body = desk.absorb(desk.ask(writer, Skill.WRITE, "report", reportPrompt(), minLen = 80), writer.name, loud = false)
        val report = body.ifBlank { "# ${c.title}\n\n${c.summary}" }.trim() + "\n\n" + workLog()
        files.write(REPORT, report)
        hub.card(ctx.jobId, "report") { it.copy(status = CardStatus.DONE, doing = "$REPORT · ${report.length} chars", endedAt = clock()) }

        phase(Phase.DELIVERING, "handing over the project")
        val order = (listOf(REPORT) + c.plan.map { it.path } + files.list().sorted()).distinct().filter { files.read(it) != null }
        val delivered = runCatching { deliver(Delivery(c.title, c.summary, order, "PM ${desk.center?.name} · worked: ${desk.credits()} · final QA ${fmt(qa)}/10", isWebApp())) }.getOrElse { emptyList() }
        if (delivered.isNotEmpty()) emit(AgentEvent.Collab(clock(), Move.DELIVER, "ensemble", delivered.joinToString(" · ")))

        progress = 1.0
        val final = buildString {
            appendLine("# ${c.title}")
            appendLine(); appendLine(c.summary.ifBlank { ctx.goal })
            appendLine(); appendLine("Files (${files.list().size})")
            files.list().sorted().forEach { p -> appendLine("- $p · ${files.read(p)?.length ?: 0} chars" + (lastScore(p)?.let { " · ${fmt(it)}" } ?: "")) }
            if (delivered.isNotEmpty()) { appendLine(); appendLine("Delivered"); delivered.forEach { appendLine("- $it") } }
            appendLine()
            append("---\nPM ${desk.center?.name} · sprints $sprint · tickets ${tickets.count { it.state == TicketState.DONE }}/${tickets.size} · final QA ${fmt(qa)}/10 · ${mins(elapsed())} min · worked: ${desk.credits()} · ~${Tokens.fmt(ledger.total())} tokens")
        }
        hub.edit(ctx.jobId) { it.copy(phase = Phase.DONE, progress = 1.0, quality = qa) }
        emit(AgentEvent.Done(clock(), JobState.COMPLETED, final))
        checkpoint()
        return Outcome(JobState.COMPLETED, final, tickets.size)
    }

    /** Web-app projects are actually opened; console errors become fix tickets (at most two rounds). */
    private suspend fun inspectApp() {
        val check = inspect ?: return
        if (!isWebApp()) return
        for (round in 0 until 2) {
            phase(Phase.INSPECTING, "opening the app to look for errors")
            val err = check(files)
            inspectNote = err
            hub.addCards(ctx.jobId, listOf(Card("inspect$round", "Smoke test", "ensemble", "", if (err == null) CardStatus.DONE else CardStatus.FAILED,
                err?.lineSequence()?.first()?.take(70) ?: "no errors · renders", clock(), clock())))
            emit(AgentEvent.Collab(clock(), Move.INSPECT, "ensemble", if (err == null) "the app opens without errors" else "${err.lines().size} errors → fix ticket"))
            if (err == null || left() < reserve / 3) break
            val target = Regex("""\(([\w.-]+\.(?:js|html|css)):\d+\)""").find(err)?.groupValues?.get(1)?.let { "app/$it" }?.takeIf { files.read(it) != null } ?: APP_ENTRY
            sprint++
            val fix = Ticket("FIX${round + 1}", "Fix runtime errors", target,
                "The app was opened and produced the errors below. Find the cause and output the complete fixed file without breaking other features.\n$err",
                files.list().filter { it.startsWith("app/") && it != target }, emptyList(), sprint)
            launchSprint(assign(listOf(fix)))
        }
    }

    private suspend fun finalQa() {
        val center = desk.center
        val picks = desk.team.filter { desk.crew.available(it) && it.key != center?.key }.sortedByDescending { desk.crew.rate(it, Skill.REVIEW) }
            .fold(ArrayList<CrewMember>()) { acc, m -> if (acc.none { it.vendor == m.vendor } && acc.size < 2) acc += m; acc }
            .ifEmpty { listOfNotNull(center) }
        val scores = ArrayList<Double>()
        val issues = ArrayList<String>()
        for ((i, rv) in picks.withIndex()) {
            val id = "qa$sprint-$i"
            hub.addCards(ctx.jobId, listOf(Card(id, "Final QA", rv.name, rv.vendor.label, CardStatus.CHECKING, "grading the whole project", clock())))
            val parsed = parseGrade(desk.once(rv, qaPrompt()).text)
            parsed?.let { scores += it.first; issues += it.second; desk.thank(rv) }
            hub.card(ctx.jobId, id) { it.copy(status = if (parsed == null) CardStatus.FAILED else CardStatus.DONE, doing = parsed?.let { p -> "${fmt(p.first)} · ${p.second.size} findings" } ?: "unreadable grade", endedAt = clock()) }
        }
        qa = if (scores.isEmpty()) quality() else scores.average()
        qaIssues = issues.distinct().take(8)
        hub.edit(ctx.jobId) { it.copy(quality = qa) }
        emit(AgentEvent.Collab(clock(), Move.QA, picks.joinToString(" + ") { it.name }, "final QA ${fmt(qa)}/10" + if (qaIssues.isNotEmpty()) " · " + qaIssues.take(3).joinToString(" / ") else ""))
    }

    // ───────────── prompts ─────────────

    private fun charterBrief() = charter!!.let { c ->
        "Project “${c.title}”: ${c.summary}\nAudience: ${c.audience}\nRubric (10 points each): " + c.rubric.joinToString(" / ") { "${it.name} — ${it.desc}" } +
            "\nFile plan: " + c.plan.joinToString(", ") { "${it.path} (${it.purpose})" }
    }

    private fun writePrompt(t: Ticket) = buildString {
        appendLine("${Prompts.TICKET} Ticket ${t.id}, assigned to you by PM ${desk.center?.name}. A rival from another company grades the result against the rubric below; under ${fmt(bar)} it comes back.")
        appendLine(charterBrief())
        appendLine(); appendLine("Your ticket: ${t.title}"); appendLine("File: ${t.file}"); appendLine(t.brief)
        val cur = files.read(t.file)
        if (cur != null) { appendLine(); appendLine("Current version of this file — write a better complete version from it: keep what is good, fill the gaps, fix the weak parts:"); appendLine(cur.take(chars / 2)) }
        inputs(t, if (cur != null) chars / 3 else chars * 2 / 3).takeIf { it.isNotBlank() }?.let { appendLine(); appendLine("Reference files:"); appendLine(it) }
        desk.board.digest(1400).takeIf { it.isNotBlank() }?.let { appendLine(); appendLine("[Team board]"); appendLine(it) }
        appendLine()
        if (t.file.startsWith("app/")) appendLine("This file is part of an offline web app: no external CDNs, fonts or image URLs (draw with SVG/CSS), portrait mobile layout, touch input, localStorage for state. Ids and function names must match the other app/ files. Zero console errors.")
        appendLine(if (isCode(t.file)) "Output the complete file only — one code block, nothing omitted, comments only where needed."
            else "Output only the finished content of the file (markdown, starting with the title). No greeting, no explanation. Concrete facts, numbers, examples and sources instead of generalities. ${ctx.language.directive()}")
        append(Conductor.BOARD_ASK)
    }

    private fun reworkPrompt(t: Ticket, draft: String, issues: List<String>) = buildString {
        appendLine("${Prompts.REWORK} Ticket ${t.id} came back from review below the bar. Rewrite the complete file with every finding addressed.")
        appendLine(charterBrief())
        appendLine("File: ${t.file} · ticket: ${t.title}")
        appendLine("Review findings:"); issues.ifEmpty { listOf("Not deep or concrete enough for the rubric") }.forEach { appendLine("- $it") }
        appendLine("Previous version:"); appendLine(draft.take(chars))
        append(if (isCode(t.file)) "Output the complete fixed file only (one code block)." else "Output the complete revised content only. ${ctx.language.directive()}")
    }

    private fun gradePrompt(t: Ticket, content: String) = buildString {
        appendLine("${Prompts.GRADE} You are this project's reviewer. Be strict but fair: 9–10 only if it could go to the client unchanged.")
        appendLine(charterBrief())
        appendLine("Ticket: ${t.title} · file: ${t.file} · brief: ${t.brief}")
        appendLine(); appendLine("Submission:"); appendLine(content.take(chars))
        appendLine()
        append("""One JSON object only: {"scores":{""" + charter!!.rubric.joinToString(",") { "\"${it.name}\":0" } + """},"must":["what must change — where and how"],"issues":["what would make it better"]}""")
    }

    private fun qaPrompt() = buildString {
        appendLine("${Prompts.FINAL_QA} This goes to the client next. Grade the whole project, including contradictions between files, missing requirements and duplication.")
        if (isWebApp() && inspect != null) appendLine("Smoke test: " + (inspectNote?.let { "errors — $it" } ?: "opens without errors"))
        appendLine("Goal: ${ctx.goal}"); appendLine(charterBrief())
        val ps = files.list().sorted()
        val each = (chars + 6000) / ps.size.coerceAtLeast(1)
        ps.forEach { p -> appendLine(); appendLine("=== $p ==="); appendLine(files.read(p).orEmpty().take(each)) }
        appendLine()
        append("""One JSON object only: {"scores":{""" + charter!!.rubric.joinToString(",") { "\"${it.name}\":0" } + """},"must":["what must be fixed before delivery, with the file name"],"issues":[]}""")
    }

    private fun reportPrompt() = buildString {
        appendLine("${Prompts.REPORT} Write $REPORT, the first document the client reads.")
        appendLine("Goal: ${ctx.goal}"); appendLine(charterBrief())
        appendLine("[Files]"); appendLine(fileStatus())
        appendLine("[Opening of each file]")
        files.list().sorted().filter { it != REPORT }.forEach { p -> appendLine("--- $p"); appendLine(files.read(p).orEmpty().take(900)) }
        if (decisions.isNotEmpty()) { appendLine("[Decisions]"); decisions.forEach { appendLine("- $it") } }
        desk.board.digest(1200).takeIf { it.isNotBlank() }?.let { appendLine("[Team board]"); appendLine(it) }
        appendLine("[Final QA] ${fmt(qa)}/10" + if (qaIssues.isNotEmpty()) " · open findings: " + qaIssues.joinToString(" / ") else "")
        append("Structure: one-paragraph summary → guide to the files (what each holds and how to use it) → key decisions and why → quality by criterion → open issues and next steps. Facts only, no hype. Start with the title. ${ctx.language.directive()}")
    }

    private fun inputs(t: Ticket, budget: Int): String {
        val paths = (t.inputs + synchronized(tickets) { tickets.filter { it.id in t.after }.map { it.file } }).distinct().filter { it != t.file }
        if (paths.isEmpty()) return ""
        val each = budget / paths.size
        return paths.mapNotNull { p -> files.read(p)?.let { "=== $p ===\n" + it.take(each) } }.joinToString("\n\n")
    }

    private fun fileStatus() = charter!!.plan.joinToString("\n") { f ->
        val txt = files.read(f.path)
        val last = lastDone(f.path)
        "- ${f.path} (${f.purpose}): " + if (txt == null) "missing" else "${txt.length} chars · " +
            (last?.let { "${fmt(it.score)} (${it.who}, graded by ${it.reviewer})" + if (it.issues.isNotEmpty()) " · findings: " + it.issues.take(2).joinToString(" / ") else "" } ?: "not graded")
    }

    private fun workLog() = buildString {
        appendLine("---"); appendLine("## Work log (recorded by Ensemble)")
        appendLine("| | |"); appendLine("|---|---|")
        appendLine("| PM | ${desk.center?.name} |")
        appendLine("| Time | ${mins(elapsed())} of ${minutes} min |")
        appendLine("| Sprints | $sprint |")
        appendLine("| Tickets | ${tickets.count { it.state == TicketState.DONE }} done · ${tickets.sumOf { it.attempts }} reworks · ${tickets.count { it.state == TicketState.FAILED }} failed |")
        appendLine("| Final QA | ${fmt(qa)} / 10 |")
        appendLine("| Tokens (est.) | ${Tokens.fmt(ledger.total())} |")
        appendLine(); appendLine("| Member | Finished | Failed (why) |"); appendLine("|---|---|---|")
        desk.roll().forEach { (n, done, why) -> appendLine("| $n${if (n == desk.center?.name) " (PM)" else ""} | $done | ${why.ifBlank { "—" }} |") }
        if (history.isNotEmpty()) { appendLine(); appendLine("Average score by sprint: " + history.joinToString(" → ") { "S${it.first} ${fmt(it.second)}" }) }
        appendLine(); appendLine("| Ticket | File | Author | Reviewer | Score |"); appendLine("|---|---|---|---|---|")
        tickets.filter { it.state == TicketState.DONE }.forEach { appendLine("| ${it.id} ${it.title.replace("|", "/")} | ${it.file} | ${it.who} | ${it.reviewer} | ${fmt(it.score)} |") }
    }

    // ───────────── state ─────────────

    private fun quality(): Double = charter?.plan?.mapNotNull { lastScore(it.path) }.orEmpty().let { if (it.isEmpty()) 0.0 else it.average() }
    private fun lastDone(path: String) = synchronized(tickets) { tickets.lastOrNull { it.file == path && it.state == TicketState.DONE } }
    private fun lastScore(path: String) = lastDone(path)?.score

    private fun status() {
        progress = (0.03 + 0.92 * elapsed().toDouble() / budget).coerceIn(progress, 0.96)
        val fl = files.list().sorted().map { p -> FileState(p, files.read(p)?.length ?: 0, lastScore(p) ?: 0.0) }
        hub.edit(ctx.jobId) { it.copy(progress = progress, sprint = sprint, deadline = clock() + left(), quality = quality(), files = fl, budgetMinutes = minutes) }
    }

    private fun phase(p: Phase, next: String) {
        status()
        hub.edit(ctx.jobId) { it.copy(phase = p) }
        emit(AgentEvent.Progress(clock(), p, progress, next))
    }

    private fun card(t: Ticket) = Card(t.id, t.title, t.who.ifBlank { "unassigned" }, desk.member(t.who)?.vendor?.label.orEmpty(),
        when (t.state) { TicketState.DONE -> CardStatus.DONE; TicketState.FAILED -> CardStatus.FAILED; else -> CardStatus.WAITING },
        when { t.state == TicketState.DONE -> "${t.file} · ${fmt(t.score)}"; t.after.isEmpty() -> "starting · ${t.file}"; else -> "waiting for ${t.after.joinToString()} · ${t.file}" },
        after = t.after, round = t.sprint)

    // Tickets that finish together must not save over each other with a stale snapshot.
    private val saveLock = Any()
    private fun checkpoint() = synchronized(saveLock) { runCatching { save(snapshot().toString()) } }

    fun snapshot(): JSONObject = JSONObject().apply {
        put("v", 1); put("minutes", minutes); put("sprint", sprint); put("elapsed", elapsed()); put("qa", qa)
        put("center", desk.center?.name ?: "")
        charter?.let { c ->
            put("charter", JSONObject().put("title", c.title).put("summary", c.summary).put("audience", c.audience)
                .put("rubric", JSONArray(c.rubric.map { JSONObject().put("name", it.name).put("desc", it.desc) }))
                .put("files", JSONArray(c.plan.map { JSONObject().put("path", it.path).put("purpose", it.purpose) }))
                .put("milestones", JSONArray(c.milestones)))
        }
        put("tickets", JSONArray(synchronized(tickets) {
            tickets.map { t ->
                JSONObject().put("id", t.id).put("title", t.title).put("file", t.file).put("brief", t.brief)
                    .put("inputs", JSONArray(t.inputs)).put("after", JSONArray(t.after)).put("sprint", t.sprint).put("state", t.state.name).put("who", t.who)
                    .put("score", t.score).put("attempts", t.attempts).put("reviewer", t.reviewer).put("issues", JSONArray(t.issues))
            }
        }))
        put("decisions", JSONArray(decisions.toList()))
        put("history", JSONArray(history.map { JSONArray().put(it.first).put(it.second) }))
        put("board", JSONArray(desk.board.all().map { JSONObject().put("who", it.who).put("kind", it.kind.name).put("text", it.text) }))
    }

    private fun restore(): Boolean {
        val o = runCatching { load()?.let { JSONObject(it) } }.getOrNull() ?: return false
        val c = o.optJSONObject("charter") ?: return false
        charter = Charter(c.optString("title"), c.optString("summary"), c.optString("audience"),
            c.optJSONArray("rubric")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { r -> Criterion(r.optString("name"), r.optString("desc")) } } }.orEmpty().ifEmpty { DEFAULT_RUBRIC },
            c.optJSONArray("files")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { f -> FilePlan(f.optString("path"), f.optString("purpose")) } } }.orEmpty(),
            JsonPick.strings(c.optJSONArray("milestones")))
        sprint = o.optInt("sprint")
        elapsedBefore = o.optLong("elapsed")
        qa = o.optDouble("qa", 0.0)
        restoredCenter = o.optString("center").ifBlank { null }
        o.optJSONArray("tickets")?.let { a ->
            for (i in 0 until a.length()) {
                val t = a.getJSONObject(i)
                tickets += Ticket(t.optString("id"), t.optString("title"), t.optString("file"), t.optString("brief"),
                    JsonPick.strings(t.optJSONArray("inputs")), JsonPick.strings(t.optJSONArray("after")), t.optInt("sprint")).also { k ->
                    val st = runCatching { TicketState.valueOf(t.optString("state")) }.getOrDefault(TicketState.TODO)
                    k.state = if (st == TicketState.DOING || st == TicketState.REVIEW) TicketState.TODO else st   // half-done tickets restart
                    k.who = t.optString("who"); k.score = t.optDouble("score", 0.0); k.attempts = t.optInt("attempts")
                    k.reviewer = t.optString("reviewer"); k.issues = JsonPick.strings(t.optJSONArray("issues"))
                }
            }
        }
        JsonPick.strings(o.optJSONArray("decisions")).forEach { decisions += it }
        o.optJSONArray("history")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { p -> history += p.optInt(0) to p.optDouble(1) } }
        o.optJSONArray("board")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { n ->
                desk.board.add(n.optString("who"), runCatching { NoteKind.valueOf(n.optString("kind")) }.getOrDefault(NoteKind.FACT), n.optString("text"))
            }
        }
        return true
    }

    // ───────────── helpers ─────────────

    private fun shape(text: String, path: String): String {
        if (!isCode(path)) return text.trim()
        val blocks = Regex("""```[\w+#.-]*[^\n]*\n([\s\S]*?)```""").findAll(text).map { it.groupValues[1] }.toList()
        return (blocks.maxByOrNull { it.length } ?: text).trimEnd() + "\n"
    }

    private fun isCode(p: String) = CODE_FILE.containsMatchIn(p)
    private fun mins(ms: Long) = (ms.coerceAtLeast(0) / 60_000).toInt()

    companion object {
        const val REPORT = "00_REPORT.md"
        const val APP_ENTRY = "app/index.html"
        private val CODE_FILE = Regex("""\.(kt|java|py|js|ts|tsx|jsx|html|css|swift|dart|go|rs|c|cpp|h|sh|json|yml|yaml|xml|gradle|toml|sql)\b""")
        private val DEFAULT_RUBRIC = listOf(
            Criterion("Accuracy", "Facts and code are correct and backed by sources"),
            Criterion("Completeness", "Every requirement covered, usable as is"),
            Criterion("Depth", "Concrete cases, numbers and reasons — no platitudes"),
            Criterion("Originality", "A perspective or solution beyond the obvious"),
            Criterion("Clarity", "Clear structure, easy to read"))

        internal fun fmt(d: Double): String = String.format(Locale.US, "%.1f", d)

        /** Parse a grade: average of `scores`, or `score`, or "7.5/10" in prose; fixes from `must` + `issues`. */
        fun parseGrade(text: String): Pair<Double, List<String>>? {
            val o = JsonPick.obj(text)
            if (o != null) {
                val sc = o.optJSONObject("scores")
                val nums = sc?.keys()?.asSequence()?.mapNotNull { k -> sc.optDouble(k).takeIf { !it.isNaN() } }?.toList().orEmpty()
                val avg = if (nums.isNotEmpty()) nums.average() else o.optDouble("score").takeIf { !it.isNaN() } ?: return null
                return avg.coerceIn(0.0, 10.0) to (JsonPick.strings(o.optJSONArray("must")) + JsonPick.strings(o.optJSONArray("issues"))).distinct().take(6)
            }
            return Regex("""(\d+(?:\.\d)?)\s*/\s*10""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.let { it.coerceIn(0.0, 10.0) to emptyList() }
        }
    }
}
