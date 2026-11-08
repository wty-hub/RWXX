#!/usr/bin/env python3
"""Offline structural and sampling tests; no sockets or application launch."""

from dataclasses import replace
from pathlib import Path
import random
import struct
import unittest

import wire_v7 as w


def raw_field(field_id, flags, kind, body):
    return struct.pack(">HBBI", field_id, flags, kind, len(body)) + body


class FrameAndRecordTests(unittest.TestCase):
    def test_handwritten_frame_and_exact_byte_order(self):
        raw = bytes.fromhex("000000145257585400070001000101020000000401020304")
        frame = w.Frame(1, (w.Field(1, 1, w.U32, 0x01020304),))
        self.assertEqual(w.decode_frame(raw), frame)
        self.assertEqual(w.encode_frame(frame), raw)

    def test_version_magic_type_length_and_trailing_bytes_rejected(self):
        raw = w.encode_frame(w.Frame(101, ()))
        variants = [raw + b"\0", raw[:-1], struct.pack(">I", 7),
                    struct.pack(">I", w.MAX_FRAME_PAYLOAD + 1),
                    raw[:4] + b"RWXA" + raw[8:],
                    raw[:8] + b"\0\6" + raw[10:],
                    raw[:8] + b"\0\5" + raw[10:],
                    raw[:10] + b"\0\0"]
        for invalid in variants:
            with self.subTest(data=invalid.hex()), self.assertRaises(w.ProtocolError):
                w.decode_frame(invalid)

    def test_exact_frame_capacity_and_one_byte_over(self):
        body_size = w.MAX_FRAME_PAYLOAD - 8 - 8
        frame = w.Frame(65535, (w.Field(1, 0, w.BYTES, bytes(body_size)),))
        encoded = w.encode_frame(frame)
        self.assertEqual(len(encoded), w.MAX_FRAME_PAYLOAD + 4)
        self.assertEqual(w.decode_frame(encoded), frame)
        oversized = w.Frame(1, (w.Field(1, 0, w.BYTES, bytes(body_size + 1)),))
        with self.assertRaises(w.ProtocolError):
            w.encode_frame(oversized)

    def test_all_value_kinds_unicode_and_integer_boundaries(self):
        fields = (
            w.Field(1, 1, w.BOOL, False),
            w.Field(2, 0, w.U32, (1 << 32) - 1),
            w.Field(3, 0, w.U64, (1 << 63) - 1),
            w.Field(4, 0, w.I64, -(1 << 63)),
            w.Field(5, 0, w.TEXT, "地图🛰️é"),
            w.Field(6, 0, w.BYTES, b"\x00\xff"),
            w.Field(7, 0, w.RECORD, (w.Field(1, 0, w.I64, (1 << 63) - 1),)),
            w.Field(8, 0, w.RECORDS, ((), (w.Field(1, 0, w.BOOL, True),))),
        )
        self.assertEqual(w.decode_tlv(w.encode_tlv(fields)), fields)

    def test_optional_unknown_bytes_preserved_and_critical_rejected(self):
        opaque = raw_field(9, 0, 240, b"\xff\x00\x80\x7f")
        fields = w.decode_tlv(opaque)
        w.validate_schema(fields, {})
        self.assertEqual(w.encode_tlv(fields), opaque)
        self.assertEqual(fields[0].value, b"\xff\x00\x80\x7f")
        with self.assertRaises(w.ProtocolError):
            w.decode_tlv(raw_field(9, 1, 240, b"anything"))
        known_kind_unknown_field = w.decode_tlv(raw_field(9, 1, w.U32, b"\0\0\0\1"))
        with self.assertRaises(w.ProtocolError):
            w.validate_schema(known_kind_unknown_field, {})

    def test_schema_checks_required_wrong_kind_and_nested_critical(self):
        schema = {1: w.FieldRule(w.TEXT, required=True)}
        with self.assertRaises(w.ProtocolError):
            w.validate_schema((), schema)
        with self.assertRaises(w.ProtocolError):
            w.validate_schema((w.Field(1, 0, w.BYTES, b"name"),), schema)
        nested = (w.Field(1, 0, w.RECORD, (w.Field(2, 1, w.BOOL, True),)),)
        with self.assertRaises(w.ProtocolError):
            w.validate_schema(nested, {1: w.FieldRule(w.RECORD, fields={})})

    def test_schema_critical_flags_and_constructed_records_are_validated(self):
        for critical in (False, True):
            field = w.Field(1, int(critical), w.BOOL, True)
            schema = {1: w.FieldRule(w.BOOL, required=True, critical=critical)}
            w.validate_schema((field,), schema)
            with self.assertRaises(w.ProtocolError):
                w.validate_schema((replace(field, flags=int(not critical)),), schema)
        malformed = [(w.Field(1, 0, w.U64, 1 << 63),),
                     (w.Field(1, 0, w.BOOL, True), w.Field(1, 0, w.BOOL, True)),
                     (w.Field(1, 2, w.BOOL, True),)]
        for fields in malformed:
            with self.assertRaises(w.ProtocolError):
                w.validate_schema(fields, {})

    def test_fields_must_be_sorted_unique_in_range_and_flags_known(self):
        field = raw_field(2, 0, w.BOOL, b"\1")
        for invalid in [field + field, field + raw_field(1, 0, w.BOOL, b"\1"),
                        raw_field(0, 0, w.BOOL, b"\1"),
                        raw_field(32768, 0, w.BOOL, b"\1"),
                        raw_field(1, 2, w.BOOL, b"\1"),
                        raw_field(1, 0, 0, b"")]:
            with self.subTest(data=invalid.hex()), self.assertRaises(w.ProtocolError):
                w.decode_tlv(invalid)
        with self.assertRaises(w.ProtocolError):
            w.encode_tlv((w.Field(2, 0, w.BOOL, True), w.Field(1, 0, w.BOOL, False)))

    def test_fixed_lengths_bool_and_unsigned_high_bit(self):
        malformed = [(w.BOOL, b"\2"), (w.BOOL, b""), (w.U32, b"\0" * 5),
                     (w.U64, b"\x80" + b"\0" * 7), (w.I64, b"\0" * 7)]
        for kind, body in malformed:
            with self.subTest(kind=kind, body=body), self.assertRaises(w.ProtocolError):
                w.decode_tlv(raw_field(1, 0, kind, body))

    def test_text_rejects_control_and_malformed_utf8_but_does_not_normalize(self):
        for text in ["\0", "\n", "\x1f", "\x7f", "\x80", "\x9f", "\ud800"]:
            with self.subTest(text=repr(text)), self.assertRaises(w.ProtocolError):
                w.encode_tlv((w.Field(1, 0, w.TEXT, text),))
        for raw in [b"\xc0\x80", b"\xed\xa0\x80", b"\xf4\x90\x80\x80", b"\xf0\x9f"]:
            with self.subTest(raw=raw), self.assertRaises(w.ProtocolError):
                w.decode_tlv(raw_field(1, 0, w.TEXT, raw))
        a = w.encode_tlv((w.Field(1, 0, w.TEXT, "é"),))
        b = w.encode_tlv((w.Field(1, 0, w.TEXT, "e\u0301"),))
        self.assertNotEqual(a, b)
        self.assertEqual(w.decode_tlv(b)[0].value, "e\u0301")

    def test_depth_and_field_count_boundaries(self):
        record = (w.Field(1, 0, w.BOOL, True),)
        for _ in range(7):
            record = (w.Field(1, 0, w.RECORD, record),)
        self.assertEqual(w.decode_tlv(w.encode_tlv(record)), record)
        with self.assertRaises(w.ProtocolError):
            w.encode_tlv((w.Field(1, 0, w.RECORD, record),))
        deeper_raw = raw_field(1, 0, w.RECORD, w.encode_tlv(record))
        with self.assertRaises(w.ProtocolError):
            w.decode_tlv(deeper_raw)
        fields = tuple(w.Field(i + 1, 0, w.BOOL, True) for i in range(256))
        self.assertEqual(len(w.decode_tlv(w.encode_tlv(fields))), 256)
        with self.assertRaises(w.ProtocolError):
            w.decode_tlv(w.encode_tlv(fields) + raw_field(257, 0, w.BOOL, b"\1"))

    def test_record_count_boundaries_and_declared_lengths_without_allocation(self):
        empty_records = ((),) * 65536
        fields = (w.Field(1, 0, w.RECORDS, empty_records),)
        self.assertEqual(len(w.decode_tlv(w.encode_tlv(fields))[0].value), 65536)
        for body in [struct.pack(">I", 65537), struct.pack(">II", 1, 0xFFFFFFFF),
                     struct.pack(">I", 2) + b"\0" * 4, struct.pack(">I", 0) + b"\0"]:
            with self.subTest(body=body.hex()), self.assertRaises(w.ProtocolError):
                w.decode_tlv(raw_field(1, 0, w.RECORDS, body))
        with self.assertRaises(w.ProtocolError):
            w.decode_tlv(struct.pack(">HBBI", 1, 0, w.BYTES, 0xFFFFFFFF))


