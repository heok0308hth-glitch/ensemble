package com.ht.ensemble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** One seat on the team. [lanes] > 1 lets the same model take that many requests at once. */
data class CrewMember(val key: String, val name: String, val vendor: Vendor, val lanes: Int = 1)

/** The team as the engines see it. [Ensemble] implements it; tests and apps may supply their own. */
interface Crew {
    fun members(): List<CrewMember>
    fun port(m: CrewMember, lane: Int): LlmPort
    /** Called after every request so the scoreboard can learn (and bench members that hit limits). */
    fun report(m: CrewMember, ok: Boolean, ms: Long, text: String, via: String)
    fun available(m: CrewMember): Boolean
    /** Ability for a skill, 0..10, after live performance is applied. */
    fun rate(m: CrewMember, s: Skill): Double
}

/**
 * The front desk shared by [Conductor] and [Company]. It owns everything about *calling* the team:
 * who is busy, how many lanes each member has, who takes over on failure, how pause and abort are
 * honoured, how board notes are lifted out of replies, and why each member failed.
 */
class CrewDesk(
    val jobId: String,
    val crew: Crew,
    val hub: StageHub,
    private val control: JobControl,
    private val emit: (AgentEvent) -> Unit,
    private val clock: () -> Long,
    val ledger: TokenLedger,
) {
    /** A plain exception (not cancellation) so that siblings in the same scope are cancelled too. */
    class Aborted : Exception("job aborted")

    var team: List<CrewMember> = emptyList()
    var center: CrewMember? = null
    val board = Board()

    private val busy = ConcurrentHashMap<String, AtomicInteger>()
    private val credit = ConcurrentHashMap<String, AtomicInteger>()
    private val fails = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    private val rr = ConcurrentHashMap<String, AtomicInteger>()
    private val lanes = ConcurrentHashMap<String, Semaphore>()

    fun laneOf(m: CrewMember): Int = rr.getOrPut(m.key) { AtomicInteger() }.getAndIncrement() % m.lanes.coerceAtLeast(1)
    fun lane(m: CrewMember): Semaphore = lanes.getOrPut(m.key) { Semaphore(m.lanes.coerceAtLeast(1)) }
    fun busyOf(m: CrewMember): Int = busy[m.key]?.get() ?: 0
    fun hold(m: CrewMember) { busy.getOrPut(m.key) { AtomicInteger() }.incrementAndGet() }
    fun release(m: CrewMember) { busy[m.key]?.decrementAndGet() }
    fun thank(m: CrewMember) { credit.getOrPut(m.key) { AtomicInteger() }.incrementAndGet() }
    fun creditOf(m: CrewMember): Int = credit[m.key]?.get() ?: 0
    fun member(name: String): CrewMember? = team.firstOrNull { it.name == name }

    /** "Claude 3 · Gemini 2 · …" — finished units per member, busiest first. */
    fun credits(): String = credit.entries.sortedByDescending { it.value.get() }
        .joinToString(" · ") { (k, v) -> "${team.firstOrNull { it.key == k }?.name ?: k} ${v.get()}" }

    /** Per member: finished units and failure reasons ("usage limit 2, empty reply 1"). */
    fun roll(): List<Triple<String, Int, String>> = team.map { m ->
        val f = fails[m.key]?.toList().orEmpty()
        Triple(m.name, creditOf(m), f.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" })
    }

    private fun failed(m: CrewMember, text: String, via: String) {
        fails.getOrPut(m.key) { ConcurrentLinkedQueue() }.add(reason(text, via))
    }

    private fun reason(text: String, via: String): String = when {
        Vendor.limitHit(text, via) -> "usage limit"
        via.contains("LOGIN") || via.contains("HTTP401") -> "signed out"
        via.contains("HTTP403") || via.contains("CHALLENGE") -> "blocked"
        via.startsWith("HTTP5") -> "server error"
        via.startsWith("ERR") -> "network error"
        text.isBlank() -> "empty reply"
        else -> "weak reply"
    }

    /** Run [block] under an abort watcher: the moment abort is signalled every request inside is cancelled. */
    suspend fun <T> guarded(block: suspend CoroutineScope.() -> T): T = coroutineScope {
        val watch = launch { control.signal.first { it == JobControl.Signal.ABORT }; throw Aborted() }
        try { block() } finally { watch.cancel() }
    }

    /** Before every request: wait while paused, throw on abort. */
    suspend fun pauseGate() {
        when (control.signal.value) {
            JobControl.Signal.ABORT -> throw Aborted()
            JobControl.Signal.PAUSE -> {
                val was = hub[jobId]?.phase ?: Phase.RUNNING
                hub.edit(jobId) { it.copy(phase = Phase.PAUSED) }
                if (control.signal.first { it != JobControl.Signal.PAUSE } == JobControl.Signal.ABORT) throw Aborted()
                hub.edit(jobId) { it.copy(phase = was) }
            }
            JobControl.Signal.RUN -> Unit
        }
    }

    /**
     * Ask [first]; if the reply is empty, too short, a refusal or a usage-limit notice, hand the
     * same prompt to the best idle member for [skill]. Up to three members; the center last.
     * Returns "" only when everyone failed.
     */
    suspend fun ask(first: CrewMember, skill: Skill, cardId: String, prompt: String, minLen: Int = 20): String {
        val tried = HashSet<String>()
        var m: CrewMember? = first
        while (tried.size < 3) {
            val cur = m ?: break
            pauseGate()
            tried += cur.key
            if (!crew.available(cur)) { m = next(skill, tried); continue }
            val t0 = clock()
            hold(cur)
            val r = try { send(cur, prompt) } finally { release(cur) }
            val text = r.text.trim()
            val echoed = Gate.echo(text, prompt)
            val ok = text.length >= minLen && !refusal(text) && !Vendor.limitHit(r.text, r.via) && !echoed
            crew.report(cur, ok, clock() - t0, r.text, r.via)
            ledger.add(cur.name, Tokens.estimate(prompt), Tokens.estimate(text))
            if (ok) { thank(cur); return text }
            val why = if (echoed) ECHOED else reason(r.text, r.via)
            fails.getOrPut(cur.key) { ConcurrentLinkedQueue() }.add(why)
            val alt = next(skill, tried) ?: break
            emit(AgentEvent.Collab(clock(), Move.SWAP, alt.name, "${cur.name}: $why → ${alt.name} takes over"))
            hub.card(jobId, cardId) { it.copy(who = alt.name, vendor = alt.vendor.label, status = CardStatus.SWAPPED, doing = "${cur.name} $why → ${alt.name}") }
            delay(200)
            hub.card(jobId, cardId) { it.copy(status = CardStatus.RUNNING) }
            m = alt
        }
        return ""
    }

    /** Center-only calls (plan, judgement). On failure the next best planner takes the baton if [takeOver]. */
    suspend fun askCenter(m: CrewMember, prompt: String, takeOver: Boolean, depth: Int = 0): String {
        pauseGate()
        val t0 = clock()
        val r = send(m, prompt, lane = 0)
        val ok = r.text.isNotBlank() && !Vendor.limitHit(r.text, r.via)
        crew.report(m, ok, clock() - t0, r.text, r.via)
        ledger.add(m.name, Tokens.estimate(prompt), Tokens.estimate(r.text))
        if (ok) return r.text
        failed(m, r.text, r.via)
        if (depth >= 2) return ""
        val alt = next(Skill.PLAN, setOf(m.key), allowCenter = true) ?: return ""
        if (takeOver) {
            center = alt
            hub.edit(jobId) { it.copy(center = alt.name, centerVendor = alt.vendor.label) }
            emit(AgentEvent.Collab(clock(), Move.CENTER, alt.name, "takes the baton from ${m.name} (${reason(r.text, r.via)})"))
        }
        return askCenter(alt, prompt, takeOver, depth + 1)
    }

    /** One shot, no failover — for cross-checks and grading, where the flow continues either way. */
    suspend fun once(m: CrewMember, prompt: String): LlmReply {
        pauseGate()
        val t0 = clock()
        hold(m)
        val r = try { send(m, prompt) } finally { release(m) }
        val ok = r.text.isNotBlank() && !Vendor.limitHit(r.text, r.via)
        crew.report(m, ok, clock() - t0, r.text, r.via)
        if (!ok) failed(m, r.text, r.via)
        ledger.add(m.name, Tokens.estimate(prompt), Tokens.estimate(r.text))
        return r
    }

    private suspend fun send(m: CrewMember, prompt: String, lane: Int = laneOf(m)): LlmReply =
        try { crew.port(m, lane).send(prompt, jobId) } catch (e: CancellationException) { throw e } catch (e: Exception) { LlmReply("", "ERR:${e.message}", 0) }

    /** Next taker for [skill]: ability minus current load minus the center penalty. */
    fun next(skill: Skill, tried: Set<String>, allowCenter: Boolean = false): CrewMember? =
        team.filter { it.key !in tried && crew.available(it) }
            .maxByOrNull { Dispatch.bid(crew.rate(it, skill), busyOf(it), it.lanes, !allowCenter && it.key == center?.key, team.size, false) }

    fun bestFree(skill: Skill, avoidCenter: Boolean, hint: String = "", exclude: Set<String> = emptySet()): CrewMember {
        val pool = team.filter { crew.available(it) && it.key !in exclude }.ifEmpty { team.filter { it.key !in exclude } }.ifEmpty { team }
        return pool.maxBy { Dispatch.bid(crew.rate(it, skill), busyOf(it), it.lanes, avoidCenter && it.key == center?.key, pool.size, Dispatch.matches(hint, it)) }
    }

    /** Lift the board block off a reply, pin the notes, return the deliverable. */
    fun absorb(raw: String, who: String, loud: Boolean = true): String {
        val (body, notes) = Board.split(raw)
        val added = notes.count { (k, v) -> board.add(who, k, v) }
        if (added > 0) {
            hub.edit(jobId) { it.copy(board = recentBoard()) }
            val summary = notes.joinToString(" / ") { "${it.first.label}: ${it.second.take(48)}" }
            emit(if (loud) AgentEvent.Collab(clock(), Move.BOARD, who, summary) else AgentEvent.Note(clock(), "board · $who · $summary"))
        }
        return body
    }

    fun recentBoard(): List<String> = board.all().takeLast(6).map { "${it.who} · ${it.kind.label} · ${it.text.take(60)}" }

    fun refusal(s: String): Boolean = s.length < 120 && REFUSAL.containsMatchIn(s)

    /** Record a failure the caller detected itself (a gate rejection, an unreadable grade). */
    fun noteFailure(m: CrewMember, why: String) { fails.getOrPut(m.key) { ConcurrentLinkedQueue() }.add(why) }

    companion object {
        const val ECHOED = "echoed the prompt"
        private val REFUSAL = Regex("(I can't help|I cannot help|I can't assist|unable to help|죄송하지만|도와드릴 수 없)", RegexOption.IGNORE_CASE)
    }
}
