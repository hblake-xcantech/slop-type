package dev.sloptype.app.daemon

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** Pure-JVM tests of [GitHubClient] against a local HTTP server (no Android, no network). */
class GitHubClientTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val received = ArrayList<Pair<String, String>>()  // method+path, body
    private val headers = HashMap<String, Map<String, String>>()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stop() { server.stop(0) }

    private fun client() = GitHubClient("tok_test", apiBase = base, retryDelayMs = 1)

    private fun route(path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path) { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            received += "${ex.requestMethod} ${ex.requestURI}" to body
            headers["${ex.requestMethod} ${ex.requestURI.path}"] = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            handler(ex)
            ex.close()
        }
    }

    private fun HttpExchange.reply(status: Int, body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }

    @Test
    fun getContent_decodesBase64_andSendsAuthHeaders() {
        val content = Base64.getEncoder().encodeToString("{\"a\":1}\n".toByteArray()).chunked(4).joinToString("\n")
        route("/repos/o/r/contents/configs/engine.json") { ex ->
            ex.reply(200, """{"type":"file","path":"configs/engine.json","sha":"abc","size":8,"encoding":"base64","content":"$content"}""")
        }
        val f = client().getContent("o", "r", "configs/engine.json")
        assertNotNull(f)
        assertEquals("abc", f!!.sha)
        assertEquals("{\"a\":1}\n", String(f.bytes))
        val h = headers["GET /repos/o/r/contents/configs/engine.json"]!!
        assertEquals("Bearer tok_test", h["authorization"])
        assertEquals("slop-type-daemon", h["user-agent"])
        assertTrue(received.first().first.contains("ref=main"))
    }

    @Test
    fun getContent_returnsNullOn404_andThrowsOn401() {
        route("/repos/o/r/contents/missing.txt") { ex -> ex.reply(404, """{"message":"Not Found"}""") }
        route("/repos/o/r/contents/secret.txt") { ex -> ex.reply(401, """{"message":"Bad credentials"}""") }
        assertNull(client().getContent("o", "r", "missing.txt"))
        val e = runCatching { client().getContent("o", "r", "secret.txt") }.exceptionOrNull()
        assertTrue(e is GitHubException)
        assertEquals(401, (e as GitHubException).status)
        assertTrue(e.message!!.contains("unauthorized"))
    }

    @Test
    fun putContent_createThenUpdate() {
        route("/repos/o/r/contents/logs/dev/2026-10-12/x.age") { ex ->
            val req = Json.parseToJsonElement(received.last().second).jsonObject
            val status = if (req["sha"] == null) 201 else 200
            ex.reply(status, """{"content":{"sha":"newsha"},"commit":{"sha":"c1"}}""")
        }
        val c = client()
        val r1 = c.putContent("o", "r", "logs/dev/2026-10-12/x.age", byteArrayOf(1, 2, 3), "logs: dev 1 files", null)
        assertEquals(201, r1.status)
        assertEquals("newsha", r1.sha)
        val r2 = c.putContent("o", "r", "logs/dev/2026-10-12/x.age", byteArrayOf(1, 2, 3), "logs: dev 1 files", "newsha")
        assertEquals(200, r2.status)
        val req1 = Json.parseToJsonElement(received[0].second).jsonObject
        assertEquals("logs: dev 1 files", req1["message"]!!.jsonPrimitive.content)
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), req1["content"]!!.jsonPrimitive.content)
        assertFalse(received[0].second.contains("\"sha\""))
        assertTrue(received[1].second.contains("\"sha\":\"newsha\""))
    }

    @Test
    fun upsert_usesParentListingForSha() {
        route("/repos/o/r/contents/metrics/dev") { ex ->
            ex.reply(200, """[{"name":"counters.json","path":"metrics/dev/counters.json","sha":"old","size":2,"type":"file"}]""")
        }
        route("/repos/o/r/contents/metrics/dev/counters.json") { ex ->
            val req = Json.parseToJsonElement(received.last().second).jsonObject
            assertEquals("old", req["sha"]?.jsonPrimitive?.content)
            ex.reply(200, """{"content":{"sha":"new"},"commit":{"sha":"c"}}""")
        }
        val r = client().upsertContent("o", "r", "metrics/dev/counters.json", "{}".toByteArray(), "metrics")
        assertEquals(200, r.status)
    }

    @Test
    fun listDir_and_latestRelease() {
        route("/repos/o/r/contents/configs/prompts") { ex ->
            ex.reply(200, """[{"name":"a.txt","path":"configs/prompts/a.txt","sha":"s1","size":5,"type":"file"},{"name":"sub","path":"configs/prompts/sub","sha":"s2","size":0,"type":"dir"}]""")
        }
        route("/repos/o/slop-type/releases/latest") { ex ->
            ex.reply(200, """{"tag_name":"v1.2.3","name":"1.2.3","published_at":"2026-10-11T00:00:00Z","assets":[{"name":"app.apk","url":"$base/asset/1","size":3,"browser_download_url":"x"}]}""")
        }
        route("/repos/o/none/releases/latest") { ex -> ex.reply(404, "{}") }
        val list = client().listDir("o", "r", "configs/prompts")
        assertEquals(listOf("a.txt", "sub"), list.map { it.name })
        assertEquals("dir", list[1].type)
        val rel = client().latestRelease("o", "slop-type")!!
        assertEquals("v1.2.3", rel.tagName)
        assertEquals("app.apk", rel.assets.single().name)
        assertEquals(3L, rel.assets.single().size)
        assertNull(client().latestRelease("o", "none"))
    }

    @Test
    fun retries5xxTwice_thenSucceeds() {
        val n = AtomicInteger()
        route("/repos/o/r/contents/flaky.txt") { ex ->
            if (n.incrementAndGet() < 3) ex.reply(502, "bad gateway")
            else ex.reply(200, """{"type":"file","path":"flaky.txt","sha":"s","size":2,"encoding":"base64","content":"aGk="}""")
        }
        val f = client().getContent("o", "r", "flaky.txt")!!
        assertEquals("hi", String(f.bytes))
        assertEquals(3, n.get())
    }

    @Test
    fun gives_up_after_three_5xx() {
        route("/repos/o/r/contents/dead.txt") { ex -> ex.reply(503, "down") }
        val e = runCatching { client().getContent("o", "r", "dead.txt") }.exceptionOrNull() as GitHubException
        assertEquals(503, e.status)
        assertEquals(3, received.size)
    }

    @Test
    fun download_followsRedirect_checksSize_andOmitsAuthOffHost() {
        val payload = ByteArray(1000) { it.toByte() }
        route("/asset/1") { ex ->
            assertEquals("application/octet-stream", ex.requestHeaders.getFirst("Accept"))
            // Redirect to "another host" (localhost by name resolves to the same server).
            ex.responseHeaders.add("Location", "http://localhost:${server.address.port}/blob")
            ex.sendResponseHeaders(302, -1)
        }
        route("/blob") { ex ->
            assertNull(ex.requestHeaders.getFirst("Authorization"))
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.write(payload)
        }
        val dest = File.createTempFile("download", ".apk").apply { delete() }
        client().download("$base/asset/1", dest, 1000)
        assertTrue(dest.isFile)
        assertTrue(payload.contentEquals(dest.readBytes()))
        assertFalse(File(dest.parentFile, dest.name + ".part").exists())
        dest.delete()

        val e = runCatching { client().download("$base/asset/1", dest, 999) }.exceptionOrNull() as GitHubException
        assertTrue(e.message!!.contains("size mismatch"))
        assertFalse(dest.exists())
    }
}
