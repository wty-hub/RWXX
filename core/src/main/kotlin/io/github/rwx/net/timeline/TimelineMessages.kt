package io.github.rwx.net.timeline

/** Profile-1 message syntax. Authentication, stream receipts and authorization are checked separately. */
object TimelineMessages {
    private const val BOOL = 1
    private const val U32 = 2
    private const val U64 = 3
    private const val I64 = 4
    private const val TEXT = 5
    private const val BYTES = 6
    private const val RECORD = 7
    private const val MIN_BUDGET = 16_384L
    private const val MAX_BUDGET = 16_777_216L
    private val schemas = mapOf(
        1 to intArrayOf(U32, TEXT, TEXT), 2 to intArrayOf(BYTES, BOOL),
        3 to intArrayOf(U32, U32, U64, U64, U32), 4 to intArrayOf(U32),
        5 to intArrayOf(U32, U64, U64, U64, U64, U32, BYTES, U64, RECORD, U32),
        6 to intArrayOf(U32, U64, U64, U64, BYTES), 7 to intArrayOf(U64, U64),
        8 to intArrayOf(U32, U64, U64, U64, U32, BYTES), 9 to intArrayOf(U32, U64, U64, U32),
        10 to intArrayOf(U32, U32),
        101 to intArrayOf(U32, U32, U32, U32, BYTES, BYTES, U64, U64, U64, U32, U32, BYTES, U64, U32),
        102 to intArrayOf(U32, TEXT), 103 to intArrayOf(BYTES, U64),
        104 to intArrayOf(BYTES, U64, BYTES), 105 to intArrayOf(BYTES),
        106 to intArrayOf(U32, U64, U32, U64, U64, U64, U64, U64, U64, U64, U64, BYTES, U64),
        107 to intArrayOf(U32, U64, U64, U64, BYTES), 108 to intArrayOf(U32, U64, U64),
        109 to intArrayOf(U32, U64, U64, U64, BYTES),
        110 to intArrayOf(U32, U32, U32, U32, U64, U64, U32, U64, U64),
        111 to intArrayOf(U64, U64, U64), 112 to intArrayOf(U32, U64, U32, U64, U64),
    )

