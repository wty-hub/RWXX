package com.corrodinggames.rts.game

import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.DebugOutputStream
import com.corrodinggames.rts.gameFramework.network.GameInputStream
import com.corrodinggames.rts.gameFramework.network.GameOutputStream
import java.io.*
import java.util.zip.GZIPOutputStream
import kotlin.test.*

class PlayerTeamFogStreamTest {
    private val marker = 0x7193b5d7
    private val instanceField = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previousEngine: Any? = null
    private var previousPlatform = false

    @BeforeTest fun setUpEngine() {
        previousEngine = instanceField.get(null)
        previousPlatform = GameEngine.isNonAndroidVersion
        instanceField.set(null, null)
        GameEngine.isNonAndroidVersion = true
        try {
            GameLogic().tileMap = TileMap()
        } catch (failure: Throwable) {
            restoreEngine()
            throw failure
        }
    }

    @AfterTest fun restoreEngine() {
        instanceField.set(null, previousEngine)
        GameEngine.isNonAndroidVersion = previousPlatform
    }

    private fun team(width: Int, height: Int, columnHeight: Int = height) = GameTeam(0, false).apply {
        fogOfWarWidth = width
        fogOfWarHeight = height
        fogOfWarData = Array(width) { x -> ByteArray(columnHeight) { y -> (x * 137 + y * 53 - 128).toByte() } }
    }

    // The old byte writer is the oracle; it never uses the new range API.
    private fun oldFog(output: DataOutputStream, team: PlayerTeam) {
        val grid = team.fogOfWarData
        output.writeBoolean(grid != null)
        if (grid != null) {
            output.writeInt(team.fogOfWarWidth)
            output.writeInt(team.fogOfWarHeight)
            for (x in 0 until team.fogOfWarWidth) for (y in 0 until team.fogOfWarHeight) output.writeByte(grid[x][y].toInt())
        }
    }

    private fun oldFog(output: GameOutputStream, team: PlayerTeam) {
        output.writeDebugMessage("-- Saving fog --")
        val grid = team.fogOfWarData
        output.writeBoolean(grid != null)
        if (grid != null) {
            output.writeInt(team.fogOfWarWidth)
            output.writeInt(team.fogOfWarHeight)
            for (x in 0 until team.fogOfWarWidth) for (y in 0 until team.fogOfWarHeight) output.writeByte(grid[x][y].toInt())
        }
        output.writeDebugMessage("--End fog--")
    }

    private fun oldRead(input: DataInputStream, team: PlayerTeam, width: Int, height: Int) {
        if (!input.readBoolean()) {
            team.fogOfWarData = null
            return
        }
        team.fogOfWarWidth = input.readInt()
        team.fogOfWarHeight = input.readInt()
        team.fogOfWarData = Array(width) { ByteArray(height) }
        for (x in 0 until team.fogOfWarWidth) for (y in 0 until team.fogOfWarHeight) team.fogOfWarData[x][y] = input.readByte()
    }

    private fun bytes(write: (DataOutputStream) -> kotlin.Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        write(DataOutputStream(buffer))
        return buffer.toByteArray()
    }

    private fun compressed(write: (DataOutputStream) -> kotlin.Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        val gzip = GZIPOutputStream(buffer)
        val buffered = BufferedOutputStream(gzip)
        val data = DataOutputStream(buffered)
        write(data)
        data.flush()
        buffered.flush()
        gzip.finish()
        val result = buffer.toByteArray()
        data.close()
        return result
    }

    private fun oldWire(team: PlayerTeam, nested: Boolean): ByteArray = bytes { root ->
        root.writeByte(98)
        if (nested) {
            val outer = compressed { data ->
                data.writeInt(0x1117)
                oldFog(data, team)
                val inner = bytes { innerData -> innerData.writeByte(3); oldFog(innerData, team) }
                data.writeUTF("fog-inner")
                data.writeInt(inner.size)
                data.write(inner)
                data.writeInt(marker)
            }
            root.writeUTF("saveCompression")
            root.writeInt(outer.size)
            root.write(outer)
        } else oldFog(root, team)
        root.writeInt(marker)
    }

