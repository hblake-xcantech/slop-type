package dev.sloptype.agekt

/**
 * An X25519 age identity: the secret key (`AGE-SECRET-KEY-1...`) and the matching
 * recipient public key (`age1...`).
 */
class AgeIdentity(val secretKey: String, val publicKey: String) {

    override fun toString(): String = "AgeIdentity(publicKey=$publicKey)"

    companion object {
        internal const val SECRET_HRP = "AGE-SECRET-KEY-"
        internal const val PUBLIC_HRP = "age"

        /** Parses an `AGE-SECRET-KEY-1...` string and derives its public key. */
        fun fromSecretKey(secretKey: String): AgeIdentity {
            val scalar = decodeSecretKey(secretKey)
            return AgeIdentity(secretKey.trim(), encodePublicKey(X25519.publicKey(scalar)))
        }

        /** Builds an identity from a raw 32-byte X25519 private scalar. */
        fun fromScalar(scalar: ByteArray): AgeIdentity {
            require(scalar.size == 32) { "age: X25519 secret key must be 32 bytes" }
            return AgeIdentity(encodeSecretKey(scalar), encodePublicKey(X25519.publicKey(scalar)))
        }

        internal fun encodeSecretKey(scalar: ByteArray): String = Bech32.encode(SECRET_HRP, scalar)

        internal fun encodePublicKey(u: ByteArray): String = Bech32.encode(PUBLIC_HRP, u)

        internal fun decodeSecretKey(secretKey: String): ByteArray {
            val decoded = Bech32.decode(secretKey.trim())
            if (decoded.hrp != SECRET_HRP.lowercase()) throw AgeException("age: malformed secret key: unexpected prefix")
            if (decoded.data.size != 32) throw AgeException("age: malformed secret key: wrong length")
            return decoded.data
        }

        internal fun decodePublicKey(publicKey: String): ByteArray {
            val decoded = Bech32.decode(publicKey.trim())
            if (decoded.hrp != PUBLIC_HRP) throw AgeException("age: malformed recipient: unexpected prefix")
            if (decoded.data.size != 32) throw AgeException("age: malformed recipient: wrong length")
            return decoded.data
        }
    }
}
