package dev.sloptype.engine

import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.ln

/**
 * Compact array-backed trie over the main word list with unigram log-probs (natural log).
 * Nodes: firstChild / nextSibling linked lists; `logp[node]` is NaN unless the node ends a word.
 * `maxLogp[node]` is the best word log-prob in the subtree (used for beam pruning and ghosts).
 */
class Lexicon private constructor(
    private val ch: CharArray,
    private val firstChild: IntArray,
    private val nextSibling: IntArray,
    private val logp: FloatArray,
    private val maxLogp: FloatArray,
    private val parent: IntArray,
    val size: Int,
    val totalCount: Double,
) {
    val root: Int get() = 0
    fun charAt(node: Int): Char = ch[node]
    fun wordLogP(node: Int): Float = logp[node]
    fun isWord(node: Int): Boolean = !logp[node].isNaN()
    fun bestInSubtree(node: Int): Float = maxLogp[node]

    inline fun forEachChild(node: Int, f: (child: Int, c: Char) -> Unit) {
        var c = firstChildOf(node)
        while (c != -1) {
            f(c, charAt(c))
            c = nextSiblingOf(c)
        }
    }
    fun firstChildOf(node: Int) = firstChild[node]
    fun nextSiblingOf(node: Int) = nextSibling[node]

    fun child(node: Int, c: Char): Int {
        var n = firstChild[node]
        while (n != -1) {
            if (ch[n] == c) return n
            n = nextSibling[n]
        }
        return -1
    }

    fun find(word: String): Int {
        var n = root
        for (c in word) {
            n = child(n, c)
            if (n == -1) return -1
        }
        return n
    }

    fun contains(word: String): Boolean {
        val n = find(word)
        return n != -1 && isWord(n)
    }

    /** natural-log unigram probability, or null if OOV */
    fun unigram(word: String): Float? {
        val n = find(word)
        return if (n != -1 && isWord(n)) logp[n] else null
    }

    fun wordOf(node: Int): String {
        val sb = StringBuilder()
        var n = node
        while (n != root) {
            sb.append(ch[n]); n = parent[n]
        }
        return sb.reverse().toString()
    }

    /** Best-first completions below [node] (most probable words first), at most [limit]. */
    fun completions(node: Int, limit: Int): List<Pair<String, Float>> {
        if (node == -1) return emptyList()
        val out = ArrayList<Pair<String, Float>>()
        // best-first search on maxLogp
        val pq = java.util.PriorityQueue<Int>(compareByDescending { maxLogp[it] })
        pq.add(node)
        var expanded = 0
        while (pq.isNotEmpty() && out.size < limit && expanded < 4000) {
            val n = pq.poll(); expanded++
            if (isWord(n) && n != node) out.add(wordOf(n) to logp[n])
            forEachChild(n) { c, _ -> pq.add(c) }
        }
        return out.sortedByDescending { it.second }.take(limit)
    }

    companion object {
        private const val LN10 = 2.302585092994046

        fun loadDefault(): Lexicon {
            val s = Lexicon::class.java.getResourceAsStream("/lexicon/en_unigrams.tsv.gz")
                ?: error("missing /lexicon/en_unigrams.tsv.gz")
            return loadGz(s, Misspellings.default().keys)
        }

        fun loadGz(input: InputStream, exclude: Set<String> = emptySet()): Lexicon =
            load(GZIPInputStream(input).bufferedReader(Charsets.UTF_8).readLines(), exclude)

        /** Lines: `#total\t<count>` then `word\tlog10prob`. Words in [exclude] (known misspellings) are dropped. */
        fun load(lines: List<String>, exclude: Set<String> = emptySet()): Lexicon {
            val words = ArrayList<String>(lines.size)
            val probs = ArrayList<Float>(lines.size)
            var total = 1.0
            for (line in lines) {
                if (line.isEmpty()) continue
                if (line.startsWith("#total")) { total = line.substringAfter('\t').toDouble(); continue }
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val w = line.substring(0, tab)
                if (w in exclude) continue
                words.add(w)
                probs.add((line.substring(tab + 1).toDouble() * LN10).toFloat())
            }
            return build(words, probs, total)
        }

        fun build(words: List<String>, logProbs: List<Float>, total: Double = 1.0): Lexicon {
            val cap = words.sumOf { it.length } + 1
            val ch = CharArray(cap)
            val firstChild = IntArray(cap) { -1 }
            val nextSibling = IntArray(cap) { -1 }
            val parent = IntArray(cap) { -1 }
            val logp = FloatArray(cap) { Float.NaN }
            var n = 1 // 0 = root
            for ((i, w) in words.withIndex()) {
                var node = 0
                for (c in w) {
                    var k = firstChild[node]
                    var prev = -1
                    while (k != -1 && ch[k] != c) { prev = k; k = nextSibling[k] }
                    if (k == -1) {
                        k = n++
                        ch[k] = c; parent[k] = node
                        if (prev == -1) firstChild[node] = k else nextSibling[prev] = k
                    }
                    node = k
                }
                val p = logProbs[i]
                logp[node] = if (logp[node].isNaN()) p else maxOf(logp[node], p)
            }
            val maxLogp = FloatArray(n) { Float.NEGATIVE_INFINITY }
            // nodes are allocated parents-before-children, so a reverse sweep propagates maxima
            for (i in n - 1 downTo 1) {
                val own = if (logp[i].isNaN()) Float.NEGATIVE_INFINITY else logp[i]
                if (own > maxLogp[i]) maxLogp[i] = own
                val p = parent[i]
                if (maxLogp[i] > maxLogp[p]) maxLogp[p] = maxLogp[i]
            }
            return Lexicon(
                ch.copyOf(n), firstChild.copyOf(n), nextSibling.copyOf(n), logp.copyOf(n), maxLogp, parent.copyOf(n),
                words.size, total,
            )
        }
    }
}

/** Natural-log helper. */
fun log10ToLn(l10: Double): Float = (l10 * ln(10.0)).toFloat()

/** Known misspelling → correct spelling (resource /misspellings.tsv). */
class Misspellings(private val map: Map<String, String>) {
    val keys: Set<String> get() = map.keys
    operator fun get(word: String): String? = map[word]
    companion object {
        fun default(): Misspellings {
            val s = Misspellings::class.java.getResourceAsStream("/misspellings.tsv") ?: return Misspellings(emptyMap())
            val m = HashMap<String, String>()
            s.bufferedReader().forEachLine { line ->
                val t = line.split('\t')
                if (t.size == 2 && t[0].isNotBlank()) m[t[0].trim()] = t[1].trim()
            }
            return Misspellings(m)
        }
    }
}
