package dev.sloptype.app.context

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.sloptype.app.SlopRuntime
import dev.sloptype.app.ime.SlopPrivacy

/**
 * Reads visible on-screen text (debounced) and hands the words to the engine as a vocabulary
 * bias. Enabled on the rooted phone via `scripts/provision_phone.sh --enable-accessibility`.
 * Excluded apps and password fields contribute nothing. Text is never stored or logged.
 */
class ScreenContextService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var lastPkg: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (SlopPrivacy.isExcludedPackage(this, pkg)) {
            if (lastPkg != pkg) { SlopRuntime.engineOrNull()?.onScreenText(emptyList()); lastPkg = pkg }
            return
        }
        lastPkg = pkg
        pending?.let { main.removeCallbacks(it) }
        val r = Runnable { collect() }
        pending = r
        main.postDelayed(r, 350)
    }

    private fun collect() {
        val engine = SlopRuntime.engineOrNull() ?: return
        val words = HashSet<String>()
        try {
            val root = rootInActiveWindow ?: return
            walk(root, words, 0)
        } catch (_: Throwable) { return }
        engine.onScreenText(words)
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableSet<String>, depth: Int) {
        if (depth > 40 || out.size > 600) return
        if (node.isPassword) return
        val texts = listOfNotNull(node.text, node.contentDescription)
        for (t in texts) tokenize(t.toString(), out)
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            walk(c, out, depth + 1)
            if (android.os.Build.VERSION.SDK_INT < 34) @Suppress("DEPRECATION") c.recycle()
        }
    }

    private fun tokenize(s: String, out: MutableSet<String>) {
        var i = 0
        val n = s.length
        while (i < n) {
            if (!s[i].isLetter()) { i++; continue }
            val st = i
            while (i < n && (s[i].isLetter() || s[i] == '\'' || s[i] == '-')) i++
            val w = s.substring(st, i)
            if (w.length in 2..30) out.add(w.lowercase())
        }
    }

    override fun onInterrupt() {}
}
