package io.github.rwx.render.canvas

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class KoolCanvasSourceIndexTest {
    private fun id(value: String) = KoolCanvasTextureId(String(value.toCharArray()))
    private fun draw(id: KoolCanvasTextureId) = KoolCanvasCommand.DrawTexture(
        KoolCanvasTextureRef(id, 1, 1), KoolCanvasRect.fromSize(1f, 1f),
        KoolCanvasRect.fromSize(1f, 1f), KoolCanvasPaint.Default, KoolCanvasState.Default)
    private fun frame(ids: List<KoolCanvasTextureId> = emptyList()) =
        KoolCanvasFrame(KoolCanvasViewport(8, 8), ids.map(::draw))
    private fun KoolCanvasCpuTextureStore.freeze(ids: List<KoolCanvasTextureId>) =
        freezeFrame(frame(ids), 1, 1, 1, 1)
    private fun pixels(envelope: FrameEnvelope) = envelope.frame.commands.map {
        val key = (it as KoolCanvasCommand.DrawTexture).texture.id
        (envelope.resourceLease.resources().getValue(key) as FrozenCanvasResource.Pixels).image.pixels.single()
    }

    @Test fun `equal independent ID strings resolve cached dependencies and invalidate on replacement`() {
        KoolCanvasCpuTextureStore().use { store ->
            val sprite = id("source/雪/cpu-1-v7")
            val equal = id(sprite.value)
            assertNotSame(sprite.value, equal.value)
            val target = id("target/cpu-1-v7")
            store.registerArgb(sprite, 1, 1, intArrayOf(0xff123456.toInt()), true)
            store.registerFrame(target, frame(listOf(equal)))
            assertEquals(0xff123456.toInt(), store.staticArgbImage(equal)!!.pixels.single())
            val first = store.freeze(listOf(target))
            val second = store.freeze(listOf(id(target.value)))
            try {
                val firstTarget = first.resourceLease.resources().values.filterIsInstance<FrozenCanvasResource.Frame>().single()
                val secondTarget = second.resourceLease.resources().values.filterIsInstance<FrozenCanvasResource.Frame>().single()
                assertSame(firstTarget.frame, secondTarget.frame)
                store.registerArgb(equal, 1, 1, intArrayOf(0xff654321.toInt()), true)
                store.freeze(listOf(target)).use { latest ->
                    val resource = latest.resourceLease.resources().values.filterIsInstance<FrozenCanvasResource.Frame>().single()
                    assertNotSame(firstTarget.frame, resource.frame)
                    assertEquals(0xff654321.toInt(), latest.resourceLease.resources().values
                        .filterIsInstance<FrozenCanvasResource.Pixels>().single().image.pixels.single())
                }
                assertEquals(0xff123456.toInt(), first.resourceLease.resources().values
                    .filterIsInstance<FrozenCanvasResource.Pixels>().single().image.pixels.single())
            } finally { first.close(); second.close() }
        }
    }

    @Test fun `hash collisions and full version namespaces survive separate removal and source recreation`() {
        KoolCanvasCpuTextureStore().use { store ->
            val ids = listOf(id("Aa"), id("BB"), id("Aa/cpu-1-v1"), id("Aa/cpu-1-v2"))
            assertEquals(ids[0].value.hashCode(), ids[1].value.hashCode())
            assertNotEquals(ids[0], ids[1])
            val colors = listOf(0xff123456.toInt(), 0xff654321.toInt(), 0xff010203.toInt(), 0xff040506.toInt())
            ids.zip(colors).forEach { (id, color) -> store.registerArgb(id, 1, 1, intArrayOf(color), false) }
            store.freeze(ids).use { old ->
                assertEquals(colors, pixels(old))
                store.unregister(id("Aa"))
                assertNull(store.argbImageView(id("Aa")))
                assertEquals(colors[1], store.argbImageView(id("BB"))!!.pixels.single())
                assertEquals(colors[2], store.argbImageView(id("Aa/cpu-1-v1"))!!.pixels.single())
                assertEquals(colors[3], store.argbImageView(id("Aa/cpu-1-v2"))!!.pixels.single())
                store.registerArgb(id("Aa"), 1, 1, intArrayOf(0xffaabbcc.toInt()), false)
                store.freeze(ids).use { latest ->
                    assertEquals(listOf(0xffaabbcc.toInt()) + colors.drop(1), pixels(latest))
                    assertNotEquals((old.frame.commands[0] as KoolCanvasCommand.DrawTexture).texture.id,
                        (latest.frame.commands[0] as KoolCanvasCommand.DrawTexture).texture.id)
                }
                assertEquals(colors, pixels(old))
            }
        }
    }

    @Test fun `equivalent frame IDs balance retained snapshot ownership through unregister`() {
        KoolCanvasCpuTextureStore().use { store ->
            val source = id("snapshot/\u0000/target")
            val content = frame()
            store.registerFrame(source, content)
            store.retainFrameSnapshot(id(source.value))
            store.retainFrameSnapshot(id(source.value))
            repeat(2) {
                store.unregister(id(source.value))
                assertEquals(content, store.frame(id(source.value)))
            }
            store.unregister(id(source.value))
            assertNull(store.frame(id(source.value)))
            store.registerArgb(source, 1, 1, intArrayOf(0xff102030.toInt()), false)
            assertContentEquals(intArrayOf(0xff102030.toInt()), store.argbImage(id(source.value))!!.pixels)
            store.unregister(id(source.value))
            assertNull(store.argbImageView(source))
        }
    }
}
