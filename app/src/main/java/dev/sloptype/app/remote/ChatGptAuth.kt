package dev.sloptype.app.remote

import android.content.Context
import android.util.Log
import dev.sloptype.app.SlopSecrets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.math.BigInteger
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID

/**
 * "Sign in with ChatGPT" plan-usage (token sharing) for open-source local tools.
 *
 * Endpoints and parameters follow
 *  - https://developers.openai.com/siwc/token-sharing-open-source (host id, flow outline)
 *  - https://developers.openai.com/siwc/token-sharing-open-source/sign-in (authorize/token/callback)
 *  - https://developers.openai.com/siwc/token-sharing-open-source/token-reference (token fields/lifetimes)
 *  - https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions (refresh/revoke)
 *  - https://auth.openai.com/.well-known/openid-configuration (endpoint URLs, verified 2026-10-12)
 *
 * Tokens live in `SlopSecrets.chatgptOauth` as a JsonObject:
 * {ext_agent_host_id, client_id, access_token, refresh_token, expires_at (ms), id_token, account_id, email, scope}.
 * The host id and issued client id survive sign-out (the registration is kept, per the docs).
 */
object ChatGptAuth {
    private const val TAG = "ChatGptAuth"

    const val ISSUER = "https://auth.openai.com"
    const val AUTHORIZE_URL = "$ISSUER/api/accounts/authorize"
    const val TOKEN_URL = "$ISSUER/api/accounts/oauth/token"
    const val REVOKE_URL = "$ISSUER/api/accounts/oauth/revoke"
    const val JWKS_URL = "$ISSUER/.well-known/jwks.json"
    const val RESOURCE = "https://api.openai.com/v1"
    const val SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    const val REQUIRED_SCOPE = "chatgpt.tokens.use.direct"
    const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
    const val AGENT_NAME_HINT = "Slop Type"
    const val CALLBACK_PATH = "/auth/callback"
    /** Port the docs require for the initial dynamic registration; later sign-ins may use any port. */
    const val DEFAULT_PORT = 1455
    const val REFRESH_MARGIN_MS = 5 * 60 * 1000L
    private val CLIENT_ID_RE = Regex("^[a-zA-Z0-9_-]{1,200}$")

    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val lock = Any()
    @Volatile private var jwksCache: JsonObject? = null

    // ------------------------------------------------------------------ storage

    data class Stored(
        val hostId: String = "",
        val clientId: String = "",
        val accessToken: String = "",
        val refreshToken: String = "",
        val expiresAt: Long = 0,
        val idToken: String = "",
        val accountId: String = "",
        val email: String = "",
        val scope: String = "",
    ) {
        val signedIn: Boolean get() = accessToken.isNotBlank() && refreshToken.isNotBlank() && clientId.isNotBlank()
        val hasPlanScope: Boolean get() = scope.split(' ', '+').any { it == REQUIRED_SCOPE }

        fun toJson(): JsonObject = buildJsonObject {
            put("ext_agent_host_id", hostId); put("client_id", clientId)
            put("access_token", accessToken); put("refresh_token", refreshToken); put("expires_at", expiresAt)
            put("id_token", idToken); put("account_id", accountId); put("email", email); put("scope", scope)
        }

        companion object {
            fun fromJson(o: JsonObject?): Stored {
                if (o == null) return Stored()
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull ?: ""
                return Stored(
                    hostId = s("ext_agent_host_id"), clientId = s("client_id"), accessToken = s("access_token"),
                    refreshToken = s("refresh_token"), expiresAt = (o["expires_at"] as? JsonPrimitive)?.longOrNull ?: 0L,
                    idToken = s("id_token"), accountId = s("account_id"), email = s("email"), scope = s("scope"),
                )
            }
        }
    }

    fun load(ctx: Context): Stored = Stored.fromJson(SlopSecrets.load(ctx).chatgptOauth)

    fun save(ctx: Context, s: Stored) = SlopSecrets.update(ctx) { it.copy(chatgptOauth = s.toJson()) }

    fun isSignedIn(ctx: Context): Boolean = load(ctx).signedIn

