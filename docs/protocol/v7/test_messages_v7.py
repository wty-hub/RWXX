#!/usr/bin/env python3
from dataclasses import replace
import hashlib
import json
from pathlib import Path
import unittest
import wire_v7 as w
import messages_v7 as m


def message(kind, *values):
    return w.Frame(kind, tuple(w.Field(i, 1, t, value) for i, (t, value) in enumerate(zip(m.SCHEMAS[kind], values), 1)))


def changed(frame, field_id, value=None, kind=None, flags=None):
    return replace(frame, fields=tuple(replace(f, value=value if value is not None else f.value,
        kind=kind if kind is not None else f.kind, flags=flags if flags is not None else f.flags) if f.field_id == field_id else f for f in frame.fields))


def examples():
    zero16, zero32 = bytes(16), bytes(32)
    target = (w.Field(1, 1, w.U32, 1), w.Field(2, 1, w.I64, -128), w.Field(3, 1, w.I64, 256))
    operations = b"\x01\x01\x04\x00\x00\x00\x00"  # one ObjectCreate ROOT, empty descriptor
    begin = message(106, 1, 1, 1, 1, 0, 100, 50, 0, 200, 1, len(operations), hashlib.sha256(operations).digest(), 0)
    return [message(1, 1, "玩家🛰", "x" * 32), message(2, zero32, False),
        message(3, 1, 1, 0, 0, 16384), message(4, 1),
        message(5, 1, 1, 1, 1, 1, 1, b"\1\1", 0, target, 1),
        message(6, 1, 1, 1, 1, b"\0"), message(7, 1, w.MAX_U63),
        message(8, 1, 1, 1, 1, 0, zero16), message(9, 1, 1, 0, 1), message(10, 1, 16_777_216),
        message(101, 1, 0, 60, 1024, zero16, zero16, 100, 0, 61440, 30, 100, zero16, 0, 1),
        message(102, 2, "Unable to join"), message(103, zero32, 123), message(104, zero32, 0, b"\x01"),
        message(105, zero32), begin, message(107, 1, 1, 1, 0, operations), message(108, 1, 1, 1),
        message(109, 1, 1, 1, 1, zero16), message(110, 1, 0, 1, 1, 0, 200, 16384, 0, 100),
        message(111, 1, w.MAX_U63, 100), message(112, 1, 1, 1, 0, 100)]


def negative_examples():
    e = {f.message_type: f for f in examples()}
    none = (w.Field(1, 1, w.U32, 0),)
    stop = changed(changed(changed(e[5], 6, 3), 9, none), 10, 0)
    cancel = changed(changed(changed(e[5], 6, 5), 8, 3), 9, none)
    cancel = changed(cancel, 10, 2)
    return [
        ("hello-blank", changed(e[1], 2, "\u3000")), ("hello-token-format", changed(e[1], 3, "bad")),
        ("map-key-short", changed(e[2], 1, bytes(31))), ("live-nonzero-window", changed(e[3], 3, 1)),
        ("budget-too-small", changed(e[10], 2, 16383)), ("command-empty-selection", changed(e[5], 7, b"\0")),
        ("selection-duplicate", changed(e[6], 5, b"\2\1\1")), ("selection-zero-handle", changed(e[6], 5, b"\1\0")),
        ("none-with-optional-point", changed(stop, 9, none + (w.Field(2, 0, w.I64, 0),))),
        ("point-with-optional-unit", changed(e[5], 9, e[5].fields[8].value + (w.Field(4, 0, w.U64, 1),))),
        ("cancel-cannot-queue", changed(cancel, 10, 3)), ("cancel-cannot-point", changed(cancel, 9, e[5].fields[8].value)),
        ("basic-cannot-use-action", changed(e[5], 8, 3)), ("stop-cannot-queue", changed(stop, 10, 1)),
        ("check-missing-status-with-digest", changed(e[8], 5, 1)), ("check-full-status-without-digest", changed(e[8], 6, b"")),
        ("restore-unknown-reason", changed(e[9], 4, 3)), ("welcome-wrong-subdivision", changed(e[101], 4, 1000)),
        ("welcome-wrong-authorization", changed(e[101], 13, 1)), ("welcome-excess-prediction", changed(e[101], 9, 61441)),
        ("map-over-limit", changed(e[103], 2, w.MAX_BLOB_BYTES + 1)), ("empty-map-chunk", changed(e[104], 3, b"")),
        ("chunk-over-total-limit", changed(e[104], 2, w.MAX_BLOB_BYTES)), ("reset-nonzero-base", changed(e[106], 5, 1)),
        ("update-revision-gap", changed(changed(changed(e[106], 3, 2), 5, 1), 4, 3)),
        ("seal-after-observation", changed(e[106], 7, 101)), ("transaction-empty-byte-declaration", changed(e[106], 11, 0)),
        ("commit-zero-revision", changed(e[108], 3, 0)), ("check-short-digest", changed(e[109], 5, bytes(15))),
        ("failed-status-contains-stream", changed(e[110], 2, 1)), ("unknown-end-reason", changed(e[112], 3, 5)),
        ("known-field-noncritical", changed(e[7], 1, flags=0)), ("known-field-wrong-kind", changed(e[7], 1, kind=w.U32)),
        ("unknown-critical-field", replace(e[7], fields=e[7].fields + (w.Field(99, 1, w.BOOL, True),))),
        ("required-field-missing", replace(e[7], fields=e[7].fields[1:])),
    ]


