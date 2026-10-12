package dev.sloptype.app.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import dev.sloptype.app.SlopPaths
import dev.sloptype.app.SlopRuntime
import dev.sloptype.engine.ClassifyRequest
import dev.sloptype.engine.DecisionKind
import dev.sloptype.engine.FieldInfo
import dev.sloptype.engine.RewriteRequest
import dev.sloptype.engine.Touch
import dev.sloptype.engine.WordAlt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app latency/accuracy/cost benchmark of every available tier, triggered by `eval/bench.py`
 * over adb. Results go to `bench/latest.json` and are appended to `bench/history.jsonl`.
 */
object Benchmark {
    private const val TAG = "Benchmark"
    const val N = 20
    private val running = AtomicBoolean(false)
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    private val jsonLine = Json { encodeDefaults = true }

    @Serializable
    data class TierResult(
        val tier: Int, val provider: String, val model: String, val n: Int,
        val p50: Long, val p95: Long, val errors: Int, val accuracy: Double, val costUsd: Double, val costPer1kWords: Double,
        val note: String = "",
    )

    @Serializable
    data class BenchResult(val ts: Long, val network: String, val device: String, val tiers: List<TierResult>)

    /** (context, typed, candidates, expected) — expected is the final committed word. */
    data class Case(val context: String, val typed: String, val candidates: List<String>, val expected: String)

    val CASES: List<Case> = listOf(
        Case("I think", "teh", listOf("the", "tech"), "the"),
        Case("see you", "tomorow", listOf("tomorrow", "tomorrows"), "tomorrow"),
        Case("that was", "definately", listOf("definitely", "defiantly"), "definitely"),
        Case("can you", "recieve", listOf("receive", "relieve"), "receive"),
        Case("we should", "seperate", listOf("separate", "desperate"), "separate"),
        Case("I will", "probly", listOf("probably", "poorly"), "probably"),
        Case("what are", "thier", listOf("their", "there", "tier"), "their"),
        Case("it was", "wierd", listOf("weird", "wired"), "weird"),
        Case("just", "becuase", listOf("because", "beacuse"), "because"),
        Case("on the", "calender", listOf("calendar", "colander"), "calendar"),
        Case("I am", "running", listOf("ruining", "cunning"), "running"),
        Case("send it to", "Blake", listOf("black", "bleak"), "Blake"),
        Case("the new", "iPhone", listOf("phone", "iphones"), "iPhone"),
        Case("we use", "kubernetes", listOf("cybernetics"), "kubernetes"),
        Case("meet at", "Starbucks", listOf("starbucks", "starburst"), "Starbucks"),
        Case("that is", "lit", listOf("lot", "lie", "lid"), "lit"),
        Case("what a", "beautiful", listOf("bountiful"), "beautiful"),
        Case("I", "cant", listOf("can't", "cent", "cast"), "can't"),
        Case("please", "acknowlege", listOf("acknowledge", "knowledge"), "acknowledge"),
        Case("the", "goverment", listOf("government", "garment"), "government"),
    )

    /** Expected tier-2 answer: "keep" when the typed word is right, "novel" for names/new words, else the candidate. */
    fun expectedChoice(c: Case): Set<String> = when {
        c.expected.equals(c.typed, true) && c.candidates.none { it.equals(c.typed, true) } ->
            if (c.typed[0].isUpperCase() || c.typed in setOf("kubernetes", "lit")) setOf(Tier2Protocol.KEEP, Tier2Protocol.NOVEL) else setOf(Tier2Protocol.KEEP)
        c.expected.equals(c.typed, true) -> setOf(Tier2Protocol.KEEP, c.candidates.first { it.equals(c.typed, true) })
        else -> setOf(c.expected)
    }

    fun runAsync(ctx: Context, onDone: ((BenchResult?) -> Unit)? = null) {
        if (!running.compareAndSet(false, true)) { onDone?.invoke(null); return }
        val app = ctx.applicationContext
        Thread({
            try { onDone?.invoke(run(app)) } catch (t: Throwable) { Log.e(TAG, "benchmark failed", t); onDone?.invoke(null) } finally { running.set(false) }
        }, "slop-bench").apply { isDaemon = true }.start()
    }

