package io.github.rwx.benchmark

import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.pipeline.*
import de.fabmax.kool.pipeline.backend.vk.RenderBackendVk
import de.fabmax.kool.platform.Lwjgl3Context
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.OrthographicCamera
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.util.Time
import de.fabmax.kool.util.Viewport
import io.github.rwx.kool.vulkan.KoolCanvasGpuMapCellOracle
import io.github.rwx.kool.vulkan.VulkanFrameLifecycle
import io.github.rwx.render.canvas.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/** Correctness only: sequential independent references for one unchanged, retained real frame. */
internal object RealReplayCellReference {
    private const val TOLERANCE = 3
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    // Nullable inline-class fields are unboxed to String in Slot / GpuTarget JVM layouts.
    private fun reflectedId(owner: Any, name: String): String? = when (val value = field(owner, name)) {
        null -> null
        is String -> value
        is KoolCanvasTextureId -> value.value
        else -> error("Unexpected $name representation: ${value.javaClass.name}: $value")
    }

    suspend fun verify(context: Lwjgl3Context, host: KoolCanvasSceneHost, envelope: FrameEnvelope, output: File): JsonObject =
        verifyInternal(context, host, envelope, output, requireAtlasReference = true)

    /** Prepared tiles use their original source textures; this does not test atlas sharing. */
    suspend fun verifyPreparedMapCells(context: Lwjgl3Context, host: KoolCanvasSceneHost, envelope: FrameEnvelope, output: File): JsonObject =
        verifyInternal(context, host, envelope, output, requireAtlasReference = false)

