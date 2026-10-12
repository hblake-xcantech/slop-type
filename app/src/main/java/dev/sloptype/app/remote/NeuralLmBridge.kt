package dev.sloptype.app.remote

import android.content.Context
import android.util.Log
import dev.sloptype.app.SlopRuntime
import dev.sloptype.engine.NeuralLm
import dev.sloptype.llamabridge.DefaultModel
import dev.sloptype.llamabridge.LlamaLm
import dev.sloptype.llamabridge.ModelStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adapts the llama.cpp [LlamaLm] to the engine's [NeuralLm]. Continuations are words; the
 * engine passes bare words after a context string, so a leading space is prepended (the
 * tokeniser treats " the" and "the" differently).
 */
class NeuralLmBridge(val lm: LlamaLm) : NeuralLm {
    override fun score(context: String, continuations: List<String>): FloatArray {
        if (continuations.isEmpty()) return FloatArray(0)
        val ctx = context.trimEnd()
        val conts = continuations.map { c -> if (ctx.isEmpty() || c.firstOrNull()?.isWhitespace() == true) c else " $c" }
        return lm.scoreContinuations(ctx, conts)
    }

    override fun complete(context: String, maxTokens: Int): String = lm.complete(context, maxTokens)
}

/** Loads the on-device model when present; downloads it on unmetered networks only. */
object NeuralLoader {
    private const val TAG = "NeuralLoader"
    @Volatile private var cached: NeuralLmBridge? = null
    @Volatile private var cachedKey: String = ""
    private val downloading = AtomicBoolean(false)

    /** A bridge if `models/lm.gguf` exists and the native library loads; cached per model file. */
    fun loadIfPresent(ctx: Context): NeuralLm? {
        val f = ModelStore.modelFile(ctx.applicationContext)
        if (!f.isFile || f.length() == 0L || !LlamaLm.isAvailable()) return null
        val key = f.path + ":" + f.length() + ":" + f.lastModified()
        cached?.takeIf { cachedKey == key && it.lm.isOpen }?.let { return it }
        synchronized(this) {
            cached?.takeIf { cachedKey == key && it.lm.isOpen }?.let { return it }
            val lm = LlamaLm.load(f.path) ?: return null
            val b = NeuralLmBridge(lm)
            cached?.lm?.let { runCatching { it.close() } }
            cached = b; cachedKey = key
            Log.i(TAG, "neural LM loaded from ${f.name}")
            return b
        }
    }

    /**
     * Download [DefaultModel.URL] in the background when on an unmetered network, then attach the
     * bridge to the running engine. No-op if already present or metered.
     */
    fun ensureModelAsync(ctx: Context, onDone: ((NeuralLm?) -> Unit)? = null) {
        val app = ctx.applicationContext
        if (!LlamaLm.isAvailable()) { Log.w(TAG, "llama native unavailable: ${LlamaLm.unavailableReason()}"); onDone?.invoke(null); return }
        if (!ModelStore.isDownloaded(app) && !SlopRuntime.NetworkState.isUnmetered(app)) { Log.i(TAG, "model missing and network metered; skipping download"); onDone?.invoke(null); return }
        if (!downloading.compareAndSet(false, true)) return
        Thread({
            try {
                ModelStore.ensureDownloaded(app) { done, total -> if (total > 0 && done % (16L shl 20) < (256L shl 10)) Log.i(TAG, "model download ${done * 100 / total}%") }
                val bridge = loadIfPresent(app)
                SlopRuntime.engineOrNull()?.let { it.neural = bridge }
                onDone?.invoke(bridge)
            } catch (t: Throwable) {
                Log.w(TAG, "model download failed", t)
                onDone?.invoke(null)
            } finally {
                downloading.set(false)
            }
        }, "slop-model-dl").apply { isDaemon = true }.start()
    }
}
