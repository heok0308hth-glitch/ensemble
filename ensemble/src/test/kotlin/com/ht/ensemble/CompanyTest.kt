package com.ht.ensemble

import com.ht.ensemble.aegis.ApprovalGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A two-hour project in a few seconds: a fake clock advances four minutes per reply (overlapping
 * requests overlap in time, as they would for real).
 */
class CompanyTest {
    private val ctx = JobContext("CO", "Write a business plan for a small specialty café", "2026-11-24 16:00")

    private val charter = """{"title":"Café business plan","summary":"A 40 m² specialty café downtown","audience":"founders and investors",
 "rubric":[{"name":"Accuracy","desc":"numbers with sources"},{"name":"Actionable","desc":"can be executed as written"},{"name":"Depth","desc":"local cases"},{"name":"Clarity","desc":"structure"}],
 "files":[{"path":"01_market.md","purpose":"area and competition"},{"path":"02_model.md","purpose":"menu and pricing"},{"path":"03_finance.md","purpose":"investment and P&L"},{"path":"04_execution.md","purpose":"timeline"}],
 "milestones":["Research","Design","Polish"],
 "backlog":[{"id":"T1","title":"Area research","file":"01_market.md","brief":"foot traffic and competing cafés"},
  {"id":"T2","title":"Menu and pricing","file":"02_model.md","brief":"signature menu and price points"},
  {"id":"T3","title":"Financials","file":"03_finance.md","brief":"capex and monthly P&L","inputs":["01_market.md","02_model.md"],"after":["T1","T2"]},
  {"id":"T4","title":"Timeline","file":"04_execution.md","brief":"12-week schedule"}]}"""

    private inner class Firm(
        val gradeOf: (String) -> Double = { p -> if (p.contains("# Draft")) 6.5 else 9.0 },
        val pmDone: () -> Boolean = { false },
        val charterJson: String = charter,
    ) : ScriptedCrew(delayMs = 3) {
        val now = AtomicLong(0)
        val authored = ConcurrentHashMap<String, MutableList<String>>()

        override fun port(m: CrewMember, lane: Int): LlmPort {
            val inner = super.port(m, lane)
            return object : LlmPort {
                override val name = m.name
                override suspend fun send(prompt: String, jobId: String): LlmReply {
                    val start = now.get()
                    val r = inner.send(prompt, jobId)
                    now.accumulateAndGet(start + 4 * 60_000) { a, b -> maxOf(a, b) }
                    return LlmReply(answer(m, prompt) ?: r.text, r.via, r.ms)
                }
            }
        }

        fun answer(m: CrewMember, p: String): String? = when (Prompts.kindOf(p)) {
            Prompts.CHARTER -> charterJson
            Prompts.STANDUP -> if (pmDone()) """{"summary":"good enough","done":true,"tickets":[]}"""
                else """{"summary":"strengthen finance","done":false,"decisions":["use the downtown average rent"],"tickets":[{"id":"A","title":"Finance sensitivity","file":"03_finance.md","brief":"add sensitivity analysis","inputs":["01_market.md"]},{"id":"B","title":"More competitors","file":"01_market.md","brief":"five competing cafés"},{"id":"C","title":"Risks","file":"05_risks.md","brief":"risks and responses"}],"files":[{"path":"05_risks.md","purpose":"risks"}]}"""
            Prompts.GRADE -> { val s = gradeOf(p); """{"scores":{"Accuracy":$s,"Actionable":$s,"Depth":$s,"Clarity":$s},"must":${if (s < 8) "[\"cite the numbers\"]" else "[]"},"issues":[]}""" }
            Prompts.FINAL_QA -> """{"scores":{"Accuracy":8.6,"Actionable":8.9,"Depth":8.4,"Clarity":9.0},"must":[],"issues":[]}"""
            Prompts.REPORT -> "# Café business plan — report\n\nSummary: 40 m², capex 78k, payback in 14 months.\n\nGuide to the files…"
            Prompts.REWORK -> "# Final\n\nFindings addressed, sources cited (city open data). " + "Concrete numbers and cases. ".repeat(30)
            Prompts.TICKET -> {
                val tid = Regex("""Ticket ([\w-]+),""").find(p)?.groupValues?.get(1) ?: "?"
                authored.getOrPut(m.name) { java.util.Collections.synchronizedList(ArrayList()) }.add(tid)
                (if (tid == "T1") "# Draft\n\n" else "# $tid\n\n") + "${m.name} wrote this. " + "Concrete evidence and numbers. ".repeat(30) + "\n\n[board]\nfact: ${m.name} checked $tid"
            }
            else -> null
        }
    }

