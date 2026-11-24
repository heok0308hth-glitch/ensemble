package com.ht.ensemble.aegis

import com.ht.ensemble.tools.CommandSpec
import com.ht.ensemble.tools.Effect
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * AEGIS — anything that leaves the machine needs a human "yes", and that yes is bound to the
 * exact action the human saw. The action is canonicalized (RFC 8785 subset), hashed, and the
 * hash is sealed into a one-time HMAC token. Right before execution the action is normalized
 * again; if a single byte differs — a swapped recipient, an edited body — it does not run.
 */

data class NormalizedAction(val tool: String, val params: Map<String, String>, val account: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("tool", tool).put("params", JSONObject(params.toSortedMap() as Map<*, *>))
        .apply { account?.let { put("account", it) } }
}

/** Canonical JSON (the string/object subset of RFC 8785 JCS): sorted keys, no whitespace, minimal escapes. */
object Canonical {
    fun of(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { k -> str(k) + ":" + of(v.opt(k)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { of(v.opt(it)) }
        is String -> str(v)
        is Boolean -> v.toString()
        is Number -> v.toDouble().let { d -> if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString() }
        else -> str(v.toString())
    }

    fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c == '\b' -> sb.append("\\b")
            c == '\u000C' -> sb.append("\\f")
            c.code < 0x20 -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    fun digest(a: NormalizedAction): String = sha256(of(a.toJson()))
    fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** The approval text is never written by a model: it is templated from the normalized action. */
object ApprovalSummaries {
    fun describe(spec: CommandSpec?, a: NormalizedAction): String {
        val p = a.params
        fun v(k: String) = p[k]?.let { if (it.length > 140) it.take(140) + "…" else it } ?: "-"
        return when (a.tool) {
            "mail.send" -> "Send an email from ${a.account ?: "the default account"} to ${v("to")}.\nSubject: ${v("subject")}\nBody: ${p["body"]?.length ?: 0} chars"
            "file.delete" -> "Delete '${v("path")}'. This cannot be undone."
            else -> (spec?.title ?: a.tool) + "\n" + p.entries.joinToString("\n") { "${it.key}: ${if (it.value.length > 80) it.value.take(80) + "…" else it.value}" }
        }
    }
}

data class PolicyInput(val effect: Effect, val recipientKnown: Boolean = true, val bodyHasLink: Boolean = false)

sealed interface PolicyDecision {
    data class Auto(val reason: String) : PolicyDecision
    data class Ask(val reason: String, val warning: String? = null) : PolicyDecision
    data class Deny(val reason: String) : PolicyDecision
}

/** A pure function; more specific risks are checked first. */
object Policy {
    fun decide(i: PolicyInput): PolicyDecision = when {
        i.effect == Effect.MONEY -> PolicyDecision.Deny("payments are disabled")
        i.effect.level >= 2 && !i.recipientKnown && i.bodyHasLink ->
            PolicyDecision.Ask("new recipient + link", "First message to this address and it contains a link. Check for phishing or prompt injection.")
        i.effect.level >= 2 && !i.recipientKnown -> PolicyDecision.Ask("new recipient", "First message to this address.")
        i.effect == Effect.DESTRUCTIVE -> PolicyDecision.Ask("irreversible", "This cannot be undone.")
        i.effect.level >= 2 -> PolicyDecision.Ask("leaves the machine")
        else -> PolicyDecision.Auto("read-only or local")
    }
}

/** One-time approval tokens: HMAC over (nonce, digest, expiry); replay and tampering are rejected. */
class ApprovalGate(private val key: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }, private val ttlMs: Long = 10 * 60_000) {
    data class Token(val jti: String, val digest: String, val exp: Long, val sig: String)
    sealed interface Verdict { data object Ok : Verdict; data class Bad(val code: String) : Verdict }

    private val used = HashSet<String>()

    fun issue(digest: String, now: Long): Token {
        val jti = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val exp = now + ttlMs
        return Token(jti, digest, exp, sign("$jti|$digest|$exp"))
    }

    @Synchronized
    fun verifyAndConsume(t: Token, action: NormalizedAction, now: Long): Verdict {
        if (sign("${t.jti}|${t.digest}|${t.exp}") != t.sig) return Verdict.Bad("E_SIGNATURE")
        if (now > t.exp) return Verdict.Bad("E_EXPIRED")
        if (t.jti in used) return Verdict.Bad("E_REPLAY")
        if (Canonical.digest(action) != t.digest) return Verdict.Bad("E_DIGEST")
        used += t.jti
        return Verdict.Ok
    }

    private fun sign(s: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