    /** The persisted `ext_agent_host_id` (`urn:uuid:…`), created on first use. Never user-identifying. */
    fun hostId(ctx: Context): String = synchronized(lock) {
        val s = load(ctx)
        if (s.hostId.isNotBlank()) return s.hostId
        val id = newHostId()
        save(ctx, s.copy(hostId = id))
        id
    }

    fun newHostId(): String = "urn:uuid:" + UUID.randomUUID().toString()

    /** Drop tokens (best-effort remote revocation) but keep host id + client registration. */
    fun signOut(ctx: Context): Boolean {
        val s = load(ctx)
        var revoked = false
        if (s.refreshToken.isNotBlank() && s.clientId.isNotBlank()) {
            revoked = runCatching {
                val r = Http.postForm(REVOKE_URL, mapOf("token" to s.refreshToken, "token_type_hint" to "refresh_token", "client_id" to s.clientId), timeoutMs = 10_000)
                r.ok
            }.getOrDefault(false)
        }
        save(ctx, Stored(hostId = s.hostId, clientId = s.clientId))
        return revoked
    }

    // ------------------------------------------------------------------ access tokens

    /**
     * A usable plan-usage access token, refreshed synchronously when within [REFRESH_MARGIN_MS]
     * of expiry. Call on a background thread. Returns null when signed out or refresh fails and
     * the old token is already expired.
     */
    fun accessToken(ctx: Context): String? = synchronized(lock) {
        val s = load(ctx)
        if (!s.signedIn) return null
        val now = System.currentTimeMillis()
        if (s.expiresAt - now > REFRESH_MARGIN_MS) return s.accessToken
        val refreshed = refresh(ctx, s)
        if (refreshed != null) return refreshed.accessToken
        return if (s.expiresAt > now) s.accessToken else null
    }

    /** Refresh-token grant (profiles-and-sessions docs). Rotates the refresh token. */
    fun refresh(ctx: Context, current: Stored = load(ctx)): Stored? = synchronized(lock) {
        if (current.refreshToken.isBlank() || current.clientId.isBlank()) return null
        val form = mapOf(
            "grant_type" to "refresh_token",
            "client_id" to current.clientId,
            "refresh_token" to current.refreshToken,
            "resource" to RESOURCE,
        )
        val r = try { Http.postForm(TOKEN_URL, form, timeoutMs = 15_000) } catch (e: IOException) { Log.w(TAG, "refresh failed: $e"); return null }
        if (!r.ok) {
            Log.w(TAG, "refresh HTTP ${r.status}")
            if (r.status == 400 || r.status == 401) {
                val err = runCatching { json.parseToJsonElement(r.body).jsonObject["error"]?.let { (it as? JsonPrimitive)?.contentOrNull } }.getOrNull()
                if (err == "invalid_grant") save(ctx, current.copy(accessToken = "", refreshToken = "", expiresAt = 0, idToken = ""))
            }
            return null
        }
        val tok = parseTokenResponse(r.body) ?: return null
        val next = current.copy(
            accessToken = tok.accessToken,
            refreshToken = tok.refreshToken.ifBlank { current.refreshToken },
            expiresAt = System.currentTimeMillis() + tok.expiresIn * 1000L,
            idToken = tok.idToken.ifBlank { current.idToken },
            scope = tok.scope.ifBlank { current.scope },
        )
        save(ctx, next)
        next
    }

    // ------------------------------------------------------------------ pure helpers (unit tested)

    data class Pkce(val verifier: String, val challenge: String)

    fun newPkce(): Pkce {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = b64url(bytes)
        return Pkce(verifier, challengeFor(verifier))
    }

    fun challengeFor(verifier: String): String = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun randomToken(): String = b64url(ByteArray(24).also { SecureRandom().nextBytes(it) })

    fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun b64urlDecode(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trim())

    fun redirectUri(port: Int): String = "http://127.0.0.1:$port$CALLBACK_PATH"

