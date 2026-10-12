package dev.sloptype.agekt

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC-SHA-256 and HKDF-SHA-256 (RFC 5869) on top of javax.crypto. */
internal object Hkdf {
    private const val HMAC = "HmacSHA256"
    private const val HASH_LEN = 32

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        // SecretKeySpec rejects empty keys; HMAC pads an empty key to a block of zeros anyway.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(HASH_LEN) else key, HMAC))
        return mac.doFinal(data)
    }

    /** HKDF-Extract + HKDF-Expand. An empty [salt] is treated as HashLen zero bytes per RFC 5869. */
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * HASH_LEN))
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ikm)
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(prk, HMAC))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        return out
    }
}
