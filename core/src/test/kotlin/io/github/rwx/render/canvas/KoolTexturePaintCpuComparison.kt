package io.github.rwx.render.canvas

import com.corrodinggames.rts.game.ColorMode
import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine
import com.corrodinggames.rts.gameFramework.graphics.TeamColorTexture
import com.corrodinggames.rts.gameFramework.graphics.Texture
import de.fabmax.kool.KoolSystem
import io.github.rwx.geometry.Rect
import io.github.rwx.geometry.RectF
import java.io.File
import java.lang.management.CompilationMXBean
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import com.sun.management.ThreadMXBean
import kotlinx.serialization.json.*

/**
 * Explicit, windowless CPU comparison entry point. Not a JUnit test and never run by core:test.
 *
 * Run the SAME compiled helper and dependencies against two frozen core jars, in independent
 * sequential ABBA JVMs. Example: --run --mode=mixed --draws=512 --warmup=1200 --blocks=5 --frames=120.
 * Modes white/shadow/team/tint use ordinary GraphicsEngine draws; tiled/repeat exercise those APIs.
 * transform and transform-mixed additionally save, rotate around the draw's center, scale around
 * that same center, draw, and restore for EVERY draw, using the public GraphicsEngine overloads.
 * Their finite deterministic rotation/uniform-and-nonuniform scaling inputs exercise the actual
 * pivoted transform allocation path. They do not reproduce a complete game's unit renderer.
 * mixed is a representative fixture using JFR caller weights 214:191:180:24 for team-body,
 * white-turret, alpha128-shadow and tinted-effect. Those caller weights do NOT measure actual paint
 * field values, fast-path hit rates, or exact game draw counts. This fixture is not a full game.
 *
 * Real Texture, KoolPaint, TeamColorTexture, KoolGraphicsEngine and CPU resource store paths run.
 * team/mixed require an assets/shaders directory in the working directory; team shader source is
 * loaded normally, without replacing shader factories or setting private/static shader state.
 * No renderer, KoolContext, GPU, native window, game session, audio backend, input, or FPS is used.
 *
 * Current-thread CPU time and allocated bytes cover draw+snapshot, freeze, and lease.close separately.
 * Their sum excludes timer bookkeeping and correctness hashing; it is not end-to-end throughput.
 * Every timed frame's complete emitted command fields and frozen resource bytes are SHA256 checked
 * OUTSIDE timing, with the lease still open. Warm-up and final phase sweeps must also agree. The
 * reusable digest buffer avoids allocating strings/byte arrays per field; validation still consumes
 * CPU and may affect caches/GC, identically in both arms. Report all blocks, not just the best one.
 * Compilation and collector totals are read only at warm/sample BLOCK boundaries, outside timing.
 * Their deltas include the block's validation/helper work; they do not identify compiled methods,
 * prove C2 compilation, precisely locate GC within a stage, or establish steady state on their own.
 */
object KoolTexturePaintCpuComparison {
    @Volatile private var sink = 0L

    private data class Options(
        val mode: String = "mixed",
        val draws: Int = 512,
        val warmup: Int = 1200,
        val blocks: Int = 5,
        val frames: Int = 120,
        val phases: Int = 32,
    )

    private class Counters {
        var cpuNanos = 0L
        var wallNanos = 0L
        var allocatedBytes = 0L
        var calls = 0

        fun json(): JsonObject = buildJsonObject {
            put("calls", calls)
            put("cpuNanos", cpuNanos)
            put("wallNanos", wallNanos)
            put("allocatedBytes", allocatedBytes)
            put("cpuNanosPerFrame", cpuNanos.toDouble() / calls)
            put("wallNanosPerFrame", wallNanos.toDouble() / calls)
            put("allocatedBytesPerFrame", allocatedBytes.toDouble() / calls)
        }
    }

    private data class Signatures(val recorded: ByteArray, val frozen: ByteArray)
    private data class Phase(
        val source: Rect,
        val destinations: Array<RectF>,
        val tiledDestinations: Array<Rect>,
        val white: KoolPaint,
        val shadow: KoolPaint,
        val tint: KoolPaint,
        val localTransforms: FloatArray?,
    )

