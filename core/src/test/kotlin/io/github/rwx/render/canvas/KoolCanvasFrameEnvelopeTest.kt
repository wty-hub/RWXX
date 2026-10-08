package io.github.rwx.render.canvas

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File

class KoolCanvasFrameEnvelopeTest {
    private fun draw(id: KoolCanvasTextureId, paint: KoolCanvasPaint = KoolCanvasPaint.Default) =
        KoolCanvasCommand.DrawTexture(KoolCanvasTextureRef(id, 1, 1), KoolCanvasRect.fromSize(1f, 1f),
            KoolCanvasRect.fromSize(1f, 1f), paint, KoolCanvasState.Default)

    private fun frame(vararg commands: KoolCanvasCommand) = KoolCanvasFrame(KoolCanvasViewport(100, 100), commands.toList())
    private fun KoolCanvasCpuTextureStore.freeze(frame: KoolCanvasFrame, sequence: Long = 1, generation: Long = 1) =
        freezeFrame(frame, sequence, generation, 42, 3)

    @Test
    fun `cached frame and GPU targets restore transitive resources without unrelated sibling dependencies`() {
        for (gpu in listOf(false, true)) {
            val store = KoolCanvasCpuTextureStore(KoolCanvasPixelPool(), true, reuseFrozenContent = true)
            val sprite = KoolCanvasTextureId("closure-sprite-$gpu")
            val inner = KoolCanvasTextureId("closure-inner-$gpu")
            val target = KoolCanvasTextureId("closure-target-$gpu")
            val sibling = KoolCanvasTextureId("closure-sibling-$gpu")
            store.registerArgb(sprite, 1, 1, intArrayOf(0xff123456.toInt()), false)
            store.registerArgb(sibling, 1, 1, intArrayOf(0xff654321.toInt()), false)
            store.registerFrame(inner, frame(draw(sprite)))
            val recorded = frame(draw(inner), draw(sprite))
            if (gpu) store.registerGpuTargetFrame(target, recorded, 100, 100)
            else store.registerFrame(target, recorded)
            // Resolve the shared leaf first: the enclosing cache must account for memo-hit resources.
            val root = frame(draw(sibling), draw(sprite), draw(target))
            fun nested(envelope: FrameEnvelope): KoolCanvasFrame {
                val id = (envelope.frame.commands.last() as KoolCanvasCommand.DrawTexture).texture.id
                return when (val resource = envelope.resourceLease.resources().getValue(id)) {
                    is FrozenCanvasResource.Frame -> resource.frame
                    is FrozenCanvasResource.GpuTarget -> resource.frame
                    else -> error("Expected recorded target")
                }
            }
            val first = store.freeze(root)
            store.registerArgb(sibling, 1, 1, intArrayOf(0xff987654.toInt()), false)
            val second = store.freeze(root, 2)
            assertSame(nested(first), nested(second), "A changed unrelated sibling cannot invalidate this target")
            assertEquals(4, second.resourceLease.resourceCount)
            // A hit on the enclosing target must restore both the nested target and its leaf image.
            val third = store.freeze(frame(draw(target)), 3)
            assertSame(nested(second), nested(third))
            assertEquals(3, third.resourceLease.resourceCount)
            assertEquals(0xff123456.toInt(), third.resourceLease.resources().values
                .filterIsInstance<FrozenCanvasResource.Pixels>().single().image.pixels[0])
            store.registerArgb(sprite, 1, 1, intArrayOf(0xffabcdef.toInt()), false)
            val fourth = store.freeze(frame(draw(target)), 4)
            assertFalse(nested(third) === nested(fourth), "Descendant updates must invalidate every enclosing cached target")
            assertEquals(0xffabcdef.toInt(), fourth.resourceLease.resources().values
                .filterIsInstance<FrozenCanvasResource.Pixels>().single().image.pixels[0])
            assertEquals(0xff123456.toInt(), third.resourceLease.resources().values
                .filterIsInstance<FrozenCanvasResource.Pixels>().single().image.pixels[0], "An old lease keeps old pixels")
            listOf(first, second, third, fourth).forEach(FrameEnvelope::close)
            store.close()
        }
    }

