package io.github.rwx.authoritative

import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.util.MsdfAtlasInfo
import de.fabmax.kool.util.MsdfFont
import de.fabmax.kool.util.MsdfFontData
import de.fabmax.kool.util.MsdfMeta
import de.fabmax.kool.util.MsdfMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Atlas descriptors only: these tests create neither a native window nor a GPU context. */
class AuthoritativeFontOwnershipTest {
    @Test
    fun `closing a client restores its previous font and releases only its owned atlas once`() {
        val previous = font("shared-default")
        val owned = font("owned-cjk")
        var selected = previous
        var restores = 0
        val owner = AuthoritativeFontOwnership({ selected = it }, {
            assertSame(owned, it)
            assertFalse(it.data.map.isReleased, "Unpublish before releasing the atlas")
            selected = previous
            restores++
        })
        try {
            assertTrue(owner.accept(owned))
            assertSame(owned, selected)
            owner.close()
            owner.close()
            assertEquals(1, restores)
            assertSame(previous, selected)
            assertTrue(owned.data.map.isReleased)
            assertFalse(previous.data.map.isReleased, "Shared/default font lifetime belongs to its original owner")
        } finally { owner.close(); previous.data.map.release(); owned.data.map.release() }
    }

    @Test
    fun `shutdown before asynchronous font loading finishes rejects and releases the late result`() {
        var publications = 0
        val owner = AuthoritativeFontOwnership({ publications++ }, { error("No font was installed") })
        owner.close()
        val late = font("late-cjk")
        try {
            assertFalse(owner.accept(late))
            assertEquals(0, publications)
            assertTrue(late.data.map.isReleased)
            owner.close()
        } finally { late.data.map.release() }
    }

    @Test
    fun `font validation or installation failure releases the unadopted atlas`() {
        var restores = 0
        val owner = AuthoritativeFontOwnership({ error("Atlas is too large") }, { restores++ })
        val rejected = font("invalid-cjk")
        try {
            assertFailsWith<IllegalStateException> { owner.accept(rejected) }
            assertTrue(rejected.data.map.isReleased)
            owner.close()
            assertEquals(0, restores)
        } finally { owner.close(); rejected.data.map.release() }
    }

    @Test
    fun `an unexpected second load cannot replace or release the active font`() {
        var publications = 0
        val owner = AuthoritativeFontOwnership({ publications++ }, {})
        val active = font("first-cjk")
        val duplicate = font("duplicate-cjk")
        try {
            assertTrue(owner.accept(active))
            assertFalse(owner.accept(active))
            assertFalse(active.data.map.isReleased)
            assertFalse(owner.accept(duplicate))
            assertTrue(duplicate.data.map.isReleased)
            assertEquals(1, publications)
            owner.close()
            assertTrue(active.data.map.isReleased)
        } finally { owner.close(); active.data.map.release(); duplicate.data.map.release() }
    }

    private fun font(name: String) = MsdfFont(MsdfFontData(Texture2d(TexFormat.RGBA, name = name),
        MsdfMeta(MsdfAtlasInfo("msdf", 4f, 16f, 1, 1, "bottom"), name,
            MsdfMetrics(1f, 1.2f, .8f, -.2f, -.1f, .05f))))
}
