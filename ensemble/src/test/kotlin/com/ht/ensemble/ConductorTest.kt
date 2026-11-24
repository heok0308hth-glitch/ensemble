package com.ht.ensemble

import com.ht.ensemble.aegis.ApprovalGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConductorTest {
    private val ctx = JobContext("J", "Compare the design competitions closing in December and draft a prep plan", "2026-11-24 09:00")

    private fun conductor(crew: Crew, id: String, hub: StageHub, control: JobControl = JobControl(), tools: ToolPort = FakeTools(),
                          catalog: String = "", files: ProjectFiles = MemoryFiles(), difficulty: Difficulty = Difficulty.STANDARD, ev: MutableList<AgentEvent> = ArrayList()) =
        Conductor(ctx.copy(jobId = id), crew, tools, Rejecter, ApprovalGate(), control, { synchronized(ev) { ev += it } }, hub, difficulty = difficulty, catalog = catalog, files = files)

    private val plan = """Here is the plan.
```json
{"deliverable":"Competition table and prep plan","criteria":["December deadlines","Entry fees checked"],"tasks":[
 {"id":"t1","title":"Find competitions","who":"Gemini","tool":"web.search","args":{"q":"design competition December 2026 deadline"}},
 {"id":"t2","title":"Analyse rules {important}","who":"ChatGPT","prompt":"Summarise eligibility and deliverables"},
 {"id":"t3","title":"Comparison table","who":"DeepSeek","prompt":"Build the table from t1 and t2","after":["t1","t2"]}],
 "final":{"who":"Claude","how":"table, then schedule"}}
```"""

    @Test fun centerPlans_runsInParallel_swapsOnLimit_researchesAroundBlockedTool() = runBlocking {
        val crew = ScriptedCrew(FIVE.take(4), delayMs = 120) { m, kind, _ ->
            when {
                m.name == "ChatGPT" -> "You've reached your usage limit. Try again in 3 hours."
                kind == Prompts.PLAN -> plan
                kind == Prompts.COMPOSE -> "# Competition table and prep plan\n\n| Competition | Deadline |\n|---|---|\n| Seoul Public Design | 12-12 |\n\nWeek 1 research → week 2 drafts → week 3 submit."
                else -> null
            }
        }
        val hub = StageHub()
        val tools = FakeTools()
        val out = conductor(crew, "J1", hub, tools = tools, catalog = "web.search(q) search the web").run()
        assertEquals(JobState.COMPLETED, out.state)
        assertTrue(out.text.contains("Competition table"))
        assertTrue("a member that hits its limit is benched", "G" in crew.benched)
        val st = hub["J1"]!!
        assertNotEquals("the limited member's task moves to someone else", "ChatGPT", st.cards.first { it.id == "t2" }.who)
        assertEquals("blocked search → a member researches directly", CardStatus.DONE, st.cards.first { it.id == "t1" }.status)
        assertTrue(tools.ran.contains("web.search"))
        assertTrue("t1 and t2 run at the same time (peak ${crew.peak.get()})", crew.peak.get() >= 2)
        assertEquals("the center is the best planner/reviewer", "Claude", st.center)
    }

    @Test fun dispatch_overrulesBiasedPlan_centerOnlyConducts_boardFlows() = runBlocking {
        val crew = ScriptedCrew()
        val hub = StageHub()
        val files = MemoryFiles()
        val out = conductor(crew, "J2", hub, files = files).run()
        assertEquals(JobState.COMPLETED, out.state)
        val doers = hub["J2"]!!.cards.filter { it.id.startsWith("t") }.map { it.who }
        assertFalse("the center takes no task: $doers", "Claude" in doers)
        assertTrue("at least three members share the work: $doers", doers.distinct().size >= 3)
        assertTrue("board notes accumulate", hub["J2"]!!.board.isNotEmpty())
        val t4 = crew.all().first { it.contains("Your task: Write the body") }
        assertTrue("later teammates read the board", t4.contains("[Team board"))
        assertFalse("board blocks are stripped from the deliverable", out.text.contains("[board]"))
        assertNotNull("the result is saved", files.read(Conductor.RESULT))
    }

    @Test fun unreadablePlan_fallsBackToPlaybook() = runBlocking {
        val crew = ScriptedCrew(FIVE.take(3)) { m, kind, _ ->
            if (kind == Prompts.REVIEW) "Looks fine to me." else "${m.name}: prose instead of JSON. Seoul Public Design closes 12-12; three weeks of prep is enough."
        }
        val hub = StageHub()
        val out = conductor(crew, "J3", hub).run()
        assertEquals(JobState.COMPLETED, out.state)
        assertTrue(hub["J3"]!!.cards.size >= 3)
    }

    @Test fun pause_sendsNothingNew_untilResume() = runBlocking {
        val control = JobControl()
        val crew = ScriptedCrew(delayMs = 150)
        val hub = StageHub()
        val job = launch(Dispatchers.Default) { conductor(crew, "J4", hub, control).run() }
        while (crew.all().none { it.startsWith(Prompts.TASK) }) delay(10)
        control.pause()
        delay(250)                                  // let in-flight replies land
        val atPause = crew.sends.get()
        delay(600)
        val duringPause = crew.sends.get() - atPause
        control.resume()
        job.join()
        assertEquals("no new requests while paused", 0, duringPause)
        assertEquals(Phase.DONE, hub["J4"]!!.phase)
    }

    @Test fun abort_cancelsInFlight_immediately() = runBlocking {
        val control = JobControl()
        val slow = ScriptedCrew(delayMs = 5_000)
        val fast = ScriptedCrew(delayMs = 30)
        val crew = object : Crew by fast {
            override fun port(m: CrewMember, lane: Int): LlmPort = if (fast.sends.get() == 0) fast.port(m, lane) else slow.port(m, lane)
        }
        val t0 = System.currentTimeMillis()
        var outcome: Outcome? = null
        val job = launch(Dispatchers.Default) { outcome = conductor(crew, "J5", StageHub(), control).run() }
        while (slow.sends.get() < 2) delay(10)
        val before = slow.sends.get()
        control.abort()
        job.join()
        assertEquals(JobState.ABORTED, outcome!!.state)
        assertTrue("abort is immediate (${System.currentTimeMillis() - t0} ms)", System.currentTimeMillis() - t0 < 3_000)
        assertTrue("in-flight requests are cancelled", slow.cancelled.get() >= 2)
        assertEquals("nothing new after abort", before, slow.sends.get())
    }

    @Test fun crossCheck_pinsWarning_andComposerSeesIt() = runBlocking {
        val crew = ScriptedCrew { _, kind, _ -> if (kind == Prompts.CHECK) "The 2024 figure contradicts the table in section 2." else null }
        val hub = StageHub()
        conductor(crew, "J6", hub).run()
        val compose = crew.all().first { it.startsWith(Prompts.COMPOSE) }
        assertTrue("warnings reach the composer", compose.contains("warning (") && compose.contains("contradicts"))
    }

    @Test fun codeBlocks_becomeFiles() = runBlocking {
        val crew = ScriptedCrew { _, kind, _ ->
            if (kind == Prompts.COMPOSE) "# Tiny tool\n\n**src/main.py**\n```python\nimport sys\n\nprint(sys.argv)\n```\n" else null
        }
        val files = MemoryFiles()
        conductor(crew, "J7", StageHub(), files = files).run()
        assertEquals("import sys\n\nprint(sys.argv)\n", files.read("src/main.py"))
    }

    @Test fun prompts_areEnglish_andCarryTheOutputLanguage() = runBlocking {
        val crew = ScriptedCrew()
        Conductor(ctx.copy(jobId = "J8", goal = "카페 창업 체크리스트를 만들어줘", language = Language.KOREAN), crew, FakeTools(), Rejecter, ApprovalGate(), JobControl(), {}).run()
        val task = crew.all().first { it.startsWith(Prompts.TASK) }
        assertTrue(task.contains("Write the deliverable in Korean."))
        assertTrue(task.contains("Your task:"))
    }
}
