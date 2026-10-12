package dev.sloptype.app.daemon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/** Any non-success GitHub response or transport error. [status] is 0 for transport errors. */
class GitHubException(val status: Int, val body: String, cause: Throwable? = null) :
    IOException("GitHub HTTP $status (${describe(status)}): ${body.take(400)}", cause) {
    companion object {
        fun describe(status: Int): String = when (status) {
            0 -> "network error"
            401 -> "unauthorized: bad or expired token"
            403 -> "forbidden: token lacks permission, or rate limited"
            404 -> "not found: wrong path/ref, or token has no access to the repo"
            409 -> "conflict: branch/sha changed concurrently"
            422 -> "unprocessable: sha missing for an existing file or sha mismatch"
            in 500..599 -> "server error"
            else -> "unexpected status"
        }
    }
}

/**
 * Minimal GitHub REST client (Contents API + releases) using a fine-grained PAT and
 * [HttpURLConnection]. No Android dependencies.
 */
class GitHubClient(
    private val token: String,
    private val apiBase: String = "https://api.github.com",
    private val userAgent: String = "slop-type-daemon",
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 90_000,
    /** Sleep between retries of 5xx responses (ms); overridable for tests. */
    private val retryDelayMs: Long = 1_500,
) {
    data class ContentFile(val path: String, val sha: String, val bytes: ByteArray, val size: Long)
    data class DirEntry(val name: String, val path: String, val type: String, val sha: String, val size: Long)
    data class Asset(val name: String, val url: String, val size: Long, val browserDownloadUrl: String?)
    data class Release(val tagName: String, val name: String?, val publishedAt: String?, val assets: List<Asset>)
    data class PutResult(val status: Int, val sha: String?, val commitSha: String?)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
    private val apiHost: String = runCatching { URL(apiBase).host }.getOrDefault("api.github.com")

    // ------------------------------------------------------------------ public API

    /** Decoded file contents + blob sha, or null if the path does not exist (404). */
    fun getContent(owner: String, repo: String, path: String, ref: String = "main"): ContentFile? {
        val url = contentsUrl(owner, repo, path) + "?ref=" + enc(ref)
        val r = request("GET", url)
        if (r.status == 404) return null
        r.ensureSuccess()
        val dto = json.decodeFromString(ContentDto.serializer(), r.text())
        if (dto.type != "file") throw GitHubException(r.status, "expected a file at $path, got type '${dto.type}'")
        val bytes = if (dto.encoding == "base64" && dto.content != null) {
            Base64.getMimeDecoder().decode(dto.content.replace("\n", ""))
        } else {
            // Files > 1 MB come back without inline content; fetch the raw blob.
            val raw = request("GET", url, accept = "application/vnd.github.raw+json")
            raw.ensureSuccess()
            raw.body
        }
        return ContentFile(dto.path, dto.sha, bytes, dto.size)
    }

    /**
     * Creates ([sha] == null) or updates a file through the Contents API. Returns 201 on create,
     * 200 on update. Throws [GitHubException] on 409/422 (e.g. the file exists but no sha given).
     */
    fun putContent(owner: String, repo: String, path: String, bytes: ByteArray, message: String, sha: String?, branch: String? = null): PutResult {
        val body = json.encodeToString(
            PutRequest.serializer(),
            PutRequest(message = message, content = Base64.getEncoder().encodeToString(bytes), sha = sha, branch = branch),
        )
        val r = request("PUT", contentsUrl(owner, repo, path), body = body.toByteArray(Charsets.UTF_8))
        r.ensureSuccess()
        val dto = runCatching { json.decodeFromString(PutResponse.serializer(), r.text()) }.getOrNull()
        return PutResult(r.status, dto?.content?.sha, dto?.commit?.sha)
    }

    /** Create-or-update: tries a create first, then looks the sha up (via the parent listing) on 422/409. */
    fun upsertContent(owner: String, repo: String, path: String, bytes: ByteArray, message: String, branch: String? = null): PutResult {
        val existing = runCatching { shaOf(owner, repo, path, branch ?: "main") }.getOrNull()
        return try {
            putContent(owner, repo, path, bytes, message, existing, branch)
        } catch (e: GitHubException) {
            if (e.status != 422 && e.status != 409) throw e
            val sha = shaOf(owner, repo, path, branch ?: "main") ?: throw e
            putContent(owner, repo, path, bytes, message, sha, branch)
        }
    }

    /** Blob sha of [path] without downloading its content (lists the parent directory), or null if absent. */
    fun shaOf(owner: String, repo: String, path: String, ref: String = "main"): String? {
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val entries = try { listDir(owner, repo, parent, ref) } catch (e: GitHubException) { if (e.status == 404) return null else throw e }
        return entries.firstOrNull { it.name == name }?.sha
    }

    /** Directory listing. Throws [GitHubException] with status 404 if the directory does not exist. */
    fun listDir(owner: String, repo: String, path: String, ref: String = "main"): List<DirEntry> {
        val url = contentsUrl(owner, repo, path) + "?ref=" + enc(ref) + "&per_page=1000"
        val r = request("GET", url)
        r.ensureSuccess()
        val text = r.text().trimStart()
        if (!text.startsWith("[")) throw GitHubException(r.status, "expected a directory at '$path' but got a file")
        return json.decodeFromString(ListSerializer, text).map { DirEntry(it.name, it.path, it.type, it.sha, it.size) }
    }

    /** The latest (non-draft, non-prerelease) release, or null if the repo has none (404). */
    fun latestRelease(owner: String, repo: String): Release? {
        val r = request("GET", "$apiBase/repos/${enc(owner)}/${enc(repo)}/releases/latest")
        if (r.status == 404) return null
        r.ensureSuccess()
        val dto = json.decodeFromString(ReleaseDto.serializer(), r.text())
        return Release(dto.tagName, dto.name, dto.publishedAt, dto.assets.map { Asset(it.name, it.url, it.size, it.browserDownloadUrl) })
    }

    /**
     * Downloads [url] (a release asset API url or any direct url) to [dest] via a temp file,
     * following redirects manually (the Authorization header is only sent to the API host).
     */
    fun download(url: String, dest: File, expectedSize: Long? = null) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        var attempt = 0
        while (true) {
            try {
                var current = url
                var hops = 0
                while (true) {
                    val conn = open("GET", current, accept = "application/octet-stream")
                    conn.instanceFollowRedirects = false
                    val status = conn.responseCode
                    if (status in 300..399) {
                        val loc = conn.getHeaderField("Location") ?: throw GitHubException(status, "redirect without Location")
                        conn.disconnect()
                        current = URL(URL(current), loc).toString()
                        if (++hops > 6) throw GitHubException(status, "too many redirects")
                        continue
                    }
                    if (status >= 500) { val b = readBody(conn); throw GitHubException(status, b.toString(Charsets.UTF_8)) }
                    if (status !in 200..299) { val b = readBody(conn); throw GitHubException(status, b.toString(Charsets.UTF_8)) }
                    var total = 0L
                    conn.inputStream.use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n); total += n
                            }
                            out.flush(); out.fd.sync()
                        }
                    }
                    if (expectedSize != null && expectedSize >= 0 && total != expectedSize) {
                        tmp.delete()
                        throw GitHubException(0, "size mismatch: got $total bytes, expected $expectedSize")
                    }
                    if (dest.exists()) dest.delete()
                    if (!tmp.renameTo(dest)) throw GitHubException(0, "rename ${tmp.name} -> ${dest.name} failed")
                    return
                }
            } catch (e: GitHubException) {
                tmp.delete()
                if (e.status in 500..599 && attempt < 2) { attempt++; sleep(); continue }
                throw e
            } catch (e: IOException) {
                tmp.delete()
                if (attempt < 2) { attempt++; sleep(); continue }
                throw GitHubException(0, e.message ?: e.toString(), e)
            }
        }
    }

    // ------------------------------------------------------------------ HTTP core

    private class Response(val status: Int, val body: ByteArray) {
        fun text(): String = body.toString(Charsets.UTF_8)
        fun ensureSuccess() { if (status !in 200..299) throw GitHubException(status, text()) }
    }

    private fun request(method: String, url: String, accept: String = "application/vnd.github+json", body: ByteArray? = null): Response {
        var attempt = 0
        while (true) {
            try {
                val conn = open(method, url, accept)
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                }
                val status = conn.responseCode
                val bytes = readBody(conn)
                conn.disconnect()
                if (status in 500..599 && attempt < 2) { attempt++; sleep(); continue }
                return Response(status, bytes)
            } catch (e: IOException) {
                if (attempt < 2) { attempt++; sleep(); continue }
                throw GitHubException(0, "$method $url: ${e.message}", e)
            }
        }
    }

    private fun open(method: String, url: String, accept: String): HttpURLConnection {
        val u = URL(url)
        val conn = u.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.useCaches = false
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (token.isNotBlank() && u.host.equals(apiHost, ignoreCase = true)) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        return conn
    }

    private fun readBody(conn: HttpURLConnection): ByteArray {
        val stream: InputStream? = try {
            if (conn.responseCode in 200..399) conn.inputStream else conn.errorStream
        } catch (_: IOException) { conn.errorStream }
        if (stream == null) return ByteArray(0)
        return stream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    private fun sleep() { try { Thread.sleep(retryDelayMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt() } }

    private fun contentsUrl(owner: String, repo: String, path: String): String {
        val p = path.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { enc(it) }
        return "$apiBase/repos/${enc(owner)}/${enc(repo)}/contents" + (if (p.isEmpty()) "" else "/$p")
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ------------------------------------------------------------------ DTOs

    @Serializable
    internal class ContentDto(
        val name: String = "",
        val path: String = "",
        val sha: String = "",
        val size: Long = 0,
        val type: String = "",
        val encoding: String? = null,
        val content: String? = null,
    )

    @Serializable
    internal class PutRequest(val message: String, val content: String, val sha: String? = null, val branch: String? = null)

    @Serializable
    internal class CommitDto(val sha: String? = null)

    @Serializable
    internal class PutResponse(val content: ContentDto? = null, val commit: CommitDto? = null)

    @Serializable
    internal class AssetDto(
        val name: String = "",
        val url: String = "",
        val size: Long = 0,
        @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
    )

    @Serializable
    internal class ReleaseDto(
        @SerialName("tag_name") val tagName: String = "",
        val name: String? = null,
        @SerialName("published_at") val publishedAt: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<AssetDto> = emptyList(),
    )

    private companion object {
        val ListSerializer = kotlinx.serialization.builtins.ListSerializer(ContentDto.serializer())
    }
}
