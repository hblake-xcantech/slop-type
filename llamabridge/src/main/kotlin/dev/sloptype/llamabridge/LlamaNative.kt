package dev.sloptype.llamabridge

/** Raw JNI surface of `libsloptype_llama.so`. Not thread-safe; [LlamaLm] serialises access. */
internal object LlamaNative {
    @JvmStatic external fun load(path: String, nThreads: Int, ctxSize: Int): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun score(handle: Long, context: String, continuations: Array<String>): FloatArray
    @JvmStatic external fun complete(handle: Long, context: String, maxTokens: Int): String
    @JvmStatic external fun contextSize(handle: Long): Int
    @JvmStatic external fun tokenCount(handle: Long, text: String): Int
    @JvmStatic external fun systemInfo(): String
}
