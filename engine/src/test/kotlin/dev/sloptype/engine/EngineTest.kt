package dev.sloptype.engine

import org.junit.BeforeClass
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EngineTest {
    companion object {
        lateinit var lexicon: Lexicon
        lateinit var lm: NgramLm

        @BeforeClass @JvmStatic
        fun load() {
            lexicon = Lexicon.loadDefault()
            lm = NgramLm.loadDefault(lexicon)
        }
    }

    private fun engine(): SlopEngine {
        val e = SlopEngine(lexicon, NgramLm.loadDefault(lexicon))
        e.startField(FieldInfo("test"), TestKeyboard.geom, "")
        return e
    }

    private fun type(e: SlopEngine, touches: List<Touch>, context: String = ""): WordDecision {
        for (t in touches) { e.onTouch(t); e.onKey(t.code, t) }
        val literal = String(touches.map { it.code.toChar() }.toCharArray())
        return e.onSeparator(" ", if (context.isEmpty()) literal else "$context $literal")
    }

    @Test fun lexiconLoads() {
        assertTrue(lexicon.contains("the"))
        assertTrue(!lexicon.contains("teh"), "known misspellings are excluded from the lexicon")
        assertTrue(lm.isKnown("don't") && lm.isKnown("elena's") || lm.isKnown("i'm"))
        assertTrue(lexicon.contains("monarchy"))
        assertTrue(!lexicon.contains("omarchy"))
        assertTrue(lexicon.unigram("the")!! > lexicon.unigram("tech")!!)
    }

    @Test fun tehBecomesThe() {
        val d = type(engine(), TestKeyboard.exact("teh"), "I think")
        assertEquals(DecisionKind.CORRECT, d.kind, d.reason)
        assertEquals("the", d.commit)
    }

    @Test fun preciseNovelWordIsKept() {
        val d = type(engine(), TestKeyboard.exact("omarchy"), "I use")
        assertTrue(d.kind == DecisionKind.NOVEL || d.kind == DecisionKind.KEEP, "got ${d.kind}: ${d.reason}")
        assertEquals("omarchy", d.commit)
    }

    @Test fun commonWordsKept() {
        val e = engine()
        for (w in listOf("the", "and", "you", "that", "with", "have", "this", "from", "they", "would", "there", "about", "people", "because", "form", "from")) {
            val d = type(e, TestKeyboard.exact(w), "so")
            assertEquals(w, d.commit, "word $w: ${d.reason}")
        }
    }

    @Test fun sloppyTapsNearKnownWordAreFixed() {
        // 'thw' typed because the last tap landed on w instead of e (adjacent); this is OOV and sloppy
        val touches = TestKeyboard.exact("the").toMutableList()
        val e = TestKeyboard.geom.key('e'.code)!!
        val w = TestKeyboard.geom.key('w'.code)!!
        // land just inside 'w', near the border with 'e'
        touches[2] = touches[2].copy(x = w.x + w.w - 10f, y = w.cy + 40f, code = 'w'.code)
        val d = type(engine(), touches, "I think")
        assertEquals("the", d.commit, d.reason)
    }

    @Test fun noisyTypingMostlyRight() {
        val e = engine()
        val words = "this is a test of the keyboard with some common words that people type every day".split(" ")
        var right = 0
        val rnd = Random(7)
        var ctx = ""
        for (w in words) {
            val d = type(e, TestKeyboard.touches(w, 0.28f, rnd), ctx)
            if (d.commit == w) right++ else println("MISS want=$w typed=${d.typed} got=${d.commit} ${d.kind} ${d.reason} alts=${d.alternatives}")
            ctx = "$ctx ${d.commit}".trim()
        }
        assertTrue(right >= words.size - 2, "only $right/${words.size} right")
    }

    @Test fun retypeLearnsAndLocks() {
        val e = engine()
        // first time: 'xcantech' precise → novel/keep. Make it sloppy so a correction is plausible
        val d1 = type(e, TestKeyboard.exact("teh"), "I think")
        assertEquals("the", d1.commit)
        // user deletes "the" and retypes "teh" → locked, learned (but teh is on the never-learn list, so not learned)
        val d2 = type(e, TestKeyboard.exact("teh"), "I think")
        assertEquals("teh", d2.commit, d2.reason)
        assertTrue(!e.personal.contains("teh"), "never-learn words must not enter the personal lexicon")
    }

    @Test fun retypeLearnsRealWord() {
        val e = engine()
        // force a correction by making the word sloppy: 'hblake' is OOV; type it with wide noise
        val typed = TestKeyboard.exact("hblake")
        val d1 = type(e, typed, "contact")
        // whatever the first decision, after a revert + retype the word must be locked and learned
        e.onRevert(d1)
        val d2 = type(e, typed, "contact")
        assertEquals("hblake", d2.commit)
        assertTrue(e.personal.contains("hblake"))
        assertTrue(e.personal.isLocked("hblake"))
    }

    @Test fun ghostCompletion() {
        val e = engine()
        for (t in TestKeyboard.exact("keyboa")) { e.onTouch(t); e.onKey(t.code, t) }
        val g = e.ghost()
        assertNotNull(g)
        assertEquals("keyboa", g.prefix)
        assertEquals("rd", g.completion)
    }

    @Test fun mistakeAlignment() {
        val e = engine()
        type(e, TestKeyboard.exact("this"), "")
        type(e, TestKeyboard.exact("is"), "this")
        type(e, TestKeyboard.exact("tst"), "this is")
        val r = e.resolveMistake("testing", "this is tst ")
        assertNotNull(r)
        assertEquals(1, r.wordsBack)
        assertEquals("tst", r.oldText)
    }

    @Test fun postureLearning() {
        val e = engine()
        val rnd = Random(3)
        var ctx = ""
        val words = "the quick brown fox jumps over the lazy dog and then runs away from the farmer".split(" ")
        for (w in words) {
            val d = type(e, TestKeyboard.touches(w, 0.15f, rnd, dx = 30f, dy = 25f), ctx)
            ctx = "$ctx ${d.commit}".trim()
        }
        val state = e.exportState()
        assertTrue(state.contains("\"dx\""))
        val st = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(SlopEngine.State.serializer(), state)
        val p = st.touchModels["test_qwerty"]!!.posture
        assertTrue(p.dx > 5f, "posture dx should move toward +30, got ${p.dx}")
    }

    @Test fun configRoundTrip() {
        val c = EngineConfig.loadDefaultResource()
        val j = EngineConfig.toJson(c)
        assertEquals(c, EngineConfig.fromJson(j))
        val c2 = EngineConfig.fromJson("""{"beamWidth": 12, "unknownKey": 3}""")
        assertEquals(12, c2.beamWidth)
        assertEquals(c.sigmaX, c2.sigmaX)
    }

    @Test fun decodeIsFast() {
        val e = engine()
        val rnd = Random(11)
        val t0 = System.nanoTime()
        var n = 0
        for (w in "performance matters because every keystroke runs the decoder again and again".split(" ")) {
            type(e, TestKeyboard.touches(w, 0.3f, rnd), ""); n++
        }
        val ms = (System.nanoTime() - t0) / 1_000_000.0 / n
        println("avg decode per word: %.2f ms".format(ms))
        assertTrue(ms < 60, "decode too slow: $ms ms/word")
    }
}
