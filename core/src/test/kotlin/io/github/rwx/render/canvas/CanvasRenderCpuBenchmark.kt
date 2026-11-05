package io.github.rwx.render.canvas

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.pipeline.ClearColor
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.OrthographicCamera
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.Locale

/**
 * Windowless production renderInto CPU benchmark; not a JUnit test and never launched by core:test.
 * Run its main from the core test runtime classpath using JDK 25, nice -n 15 where available,
 * -Djava.awt.headless=true -Xms192m -Xmx192m -XX:+UseSerialGC -XX:ActiveProcessorCount=1.
 * Run independent JVMs sequentially in A/B/B/A order, with one candidate env changed at a time.
 * Clear inherited RWX_* diagnostics/candidates. None of the candidates below is enabled by default.
 *
 * Examples: --mode=static --tiles=128 --warmup=300 --frames=1000 --phases=32
 *           --mode=moving --tiles=128 --warmup=300 --frames=1000 --phases=32
 * Supported independent switches: RWX_REUSE_MAP_MESH_SLOTS,
 * RWX_REUSE_INSTANCED_TEXTURE_SHADERS, RWX_COMPACT_QUAD_STORAGE,
 * RWX_COMPACT_TEXTURE_INSTANCES. This bypasses the sprite atlas in BOTH arms so fake textures never
 * trigger pixel uploads. It does not exercise shared materials across multiple offscreen renderers.
 *
 * Timed work is CPU command dispatch and geometry/instance/material object construction only.
 * Frames, coordinates and command state are prebuilt; recording/freezing are outside the timer.
 * No KoolContext, scene collection, createPipeline, driver, GPU execution, fence or presentation.
 * KSL program object construction can occur when a CPU mesh/material is created; shader compilation
 * and native pipeline creation cannot. Concurrent user work makes CPU/wall screening data, not FPS.
 *
 * moving is a synthetic offscreen slot workload: logical source keys change between phases and
 * detached command states carry pan/zoom/affine transforms. It is not a physical camera-input test.
 * Correctness checks compare all phases with a constructor-candidate-disabled renderer before warmup
 * and again after timing: draw order, counts, bindings, raw instance/vertex bits and indices must match.
 * Compact quad capacity is a process-wide production constant, so compare the reported geometry
 * digests across separate OFF/ON JVMs too; this in-process control cannot override that capacity.
 */
object CanvasRenderCpuBenchmark {
    @Volatile private var sink = 0L
    private val ignoreClear: (ClearColor) -> Unit = {}

    private data class Options(
        val moving: Boolean = false,
        val tiles: Int = 128,
        val warmup: Int = 300,
        val frames: Int = 1000,
        val phases: Int = 32,
    )

    private data class Candidates(
        val mapMeshSlots: Boolean,
        val textureMaterials: Boolean,
        val compactQuads: Boolean,
        val compactInstances: Boolean,
    )

    private data class Picture(
        val drawGroups: List<Int>,
        val textureBindings: List<Int>,
        val instanceCounts: List<Int>,
        val vertexCounts: List<Int>,
        val indexCounts: List<Int>,
        val rawGeometrySha256: String,
    )

