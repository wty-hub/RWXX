package io.github.rwx.kool.vulkan

import de.fabmax.kool.math.float32ToFloat16
import de.fabmax.kool.math.numMipLevels
import de.fabmax.kool.pipeline.*
import de.fabmax.kool.pipeline.backend.vk.*
import de.fabmax.kool.util.*
import io.github.rwx.render.canvas.KoolCanvasBgraImageData
import org.lwjgl.system.MemoryStack
import org.lwjgl.util.vma.Vma.vmaFlushAllocation
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkBufferCopy
import org.lwjgl.vulkan.VkBufferImageCopy
import org.lwjgl.vulkan.VkBufferMemoryBarrier
import org.lwjgl.vulkan.VkCommandBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Main-command-buffer transfers, called from the version-checked Kool 0.19 overlay. */
object VulkanUploads {
    @JvmStatic
    fun prepareTextures(command: DrawCommand, encoder: PassEncoderState) {
        val pipeline = command.pipeline
        prepareGroupTextures(pipeline.capturedPipelineData, encoder)
        prepareGroupTextures(command.queue.view.viewPipelineData.getPipelineData(pipeline), encoder)
        prepareGroupTextures(command.mesh.meshPipelineData.getPipelineData(pipeline), encoder)
    }

    private fun prepareGroupTextures(group: BindGroupData, encoder: PassEncoderState) {
        group.bufferedBindings.forEach { binding ->
            if (binding is BindGroupData.TextureBindingData<*>) {
                binding.texture?.let { texture ->
                    if (texture.uploadData != null) encoder.backend.textureLoader.loadTexture(texture)
                }
            }
        }
    }

    @JvmStatic
    fun writeData(dst: GrowingBufferVk, data: Float32Buffer, commandBuffer: VkCommandBuffer) {
        val bytes = Math.multiplyExact(data.limit, 4)
        writeBuffer(dst, bytes, commandBuffer) { target -> data.useRaw { target.asFloatBuffer().put(it) } }
    }

    @JvmStatic
    fun writeData(dst: GrowingBufferVk, data: Int32Buffer, commandBuffer: VkCommandBuffer) {
        val bytes = Math.multiplyExact(data.limit, 4)
        writeBuffer(dst, bytes, commandBuffer) { target -> data.useRaw { target.asIntBuffer().put(it) } }
    }

    @JvmStatic
    fun writeData(dst: GrowingBufferVk, data: MixedBuffer, commandBuffer: VkCommandBuffer) {
        // MixedBuffer and StructBuffer are byte-addressed; multiplying by four corrupts the copy size.
        writeBuffer(dst, data.limit, commandBuffer) { target -> data.useRaw { target.put(it) } }
    }

