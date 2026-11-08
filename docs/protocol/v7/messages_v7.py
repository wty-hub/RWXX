#!/usr/bin/env python3
"""Strict profile-1 message syntax and bounded transaction byte assembly.

This is not a network client, authorization checker, or timeline state machine.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import re
from typing import Mapping, Sequence
import wire_v7 as w

SCHEMAS = {
    1: (2, 5, 5), 2: (6, 1), 3: (2, 2, 3, 3, 2), 4: (2,),
    5: (2, 3, 3, 3, 3, 2, 6, 3, 7, 2), 6: (2, 3, 3, 3, 6),
    7: (3, 3), 8: (2, 3, 3, 3, 2, 6), 9: (2, 3, 3, 2), 10: (2, 2),
    101: (2, 2, 2, 2, 6, 6, 3, 3, 3, 2, 2, 6, 3, 2), 102: (2, 5),
    103: (6, 3), 104: (6, 3, 6), 105: (6,),
    106: (2, 3, 2, 3, 3, 3, 3, 3, 3, 3, 3, 6, 3),
    107: (2, 3, 3, 3, 6), 108: (2, 3, 3), 109: (2, 3, 3, 3, 6),
    110: (2, 2, 2, 2, 3, 3, 2, 3, 3), 111: (3, 3, 3), 112: (2, 3, 2, 3, 3),
}
DIRECTIONS = {kind: "C2S" if kind < 100 else "S2C" for kind in SCHEMAS}
KEY = re.compile(r"[A-Za-z0-9_.-]*\Z")
TOKEN = re.compile(r"[A-Za-z0-9_-]{32,128}\Z")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise w.ProtocolError(message)


def fields(record: Sequence[w.Field], schema: Mapping[int, int], optional: Mapping[int, int] | None = None) -> dict:
    optional = optional or {}
    rules = {key: w.FieldRule(kind, required=True, critical=True) for key, kind in schema.items()}
    rules.update({key: w.FieldRule(kind, critical=False) for key, kind in optional.items()})
    w.validate_schema(record, rules)
    return {item.field_id: item.value for item in record}


def positive(*values: int) -> None:
    require(all(value > 0 for value in values), "Expected positive identity")


def fixed(value: bytes, length: int) -> None:
    require(len(value) == length, "Wrong fixed byte length")


def budget(value: int) -> None:
    require(16_384 <= value <= 16_777_216, "Invalid stream budget")


def chunk(offset: int, data: bytes) -> None:
    require(1 <= len(data) <= 65_536 and offset <= w.MAX_BLOB_BYTES - len(data), "Invalid transfer chunk")


class Cursor:
    def __init__(self, data: bytes):
        self.reader = w._Reader(data)

    @property
    def remaining(self):
        return self.reader.remaining

    def u8(self):
        return self.reader.unpack(">B")

    def vuint(self):
        return w._read_uleb128(self.reader)

    def vsint(self):
        return w._read_zigzag63(self.reader)

    def take(self, count):
        return bytes(self.reader.take(count))

    def vbytes(self, maximum=w.MAX_BLOB_BYTES):
        count = self.vuint()
        require(count <= maximum, "Byte string exceeds limit")
        return self.take(count)

    def record(self, maximum=w.MAX_BLOB_BYTES):
        return w.decode_tlv(self.vbytes(maximum))

    def finish(self):
        self.reader.finish()


def selection(data: bytes, allow_empty=True) -> tuple[int, ...]:
    c = Cursor(data)
    count = c.vuint()
    require(count <= 256 and count <= c.remaining and (allow_empty or count > 0), "Invalid selection count")
    result = tuple(c.vuint() for _ in range(count))
    require(all(value > 0 and (i == 0 or value > result[i - 1]) for i, value in enumerate(result)), "Selection is not strictly increasing")
    c.finish()
    return result


def validate_message(frame: w.Frame, direction: str) -> None:
    require(direction in ("C2S", "S2C"), "Unknown direction")
    require(frame.message_type in SCHEMAS and DIRECTIONS[frame.message_type] == direction, "Unknown message or wrong direction")
    r = fields(frame.fields, dict(enumerate(SCHEMAS[frame.message_type], 1)))
    t = frame.message_type
    if t == 1:
        blank = lambda c: c in " \u00a0\u1680\u2028\u2029\u202f\u205f\u3000" or "\u2000" <= c <= "\u200a"
        require(r[1] == 1 and 1 <= len(r[2].encode("utf-8")) <= 128 and any(not blank(c) for c in r[2]), "Invalid hello profile/name")
        require(TOKEN.fullmatch(r[3]) is not None, "Invalid credential format")
    elif t == 2:
        fixed(r[1], 32)
    elif t == 3:
        positive(r[1]); require(r[2] in (1, 2), "Invalid stream mode")
        require((r[3] == r[4] == 0) if r[2] == 1 else r[3] < r[4] <= w.MAX_TIME, "Invalid requested window")
        budget(r[5])
    elif t == 4:
        positive(r[1])
    elif t == 5:
        positive(*(r[i] for i in range(1, 6)))
        require(1 <= r[6] <= 5 and 0 <= r[10] <= 3, "Invalid command kind/flags")
        selected = selection(r[7], False)
        target_fields = {f.field_id: f for f in r[9]}
        tag_field = target_fields.get(1)
        require(tag_field is not None and tag_field.kind == w.U32, "Invalid target tag")
        tag = tag_field.value
        require(tag in (0, 1, 2), "Unknown target tag")
        schema = {0: {1: w.U32}, 1: {1: w.U32, 2: w.I64, 3: w.I64}, 2: {1: w.U32, 4: w.U64}}[tag]
        target = fields(r[9], schema)
        require(all(key in schema for key in target_fields if key in (1, 2, 3, 4)), "Target contains another branch's fields")
        if tag == 2:
            positive(target[4])
        if r[6] == 5:
            positive(r[8]); require(len(selected) == 1, "Ability needs one subject")
            if r[10] & 2:
                require(tag == 0 and not r[10] & 1, "Invalid cancellation target/queue flag")
        else:
            require(r[8] == 0 and not r[10] & 2, "Basic command contains capability/cancel")
            require(tag == (1 if r[6] in (1, 2) else 0 if r[6] == 3 else 2), "Command target differs")
            if r[6] in (3, 4):
                require(r[10] == 0, "Basic command cannot queue")
    elif t == 6:
        positive(*(r[i] for i in range(1, 5))); selection(r[5])
    elif t == 7:
        positive(r[1])
    elif t == 8:
        positive(*(r[i] for i in range(1, 5)))
        require(r[5] in (0, 1), "Invalid check status"); fixed(r[6], 16 if r[5] == 0 else 0)
    elif t == 9:
        positive(r[1], r[2]); require(r[4] in (1, 2), "Invalid restore reason")
    elif t == 10:
        positive(r[1]); budget(r[2])
    elif t == 101:
        require(r[1] == 1 and 0 <= r[2] <= 63 and 1 <= r[3] <= 1000 and r[4] == 1024, "Invalid welcome profile")
        for i in (5, 6, 12):
            fixed(r[i], 16)
        require(r[13] <= r[8] <= r[7] <= w.MAX_TIME, "Invalid welcome authorization window")
        require(1 <= r[9] <= r[3] * 1024 and 1 <= r[10] <= 120 and 0 <= r[11] <= 500 and r[14] in (1, 2), "Invalid welcome limits")
    elif t == 102:
        require(1 <= r[1] <= 5 and 1 <= len(r[2].encode("utf-8")) <= 1024, "Invalid rejection")
    elif t == 103:
        fixed(r[1], 32); require(1 <= r[2] <= w.MAX_BLOB_BYTES, "Invalid map size")
    elif t == 104:
        fixed(r[1], 32); chunk(r[2], r[3])
    elif t == 105:
        fixed(r[1], 32)
    elif t == 106:
        positive(r[1], r[2], r[4]); require(r[3] in (1, 2), "Invalid transaction kind")
        fixed(r[12], 32); require(1 <= r[11] <= w.MAX_BLOB_BYTES, "Invalid operation length")
        require(r[8] <= r[7] <= r[6] <= w.MAX_TIME and r[8] < r[9], "Invalid watermarks")
        require((r[4] == 1 and r[5] == 0) if r[3] == 1 else 0 < r[5] < w.MAX_U63 and r[4] == r[5] + 1, "Invalid revision/base")
    elif t == 107:
        positive(r[1], r[2], r[3]); chunk(r[4], r[5])
    elif t == 108:
        positive(r[1], r[2], r[3])
    elif t == 109:
        positive(r[1], r[2], r[3], r[4]); fixed(r[5], 16)
    elif t == 110:
        positive(r[1]); require(0 <= r[2] <= 3 and r[4] in (1, 2), "Invalid stream status")
        require(r[8] <= r[9] <= w.MAX_TIME, "Invalid history range")
        if r[2] == 0:
            positive(r[3]); require(r[5] < r[6], "Empty accepted window"); budget(r[7])
        else:
            require(r[3] == r[5] == r[6] == r[7] == 0, "Failed stream carries identity/window")
    elif t == 111:
        positive(r[1]); require(r[3] <= w.MAX_TIME, "Invalid server time")
    elif t == 112:
        positive(r[1], r[2]); require(1 <= r[3] <= 4 and r[4] <= r[5], "Invalid stream end")


def encode_message(frame: w.Frame, direction: str) -> bytes:
    validate_message(frame, direction)
    return w.encode_frame(frame)


def decode_message(data: bytes, direction: str) -> w.Frame:
    frame = w.decode_frame(data)
    validate_message(frame, direction)
    return frame


def validate_ability(record: Sequence[w.Field]) -> None:
    r = fields(record, {1: 2, 2: 2, 3: 1, 4: 5, 5: 5, 6: 4, 7: 2}, {9: 7, 10: 7})
    require(1 <= r[1] <= 24 and 0 <= r[2] <= 2 and 0 <= r[7] <= 9, "Invalid ability enumeration")
    require(1 <= len(r[4]) <= 128 and len(r[5]) <= 128 and all(KEY.fullmatch(r[i]) for i in (4, 5)), "Invalid ability key")
    require(r[6] >= -1, "Invalid credits cost")
    for id in (9, 10):
        if id in r:
            cost = fields(r[id], {i: w.I64 for i in range(1, 6)})
            require(all(cost[i] >= -1 for i in range(1, 6)), "Invalid resource cost")
            if id == 9:
                require(cost[1] == r[6], "Divergent credits cost")


def validate_descriptor(kind: int, record: Sequence[w.Field]) -> None:
    require(kind in range(5), "Unknown object kind")
    schema = {2: w.U64, 5: w.BYTES} if kind == 2 else {2: w.U64, 3: w.U64, 4: w.RECORD} if kind == 3 else {}
    r = fields(record, schema, {1: w.TEXT})
    require(all(id in schema for id in r if id in (2, 3, 4, 5)), "Forbidden descriptor reference")
    if 1 in r:
        require(len(r[1].encode("utf-8")) <= 128, "Descriptor key too long")
    for id in (2, 3):
        if id in r:
            positive(r[id])
    if 4 in r:
        validate_ability(r[4])
    if 5 in r:
        selection(r[5])


def validate_metadata(mode: int, record: Sequence[w.Field], ticks_per_second=1000) -> None:
    r = fields(record, {1: w.U64} if mode == w.PULSE else {})
    if mode == w.PULSE:
        require(r[1] <= ticks_per_second * 1024 * 10, "Tail exceeds ten seconds")
    else:
        require(1 not in r, "Non-pulse metadata contains tail")


def validate_map(record: Sequence[w.Field]) -> None:
    r = fields(record, {i: w.BYTES if i in (6, 8, 9, 10) else w.U32 for i in range(1, 11)})
    require(r[1] == r[7] == 1 and 1 <= r[2] <= 1024 and 1 <= r[3] <= 1024 and
            1 <= r[4] <= 4096 and 1 <= r[5] <= 4096, "Invalid map dimensions/catalog")
    cells = r[2] * r[3]
    fixed(r[6], cells)
    require(all(value <= 15 for value in r[6]), "Unknown terrain flags")
    for id in (8, 9):
        fixed(r[id], cells * 2)
        require(all(int.from_bytes(r[id][i:i + 2], "big") <= 234 for i in range(0, cells * 2, 2)), "Unknown terrain tile")
    cursor = Cursor(r[10]); count = cursor.vuint()
    require(count <= cells and count <= cursor.remaining // 2, "Invalid resource pool count")
    pools = []; previous = -1
    for _ in range(count):
        x, y = cursor.vuint(), cursor.vuint(); index = y * r[2] + x
        require(x < r[2] and y < r[3] and index > previous, "Invalid resource pool order/location")
        pools.append(index); previous = index
    cursor.finish()
    require(pools == [i for i, value in enumerate(r[6]) if value & 4], "Resource pools differ from terrain")


def read_segment(c: Cursor, mode: int, value_type: int) -> w.Segment:
    lo, hi, flags = c.vuint(), c.vuint(), c.u8()
    require(flags in (0, 1), "Invalid prediction flag")
    if mode == w.STEP:
        result = w.Segment(lo, hi, bool(flags), value=w._read_value(c.reader, value_type))
    else:
        a0, a1 = c.vuint(), c.vuint()
        result = w.Segment(lo, hi, bool(flags), anchor0_time=a0, anchor1_time=a1,
                           value0=w._read_value(c.reader, value_type), value1=w._read_value(c.reader, value_type))
    w.encode_segment(mode, value_type, result)
    return result


def read_pulse(c: Cursor, value_type: int) -> w.Pulse:
    result = w.Pulse(c.vuint(), c.vuint(), w._read_value(c.reader, value_type))
    w.encode_pulse(value_type, result)
    return result


def validate_operations(data: bytes, initial_formats=None, allow_missing_context=False) -> dict:
    """Validate operation byte grammar, not object authority or sealed-history semantics."""
    c = Cursor(data); count = c.vuint(); formats = dict(initial_formats or {})
    require(count <= 2_097_152 and count <= c.remaining // 2, "Invalid operation count")
    missing = False
    for _ in range(count):
        opcode = c.u8(); require(1 <= opcode <= 6, "Unknown opcode")
        body = Cursor(c.vbytes(1_048_576))
        if opcode == 1:
            handle, kind, start = body.vuint(), body.u8(), body.vuint()
            require(kind in range(5) and ((handle == 0) == (kind == 0)) and start <= w.MAX_TIME, "Invalid object identity/time")
            validate_descriptor(kind, body.record(1_048_576))
        elif opcode == 2:
            handle, attribute = body.vuint(), body.vuint()
            mode, value_type, flags, high_water = body.u8(), body.u8(), body.u8(), body.vuint()
            require(1 <= attribute <= 0x7fffffff and mode in range(1, 5) and flags in (0, 1), "Invalid curve declaration")
            w._mode_type(mode, value_type, pulse=mode == w.PULSE)
            require(mode == w.PULSE or high_water == 0, "Non-pulse high water")
            validate_metadata(mode, body.record(1_048_576))
            require((handle, attribute) not in formats, "Duplicate curve declaration")
            formats[handle, attribute] = (mode, value_type)
        elif opcode in (3, 4):
            handle, attribute, revision, start, end, records = (body.vuint() for _ in range(6))
            require(1 <= attribute <= 0x7fffffff and revision < w.MAX_U63 and start <= w.MAX_TIME and end > start,
                    "Invalid splice header")
            require(records <= 4096 and records <= body.remaining // (4 if opcode == 3 else 3), "Invalid splice count")
            form = formats.get((handle, attribute))
            if form is None:
                missing = True
                if records:
                    body.take(body.remaining)
            else:
                mode, value_type = form
                require((opcode == 4) == (mode == w.PULSE), "Splice kind differs from curve")
                previous_end = start; previous_event = (-1, -1); ids = set()
                for _ in range(records):
                    if opcode == 3:
                        segment = read_segment(body, mode, value_type)
                        require(segment.lo >= previous_end and segment.hi <= end, "Overlapping/out-of-range segment")
                        previous_end = segment.hi
                    else:
                        event = read_pulse(body, value_type)
                        require(start <= event.time < end and (event.time, event.event_id) > previous_event and event.event_id not in ids,
                                "Unordered/duplicate/out-of-range event")
                        previous_event = event.time, event.event_id; ids.add(event.event_id)
        elif opcode == 5:
            handle, end, kind = body.vuint(), body.vuint(), body.u8()
            require(handle > 0 and end <= w.MAX_TIME and kind in (1, 2), "Invalid observation close")
        else:
            require(body.vuint() <= w.MAX_TIME, "Invalid retain time")
        body.finish()
    c.finish()
    require(allow_missing_context or not missing, "Missing curve type context")
    return formats


@dataclass(frozen=True)
class Transaction:
    header: w.Frame
    operations: bytes


class TransactionAssembler:
    """At most four independently staged streams; nothing is returned before Commit.

    This validates framing/SHA and operation syntax only. Passing a transaction does
    not mean it is applicable to any existing library, authorized, or fully checked.
    """
    def __init__(self, max_staging_bytes=64 * 1024 * 1024):
        require(type(max_staging_bytes) is int and 1 <= max_staging_bytes <= w.MAX_BLOB_BYTES * 4, "Invalid staging budget")
        self.maximum = max_staging_bytes
        self.staged: dict[int, tuple[w.Frame, bytearray]] = {}

    def accept(self, frame: w.Frame, initial_formats=None) -> Transaction | None:
        validate_message(frame, "S2C")
        require(frame.message_type in (106, 107, 108, 112), "Not a transaction/end message")
        r = {f.field_id: f.value for f in frame.fields}; stream = r[1]
        if frame.message_type == 106:
            require(stream not in self.staged and len(self.staged) < 4, "Transaction already staged/stream limit")
            reserved = sum({f.field_id: f.value for f in h.fields}[11] for h, _ in self.staged.values())
            require(reserved + r[11] <= self.maximum, "Aggregate staging limit")
            self.staged[stream] = frame, bytearray(); return None
        if frame.message_type == 112:
            if stream in self.staged:
                h = {f.field_id: f.value for f in self.staged[stream][0].fields}
                require(r[2] == h[2] and r[3] in (1, 3), "StreamEnd cannot abort this transaction")
                del self.staged[stream]
            return None
        require(stream in self.staged, "Chunk/Commit without Begin")
        header, data = self.staged[stream]; h = {f.field_id: f.value for f in header.fields}
        require(all(r[i] == h[i] for i in (1, 2)) and r[3] == h[4], "Transaction identity differs")
        if frame.message_type == 107:
            require(r[4] == len(data) and len(data) + len(r[5]) <= h[11], "Chunk offset/size differs")
            data.extend(r[5]); return None
        require(len(data) == h[11] and hashlib.sha256(data).digest() == h[12], "Incomplete transaction/digest mismatch")
        raw = bytes(data)
        validate_operations(raw, {} if h[3] == 1 else initial_formats, allow_missing_context=h[3] == 2)
        del self.staged[stream]
        return Transaction(header, raw)


def verify_vectors(path) -> dict:
    import json
    from pathlib import Path
    document = json.loads(Path(path).read_text(encoding="utf-8"))
    require(document.get("wireVersion") == 7, "Fixture version differs")
    accepted = rejected = 0
    kinds = set()
    for case in document["vectors"]:
        raw = bytes.fromhex(case["hex"])
        try:
            frame = decode_message(raw, case["direction"])
        except w.ProtocolError:
            require(not case["accepted"], "Valid message fixture rejected")
            rejected += 1
            continue
        require(case["accepted"], "Invalid message fixture accepted")
        require(frame.message_type == case["messageType"] and encode_message(frame, case["direction"]) == raw,
                "Message fixture did not round trip")
        accepted += 1; kinds.add(frame.message_type)
    require(kinds == set(SCHEMAS), "Message fixture does not cover all 22 types")
    return {"passed": True, "wireVersion": 7, "messageTypes": len(kinds), "accepted": accepted, "rejected": rejected}


def verify_transaction_vectors(path) -> dict:
    import json
    from pathlib import Path
    document = json.loads(Path(path).read_text(encoding="utf-8"))
    require(document.get("wireVersion") == 7, "Fixture version differs")
    accepted = rejected = 0
    for case in document["vectors"]:
        initial = {(int(item[0]), int(item[1])): (item[2], item[3]) for item in case.get("initialFormats", [])}
        try:
            result = validate_operations(bytes.fromhex(case["operationsHex"]), initial,
                                         allow_missing_context=case.get("allowMissingContext", False))
            actual = [[str(handle), str(attribute), mode, value_type] for (handle, attribute), (mode, value_type) in sorted(result.items())]
        except w.ProtocolError:
            require(not case["accepted"], "Valid operation fixture rejected")
            rejected += 1
            continue
        require(case["accepted"] and actual == case["expectedFormats"], "Operation fixture result differs")
        accepted += 1
    return {"passed": True, "wireVersion": 7, "accepted": accepted, "rejected": rejected}


if __name__ == "__main__":
    import argparse
    import json
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--verify")
    modes.add_argument("--verify-transactions")
    options = parser.parse_args()
    result = verify_vectors(options.verify) if options.verify else verify_transaction_vectors(options.verify_transactions)
    print(json.dumps(result, indent=2))
