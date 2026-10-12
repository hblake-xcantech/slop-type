package dev.sloptype.app

import android.content.Context
import android.util.Log
import dev.sloptype.engine.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide owner of the engine, logger, configs and state. Created lazily on first use
 * (App.onCreate kicks off the lexicon load on a background thread so the IME rarely waits).
 *
 * Other packages may call: [engine], [logger], [reloadConfigs], [saveState], [correctionsPerWord],
 * [geometryJson], [secrets]. The IME integration lives in dev.sloptype.app.ime.
 */
object SlopRuntime {
    private const val TAG = "SlopRuntime"
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "slop-init").apply { isDaemon = true } }
    private val engineRef = AtomicReference<SlopEngine?>(null)
    @Volatile private var loading = false
    @Volatile private var appCtx: Context? = null
    @Volatile private var loggerRef: SlopLogger? = null
    @Volatile var lastGeometry: KeyboardGeometry? = null
    /** set by the remote package once clients are built; the IME wires them into the engine */
    @Volatile var tier2Factory: ((Context, EngineConfig) -> Tier2Classifier?)? = null
    @Volatile var tier3Factory: ((Context) -> Tier3Model?)? = null
    @Volatile var neuralFactory: ((Context) -> NeuralLm?)? = null

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (loggerRef == null) loggerRef = SlopLogger(app)
        if (engineRef.get() == null && !loading) {
            loading = true
            exec.execute { try { buildEngine(app) } catch (e: Throwable) { Log.e(TAG, "engine init failed", e) } finally { loading = false } }
        }
    }

    /** The engine, or null while the lexicon is still loading (callers fall back to "keep as typed"). */
    fun engineOrNull(): SlopEngine? = engineRef.get()

    /** Blocks up to [waitMs] for the engine. */
    fun engine(waitMs: Long = 0): SlopEngine? {
        val e = engineRef.get()
        if (e != null || waitMs <= 0) return e
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            engineRef.get()?.let { return it }
            Thread.sleep(20)
        }
        return engineRef.get()
    }

    val logger: SlopLogger get() = loggerRef ?: SlopLogger(appCtx!!).also { loggerRef = it }
    fun secrets(ctx: Context): Secrets = SlopSecrets.load(ctx)

    private fun buildEngine(ctx: Context) {
        val t0 = System.currentTimeMillis()
        val cfgFile = File(SlopPaths.configs(ctx), "engine.json")
        val cfg = if (cfgFile.exists()) runCatching { EngineConfig.fromJson(cfgFile.readText()) }.getOrNull() ?: EngineConfig.loadDefaultResource() else EngineConfig.loadDefaultResource()
        val engine = SlopEngine.createDefault(logger, cfg)
        val routerFile = File(SlopPaths.configs(ctx), "router.json")
        if (routerFile.exists()) runCatching { engine.reloadRouter(routerFile.readText()) }.onFailure { Log.w(TAG, "bad router.json", it) }
        val st = File(SlopPaths.state(ctx), "engine-state.json")
        if (st.exists()) runCatching { engine.importState(st.readText()) }.onFailure { Log.w(TAG, "bad engine-state.json", it) }
        val lex = File(SlopPaths.state(ctx), "personal-lexicon.json")
        if (lex.exists()) runCatching { engine.loadPersonalLexicon(lex.readText()) }.onFailure { Log.w(TAG, "bad personal-lexicon.json", it) }
        engine.router.online = NetworkState.isOnline(ctx)
        attachRemotes(ctx, engine)
        engineRef.set(engine)
        Log.i(TAG, "engine ready in ${System.currentTimeMillis() - t0} ms (personal lexicon ${engine.personal.size()} words)")
    }

    fun attachRemotes(ctx: Context, engineArg: SlopEngine? = null) {
        val engine = engineArg ?: engineRef.get() ?: return
        runCatching { engine.tier2 = tier2Factory?.invoke(ctx, engine.config) }.onFailure { Log.w(TAG, "tier2 init", it) }
        runCatching { engine.tier3 = tier3Factory?.invoke(ctx) }.onFailure { Log.w(TAG, "tier3 init", it) }
        runCatching { engine.neural = neuralFactory?.invoke(ctx) }.onFailure { Log.w(TAG, "neural init", it) }
    }

    /** Hot-reload engine.json / router.json from the configs dir (called by the daemon after a pull). */
    fun reloadConfigs(ctx: Context) {
        val e = engineRef.get() ?: return
        val cfgFile = File(SlopPaths.configs(ctx), "engine.json")
        if (cfgFile.exists()) runCatching { e.reloadConfig(cfgFile.readText()) }.onFailure { Log.w(TAG, "reload engine.json failed", it) }
        val routerFile = File(SlopPaths.configs(ctx), "router.json")
        if (routerFile.exists()) runCatching { e.reloadRouter(routerFile.readText()) }.onFailure { Log.w(TAG, "reload router.json failed", it) }
        attachRemotes(ctx, e)
        Log.i(TAG, "configs reloaded")
    }

    fun saveState(ctx: Context) {
        val e = engineRef.get() ?: return
        runCatching {
            File(SlopPaths.state(ctx), "engine-state.json").writeText(e.exportState())
            File(SlopPaths.state(ctx), "personal-lexicon.json").writeText(e.snapshotPersonalLexicon())
            logger.counters().save()
        }.onFailure { Log.w(TAG, "saveState failed", it) }
    }

    /** corrections per word over the last [days] days, or null if too little data */
    fun correctionsPerWord(ctx: Context, days: Int): Double? = logger.counters().correctionsPerWord(days)

    /** Current keyboard geometry as JSON (for eval/data/geometry); null if the keyboard never showed. */
    fun geometryJson(): String? = lastGeometry?.let { kotlinx.serialization.json.Json.encodeToString(KeyboardGeometry.serializer(), it) }
        ?: appCtx?.let { c -> File(SlopPaths.state(c), "geometry.json").takeIf { it.exists() }?.readText() }

    fun spendSummary(): Spend? = engineRef.get()?.router?.spend

    object NetworkState {
        fun isOnline(ctx: Context): Boolean = try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) { false }
        fun isUnmetered(ctx: Context): Boolean = try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (_: Exception) { false }
    }
}
