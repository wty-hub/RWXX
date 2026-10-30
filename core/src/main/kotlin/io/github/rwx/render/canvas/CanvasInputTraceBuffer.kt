package io.github.rwx.render.canvas

import java.io.Writer

/** Fixed diagnostic rows; the render/owner threads neither format CSV nor touch the filesystem. */
internal class CanvasInputTraceBuffer(private val capacity: Int) {
    init { require(capacity > 0 && capacity <= 1_048_576) }
    private val stages = arrayOfNulls<String>(capacity)
    private val kinds = arrayOfNulls<String>(capacity)
    private val data = LongArray(capacity * WIDTH)
    private var count = 0
    private var dropped = 0L
    private var closed = false

    // CanvasInputResponseTrace provides synchronization for append and close.
    fun append(stage: String, id: Long, kind: String, value: Int, sampled: Long, applied: Long,
               produced: Long, present: Long, generation: Long, sequence: Long,
               x: Float, y: Float, zoom: Float, expectsCamera: Boolean) {
        if (closed) return
        if (count == capacity) { dropped++; return }
        stages[count] = stage; kinds[count] = kind
        val offset = count++ * WIDTH
        data[offset] = id; data[offset + 1] = value.toLong(); data[offset + 2] = sampled
        data[offset + 3] = applied; data[offset + 4] = produced; data[offset + 5] = present
        data[offset + 6] = generation; data[offset + 7] = sequence
        data[offset + 8] = x.toRawBits().toLong(); data[offset + 9] = y.toRawBits().toLong()
        data[offset + 10] = zoom.toRawBits().toLong(); data[offset + 11] = if (expectsCamera) 1L else 0L
    }

    fun closeTo(writer: Writer) {
        if (closed) return
        closed = true
        writer.use { output ->
            output.write("stage,id,kind,value,sampledNanos,appliedNanos,producedNanos,acceptedPresentNanos,generation,sequence,cameraX,cameraY,zoom,expectsCamera\n")
            repeat(count) { index ->
                val offset = index * WIDTH
                output.write("${stages[index]},${data[offset]},${kinds[index]},${data[offset + 1]}," +
                    "${data[offset + 2]},${data[offset + 3]},${data[offset + 4]},${data[offset + 5]}," +
                    "${data[offset + 6]},${data[offset + 7]},${Float.fromBits(data[offset + 8].toInt())}," +
                    "${Float.fromBits(data[offset + 9].toInt())},${Float.fromBits(data[offset + 10].toInt())},${data[offset + 11] != 0L}\n")
            }
            if (dropped > 0) output.write("trace-overflow,-1,diagnostic,$dropped,0,0,0,0,-1,-1,NaN,NaN,NaN,false\n")
            output.write("trace-complete,-1,diagnostic,$count,0,0,0,0,-1,-1,NaN,NaN,NaN,false\n")
        }
    }

    private companion object { const val WIDTH = 12 }
}