    @Test
    fun `frozen reuse preserves displacement resources and invalidates late or cyclic sources`() {
        val store = KoolCanvasCpuTextureStore(KoolCanvasPixelPool(), true, reuseFrozenContent = true)
        val target = KoolCanvasTextureId("closure-effect")
        val screen = KoolCanvasTextureId("closure-screen")
        val late = KoolCanvasTextureId("closure-late")
        val paint = KoolCanvasPaint(textureEffect = KoolCanvasTextureEffect.Displacement(KoolCanvasTextureRef(screen, 1, 1), 2f))
        store.registerArgb(screen, 1, 1, intArrayOf(0xff010203.toInt()), true)
        store.registerFrame(target, frame(draw(late, paint)))
        val first = store.freeze(frame(draw(target)))
        val second = store.freeze(frame(draw(target)), 2)
        assertEquals(3, second.resourceLease.resourceCount)
        assertEquals(1, second.resourceLease.resources().values.count { it === FrozenCanvasResource.Missing })
        store.registerArgb(late, 1, 1, intArrayOf(0xff040506.toInt()), true)
        val third = store.freeze(frame(draw(target)), 3)
        assertEquals(0, third.resourceLease.resources().values.count { it === FrozenCanvasResource.Missing })
        assertEquals(3, third.resourceLease.resourceCount)
        store.registerFrame(target, frame(draw(target)))
        val fourth = store.freeze(frame(draw(target)), 4)
        val fifth = store.freeze(frame(draw(target)), 5)
        assertEquals(2, fifth.resourceLease.resourceCount)
        assertEquals(1, fifth.resourceLease.resources().values.count { it === FrozenCanvasResource.Missing })
        listOf(first, second, third, fourth, fifth).forEach(FrameEnvelope::close)
        store.close()
    }

    @Test
    fun `asset fallback owns encoded file version across reload and delayed consumption`() {
        val file = File.createTempFile("rwx-frame-asset", ".png")
        val firstBytes = byteArrayOf(1, 2, 3, 4)
        val secondBytes = byteArrayOf(5, 6, 7, 8)
        val store = KoolCanvasCpuTextureStore()
        val id = KoolCanvasTextureId("delayed-mod-asset")
        try {
            file.writeBytes(firstBytes)
            store.registerAsset(id, file.absolutePath)
            val first = store.freeze(frame(draw(id)))
            file.writeBytes(secondBytes)
            store.registerAsset(id, file.absolutePath)
            val second = store.freeze(frame(draw(id)), 2)
            file.delete()
            val oldId = (first.frame.commands.single() as KoolCanvasCommand.DrawTexture).texture.id
            val newId = (second.frame.commands.single() as KoolCanvasCommand.DrawTexture).texture.id
            assertNotEquals(oldId, newId)
            assertContentEquals(firstBytes, (first.resourceLease.resources()[oldId] as FrozenCanvasResource.Asset).encodedBytes)
            assertContentEquals(secondBytes, (second.resourceLease.resources()[newId] as FrozenCanvasResource.Asset).encodedBytes)
            first.close(); second.close()
        } finally { file.delete() }
    }

    @Test
    fun `presentation stalls can drop updates without losing complete current texture resources`() {
        val store = KoolCanvasCpuTextureStore()
        val staticId = KoolCanvasTextureId("drop-static-sprite")
        val dynamicId = KoolCanvasTextureId("drop-dynamic-map")
        val mailbox = LatestFrameMailbox()
        store.registerArgb(staticId, 1, 1, intArrayOf(0xff010203.toInt()), true)
        store.registerArgb(dynamicId, 1, 1, intArrayOf(0xff111111.toInt()), false)
        val shown = store.freeze(frame(draw(staticId), draw(dynamicId)))
        val inFlight = shown.retain()
        store.registerArgb(dynamicId, 1, 1, intArrayOf(0xff222222.toInt()), false)
        val dropped = store.freeze(frame(draw(staticId), draw(dynamicId)), 2)
        mailbox.publish(dropped)
        store.registerArgb(dynamicId, 1, 1, intArrayOf(0xff333333.toInt()), false)
        val latest = store.freeze(frame(draw(staticId), draw(dynamicId)), 3)
        mailbox.publish(latest)
        assertTrue(dropped.resourceLease.isReleased)
        assertEquals(2, latest.resourceLease.resourceCount)
        val latestPixels = latest.resourceLease.resources().values.filterIsInstance<FrozenCanvasResource.Pixels>()
        assertEquals(setOf(0xff010203.toInt(), 0xff333333.toInt()), latestPixels.map { it.image.pixels[0] }.toSet())
        shown.close()
        assertFalse(inFlight.resourceLease.isReleased)
        assertEquals(setOf(0xff010203.toInt(), 0xff111111.toInt()),
            inFlight.resourceLease.resources().values.filterIsInstance<FrozenCanvasResource.Pixels>()
                .map { it.image.pixels[0] }.toSet())
        inFlight.close()
        assertTrue(shown.resourceLease.isReleased)
        mailbox.close()
        assertTrue(latest.resourceLease.isReleased)
    }

