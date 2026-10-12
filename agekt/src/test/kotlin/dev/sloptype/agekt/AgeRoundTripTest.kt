package dev.sloptype.agekt

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgeRoundTripTest {

    private fun randomBytes(n: Int, seed: Long = n.toLong()): ByteArray = ByteArray(n).also { Random(seed).nextBytes(it) }

    @Test
    fun generatedIdentityIsWellFormed() {
        val id = Age.generateIdentity()
        assertTrue(id.secretKey.startsWith("AGE-SECRET-KEY-1"))
        assertTrue(id.publicKey.startsWith("age1"))
        assertEquals(id.publicKey, AgeIdentity.fromSecretKey(id.secretKey).publicKey)
    }

    @Test
    fun roundTripVariousSizes() {
        val id = Age.generateIdentity()
        val sizes = listOf(0, 1, 17, 1000, 64 * 1024 - 1, 64 * 1024, 64 * 1024 + 1, 128 * 1024, 200 * 1024, 3 * 64 * 1024 + 5)
        for (n in sizes) {
            val plain = randomBytes(n)
            val ct = Age.encrypt(plain, listOf(id.publicKey))
            val expectedChunks = if (n == 0) 1 else (n + 64 * 1024 - 1) / (64 * 1024)
            val headerEnd = ct.indexOfSubsequence("---".toByteArray()) + 3 + 1 + 43 + 1
            assertEquals(16 + n + 16 * expectedChunks, ct.size - headerEnd, "payload size for n=$n")
            assertArrayEquals("n=$n", plain, Age.decrypt(ct, id.secretKey))
        }
    }

    @Test
    fun headerFormat() {
        val id = Age.generateIdentity()
        val ct = Age.encrypt("hi".toByteArray(), listOf(id.publicKey))
        val text = String(ct, Charsets.ISO_8859_1)
        val lines = text.split("\n")
        assertEquals("age-encryption.org/v1", lines[0])
        assertTrue(lines[1].startsWith("-> X25519 "))
        assertEquals(43, lines[1].removePrefix("-> X25519 ").length)
        assertEquals(43, lines[2].length)
        assertTrue(lines[3].startsWith("--- "))
        assertEquals(43, lines[3].removePrefix("--- ").length)
        assertFalse(text.substring(0, text.indexOf("--- ")).contains('='))
    }

    @Test
    fun multipleRecipients() {
        val a = Age.generateIdentity()
        val b = Age.generateIdentity()
        val c = Age.generateIdentity()
        val plain = randomBytes(5000)
        val ct = Age.encrypt(plain, listOf(a.publicKey, b.publicKey))
        assertArrayEquals(plain, Age.decrypt(ct, a.secretKey))
        assertArrayEquals(plain, Age.decrypt(ct, b.secretKey))
        assertFailsWith<AgeException> { Age.decrypt(ct, c.secretKey) }
    }

    @Test
    fun streamingApi() {
        val id = Age.generateIdentity()
        val plain = randomBytes(150 * 1024)
        val ct = ByteArrayOutputStream()
        Age.encrypt(ByteArrayInputStream(plain), ct, listOf(id.publicKey))
        val out = ByteArrayOutputStream()
        Age.decrypt(OneByteAtATime(ct.toByteArray()), out, id.secretKey)
        assertArrayEquals(plain, out.toByteArray())
    }

    @Test
    fun tamperingIsDetected() {
        val id = Age.generateIdentity()
        val plain = randomBytes(100 * 1024)
        val ct = Age.encrypt(plain, listOf(id.publicKey))

        // Flip a payload byte in the second chunk.
        val tampered = ct.copyOf().also { it[it.size - 10] = (it[it.size - 10].toInt() xor 1).toByte() }
        assertFailsWith<AgeException> { Age.decrypt(tampered, id.secretKey) }

        // Truncate the final chunk entirely: the first chunk is then not flagged as last.
        val truncated = ct.copyOf(ct.size - (plain.size - 64 * 1024) - 16)
        assertFailsWith<AgeException> { Age.decrypt(truncated, id.secretKey) }

        // Corrupt the header MAC.
        val macPos = ct.indexOfSubsequence("--- ".toByteArray()) + 5
        val badMac = ct.copyOf().also { it[macPos] = if (it[macPos] == 'A'.code.toByte()) 'B'.code.toByte() else 'A'.code.toByte() }
        assertFailsWith<AgeException> { Age.decrypt(badMac, id.secretKey) }
    }

    @Test
    fun decryptsCliProducedFixture() {
        assertEquals(Fixtures.CLI_PLAINTEXT, String(Age.decrypt(Fixtures.CLI_CIPHERTEXT, Fixtures.SECRET_KEY)))
    }

    @Test
    fun ignoresUnknownStanzas() {
        // Splice a multi-line "grease" stanza with an empty final body line into a valid header.
        val id = Age.generateIdentity()
        val ct = Age.encrypt("grease".toByteArray(), listOf(id.publicKey))
        val text = String(ct, Charsets.ISO_8859_1)
        val footer = text.indexOf("--- ")
        val grease = "-> grease-stanza 7b/x arg2\n" + "A".repeat(64) + "\n" + "B".repeat(64) + "\n\n"
        val spliced = text.substring(0, footer) + grease + text.substring(footer)
        // The MAC no longer matches, so decryption must fail with a MAC error, not a parse error.
        val e = assertFailsWith<AgeException> { Age.decrypt(spliced.toByteArray(Charsets.ISO_8859_1), id.secretKey) }
        assertTrue(e.message!!.contains("MAC"), e.message)
    }

    @Test
    fun rejectsBadInputs() {
        val id = Age.generateIdentity()
        assertFailsWith<AgeException> { Age.encrypt(ByteArray(1), emptyList()) }
        assertFailsWith<AgeException> { Age.encrypt(ByteArray(1), listOf("age1notavalidkey")) }
        assertFailsWith<AgeException> { Age.encrypt(ByteArray(1), listOf(id.secretKey)) }
        assertFailsWith<AgeException> { Age.decrypt("not an age file".toByteArray(), id.secretKey) }
        assertFailsWith<AgeException> { Age.decrypt(Age.encrypt(ByteArray(1), listOf(id.publicKey)), id.publicKey) }
    }

    private class OneByteAtATime(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
    }

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