    private fun newWire(team: PlayerTeam, nested: Boolean): ByteArray {
        val output = GameOutputStream()
        output.writeByte(98)
        if (nested) {
            output.beginBlockInternal("saveCompression", true)
            output.writeInt(0x1117)
            team.writeFogState(output)
            output.startBlock("fog-inner")
            output.writeByte(3)
            team.writeFogState(output)
            output.endBlock("fog-inner")
            output.writeInt(marker)
            output.endBlock("saveCompression")
        } else team.writeFogState(output)
        output.writeInt(marker)
        return output.toByteArray()
    }

    private fun withMap(width: Int, height: Int, block: () -> kotlin.Unit) {
        val engine = GameEngine.getInstance()
        val previousMap = engine.tileMap
        engine.tileMap = TileMap().apply { tileCountX = width; tileCountY = height }
        try {
            block()
        } finally {
            engine.tileMap = previousMap
        }
    }

    private fun assertGrid(expected: PlayerTeam, actual: PlayerTeam) {
        assertEquals(expected.fogOfWarWidth, actual.fogOfWarWidth)
        assertEquals(expected.fogOfWarHeight, actual.fogOfWarHeight)
        val expectedGrid = expected.fogOfWarData
        if (expectedGrid == null) assertNull(actual.fogOfWarData)
        else {
            assertEquals(expectedGrid.size, actual.fogOfWarData.size)
            expectedGrid.indices.forEach { assertContentEquals(expectedGrid[it], actual.fogOfWarData[it], "column=$it") }
        }
    }

