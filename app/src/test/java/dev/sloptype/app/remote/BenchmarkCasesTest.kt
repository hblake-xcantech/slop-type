package dev.sloptype.app.remote

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchmarkCasesTest {
    @Test fun twentyCasesWithExpectations() {
        assertEquals(Benchmark.N, Benchmark.CASES.size)
        assertEquals(setOf("the"), Benchmark.expectedChoice(Benchmark.CASES[0]))
        val blake = Benchmark.CASES.first { it.typed == "Blake" }
        assertTrue(Tier2Protocol.NOVEL in Benchmark.expectedChoice(blake) && Tier2Protocol.KEEP in Benchmark.expectedChoice(blake))
        val running = Benchmark.CASES.first { it.typed == "running" }
        assertEquals(setOf(Tier2Protocol.KEEP), Benchmark.expectedChoice(running))
        val starbucks = Benchmark.CASES.first { it.typed == "Starbucks" }
        assertTrue("starbucks" in Benchmark.expectedChoice(starbucks))
    }
}
