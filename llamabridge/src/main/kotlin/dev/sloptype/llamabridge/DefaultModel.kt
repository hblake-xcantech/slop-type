package dev.sloptype.llamabridge

/**
 * The model downloaded by [ModelStore.ensureDownloaded] when no URL is given:
 * SmolLM2-135M (base, not instruct) quantised to Q8_0. ~145 MB, ~30 tokens/s on a 2-thread
 * phone CPU, good enough for next-word plausibility scoring.
 */
object DefaultModel {
    const val NAME = "SmolLM2-135M Q8_0"
    const val URL = "https://huggingface.co/QuantFactory/SmolLM2-135M-GGUF/resolve/main/SmolLM2-135M.Q8_0.gguf"
    const val SIZE_BYTES = 144_810_464L
    /** SHA-256 of the file (Hugging Face LFS object id). */
    const val SHA256 = "b761d9ccdfce67726e41ca2ef30e9dfbcf6a32ca2aaef47df5c049ec362d04cd"
}