    private class Fragmented(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        private var reads = 0
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, minOf(length, 1 + reads++ % 3))
    }

    @Test fun `fog wire bytes match old byte oracle including compressed nested blocks and tall columns`() {
        for (height in listOf(1, 3, 399, 8191, 8192, 17003)) {
            val source = team(3, height, height + 7)
            for (nested in listOf(false, true)) assertContentEquals(oldWire(source, nested), newWire(source, nested), "height=$height nested=$nested")
        }
        val absent = team(2, 3).apply { fogOfWarData = null }
        for (nested in listOf(false, true)) assertContentEquals(oldWire(absent, nested), newWire(absent, nested))
    }

    @Test fun `debug fog retains every signed byte line and nested block output`() {
        val source = team(4, 17, 23)
        fun debug(old: Boolean): ByteArray {
            val output = DebugOutputStream()
            output.writeInt(53)
            output.beginBlockInternal("saveCompression", true)
            if (old) oldFog(output, source) else source.writeFogState(output)
            output.startBlock("fog-inner")
            if (old) oldFog(output, source) else source.writeFogState(output)
            output.endBlock("fog-inner")
            output.endBlock("saveCompression")
            output.writeInt(marker)
            return output.toByteArray()
        }
        assertContentEquals(debug(true), debug(false))
        val values = byteArrayOf(51, -128, -1, 0, 1, 127, 73)
        val slice = DebugOutputStream().apply { writeBytesRaw(values, 1, 5) }.bufferAsString
        val lines = listOf(-128, -1, 0, 1, 127).joinToString(System.lineSeparator(), postfix = System.lineSeparator())
        assertEquals(lines, slice)
    }

    @Test fun `range IO respects signed bytes offsets zero length and fragmented input`() {
        val values = byteArrayOf(51, -128, -1, 0, 1, 127, 73)
        val output = GameOutputStream()
        output.writeBytesRaw(values, 1, 5)
        output.writeBytesRaw(values, values.size, 0)
        output.writeInt(marker)
        val expected = bytes { data -> for (index in 1..5) data.writeByte(values[index].toInt()); data.writeInt(marker) }
        assertContentEquals(expected, output.toByteArray())
        val input = GameInputStream(DataInputStream(Fragmented(expected)))
        val target = ByteArray(9) { 85 }
        input.readBytesRaw(target, 2, 5)
        input.readBytesRaw(target, target.size, 0)
        assertContentEquals(byteArrayOf(85, 85, -128, -1, 0, 1, 127, 85, 85), target)
        assertEquals(marker, input.readInt())
        val truncated = GameInputStream(DataInputStream(Fragmented(values.copyOfRange(1, 4))))
        val partial = ByteArray(7) { 85 }
        assertFailsWith<EOFException> { truncated.readBytesRaw(partial, 2, 5) }
        assertContentEquals(byteArrayOf(85, 85, -128, -1, 0, 85, 85), partial)
    }

    @Test fun `fog reads match the old oracle through compressed nesting and resume at following markers`() = withMap(3, 399) {
        val source = team(3, 399, 407)
        val input = GameInputStream(DataInputStream(Fragmented(oldWire(source, true))))
        assertEquals(98, input.readByte().toInt())
        input.a("saveCompression", true)
        assertEquals(0x1117, input.readInt())
        val first = team(1, 1)
        first.readFogState(input)
        assertGrid(team(3, 399), first)
        input.startBlockNamed("fog-inner")
        assertEquals(3, input.readByte().toInt())
        val second = team(1, 1)
        second.readFogState(input)
        assertGrid(first, second)
        input.d("fog-inner")
        assertEquals(marker, input.readInt())
        input.d("saveCompression")
        assertEquals(marker, input.readInt())
    }

    @Test fun `fog read EOF preserves the same partial grid and input position`() = withMap(3, 17) {
        val source = team(3, 17)
        val payload = bytes { oldFog(it, source) }.dropLast(2).toByteArray()
        val oldInput = DataInputStream(Fragmented(payload))
        val newInput = DataInputStream(Fragmented(payload))
        val expected = team(1, 1)
        val actual = team(1, 1)
        assertFailsWith<EOFException> { oldRead(oldInput, expected, 3, 17) }
        assertFailsWith<EOFException> { actual.readFogState(GameInputStream(newInput)) }
        assertGrid(expected, actual)
        assertEquals(oldInput.available(), newInput.available())
    }

    @Test fun `larger map retains zero tails and smaller mismatch retains old failure consumption`() {
        val source = team(2, 3, 7)
        val payload = bytes { oldFog(it, source); it.writeInt(marker) }
        withMap(4, 7) {
            val expected = team(1, 1)
            val actual = team(1, 1)
            val oldInput = DataInputStream(Fragmented(payload))
            val newInput = GameInputStream(DataInputStream(Fragmented(payload)))
            oldRead(oldInput, expected, 4, 7)
            actual.readFogState(newInput)
            assertGrid(expected, actual)
            assertEquals(marker, oldInput.readInt())
            assertEquals(marker, newInput.readInt())
        }
        withMap(1, 1) {
            val expected = team(1, 1)
            val actual = team(1, 1)
            val oldInput = DataInputStream(Fragmented(payload))
            val newInput = DataInputStream(Fragmented(payload))
            assertFailsWith<ArrayIndexOutOfBoundsException> { oldRead(oldInput, expected, 1, 1) }
            assertFailsWith<ArrayIndexOutOfBoundsException> { actual.readFogState(GameInputStream(newInput)) }
            assertGrid(expected, actual)
            assertEquals(oldInput.available(), newInput.available())
        }
    }

    @Test fun `zero height and malformed short columns retain old emitted prefixes`() {
        for (height in listOf(0, -1)) {
            val source = team(1, 0).apply { fogOfWarWidth = 3; fogOfWarHeight = height; fogOfWarData = arrayOfNulls<ByteArray>(1) }
            assertContentEquals(oldWire(source, false), newWire(source, false))
        }
        val source = team(2, 3).apply { fogOfWarData[1] = byteArrayOf(-128, 127) }
        val oldBuffer = ByteArrayOutputStream()
        val actual = GameOutputStream()
        assertFailsWith<ArrayIndexOutOfBoundsException> { oldFog(DataOutputStream(oldBuffer), source) }
        assertFailsWith<ArrayIndexOutOfBoundsException> { source.writeFogState(actual) }
        assertContentEquals(oldBuffer.toByteArray(), actual.toByteArray())
    }
}
