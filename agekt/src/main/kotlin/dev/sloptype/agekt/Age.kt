package dev.sloptype.agekt

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and decrypts files in the age format (https://age-encryption.org/v1)
 * for native X25519 recipients (`age1...`) and identities (`AGE-SECRET-KEY-1...`).
 *
 * Scrypt (passphrase) and ssh recipients are not supported.
 */
object Age {
    private const val VERSION_LINE = "age-encryption.org/v1"
    private const val STANZA_PREFIX = "-> "
    private const val FOOTER_PREFIX = "--- "
    private const val X25519_TYPE = "X25519"
    private const val X25519_INFO = "age-encryption.org/v1/X25519"
    private const val FILE_KEY_SIZE = 16
    private const val NONCE_SIZE = 16
    private const val TAG_SIZE = 16
    private const val CHUNK_SIZE = 64 * 1024
    private const val BODY_LINE_WIDTH = 64
    private const val MAX_HEADER_LINE = 1 shl 16

    private val random = SecureRandom()
    private val b64 = Base64.getEncoder().withoutPadding()
    private val b64Decoder = Base64.getDecoder()

    // ---------------------------------------------------------------- public API

    /** Generates a new random X25519 identity. */
    fun generateIdentity(): AgeIdentity {
        val scalar = ByteArray(32).also { random.nextBytes(it) }
        return AgeIdentity.fromScalar(scalar)
    }

    /** Encrypts [plaintext] to every recipient in [recipients] (`age1...` strings), writing the age file to [out]. */
    fun encrypt(plaintext: InputStream, out: OutputStream, recipients: List<String>) {
        if (recipients.isEmpty()) throw AgeException("age: no recipients specified")
        val recipientKeys = recipients.map { AgeIdentity.decodePublicKey(it) }

        val fileKey = ByteArray(FILE_KEY_SIZE).also { random.nextBytes(it) }
        val header = ByteArrayOutputStream()
        header.write(VERSION_LINE.toByteArray(Charsets.US_ASCII))
        header.write('\n'.code)
        for (pk in recipientKeys) {
            header.write(wrapX25519(fileKey, pk))
        }
        header.write("---".toByteArray(Charsets.US_ASCII))
        val mac = Hkdf.hmacSha256(headerMacKey(fileKey), header.toByteArray())
        header.write(' '.code)
        header.write(b64.encode(mac))
        header.write('\n'.code)
        out.write(header.toByteArray())

        val nonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
        out.write(nonce)
        encryptPayload(payloadKey(fileKey, nonce), plaintext, out)
        out.flush()
    }

    fun encrypt(plaintext: ByteArray, recipients: List<String>): ByteArray {
        val out = ByteArrayOutputStream(plaintext.size + 512)
        encrypt(ByteArrayInputStream(plaintext), out, recipients)
        return out.toByteArray()
    }

    /** Decrypts the age file in [ciphertext] with the `AGE-SECRET-KEY-1...` [identity], writing the plaintext to [out]. */
    fun decrypt(ciphertext: InputStream, out: OutputStream, identity: String) {
        val scalar = AgeIdentity.decodeSecretKey(identity)
        val ourPublicKey = X25519.publicKey(scalar)

        val input = ciphertext as? BufferedInputStream ?: BufferedInputStream(ciphertext)
        val header = parseHeader(input)

        var fileKey: ByteArray? = null
        for (stanza in header.stanzas) {
            fileKey = unwrapX25519(stanza, scalar, ourPublicKey) ?: continue
            break
        }
        if (fileKey == null) throw AgeException("age: no identity matched any of the recipients")

        val expectedMac = Hkdf.hmacSha256(headerMacKey(fileKey), header.macInput)
        if (!MessageDigest.isEqual(expectedMac, header.mac)) throw AgeException("age: bad header MAC")

        val nonce = ByteArray(NONCE_SIZE)
        if (readFully(input, nonce, 0, NONCE_SIZE) != NONCE_SIZE) throw AgeException("age: truncated payload nonce")
        decryptPayload(payloadKey(fileKey, nonce), input, out)
        out.flush()
    }

    fun decrypt(ciphertext: ByteArray, identity: String): ByteArray {
        val out = ByteArrayOutputStream(maxOf(0, ciphertext.size - 200))
        decrypt(ByteArrayInputStream(ciphertext), out, identity)
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- X25519 recipient stanza

    /** Returns the complete `-> X25519 <share>\n<body>\n` stanza text for one recipient. */
    private fun wrapX25519(fileKey: ByteArray, recipientPublicKey: ByteArray): ByteArray {
        val ephemeralSecret = ByteArray(32).also { random.nextBytes(it) }
        val ephemeralShare = X25519.publicKey(ephemeralSecret)
        val sharedSecret = X25519.scalarMult(ephemeralSecret, recipientPublicKey)
        if (sharedSecret.all { it == 0.toByte() }) throw AgeException("age: X25519 shared secret is all zeros")
        val wrappingKey = Hkdf.derive(sharedSecret, ephemeralShare + recipientPublicKey, X25519_INFO.toByteArray(Charsets.US_ASCII), 32)
        val body = aead(Cipher.ENCRYPT_MODE, wrappingKey, ByteArray(12), fileKey)

        val sb = StringBuilder()
        sb.append(STANZA_PREFIX).append(X25519_TYPE).append(' ').append(b64.encodeToString(ephemeralShare)).append('\n')
        val encodedBody = b64.encodeToString(body)
        var i = 0
        while (true) {
            val end = minOf(i + BODY_LINE_WIDTH, encodedBody.length)
            sb.append(encodedBody, i, end).append('\n')
            if (end - i < BODY_LINE_WIDTH) break // the final line is always shorter than 64 chars
            i = end
        }
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    /** Tries to unwrap the file key from [stanza]; returns null if the stanza is not for this identity. */
    private fun unwrapX25519(stanza: Stanza, scalar: ByteArray, ourPublicKey: ByteArray): ByteArray? {
        if (stanza.type != X25519_TYPE) return null
        if (stanza.args.size != 1) throw AgeException("age: invalid X25519 recipient stanza: expected one argument")
        val ephemeralShare = decodeBase64(stanza.args[0])
        if (ephemeralShare.size != 32) throw AgeException("age: invalid X25519 recipient stanza: bad ephemeral share")
        if (stanza.body.size != FILE_KEY_SIZE + TAG_SIZE) throw AgeException("age: invalid X25519 recipient stanza: bad body length")

        val sharedSecret = X25519.scalarMult(scalar, ephemeralShare)
        if (sharedSecret.all { it == 0.toByte() }) throw AgeException("age: X25519 shared secret is all zeros")
        val wrappingKey = Hkdf.derive(sharedSecret, ephemeralShare + ourPublicKey, X25519_INFO.toByteArray(Charsets.US_ASCII), 32)
        return try {
            aead(Cipher.DECRYPT_MODE, wrappingKey, ByteArray(12), stanza.body)
        } catch (e: AEADBadTagException) {
            null
        }
    }

    // ---------------------------------------------------------------- header

    private class Stanza(val type: String, val args: List<String>, val body: ByteArray)

    private class Header(val stanzas: List<Stanza>, val macInput: ByteArray, val mac: ByteArray)

    private fun parseHeader(input: InputStream): Header {
        val raw = ByteArrayOutputStream()
        val version = readLine(input, raw)
        if (version != VERSION_LINE) throw AgeException("age: unsupported or malformed header: expected '$VERSION_LINE'")

        val stanzas = ArrayList<Stanza>()
        while (true) {
            val line = readLine(input, raw)
            if (line.startsWith(FOOTER_PREFIX)) {
                val mac = decodeBase64(line.substring(FOOTER_PREFIX.length))
                if (mac.size != 32) throw AgeException("age: malformed header MAC")
                val bytes = raw.toByteArray()
                // The MAC covers everything up to and including the "---" of the footer line.
                val macInput = bytes.copyOf(bytes.size - line.length - 1 + 3)
                return Header(stanzas, macInput, mac)
            }
            if (!line.startsWith(STANZA_PREFIX)) throw AgeException("age: malformed header: unexpected line")
            val args = line.substring(STANZA_PREFIX.length).split(' ')
            if (args.isEmpty() || args.any { it.isEmpty() || it.any { c -> c.code !in 33..126 } }) {
                throw AgeException("age: malformed stanza arguments")
            }
            val body = ByteArrayOutputStream()
            while (true) {
                val bodyLine = readLine(input, raw)
                if (bodyLine.length > BODY_LINE_WIDTH) throw AgeException("age: malformed stanza body line")
                body.write(decodeBase64(bodyLine))
                if (bodyLine.length < BODY_LINE_WIDTH) break
            }
            stanzas.add(Stanza(args[0], args.drop(1), body.toByteArray()))
        }
    }

    /** Reads one `\n`-terminated ASCII line (without the terminator) and appends the raw bytes (with `\n`) to [raw]. */
    private fun readLine(input: InputStream, raw: ByteArrayOutputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw AgeException("age: unexpected end of header")
            raw.write(b)
            if (b == '\n'.code) return sb.toString()
            if (b == '\r'.code || b >= 127 || b < 32) throw AgeException("age: malformed header: invalid character")
            if (sb.length >= MAX_HEADER_LINE) throw AgeException("age: header line too long")
            sb.append(b.toChar())
        }
    }

    private fun headerMacKey(fileKey: ByteArray): ByteArray =
        Hkdf.derive(fileKey, ByteArray(0), "header".toByteArray(Charsets.US_ASCII), 32)

    private fun payloadKey(fileKey: ByteArray, nonce: ByteArray): ByteArray =
        Hkdf.derive(fileKey, nonce, "payload".toByteArray(Charsets.US_ASCII), 32)

    // ---------------------------------------------------------------- STREAM payload

    private fun encryptPayload(key: ByteArray, plaintext: InputStream, out: OutputStream) {
        val chunk = ByteArray(CHUNK_SIZE)
        var n = readFully(plaintext, chunk, 0, CHUNK_SIZE)
        var counter = 0L
        while (true) {
            var last = n < CHUNK_SIZE
            var carry = -1
            if (!last) {
                carry = plaintext.read()
                last = carry < 0
            }
            out.write(aead(Cipher.ENCRYPT_MODE, key, streamNonce(counter, last), chunk.copyOf(n)))
            if (last) return
            counter++
            chunk[0] = carry.toByte()
            n = 1 + readFully(plaintext, chunk, 1, CHUNK_SIZE - 1)
        }
    }

    private fun decryptPayload(key: ByteArray, ciphertext: InputStream, out: OutputStream) {
        val chunkCipherSize = CHUNK_SIZE + TAG_SIZE
        val chunk = ByteArray(chunkCipherSize)
        var n = readFully(ciphertext, chunk, 0, chunkCipherSize)
        var counter = 0L
        while (true) {
            var last = n < chunkCipherSize
            var carry = -1
            if (!last) {
                carry = ciphertext.read()
                last = carry < 0
            }
            if (n < TAG_SIZE) throw AgeException("age: truncated payload chunk")
            if (n == TAG_SIZE && !(last && counter == 0L)) throw AgeException("age: empty non-final payload chunk")
            val plain = try {
                aead(Cipher.DECRYPT_MODE, key, streamNonce(counter, last), chunk.copyOf(n))
            } catch (e: AEADBadTagException) {
                throw AgeException("age: payload authentication failed (wrong key, corrupted or truncated file)", e)
            }
            out.write(plain)
            if (last) return
            counter++
            chunk[0] = carry.toByte()
            n = 1 + readFully(ciphertext, chunk, 1, chunkCipherSize - 1)
        }
    }

    /** 11-byte big-endian counter followed by the last-chunk flag byte. */
    private fun streamNonce(counter: Long, last: Boolean): ByteArray {
        val nonce = ByteArray(12)
        var c = counter
        for (i in 10 downTo 3) {
            nonce[i] = (c and 0xff).toByte()
            c = c ushr 8
        }
        nonce[11] = if (last) 1 else 0
        return nonce
    }

    // ---------------------------------------------------------------- helpers

    private fun aead(mode: Int, key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        return cipher.doFinal(data)
    }

    private fun decodeBase64(s: String): ByteArray {
        if (s.indexOf('=') >= 0) throw AgeException("age: malformed base64 (padding not allowed)")
        return try {
            b64Decoder.decode(s)
        } catch (e: IllegalArgumentException) {
            throw AgeException("age: malformed base64", e)
        }
    }

    /** Reads until [len] bytes are read or EOF; returns the number of bytes read. */
    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val r = input.read(buf, off + total, len - total)
            if (r < 0) break
            total += r
        }
        return total
    }
}