    private class Fixture(val options: Options) : AutoCloseable {
        val store = KoolCanvasCpuTextureStore()
        val engine = KoolGraphicsEngine(textureStore = store)
        val image = makeTexture(false)
        val shadowImage = makeTexture(true)
        val teams: Array<Texture> = if (options.mode == "team" || options.mode == "mixed" || options.mode == "transform-mixed") {
            check(File("assets/shaders/plain.vert").isFile && File("assets/shaders/pureGreenTeamColor.frag").isFile) {
                "Run team/mixed from a working directory containing the real assets/shaders files"
            }
            arrayOf(
                TeamColorTexture(image, 0xff3366cc.toInt(), ColorMode.pureGreen, 0),
                TeamColorTexture(image, 0xffdd5533.toInt(), ColorMode.pureGreen, 1),
            )
        } else emptyArray()
        val phases = Array(options.phases) { phase ->
            val linear = phase % 2 == 0
            Phase(
                Rect((phase % 2) * 16, ((phase / 2) % 2) * 16, (phase % 2) * 16 + 16, ((phase / 2) % 2) * 16 + 16),
                Array(options.draws) { index ->
                    val left = (index % 40) * 24f + phase * .125f
                    val top = ((index / 40) % 28) * 24f - phase * .0625f
                    RectF(left, top, left + if (options.mode == "repeat") 48f else 16f, top + 16f)
                },
                Array(options.draws) { index ->
                    val left = (index % 12) * 64 + phase % 4
                    val top = ((index / 12) % 20) * 32 - phase % 3
                    Rect(left, top, left + 64, top + 32)
                },
                KoolPaint().apply {
                    a(linear)
                    // Texture canonicalization already discards these on ordinary white draws;
                    // team-effect draws must retain them. Both paths are part of the fixture.
                    if (phase % 4 == 0) {
                        m = KoolPaint.Style.STROKE; o = 3f; q = 23f; p = KoolPaint.Align.RIGHT
                    }
                },
                KoolPaint().apply { a(linear); n = 0x80ffffff.toInt() },
                KoolPaint().apply {
                    a(linear)
                    n = (0xa0 shl 24) or ((40 + phase) shl 16) or (0x99 shl 8) or (100 + phase)
                    m = KoolPaint.Style.FILL_AND_STROKE; o = 2f; q = 19f
                },
                if (options.mode == "transform" || options.mode == "transform-mixed") {
                    // The destination rectangle is prebuilt too; only the public graphics calls
                    // and their ordinary per-draw pivot arithmetic belong to the timed recorder.
                    val angles = floatArrayOf(-180f, -90f, -45f, -0f, 0f, 22.5f, 45f, 90f, 135f, 180f, 270f, 359.9375f)
                    val scales = floatArrayOf(.8f, 1f, 1.25f, .5f, 1.5f)
                    FloatArray(options.draws * 3).also { values ->
                        repeat(options.draws) { index ->
                            val scaleX = scales[(index + phase) % scales.size]
                            values[index * 3] = angles[(index + phase) % angles.size]
                            values[index * 3 + 1] = scaleX
                            values[index * 3 + 2] = if (index % 3 == 0) scaleX * 1.125f else scaleX
                        }
                    }
                } else null,
            )
        }
        val categoryCounts: IntArray = IntArray(4).also { counts ->
            repeat(options.draws) { counts[category(it)]++ }
        }
        val expectedCommands = options.draws * if (options.mode == "tiled") 2 else 1
        val expectedQuads = options.draws * when (options.mode) { "tiled" -> 2; "repeat" -> 3; else -> 1 }

        private fun makeTexture(shadow: Boolean): Texture = engine.a(32, 32, true).apply {
            j = IntArray(32 * 32) { index ->
                if (shadow) ((80 + index % 176) shl 24)
                else (0xff shl 24) or (((index * 7) and 0xff) shl 16) or (((index * 13) and 0xff) shl 8) or (index and 0xff)
            }
        }

        private fun category(index: Int): Int = when (options.mode) {
            "team" -> 0
            "shadow" -> 2
            "tint" -> 3
            "mixed", "transform-mixed" -> {
                // Spread the complete 609-weight distribution over ANY requested draw count.
                val bucket = index.toLong() * 609L / options.draws
                when { bucket < 214 -> 0; bucket < 405 -> 1; bucket < 585 -> 2; else -> 3 }
            }
            else -> 1
        }

