package dev.sloptype.engine

import kotlinx.serialization.Serializable

/**
 * Decides keep / correct / novel from decoder evidence. Two small logistic models (literal in
 * vocabulary vs. literal OOV) whose weights start from the config and are nudged online by
 * user feedback (revert, backspace-and-retype, accepted correction). No user-facing knobs.
 */
class Policy(config: EngineConfig) {
    @Serializable
    data class State(val keep: List<Float>, val novel: List<Float>, val updates: Int)

    var config: EngineConfig = config
        set(value) {
            // a new config resets the initial weights; learned nudges are re-applied on top
            if (value.keepPolicy != field.keepPolicy) keepW = value.keepPolicy.toFloatArray()
            if (value.novelPolicy != field.novelPolicy) novelW = value.novelPolicy.toFloatArray()
            field = value
        }
    private var keepW: FloatArray = config.keepPolicy.toFloatArray()
    private var novelW: FloatArray = config.novelPolicy.toFloatArray()
    var updates = 0
        private set

    class Evidence(
        val literal: Candidate,
        val best: Candidate?,            // best non-literal candidate
        val precisionMean: Float,
        val precisionMin: Float,
        val prev: String?,
        val contextSupport: Float,       // bigram support for best given prev
        val literalLocked: Boolean,
        val literalPersonal: Boolean,
        /** typed with a capital first letter while not at the start of a sentence (likely a name) */
        val literalCapitalized: Boolean = false,
        val bestPersonal: Boolean = false,
        /** charModel(best) - charModel(literal): positive when the literal looks less like a word */
        val wordlikenessGain: Float = 0f,
    )

    data class Verdict(val kind: DecisionKind, val logit: Float, val features: FloatArray, val inVocab: Boolean, val reason: String)

    fun featuresInVocab(e: Evidence): FloatArray {
        val margin = ((e.best?.total ?: -1e9f) - e.literal.total).coerceIn(-12f, 12f)
        return floatArrayOf(
            1f, margin / 3f, e.precisionMin, (e.literal.lm / config.lmWeight.coerceAtLeast(1e-3f)) / 10f,
            (e.best?.structEdits ?: 0).toFloat(), e.literal.word.length / 10f,
        )
    }

    fun featuresOov(e: Evidence): FloatArray {
        val margin = ((e.best?.total ?: -1e9f) - e.literal.total).coerceIn(-20f, 20f)
        return floatArrayOf(
            1f, margin / 5f, e.precisionMean, (e.best?.structEdits ?: 0).toFloat(), e.literal.word.length / 10f,
            (e.contextSupport / 3f).coerceIn(0f, 2f),
            if (e.literalCapitalized) 1f else 0f, if (e.bestPersonal) 1f else 0f,
            e.wordlikenessGain.coerceIn(-2f, 2f),
        )
    }

    private fun dot(w: FloatArray, f: FloatArray): Float {
        var s = 0f
        for (i in f.indices) s += (w.getOrElse(i) { 0f }) * f[i]
        return s
    }

    fun decide(e: Evidence): Verdict {
        val best = e.best
        if (e.literalLocked) return Verdict(DecisionKind.KEEP, -9f, FloatArray(0), e.literal.inLexicon, "locked")
        if (best == null) {
            return if (e.literal.inLexicon) Verdict(DecisionKind.KEEP, -9f, FloatArray(0), true, "only-literal")
            else Verdict(DecisionKind.NOVEL, -9f, FloatArray(0), false, "no-candidates")
        }
        if (best.fromMisspelling && !e.literalPersonal) {
            return Verdict(DecisionKind.CORRECT, 3f, FloatArray(0), e.literal.inLexicon, "misspelling-table")
        }
        if (e.literal.inLexicon) {
            val f = featuresInVocab(e)
            var logit = dot(keepW, f)
            if (e.literalPersonal) logit -= 1.5f
            val kind = if (logit > 0 && BeamDecoder.sigmoid(logit) >= config.minConfidenceToCorrect) DecisionKind.CORRECT else DecisionKind.KEEP
            return Verdict(kind, logit, f, true, "invocab margin=%.2f prec=%.2f".format((best.total - e.literal.total), e.precisionMin))
        } else {
            val f = featuresOov(e)
            val logit = dot(novelW, f)
            val kind = if (logit > 0 && BeamDecoder.sigmoid(logit) >= config.minConfidenceToCorrect) DecisionKind.CORRECT else DecisionKind.NOVEL
            return Verdict(kind, logit, f, false, "oov margin=%.2f prec=%.2f edits=%d".format((best.total - e.literal.total), e.precisionMean, best.edits))
        }
    }

    /** Is this verdict inside the ambiguity band (worth a tier-2 call)? */
    fun isAmbiguous(v: Verdict): Boolean = v.features.isNotEmpty() && kotlin.math.abs(v.logit) < config.pendingBand

    /**
     * Online update. [target] = 1 if correcting was (or would have been) right, 0 if keeping
     * was right. Uses the stored features of the decision.
     */
    fun feedback(v: Verdict, target: Float, strength: Float = 1f) {
        if (v.features.isEmpty()) return
        val w = if (v.inVocab) keepW else novelW
        val p = BeamDecoder.sigmoid(v.logit)
        val g = (p - target) * config.policyLearningRate * strength
        for (i in v.features.indices) if (i < w.size) w[i] -= g * v.features[i]
        // keep the bias within sane bounds so one bad night cannot disable corrections
        w[0] = w[0].coerceIn(-4f, 4f)
        updates++
    }

    fun state() = State(keepW.toList(), novelW.toList(), updates)
    fun load(s: State) {
        if (s.keep.size == keepW.size) keepW = s.keep.toFloatArray()
        if (s.novel.size == novelW.size) novelW = s.novel.toFloatArray()
        updates = s.updates
    }
}
