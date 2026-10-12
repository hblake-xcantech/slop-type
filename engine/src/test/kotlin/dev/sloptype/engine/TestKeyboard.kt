package dev.sloptype.engine

import kotlin.random.Random

/** A Pixel-10-Pro-like portrait QWERTY used by the unit tests. */
object TestKeyboard {
    val geom: KeyboardGeometry by lazy { build() }

    fun build(): KeyboardGeometry {
        val width = 1080f
        val keyW = 108f; val keyH = 165f
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val keys = ArrayList<KeyGeom>()
        val offsets = listOf(0f, keyW / 2, keyW * 1.5f)
        for ((r, row) in rows.withIndex()) {
            val y = r * keyH
            for ((c, ch) in row.withIndex()) {
                keys.add(KeyGeom(ch.code, ch.toString(), offsets[r] + c * keyW, y, keyW, keyH))
            }
        }
        keys.add(KeyGeom(' '.code, "space", 270f, 3 * keyH, 540f, keyH))
        keys.add(KeyGeom('.'.code, "period", 810f, 3 * keyH, 108f, keyH))
        keys.add(KeyGeom(','.code, "comma", 162f, 3 * keyH, 108f, keyH))
        keys.add(KeyGeom(-7, "delete", 972f, 2 * keyH, 108f, keyH))
        return KeyboardGeometry("test_qwerty", width, 4 * keyH, keys)
    }

    /** Touches for [word] with Gaussian noise (sigma as fraction of key size). Codes resolved by hit test. */
    fun touches(word: String, sigma: Float = 0f, rnd: Random = Random(1), dx: Float = 0f, dy: Float = 0f, t0: Long = 0L): List<Touch> {
        val out = ArrayList<Touch>()
        var t = t0
        for (ch in word) {
            val k = geom.key(ch.lowercaseChar().code) ?: continue
            val x = (k.cx + dx + gauss(rnd) * sigma * k.w).coerceIn(0f, geom.width - 1)
            val y = (k.cy + dy + gauss(rnd) * sigma * k.h).coerceIn(0f, geom.height - 1)
            val hit = geom.letterKeys.firstOrNull { x >= it.x && x < it.x + it.w && y >= it.y && y < it.y + it.h } ?: k
            t += 180
            out.add(Touch(x, y, t, 0.4f, 0.1f, hit.code))
        }
        return out
    }

    /** Touches that literally type [typed] but whose positions are exactly on the key centres. */
    fun exact(typed: String): List<Touch> = touches(typed, 0f)

    private fun gauss(rnd: Random): Float {
        var u = 0.0; var v = 0.0
        while (u == 0.0) u = rnd.nextDouble()
        while (v == 0.0) v = rnd.nextDouble()
        return (Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2.0 * Math.PI * v)).toFloat()
    }
}