        fun record(phaseIndex: Int): KoolCanvasFrame {
            val phase = phases[phaseIndex]
            engine.beginFrame(1024, 768)
            engine.k()
            engine.b(phaseIndex * .25f, -phaseIndex * .125f)
            val scale = 1f + (phaseIndex % 5) * .125f
            engine.a(scale, scale)
            engine.a(Rect(-32, -32, 1056, 800))
            val transforms = phase.localTransforms
            repeat(options.draws) { index ->
                val category = category(index)
                val texture = when (category) { 0 -> teams[phaseIndex % teams.size]; 2 -> shadowImage; else -> image }
                val paint = when (category) { 2 -> phase.shadow; 3 -> phase.tint; else -> phase.white }
                if (transforms != null) {
                    val destination = phase.destinations[index]
                    val pivotX = destination.a + 8f
                    val pivotY = destination.b + 8f
                    engine.k()
                    engine.a(transforms[index * 3], pivotX, pivotY)
                    engine.a(transforms[index * 3 + 1], transforms[index * 3 + 2], pivotX, pivotY)
                }
                if (category == 2) engine.beginDrawRole(GraphicsEngine.DRAW_ROLE_UNIT_SHADOW, index.toLong())
                when (options.mode) {
                    "tiled" -> engine.a(texture, phase.tiledDestinations[index], paint)
                    "repeat" -> engine.a(texture, phase.source, phase.destinations[index], paint, 3)
                    else -> engine.a(texture, phase.source, phase.destinations[index], paint)
                }
                if (category == 2) engine.endDrawRole()
                if (transforms != null) engine.l()
            }
            engine.l()
            return engine.snapshot()
        }

        fun freeze(frame: KoolCanvasFrame, phase: Int): FrameEnvelope =
            store.freezeFrame(frame, phase.toLong() + 1, 1, phase * 60, phase.toLong())

        override fun close() { store.close() }
    }

    private inline fun <T> measure(bean: ThreadMXBean, thread: Long, counters: Counters, action: () -> T): T {
        val allocatedStart = bean.getThreadAllocatedBytes(thread)
        val cpuStart = bean.currentThreadCpuTime
        val wallStart = System.nanoTime()
        val result = action()
        val wallEnd = System.nanoTime()
        val cpuEnd = bean.currentThreadCpuTime
        val allocatedEnd = bean.getThreadAllocatedBytes(thread)
        check(cpuEnd >= cpuStart && allocatedEnd >= allocatedStart)
        counters.cpuNanos += cpuEnd - cpuStart
        counters.wallNanos += wallEnd - wallStart
        counters.allocatedBytes += allocatedEnd - allocatedStart
        counters.calls++
        return result
    }