    private class TextureStore : KoolCanvasTextureStore by KoolCanvasTextureRegistry,
        KoolCanvasTextureRevisionStore, KoolCanvasFrameTextureRevisionStore, AutoCloseable {
        val first = Texture2d(name = "cpu-benchmark-source-a")
        val second = Texture2d(name = "cpu-benchmark-source-b")
        // Every logical key has a fixed source binding for this process. This keeps the production
        // revision-aware resolution cache active rather than benchmarking a forced clear per frame.
        override val textureRevision = 0
        override val frameTextureRevision = 0

        override fun resolve(texture: KoolCanvasTextureRef, filter: KoolCanvasTextureFilter): Texture2d =
            when {
                texture.id.value.startsWith("a-") -> first
                texture.id.value.startsWith("b-") -> second
                else -> error("Unexpected benchmark texture: ${texture.id}")
            }

        override fun frame(id: KoolCanvasTextureId): KoolCanvasFrame? = null
        override fun staticArgbImage(id: KoolCanvasTextureId): KoolCanvasArgbImage? = null

        fun binding(texture: Texture2d?): Int = when (texture) {
            first -> 1
            second -> 2
            null -> 0
            else -> error("Benchmark renderer bound an unexpected texture")
        }

        override fun close() {
            first.release()
            second.release()
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        val options = parse(args)
        val candidates = Candidates(
            enabled("RWX_REUSE_MAP_MESH_SLOTS"), enabled("RWX_REUSE_INSTANCED_TEXTURE_SHADERS"),
            enabled("RWX_COMPACT_QUAD_STORAGE"), enabled("RWX_COMPACT_TEXTURE_INSTANCES"),
        )
        require(System.getenv("RWX_SHARE_MAP_TEXTURE_MATERIALS") != "1") {
            "This single-renderer benchmark cannot measure cross-renderer shared materials"
        }
        require(System.getenv("RWX_CANVAS_TRACE").isNullOrBlank()) { "Disable RWX_CANVAS_TRACE for CPU screening" }
        require(System.getenv("RWX_FRAME_METRICS").isNullOrBlank()) { "Disable RWX_FRAME_METRICS for CPU screening" }
        if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm())
        check(KoolSystem.getContextOrNull() == null) { "No KoolContext is allowed in this benchmark" }
        val bean = checkNotNull(ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean::class.java))
        check(bean.isThreadAllocatedMemorySupported && bean.isCurrentThreadCpuTimeSupported)
        if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
        if (!bean.isThreadCpuTimeEnabled) bean.isThreadCpuTimeEnabled = true

