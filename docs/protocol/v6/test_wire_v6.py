"""Offline boundary checks for the v6 reference codec; no network access."""
import math
import struct
import unittest
import wire_v6 as w


class WireV6Tests(unittest.TestCase):
    def test_fixed_bytes_and_no_v4_or_hello_cache_list(self):
        self.assertEqual(bytes.fromhex("00000009525758410000000602"), w.encode_frame(w.CLIENT, "KeepAlive", {}))
        packet = w.encode_frame(w.CLIENT, "Hello", dict(playerName="A", joinToken="B"))
        self.assertEqual(23, len(packet))
        self.assertEqual("0000001352575841000000060100000001410000000142", packet.hex())
        for version in (1, 2, 3, 4, 5, 7):
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

    def test_checks_exact_lengths_channels_sequences_and_old_wire_rejection(self):
        fields = dict(channel=1, checkSequence=str((1 << 63) - 1), stateSequence=str((1 << 53) + 1), digest=list(range(16)))
        for direction, name, code in ((w.CLIENT, "StateAck", 8), (w.SERVER, "StateCheck", 110)):
            packet = w.encode_frame(direction, name, fields)
            self.assertEqual(46, len(packet))
            self.assertEqual((42, 0x52575841, 6, code), struct.unpack(">iiiB", packet[:13]))
            self.assertEqual(fields, w.decode_frame(direction, packet)[1])
            for channel in (0, 3, 255):
                with self.assertRaises(ValueError): w.encode_frame(direction, name, dict(fields, channel=channel))
            for field in ("checkSequence", "stateSequence"):
                for value in ("0", str(-(1 << 63))):
                    with self.assertRaises(ValueError): w.encode_frame(direction, name, dict(fields, **{field: value}))
            for digest in ([], [0] * 15, [0] * 17, [-1] * 16, [256] * 16, [False] * 16):
                with self.assertRaises(ValueError): w.encode_frame(direction, name, dict(fields, digest=digest))
            for declared in (41, 43, 4194304):
                bad = bytearray(packet); struct.pack_into(">i", bad, 0, declared)
                with self.assertRaises(ValueError): w.decode_frame(direction, bad)
            bad = bytearray(packet); struct.pack_into(">i", bad, 8, 5)
            with self.assertRaises(ValueError): w.decode_frame(direction, bad)

    def test_complete_digest_domains_static_mutation_negative_zero_and_unit_order(self):
        unit = dict(handle="9", typeName="坦克🚀", teamId=0, x=0.0, y=1.0, height=0.0, heading=0.0,
                    health=1.0, maxHealth=100.0, buildProgress=1.0, controllable=True, upgradeLevel=1)
        state = dict(sequence="1", tick=0, teamId=0, credits=-0.0, mapWidth=2, mapHeight=2, tileWidth=20,
                     tileHeight=20, terrain=[0, 1, 4, 8], mapVisual=None, fog=[0, 5, 10, 10],
                     units=[unit, dict(unit, handle="1")], matchStatus=dict(phase="RUNNING", winnerTeamIds=[], defeatedTeamIds=[]))
        digest = w.state_digest(1, state)
        self.assertEqual(16, len(digest))
        self.assertEqual(digest, w.state_digest(1, dict(state, units=list(reversed(state["units"])))))
        for change in (dict(credits=0.0), dict(sequence="2"), dict(tick=1), dict(terrain=[1, 1, 4, 8]),
                       dict(fog=[1, 5, 10, 10]), dict(units=[dict(unit, x=-0.0), state["units"][1]]),
                       dict(units=[dict(unit, upgradeLevel=2), state["units"][1]])):
            self.assertNotEqual(digest, w.state_digest(1, dict(state, **change)))
        state["terrain"][0] = 1
        self.assertNotEqual(digest, w.state_digest(1, state))
        with self.assertRaises(ValueError): w.state_digest(1, dict(state, fog=[0]))
        with self.assertRaises(ValueError): w.state_digest(1, dict(state, units=[dict(unit, x=40.0 - 1e-10)]))
        with self.assertRaises(ValueError): w.state_digest(3, state)

    def test_panel_digest_preserves_action_order_and_costs(self):
        a = dict(handle="1", kind="PRODUCE", targetMode="NONE", presentationKey="produce.tank", producedTypeKey="tank",
                 upfrontCost=dict(credits=100, energy=-0.0, hp=1.0, shield=2.0, ammo=3), streamingCost=None,
                 readiness="READY", queuedCount=1, stockpileCount=-1, progress=-0.0, canCancel=True)
        p = dict(querySequence="1", panelSequence="1", viewSequence="1", teamId=0, unitHandle="9",
                 actions=[a, dict(a, handle="2")])
        digest = w.state_digest(2, p)
        self.assertEqual(w.hashlib.sha256(b"RWXA-PANEL-6\0" + w.encode_frame(w.SERVER, "ActionPanelBaseline", dict(panel=p))[13:]).digest()[:16], digest)
        for actions in (list(reversed(p["actions"])), [dict(a, progress=0.0), p["actions"][1]],
                        [dict(a, upfrontCost=dict(a["upfrontCost"], credits=101)), p["actions"][1]]):
            self.assertNotEqual(digest, w.state_digest(2, dict(p, actions=actions)))

    @staticmethod
    def delta(**kwargs):
        result = dict(baseSequence="1", sequence="2", tick=6, teamId=0, credits=None, fog=None,
                      enteredUnits=[], unitPatches=[], removedHandles=[], matchStatus=None)
        result.update(kwargs)
        return result


if __name__ == "__main__":
    unittest.main()