    fun run(ctx: Context): BenchResult {
        val app = ctx.applicationContext
        val tiers = ArrayList<TierResult>()
        runCatching { tier1(app) }.onSuccess { if (it != null) tiers.add(it) }.onFailure { Log.w(TAG, "tier1 bench", it) }
        val cfg = SlopRuntime.engineOrNull()?.config ?: dev.sloptype.engine.EngineConfig.loadDefaultResource()
        val extras = RemoteWiring.routerExtras(app)
        for (provider in listOf(Tier2Protocol.PROVIDER_JEV, Tier2Protocol.PROVIDER_OPENAI_DECISIONS, Tier2Protocol.PROVIDER_OPENAI_RESPONSES)) {
            val model = if (provider == extras.tier2Provider) extras.tier2Model else ""
            val tier = if (provider == extras.tier2Provider) extras.tier2ServiceTier.ifBlank { null } else null
            val client = Tier2Client(app, cfg, provider, model, tier)
            if (!client.available) continue
            runCatching { tiers.add(tier2(client)) }.onFailure { Log.w(TAG, "tier2 bench $provider", it) }
        }
        val t3 = RemoteWiring.buildTier3(app)
        if (t3 != null) runCatching { tiers.add(tier3(t3)) }.onFailure { Log.w(TAG, "tier3 bench", it) }
        val result = BenchResult(System.currentTimeMillis(), network(app), Build.MODEL ?: "unknown", tiers)
        runCatching {
            File(SlopPaths.bench(app), "latest.json").writeText(json.encodeToString(BenchResult.serializer(), result))
            File(SlopPaths.bench(app), "history.jsonl").appendText(jsonLine.encodeToString(BenchResult.serializer(), result) + "\n")
        }.onFailure { Log.w(TAG, "write bench", it) }
        Log.i(TAG, "benchmark done: ${result.tiers.size} tiers on ${result.network}")
        return result
    }

    // ------------------------------------------------------------------ tiers

    /** On-device decode: synthetic touches at key centres, timed through the engine. */
    private fun tier1(ctx: Context): TierResult? {
        val engine = SlopRuntime.engine(5000) ?: return null
        val geom = SlopRuntime.lastGeometry ?: return TierResult(1, "device", "beam", 0, 0, 0, 0, 0.0, 0.0, 0.0, "no keyboard geometry yet")
        val lat = ArrayList<Long>(); var ok = 0; var err = 0
        // sensitive=true keeps remote tiers and logging out of the benchmark
        engine.startField(FieldInfo("dev.sloptype.bench", sensitive = true), geom, "")
        try {
            for (c in CASES) {
                try {
                    var t = System.currentTimeMillis()
                    for (ch in c.typed) {
                        val code = ch.lowercaseChar().code
                        val k = geom.key(code)
                        t += 150
                        engine.onKey(ch.code, if (k != null) Touch(k.cx, k.cy, t, 0.4f, 0.1f, code) else null)
                    }
                    val t0 = System.nanoTime()
                    val d = engine.onSeparator(" ", c.context + " " + c.typed)
                    lat.add((System.nanoTime() - t0) / 1_000_000)
                    val committed = if (d.kind == DecisionKind.PENDING) d.typed else d.commit
                    if (committed.equals(c.expected, true)) ok++
                } catch (t: Throwable) { err++ }
            }
        } finally {
            runCatching { engine.endField("") }
        }
        return summarise(1, "device", "beam", lat, ok, err, 0.0)
    }

    private fun tier2(client: Tier2Client): TierResult {
        val lat = ArrayList<Long>(); var ok = 0; var err = 0; var cost = 0.0
        for ((i, c) in CASES.withIndex()) {
            val t0 = System.nanoTime()
            val r = runCatching { client.classifyBlocking(ClassifyRequest(i.toLong(), c.context, c.typed, c.candidates)) }.getOrNull()
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (r == null) { err++; continue }
            lat.add(ms); cost += r.costUsd
            if (r.choice in expectedChoice(c)) ok++
        }
        return summarise(2, client.provider, client.model, lat, ok, err, cost)
    }

    private fun tier3(client: Tier3Client): TierResult {
        val lat = ArrayList<Long>(); var ok = 0; var err = 0
        var model = client.model() ?: "?"
        for ((i, c) in CASES.withIndex()) {
            val words = c.context.split(' ').map { WordAlt(it, emptyList(), true) } + WordAlt(c.typed, c.candidates, false)
            val t0 = System.nanoTime()
            val r = runCatching { client.rewriteBlocking(RewriteRequest(i.toLong(), words, "")) }.getOrNull()
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (r == null) { err++; continue }
            lat.add(ms); model = r.model
            if (r.words.last().equals(c.expected, true)) ok++
        }
        return summarise(3, "chatgpt", model, lat, ok, err, 0.0)
    }

    private fun summarise(tier: Int, provider: String, model: String, lat: List<Long>, ok: Int, err: Int, cost: Double): TierResult {
        val n = CASES.size
        val s = lat.sorted()
        fun pct(p: Double) = if (s.isEmpty()) 0L else s[((s.size - 1) * p).toInt()]
        return TierResult(tier, provider, model, n, pct(0.5), pct(0.95), err, ok.toDouble() / n, cost, if (n > 0) cost / n * 1000 else 0.0)
    }

    fun network(ctx: Context): String = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    } catch (_: Exception) { "unknown" }
}
