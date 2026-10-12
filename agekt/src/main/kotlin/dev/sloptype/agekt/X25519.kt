package dev.sloptype.agekt

import java.math.BigInteger

/**
 * X25519 (RFC 7748) scalar multiplication implemented with [BigInteger].
 *
 * Android only exposes XDH through javax.crypto from API 33, so this is a small,
 * dependency-free implementation. It favours clarity over speed and is not
 * constant-time; age files are small and the operation is performed once per
 * recipient, so this is acceptable for this use.
 */
object X25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)

    /** The X25519 base point u = 9, little-endian. */
    val BASEPOINT: ByteArray = ByteArray(32).also { it[0] = 9 }

    /** Computes X25519(scalar, u) per RFC 7748 section 5. Both inputs and the output are 32-byte little-endian. */
    fun scalarMult(scalar: ByteArray, u: ByteArray): ByteArray {
        require(scalar.size == 32) { "X25519 scalar must be 32 bytes" }
        require(u.size == 32) { "X25519 u-coordinate must be 32 bytes" }
        val k = decodeScalar(scalar)
        val x1 = decodeUCoordinate(u)

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = false

        for (t in 254 downTo 0) {
            val kt = k.testBit(t)
            swap = swap xor kt
            if (swap) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            val daPlusCb = da.add(cb)
            x3 = daPlusCb.multiply(daPlusCb).mod(P)
            val daMinusCb = da.subtract(cb)
            z3 = x1.multiply(daMinusCb.multiply(daMinusCb)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }

        val result = x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P)
        return encodeUCoordinate(result)
    }

    /** Derives the public key (u-coordinate) for a private scalar: X25519(scalar, 9). */
    fun publicKey(scalar: ByteArray): ByteArray = scalarMult(scalar, BASEPOINT)

    private fun decodeScalar(scalar: ByteArray): BigInteger {
        val clamped = scalar.copyOf()
        clamped[0] = (clamped[0].toInt() and 248).toByte()
        clamped[31] = (clamped[31].toInt() and 127).toByte()
        clamped[31] = (clamped[31].toInt() or 64).toByte()
        return decodeLittleEndian(clamped)
    }

    private fun decodeUCoordinate(u: ByteArray): BigInteger {
        val masked = u.copyOf()
        // RFC 7748: implementations MUST mask the most significant bit of the final byte.
        masked[31] = (masked[31].toInt() and 127).toByte()
        return decodeLittleEndian(masked).mod(P)
    }

    private fun encodeUCoordinate(value: BigInteger): ByteArray {
        val out = ByteArray(32)
        val bytes = value.mod(P).toByteArray() // big-endian, may have a leading zero sign byte
        var i = 0
        var j = bytes.size - 1
        while (i < 32 && j >= 0) {
            out[i] = bytes[j]
            i++
            j--
        }
        return out
    }

    private fun decodeLittleEndian(bytes: ByteArray): BigInteger {
        val be = ByteArray(bytes.size + 1)
        for (i in bytes.indices) be[bytes.size - i] = bytes[i]
        return BigInteger(be) // leading zero byte keeps it non-negative
    }
}
