package dev.sloptype.llamabridge

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogProbsTest {
    private fun close(a: Float, b: Float, eps: Float = 1e-5f) = assertTrue(abs(a - b) < eps, "expected $b, got $a")

    @Test fun logSumExpMatchesNaive() {
        val xs = floatArrayOf(-1f, -2.5f, -0.3f)
        val naive = ln(xs.sumOf { exp(it.toDouble()) }).toFloat()
        close(LogProbs.logSumExp(xs), naive)
    }

    @Test fun logSumExpIsStableForLargeMagnitudes() {
        val xs = floatArrayOf(-1000f, -1001f)
        close(LogProbs.logSumExp(xs), -1000f + ln(1.0 + exp(-1.0)).toFloat())
    }

    @Test fun logSumExpEmptyOrAllNegInf() {
        assertEquals(Float.NEGATIVE_INFINITY, LogProbs.logSumExp(FloatArray(0)))
        assertEquals(Float.NEGATIVE_INFINITY, LogProbs.logSumExp(floatArrayOf(Float.NEGATIVE_INFINITY)))
    }

    @Test fun posteriorSumsToOneAndKeepsOrder() {
        val p = LogProbs.posterior(floatArrayOf(-3f, -1f, -2f))
        close(p.sum(), 1f)
        assertTrue(p[1] > p[2] && p[2] > p[0])
    }

    @Test fun normalizeKeepsFailedCandidatesAtNegInf() {
        val n = LogProbs.normalize(floatArrayOf(-1f, Float.NEGATIVE_INFINITY))
        close(n[0], 0f)
        assertEquals(Float.NEGATIVE_INFINITY, n[1])
        assertEquals(0f, LogProbs.posterior(floatArrayOf(-1f, Float.NEGATIVE_INFINITY))[1])
    }

    @Test fun combineAndArgmax() {
        close(LogProbs.combine(-2f, -1f, 0.5f), -2f)
        assertEquals(Float.NEGATIVE_INFINITY, LogProbs.combine(Float.NEGATIVE_INFINITY, -1f))
        assertEquals(1, LogProbs.argmax(floatArrayOf(-5f, -0.5f, -3f)))
        assertEquals(-1, LogProbs.argmax(floatArrayOf(Float.NEGATIVE_INFINITY)))
        close(LogProbs.perToken(-6f, 3), -2f)
        close(LogProbs.perToken(-6f, 0), -6f)
    }
}
