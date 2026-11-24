package com.ht.ensemble.ports

import com.ht.ensemble.LlmPort
import com.ht.ensemble.LlmReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ready-made model connections. Plain `HttpURLConnection` + `org.json`, so they run on servers,
 * desktops and Android alike, with no HTTP client to pick.
 *
 * ```
 * Ports.anthropic(key)            Ports.openAi(key)              Ports.gemini(key)
 * Ports.deepSeek(key)             Ports.grok(key)                Ports.openRouter(key, "meta-llama/llama-4")
 * Ports.ollama("qwen3")           Ports.fn("my model") { prompt -> myModel(prompt) }
 * ```
 *
 * Failures never throw: they return an empty reply and a reason in `via` (`HTTP429:…`, `ERR:…`),
 * which the desk turns into a hand-over. A 429 is recognised as a usage limit and benches the member.
 * Default model ids are only defaults — pass the one you use.
 */
object Ports {
    /** Any OpenAI-compatible `/chat/completions`: OpenAI, DeepSeek, xAI, Gemini, OpenRouter, Ollama, vLLM, LM Studio… */
    fun openAiCompatible(name: String, baseUrl: String, apiKey: String?, model: String, temperature: Double? = null,
                         maxTokens: Int? = null, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 600_000): LlmPort =
        HttpPort(name, timeoutMs) { prompt ->
            val body = JSONObject().put("model", model).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            temperature?.let { body.put("temperature", it) }
            maxTokens?.let { body.put("max_tokens", it) }
            val auth = if (apiKey.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")
            Req(baseUrl.trimEnd('/') + "/chat/completions", headers + auth, body) { o ->
                o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            }
        }

    fun openAi(apiKey: String, model: String = "gpt-5") = openAiCompatible("ChatGPT", "https://api.openai.com/v1", apiKey, model)
    fun deepSeek(apiKey: String, model: String = "deepseek-chat") = openAiCompatible("DeepSeek", "https://api.deepseek.com", apiKey, model)
    fun grok(apiKey: String, model: String = "grok-4") = openAiCompatible("Grok", "https://api.x.ai/v1", apiKey, model)
    fun gemini(apiKey: String, model: String = "gemini-2.5-pro") = openAiCompatible("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", apiKey, model)
    fun openRouter(apiKey: String, model: String) = openAiCompatible(model.substringBefore('/').ifBlank { "OpenRouter" }, "https://openrouter.ai/api/v1", apiKey, model)
    fun ollama(model: String, baseUrl: String = "http://localhost:11434/v1") = openAiCompatible("Ollama $model", baseUrl, null, model)

    /** Anthropic Messages API. */
    fun anthropic(apiKey: String, model: String = "claude-sonnet-4-5", maxTokens: Int = 8192, baseUrl: String = "https://api.anthropic.com"): LlmPort =
        HttpPort("Claude", 600_000) { prompt ->
            val body = JSONObject().put("model", model).put("max_tokens", maxTokens)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            Req(baseUrl.trimEnd('/') + "/v1/messages", mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"), body) { o ->
                val c = o.optJSONArray("content") ?: JSONArray()
                (0 until c.length()).mapNotNull { c.optJSONObject(it)?.takeIf { b -> b.optString("type") == "text" }?.optString("text") }.joinToString("")
            }
        }

    /** Wrap any function: a local model, a company gateway, a test double. Exceptions become `ERR:` replies. */
    fun fn(name: String, block: suspend (String) -> String): LlmPort = object : LlmPort {
        override val name = name
        override suspend fun send(prompt: String, jobId: String): LlmReply {
            val t0 = System.currentTimeMillis()
            return try {
                LlmReply(block(prompt), "FN", System.currentTimeMillis() - t0)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                LlmReply("", "ERR:${e.message}", System.currentTimeMillis() - t0)
            }
        }
    }

    internal class Req(val url: String, val headers: Map<String, String>, val body: JSONObject, val pick: (JSONObject) -> String)

    internal class HttpPort(override val name: String, private val timeoutMs: Int, private val build: (String) -> Req) : LlmPort {
        override suspend fun send(prompt: String, jobId: String): LlmReply = withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val r = build(prompt)
            val c = URL(r.url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.connectTimeout = 30_000; c.readTimeout = timeoutMs; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                r.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.outputStream.use { it.write(r.body.toString().toByteArray()) }
                val code = c.responseCode
                val txt = ((if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }).orEmpty()
                if (code !in 200..299) return@withContext LlmReply("", "HTTP$code:" + txt.take(160), System.currentTimeMillis() - t0)
                LlmReply(runCatching { r.pick(JSONObject(txt)) }.getOrDefault("").trim(), "HTTP$code", System.currentTimeMillis() - t0)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                LlmReply("", "ERR:${e.message}", System.currentTimeMillis() - t0)
            } finally { c.disconnect() }
        }
    }
}
