package dev.sloptype.app.daemon

import android.content.Context
import android.util.Log
import dev.sloptype.app.Secrets
import dev.sloptype.app.SlopPaths
import dev.sloptype.app.SlopRuntime
import dev.sloptype.app.SlopSecrets
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Daemon step 2: pull the configs dir (engine.json, router.json, recipients.txt, prompts/) and
 * `lexicon/personal.json` from `slop-type-data`. Blob shas are cached in `configs/.shas.json`
 * so unchanged files are not re-downloaded. Hot-reloads the engine when anything changed.
 */
object ConfigPuller {
    private const val TAG = "SlopDaemon"
    const val SHAS_FILE = ".shas.json"
    private val TOP_LEVEL = setOf("engine.json", "router.json", "recipients.txt")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val shasSerializer = MapSerializer(String.serializer(), String.serializer())

    /** @return true if at least one file changed locally. */
    fun run(
        ctx: Context,
        gh: GitHubClient = GitHubClient(SlopSecrets.load(ctx).githubToken),
        secrets: Secrets = SlopSecrets.load(ctx),
    ): Boolean {
        if (!secrets.hasGithub) return false
        val owner = secrets.githubOwner
        val repo = secrets.dataRepo
        val shas = localShas(ctx)
        val configs = SlopPaths.configs(ctx)
        var changed = false
        var configChanged = false
        var recipientsChanged = false

        // Remote path -> local file.
        val wanted = LinkedHashMap<String, File>()
        val entries = LinkedHashMap<String, GitHubClient.DirEntry>()

        fun collect(dir: String, filter: (GitHubClient.DirEntry) -> Boolean, local: (GitHubClient.DirEntry) -> File) {
            val list = try { gh.listDir(owner, repo, dir) } catch (e: GitHubException) {
                if (e.status == 404) { Log.i(TAG, "remote dir '$dir' does not exist"); return } else throw e
            }
            for (e in list) if (e.type == "file" && filter(e)) { entries[e.path] = e; wanted[e.path] = local(e) }
        }

        collect("configs", { it.name in TOP_LEVEL }) { File(configs, it.name) }
        collect("configs/prompts", { true }) { File(File(configs, "prompts"), it.name) }
        collect("lexicon", { it.name == "personal.json" }) { File(SlopPaths.state(ctx), "personal-lexicon.remote.json") }

        for ((path, local) in wanted) {
            val entry = entries[path] ?: continue
            if (shas[path] == entry.sha && local.isFile) continue
            try {
                val content = gh.getContent(owner, repo, path) ?: continue
                DaemonUtil.writeAtomic(local, content.bytes)
                shas[path] = content.sha
                changed = true
                if (path.startsWith("configs/")) configChanged = true
                if (path == "configs/recipients.txt") recipientsChanged = true
                Log.i(TAG, "pulled $path (${content.bytes.size} bytes)")
            } catch (e: GitHubException) {
                Log.w(TAG, "pull $path failed: ${e.message}")
                if (e.status == 401 || e.status == 403) throw e
            }
        }

        // Prompts removed remotely are removed locally too.
        val remotePrompts = wanted.keys.filter { it.startsWith("configs/prompts/") }.map { it.substringAfterLast('/') }.toSet()
        if (entries.keys.any { it.startsWith("configs/prompts/") } || shas.keys.any { it.startsWith("configs/prompts/") }) {
            File(configs, "prompts").listFiles()?.forEach { f ->
                if (f.isFile && f.name !in remotePrompts && shas.containsKey("configs/prompts/${f.name}")) {
                    if (f.delete()) { shas.remove("configs/prompts/${f.name}"); changed = true; configChanged = true }
                }
            }
        }

        if (changed) saveShas(ctx, shas)
        if (recipientsChanged) applyRecipients(ctx, File(configs, "recipients.txt"))
        if (changed) runCatching { SlopRuntime.reloadConfigs(ctx) }.onFailure { Log.w(TAG, "reloadConfigs failed", it) }
        if (configChanged) Log.i(TAG, "configs changed; engine reloaded")
        return changed
    }

    /** Recipients file: one `age1...` per line; `#` comments and blank lines ignored. */
    fun parseRecipients(text: String): List<String> = DaemonUtil.parseRecipients(text)

    private fun applyRecipients(ctx: Context, file: File) {
        if (!file.isFile) return
        val recips = parseRecipients(file.readText())
        if (recips.isEmpty()) { Log.w(TAG, "recipients.txt has no age recipients; keeping the current ones"); return }
        SlopSecrets.update(ctx) { it.copy(ageRecipients = recips) }
        Log.i(TAG, "age recipients updated (${recips.size})")
    }

    fun localShas(ctx: Context): MutableMap<String, String> {
        val f = File(SlopPaths.configs(ctx), SHAS_FILE)
        if (!f.isFile) return mutableMapOf()
        return runCatching { json.decodeFromString(shasSerializer, f.readText()).toMutableMap() }.getOrDefault(mutableMapOf())
    }

    private fun saveShas(ctx: Context, shas: Map<String, String>) {
        DaemonUtil.writeAtomic(File(SlopPaths.configs(ctx), SHAS_FILE), json.encodeToString(shasSerializer, shas.toSortedMap()))
    }
}
