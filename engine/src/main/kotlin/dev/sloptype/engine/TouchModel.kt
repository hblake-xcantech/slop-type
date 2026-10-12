package dev.sloptype.engine

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Hand-posture hypothesis: a global offset that varies linearly across the keyboard
 * (trapezoid skew) — dx depends on the row, dy on the column — plus a small isotropic scale.
 * Values are in pixels except [scale].
 */
@Serializable
data class Posture(
    val dx: Float = 0f, val dy: Float = 0f,
    /** dx changes by this much from the top row to the bottom row */
    val dxPerRow: Float = 0f,
    /** dy changes by this much from the left edge to the right edge */
    val dyPerCol: Float = 0f,
    val scale: Float = 1f,
) {
    /** Map a raw touch into "intended" keyboard space under this posture. */
    fun apply(x: Float, y: Float, geom: KeyboardGeometry): Pair<Float, Float> {
        val fy = (y / geom.height) - 0.5f
        val fx = (x / geom.width) - 0.5f
        val ox = dx + dxPerRow * fy
        val oy = dy + dyPerCol * fx
        val cx = geom.width / 2f; val cy = geom.height / 2f
        val sx = cx + (x - cx) / scale
        val sy = cy + (y - cy) / scale
        return (sx - ox) to (sy - oy)
    }
}

/**
 * Per-key 2D Gaussian touch distributions with learned per-key offsets, under a posture.
 * Likelihoods are natural log densities (comparable across keys for one touch).
 */
class TouchModel(val geom: KeyboardGeometry, var config: EngineConfig) {
    @Serializable
    data class State(
        val posture: Posture = Posture(),
        val keyOffsets: Map<Int, List<Float>> = emptyMap(),
        val sigmaScale: Float = 1f,
    )

    var posture = Posture()
        private set
    private val offX = HashMap<Int, Float>()
    private val offY = HashMap<Int, Float>()
    private var sigmaScale = 1f

    val sigmaX: Float get() = config.sigmaX * geom.avgKeyW * sigmaScale
    val sigmaY: Float get() = config.sigmaY * geom.avgKeyH * sigmaScale

    fun state(): State = State(posture, geom.letterKeys.associate { it.code to listOf(offX[it.code] ?: 0f, offY[it.code] ?: 0f) }, sigmaScale)
    fun load(s: State) {
        posture = s.posture; sigmaScale = s.sigmaScale
        offX.clear(); offY.clear()
        for ((k, v) in s.keyOffsets) { if (v.size == 2) { offX[k] = v[0]; offY[k] = v[1] } }
    }

    /** log N(touch | key) under [p]. */
    fun logLik(key: KeyGeom, touch: Touch, p: Posture = posture): Float {
        val (x, y) = p.apply(touch.x, touch.y, geom)
        val mx = key.cx + (offX[key.code] ?: 0f)
        val my = key.cy + (offY[key.code] ?: 0f)
        val sx = sigmaX; val sy = sigmaY
        val zx = (x - mx) / sx; val zy = (y - my) / sy
        // log-density up to the per-touch constant: 0 at the key centre, negative away from it.
        // Dropping the constant keeps SUB and INS/OMIT alignments comparable.
        return -0.5f * (zx * zx + zy * zy)
    }

    /** Candidate keys for a touch, best first, limited by distance and count. */
    fun candidateKeys(touch: Touch, p: Posture = posture): List<Pair<KeyGeom, Float>> {
        val (x, y) = p.apply(touch.x, touch.y, geom)
        val maxD = config.maxKeyDistance * geom.avgKeyW
        val out = ArrayList<Pair<KeyGeom, Float>>()
        for (k in geom.letterKeys) {
            val ddx = abs(x - k.cx); val ddy = abs(y - k.cy)
            if (ddx > maxD || ddy > maxD * 1.2f) continue
            out.add(k to logLik(k, touch, p))
        }
        out.sortByDescending { it.second }
        return if (out.size > config.keysPerTouch) out.subList(0, config.keysPerTouch) else out
    }

    /** exp(logLik(literal) - max logLik) ∈ (0,1]: 1 means the tap is unambiguous. */
    fun tapPrecision(touch: Touch, p: Posture = posture): Float {
        val literal = geom.key(touch.code) ?: return 0.5f
        val best = candidateKeys(touch, p).firstOrNull()?.second ?: return 0.5f
        return exp(logLik(literal, touch, p) - best).coerceIn(0f, 1f)
    }

    /** Total log-likelihood of a key sequence against touches (SUB-only alignment). */
    fun sequenceLogLik(codes: IntArray, touches: List<Touch>, p: Posture): Float {
        var s = 0f
        for (i in touches.indices) {
            if (i >= codes.size) break
            val k = geom.key(codes[i]) ?: continue
            s += logLik(k, touches[i], p)
        }
        return s
    }

