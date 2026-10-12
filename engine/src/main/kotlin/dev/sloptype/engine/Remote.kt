package dev.sloptype.engine

import kotlinx.serialization.Serializable

/** Tier 2: millisecond-class remote decision. Implemented by the app (HTTP); mocked in the CLI. */
data class ClassifyRequest(val id: Long, val context: String, val typed: String, val candidates: List<String>)

data class ClassifyResult(
    val id: Long,
    /** "keep", "novel", or one of the candidate words */
    val choice: String,
    val confidence: Float,
    val latencyMs: Long,
    val costUsd: Double,
    val model: String,
)

interface Tier2Classifier {
    val available: Boolean
    fun classify(req: ClassifyRequest, callback: (ClassifyResult?) -> Unit)
}

/** Tier 3: subscription LLM. Sentence rewrites at pauses/punctuation and novel-word lookups. */
data class WordAlt(val text: String, val alternatives: List<String>, val locked: Boolean)
data class RewriteRequest(val id: Long, val words: List<WordAlt>, val contextBefore: String)
data class RewriteResult(val id: Long, val words: List<String>, val latencyMs: Long, val costUsd: Double, val model: String)

interface Tier3Model {
    val available: Boolean
    fun rewrite(req: RewriteRequest, callback: (RewriteResult?) -> Unit)
    /** Is [word] a real word / name / term in this context? null = unknown/failed. */
    fun lookupWord(word: String, context: String, callback: (Boolean?) -> Unit)
}

/** Monthly tier-2 spend; persisted by the app through [SlopEngine.exportState]. */
@Serializable
data class Spend(var month: String = "", var usd: Double = 0.0, var calls: Int = 0)

/**
 * Decides which tier handles a situation. The learned part is `router.json` (per-situation tier
 * preference from eval data); the hard part is budget and privacy, which never bend.
 */
class Router(var config: EngineConfig) {
    @Serializable
    data class RouterConfig(
        /** provider for tier 2: jev | openai-decisions | openai-responses | none */
        val tier2Provider: String = "none",
        val tier2Model: String = "",
        /** learned preferences: situation -> tier (1,2,3). Situations: "oov_ambiguous", "invocab_ambiguous", "sentence_end", "pause", "novel_lookup" */
        val preference: Map<String, Int> = mapOf(
            "oov_ambiguous" to 2, "invocab_ambiguous" to 2, "sentence_end" to 3, "pause" to 3, "novel_lookup" to 3,
        ),
        val tier2MonthlyCapUsd: Double = 0.0,
        /** expected tier-2 latency budget; above this the router stops speculating */
        val tier2MaxP95Ms: Long = 400,
    )

    var routerConfig = RouterConfig()
    val spend = Spend()
    @Volatile var online: Boolean = true
    @Volatile var observedTier2P95Ms: Long = 0

    private fun monthKey(now: Long): String {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = now }
        return "%04d-%02d".format(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1)
    }

    fun rollMonth(now: Long = System.currentTimeMillis()) {
        val k = monthKey(now)
        if (spend.month != k) { spend.month = k; spend.usd = 0.0; spend.calls = 0 }
    }

    val capUsd: Double get() = if (routerConfig.tier2MonthlyCapUsd > 0) routerConfig.tier2MonthlyCapUsd else config.tier2MonthlyCapUsd.toDouble()

    fun tier2Allowed(sensitive: Boolean, situation: String): Boolean {
        rollMonth()
        if (sensitive || !online || !config.tier2Enabled) return false
        if (routerConfig.tier2Provider == "none") return false
        if (capUsd <= 0 || spend.usd >= capUsd) return false
        if (observedTier2P95Ms > routerConfig.tier2MaxP95Ms && observedTier2P95Ms > 0) return false
        return (routerConfig.preference[situation] ?: 2) == 2
    }

    fun tier3Allowed(sensitive: Boolean, situation: String): Boolean {
        if (sensitive || !online || !config.tier3Enabled) return false
        return (routerConfig.preference[situation] ?: 3) == 3
    }

    fun recordTier2(result: ClassifyResult?) {
        rollMonth()
        if (result == null) return
        spend.usd += result.costUsd; spend.calls++
    }
}
