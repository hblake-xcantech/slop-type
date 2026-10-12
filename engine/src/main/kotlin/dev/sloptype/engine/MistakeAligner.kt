package dev.sloptype.engine

import kotlin.math.min

/**
 * The MISTAKE key: the user types a replacement after the marker; we find the span before the
 * marker it most likely replaces. Score = normalised edit distance + touch evidence (if we still
 * have the touches for those words) + LM gain of the replacement in context.
 */
class MistakeAligner(private val lm: NgramLm, private val touchModel: TouchModel?) {

    class RecentWord(val text: String, val touches: List<Touch>?)

    fun resolve(replacement: String, recent: List<RecentWord>, contextBefore: List<String>): MistakeResolution? {
        if (recent.isEmpty()) return null
        val rep = replacement.lowercase()
        val maxSpan = min(3, recent.size)
        data class Opt(val wordsBack: Int, val old: String, val score: Float)
        val opts = ArrayList<Opt>()
        for (k in 1..maxSpan) {
            val span = recent.takeLast(k)
            val old = span.joinToString(" ") { it.text }
            val oldL = old.lowercase()
            val ed = editDistance(oldL, rep).toFloat()
            val norm = ed / maxOf(oldL.length, rep.length).coerceAtLeast(1)
            var score = -3.0f * norm
            // prefix/suffix overlap helps ("tst" ~ "testing")
            if (rep.startsWith(oldL.take(2)) || oldL.startsWith(rep.take(2))) score += 0.6f
            // touch evidence: only for single-word spans whose touches we kept
            if (k == 1 && touchModel != null) {
                val t = span[0].touches
                if (t != null && t.isNotEmpty()) {
                    val codes = rep.map { it.code }.toIntArray()
                    val subLen = min(codes.size, t.size)
                    val ll = touchModel.sequenceLogLik(codes, t.take(subLen), touchModel.posture)
                    val litLl = touchModel.sequenceLogLik(span[0].text.lowercase().map { it.code }.toIntArray(), t.take(subLen), touchModel.posture)
                    score += ((ll - litLl) / (subLen.coerceAtLeast(1) * 4f)).coerceIn(-1f, 1f)
                }
            }
            // LM gain
            val before = contextBefore.dropLast(k).lastOrNull()
            // per-word LM gain (replacing k words by one must not win just by deleting words)
            val gain = lm.logProb(rep, before) - lm.sequenceLogProb(span.map { it.text.lowercase() }, before) / k
            score += (gain / 6f).coerceIn(-1.5f, 1.5f)
            // prefer shorter spans
            score -= 0.8f * (k - 1)
            opts.add(Opt(k, old, score))
        }
        opts.sortByDescending { it.score }
        val top = opts.first()
        return MistakeResolution(
            wordsBack = top.wordsBack, oldText = top.old, newText = replacement,
            alternatives = opts.drop(1).map { it.wordsBack to it.old },
        )
    }

    companion object {
        fun editDistance(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]; dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return dp[b.length]
        }
    }
}
