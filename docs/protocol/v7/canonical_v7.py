#!/usr/bin/env python3
"""Streaming v7 canonical-state encoding and SHA-256/128 reference functions.

Input is a complete library description, not rendered poses or transaction bytes.
This validates canonical structure; profile coverage, permissions, reference lifetimes,
and old/new sealed-history invariants need a separate transactional state validator.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
from typing import Iterator
import wire_v7 as w
import messages_v7 as m

DOMAIN = b"RWXT-STATE-7\0"
HEADER_FIELDS = ("profileId", "teamId", "ticksPerSecond", "streamId", "epoch", "revision",
                 "observedThrough", "sealedThrough", "retainedFrom", "retainedUntil", "controlEpoch", "objectHighWater")


def integer(value) -> int:
    if isinstance(value, str):
        m.require(re.fullmatch(r"0|-?[1-9][0-9]*", value) is not None, "Noncanonical decimal integer")
        value = int(value)
    return w._integer(value, -(1 << 63), w.MAX_U63, "integer")


def raw_hex(value: str) -> bytes:
    m.require(type(value) is str and re.fullmatch(r"(?:[0-9a-f]{2})*", value) is not None, "Noncanonical hex")
    m.require(len(value) <= w.MAX_BLOB_BYTES * 2, "Hex blob too large")
    return bytes.fromhex(value)


def record_hex(value: str) -> bytes:
    raw = raw_hex(value)
    m.require(w.encode_tlv(w.decode_tlv(raw)) == raw, "Noncanonical record")
    return raw


def vbytes(raw: bytes) -> bytes:
    return w.encode_uleb128(len(raw)) + raw


def value_from_json(kind: int, value):
    if kind in (w.SINT, w.UINT):
        return integer(value)
    if kind == w.VEC3:
        m.require(isinstance(value, list) and len(value) == 3, "Invalid vector")
        return tuple(integer(item) for item in value)
    if kind == w.VALUE_BOOL:
        m.require(type(value) is bool, "Invalid boolean")
        return value
    if kind == w.BLOB:
        m.require(isinstance(value, dict) and set(value) == {"hex"}, "Invalid blob JSON")
        return raw_hex(value["hex"])
    raise w.ProtocolError("Unknown value type")


def segment_from_json(mode: int, kind: int, record: dict) -> w.Segment:
    common = integer(record["lo"]), integer(record["hi"]), record["predicted"]
    if mode == w.STEP:
        return w.Segment(*common, value=value_from_json(kind, record["value"]))
    return w.Segment(*common, anchor0_time=integer(record["anchor0Time"]), anchor1_time=integer(record["anchor1Time"]),
                     value0=value_from_json(kind, record["value0"]), value1=value_from_json(kind, record["value1"]))


def _persistent_chunks(state: dict) -> Iterator[bytes]:
    h = {name: integer(state[name]) for name in HEADER_FIELDS}
    m.require(h["profileId"] == 1 and 0 <= h["teamId"] <= 63 and 1 <= h["ticksPerSecond"] <= 1000, "Invalid profile context")
    m.require(1 <= h["streamId"] <= 0xffffffff and h["epoch"] > 0 and h["revision"] > 0, "Invalid state identity")
    m.require(0 <= h["retainedFrom"] <= h["sealedThrough"] <= h["observedThrough"] <= w.MAX_TIME and
              h["retainedUntil"] > h["retainedFrom"], "Invalid state watermarks")
    mode = integer(state["mode"])
    m.require(mode in (1, 2), "Invalid stream mode")
    m.require((h["controlEpoch"] > 0 and h["retainedUntil"] > h["observedThrough"]) if mode == 1 else
              (h["controlEpoch"] == 0 and h["retainedUntil"] <= h["sealedThrough"]), "Mode/watermarks differ")
    m.require(h["objectHighWater"] >= 0, "Invalid object high water")
    for name in HEADER_FIELDS:
        yield w.encode_uleb128(h[name])
    objects = state["objects"]
    m.require(isinstance(objects, list) and len(objects) <= 262_144, "Object count limit")
    handles = [integer(item["handle"]) for item in objects]
    m.require(len(set(handles)) == len(handles) and 0 in handles, "Duplicate object/missing ROOT")
    yield w.encode_uleb128(len(objects))
    total_records = 0; active_units = 0
    for item in sorted(objects, key=lambda obj: integer(obj["handle"])):
        handle, kind, start = integer(item["handle"]), integer(item["kind"]), integer(item["observedFrom"])
        m.require(kind in range(5) and 0 <= handle <= h["objectHighWater"] and ((handle == 0) == (kind == 0)), "Invalid observation identity")
        m.require(0 <= start <= h["observedThrough"], "Invalid observation start")
        close = item.get("close")
        end = None if close is None else integer(close["until"])
        if kind == 1 and (end is None or end > h["observedThrough"]):
            active_units += 1
            m.require(active_units <= 65_536, "Too many active units")
        yield w.encode_uleb128(handle); yield bytes([kind]); yield w.encode_uleb128(start)
        yield bytes([int(close is not None)])
        if close is not None:
            close_kind = integer(close["kind"])
            m.require(handle != 0 and start <= end <= h["observedThrough"] and close_kind in (1, 2), "Invalid observation close")
            yield w.encode_uleb128(end); yield bytes([close_kind])
        descriptor = record_hex(item.get("descriptorHex", ""))
        m.validate_descriptor(kind, w.decode_tlv(descriptor))
        yield vbytes(descriptor)
        curves = item["curves"]
        m.require(isinstance(curves, list) and len(curves) <= (8192 if kind == 0 else 128), "Attribute count limit")
        ids = [integer(curve["attributeId"]) for curve in curves]
        m.require(len(set(ids)) == len(ids), "Duplicate attribute")
        yield w.encode_uleb128(len(curves))
        for curve in sorted(curves, key=lambda c: integer(c["attributeId"])):
            attribute, mode, value_type = integer(curve["attributeId"]), integer(curve["mode"]), integer(curve["valueType"])
            critical, high_water, revision = curve["critical"], integer(curve["eventHighWater"]), integer(curve["curveRevision"])
            m.require(1 <= attribute <= 0x7fffffff and high_water >= 0 and revision >= 0, "Invalid curve identity")
            m.require(type(critical) is bool, "Invalid critical flag")
            w._mode_type(mode, value_type, pulse=mode == w.PULSE)
            metadata = record_hex(curve.get("metadataHex", ""))
            m.validate_metadata(mode, w.decode_tlv(metadata), h["ticksPerSecond"])
            yield w.encode_uleb128(attribute); yield bytes([mode, value_type, int(critical)])
            yield w.encode_uleb128(high_water); yield vbytes(metadata); yield w.encode_uleb128(revision)
            segments, events = curve.get("segments", []), curve.get("events", [])
            m.require(isinstance(segments, list) and isinstance(events, list), "Invalid curve records")
            m.require(not segments if mode == w.PULSE else not events and high_water == 0, "Curve contains wrong record kind")
            records = events if mode == w.PULSE else segments
            total_records += len(records)
            m.require(total_records <= 2_000_000, "Curve record count limit")
            yield w.encode_uleb128(len(records))
            if mode == w.PULSE:
                previous = (-1, -1); seen = set()
                for event in events:
                    pulse = w.Pulse(integer(event["time"]), integer(event["eventId"]), value_from_json(value_type, event["value"]))
                    m.require(pulse.event_id <= high_water and pulse.event_id not in seen and (pulse.time, pulse.event_id) > previous,
                              "Pulse order/identity/high water differs")
                    m.require(start <= pulse.time <= h["observedThrough"] and (end is None or pulse.time < end), "Pulse outside observation")
                    yield w.encode_pulse(value_type, pulse)
                    previous = pulse.time, pulse.event_id; seen.add(pulse.event_id)
            else:
                previous_end = start
                for data in segments:
                    segment = segment_from_json(mode, value_type, data)
                    m.require(segment.lo >= previous_end and (end is None or segment.hi <= end), "Segment overlaps or escapes observation")
                    yield w.encode_segment(mode, value_type, segment)
                    previous_end = segment.hi


def persistent_chunks(state: dict) -> Iterator[bytes]:
    size = 0
    for chunk in _persistent_chunks(state):
        size += len(chunk)
        m.require(size <= w.MAX_BLOB_BYTES, "Persistent state exceeds 64 MiB")
        yield chunk


def canonical_chunks(state: dict) -> Iterator[bytes]:
    match, access, map_key = (raw_hex(state[name]) for name in ("matchIdHex", "accessIdHex", "mapKeyHex"))
    m.fixed(match, 16); m.fixed(access, 16); m.fixed(map_key, 32)
    map_data = record_hex(state["mapHex"])
    m.validate_map(w.decode_tlv(map_data))
    # mapKey is encoded as supplied, alongside the complete map bytes. Checking the map-key
    # relationship belongs to map admission; a corrupt retained map must change this digest.
    yield DOMAIN; yield match; yield access; yield map_key
    yield w.encode_uleb128(len(map_data)); yield map_data
    yield from persistent_chunks(state)


def state_digest(state: dict) -> bytes:
    sha = hashlib.sha256()
    for chunk in canonical_chunks(state):
        sha.update(chunk)
    return sha.digest()[:16]


def verify_fixture(path: str | Path) -> dict:
    document = json.loads(Path(path).read_text(encoding="utf-8"))
    m.require(document.get("wireVersion") == 7, "Fixture version differs")
    for case in document["states"]:
        state = case["state"]
        raw = b"".join(canonical_chunks(state))
        persistent_size = sum(map(len, persistent_chunks(state)))
        m.require(raw.hex() == case["canonicalHex"] and len(raw) == case["canonicalBytes"], "Canonical fixture bytes differ")
        m.require(persistent_size == case["persistentBytes"] and state_digest(state).hex() == case["digest128"], "Canonical fixture digest/size differs")
    return {"passed": True, "wireVersion": 7, "states": len(document["states"])}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", type=Path, required=True)
    print(json.dumps(verify_fixture(parser.parse_args().verify), indent=2))
