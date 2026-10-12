package dev.sloptype.app

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Secrets live only in filesDir/sloptype/secrets.json (written by scripts/provision_phone.sh
 * over adb as root, or from the settings screen). Never logged, never uploaded.
 */
@Serializable
data class Secrets(
    val githubToken: String = "",
    val githubOwner: String = "hblake-xcantech",
    val appRepo: String = "slop-type",
    val dataRepo: String = "slop-type-data",
    val openaiApiKey: String = "",
    val jevApiKey: String = "",
    /** age public key(s) the logs are encrypted to; the first is the autopilot's */
    val ageRecipients: List<String> = listOf(DEFAULT_AGE_RECIPIENT),
    /** Sign in with ChatGPT tokens (access/refresh/expiry/host id), managed by remote.ChatGptAuth */
    val chatgptOauth: JsonObject? = null,
    val deviceName: String = "pixel10pro",
) {
    val hasGithub get() = githubToken.isNotBlank()
    companion object {
        const val DEFAULT_AGE_RECIPIENT = "age1lvhv5hyawuvfxw0ejglv0y8mkpqmua0xuax5z6ad4pl5tnqp24ascyrgdg"
    }
}

object SlopSecrets {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    @Volatile private var cached: Secrets? = null

    fun load(ctx: Context): Secrets {
        cached?.let { return it }
        val f = SlopPaths.secrets(ctx)
        val s = if (f.exists()) runCatching { json.decodeFromString(Secrets.serializer(), f.readText()) }.getOrDefault(Secrets()) else Secrets()
        cached = s
        return s
    }

    fun save(ctx: Context, s: Secrets) {
        val f = SlopPaths.secrets(ctx)
        f.writeText(json.encodeToString(Secrets.serializer(), s))
        runCatching { f.setReadable(false, false); f.setReadable(true, true) }
        cached = s
    }

    fun update(ctx: Context, f: (Secrets) -> Secrets) = save(ctx, f(load(ctx)))
    fun invalidate() { cached = null }
}
