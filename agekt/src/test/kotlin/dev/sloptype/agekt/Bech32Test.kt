package dev.sloptype.agekt

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Bech32Test {

    @Test
    fun roundTripLowerAndUpper() {
        val data = ByteArray(32) { (it * 7 + 3).toByte() }
        val pub = Bech32.encode("age", data)
        assert(pub.startsWith("age1"))
        assertEquals("age", Bech32.decode(pub).hrp)
        assertArrayEquals(data, Bech32.decode(pub).data)

        val sec = Bech32.encode("AGE-SECRET-KEY-", data)
        assert(sec.startsWith("AGE-SECRET-KEY-1"))
        assertEquals(sec, sec.uppercase())
        assertEquals("age-secret-key-", Bech32.decode(sec).hrp)
        assertArrayEquals(data, Bech32.decode(sec).data)
    }

    @Test
    fun rejectsBadChecksumAndMixedCase() {
        val pub = Bech32.encode("age", ByteArray(32))
        val corrupted = pub.substring(0, pub.length - 1) + (if (pub.last() == 'q') 'p' else 'q')
        assertFailsWith<AgeException> { Bech32.decode(corrupted) }
        assertFailsWith<AgeException> { Bech32.decode("Age1" + pub.substring(4)) }
    }

    @Test
    fun knownAgeKeygenPair() {
        // Generated with `age-keygen` (age v1.3.2); throwaway test key.
        val identity = AgeIdentity.fromSecretKey(Fixtures.SECRET_KEY)
        assertEquals(Fixtures.PUBLIC_KEY, identity.publicKey)
    }
}
