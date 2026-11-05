package io.github.rwx.render.canvas

import kotlin.test.*

class KoolCanvasLazySignatureTest {
    private val viewport = KoolCanvasViewport(320, 180)
    private val texture = KoolCanvasTextureRef(KoolCanvasTextureId("signature-source"), 16, 8)
    private val rect = KoolCanvasRect(1f, 2f, 13f, 7f)
    private val paint = KoolCanvasPaint.Default

    private class PairBuffer(batches: Boolean = false) {
        val eager = KoolCanvasCommandBuffer(textureBatches = batches, fogBatches = batches).apply {
            configureLazySignatureForTest(false)
        }
        val lazy = KoolCanvasCommandBuffer(textureBatches = batches, fogBatches = batches).apply {
            configureLazySignatureForTest(true)
        }

        fun record(block: KoolCanvasCommandBuffer.() -> Unit) {
            eager.block(); lazy.block()
        }

        fun compare(): KoolCanvasFrame {
            val control = eager.snapshot()
            assertEquals(control, lazy.snapshot(), "Hash policy must not change recorded commands")
            // The literal existing recording polynomial is also checked independently of the
            // eager buffer's accumulated state, after interleaved reads, clears and frame resets.
            val expected = control.commands.fold(0) { signature, command ->
                signature * 31 + command.hashCode() + 0x9E3779B9.toInt()
            }
            assertEquals(expected, eager.signature)
            assertEquals(expected, lazy.signature)
            assertEquals(expected, lazy.signature, "Repeated reads must not fold a prefix twice")
            return control
        }
    }

    @Test fun `interleaved signature reads retain exact command order across every command kind`() {
        val pair = PairBuffer()
        pair.compare()
        pair.record {
            drawTexture(texture, rect)
            drawRect(rect, paint)
            drawLine(KoolCanvasPoint(0f, -0f), KoolCanvasPoint(4f, 8f), paint)
        }
        pair.compare()
        pair.record {
            save()
            clip(KoolCanvasRect(2f, 1f, 12f, 8f))
            translate(4f, 5f)
            rotate(37f)
            drawTexture(texture, rect, paint.copy(textureEffect = KoolCanvasTextureEffect.TeamColor(
                KoolCanvasTeamColorMode.HueShift, KoolCanvasColor(0xff12ab34.toInt()), .375f)))
            drawCircle(KoolCanvasPoint(3f, 7f), 2.5f, paint.copy(style = KoolCanvasPaintStyle.Stroke))
            drawText("signature中🙂", KoolCanvasPoint(2f, 10f), paint.copy(typefaceKey = "sans"))
            restore()
            drawTextureRepeat(texture, texture.fullRect, rect, 3, paint, state)
            drawTexture(texture, rect, paint.copy(textureEffect = KoolCanvasTextureEffect.Displacement(texture, .5f)))
        }
        val frame = pair.compare()
        assertEquals(8, frame.commands.size)
        pair.record { drawRect(rect.copy(right = 20f), paint) }
        pair.compare()
    }

    @Test fun `clear discards only its target and invalidates an already requested prefix`() {
        val pair = PairBuffer()
        val target = KoolCanvasRenderTargetRef(KoolCanvasRenderTargetId("signature-target"), 16, 8)
        pair.record { drawTexture(texture, rect); setRenderTarget(target); drawRect(rect, paint) }
        pair.compare()
        pair.record { setRenderTarget(null); drawCircle(KoolCanvasPoint(2f, 3f), 1f, paint) }
        pair.compare()
        pair.record { clear(KoolCanvasColor.Transparent) }
        val rootCleared = pair.compare()
        assertEquals(2, rootCleared.commands.size)
        assertEquals(target.id, assertIs<KoolCanvasCommand.DrawRect>(rootCleared.commands[0]).state.renderTarget)
        assertNull(assertIs<KoolCanvasCommand.Clear>(rootCleared.commands[1]).renderTarget)
        pair.record {
            setRenderTarget(target)
            clear(KoolCanvasColor.White, KoolCanvasBlendMode.ClearAlpha)
        }
        assertEquals(3, pair.compare().commands.size, "ClearAlpha preserves preceding content")
        pair.record { clear(KoolCanvasColor.White, KoolCanvasBlendMode.Source) }
        val targetCleared = pair.compare()
        assertEquals(2, targetCleared.commands.size)
        assertNull(assertIs<KoolCanvasCommand.Clear>(targetCleared.commands[0]).renderTarget)
        assertEquals(target.id, assertIs<KoolCanvasCommand.Clear>(targetCleared.commands[1]).renderTarget)
    }