    @JvmStatic fun main(args: Array<String>) {
        if ("--run" !in args) {
            println("Explicit CPU-only entry: pass --run --mode=white|shadow|team|tint|tiled|repeat|mixed|transform|transform-mixed")
            return
        }
        System.setProperty("java.awt.headless", "true")
        val options = parse(args)
        for (name in listOf("RWX_CANVAS_TRACE", "RWX_FRAME_METRICS", "RWX_ENGINE_TIMING_TRACE", "RWX_VULKAN_TRACE"))
            require(System.getenv(name).isNullOrBlank()) { "Disable $name for CPU comparison" }
        require(System.getenv("RWX_REUSE_PAINT_SNAPSHOTS") != "1") { "Keep the independently rejected snapshot-cache candidate OFF" }
        require(System.getenv("RWX_PACKED_ROOT_TEXTURE_COMMANDS") != "1") { "This fixture compares ordinary commands" }
        check(!KoolSystem.isInitialized || KoolSystem.getContextOrNull() == null)
        val bean = checkNotNull(ManagementFactory.getPlatformMXBean(ThreadMXBean::class.java))
        check(bean.isThreadAllocatedMemorySupported && bean.isCurrentThreadCpuTimeSupported)
        if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
        if (!bean.isThreadCpuTimeEnabled) bean.isThreadCpuTimeEnabled = true
        val thread = Thread.currentThread().threadId()
        val compiler = ManagementFactory.getCompilationMXBean()
        val collectors = ManagementFactory.getGarbageCollectorMXBeans()
        val hasher = RawFieldsHash()
        Fixture(options).use { fixture ->
            val expected = Array(options.phases) { phase -> signatures(fixture, phase, hasher) }
            val phaseDigest = MessageDigest.getInstance("SHA-256").also { digest ->
                expected.forEach { digest.update(it.recorded); digest.update(it.frozen) }
            }.digest().hex()
            val warmBlocks = buildJsonArray {
                var warmed = 0
                repeat(4) { block ->
                    val count = options.warmup / 4 + if (block < options.warmup % 4) 1 else 0
                    val counters = Counters()
                    val vmBefore = vmProgress(compiler, collectors)
                    measure(bean, thread, counters) {
                        repeat(count) {
                            val phase = warmed++ % options.phases
                            val frame = fixture.record(phase)
                            check(frame.commands.size == fixture.expectedCommands)
                            fixture.freeze(frame, phase).use { envelope -> sink = sink xor envelope.frame.commands.size.toLong() }
                        }
                    }
                    val vmAfter = vmProgress(compiler, collectors)
                    add(buildJsonObject {
                        put("index", block); put("frames", count)
                        put("cpuNanosPerFrame", counters.cpuNanos.toDouble() / count)
                        put("allocatedBytesPerFrame", counters.allocatedBytes.toDouble() / count)
                        put("wallNanosPerFrame", counters.wallNanos.toDouble() / count)
                        put("vmProgressBefore", vmBefore); put("vmProgressAfter", vmAfter)
                        put("vmProgressDelta", vmDelta(vmBefore, vmAfter))
                    })
                }
            }
            var validatedFrames = 0
            var actualCommands = 0L
            val blocks = buildJsonArray {
                repeat(options.blocks) { block ->
                    val record = Counters(); val freeze = Counters(); val close = Counters()
                    val vmBefore = vmProgress(compiler, collectors)
                    repeat(options.frames) { index ->
                        val phase = (block * options.frames + index) % options.phases
                        val frame = measure(bean, thread, record) { fixture.record(phase) }
                        val envelope = measure(bean, thread, freeze) { fixture.freeze(frame, phase) }
                        try {
                            check(frame.commands.size == fixture.expectedCommands)
                            check(envelope.frame.commands.size == fixture.expectedCommands)
                            hasher.recorded(frame)
                            check(hasher.result.contentEquals(expected[phase].recorded)) { "Recorded raw fields changed at block=$block frame=$index phase=$phase" }
                            hasher.frozen(envelope)
                            check(hasher.result.contentEquals(expected[phase].frozen)) { "Frozen raw fields/resources changed at block=$block frame=$index phase=$phase" }
                            validatedFrames++
                            actualCommands += frame.commands.size
                            sink = sink xor hasher.result[phase % hasher.result.size].toLong()
                        } finally { measure(bean, thread, close) { envelope.close() } }
                    }
                    val vmAfter = vmProgress(compiler, collectors)
                    add(buildJsonObject {
                        put("index", block)
                        put("drawAndSnapshot", record.json()); put("freeze", freeze.json()); put("leaseClose", close.json())
                        put("freezeAndCloseCpuNanosPerFrame", (freeze.cpuNanos + close.cpuNanos).toDouble() / options.frames)
                        put("freezeAndCloseAllocatedBytesPerFrame", (freeze.allocatedBytes + close.allocatedBytes).toDouble() / options.frames)
                        put("stageSumCpuNanosPerFrame", (record.cpuNanos + freeze.cpuNanos + close.cpuNanos).toDouble() / options.frames)
                        put("stageSumAllocatedBytesPerFrame", (record.allocatedBytes + freeze.allocatedBytes + close.allocatedBytes).toDouble() / options.frames)
                        put("vmProgressBefore", vmBefore); put("vmProgressAfter", vmAfter)
                        put("vmProgressDelta", vmDelta(vmBefore, vmAfter))
                    })
                }
            }
            repeat(options.phases) { phase ->
                val actual = signatures(fixture, phase, hasher)
                check(actual.recorded.contentEquals(expected[phase].recorded))
                check(actual.frozen.contentEquals(expected[phase].frozen))
            }
            check(validatedFrames == options.blocks * options.frames)
            check(actualCommands == validatedFrames.toLong() * fixture.expectedCommands)
            check(!KoolSystem.isInitialized || KoolSystem.getContextOrNull() == null)
            println("RWXTexturePaintCpuComparison " + buildJsonObject {
                put("schema", "rwx-texture-paint-cpu-comparison-v1")
                put("mode", options.mode); put("drawCallsPerFrame", options.draws)
                put("perDrawPivotedTransform", options.mode == "transform" || options.mode == "transform-mixed")
                put("pivotedRotateAndScaleCallsPerFrame", if (options.mode == "transform" || options.mode == "transform-mixed") options.draws * 2 else 0)
                put("perDrawTransformOrder", "save, rotateAroundCenter, scaleAroundCenter, draw, restore")
                put("finiteTransformInputsOnly", true)
                put("commandsPerFrame", fixture.expectedCommands); put("logicalQuadsPerFrame", fixture.expectedQuads)
                put("phases", options.phases); put("warmupFrames", options.warmup)
                put("blocks", options.blocks); put("sampleFramesPerBlock", options.frames)
                put("validatedSampleFrames", validatedFrames); put("actualSampleCommands", actualCommands)
                put("completePhaseFieldsAndResourceBytesSha256", phaseDigest)
                put("phaseSignatures", buildJsonArray { expected.forEachIndexed { phase, signature ->
                    add(buildJsonObject { put("phase", phase); put("recordedSha256", signature.recorded.hex()); put("frozenSha256", signature.frozen.hex()) })
                } })
                put("categoriesPerFrame", buildJsonObject {
                    put("teamBody", fixture.categoryCounts[0]); put("ordinaryWhite", fixture.categoryCounts[1])
                    put("alpha128Shadow", fixture.categoryCounts[2]); put("tintedEffect", fixture.categoryCounts[3])
                })
                put("warmupBlocks", warmBlocks); put("sampleBlocks", blocks)
                put("javaRuntimeVersion", System.getProperty("java.runtime.version"))
                put("coreCodeSource", KoolGraphicsEngine::class.java.protectionDomain.codeSource.location.toString())
                put("snapshotCacheOptIn", System.getenv("RWX_REUSE_PAINT_SNAPSHOTS") == "1")
                put("cpuOnly", true); put("windowOrGpuOrAudioOrInputStarted", false)
                put("gameplayOrFpsMeasured", false); put("timerAndDigestExcludedFromStageSum", true)
                put("syntheticRepresentativeDrawFixture", true); put("sink", sink)
                put("vmProgressSampling", "warm/sample block boundaries outside stage timing; whole-block VM totals include validation/helper work")
            })
        }
    }

