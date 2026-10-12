package dev.sloptype.engine

import kotlin.math.exp
import kotlin.math.ln

/**
 * Noisy-channel beam search over the lexicon trie driven by raw touches.
 *
 * Score(word) = Σ spatial log-lik of aligned touches + edit penalties + lmWeight · log P(word | prev).
 * Edit operations: SUB (touch ↔ letter, spatial), INS (extra touch skipped), OMIT (letter with no
 * touch), TRANS (two adjacent touches swapped). Decoding is re-run from scratch on every keystroke
 * (words are short, beams are small) under a few posture hypotheses.
 */
class BeamDecoder(
    private val lexicon: Lexicon,
    private val touchModel: TouchModel,
    private val lm: NgramLm,
    var config: EngineConfig,
) {
    enum class Op { START, SUB, INS, OMIT, TRANS }

    class Hyp(
        val node: Int, val consumed: Int,
        val spatial: Float, val editPen: Float, val edits: Int,
        val lastOp: Op, val structEdits: Int = 0,
    )

    data class Result(val candidates: List<Candidate>, val literal: Candidate, val posture: Posture, val precisionMean: Float, val precisionMin: Float)

    /** Decode under every posture hypothesis and return the best-scoring one. */
    fun decode(touches: List<Touch>, prev: String?): Result {
        var best: Result? = null
        for (p in touchModel.postureHypotheses()) {
            val r = decodeUnder(touches, prev, p)
            val top = r.candidates.firstOrNull()?.total ?: r.literal.total
            val bestTop = best?.candidates?.firstOrNull()?.total ?: best?.literal?.total
            if (best == null || top > bestTop!!) best = r
        }
        return best!!
    }

    fun decodeUnder(touches: List<Touch>, prev: String?, posture: Posture): Result {
        val n = touches.size
        val literalStr = String(touches.map { Character.toLowerCase(it.code).toChar() }.toCharArray())
        // per-touch candidate keys: char -> loglik
        val cand = Array(n) { i ->
            val m = HashMap<Char, Float>(8)
            val lit = Character.toLowerCase(touches[i].code).toChar()
            val litKey = touchModel.geom.key(touches[i].code) ?: touchModel.geom.key(lit.code)
            if (litKey == null) {
                // no geometry for this key (popup character, apostrophe, hardware): trust it exactly
                m[lit] = 0f
            } else {
                for ((k, ll) in touchModel.candidateKeys(touches[i], posture)) m[Character.toLowerCase(k.code).toChar()] = ll
                if (!m.containsKey(lit)) m[lit] = touchModel.logLik(litKey, touches[i], posture)
            }
            m
        }
        // precision of the literal taps (how centred they are on their keys)
        var pSum = 0f; var pMin = 1f
        for (i in 0 until n) {
            val k = touchModel.geom.key(touches[i].code)
            val pr = if (k == null) 1f else {
                val (x, y) = posture.apply(touches[i].x, touches[i].y, touchModel.geom)
                val zx = (x - k.cx) / touchModel.sigmaX; val zy = (y - k.cy) / touchModel.sigmaY
                exp(-0.5f * (zx * zx + zy * zy))
            }
            pSum += pr; if (pr < pMin) pMin = pr
        }
        val precisionMean = if (n > 0) pSum / n else 1f
        val precisionMin = if (n > 0) pMin else 1f

        val width = config.beamWidth
        val beams = Array(n + 2) { ArrayList<Hyp>() }
        beams[0].add(Hyp(lexicon.root, 0, 0f, 0f, 0, Op.START))
        val lmW = config.lmWeight

        fun pruneKey(h: Hyp): Float = h.spatial + h.editPen + lmW * lexicon.bestInSubtree(h.node)

        for (i in 0..n) {
            val layer = beams[i]
            if (layer.isEmpty()) continue
            // OMIT expansions (do not consume a touch); only one in a row, and not at i==n here
            if (i < n) {
                val extra = ArrayList<Hyp>()
                for (h in layer) {
                    if (h.lastOp == Op.OMIT || h.edits >= config.maxEdits || h.lastOp == Op.START) continue
                    lexicon.forEachChild(h.node) { c, _ ->
                        extra.add(Hyp(c, i, h.spatial, h.editPen + config.omitPenalty, h.edits + 1, Op.OMIT, h.structEdits + 1))
                    }
                }
                layer.addAll(extra)
            }
            // prune
            if (layer.size > width) {
                layer.sortByDescending { pruneKey(it) }
                layer.subList(width, layer.size).clear()
            }
            if (i == n) break
            val next = beams[i + 1]
            val next2 = beams[i + 2]
            val touchCand = cand[i]
            for (h in layer) {
                // SUB
                lexicon.forEachChild(h.node) { c, ch ->
                    val ll = touchCand[ch]
                    if (ll != null) next.add(Hyp(c, i + 1, h.spatial + ll, h.editPen, h.edits, Op.SUB, h.structEdits))
                }
                // INS: skip this touch
                if (h.edits < config.maxEdits && h.lastOp != Op.START) {
                    val isDouble = i > 0 && touches[i].code == touches[i - 1].code
                    val pen = if (isDouble) config.doubleTapPenalty else config.insertPenalty
                    next.add(Hyp(h.node, i + 1, h.spatial, h.editPen + pen, h.edits + 1, Op.INS, h.structEdits + (if (isDouble) 0 else 1)))
                }
                // TRANS: touches i, i+1 swapped
                if (i + 1 < n && h.edits < config.maxEdits) {
                    val c2 = cand[i + 1]
                    lexicon.forEachChild(h.node) { c1node, ch1 ->
                        val ll1 = c2[ch1] ?: return@forEachChild   // first letter matches the second touch
                        lexicon.forEachChild(c1node) { c2node, ch2 ->
                            if (ch2 == ch1) return@forEachChild
                            val ll2 = touchCand[ch2] ?: return@forEachChild
                            next2.add(Hyp(c2node, i + 2, h.spatial + ll1 + ll2, h.editPen + config.transposePenalty, h.edits + 1, Op.TRANS, h.structEdits))
                        }
                    }
                }
            }
        }
        // final layer: words, plus one trailing omission
        val finals = ArrayList<Hyp>()
        for (h in beams[n]) {
            if (lexicon.isWord(h.node)) finals.add(h)
            if (h.edits < config.maxEdits && h.lastOp != Op.OMIT && n >= 3) {
                lexicon.forEachChild(h.node) { c, _ ->
                    if (lexicon.isWord(c)) finals.add(Hyp(c, n, h.spatial, h.editPen + config.trailingOmitPenalty, h.edits + 1, Op.OMIT, h.structEdits + 1))
                }
            }
        }
        val byWord = HashMap<String, Candidate>()
        for (h in finals) {
            val w = lexicon.wordOf(h.node)
            val lmScore = lmW * lm.logProb(w, prev)
            val c = Candidate(w, h.spatial, h.edits, h.editPen, lmScore, inLexicon = true, isLiteral = w == literalStr, structEdits = h.structEdits)
            val old = byWord[w]
            if (old == null || c.total > old.total) byWord[w] = c
        }
        // literal candidate (always present)
        var litSpatial = 0f
        for (i in 0 until n) litSpatial += cand[i][literalStr[i]] ?: -30f
        val litKnown = lm.isKnown(literalStr)
        val litLm = lmW * (if (litKnown) lm.logProb(literalStr, prev) else config.oovLogProb)
        val literal = byWord[literalStr] ?: Candidate(literalStr, litSpatial, 0, 0f, litLm, inLexicon = litKnown, isLiteral = true)
        if (litKnown && byWord[literalStr] == null) byWord[literalStr] = literal
        // known misspelling: a direct, cheap candidate
        lm.misspellings[literalStr]?.let { target ->
            val lmScore = lmW * lm.logProb(target.substringAfterLast(' ').lowercase(), prev)
            val c = Candidate(target, litSpatial, 1, -1.0f, lmScore, inLexicon = true, isLiteral = false, structEdits = 0, fromMisspelling = true)
            val old = byWord[target]
            if (old == null || c.total > old.total) byWord[target] = c
        }
        val list = byWord.values.sortedByDescending { it.total }.take(config.maxCandidates)
        return Result(list, literal, posture, precisionMean, precisionMin)
    }

    /** Most probable completion of the current (literal-aligned) prefix, or null. */
    fun ghost(touches: List<Touch>, prev: String?): Ghost? {
        if (touches.size < config.ghostMinChars) return null
        val prefix = String(touches.map { Character.toLowerCase(it.code).toChar() }.toCharArray())
        val node = lexicon.find(prefix)
        if (node == -1) return null
        val comps = lexicon.completions(node, 6)
        if (comps.isEmpty()) return null
        // rerank by bigram context
        val scored = comps.map { (w, _) -> w to lm.logProb(w, prev) }.sortedByDescending { it.second }
        val (w, s) = scored[0]
        if (s < config.ghostMinLogP) return null
        // extensions of the top completion (keyboard → keyboards) do not compete with it
        val second = scored.drop(1).firstOrNull { !it.first.startsWith(w) }?.second ?: Float.NEGATIVE_INFINITY
        // the prefix itself as a word competes too
        val selfScore = if (lexicon.isWord(node)) lm.logProb(prefix, prev) else Float.NEGATIVE_INFINITY
        if (s - maxOf(second, selfScore) < config.ghostMargin) return null
        if (w.length <= prefix.length) return null
        return Ghost(prefix, w.substring(prefix.length))
    }

    companion object {
        fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()
        fun safeLn(x: Double) = ln(x.coerceAtLeast(1e-30))
    }
}
