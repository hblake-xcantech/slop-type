# agekt

A small, dependency-free Kotlin/JVM implementation of the
[age file format](https://age-encryption.org/v1) ([spec](https://github.com/C2SP/C2SP/blob/main/age.md))
for native **X25519 recipients only**. It is used by the Slop Type daemon to encrypt logs before
upload, and it is interoperable with the reference `age` CLI in both directions.

It has no Android dependencies and only uses `java.security` / `javax.crypto` / `java.util.Base64`
APIs that exist on Android 9+ (API 28) and JDK 11+:

- X25519 is implemented in pure Kotlin (RFC 7748, `BigInteger` Montgomery ladder) because Android's
  `javax.crypto` only gained XDH in API 33.
- HKDF-SHA-256 / HMAC-SHA-256 via `javax.crypto.Mac("HmacSHA256")`.
- ChaCha20-Poly1305 via `javax.crypto.Cipher("ChaCha20-Poly1305")` (Android 28+, JDK 11+).
- Bech32 for `age1...` recipients and `AGE-SECRET-KEY-1...` identities.

## API

Package `dev.sloptype.agekt`.

```kotlin
object Age {
    fun generateIdentity(): AgeIdentity
    fun encrypt(plaintext: InputStream, out: OutputStream, recipients: List<String>)
    fun encrypt(plaintext: ByteArray, recipients: List<String>): ByteArray
    fun decrypt(ciphertext: InputStream, out: OutputStream, identity: String)
    fun decrypt(ciphertext: ByteArray, identity: String): ByteArray
}

class AgeIdentity(val secretKey: String /* AGE-SECRET-KEY-1... */, val publicKey: String /* age1... */) {
    companion object {
        fun fromSecretKey(secretKey: String): AgeIdentity   // derives the public key
        fun fromScalar(scalar: ByteArray): AgeIdentity      // raw 32-byte X25519 private key
    }
}

class AgeException(message: String, cause: Throwable? = null) : RuntimeException
```

```kotlin
val id = Age.generateIdentity()
val ciphertext = Age.encrypt("hello".toByteArray(), listOf(id.publicKey, "age1...someone-else"))
val plaintext = Age.decrypt(ciphertext, id.secretKey)

// Streaming (constant memory, 64 KiB chunks):
FileInputStream(log).use { input -> FileOutputStream(enc).use { out -> Age.encrypt(input, out, recipients) } }
```

Errors (malformed input, no matching identity, bad header MAC, tampered/truncated payload) are
reported as `AgeException`. All output is written to the stream only after the header is complete;
on a decryption failure part of the plaintext may already have been written to `out`, so treat the
output as untrusted until `decrypt` returns normally (this matches the behaviour of `age -d`).

## Format details

Output is exactly the binary age v1 format:

```
age-encryption.org/v1
-> X25519 <base64 ephemeral share>
<base64 wrapped file key>
--- <base64 header HMAC>
<16-byte nonce><STREAM payload>
```

- One `X25519` stanza per recipient, each with a fresh ephemeral key.
- Base64 is standard-alphabet, unpadded; stanza bodies are wrapped at 64 columns.
- Payload uses the STREAM construction: 64 KiB plaintext chunks, ChaCha20-Poly1305 with a nonce
  of an 11-byte big-endian counter plus a last-chunk flag byte, payload key from
  `HKDF-SHA-256(ikm = file key, salt = nonce, info = "payload")`.
- Unknown stanza types (e.g. grease, scrypt, ssh-ed25519) are parsed and ignored on decrypt.

## Limits

- Only X25519 recipients/identities. Passphrase (`scrypt`) and `ssh-*` recipients are not supported;
  files encrypted only to those cannot be decrypted (`AgeException`).
- No ASCII armor (`-a` / PEM) support; only the binary format.
- The X25519 implementation uses `BigInteger` and is **not constant-time**. It is fine for
  encrypting log files to a fixed recipient on a phone, but do not use it where a local timing
  side channel on the secret key matters.
- Payload chunks are processed one at a time (~64 KiB buffers), so arbitrarily large files are fine,
  but the whole header is read into memory.

## Tests

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH
./gradlew :agekt:test
```

- `X25519Test`: RFC 7748 section 5.2 vectors (incl. the 1000-iteration vector), section 6.1
  Diffie-Hellman, and a random cross-check against the JDK's own XDH when available.
- `Bech32Test`: encode/decode round trips, checksum/mixed-case rejection, a key pair from `age-keygen`.
- `AgeRoundTripTest`: round trips at 0 B, 1 B, 64 KiB - 1, exactly 64 KiB, 64 KiB + 1, 200 KiB and
  more; header layout; multiple recipients; tamper/truncation/MAC detection; unknown stanzas; and
  decryption of a ciphertext produced by the `age` CLI (embedded fixture, no CLI needed).
- `AgeCliInteropTest`: skipped unless `age` and `age-keygen` are on `PATH` (or in
  `/opt/homebrew/bin` / `/usr/local/bin`). Generates keys with `age-keygen`, checks the derived
  public key, encrypts with agekt and decrypts with `age -d`, and the reverse, at the same sizes
  including multi-chunk payloads and multi-recipient files.
