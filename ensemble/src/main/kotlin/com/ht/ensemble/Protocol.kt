package com.ht.ensemble

import com.ht.ensemble.aegis.ApprovalGate
import com.ht.ensemble.aegis.NormalizedAction
import com.ht.ensemble.tools.CommandCall
import com.ht.ensemble.tools.CommandResult
import com.ht.ensemble.tools.CommandSpec
import kotlinx.coroutines.flow.MutableStateFlow

/** One model reply. [via] says how it went: `HTTP200`, `HTTP429:…`, `ERR:…`, or anything your port reports. */
data class LlmReply(val text: String, val via: String, val ms: Long)

/**
 * The only way Ensemble talks to a model. Implementations must not throw for ordinary failures:
 * return an empty [LlmReply.text] and a reason in [LlmReply.via] so the desk can hand the work
 * to someone else. See [com.ht.ensemble.ports.Ports] for ready-made HTTP ports.
 */
interface LlmPort {
    val name: String
    suspend fun send(prompt: String, jobId: String): LlmReply
}

/** Tools the team may call (web search, files, mail…). Calls with outside effects pass through the approval gate. */
interface ToolPort {
    fun spec(id: String): CommandSpec?
    suspend fun run(call: CommandCall): CommandResult
    /** Has this address been written to before? Unknown recipients always require approval. */
    fun recipientKnown(address: String): Boolean = true
}

data class ApprovalRequest(
    val jobId: String,
    val call: CommandCall,
    val spec: CommandSpec,
    val action: NormalizedAction,
    val digest: String,
    val summary: String,
    val warning: String?,
)

sealed interface ApprovalAnswer {
    data class Approved(val token: ApprovalGate.Token) : ApprovalAnswer
    data class Rejected(val why: String) : ApprovalAnswer
}

interface HumanPort {
    suspend fun approve(req: ApprovalRequest): ApprovalAnswer
    suspend fun ask(jobId: String, question: String, choices: List<String>): String?
}

enum class JobState { RUNNING, PAUSED_APPROVAL, PAUSED, COMPLETED, FAILED, ABORTED;
    val terminal: Boolean get() = this == COMPLETED || this == FAILED || this == ABORTED
}

/** How deep a [Conductor] run goes: tasks per plan, gap-filling rounds, cross-checks, reviewers. */
enum class Difficulty(val maxTasks: Int, val extraRounds: Int, val crossCheck: Boolean, val reviewers: Int, val budgetChars: Int) {
    QUICK(3, 0, false, 0, 4_000),
    STANDARD(5, 1, true, 1, 8_000),
    DEEP(7, 1, true, 2, 14_000),
}

/** The beats of teamwork, as they show up in [AgentEvent.Collab]. */
enum class Move { CENTER, DISPATCH, SWAP, BOARD, CHECK, REINFORCE, REVIEW, KICKOFF, ASSIGN, STANDUP, RETRO, QA, INSPECT, DELIVER }

sealed interface AgentEvent {
    val at: Long
    data class Started(override val at: Long, val goal: String, val center: String) : AgentEvent
    /** Somebody did something for the team: who, which beat, what. */
    data class Collab(override val at: Long, val move: Move, val who: String, val text: String) : AgentEvent
    data class Note(override val at: Long, val text: String) : AgentEvent
    data class Progress(override val at: Long, val phase: Phase, val progress: Double, val next: String) : AgentEvent
    data class ToolStart(override val at: Long, val call: CommandCall, val spec: CommandSpec) : AgentEvent
    data class ToolEnd(override val at: Long, val call: CommandCall, val spec: CommandSpec?, val result: CommandResult, val ms: Long) : AgentEvent
    data class NeedApproval(override val at: Long, val req: ApprovalRequest) : AgentEvent
    data class Approval(override val at: Long, val call: CommandCall, val approved: Boolean, val note: String) : AgentEvent
    data class Done(override val at: Long, val state: JobState, val text: String) : AgentEvent

    /** A one-line, human-readable rendering — handy for logs. */
    fun line(): String = when (this) {
        is Started -> "▶ $goal  (center: $center)"
        is Collab -> "· ${move.name.lowercase().padEnd(9)} $who — $text"
        is Note -> "  $text"
        is Progress -> "  [${phase.name.lowercase()}] ${(progress * 100).toInt()}% · $next"
        is ToolStart -> "  ⚙ ${call.cmd}"
        is ToolEnd -> "  ⚙ ${call.cmd} → ${if (result.ok) "ok" else "failed: " + result.text.take(60)}"
        is NeedApproval -> "  ? approval needed: ${req.summary.lineSequence().first()}"
        is Approval -> "  ${if (approved) "✓ approved" else "✗ rejected"} ${call.cmd} ($note)"
        is Done -> "■ ${state.name.lowercase()}"
    }
}

/** What the team is working for. */
data class JobContext(
    val jobId: String,
    val goal: String,
    val now: String,
    val language: Language = Language.ENGLISH,
    val client: String? = null,
    val notes: List<String> = emptyList(),
    val guide: String? = null,
)

/** Pause, resume, abort. Pause stops new requests; abort also cancels the ones in flight. */
class JobControl {
    enum class Signal { RUN, PAUSE, ABORT }
    val signal: MutableStateFlow<Signal> = MutableStateFlow(Signal.RUN)
    fun pause() { if (signal.value != Signal.ABORT) signal.value = Signal.PAUSE }
    fun resume() { if (signal.value != Signal.ABORT) signal.value = Signal.RUN }
    fun abort() { signal.value = Signal.ABORT }
}

/** [partial]: the job finished, but some work never came back and the deliverable was assembled from what did. */
data class Outcome(val state: JobState, val text: String, val units: Int, val partial: Boolean = false)

/**
 * Every prompt Ensemble sends starts with one of these tags. They make runs easy to grep and let
 * simulators and test doubles answer by role. [kindOf] recovers the tag from a prompt.
 */
object Prompts {
    const val PLAN = "[ensemble:plan]"
    const val TASK = "[ensemble:task]"
    const val CHECK = "[ensemble:check]"
    const val GAP = "[ensemble:gap]"
    const val COMPOSE = "[ensemble:compose]"
    const val REVIEW = "[ensemble:review]"
    const val REVISE = "[ensemble:revise]"
    const val RESEARCH = "[ensemble:research]"
    const val CHARTER = "[ensemble:charter]"
    const val TICKET = "[ensemble:ticket]"
    const val REWORK = "[ensemble:rework]"
    const val GRADE = "[ensemble:grade]"
    const val STANDUP = "[ensemble:standup]"
    const val FINAL_QA = "[ensemble:qa]"
    const val REPORT = "[ensemble:report]"
    const val REFORMAT = "[ensemble:reformat]"
    const val CONTINUE = "[ensemble:continue]"

    private val TAG = Regex("""\[ensemble:([a-z-]+)]""")
    fun kindOf(prompt: String): String? = TAG.find(prompt.take(80))?.value
}