    @Test fun `batched pending prefixes grow flush and remain immutable across recorder reuse`() {
        val pair = PairBuffer(batches = true)
        pair.record { repeat(4097) { drawTexture(texture, rect.copy(left = it.toFloat(), right = it + 12f)) } }
        // The signature getter itself must publish a pending batch, identically to snapshot().
        assertEquals(pair.eager.signature, pair.lazy.signature)
        val published = pair.compare()
        assertEquals(listOf(4096, 1), published.commands.map { assertIs<KoolCanvasCommand.DrawTextureBatch>(it).quads.size })
        val publishedSignature = pair.lazy.signature
        pair.record {
            repeat(300) { drawRect(rect.copy(top = it.toFloat(), bottom = it + 5f), paint) }
            setDrawRole(KoolCanvasDrawRole.MapFog)
            repeat(300) {
                drawRect(rect, paint.copy(color = KoolCanvasColor(0x7d000000)))
                drawTexture(texture, rect, paint.copy(color = KoolCanvasColor(0x7d000000)))
            }
        }
        val mixed = pair.compare()
        assertTrue(mixed.commands.any { it is KoolCanvasCommand.DrawRectBatch })
        assertTrue(mixed.commands.any { it is KoolCanvasCommand.DrawFogBatch })
        pair.record { beginFrame(KoolCanvasViewport(640, 360)); repeat(5000) { drawTexture(texture, rect) } }
        pair.compare()
        assertEquals(publishedSignature, published.commands.fold(0) { signature, command ->
            signature * 31 + command.hashCode() + 0x9E3779B9.toInt()
        }, "Published batch coordinates cannot alias scratch storage")
        assertEquals(1f, assertIs<KoolCanvasCommand.DrawTextureBatch>(published.commands[0]).quads.coordinate(1, 4))
    }

    @Test fun `unrequested recordings snapshots and clear reset never carry a stale prefix into next frame`() {
        val pair = PairBuffer(batches = true)
        pair.record { drawTexture(texture, rect) }
        // Publish without requesting a signature, as the ordinary root canvas does.
        assertEquals(pair.eager.snapshot(), pair.lazy.snapshot())
        pair.record { drawRect(rect, paint); clear(KoolCanvasColor.White); drawTexture(texture, rect) }
        pair.compare()
        pair.record { drawRect(rect, paint); beginFrame(viewport) }
        assertTrue(pair.eager.isEmpty && pair.lazy.isEmpty)
        assertEquals(0, pair.lazy.signature)
        pair.compare()
        pair.record { drawText("next", KoolCanvasPoint(1f, 2f), paint) }
        pair.compare()
        pair.record { beginFrame(viewport) }
        pair.compare()
    }

    @Test fun `test policy configuration is restricted to a fresh empty recorder`() {
        val used = KoolCanvasCommandBuffer().apply { drawTexture(texture, rect) }
        assertFailsWith<IllegalStateException> { used.configureLazySignatureForTest(true) }
        val reset = KoolCanvasCommandBuffer().apply { beginFrame(viewport) }
        assertFailsWith<IllegalStateException> { reset.configureLazySignatureForTest(true) }
    }
}