    private fun company(f: Firm, files: ProjectFiles, minutes: Int = 120, control: JobControl = JobControl(), id: String = "CO1", hub: StageHub = StageHub(),
                        saved: StringBuilder = StringBuilder(), load: String? = null, events: MutableList<AgentEvent> = ArrayList(),
                        deliver: suspend (Company.Delivery) -> List<String> = { emptyList() }, inspect: (suspend (ProjectFiles) -> String?)? = null) =
        Company(ctx.copy(jobId = id), f, FakeTools(), Rejecter, ApprovalGate(), control, { synchronized(events) { events += it } }, hub, { f.now.get() },
            minutes, files = files, save = { saved.setLength(0); saved.append(it) }, load = { load }, deliver = deliver, inspect = inspect)

    @Test fun twoHourProject_sprints_grades_reworks_untilTimeIsSpent() = runBlocking {
        val f = Firm()
        val files = MemoryFiles()
        val saved = StringBuilder()
        val ev = ArrayList<AgentEvent>()
        val hub = StageHub()
        val out = withTimeoutOrNull(60_000) { company(f, files, 120, hub = hub, saved = saved, events = ev).run() }
        assertNotNull("no deadlock · cards=" + hub["CO1"]?.cards?.joinToString { "${it.id}:${it.status}" }, out)
        assertEquals(out!!.text, JobState.COMPLETED, out.state)
        val st = hub["CO1"]!!
        assertTrue("several sprints (${st.sprint})", st.sprint >= 3)
        assertTrue("uses most of the budget (${f.now.get() / 60_000} min)", f.now.get() >= 90 * 60_000L)
        assertTrue("does not overrun badly (${f.now.get() / 60_000} min)", f.now.get() <= 150 * 60_000L)
        listOf("01_market.md", "02_model.md", "03_finance.md", "04_execution.md", "05_risks.md", Company.REPORT).forEach { assertNotNull("file $it", files.read(it)) }
        assertFalse("the rejected draft was replaced", files.read("01_market.md")!!.startsWith("# Draft"))
        assertFalse("the PM writes no tickets: ${f.authored.keys}", f.authored.containsKey("Claude"))
        assertTrue("at least three authors: ${f.authored.keys}", f.authored.keys.size >= 3)
        assertTrue("the report carries a real work log", files.read(Company.REPORT)!!.contains("## Work log"))
        assertTrue("checkpointed", saved.contains("\"tickets\""))
        assertTrue("stand-up decisions are recorded", saved.contains("downtown average rent"))
        assertTrue(ev.any { it is AgentEvent.Collab && it.move == Move.RETRO })
        val snap = JSONObject(saved.toString()).getJSONArray("tickets")
        for (i in 0 until snap.length()) {
            val t = snap.getJSONObject(i)
            if (t.getString("state") == "DONE") {
                val author = FIVE.first { it.name == t.getString("who") }.vendor
                val reviewer = FIVE.first { it.name == t.getString("reviewer") }.vendor
                assertNotEquals("graded by a rival vendor", author, reviewer)
            }
        }
    }

