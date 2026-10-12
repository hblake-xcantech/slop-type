package dev.sloptype.agekt

/**
 * Bech32 (BIP 173) encoding as used by age for `age1...` recipients and
 * `AGE-SECRET-KEY-1...` identities. Unlike BIP 173 there is no length limit.
 */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    class Decoded(val hrp: String, val data: ByteArray)

    /**
     * Encodes [data] with human readable part [hrp]. If [hrp] is upper case the whole
     * output is upper case (the checksum is always computed over the lower-case hrp).
     */
    fun encode(hrp: String, data: ByteArray): String {
        require(hrp.isNotEmpty()) { "bech32: empty hrp" }
        require(hrp.all { it.code in 33..126 }) { "bech32: invalid character in hrp" }
        val lower = hrp.lowercase()
        val upper = hrp.uppercase()
        require(hrp == lower || hrp == upper) { "bech32: mixed-case hrp" }
        val values = toFiveBit(data)
        val sb = StringBuilder(lower.length + 1 + values.size + 6)
        sb.append(lower).append('1')
        for (v in values) sb.append(CHARSET[v])
        for (v in createChecksum(lower, values)) sb.append(CHARSET[v])
        val s = sb.toString()
        return if (hrp == lower) s else s.uppercase()
    }

    /** Decodes a bech32 string. The returned hrp is lower-cased. */
    fun decode(input: String): Decoded {
        val lower = input.lowercase()
        val upper = input.uppercase()
        if (input != lower && input != upper) throw AgeException("bech32: mixed-case string")
        val s = lower
        val pos = s.lastIndexOf('1')
        if (pos < 1 || pos + 7 > s.length) throw AgeException("bech32: separator '1' at invalid position")
        val hrp = s.substring(0, pos)
        if (hrp.any { it.code !in 33..126 }) throw AgeException("bech32: invalid character in hrp")
        val values = IntArray(s.length - pos - 1)
        for (i in values.indices) {
            val idx = CHARSET.indexOf(s[pos + 1 + i])
            if (idx < 0) throw AgeException("bech32: invalid data character")
            values[i] = idx
        }
        if (!verifyChecksum(hrp, values)) throw AgeException("bech32: invalid checksum")
        val payload = values.copyOfRange(0, values.size - 6)
        return Decoded(hrp, fromFiveBit(payload))
    }

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) {
                if ((top ushr i) and 1 == 1) chk = chk xor GENERATOR[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            out[i] = hrp[i].code ushr 5
            out[hrp.length + 1 + i] = hrp[i].code and 31
        }
        return out
    }

    private fun verifyChecksum(hrp: String, values: IntArray): Boolean =
        polymod(hrpExpand(hrp) + values) == 1

    private fun createChecksum(hrp: String, values: IntArray): IntArray {
        val polymod = polymod(hrpExpand(hrp) + values + IntArray(6)) xor 1
        return IntArray(6) { (polymod ushr (5 * (5 - it))) and 31 }
    }

    /** 8-bit groups to 5-bit groups, padding the final group with zero bits. */
    private fun toFiveBit(data: ByteArray): IntArray {
        val out = ArrayList<Int>(data.size * 8 / 5 + 1)
        var acc = 0
        var bits = 0
        for (b in data) {
            acc = (acc shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.add((acc ushr bits) and 31)
            }
        }
        if (bits > 0) out.add((acc shl (5 - bits)) and 31)
        return out.toIntArray()
    }

    /** 5-bit groups to 8-bit groups; rejects non-zero or oversized padding. */
    private fun fromFiveBit(data: IntArray): ByteArray {
        val out = ArrayList<Byte>(data.size * 5 / 8 + 1)
        var acc = 0
        var bits = 0
        for (v in data) {
            if (v < 0 || v > 31) throw AgeException("bech32: invalid data value")
            acc = (acc shl 5) or v
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out.add(((acc ushr bits) and 0xff).toByte())
            }
        }
        if (bits >= 5 || ((acc shl (8 - bits)) and 0xff) != 0) {
            throw AgeException("bech32: invalid padding")
        }
        return out.toByteArray()
    }
}
