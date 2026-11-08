#!/usr/bin/env python3
"""Offline RWXT v7 framing, canonical values, and integer curve sampling.

This module does not implement message semantics, state reconstruction, a network
client, or state digests. Unknown optional fields remain in the decoded record.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import struct
from typing import Any, Mapping, Sequence

MAGIC = b"RWXT"
VERSION = 7
MAX_FRAME_PAYLOAD = 1_048_576
MAX_BLOB_BYTES = 64 * 1024 * 1024
MAX_CURVE_BLOB_BYTES = 65_536
MAX_DEPTH = 8
MAX_FIELDS = 256
MAX_RECORDS = 65_536
MAX_U63 = (1 << 63) - 1
MAX_TIME = MAX_U63 - 1
MIN_ZIGZAG63 = -(1 << 62)
MAX_ZIGZAG63 = (1 << 62) - 1

BOOL, U32, U64, I64, TEXT, BYTES, RECORD, RECORDS = range(1, 9)
SINT, VEC3, UINT, VALUE_BOOL, BLOB = range(1, 6)
STEP, LINEAR, ANGLE, PULSE = range(1, 5)
CRITICAL = 1


class ProtocolError(ValueError):
    """A malformed or unsupported encoding, without partial state application."""


@dataclass(frozen=True)
class Field:
    field_id: int
    flags: int
    kind: int
    value: Any


@dataclass(frozen=True)
class FieldRule:
    kind: int
    required: bool = False
    fields: Mapping[int, "FieldRule"] | None = None
    critical: bool | None = None


@dataclass(frozen=True)
class Frame:
    message_type: int
    fields: tuple[Field, ...]


@dataclass(frozen=True)
class Segment:
    lo: int
    hi: int
    predicted: bool
    value: Any = None
    anchor0_time: int | None = None
    anchor1_time: int | None = None
    value0: Any = None
    value1: Any = None


@dataclass(frozen=True)
class Pulse:
    time: int
    event_id: int
    value: Any


def _integer(value: Any, lo: int, hi: int, label: str) -> int:
    if type(value) is not int or not lo <= value <= hi:
        raise ProtocolError(f"Invalid {label}")
    return value


def _boolean(value: Any) -> bool:
    if type(value) is not bool:
        raise ProtocolError("Boolean value required")
    return value


def _bytes(value: Any) -> bytes:
    if not isinstance(value, (bytes, bytearray, memoryview)):
        raise ProtocolError("Byte sequence required")
    if len(value) > MAX_BLOB_BYTES:
        raise ProtocolError("Blob size limit exceeded")
    return bytes(value)


def _text(value: Any) -> bytes:
    if type(value) is not str:
        raise ProtocolError("Text value required")
    if any(ord(c) < 0x20 or 0x7F <= ord(c) <= 0x9F for c in value):
        raise ProtocolError("Control character in text")
    try:
        result = value.encode("utf-8", errors="strict")
    except UnicodeError as exc:
        raise ProtocolError("Invalid UTF-8 text") from exc
    if len(result) > MAX_BLOB_BYTES:
        raise ProtocolError("Text size limit exceeded")
    return result


class _Reader:
    def __init__(self, data: bytes | bytearray | memoryview):
        if not isinstance(data, (bytes, bytearray, memoryview)):
            raise ProtocolError("Byte sequence required")
        if len(data) > MAX_BLOB_BYTES:
            raise ProtocolError("Blob size limit exceeded")
        self.data = memoryview(data).cast("B")
        self.offset = 0

    @property
    def remaining(self) -> int:
        return len(self.data) - self.offset

    def take(self, count: int) -> memoryview:
        if count < 0 or count > self.remaining:
            raise ProtocolError("Truncated value")
        start = self.offset
        self.offset += count
        return self.data[start:self.offset]

    def unpack(self, fmt: str) -> Any:
        size = struct.calcsize(fmt)
        return struct.unpack(fmt, self.take(size))[0]

    def finish(self) -> None:
        if self.remaining:
            raise ProtocolError("Trailing bytes")


def _append(target: bytearray, value: bytes | bytearray | memoryview) -> None:
    if len(target) + len(value) > MAX_BLOB_BYTES:
        raise ProtocolError("Blob size limit exceeded")
    target.extend(value)


def _depth(depth: int) -> None:
    if not 1 <= depth <= MAX_DEPTH:
        raise ProtocolError("Record depth limit exceeded")


def encode_tlv(fields: Sequence[Field], *, _level: int = 1) -> bytes:
    """Encode a canonical record. Field ordering is checked, never repaired."""
    _depth(_level)
    if not isinstance(fields, (tuple, list)) or len(fields) > MAX_FIELDS:
        raise ProtocolError("Field count limit exceeded")
    output = bytearray()
    previous = 0
    for field in fields:
        if not isinstance(field, Field):
            raise ProtocolError("Field required")
        field_id = _integer(field.field_id, 1, 32767, "field id")
        if field_id <= previous:
            raise ProtocolError("Field ids must be strictly increasing")
        previous = field_id
        flags = _integer(field.flags, 0, CRITICAL, "field flags")
        kind = _integer(field.kind, 1, 255, "field kind")
        value = field.value
        if kind == BOOL:
            body = bytes([int(_boolean(value))])
        elif kind == U32:
            body = struct.pack(">I", _integer(value, 0, (1 << 32) - 1, "u32"))
        elif kind == U64:
            body = struct.pack(">Q", _integer(value, 0, MAX_U63, "u64"))
        elif kind == I64:
            body = struct.pack(">q", _integer(value, -(1 << 63), MAX_U63, "i64"))
        elif kind == TEXT:
            body = _text(value)
        elif kind == BYTES:
            body = _bytes(value)
        elif kind == RECORD:
            body = encode_tlv(value, _level=_level + 1)
        elif kind == RECORDS:
            if not isinstance(value, (tuple, list)) or len(value) > MAX_RECORDS:
                raise ProtocolError("Record count limit exceeded")
            records = bytearray(struct.pack(">I", len(value)))
            for record in value:
                item = encode_tlv(record, _level=_level + 1)
                _append(records, struct.pack(">I", len(item)))
                _append(records, item)
            body = bytes(records)
        else:
            if flags & CRITICAL:
                raise ProtocolError("Unknown critical value kind")
            body = _bytes(value)
        _append(output, struct.pack(">HBBI", field_id, flags, kind, len(body)))
        _append(output, body)
    return bytes(output)


def decode_tlv(data: bytes | bytearray | memoryview, *, _level: int = 1) -> tuple[Field, ...]:
    """Decode structural TLV; use validate_schema for known/required field rules."""
    _depth(_level)
    reader = _Reader(data)
    fields: list[Field] = []
    previous = 0
    while reader.remaining:
        if len(fields) >= MAX_FIELDS:
            raise ProtocolError("Field count limit exceeded")
        header = reader.take(8)
        field_id, flags, kind, size = struct.unpack(">HBBI", header)
        if not 1 <= field_id <= 32767 or field_id <= previous:
            raise ProtocolError("Field ids must be strictly increasing and in range")
        if flags & ~CRITICAL:
            raise ProtocolError("Invalid field flags")
        if not kind:
            raise ProtocolError("Invalid field kind")
        previous = field_id
        body = reader.take(size)
        fixed_sizes = {BOOL: 1, U32: 4, U64: 8, I64: 8}
        if kind in fixed_sizes and size != fixed_sizes[kind]:
            raise ProtocolError("Wrong fixed-width value length")
        if kind == BOOL:
            if body[0] not in (0, 1):
                raise ProtocolError("Invalid boolean byte")
            value = bool(body[0])
        elif kind == U32:
            value = struct.unpack(">I", body)[0]
        elif kind == U64:
            value = struct.unpack(">Q", body)[0]
            if value > MAX_U63:
                raise ProtocolError("Invalid u64")
        elif kind == I64:
            value = struct.unpack(">q", body)[0]
        elif kind == TEXT:
            try:
                value = bytes(body).decode("utf-8", errors="strict")
            except UnicodeError as exc:
                raise ProtocolError("Invalid UTF-8 text") from exc
            _text(value)
        elif kind == BYTES:
            value = bytes(body)
        elif kind == RECORD:
            value = decode_tlv(body, _level=_level + 1)
        elif kind == RECORDS:
            records_reader = _Reader(body)
            count = records_reader.unpack(">I")
            if count > MAX_RECORDS:
                raise ProtocolError("Record count limit exceeded")
            if count > records_reader.remaining // 4:
                raise ProtocolError("Truncated record list")
            records = []
            for _ in range(count):
                length = records_reader.unpack(">I")
                records.append(decode_tlv(records_reader.take(length), _level=_level + 1))
            records_reader.finish()
            value = tuple(records)
        else:
            if flags & CRITICAL:
                raise ProtocolError("Unknown critical value kind")
            value = bytes(body)
        fields.append(Field(field_id, flags, kind, value))
    return tuple(fields)


def validate_schema(fields: Sequence[Field], schema: Mapping[int, FieldRule]) -> None:
    """Reject unknown critical fields and known-field kind mismatches.

    Optional unknown fields are retained by the caller; this function must not be
    used to filter them from replicated state or future digest inputs.
    """
    encode_tlv(fields)
    present = set()
    for field in fields:
        rule = schema.get(field.field_id)
        if rule is None:
            if field.flags & CRITICAL:
                raise ProtocolError("Unknown critical field")
            continue
        if field.kind != rule.kind:
            raise ProtocolError("Known field has the wrong kind")
        if rule.critical is not None and bool(field.flags & CRITICAL) != _boolean(rule.critical):
            raise ProtocolError("Known field has the wrong critical flag")
        present.add(field.field_id)
        if rule.fields is not None:
            if field.kind == RECORD:
                validate_schema(field.value, rule.fields)
            elif field.kind == RECORDS:
                for record in field.value:
                    validate_schema(record, rule.fields)
            else:
                raise ProtocolError("Nested schema on a non-record field")
    if any(rule.required and field_id not in present for field_id, rule in schema.items()):
        raise ProtocolError("Missing required field")


def encode_frame(frame: Frame) -> bytes:
    message_type = _integer(frame.message_type, 1, 65535, "message type")
    body = encode_tlv(frame.fields)
    payload_size = 8 + len(body)
    if payload_size > MAX_FRAME_PAYLOAD:
        raise ProtocolError("Frame payload size limit exceeded")
    return struct.pack(">I4sHH", payload_size, MAGIC, VERSION, message_type) + body


def decode_frame(data: bytes | bytearray | memoryview) -> Frame:
    reader = _Reader(data)
    length = reader.unpack(">I")
    if not 8 <= length <= MAX_FRAME_PAYLOAD:
        raise ProtocolError("Invalid frame payload length")
    if length != reader.remaining:
        raise ProtocolError("Frame length mismatch")
    if bytes(reader.take(4)) != MAGIC:
        raise ProtocolError("Invalid frame magic")
    if reader.unpack(">H") != VERSION:
        raise ProtocolError("Unsupported wire version")
    message_type = reader.unpack(">H")
    if not message_type:
        raise ProtocolError("Invalid message type")
    fields = decode_tlv(reader.take(reader.remaining))
    return Frame(message_type, fields)


def encode_uleb128(value: int) -> bytes:
    remaining = _integer(value, 0, MAX_U63, "ULEB128 value")
    output = bytearray()
    while remaining >= 128:
        output.append((remaining & 0x7F) | 0x80)
        remaining >>= 7
    output.append(remaining)
    return bytes(output)


def _read_uleb128(reader: _Reader) -> int:
    result = 0
    for index in range(9):
        byte = reader.unpack(">B")
        result |= (byte & 0x7F) << (7 * index)
        if not byte & 0x80:
            if index and byte == 0:
                raise ProtocolError("Redundant ULEB128 representation")
            return result
    raise ProtocolError("ULEB128 length or range exceeded")


def decode_uleb128(data: bytes | bytearray | memoryview) -> int:
    reader = _Reader(data)
    value = _read_uleb128(reader)
    reader.finish()
    return value


def encode_zigzag63(value: int) -> bytes:
    value = _integer(value, MIN_ZIGZAG63, MAX_ZIGZAG63, "ZigZag63 value")
    return encode_uleb128(value * 2 if value >= 0 else -value * 2 - 1)


def _read_zigzag63(reader: _Reader) -> int:
    value = _read_uleb128(reader)
    return value >> 1 if value & 1 == 0 else -(value >> 1) - 1


def decode_zigzag63(data: bytes | bytearray | memoryview) -> int:
    reader = _Reader(data)
    value = _read_zigzag63(reader)
    reader.finish()
    return value


def encode_value(value_type: int, value: Any) -> bytes:
    _integer(value_type, SINT, BLOB, "curve value type")
    if value_type == SINT:
        return encode_zigzag63(value)
    if value_type == VEC3:
        if not isinstance(value, (tuple, list)) or len(value) != 3:
            raise ProtocolError("VEC3 requires three signed integers")
        return b"".join(encode_zigzag63(component) for component in value)
    if value_type == UINT:
        return encode_uleb128(value)
    if value_type == VALUE_BOOL:
        return bytes([int(_boolean(value))])
    if not isinstance(value, (bytes, bytearray, memoryview)):
        raise ProtocolError("Byte sequence required")
    if memoryview(value).nbytes > MAX_CURVE_BLOB_BYTES:
        raise ProtocolError("Curve blob size limit exceeded")
    body = _bytes(value)
    return encode_uleb128(len(body)) + body


def _read_value(reader: _Reader, value_type: int) -> Any:
    _integer(value_type, SINT, BLOB, "curve value type")
    if value_type == SINT:
        return _read_zigzag63(reader)
    if value_type == VEC3:
        return tuple(_read_zigzag63(reader) for _ in range(3))
    if value_type == UINT:
        return _read_uleb128(reader)
    if value_type == VALUE_BOOL:
        value = reader.unpack(">B")
        if value not in (0, 1):
            raise ProtocolError("Invalid boolean byte")
        return bool(value)
    length = _read_uleb128(reader)
    if length > MAX_CURVE_BLOB_BYTES:
        raise ProtocolError("Curve blob size limit exceeded")
    return bytes(reader.take(length))


def decode_value(value_type: int, data: bytes | bytearray | memoryview) -> Any:
    reader = _Reader(data)
    value = _read_value(reader, value_type)
    reader.finish()
    return value


def _mode_type(mode: int, value_type: int, *, pulse: bool = False) -> None:
    _integer(mode, STEP, PULSE, "curve mode")
    _integer(value_type, SINT, BLOB, "curve value type")
    if pulse:
        if mode != PULSE:
            raise ProtocolError("Pulse mode required")
    elif mode == PULSE:
        raise ProtocolError("PULSE has events, not segments")
    if mode == LINEAR and value_type not in (SINT, VEC3, UINT):
        raise ProtocolError("LINEAR requires a numeric value type")
    if mode == ANGLE and value_type != UINT:
        raise ProtocolError("ANGLE requires UINT")


def _check_segment(segment: Segment, mode: int, value_type: int) -> None:
    _mode_type(mode, value_type)
    _integer(segment.lo, 0, MAX_TIME, "segment lo")
    _integer(segment.hi, 0, MAX_U63, "segment hi")
    _boolean(segment.predicted)
    if segment.lo >= segment.hi:
        raise ProtocolError("Empty or reversed segment")
    if mode == STEP:
        if any(v is not None for v in (
            segment.anchor0_time, segment.anchor1_time, segment.value0, segment.value1
        )):
            raise ProtocolError("STEP cannot contain interpolation anchors")
        encode_value(value_type, segment.value)
        return
    if segment.value is not None:
        raise ProtocolError("Interpolated segment cannot contain a step value")
    a0 = _integer(segment.anchor0_time, 0, MAX_U63, "anchor0 time")
    a1 = _integer(segment.anchor1_time, 0, MAX_U63, "anchor1 time")
    if not a0 <= segment.lo < segment.hi <= a1 or a0 >= a1:
        raise ProtocolError("Segment lies outside its interpolation anchors")
    encode_value(value_type, segment.value0)
    encode_value(value_type, segment.value1)
    if a1 == MAX_U63 and segment.value0 != segment.value1:
        raise ProtocolError("FOREVER requires a constant segment")
    if mode == ANGLE:
        _integer(segment.value0, 0, 65535, "angle0")
        _integer(segment.value1, 0, 65535, "angle1")


def encode_segment(mode: int, value_type: int, segment: Segment) -> bytes:
    _check_segment(segment, mode, value_type)
    body = bytearray(encode_uleb128(segment.lo) + encode_uleb128(segment.hi))
    body.append(int(segment.predicted))
    if mode == STEP:
        _append(body, encode_value(value_type, segment.value))
    else:
        _append(body, encode_uleb128(segment.anchor0_time))
        _append(body, encode_uleb128(segment.anchor1_time))
        _append(body, encode_value(value_type, segment.value0))
        _append(body, encode_value(value_type, segment.value1))
    return bytes(body)


def decode_segment(mode: int, value_type: int, data: bytes | bytearray | memoryview) -> Segment:
    _mode_type(mode, value_type)
    reader = _Reader(data)
    lo, hi = _read_uleb128(reader), _read_uleb128(reader)
    flags = reader.unpack(">B")
    if flags not in (0, 1):
        raise ProtocolError("Invalid segment flags")
    if mode == STEP:
        segment = Segment(lo, hi, bool(flags), value=_read_value(reader, value_type))
    else:
        a0, a1 = _read_uleb128(reader), _read_uleb128(reader)
        value0, value1 = _read_value(reader, value_type), _read_value(reader, value_type)
        segment = Segment(lo, hi, bool(flags), anchor0_time=a0, anchor1_time=a1,
                          value0=value0, value1=value1)
    reader.finish()
    _check_segment(segment, mode, value_type)
    return segment


def encode_pulse(value_type: int, pulse: Pulse) -> bytes:
    _mode_type(PULSE, value_type, pulse=True)
    _integer(pulse.time, 0, MAX_TIME, "event time")
    _integer(pulse.event_id, 1, MAX_U63, "event id")
    output = bytearray(encode_uleb128(pulse.time) + encode_uleb128(pulse.event_id))
    _append(output, encode_value(value_type, pulse.value))
    return bytes(output)


def decode_pulse(value_type: int, data: bytes | bytearray | memoryview) -> Pulse:
    _mode_type(PULSE, value_type, pulse=True)
    reader = _Reader(data)
    pulse = Pulse(_read_uleb128(reader), _read_uleb128(reader), _read_value(reader, value_type))
    reader.finish()
    _integer(pulse.time, 0, MAX_TIME, "event time")
    _integer(pulse.event_id, 1, MAX_U63, "event id")
    return pulse


def round_ratio(numerator: int, denominator: int) -> int:
    """Nearest integer, with exact half ties away from zero; no floating point."""
    if type(numerator) is not int or type(denominator) is not int or denominator <= 0:
        raise ProtocolError("Invalid rational number")
    magnitude, remainder = divmod(abs(numerator), denominator)
    if remainder * 2 >= denominator:
        magnitude += 1
    return -magnitude if numerator < 0 else magnitude


def sample_segment(mode: int, value_type: int, segment: Segment, time: int) -> Any | None:
    """Sample only [lo, hi), preserving the original anchors after clipping."""
    _check_segment(segment, mode, value_type)
    _integer(time, 0, MAX_TIME, "sample time")
    if time < segment.lo or time >= segment.hi:
        return None
    if mode == STEP:
        return segment.value
    denominator = segment.anchor1_time - segment.anchor0_time
    elapsed = time - segment.anchor0_time
    if mode == ANGLE:
        delta = (segment.value1 - segment.value0) % 65536
        if delta > 32768:
            delta -= 65536
        numerator = segment.value0 * denominator + delta * elapsed
        return round_ratio(numerator, denominator) % 65536

    def interpolate(a: int, b: int) -> int:
        return round_ratio(a * (denominator - elapsed) + b * elapsed, denominator)

    if value_type == VEC3:
        return tuple(interpolate(a, b) for a, b in zip(segment.value0, segment.value1))
    return interpolate(segment.value0, segment.value1)


def _json_value(value: Any) -> Any:
    if type(value) is bool or value is None:
        return value
    if type(value) is int:
        return str(value)
    if isinstance(value, bytes):
        return {"hex": value.hex()}
    if isinstance(value, (tuple, list)):
        return [_json_value(item) for item in value]
    if isinstance(value, Field):
        return {"id": value.field_id, "flags": value.flags, "kind": value.kind,
                "value": _json_value(value.value)}
    return value


def _json_segment(segment: Segment) -> dict[str, Any]:
    result = {"lo": str(segment.lo), "hi": str(segment.hi), "predicted": segment.predicted}
    if segment.anchor0_time is None:
        result["value"] = _json_value(segment.value)
    else:
        result.update(anchor0Time=str(segment.anchor0_time), anchor1Time=str(segment.anchor1_time),
                      value0=_json_value(segment.value0), value1=_json_value(segment.value1))
    return result


def _vector_result(vector: dict[str, Any]) -> tuple[Any, bytes | None]:
    operation = vector["operation"]
    data = bytes.fromhex(vector.get("hex", ""))
    if operation == "frame":
        frame = decode_frame(data)
        return {"messageType": frame.message_type, "fields": _json_value(frame.fields)}, encode_frame(frame)
    if operation in ("tlv", "schema"):
        fields = decode_tlv(data)
        if operation == "schema":
            schema = {int(key): FieldRule(value["kind"], value.get("required", False),
                                         critical=value.get("critical"))
                      for key, value in vector["schema"].items()}
            validate_schema(fields, schema)
        return _json_value(fields), encode_tlv(fields)
    if operation == "uleb128":
        value = decode_uleb128(data)
        return str(value), encode_uleb128(value)
    if operation == "zigzag63":
        value = decode_zigzag63(data)
        return str(value), encode_zigzag63(value)
    if operation == "value":
        value = decode_value(vector["valueType"], data)
        return _json_value(value), encode_value(vector["valueType"], value)
    if operation in ("segment", "sample"):
        mode, value_type = vector["mode"], vector["valueType"]
        segment = decode_segment(mode, value_type, data)
        result = (_json_segment(segment) if operation == "segment" else
                  _json_value(sample_segment(mode, value_type, segment, int(vector["time"]))))
        return result, encode_segment(mode, value_type, segment)
    if operation == "pulse":
        pulse = decode_pulse(vector["valueType"], data)
        result = {"time": str(pulse.time), "eventId": str(pulse.event_id),
                  "value": _json_value(pulse.value)}
        return result, encode_pulse(vector["valueType"], pulse)
    if operation == "encodeValue":
        encoded = encode_value(vector["valueType"], vector["input"])
        return {"hex": encoded.hex()}, None
    raise ProtocolError("Unknown vector operation")


def verify_vectors(path: str | Path) -> dict[str, Any]:
    document = json.loads(Path(path).read_text(encoding="utf-8"))
    if document.get("wireVersion") != VERSION:
        raise ProtocolError("Wrong vector wire version")
    counts = {"accepted": 0, "rejected": 0, "samples": 0}
    names: set[str] = set()
    for vector in document["vectors"]:
        name = vector["name"]
        if name in names:
            raise ProtocolError("Duplicate vector name")
        names.add(name)
        expected_acceptance = vector["accepted"]
        if type(expected_acceptance) is not bool:
            raise ProtocolError("Vector acceptance must be boolean")
        try:
            actual, reencoded = _vector_result(vector)
        except ProtocolError as exc:
            if expected_acceptance:
                raise ProtocolError(f"Vector {name} unexpectedly rejected: {exc}") from exc
            counts["rejected"] += 1
            continue
        if not expected_acceptance:
            raise ProtocolError(f"Vector {name} unexpectedly accepted")
        if actual != vector["expected"]:
            raise ProtocolError(f"Vector {name} decoded value differs")
        if reencoded is not None and reencoded.hex() != vector["hex"]:
            raise ProtocolError(f"Vector {name} does not re-encode byte-for-byte")
        counts["accepted"] += 1
        if vector["operation"] == "sample":
            counts["samples"] += 1
    return {"passed": True, "wireVersion": VERSION, "vectors": len(names), **counts}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", type=Path, default=Path(__file__).with_name("vectors.json"))
    args = parser.parse_args()
    print(json.dumps(verify_vectors(args.verify), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