    fun validate(frame: TimelineFrame, fromClient: Boolean) {
        TimelineBinary.validateRecord(frame.body)
        val types = schemas[frame.type] ?: throw ProtocolException("Unknown timeline message type")
        protocolRequire(if (fromClient) frame.type in 1..10 else frame.type in 101..112, "Wrong message direction")
        checkFields(frame.body, types.mapIndexed { index, kind -> index + 1 to kind }.toMap())
        val r = frame.body
        when (frame.type) {
            1 -> {
                protocolRequire(r.number(1) == 1L, "Unsupported timeline profile")
                val name = r.text(2)
                protocolRequire(name.toByteArray(Charsets.UTF_8).size in 1..128 && name.any { !blank(it) }, "Invalid player name")
                protocolRequire(r.text(3).matches(Regex("[A-Za-z0-9_-]{32,128}")), "Invalid seat credential format")
            }
            2 -> r.bytes(1).sizeIs(32)
            3 -> {
                positive(r.number(1)); val mode = r.number(2)
                protocolRequire(mode in 1L..2L, "Invalid timeline mode")
                val lo = r.number(3); val hi = r.number(4)
                protocolRequire(if (mode == 1L) lo == 0L && hi == 0L else lo < hi && hi <= TimelineProtocol.MAX_TIME, "Invalid timeline request window")
                budget(r.number(5))
            }
            4 -> positive(r.number(1))
            5 -> command(r)
            6 -> { (1..4).forEach { positive(r.number(it)) }; selection(r.bytes(5), allowEmpty = true) }
            7 -> positive(r.number(1))
            8 -> {
                (1..4).forEach { positive(r.number(it)) }
                protocolRequire(r.number(5) in 0L..1L, "Invalid state ACK status")
                r.bytes(6).sizeIs(if (r.number(5) == 0L) 16 else 0)
            }
            9 -> { positive(r.number(1)); positive(r.number(2)); protocolRequire(r.number(4) in 1L..2L, "Invalid restore reason") }
            10 -> { positive(r.number(1)); budget(r.number(2)) }
            101 -> {
                protocolRequire(r.number(1) == 1L && r.number(2) in 0L..63L && r.number(3) in 1L..1000L && r.number(4) == 1024L, "Invalid welcome profile")
                r.bytes(5).sizeIs(16); r.bytes(6).sizeIs(16); r.bytes(12).sizeIs(16)
                protocolRequire(r.number(7) <= TimelineProtocol.MAX_TIME && r.number(13) <= r.number(8) && r.number(8) <= r.number(7), "Invalid welcome authorization window")
                protocolRequire(r.number(9) in 1..r.number(3) * 1024 && r.number(10) in 1L..120L && r.number(11) in 0L..500L && r.number(14) in 1L..2L, "Invalid welcome limits")
            }
            102 -> protocolRequire(r.number(1) in 1L..5L && r.text(2).toByteArray(Charsets.UTF_8).size in 1..1024, "Invalid rejection")
            103 -> { r.bytes(1).sizeIs(32); protocolRequire(r.number(2) in 1L..TimelineProtocol.MAX_BLOB_BYTES.toLong(), "Invalid map size") }
            104 -> { r.bytes(1).sizeIs(32); chunk(r.number(2), r.bytes(3)) }
            105 -> r.bytes(1).sizeIs(32)
            106 -> header(r)
            107 -> { (1..3).forEach { positive(r.number(it)) }; chunk(r.number(4), r.bytes(5)) }
            108 -> (1..3).forEach { positive(r.number(it)) }
            109 -> { (1..4).forEach { positive(r.number(it)) }; r.bytes(5).sizeIs(16) }
            110 -> {
                positive(r.number(1)); protocolRequire(r.number(2) in 0L..3L && r.number(4) in 1L..2L, "Invalid stream status")
                protocolRequire(r.number(8) <= r.number(9) && r.number(9) <= TimelineProtocol.MAX_TIME, "Invalid history range")
                if (r.number(2) == 0L) {
                    positive(r.number(3)); protocolRequire(r.number(5) < r.number(6), "Invalid accepted window"); budget(r.number(7))
                } else protocolRequire(r.number(3) == 0L && (5..7).all { r.number(it) == 0L }, "Failed stream status contains a live identity")
            }
            111 -> { positive(r.number(1)); protocolRequire(r.number(3) <= TimelineProtocol.MAX_TIME, "Invalid server time") }
            112 -> {
                positive(r.number(1)); positive(r.number(2)); protocolRequire(r.number(3) in 1L..4L && r.number(4) <= r.number(5), "Invalid stream end")
            }
        }
    }

    fun header(record: TimelineRecord): TimelineHeader {
        TimelineBinary.validateRecord(record)
        checkFields(record, schemas.getValue(106).mapIndexed { index, kind -> index + 1 to kind }.toMap())
        (1..2).forEach { positive(record.number(it)) }
        positive(record.number(4))
        val kind = when (record.number(3)) { 1L -> TimelineTransactionKind.RESET; 2L -> TimelineTransactionKind.UPDATE; else -> throw ProtocolException("Invalid transaction kind") }
        record.bytes(12).sizeIs(32)
        protocolRequire(record.number(11) in 1L..TimelineProtocol.MAX_BLOB_BYTES.toLong(), "Invalid operation size")
        protocolRequire(record.number(8) <= record.number(7) && record.number(7) <= record.number(6) && record.number(6) <= TimelineProtocol.MAX_TIME && record.number(8) < record.number(9), "Invalid timeline watermarks")
        protocolRequire(if (kind == TimelineTransactionKind.RESET) record.number(4) == 1L && record.number(5) == 0L else record.number(5) > 0 && record.number(5) < Long.MAX_VALUE && record.number(4) == record.number(5) + 1, "Invalid transaction revision")
        return TimelineHeader(record.number(1), record.number(2), kind, record.number(4), record.number(5),
            record.number(6), record.number(7), record.number(8), record.number(9), record.number(10),
            record.number(11), record.bytes(12), record.number(13))
    }

    fun numberField(id: Int, value: Long, u32: Boolean = false) = TimelineField(id, true,
        if (u32) TimelineTlvValue.U32(value) else TimelineTlvValue.U64(value))
    fun bytesField(id: Int, value: TimelineBytes) = TimelineField(id, true, TimelineTlvValue.Bytes(value))
    fun textField(id: Int, value: String) = TimelineField(id, true, TimelineTlvValue.Text(value))
    fun boolField(id: Int, value: Boolean) = TimelineField(id, true, TimelineTlvValue.Bool(value))
    fun recordField(id: Int, value: TimelineRecord) = TimelineField(id, true, TimelineTlvValue.Record(value))

