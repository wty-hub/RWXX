package com.corrodinggames.rts.gameFramework.mission

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.GameInputStream
import java.io.*
import kotlin.test.*

class MissionSavedTriggerRestoreTest {
    private val marker = 0x7193b5d7

    private data class Saved(val id: String, val active: Boolean, val time: Int, val count: Int, val completed: Boolean, val warmup: Int)

    private class DormantTrigger : MapTrigger() {
        override fun d() = false
    }

    private fun mission(ids: List<String?>): MissionEngine = MissionEngine().apply {
        showIntro = false
        r = 901; u = 902; v = 903; w = 904; x = 905
        z = 901f; A = 902f; B = 903f
        isDefeatWave = false
        y = 907
        survivalWavesClassic = false
        ids.forEachIndexed { index, id ->
            J.add(DormantTrigger().apply {
                uniqueId = id
                rawId = id
                isActive = index % 2 == 0
                activationTime = 800 + index
                l = 900 + index
                hasCompleted = index % 2 == 1
                n = 700 + index
                i = index % 2 == 0
                u = index % 3 == 0
                q = -1
                repeatDelay = -1
            })
        }
    }

    // Independently produce the old on-wire layout with distinct field values.
    private fun saved(version: Int, records: List<Saved>): ByteArray {
        val bytes = ByteArrayOutputStream()
        val data = DataOutputStream(bytes)
        data.writeBoolean(true)
        data.writeInt(-37); data.writeInt(19); data.writeInt(-41); data.writeInt(53); data.writeInt(-67)
        data.writeFloat(1.25f); data.writeFloat(-9.5f); data.writeFloat(31.75f)
        data.writeBoolean(true)
        data.writeInt(version)
        if (version >= 1) {
            data.writeInt(records.size)
            records.forEach {
                data.writeUTF(it.id)
                data.writeBoolean(it.active)
                if (version >= 2) { data.writeInt(it.time); data.writeInt(it.count) }
                if (version >= 3) data.writeBoolean(it.completed)
                if (version >= 4) data.writeInt(it.warmup)
            }
        }
        if (version >= 5) data.writeInt(971)
        if (version >= 6) data.writeBoolean(false)
        if (version >= 7) data.writeBoolean(false)
        if (version >= 8) data.writeBoolean(false)
        data.writeInt(marker)
        return bytes.toByteArray()
    }

    // Old restore reader with linear equalsIgnoreCase lookup, no indexed lookup helpers.
    private fun oldRestore(input: DataInputStream, mission: MissionEngine): List<String> {
        mission.showIntro = input.readBoolean()
        mission.r = input.readInt(); mission.u = input.readInt(); mission.v = input.readInt(); mission.w = input.readInt(); mission.x = input.readInt()
        mission.z = input.readFloat(); mission.A = input.readFloat(); mission.B = input.readFloat()
        mission.isDefeatWave = input.readBoolean()
        val version = input.readInt()
        val missing = ArrayList<String>()
        if (version >= 1) repeat(input.readInt()) {
            val id = input.readUTF()
            val active = input.readBoolean()
            var time = 0
            var count = 0
            var completed = false
            var warmup = 0
            if (version >= 2) { time = input.readInt(); count = input.readInt() }
            if (version >= 3) completed = input.readBoolean()
            if (version >= 4) warmup = input.readInt()
            val query = id.trim { it <= ' ' }
            var found: MapTrigger? = null
            for (trigger in mission.J) {
                if (trigger.uniqueId!!.equals(query, ignoreCase = true)) {
                    found = trigger
                    break
                }
            }
            if (found == null) missing.add(id)
            else {
                found.isActive = active
                found.activationTime = time
                found.l = count
                found.hasCompleted = completed
                found.n = warmup
            }
        }
        if (version >= 5) mission.y = input.readInt()
        mission.survivalWavesClassic = if (version >= 6) input.readBoolean() else true
        if (version >= 7) assertFalse(input.readBoolean(), "Fixture does not include area-control state")
        if (version >= 8) {
            assertFalse(input.readBoolean(), "Fixture does not include portal state")
            mission.mapPortalMode = null
        }
        return missing
    }

    private fun fields(mission: MissionEngine): List<Any> = listOf(
        mission.showIntro, mission.r, mission.u, mission.v, mission.w, mission.x, mission.z, mission.A, mission.B,
        mission.isDefeatWave, mission.y, mission.survivalWavesClassic
    ) + mission.J.map { trigger -> listOf(trigger.isActive, trigger.activationTime, trigger.l, trigger.hasCompleted, trigger.n, trigger.i, trigger.u) }

    private fun captureRestore(input: DataInputStream, mission: MissionEngine): String {
        val previousPlatform = GameEngine.isNonAndroidVersion
        val previousColors = GameEngine.isLogColorEnabled
        val previousError = System.err
        val output = ByteArrayOutputStream()
        GameEngine.isNonAndroidVersion = true
        GameEngine.isLogColorEnabled = false
        System.setErr(PrintStream(output))
        try { mission.a(GameInputStream(input)) }
        finally {
            System.setErr(previousError)
            GameEngine.isNonAndroidVersion = previousPlatform
            GameEngine.isLogColorEnabled = previousColors
        }
        return output.toString()
    }

