package dev.sloptype.llamabridge

import android.util.Log
import java.io.File

/**
 * A small causal language model (GGUF, run by llama.cpp on the CPU) for next-word scoring and
 * short greedy completion inside the keyboard.
 *
 * All methods are safe to call from any thread; calls are serialised per instance. Every method
 * degrades to a harmless value (-inf / empty) instead of throwing once the instance is loaded.
 */
class LlamaLm private constructor(private var handle: Long) : AutoCloseable {

    companion object {
        private const val TAG = "LlamaLm"

        private val loadError: Throwable? = try {
            System.loadLibrary("sloptype_llama")
            null
        } catch (t: Throwable) {
            t
        }

        /** True when the native library could be loaded on this device (arm64-v8a only). */
        fun isAvailable(): Boolean = loadError == null

        /** Why [isAvailable] is false, if it is. */
        fun unavailableReason(): String? = loadError?.toString()

        /**
         * Load a GGUF model. Returns null (never throws) when the native library is missing, the
         * file does not exist, or llama.cpp rejects the model.
         *
         * @param nThreads CPU threads for decoding; 2 is a good default for a keyboard.
         * @param ctxSize  KV cache size in tokens; the context is left-truncated to fit.
         */
        fun load(modelPath: String, nThreads: Int = 2, ctxSize: Int = 256): LlamaLm? {
            if (!isAvailable()) {
                Log.w(TAG, "native library unavailable: $loadError")
                return null
            }
            if (!File(modelPath).isFile) {
                Log.w(TAG, "model file missing: $modelPath")
                return null
            }
            val h = try {
                LlamaNative.load(modelPath, nThreads, ctxSize)
            } catch (t: Throwable) {
                Log.e(TAG, "native load threw", t)
                0L
            }
            return if (h == 0L) null else LlamaLm(h)
        }

        /**
         * Sanity check runnable without instrumentation: loads [modelPath], scores "the" vs "teh"
         * after "I think", and returns true when the real word wins. Logs the scores under
         * tag `LlamaLm`.
         */
        fun selfTest(modelPath: String, nThreads: Int = 2): Boolean {
            val lm = load(modelPath, nThreads) ?: run {
                Log.e(TAG, "selfTest: load failed")
                return false
            }
            return lm.use {
                val scores = it.scoreContinuations("I think", listOf(" the", " teh"))
                val the = scores[0]
                val teh = scores[1]
                val completion = it.complete("I think")
                Log.i(TAG, "selfTest: the=$the teh=$teh complete(\"I think\")=\"$completion\"")
                the.isFinite() && teh.isFinite() && the > teh
            }
        }

        /** llama.cpp's feature summary (which SIMD paths were compiled in), or null if unavailable. */
        fun systemInfo(): String? = if (isAvailable()) runCatching { LlamaNative.systemInfo() }.getOrNull() else null
    }

    private val lock = Any()

    val isOpen: Boolean get() = synchronized(lock) { handle != 0L }

    /** KV cache size in tokens. */
    val contextSize: Int get() = synchronized(lock) { if (handle == 0L) 0 else LlamaNative.contextSize(handle) }

    /**
     * Sum of natural-log probabilities of the tokens of [continuation] given [context].
     * A BOS token is prepended to the context when the model expects one and the context does not
     * already start with it. Returns `Float.NEGATIVE_INFINITY` on failure (never throws).
     */
    fun scoreContinuation(context: String, continuation: String): Float =
        scoreContinuations(context, listOf(continuation)).firstOrNull() ?: Float.NEGATIVE_INFINITY

    /**
     * Score several continuations of the same [context]. The context is evaluated once and its KV
     * cache reused for every continuation. Result order matches [continuations].
     */
    fun scoreContinuations(context: String, continuations: List<String>): FloatArray {
        if (continuations.isEmpty()) return FloatArray(0)
        synchronized(lock) {
            if (handle == 0L) return FloatArray(continuations.size) { Float.NEGATIVE_INFINITY }
            return try {
                LlamaNative.score(handle, context, continuations.toTypedArray())
            } catch (t: Throwable) {
                Log.e(TAG, "score failed", t)
                FloatArray(continuations.size) { Float.NEGATIVE_INFINITY }
            }
        }
    }

    /**
     * Greedy-complete [context] by up to [maxTokens] tokens, stopping at the first whitespace or
     * punctuation after the first token. Returns only the new text (possibly with one leading
     * space if the model started a new word); empty on failure.
     */
    fun complete(context: String, maxTokens: Int = 8): String {
        synchronized(lock) {
            if (handle == 0L) return ""
            return try {
                LlamaNative.complete(handle, context, maxTokens)
            } catch (t: Throwable) {
                Log.e(TAG, "complete failed", t)
                ""
            }
        }
    }

    /** Number of tokens [text] occupies for this model's tokenizer (no BOS). */
    fun tokenCount(text: String): Int = synchronized(lock) {
        if (handle == 0L) 0 else runCatching { LlamaNative.tokenCount(handle, text) }.getOrDefault(0)
    }

    override fun close() {
        synchronized(lock) {
            if (handle != 0L) {
                runCatching { LlamaNative.free(handle) }.onFailure { Log.e(TAG, "free failed", it) }
                handle = 0L
            }
        }
    }
}
