package com.ht.ensemble

import com.ht.ensemble.ports.Ports
import com.ht.ensemble.sim.Simulated
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

/** The public API end to end: builder → conduct / project, simulated members, real HTTP ports against a local server. */
class EnsembleTest {
    @Test fun conduct_withSimulatedTeam_underVirtualTime() = runTest {
        val events = ArrayList<AgentEvent>()
        val team = Ensemble.team {
            listOf(Vendor.CLAUDE, Vendor.GPT, Vendor.GEMINI, Vendor.DEEPSEEK).forEach { member(it, Simulated.port(it)) }
            clock = { testScheduler.currentTime }
            onEvent { events += it }
        }
        val r = team.conduct("Compare three onboarding flows for a budgeting app")
        assertTrue(r.text, r.ok)
        assertTrue(r.files.read(Conductor.RESULT)!!.isNotBlank())
        assertTrue(events.first() is AgentEvent.Started)
        assertTrue(events.any { it is AgentEvent.Collab && it.move == Move.DISPATCH })
        assertTrue("everyone but the center worked", r.text.lines().last().let { l -> listOf("ChatGPT", "Gemini", "DeepSeek").all { it in l } })
        assertTrue(r.tokens > 0)
    }

    @Test fun project_withSimulatedTeam_writesFilesAndCheckpoint() = runTest {
        val dir = kotlin.io.path.createTempDirectory("ensemble").toFile()
        val team = Ensemble.team {
            listOf(Vendor.CLAUDE, Vendor.GPT, Vendor.GEMINI, Vendor.GROK).forEach { member(it, Simulated.port(it, Simulated.Profile(latencyMs = 60_000L..180_000L))) }
            clock = { testScheduler.currentTime }
        }
        val r = team.project("A launch plan for a pottery studio", minutes = 90, dir = dir)
        assertTrue(r.text, r.ok)
        assertTrue(File(dir, Company.REPORT).readText().contains("## Work log"))
        assertTrue(File(dir, "01_research.md").exists())
        assertTrue(File(dir, Ensemble.CHECKPOINT).exists())
        assertTrue("checkpoint is hidden from the file list", Ensemble.CHECKPOINT !in r.files.list())
        assertTrue("used a real share of 90 virtual minutes (${testScheduler.currentTime / 60_000})", testScheduler.currentTime >= 45 * 60_000L)
    }

    @Test fun limitedMember_isBenched_andTheTeamFinishes() = runTest {
        val team = Ensemble.team {
            member(Vendor.CLAUDE, Simulated.port(Vendor.CLAUDE))
            member(Vendor.GPT, Simulated.port(Vendor.GPT, Simulated.Profile(limitAfter = 1)))
            member(Vendor.GEMINI, Simulated.port(Vendor.GEMINI))
            member(Vendor.DEEPSEEK, Simulated.port(Vendor.DEEPSEEK))
            clock = { testScheduler.currentTime }
        }
        val r = team.conduct("Summarise the trade-offs of three database engines")
        assertTrue(r.ok)
        val gpt = team.crew.members().first { it.vendor == Vendor.GPT }
        assertTrue("GPT is resting after its limit", !team.crew.available(gpt))
    }

    @Test fun builder_needsAMember_andNamesDuplicates() {
        try { Ensemble.team { }; fail() } catch (_: IllegalArgumentException) { }
        val t = Ensemble.team { member(Vendor.DEEPSEEK, Simulated.port(Vendor.DEEPSEEK)); member(Vendor.DEEPSEEK, Simulated.port(Vendor.DEEPSEEK)) }
        assertEquals(listOf("DeepSeek", "DeepSeek 2"), t.crew.members().map { it.name })
    }

    @Test fun httpPorts_openAiCompatible_anthropic_429_andDown() = runBlocking {
        val seen = ArrayList<String>()
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/v1/chat/completions") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen += ex.requestHeaders.getFirst("Authorization") + " " + JSONObject(body).getString("model")
            val out = """{"choices":[{"message":{"role":"assistant","content":"hello"}}]}""".toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        srv.createContext("/v1/messages") { ex ->
            seen += ex.requestHeaders.getFirst("x-api-key") + " " + ex.requestHeaders.getFirst("anthropic-version")
            val out = """{"content":[{"type":"text","text":"Hi, "},{"type":"text","text":"Claude here"}]}""".toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        srv.createContext("/limited/chat/completions") { ex ->
            val out = """{"error":{"message":"Rate limit reached"}}""".toByteArray()
            ex.sendResponseHeaders(429, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        srv.start()
        try {
            val base = "http://127.0.0.1:${srv.address.port}"
            val a = Ports.openAiCompatible("Local", "$base/v1", "sk-test", "my-model").send("hi", "j")
            assertEquals("hello", a.text); assertEquals("Bearer sk-test my-model", seen[0])
            val b = Ports.anthropic("ak-test", baseUrl = base).send("hi", "j")
            assertEquals("Hi, Claude here", b.text); assertEquals("ak-test 2023-06-01", seen[1])
            val c = Ports.openAiCompatible("Busy", "$base/limited", "k", "m").send("hi", "j")
            assertEquals("", c.text); assertTrue(c.via.startsWith("HTTP429")); assertTrue(Vendor.limitHit(c.text, c.via))
            val d = Ports.openAiCompatible("Down", "http://127.0.0.1:1", null, "m").send("hi", "j")
            assertTrue(d.via.startsWith("ERR"))
        } finally { srv.stop(0) }
    }

    @Test fun approval_flowsThroughTheBuilder_andIsBoundToTheAction() = runBlocking {
        val asked = AtomicLong()
        val sent = ArrayList<String>()
        val plan = """{"deliverable":"note","criteria":["sent"],"tasks":[{"id":"t1","title":"Email the summary","tool":"mail.send","args":{"to":"me@ex.com","body":"Summary attached"}},
            {"id":"t2","title":"Write the summary","prompt":"write"}],"final":{"how":"merge"}}"""
        val port = Ports.fn("Claude") { p -> when (Prompts.kindOf(p)) {
            Prompts.PLAN -> plan; Prompts.REVIEW -> """{"pass":true}"""; Prompts.GAP -> """{"more":[]}"""
            else -> "A finished summary with enough words to count as real work."
        } }
        val team = Ensemble.team {
            member(Vendor.CLAUDE, port)
            tools(com.ht.ensemble.tools.Toolbox {
                tool("mail.send", "Send mail", com.ht.ensemble.tools.Effect.EXTERNAL,
                    listOf(com.ht.ensemble.tools.Param("to", "to"), com.ht.ensemble.tools.Param("body", "body"))) { c ->
                    sent += c.args["to"]!!; com.ht.ensemble.tools.CommandResult.ok("Mail sent to ${c.args["to"]} with the summary attached.")
                }
            })
            approve { req -> asked.incrementAndGet(); req.call.args["to"] == "me@ex.com" }
        }
        val r = team.conduct("Write a summary and email it to me")
        assertTrue(r.text, r.ok)
        assertEquals("the human was asked once", 1L, asked.get())
        assertEquals(listOf("me@ex.com"), sent)
    }
}