    @Test fun excellentAndDone_finishesEarly() = runBlocking {
        val f = Firm(gradeOf = { 9.6 }, pmDone = { true })
        val out = company(f, MemoryFiles(), 180, id = "CO2").run()
        assertEquals(JobState.COMPLETED, out.state)
        assertTrue("quality ≥ 9 and the PM agrees → early finish (${f.now.get() / 60_000} min)", f.now.get() < 90 * 60_000L)
    }

    @Test fun abort_thenResume_withoutRedoingFinishedTickets() = runBlocking {
        val files = MemoryFiles()
        val saved = StringBuilder()
        val control = JobControl()
        var first: Outcome? = null
        val job = launch(Dispatchers.Default) { first = company(Firm(), files, 120, control = control, id = "CO3", saved = saved).run() }
        while (files.read("04_execution.md") == null || files.read("03_finance.md") == null) delay(5)
        control.abort()
        job.join()
        assertEquals(JobState.ABORTED, first!!.state)
        val tickets = JSONObject(saved.toString()).getJSONArray("tickets")
        assertTrue((0 until tickets.length()).count { tickets.getJSONObject(it).getString("state") == "DONE" } >= 3)
        val f2 = Firm()
        val out = company(f2, files, 120, id = "CO3", load = saved.toString()).run()
        assertEquals(JobState.COMPLETED, out.state)
        assertFalse("the charter is not rewritten", f2.all().any { it.startsWith(Prompts.CHARTER) })
        assertFalse("finished T1 is not written again: ${f2.authored}", f2.authored.values.flatten().contains("T1"))
    }

    @Test fun webApp_isInspected_errorsBecomeFixTickets_thenDelivered() = runBlocking {
        val appCharter = """{"title":"Habit garden","summary":"Water your habits like plants","audience":"students",
 "rubric":[{"name":"Works","desc":"no errors"},{"name":"Complete","desc":"all features"}],
 "files":[{"path":"01_spec.md","purpose":"features"},{"path":"app/index.html","purpose":"app"},{"path":"app/main.js","purpose":"logic"}],
 "milestones":["Spec","Build"],
 "backlog":[{"id":"T1","title":"Spec","file":"01_spec.md","brief":"features"},{"id":"T2","title":"Screen","file":"app/index.html","brief":"screen"},{"id":"T3","title":"Logic","file":"app/main.js","brief":"logic","inputs":["app/index.html"],"after":["T2"]}]}"""
        val f = Firm(gradeOf = { 9.2 }, pmDone = { true }, charterJson = appCharter)
        val runs = AtomicInteger()
        var delivered: Company.Delivery? = null
        val out = company(f, MemoryFiles(), 60, id = "CO4",
            inspect = { if (runs.getAndIncrement() == 0) "Uncaught ReferenceError: water is not defined (main.js:12)" else null },
            deliver = { d -> delivered = d; listOf("habit-garden.zip — 4 KB") }).run()
        assertEquals(JobState.COMPLETED, out.state)
        assertEquals("inspected again after the fix", 2, runs.get())
        assertTrue("a fix ticket targets the failing file", f.all().any { it.contains("Ticket FIX1,") && it.contains("File: app/main.js") && it.contains("water is not defined") })
        assertTrue(delivered!!.webApp)
        assertEquals(Company.REPORT, delivered!!.files.first())
        assertTrue(out.text.contains("habit-garden.zip"))
        assertTrue("web-app rules reach the author", f.all().any { it.contains("offline web app") })
    }

    @Test fun gradeParsing() {
        assertEquals(7.5, Company.parseGrade("""grade {"scores":{"a":7,"b":8},"must":["add a table"]}""")!!.first, 0.01)
        assertEquals(listOf("add a table"), Company.parseGrade("""{"scores":{"a":7,"b":8},"must":["add a table"]}""")!!.second)
        assertEquals(6.0, Company.parseGrade("Overall 6/10.")!!.first, 0.01)
    }
}
