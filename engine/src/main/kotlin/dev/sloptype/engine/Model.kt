package dev.sloptype.engine

import kotlinx.serialization.Serializable

/** One key of the on-screen keyboard. [x],[y] are the top-left corner in keyboard pixels. */
@Serializable
data class KeyGeom(val code: Int, val label: String, val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx: Float get() = x + w / 2f
    val cy: Float get() = y + h / 2f
}

@Serializable
data class KeyboardGeometry(val id: String, val width: Float, val height: Float, val keys: List<KeyGeom>) {
    @Transient
    private val byCode: Map<Int, KeyGeom> = keys.associateBy { it.code }
    fun key(code: Int): KeyGeom? = byCode[code]
    /** Letter keys only (lower-case Unicode letters and apostrophe). */
    @Transient
    val letterKeys: List<KeyGeom> = keys.filter { isWordChar(it.code) }
    @Transient
    val avgKeyW: Float = letterKeys.map { it.w }.average().toFloat().takeIf { !it.isNaN() } ?: (width / 10f)
    @Transient
    val avgKeyH: Float = letterKeys.map { it.h }.average().toFloat().takeIf { !it.isNaN() } ?: (height / 4f)

    companion object {
        fun isWordChar(code: Int): Boolean = code == '\''.code || (code > 0 && Character.isLetter(code))
    }
}

/** A raw touch that produced a key (down position is what matters for decoding). */
@Serializable
data class Touch(
    val x: Float, val y: Float, val t: Long,
    val pressure: Float = 0f, val size: Float = 0f,
    /** key code the keyboard resolved for this touch (the literal letter) */
    val code: Int,
)

@Serializable
data class FieldInfo(
    val packageName: String,
    val inputType: Int = 0,
    val imeOptions: Int = 0,
    /** password fields, IME_FLAG_NO_PERSONALIZED_LEARNING, excluded apps: no logging, no remote */
    val sensitive: Boolean = false,
)

enum class DecisionKind { KEEP, CORRECT, NOVEL, PENDING }

@Serializable
data class RetroEdit(val wordsBack: Int, val oldText: String, val newText: String)

@Serializable
data class WordDecision(
    val typed: String,
    val commit: String,
    val kind: DecisionKind,
    val confidence: Float,
    val alternatives: List<String> = emptyList(),
    val retro: List<RetroEdit> = emptyList(),
    val tier: Int = 1,
    val latencyMs: Long = 0,
    val pendingId: Long? = null,
    /** debugging / eval: why */
    val reason: String = "",
)

@Serializable
data class Ghost(val prefix: String, val completion: String)

@Serializable
data class MistakeResolution(
    /** span to replace, counted in words back from the marker (1 = the word right before it) */
    val wordsBack: Int,
    val oldText: String,
    val newText: String,
    /** other plausible spans, for "tap to shift" */
    val alternatives: List<Pair<Int, String>> = emptyList(),
    val deleteOnly: Boolean = false,
)

/** Candidate produced by the decoder. Scores are natural-log. */
data class Candidate(
    val word: String,
    val spatial: Float,
    val edits: Int,
    val editPenalty: Float,
    val lm: Float,
    val inLexicon: Boolean,
    val isLiteral: Boolean,
    /** insertions + omissions only (transpositions are "cheap" typos) */
    val structEdits: Int = 0,
    val fromMisspelling: Boolean = false,
) {
    val total: Float get() = spatial + editPenalty + lm
}

/** Where the engine writes its events; the app serialises to JSONL and the CLI to stdout. */
fun interface LogSink {
    fun event(name: String, fields: Map<String, Any?>)
}

object NoopLogSink : LogSink {
    override fun event(name: String, fields: Map<String, Any?>) {}
}