    private suspend fun verifyInternal(context: Lwjgl3Context, host: KoolCanvasSceneHost, envelope: FrameEnvelope,
        output: File, requireAtlasReference: Boolean): JsonObject {
        val scene = checkNotNull(host.scene)
        val targets = checkNotNull(field(host, "gpuTargetPasses"))
        val direct = field(targets, "directTargetTextures") == true
        val sharedPixels = field(targets, "sharedAtlasPixels")
        if (requireAtlasReference) check(direct || sharedPixels != null) { "Cell reference requires an actual direct/pixel-sharing candidate" }
        fun poolCount(name: String): Long = if (sharedPixels == null) 0L else field(sharedPixels, name) as Long
        if (requireAtlasReference && sharedPixels != null) check(poolCount("reuseHits") > 0) { "Atlas pixel pool was never actually reused" }
        fun checkRetainedFront() {
            if (!requireAtlasReference) {
                val front = checkNotNull(field(host, "currentEnvelope") as? FrameEnvelope)
                check(front.sequence == envelope.sequence && front.resourceLease === envelope.resourceLease &&
                    !envelope.resourceLease.isReleased) { "Prepared-cell reference lost the unchanged retained host lease" }
            }
        }
        checkRetainedFront()
        @Suppress("UNCHECKED_CAST")
        val installed = field(targets, "installed") as Map<KoolCanvasTextureId, Any>
        val resources = envelope.resourceLease.javaClass.methods.single { it.name.startsWith("resources$") }
            .invoke(envelope.resourceLease) as Map<*, *>
        val cells = resources.entries.filter { it.value?.javaClass?.simpleName == "GpuTarget" }.sortedBy { (it.key as KoolCanvasTextureId).value }
        check(cells.isNotEmpty()) { "No frozen real GPU targets to compare" }
        val fonts = field(envelope.resourceLease, "fonts")
        val fontScope = KoolCanvasFontRegistry.javaClass.declaredMethods.single { it.name.startsWith("withSnapshot") }
            .apply { isAccessible = true }
        val releaseMeshes = KoolCanvasFrameRenderer::class.java.methods.single { it.name.startsWith("releaseCachedMeshes$") }
        val reports = mutableListOf<JsonObject>()
        var checkedPixels = 0L; var mismatches = 0L; var maxDelta = 0; var atlasPages = 0; var atlasMeshes = 0; var atlasUploads = 0
        var gpuAtlasMeshes = 0
        var renderedGpuMeshes = 0; var nonTransparentPixels = 0L
        var firstNonTransparentRgb: Int? = null; var differentNonTransparentRgb = false
        var complete = false
        var failureMessage: String? = null
        fun report() = buildJsonObject {
            put("correctnessOnly", true); put("performanceInvalid", true); put("completed", complete)
            put("passed", complete && mismatches == 0L && if (requireAtlasReference)
                atlasPages > 0 && atlasMeshes > 0 && atlasUploads > 0 && gpuAtlasMeshes > 0
                else renderedGpuMeshes > 0 && nonTransparentPixels > 0 && differentNonTransparentRgb)
            put("referenceMode", if (requireAtlasReference) "sprite-atlas" else "prepared-map-source-textures")
            put("sequence", envelope.sequence); put("simulationTick", envelope.simulationTick); put("tolerance", TOLERANCE)
            put("contextMsaaSamples", context.config.numSamples); put("directTargetTexturesVerified", direct)
            put("sharedAtlasPixelsVerified", requireAtlasReference && sharedPixels != null)
            put("sharedAtlasPixelsAvailable", sharedPixels != null)
            put("atlasPixelInternRequests", poolCount("internRequests")); put("atlasPixelReuseHits", poolCount("reuseHits"))
            put("atlasPixelUploadRequests", poolCount("uploadRequests")); put("atlasPixelUploadReuseHits", poolCount("uploadReuseHits"))
            put("expectedCells", cells.size); put("comparedCells", reports.size); put("checkedPixels", checkedPixels)
            put("mismatches", mismatches); put("maxChannelDelta", maxDelta); put("referenceAtlasPages", atlasPages); put("referenceAtlasMeshes", atlasMeshes)
            put("referenceUploadedAtlasTextures", atlasUploads)
            put("referenceRenderedGpuAtlasMeshes", gpuAtlasMeshes)
            put("referenceRenderedGpuMeshes", renderedGpuMeshes); put("referenceNonTransparentPixels", nonTransparentPixels)
            put("referenceNonTransparentRgbDiverse", differentNonTransparentRgb)
            failureMessage?.let { put("failure", it) }
            put("cells", JsonArray(reports)); put("scope", "Frozen real map-cell attachments only; no final-screen correctness or FPS claim")
        }
        try {
            for ((index, entry) in cells.withIndex()) {
                checkRetainedFront()
                val id = entry.key as KoolCanvasTextureId
                val resource = checkNotNull(entry.value)
                val frame = resource.javaClass.getMethod("getFrame").invoke(resource) as KoolCanvasFrame
                val width = resource.javaClass.getMethod("getWidth").invoke(resource) as Int
                val height = resource.javaClass.getMethod("getHeight").invoke(resource) as Int
                val slot = checkNotNull(installed[id]) { "Host no longer owns frozen target ${id.value}" }
                val version = reflectedId(slot, "versionId")
                val logical = reflectedId(slot, "logicalId")
                val expectedLogical = reflectedId(resource, "logicalId")
                val aliases = installed.filterValues { it === slot }.keys.map { it.value }.sorted()
                val recorded = field(slot, "recordedFrame") as? KoolCanvasFrame
                val provenance = "id=${id.value} slotVersion=$version rawVersionType=${field(slot, "versionId")?.javaClass?.name} " +
                    "logical=$logical expectedLogical=$expectedLogical aliases=$aliases recordedFrameEquals=${recorded == frame} " +
                    "recordedCommands=${recorded?.commands?.size} frozenCommands=${frame.commands.size}"
                // A slot reaches acquireSlot only after releaseVersion removes its old installed key
                // and its fence retires. A live old-key alias must fail, not silently use newer pixels.
                check(version == id.value && logical == expectedLogical && expectedLogical != null &&
                    aliases == listOf(id.value) && recorded == frame) { "Frozen GPU target provenance mismatch: $provenance" }
                check(field(checkNotNull(field(slot, "renderer")), "useSpriteAtlas") == !direct)
                val actual = field(slot, "colour") as Texture2d
                val candidatePass = field(slot, "pass") as OffscreenPass2d
                val attachment = actual.gpuTexture
                check(field(slot, "released") == false && field(slot, "attachedScene") === scene &&
                    field(slot, "width") == width && field(slot, "height") == height &&
                    !candidatePass.isReleased && candidatePass.colorTexture === actual && !actual.isReleased && attachment != null &&
                    candidatePass.width == width && candidatePass.height == height &&
                    KoolCanvasTextureRegistry.isGpuTargetTextureReady(id) &&
                    KoolCanvasTextureRegistry.resolve(KoolCanvasTextureRef(id, width, height), KoolCanvasTextureFilter.Linear) === actual) {
                    "Frozen GPU target attachment mismatch: $provenance pass=${candidatePass.name} " +
                        "passSize=${candidatePass.width}x${candidatePass.height} frozenSize=${width}x$height texture=${actual.name} " +
                        "slotReleased=${field(slot, "released")} textureReleased=${actual.isReleased} imagePresent=${attachment != null}"
                }
                val node = Node("real-replay-reference-cell-$index")
                val camera = OrthographicCamera(node.name)
                val renderer = KoolCanvasFrameRenderer(useSpriteAtlas = requireAtlasReference, meshIdentityIgnoresRecordingOwner = true,
                    meshRetentionRenders = 0, reuseInstancedTextureShaders = false, reuseMapMeshSlots = false)
                check(field(renderer, "useSpriteAtlas") == requireAtlasReference)
                // Production cell passes default to one sample even while the context/swapchain is MSAA4.
                // Match the actual attachment so the comparison isolates direct versus atlas sampling.
                val pass = OffscreenPass2d(node, AttachmentConfig.singleColorNoDepth(TexFormat.RGBA),
                    Vec2i(width, height), node.name, numSamples = candidatePass.numSamples).apply {
                    this.camera = camera; isMirrorY = false; viewport = Viewport(0, 0, width, height)
                }
                try {
                    val draw: () -> Unit = { renderer.renderInto(node, camera, frame) { pass.colorAttachments.single().clearColor = it } }
                    fontScope.invoke(KoolCanvasFontRegistry, fonts, draw)
                    val allocatedPages = if (requireAtlasReference)
                        field(checkNotNull(field(renderer, "spriteAtlas")), "pages") as List<*> else emptyList<Any>()
                    val pages = allocatedPages.size
                    val meshes = if (requireAtlasReference) node.children.filterIsInstance<Mesh<*>>().count {
                        it.isVisible && it.name.startsWith("rwx-canvas-sprites-") } else 0
                    atlasPages += pages; atlasMeshes += meshes
                    scene.addOffscreenPass(pass)
                    // Allow cold mesh/atlas pipelines to settle, using the real render loop.
                    val until = Time.frameCount + 5
                    while (Time.frameCount < until) delay(5L)
                    val reference = KoolCanvasGpuMapCellOracle.downloadFull(scene, checkNotNull(pass.colorTexture), width, height)
                    val candidate = KoolCanvasGpuMapCellOracle.downloadFull(scene, actual, width, height)
                    checkRetainedFront()
                    check(installed[id] === slot && reflectedId(slot, "versionId") == id.value &&
                        field(slot, "recordedFrame") == frame && actual.gpuTexture === attachment) {
                        "Frozen GPU target changed during reference readback: $provenance currentVersion=${reflectedId(slot, "versionId")}"
                    }
                    val uploaded = allocatedPages.sumOf { page -> (field(checkNotNull(page), "textures") as Map<*, *>).values.count {
                        (it as Texture2d).gpuTexture != null && !it.isReleased
                    } }
                    atlasUploads += uploaded
                    val gpuMeshes = if (requireAtlasReference) node.children.filterIsInstance<Mesh<*>>().count { mesh ->
                        mesh.name.startsWith("rwx-canvas-sprites-") && mesh.isRendered && !mesh.geometry.isEmpty() &&
                            (mesh.instances?.numInstances ?: 0) > 0 && mesh.shader!!.texture2d("spriteMap").get()?.let {
                                it.name.startsWith("rwx-sprite-atlas-") && it.gpuTexture != null && !it.isReleased
                            } == true
                    } else 0
                    gpuAtlasMeshes += gpuMeshes
                    val sourceGpuMeshes = if (requireAtlasReference) 0 else node.children.filterIsInstance<Mesh<*>>().count { mesh ->
                        mesh.isVisible && mesh.isRendered && !mesh.geometry.isEmpty() && mesh.geometry.numIndices > 0 &&
                            mesh.geometry.gpuGeometry != null && (mesh.instances == null || mesh.instances!!.numInstances > 0)
                    }
                    renderedGpuMeshes += sourceGpuMeshes
                    check(reference.size == width * height && candidate.size == reference.size)
                    var bad = 0; var largest = 0
                    for (pixel in reference.indices) {
                        if (!requireAtlasReference && reference[pixel] ushr 24 != 0) {
                            nonTransparentPixels++
                            val rgb = reference[pixel] and 0xffffff
                            if (firstNonTransparentRgb == null) firstNonTransparentRgb = rgb
                            else if (firstNonTransparentRgb != rgb) differentNonTransparentRgb = true
                        }
                        var differs = false
                        for (channel in 0..3) {
                            val delta = abs((reference[pixel] ushr (channel * 8) and 255) - (candidate[pixel] ushr (channel * 8) and 255))
                            largest = maxOf(largest, delta); if (delta > TOLERANCE) differs = true
                        }
                        if (differs) bad++
                    }
                    checkedPixels += reference.size; mismatches += bad; maxDelta = maxOf(maxDelta, largest)
                    reports += buildJsonObject {
                        put("id", id.value); put("width", width); put("height", height); put("commands", frame.commands.size)
                        put("slotVersion", version); put("logicalId", logical); put("installedSlotAliases", JsonArray(aliases.map(::JsonPrimitive)))
                        put("recordedFrameMatchesFrozen", true); put("hostAttachmentIdentityVerified", true)
                        put("checkedPixels", reference.size); put("mismatches", bad); put("maxChannelDelta", largest)
                        put("actualSamples", candidatePass.numSamples); put("referenceSamples", pass.numSamples)
                        put("referenceAtlasPages", pages); put("referenceAtlasMeshes", meshes)
                        put("referenceUploadedAtlasTextures", uploaded)
                        put("referenceRenderedGpuAtlasMeshes", gpuMeshes)
                        put("referenceRenderedGpuMeshes", sourceGpuMeshes)
                    }
                    if (bad > 0) {
                        for ((label, pixels) in listOf("actual" to candidate, "reference" to reference)) {
                            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                            image.setRGB(0, 0, width, height, pixels, 0, width)
                            ImageIO.write(image, "png", output.resolve("cell-$index-$label.png"))
                        }
                        output.resolve("cell-$index-commands.txt").writeText("id=${id.value}\n" + frame.commands.joinToString("\n"))
                    }
                } finally {
                    pass.isEnabled = false; scene.removeOffscreenPass(pass)
                    // Finish each reference before allocating the next 2048x2048 atlas page. Flush the
                    // outer atlas fence before Texture.releaseDelayed(1), then pump real frames.
                    val backend = context.backend as RenderBackendVk
                    backend.device.waitForIdle(); VulkanFrameLifecycle.deviceIdle(backend, false)
                    releaseMeshes.invoke(renderer, node); pass.release(); node.release()
                    val until = Time.frameCount + 3
                    while (Time.frameCount < until) delay(5L)
                }
            }
            complete = true
        } catch (error: Throwable) { failureMessage = error.toString(); throw error }
        finally { output.resolve("cell-reference.json").writeText(Json.encodeToString(JsonObject.serializer(), report())) }
        check(mismatches == 0L) { "Real replay cell reference differs at $mismatches pixels; see cell-reference.json and PNGs" }
        if (requireAtlasReference) {
            check(atlasPages > 0 && atlasMeshes > 0 && atlasUploads > 0 && gpuAtlasMeshes > 0) { "Reference never actually rendered a GPU sprite-atlas texture/mesh/page" }
        } else {
            check(renderedGpuMeshes > 0 && nonTransparentPixels > 0 && differentNonTransparentRgb) {
                "Prepared-cell reference needs real nonempty GPU meshes and nontransparent, non-monochrome pixels"
            }
        }
        return report()
    }
}