class MessageTests(unittest.TestCase):
    def test_every_message_decodes_and_reencodes_only_in_its_direction(self):
        self.assertEqual({x.message_type for x in examples()}, set(m.SCHEMAS))
        for frame in examples():
            direction = m.DIRECTIONS[frame.message_type]
            raw = m.encode_message(frame, direction)
            self.assertEqual(m.decode_message(raw, direction), frame)
            with self.assertRaises(w.ProtocolError):
                m.decode_message(raw, "S2C" if direction == "C2S" else "C2S")

    def test_semantic_negative_cases_reject_after_valid_structural_decode(self):
        for name, frame in negative_examples():
            with self.subTest(name=name), self.assertRaises(w.ProtocolError):
                m.decode_message(w.encode_frame(frame), m.DIRECTIONS[frame.message_type])

    def test_optional_opaque_survives_but_old_version_never_enters_schema(self):
        frame = examples()[6]
        frame = replace(frame, fields=frame.fields + (w.Field(99, 0, 240, b"\xff\0"),))
        raw = m.encode_message(frame, "C2S")
        self.assertEqual(m.encode_message(m.decode_message(raw, "C2S"), "C2S"), raw)
        for version in (5, 6):
            with self.assertRaises(w.ProtocolError):
                m.decode_message(raw[:8] + version.to_bytes(2, "big") + raw[10:], "C2S")

    def test_ability_optional_cost_records_are_still_strict(self):
        ability = tuple(w.Field(i, 1, kind, value) for i, (kind, value) in enumerate(
            [(w.U32, 2), (w.U32, 0), (w.BOOL, False), (w.TEXT, "produce.builder"),
             (w.TEXT, "builder"), (w.I64, 1536), (w.U32, 1)], 1))
        cost = tuple(w.Field(i, 1, w.I64, value) for i, value in enumerate((1536, 512, 0, -1, 1024), 1))
        good = ability + (w.Field(9, 0, w.RECORD, cost),)
        m.validate_ability(good)
        for bad in [ability + (w.Field(9, 1, w.RECORD, cost),),
                    ability + (w.Field(9, 0, w.BYTES, b""),),
                    ability + (w.Field(9, 0, w.RECORD, cost[:-1]),),
                    ability + (w.Field(9, 0, w.RECORD, (replace(cost[0], value=1024),) + cost[1:]),)]:
            with self.assertRaises(w.ProtocolError):
                m.validate_ability(bad)

    def test_published_message_fixtures(self):
        path = Path(__file__).with_name("message-vectors.json")
        document = json.loads(path.read_text())
        accepted = rejected = 0
        for case in document["vectors"]:
            raw = bytes.fromhex(case["hex"])
            if case["accepted"]:
                frame = m.decode_message(raw, case["direction"])
                self.assertEqual(m.encode_message(frame, case["direction"]), raw)
                self.assertEqual(frame.message_type, case["messageType"])
                accepted += 1
            else:
                with self.assertRaises(w.ProtocolError, msg=case["name"]):
                    m.decode_message(raw, case["direction"])
                rejected += 1
        self.assertGreaterEqual(accepted, 22); self.assertGreaterEqual(rejected, 22)


