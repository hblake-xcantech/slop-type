package dev.sloptype.app.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DaemonUtilTest {
    @Test
    fun semver_parse() {
        assertEquals(listOf(1, 2, 3), Semver.parse("v1.2.3"))
        assertEquals(listOf(1, 2, 3), Semver.parse("1.2.3-debug"))
        assertEquals(listOf(1, 2, 3), Semver.parse("1.2.3+42"))
        assertEquals(listOf(2, 0), Semver.parse("2.0"))
        assertNull(Semver.parse("nightly"))
        assertNull(Semver.parse("v1.x"))
        assertNull(Semver.parse(""))
    }

    @Test
    fun semver_compare_and_isNewer() {
        assertTrue(Semver.compare("v1.2.3", "1.2.2") > 0)
        assertTrue(Semver.compare("v1.10.0", "1.9.9") > 0)
        assertEquals(0, Semver.compare("v1.2", "1.2.0"))
        assertEquals(0, Semver.compare("1.2.3-debug", "v1.2.3"))
        assertTrue(Semver.isNewer("v1.2.4", "1.2.3"))
        assertFalse(Semver.isNewer("v1.2.3", "1.2.3"))
        assertFalse(Semver.isNewer("v1.2.2", "1.2.3"))
        assertFalse(Semver.isNewer("garbage", "1.2.3"))
        // an unparseable installed version never blocks an update
        assertTrue(Semver.isNewer("v0.0.1", "unknown"))
    }

    @Test
    fun dateForLogName_usesSidPrefix() {
        // 1791763200000 = 2026-10-12T00:00:00Z
        assertEquals("2026-10-12", DaemonUtil.dateForLogName("1791763200000-ab12.jsonl.age", 0))
        assertEquals("2026-10-11", DaemonUtil.dateForLogName("1791763199999-ab12.jsonl", 0))
        // no usable prefix: fallback timestamp
        assertEquals("2026-10-12", DaemonUtil.dateForLogName("current.jsonl", 1791763200000))
        assertEquals("2026-10-12", DaemonUtil.dateForLogName("12-abc.jsonl", 1791763200000))
    }

    @Test
    fun isoUtc() {
        assertEquals("2026-10-12T00:00:00Z", DaemonUtil.isoUtc(1791763200000))
    }

    @Test
    fun writeAtomic_replacesAndLeavesNoTemp() {
        val dir = File.createTempFile("atomic", "").apply { delete(); mkdirs() }
        val f = File(dir, "x.json")
        DaemonUtil.writeAtomic(f, "one")
        DaemonUtil.writeAtomic(f, "two")
        assertEquals("two", f.readText())
        assertEquals(listOf("x.json"), dir.list()!!.toList())
        dir.deleteRecursively()
    }

    @Test
    fun sha256() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", DaemonUtil.sha256Hex(ByteArray(0)))
    }

    @Test
    fun parseRecipients() {
        val text = "# autopilot\nage1lvhv5hyawuvfxw0ejglv0y8mkpqmua0xuax5z6ad4pl5tnqp24ascyrgdg\n\nage1abc # me\nnot-a-key\nage1abc\n"
        assertEquals(listOf("age1lvhv5hyawuvfxw0ejglv0y8mkpqmua0xuax5z6ad4pl5tnqp24ascyrgdg", "age1abc"), DaemonUtil.parseRecipients(text))
    }

    @Test
    fun rootQuote() {
        assertEquals("'/a b/c.apk'", Root.quote("/a b/c.apk"))
        assertEquals("'it'\\''s'", Root.quote("it's"))
    }
}