    fun checkFields(record: TimelineRecord, required: Map<Int, Int>) {
        var previous = 0
        for (field in record.fields) {
            protocolRequire(field.id > previous && field.id in 1..32767, "Noncanonical message fields")
            previous = field.id
            val expected = required[field.id]
            if (expected == null) protocolRequire(!field.critical, "Unknown critical message field")
            else protocolRequire(field.critical && field.value.kind == expected, "Invalid message field type or criticality")
        }
        protocolRequire(required.keys.all { record[it] != null }, "Missing message field")
    }

    fun selection(bytes: TimelineBytes, allowEmpty: Boolean): List<Long> {
        val values = TimelineBinary.decodeSelection(bytes)
        protocolRequire(allowEmpty || values.isNotEmpty(), "Empty command selection")
        return values
    }

    private fun command(r: TimelineRecord) {
        (1..5).forEach { positive(r.number(it)) }
        val kind = r.number(6); val flags = r.number(10)
        protocolRequire(kind in 1L..5L && flags in 0L..3L, "Invalid command kind or flags")
        val selected = selection(r.bytes(7), allowEmpty = false)
        val target = r.record(9)
        val tag = target.number(1)
        val targetFields = when (tag) { 0L -> mapOf(1 to U32); 1L -> mapOf(1 to U32, 2 to I64, 3 to I64)
            2L -> mapOf(1 to U32, 4 to U64); else -> throw ProtocolException("Invalid target tag") }
        checkFields(target, targetFields)
        protocolRequire((1..4).all { it in targetFields || target[it] == null }, "Target carries fields from a different tag")
        if (tag == 2L) positive(target.number(4))
        if (kind == 5L) {
            positive(r.number(8)); protocolRequire(selected.size == 1, "Ability requires one unit")
            if (flags and 2L != 0L) protocolRequire(tag == 0L && flags and 1L == 0L, "Invalid cancel command")
        } else {
            protocolRequire(r.number(8) == 0L && flags and 2L == 0L, "Basic command carries an ability")
            protocolRequire(when (kind) { 1L, 2L -> tag == 1L; 3L -> tag == 0L; else -> tag == 2L }, "Command target mismatch")
            if (kind == 3L || kind == 4L) protocolRequire(flags == 0L, "Basic command cannot queue")
        }
    }

    private fun chunk(offset: Long, data: TimelineBytes) {
        protocolRequire(data.size in 1..TimelineProtocol.MAX_CHUNK_BYTES && offset >= 0 &&
            offset <= TimelineProtocol.MAX_BLOB_BYTES.toLong() - data.size, "Invalid transfer chunk")
    }
    private fun budget(value: Long) = protocolRequire(value in MIN_BUDGET..MAX_BUDGET, "Invalid stream byte budget")
    private fun positive(value: Long) = protocolRequire(value > 0, "Expected positive identity")
    private fun TimelineBytes.sizeIs(expected: Int) = protocolRequire(size == expected, "Invalid fixed byte length")
    private fun blank(c: Char): Boolean = c == '\u0020' || c == '\u00a0' || c == '\u1680' ||
        c in '\u2000'..'\u200a' || c == '\u2028' || c == '\u2029' || c == '\u202f' || c == '\u205f' || c == '\u3000'
}

fun TimelineRecord.number(id: Int): Long = when (val value = get(id)?.value) {
    is TimelineTlvValue.U32 -> value.value
    is TimelineTlvValue.U64 -> value.value
    is TimelineTlvValue.I64 -> value.value
    else -> throw ProtocolException("Expected integer field")
}
fun TimelineRecord.bytes(id: Int): TimelineBytes = (get(id)?.value as? TimelineTlvValue.Bytes)?.value
    ?: throw ProtocolException("Expected byte field")
fun TimelineRecord.text(id: Int): String = (get(id)?.value as? TimelineTlvValue.Text)?.value
    ?: throw ProtocolException("Expected text field")
fun TimelineRecord.record(id: Int): TimelineRecord = (get(id)?.value as? TimelineTlvValue.Record)?.value
    ?: throw ProtocolException("Expected record field")
fun TimelineRecord.boolean(id: Int): Boolean = (get(id)?.value as? TimelineTlvValue.Bool)?.value
    ?: throw ProtocolException("Expected boolean field")
