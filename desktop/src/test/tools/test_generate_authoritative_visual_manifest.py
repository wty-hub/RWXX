"""Offline source/catalog checks. No JVM, game engine, subprocess, or graphical surface."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest

import generate_authoritative_visual_manifest as generator


class StockVisualManifestTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = generator.Compiler().compile()

    def test_checked_in_data_matches_the_closed_server_inventory_and_sources(self):
        self.assertEqual(152, len(self.data["entries"]))
        self.assertEqual(152, len({row["key"] for row in self.data["entries"]}))
        self.assertEqual(generator.kotlin(self.data), generator.OUTPUT.read_text())
        self.assertEqual(self.data, json.loads(generator.AUDIT_OUTPUT.read_text()))
        with contextlib.redirect_stdout(io.StringIO()):
            generator.main(["--check"])

    def test_every_png_is_bounded_and_belongs_to_the_fixed_asset_tree(self):
        self.assertGreaterEqual(len(self.data["images"]), 250)
        for path, image in self.data["images"].items():
            self.assertEqual(path, image["path"])
            self.assertTrue(path.startswith(("drawable/", "units/")))
            self.assertNotIn("..", Path(path).parts)
            self.assertTrue(0 < image["width"] <= 4096 and 0 < image["height"] <= 4096)
            self.assertRegex(image["sha256"], r"^[0-9a-f]{64}$")

    def test_verified_six_reference_frames_keep_their_known_stock_metrics(self):
        entries = {row["key"]: row for row in self.data["entries"]}
        self.assertEqual((16, 30, .8), (entries["tank"]["body"]["width"], entries["tank"]["body"]["height"], entries["tank"]["scale"]))
        self.assertEqual((35, 32), (entries["turret"]["body"]["width"], entries["turret"]["body"]["height"]))
        self.assertEqual(-6, entries["turret"]["turrets"][0]["y"])
        self.assertEqual((37, 59), (entries["extractor"]["body"]["width"], entries["extractor"]["body"]["height"]))
        self.assertEqual((51, 44), (entries["extractor"]["back"]["width"], entries["extractor"]["back"]["height"]))
        self.assertEqual((53, 68), (entries["commandCenter"]["body"]["width"], entries["commandCenter"]["body"]["height"]))
        self.assertTrue(entries["ladybug"]["body"]["tint"], "Ladybug.loadTextures builds PlayerTeam textures and d() selects by team")
        for key in ("landFactory", "airFactory", "seaFactory", "laserDefence", "supplyDepot"):
            self.assertIn("level2", entries[key]["variants"])
            self.assertNotIn("native_upgrade_level_required", entries[key]["limitations"], "upgradeLevel now selects this reviewed variant")

    def test_reference_pose_limitations_never_claim_missing_visual_state_is_complete(self):
        for row in self.data["entries"]:
            if row["turrets"]:
                self.assertIn("independent_turret_state_required", row["limitations"], row["key"])
            if row["body"] is None:
                self.assertTrue(row["limitations"], row["key"])
            self.assertFalse(any(value.startswith("unresolved") for value in row["limitations"]), row["key"])
        bodyless = {row["key"] for row in self.data["entries"] if row["body"] is None}
        self.assertEqual({"fogRevealer", "damagingBorder", "zoneMarker"}, bodyless)

    def test_tree_reference_includes_the_native_unrotated_secondary_frame_before_the_main_frame(self):
        tree = next(row for row in self.data["entries"] if row["key"] == "tree")
        self.assertEqual("drawable/trees.png", tree["back"]["image"])
        self.assertEqual((25, 0, 25, 30), tuple(tree["back"][key] for key in ("sourceX", "sourceY", "width", "height")))
        self.assertFalse(tree["back"]["tint"])
        self.assertIn("tree_subtype_scale_and_falling_state_required", tree["limitations"])

    def test_native_extra_shadow_masks_and_offsets_keep_their_setting_and_state_limits(self):
        entries = {row["key"]: row for row in self.data["entries"]}
        for key, offset in {"missileShip": 3, "gunBoat": 1, "battleShip": 3, "heavyTank": 2,
                            "attackSubmarine": 0, "builderShip": 3, "experimentalHoverTank": 4}.items():
            entry = entries[key]
            self.assertTrue(entry["autoShadow"], key)
            self.assertEqual((offset, offset), (entry["shadowX"], entry["shadowY"]), key)
            self.assertEqual(128, entry["shadowAlpha"], key)
            self.assertIn("extra_shadow_setting_not_in_view", entry["limitations"], key)
        self.assertEqual("surface_ge_zero", entries["attackSubmarine"]["shadowCondition"])
        self.assertEqual("completed_non_invulnerable", entries["heavyTank"]["shadowCondition"])
        self.assertIn("native_main_turret_heading_state_required", entries["amphibiousJet"]["limitations"])

    def test_copy_order_and_local_values_match_missing_only_native_merge(self):
        with tempfile.TemporaryDirectory(prefix="rwxx-offline-art-") as directory:
            root = Path(directory).resolve()
            units = root / "assets/units"
            units.mkdir(parents=True)
            (units / "a.ini").write_text("[graphics]\nimage: a.png\nimageScale: 2\n")
            (units / "b.ini").write_text("[graphics]\nimage: b.png\n")
            current = units / "unit.ini"
            current.write_text("[core]\ncopyFrom: a.ini,b.ini\n[graphics]\nimageScale: 3\n")
            compiler = generator.Compiler(root)
            result = compiler.inherited_ini(current)
            self.assertEqual("b.png", result["graphics"]["image"])
            self.assertEqual("3", result["graphics"]["imageScale"])

    def test_cycles_dynamic_numbers_and_escaping_sources_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix="rwxx-offline-art-") as directory:
            root = Path(directory).resolve()
            units = root / "assets/units"
            units.mkdir(parents=True)
            current = units / "unit.ini"
            current.write_text("[core]\ncopyFrom: unit.ini\n")
            compiler = generator.Compiler(root)
            with self.assertRaises(generator.ManifestError):
                compiler.inherited_ini(current)
            with self.assertRaises(generator.ManifestError):
                compiler.png("../outside.png")
            with self.assertRaises(generator.ManifestError):
                compiler.image_path("${core.dynamicImage}", "units/unit.ini")
        for value in ("${self.hp}", "__import__('os')", "1+2", "NaN", "inf"):
            with self.subTest(value=value), self.assertRaises(generator.ManifestError):
                generator.Compiler.number(value)


if __name__ == "__main__":
    unittest.main()