    /** Posture hypotheses to decode under: the current estimate and nudged variants. */
    fun postureHypotheses(): List<Posture> {
        val n = config.postureHypotheses
        if (n <= 1) return listOf(posture)
        val step = 0.18f * geom.avgKeyW
        val out = ArrayList<Posture>()
        out.add(posture)
        out.add(posture.copy(dx = posture.dx + step, dy = posture.dy + step * 0.6f))
        out.add(posture.copy(dx = posture.dx - step, dy = posture.dy - step * 0.6f))
        if (n > 3) out.add(posture.copy(dxPerRow = posture.dxPerRow + step))
        if (n > 4) out.add(posture.copy(dxPerRow = posture.dxPerRow - step))
        return out.take(n)
    }

    /**
     * EM-style update after a word is committed: the intended keys are known, so the
     * residuals move the posture (global) and the per-key offsets (local), both with
     * exponential forgetting. Only SUB-aligned touches are used.
     */
    fun learn(intended: String, touches: List<Touch>) {
        if (intended.length != touches.size || touches.isEmpty()) return
        val lrP = config.postureLearningRate
        val lrK = config.keyOffsetLearningRate
        val maxOff = config.maxPostureOffsetKeys * geom.avgKeyW
        var sdx = 0f; var sdy = 0f; var n = 0
        var sRowX = 0f; var sRowW = 0f; var sColY = 0f; var sColW = 0f
        for (i in touches.indices) {
            val k = geom.key(intended[i].code) ?: continue
            val t = touches[i]
            val (x, y) = posture.apply(t.x, t.y, geom)
            val rx = x - k.cx - (offX[k.code] ?: 0f)
            val ry = y - k.cy - (offY[k.code] ?: 0f)
            // ignore gross outliers (likely a different key)
            if (abs(rx) > 1.5f * geom.avgKeyW || abs(ry) > 1.5f * geom.avgKeyH) continue
            sdx += rx; sdy += ry; n++
            val fy = (t.y / geom.height) - 0.5f; val fx = (t.x / geom.width) - 0.5f
            sRowX += rx * fy; sRowW += fy * fy
            sColY += ry * fx; sColW += fx * fx
            // per-key offset (slow)
            offX[k.code] = ((offX[k.code] ?: 0f) + lrK * rx).coerceIn(-maxOff, maxOff)
            offY[k.code] = ((offY[k.code] ?: 0f) + lrK * ry).coerceIn(-maxOff, maxOff)
        }
        if (n == 0) return
        val mdx = sdx / n; val mdy = sdy / n
        val rowSlope = if (sRowW > 1e-3f) sRowX / sRowW else 0f
        val colSlope = if (sColW > 1e-3f) sColY / sColW else 0f
        posture = posture.copy(
            dx = (posture.dx + lrP * mdx).coerceIn(-maxOff, maxOff),
            dy = (posture.dy + lrP * mdy).coerceIn(-maxOff, maxOff),
            dxPerRow = (posture.dxPerRow + lrP * 0.5f * rowSlope).coerceIn(-maxOff, maxOff),
            dyPerCol = (posture.dyPerCol + lrP * 0.5f * colSlope).coerceIn(-maxOff, maxOff),
        )
        // sigma adaptation from residual spread (bounded)
        var ss = 0f; var m = 0
        for (i in touches.indices) {
            val k = geom.key(intended[i].code) ?: continue
            val (x, y) = posture.apply(touches[i].x, touches[i].y, geom)
            val rx = (x - k.cx - (offX[k.code] ?: 0f)) / (config.sigmaX * geom.avgKeyW)
            val ry = (y - k.cy - (offY[k.code] ?: 0f)) / (config.sigmaY * geom.avgKeyH)
            if (abs(rx) > 3f || abs(ry) > 3f) continue
            ss += rx * rx + ry * ry; m += 2
        }
        if (m > 0) {
            val observed = sqrt(ss / m)
            sigmaScale = (sigmaScale * (1 - lrK) + observed * lrK).coerceIn(0.6f, 1.6f)
        }
    }

    /** Adopt a posture hypothesis that explained a word better (soft move). */
    fun nudgeToward(p: Posture) {
        val a = config.postureLearningRate
        posture = Posture(
            posture.dx + a * (p.dx - posture.dx),
            posture.dy + a * (p.dy - posture.dy),
            posture.dxPerRow + a * (p.dxPerRow - posture.dxPerRow),
            posture.dyPerCol + a * (p.dyPerCol - posture.dyPerCol),
            posture.scale + a * (p.scale - posture.scale),
        )
    }
}
