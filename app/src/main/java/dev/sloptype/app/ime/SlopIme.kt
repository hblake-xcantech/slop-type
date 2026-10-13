package dev.sloptype.app.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import dev.sloptype.app.SlopRuntime
import dev.sloptype.engine.*
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.RichInputConnection
import helium314.keyboard.latin.utils.Log

/**
 * Bridge between HeliBoard's input pipeline and the Slop engine. All methods are called on the
 * IME main thread except [onMotion] (also main thread, from the keyboard view) and the pending
 * listener, which is posted back to the main thread.
 */
object SlopIme {
    private const val TAG = "SlopIme"
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var ctx: Context? = null
    @Volatile var sensitive = true
        private set
    @Volatile private var fieldActive = false
    private var lastDown: Touch? = null
    private var lastDownAt = 0L
    /** recent down touches (rollover typing: the next finger lands before the previous key fires) */
    private val recentDowns = ArrayDeque<Touch>()
    private var geometryId: String? = null
    private var pendingAfterInstall = false
    private var lastSave = 0L
    /** decisions in this field, newest last, for "tap a corrected word to revert" */
    private val recent = ArrayDeque<WordDecision>()
    /** listener installed by LatinIME to apply late (tier 2/3) edits */
    @Volatile var lateEditApplier: ((WordDecision) -> Unit)? = null
    @Volatile var lastDecision: WordDecision? = null
        private set
    /** the span chosen by the last MISTAKE resolution; "tap to shift" uses it */
    @Volatile var lastMistake: MistakeResolution? = null

    fun init(context: Context) {
        ctx = context.applicationContext
        SlopRuntime.init(context)
    }

    val enabled: Boolean get() = ctx != null

    fun engine(): SlopEngine? = SlopRuntime.engineOrNull()

    // ------------------------------------------------------------------ field lifecycle
    fun onStartInputView(editorInfo: EditorInfo?, keyboard: Keyboard?, textBeforeCursor: CharSequence?) {
        val c = ctx ?: return
        sensitive = SlopPrivacy.isSensitive(c, editorInfo)
        val geom = keyboard?.let { geometryOf(it) }
        if (geom != null) { SlopRuntime.lastGeometry = geom; persistGeometry(c, geom) }
        val e = SlopRuntime.engine(if (fieldActive) 0 else 300)
        val field = FieldInfo(editorInfo?.packageName ?: "", editorInfo?.inputType ?: 0, editorInfo?.imeOptions ?: 0, sensitive)
        if (e != null && geom != null) {
            e.pendingListener = { d -> main.post { lateEditApplier?.invoke(d) } }
            e.startField(field, geom, textBeforeCursor?.toString() ?: "")
        }
        recent.clear(); lastDecision = null; lastMistake = null
        fieldActive = true
        val logger = SlopRuntime.logger
        logger.enabled = !sensitive && SlopPrivacy.loggingEnabled(c)
        if (logger.enabled && geom != null) {
            logger.startSession(mapOf(
                "pkg" to (editorInfo?.packageName ?: ""), "inputType" to (editorInfo?.inputType ?: 0),
                "imeOptions" to (editorInfo?.imeOptions ?: 0), "geom" to geomMap(geom),
                "device" to android.os.Build.MODEL, "app_version" to helium314.keyboard.latin.BuildConfig.VERSION_NAME,
            ))
        }
    }

    fun onKeyboardChanged(keyboard: Keyboard?) {
        val geom = keyboard?.let { geometryOf(it) } ?: return
        if (geom.id == geometryId) return
        SlopRuntime.lastGeometry = geom
        engine()?.setGeometry(geom)
        geometryId = geom.id
    }

    fun onFinishInputView(textBeforeCursor: CharSequence?) {
        if (!fieldActive) return
        fieldActive = false
        val text = textBeforeCursor?.toString() ?: ""
        logTruth(text)
        engine()?.endField(text)
        SlopRuntime.logger.endSession()
        val c = ctx
        val now = System.currentTimeMillis()
        if (c != null && now - lastSave > 60_000) { lastSave = now; Thread { SlopRuntime.saveState(c) }.start() }
    }

    fun logTruth(textBeforeCursor: String) {
        if (sensitive || textBeforeCursor.isEmpty()) return
        SlopRuntime.logger.event("truth", mapOf("text" to textBeforeCursor.takeLast(240)))
    }

