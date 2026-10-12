package dev.sloptype.agekt

import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.KeyAgreement
import kotlin.test.assertEquals

class X25519Test {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun rfc7748Section52Vector1() {
        val k = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val u = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        assertEquals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", X25519.scalarMult(k, u).hex())
    }

    @Test
    fun rfc7748Section52Vector2() {
        val k = hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d")
        val u = hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493")
        assertEquals("95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957", X25519.scalarMult(k, u).hex())
    }

    @Test
    fun rfc7748Section52IteratedVectors() {
        var k = X25519.BASEPOINT.copyOf()
        var u = X25519.BASEPOINT.copyOf()
        for (i in 1..1000) {
            val r = X25519.scalarMult(k, u)
            u = k
            k = r
            if (i == 1) assertEquals("422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079", k.hex())
        }
        assertEquals("684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51", k.hex())
    }

    @Test
    fun rfc7748Section61DiffieHellman() {
        val a = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val b = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val aPub = X25519.publicKey(a)
        val bPub = X25519.publicKey(b)
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", aPub.hex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", bPub.hex())
        val expected = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(expected, X25519.scalarMult(a, bPub).hex())
        assertEquals(expected, X25519.scalarMult(b, aPub).hex())
    }

    /** Cross-checks random inputs against the JDK's XDH implementation when it is available (JDK 11+). */
    @Test
    fun matchesJdkXdhOnRandomInputs() {
        val kf = try {
            KeyFactory.getInstance("XDH")
        } catch (e: Exception) {
            assumeTrue("JDK XDH not available", false); return
        }
        val rnd = java.security.SecureRandom()
        repeat(20) {
            val scalar = ByteArray(32).also { rnd.nextBytes(it) }
            val peerScalar = ByteArray(32).also { rnd.nextBytes(it) }
            val peerPublic = X25519.publicKey(peerScalar)

            val priv = kf.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, scalar))
            val uLe = peerPublic.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }
            val pub = kf.generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger(1, uLe.reversedArray())))
            val ka = KeyAgreement.getInstance("X25519")
            ka.init(priv)
            ka.doPhase(pub, true)
            assertArrayEquals(ka.generateSecret(), X25519.scalarMult(scalar, peerPublic))
        }
    }
}
