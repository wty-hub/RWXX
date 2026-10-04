package io.github.rwx.render.canvas

import com.corrodinggames.rts.gameFramework.graphics.RenderTargetMode
import io.github.rwx.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KoolImmutablePixelSnapshotTest {
    private fun draw(id: KoolCanvasTextureId, width: Int = 2, height: Int = 1) = KoolCanvasCommand.DrawTexture(
        KoolCanvasTextureRef(id, width, height), KoolCanvasRect.fromSize(width.toFloat(), height.toFloat()),
        KoolCanvasRect.fromSize(width.toFloat(), height.toFloat()), KoolCanvasPaint.Default, KoolCanvasState.Default,
    )

    private fun frame(vararg commands: KoolCanvasCommand) = KoolCanvasFrame(KoolCanvasViewport(2, 1), commands.toList())

    @Test
    fun detachedPixelsKeepTheirSnapshotVersionAcrossLegacyEditsReplacementAndUnregister() {
        val store = KoolCanvasCpuTextureStore()
        val sourceId = KoolCanvasTextureId("snapshot-mutable-source")
        val snapshotId = KoolCanvasTextureId("snapshot-mutable-dependency")
        val legacy = intArrayOf(0xff123456.toInt(), 0xff654321.toInt())
        val original = legacy.copyOf()
        store.registerArgb(sourceId, 2, 1, legacy, alphaBleed = false)
        val source = assertNotNull(store.argbImageView(sourceId))
        assertNotSame(legacy, source.pixels)
        assertSame(source, store.snapshotPixels(sourceId, snapshotId))
        assertSame(source, store.argbImageView(snapshotId))
        val frozen = store.freezeFrame(frame(draw(snapshotId)), 1, 1, 0, 0)
        try {
            legacy.fill(0)
            store.registerArgb(sourceId, 2, 1, intArrayOf(0xffabcdef.toInt(), 0xfffedcba.toInt()), false)
            assertNotSame(source, store.argbImageView(sourceId))
            assertSame(source, store.argbImageView(snapshotId))
            store.unregister(sourceId)
            store.unregister(snapshotId)
            val pixels = frozen.resourceLease.resources().values.single() as FrozenCanvasResource.Pixels
            assertSame(source, pixels.image)
            assertContentEquals(original, pixels.image.pixels)
        } finally {
            frozen.close()
            store.close()
        }
    }

    @Test
    fun snapshotsPreserveCanonicalAlphaBleedAndPremultipliedData() {
        val store = KoolCanvasCpuTextureStore()
        val bledId = KoolCanvasTextureId("snapshot-bled-source")
        val bledSnapshotId = KoolCanvasTextureId("snapshot-bled-dependency")
        val premulId = KoolCanvasTextureId("snapshot-premul-source")
        val premulSnapshotId = KoolCanvasTextureId("snapshot-premul-dependency")
        store.registerArgb(bledId, 2, 1, intArrayOf(0xff2468ac.toInt(), 0x00ffffff), true)
        val bled = assertNotNull(store.argbImageView(bledId))
        assertContentEquals(intArrayOf(0xff2468ac.toInt(), 0x002468ac), bled.pixels)
        assertSame(bled, store.snapshotPixels(bledId, bledSnapshotId))
        assertSame(bled, store.staticArgbImage(bledSnapshotId))

        val premulInput = intArrayOf(0x80402010.toInt(), 0x00123456)
        store.registerPremultipliedArgb(premulId, 2, 1, premulInput)
        val premul = assertNotNull(store.argbImageView(premulId))
        assertContentEquals(intArrayOf(0x80402010.toInt(), 0), premul.pixels)
        assertNotSame(premulInput, premul.pixels)
        assertSame(premul, store.snapshotPixels(premulId, premulSnapshotId))
        assertTrue(premul.premultipliedAlpha)
        assertNull(store.staticArgbImage(premulSnapshotId))
        premulInput.fill(0)
        assertContentEquals(intArrayOf(0x80402010.toInt(), 0), premul.pixels)
        store.close()
    }

    @Test
    fun pooledSnapshotAndFrozenGpuLeasePinOneAllocationUntilFenceRetirement() {
        val pool = KoolCanvasPixelPool(maxRetainedBytes = 64, maxArraysPerSize = 4)
        val store = KoolCanvasCpuTextureStore(pool, true)
        val sourceId = KoolCanvasTextureId("snapshot-pooled-source")
        val snapshotId = KoolCanvasTextureId("snapshot-pooled-dependency")
        val raster = pool.borrow(2)
        val pixels = raster.pixels
        pixels.fill(0xff123456.toInt())
        store.registerPooledArgb(sourceId, 2, 1, raster)
        assertSame(store.argbImageView(sourceId), store.snapshotPixels(sourceId, snapshotId))
        assertEquals(3, raster.allocation.ownerCount) // Borrow, source, independent snapshot.
        val frozen = store.freezeFrame(frame(draw(sourceId), draw(snapshotId)), 1, 1, 0, 0)
        assertEquals(4, raster.allocation.ownerCount) // Both ids share one lease pin.
        val installed = FrozenCanvasGpuResources.install(frozen.resourceLease)
        val pending = mutableListOf<() -> Unit>()
        KoolCanvasGpuRetirement.install { pending += it }
        try {
            raster.close()
            store.unregister(sourceId)
            store.unregister(snapshotId)
            frozen.close()
            assertEquals(1, raster.allocation.ownerCount)
            assertFalse(frozen.resourceLease.isReleased)
            KoolCanvasGpuRetirement.retire { installed.close() }
            assertEquals(1, pending.size)
            val concurrent = pool.borrow(2)
            try {
                assertNotSame(pixels, concurrent.pixels)
                assertContentEquals(IntArray(2) { 0xff123456.toInt() }, pixels)
                pending.removeAt(0).invoke()
                assertTrue(frozen.resourceLease.isReleased)
                val reused = pool.borrow(2)
                try {
                    assertSame(pixels, reused.pixels)
                } finally {
                    reused.close()
                }
            } finally {
                concurrent.close()
            }
            assertEquals(0, pool.stats().activeAllocations)
        } finally {
            pending.forEach { it() }
            KoolCanvasGpuRetirement.install(null)
            installed.close()
            frozen.close()
            raster.close()
            store.close()
        }
    }

    @Test
    fun offscreenBatchSharesPooledAndUnpooledRastersWhileLegacyWritesDetach() {
        for (pooled in listOf(false, true)) {
            val store = KoolCanvasCpuTextureStore(KoolCanvasPixelPool(), pooled)
            val root = KoolGraphicsEngine(textureStore = store)
            val source = root.b(2, 1, true)
            val sourceTarget = root.b(source, RenderTargetMode.IMMEDIATE)
            val destination = root.b(2, 1, true)
            val destinationTarget = root.b(destination, RenderTargetMode.DEFAULT) as KoolGraphicsEngine
            val sourceId = KoolCanvasTextureId("legacy-texture-${source.d}")
            try {
                sourceTarget.b(0xff123456.toInt())
                sourceTarget.p()
                val original = assertNotNull(store.argbImageView(sourceId))
                if (pooled) assertSame(original.pixels, source.argbPixelsRef)
                else assertNotSame(original.pixels, source.argbPixelsRef)
                destinationTarget.b(source, 0f, 0f, null)
                val firstId = (destinationTarget.snapshot().commands.single() as KoolCanvasCommand.DrawTexture).texture.id
                assertSame(original, store.argbImageView(firstId), "offscreen dependency shares the detached current image")
                destinationTarget.b(source, 0f, 0f, null)
                val unchangedId = (destinationTarget.snapshot().commands.last() as KoolCanvasCommand.DrawTexture).texture.id
                assertEquals(firstId, unchangedId, "an unchanged batch dependency keeps its snapshot id")

                source.a(0, 0, 0xff654321.toInt())
                destinationTarget.b(source, 0f, 0f, null)
                val editedId = (destinationTarget.snapshot().commands.last() as KoolCanvasCommand.DrawTexture).texture.id
                assertNotEquals(firstId, editedId)
                assertContentEquals(IntArray(2) { 0xff123456.toInt() }, original.pixels)
                assertContentEquals(intArrayOf(0xff654321.toInt(), 0xff123456.toInt()),
                    assertNotNull(store.argbImageView(editedId)).pixels)

                destinationTarget.beginFrame(2, 1)
                assertNull(store.argbImageView(firstId), "the completed batch releases its snapshot source owner")
                assertNull(store.argbImageView(editedId))
            } finally {
                destinationTarget.q()
                sourceTarget.q()
                source.o()
                destination.o()
                store.close()
            }
        }
    }

    @Test
    fun restoringAnExternallyReplacedSourceInvalidatesTheSameRevisionBatchSnapshot() {
        val store = KoolCanvasCpuTextureStore()
        val root = KoolGraphicsEngine(textureStore = store)
        val source = root.b(2, 1, true)
        val sourceTarget = root.b(source, RenderTargetMode.IMMEDIATE)
        val destination = root.b(2, 1, true)
        val destinationTarget = root.b(destination, RenderTargetMode.DEFAULT) as KoolGraphicsEngine
        val sourceId = KoolCanvasTextureId("legacy-texture-${source.d}")
        try {
            sourceTarget.b(0xff123456.toInt())
            sourceTarget.p()
            destinationTarget.b(source, 0f, 0f, null)
            val firstId = (destinationTarget.snapshot().commands.single() as KoolCanvasCommand.DrawTexture).texture.id
            val first = assertNotNull(store.argbImageView(firstId))
            val pixelRevision = source.getPixelRevision()
            store.registerArgb(sourceId, 2, 1, IntArray(2) { 0xffabcdef.toInt() }, false)
            destinationTarget.b(source, 0f, 0f, null)
            val restoredId = (destinationTarget.snapshot().commands.last() as KoolCanvasCommand.DrawTexture).texture.id
            assertEquals(pixelRevision, source.getPixelRevision())
            assertNotEquals(firstId, restoredId, "a replaced source image invalidates a same-revision dependency")
            val restored = assertNotNull(store.argbImageView(restoredId))
            assertNotSame(first, restored)
            assertSame(store.argbImageView(sourceId), restored)
            assertContentEquals(IntArray(2) { 0xff123456.toInt() }, restored.pixels)
            store.unregister(sourceId)
            source.o()
            assertContentEquals(IntArray(2) { 0xff123456.toInt() }, first.pixels)
            assertSame(first, store.argbImageView(firstId))
        } finally {
            destinationTarget.q()
            sourceTarget.q()
            source.o()
            destination.o()
            store.close()
        }
    }

    @Test
    fun nonCpuStoresKeepTheDetachedOffscreenCopyPath() {
        val delegate = KoolCanvasCpuTextureStore()
        val otherStore = object : KoolCanvasTextureStore by delegate {}
        val root = KoolGraphicsEngine(textureStore = otherStore)
        val source = root.b(2, 1, true)
        val sourceTarget = root.b(source, RenderTargetMode.IMMEDIATE)
        val destination = root.b(2, 1, true)
        val destinationTarget = root.b(destination, RenderTargetMode.DEFAULT) as KoolGraphicsEngine
        val sourceId = KoolCanvasTextureId("legacy-texture-${source.d}")
        try {
            sourceTarget.b(0xff123456.toInt())
            sourceTarget.p()
            val original = assertNotNull(delegate.argbImageView(sourceId))
            destinationTarget.b(source, 0f, 0f, null)
            val snapshotId = (destinationTarget.snapshot().commands.single() as KoolCanvasCommand.DrawTexture).texture.id
            val copied = assertNotNull(delegate.argbImageView(snapshotId))
            assertNotSame(original.pixels, copied.pixels)
            assertContentEquals(original.pixels, copied.pixels)
        } finally {
            destinationTarget.q()
            sourceTarget.q()
            source.o()
            destination.o()
            delegate.close()
        }
    }

    @Test
    fun incrementalAndNestedRecordedTargetsKeepPixelDependenciesUntilTheirLastOwnerCloses() {
        val pool = KoolCanvasPixelPool(maxRetainedBytes = 64, maxArraysPerSize = 4)
        val store = KoolCanvasCpuTextureStore(pool, true)
        val root = KoolGraphicsEngine(textureStore = store)
        val source = root.b(2, 1, true)
        val sourceTarget = root.b(source, RenderTargetMode.IMMEDIATE)
        val recorded = root.b(2, 1, true)
        val recordedTarget = root.b(recorded, RenderTargetMode.DEFAULT) as KoolGraphicsEngine
        val nested = root.b(2, 1, true)
        val nestedTarget = root.b(nested, RenderTargetMode.DEFAULT) as KoolGraphicsEngine
        val recordedId = KoolCanvasTextureId("legacy-texture-${recorded.d}")
        val nestedId = KoolCanvasTextureId("legacy-texture-${nested.d}")
        var shown: FrameEnvelope? = null
        try {
            sourceTarget.b(0xff123456.toInt())
            sourceTarget.p()
            recordedTarget.b(source, 0f, 0f, null)
            recordedTarget.b(source, 0f, 0f, null)
            recordedTarget.p()
            val firstFrame = assertNotNull(store.frame(recordedId))
            val pixelId = (firstFrame.commands.first() as KoolCanvasCommand.DrawTexture).texture.id
            val image = assertNotNull(store.argbImageView(pixelId))
            recordedTarget.a(Rect(0, 0, 1, 1), KoolPaint().apply { setColor(0xff654321.toInt()) })
            recordedTarget.p() // No full clear: the original two pixel draws remain dependencies.
            assertSame(image, store.argbImageView(pixelId))
            assertEquals(2, assertNotNull(store.frame(recordedId)).commands.filterIsInstance<KoolCanvasCommand.DrawTexture>().size)

            nestedTarget.b(recorded, 0f, 0f, null)
            nestedTarget.b(recorded, 0f, 0f, null)
            nestedTarget.p()
            // Repeated nested recording retains each shared pixel dependency once per target,
            // including a dependency already owned by a previous incremental commit.
            nestedTarget.b(recorded, 0f, 0f, null)
            nestedTarget.p()
            recordedTarget.b(0xffabcdef.toInt())
            recordedTarget.p() // Original target releases its pixel dependency; nested still owns it.
            source.o()
            assertSame(image, store.argbImageView(pixelId))

            val frozen = store.freezeFrame(assertNotNull(store.frame(nestedId)), 1, 1, 0, 0)
            shown = frozen
            val pixelResources = frozen.resourceLease.resources().values.filterIsInstance<FrozenCanvasResource.Pixels>()
            assertEquals(1, pixelResources.size)
            assertSame(image, pixelResources.single().image)
            assertFalse(frozen.resourceLease.resources().values.any { it === FrozenCanvasResource.Missing })
            recorded.o()
            nested.o()
            assertNull(store.argbImageView(pixelId), "the nested target must release its final source snapshot owner")
            assertEquals(1, pool.stats().activeAllocations, "the displayed lease still pins the original allocation")
            assertContentEquals(IntArray(2) { 0xff123456.toInt() }, image.pixels)
            frozen.close()
            assertEquals(0, pool.stats().activeAllocations, "duplicate dependency draws must not leak retained owners")
            val reused = pool.borrow(2)
            try {
                assertSame(image.pixels, reused.pixels)
            } finally {
                reused.close()
            }
        } finally {
            shown?.close()
            nestedTarget.q()
            recordedTarget.q()
            sourceTarget.q()
            nested.o()
            recorded.o()
            source.o()
            store.close()
        }
    }

    @Test
    fun releasingAnUncommittedTargetReleasesItsPendingPixelSnapshotOwner() {
        val pool = KoolCanvasPixelPool(maxRetainedBytes = 64, maxArraysPerSize = 4)
        val store = KoolCanvasCpuTextureStore(pool, true)
        val root = KoolGraphicsEngine(textureStore = store)
        val source = root.b(2, 1, true)
        val sourceTarget = root.b(source, RenderTargetMode.IMMEDIATE)
        val destination = root.b(2, 1, true)
        val destinationTarget = root.b(destination, RenderTargetMode.DEFAULT) as KoolGraphicsEngine
        try {
            sourceTarget.b(0xff123456.toInt())
            sourceTarget.p()
            destinationTarget.b(source, 0f, 0f, null)
            val snapshotId = (destinationTarget.snapshot().commands.single() as KoolCanvasCommand.DrawTexture).texture.id
            assertNotNull(store.argbImageView(snapshotId))
            source.o()
            assertEquals(1, pool.stats().activeAllocations)
            destination.o() // Pending commands were never committed into active auxiliary ids.
            assertNull(store.argbImageView(snapshotId))
            assertEquals(0, pool.stats().activeAllocations)
        } finally {
            destinationTarget.q()
            sourceTarget.q()
            destination.o()
            source.o()
            store.close()
        }
    }
}