    // ------------------------------------------------------------------ touches
    /** Raw motion from MainKeyboardView; only down/up are kept. */
    fun onMotion(me: MotionEvent, keyboard: Keyboard?) {
        if (!fieldActive) return
        val action = me.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_POINTER_UP) return
        val idx = me.actionIndex
        val x = me.getX(idx); val y = me.getY(idx)
        val p = me.getPressure(idx); val s = me.getSize(idx)
        val t = me.eventTime
        val isDown = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
        val key = keyboard?.getKey(x.toInt(), y.toInt())?.firstOrNull()
        val code = key?.code ?: 0
        if (isDown) {
            val touch = Touch(x, y, t, p, s, code)
            lastDown = touch; lastDownAt = System.currentTimeMillis()
            synchronized(recentDowns) { recentDowns.addLast(touch); while (recentDowns.size > 8) recentDowns.removeFirst() }
        }
        if (!sensitive) SlopRuntime.logger.event("touch", mapOf("t" to t, "x" to x, "y" to y, "p" to p, "s" to s, "a" to (if (isDown) "d" else "u"), "code" to code))
    }

    private fun Keyboard.getKey(x: Int, y: Int): List<helium314.keyboard.keyboard.Key> {
        val nearest = getNearestKeys(x, y)
        return nearest.filter { it.isOnKey(x, y) }
    }

    /** A word character was appended to the composing word (InputLogic.handleNonSeparatorEvent). */
    fun onLetter(codePoint: Int, keyX: Int, keyY: Int) {
        if (!fieldActive) return
        val e = engine()
        // The event's x/y are the real touch coordinates of THIS key press (PointerTracker passes
        // them). Use them, and only borrow pressure/size/time from the matching down event.
        val lower = Character.toLowerCase(codePoint)
        val down: Touch? = synchronized(recentDowns) {
            recentDowns.lastOrNull { d ->
                (Character.toLowerCase(d.code) == lower) &&
                    (keyX < 0 || (kotlin.math.abs(d.x - keyX) < 80f && kotlin.math.abs(d.y - keyY) < 80f))
            }?.also { recentDowns.remove(it) }
        }
        val touch = when {
            keyX >= 0 && keyY >= 0 -> Touch(keyX.toFloat(), keyY.toFloat(), down?.t ?: System.currentTimeMillis(), down?.pressure ?: 0f, down?.size ?: 0f, codePoint)
            down != null -> down.copy(code = codePoint)
            else -> null
        }
        lastDown = null
        e?.onKey(codePoint, touch)
        if (!sensitive) SlopRuntime.logger.event("key", mapOf("code" to codePoint, "x" to keyX, "y" to keyY, "repeat" to false))
    }

    fun onBackspace(composing: Boolean) {
        if (!fieldActive) return
        engine()?.onBackspace(composing, null)
        if (!sensitive) SlopRuntime.logger.event("backspace", mapOf("composing" to composing, "count" to 1))
    }

    fun onSeparatorLogged(code: Int) {
        if (!sensitive) SlopRuntime.logger.event("key", mapOf("code" to code, "x" to -1, "y" to -1, "repeat" to false))
    }

    fun onCursorMoved(textBeforeCursor: CharSequence?) {
        engine()?.onCursorMoved(textBeforeCursor?.toString() ?: "")
    }

    // ------------------------------------------------------------------ decisions
    /** Called at a separator while composing. Returns the decision; the caller commits [WordDecision.commit]. */
    fun decide(separator: String, textBeforeCursor: CharSequence?, typedWord: String): WordDecision {
        val e = engine() ?: return WordDecision(typedWord, typedWord, DecisionKind.KEEP, 1f, reason = "engine-loading")
        if (!fieldActive) return WordDecision(typedWord, typedWord, DecisionKind.KEEP, 1f, reason = "no-field")
        return try {
            var d = e.onSeparator(separator, textBeforeCursor?.toString() ?: "")
            if (d.typed != typedWord) {
                // the composer and the engine disagree (e.g. gesture or pasted text): trust the composer
                Log.w(TAG, "typed mismatch engine='${d.typed}' composer='$typedWord'")
                d = WordDecision(typedWord, typedWord, DecisionKind.KEEP, 1f, reason = "mismatch")
            }
            if (sensitive && d.kind != DecisionKind.KEEP && d.kind != DecisionKind.CORRECT) d = d.copy(kind = DecisionKind.KEEP, commit = d.typed, pendingId = null)
            lastDecision = d
            if (d.kind == DecisionKind.CORRECT) { recent.addLast(d); while (recent.size > 20) recent.removeFirst() }
            d
        } catch (t: Throwable) {
            Log.e(TAG, "decide failed", t)
            WordDecision(typedWord, typedWord, DecisionKind.KEEP, 1f, reason = "error")
        }
    }

    fun onRevert(typed: String, committed: String) {
        val d = recent.lastOrNull { it.typed == typed && it.commit == committed } ?: lastDecision?.takeIf { it.typed == typed && it.commit == committed }
        if (d != null) engine()?.onRevert(d)
    }

    /** The original typed string if [word] is a word we corrected recently in this field. */
    fun originalFor(word: String): String? = recent.lastOrNull { it.commit == word }?.typed

    fun ghost(): Ghost? = if (fieldActive) engine()?.ghost() else null
    fun onGhostAccepted(g: Ghost) { engine()?.onGhostAccepted(g) }

    fun isMistakeArmed(): Boolean = engine()?.isMistakeArmed() ?: false
    fun armMistake() { engine()?.onMistakeMarker() }
    fun clearMistake() { engine()?.clearMistake() }
    fun resolveMistake(replacement: String, textBeforeCursor: CharSequence?): MistakeResolution? {
        val r = engine()?.resolveMistake(replacement, textBeforeCursor?.toString() ?: "")
        lastMistake = r
        return r
    }

    fun requestSentenceReview(textBeforeCursor: CharSequence?) {
        if (sensitive) return
        engine()?.requestSentenceReview(textBeforeCursor?.toString() ?: "")
    }

    // ------------------------------------------------------------------ retro edits
    /**
     * Replace earlier words before the cursor. [edits] use wordsBack counted from the end of the
     * text before the cursor (1 = last word). The cursor is restored afterwards. Returns how many
     * edits were applied.
     */
    fun applyRetro(edits: List<RetroEdit>, conn: RichInputConnection): Int {
        if (edits.isEmpty()) return 0
        var applied = 0
        for (edit in edits.sortedBy { it.wordsBack }) {   // nearest first keeps later offsets valid? no: farthest first keeps nearer offsets valid
        }
        // apply farthest-from-cursor first so the offsets of nearer words are unchanged? Offsets are
        // recomputed from the live text for each edit, so order does not matter; nearest first is fine.
        for (edit in edits) {
            if (applyOne(edit, conn)) applied++
        }
        return applied
    }

    private fun applyOne(edit: RetroEdit, conn: RichInputConnection): Boolean {
        val before = conn.getTextBeforeCursor(400, 0)?.toString() ?: return false
        val cursor = conn.expectedSelectionEnd
        if (cursor < 0) return false
        // tokenize keeping offsets
        val tokens = ArrayList<IntArray>() // [start, end)
        var i = 0
        while (i < before.length) {
            if (before[i].isWhitespace()) { i++; continue }
            val s = i
            while (i < before.length && !before[i].isWhitespace()) i++
            tokens.add(intArrayOf(s, i))
        }
        if (tokens.size < edit.wordsBack) return false
        val tok = tokens[tokens.size - edit.wordsBack]
        var s = tok[0]; var e = tok[1]
        // strip surrounding punctuation from the token to find the word core
        while (s < e && !isWordChar(before[s])) s++
        while (e > s && !isWordChar(before[e - 1])) e--
        val core = before.substring(s, e)
        if (!core.equals(edit.oldText, ignoreCase = false)) {
            if (!core.equals(edit.oldText, ignoreCase = true)) return false
        }
        val absStart = cursor - (before.length - s)
        val absEnd = cursor - (before.length - e)
        val delta = edit.newText.length - core.length
        try {
            conn.beginBatchEdit()
            conn.finishComposingText()
            conn.setComposingRegion(absStart, absEnd)
            conn.setComposingText(edit.newText, 1)
            conn.finishComposingText()
            conn.setSelection(cursor + delta, cursor + delta)
            conn.endBatchEdit()
        } catch (t: Throwable) {
            Log.e(TAG, "retro edit failed", t); return false
        }
        return true
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '-'

    // ------------------------------------------------------------------ geometry
    fun geometryOf(kb: Keyboard): KeyboardGeometry {
        val keys = ArrayList<KeyGeom>()
        for (k in kb.sortedKeys) {
            if (k.isSpacer) continue
            keys.add(KeyGeom(k.code, k.label ?: "", k.x.toFloat(), k.y.toFloat(), k.width.toFloat(), k.height.toFloat()))
        }
        val id = "${kb.mId.element.name.lowercase()}_${kb.mId.subtype.locale}_${kb.mOccupiedWidth}x${kb.mOccupiedHeight}_${kb.mId.width}"
        return KeyboardGeometry(id, kb.mOccupiedWidth.toFloat(), kb.mOccupiedHeight.toFloat(), keys)
    }

    private var persistedGeomId: String? = null
    private fun persistGeometry(c: Context, g: KeyboardGeometry) {
        if (g.id == persistedGeomId) return
        persistedGeomId = g.id
        Thread {
            runCatching { java.io.File(dev.sloptype.app.SlopPaths.state(c), "geometry.json").writeText(kotlinx.serialization.json.Json.encodeToString(KeyboardGeometry.serializer(), g)) }
        }.start()
    }

    private fun geomMap(g: KeyboardGeometry): Map<String, Any?> = mapOf(
        "id" to g.id, "width" to g.width, "height" to g.height,
        "keys" to g.keys.map { mapOf("code" to it.code, "label" to it.label, "x" to it.x, "y" to it.y, "w" to it.w, "h" to it.h) },
    )
}