        val frames = List(if (options.moving) options.phases else 1) { frame(options.tiles, it, options.moving) }
        TextureStore().use { store ->
            val node = Node("cpu-render-benchmark")
            val camera = OrthographicCamera("cpu-render-benchmark-camera")
            val renderer = renderer(store, candidates)
            try {
                val expected = controlPictures(store, frames)
                validate(renderer, node, camera, frames, expected, store)
                repeat(options.warmup) { renderer.renderInto(node, camera, frames[it % frames.size], ignoreClear) }
                val thread = Thread.currentThread().threadId()
                val allocatedStart = bean.getThreadAllocatedBytes(thread)
                val cpuStart = bean.currentThreadCpuTime
                val wallStart = System.nanoTime()
                repeat(options.frames) { renderer.renderInto(node, camera, frames[it % frames.size], ignoreClear) }
                val wallNanos = System.nanoTime() - wallStart
                val cpuNanos = bean.currentThreadCpuTime - cpuStart
                val allocatedBytes = bean.getThreadAllocatedBytes(thread) - allocatedStart
                val cachedMeshes = renderer.cachedMeshCount()
                val reusedMapSlots = renderer.reusedMapMeshSlotCount()
                validate(renderer, node, camera, frames, expected, store)
                check(KoolSystem.getContextOrNull() == null)
                sink = expected.sumOf { it.instanceCounts.sum().toLong() + it.indexCounts.sum() } + cachedMeshes
                println(String.format(Locale.ROOT,
                    "RWXCanvasRenderCpuBenchmark {\"mode\":\"%s\",\"phases\":%d,\"tilesPerRun\":%d," +
                        "\"textureRuns\":4,\"commandsPerFrame\":%d,\"warmup\":%d,\"frames\":%d," +
                        "\"mapMeshSlots\":%s,\"textureMaterials\":%s,\"compactQuads\":%s,\"compactInstances\":%s," +
                        "\"allocatedBytes\":%d,\"bytesPerFrame\":%.3f,\"cpuNanosPerFrame\":%.3f," +
                        "\"wallNanosPerFrame\":%.3f,\"cachedMeshes\":%d,\"reusedMapSlots\":%d," +
                        "\"validatedPhases\":%d,\"lastPhaseVisibleMeshes\":%d,\"lastPhaseInstances\":%d," +
                        "\"lastPhaseGeometrySha256\":\"%s\",\"sink\":%d,\"cpuBuildOnly\":true," +
                        "\"recordingOrFreezeMeasured\":false,\"shaderCompilationMeasured\":false," +
                        "\"gpuOrPresentationMeasured\":false,\"noContext\":true}",
                    if (options.moving) "moving" else "static", frames.size, options.tiles,
                    frames.first().commands.size, options.warmup, options.frames,
                    candidates.mapMeshSlots, candidates.textureMaterials, candidates.compactQuads, candidates.compactInstances,
                    allocatedBytes, allocatedBytes.toDouble() / options.frames, cpuNanos.toDouble() / options.frames,
                    wallNanos.toDouble() / options.frames, cachedMeshes, reusedMapSlots, expected.size,
                    expected.last().drawGroups.size, expected.last().instanceCounts.sum(),
                    expected.last().rawGeometrySha256, sink))
            } finally {
                renderer.releaseCachedMeshes(node)
                node.release()
            }
        }
    }

    private fun renderer(store: TextureStore, candidates: Candidates) = KoolCanvasFrameRenderer(
        textureStore = store,
        performanceRates = { null },
        meshRetentionRenders = 8,
        meshIdentityIgnoresRecordingOwner = true,
        useSpriteAtlas = false,
        reuseMapMeshSlots = candidates.mapMeshSlots,
        reuseInstancedTextureShaders = candidates.textureMaterials,
        compactTextureInstances = candidates.compactInstances,
    )

    private fun controlPictures(store: TextureStore, frames: List<KoolCanvasFrame>): List<Picture> {
        val control = renderer(store, Candidates(false, false, false, false))
        val node = Node("cpu-render-benchmark-control")
        val camera = OrthographicCamera("cpu-render-benchmark-control-camera")
        return try {
            frames.map { frame ->
                control.renderInto(node, camera, frame, ignoreClear)
                picture(node, store).also { picture ->
                    // A clipped texture deliberately takes the ordinary geometry path. Texture run
                    // coverage must follow the actual texture binding, not the presence of instances.
                    check(picture.textureBindings == listOf(1, 0, 1, 0, 1, 0, 2)) {
                        "Four texture runs and three primitive barriers must keep their order: " +
                            "bindings=${picture.textureBindings}, instances=${picture.instanceCounts}"
                    }
                    check(picture.instanceCounts.count { it > 0 } == 2) {
                        "The mixed fixture must exercise two instanced and two ordinary texture meshes: " +
                            "bindings=${picture.textureBindings}, instances=${picture.instanceCounts}"
                    }
                }
            }
        } finally {
            control.releaseCachedMeshes(node)
            node.release()
        }
    }

    private fun validate(renderer: KoolCanvasFrameRenderer, node: Node, camera: OrthographicCamera,
                         frames: List<KoolCanvasFrame>, expected: List<Picture>, store: TextureStore) {
        frames.forEachIndexed { index, frame ->
            renderer.renderInto(node, camera, frame, ignoreClear)
            check(picture(node, store) == expected[index]) {
                "CPU geometry, draw groups, instance counts or texture bindings changed in phase $index"
            }
        }
    }

    private fun picture(node: Node, store: TextureStore): Picture {
        val meshes = node.children.filterIsInstance<Mesh<*>>().filter { it.isVisible }.sortedBy { it.drawGroupId }
        check(meshes.zipWithNext().all { (a, b) -> a.drawGroupId < b.drawGroupId }) {
            "Separate visible batches must retain distinct increasing draw groups"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        fun integer(value: Int) {
            digest.update((value ushr 24).toByte()); digest.update((value ushr 16).toByte())
            digest.update((value ushr 8).toByte()); digest.update(value.toByte())
        }
        val bindings = ArrayList<Int>(meshes.size)
        for (mesh in meshes) {
            val binding = when (val shader = mesh.shader) {
                is KoolCanvasInstancedTextureShader -> store.binding(shader.colorMap)
                is KoolCanvasAffineInstancedTextureShader -> store.binding(shader.colorMap)
                is KoolCanvasTextureShader -> store.binding(shader.colorMap)
                else -> 0
            }
            bindings += binding
            integer(mesh.drawGroupId); integer(binding)
            digest.update(checkNotNull(mesh.shader)::class.java.name.toByteArray(Charsets.UTF_8))
            val vertices = mesh.geometry.vertexData
            integer(mesh.geometry.numVertices); integer(mesh.geometry.numIndices)
            repeat(mesh.geometry.numVertices * vertices.strideBytes / 4) { integer(vertices.buffer.getInt32(it * 4)) }
            repeat(mesh.geometry.numIndices) { integer(mesh.geometry.indices[it]) }
            val instances = mesh.instances
            integer(instances?.numInstances ?: 0)
            if (instances != null) {
                val data = instances.instanceData
                repeat(instances.numInstances * data.strideBytes / 4) { integer(data.buffer.getInt32(it * 4)) }
            }
        }
        return Picture(meshes.map { it.drawGroupId }, bindings, meshes.map { it.instances?.numInstances ?: 0 },
            meshes.map { it.geometry.numVertices }, meshes.map { it.geometry.numIndices },
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

    private fun frame(tiles: Int, phase: Int, moving: Boolean): KoolCanvasFrame {
        val scale = if (moving) 0.875f + (phase and 7) / 32f else 1f
        val pan = if (moving) (phase % 9 - 4).toFloat() else 0f
        val transform = KoolCanvasTransform(scaleX = scale, scaleY = scale, translateX = pan, translateY = -pan)
        val state = KoolCanvasState(transform = transform)
        // Keep two runs on the canonical identity-state instanced path in both modes. The other
        // two carry clip/camera/affine state and cover the production ordinary geometry fallback.
        // An equal-valued freshly constructed transform is not the canonical Identity singleton.
        val instancedState = KoolCanvasState.Default
        val clipped = state.copy(clip = KoolCanvasRect(20f, 20f, 240f, 240f))
        val affine = state.copy(transform = transform.rotate(7f, 128f, 128f), clip = KoolCanvasRect(8f, 8f, 480f, 480f))
        val commands = ArrayList<KoolCanvasCommand>(tiles * 4 + 3)
        val source = KoolCanvasRect(1f, 2f, 17f, 18f)
        val suffix = if (moving) phase else 0
        fun run(prefix: String, alpha: Boolean, runState: KoolCanvasState) {
            val ref = KoolCanvasTextureRef(KoolCanvasTextureId("$prefix-$suffix"), 32, 32, hasAlpha = alpha)
            repeat(tiles) { index ->
                val x = 32f + (index and 15) * 12f
                val y = 32f + ((index ushr 4) and 15) * 12f
                commands += KoolCanvasCommand.DrawTexture(ref, source,
                    KoolCanvasRect(x, y, x + 12.5f, y + 12.5f), KoolCanvasPaint.Default, runState)
            }
        }
        val barrierPaint = KoolCanvasPaint(color = KoolCanvasColor(0x8000ff00.toInt()))
        run("a-opaque", false, instancedState)
        commands += KoolCanvasCommand.DrawRect(KoolCanvasRect(4f, 4f, 100f, 40f), barrierPaint, state)
        run("a-clipped", false, clipped)
        commands += KoolCanvasCommand.DrawLine(KoolCanvasPoint(10f, 10f), KoolCanvasPoint(200f, 200f), barrierPaint, state)
        run("a-alpha", true, instancedState)
        commands += KoolCanvasCommand.DrawRect(KoolCanvasRect(8f, 8f, 80f, 80f), barrierPaint, clipped)
        run("b-affine-alpha", true, affine)
        return KoolCanvasFrame(KoolCanvasViewport(512, 512), commands)
    }

    private fun enabled(name: String): Boolean = System.getenv(name) == "1"

    private fun parse(args: Array<String>): Options {
        var options = Options()
        val seen = HashSet<String>()
        for (argument in args) {
            val parts = argument.split('=', limit = 2)
            require(parts.size == 2 && seen.add(parts[0])) { "Use each --name=value argument once" }
            options = when (parts[0]) {
                "--mode" -> options.copy(moving = when (parts[1]) {
                    "static" -> false
                    "moving" -> true
                    else -> error("--mode must be static or moving")
                })
                "--tiles" -> options.copy(tiles = parts[1].toInt())
                "--warmup" -> options.copy(warmup = parts[1].toInt())
                "--frames" -> options.copy(frames = parts[1].toInt())
                "--phases" -> options.copy(phases = parts[1].toInt())
                else -> error("Unknown argument: $argument")
            }
        }
        require(options.tiles in 1..2048 && options.warmup in 1..100_000 && options.frames in 1..100_000 &&
            options.phases in 2..64) { "tiles=1..2048, warmup/frames=1..100000, phases=2..64" }
        return options
    }
}
