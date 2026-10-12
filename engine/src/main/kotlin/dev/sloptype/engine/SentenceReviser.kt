package dev.sloptype.engine

/**
 * Retroactive revision: each committed word keeps its top candidates; at every boundary a
 * Viterbi pass over the last [EngineConfig.retroWindow] words with the bigram LM may find that an
 * earlier word reads better as one of its alternatives given what came after. Only unlocked words
 * whose alternative wins by [EngineConfig.retroMargin] are revised, at most [retroMaxEdits] per pass.
 */
class SentenceReviser(private val lm: NgramLm, var config: EngineConfig) {

    class Slot(val committed: String, val candidates: List<Candidate>, val locked: Boolean, val kind: DecisionKind)

    fun revise(window: List<Slot>, prevContext: String?): List<RetroEdit> {
        val n = window.size
        if (n < 2) return emptyList()
        // lattice: options per slot (committed first)
        val opts = window.map { s ->
            val list = ArrayList<Pair<String, Float>>()
            list.add(s.committed.lowercase() to 0f)
            if (!s.locked) for (c in s.candidates) {
                val w = c.word.lowercase()
                if (w != s.committed.lowercase() && list.none { it.first == w }) list.add(w to (c.spatial + c.editPenalty))
            }
            list
        }
        // acoustic-like score: spatial+edit relative to the committed option
        val base = window.map { s -> s.candidates.firstOrNull { it.word.equals(s.committed, true) }?.let { it.spatial + it.editPenalty } ?: 0f }
        // Viterbi
        val score = Array(n) { FloatArray(opts[it].size) { Float.NEGATIVE_INFINITY } }
        val back = Array(n) { IntArray(opts[it].size) }
        for (j in opts[0].indices) {
            val (w, sp) = opts[0][j]
            score[0][j] = (if (j == 0) 0f else sp - base[0]) + config.lmWeight * lm.logProb(w, prevContext)
        }
        for (i in 1 until n) for (j in opts[i].indices) {
            val (w, sp) = opts[i][j]
            val local = (if (j == 0) 0f else sp - base[i])
            var bestS = Float.NEGATIVE_INFINITY; var bestK = 0
            for (k in opts[i - 1].indices) {
                val s = score[i - 1][k] + config.lmWeight * lm.logProb(w, opts[i - 1][k].first)
                if (s > bestS) { bestS = s; bestK = k }
            }
            score[i][j] = bestS + local; back[i][j] = bestK
        }
        // committed path score
        var committedScore = score0(opts, base, prevContext)
        var bestJ = 0
        for (j in opts[n - 1].indices) if (score[n - 1][j] > score[n - 1][bestJ]) bestJ = j
        val bestScore = score[n - 1][bestJ]
        if (bestScore - committedScore < config.retroMargin) return emptyList()
        // backtrack
        val path = IntArray(n)
        var j = bestJ
        for (i in n - 1 downTo 0) { path[i] = j; j = back[i][j] }
        val edits = ArrayList<RetroEdit>()
        for (i in 0 until n - 1) { // never touch the word just committed (index n-1)
            if (path[i] != 0) {
                val slot = window[i]
                val newW = matchCase(slot.committed, opts[i][path[i]].first)
                edits.add(RetroEdit(wordsBack = n - i, oldText = slot.committed, newText = newW))
            }
        }
        return edits.take(config.retroMaxEdits)
    }

    private fun score0(opts: List<List<Pair<String, Float>>>, base: List<Float>, prev: String?): Float {
        var s = 0f; var p = prev
        for (o in opts) { s += config.lmWeight * lm.logProb(o[0].first, p); p = o[0].first }
        return s
    }

    companion object {
        fun matchCase(template: String, word: String): String {
            if (template.isEmpty()) return word
            return when {
                template.length > 1 && template.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
                template[0].isUpperCase() -> word.replaceFirstChar { it.uppercase() }
                else -> word
            }
        }
    }
}
