"""Offline boundary checks for the v5 reference codec; no network access."""
import math
import struct
import unittest
import wire_v5 as w


class WireV5Tests(unittest.TestCase):
    def test_fixed_bytes_and_no_v4_or_hello_cache_list(self):
        self.assertEqual(bytes.fromhex("00000009525758410000000502"), w.encode_frame(w.CLIENT, "KeepAlive", {}))
        packet = w.encode_frame(w.CLIENT, "Hello", dict(playerName="A", joinToken="B"))
        self.assertEqual(23, len(packet))
        self.assertEqual("0000001352575841000000050100000001410000000142", packet.hex())
        for version in (1, 2, 3, 4, 6):
            bad = bytearray(packet); struct.pack_into(">i", bad, 8, version)
            with self.assertRaises(ValueError): w.decode_frame(w.CLIENT, bad)
        bad = bytearray(packet + b"\0"); struct.pack_into(">i", bad, 0, len(bad) - 4)
        with self.assertRaises(ValueError): w.decode_frame(w.CLIENT, bad)

    def test_map_key_body_domain_and_ready_strict_boolean(self):
        m = dict(width=1, height=1, tileWidth=20, tileHeight=20, terrain=[0], visual=None)
        packet = w.encode_frame(w.SERVER, "MapInit", dict(map=m))
        self.assertEqual(w.hashlib.sha256(packet[13:]).hexdigest(), w.map_content_key(m))
        key = w.map_content_key(m)
        for name in ("MapOffer", "MapCached"):
            self.assertEqual(45, len(w.encode_frame(w.SERVER, name, dict(contentKey=key))))
        for flag in (False, True):
            packet = w.encode_frame(w.CLIENT, "MapReady", dict(contentKey=key, haveMap=flag))
            self.assertEqual(46, len(packet))
            self.assertEqual(flag, w.decode_frame(w.CLIENT, packet)[1]["haveMap"])
        for flag in (0, 1, "true", None):
            with self.assertRaises(ValueError): w.encode_frame(w.CLIENT, "MapReady", dict(contentKey=key, haveMap=flag))
        packet = bytearray(packet); packet[-1] = 2
        with self.assertRaises(ValueError): w.decode_frame(w.CLIENT, packet)
        for key in (key.upper(), "f" * 63, "z" * 64):
            with self.assertRaises(ValueError): w.encode_frame(w.SERVER, "MapOffer", dict(contentKey=key))

    def test_long_precision_and_nonpositive_order_sequence(self):
        message = dict(sequence=str((1 << 63) - 1), viewSequence=str((1 << 53) + 1),
                       kind="ATTACK_UNIT", unitHandles=["1"], target=dict(kind="UNIT", handle=str((1 << 63) - 1)))
        self.assertEqual(message, w.decode_frame(w.CLIENT, w.encode_frame(w.CLIENT, "Order", message))[1])
        for bad in (True, 1.0, "01", "+1", str(1 << 63), 0, -(1 << 63)):
            with self.assertRaises((ValueError, OverflowError, struct.error)):
                w.encode_frame(w.CLIENT, "Order", dict(message, sequence=bad))

    def test_patch_masks_and_negative_zero_are_lossless(self):
        for bit, (field, fmt) in enumerate(w.UNIT_FIELDS):
            value = {"text": "坦克🚀", "i": 0, "f": -0.0, "bool": False, "B": 1}[fmt]
            if field == "maxHealth": value = 1.0
            patch = {k: None for k, _ in w.UNIT_FIELDS}; patch.update(handle="7"); patch[field] = value
            message = dict(delta=self.delta(unitPatches=[patch]))
            encoded = w.encode_frame(w.SERVER, "Delta", message)
            self.assertEqual(1 << bit, struct.unpack_from(">H", encoded, 54)[0])
            decoded = w.decode_frame(w.SERVER, encoded)[1]
            self.assertEqual(message, decoded)
            self.assertEqual(encoded, w.encode_frame(w.SERVER, "Delta", decoded))
            for mask in (0, 0x800, 0xffff):
                bad = bytearray(encoded); struct.pack_into(">H", bad, 54, mask)
                with self.assertRaises(ValueError): w.decode_frame(w.SERVER, bad)

    def test_fog_range_and_unit_collection_conflicts(self):
        for patches in ([], [dict(start=-1, values=[0])], [dict(start=1048575, values=[0, 0])],
                        [dict(start=0, values=[0, 0]), dict(start=1, values=[0])]):
            with self.assertRaises(ValueError):
                w.encode_frame(w.SERVER, "Delta", dict(delta=self.delta(fog=dict(kind="RUNS", patches=patches))))
        for handles in (["0"], ["7", "7"], [str(i + 1) for i in range(65537)]):
            with self.assertRaises(ValueError):
                w.encode_frame(w.SERVER, "Delta", dict(delta=self.delta(removedHandles=handles)))
        packet = w.encode_frame(w.SERVER, "Delta", dict(delta=self.delta(removedHandles=["7"])))
        self.assertEqual(["7"], w.decode_frame(w.SERVER, packet)[1]["delta"]["removedHandles"])

    def test_text_flags_capacities_and_targets_fail_closed(self):
        for name in ("", " \u00a0", "x\0", "\ud800", "中" * 43):
            with self.assertRaises((ValueError, UnicodeError)):
                w.encode_frame(w.CLIENT, "Hello", dict(playerName=name, joinToken="B"))
        for length in (-1, 8, 4194305, (1 << 31) - 1):
            with self.assertRaises(ValueError): w.decode_frame(w.CLIENT, struct.pack(">i", length))
        for target in (dict(kind="NONE"), dict(kind="UNIT", handle="1"), dict(kind="POINT", x=math.nan, y=0.0)):
            with self.assertRaises(ValueError): w.encode_frame(w.CLIENT, "Order", dict(sequence="1", viewSequence="1", kind="MOVE", unitHandles=["1"], target=target))
        action = dict(sequence="1", viewSequence="1", panelSequence="1", unitHandle="1", actionHandle="1",
                      target=dict(kind="NONE"), queued=False, cancel=True)
        self.assertEqual(55, len(w.encode_frame(w.CLIENT, "ActionIntent", action)))
        with self.assertRaises(ValueError): w.encode_frame(w.CLIENT, "ActionIntent", dict(action, queued=True))
        bad = bytearray(w.encode_frame(w.CLIENT, "ActionIntent", action)); bad[-1] = 4
        with self.assertRaises(ValueError): w.decode_frame(w.CLIENT, bad)

    @staticmethod
    def delta(**kwargs):
        result = dict(baseSequence="1", sequence="2", tick=6, teamId=0, credits=None, fog=None,
                      enteredUnits=[], unitPatches=[], removedHandles=[], matchStatus=None)
        result.update(kwargs)
        return result


if __name__ == "__main__":
    unittest.main()
