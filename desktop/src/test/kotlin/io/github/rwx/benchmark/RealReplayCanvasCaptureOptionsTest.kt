package io.github.rwx.benchmark

import java.lang.reflect.InvocationTargetException
import kotlin.test.*

/** Parser-only checks. Never boot a game, run the opt-in main, or create its output directory. */
class RealReplayCanvasCaptureOptionsTest {
    private fun parse(vararg arguments: String): Any = try {
        RealReplayCanvasCapture::class.java.getDeclaredMethod("parse", Array<String>::class.java)
            .apply { isAccessible = true }.invoke(RealReplayCanvasCapture, arguments)
    } catch (error: InvocationTargetException) { throw error.targetException }

    private fun value(options: Any, name: String): Any? =
        options.javaClass.getDeclaredMethod("get$name").apply { isAccessible = true }.invoke(options)

    private val output = "--output=/tmp/rwx-capture-parser-only-no-created-directory"

    @Test fun `existing replay arguments retain every default`() {
        val options = parse("--replay=old.replay", output)
        assertEquals("old.replay", value(options, "Replay"))
        assertNull(value(options, "Map")); assertNull(value(options, "Width")); assertNull(value(options, "Height"))
        assertEquals("static", value(options, "Mode"))
        assertEquals(30, value(options, "Warmup")); assertEquals(60, value(options, "Frames")); assertEquals(60, value(options, "Fps"))
        assertEquals(0, value(options, "SeekTick")); assertEquals(false, value(options, "ReplayFog")); assertEquals(false, value(options, "VerifyFullFreeze"))
    }

    @Test fun `local map accepts isolated asset name and explicit production-sized viewport`() {
        val options = parse("--map=maps/skirmish/rwx-cpu-10000.tmx", output, "--width=1792", "--height=1008",
            "--mode=pan-zoom", "--warmup-frames=600", "--frames=1200", "--fps=60")
        assertNull(value(options, "Replay"))
        assertEquals("maps/skirmish/rwx-cpu-10000.tmx", value(options, "Map"))
        assertEquals(1792, value(options, "Width")); assertEquals(1008, value(options, "Height"))
        assertEquals(600, value(options, "Warmup")); assertEquals(1200, value(options, "Frames"))
    }

    @Test fun `exactly one replay or map source is required`() {
        assertFailsWith<IllegalArgumentException> { parse(output) }
        assertFailsWith<IllegalArgumentException> { parse("--replay=x.replay", "--map=maps/skirmish/x.tmx", output) }
    }

    @Test fun `replay file isolation remains strict`() {
        for (name in listOf("", "../x.replay", "/tmp/x.replay", "a/b.replay", "a\\b.replay", ".", "..")) {
            assertFailsWith<IllegalArgumentException>(name) { parse("--replay=$name", output) }
        }
    }

    @Test fun `map paths cannot route through mods or outside the asset skirmish directory`() {
        for (path in listOf("x.tmx", "/tmp/x.tmx", "/SD/mods/maps/x.tmx", "maps/skirmish/../x.tmx",
            "maps/skirmish/nested/x.tmx", "maps/skirmish/a\\b.tmx", "maps/skirmish/.tmx", "maps/skirmish/x.zip")) {
            assertFailsWith<IllegalArgumentException>(path) { parse("--map=$path", output) }
        }
    }

    @Test fun `local map cannot silently consume replay fog or seek options`() {
        for (option in listOf("--seek-tick=0", "--seek-tick=60", "--replay-fog=off", "--replay-fog=on")) {
            assertFailsWith<IllegalArgumentException>(option) { parse("--map=maps/skirmish/x.tmx", output, option) }
        }
    }

    @Test fun `viewport pairs ranges duplicate and unknown option guards remain strict`() {
        for (extra in listOf(arrayOf("--width=1792"), arrayOf("--height=1008"), arrayOf("--width=0", "--height=1008"),
            arrayOf("--width=1792", "--height=invalid"), arrayOf("--width=8193", "--height=1008"),
            arrayOf("--frames=60", "--frames=60"), arrayOf("--unexpected=true"))) {
            assertFailsWith<IllegalArgumentException>(extra.joinToString()) { parse("--replay=x.replay", output, *extra) }
        }
    }
}