    private inline fun writeBuffer(
        dst: GrowingBufferVk,
        bytes: Int,
        commandBuffer: VkCommandBuffer,
        copy: (ByteBuffer) -> Unit,
    ) {
        if (bytes == 0) return
        val state = VulkanFrameLifecycle.stateFor(dst.backend)
        val startedAt = if (VulkanBackendMetrics.enabled) System.nanoTime() else 0L
        check(state.commandBuffer === commandBuffer) { "Vulkan buffer upload outside the acquired frame" }
        state.injectUploadDelayOnce()
        val slice = state.allocate(bytes)
        copy(slice.mapped)
        state.flush(slice)
        MemoryStack.stackPush().use { stack ->
            val barriers = VkBufferMemoryBarrier.calloc(1, stack)
            barriers[0].set(
                VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER, 0L,
                VK_ACCESS_MEMORY_READ_BIT or VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED,
                dst.buffer.vkBuffer.handle, 0L, bytes.toLong(),
            )
            // The destination may have been read by a preceding frame in this same graphics queue.
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, null, barriers, null)
            val region = VkBufferCopy.calloc(1, stack)
            region[0].set(slice.offset.toLong(), 0L, bytes.toLong())
            vkCmdCopyBuffer(commandBuffer, slice.buffer.handle, dst.buffer.vkBuffer.handle, region)
            barriers[0].srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT)
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0, null, barriers, null)
        }
        state.bufferUploadBytes += bytes
        if (startedAt != 0L) {
            val uploadEnd = System.nanoTime()
            state.uploadNanos += uploadEnd - startedAt
            // Keep the trace small: small ordinary buffer transfers are summarized at submission.
            if (uploadEnd - startedAt >= 1_000_000L) VulkanBackendMetrics.stageEnd("buffer-upload", startedAt, bytes.toLong())
        }
    }


    @JvmStatic
    fun loadTexture(
        loader: TextureLoaderVk,
        tex: Texture<*>,
        type: Int,
        data: ImageData,
        width: Int,
        height: Int,
        depth: Int,
        layers: Int,
        mipMapping: MipMapping,
        flags: Int,
    ): ImageVk {
        val loadStart = VulkanBackendMetrics.stageStart()
        val levels = when (mipMapping) {
            MipMapping.Full -> numMipLevels(width, height, depth)
            is MipMapping.Limited -> mipMapping.numLevels
            MipMapping.Off -> 1
        }
        val image = ImageVk(loader.backend, ImageInfo(
            imageType = type, format = imageVkFormat(data, type, depth, layers, mipMapping, flags),
            width = width, height = height,
            depth = depth, arrayLayers = layers, mipLevels = levels, samples = VK_SAMPLE_COUNT_1_BIT,
            usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT or
                (if (mipMapping.isMipMapped) VK_IMAGE_USAGE_TRANSFER_SRC_BIT else 0),
            label = tex.name, aspectMask = VK_IMAGE_ASPECT_COLOR_BIT, flags = flags,
        ))
        val bytes = checkedTextureBytes(width, height, depth, layers, data.format)
        val state = VulkanFrameLifecycle.stateFor(loader.backend)
        if (state.commandBuffer != null) {
            state.recordImage(image, bytes, mipMapping.isMipMapped) { copyImageData(data, it) }
        } else {
            // Scene collection can upload before acquire; freeze payload until a real frame is begun.
            val payload = ByteArray(bytes)
            val target = ByteBuffer.wrap(payload).order(ByteOrder.nativeOrder())
            copyImageData(data, target)
            check(target.position() == bytes) { "Incomplete image upload: ${target.position()} != $bytes" }
            state.pendingImages.removeAll { it.owner.isReleased || it.image.isReleased }
            state.pendingImages += PendingImageUpload(tex, image, payload, mipMapping.isMipMapped)
        }
        VulkanBackendMetrics.stageEnd("texture-load", loadStart, bytes.toLong(), width.toLong(), height.toLong())
        return image
    }

    internal fun checkedTextureBytes(width: Int, height: Int, depth: Int, layers: Int, format: TexFormat): Int {
        require(width > 0 && height > 0 && depth > 0 && layers > 0)
        val channelSize = if (format.isByte) 1 else if (format.isF16) 2 else 4
        val pixelSize = if (format == TexFormat.RG11B10_F) 4 else Math.multiplyExact(format.channels, channelSize)
        var size = Math.multiplyExact(width.toLong(), height.toLong())
        size = Math.multiplyExact(size, depth.toLong())
        size = Math.multiplyExact(size, layers.toLong())
        size = Math.multiplyExact(size, pixelSize.toLong())
        return Math.toIntExact(size)
    }

    internal fun copyImageData(src: ImageData, target: ByteBuffer) {
        when (src) {
            is KoolCanvasBgraImageData -> copyBuffer(src.data, src.format, target)
            is BufferedImageData -> copyBuffer(src.data, src.format, target)
            is ImageDataCube -> {
                listOf(src.posX, src.negX, src.posY, src.negY, src.posZ, src.negZ).forEach { copyImageData(it, target) }
            }
            is ImageDataCubeArray -> src.cubes.forEach { copyImageData(it, target) }
            is ImageData2dArray -> src.images.forEach { copyImageData(it, target) }
            else -> error("Unsupported Vulkan image payload ${src::class.simpleName}")
        }
    }

    internal fun imageVkFormat(
        data: ImageData,
        type: Int,
        depth: Int,
        layers: Int,
        mipMapping: MipMapping,
        flags: Int,
    ): Int {
        if (data is KoolCanvasBgraImageData) {
            require(type == VK_IMAGE_TYPE_2D && depth == 1 && layers == 1 && flags == 0) {
                "Native BGRA canvas payloads require a plain 2d image"
            }
            require(mipMapping == MipMapping.Off) { "Native BGRA canvas payloads cannot generate mipmaps" }
            return VK_FORMAT_B8G8R8A8_UNORM
        }
        return vkFormat(data.format)
    }

    private fun copyBuffer(src: Buffer, format: TexFormat, target: ByteBuffer) {
        when (src) {
            is Uint8Buffer -> src.useRaw { target.put(it) }
            is Uint16Buffer -> src.useRaw { raw ->
                target.asShortBuffer().put(raw)
                target.position(target.position() + src.limit * 2)
            }
            is Int32Buffer -> src.useRaw { raw ->
                target.asIntBuffer().put(raw)
                target.position(target.position() + src.limit * 4)
            }
            is Float32Buffer -> src.useRaw { raw ->
                if (format.isF16) {
                    for (i in 0 until raw.limit()) float32ToFloat16(raw[i]) { high, low ->
                        target.put(low).put(high)
                    }
                } else {
                    target.asFloatBuffer().put(raw)
                    target.position(target.position() + src.limit * 4)
                }
            }
            else -> error("Unsupported Vulkan texture buffer ${src::class.simpleName}")
        }
    }

    private fun vkFormat(format: TexFormat): Int = when (format) {
        TexFormat.R -> VK_FORMAT_R8_UNORM
        TexFormat.RG -> VK_FORMAT_R8G8_UNORM
        TexFormat.RGBA -> VK_FORMAT_R8G8B8A8_UNORM
        TexFormat.R_F16 -> VK_FORMAT_R16_SFLOAT
        TexFormat.RG_F16 -> VK_FORMAT_R16G16_SFLOAT
        TexFormat.RGBA_F16 -> VK_FORMAT_R16G16B16A16_SFLOAT
        TexFormat.R_F32 -> VK_FORMAT_R32_SFLOAT
        TexFormat.RG_F32 -> VK_FORMAT_R32G32_SFLOAT
        TexFormat.RGBA_F32 -> VK_FORMAT_R32G32B32A32_SFLOAT
        TexFormat.R_I32 -> VK_FORMAT_R32_SINT
        TexFormat.RG_I32 -> VK_FORMAT_R32G32_SINT
        TexFormat.RGBA_I32 -> VK_FORMAT_R32G32B32A32_SINT
        TexFormat.R_U32 -> VK_FORMAT_R32_UINT
        TexFormat.RG_U32 -> VK_FORMAT_R32G32_UINT
        TexFormat.RGBA_U32 -> VK_FORMAT_R32G32B32A32_UINT
        TexFormat.RG11B10_F -> VK_FORMAT_B10G11R11_UFLOAT_PACK32
    }
}