    @Test
    fun `fence retirement retains current GPU registration and CPU pixels after front frame changes`() {
        val store = KoolCanvasCpuTextureStore()
        val id = KoolCanvasTextureId("fence-current-frame")
        store.registerArgb(id, 1, 1, intArrayOf(0xff123456.toInt()), true)
        val current = store.freeze(frame(draw(id)))
        val flight = current.retain()
        val gpuOwner = FrozenCanvasGpuResources.install(current.resourceLease)
        val versionId = (current.frame.commands.single() as KoolCanvasCommand.DrawTexture).texture.id
        val pending = mutableListOf<() -> Unit>()
        KoolCanvasGpuRetirement.install { pending += it }
        try {
            current.close()
            KoolCanvasGpuRetirement.retire { gpuOwner.close(); flight.close() }
            assertFalse(flight.resourceLease.isReleased)
            assertEquals(0xff123456.toInt(), KoolCanvasTextureRegistry.staticArgbImage(versionId)!!.pixels[0])
            assertEquals(1, pending.size)
            pending.removeAt(0).invoke()
            assertTrue(flight.resourceLease.isReleased)
            assertEquals(null, KoolCanvasTextureRegistry.staticArgbImage(versionId))
        } finally {
            pending.forEach { it() }
            KoolCanvasGpuRetirement.install(null)
        }
    }

    @Test
    fun `mailbox shutdown releases publication arriving after close while producer is active`() {
        val store = KoolCanvasCpuTextureStore()
        val mailbox = LatestFrameMailbox()
        val firstHalfReady = CountDownLatch(1)
        val resumeProducer = CountDownLatch(1)
        val packets = mutableListOf<FrameEnvelope>()
        val producer = Thread {
            repeat(200) { index ->
                if (index == 100) {
                    firstHalfReady.countDown()
                    assertTrue(resumeProducer.await(5, TimeUnit.SECONDS))
                }
                val packet = store.freeze(frame(), index.toLong())
                packets += packet
                mailbox.publish(packet)
            }
        }
        producer.start()
        assertTrue(firstHalfReady.await(5, TimeUnit.SECONDS))
        mailbox.close()
        resumeProducer.countDown()
        producer.join(5000)
        assertFalse(producer.isAlive)
        assertEquals(200, packets.size)
        assertTrue(packets.all { it.resourceLease.isReleased })
        assertEquals(null, mailbox.poll())
    }

    @Test
    fun `mailbox transfers newest owner and immediately releases overwritten pending frame`() {
        val store = KoolCanvasCpuTextureStore()
        val mailbox = LatestFrameMailbox()
        val first = store.freeze(frame(), 1)
        val second = store.freeze(frame(), 2)
        mailbox.publish(first)
        mailbox.publish(second)
        assertTrue(first.resourceLease.isReleased)
        assertSame(second, mailbox.poll())
        assertEquals(null, mailbox.poll())
        assertFalse(second.resourceLease.isReleased)
        second.close()
        assertTrue(second.resourceLease.isReleased)
        mailbox.close()
    }

    @Test
    fun `closed mailbox releases late publication while clear permits a later game`() {
        val store = KoolCanvasCpuTextureStore()
        val mailbox = LatestFrameMailbox()
        mailbox.publish(store.freeze(frame()))
        mailbox.clear()
        val next = store.freeze(frame(), 2)
        mailbox.publish(next)
        assertSame(next, mailbox.poll())
        next.close()
        mailbox.close()
        val late = store.freeze(frame(), 3)
        mailbox.publish(late)
        assertTrue(late.resourceLease.isReleased)
        assertEquals(null, mailbox.poll())
    }

    @Test
    fun `recorded pixel versions remain detached while consumer is delayed and frames are dropped`() {
        val store = KoolCanvasCpuTextureStore()
        val id = KoolCanvasTextureId("delayed-map")
        val legacy = intArrayOf(0xff123456.toInt())
        store.registerArgb(id, 1, 1, legacy, alphaBleed = false)
        val old = store.freeze(frame(draw(id)))
        legacy[0] = 0xff654321.toInt()
        store.registerArgb(id, 1, 1, legacy, alphaBleed = false)
        val newer = store.freeze(frame(draw(id)), 2)
        store.unregister(id)
        legacy[0] = 0

        val oldTexture = (old.frame.commands.single() as KoolCanvasCommand.DrawTexture).texture.id
        val newTexture = (newer.frame.commands.single() as KoolCanvasCommand.DrawTexture).texture.id
        assertNotEquals(oldTexture, newTexture)
        assertContentEquals(intArrayOf(0xff123456.toInt()),
            (old.resourceLease.resources()[oldTexture] as FrozenCanvasResource.Pixels).image.pixels)
        assertContentEquals(intArrayOf(0xff654321.toInt()),
            (newer.resourceLease.resources()[newTexture] as FrozenCanvasResource.Pixels).image.pixels)
        old.close()
        newer.close()
    }