class TransactionTests(unittest.TestCase):
    def setUp(self):
        self.frames = {f.message_type: f for f in examples()}

    def test_published_operation_vectors_cover_all_opcodes_and_missing_context(self):
        result = m.verify_transaction_vectors(Path(__file__).with_name("transaction-vectors.json"))
        self.assertEqual(result["accepted"], 4)
        self.assertEqual(result["rejected"], 11)

    def test_atomic_commit_and_interleaved_stream_cancellation(self):
        a = m.TransactionAssembler()
        first = self.frames[106]; second = changed(first, 1, 2)
        self.assertIsNone(a.accept(first)); self.assertIsNone(a.accept(second))
        self.assertIsNone(a.accept(self.frames[107]))
        end = message(112, 2, 1, 1, 0, 100)
        self.assertIsNone(a.accept(end)); self.assertNotIn(2, a.staged)
        committed = a.accept(self.frames[108])
        self.assertEqual(committed.operations, self.frames[107].fields[-1].value)
        self.assertFalse(a.staged)
        with self.assertRaises(w.ProtocolError):
            a.accept(self.frames[108])

    def test_bad_offsets_identity_sha_truncation_and_premature_end_reject(self):
        for invalid in [changed(self.frames[107], 4, 1), changed(self.frames[107], 2, 2),
                        self.frames[108], message(112, 1, 1, 2, 0, 100)]:
            a = m.TransactionAssembler(); a.accept(self.frames[106])
            with self.assertRaises(w.ProtocolError):
                a.accept(invalid)
        a = m.TransactionAssembler(); a.accept(changed(self.frames[106], 12, bytes(32)))
        a.accept(self.frames[107])
        with self.assertRaises(w.ProtocolError):
            a.accept(self.frames[108])

    def test_aggregate_staging_reserves_declared_bytes_before_allocation(self):
        a = m.TransactionAssembler(max_staging_bytes=10)
        a.accept(changed(self.frames[106], 11, 6))
        with self.assertRaises(w.ProtocolError):
            a.accept(changed(changed(self.frames[106], 1, 2), 11, 6))
        self.assertEqual(len(a.staged), 1)

    def test_declare_supplies_type_to_later_splice_and_missing_context_is_explicit(self):
        def op(code, body):
            return bytes([code]) + w.encode_uleb128(len(body)) + body
        declare = op(2, b"\1\1\1\1\1\0\0")
        splice = op(3, b"\1\1\0\0\2\1" + w.encode_segment(w.STEP, w.SINT, w.Segment(0, 2, False, value=-1)))
        self.assertEqual(m.validate_operations(b"\2" + declare + splice), {(1, 1): (w.STEP, w.SINT)})
        with self.assertRaises(w.ProtocolError):
            m.validate_operations(b"\1" + splice)
        m.validate_operations(b"\1" + splice, allow_missing_context=True)
        for raw in (b"\1\7\0", b"\1\1\0", b"\1\6\2\x80\0",
                    w.encode_uleb128(2_097_153), b"\0\0"):
            with self.assertRaises(w.ProtocolError):
                m.validate_operations(raw, allow_missing_context=True)


if __name__ == "__main__":
    unittest.main()
