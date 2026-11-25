package com.ht.ensemble

import com.ht.ensemble.aegis.ApprovalGate
import com.ht.ensemble.sim.Simulated
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Regression tests replaying a real failure ("mobile Blender", 2026-11-25): a web client returned
 * the user's own bubble as the answer, the reviewer returned nothing, unreadable grades counted as
 * 8.0, replies stopped mid-sentence, and an unreadable stand-up ended a 60-minute project at 21.
 */
class GateTest {
    // Excerpts of what was actually saved.
    private val echoed = "당신\n\n[회사 프로젝트 · 티켓 S3-1] PM Claude가 너에게 배정했다. 이 결과는 다른 회사 AI가 아래 기준으로 채점한다(8점 미만이면 반려). " +
        "프로젝트 「모바일용 블렌더 제작.」 채점 기준(각 10점): 정확성 … 파일에 들어갈 완성된 내용만 출력(마크다운, 제목부터). 인사·설명·되묻기 금지."
    private val cutMidQuote = "# 02_분석\n\n## 인사이트 4: MVP 기능 컷\n\n" + "근거와 수치가 이어진다. ".repeat(20) + "\n\nAR 미리보기는 추가 엔지니어링이 필요하고, “"
    private val cutTable = "# Ht 3D 제품 기획서\n\n" + "본문 ".repeat(60) + "\n\n## ⑥ 성능 예산\n\n| 항목 | 목표 | 근거 |\n|---|---|---|\n| 목표 FPS | 60fps (중급 기기), 30fps 최소 | 모바일 WebGL 실측에서"

    @Test fun echoes_areCaught_byUserLabel_markers_andPromptOverlap() {
        assertTrue(Gate.echo(echoed))
        assertTrue(Gate.echo("You\n" + "x".repeat(300)))
        val prompt = "[ensemble:ticket] Ticket T2, assigned to you by PM Claude. " + "The deliverable must compare the five mobile sculpting apps on price, platforms and export formats. ".repeat(4)
        assertTrue(Gate.echo("Sure!\n" + prompt.drop(15), prompt))
        assertFalse(Gate.echo("# Mobile sculpting apps\n\nNomad Sculpt leads on export formats; Feather 3D on price.", prompt))
        assertFalse("quoting a short phrase is fine", Gate.echo("The brief asks us to compare the five mobile sculpting apps. Here is the table.", prompt))
        val draft = "# Plan\n\n" + "Nomad Sculpt exports glTF, OBJ and STL; Feather 3D focuses on drawing in space. ".repeat(6)
        val revise = "[ensemble:revise] Goal: compare mobile 3D tools for a phone-first product decision\nThe reviewer asked for:\n- cite prices\nOriginal:\n$draft\nRewrite the complete deliverable with exactly these fixes applied."
        assertFalse("a revision may repeat most of the draft it revises", Gate.echo(draft + "\nPrices: Nomad Sculpt is a one-time purchase.", revise))
    }

    @Test fun truncations_areCaught() {
        assertNotNull(Gate.truncated(cutMidQuote))
        assertNotNull(Gate.truncated(cutTable))
        assertNotNull(Gate.truncated("# Code\n\n```kotlin\nfun main() {\n"))
        assertNotNull(Gate.truncated("fun main() {\n  println(1)\n", "src/Main.kt"))
        assertNull(Gate.truncated("# Done\n\n| a | b |\n|---|---|\n| 1 | 2 |"))
        assertNull(Gate.truncated("# Done\n\nIt ends cleanly. “Quoted.”"))
        assertNull(Gate.truncated("fun main() {\n  println(1)\n}\n", "src/Main.kt"))
    }

    @Test fun check_rejectsTheFourSavedFiles_andAcceptsRealWork() {
        assertFalse(Gate.check(echoed, "01_리서치.md").ok)
        assertTrue(Gate.check(echoed, "01_리서치.md").echo)
        assertFalse(Gate.check(cutMidQuote + " 고친 완성본 전체만 출력. [티켓 S3-2 · 리뷰 반려]", "02_분석.md").ok)
        assertTrue(Gate.check(cutTable, "03_본문.md").truncated)
        assertFalse(Gate.check("# Draft\n\nTBD\n\n[board]\nfact: …", "04.md").ok)
        assertTrue(Gate.check("# Risks\n\n" + "Each risk has a probability, an impact and a mitigation week. ".repeat(5), "04_review.md").ok)
    }

    @Test fun join_dropsRepeatedTail_andFences() {
        assertEquals("The plan ends with the budget table attached.", Gate.join("The plan ends with the budget", "with the budget table attached."))
        assertEquals("fun a() {\n  b()\n}", Gate.join("fun a() {\n", "```kotlin\n  b()\n}\n```", "src/A.kt"))
    }

    // ───────── the company under those failures ─────────

    private val ctx = JobContext("RG", "Plan a mobile 3D modelling app", "2026-11-25 15:00")

    private val charter = """{"title":"Ht 3D","summary":"phone-first 3D tool","audience":"client",
 "rubric":[{"name":"Accuracy","desc":"sourced"},{"name":"Depth","desc":"numbers"}],
 "files":[{"path":"01_research.md","purpose":"evidence"},{"path":"02_analysis.md","purpose":"decisions"},{"path":"03_spec.md","purpose":"spec"}],
 "milestones":["a","b"],
 "backlog":[{"id":"T1","title":"Research","file":"01_research.md","brief":"evidence"},{"id":"T2","title":"Analysis","file":"02_analysis.md","brief":"decisions"},{"id":"T3","title":"Spec","file":"03_spec.md","brief":"spec"}]}"""