    private fun signatures(fixture: Fixture, phase: Int, hasher: RawFieldsHash): Signatures {
        val frame = fixture.record(phase)
        check(frame.commands.size == fixture.expectedCommands)
        hasher.recorded(frame)
        val recorded = hasher.result.copyOf()
        return fixture.freeze(frame, phase).use { envelope ->
            check(envelope.frame.commands.size == fixture.expectedCommands)
            hasher.frozen(envelope)
            Signatures(recorded, hasher.result.copyOf())
        }
    }

    private fun vmProgress(compiler: CompilationMXBean?, collectors: List<GarbageCollectorMXBean>): JsonObject = buildJsonObject {
        val supported = compiler?.isCompilationTimeMonitoringSupported == true
        put("compilationTimeMonitoringSupported", supported)
        if (supported) put("compilationTotalTimeMillis", checkNotNull(compiler).totalCompilationTime)
        else put("compilationTotalTimeMillis", JsonNull)
        put("garbageCollectors", buildJsonArray {
            for (collector in collectors) add(buildJsonObject {
                put("name", collector.name)
                put("collectionCount", collector.collectionCount)
                put("collectionTimeMillis", collector.collectionTime)
            })
        })
    }

    private fun vmDelta(before: JsonObject, after: JsonObject): JsonObject = buildJsonObject {
        val compilationBefore = before.getValue("compilationTotalTimeMillis").jsonPrimitive.longOrNull
        val compilationAfter = after.getValue("compilationTotalTimeMillis").jsonPrimitive.longOrNull
        if (compilationBefore != null && compilationAfter != null && compilationAfter >= compilationBefore)
            put("compilationTimeMillis", compilationAfter - compilationBefore)
        else put("compilationTimeMillis", JsonNull)
        val previous = before.getValue("garbageCollectors").jsonArray.associate { element ->
            val row = element.jsonObject
            row.getValue("name").jsonPrimitive.content to row
        }
        put("garbageCollectors", buildJsonArray {
            for (element in after.getValue("garbageCollectors").jsonArray) {
                val row = element.jsonObject
                val name = row.getValue("name").jsonPrimitive.content
                val old = previous[name]
                add(buildJsonObject {
                    put("name", name)
                    for (field in listOf("collectionCount", "collectionTimeMillis")) {
                        val a = old?.get(field)?.jsonPrimitive?.longOrNull
                        val b = row.getValue(field).jsonPrimitive.longOrNull
                        if (a != null && b != null && a >= 0 && b >= a) put(field, b - a)
                        else put(field, JsonNull)
                    }
                })
            }
        })
    }

