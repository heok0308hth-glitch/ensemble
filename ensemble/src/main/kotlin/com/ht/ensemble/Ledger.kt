package com.ht.ensemble

import java.util.concurrent.ConcurrentHashMap

/** Token estimate without a tokenizer: ~4 chars per token for Latin text, ~1.6 for Hangul/CJK. */
object Tokens {
    fun estimate(t: String): Int {
        var dense = 0
        var other = 0
        for (c in t) if (c in '가'..'힣' || c in 'ㄱ'..'ㆎ' || c in '一'..'鿿' || c in '぀'..'ヿ') dense++ else other++
        return (dense / 1.6 + other / 4.0).toInt() + 1
    }

    fun fmt(n: Int): String = if (n >= 1000) String.format(java.util.Locale.US, "%.1fk", n / 1000.0) else n.toString()
}

/** Who spent how many tokens in one job. */
class TokenLedger {
    data class Line(val input: Int, val output: Int, val calls: Int)

    private val by = LinkedHashMap<String, Line>()

    @Synchronized fun add(who: String, input: Int, output: Int) {
        val l = by[who] ?: Line(0, 0, 0)
        by[who] = Line(l.input + input, l.output + output, l.calls + 1)
    }
    @Synchronized fun total(): Int = by.values.sumOf { it.input + it.output }
    @Synchronized fun snapshot(): Map<String, Line> = LinkedHashMap(by)
}

/**
 * Live performance per member. Success rate and latency bend the vendor priors; a usage-limit
 * notice or an auth failure benches the member for [restMs] so the team routes around it.
 */
class Scoreboard(private val restMs: Long = 15 * 60_000L, private val now: () -> Long = System::currentTimeMillis) {
    private class S(var ok: Int = 0, var fail: Int = 0, var avgMs: Double = 0.0, var restUntil: Long = 0)

    private val m = ConcurrentHashMap<String, S>()

    fun record(key: String, ok: Boolean, ms: Long, text: String, via: String) {
        val s = m.getOrPut(key) { S() }
        synchronized(s) {
            if (ok) s.ok++ else s.fail++
            if (ms > 0) s.avgMs = if (s.avgMs == 0.0) ms.toDouble() else s.avgMs * 0.7 + ms * 0.3
            val benched = Vendor.limitHit(text, via) || AUTH.any { via.contains(it) }
            if (!ok && benched) s.restUntil = now() + restMs
        }
    }

    fun resting(key: String): Boolean = (m[key]?.restUntil ?: 0) > now()

    /** Prior × (0.6 + 0.4 × success rate), minus one point for members slower than three minutes. */
    fun adjust(base: Double, key: String): Double {
        val s = m[key] ?: return base
        val n = s.ok + s.fail
        val rate = if (n == 0) 1.0 else s.ok.toDouble() / n
        return base * (0.6 + 0.4 * rate) - (if (s.avgMs > 180_000) 1.0 else 0.0)
    }

    private companion object {
        val AUTH = listOf("LOGIN", "CHALLENGE", "HTTP401", "HTTP403")
    }
}