internal data class PendingImageUpload(val owner: Texture<*>, val image: ImageVk, val payload: ByteArray, val mipMapped: Boolean)
internal data class UploadSlice(val buffer: VkBuffer, val offset: Int, val mapped: ByteBuffer)

internal class VulkanUploadState(val backend: RenderBackendVk) {
    val retirement = FrameFenceRetirementQueue(Swapchain.MAX_FRAMES_IN_FLIGHT)
    val pendingImages = ArrayList<PendingImageUpload>()
    private val slots = Array(Swapchain.MAX_FRAMES_IN_FLIGHT) { ArrayList<UploadChunk>() }
    private var frameIndex = -1
    private var lastSubmittedSlot: Int? = null
    private var lastSubmittedFence = 0L
    private var encoder: PassEncoderState? = null
    private var uploadDelayInjected = false
    val commandBuffer: VkCommandBuffer? get() = encoder?.commandBuffer
    var bufferUploadBytes = 0L
    var textureUploadBytes = 0L
    var allocationCount = 0L
    var uploadNanos = 0L
    val residentStagingBytes: Long get() = slots.sumOf { chunks -> chunks.sumOf { it.buffer.bufferSize } }

    // Kool's private VMA implementation is pinned and verified by the build-time overlay task.
    private val allocator: Long by lazy {
        val implField = MemoryManager::class.java.getDeclaredField("impl").apply { isAccessible = true }
        val impl = implField.get(backend.memManager)
        impl.javaClass.getDeclaredField("allocator").apply { isAccessible = true }.getLong(impl)
    }

    fun beginFrame(encoder: PassEncoderState) {
        frameIndex = encoder.frameIndex
        retirement.completed(frameIndex)
        val chunks = slots[frameIndex]
        var retained = 0L
        val iter = chunks.iterator()
        while (iter.hasNext()) {
            val chunk = iter.next()
            if (retained + chunk.buffer.bufferSize > RETAINED_SLOT_BYTES) {
                backend.memManager.freeBuffer(chunk.buffer, deferTicks = 0)
                iter.remove()
            } else {
                retained += chunk.buffer.bufferSize
                chunk.cursor.resetAfterFence()
            }
        }
        this.encoder = encoder
        uploadDelayInjected = false
        bufferUploadBytes = 0
        textureUploadBytes = 0
        uploadNanos = 0
        val uploads = pendingImages.toList()
        pendingImages.clear()
        uploads.filterNot { it.owner.isReleased || it.image.isReleased }.forEach { upload ->
            recordImage(upload.image, upload.payload.size, upload.mipMapped) { it.put(upload.payload) }
        }
    }