    private fun parse(args: Array<String>): Options {
        var options = Options()
        for (arg in args) {
            if (arg == "--run") continue
            val key = arg.substringBefore('=')
            val value = arg.substringAfter('=', "")
            options = when (key) {
                "--mode" -> options.copy(mode = value)
                "--draws" -> options.copy(draws = value.toInt())
                "--warmup" -> options.copy(warmup = value.toInt())
                "--blocks" -> options.copy(blocks = value.toInt())
                "--frames" -> options.copy(frames = value.toInt())
                "--phases" -> options.copy(phases = value.toInt())
                else -> error("Unknown option $arg")
            }
        }
        require(options.mode in setOf("white", "shadow", "team", "tint", "tiled", "repeat", "mixed", "transform", "transform-mixed"))
        require(options.draws in 64..8192 && options.phases in 4..128)
        require(options.warmup >= options.phases * 8 && options.blocks in 2..50 && options.frames >= options.phases)
        return options
    }

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Complete raw field stream, fixed big-endian scalars and nullable UTF-16 code-unit strings. */
    private class RawFieldsHash {
        private val digest = MessageDigest.getInstance("SHA-256")
        private val buffer = ByteArray(4096)
        private var position = 0
        val result = ByteArray(32)

        private fun byte(value: Int) {
            buffer[position++] = value.toByte()
            if (position == buffer.size) flush()
        }
        private fun flush() { if (position > 0) { digest.update(buffer, 0, position); position = 0 } }
        private fun integer(value: Int) { byte(value ushr 24); byte(value ushr 16); byte(value ushr 8); byte(value) }
        private fun long(value: Long) { integer((value ushr 32).toInt()); integer(value.toInt()) }
        private fun float(value: Float) { integer(value.toRawBits()) }
        private fun bool(value: Boolean) { byte(if (value) 1 else 0) }
        private fun text(value: String?) {
            integer(value?.length ?: -1)
            if (value != null) for (character in value) { byte(character.code ushr 8); byte(character.code) }
        }
        private fun begin(tag: String) { digest.reset(); position = 0; text(tag) }
        private fun finish() { flush(); check(digest.digest(result, 0, result.size) == result.size) }

        fun recorded(frame: KoolCanvasFrame) { begin("recorded-v1"); frame(frame); finish() }
        fun frozen(envelope: FrameEnvelope) {
            begin("frozen-v1")
            long(envelope.sequence); long(envelope.generation); integer(envelope.simulationTick); long(envelope.viewportRevision)
            check(envelope.camera == null) { "This CPU fixture does not generate a game camera snapshot" }
            bool(false)
            frame(envelope.frame)
            val resources = envelope.resourceLease.resources()
            integer(resources.size)
            for ((id, resource) in resources) { text(id.value); resource(resource) }
            val fonts = envelope.resourceLease.fonts
            bool(fonts.baseVersion != null); fonts.baseVersion?.let { long(it) }
            integer(fonts.keyedVersions.size)
            for ((key, version) in fonts.keyedVersions) { text(key); long(version) }
            finish()
        }

