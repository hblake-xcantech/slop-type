package dev.sloptype.app.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.math.BigInteger
import java.net.URI
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatGptAuthTest {
    @Test fun pkceRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptAuth.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val p = ChatGptAuth.newPkce()
        assertTrue(p.verifier.length >= 43); assertEquals(ChatGptAuth.challengeFor(p.verifier), p.challenge)
    }

    @Test fun authorizeUrlParameters() {
        val url = ChatGptAuth.authorizeUrl(ChatGptAuth.DYNAMIC_CLIENT_ID, "urn:uuid:abc", ChatGptAuth.redirectUri(1455), "st", "nn", "ch")
        assertTrue(url.startsWith("https://auth.openai.com/api/accounts/authorize?"))
        val q = ChatGptAuth.parseQuery(URI(url).rawQuery)
        assertEquals("dynamic_agent_client", q["client_id"]!![0])
        assertEquals("Slop Type", q["agent_name_hint"]!![0])
        assertEquals("urn:uuid:abc", q["ext_agent_host_id"]!![0])
        assertEquals("code", q["response_type"]!![0])
        assertEquals("http://127.0.0.1:1455/auth/callback", q["redirect_uri"]!![0])
        assertEquals("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", q["scope"]!![0])
        assertEquals("https://api.openai.com/v1", q["resource"]!![0])
        assertEquals("S256", q["code_challenge_method"]!![0])
        assertEquals("ch", q["code_challenge"]!![0]); assertEquals("st", q["state"]!![0]); assertEquals("nn", q["nonce"]!![0])
        // reauth: issued client id, no agent_name_hint, hints present
        val q2 = ChatGptAuth.parseQuery(URI(ChatGptAuth.authorizeUrl("oaiapp_1", "urn:uuid:abc", ChatGptAuth.redirectUri(5000), "s", "n", "c", idTokenHint = "tok", loginHint = "a@b.c")).rawQuery)
        assertEquals("oaiapp_1", q2["client_id"]!![0]); assertEquals(null, q2["agent_name_hint"]); assertEquals("tok", q2["id_token_hint"]!![0]); assertEquals("a@b.c", q2["login_hint"]!![0])
    }

    @Test fun callbackValidation() {
        val ok = ChatGptAuth.validateCallback(ChatGptAuth.parseQuery("code=abc&scope=chatgpt.tokens.use.direct+openid&state=S1&client_id=oaiapp_x"), "S1", null)
        assertEquals("abc", ok.code); assertEquals("oaiapp_x", ok.clientId); assertTrue(ChatGptAuth.hasPlanScope(ok.scope))
        assertFailsWith<ChatGptAuth.AuthException> { ChatGptAuth.validateCallback(ChatGptAuth.parseQuery("code=abc&state=WRONG&client_id=oaiapp_x"), "S1", null) }
        assertFailsWith<ChatGptAuth.AuthException> { ChatGptAuth.validateCallback(ChatGptAuth.parseQuery("error=access_denied&state=S1"), "S1", null) }
        assertFailsWith<ChatGptAuth.AuthException> { ChatGptAuth.validateCallback(ChatGptAuth.parseQuery("code=abc&state=S1"), "S1", null) }  // new registration without client_id
        assertFailsWith<ChatGptAuth.AuthException> { ChatGptAuth.validateCallback(ChatGptAuth.parseQuery("code=abc&state=S1&client_id=dynamic_agent_client"), "S1", null) }
        assertFailsWith<ChatGptAuth.AuthException> { ChatGptAuth.validateCallback(ChatGptAuth.parseQuery("code=abc&state=S1&client_id=oaiapp_other"), "S1", "oaiapp_x") }
        // reauth may omit client_id
        assertEquals("oaiapp_x", ChatGptAuth.validateCallback(ChatGptAuth.parseQuery("code=abc&state=S1"), "S1", "oaiapp_x").clientId)
    }

    @Test fun tokenAndForms() {
        val t = assertNotNull(ChatGptAuth.parseTokenResponse("""{"access_token":"a","refresh_token":"r","id_token":"i","token_type":"Bearer","expires_in":3600,"scope":"openid chatgpt.tokens.use.direct"}"""))
        assertEquals("a", t.accessToken); assertEquals(3600L, t.expiresIn); assertTrue(ChatGptAuth.hasPlanScope(t.scope))
        assertEquals(mapOf("grant_type" to "authorization_code", "client_id" to "c", "code" to "k", "code_verifier" to "v", "redirect_uri" to "http://127.0.0.1:1455/auth/callback", "resource" to "https://api.openai.com/v1"),
            ChatGptAuth.exchangeForm("c", "k", "v", "http://127.0.0.1:1455/auth/callback"))
        val s = ChatGptAuth.Stored(hostId = "h", clientId = "c", accessToken = "a", refreshToken = "r", expiresAt = 5, scope = "chatgpt.tokens.use.direct")
        assertEquals(s, ChatGptAuth.Stored.fromJson(s.toJson()))
        assertTrue(s.signedIn && s.hasPlanScope)
        assertTrue(ChatGptAuth.newHostId().startsWith("urn:uuid:"))
    }

    @Test fun idTokenVerification() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pub = kp.public as RSAPublicKey
        fun b64(b: ByteArray) = ChatGptAuth.b64url(b)
        fun unsigned(bi: BigInteger): ByteArray { val b = bi.toByteArray(); return if (b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b }
        val jwks = buildJsonObject { put("keys", buildJsonArray { add(buildJsonObject { put("kty", "RSA"); put("kid", "k1"); put("alg", "RS256"); put("n", b64(unsigned(pub.modulus))); put("e", b64(unsigned(pub.publicExponent))) }) }) }
        val now = System.currentTimeMillis() / 1000
        fun jwt(payload: String): String {
            val h = b64("""{"alg":"RS256","kid":"k1"}""".toByteArray()); val p = b64(payload.toByteArray())
            val sig = Signature.getInstance("SHA256withRSA").apply { initSign(kp.private); update("$h.$p".toByteArray()) }.sign()
            return "$h.$p.${b64(sig)}"
        }
        val good = jwt("""{"iss":"https://auth.openai.com","aud":"oaiapp_x","sub":"user_1","email":"u@x.y","nonce":"N","exp":${now + 600}}""")
        val id = ChatGptAuth.validateIdToken(good, "oaiapp_x", "N", jwks, now)
        assertEquals("user_1", id.subject); assertEquals("u@x.y", id.email)
        assertFailsWith<ChatGptAuth.AuthException> { ChatGptAuth.validateIdToken(good, "oaiapp_other", "N", jwks, now) }
        assertFailsWith<ChatGptAuth.AuthException> { ChatGptAuth.validateIdToken(good, "oaiapp_x", "WRONG", jwks, now) }
        assertFailsWith<ChatGptAuth.AuthException> { ChatGptAuth.validateIdToken(good, "oaiapp_x", "N", jwks, now + 10_000) }
        val tampered = good.substring(0, good.lastIndexOf('.') + 1) + b64(ByteArray(256))
        assertFailsWith<ChatGptAuth.AuthException> { ChatGptAuth.validateIdToken(tampered, "oaiapp_x", "N", jwks, now) }
        assertEquals("k1", Json.parseToJsonElement(String(ChatGptAuth.b64urlDecode(good.split('.')[0]))).jsonObject["kid"].toString().trim('"'))
    }
}