class PackedValueTests(unittest.TestCase):
    def test_handwritten_varints_and_extreme_values(self):
        unsigned = {0: "00", 127: "7f", 128: "8001", 16383: "ff7f",
                    16384: "808001", w.MAX_U63: "ffffffffffffffff7f"}
        signed = {0: "00", -1: "01", 1: "02", -64: "7f", 64: "8001",
                  w.MIN_ZIGZAG63: "ffffffffffffffff7f",
                  w.MAX_ZIGZAG63: "feffffffffffffff7f"}
        for value, encoded in unsigned.items():
            self.assertEqual(w.encode_uleb128(value).hex(), encoded)
            self.assertEqual(w.decode_uleb128(bytes.fromhex(encoded)), value)
        for value, encoded in signed.items():
            self.assertEqual(w.encode_zigzag63(value).hex(), encoded)
            self.assertEqual(w.decode_zigzag63(bytes.fromhex(encoded)), value)

    def test_noncanonical_and_overflow_varints_rejected(self):
        for raw in [b"", b"\x80", b"\x80\0", b"\x81\0", b"\xff\0",
                    b"\xff" * 9 + b"\0", b"\x80" * 9 + b"\1", b"\0\0"]:
            with self.subTest(raw=raw.hex()), self.assertRaises(w.ProtocolError):
                w.decode_uleb128(raw)
        for value in [-1, w.MAX_U63 + 1, 1.0, True]:
            with self.assertRaises(w.ProtocolError):
                w.encode_uleb128(value)
        for value in [w.MIN_ZIGZAG63 - 1, w.MAX_ZIGZAG63 + 1]:
            with self.assertRaises(w.ProtocolError):
                w.encode_zigzag63(value)

    def test_all_packed_types_and_length_boundaries(self):
        values = [(w.SINT, -123), (w.VEC3, (-1, 0, 128)), (w.UINT, w.MAX_U63),
                  (w.VALUE_BOOL, False), (w.BLOB, b"\0\xff\x80")]
        for value_type, value in values:
            self.assertEqual(w.decode_value(value_type, w.encode_value(value_type, value)), value)
        for value_type, raw in [(w.VALUE_BOOL, b"\2"), (w.VEC3, b"\0\0"),
                                (w.BLOB, b"\2\xff"),
                                (w.BLOB, w.encode_uleb128(w.MAX_BLOB_BYTES + 1))]:
            with self.assertRaises(w.ProtocolError):
                w.decode_value(value_type, raw)

    def test_curve_blob_limit_is_distinct_from_tlv_container_limit(self):
        exact = bytes(range(256)) * 256
        encoded = w.encode_value(w.BLOB, exact)
        self.assertEqual(encoded[:3], b"\x80\x80\x04")
        self.assertEqual(w.decode_value(w.BLOB, encoded), exact)
        oversized = exact + b"\xff"
        # A complete body proves rejection is due to the cap, not truncation.
        too_large_wire = b"\x81\x80\x04" + oversized
        for body in (oversized, bytearray(oversized), memoryview(oversized)):
            with self.assertRaises(w.ProtocolError):
                w.encode_value(w.BLOB, body)
        for raw in (too_large_wire, b"\x81\x80\x04"):
            with self.assertRaises(w.ProtocolError):
                w.decode_value(w.BLOB, raw)
        fields = (w.Field(1, 0, w.BYTES, oversized),)
        self.assertEqual(w.decode_tlv(w.encode_tlv(fields)), fields)
        with self.assertRaises(w.ProtocolError):
            w.decode_segment(w.STEP, w.BLOB, b"\0\1\0" + too_large_wire)
        with self.assertRaises(w.ProtocolError):
            w.decode_pulse(w.BLOB, b"\0\1" + too_large_wire)
        with self.assertRaises(w.ProtocolError):
            w.encode_segment(w.STEP, w.BLOB, w.Segment(0, 1, False, value=oversized))
        with self.assertRaises(w.ProtocolError):
            w.encode_pulse(w.BLOB, w.Pulse(0, 1, oversized))

    def test_no_ieee_or_negative_zero_numeric_input(self):
        for floating in [-0.0, 0.0, 1.0, 1.5, float("inf"), float("nan")]:
            for value_type in [w.SINT, w.UINT]:
                with self.subTest(value=floating, kind=value_type), self.assertRaises(w.ProtocolError):
                    w.encode_value(value_type, floating)
            for kind in [w.U32, w.U64, w.I64]:
                with self.assertRaises(w.ProtocolError):
                    w.encode_tlv((w.Field(1, 0, kind, floating),))


