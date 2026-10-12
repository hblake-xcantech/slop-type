package dev.sloptype.agekt

import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * Interoperability with the reference `age` CLI. Skipped unless `age` and `age-keygen`
 * are found on PATH (or in the usual Homebrew locations).
 */
class AgeCliInteropTest {

    private lateinit var age: String
    private lateinit var ageKeygen: String
    private lateinit var dir: File

    @Before
    fun setUp() {
        val a = findExecutable("age")
        val k = findExecutable("age-keygen")
        assumeTrue("age CLI not found on PATH; skipping interop tests", a != null && k != null)
        age = a!!
        ageKeygen = k!!
        dir = Files.createTempDirectory("agekt-interop").toFile()
        dir.deleteOnExit()
    }

    private fun findExecutable(name: String): String? {
        val candidates = (System.getenv("PATH") ?: "").split(File.pathSeparator) + listOf("/opt/homebrew/bin", "/usr/local/bin")
        return candidates.map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }

    private fun run(vararg cmd: String, stdin: ByteArray? = null): ByteArray {
        val pb = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(false)
        val p = pb.start()
        val stderr = StringBuilder()
        val errThread = Thread { stderr.append(String(p.errorStream.readBytes())) }.also { it.start() }
        p.outputStream.use { if (stdin != null) it.write(stdin) }
        val out = p.inputStream.readBytes()
        check(p.waitFor(60, TimeUnit.SECONDS)) { "timeout running ${cmd.joinToString(" ")}" }
        errThread.join()
        check(p.exitValue() == 0) { "${cmd.joinToString(" ")} failed (${p.exitValue()}): $stderr" }
        return out
    }

    private class CliKey(val secretKey: String, val publicKey: String, val keyFile: File)

    private fun keygen(): CliKey {
        val keyFile = File(dir, "key-${System.nanoTime()}.txt")
        run(ageKeygen, "-o", keyFile.absolutePath)
        val lines = keyFile.readLines()
        val pub = lines.first { it.startsWith("# public key: ") }.removePrefix("# public key: ").trim()
        val sec = lines.first { it.startsWith("AGE-SECRET-KEY-1") }.trim()
        return CliKey(sec, pub, keyFile)
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { Random(n.toLong() * 31 + 7).nextBytes(it) }

    private val sizes = listOf(0, 1, 11, 4096, 64 * 1024 - 1, 64 * 1024, 64 * 1024 + 1, 200 * 1024)

    @Test
    fun keygenPublicKeyMatchesOurDerivation() {
        val key = keygen()
        assertEquals(key.publicKey, AgeIdentity.fromSecretKey(key.secretKey).publicKey)
    }

    @Test
    fun agektEncryptsAndCliDecrypts() {
        val key = keygen()
        for (n in sizes) {
            val plain = randomBytes(n)
            val ct = Age.encrypt(plain, listOf(key.publicKey))
            val ctFile = File(dir, "ours-$n.age").also { it.writeBytes(ct) }
            val decrypted = run(age, "-d", "-i", key.keyFile.absolutePath, ctFile.absolutePath)
            assertArrayEquals("size $n", plain, decrypted)
        }
    }

    @Test
    fun cliEncryptsAndAgektDecrypts() {
        val key = keygen()
        for (n in sizes) {
            val plain = randomBytes(n)
            val plainFile = File(dir, "plain-$n").also { it.writeBytes(plain) }
            val ctFile = File(dir, "theirs-$n.age")
            run(age, "-r", key.publicKey, "-o", ctFile.absolutePath, plainFile.absolutePath)
            assertArrayEquals("size $n", plain, Age.decrypt(ctFile.readBytes(), key.secretKey))
        }
    }

    @Test
    fun multiRecipientInBothDirections() {
        val k1 = keygen()
        val k2 = keygen()
        val ours = Age.generateIdentity()
        val plain = randomBytes(70 * 1024)

        val ct = Age.encrypt(plain, listOf(k1.publicKey, ours.publicKey, k2.publicKey))
        val ctFile = File(dir, "multi-ours.age").also { it.writeBytes(ct) }
        assertArrayEquals(plain, run(age, "-d", "-i", k1.keyFile.absolutePath, ctFile.absolutePath))
        assertArrayEquals(plain, run(age, "-d", "-i", k2.keyFile.absolutePath, ctFile.absolutePath))
        assertArrayEquals(plain, Age.decrypt(ct, ours.secretKey))

        val plainFile = File(dir, "multi-plain").also { it.writeBytes(plain) }
        val theirs = File(dir, "multi-theirs.age")
        run(age, "-r", k1.publicKey, "-r", ours.publicKey, "-o", theirs.absolutePath, plainFile.absolutePath)
        assertArrayEquals(plain, Age.decrypt(theirs.readBytes(), ours.secretKey))
        assertArrayEquals(plain, Age.decrypt(theirs.readBytes(), k1.secretKey))
    }

    /**
     * The age library may add random "grease" stanzas to headers; repeat a few CLI encryptions so any that appear
     * exercise the parser (age v1.3.2 was not observed to emit them; [AgeRoundTripTest.ignoresUnknownStanzas] covers it synthetically).
     */
    @Test
    fun repeatedCliEncryptionsDecrypt() {
        val key = keygen()
        val plain = "grease me".toByteArray()
        val plainFile = File(dir, "grease-plain").also { it.writeBytes(plain) }
        repeat(12) { i ->
            val ctFile = File(dir, "grease-$i.age")
            run(age, "-r", key.publicKey, "-o", ctFile.absolutePath, plainFile.absolutePath)
            assertArrayEquals(plain, Age.decrypt(ctFile.readBytes(), key.secretKey))
        }
    }
}
