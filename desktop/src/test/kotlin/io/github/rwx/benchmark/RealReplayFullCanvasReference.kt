package io.github.rwx.benchmark

// TEST ONLY. Pixels alone never make this gate pass.
// Requires post-shutdown finishAfterContextShutdown(): pixels alone never make this gate pass.

import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.pipeline.*
import de.fabmax.kool.pipeline.backend.vk.RenderBackendVk
import de.fabmax.kool.platform.Lwjgl3Context
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.OrthographicCamera
import de.fabmax.kool.util.Time
import de.fabmax.kool.util.Viewport
import io.github.rwx.kool.vulkan.KoolCanvasGpuMapCellOracle
import io.github.rwx.kool.vulkan.VulkanFrameLifecycle
import io.github.rwx.render.canvas.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Frozen complete GAME CANVAS, not outer App UI or a display scanout.
 * Two independent root-style renderers replay the same retained host-installed packet twice.
 * Only reuseInstancedTextureShaders differs. No host renderer is redirected to another Node.
 * All timing/allocation/performance from the containing process is INVALID in this mode.
 */
internal object RealReplayFullCanvasReference {
    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private const val SAMPLES = 4
    private const val TOLERANCE = 0 // Same inputs/backend: require exact RGBA, not an unreported tolerance.
    private const val PREFIX = "rwx-gpu-target-full-canvas-reference-"
    private const val MAX_WITNESSES = 20_000
    private val RGBA_SHIFTS = intArrayOf(16, 8, 0, 24)

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    private fun sha(pixels: IntArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Explicit RGBA order, independent of native byte order / IntArray representation.
        for (pixel in pixels) for (shift in RGBA_SHIFTS) digest.update((pixel ushr shift).toByte())
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun cache(renderer: KoolCanvasFrameRenderer) = field(renderer, "instancedTextureShaders") as Map<*, *>
    private fun instanced(node: Node) = node.children.filterIsInstance<Mesh<*>>().filter {
        it.isVisible && it.isRendered && !it.geometry.isEmpty() && it.geometry.numIndices > 0 &&
            (it.instances?.numInstances ?: 0) > 0 && it.shader is KoolCanvasInstancedTextureShader
    }
    private data class OldBinding(val key: WeakReference<Any>, val shader: WeakReference<Any>)
    private class Arm(val label: String, enabled: Boolean, frozenRates: CanvasFrameRateSample?) {
        val node = Node("full-canvas-reference-$label")
        val camera = OrthographicCamera(node.name)
        // Root defaults retained. The performance HUD input, if present, is frozen identically.
        val renderer = KoolCanvasFrameRenderer(reuseInstancedTextureShaders = enabled,
            performanceRates = { frozenRates })
        val pass = OffscreenPass2d(node, AttachmentConfig.singleColorNoDepth(TexFormat.RGBA),
            Vec2i(WIDTH, HEIGHT), PREFIX + label, numSamples = SAMPLES).apply {
            this.camera = this@Arm.camera
            isMirrorY = false
            isReleaseDrawNode = false // Explicit single node owner in cleanup below.
            viewport = Viewport(0, 0, WIDTH, HEIGHT)
        }
        var attached = false
        var round = -1
        var setupFailure: String? = null
        val witnesses = ArrayList<JsonObject>() // Primitive evidence only; no Shader/Mesh/Pipeline refs.
        var oldBindings: List<OldBinding> = emptyList()
    }

    /** Caller must have stopped generation and reached final adoption + completed native fence. */
    suspend fun verify(context: Lwjgl3Context, host: KoolCanvasSceneHost,
        finalEnvelope: FrameEnvelope, output: File): JsonObject {
        val retained = finalEnvelope.retain()
        return try { verifyRetained(context, host, retained, output) } finally { retained.close() }
    }

    private suspend fun verifyRetained(context: Lwjgl3Context, host: KoolCanvasSceneHost,
        retained: FrameEnvelope, output: File): JsonObject {
        require(context.config.numSamples == SAMPLES)
        require(System.getenv("RWX_REAL_SCENE_CELL_ORACLE") == "1") {
            "Existing native bind-return witness must be enabled for correctness mode"
        }
        require(File(checkNotNull(System.getenv("RWX_REAL_SCENE_ORACLE"))).canonicalFile == output.canonicalFile)
        require(!output.resolve("full-canvas-reference-pending.json").exists())
        val backend = context.backend as RenderBackendVk
        require(!backend.isAsyncRendering) { "Bind-ready Mesh storage proof requires the existing serial adapter" }
        val scene = checkNotNull(host.scene)
        val frame = retained.frame
        check(frame.viewport.width == WIDTH && frame.viewport.height == HEIGHT)
        val frozenRates = CanvasFrameMetrics.snapshot()
        val fonts = field(retained.resourceLease, "fonts")
        val fontScope = KoolCanvasFontRegistry.javaClass.declaredMethods.single { it.name.startsWith("withSnapshot") }
            .apply { isAccessible = true }
        val releaseMeshes = KoolCanvasFrameRenderer::class.java.methods.single { it.name.startsWith("releaseCachedMeshes$") }
        val surfaceClear = Class.forName("io.github.rwx.render.canvas.KoolCanvasSceneHostKt").declaredMethods
            .single { it.name == "withSurfaceClear" }.apply { isAccessible = true }
        val replayFrame = surfaceClear.invoke(null, frame) as KoolCanvasFrame
        val arms = listOf(Arm("off", false, frozenRates), Arm("on", true, frozenRates))
        val rounds = ArrayList<JsonObject>(2)
        var failure: String? = null
        var pixelsPassed = false
        fun checkHost() {
            val front = checkNotNull(field(host, "currentEnvelope") as? FrameEnvelope)
            check(front.sequence == retained.sequence && front.generation == retained.generation &&
                front.frame === frame && front.resourceLease === retained.resourceLease &&
                !front.resourceLease.isReleased && field(host, "preparingEnvelope") == null) {
                "Frozen host packet/resource installation changed during full-canvas reference"
            }
            check(host.scene === scene && !scene.isReleased)
            val resources = retained.resourceLease.javaClass.methods.single { it.name.startsWith("resources$") }
                .invoke(retained.resourceLease) as Map<*, *>
            check(resources.values.none { it?.javaClass?.simpleName == "Missing" })
            val targetIds = resources.filterValues { it?.javaClass?.simpleName == "GpuTarget" }.keys
            check(targetIds.isNotEmpty() && targetIds.all {
                KoolCanvasTextureRegistry.isGpuTargetTextureReady(it as KoolCanvasTextureId)
            }) { "The host must still own all ready frozen GPU target images" }
        }
        fun readyWitness(arm: Arm) {
            if (arm.round < 0) return
            try {
                val meshes = instanced(arm.node)
                for (mesh in meshes) {
                    val shader = mesh.shader as KoolCanvasInstancedTextureShader
                    val pipeline = field(mesh, "pipeline") as? DrawPipeline ?: return
                    if (!mesh.isRendered || mesh.geometry.gpuGeometry == null || pipeline.isReleased ||
                        pipeline !== shader.createdPipeline) return
                    val texture = shader.colorMap ?: return
                    val captured = pipeline.capturedPipelineData.bufferedBindings
                        .filterIsInstance<BindGroupData.Texture2dBindingData>().single().texture
                    if (captured !== texture || texture.isReleased || !texture.isLoaded || texture.gpuTexture == null) return
                }
                // Cold or fully culled geometry cannot prove a draw. Wait, then fail closed on timeout.
                if (meshes.isEmpty()) return
                check(arm.witnesses.size + meshes.size <= MAX_WITNESSES) { "Bounded draw witness capacity exceeded" }
                for (mesh in meshes) arm.witnesses += buildJsonObject {
                    put("round", arm.round); put("frame", Time.frameCount); put("slot", arm.pass.name)
                    put("mesh", mesh.name); put("group", mesh.drawGroupId); put("instances", mesh.instances!!.numInstances)
                }
            } catch (error: Throwable) { arm.setupFailure = error.toString() }
        }
        fun materialProof(arm: Arm, round: Int): JsonObject {
            val entries = cache(arm.renderer)
            val meshes = instanced(arm.node)
            check(meshes.isNotEmpty() && meshes.all { it.geometry.gpuGeometry != null }) {
                "No real GPU-backed instanced texture meshes in complete canvas"
            }
            if (arm.label == "off") {
                check(entries.isEmpty()) { "OFF renderer material cache must remain empty" }
                return buildJsonObject {
                    put("materialCacheSize", 0); put("reuseDisabled", true); put("actualRenderedInstancedMeshes", meshes.size)
                }
            }
            check(entries.isNotEmpty()) { "ON renderer cache remained empty" }
            val shared = meshes.indices.firstNotNullOfOrNull { i ->
                meshes.indices.firstOrNull { j -> j > i && meshes[i].shader === meshes[j].shader }?.let { j -> i to j }
            } ?: error("Actual final frame has no provable material reuse across distinct visible meshes")
            val a = meshes[shared.first]; val b = meshes[shared.second]
            check(a !== b && a.instances !== b.instances && a.meshPipelineData !== b.meshPipelineData)
            check(field(a, "pipeline") != null && field(a, "pipeline") === field(b, "pipeline"))
            val active = entries.filterValues { value -> meshes.any { it.shader === value } }
            check(active.isNotEmpty() && active.values.any { it === a.shader })
            var acrossReplay = 0
            if (round == 1) {
                check(arm.oldBindings.isNotEmpty())
                for (old in arm.oldBindings) {
                    val key = checkNotNull(old.key.get()) { "Previous material key vanished; cannot prove replay reuse" }
                    val shader = checkNotNull(old.shader.get()) { "Previous shader vanished; cannot prove replay reuse" }
                    check(entries[key] === shader) { "Same-frame replay recreated a cached immutable material" }
                    acrossReplay++
                }
                check(acrossReplay > 0)
            } else arm.oldBindings = active.map { (key, value) ->
                OldBinding(WeakReference(checkNotNull(key)), WeakReference(checkNotNull(value)))
            }
            return buildJsonObject {
                put("materialCacheSize", entries.size); put("activeMaterialKeys", active.size)
                put("actualRenderedInstancedMeshes", meshes.size)
                put("distinctMeshesSharingShader", 2); put("sharedPipelineVerified", true)
                put("independentInstanceAndMeshBindingStorage", true); put("materialsReusedAcrossReplay", acrossReplay)
                put("meshA", a.name); put("groupA", a.drawGroupId); put("meshB", b.name); put("groupB", b.drawGroupId)
            }
        }
        fun report() = buildJsonObject {
            put("kind", "frozen-complete-game-canvas-shader-reuse-reference"); put("correctnessOnly", true)
            put("performanceInvalid", true); put("passed", false); put("pixelsPassed", pixelsPassed)
            put("status", if (pixelsPassed) "pending-post-shutdown-native-bind-evidence" else "failed-or-incomplete")
            put("scope", "Complete frozen 1280x720 game canvas and its game HUD, not App outer UI or display scanout")
            put("sequence", retained.sequence); put("simulationTick", retained.simulationTick)
            put("width", WIDTH); put("height", HEIGHT); put("numSamples", SAMPLES); put("tolerance", TOLERANCE)
            put("replaysPerArm", 2); put("sameFontSnapshot", true); put("hostRendererRedirected", false)
            put("identityRetention", "WeakReference only between replay rounds; no extra strong Shader/Pipeline/Mesh identity set")
            put("nativeBindEvidenceRequired", true); put("rounds", JsonArray(rounds))
            put("setupWitnesses", JsonArray(arms.flatMap { it.witnesses }))
            failure?.let { put("failure", it) }
        }
        try {
            checkHost()
            for (arm in arms) {
                check(cache(arm.renderer).isEmpty())
                arm.pass.defaultView.onSetupView { readyWitness(arm) }
                scene.addOffscreenPass(arm.pass); arm.attached = true; arm.pass.isEnabled = false
            }
            repeat(2) { round ->
                checkHost()
                // Same frozen inputs, fresh complete replay into each independent renderer.
                for (arm in arms) {
                    arm.round = round
                    fontScope.invoke(KoolCanvasFontRegistry, fonts, {
                        arm.renderer.renderInto(arm.node, arm.camera, replayFrame) {
                            arm.pass.colorAttachments.single().clearColor = it
                        }
                    } as () -> Unit)
                    arm.pass.isEnabled = true
                }
                val deadline = System.nanoTime() + 15_000_000_000L
                val firstFrame = Time.frameCount
                while (arms.any { arm -> arm.witnesses.filter { it["round"]!!.jsonPrimitive.int == round }
                        .map { it["frame"]!!.jsonPrimitive.long }.distinct().size < 2 } || Time.frameCount < firstFrame + 5) {
                    arms.forEach { check(it.setupFailure == null) { it.setupFailure!! } }
                    check(System.nanoTime() < deadline && Time.frameCount <= firstFrame + 180) { "Real bind-ready witnesses timed out" }
                    delay(5L)
                }
                val control = KoolCanvasGpuMapCellOracle.downloadFull(scene, checkNotNull(arms[0].pass.colorTexture), WIDTH, HEIGHT)
                val candidate = KoolCanvasGpuMapCellOracle.downloadFull(scene, checkNotNull(arms[1].pass.colorTexture), WIDTH, HEIGHT)
                check(control.size == WIDTH * HEIGHT && candidate.size == control.size)
                var mismatches = 0; var maxDelta = 0; var nonTransparent = 0
                var firstRgb: Int? = null; var diverseRgb = false
                for (i in control.indices) {
                    if ((control[i] ushr 24) != 0) {
                        nonTransparent++
                        val rgb = control[i] and 0xffffff
                        if (firstRgb == null) firstRgb = rgb else if (firstRgb != rgb) diverseRgb = true
                    }
                    var differs = false
                    for (shift in 0..3) {
                        val delta = abs((control[i] ushr (shift * 8) and 255) - (candidate[i] ushr (shift * 8) and 255))
                        maxDelta = maxOf(maxDelta, delta); if (delta > TOLERANCE) differs = true
                    }
                    if (differs) mismatches++
                }
                check(nonTransparent > 0 && diverseRgb) { "Control attachment must be nontransparent and non-monochrome" }
                val offHash = sha(control); val onHash = sha(candidate)
                if (round == 1) check(rounds[0]["offRgbaSha256"]!!.jsonPrimitive.content == offHash &&
                    rounds[0]["onRgbaSha256"]!!.jsonPrimitive.content == onHash) {
                    "Replaying the same frozen frame changed complete-canvas pixels"
                }
                rounds += buildJsonObject {
                    put("round", round); put("checkedPixels", control.size); put("checkedChannels", control.size.toLong() * 4)
                    put("mismatches", mismatches); put("maxChannelDelta", maxDelta); put("nonTransparentControlPixels", nonTransparent)
                    put("nonTransparentControlRgbDiverse", diverseRgb)
                    put("offRgbaSha256", offHash); put("onRgbaSha256", onHash)
                    put("off", materialProof(arms[0], round)); put("on", materialProof(arms[1], round))
                }
                check(mismatches == 0) { "Full game canvas differs: $mismatches pixels, max delta $maxDelta" }
                checkHost()
                arms.forEach { it.pass.isEnabled = false; it.round = -1 }
                backend.device.waitForIdle(); VulkanFrameLifecycle.deviceIdle(backend, false)
            }
            pixelsPassed = true
        } catch (error: Throwable) { failure = error.toString(); throw error }
        finally {
            // Native calls require the caller's external process deadline; coroutine timeouts cannot interrupt them.
            try {
                arms.forEach { it.round = -1; it.pass.isEnabled = false; if (it.attached) scene.removeOffscreenPass(it.pass) }
                backend.device.waitForIdle(); VulkanFrameLifecycle.deviceIdle(backend, false)
                for (arm in arms) {
                    releaseMeshes.invoke(arm.renderer, arm.node)
                    arm.pass.release(); arm.node.release()
                }
                // Atlas retirement schedules Texture.releaseDelayed(1). Pump genuine frames while lease remains pinned.
                val deadline = System.nanoTime() + 10_000_000_000L
                val until = Time.frameCount + 3
                while (Time.frameCount < until) { check(System.nanoTime() < deadline); delay(5L) }
                backend.device.waitForIdle(); VulkanFrameLifecycle.deviceIdle(backend, false)
            } catch (error: Throwable) {
                pixelsPassed = false
                failure = "Cleanup failed: $error; preceding failure=$failure"
                throw error
            } finally {
                output.resolve("full-canvas-reference-pending.json").writeText(report().toString())
            }
        }
        return report() // passed remains false until existing native logger is closed/flushed.
    }

    /** Run only AFTER context.run() shutdown returned, before deciding oracle result exit status. */
    fun finishAfterContextShutdown(output: File): JsonObject {
        val pending = Json.parseToJsonElement(output.resolve("full-canvas-reference-pending.json").readText()).jsonObject
        check(pending["pixelsPassed"]!!.jsonPrimitive.boolean && pending["failure"] == null)
        val raw = output.resolve("gpu-target-draw.ndjson")
        check(raw.isFile && raw.length() > 0)
        val expected = pending["setupWitnesses"]!!.jsonArray.map { it.jsonObject }
        check(expected.isNotEmpty())
        fun key(row: JsonObject) = listOf("frame", "slot", "mesh", "group", "instances").map { row[it].toString() }
        val expectedKeys = expected.map(::key).toSet()
        check(expectedKeys.size == expected.size) { "Ambiguous duplicate mesh/group witness" }
        val matched = HashMap<List<String>, Int>()
        var ownRows = 0
        raw.useLines { lines -> lines.forEach { line ->
            check(line.isNotBlank()) { "Malformed native draw log" }
            val row = Json.parseToJsonElement(line).jsonObject
            if (row["slot"]?.jsonPrimitive?.content?.startsWith(PREFIX) == true) {
                ownRows++
                val k = key(row)
                if (k in expectedKeys) {
                    check(row["bound"]!!.jsonPrimitive.boolean) { "Instanced full-canvas draw failed actual native bind" }
                    matched[k] = (matched[k] ?: 0) + 1
                }
            }
        } }
        check(expectedKeys.all { matched[it] == 1 }) { "Every bind-ready mesh witness needs exactly one native bound=true return" }
        val finished = JsonObject(pending + mapOf(
            "passed" to JsonPrimitive(true), "status" to JsonPrimitive("passed-pixels-and-native-bind-coverage"),
            "nativeDrawLogSha256" to JsonPrimitive(MessageDigest.getInstance("SHA-256").digest(raw.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }),
            "nativeOwnPassRows" to JsonPrimitive(ownRows), "matchedNativeBoundDraws" to JsonPrimitive(expectedKeys.size),
            "contextShutdownAndNativeLoggerFlushRequired" to JsonPrimitive(true)))
        check(!output.resolve("full-canvas-reference.json").exists())
        output.resolve("full-canvas-reference.json").writeText(finished.toString())
        return finished
    }
}