        private fun frame(frame: KoolCanvasFrame) {
            integer(frame.viewport.width); integer(frame.viewport.height)
            bool(frame.visualStats != null)
            frame.visualStats?.let { integer(it.selectedUnits); integer(it.visibleUnits); bool(it.adaptiveBattleVisuals) }
            integer(frame.commands.size)
            for (command in frame.commands) when (command) {
                is KoolCanvasCommand.DrawTexture -> {
                    integer(1); texture(command.texture); rect(command.source); rect(command.destination)
                    paint(command.paint); state(command.state); bool(command.sourceIsFullTexture)
                    float(command.sourceU0); float(command.sourceV0); float(command.sourceU1); float(command.sourceV1)
                }
                is KoolCanvasCommand.DrawTextureRepeat -> {
                    integer(2); texture(command.texture); rect(command.source); rect(command.destination)
                    integer(command.repeat); paint(command.paint); state(command.state)
                }
                else -> error("Unexpected command in ordinary texture fixture: ${command::class.java.name}")
            }
        }

        private fun texture(texture: KoolCanvasTextureRef) {
            text(texture.id.value); integer(texture.width); integer(texture.height)
            bool(texture.hasAlpha); bool(texture.requiresOrderedAlpha); bool(texture.premultipliedAlpha)
            bool(texture.frozenPixelIdentity != null)
            texture.frozenPixelIdentity?.let { long(it.recordingOwnerId); text(it.logicalTextureId.value) }
            float(texture.widthFloat); float(texture.heightFloat); float(texture.safeWidthFloat); float(texture.safeHeightFloat)
            float(texture.inverseSafeWidth); float(texture.inverseSafeHeight); rect(texture.fullRect)
        }

        private fun paint(paint: KoolCanvasPaint) {
            integer(paint.color.argb); float(paint.alphaMultiplier); integer(paint.style.ordinal)
            float(paint.strokeWidth); integer(paint.textureFilter.ordinal); integer(paint.blendMode.ordinal)
            float(paint.textSize); integer(paint.textAlign.ordinal); text(paint.typefaceKey)
            when (val effect = paint.textureEffect) {
                null -> integer(0)
                is KoolCanvasTextureEffect.TeamColor -> { integer(1); integer(effect.mode.ordinal); integer(effect.color.argb); float(effect.amount) }
                is KoolCanvasTextureEffect.Displacement -> { integer(2); texture(effect.screenBase); float(effect.offsetBy) }
            }
        }

        private fun rect(rect: KoolCanvasRect) { float(rect.left); float(rect.top); float(rect.right); float(rect.bottom) }
        private fun state(state: KoolCanvasState) {
            val transform = state.transform
            float(transform.scaleX); float(transform.skewY); float(transform.skewX); float(transform.scaleY)
            float(transform.translateX); float(transform.translateY)
            bool(state.clip != null); state.clip?.let { rect(it) }
            text(state.renderTarget?.value); integer(state.drawRole.ordinal); long(state.semanticUnitId)
        }

        private fun resource(resource: FrozenCanvasResource) {
            when (resource) {
                is FrozenCanvasResource.Pixels -> {
                    integer(1); bool(resource.isStatic)
                    integer(resource.image.width); integer(resource.image.height); bool(resource.image.premultipliedAlpha)
                    integer(resource.image.pixels.size); for (pixel in resource.image.pixels) integer(pixel)
                }
                is FrozenCanvasResource.Asset -> {
                    integer(2); text(resource.path); integer(resource.encodedBytes.size)
                    for (value in resource.encodedBytes) byte(value.toInt())
                }
                is FrozenCanvasResource.Frame -> { integer(3); frame(resource.frame) }
                is FrozenCanvasResource.GpuTarget -> {
                    integer(4); text(resource.logicalId.value); integer(resource.width); integer(resource.height); frame(resource.frame)
                }
                FrozenCanvasResource.Missing -> integer(5)
            }
        }
    }
}
