package io.github.rwx.ui.emoji

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmojiSupportTest {
    @Test
    fun `detects emoji and leaves plain text alone`() {
        assertFalse("hello 猫".containsEmoji())
        assertTrue("加🍎群".containsEmoji())
        assertTrue("1️⃣".containsEmoji())
        assertFalse("123".containsEmoji())
    }

    @Test
    fun `splits emoji runs from surrounding text`() {
        val runs = "加🍎群😀送".toTextRuns()
        assertEquals(
            listOf(
                TextRun.Plain("加"),
                TextRun.Emoji("🍎", intArrayOf(0x1F34E)),
                TextRun.Plain("群"),
                TextRun.Emoji("😀", intArrayOf(0x1F600)),
                TextRun.Plain("送"),
            ).map { it.display() },
            runs.map { it.display() },
        )
    }

    @Test
    fun `keeps adjacent emoji as separate clusters`() {
        assertEquals(
            listOf("E:🚀", "E:🚀"),
            "🚀🚀".toTextRuns().map { it.display() },
        )
        assertEquals(
            listOf("E:🇨🇳", "E:🇨🇳"),
            "🇨🇳🇨🇳".toTextRuns().map { it.display() },
        )
        assertEquals(
            listOf("E:🔥", "T: ", "E:👉", "T: ", "E:🎮"),
            "🔥 👉 🎮".toTextRuns().map { it.display() },
        )
    }

    @Test
    fun `keeps flag pairs and zwj families together`() {
        assertEquals(listOf("E:🇺🇳"), "🇺🇳".toTextRuns().map { it.display() })
        assertEquals(listOf("E:🇨🇳"), "🇨🇳".toTextRuns().map { it.display() })
        val family = "👨‍👩‍👧"
        assertEquals(listOf("E:$family"), family.toTextRuns().map { it.display() })
    }

    @Test
    fun `keycap sequences are a single emoji cluster`() {
        assertEquals(listOf("E:1️⃣"), "1️⃣".toTextRuns().map { it.display() })
        assertEquals(
            listOf("T:room ", "E:1️⃣"),
            "room 1️⃣".toTextRuns().map { it.display() },
        )
    }

    @Test
    fun `BMP emoji bases keep their variation selector in the same cluster`() {
        val bases = listOf(0x00A9, 0x00AE, 0x203C, 0x2049, 0x2122, 0x2139,
            0x24C2, 0x25B6, 0x25C0) + (0x2194..0x2199) + (0x21A9..0x21AA) +
            (0x25AA..0x25AB) + (0x25FB..0x25FE) + (0x2934..0x2935)
        for (base in bases) {
            val emoji = "${base.toChar()}\uFE0F"
            assertTrue(emoji.containsEmoji(), "Missing BMP emoji U+${base.toString(16)}")
            assertEquals(listOf("T:前", "E:$emoji", "T:后"),
                "前${emoji}后".toTextRuns().map { it.display() })
        }
        assertEquals(listOf("E:‼️", "E:↔️", "E:Ⓜ️"),
            "‼️↔️Ⓜ️".toTextRuns().map { it.display() })
    }

    @Test
    fun `variation selectors without an emoji base remain ordinary text`() {
        for (text in listOf("\uFE0F", "\uFE0E", "\uFE00", "A\uFE0F", "猫\uFE0F",
            "#\uFE0F", "*\uFE0F", "1\uFE0F", "#*0123456789")) {
            assertFalse(text.containsEmoji())
            assertEquals(listOf("T:$text"), text.toTextRuns().map { it.display() })
        }
        assertEquals(listOf("T:A\uFE0F", "E:‼️", "T:B\uFE0F"),
            "A\uFE0F‼️B\uFE0F".toTextRuns().map { it.display() })
    }

    private fun TextRun.display(): String = when (this) {
        is TextRun.Plain -> "T:$value"
        is TextRun.Emoji -> "E:$value"
    }
}
