package io.github.rwx.kool.vulkan

import de.fabmax.kool.KoolSystem
import de.fabmax.kool.pipeline.BindGroupData
import de.fabmax.kool.pipeline.DrawCommand
import de.fabmax.kool.pipeline.backend.vk.PassEncoderState
import de.fabmax.kool.util.Time
import kotlinx.serialization.json.*
import java.io.File

/** Opt-in draw-time evidence for live cell failures; never enabled in performance runs. */
object VulkanGpuTargetDrawDiagnostics {
    private val enabled = System.getenv("RWX_REAL_SCENE_CELL_ORACLE") == "1"
    private val writer by lazy {
        File(checkNotNull(System.getenv("RWX_REAL_SCENE_ORACLE")), "gpu-target-draw.ndjson")
            .apply { parentFile.mkdirs() }.bufferedWriter( bufferSize = 65536).also { output ->
                KoolSystem.requireContext().onShutdown += { output.close() }
            }
    }
    @JvmStatic
    fun record(bound: Boolean, command: DrawCommand, encoder: PassEncoderState) {
        if (!enabled || !encoder.renderPass.name.startsWith("rwx-gpu-target-")) return
        val pipeline = command.pipeline
        val groups = listOf(command.queue.view.viewPipelineData.getPipelineData(pipeline),
            pipeline.capturedPipelineData, command.mesh.meshPipelineData.getPipelineData(pipeline))
        writer.write(buildJsonObject {
            put("frame", Time.frameCount); put("lane", encoder.frameIndex); put("slot", encoder.renderPass.name)
            put("mesh", command.mesh.name); put("group", command.drawGroupId); put("bound", bound)
            put("instances", command.instanceData?.numInstances ?: -1)
            put("bindings", JsonArray(groups.flatMap { group -> group.bufferedBindings.map { binding -> buildJsonObject {
                put("name", binding.name)
                when (binding) {
                    is BindGroupData.UniformBufferBindingData<*> -> {
                        val bytes = binding.buffer.buffer
                        put("values", JsonArray((0 until bytes.limit / 4).map { JsonPrimitive(bytes.getFloat32(it * 4)) }))
                    }
                    is BindGroupData.TextureBindingData<*> -> {
                        put("texture", binding.texture?.name); put("loaded", binding.texture?.isLoaded ?: false)
                    }
                    else -> {}
                }
            } } }))
        }.toString())
        writer.newLine()
    }
}
