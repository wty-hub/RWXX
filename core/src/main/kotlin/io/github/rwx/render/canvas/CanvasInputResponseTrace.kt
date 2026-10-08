package io.github.rwx.render.canvas

import java.io.File

/** Opt-in normal session input → owner adoption → accepted picture trace; never drives the camera. */
internal object CanvasInputResponseTrace {
    private val writer = System.getenv("RWX_INPUT_RESPONSE_TRACE")?.takeIf(String::isNotBlank)?.let { path ->
        File(path).also { it.absoluteFile.parentFile?.mkdirs() }.bufferedWriter().apply {
            write("stage,id,kind,value,sampledNanos,appliedNanos,producedNanos,acceptedPresentNanos,generation,sequence,cameraX,cameraY,zoom,expectsCamera\n")
        }
    }
    private data class Input(val id: Long, val kind: String, val value: Int, val sampled: Long, val expectsCamera: Boolean,
        var applied: Long = 0, var x: Float = Float.NaN, var y: Float = Float.NaN, var zoom: Float = Float.NaN)
    private data class Picture(val envelopeSequence: Long, val generation: Long, val produced: Long,
        val x: Float, val y: Float, val zoom: Float, val inputs: LongArray)
    private val pending = LinkedHashMap<Long, Input>()
    private val awaitingEffect = LinkedHashMap<Long, Input>()
    private var middleDown = false
    private val pictures = LinkedHashMap<Pair<Long, Long>, Picture>()
    private var serial = 0L
    private var closed = false

    @Synchronized fun sampled(kind: String, value: Int): Long {
        if (writer == null || closed) return 0
        val expects = when {
            kind == "pointer/3" -> (value == 1 && middleDown).also { middleDown = value == 1 }
            kind.startsWith("key/") -> value == 1 && kind.substringAfter('/').toIntOrNull() in setOf(19, 20, 21, 22)
            kind == "wheel" -> value != 0
            else -> false
        }
        val input = Input(++serial, kind.replace(',', '_'), value, System.nanoTime(), expects)
        pending[input.id] = input
        write("sampled", input)
        if (pending.size > 4096) {
            val oldest = pending.entries.iterator()
            write("unpresented-overflow", oldest.next().value)
            oldest.remove()
        }
        return input.id
    }

    @Synchronized fun applied(id: Long, x: Float, y: Float, zoom: Float) {
        if (id == 0L) return
        pending[id]?.let { input ->
            input.applied = System.nanoTime(); input.x = x; input.y = y; input.zoom = zoom
            write("applied", input)
        }
    }

    @Synchronized fun produced(envelope: FrameEnvelope) {
        if (writer == null || closed || pending.isEmpty() && awaitingEffect.isEmpty()) return
        val ids = (pending.values.asSequence() + awaitingEffect.values.asSequence())
            .filter { it.applied != 0L }.map { it.id }.toList().toLongArray()
        if (ids.isEmpty()) return
        val camera = envelope.camera
        pictures[envelope.generation to envelope.sequence] = Picture(envelope.sequence, envelope.generation,
            System.nanoTime(), camera?.x ?: Float.NaN, camera?.y ?: Float.NaN, camera?.zoom ?: Float.NaN, ids)
        // A discarded mailbox frame is never an accepted picture. Later frames retain every input
        // still awaiting presentation, so dropping this index entry cannot manufacture a low latency.
        while (pictures.size > 512) pictures.entries.iterator().let { it.next(); it.remove() }
    }

    @Synchronized fun presented(sequence: Long, generation: Long, now: Long) {
        if (writer == null || closed) return
        val picture = pictures.remove(generation to sequence) ?: return
        for (id in picture.inputs) {
            pending.remove(id)?.let { input ->
                write("presented", input, picture, now)
                if (input.expectsCamera) awaitingEffect[id] = input
            }
            awaitingEffect[id]?.let { input ->
                val changed = if (input.kind == "wheel") kotlin.math.abs(picture.zoom - input.zoom) > 1e-5f
                    else kotlin.math.abs(picture.x - input.x) > 1e-5f || kotlin.math.abs(picture.y - input.y) > 1e-5f
                if (changed) {
                    write("camera-effect", input, picture, now); awaitingEffect.remove(id)
                } else if (now - input.sampled >= 200_000_000L) {
                    write("camera-effect-timeout", input, picture, now); awaitingEffect.remove(id)
                }
            }
        }
        writer.flush()
    }

    private fun write(stage: String, input: Input, picture: Picture? = null, present: Long = 0) {
        writer?.write("$stage,${input.id},${input.kind},${input.value},${input.sampled},${input.applied}," +
            "${picture?.produced ?: 0},$present,${picture?.generation ?: -1},${picture?.envelopeSequence ?: -1}," +
            "${picture?.x ?: input.x},${picture?.y ?: input.y},${picture?.zoom ?: input.zoom},${input.expectsCamera}\n")
    }

    @Synchronized fun close() {
        if (closed) return
        pending.values.forEach { write("unpresented-at-close", it) }
        awaitingEffect.values.forEach { write("camera-effect-unpresented-at-close", it) }
        closed = true
        writer?.close()
    }
}
