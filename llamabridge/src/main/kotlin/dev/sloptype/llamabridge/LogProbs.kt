package dev.sloptype.llamabridge

import kotlin.math.exp
import kotlin.math.ln

/**
 * Pure-Kotlin helpers for combining the sum-log-probs returned by [LlamaLm.scoreContinuations]
 * with other evidence (spatial likelihoods, n-gram priors). No Android dependencies; unit-tested.
 */
object LogProbs {
    /** ln(sum(exp(x))) with max-subtraction; -inf for an empty or all -inf input. */
    fun logSumExp(xs: FloatArray): Float {
        var mx = Float.NEGATIVE_INFINITY
        for (x in xs) if (x > mx) mx = x
        if (mx == Float.NEGATIVE_INFINITY) return Float.NEGATIVE_INFINITY
        var sum = 0.0
        for (x in xs) sum += exp((x - mx).toDouble())
        return (mx + ln(sum)).toFloat()
    }

    /**
     * Log-posterior over candidates: `scores[i] - logSumExp(scores)`. Candidates that failed to
     * score (-inf) stay at -inf. Returns all -inf when nothing scored.
     */
    fun normalize(scores: FloatArray): FloatArray {
        val z = logSumExp(scores)
        return FloatArray(scores.size) { i -> if (z.isFinite()) scores[i] - z else Float.NEGATIVE_INFINITY }
    }

    /** `exp(normalize(scores))`: probabilities that sum to 1 over the candidates that scored. */
    fun posterior(scores: FloatArray): FloatArray {
        val n = normalize(scores)
        return FloatArray(n.size) { i -> if (n[i].isFinite()) exp(n[i].toDouble()).toFloat() else 0f }
    }

    /**
     * Combine a neural LM score with another log-domain score (e.g. spatial likelihood + n-gram
     * prior) using a weight on the LM term. Either -inf makes the result -inf.
     */
    fun combine(lmLogProb: Float, otherLogProb: Float, lmWeight: Float = 1f): Float {
        if (lmLogProb == Float.NEGATIVE_INFINITY || otherLogProb == Float.NEGATIVE_INFINITY) return Float.NEGATIVE_INFINITY
        return lmWeight * lmLogProb + otherLogProb
    }

    /** Per-token average, guarding against a zero token count. */
    fun perToken(sumLogProb: Float, tokenCount: Int): Float =
        if (tokenCount <= 0) sumLogProb else sumLogProb / tokenCount

    /** Index of the best finite score, or -1 if none is finite. */
    fun argmax(scores: FloatArray): Int {
        var best = -1
        var bestV = Float.NEGATIVE_INFINITY
        for (i in scores.indices) if (scores[i] > bestV) { bestV = scores[i]; best = i }
        return best
    }
}
