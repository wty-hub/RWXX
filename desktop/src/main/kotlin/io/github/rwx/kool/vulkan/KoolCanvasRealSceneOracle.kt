package io.github.rwx.kool.vulkan

import de.fabmax.kool.math.Vec2i
import de.fabmax.kool.pipeline.AttachmentConfig
import de.fabmax.kool.pipeline.OffscreenPass2d
import de.fabmax.kool.pipeline.FrameCopy
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.platform.Lwjgl3Context
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.OnRenderScene
import de.fabmax.kool.scene.OrthographicCamera
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.util.FrontendScope
import de.fabmax.kool.util.Viewport
import io.github.rwx.benchmark.ReplayPanBenchmark
import io.github.rwx.render.canvas.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/** Diagnostic: compares the same leased live game snapshot through control and candidate renderers.
 * Reflection reads existing private owners; no production observer API or simulation changes. */
internal object KoolCanvasRealSceneOracle {
    fun install(context: Lwjgl3Context, host: KoolCanvasSceneHost) {
        val output = System.getenv("RWX_REAL_SCENE_ORACLE")?.let(::File) ?: return
        output.mkdirs()
        try {
            installConfigured(context, host, output)
        } catch (failure: Throwable) {
            File(output, "setup-failure.txt").writeText(failure.stackTraceToString())
            failure.printStackTrace()
            throw failure
        }
    }