    @Test fun `restore matches old linear lookup for Unicode duplicate IDs trimmed queries and every state version`() {
        val ids = listOf("Foo", "foo", "Foo_1", "FOO_1", "İ", "ı", "Σ", "ς", "K", "\uD801\uDC00", " padded ", "")
        val queries = listOf("foo", "foo_1", " I ", "ı", "σ", "k", "\uD801\uDC28", "\tFOO_1 \r", " padded ", " Missing ", "")
        val records = queries.mapIndexed { index, id -> Saved(id, index % 2 == 1, -101 - index * 13, 201 + index * 17, index % 3 == 0, -301 - index * 19) }
        for (version in 0..8) {
            val payload = saved(version, records)
            val expected = mission(ids)
            val actual = mission(ids)
            val originalInstances = actual.J.toList()
            val oldInput = DataInputStream(payload.inputStream())
            val newInput = DataInputStream(payload.inputStream())
            val missing = oldRestore(oldInput, expected)
            val logs = captureRestore(newInput, actual)
            assertEquals(fields(expected), fields(actual), "version=$version")
            assertEquals(marker, oldInput.readInt())
            assertEquals(marker, newInput.readInt())
            originalInstances.indices.forEach { assertSame(originalInstances[it], actual.J[it], "Restore must not replace or deduplicate triggers") }
            val expectedLogs = missing.joinToString("") { "RustedWarfare: MissionEngine:readIn: Could not find saved trigger:$it for de/activation${System.lineSeparator()}" }
            assertEquals(expectedLogs, logs, "version=$version")
            if (version >= 1) {
                assertEquals(901, actual.J[1].l, "The second case-insensitive Foo must remain untouched")
                assertEquals(903, actual.J[3].l, "A colliding explicit Foo_1 must remain untouched")
            }
        }
    }

    @Test fun `restoring does not retain a trigger index across replacement or renamed instances`() {
        val actual = mission(listOf("before", "second"))
        val expected = mission(listOf("before", "second"))
        val first = saved(6, listOf(Saved("before", true, 17, 23, true, 31)))
        oldRestore(DataInputStream(first.inputStream()), expected)
        captureRestore(DataInputStream(first.inputStream()), actual)
        val previous = actual.J[0]
        expected.J[0] = mission(listOf("replacement")).J[0]
        actual.J[0] = mission(listOf("replacement")).J[0]
        expected.J[1].uniqueId = "renamed"
        actual.J[1].uniqueId = "renamed"
        val second = saved(6, listOf(Saved("replacement", false, 37, 41, false, 43), Saved("renamed", true, 47, 53, true, 59), Saved("before", false, 61, 67, false, 71)))
        val missing = oldRestore(DataInputStream(second.inputStream()), expected)
        val logs = captureRestore(DataInputStream(second.inputStream()), actual)
        assertEquals(fields(expected), fields(actual))
        assertEquals(listOf("before"), missing)
        assertTrue(logs.contains("saved trigger:before for de/activation"))
        assertEquals(23, previous.l, "Previously restored instance is no longer targeted")
    }

    @Test fun `pending state and subsequent mission reset scheduling remain identical`() {
        fun scheduled(): MissionEngine = mission(listOf("active", "pending")).apply {
            J[0].q = 50
            J[0].u = false
            J[1].u = true
            J[1].e.a(DormantTrigger().apply { isActive = true })
        }
        val expected = scheduled()
        val actual = scheduled()
        val payload = saved(6, listOf(Saved("active", true, 100, 17, false, 119), Saved("pending", false, -1, 29, false, -1)))
        oldRestore(DataInputStream(payload.inputStream()), expected)
        captureRestore(DataInputStream(payload.inputStream()), actual)
        assertTrue(actual.J[1].u, "Pending activation is not a serialized field and must retain its rebuilt initial state")
        assertEquals(fields(expected), fields(actual))
        for (time in listOf(100, 149, 150, 151)) {
            expected.a(time)
            actual.a(time)
            assertEquals(fields(expected), fields(actual), "continued update time=$time")
            assertEquals(time < 150, actual.J[0].isActive)
            assertFalse(actual.J[1].u)
            assertTrue(actual.J[1].hasCompleted)
        }
    }

    @Test fun `empty saved list and malformed IDs retain original failure order and restored prefix`() {
        val empty = saved(6, emptyList())
        val noRows = mission(listOf("known", null))
        captureRestore(DataInputStream(empty.inputStream()), noRows)
        assertEquals(900, noRows.J[0].l)
        val payload = saved(6, listOf(Saved("known", true, 37, 41, true, 43), Saved("missing", false, 47, 53, false, 59)))
        val expected = mission(listOf("known", null))
        val actual = mission(listOf("known", null))
        val oldInput = DataInputStream(payload.inputStream())
        val newInput = DataInputStream(payload.inputStream())
        assertFailsWith<NullPointerException> { oldRestore(oldInput, expected) }
        assertFailsWith<NullPointerException> { captureRestore(newInput, actual) }
        assertEquals(fields(expected), fields(actual))
        assertEquals(41, actual.J[0].l)
        assertEquals(oldInput.available(), newInput.available())
    }

    @Test fun `truncated record does not apply incomplete fields or consume later state`() {
        val complete = saved(6, listOf(Saved("first", true, 37, 41, true, 43), Saved("missing", false, 47, 53, false, 59)))
        // Remove marker, final mission fields, and the end of the second trigger's n field.
        val payload = complete.copyOf(complete.size - 4 - 1 - 4 - 2)
        val expected = mission(listOf("first", "second"))
        val actual = mission(listOf("first", "second"))
        val oldInput = DataInputStream(payload.inputStream())
        val newInput = DataInputStream(payload.inputStream())
        assertFailsWith<EOFException> { oldRestore(oldInput, expected) }
        assertFailsWith<EOFException> { captureRestore(newInput, actual) }
        assertEquals(fields(expected), fields(actual))
        assertEquals(41, actual.J[0].l)
        assertEquals(901, actual.J[1].l)
        assertEquals(oldInput.available(), newInput.available())
    }
}