class CurveTests(unittest.TestCase):
    def test_whole_rational_rounding_is_not_rounded_delta(self):
        negative = w.Segment(0, 2, False, anchor0_time=0, anchor1_time=2, value0=-1, value1=0)
        positive = replace(negative, value0=1, value1=0)
        self.assertEqual(w.sample_segment(w.LINEAR, w.SINT, negative, 1), -1)
        self.assertEqual(w.sample_segment(w.LINEAR, w.SINT, positive, 1), 1)
        crossing = replace(negative, value0=-3, value1=4, hi=4, anchor1_time=4)
        self.assertEqual([w.sample_segment(w.LINEAR, w.SINT, crossing, t) for t in range(4)],
                         [-3, -1, 1, 2])

    def test_clipping_preserves_original_anchors_and_gaps_have_no_value(self):
        original = w.Segment(0, 100, True, anchor0_time=0, anchor1_time=100,
                             value0=(0, -100, 200), value1=(100, 100, -200))
        clipped = replace(original, lo=20, hi=60)
        encoded = w.encode_segment(w.LINEAR, w.VEC3, clipped)
        self.assertEqual(w.decode_segment(w.LINEAR, w.VEC3, encoded), clipped)
        for time in range(20, 60):
            self.assertEqual(w.sample_segment(w.LINEAR, w.VEC3, original, time),
                             w.sample_segment(w.LINEAR, w.VEC3, clipped, time))
        self.assertIsNone(w.sample_segment(w.LINEAR, w.VEC3, clipped, 19))
        self.assertIsNone(w.sample_segment(w.LINEAR, w.VEC3, clipped, 60))

    def test_angles_choose_positive_half_turn_and_shortest_wrapped_path(self):
        for a, b, expected in [(0, 32768, 16384), (32768, 0, 49152),
                               (65530, 6, 0), (6, 65530, 0), (0, 65535, 65535)]:
            segment = w.Segment(0, 2, False, anchor0_time=0, anchor1_time=2, value0=a, value1=b)
            self.assertEqual(w.sample_segment(w.ANGLE, w.UINT, segment, 1), expected)

    def test_near_i63_time_and_extreme_signed_interpolation_no_float_loss(self):
        start, end = w.MAX_U63 - 11, w.MAX_U63 - 1
        segment = w.Segment(start, end, False, anchor0_time=start, anchor1_time=end,
                            value0=w.MIN_ZIGZAG63, value1=w.MAX_ZIGZAG63)
        self.assertEqual(w.sample_segment(w.LINEAR, w.SINT, segment, start + 5), -1)
        self.assertEqual(w.decode_segment(w.LINEAR, w.SINT,
                                         w.encode_segment(w.LINEAR, w.SINT, segment)), segment)
        self.assertIsNone(w.sample_segment(w.LINEAR, w.SINT, segment, end))

    def test_forever_is_an_endpoint_not_a_sample_or_event_time(self):
        step = w.Segment(0, w.MAX_U63, False, value=-1)
        linear = w.Segment(0, w.MAX_U63, False, anchor0_time=0,
                           anchor1_time=w.MAX_U63, value0=-1, value1=-1)
        for mode, segment in ((w.STEP, step), (w.LINEAR, linear)):
            self.assertEqual(w.sample_segment(mode, w.SINT, segment, w.MAX_TIME), -1)
            self.assertEqual(w.decode_segment(mode, w.SINT,
                                             w.encode_segment(mode, w.SINT, segment)), segment)
            with self.assertRaises(w.ProtocolError):
                w.sample_segment(mode, w.SINT, segment, w.MAX_U63)
        with self.assertRaises(w.ProtocolError):
            w.encode_segment(w.LINEAR, w.SINT, replace(linear, value1=0))
        with self.assertRaises(w.ProtocolError):
            w.decode_pulse(w.VALUE_BOOL, w.encode_uleb128(w.MAX_U63) + b"\1\0")
        with self.assertRaises(w.ProtocolError):
            w.decode_pulse(w.VALUE_BOOL, b"\1\0\0")

    def test_step_all_types_and_zero_pulse_values_are_not_missing(self):
        for value_type, value in [(w.SINT, 0), (w.VEC3, (0, 0, 0)), (w.UINT, 0),
                                  (w.VALUE_BOOL, False), (w.BLOB, b"")]:
            segment = w.Segment(10, 20, False, value=value)
            self.assertEqual(w.sample_segment(w.STEP, value_type, segment, 10), value)
            self.assertEqual(w.decode_segment(w.STEP, value_type,
                                             w.encode_segment(w.STEP, value_type, segment)), segment)
            pulse = w.Pulse(w.MAX_U63 - 1, 1, value)
            self.assertEqual(w.decode_pulse(value_type, w.encode_pulse(value_type, pulse)), pulse)
            for invalid in (replace(pulse, time=w.MAX_U63), replace(pulse, event_id=0)):
                with self.assertRaises(w.ProtocolError):
                    w.encode_pulse(value_type, invalid)

    def test_malformed_modes_ranges_flags_anchors_and_trailing_data(self):
        good = w.Segment(2, 8, False, anchor0_time=0, anchor1_time=10, value0=1, value1=9)
        for bad in [replace(good, lo=8), replace(good, hi=11), replace(good, anchor0_time=3),
                    replace(good, anchor1_time=0), replace(good, value=0)]:
            with self.assertRaises(w.ProtocolError):
                w.encode_segment(w.LINEAR, w.SINT, bad)
        for mode, value_type in [(w.LINEAR, w.BLOB), (w.LINEAR, w.VALUE_BOOL),
                                  (w.ANGLE, w.SINT), (w.PULSE, w.SINT)]:
            with self.assertRaises(w.ProtocolError):
                w.encode_segment(mode, value_type, good)
        with self.assertRaises(w.ProtocolError):
            w.encode_segment(w.ANGLE, w.UINT, replace(good, value1=65536))
        raw = w.encode_segment(w.LINEAR, w.SINT, good)
        for invalid in [raw + b"\0", raw[:2] + b"\2" + raw[3:], raw[:-1]]:
            with self.assertRaises(w.ProtocolError):
                w.decode_segment(w.LINEAR, w.SINT, invalid)

    def test_deterministic_random_clipping_invariance(self):
        rng = random.Random(70413)
        for _ in range(200):
            end = rng.randrange(3, 10000)
            lo = rng.randrange(end - 1)
            hi = rng.randrange(lo + 1, end + 1)
            a, b = rng.randrange(-(1 << 40), 1 << 40), rng.randrange(-(1 << 40), 1 << 40)
            original = w.Segment(0, end, True, anchor0_time=0, anchor1_time=end, value0=a, value1=b)
            clipped = replace(original, lo=lo, hi=hi)
            t = rng.randrange(lo, hi)
            self.assertEqual(w.sample_segment(w.LINEAR, w.SINT, original, t),
                             w.sample_segment(w.LINEAR, w.SINT, clipped, t))


class PublishedVectorsTests(unittest.TestCase):
    def test_published_vectors(self):
        result = w.verify_vectors(Path(__file__).with_name("vectors.json"))
        self.assertTrue(result["passed"])
        self.assertGreater(result["accepted"], 25)
        self.assertGreater(result["rejected"], 25)
        self.assertGreater(result["samples"], 5)


if __name__ == "__main__":
    unittest.main()
