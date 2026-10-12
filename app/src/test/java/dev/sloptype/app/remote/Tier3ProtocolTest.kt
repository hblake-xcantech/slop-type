package dev.sloptype.app.remote

import dev.sloptype.engine.RewriteRequest
import dev.sloptype.engine.WordAlt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Tier3ProtocolTest {
    @Test fun sseParsing() {
        val sse = "event: response.created\ndata: {\"type\":\"response.created\"}\n\n: keepalive\n\nevent: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"[\\\"I\\\",\"}\n\nevent: response.output_text.delta\ndata: {\"delta\":\"\\\"think\\\"]\"}\n\nevent: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"model\":\"gpt-6-luna\",\"usage\":{\"input_tokens\":50,\"output_tokens\":6}}}\n\n"
        val evs = Http.parseSse(sse)
        assertEquals(4, evs.size)
        val s = Tier3Protocol.foldStream(sse)
        assertTrue(s.completed)
        assertEquals("[\"I\",\"think\"]", s.text)
        assertEquals(50L, s.inputTokens); assertEquals("gpt-6-luna", s.model)
    }

    @Test fun streamWithoutCompletedFails() {
        val s = Tier3Protocol.foldStream("event: response.output_text.delta\ndata: {\"delta\":\"x\"}\n\n")
        assertFalse(s.completed)
        val f = Tier3Protocol.foldStream("event: response.failed\ndata: {\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n")
        assertFalse(f.completed); assertEquals("subscription_sharing_usage_limit_exceeded", f.errorCode)
    }

    @Test fun rewriteParseStrict() {
        val words = listOf(WordAlt("I", emptyList(), true), WordAlt("thnik", listOf("think"), false), WordAlt("Blake", emptyList(), true))
        assertEquals(listOf("I", "think", "Blake"), Tier3Protocol.parseRewrite("```json\n[\"I\", \"think\", \"Blake\"]\n```", words))
        // locked words are forced back
        assertEquals(listOf("I", "think", "Blake"), Tier3Protocol.parseRewrite("[\"I\",\"think\",\"Black\"]", words))
        assertNull(Tier3Protocol.parseRewrite("[\"I\",\"think\"]", words))
        assertNull(Tier3Protocol.parseRewrite("[\"I\",\"think a lot\",\"Blake\"]", words))
        assertNull(Tier3Protocol.parseRewrite("I think Blake", words))
    }

    @Test fun rewriteInputListsWords() {
        val req = RewriteRequest(1, listOf(WordAlt("I", emptyList(), true), WordAlt("thnik", listOf("think", "thank"), false)), "earlier\ntext")
        val s = Tier3Protocol.rewriteInput(req)
        assertTrue(s.contains("1. \"I\" [locked]"))
        assertTrue(s.contains("2. \"thnik\" alternatives: \"think\", \"thank\""))
        assertTrue(s.contains("\"earlier text\""))
    }

    @Test fun yesNo() {
        assertEquals(true, Tier3Protocol.parseYesNo("Yes."))
        assertEquals(false, Tier3Protocol.parseYesNo(" no, it is not"))
        assertNull(Tier3Protocol.parseYesNo("maybe"))
    }

    @Test fun modelsAndDefault() {
        val m = Tier3Protocol.parseModels("""{"models":[{"slug":"gpt-6.1-sol","display_name":"GPT-6.1 Sol","visibility":"list"},{"slug":"hidden","visibility":"hidden"},{"slug":"gpt-6-luna","display_name":"Luna","visibility":"list"}]}""")
        assertEquals(listOf("gpt-6.1-sol", "gpt-6-luna"), m.map { it.slug })
        assertEquals("gpt-6-luna", Tier3Protocol.pickDefaultModel(m))
        assertEquals("gpt-5.4-mini", Tier3Protocol.pickDefaultModel(Tier3Protocol.parseModels("""{"data":[{"id":"gpt-5.4"},{"id":"gpt-5.4-mini"}]}""")))
        assertEquals("gpt-6.1-sol", Tier3Protocol.pickDefaultModel(m.take(1)))
    }

    @Test fun responsesBodyHasRequiredFlags() {
        val o = Json.parseToJsonElement(Tier3Protocol.responsesBody("gpt-6-luna", "sys", "in", "low")).jsonObject
        assertEquals("false", o["store"]!!.jsonPrimitive.content)
        assertEquals("true", o["stream"]!!.jsonPrimitive.content)
        assertNull(o["max_output_tokens"]); assertNull(o["temperature"])
        assertNull(Json.parseToJsonElement(Tier3Protocol.responsesBody("m", "s", "i", null)).jsonObject["reasoning"])
    }

    @Test fun routerExtras() {
        val x = RemoteWiring.parseRouterExtras("""{"tier2Provider":"JEV","tier2Model":"jev-latest","preference":{"pause":3},"tier3Model":"gpt-6-luna"}""")
        assertEquals("jev", x.tier2Provider); assertEquals("jev-latest", x.tier2Model); assertEquals("gpt-6-luna", x.tier3Model); assertEquals("low", x.tier3ReasoningEffort)
        val y = RemoteWiring.parseRouterExtras("""{"tier2":{"provider":"openai-responses","serviceTier":"fast"}}""")
        assertEquals("openai-responses", y.tier2Provider); assertEquals("fast", y.tier2ServiceTier)
        assertEquals("none", RemoteWiring.parseRouterExtras("{}").tier2Provider)
    }
}
