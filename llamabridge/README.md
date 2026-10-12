# llamabridge

Android library module (`:llamabridge`, package `dev.sloptype.llamabridge`) that builds
[llama.cpp](https://github.com/ggml-org/llama.cpp) statically with the NDK and exposes a tiny JNI
API for next-word scoring and short greedy completion. It is the tier-1 neural LM in
[DESIGN.md](../DESIGN.md).

- llama.cpp tag: **`b11577`** (git submodule at `src/main/cpp/llama.cpp`)
- ABI: **arm64-v8a only**, minSdk 28, built with `-march=armv8.2-a+dotprod+i8mm+fp16` (any
  Android 11-era or newer arm64 SoC; the Pixel 10 Pro target is far above that)
- Output: `build/outputs/aar/llamabridge-release.aar` (~2.1 MB) containing
  `jni/arm64-v8a/libsloptype_llama.so` (~3.9 MB, stripped) and `libc++_shared.so`

## API

```kotlin
package dev.sloptype.llamabridge

class LlamaLm private constructor(...) : AutoCloseable {
    companion object {
        fun isAvailable(): Boolean                          // native lib loaded OK
        fun unavailableReason(): String?                    // why not, if not
        fun load(modelPath: String, nThreads: Int = 2, ctxSize: Int = 256): LlamaLm?  // null on failure, never throws
        fun selfTest(modelPath: String, nThreads: Int = 2): Boolean  // "the" beats "teh" after "I think"
        fun systemInfo(): String?                           // llama.cpp SIMD feature summary
    }
    val contextSize: Int
    /** Sum log-prob (natural log) of `continuation` tokens given `context`. -inf on failure. */
    fun scoreContinuation(context: String, continuation: String): Float
    /** Same context, several continuations: context KV cache is evaluated once and reused. Order preserved. */
    fun scoreContinuations(context: String, continuations: List<String>): FloatArray
    /** Greedy completion of up to maxTokens, stopping at whitespace/punctuation; returns only the new text. */
    fun complete(context: String, maxTokens: Int = 8): String
    fun tokenCount(text: String): Int
    override fun close()
}

object ModelStore {
    fun modelFile(context: Context): File                 // filesDir/models/lm.gguf
    fun isDownloaded(context: Context, sha256: String? = DefaultModel.SHA256): Boolean
    fun ensureDownloaded(context: Context, url: String = DefaultModel.URL, sha256: String? = DefaultModel.SHA256,
                         onProgress: (bytesSoFar: Long, totalBytes: Long) -> Unit = { _, _ -> }): File
    fun delete(context: Context)
}

object DefaultModel { const val NAME; const val URL; const val SIZE_BYTES; const val SHA256 }

object LogProbs { logSumExp, normalize, posterior, combine, perToken, argmax }   // pure Kotlin, unit-tested
```

Notes:

- Continuations are tokenized with `add_special=false`, so pass the leading space yourself when
  the continuation is a new word (`" the"`, not `"the"`), exactly as the text would be typed.
- A BOS token is prepended to the context when the model expects one, unless the context already
  starts with one. The context is left-truncated (keeping BOS) to leave room for the longest
  continuation in `ctxSize`.
- All `LlamaLm` methods are synchronized per instance; `ensureDownloaded` blocks and must run off
  the main thread.
- The engine can turn a `scoreContinuations` result into a posterior over candidates with
  `LogProbs.posterior(scores)` and mix it with spatial/n-gram scores via `LogProbs.combine`.

### How scoring reuses the KV cache

`libsloptype_llama.so` keeps one `llama_context` (one sequence). The cache always holds a prefix
of the last context: every context token except the last one. For each continuation it decodes
`[last_context_token] + continuation_tokens` as a single batch with logits requested for every
position, sums `log_softmax(logits[i])[token[i+1]]` (float, max-subtracted, double accumulator),
then `llama_memory_seq_rm`s those positions. The next continuation, or the next call with the
same (or a longer) context, starts from the cached prefix; only the common-prefix diff is decoded.

## Default model

| | |
|---|---|
| model | SmolLM2-135M (base, not instruct), Q8_0 |
| URL | `https://huggingface.co/QuantFactory/SmolLM2-135M-GGUF/resolve/main/SmolLM2-135M.Q8_0.gguf` |
| size | 144,810,464 bytes (138.1 MiB) |
| sha256 | `b761d9ccdfce67726e41ca2ef30e9dfbcf6a32ca2aaef47df5c049ec362d04cd` |
| license | Apache-2.0 (SmolLM2) |

`ModelStore.ensureDownloaded` downloads to `lm.gguf.part` (resumable with `Range`), verifies the
sha256, renames atomically to `lm.gguf`, and records the verified hash in `lm.gguf.sha256` so
later calls return immediately.

## Build

```sh
cd /Users/blakehawkins/sloptype/slop-type
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :llamabridge:assembleRelease
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :llamabridge:testDebugUnitTest   # LogProbs tests
```

Requirements: NDK `28.0.13004108` and SDK `cmake;3.22.1` (install with
`JAVA_HOME=/opt/homebrew/opt/openjdk@17 ~/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager --install "cmake;3.22.1"`).

CMake configuration (`src/main/cpp/CMakeLists.txt`): `add_subdirectory(llama.cpp)` with
`BUILD_SHARED_LIBS=OFF`, `LLAMA_BUILD_{COMMON,TESTS,EXAMPLES,SERVER,TOOLS}=OFF`,
`GGML_NATIVE=OFF`, `GGML_OPENMP=OFF`, `GGML_LLAMAFILE=OFF`, `GGML_BACKEND_DL=OFF`,
`GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+i8mm+fp16`; `CMAKE_BUILD_TYPE=Release` (`-O3`) is forced
for all Gradle variants; symbols are hidden and `--gc-sections`/`--exclude-libs,ALL` keep the
.so to the seven `Java_dev_sloptype_llamabridge_LlamaNative_*` exports. STL is `c++_shared`
(the app's own ndk-build code uses `c++_static`; the two do not share C++ objects, so that is fine).

The consumer ProGuard rules (`consumer-rules.pro`) keep `LlamaNative`'s native methods through
R8 in the app.

## Phone sanity test

`LlamaLm.selfTest(path)` loads the model, scores `" the"` vs `" teh"` after `"I think"`, runs
`complete("I think")`, logs everything under tag `LlamaLm`, and returns true when the real word
wins. Wire it to a broadcast or a debug menu in the app, then:

```sh
adb logcat -s LlamaLm llama.cpp sloptype_llama
```

## Using it from the app

```kotlin
dependencies { implementation(project(":llamabridge")) }
```

```kotlin
val file = ModelStore.ensureDownloaded(context) { done, total -> /* progress */ }   // background thread
val lm = LlamaLm.load(file.path) ?: return
val scores = lm.scoreContinuations("I think", listOf(" the", " teh", " then"))
val ghost = lm.complete("I think th")
lm.close()
```
