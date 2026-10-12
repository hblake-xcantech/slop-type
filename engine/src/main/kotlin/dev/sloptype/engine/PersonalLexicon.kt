package dev.sloptype.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.pow

/**
 * Words the user taught us. Weight ∈ (0, 1.5]; it decays with disuse and is refreshed on use.
 * Words on the never-learn list (classic typos like "teh") are refused so "teh"→"the" can
 * never break.
 */
class PersonalLexicon {
    @Serializable
    data class Entry(var weight: Float, var count: Int, var lastUsed: Long, var source: String)

    @Serializable
    private data class Snapshot(val words: Map<String, Entry>)

    private val words = HashMap<String, Entry>()
    /** words the user explicitly confirmed this session (retyped after a revert): never corrected */
    val locked = HashSet<String>()
    var halfLifeDays = 45f
    var neverLearn: Set<String> = defaultNeverLearn()

    fun contains(word: String): Boolean = words.containsKey(word.lowercase())
    fun weight(word: String): Float? = words[word.lowercase()]?.weight
    fun isLocked(word: String) = locked.contains(word.lowercase())
    fun size() = words.size
    fun entries(): Map<String, Entry> = words

    fun learn(word: String, weight: Float, source: String, now: Long = System.currentTimeMillis()) {
        val w = word.lowercase()
        if (w.length < 2 || w in neverLearn || !w.all { it.isLetter() || it == '\'' || it == '-' }) return
        val e = words[w]
        if (e == null) words[w] = Entry(weight.coerceIn(0.05f, 1.5f), 1, now, source)
        else {
            e.weight = (e.weight + weight).coerceAtMost(1.5f); e.count++; e.lastUsed = now
            if (weight >= 1f) e.source = source
        }
    }

    fun touch(word: String, now: Long = System.currentTimeMillis()) {
        words[word.lowercase()]?.let { it.lastUsed = now; it.count++ }
    }

    fun lock(word: String) { locked.add(word.lowercase()) }

    fun forget(word: String) { words.remove(word.lowercase()) }

    /** Apply exponential decay since last use; drop words that fell below the floor. */
    fun decay(now: Long = System.currentTimeMillis()) {
        val it = words.entries.iterator()
        while (it.hasNext()) {
            val (_, e) = it.next()
            val days = (now - e.lastUsed) / 86_400_000.0
            if (days <= 0) continue
            e.weight = (e.weight * 0.5.pow(days / halfLifeDays)).toFloat()
            e.lastUsed = now
            if (e.weight < 0.05f) it.remove()
        }
    }

    fun toJson(): String = json.encodeToString(Snapshot.serializer(), Snapshot(words))

    fun loadJson(text: String) {
        val s = json.decodeFromString(Snapshot.serializer(), text)
        words.clear(); words.putAll(s.words)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
        fun defaultNeverLearn(): Set<String> {
            val s = PersonalLexicon::class.java.getResourceAsStream("/never_learn.txt") ?: return setOf("teh")
            return s.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
    }
}
