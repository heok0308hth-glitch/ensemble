package com.ht.ensemble.tools

import com.ht.ensemble.AgentEvent
import com.ht.ensemble.ApprovalAnswer
import com.ht.ensemble.ApprovalRequest
import com.ht.ensemble.HumanPort
import com.ht.ensemble.ToolPort
import com.ht.ensemble.aegis.ApprovalGate
import com.ht.ensemble.aegis.ApprovalSummaries
import com.ht.ensemble.aegis.Canonical
import com.ht.ensemble.aegis.NormalizedAction
import com.ht.ensemble.aegis.Policy
import com.ht.ensemble.aegis.PolicyDecision
import com.ht.ensemble.aegis.PolicyInput
import kotlinx.coroutines.CancellationException

/** What running a tool does to the world. Levels ≥ 2 leave the machine and need a human "yes". */
enum class Effect(val level: Int, val needsApproval: Boolean) {
    READ(0, false), LOCAL(1, false), EXTERNAL(2, true), MONEY(3, true), DESTRUCTIVE(4, true);
}

data class Param(val name: String, val desc: String, val required: Boolean = true)

/** A tool the planner may put in a task: `web.search(q)`, `mail.send(to, subject, body)`… */
data class CommandSpec(val id: String, val title: String, val effect: Effect, val params: List<Param>, val desc: String) {
    /** The one-line catalog entry the planner sees. */
    fun line(): String {
        val p = params.joinToString(",") { (if (it.required) "" else "?") + it.name }
        return "$id($p) $desc" + if (effect.needsApproval) " [needs approval]" else ""
    }
}

data class CommandCall(val callId: String, val cmd: String, val args: Map<String, String>)

data class CommandResult(val ok: Boolean, val text: String) {
    companion object {
        fun ok(t: String) = CommandResult(true, t)
        fun fail(t: String) = CommandResult(false, t)
    }
}

/**
 * A [ToolPort] from plain lambdas.
 *
 * ```
 * val tools = Toolbox {
 *     tool("web.search", "Web search", Effect.READ, listOf(Param("q", "query"))) { call -> CommandResult.ok(search(call.args["q"]!!)) }
 * }
 * ```
 */
class Toolbox(block: Toolbox.() -> Unit = {}) : ToolPort {
    private val specs = LinkedHashMap<String, CommandSpec>()
    private val handlers = HashMap<String, suspend (CommandCall) -> CommandResult>()

    init { block() }

    fun tool(id: String, desc: String, effect: Effect = Effect.READ, params: List<Param> = emptyList(), run: suspend (CommandCall) -> CommandResult) {
        specs[id] = CommandSpec(id, desc, effect, params, desc)
        handlers[id] = run
    }

    fun catalog(): String = specs.values.joinToString("\n") { it.line() }
    override fun spec(id: String): CommandSpec? = specs[id]
    override suspend fun run(call: CommandCall): CommandResult = handlers[call.cmd]?.invoke(call) ?: CommandResult.fail("unknown tool: ${call.cmd}")
}

/** Validates a call, routes it through policy and the approval gate, runs it, and reports events. */
class CommandExec(
    private val jobId: String,
    private val tools: ToolPort,
    private val human: HumanPort,
    private val gate: ApprovalGate,
    private val emit: (AgentEvent) -> Unit,
    private val clock: () -> Long,
) {
    suspend fun run(call: CommandCall): CommandResult {
        val spec = tools.spec(call.cmd)
            ?: return CommandResult.fail("unknown tool: ${call.cmd}").also { emit(AgentEvent.ToolEnd(clock(), call, null, it, 0)) }
        val missing = spec.params.filter { it.required && call.args[it.name].isNullOrBlank() }.map { it.name }
        if (missing.isNotEmpty()) return CommandResult.fail("missing argument: ${missing.joinToString()}").also { emit(AgentEvent.ToolEnd(clock(), call, spec, it, 0)) }
        if (spec.effect.needsApproval) approve(call, spec)?.let { return it }
        emit(AgentEvent.ToolStart(clock(), call, spec))
        val t0 = clock()
        val r = try { tools.run(call) } catch (e: CancellationException) { throw e } catch (t: Throwable) { CommandResult.fail("tool error: ${t.message ?: t.javaClass.simpleName}") }
        emit(AgentEvent.ToolEnd(clock(), call, spec, r, clock() - t0))
        return r
    }

    private fun normalize(call: CommandCall) = NormalizedAction(call.cmd, call.args.toSortedMap(), call.args["account"])

    private suspend fun approve(call: CommandCall, spec: CommandSpec): CommandResult? {
        val action = normalize(call)
        val to = call.args["to"].orEmpty()
        val known = to.isBlank() || to.split(',', ';').all { tools.recipientKnown(it.trim()) }
        val text = call.args["body"] ?: call.args["text"] ?: ""
        return when (val d = Policy.decide(PolicyInput(spec.effect, known, Regex("https?://").containsMatchIn(text)))) {
            is PolicyDecision.Deny -> CommandResult.fail("denied by policy: ${d.reason}").also { emit(AgentEvent.Approval(clock(), call, false, d.reason)) }
            is PolicyDecision.Auto -> null
            is PolicyDecision.Ask -> {
                val req = ApprovalRequest(jobId, call, spec, action, Canonical.digest(action), ApprovalSummaries.describe(spec, action), d.warning)
                emit(AgentEvent.NeedApproval(clock(), req))
                when (val ans = human.approve(req)) {
                    is ApprovalAnswer.Rejected -> CommandResult.fail("rejected: ${ans.why}").also { emit(AgentEvent.Approval(clock(), call, false, ans.why)) }
                    // Re-normalize at execution time: the token is bound to the exact bytes the human saw.
                    is ApprovalAnswer.Approved -> when (val v = gate.verifyAndConsume(ans.token, normalize(call), clock())) {
                        is ApprovalGate.Verdict.Ok -> { emit(AgentEvent.Approval(clock(), call, true, "approved")); null }
                        is ApprovalGate.Verdict.Bad -> CommandResult.fail("approval check failed (${v.code})").also { emit(AgentEvent.Approval(clock(), call, false, v.code)) }
                    }
                }
            }
        }
    }
}