    /** Authorization URL (sign-in docs, "Authorization request parameters"). */
    fun authorizeUrl(
        clientId: String, hostId: String, redirectUri: String, state: String, nonce: String, codeChallenge: String,
        idTokenHint: String? = null, loginHint: String? = null,
    ): String {
        val q = LinkedHashMap<String, String>()
        q["client_id"] = clientId
        if (clientId == DYNAMIC_CLIENT_ID) q["agent_name_hint"] = AGENT_NAME_HINT
        q["ext_agent_host_id"] = hostId
        q["response_type"] = "code"
        q["redirect_uri"] = redirectUri
        q["scope"] = SCOPE
        q["resource"] = RESOURCE
        q["state"] = state
        q["nonce"] = nonce
        q["code_challenge_method"] = "S256"
        q["code_challenge"] = codeChallenge
        if (!idTokenHint.isNullOrBlank()) q["id_token_hint"] = idTokenHint
        if (!loginHint.isNullOrBlank()) q["login_hint"] = loginHint
        return AUTHORIZE_URL + "?" + q.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
    }

    fun parseQuery(query: String?): Map<String, List<String>> {
        if (query.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MutableList<String>>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            val k = URLDecoder.decode(if (i < 0) pair else pair.substring(0, i), "UTF-8")
            val v = URLDecoder.decode(if (i < 0) "" else pair.substring(i + 1), "UTF-8")
            out.getOrPut(k) { ArrayList() }.add(v)
        }
        return out
    }

    data class Callback(val code: String, val clientId: String, val scope: String)

    class AuthException(message: String) : Exception(message)

    /**
     * Validate the loopback callback query (sign-in docs, "Callback"): state must match exactly
     * once; `error` aborts; `code` exactly once; `client_id` at most once, must equal the known
     * issued id when both exist, must never be the dynamic placeholder, and must be present for
     * a new registration.
     */
    fun validateCallback(params: Map<String, List<String>>, expectedState: String, knownClientId: String?): Callback {
        val states = params["state"] ?: emptyList()
        if (states.size != 1 || !constantTimeEquals(states[0], expectedState)) throw AuthException("state mismatch")
        params["error"]?.firstOrNull()?.let { err ->
            val desc = params["error_description"]?.firstOrNull()
            throw AuthException(if (err == "access_denied") "sign-in cancelled" else "authorization error: $err${desc?.let { " ($it)" } ?: ""}")
        }
        val codes = params["code"] ?: emptyList()
        if (codes.size != 1 || codes[0].isBlank()) throw AuthException("registration_incomplete: missing code")
        val ids = params["client_id"] ?: emptyList()
        if (ids.size > 1) throw AuthException("registration_incomplete: duplicate client_id")
        val issued = ids.firstOrNull()
        if (issued != null) {
            if (issued == DYNAMIC_CLIENT_ID || !CLIENT_ID_RE.matches(issued)) throw AuthException("registration_incomplete: bad client_id")
            if (!knownClientId.isNullOrBlank() && knownClientId != issued) throw AuthException("registration_incomplete: client_id changed")
        }
        val clientId = issued ?: knownClientId
        if (clientId.isNullOrBlank()) throw AuthException("registration_incomplete: no client_id issued")
        return Callback(codes[0], clientId, params["scope"]?.firstOrNull() ?: "")
    }

    fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray(); val y = b.toByteArray()
        if (x.size != y.size) return false
        var d = 0
        for (i in x.indices) d = d or (x[i].toInt() xor y[i].toInt())
        return d == 0
    }

    data class TokenResponse(val accessToken: String, val refreshToken: String, val idToken: String, val expiresIn: Long, val scope: String, val tokenType: String)

    fun parseTokenResponse(body: String): TokenResponse? {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull ?: ""
        val access = s("access_token")
        if (access.isBlank()) return null
        return TokenResponse(access, s("refresh_token"), s("id_token"), (o["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600L, s("scope"), s("token_type"))
    }

    /** Form body for the authorization_code grant (sign-in docs, "Token exchange"). */
    fun exchangeForm(clientId: String, code: String, codeVerifier: String, redirectUri: String): Map<String, String> = mapOf(
        "grant_type" to "authorization_code",
        "client_id" to clientId,
        "code" to code,
        "code_verifier" to codeVerifier,
        "redirect_uri" to redirectUri,
        "resource" to RESOURCE,
    )

    fun exchangeCode(clientId: String, code: String, codeVerifier: String, redirectUri: String): TokenResponse {
        val r = Http.postForm(TOKEN_URL, exchangeForm(clientId, code, codeVerifier, redirectUri), timeoutMs = 20_000)
        if (!r.ok) throw AuthException("token exchange failed: " + Http.describeFailure(r))
        return parseTokenResponse(r.body) ?: throw AuthException("token exchange: unparsable response")
    }

    // ------------------------------------------------------------------ ID token

    object Jwt {
        fun parts(jwt: String): List<String> = jwt.split('.').also { if (it.size != 3) throw AuthException("malformed id_token") }
        fun header(jwt: String): JsonObject = json.parseToJsonElement(String(b64urlDecode(parts(jwt)[0]), Charsets.UTF_8)).jsonObject
        fun payload(jwt: String): JsonObject = json.parseToJsonElement(String(b64urlDecode(parts(jwt)[1]), Charsets.UTF_8)).jsonObject

        /** RS256 verification against a JWKS document ({"keys":[{kty:"RSA",kid,n,e}]}). */
        fun verifyRs256(jwt: String, jwks: JsonObject): Boolean {
            val p = parts(jwt)
            val hdr = header(jwt)
            val alg = (hdr["alg"] as? JsonPrimitive)?.contentOrNull
            if (alg != "RS256") throw AuthException("unsupported id_token alg $alg")
            val kid = (hdr["kid"] as? JsonPrimitive)?.contentOrNull
            val keys = (jwks["keys"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
            val candidates = keys.filter { (it["kty"] as? JsonPrimitive)?.contentOrNull == "RSA" && (kid == null || (it["kid"] as? JsonPrimitive)?.contentOrNull == kid) }
            val signed = (p[0] + "." + p[1]).toByteArray(Charsets.US_ASCII)
            val sig = b64urlDecode(p[2])
            for (k in candidates) {
                val n = BigInteger(1, b64urlDecode((k["n"] as? JsonPrimitive)?.contentOrNull ?: continue))
                val e = BigInteger(1, b64urlDecode((k["e"] as? JsonPrimitive)?.contentOrNull ?: continue))
                val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
                val v = Signature.getInstance("SHA256withRSA").apply { initVerify(pub); update(signed) }
                if (runCatching { v.verify(sig) }.getOrDefault(false)) return true
            }
            return false
        }
    }

    fun fetchJwks(): JsonObject {
        jwksCache?.let { return it }
        val r = Http.getJson(JWKS_URL, timeoutMs = 15_000)
        if (!r.ok) throw AuthException("jwks fetch failed: HTTP ${r.status}")
        return json.parseToJsonElement(r.body).jsonObject.also { jwksCache = it }
    }

    data class Identity(val subject: String, val email: String)

    /**
     * Validate issuer, audience (= issued client id), expiry and nonce, and the RS256 signature
     * against [jwks]. Returns the verified subject/email.
     */
    fun validateIdToken(idToken: String, clientId: String, nonce: String, jwks: JsonObject, nowSec: Long = System.currentTimeMillis() / 1000): Identity {
        if (!Jwt.verifyRs256(idToken, jwks)) throw AuthException("id_token signature invalid")
        val c = Jwt.payload(idToken)
        fun s(k: String) = (c[k] as? JsonPrimitive)?.contentOrNull
        if (s("iss") != ISSUER) throw AuthException("id_token issuer mismatch")
        val aud = c["aud"]
        val audOk = when (aud) {
            is JsonPrimitive -> aud.contentOrNull == clientId
            is JsonArray -> aud.any { (it as? JsonPrimitive)?.contentOrNull == clientId }
            else -> false
        }
        if (!audOk) throw AuthException("id_token audience mismatch")
        val exp = (c["exp"] as? JsonPrimitive)?.longOrNull ?: 0L
        if (exp < nowSec - 60) throw AuthException("id_token expired")
        if (s("nonce") != nonce) throw AuthException("id_token nonce mismatch")
        val sub = s("sub") ?: throw AuthException("id_token without sub")
        return Identity(sub, s("email") ?: "")
    }

    /** Scope check required before any inference (sign-in docs, "Plan permission"). */
    fun hasPlanScope(scope: String): Boolean = scope.split(' ', '+').any { it == REQUIRED_SCOPE }
}
