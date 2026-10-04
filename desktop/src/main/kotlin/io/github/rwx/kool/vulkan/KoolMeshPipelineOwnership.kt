package io.github.rwx.kool.vulkan

import de.fabmax.kool.pipeline.DrawPipeline
import de.fabmax.kool.scene.Mesh

/** A Mesh owns its selected pipeline before the current DrawCommand is captured. */
object KoolMeshPipelineOwnership {
    @JvmStatic
    fun retain(pipeline: DrawPipeline?, mesh: Mesh<*>): DrawPipeline? {
        // Kool's user set makes this idempotent with captureData's existing addUser. Shader
        // replacement and Mesh.doRelease already remove exactly this Mesh from the pipeline.
        pipeline?.addUser(mesh)
        return pipeline
    }
}
