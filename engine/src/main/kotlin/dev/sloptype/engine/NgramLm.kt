package dev.sloptype.engine

import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.exp
import kotlin.math.ln

/**
 * Interpolated bigram language model over the main lexicon plus the personal lexicon and the
 * on-screen vocabulary. All values are natural log. The neural LM (llama.cpp), when present,
 * is a *reranker* at word boundaries and is attached through [NeuralLm].
 */
class NgramLm(
    val lexicon: Lexicon,
    private val bigrams: HashMap<Long, Int>,
    private val leftCounts: HashMap<Int, Long>,
    private val wordIds: HashMap<String, Int>,
) {
    var config: EngineConfig = EngineConfig.DEFAULT
    var personal: PersonalLexicon = PersonalLexicon()
    /** words currently visible on screen (lower-cased) */
    @Volatile var screenVocab: Set<String> = emptySet()

    private fun id(w: String): Int = wordIds[w] ?: -1

    val misspellings: Misspellings = Misspellings.default()
    /** letter-bigram model over the lexicon: how English-like a string looks */
    val chars: CharModel by lazy { CharModel.build(lexicon) }

    fun isKnown(word: String): Boolean =
        lexicon.contains(word) || personal.contains(word) || screenVocab.contains(word) || possessiveBase(word)?.let { isKnown(it) } == true

    private fun possessiveBase(word: String): String? =
        if (word.length > 3 && (word.endsWith("'s") || word.endsWith("s'"))) word.dropLast(if (word.endsWith("'s")) 2 else 1) else null

    /** log P(word) with personal / screen fallbacks; null if nothing knows the word. */
    fun unigram(word: String): Float? {
        possessiveBase(word)?.let { base -> if (!lexicon.contains(word)) return unigram(base)?.let { it - 1.5f } }
        val main = lexicon.unigram(word)
        val pers = personal.weight(word)?.let { config.personalBase + config.personalBoost * it }
        val screen = if (screenVocab.contains(word)) config.screenOovLogProb else null
        var best: Float? = null
        for (v in listOf(main, pers, screen)) if (v != null && (best == null || v > best)) best = v
        if (best != null && screenVocab.contains(word)) best += config.screenBoost
        return best
    }

    /** log P(word | prev). prev may be null/empty (sentence start). */
    fun logProb(word: String, prev: String?): Float {
        val uni = unigram(word) ?: return config.oovLogProb
        val p = prev?.lowercase()?.takeIf { it.isNotEmpty() } ?: return uni
        val a = id(p); val b = id(word)
        if (a < 0 || b < 0) return uni
        val c = bigrams[key(a, b)] ?: return ln(1f - config.bigramLambda) + uni
        val left = leftCounts[a] ?: return uni
        val pb = c.toDouble() / left.toDouble()
        val lam = config.bigramLambda.toDouble()
        return ln(lam * pb + (1 - lam) * exp(uni.toDouble())).toFloat()
    }

    /** log P(w2 | w1) raw bigram support, used as a feature (0 if none). */
    fun bigramSupport(word: String, prev: String?): Float {
        val p = prev?.lowercase() ?: return 0f
        val a = id(p); val b = id(word)
        if (a < 0 || b < 0) return 0f
        val c = bigrams[key(a, b)] ?: return 0f
        val left = leftCounts[a] ?: return 0f
        val uni = unigram(word) ?: return 0f
        return (ln(c.toDouble() / left.toDouble()) - uni).toFloat().coerceAtLeast(0f)
    }

    /** Sum of bigram log-probs across a short word sequence. */
    fun sequenceLogProb(words: List<String>, prev: String?): Float {
        var p = prev
        var s = 0f
        for (w in words) { s += logProb(w.lowercase(), p); p = w }
        return s
    }

    companion object {
        private fun key(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

        fun loadDefault(lexicon: Lexicon): NgramLm {
            val s = NgramLm::class.java.getResourceAsStream("/lexicon/en_bigrams.tsv.gz")
                ?: error("missing /lexicon/en_bigrams.tsv.gz")
            return loadGz(lexicon, s)
        }

        fun loadGz(lexicon: Lexicon, input: InputStream): NgramLm =
            load(lexicon, GZIPInputStream(input).bufferedReader(Charsets.UTF_8).lineSequence())

        fun load(lexicon: Lexicon, lines: Sequence<String>): NgramLm {
            val ids = HashMap<String, Int>(300_000)
            val bigrams = HashMap<Long, Int>(300_000)
            val left = HashMap<Int, Long>(100_000)
            fun idOf(w: String) = ids.getOrPut(w) { ids.size }
            for (line in lines) {
                val parts = line.split('\t')
                if (parts.size != 3) continue
                val a = idOf(parts[0]); val b = idOf(parts[1])
                val c = parts[2].toIntOrNull() ?: continue
                bigrams[key(a, b)] = c
                left.merge(a, c.toLong(), Long::plus)
            }
            // left counts from bigram sums underestimate the true unigram count; scale by the
            // lexicon's count when available so P(b|a) is not inflated for rare left words.
            for ((w, i) in ids) {
                val uni = lexicon.unigram(w) ?: continue
                val est = exp(uni.toDouble()) * lexicon.totalCount
                val have = left[i] ?: 0L
                if (est > have) left[i] = est.toLong()
            }
            return NgramLm(lexicon, bigrams, left, ids)
        }

        fun empty(lexicon: Lexicon) = NgramLm(lexicon, HashMap(), HashMap(), HashMap())
    }
}

/** Optional neural LM reranker (llama.cpp on device). Implemented in the app; absent in the CLI. */
interface NeuralLm {
    /** natural-log score of each continuation given the context, same order as input */
    fun score(context: String, continuations: List<String>): FloatArray
    fun complete(context: String, maxTokens: Int): String
}

/** Letter bigram log-probs (with boundary markers) estimated from the lexicon, frequency-weighted. */
class CharModel private constructor(private val logp: FloatArray, private val uni: FloatArray) {
    private fun idx(c: Char): Int = when {
        c == '^' -> 0
        c == '$' -> 1
        c == '\'' -> 2
        c in 'a'..'z' -> 3 + (c - 'a')
        else -> 2
    }
    /** mean per-transition natural-log probability of [word] (lower = less word-like) */
    fun score(word: String): Float {
        if (word.isEmpty()) return -6f
        var prev = 0; var s = 0f; var n = 0
        for (ch in word.lowercase()) { val i = idx(ch); s += logp[prev * N + i]; prev = i; n++ }
        s += logp[prev * N + 1]; n++
        return s / n
    }
    companion object {
        private const val N = 29
        fun build(lex: Lexicon): CharModel {
            val counts = DoubleArray(N * N) { 0.5 }   // add-half smoothing
            val rowSum = DoubleArray(N) { 0.5 * N }
            val m = CharModel(FloatArray(0), FloatArray(0))
            // walk the trie
            fun walk(node: Int, prevIdx: Int, w: Double) {
                lex.forEachChild(node) { c, ch ->
                    val i = m.idx(ch)
                    val weight = Math.exp((lex.bestInSubtree(c) + 12.0).coerceAtLeast(0.0) / 4.0) // dampened frequency weight
                    counts[prevIdx * N + i] += weight; rowSum[prevIdx] += weight
                    if (lex.isWord(c)) { counts[i * N + 1] += weight; rowSum[i] += weight }
                    walk(c, i, w)
                }
            }
            walk(lex.root, 0, 1.0)
            val logp = FloatArray(N * N) { k -> Math.log(counts[k] / rowSum[k / N]).toFloat() }
            return CharModel(logp, FloatArray(0))
        }
    }
}
