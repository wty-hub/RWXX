package io.github.rwx.render.canvas

import de.fabmax.kool.scene.MeshInstanceList
import de.fabmax.kool.util.StructBuffer

/** Scalar writes for the fixed sprite layout; publication still happens in the renderer's finish pass. */
internal object KoolCanvasSpriteInstanceWriter {
    // Derive the ABI from Kool once, rather than looking up struct members for every sprite.
    private val strideBytes = KoolCanvasSpriteInstanceLayout.structSize
    private val originAxisXOffset = KoolCanvasSpriteInstanceLayout.originAxisX.byteOffset
    private val axisYModeAmountOffset = KoolCanvasSpriteInstanceLayout.axisYModeAmount.byteOffset
    private val uvOffset = KoolCanvasSpriteInstanceLayout.uv.byteOffset
    private val tintOffset = KoolCanvasSpriteInstanceLayout.tint.byteOffset
    private val teamOffset = KoolCanvasSpriteInstanceLayout.team.byteOffset
    private val clipOffset = KoolCanvasSpriteInstanceLayout.clip.byteOffset

    fun append(
        instances: MeshInstanceList<KoolCanvasSpriteInstanceLayout>,
        originX: Float, originY: Float, axisXx: Float, axisXy: Float,
        axisYx: Float, axisYy: Float, mode: Float, amount: Float,
        u0: Float, v0: Float, u1: Float, v1: Float,
        tintR: Float, tintG: Float, tintB: Float, tintA: Float,
        teamR: Float, teamG: Float, teamB: Float, teamA: Float,
        clipLeft: Float, clipBottom: Float, clipRight: Float, clipTop: Float,
    ) {
        val data = instances.instanceData
        if (data.limit >= data.capacity) {
            // Keep Kool's growth, old-data copy, non-resizable failure and mod-counter contract.
            instances.addInstances(1) { grown ->
                write(grown, originX, originY, axisXx, axisXy, axisYx, axisYy, mode, amount,
                    u0, v0, u1, v1, tintR, tintG, tintB, tintA, teamR, teamG, teamB, teamA,
                    clipLeft, clipBottom, clipRight, clipTop)
            }
        } else {
            // The existing sprite finish pass increments modCount once after all writes.
            write(data, originX, originY, axisXx, axisXy, axisYx, axisYy, mode, amount,
                u0, v0, u1, v1, tintR, tintG, tintB, tintA, teamR, teamG, teamB, teamA,
                clipLeft, clipBottom, clipRight, clipTop)
        }
    }

    private fun write(
        data: StructBuffer<KoolCanvasSpriteInstanceLayout>,
        originX: Float, originY: Float, axisXx: Float, axisXy: Float,
        axisYx: Float, axisYy: Float, mode: Float, amount: Float,
        u0: Float, v0: Float, u1: Float, v1: Float,
        tintR: Float, tintG: Float, tintB: Float, tintA: Float,
        teamR: Float, teamG: Float, teamB: Float, teamA: Float,
        clipLeft: Float, clipBottom: Float, clipRight: Float, clipTop: Float,
    ) {
        val index = data.limit
        data.limit = index + 1
        val base = index * strideBytes
        val buffer = data.buffer
        var offset = base + originAxisXOffset
        buffer.setFloat32(offset, originX)
        buffer.setFloat32(offset + 4, originY)
        buffer.setFloat32(offset + 8, axisXx)
        buffer.setFloat32(offset + 12, axisXy)
        offset = base + axisYModeAmountOffset
        buffer.setFloat32(offset, axisYx)
        buffer.setFloat32(offset + 4, axisYy)
        buffer.setFloat32(offset + 8, mode)
        buffer.setFloat32(offset + 12, amount)
        offset = base + uvOffset
        buffer.setFloat32(offset, u0)
        buffer.setFloat32(offset + 4, v0)
        buffer.setFloat32(offset + 8, u1)
        buffer.setFloat32(offset + 12, v1)
        offset = base + tintOffset
        buffer.setFloat32(offset, tintR)
        buffer.setFloat32(offset + 4, tintG)
        buffer.setFloat32(offset + 8, tintB)
        buffer.setFloat32(offset + 12, tintA)
        offset = base + teamOffset
        buffer.setFloat32(offset, teamR)
        buffer.setFloat32(offset + 4, teamG)
        buffer.setFloat32(offset + 8, teamB)
        buffer.setFloat32(offset + 12, teamA)
        offset = base + clipOffset
        buffer.setFloat32(offset, clipLeft)
        buffer.setFloat32(offset + 4, clipBottom)
        buffer.setFloat32(offset + 8, clipRight)
        buffer.setFloat32(offset + 12, clipTop)
    }
}