    /** Grok echoes, DeepSeek returns nothing, Gemini writes but cuts off once, GPT grades in prose, Claude (PM) answers stand-ups in prose. */
    private inner class Broken : ScriptedCrew(delayMs = 2) {
        val now = AtomicLong(0)
        val cutOnce = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        override fun port(m: CrewMember, lane: Int): LlmPort {
            val inner = super.port(m, lane)
            return object : LlmPort {
                override val name = m.name
                override suspend fun send(prompt: String, jobId: String): LlmReply {
                    val start = now.get()
                    inner.send(prompt, jobId)
                    now.accumulateAndGet(start + 3 * 60_000) { a, b -> maxOf(a, b) }
                    return LlmReply(answer(m, prompt), "fake", 1)
                }
            }
        }

        fun answer(m: CrewMember, p: String): String {
            val kind = Prompts.kindOf(p)
            val file = Regex("""(?m)^File:\s*(\S+)""").find(p)?.groupValues?.get(1) ?: "x.md"
            return when {
                kind == Prompts.CHARTER -> charter
                kind == Prompts.STANDUP -> "Good progress overall; let's keep improving the weaker parts next sprint."
                m.name == "Grok" && kind in setOf(Prompts.TICKET, Prompts.REWORK) -> "당신\n\n$p"
                m.name == "DeepSeek" -> ""
                kind == Prompts.GRADE && m.name == "ChatGPT" -> "Solid work, though section two needs more sources."
                kind == Prompts.GRADE -> """{"scores":{"Accuracy":7.2,"Depth":7.6},"must":["cite the benchmark"],"issues":[]}"""
                kind == Prompts.FINAL_QA -> """{"scores":{"Accuracy":7.9,"Depth":8.1},"must":[],"issues":[]}"""
                kind == Prompts.CONTINUE -> "conservative figure.” The rest of the section follows with sources.\n\nEnd of file."
                kind in setOf(Prompts.TICKET, Prompts.REWORK) && cutOnce.add(file) ->
                    "# $file\n\n" + "Evidence with numbers and sources. ".repeat(15) + "\n\nThe benchmark quotes the “"
                kind in setOf(Prompts.TICKET, Prompts.REWORK) -> "# $file\n\n" + "${m.name}: evidence with numbers and sources. ".repeat(15)
                kind == Prompts.REPORT -> "# Report\n\nSummary of the project with every file explained."
                else -> null ?: "ok"
            }
        }
    }

    @Test fun company_underTheRealFailure_savesNoEcho_scoresNothingFake_usesTheBudget() = runBlocking {
        val crew = Broken()
        val files = MemoryFiles()
        val saved = StringBuilder()
        val events = ArrayList<AgentEvent>()
        val out = Company(ctx, crew, FakeTools(), Rejecter, ApprovalGate(), JobControl(), { synchronized(events) { events += it } }, StageHub(), { crew.now.get() },
            minutes = 60, files = files, save = { saved.setLength(0); saved.append(it) }).run()
        assertEquals(out.text, JobState.COMPLETED, out.state)
        for (p in files.list()) {
            val text = files.read(p)!!
            assertFalse("$p holds an instruction", Gate.echo(text))
            assertNull("$p is cut off: ${text.takeLast(60)}", Gate.truncated(text, p))
        }
        assertTrue("cut-off replies were continued, not saved", crew.all().count { it.startsWith(Prompts.CONTINUE) && it.contains("The benchmark quotes the “") } >= 3)
        val tickets = JSONObject(saved.toString()).getJSONArray("tickets")
        for (i in 0 until tickets.length()) {
            val t = tickets.getJSONObject(i)
            if (t.getString("state") == "DONE") {
                assertFalse("echoing members never own a saved file", t.getString("who") == "Grok")
                assertTrue("a saved score is a real grade or flagged ungraded, never a silent 8.0", t.getBoolean("ungraded") || t.getDouble("score") == 7.4)
            }
        }
        assertTrue("the echo was caught and handed over", events.any { it is AgentEvent.Collab && (it.text.contains(CrewDesk.ECHOED) || it.text.contains("repeated the instruction")) })
        assertTrue("unreadable stand-ups no longer end the project (${crew.now.get() / 60_000} min of 60)", crew.now.get() >= 40 * 60_000L)
        assertTrue("the work log names the echo", files.read(Company.REPORT)!!.contains(CrewDesk.ECHOED))
    }

    @Test fun simulatedTeam_withEchoesCutsAndUnreadableGrades_stillDeliversCleanFiles() = runTest {
        val bad = Simulated.Profile(latencyMs = 30_000L..90_000L, echoRate = 0.3, cutRate = 0.4, unreadableGradeRate = 0.3)
        val team = Ensemble.team {
            listOf(Vendor.CLAUDE, Vendor.GPT, Vendor.GEMINI, Vendor.GROK, Vendor.DEEPSEEK).forEach { member(it, Simulated.port(it, bad)) }
            clock = { testScheduler.currentTime }
        }
        val r = team.project("A launch plan for a pottery studio", minutes = 60)
        assertTrue(r.text, r.ok)
        for (p in r.files.list()) assertTrue("$p: ${Gate.check(r.files.read(p)!!, p).reasons}", Gate.check(r.files.read(p)!!, p).let { it.ok || p == Company.REPORT })
    }
}