    fun submitted(slot: Int) {
        retirement.submitted(slot)
        lastSubmittedSlot = slot
        lastSubmittedFence = backend.swapchain.inFlightFence.handle
        encoder = null
    }

    fun retainUntilFrameComplete(release: () -> Unit) {
        if (encoder != null) {
            retirement.retain(release)
            return
        }
        val slot = lastSubmittedSlot
        if (slot == null) {
            release()
        } else if (vkGetFenceStatus(backend.device.vkDevice, lastSubmittedFence) == VK_SUCCESS) {
            // One graphics queue: the latest submission's fence also completes every older reference.
            retirement.deviceIdle()
            release()
        } else {
            retirement.retainAfterSubmitted(slot, release)
        }
    }

    fun allocate(bytes: Int): UploadSlice {
        check(encoder != null && frameIndex >= 0) { "Staging allocation outside an acquired GPU frame" }
        val chunks = slots[frameIndex]
        for (chunk in chunks) {
            val offset = chunk.cursor.allocate(bytes) ?: continue
            return chunk.slice(offset, bytes)
        }
        val capacity = maxOf(INITIAL_CHUNK_BYTES, bytes)
        val buffer = backend.memManager.createBuffer(MemoryInfo(
            size = capacity.toLong(), usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            label = "RWX persistent upload slot $frameIndex", createMapped = true,
        ))
        allocationCount++
        val chunk = UploadChunk(buffer, UploadChunkCursor(capacity))
        chunks += chunk
        return chunk.slice(checkNotNull(chunk.cursor.allocate(bytes)), bytes)
    }

    fun injectUploadDelayOnce() {
        if (!uploadDelayInjected) {
            uploadDelayInjected = true
            VulkanDelayInjection.upload.pause()
        }
    }

    fun flush(slice: UploadSlice) {
        vmaFlushAllocation(allocator, slice.buffer.allocation, slice.offset.toLong(), slice.mapped.capacity().toLong())
    }

    fun recordImage(image: ImageVk, bytes: Int, mipMapped: Boolean, copy: (ByteBuffer) -> Unit) {
        val startedAt = if (VulkanBackendMetrics.enabled) System.nanoTime() else 0L
        val encoder = checkNotNull(encoder)
        check(!encoder.isPassActive) { "Texture upload requested during an active render pass" }
        val commandBuffer = encoder.commandBuffer
        injectUploadDelayOnce()
        val slice = allocate(bytes)
        copy(slice.mapped)
        flush(slice)
        MemoryStack.stackPush().use { stack ->
            image.transitionLayout(image.lastKnownLayout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, commandBuffer, stack = stack)
            val region = VkBufferImageCopy.calloc(1, stack)
            region[0].bufferOffset(slice.offset.toLong()).bufferRowLength(0).bufferImageHeight(0)
            region[0].imageSubresource().set(image.imageInfo.aspectMask, 0, 0, image.arrayLayers)
            region[0].imageOffset().set(0, 0, 0)
            region[0].imageExtent().set(image.width, image.height, image.depth)
            vkCmdCopyBufferToImage(commandBuffer, slice.buffer.handle, image.vkImage.handle,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region)
            if (mipMapped) image.generateMipmaps(stack, commandBuffer)
            else image.transitionLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, commandBuffer, stack = stack)
        }
        textureUploadBytes += bytes
        if (startedAt != 0L) uploadNanos += System.nanoTime() - startedAt
    }

    fun deviceIdle(destroying: Boolean) {
        encoder = null
        lastSubmittedSlot = null
        lastSubmittedFence = 0L
        retirement.deviceIdle()
        slots.forEach { chunks ->
            chunks.forEach { backend.memManager.freeBuffer(it.buffer, deferTicks = 0) }
            chunks.clear()
        }
        if (destroying) pendingImages.clear()
    }

    private class UploadChunk(val buffer: VkBuffer, val cursor: UploadChunkCursor) {
        fun slice(offset: Int, bytes: Int): UploadSlice {
            val mapped = checkNotNull(buffer.mapped).duplicate().order(ByteOrder.nativeOrder())
            mapped.position(offset).limit(offset + bytes)
            return UploadSlice(buffer, offset, mapped.slice().order(ByteOrder.nativeOrder()))
        }
    }

    companion object {
        private const val INITIAL_CHUNK_BYTES = 4 * 1024 * 1024
        private const val RETAINED_SLOT_BYTES = 32L * 1024 * 1024
    }
}
