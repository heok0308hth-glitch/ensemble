package com.ht.ensemble

import com.ht.ensemble.tools.CommandCall
import com.ht.ensemble.tools.CommandResult
import com.ht.ensemble.tools.CommandSpec
import com.ht.ensemble.tools.Effect
import com.ht.ensemble.tools.Param
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object Rejecter : HumanPort {
    override suspend fun approve(req: ApprovalRequest) = ApprovalAnswer.Rejected("no")
    override suspend fun ask(jobId: String, question: String, choices: List<String>) = choices.firstOrNull()
}

/** A web search that is blocked (403) and a local file tool. */
class FakeTools : ToolPort {
    val ran: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val specs = listOf(
        CommandSpec("web.search", "Web search", Effect.READ, listOf(Param("q", "query")), "search the web"),
        CommandSpec("mail.send", "Send mail", Effect.EXTERNAL, listOf(Param("to", "address"), Param("body", "text")), "send an email"),
    ).associateBy { it.id }
    override fun spec(id: String) = specs[id]
    override suspend fun run(call: CommandCall): CommandResult {
        ran += call.cmd
        return if (call.cmd == "web.search") CommandResult.fail("blocked (403)") else CommandResult.ok("sent")
    }
}

val FIVE = listOf(
    CrewMember("C", "Claude", Vendor.CLAUDE), CrewMember("G", "ChatGPT", Vendor.GPT), CrewMember("M", "Gemini", Vendor.GEMINI),
    CrewMember("X", "Grok", Vendor.GROK), CrewMember("D", "DeepSeek", Vendor.DEEPSEEK, lanes = 2),
)

/**
 * A scripted team. [reply] picks the answer per (member, prompt kind, prompt); returning null
 * falls back to sensible defaults. Records every prompt, peak concurrency and cancellations.
 */
open class ScriptedCrew(
    val team: List<CrewMember> = FIVE,
    val delayMs: Long = 40,
    val reply: (CrewMember, String?, String) -> String? = { _, _, _ -> null },
) : Crew {
    val prompts = ConcurrentHashMap<String, MutableList<String>>()
    val sends = AtomicInteger()
    val live = AtomicInteger()
    val peak = AtomicInteger()
    val cancelled = AtomicInteger()
    val benched: MutableSet<String> = Collections.synchronizedSet(HashSet())

    override fun members() = team
    override fun available(m: CrewMember) = m.key !in benched
    override fun rate(m: CrewMember, s: Skill) = m.vendor.score(s).toDouble()
    override fun report(m: CrewMember, ok: Boolean, ms: Long, text: String, via: String) { if (Vendor.limitHit(text, via)) benched += m.key }

    fun all(): List<String> = prompts.values.flatMap { synchronized(it) { it.toList() } }

    override fun port(m: CrewMember, lane: Int) = object : LlmPort {
        override val name = m.name
        override suspend fun send(prompt: String, jobId: String): LlmReply {
            sends.incrementAndGet()
            prompts.getOrPut(m.name) { Collections.synchronizedList(ArrayList()) }.add(prompt)
            val n = live.incrementAndGet(); peak.accumulateAndGet(n, ::maxOf)
            try {
                try { delay(delayMs) } catch (e: CancellationException) { cancelled.incrementAndGet(); throw e }
                val kind = Prompts.kindOf(prompt)
                return LlmReply(reply(m, kind, prompt) ?: default(m, kind), "fake", delayMs)
            } finally { live.decrementAndGet() }
        }
    }

    open fun default(m: CrewMember, kind: String?): String = when (kind) {
        Prompts.PLAN, Prompts.REFORMAT -> ALL_TO_CLAUDE
        Prompts.REVIEW -> """{"pass":true}"""
        Prompts.GAP -> """{"more":[]}"""
        Prompts.CHECK -> "OK"
        Prompts.COMPOSE -> "# Result\n\n" + "The merged body written by the team. ".repeat(12)
        else -> "${m.name} result: " + "concrete content with numbers. ".repeat(12) + "\n\n[board]\nfact: ${m.name} verified one fact\nquestion: none"
    }

    companion object {
        /** A biased plan: the planner names Claude for everything. The market must not obey. */
        const val ALL_TO_CLAUDE = """{"deliverable":"report","criteria":["complete"],"tasks":[
 {"id":"t1","title":"Gather material","who":"Claude","prompt":"gather"},{"id":"t2","title":"Organise viewpoints","who":"Claude","prompt":"organise"},
 {"id":"t3","title":"Analyse cases","who":"Claude","prompt":"analyse"},{"id":"t4","title":"Write the body","who":"Claude","prompt":"write","after":["t1","t2","t3"]}],
 "final":{"who":"Claude","how":"merge"}}"""
    }
}
