package dev.sloptype.app.remote

import android.content.Context
import android.util.Log
import dev.sloptype.app.SlopPaths
import dev.sloptype.app.SlopRuntime
import dev.sloptype.engine.EngineConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Hooks the remote tiers into [SlopRuntime]. Call [install] from App.onCreate before
 * `SlopRuntime.init`. Factories are re-invoked by the runtime after every config reload, so
 * `router.json` changes (provider / model / service tier) take effect without a restart.
 */
object RemoteWiring {
    private const val TAG = "RemoteWiring"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Keys read from router.json beyond what the engine's RouterConfig knows. */
    data class RouterExtras(val tier2Provider: String = "none", val tier2Model: String = "", val tier2ServiceTier: String = "", val tier3Model: String = "", val tier3ReasoningEffort: String = "low")

    fun routerExtras(ctx: Context): RouterExtras {
        val f = File(SlopPaths.configs(ctx), "router.json")
        if (!f.isFile) return RouterExtras()
        return runCatching { parseRouterExtras(f.readText()) }.onFailure { Log.w(TAG, "bad router.json", it) }.getOrDefault(RouterExtras())
    }

    fun parseRouterExtras(text: String): RouterExtras {
        val o = json.parseToJsonElement(text).jsonObject
        fun s(k: String, d: String = ""): String = (o[k] as? JsonPrimitive)?.contentOrNull ?: d
        // accept both the flat keys the engine uses and a nested {"tier2": {"provider": ...}} block
        val t2 = o["tier2"] as? JsonObject
        fun s2(k: String): String = (t2?.get(k) as? JsonPrimitive)?.contentOrNull ?: ""
        val t3 = o["tier3"] as? JsonObject
        fun s3(k: String): String = (t3?.get(k) as? JsonPrimitive)?.contentOrNull ?: ""
        return RouterExtras(
            tier2Provider = s("tier2Provider").ifBlank { s2("provider") }.ifBlank { "none" }.lowercase(),
            tier2Model = s("tier2Model").ifBlank { s2("model") },
            tier2ServiceTier = s("tier2ServiceTier").ifBlank { s2("serviceTier") },
            tier3Model = s("tier3Model").ifBlank { s3("model") },
            tier3ReasoningEffort = s("tier3ReasoningEffort", "low").ifBlank { s3("reasoningEffort") }.ifBlank { "low" },
        )
    }

    fun buildTier2(ctx: Context, cfg: EngineConfig): Tier2Client? {
        val x = routerExtras(ctx)
        if (x.tier2Provider !in Tier2Protocol.PROVIDERS) return null
        return Tier2Client(ctx, cfg, x.tier2Provider, x.tier2Model, x.tier2ServiceTier.ifBlank { null })
    }

    fun buildTier3(ctx: Context): Tier3Client? {
        val x = routerExtras(ctx)
        val effort = x.tier3ReasoningEffort.takeIf { it != "default" && it.isNotBlank() }
        return Tier3Client(ctx, x.tier3Model, effort).takeIf { it.available }
    }

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        SlopRuntime.tier2Factory = { c, cfg -> buildTier2(c, cfg) }
        SlopRuntime.tier3Factory = { c -> buildTier3(c) }
        SlopRuntime.neuralFactory = { c -> NeuralLoader.loadIfPresent(c) }
        Log.i(TAG, "remote tiers wired (router: ${routerExtras(app)})")
    }
}
