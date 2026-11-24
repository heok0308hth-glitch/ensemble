package com.ht.ensemble

import com.ht.ensemble.aegis.ApprovalGate
import com.ht.ensemble.aegis.Canonical
import com.ht.ensemble.aegis.NormalizedAction
import com.ht.ensemble.aegis.Policy
import com.ht.ensemble.aegis.PolicyDecision
import com.ht.ensemble.aegis.PolicyInput
import com.ht.ensemble.tools.CommandCall
import com.ht.ensemble.tools.CommandExec
import com.ht.ensemble.tools.CommandResult
import com.ht.ensemble.tools.Effect
import com.ht.ensemble.tools.Param
import com.ht.ensemble.tools.Toolbox
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartsTest {
    @Test fun language_detection() {
        assertEquals(Language.KOREAN, Language.detect("카페 창업 사업계획서를 만들어줘"))
        assertEquals(Language.JAPANESE, Language.detect("東京のカフェ開業計画を作って"))
        assertEquals(Language.CHINESE, Language.detect("帮我写一份咖啡店的商业计划"))
        assertEquals(Language.SPANISH, Language.detect("Escribe un plan de negocio para una cafetería en la ciudad"))
        assertEquals(Language.GERMAN, Language.detect("Schreibe einen Businessplan für ein Café und die Finanzen"))
        assertEquals(Language.ENGLISH, Language.detect("Write a business plan for a café"))
        assertEquals(Language.ENGLISH, Language.detect("Kotlin coroutine guide for SwiftUI devs"))
        assertEquals(Language.KOREAN, Language.resolve(Language.AUTO, "React 대신 Compose를 쓰는 이유"))
        assertEquals(Language.FRENCH, Language.resolve(Language.FRENCH, "anything"))
    }

    @Test fun board_split_englishAndKorean_digestOrder() {
        val (body, notes) = Board.split("The body.\n\n[board]\nfact: deadline is Dec 12 (notice)\ndecision: poster category\nquestion: none")
        assertEquals("The body.", body)
        assertEquals(listOf(NoteKind.FACT, NoteKind.DECISION), notes.map { it.first })
        val (body2, notes2) = Board.split("본문입니다.\n\n[칠판]\n사실: 마감은 12월 12일\n주의: 상금 오기")
        assertEquals("본문입니다.", body2)
        assertEquals(listOf(NoteKind.FACT, NoteKind.WARNING), notes2.map { it.first })
        assertEquals("No board here." to emptyList<Pair<NoteKind, String>>(), Board.split("No board here."))
        val b = Board()
        b.add("Gemini", NoteKind.FACT, "deadline 12/12"); b.add("ChatGPT", NoteKind.WARNING, "prize amount is wrong"); b.add("Gemini", NoteKind.FACT, "deadline 12/12")
        assertEquals(2, b.all().size)
        assertTrue(b.digest().lineSequence().first().startsWith("- warning"))
    }

    @Test fun dispatch_spreadsLoad_andRespectsCenterAndLanes() {
        val rate = { m: CrewMember, s: Skill -> m.vendor.score(s).toDouble() }
        val tasks = List(8) { "write chapter $it" }
        val who = Dispatch.assign(tasks, FIVE, FIVE[0], rate, { Skill.WRITE }, { "Claude" })
        val counts = who.groupingBy { it!!.name }.eachCount()
        assertFalse("center excluded with ≥3 members: $counts", "Claude" in counts)
        assertTrue("nobody hoards: $counts", counts.values.max() <= 3)
        assertTrue("DeepSeek has two lanes, so it may take more: $counts", (counts["DeepSeek"] ?: 0) >= 1)
        val two = Dispatch.assign(tasks.take(2), FIVE.take(2), FIVE[0], rate, { Skill.WRITE })
        assertTrue("with two members the center works too", two.any { it!!.name == "Claude" })
        assertNull("tool items are skipped", Dispatch.assign(listOf("x"), FIVE, null, rate, { Skill.WRITE }, skip = { true }).single())
    }

    @Test fun skill_guess_isBilingual() {
        assertEquals(Skill.RESEARCH, Skill.of("Research the latest prices"))
        assertEquals(Skill.CODE, Skill.of("Fix the bug in main.kt"))
        assertEquals(Skill.RESEARCH, Skill.of("최신 가격 조사"))
        assertEquals(Skill.WRITE, Skill.of("A warm letter to the team"))
    }

    @Test fun scoreboard_benchesOnLimit_andBendsPriors() {
        var now = 0L
        val s = Scoreboard(restMs = 1000) { now }
        s.record("a", false, 100, "You've reached your usage limit", "HTTP429")
        assertTrue(s.resting("a"))
        now = 1001
        assertFalse(s.resting("a"))
        s.record("b", true, 100, "ok", "")
        s.record("b", false, 100, "", "ERR")
        assertEquals(9.0 * 0.8, s.adjust(9.0, "b"), 1e-9)
        assertTrue(Vendor.limitHit("Rate limit reached, try again later"))
        assertFalse("long answers that mention quota are not limits", Vendor.limitHit("quota ".repeat(80)))
    }

    @Test fun jsonPick_and_codeFiles() {
        assertEquals("x } y {", JsonPick.obj("noise {junk ```json\n{\"a\":\"x } y {\",\"b\":[1,2]}\n``` tail")!!.getString("a"))
        val files = CodeFiles.extract("Intro\n\n**app/main.py**\n```python\na = 1\nb = 2\nprint(a + b)\n```\n\n```js\n// file: web/app.js\nlet x = 1\nconsole.log(x)\n```")
        assertEquals(listOf("app/main.py", "web/app.js"), files.map { it.first })
        assertEquals("src/app/main.py", ProjectFiles.clean("/../src/./app/main.py"))
        assertEquals("a_b.md", ProjectFiles.clean("a:b.md"))
    }

    @Test fun aegis_tokenIsBoundToTheExactAction() {
        val gate = ApprovalGate()
        val a = NormalizedAction("mail.send", mapOf("to" to "kim@ex.com", "body" to "hi"))
        val t = gate.issue(Canonical.digest(a), 0)
        assertEquals(ApprovalGate.Verdict.Bad("E_DIGEST"), gate.verifyAndConsume(t, a.copy(params = mapOf("to" to "evil@ex.com", "body" to "hi")), 1))
        assertEquals(ApprovalGate.Verdict.Ok, gate.verifyAndConsume(t, a, 1))
        assertEquals(ApprovalGate.Verdict.Bad("E_REPLAY"), gate.verifyAndConsume(t, a, 2))
        val late = gate.issue(Canonical.digest(a), 0)
        assertEquals(ApprovalGate.Verdict.Bad("E_EXPIRED"), gate.verifyAndConsume(late, a, 11 * 60_000))
        assertEquals("""{"a":"\n","b":[1,true,null]}""", Canonical.of(org.json.JSONObject("""{"b":[1,true,null],"a":"\n"}""")))
        assertTrue(Policy.decide(PolicyInput(Effect.MONEY)) is PolicyDecision.Deny)
        assertNotNull((Policy.decide(PolicyInput(Effect.EXTERNAL, recipientKnown = false, bodyHasLink = true)) as PolicyDecision.Ask).warning)
        assertTrue(Policy.decide(PolicyInput(Effect.READ)) is PolicyDecision.Auto)
    }

    @Test fun toolbox_runsRead_andGatesExternal() = runBlocking {
        val sent = ArrayList<String>()
        val box = Toolbox {
            tool("calc.add", "Add numbers", Effect.READ, listOf(Param("a", "a"), Param("b", "b"))) { c -> CommandResult.ok("${c.args["a"]!!.toInt() + c.args["b"]!!.toInt()}") }
            tool("mail.send", "Send mail", Effect.EXTERNAL, listOf(Param("to", "to"), Param("body", "body"))) { c -> sent += c.args["to"]!!; CommandResult.ok("sent") }
        }
        assertTrue(box.catalog().contains("mail.send(to,body) Send mail [needs approval]"))
        val events = ArrayList<AgentEvent>()
        val exec = CommandExec("J", box, Rejecter, ApprovalGate(), { events += it }, { 0 })
        assertEquals("5", exec.run(CommandCall("1", "calc.add", mapOf("a" to "2", "b" to "3"))).text)
        assertFalse(exec.run(CommandCall("2", "calc.add", mapOf("a" to "2"))).ok)
        val mail = exec.run(CommandCall("3", "mail.send", mapOf("to" to "kim@ex.com", "body" to "hi")))
        assertFalse("rejected by the human", mail.ok)
        assertTrue("never sent", sent.isEmpty())
        assertTrue(events.any { it is AgentEvent.NeedApproval })
    }
}
