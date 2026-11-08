#!/usr/bin/env python3
import copy
from dataclasses import replace
import hashlib
import json
from pathlib import Path
import unittest
import wire_v7 as w
import messages_v7 as m
import canonical_v7 as c


class CanonicalTests(unittest.TestCase):
    def setUp(self):
        self.path = Path(__file__).with_name("state-fixtures.json")
        self.document = json.loads(self.path.read_text())
        self.state = copy.deepcopy(self.document["states"][0]["state"])

    def test_published_bytes_sizes_and_independently_recomputed_digest(self):
        self.assertEqual(c.verify_fixture(self.path)["states"], 3)
        raw = b"".join(c.canonical_chunks(self.state))
        self.assertEqual(raw[:13], b"RWXT-STATE-7\0")
        self.assertEqual(len(raw), 365)
        self.assertEqual(sum(map(len, c.persistent_chunks(self.state))), 177)
        self.assertEqual(hashlib.sha256(raw).digest()[:16], c.state_digest(self.state))
        self.assertEqual(c.state_digest(self.state).hex(), "d9d58a53f9d767f20b481f6cc3a24bed")

    def test_every_context_header_component_and_static_map_bytes_affects_digest(self):
        before = c.state_digest(self.state)
        for name in ("teamId", "ticksPerSecond", "streamId", "epoch", "revision", "observedThrough",
                     "sealedThrough", "retainedFrom", "retainedUntil", "controlEpoch", "objectHighWater"):
            state = copy.deepcopy(self.state)
            state[name] = str(c.integer(state[name]) + 1)
            with self.subTest(field=name):
                self.assertNotEqual(c.state_digest(state), before)
        for name in ("matchIdHex", "accessIdHex", "mapKeyHex"):
            state = copy.deepcopy(self.state); raw = bytearray.fromhex(state[name]); raw[0] ^= 1
            state[name] = raw.hex()
            self.assertNotEqual(c.state_digest(state), before)
        state = copy.deepcopy(self.state)
        record = w.decode_tlv(bytes.fromhex(state["mapHex"]))
        state["mapHex"] = w.encode_tlv(tuple(replace(f, value=b"\1") if f.field_id == 6 else f for f in record)).hex()
        # Deliberately leave mapKey unchanged: full map data must still be scanned, not replaced by its key.
        self.assertNotEqual(c.state_digest(state), before)

    def test_library_sorting_is_canonical_but_segments_and_events_are_not_normalized(self):
        before = c.state_digest(self.state)
        shuffled = copy.deepcopy(self.state); shuffled["objects"].reverse()
        for obj in shuffled["objects"]:
            obj["curves"].reverse()
        self.assertEqual(c.state_digest(shuffled), before)
        state = copy.deepcopy(self.state)
        curve = state["objects"][0]["curves"][0]
        curve["segments"] = [dict(curve["segments"][0], hi="50"), dict(curve["segments"][0], lo="50")]
        self.assertNotEqual(c.state_digest(state), before, "Equal sampled function is not the same retained record library")
        state = copy.deepcopy(self.state); state["objects"][2]["curves"][0]["events"].reverse()
        with self.assertRaises(w.ProtocolError):
            c.state_digest(state)

    def test_unmodified_fields_opaque_bytes_tail_identity_and_revision_are_rehashed(self):
        before = c.state_digest(self.state)
        variants = []
        for field, value in [("eventHighWater", "5"), ("curveRevision", "3")]:
            state = copy.deepcopy(self.state); state["objects"][2]["curves"][0][field] = value; variants.append(state)
        state = copy.deepcopy(self.state); pulse = state["objects"][2]["curves"][0]
        pulse["metadataHex"] = pulse["metadataHex"][:-2] + "ff"; variants.append(state)
        state = copy.deepcopy(self.state); state["objects"][2]["curves"][0]["events"].pop(); variants.append(state)
        state = copy.deepcopy(self.state); state["objects"][2]["close"] = {"until": "100", "kind": 1}; variants.append(state)
        state = copy.deepcopy(self.state); state["objects"][1]["curves"][0]["segments"][0]["value"] = "2"; variants.append(state)
        for state in variants:
            self.assertNotEqual(c.state_digest(state), before)
        self.assertEqual(c.state_digest(self.state), before)

    def test_optional_cost_components_participate_in_full_state_hash(self):
        state = copy.deepcopy(self.document["states"][2]["state"])
        before = c.state_digest(state)
        action = state["objects"][-1]
        descriptor = w.decode_tlv(bytes.fromhex(action["descriptorHex"]))
        ability = next(f.value for f in descriptor if f.field_id == 4)
        streaming = next(f.value for f in ability if f.field_id == 10)
        changed = tuple(replace(f, value=0) if f.field_id == 4 else f for f in streaming)
        changed_ability = tuple(replace(f, value=changed) if f.field_id == 10 else f for f in ability)
        action["descriptorHex"] = w.encode_tlv(tuple(replace(f, value=changed_ability) if f.field_id == 4 else f for f in descriptor)).hex()
        self.assertNotEqual(c.state_digest(state), before)

    def test_invalid_map_ids_duplicate_handles_and_noninteger_json_reject(self):
        states = []
        state = copy.deepcopy(self.state); state["objects"].append(copy.deepcopy(state["objects"][1])); states.append(state)
        state = copy.deepcopy(self.state); state["revision"] = -0.0; states.append(state)
        state = copy.deepcopy(self.state); state["epoch"] = "01"; states.append(state)
        state = copy.deepcopy(self.state); state["objects"][0]["curves"][0]["segments"][0]["value"] = 1.0; states.append(state)
        state = copy.deepcopy(self.state); state["objects"][1]["curves"].append(copy.deepcopy(state["objects"][1]["curves"][0])); states.append(state)
        state = copy.deepcopy(self.state)
        record = w.decode_tlv(bytes.fromhex(state["mapHex"]))
        state["mapHex"] = w.encode_tlv(tuple(replace(f, value=b"\0\xeb") if f.field_id == 8 else f for f in record)).hex(); states.append(state)
        for state in states:
            with self.assertRaises(w.ProtocolError):
                c.state_digest(state)

    def test_resource_pool_map_must_agree_with_public_terrain(self):
        record = w.decode_tlv(bytes.fromhex(self.state["mapHex"]))
        malformed = tuple(replace(f, value=b"\4") if f.field_id == 6 else f for f in record)
        with self.assertRaises(w.ProtocolError):
            m.validate_map(malformed)
        complete = tuple(replace(f, value=b"\1\0\0") if f.field_id == 10 else f for f in malformed)
        m.validate_map(complete)


if __name__ == "__main__":
    unittest.main()