    @Test
    fun `unchanged static pixels share immutable data across frames and owners`() {
        val store = KoolCanvasCpuTextureStore()
        val id = KoolCanvasTextureId("static-sprite")
        store.registerArgb(id, 1, 1, intArrayOf(0xffabcdef.toInt()), alphaBleed = true)
        val first = store.freeze(frame(draw(id)))
        val second = store.freeze(frame(draw(id)), 2)
        val resource = first.resourceLease.resources().values.single() as FrozenCanvasResource.Pixels
        assertSame(resource, second.resourceLease.resources().values.single())
        assertTrue(resource.isStatic)
        val retained = first.retain()
        first.close()
        first.close() // Closing one envelope owner twice is harmless.
        assertFalse(retained.resourceLease.isReleased)
        retained.close()
        assertTrue(first.resourceLease.isReleased)
        second.close()
    }

    @Test
    fun `all nested frames and displacement screen base are included in every packet`() {
        val store = KoolCanvasCpuTextureStore()
        val sprite = KoolCanvasTextureId("nested-sprite")
        val target = KoolCanvasTextureId("nested-target")
        val screen = KoolCanvasTextureId("displacement-screen")
        store.registerArgb(sprite, 1, 1, intArrayOf(0xff010203.toInt()), false)
        store.registerArgb(screen, 1, 1, intArrayOf(0xff040506.toInt()), false)
        store.registerFrame(target, frame(draw(sprite)))
        val paint = KoolCanvasPaint(textureEffect = KoolCanvasTextureEffect.Displacement(KoolCanvasTextureRef(screen, 1, 1), 2f))
        val first = store.freeze(frame(draw(target, paint)), generation = 5)
        assertEquals(3, first.resourceLease.resourceCount)
        assertEquals(5, first.generation)
        val command = first.frame.commands.single() as KoolCanvasCommand.DrawTexture
        val displacement = command.paint.textureEffect as KoolCanvasTextureEffect.Displacement
        assertTrue(displacement.screenBase.id in first.resourceLease.resources())
        val oldNested = first.resourceLease.resources()[command.texture.id] as FrozenCanvasResource.Frame
        val oldSpriteId = (oldNested.frame.commands.single() as KoolCanvasCommand.DrawTexture).texture.id

        // A retained frame target can refer to a dynamic source updated without re-registering the target.
        store.registerArgb(sprite, 1, 1, intArrayOf(0xff070809.toInt()), false)
        val second = store.freeze(frame(draw(target, paint)), 2, 6)
        val secondCommand = second.frame.commands.single() as KoolCanvasCommand.DrawTexture
        val newNested = second.resourceLease.resources()[secondCommand.texture.id] as FrozenCanvasResource.Frame
        val newSpriteId = (newNested.frame.commands.single() as KoolCanvasCommand.DrawTexture).texture.id
        assertNotEquals(command.texture.id, secondCommand.texture.id)
        assertNotEquals(oldSpriteId, newSpriteId)
        assertContentEquals(intArrayOf(0xff010203.toInt()),
            (first.resourceLease.resources()[oldSpriteId] as FrozenCanvasResource.Pixels).image.pixels)
        assertEquals(3, second.resourceLease.resourceCount)
        first.close()
        second.close()
    }

    @Test
    fun `install leases retain shared GPU resource until the last completed frame releases it`() {
        val store = KoolCanvasCpuTextureStore()
        val id = KoolCanvasTextureId("shared-installed")
        store.registerArgb(id, 1, 1, intArrayOf(0xff123456.toInt()), true)
        val first = store.freeze(frame(draw(id)))
        val second = store.freeze(frame(draw(id)), 2)
        val versionId = (first.frame.commands.single() as KoolCanvasCommand.DrawTexture).texture.id
        val firstGpu = FrozenCanvasGpuResources.install(first.resourceLease)
        val secondGpu = FrozenCanvasGpuResources.install(second.resourceLease)
        firstGpu.close()
        assertEquals(0xff123456.toInt(), KoolCanvasTextureRegistry.argbImageView(versionId)!!.pixels[0])
        secondGpu.close()
        assertEquals(null, KoolCanvasTextureRegistry.argbImageView(versionId))
        first.close()
        second.close()
    }

    @Test
    fun `cyclic frame reference is replaced with missing texture without recursive replay`() {
        val store = KoolCanvasCpuTextureStore()
        val id = KoolCanvasTextureId("cyclic-frame")
        store.registerFrame(id, frame(draw(id)))
        val envelope = store.freeze(frame(draw(id)))
        assertEquals(2, envelope.resourceLease.resourceCount)
        assertEquals(1, envelope.resourceLease.resources().values.count { it === FrozenCanvasResource.Missing })
        envelope.close()
    }
}
