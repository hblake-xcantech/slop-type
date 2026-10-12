package dev.sloptype.app.remote

/**
 * Per-1M-token list prices used to meter tier-2 spend against the monthly cap.
 * Source: docs/TIER2_COMPARISON.md (researched 2026-10-12). Update [LAST_UPDATED] when editing.
 */
object Pricing {
    const val LAST_UPDATED = "2026-10-12"

    data class Price(val inputPerM: Double, val outputPerM: Double)

    /** keyed by model id (lower-case). Decision-style endpoints charge input only. */
    val table: Map<String, Price> = mapOf(
        // TypeSafe AI System One (classification only, output free)
        "jev-1.13.0" to Price(0.042, 0.0),
        "jev-latest" to Price(0.042, 0.0),
        "jev-preview" to Price(0.042, 0.0),
        // OpenAI Luna tier (Responses; Decisions API charges the same input rate and no output)
        "gpt-6-luna" to Price(0.10, 0.50),
        "gpt-5.6-luna" to Price(0.20, 1.20),
        "gpt-5-nano" to Price(0.05, 0.40),
    )

    val fallback = Price(0.10, 0.50)

    fun price(model: String): Price = table[model.lowercase()] ?: fallback

    /** `service_tier: "fast"` (formerly "priority") costs 2x list. */
    fun multiplier(serviceTier: String?): Double = when (serviceTier?.lowercase()) {
        "fast", "priority", "ultrafast" -> 2.0
        else -> 1.0
    }

    fun cost(model: String, inputTokens: Long, outputTokens: Long, serviceTier: String? = null): Double {
        val p = price(model)
        return (inputTokens * p.inputPerM + outputTokens * p.outputPerM) / 1_000_000.0 * multiplier(serviceTier)
    }

    /** Rough token estimate when the API does not report usage: ~4 chars per token. */
    fun estimateTokens(text: String): Long = ((text.length + 3) / 4).toLong()
}
