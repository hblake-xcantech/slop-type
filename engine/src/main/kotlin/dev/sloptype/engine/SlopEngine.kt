package dev.sloptype.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The engine facade: owns the composing word, the decoder, the policy, learning, the remote
 * router and the retro-revision window. The Android shell and the CLI drive it identically.
 * Thread-safety: all `on*` calls happen on one thread (the IME main thread); remote callbacks
 * arrive on other threads and only touch concurrent structures + [pendingListener].
 */
class SlopEngine(
    private val lexicon: Lexicon,
    private val lm: NgramLm,
    config: EngineConfig = EngineConfig.loadDefaultResource(),
    val sink: LogSink = NoopLogSink,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var config: EngineConfig = config
        private set
    val personal: PersonalLexicon get() = lm.personal
    val policy = Policy(config)
    val router = Router(config)
    var tier2: Tier2Classifier? = null
    var tier3: Tier3Model? = null
    var neural: NeuralLm? = null
    /** called (on a background thread) when a PENDING word or a tier-3 rewrite resolves */
    @Volatile var pendingListener: ((WordDecision) -> Unit)? = null

    // ---- per-field state ----
    private var field: FieldInfo = FieldInfo("")
    private var geometry: KeyboardGeometry? = null
    private var touchModel: TouchModel? = null
    private var decoder: BeamDecoder? = null
    private val touchModels = HashMap<String, TouchModel>()
    private val reviser = SentenceReviser(lm, config)

    // ---- composing word ----
    private val touches = ArrayList<Touch>()
    private val literalRaw = StringBuilder()
    private var lastTouch: Touch? = null
    private var mistakeArmed = false

    // ---- history within the field ----
    private class Committed(
        val decision: WordDecision, val candidates: List<Candidate>, val touches: List<Touch>,
        val verdict: Policy.Verdict?, var locked: Boolean, val t: Long, var wordsAfter: Int = 0,
    )
    private val history = ArrayDeque<Committed>()
    private val recentCorrections = ArrayDeque<Committed>()  // CORRECT decisions awaiting accept/revert

    // ---- speculation ----
    private val ids = AtomicLong(1)
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "slop-spec").apply { isDaemon = true } }
    private var specFuture: ScheduledFuture<*>? = null
    private val specResults = ConcurrentHashMap<String, ClassifyResult>()   // literal -> result
    private val specInFlight = ConcurrentHashMap<String, Long>()            // literal -> id
    private val pending = ConcurrentHashMap<Long, PendingWord>()
    private class PendingWord(val decision: WordDecision, val literal: String, val candidates: List<String>, var wordsAfter: Int, val verdict: Policy.Verdict?)
    private val tier2Latencies = ArrayDeque<Long>()

    var correctionsThisField = 0; private set
    var wordsThisField = 0; private set

    // ------------------------------------------------------------------ lifecycle
    fun startField(field: FieldInfo, geometry: KeyboardGeometry, textBeforeCursor: String) {
        this.field = field
        setGeometry(geometry)
        resetComposing()
        history.clear(); recentCorrections.clear()
        correctionsThisField = 0; wordsThisField = 0
        mistakeArmed = false
        router.rollMonth(clock())
    }

    fun setGeometry(geometry: KeyboardGeometry) {
        if (this.geometry?.id == geometry.id && touchModel != null) return
        this.geometry = geometry
        val tm = touchModels.getOrPut(geometry.id) { TouchModel(geometry, config) }
        tm.config = config
        touchModel = tm
        decoder = BeamDecoder(lexicon, tm, lm, config)
    }

    fun endField(textBeforeCursor: String) {
        resetComposing()
        specFuture?.cancel(false)
    }

    fun reloadConfig(json: String) {
        val cfg = EngineConfig.fromJson(json)
        applyConfig(cfg)
    }

    fun applyConfig(cfg: EngineConfig) {
        config = cfg
        policy.config = cfg
        router.config = cfg
        reviser.config = cfg
        lm.config = cfg
        lm.personal.halfLifeDays = cfg.lexiconHalfLifeDays
        touchModels.values.forEach { it.config = cfg }
        decoder?.config = cfg
    }

    fun reloadRouter(json: String) {
        router.routerConfig = Json { ignoreUnknownKeys = true }.decodeFromString(Router.RouterConfig.serializer(), json)
    }

    // ------------------------------------------------------------------ typing
    fun onTouch(touch: Touch) { lastTouch = touch }

    /** A word character was appended to the composing word. */
    fun onKey(code: Int, touch: Touch?) {
        val t = touch ?: lastTouch?.takeIf { it.code == code } ?: syntheticTouch(code)
        touches.add(t.copy(code = code))
        literalRaw.append(code.toChar())
        lastTouch = null
        maybeSpeculate()
    }

    private fun syntheticTouch(code: Int): Touch {
        val k = geometry?.key(Character.toLowerCase(code)) ?: geometry?.key(code)
        return Touch(k?.cx ?: 0f, k?.cy ?: 0f, clock(), 0f, 0f, code)
    }

    fun onBackspace(composing: Boolean, deletedChar: Char?) {
        if (composing && touches.isNotEmpty()) {
            touches.removeAt(touches.size - 1)
            literalRaw.setLength(literalRaw.length - 1)
        }
        specFuture?.cancel(false)
    }

    fun isComposing() = touches.isNotEmpty()
    fun composingText(): String = literalRaw.toString()

    fun onCursorMoved(textBeforeCursor: String) {
        // Moving the cursor away ends the composing word without a decision.
        resetComposing()
    }

    private fun resetComposing() {
        touches.clear(); literalRaw.setLength(0); lastTouch = null
        specFuture?.cancel(false)
    }

    fun onMistakeMarker() { mistakeArmed = true }
    fun isMistakeArmed() = mistakeArmed
    fun clearMistake() { mistakeArmed = false }

    fun ghost(): Ghost? {
        val d = decoder ?: return null
        if (touches.isEmpty()) return null
        return d.ghost(touches, prevWordHint)
    }

    private var prevWordHint: String? = null

    // ------------------------------------------------------------------ decision
    fun onSeparator(separator: String, textBeforeCursor: String): WordDecision {
        val t0 = clock()
        val literal = literalRaw.toString()
        val touchesCopy = ArrayList(touches)
        val ctx = contextWords(textBeforeCursor, literal)
        val prev = ctx.lastOrNull()
        prevWordHint = literal.lowercase()
        resetComposing()
        if (literal.isEmpty()) return WordDecision(literal, literal, DecisionKind.KEEP, 1f, reason = "empty")
        val d = decoder ?: return WordDecision(literal, literal, DecisionKind.KEEP, 1f, reason = "no-geometry")
        if (mistakeArmed) {
            mistakeArmed = false
            return WordDecision(literal, literal, DecisionKind.KEEP, 1f, reason = "mistake-replacement")
        }
        if (!isDecodable(literal)) return commitDecision(WordDecision(literal, literal, DecisionKind.KEEP, 1f, reason = "non-word"), emptyList(), touchesCopy, null, t0)

        val res = d.decode(touchesCopy, prev)
        val lower = literal.lowercase()
        val best = res.candidates.firstOrNull { !it.word.equals(lower, true) }
        val litCand = res.candidates.firstOrNull { it.word.equals(lower, true) } ?: res.literal
        val retype = detectRetype(lower, textBeforeCursor)
        val locked = retype || personal.isLocked(lower)
        val evidence = Policy.Evidence(
            literal = litCand, best = best, precisionMean = res.precisionMean, precisionMin = res.precisionMin,
            prev = prev, contextSupport = best?.let { lm.bigramSupport(it.word, prev) } ?: 0f,
            literalLocked = locked, literalPersonal = personal.contains(lower),
            literalCapitalized = literal[0].isUpperCase() && literal.drop(1).any { it.isLowerCase() } && prev != null,
            bestPersonal = best != null && personal.contains(best.word),
            wordlikenessGain = if (best != null) lm.chars.score(best.word) - lm.chars.score(lower) else 0f,
        )
        var verdict = policy.decide(evidence)
        var tier = 1
        var reason = verdict.reason
        var kind = verdict.kind
        var pendingId: Long? = null

        if (retype) {
            personal.learn(lower, config.retypeWeight, "retype", t0); personal.lock(lower)
            sink.event("retype", mapOf("word" to lower, "t" to t0))
            // and tell the policy that correcting this one was wrong
            recentCorrections.lastOrNull { it.decision.typed.equals(lower, true) }?.verdict?.let { policy.feedback(it, 0f, 1.5f) }
            reason = "retype"
        }

        // neural reranking at the boundary (only when the choice is close)
        val nn = neural
        if (nn != null && best != null && kind != DecisionKind.KEEP && policy.isAmbiguous(verdict)) {
            try {
                val contextStr = ctx.joinToString(" ")
                val scores = nn.score(contextStr, listOf(lower, best.word))
                if (scores.size == 2) {
                    val delta = scores[1] - scores[0]
                    if (delta < -1.0f && kind == DecisionKind.CORRECT) { kind = if (litCand.inLexicon) DecisionKind.KEEP else DecisionKind.NOVEL; reason += " nn-keep" }
                    if (delta > 2.5f && kind != DecisionKind.CORRECT && !locked) { kind = DecisionKind.CORRECT; reason += " nn-correct" }
                }
            } catch (_: Throwable) {}
        }

        // tier 2: speculative answer already here?
        val candWords = res.candidates.filter { !it.word.equals(lower, true) }.map { it.word }.take(4)
        val situation = if (litCand.inLexicon) "invocab_ambiguous" else "oov_ambiguous"
        if (!locked && policy.isAmbiguous(verdict) && router.tier2Allowed(field.sensitive, situation) && candWords.isNotEmpty()) {
            val spec = specResults.remove(lower)
            if (spec != null) {
                tier = 2
                kind = applyClassify(spec, candWords, litCand.inLexicon)
                reason += " tier2=${spec.choice}"
                if (kind == DecisionKind.CORRECT) verdict = verdict.copy(kind = kind)
                if (spec.choice != "keep" && spec.choice != "novel") {
                    // remote chose a different candidate than our best: reorder
                    val chosen = res.candidates.firstOrNull { it.word == spec.choice }
                    if (chosen != null) {
                        val decision = finishDecision(literal, lower, chosen, kind, res, verdict, tier, t0, reason, null, candWords, touchesCopy, ctx)
                        return decision
                    }
                }
            } else {
                // not resolved yet: commit as typed, resolve once when the answer lands
                val id = specInFlight[lower] ?: fireClassify(lower, candWords, ctx, t0)
                pendingId = id
                kind = DecisionKind.PENDING
                reason += " pending"
            }
        }

        val chosen = if (kind == DecisionKind.CORRECT && best != null) best else litCand
        return finishDecision(literal, lower, chosen, kind, res, verdict, tier, t0, reason, pendingId, candWords, touchesCopy, ctx)
    }

    private fun finishDecision(
        literal: String, lower: String, chosen: Candidate, kind: DecisionKind, res: BeamDecoder.Result,
        verdict: Policy.Verdict, tier: Int, t0: Long, reason: String, pendingId: Long?, candWords: List<String>,
        touchesCopy: List<Touch>, ctx: List<String>,
    ): WordDecision {
        val commitWord = if (kind == DecisionKind.CORRECT) SentenceReviser.matchCase(literal, chosen.word) else literal
        val conf = BeamDecoder.sigmoid(kotlin.math.abs(verdict.logit))
        var decision = WordDecision(
            typed = literal, commit = commitWord, kind = kind, confidence = conf,
            alternatives = candWords, tier = tier, latencyMs = clock() - t0, pendingId = pendingId, reason = reason,
        )
        // retroactive revision of earlier words
        val retro = computeRetro(decision, res.candidates, ctx)
        if (retro.isNotEmpty()) decision = decision.copy(retro = retro)
        decision = commitDecision(decision, res.candidates, touchesCopy, verdict, t0)
        if (pendingId != null) pending[pendingId] = PendingWord(decision, lower, candWords, 0, verdict)
        // posture: adopt the hypothesis that explained this word if it was not the current one
        touchModel?.let { tm -> if (res.posture != tm.posture) tm.nudgeToward(res.posture) }
        return decision
    }

    private fun isDecodable(literal: String): Boolean =
        literal.length in 1..24 && literal.all { it.isLetter() || it == '\'' } && literal.any { it.isLetter() }

    private fun applyClassify(r: ClassifyResult, candWords: List<String>, inLexicon: Boolean): DecisionKind = when (r.choice) {
        "keep" -> if (inLexicon) DecisionKind.KEEP else DecisionKind.NOVEL
        "novel" -> DecisionKind.NOVEL
        in candWords -> DecisionKind.CORRECT
        else -> if (inLexicon) DecisionKind.KEEP else DecisionKind.NOVEL
    }

    /** Book-keeping after the shell will apply [decision]: learning, history, logging. */
    private fun commitDecision(decision: WordDecision, candidates: List<Candidate>, touchesCopy: List<Touch>, verdict: Policy.Verdict?, t0: Long): WordDecision {
        wordsThisField++
        val lower = decision.typed.lowercase()
        val locked = personal.isLocked(lower)
        val c = Committed(decision, candidates, touchesCopy, verdict, locked, t0)
        history.forEach { it.wordsAfter++ }
        history.addLast(c)
        while (history.size > 12) history.removeFirst()
        when (decision.kind) {
            DecisionKind.CORRECT -> {
                correctionsThisField++
                recentCorrections.addLast(c)
                while (recentCorrections.size > 6) recentCorrections.removeFirst()
                val best = candidates.firstOrNull { it.word.equals(decision.commit, true) }
                if (best != null && best.edits == 0 && best.word.length == touchesCopy.size) touchModel?.learn(best.word, touchesCopy)
                personal.touch(decision.commit.lowercase(), t0)
            }
            DecisionKind.KEEP -> {
                if (lower.length == touchesCopy.size && lm.isKnown(lower)) touchModel?.learn(lower, touchesCopy)
                personal.touch(lower, t0)
            }
            DecisionKind.NOVEL -> {
                if (lower.length == touchesCopy.size) touchModel?.learn(lower, touchesCopy)
                personal.learn(lower, config.novelTentativeWeight, "novel", t0)
                maybeLookupNovel(lower, decision)
            }
            DecisionKind.PENDING -> {}
        }
        // accepted corrections: once two more words were typed without a revert, reinforce
        val it = recentCorrections.iterator()
        while (it.hasNext()) {
            val rc = it.next()
            if (rc !== c && rc.wordsAfter >= 2) { rc.verdict?.let { v -> policy.feedback(v, 1f, 0.3f) }; it.remove() }
        }
        for (p in pending.values) p.wordsAfter++
        sink.event("decision", mapOf(
            "t" to t0, "typed" to decision.typed, "commit" to decision.commit, "kind" to decision.kind.name,
            "conf" to decision.confidence, "tier" to decision.tier, "latencyMs" to decision.latencyMs,
            "alts" to decision.alternatives, "retro" to decision.retro.map { mapOf("wordsBack" to it.wordsBack, "old" to it.oldText, "new" to it.newText) },
            "pendingId" to decision.pendingId, "reason" to decision.reason,
        ))
        return decision
    }

    private fun computeRetro(current: WordDecision, candidates: List<Candidate>, ctx: List<String>): List<RetroEdit> {
        if (config.retroWindow < 2) return emptyList()
        val window = ArrayList<SentenceReviser.Slot>()
        val hist = history.takeLast(config.retroWindow - 1)
        // only consecutive words typed in this field, in order; ctx must still end with them
        val ctxTail = ctx.takeLast(hist.size).map { it.lowercase() }
        if (ctxTail.size == hist.size && hist.indices.all { ctxTail[it] == hist[it].decision.commit.lowercase() }) {
            for (h in hist) window.add(SentenceReviser.Slot(h.decision.commit, h.candidates, h.locked || h.decision.kind == DecisionKind.NOVEL || h.decision.kind == DecisionKind.PENDING, h.decision.kind))
        } else return emptyList()
        window.add(SentenceReviser.Slot(current.commit, candidates, true, current.kind))
        val prevContext = ctx.dropLast(hist.size).lastOrNull()
        return reviser.revise(window, prevContext)
    }

    /** Previous words before the composing literal, from the field text. */
    private fun contextWords(textBeforeCursor: String, literal: String): List<String> {
        var s = textBeforeCursor
        if (literal.isNotEmpty() && s.endsWith(literal)) s = s.substring(0, s.length - literal.length)
        val words = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        // stop at sentence punctuation: context resets
        val out = ArrayList<String>()
        for (w in words.takeLast(8)) {
            val clean = w.trim { !it.isLetterOrDigit() && it != '\'' }
            if (w.endsWith('.') || w.endsWith('?') || w.endsWith('!') || w.endsWith('\n')) { out.clear(); continue }
            if (clean.isNotEmpty()) out.add(clean)
        }
        return out
    }

    /** "backspace + retype identical string": a recent correction's typed form shows up again and its commit is gone. */
    private fun detectRetype(lower: String, textBeforeCursor: String): Boolean {
        val rc = recentCorrections.lastOrNull { it.decision.typed.equals(lower, true) && !it.decision.commit.equals(lower, true) } ?: return false
        val tail = textBeforeCursor.takeLast(80).lowercase()
        val committed = rc.decision.commit.lowercase()
        return !Regex("\\b" + Regex.escape(committed) + "\\b").containsMatchIn(tail)
    }

    // ------------------------------------------------------------------ feedback from the shell
    fun onCommitted(decision: WordDecision) { /* reserved: shell confirmation */ }

    /** The user backspaced right after a correction and the original was restored. */
    fun onRevert(decision: WordDecision) {
        val c = history.lastOrNull { it.decision === decision || (it.decision.typed == decision.typed && it.decision.commit == decision.commit) }
        c?.verdict?.let { policy.feedback(it, 0f, 1.5f) }
        c?.locked = true
        val lower = decision.typed.lowercase()
        personal.learn(lower, 0.5f, "revert", clock()); personal.lock(lower)
        recentCorrections.removeAll { it === c }
        sink.event("revert", mapOf("t" to clock(), "from" to decision.commit, "to" to decision.typed))
    }

    fun onGhostAccepted(ghost: Ghost) {
        sink.event("ghost_accept", mapOf("t" to clock(), "prefix" to ghost.prefix, "completion" to ghost.completion))
        resetComposing()
    }

    fun onScreenText(words: Collection<String>) {
        lm.screenVocab = words.map { it.lowercase() }.filter { it.length in 2..30 && it.all { ch -> ch.isLetter() || ch == '\'' || ch == '-' } }.toHashSet()
    }

    // ------------------------------------------------------------------ MISTAKE key
    fun resolveMistake(replacement: String, textBeforeCursor: String): MistakeResolution? {
        mistakeArmed = false
        val tm = touchModel
        val aligner = MistakeAligner(lm, tm)
        val ctx = contextWords(textBeforeCursor, "")
        if (replacement.isEmpty()) {
            val last = ctx.lastOrNull() ?: return null
            return MistakeResolution(1, last, "", deleteOnly = true)
        }
        // recent words: prefer our history when it matches the text, else fall back to the text
        val recent = ArrayList<MistakeAligner.RecentWord>()
        val hist = history.takeLast(4)
        val tail = ctx.takeLast(hist.size)
        if (hist.isNotEmpty() && tail.size == hist.size && hist.indices.all { tail[it].equals(hist[it].decision.commit, true) }) {
            for (h in hist) recent.add(MistakeAligner.RecentWord(h.decision.commit, h.touches))
        } else for (w in ctx.takeLast(4)) recent.add(MistakeAligner.RecentWord(w, null))
        val r = aligner.resolve(replacement, recent, ctx) ?: return null
        sink.event("mistake", mapOf("t" to clock(), "replacement" to replacement, "chosenSpan" to r.oldText, "candidates" to r.alternatives.map { it.second }))
        return r
    }

    // ------------------------------------------------------------------ tier 2 speculation
    private fun maybeSpeculate() {
        val t2 = tier2 ?: return
        if (!t2.available) return
        val lower = literalRaw.toString().lowercase()
        if (lower.length < config.tier2MinChars || !isDecodable(lower)) return
        if (lm.isKnown(lower) || personal.isLocked(lower)) return
        if (!router.tier2Allowed(field.sensitive, "oov_ambiguous")) return
        specFuture?.cancel(false)
        val snapshot = ArrayList(touches)
        specFuture = exec.schedule({
            try {
                val d = decoder ?: return@schedule
                val res = d.decodeUnder(snapshot, prevWordHint, touchModel?.posture ?: Posture())
                val litCand = res.candidates.firstOrNull { it.word == lower } ?: res.literal
                val best = res.candidates.firstOrNull { it.word != lower } ?: return@schedule
                val ev = Policy.Evidence(litCand, best, res.precisionMean, res.precisionMin, prevWordHint, lm.bigramSupport(best.word, prevWordHint), false, personal.contains(lower))
                val v = policy.decide(ev)
                if (!policy.isAmbiguous(v)) return@schedule
                if (specInFlight.containsKey(lower) || specResults.containsKey(lower)) return@schedule
                val cands = res.candidates.filter { it.word != lower }.map { it.word }.take(4)
                fireClassify(lower, cands, emptyList(), clock())
            } catch (_: Throwable) {}
        }, config.tier2DebounceMs, TimeUnit.MILLISECONDS)
    }

    private fun fireClassify(lower: String, candWords: List<String>, ctx: List<String>, t0: Long): Long {
        val t2 = tier2 ?: return -1
        val id = ids.getAndIncrement()
        specInFlight[lower] = id
        val req = ClassifyRequest(id, ctx.takeLast(6).joinToString(" "), lower, candWords)
        t2.classify(req) { r ->
            specInFlight.remove(lower)
            router.recordTier2(r)
            if (r != null) {
                synchronized(tier2Latencies) { tier2Latencies.addLast(r.latencyMs); while (tier2Latencies.size > 50) tier2Latencies.removeFirst(); router.observedTier2P95Ms = p95(tier2Latencies) }
                sink.event("remote", mapOf("t" to clock(), "tier" to 2, "model" to r.model, "ok" to true, "latencyMs" to r.latencyMs, "costUsd" to r.costUsd))
                val pw = pending.values.firstOrNull { it.decision.pendingId == id }
                if (pw != null) resolvePending(pw, r) else specResults[lower] = r
            } else {
                sink.event("remote", mapOf("t" to clock(), "tier" to 2, "ok" to false))
                pending.values.firstOrNull { it.decision.pendingId == id }?.let { pending.remove(id) }
            }
        }
        return id
    }

    private fun resolvePending(pw: PendingWord, r: ClassifyResult) {
        pending.remove(pw.decision.pendingId)
        val inLex = lm.isKnown(pw.literal)
        val kind = applyClassify(r, pw.candidates, inLex)
        val resolved = if (kind == DecisionKind.CORRECT) {
            val newW = SentenceReviser.matchCase(pw.decision.typed, r.choice)
            pw.decision.copy(kind = kind, commit = newW, tier = 2, latencyMs = r.latencyMs, retro = listOf(RetroEdit(pw.wordsAfter + 1, pw.decision.typed, newW)), reason = "tier2-late")
        } else {
            if (kind == DecisionKind.NOVEL) personal.learn(pw.literal, config.novelTentativeWeight, "tier2", clock())
            pw.decision.copy(kind = kind, tier = 2, latencyMs = r.latencyMs, reason = "tier2-late-keep")
        }
        sink.event("pending_resolved", mapOf("t" to clock(), "pendingId" to pw.decision.pendingId, "kind" to kind.name, "commit" to resolved.commit, "latencyMs" to r.latencyMs, "tier" to 2))
        pendingListener?.invoke(resolved)
    }

    fun pendingResult(pendingId: Long): WordDecision? = null // resolved via pendingListener

    private fun p95(xs: Collection<Long>): Long {
        if (xs.isEmpty()) return 0
        val s = xs.sorted(); return s[((s.size - 1) * 0.95).toInt()]
    }

    // ------------------------------------------------------------------ tier 3
    private fun maybeLookupNovel(lower: String, decision: WordDecision) {
        val t3 = tier3 ?: return
        if (!t3.available || !router.tier3Allowed(field.sensitive, "novel_lookup")) return
        if (decision.confidence > 0.85f) return
        t3.lookupWord(lower, "") { known ->
            if (known == true) personal.learn(lower, 0.8f, "tier3", clock())
            sink.event("remote", mapOf("t" to clock(), "tier" to 3, "ok" to (known != null), "kind" to "lookup"))
        }
    }

    /** Called by the shell at sentence punctuation or after a typing pause. */
    fun requestSentenceReview(textBeforeCursor: String) {
        val t3 = tier3 ?: return
        if (!t3.available || !router.tier3Allowed(field.sensitive, "sentence_end")) return
        val hist = history.takeLast(10)
        if (hist.size < config.tier3MinWords) return
        val ctx = contextWords(textBeforeCursor, "")
        val tail = ctx.takeLast(hist.size)
        if (tail.size != hist.size || !hist.indices.all { tail[it].equals(hist[it].decision.commit, true) }) return
        val words = hist.map { h -> WordAlt(h.decision.commit, h.candidates.map { it.word }.filter { !it.equals(h.decision.commit, true) }.take(4), h.locked || h.decision.kind == DecisionKind.NOVEL) }
        val id = ids.getAndIncrement()
        val before = ctx.dropLast(hist.size).joinToString(" ")
        t3.rewrite(RewriteRequest(id, words, before)) { r ->
            sink.event("remote", mapOf("t" to clock(), "tier" to 3, "ok" to (r != null), "latencyMs" to (r?.latencyMs ?: 0), "kind" to "rewrite"))
            if (r == null || r.words.size != words.size) return@rewrite
            val edits = ArrayList<RetroEdit>()
            for (i in words.indices) {
                val old = words[i].text; val new = r.words[i]
                if (new.equals(old, true) || words[i].locked) continue
                val ok = words[i].alternatives.any { it.equals(new, true) } || (lm.isKnown(new.lowercase()) && MistakeAligner.editDistance(old.lowercase(), new.lowercase()) <= 2)
                if (ok) edits.add(RetroEdit(words.size - i, old, SentenceReviser.matchCase(old, new)))
            }
            if (edits.isEmpty()) return@rewrite
            pendingListener?.invoke(WordDecision("", "", DecisionKind.CORRECT, 0.9f, retro = edits, tier = 3, latencyMs = r.latencyMs, reason = "tier3-rewrite"))
        }
    }

    // ------------------------------------------------------------------ persistence
    @Serializable
    data class State(
        val touchModels: Map<String, TouchModel.State> = emptyMap(),
        val policy: Policy.State? = null,
        val spend: Spend = Spend(),
        val lexiconVersion: Int = 1,
    )

    fun exportState(): String {
        val s = State(touchModels.mapValues { it.value.state() }, policy.state(), router.spend)
        return json.encodeToString(State.serializer(), s)
    }

    fun importState(text: String) {
        val s = json.decodeFromString(State.serializer(), text)
        for ((id, st) in s.touchModels) touchModels[id]?.load(st) ?: run { pendingTouchStates[id] = st }
        s.policy?.let { policy.load(it) }
        router.spend.month = s.spend.month; router.spend.usd = s.spend.usd; router.spend.calls = s.spend.calls
    }
    private val pendingTouchStates = HashMap<String, TouchModel.State>()

    fun snapshotPersonalLexicon(): String = personal.toJson()
    fun loadPersonalLexicon(text: String) { personal.loadJson(text); personal.decay(clock()) }

    fun shutdown() { exec.shutdownNow() }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Convenience: load the bundled English lexicon + bigrams. Takes ~0.5 s on a phone. */
        fun createDefault(sink: LogSink = NoopLogSink, config: EngineConfig = EngineConfig.loadDefaultResource()): SlopEngine {
            val lex = Lexicon.loadDefault()
            val lm = NgramLm.loadDefault(lex)
            return SlopEngine(lex, lm, config, sink)
        }
    }
}
