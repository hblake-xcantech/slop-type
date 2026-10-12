package dev.sloptype.app.remote

import dev.sloptype.engine.ClassifyRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Tier2ProtocolTest {
    private val req = ClassifyRequest(1, "I think", "teh", listOf("the", "tech"))

    @Test fun optionsAreKeepCandidatesNovel() {
        assertEquals(listOf("keep", "the", "tech", "novel"), Tier2Protocol.options(req))
        // reserved labels and the typed token itself are never offered as candidates
        val r2 = ClassifyRequest(2, "", "keep", listOf("keep", "novel", "kept"))
        assertEquals(listOf("keep", "kept", "novel"), Tier2Protocol.options(r2))
    }

    @Test fun stateTextIsShortAndSingleLine() {
        val longCtx = (1..80).joinToString(" ") { "w$it" } + "\nline2\n"
        val s = Tier2Protocol.stateText(ClassifyRequest(1, longCtx, "teh", listOf("the")))
        assertFalse(s.contains('\n'))
        assertFalse(s.contains("w50 "))
        assertTrue(s.contains("w80"))
        assertTrue(s.contains("\"teh\""))
        assertTrue(s.contains("\"the\""))
    }

    @Test fun jevBodyShape() {
        val o = Json.parseToJsonElement(Tier2Protocol.jevBody(req, "jev-1.13.0")).jsonObject
        assertEquals("jev-1.13.0", o["model"]!!.jsonPrimitive.content)
        val q = o["questions"]!!.jsonObject["meant"]!!.jsonObject
        assertEquals("choice", q["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("keep", "the", "tech", "novel"), q["criteria"]!!.jsonObject.keys.toList())
    }

    @Test fun decisionsBodyShape() {
        val o = Json.parseToJsonElement(Tier2Protocol.decisionsBody(req, "gpt-6-luna")).jsonObject
        val q = o["questions"]!!.jsonArray[0].jsonObject
        assertEquals("choice", q["type"]!!.jsonPrimitive.content)
        assertEquals("meant", q["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("keep", "the", "tech", "novel"), q["choices"]!!.jsonArray.map { it.jsonObject["value"]!!.jsonPrimitive.content })
    }

    @Test fun responsesBodyShape() {
        val o = Json.parseToJsonElement(Tier2Protocol.responsesBody(req, "gpt-6-luna", "fast")).jsonObject
        assertEquals("none", o["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("false", o["store"]!!.jsonPrimitive.content)
        assertEquals("fast", o["service_tier"]!!.jsonPrimitive.content)
        val fmt = o["text"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", fmt["type"]!!.jsonPrimitive.content)
        assertEquals("true", fmt["strict"]!!.jsonPrimitive.content)
        val enum = fmt["schema"]!!.jsonObject["properties"]!!.jsonObject["choice"]!!.jsonObject["enum"]!!.jsonArray
        assertEquals(4, enum.size)
        assertNull(Json.parseToJsonElement(Tier2Protocol.responsesBody(req, "gpt-6-luna", null)).jsonObject["service_tier"])
    }

    @Test fun parseJev() {
        val body = """{"model":"jev-1.13.0","answers":{"meant":{"type":"choice","choice":"the","probabilities":{"keep":0.05,"the":0.9,"tech":0.05,"novel":0.0},"confidence":0.81}},"usage":{"input_tokens":120,"output_tokens":4}}"""
        val p = assertNotNull(Tier2Protocol.parseJev(body))
        assertEquals("the", p.choice); assertEquals(0.81f, p.confidence, 1e-4f); assertEquals(120L, p.inputTokens)
        assertNull(Tier2Protocol.parseJev("not json"))
    }

    @Test fun parseDecisions() {
        val body = """{"answers":[{"type":"choice","name":"meant","choice":"the","probabilities":[{"value":"the","probability":0.95}],"confidence":0.93}]}"""
        val p = assertNotNull(Tier2Protocol.parseDecisions(body))
        assertEquals("the", p.choice); assertEquals(0.93f, p.confidence, 1e-4f); assertNull(p.inputTokens)
        assertNull(Tier2Protocol.parseDecisions("""{"answers":[{"type":"refusal","name":"meant"}]}"""))
    }

    @Test fun parseResponses() {
        val body = """{"id":"resp_1","model":"gpt-6-luna","output":[{"type":"reasoning","summary":[]},{"type":"message","content":[{"type":"output_text","text":"{\"choice\":\"the\"}"}]}],"usage":{"input_tokens":100,"output_tokens":5}}"""
        val p = assertNotNull(Tier2Protocol.parseResponses(body))
        assertEquals("the", p.choice); assertEquals(100L, p.inputTokens); assertEquals(5L, p.outputTokens)
        assertNull(Tier2Protocol.parseResponses("""{"error":{"message":"bad"}}"""))
    }

    @Test fun normalise() {
        assertEquals("keep", Tier2Protocol.normaliseChoice("Keep", req))
        assertEquals("novel", Tier2Protocol.normaliseChoice("novel", req))
        assertEquals("the", Tier2Protocol.normaliseChoice("\"The\"", req))
        assertEquals("keep", Tier2Protocol.normaliseChoice("teh", req))
        assertNull(Tier2Protocol.normaliseChoice("banana", req))
    }

    @Test fun pricing() {
        assertEquals(0.042e-6 * 150, Pricing.cost("jev-1.13.0", 150, 3), 1e-12)
        assertEquals((0.10 * 150 + 0.50 * 3) / 1e6, Pricing.cost("gpt-6-luna", 150, 3), 1e-12)
        assertEquals(2 * (0.10 * 150 + 0.50 * 3) / 1e6, Pricing.cost("GPT-6-LUNA", 150, 3, "fast"), 1e-12)
        assertEquals(Pricing.fallback, Pricing.price("unknown-model"))
        assertEquals(3L, Pricing.estimateTokens("123456789"))
    }
}
