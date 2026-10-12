package dev.sloptype.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every tunable number lives here. The autopilot edits `configs/engine.json` in slop-type-data;
 * the daemon pulls it and the engine hot-reloads. Unknown keys are ignored, missing keys keep
 * defaults, so configs are forward/backward compatible.
 */
@Serializable
data class EngineConfig(
    val version: Int = 1,
    // ---- spatial model ----
    /** Gaussian sigma as a fraction of key width / height. */
    val sigmaX: Float = 0.45f,
    val sigmaY: Float = 0.50f,
    /** Max distance (in key widths) from a touch to a key that is still considered. */
    val maxKeyDistance: Float = 2.2f,
    /** Max keys considered per touch. */
    val keysPerTouch: Int = 7,
    // ---- edit penalties (natural log, negative) ----
    val omitPenalty: Float = -4.5f,
    val insertPenalty: Float = -4.5f,
    /** extra touch that is a near-repeat of the previous key (double tap) */
    val doubleTapPenalty: Float = -2.0f,
    val transposePenalty: Float = -3.0f,
    val trailingOmitPenalty: Float = -5.0f,
    val maxEdits: Int = 2,
    // ---- beam ----
    val beamWidth: Int = 48,
    val maxCandidates: Int = 6,
    // ---- language model ----
    val lmWeight: Float = 1.0f,
    /** log prob assigned to an out-of-vocabulary literal */
    val oovLogProb: Float = -15.0f,
    /** interpolation weight of the bigram (rest goes to unigram) */
    val bigramLambda: Float = 0.75f,
    /** personal lexicon: base log prob + boost per unit weight */
    val personalBase: Float = -8.0f,
    val personalBoost: Float = 3.0f,
    /** words visible on screen get this bonus (natural log) */
    val screenBoost: Float = 2.5f,
    val screenOovLogProb: Float = -11.0f,
    // ---- policy (in-vocabulary literal) ----
    /** logistic weights: bias, margin, precision, literalLogP/10, bestEdits, len/10 */
    val keepPolicy: List<Float> = listOf(-1.0f, 1.5f, -1.0f, -0.6f, -1.5f, 0.0f),
    // ---- policy (OOV literal): bias, margin/5, precision, structEdits, len/10, contextSupport, capitalizedMidSentence, bestIsPersonal, wordlikenessGain ----
    val novelPolicy: List<Float> = listOf(0.8f, 1.2f, -3.5f, -1.5f, -0.3f, 0.5f, -2.0f, 2.0f, 1.5f),
    /** |logit| below this is "ambiguous" → tier 2 (if available) */
    val pendingBand: Float = 0.9f,
    val policyLearningRate: Float = 0.15f,
    val minConfidenceToCorrect: Float = 0.55f,
    // ---- learning ----
    val novelTentativeWeight: Float = 0.35f,
    val retypeWeight: Float = 1.0f,
    val lexiconHalfLifeDays: Float = 45f,
    val postureLearningRate: Float = 0.08f,
    val keyOffsetLearningRate: Float = 0.05f,
    val maxPostureOffsetKeys: Float = 0.6f,
    val postureHypotheses: Int = 3,
    // ---- ghost completion ----
    val ghostMinChars: Int = 3,
    val ghostMargin: Float = 1.2f,
    val ghostMinLogP: Float = -13.0f,
    // ---- retroactive sentence revision ----
    val retroWindow: Int = 4,
    val retroMargin: Float = 2.5f,
    val retroMaxEdits: Int = 1,
    // ---- router ----
    val tier2Enabled: Boolean = true,
    val tier2MinChars: Int = 3,
    val tier2DebounceMs: Long = 120,
    val tier2TimeoutMs: Long = 450,
    val tier3Enabled: Boolean = true,
    val tier3PauseMs: Long = 1800,
    val tier3MinWords: Int = 4,
    /** USD per month; the app persists spend, the engine refuses tier 2 beyond this */
    val tier2MonthlyCapUsd: Float = 0f,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
        val DEFAULT = EngineConfig()
        fun fromJson(text: String): EngineConfig = json.decodeFromString(serializer(), text)
        fun toJson(cfg: EngineConfig): String = json.encodeToString(serializer(), cfg)
        fun loadDefaultResource(): EngineConfig {
            val s = EngineConfig::class.java.getResourceAsStream("/engine-default.json")
                ?: return DEFAULT
            return fromJson(s.bufferedReader().readText())
        }
    }
}
