package com.ht.ensemble

import com.ht.ensemble.aegis.ApprovalGate
import com.ht.ensemble.tools.CommandCall
import com.ht.ensemble.tools.CommandExec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Common surface of the two engines. */
interface Engine {
    val progress: Double
    suspend fun run(): Outcome
}

/**
 * The conductor — a job of a few minutes, done by the whole team at once.
 *
 *  1. **Plan.** The center splits the goal into a deliverable, acceptance criteria and tasks.
 *     With three or more members it takes no task itself.
 *  2. **Dispatch.** Every task goes to the market ([Dispatch]); able members spread the load.
 *  3. **Run.** Tasks start the moment their inputs exist. Members leave `[board]` notes that
 *     everyone who starts later reads. An idle *rival* (different vendor) cross-checks each
 *     result in the background; problems land on the board as warnings.
 *  4. **Reinforce.** The center reads results and board, retries failures with someone else and
 *     adds what is missing.
 *  5. **Compose & review.** A strong writer (not the center) merges; the center reviews against
 *     the criteria, and on [Difficulty.DEEP] a rival reviews once more.
 *
 * Pause stops new requests; abort cancels the ones in flight.
 */
class Conductor(
    private val ctx: JobContext,
    private val crew: Crew,
    private val tools: ToolPort,
    human: HumanPort,
    gate: ApprovalGate,
    control: JobControl,
    private val emit: (AgentEvent) -> Unit,
    private val hub: StageHub = StageHub(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val difficulty: Difficulty = Difficulty.STANDARD,
    val ledger: TokenLedger = TokenLedger(),
    private val catalog: String = "",
    private val files: ProjectFiles = MemoryFiles(),
) : Engine {
    data class Task(val id: String, val title: String, val who: String, val tool: String = "", val args: Map<String, String> = emptyMap(),
                    val prompt: String = "", val after: List<String> = emptyList(), val round: Int = 0)
    data class Plan(val deliverable: String, val criteria: List<String>, val tasks: List<Task>, val finalWho: String, val finalHow: String)

    private val desk = CrewDesk(ctx.jobId, crew, hub, control, emit, clock, ledger)
    private val exec = CommandExec(ctx.jobId, tools, human, gate, emit, clock)
    private val outputs = Collections.synchronizedMap(LinkedHashMap<String, String>())
    private val checks = ConcurrentHashMap<String, String>()
    private val finished = AtomicInteger()
    private var totalTasks = 1
    private val team get() = desk.team
    val board: Board get() = desk.board

    @Volatile override var progress: Double = 0.0
        private set

    override suspend fun run(): Outcome {
        desk.team = crew.members().filter { crew.available(it) }
        if (team.isEmpty()) return Outcome(JobState.FAILED, "No team member is available.", 0)
        val c = team.maxBy { crew.rate(it, Skill.PLAN) * 0.6 + crew.rate(it, Skill.REVIEW) * 0.4 }
        desk.center = c
        hub.put(Stage(ctx.jobId, ctx.goal, c.name, c.vendor.label, Phase.PLANNING, startedAt = clock()))
        emit(AgentEvent.Started(clock(), ctx.goal, c.name))
        emit(AgentEvent.Collab(clock(), Move.CENTER, c.name, "plans and reviews; ${team.size - 1} teammates do the work"))
        return try {
            desk.guarded { conduct() }
        } catch (e: CrewDesk.Aborted) {
            hub.edit(ctx.jobId) { st ->
                st.copy(phase = Phase.STOPPED, cards = st.cards.map { if (it.status != CardStatus.DONE && it.status != CardStatus.FAILED) it.copy(status = CardStatus.FAILED, doing = "stopped") else it })
            }
            emit(AgentEvent.Done(clock(), JobState.ABORTED, "stopped"))
            Outcome(JobState.ABORTED, "Stopped.", outputs.size)
        }
    }

    private suspend fun conduct(): Outcome = coroutineScope { conductIn(this) }

    private suspend fun conductIn(scope: CoroutineScope): Outcome {
        val c0 = desk.center!!
        phase(Phase.PLANNING, 0.05, "${c0.name} is splitting the goal")
        val raw = design()
        val c = desk.center!!
        val p = raw.copy(tasks = dispatch(raw.tasks))
        totalTasks = p.tasks.size + 2
        hub.edit(ctx.jobId) { it.copy(phase = Phase.RUNNING, cards = p.tasks.map(::card), note = p.deliverable) }
        emit(AgentEvent.Collab(clock(), Move.DISPATCH, c.name, p.tasks.groupBy { it.who.ifBlank { "tool" } }.entries.joinToString(" · ") { (w, l) -> "$w ${l.size}" }))

        runAll(p.tasks, scope)

        var all = p.tasks
        for (round in 1..difficulty.extraRounds) {
            desk.pauseGate()
            val more = dispatch(reinforce(p, all, round))
            if (more.isEmpty()) break
            totalTasks += more.size
            hub.edit(ctx.jobId) { it.copy(phase = Phase.REINFORCING, cards = it.cards + more.map(::card)) }
            emit(AgentEvent.Collab(clock(), Move.REINFORCE, c.name, more.joinToString(" · ") { "${it.who}: ${it.title}" }))
            runAll(more, scope)
            all = all + more
        }

        // The last cross-checks overlap the gap analysis; the composer must see their warnings.
        pendingChecks.toList().forEach { it.join() }
        desk.pauseGate()
        phase(Phase.INTEGRATING, 0.84, "merging the results")
        val writer = desk.bestFree(Skill.WRITE, avoidCenter = true, hint = p.finalWho)
        hub.addCards(ctx.jobId, listOf(Card("final", "Compose · ${p.deliverable.take(28)}", writer.name, writer.vendor.label, CardStatus.RUNNING, "merging results and board", clock())))
        var draft = desk.absorb(desk.ask(writer, Skill.WRITE, "final", composePrompt(p)), writer.name)
        hub.card(ctx.jobId, "final") { it.copy(status = if (draft.isBlank()) CardStatus.FAILED else CardStatus.DONE, doing = "${draft.length} chars", endedAt = clock()) }

        if (draft.isNotBlank()) draft = review(p, draft, writer)
        val composed = draft.isNotBlank()
        if (!composed) draft = synchronized(outputs) { outputs.values.filter { it.isNotBlank() }.joinToString("\n\n") }
        val partial = !composed || synchronized(outputs) { all.any { outputs[it.id].isNullOrBlank() && all.none { r -> r.id.startsWith(it.id + "r") && !outputs[r.id].isNullOrBlank() } } }
        if (draft.isBlank()) {
            hub.edit(ctx.jobId) { it.copy(phase = Phase.FAILED) }
            emit(AgentEvent.Done(clock(), JobState.FAILED, ""))
            return Outcome(JobState.FAILED, "Every teammate failed to answer. Check the connections and try again.", outputs.size)
        }

        files.write(RESULT, "# ${p.deliverable}\n\n$draft\n")
        CodeFiles.extract(draft).forEach { (path, code) -> files.write(path, code) }
        val footer = (if (partial) "---\nPartial: some work never came back; this was assembled from what did.\n" else "") + "---\ncenter ${c.name} · worked: ${desk.credits()} · board notes ${board.all().size} · ${difficulty.name.lowercase()} · ~${Tokens.fmt(ledger.total())} tokens"
        val final = draft.trim() + "\n\n" + footer
        progress = 1.0
        hub.edit(ctx.jobId) { it.copy(phase = Phase.DONE, progress = 1.0) }
        emit(AgentEvent.Done(clock(), JobState.COMPLETED, final))
        return Outcome(JobState.COMPLETED, final, outputs.size, partial)
    }

    private fun dispatch(tasks: List<Task>): List<Task> {
        val who = Dispatch.assign(tasks, team, desk.center, crew::rate, { Skill.of(it.title + " " + it.prompt) }, { it.who }, { it.tool.isNotBlank() })
        return tasks.mapIndexed { i, t -> who[i]?.let { t.copy(who = it.name) } ?: t }
    }

    // ───────────── plan ─────────────

    private suspend fun design(): Plan {
        val c = desk.center!!
        val roster = roster(team.filter { it.key != c.key || team.size < 3 })
        parsePlan(desk.askCenter(c, planPrompt(roster), takeOver = true))?.let { return it }
        emit(AgentEvent.Note(clock(), "the plan was not valid JSON — asking once more"))
        parsePlan(desk.askCenter(desk.center!!, "${Prompts.REFORMAT}\nResend your last answer as one JSON object only, no prose. Keys: deliverable, criteria[], tasks[{id,title,who,tool,args,prompt,after[]}], final{who,how}", takeOver = true))?.let { return it }
        emit(AgentEvent.Collab(clock(), Move.CENTER, desk.center!!.name, "plan unreadable — falling back to a playbook for this kind of goal"))
        return playbook()
    }

    private fun planPrompt(roster: String) = buildString {
        appendLine(Prompts.PLAN)
        appendLine("You are the center (conductor) of an AI team. Split the goal below among your teammates and own the quality to the end. You only plan and review; you take no task yourself.")
        appendLine("Goal: ${ctx.goal}")
        context()
        appendLine()
        appendLine("[Team — name · honest strengths and weaknesses]")
        appendLine(roster)
        if (catalog.isNotBlank()) { appendLine(); appendLine("[Tools — only when a model cannot do it itself]"); appendLine(catalog) }
        appendLine()
        appendLine("Rules:")
        appendLine("- At most ${difficulty.maxTasks} tasks. Leave `after` empty when a task needs nobody else's output, so tasks run in parallel.")
        appendLine("- Spread the work across the team; nobody gets more than two tasks. `who` is a name from the team list.")
        appendLine("- Match strengths: fresh facts → research, long text → writing, code → code, counter-arguments → critique.")
        appendLine("- If two members cover one topic, give them different angles (local vs. global, for vs. against) so results do not overlap.")
        appendLine("- Make each `prompt` concrete enough to start at once: length, format, angle. List ids in `after` when an earlier result is needed.")
        appendLine("- For a tool, put its name in `tool` and its arguments in `args`. Search queries are real keywords, not the goal sentence.")
        appendLine("- Writing and creative goals need no web search.")
        appendLine("- `final.who` merges the results (a strong writer, not you); `final.how` says how.")
        appendLine("- The deliverable will be written in ${ctx.language}.")
        appendLine("Reply with one JSON object only:")
        append("""{"deliverable":"one line","criteria":["acceptance criterion"],"tasks":[{"id":"t1","title":"short title","who":"teammate","tool":"","args":{},"prompt":"concrete instruction","after":[]}],"final":{"who":"teammate","how":"how to merge"}}""")
    }

    private fun StringBuilder.context() {
        appendLine("Now: ${ctx.now}")
        ctx.client?.let { appendLine("Client: $it") }
        if (ctx.notes.isNotEmpty()) appendLine("Known about the client: " + ctx.notes.take(5).joinToString(" / "))
        ctx.guide?.let { appendLine("Guide: $it") }
    }

    fun parsePlan(raw: String): Plan? {
        val o = JsonPick.obj(raw) ?: return null
        val arr = o.optJSONArray("tasks") ?: o.optJSONArray("steps") ?: return null
        val tasks = (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optString("title").ifBlank { t.optString("name") }.ifBlank { return@mapNotNull null }
            val tool = t.optString("tool").trim().takeIf { it.isNotBlank() && tools.spec(it) != null }.orEmpty()
            Task(t.optString("id").ifBlank { "t${i + 1}" }, title.take(48), t.optString("who"), tool, JsonPick.map(t.optJSONObject("args")),
                t.optString("prompt").ifBlank { title }, JsonPick.strings(t.optJSONArray("after")))
        }.take(difficulty.maxTasks).distinctBy { it.id }
        if (tasks.isEmpty()) return null
        val ids = tasks.map { it.id }.toSet()
        val fin = o.optJSONObject("final")
        return Plan(o.optString("deliverable").ifBlank { ctx.goal.take(60) },
            JsonPick.strings(o.optJSONArray("criteria")).ifEmpty { listOf("Covers every part of the goal", "Ready to use as is") },
            tasks.map { t -> t.copy(after = t.after.filter { it in ids && it != t.id }) },
            fin?.optString("who").orEmpty(), fin?.optString("how").orEmpty())
    }

    /** When the plan cannot be read: a fixed pipeline per kind of goal. The market still picks who. */
    private fun playbook(): Plan {
        val g = ctx.goal
        return when {
            CODE_GOAL.containsMatchIn(g) -> Plan("Working code with usage notes", listOf("Runs", "Explained"), listOf(
                Task("t1", "Design the structure", "", prompt = "Design the structure, files and core logic for: $g"),
                Task("t2", "List edge cases", "", prompt = "List the edge cases, failure modes and security issues that are easy to miss for: $g"),
                Task("t3", "Implement", "", prompt = "Write the complete code following the design, handling the edge cases. No omissions.", after = listOf("t1", "t2")),
                Task("t4", "Review the code", "", prompt = "Find bugs and missing handling; return the corrected code.", after = listOf("t3"))), "", "the reviewed version")
            RESEARCH_GOAL.containsMatchIn(g) -> Plan(g.take(60), listOf("Every fact has a source", "Current information", "Organised for use"), listOf(
                Task("t1", "Research the latest facts", "", prompt = "Search the web yourself and gather the latest facts needed for this goal, each with a source link: $g"),
                Task("t2", "Research another angle", "", prompt = "Research the same topic from other sources and an international angle, with links: $g"),
                Task("t3", "Find counter-evidence", "", prompt = "Collect common misconceptions, pitfalls and counter-evidence on: $g"),
                Task("t4", "Analyse and recommend", "", prompt = "Compare and verify the findings; state the key conclusions and a recommendation.", after = listOf("t1", "t2", "t3"))), "", "conclusion first, evidence and sources after")
            else -> Plan(g.take(60), listOf("Nothing missing", "Original and concrete", "Finished prose"), listOf(
                Task("t1", "Explore concepts", "", prompt = "Propose three genuinely different concepts for this goal and a detailed outline for the best one: $g"),
                Task("t2", "Arguments and evidence", "", prompt = "Collect the key points, evidence and counter-arguments this goal needs: $g"),
                Task("t3", "Write the body", "", prompt = "Write the finished piece from the concept and the arguments.", after = listOf("t1", "t2"))), "", "polish the body")
        }
    }

    // ───────────── run ─────────────

    /** Cross-checks still running in the background; joined right before composing. */
    private val pendingChecks = Collections.synchronizedList(ArrayList<Job>())

    private suspend fun runAll(tasks: List<Task>, scope: CoroutineScope) = coroutineScope {
        val jobs = HashMap<String, Deferred<Unit>>()
        for (t in tasks) {
            jobs[t.id] = async(start = CoroutineStart.LAZY) {
                t.after.forEach { jobs[it]?.await() }
                desk.pauseGate()
                val m = desk.member(t.who) ?: desk.bestFree(Skill.of(t.title + " " + t.prompt), avoidCenter = true)
                val out = if (t.tool.isNotBlank()) toolTask(t) else desk.lane(m).withPermit { aiTask(t, m) }
                outputs[t.id] = out
                progress = (0.12 + 0.70 * finished.incrementAndGet() / totalTasks.coerceAtLeast(1)).coerceAtLeast(progress)
                hub.edit(ctx.jobId) { it.copy(progress = progress) }
                emit(AgentEvent.Progress(clock(), Phase.RUNNING, progress, tasks.firstOrNull { it.id !in outputs }?.title ?: "compose"))
                if (difficulty.crossCheck && t.tool.isBlank() && out.length > 200) crossCheck(scope, t, out)?.let { pendingChecks += it }
            }
        }
        // Fill the map first, then start: a task must never look up a dependency that is not registered yet.
        jobs.values.forEach { it.start() }
        jobs.values.forEach { it.await() }
    }

    private suspend fun aiTask(t: Task, m: CrewMember): String {
        hub.card(ctx.jobId, t.id) { it.copy(who = m.name, vendor = m.vendor.label, status = CardStatus.RUNNING, doing = verb(Skill.of(t.title + " " + t.prompt)), startedAt = clock()) }
        val raw = desk.ask(m, Skill.of(t.title + " " + t.prompt), t.id, taskPrompt(t))
        val who = hub[ctx.jobId]?.cards?.firstOrNull { it.id == t.id }?.who ?: m.name
        val out = desk.absorb(raw, who)
        hub.card(ctx.jobId, t.id) {
            it.copy(status = if (out.isBlank()) CardStatus.FAILED else CardStatus.DONE, doing = if (out.isBlank()) "no answer · retried in reinforcement" else "${out.length} chars", endedAt = clock())
        }
        return out
    }

    /** An idle rival takes a quick look. The main flow does not wait for it. */
    private fun crossCheck(scope: CoroutineScope, t: Task, out: String): Job? {
        val author = hub[ctx.jobId]?.cards?.firstOrNull { it.id == t.id }?.vendor
        val pick = team.filter { it.vendor.label != author && it.key != desk.center?.key && desk.busyOf(it) == 0 && crew.available(it) }
            .maxByOrNull { crew.rate(it, Skill.REVIEW) } ?: return null
        desk.hold(pick)                       // reserve now so two checks never pick the same member
        return scope.launch {
            try {
                hub.card(ctx.jobId, t.id) { it.copy(peer = "${pick.name} checking") }
                val r = desk.once(pick, checkPrompt(t, out))
                val v = r.text.trim()
                val fine = v.isBlank() || FINE.containsMatchIn(v)
                if (!fine && !Vendor.limitHit(v, r.via)) {
                    checks[t.id] = "${pick.name}: ${v.take(600)}"
                    board.add(pick.name, NoteKind.WARNING, "${t.title} — ${v.lineSequence().first().take(160)}")
                    hub.edit(ctx.jobId) { it.copy(board = desk.recentBoard()) }
                    emit(AgentEvent.Collab(clock(), Move.CHECK, pick.name, "${t.title}: ${v.lineSequence().first().take(80)}"))
                }
                hub.card(ctx.jobId, t.id) { it.copy(peer = if (fine) "${pick.name} · OK" else "${pick.name} · warning pinned") }
                if (v.isNotBlank()) desk.thank(pick)
            } finally { desk.release(pick) }
        }
    }

    /** Retry failures with someone else, then ask the center for what is still missing (at most two tasks). */
    private suspend fun reinforce(p: Plan, done: List<Task>, round: Int): List<Task> {
        val c = desk.center!!
        val retry = done.filter { outputs[it.id].isNullOrBlank() && it.round == round - 1 }.map { t ->
            val other = team.filter { it.name != t.who && it.key != c.key }.maxByOrNull { crew.rate(it, Skill.of(t.title)) }
            t.copy(id = "${t.id}r$round", title = "${t.title} (retry)", who = other?.name.orEmpty(), round = round, after = emptyList())
        }
        phase(Phase.REINFORCING, 0.8, "${c.name} is looking for gaps")
        hub.addCards(ctx.jobId, listOf(Card("gap$round", "Find gaps", c.name, c.vendor.label, CardStatus.CHECKING, "comparing results with the criteria", clock())))
        val o = JsonPick.obj(desk.askCenter(c, gapPrompt(p, done), takeOver = false))
        val arr = o?.optJSONArray("more") ?: JSONArray()
        val ids = synchronized(outputs) { outputs.keys.toSet() }
        val more = (0 until minOf(arr.length(), 2)).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optString("title").ifBlank { return@mapNotNull null }
            Task("x$round${i + 1}", title.take(48), t.optString("who"), prompt = t.optString("prompt").ifBlank { title },
                after = JsonPick.strings(t.optJSONArray("after")).filter { it in ids }, round = round)
        }
        hub.card(ctx.jobId, "gap$round") {
            it.copy(status = CardStatus.DONE, doing = if (more.isEmpty() && retry.isEmpty()) "nothing missing" else "added ${more.size} · retry ${retry.size}", endedAt = clock())
        }
        return retry + more
    }

    private suspend fun toolTask(t: Task): String {
        hub.card(ctx.jobId, t.id) { it.copy(status = CardStatus.RUNNING, doing = "running ${t.tool}", startedAt = clock()) }
        val args = t.args.mapValues { (_, v) -> fill(v) }
        val r = exec.run(CommandCall("c-${t.id}", t.tool, args))
        if (r.ok && r.text.length > 40) {
            hub.card(ctx.jobId, t.id) { it.copy(status = CardStatus.DONE, doing = "tool result · ${r.text.length} chars", endedAt = clock()) }
            return r.text.take(6000)
        }
        // The tool is blocked or came back empty: a research-strong member looks it up directly.
        desk.pauseGate()
        val alt = desk.bestFree(Skill.RESEARCH, avoidCenter = true)
        emit(AgentEvent.Collab(clock(), Move.SWAP, alt.name, "'${t.tool}' failed — ${alt.name} researches it directly"))
        hub.card(ctx.jobId, t.id) { it.copy(who = alt.name, vendor = alt.vendor.label, status = CardStatus.SWAPPED, doing = "${alt.name} is searching directly") }
        val prompt = "${Prompts.RESEARCH}\nSearch the web yourself and research the following; attach a source link to every fact.\nTask: ${t.title}\n" +
            args.entries.joinToString(" · ") { "${it.key}=${it.value}" } + "\nTeam goal: ${ctx.goal}" + boardTail() + "\n" + ctx.language.directive()
        val out = desk.absorb(desk.lane(alt).withPermit { desk.ask(alt, Skill.RESEARCH, t.id, prompt) }, alt.name)
        hub.card(ctx.jobId, t.id) { it.copy(status = if (out.isBlank()) CardStatus.FAILED else CardStatus.DONE, doing = if (out.isBlank()) "no answer" else "${out.length} chars", endedAt = clock()) }
        return out
    }

    /** `{{t1}}` in tool arguments is replaced with the first line of t1's result. */
    private fun fill(v: String): String = Regex("""\{\{(\w+)}}""").replace(v) { m -> outputs[m.groupValues[1]]?.lineSequence()?.firstOrNull()?.take(200).orEmpty() }

    private suspend fun review(p: Plan, first: String, writer: CrewMember): String {
        var draft = first
        val c = desk.center!!
        val rival = team.filter { it.vendor != c.vendor && it.vendor != writer.vendor }.maxByOrNull { crew.rate(it, Skill.REVIEW) }
        val reviewers = listOfNotNull(c, rival).take(difficulty.reviewers)
        for ((round, rv) in reviewers.withIndex()) {
            desk.pauseGate()
            phase(Phase.REVIEWING, 0.9 + round * 0.04, "${rv.name} is reviewing against the criteria")
            val id = "review$round"
            hub.addCards(ctx.jobId, listOf(Card(id, if (round == 0) "Center review" else "Rival review", rv.name, rv.vendor.label, CardStatus.CHECKING, "checking against criteria", clock())))
            // A review can legitimately be tiny ({"pass":true}), so a short reply is not a weak one here.
            val fixes = parseFixes(desk.ask(rv, Skill.REVIEW, id, reviewPrompt(p, draft), minLen = 8))
            if (fixes.isEmpty()) {
                hub.card(ctx.jobId, id) { it.copy(status = CardStatus.DONE, doing = "passed", endedAt = clock()) }
                emit(AgentEvent.Collab(clock(), Move.REVIEW, rv.name, "passed"))
                continue
            }
            emit(AgentEvent.Collab(clock(), Move.REVIEW, rv.name, "${fixes.size} fixes: " + fixes.take(3).joinToString(" / ")))
            hub.card(ctx.jobId, id) { it.copy(doing = "${fixes.size} fixes → ${writer.name} revises") }
            val revised = desk.absorb(desk.ask(writer, Skill.WRITE, id, revisePrompt(draft, fixes)), writer.name)
            if (revised.length > draft.length * 0.5) draft = revised
            hub.card(ctx.jobId, id) { it.copy(status = CardStatus.DONE, doing = "fixes applied", endedAt = clock()) }
        }
        return draft
    }

    // ───────────── prompts ─────────────

    private fun materials(ids: List<String>, budget: Int): String {
        if (ids.isEmpty()) return ""
        val each = budget / ids.size
        return ids.mapNotNull { id ->
            outputs[id]?.takeIf { it.isNotBlank() }?.let { "[$id]\n" + it.take(each) + (checks[id]?.let { c -> "\n(cross-check · $c)" } ?: "") }
        }.joinToString("\n\n")
    }

    private fun boardTail(): String = board.digest().let { if (it.isBlank()) "" else "\n\n[Team board — what teammates found]\n$it" }

    private fun taskPrompt(t: Task) = buildString {
        appendLine("${Prompts.TASK} Team goal: ${ctx.goal}")
        appendLine("Assigned by the center, ${desk.center?.name}. Team: " + team.joinToString(", ") { it.name } + " — each member is doing a different part right now; focus on yours.")
        appendLine("Your task: ${t.title}")
        appendLine(t.prompt)
        val m = materials(t.after, difficulty.budgetChars)
        if (m.isNotBlank()) { appendLine(); appendLine("Results from teammates before you:"); appendLine(m) }
        append(boardTail())
        appendLine()
        appendLine("Output only the finished work: no greeting, no explanation, no questions back. ${ctx.language.directive()}")
        append(BOARD_ASK)
    }

    private fun checkPrompt(t: Task, out: String) =
        "${Prompts.CHECK} Team goal: ${ctx.goal}\nTask: ${t.title}\nPoint out only factual errors, missing essentials or contradictions in the result below, in at most three lines. If nothing is wrong, reply exactly: OK\n\n${out.take(5000)}"

    private fun gapPrompt(p: Plan, done: List<Task>) = buildString {
        appendLine("${Prompts.GAP} Goal: ${ctx.goal}")
        appendLine("Deliverable: ${p.deliverable} · criteria: " + p.criteria.joinToString(" / "))
        appendLine("Results so far (openings):")
        done.forEach { t -> val o = outputs[t.id].orEmpty(); appendLine("- ${t.id} ${t.title} (${t.who.ifBlank { "tool" }}): " + if (o.isBlank()) "FAILED" else o.take(280).replace('\n', ' ')) }
        board.digest(1600).takeIf { it.isNotBlank() }?.let { appendLine("Team board:"); appendLine(it) }
        appendLine("Teammates: " + team.filter { it.key != desk.center?.key }.joinToString(", ") { it.name })
        append("""List at most two tasks that the criteria still need. Answering a board question is a good task. If nothing is missing, return an empty list. One JSON object only: {"more":[{"title":"","who":"teammate","prompt":"concrete instruction","after":["t1"]}]}""")
    }

    private fun composePrompt(p: Plan) = buildString {
        appendLine("${Prompts.COMPOSE} Goal: ${ctx.goal}")
        appendLine("Deliverable: ${p.deliverable}")
        appendLine("Acceptance criteria: " + p.criteria.joinToString(" / "))
        if (p.finalHow.isNotBlank()) appendLine("How to merge: ${p.finalHow}")
        appendLine(); appendLine("Teammates' results:"); appendLine(materials(synchronized(outputs) { outputs.keys.toList() }, difficulty.budgetChars + 4000))
        board.digest(1800).takeIf { it.isNotBlank() }?.let { appendLine(); appendLine("Team board (every warning must be addressed):"); appendLine(it) }
        appendLine()
        append("Merge everything into one finished deliverable. Combine duplicates, resolve contradictions toward the better-sourced side, fix every warning, keep the sources. Start with the title. ${ctx.language.directive()}")
    }

    private fun reviewPrompt(p: Plan, draft: String) = buildString {
        appendLine("${Prompts.REVIEW} Goal: ${ctx.goal}")
        appendLine("Acceptance criteria: " + p.criteria.joinToString(" / "))
        appendLine("Deliverable:"); appendLine(draft.take(difficulty.budgetChars + 6000))
        appendLine()
        append("""List only what falls short of the criteria, as JSON: {"pass":true|false,"fixes":["concrete fix"]}. If it is good enough: {"pass":true,"fixes":[]}""")
    }

    private fun revisePrompt(draft: String, fixes: List<String>) = buildString {
        appendLine("${Prompts.REVISE} Goal: ${ctx.goal}")
        appendLine("The reviewer asked for:"); fixes.forEach { appendLine("- $it") }
        appendLine("Original:"); appendLine(draft)
        append("Rewrite the complete deliverable with exactly these fixes applied. ${ctx.language.directive()}")
    }

    private fun parseFixes(v: String): List<String> {
        val o = JsonPick.obj(v) ?: return emptyList()
        if (o.optBoolean("pass", false)) return emptyList()
        return JsonPick.strings(o.optJSONArray("fixes")).take(6)
    }

    // ───────────── stage ─────────────

    private fun card(t: Task): Card {
        val m = desk.member(t.who)
        return Card(t.id, t.title, t.who.ifBlank { if (t.tool.isNotBlank()) "tool" else "unassigned" }, m?.vendor?.label.orEmpty(), CardStatus.WAITING,
            if (t.after.isEmpty()) "starting" else "waiting for ${t.after.joinToString()}", after = t.after, tool = t.tool.isNotBlank(), round = t.round)
    }

    private fun phase(p: Phase, value: Double, next: String) {
        progress = maxOf(progress, value)
        hub.edit(ctx.jobId) { it.copy(phase = p, progress = progress) }
        emit(AgentEvent.Progress(clock(), p, progress, next))
    }

    companion object {
        const val RESULT = "RESULT.md"

        internal val BOARD_ASK = "\nIf the team should know something, end with at most three lines (omit when there is nothing):\n" +
            "[board]\nfact: a key fact you verified (source)\ndecision: a direction you settled\nquestion: something a teammate should confirm"

        private val FINE = Regex("^(ok|okay|lgtm|no issues?|looks good|nothing wrong|이상 ?없|문제 ?없)", RegexOption.IGNORE_CASE)
        private val CODE_GOAL = Regex("code|app|program|script|website|html|bot|코드|앱|프로그램|스크립트|웹사이트", RegexOption.IGNORE_CASE)
        private val RESEARCH_GOAL = Regex("find|search|research|latest|compare|news|price|trend|recommend|찾|검색|조사|최신|추천|비교|뉴스|가격|현황|트렌드", RegexOption.IGNORE_CASE)

        /** "- Claude (Anthropic): planning 9, writing 9, critique 9 · strong… Weak: …" */
        fun roster(members: List<CrewMember>): String = members.joinToString("\n") { m ->
            val top = m.vendor.skills.entries.sortedByDescending { it.value }.take(3).joinToString(", ") { "${it.key.label} ${it.value}" }
            "- ${m.name} (${m.vendor.company}): $top" + (if (m.vendor.strong.isNotBlank()) " · ${m.vendor.strong}" else "") +
                (if (m.vendor.weak.isNotBlank()) " Weak: ${m.vendor.weak}" else "")
        }

        internal fun verb(s: Skill) = when (s) {
            Skill.RESEARCH -> "researching"; Skill.CODE -> "writing code"; Skill.REVIEW -> "reviewing"; Skill.PLAN -> "designing"
            Skill.CREATIVE -> "brainstorming"; Skill.REASON -> "analysing"; else -> "writing"
        }
    }
}
