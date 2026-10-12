package dev.sloptype.app.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.browser.customtabs.CustomTabsIntent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Runs the Sign in with ChatGPT loopback flow: starts an HTTP listener on 127.0.0.1 (port 1455
 * for the first registration, any free port afterwards), opens the authorization URL in a
 * Chrome Custom Tab, receives `/auth/callback`, exchanges the code (PKCE), validates the ID
 * token, stores tokens via [ChatGptAuth] and closes. Declared in the manifest as singleTask.
 * Launch with [start]; pass [EXTRA_SIGN_OUT] to revoke instead.
 */
class ChatGptSignInActivity : Activity() {
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var server: ServerSocket? = null
    @Volatile private var finished = false
    private var state = ""
    private var nonce = ""
    private var pkce: ChatGptAuth.Pkce? = null
    private var redirectUri = ""
    private var clientId = ""
    private var browserOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            setBackgroundColor(Color.WHITE)
        }
        status = TextView(this).apply { textSize = 18f; setTextColor(Color.BLACK); gravity = Gravity.CENTER }
        root.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val cancel = Button(this).apply { text = "Cancel"; setOnClickListener { fail("cancelled") } }
        root.addView(cancel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 48 })
        setContentView(root)

        if (intent?.getBooleanExtra(EXTRA_SIGN_OUT, false) == true) {
            setStatus("Signing out of ChatGPT…")
            Thread({
                val revoked = runCatching { ChatGptAuth.signOut(this) }.getOrDefault(false)
                done(if (revoked) "Signed out of ChatGPT." else "Signed out locally (remote revocation not confirmed; you can disconnect Slop Type under ChatGPT Settings).")
            }, "slop-signout").start()
            return
        }
        setStatus("Starting sign-in…")
        Thread(::runSignIn, "slop-siwc").apply { isDaemon = true }.start()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        finished = true
        runCatching { server?.close() }
    }

    private fun setStatus(s: String) = ui.post { status.text = s }

    private fun done(msg: String) {
        if (finished) return
        finished = true
        runCatching { server?.close() }
        setStatus(msg)
        ui.post {
            // pop the Custom Tab (singleTask + CLEAR_TOP brings us back), then close shortly after
            startActivity(Intent(this, ChatGptSignInActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            ui.postDelayed({ finish() }, 2500)
        }
    }

    private fun fail(msg: String) {
        Log.w(TAG, "sign-in failed: $msg")
        done("Sign-in failed: $msg")
    }

    private fun runSignIn() {
        try {
            val stored = ChatGptAuth.load(this)
            val hostId = ChatGptAuth.hostId(this)
            clientId = stored.clientId.ifBlank { ChatGptAuth.DYNAMIC_CLIENT_ID }
            val ss = bind(clientId == ChatGptAuth.DYNAMIC_CLIENT_ID)
            server = ss
            redirectUri = ChatGptAuth.redirectUri(ss.localPort)
            state = ChatGptAuth.randomToken()
            nonce = ChatGptAuth.randomToken()
            pkce = ChatGptAuth.newPkce()
            val url = ChatGptAuth.authorizeUrl(
                clientId, hostId, redirectUri, state, nonce, pkce!!.challenge,
                idTokenHint = stored.idToken.takeIf { clientId != ChatGptAuth.DYNAMIC_CLIENT_ID && it.isNotBlank() },
                loginHint = stored.email.takeIf { clientId != ChatGptAuth.DYNAMIC_CLIENT_ID && it.isNotBlank() },
            )
            setStatus("Complete the sign-in in your browser.\nWaiting for ChatGPT…")
            ui.post {
                runCatching {
                    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, Uri.parse(url))
                    browserOpened = true
                }.onFailure {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) ; browserOpened = true }
                        .onFailure { e -> fail("no browser available: ${e.message}") }
                }
            }
            val cb = awaitCallback(ss) ?: return
            setStatus("Finishing sign-in…")
            val tok = ChatGptAuth.exchangeCode(cb.clientId, cb.code, pkce!!.verifier, redirectUri)
            val scope = tok.scope.ifBlank { cb.scope }
            if (!ChatGptAuth.hasPlanScope(scope)) throw ChatGptAuth.AuthException("ChatGPT plan usage (chatgpt.tokens.use.direct) was not granted. Enable token sharing in ChatGPT and try again.")
            if (tok.idToken.isBlank()) throw ChatGptAuth.AuthException("no id_token in token response")
            val id = ChatGptAuth.validateIdToken(tok.idToken, cb.clientId, nonce, ChatGptAuth.fetchJwks())
            val prev = ChatGptAuth.load(this)
            if (prev.accountId.isNotBlank() && prev.accountId != id.subject && prev.clientId == cb.clientId) {
                Log.i(TAG, "account changed for the same client registration; replacing credentials")
            }
            ChatGptAuth.save(this, ChatGptAuth.Stored(
                hostId = hostId, clientId = cb.clientId, accessToken = tok.accessToken, refreshToken = tok.refreshToken,
                expiresAt = System.currentTimeMillis() + tok.expiresIn * 1000L, idToken = tok.idToken,
                accountId = id.subject, email = id.email, scope = scope,
            ))
            Tier3Client.invalidateModelCache(this)
            done("Signed in to ChatGPT" + (if (id.email.isNotBlank()) " as ${id.email}" else "") + ".")
        } catch (e: ChatGptAuth.AuthException) {
            fail(e.message ?: "auth error")
        } catch (e: Throwable) {
            fail(e.toString())
        }
    }

    /** Port 1455 is required for the initial dynamic registration; fall back to an ephemeral port otherwise. */
    private fun bind(initialRegistration: Boolean): ServerSocket {
        val loop = InetAddress.getByName("127.0.0.1")
        try {
            return ServerSocket(ChatGptAuth.DEFAULT_PORT, 1, loop)
        } catch (e: Exception) {
            if (initialRegistration) Log.w(TAG, "port ${ChatGptAuth.DEFAULT_PORT} busy during initial registration; using an ephemeral port")
            return ServerSocket(0, 1, loop)
        }
    }

    /** Accept connections until a valid callback arrives (or timeout / cancel). */
    private fun awaitCallback(ss: ServerSocket): ChatGptAuth.Callback? {
        ss.soTimeout = 1000
        val deadline = System.currentTimeMillis() + CALLBACK_TIMEOUT_MS
        while (!finished) {
            if (System.currentTimeMillis() > deadline) { fail("timed out waiting for the browser"); return null }
            val sock: Socket = try { ss.accept() } catch (_: SocketTimeoutException) { continue } catch (e: Exception) { if (!finished) fail("listener closed: ${e.message}"); return null }
            try {
                sock.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: continue
                var host: String? = null
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("Host:", true)) host = h.substring(5).trim()
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2 || parts[0] != "GET") { respond(sock, 405, "Method not allowed"); continue }
                val target = parts[1]
                val qIdx = target.indexOf('?')
                val path = if (qIdx < 0) target else target.substring(0, qIdx)
                if (path != ChatGptAuth.CALLBACK_PATH) { respond(sock, 404, "Not found"); continue }
                if (host != null && host != "127.0.0.1:${ss.localPort}") { respond(sock, 400, "Bad host"); continue }
                val params = ChatGptAuth.parseQuery(if (qIdx < 0) null else target.substring(qIdx + 1))
                val known = clientId.takeIf { it != ChatGptAuth.DYNAMIC_CLIENT_ID }
                val cb = try {
                    ChatGptAuth.validateCallback(params, state, known)
                } catch (e: ChatGptAuth.AuthException) {
                    val m = e.message ?: "error"
                    if (m == "state mismatch") { respond(sock, 400, "State mismatch"); continue }
                    respond(sock, 400, "Sign-in failed: $m. You can close this tab.")
                    fail(m); return null
                }
                respond(sock, 200, "Slop Type is signed in. You can close this tab and return to the app.")
                return cb
            } catch (e: Exception) {
                Log.w(TAG, "callback handling", e)
            } finally {
                runCatching { sock.close() }
            }
        }
        return null
    }

    private fun respond(sock: Socket, code: Int, text: String) {
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error" }
        val html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Slop Type</title></head><body style=\"font-family:sans-serif;padding:2em\"><p>${escape(text)}</p></body></html>"
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\nConnection: close\r\n\r\n"
        runCatching {
            sock.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
        }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private const val TAG = "ChatGptSignIn"
        const val EXTRA_SIGN_OUT = "sign_out"
        private const val CALLBACK_TIMEOUT_MS = 10 * 60 * 1000L

        fun start(ctx: Context, signOut: Boolean = false) {
            ctx.startActivity(Intent(ctx, ChatGptSignInActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (signOut) putExtra(EXTRA_SIGN_OUT, true)
            })
        }
    }
}