    private fun installConfigured(context: Lwjgl3Context, host: KoolCanvasSceneHost, output: File) {
        val envelopeField = KoolCanvasSceneHost::class.java.getDeclaredField("currentEnvelope").apply { isAccessible = true }
        val fontsField = KoolCanvasResourceLease::class.java.getDeclaredField("fonts").apply { isAccessible = true }
        val fontScope = KoolCanvasFontRegistry::class.java.declaredMethods.single { it.name.startsWith("withSnapshot") }
        val resourcesType = Class.forName("io.github.rwx.render.canvas.FrozenCanvasGpuResources")
        val resources = resourcesType.getField("INSTANCE").get(null)
        val resourceInstall = resourcesType.getMethod("install", KoolCanvasResourceLease::class.java)
        val releaseMeshes = KoolCanvasFrameRenderer::class.java.declaredMethods.single {
            it.name.startsWith("releaseCachedMeshes") && it.parameterTypes.contentEquals(arrayOf(Node::class.java))
        }.apply { isAccessible = true }
        val templatesClass = Class.forName("io.github.rwx.render.canvas.KoolCanvasShaderTemplates")
        val templates = templatesClass.getField("INSTANCE").get(null)
        val templateScope = templatesClass.declaredMethods.single { it.name.startsWith("withTemplates") }.apply { isAccessible = true }
        val templateCandidate = System.getenv("RWX_CACHE_CANVAS_SHADER_TEMPLATES") == "1"
        val playerDeathRequired = System.getenv("RWX_BENCHMARK_PLAYER_DEATH_SECONDS") != null
        val expected = 12
        val cellOracle = System.getenv("RWX_REAL_SCENE_CELL_ORACLE") == "1"
        val leaseResources = KoolCanvasResourceLease::class.java.declaredMethods.single {
            it.name.startsWith("resources") && it.parameterCount == 0
        }.apply { isAccessible = true }
        val variants = listOf("control", "sprites", "labels", "combined")
        val results = mutableListOf<JsonObject>()
        var scene: Scene? = null
        var busy = false
        var nextCapture = 0L
        var nodes: List<Node> = emptyList()
        var cameras: List<OrthographicCamera> = emptyList()
        var renderers: List<KoolCanvasFrameRenderer> = emptyList()
        var passes: List<OffscreenPass2d> = emptyList()
        var screenCopy: FrameCopy? = null
        var failureSeen: Throwable? = null
        fun report() {
            val visibility = results.mapNotNull { it["playerVisibility"]?.jsonObject }
            val beforeDeath = visibility.any { it["livingPlayerUnits"]!!.jsonPrimitive.int > 0 &&
                !it["playerWipedOut"]!!.jsonPrimitive.boolean && it["hiddenFogCells"]!!.jsonPrimitive.int > 0 }
            val afterDeath = visibility.any { it["livingPlayerUnits"]!!.jsonPrimitive.int == 0 &&
                it["playerWipedOut"]!!.jsonPrimitive.boolean && it["hiddenFogCells"]!!.jsonPrimitive.int == 0 }
            val deathTransition = beforeDeath && afterDeath && visibility.map { it["fogHash"] }.distinct().size > 1
            File(output, "summary.json").writeText(buildJsonObject {
                put("diagnosticOnly", true); put("expectedFrames", expected); put("completedFrames", results.size)
                put("playerDeathRequired", playerDeathRequired); put("playerDeathTransitionCovered", deathTransition)
                put("passed", failureSeen == null && (!playerDeathRequired || deathTransition) && results.size == expected && results.all {
                    it["mismatches"]?.jsonPrimitive?.int == 0 && it["cellMismatches"]?.jsonPrimitive?.int == 0 })
                put("cellOracle", cellOracle)
                put("shaderTemplateCandidate", templateCandidate)
                put("shaderTemplateControl", "ordinary KSL factory; templates disabled when creating control and reference-cell shaders")
                failureSeen?.let { put("failure", it.toString()) }
                put("comparison", "same leased live snapshot, existing texture paths versus enabled world sprite and label candidates")
                put("frames", JsonArray(results))
            }.toString())
        }
        context.onShutdown += { report() }
        context.onRender += {
            val currentScene = host.scene
            if (scene == null && currentScene != null) {
                scene = currentScene
                // Capture the final screen composition through Kool's existing copy path. Native
                // window capture may omit an embedded Vulkan surface; it is not a pixel oracle.
                currentScene.onRenderScene += OnRenderScene {
                    val start = ReplayPanBenchmark.startedAtNanos
                    val now = System.nanoTime()
                    if (start == 0L || now < start + 10_000_000_000L || now < nextCapture || busy || failureSeen != null || results.size >= expected)
                        return@OnRenderScene
                    val envelope = (envelopeField.get(host) as? FrameEnvelope)?.retain() ?: return@OnRenderScene
                    val cameraSnapshot = envelope.camera
                    if (cameraSnapshot == null) { envelope.close(); return@OnRenderScene }
                    val width = envelope.frame.viewport.width; val height = envelope.frame.viewport.height
                    if (screenCopy == null) {
                        // Bootstrap scenes are initially visible before the screen presenter runs.
                        // Select the actual final scene only after the replay is visibly running.
                        val finalScene = context.scenes.lastOrNull { it.isVisible } ?: currentScene
                        screenCopy = finalScene.mainRenderPass.copyOutput(isCopyColor = true, isCopyDepth = false)
                        File(output, "screen-scene-selection.json").writeText(buildJsonObject {
                            put("selected", finalScene.name)
                            put("scenes", JsonArray(context.scenes.map { buildJsonObject {
                                put("name", it.name); put("visible", it.isVisible)
                                put("clear", it.clearColor.toString())
                            } }))
                        }.toString())
                    }
                    if (passes.isEmpty()) {
                        nodes = variants.map { Node("live-scene-$it") }
                        cameras = nodes.map { OrthographicCamera(it.name) }
                        renderers = listOf(KoolCanvasFrameRenderer(reuseTextGeometry = false, instanceTextGlyphs = false,
                            reuseTextShaders = false, instanceTextLabels = false, projectedSpriteAtlas = false, prepareText = false, reuseTextMeshKeys = false,
                            reuseInstancedTextureShaders = false),
                            KoolCanvasFrameRenderer(instanceTextLabels = false),
                            KoolCanvasFrameRenderer(projectedSpriteAtlas = false), KoolCanvasFrameRenderer())
                        passes = nodes.mapIndexed { index, node -> OffscreenPass2d(node,
                            AttachmentConfig.singleColorNoDepth(TexFormat.RGBA), Vec2i(width, height), "live-scene-$index").apply {
                                camera = cameras[index]; isMirrorY = false; viewport = Viewport(0, 0, width, height)
                            }.also(currentScene::addOffscreenPass) }
                    }
                    busy = true; nextCapture = now + 2_000_000_000L
                    val installed = resourceInstall.invoke(resources, envelope.resourceLease) as AutoCloseable
                    val cellPasses = mutableListOf<OffscreenPass2d>()
                    val cellRenderers = mutableListOf<Pair<Node, KoolCanvasFrameRenderer>>()
                    FrontendScope.launch {
                        try {
                            val draw: () -> Unit = {
                                renderers.forEachIndexed { index, renderer ->
                                    templateScope.invoke(templates, templateCandidate && index != 0, {
                                        renderer.renderInto(nodes[index], cameras[index], envelope.frame) {
                                            passes[index].colorAttachments.single().clearColor = it
                                        }
                                    } as () -> Unit)
                                }
                            }
                            fontScope.invoke(KoolCanvasFontRegistry, fontsField.get(envelope.resourceLease), draw)
                            passes.forEach { it.isEnabled = true }
                            @Suppress("UNCHECKED_CAST")
                            val frozen = leaseResources.invoke(envelope.resourceLease) as Map<KoolCanvasTextureId, Any>
                            val cells = if (cellOracle) frozen.filterValues { it.javaClass.simpleName == "GpuTarget" } else emptyMap()
                            val cellTargets = cells.entries.map { (id, resource) ->
                                val frame = resource.javaClass.getMethod("getFrame").invoke(resource) as KoolCanvasFrame
                                val cellWidth = resource.javaClass.getMethod("getWidth").invoke(resource) as Int
                                val cellHeight = resource.javaClass.getMethod("getHeight").invoke(resource) as Int
                                val node = Node("live-cell-${id.value}")
                                val camera = OrthographicCamera(node.name)
                                val renderer = KoolCanvasFrameRenderer(reuseInstancedTextureShaders = false,
                                    useSpriteAtlas = false, meshRetentionRenders = 0, prepareText = false,
                                    reuseTextMeshKeys = false, reuseMapMeshSlots = false)
                                val pass = OffscreenPass2d(node, AttachmentConfig.singleColorNoDepth(TexFormat.RGBA),
                                    Vec2i(cellWidth, cellHeight), node.name).apply {
                                    this.camera = camera; isMirrorY = false; viewport = Viewport(0, 0, cellWidth, cellHeight)
                                }
                                templateScope.invoke(templates, false, {
                                    renderer.renderInto(node, camera, frame) { pass.colorAttachments.single().clearColor = it }
                                } as () -> Unit)
                                cellPasses += pass; cellRenderers += node to renderer
                                currentScene.addOffscreenPass(pass)
                                Triple(KoolCanvasTextureRef(id, cellWidth, cellHeight), frame, pass)
                            }
                            File(output, "${results.size + 1}-commands.txt").writeText(envelope.frame.commands.joinToString("\n"))
                            delay(100L)
                            var cellMismatches = 0
                            val cellReports = cellTargets.mapIndexed { index, (texture, frame, pass) ->
                                val referenceCell = KoolCanvasGpuMapCellOracle.downloadFull(currentScene,
                                    checkNotNull(pass.colorTexture), texture.width, texture.height)
                                val actualCell = KoolCanvasGpuMapCellOracle.downloadFull(currentScene,
                                    KoolCanvasTextureRegistry.resolve(texture, KoolCanvasTextureFilter.Linear), texture.width, texture.height)
                                val mismatches = referenceCell.indices.count { pixel -> (0..3).any { channel ->
                                    abs((referenceCell[pixel] ushr (channel * 8) and 255) - (actualCell[pixel] ushr (channel * 8) and 255)) > 3 } }
                                cellMismatches += mismatches
                                if (mismatches > 0) for ((label, pixels) in listOf("reference" to referenceCell, "actual" to actualCell)) {
                                    val image = BufferedImage(texture.width, texture.height, BufferedImage.TYPE_INT_ARGB)
                                    image.setRGB(0, 0, texture.width, texture.height, pixels, 0, texture.width)
                                    ImageIO.write(image, "png", File(output, "${results.size + 1}-cell-$index-$label.png"))
                                }
                                if (mismatches > 0) File(output, "${results.size + 1}-cell-$index-commands.txt")
                                    .writeText(frame.commands.joinToString("\n"))
                                buildJsonObject {
                                    put("id", texture.id.value); put("width", texture.width); put("height", texture.height)
                                    put("commands", frame.commands.size); put("mismatches", mismatches)
                                    put("referenceBlackPixels", referenceCell.count { it and 0xffffff == 0 })
                                    put("actualBlackPixels", actualCell.count { it and 0xffffff == 0 })
                                }
                            }
                            val reference = KoolCanvasGpuMapCellOracle.downloadFull(currentScene, checkNotNull(passes[0].colorTexture), width, height)
                            val comparisons = mutableListOf<JsonObject>()
                            var mismatchCount = 0
                            for (variant in 1 until passes.size) {
                                val actual = KoolCanvasGpuMapCellOracle.downloadFull(currentScene, checkNotNull(passes[variant].colorTexture), width, height)
                                val bad = reference.indices.filter { index -> (0..3).any { channel ->
                                    abs((reference[index] ushr (channel * 8) and 255) - (actual[index] ushr (channel * 8) and 255)) > 3 } }
                                mismatchCount += bad.size
                                comparisons += buildJsonObject {
                                    put("variant", variants[variant]); put("checkedPixels", actual.size); put("mismatches", bad.size)
                                    put("examples", JsonArray(bad.take(20).map { index -> buildJsonObject {
                                        put("x", index % width); put("y", index / width)
                                        put("reference", Integer.toHexString(reference[index])); put("actual", Integer.toHexString(actual[index]))
                                    } }))
                                }
                                if (bad.isNotEmpty()) for ((label, pixels) in listOf("control" to reference, variants[variant] to actual)) {
                                    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                                    image.setRGB(0, 0, width, height, pixels, 0, width)
                                    ImageIO.write(image, "png", File(output, "${results.size + 1}-$label.png"))
                                }
                            }
                            var screenContentPixels = -1
                            run {
                                val screen = KoolCanvasGpuMapCellOracle.downloadFull(currentScene,
                                    checkNotNull(screenCopy).colorCopy2d, width, height)
                                screenContentPixels = screen.count { it and 0xffffff != 0 }
                                val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                                image.setRGB(0, 0, width, height, screen, 0, width)
                                ImageIO.write(image, "png", File(output, "${results.size + 1}-final-screen.png"))
                                check(screenContentPixels > 10_000) { "Final screen composition contains no game picture: $screenContentPixels pixels" }
                            }
                            results += buildJsonObject {
                                put("sequence", envelope.sequence); put("generation", envelope.generation)
                                put("simulationTick", envelope.simulationTick); put("width", width); put("height", height)
                                ReplayPanBenchmark.visibilityAt(envelope.simulationTick)?.let { visibility ->
                                    put("playerVisibility", buildJsonObject {
                                        put("observedTick", visibility.tick); put("atNanos", visibility.atNanos)
                                        put("livingPlayerUnits", visibility.living); put("playerWipedOut", visibility.wiped)
                                        put("playerObserver", visibility.observer); put("fogHash", visibility.fogHash)
                                        put("hiddenFogCells", visibility.hiddenFogCells)
                                    })
                                }
                                put("cameraX", cameraSnapshot.x); put("cameraY", cameraSnapshot.y); put("zoom", cameraSnapshot.zoom)
                                put("checkedPixels", reference.size * (passes.size - 1)); put("mismatches", mismatchCount)
                                put("variants", JsonArray(comparisons))
                                put("screenContentPixels", screenContentPixels)
                                put("cellMismatches", cellMismatches); put("cells", JsonArray(cellReports))
                            }
                            report()
                            println("RWXRealSceneOracle sequence=${envelope.sequence} mismatches=$mismatchCount capture=${results.size}/$expected variants=$comparisons")
                        } catch (failure: Throwable) {
                            failureSeen = failure
                            File(output, "failure.txt").writeText(failure.stackTraceToString())
                            failure.printStackTrace(); report()
                        } finally {
                            // Between captures the old nodes still reference this exact lease.
                            // Stop their passes before retiring it; otherwise changing fog textures
                            // can be freed while these diagnostic passes keep sampling them.
                            passes.forEach { it.isEnabled = false }
                            cellPasses.forEach { it.isEnabled = false; currentScene.removeOffscreenPass(it) }
                            VulkanFrameLifecycle.retainUntilFrameComplete {
                                cellRenderers.forEach { (node, renderer) -> releaseMeshes.invoke(renderer, node) }
                                cellPasses.forEach { it.release() }
                            }
                            VulkanFrameLifecycle.retainUntilFrameComplete { installed.close(); envelope.close() }
                            busy = false
                            if (results.size >= expected) {
                                passes.forEach { it.isEnabled = false; currentScene.removeOffscreenPass(it) }
                                VulkanFrameLifecycle.retainUntilFrameComplete {
                                    renderers.forEachIndexed { index, renderer -> releaseMeshes.invoke(renderer, nodes[index]) }
                                    passes.forEach { it.release() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
