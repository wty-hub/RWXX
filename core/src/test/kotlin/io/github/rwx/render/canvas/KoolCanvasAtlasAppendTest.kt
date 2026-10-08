package io.github.rwx.render.canvas

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertSame
import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem

class KoolCanvasAtlasAppendTest {
    @Test fun `cold atlas payload stays owned until retirement then detaches from retained texture`() {
        if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm())
        val id = KoolCanvasTextureId("cold-atlas-owner")
        val image = KoolCanvasArgbImage(2, 2, IntArray(4) { 0xff123456.toInt() })
        val store = object : KoolCanvasTextureStore by KoolCanvasCpuTextureStore() {
            override fun staticArgbImage(id: KoolCanvasTextureId) = image
        }
        val atlas = KoolCanvasSpriteAtlas(store, pageSize = 16, incrementalUploads = false)
        val command = KoolCanvasCommand.DrawTexture(KoolCanvasTextureRef(id, 2, 2),
            KoolCanvasRect(0f, 0f, 2f, 2f), KoolCanvasRect(0f, 0f, 2f, 2f),
            KoolCanvasPaint.Default, KoolCanvasState.Default)
        val pending = mutableListOf<() -> Unit>()
        KoolCanvasGpuRetirement.install { pending += it }
        try {
            val texture = atlas.texture(assertNotNull(atlas.slot(command)).page, KoolCanvasTextureFilter.Linear)
            val payload = assertNotNull(texture.uploadData)
            atlas.clear()
            assertEquals(1, pending.size)
            assertSame(payload, texture.uploadData, "In-flight upload memory cannot be detached early")
            assertTrue(!texture.isReleased)
            pending.single().invoke()
            assertNull(texture.uploadData, "Retained mesh/material references must not keep a retired page payload")
            assertTrue(texture.isReleased)
        } finally { KoolCanvasGpuRetirement.install(null) }
    }

    @Test fun `queued payload owns its bytes across producer and staging reuse`() {
        val input = ByteArray(16) { it.toByte() }
        val patch = KoolCanvasAtlasAppend(2, 3, 2, 2, input)
        input.fill(99)
        val staging = ByteBuffer.allocate(24).apply { position(4) }
        patch.copyTo(staging)
        assertEquals(20, staging.position())
        assertContentEquals(ByteArray(16) { it.toByte() }, staging.array().copyOfRange(4, 20))
        staging.clear(); staging.put(ByteArray(24) { 77 }); staging.clear()
        patch.copyTo(staging)
        assertContentEquals(ByteArray(16) { it.toByte() }, staging.array().copyOfRange(0, 16))
    }

    @Test fun `invalid bounds and payload lengths are rejected`() {
        assertFailsWith<IllegalArgumentException> { KoolCanvasAtlasAppend(-1, 0, 1, 1, ByteArray(4)) }
        assertFailsWith<IllegalArgumentException> { KoolCanvasAtlasAppend(0, 0, 0, 1, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { KoolCanvasAtlasAppend(0, 0, 2, 1, ByteArray(4)) }
        assertFailsWith<ArithmeticException> { KoolCanvasAtlasAppend(0, 0, Int.MAX_VALUE, 2, ByteArray(0)) }
    }

    @Test fun `shelf appends preserve previously published slots including filtered guards`() {
        val images = (0..7).associate { index -> KoolCanvasTextureId("append-$index") to
            KoolCanvasArgbImage(3 + index % 3, 2 + index % 2,
                IntArray((3 + index % 3) * (2 + index % 2)) { 0xff010203.toInt() + index }) }
        val store = object : KoolCanvasTextureStore by KoolCanvasCpuTextureStore() {
            override fun staticArgbImage(id: KoolCanvasTextureId) = images[id]
        }
        val atlas = KoolCanvasSpriteAtlas(store, pageSize = 32, maxPages = 2, incrementalUploads = true)
        val slots = mutableListOf<KoolCanvasSpriteAtlas.Slot>()
        images.forEach { (id, image) ->
            val command = KoolCanvasCommand.DrawTexture(KoolCanvasTextureRef(id, image.width, image.height),
                KoolCanvasRect(0f, 0f, image.width.toFloat(), image.height.toFloat()),
                KoolCanvasRect(0f, 0f, image.width.toFloat(), image.height.toFloat()),
                KoolCanvasPaint.Default, KoolCanvasState.Default)
            val old = slots.map { slot -> slot.page.rgbaPixels.copyOf() }
            val next = assertNotNull(atlas.slot(command))
            slots.forEachIndexed { i, slot ->
                if (slot.page === next.page) assertTrue(next.x - 2 >= slot.x + slot.width + 2 ||
                    slot.x - 2 >= next.x + next.width + 2 || next.y - 2 >= slot.y + slot.height + 2 ||
                    slot.y - 2 >= next.y + next.height + 2)
                for (y in slot.y - 2 until slot.y + slot.height + 2) {
                    val from = (y * slot.page.size + slot.x - 2) * 4
                    assertContentEquals(old[i].copyOfRange(from, from + (slot.width + 4) * 4),
                        slot.page.rgbaPixels.copyOfRange(from, from + (slot.width + 4) * 4))
                }
            }
            slots += next
        }
    }
}
