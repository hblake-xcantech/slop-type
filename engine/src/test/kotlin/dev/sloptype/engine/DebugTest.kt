package dev.sloptype.engine

import org.junit.Test

/** Prints decoder internals for a few cases; not an assertion test. */
class DebugTest {
    @Test fun dump() {
        val lex = Lexicon.loadDefault()
        val lm = NgramLm.loadDefault(lex)
        val tm = TouchModel(TestKeyboard.geom, EngineConfig.loadDefaultResource())
        val dec = BeamDecoder(lex, tm, lm, EngineConfig.loadDefaultResource())
        for ((w, ctx) in listOf("teh" to "I think", "have" to "so", "omarchy" to "I use", "keyboa" to null, "thw" to "I think", "form" to "so")) {
            val r = dec.decodeUnder(TestKeyboard.exact(w), ctx, Posture())
            println("== $w (prev=$ctx) precMean=%.2f precMin=%.2f".format(r.precisionMean, r.precisionMin))
            println("   literal: ${r.literal.word} sp=%.2f lm=%.2f tot=%.2f inLex=${r.literal.inLexicon}".format(r.literal.spatial, r.literal.lm, r.literal.total))
            for (c in r.candidates) println("   ${c.word} sp=%.2f ed=${c.edits}/%.1f lm=%.2f tot=%.2f".format(c.spatial, c.editPenalty, c.lm, c.total))
            println("   ghost: " + dec.ghost(TestKeyboard.exact(w), ctx))
        }
        println("completions keyboa: " + lex.completions(lex.find("keyboa"), 6))
    }
}
