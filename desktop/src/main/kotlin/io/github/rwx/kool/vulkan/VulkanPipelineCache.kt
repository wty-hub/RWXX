package io.github.rwx.kool.vulkan

import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.IdentityHashMap

/** A native cache shared by every Kool draw / compute pipeline on one logical device. */
object VulkanPipelineCache {
    private val devices = IdentityHashMap<VkDevice, Cache>()

    @JvmStatic
    fun vkCreateGraphicsPipelines(
        device: VkDevice,
        ignoredCache: Long,
        createInfo: VkGraphicsPipelineCreateInfo.Buffer,
        allocator: VkAllocationCallbacks?,
        pipelines: LongBuffer,
    ): Int {
        val start = VulkanBackendMetrics.stageStart()
        try { return VK10.vkCreateGraphicsPipelines(device, cache(device).handle, createInfo, allocator, pipelines) }
        finally { VulkanBackendMetrics.stageEnd("graphics-pipeline-create", start, createInfo.remaining().toLong()) }
    }

    @JvmStatic
    fun vkCreateComputePipelines(
        device: VkDevice,
        ignoredCache: Long,
        createInfo: VkComputePipelineCreateInfo.Buffer,
        allocator: VkAllocationCallbacks?,
        pipelines: LongBuffer,
    ): Int {
        val start = VulkanBackendMetrics.stageStart()
        try { return VK10.vkCreateComputePipelines(device, cache(device).handle, createInfo, allocator, pipelines) }
        finally { VulkanBackendMetrics.stageEnd("compute-pipeline-create", start, createInfo.remaining().toLong()) }
    }

    internal fun release(device: VkDevice) {
        val cache = devices.remove(device) ?: return
        if (cache.handle == VK_NULL_HANDLE) return
        try {
            val bytes = getCacheData(device, cache.handle)
            if (bytes != null && cache.identity.accepts(bytes)) {
                cache.file.parent?.let(Files::createDirectories)
                val temp = Files.createTempFile(cache.file.parent, "pipelines-", ".tmp")
                try {
                    Files.write(temp, bytes)
                    try {
                        Files.move(temp, cache.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(temp, cache.file, StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(temp)
                }
            }
        } catch (failure: Exception) {
            // A read-only cache directory must not prevent device teardown or game startup.
            System.err.println("RWX Vulkan pipeline cache could not be saved: ${failure.message}")
        } finally {
            vkDestroyPipelineCache(device, cache.handle, null)
        }
    }

    private fun cache(device: VkDevice): Cache = devices.getOrPut(device) {
        val identity = MemoryStack.stackPush().use { stack ->
            val properties = VkPhysicalDeviceProperties.calloc(stack)
            vkGetPhysicalDeviceProperties(device.physicalDevice, properties)
            val uuid = ByteArray(VK_UUID_SIZE) { properties.pipelineCacheUUID(it) }
            PipelineCacheIdentity(properties.vendorID(), properties.deviceID(), properties.driverVersion(), properties.apiVersion(), uuid)
        }
        val directory = System.getProperty("rwx.vulkan.pipelineCacheDirectory")?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".cache", "RWX", "vulkan", "pipelines")
        val file = directory.resolve("${identity.fileKey}.bin")
        val saved = try {
            if (Files.isRegularFile(file) && Files.size(file) in PipelineCacheIdentity.HEADER_BYTES.toLong()..MAX_CACHE_BYTES.toLong()) {
                Files.readAllBytes(file).takeIf(identity::accepts)
            } else null
        } catch (_: Exception) { null }
        val handle = createCache(device, saved) ?: createCache(device, null) ?: VK_NULL_HANDLE
        Cache(handle, identity, file)
    }

    private fun createCache(device: VkDevice, saved: ByteArray?): Long? {
        val data = saved?.let { MemoryUtil.memAlloc(it.size).put(it).flip() }
        try {
            MemoryStack.stackPush().use { stack ->
                val info = VkPipelineCacheCreateInfo.calloc(stack).`sType$Default`()
                if (data != null) info.pInitialData(data)
                val handle = stack.mallocLong(1)
                return if (vkCreatePipelineCache(device, info, null, handle) == VK_SUCCESS) handle[0] else null
            }
        } finally {
            if (data != null) MemoryUtil.memFree(data)
        }
    }

    private fun getCacheData(device: VkDevice, handle: Long): ByteArray? {
        MemoryStack.stackPush().use { stack ->
            val size = stack.mallocPointer(1)
            repeat(3) {
                if (vkGetPipelineCacheData(device, handle, size, null) != VK_SUCCESS) return null
                val capacity = size[0]
                if (capacity !in PipelineCacheIdentity.HEADER_BYTES.toLong()..MAX_CACHE_BYTES.toLong()) return null
                val data = MemoryUtil.memAlloc(capacity.toInt())
                try {
                    when (vkGetPipelineCacheData(device, handle, size, data)) {
                        VK_SUCCESS -> return ByteArray(size[0].toInt()).also { bytes -> data.get(bytes) }
                        VK_INCOMPLETE -> { /* size changed; query again with a larger buffer */ }
                        else -> return null
                    }
                } finally { MemoryUtil.memFree(data) }
            }
        }
        return null
    }

    private data class Cache(val handle: Long, val identity: PipelineCacheIdentity, val file: Path)
    private const val MAX_CACHE_BYTES = 64 * 1024 * 1024
}

internal class PipelineCacheIdentity(
    private val vendorId: Int,
    private val deviceId: Int,
    private val driverVersion: Int,
    private val apiVersion: Int,
    uuid: ByteArray,
) {
    private val uuid = uuid.copyOf()
    init { require(this.uuid.size == VK_UUID_SIZE) }

    val fileKey: String get() = "$vendorId-$deviceId-$driverVersion-$apiVersion-" +
        uuid.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun accepts(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_BYTES) return false
        // Vulkan cache version-one header fields are defined as little endian, independent of host.
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val length = header.int
        if (length !in HEADER_BYTES..bytes.size || header.int != VK_PIPELINE_CACHE_HEADER_VERSION_ONE) return false
        if (header.int != vendorId || header.int != deviceId) return false
        return uuid.indices.all { header.get(16 + it) == uuid[it] }
    }

    companion object { const val HEADER_BYTES = 32 }
}
