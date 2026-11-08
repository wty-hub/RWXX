#!/usr/bin/env python3
"""Offline stock-art compiler. Runtime clients never execute this parser or read unit INIs.

Only literal graphics values and the repository's closed copyFrom graph are accepted. No eval,
scripts, engine bootstrap, TMX, external paths, or filesystem unit discovery is used for coverage.
This produces static reference poses, not animation, independent aim, or module-state rendering.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import struct

ROOT = Path(__file__).resolve().parents[4]
ASSETS = ROOT / "assets"
SERVER_MANIFEST = ROOT / "core/src/main/kotlin/io/github/rwx/net/authoritative/StockUnitManifest.kt"
ENUM_SOURCE = ROOT / "core/src/main/java/com/corrodinggames/rts/game/units/UnitTypeEnum.java"
NATIVE_SOURCE = Path(__file__).with_name("authoritative_native_visuals.json")
OUTPUT = ROOT / "desktop/src/main/kotlin/io/github/rwx/authoritative/AuthoritativeUnitVisualManifest.kt"
AUDIT_OUTPUT = ROOT / "desktop/src/test/resources/authoritative/unit-visual-audit.json"


class ManifestError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise ManifestError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


class Compiler:
    def __init__(self, root=ROOT):
        self.root = root
        self.assets = root / "assets"
        self.images = {}
        self.sources = {}
        self.ini_cache = {}

    def source(self, path):
        path = path.resolve()
        require(path.is_relative_to(self.root.resolve()), f"Source escapes repository: {path}")
        data = path.read_bytes()
        self.sources[path.relative_to(self.root.resolve()).as_posix()] = digest(data)
        return data.decode("utf-8-sig")

    def raw_ini(self, path):
        sections = {}
        section = ""
        for raw in self.source(path).splitlines():
            line = raw.strip()
            if not line or line.startswith(("#", ";")):
                continue
            if line.startswith("[") and line.endswith("]"):
                section = line[1:-1]
                sections.setdefault(section, {})
                continue
            match = re.match(r"([^:=]+)\s*[:=]\s*(.*)", line)
            if match:
                sections.setdefault(section, {})[match[1].strip()] = match[2].strip()
        return sections

    def inherited_ini(self, path, stack=()):
        path = path.resolve()
        require(len(stack) <= 10 and path not in stack, f"copyFrom cycle/depth: {path}")
        if path in self.ini_cache:
            return self.ini_cache[path]
        result = self.raw_ini(path)
        copies = result.get("core", {}).get("copyFrom", "")
        # Matches IniFile.merge: local values win, then the last named copy and its ancestors.
        for value in reversed(copies.split(",")):
            value = value.strip()
            if not value:
                continue
            require(".." not in value, f"Unapproved copyFrom: {value}")
            base = self.assets / "units" if value.startswith(("CORE:", "ROOT:")) else path.parent
            relative = value.split(":", 1)[1] if value.startswith(("CORE:", "ROOT:")) else value
            for section, values in self.inherited_ini(base / relative, stack + (path,)).items():
                target = result.setdefault(section, {})
                if target.get("@copyFrom_skipThisSection", "false").lower() != "true":
                    for key, text in values.items():
                        target.setdefault(key, text)
        for section in list(result):
            self.copy_section(result, section, section)
        self.ini_cache[path] = result
        return result

    def copy_section(self, sections, target, source, stack=()):
        require(len(stack) <= 10 and source not in stack, f"Section copy cycle: {source}")
        for name in reversed(sections[source].get("@copyFromSection", "").split(",")):
            name = name.strip()
            if not name:
                continue
            require(name in sections, f"Missing copied section: {name}")
            for key, value in sections[name].items():
                sections[target].setdefault(key, value)
            self.copy_section(sections, target, name, stack + (source,))

    def png(self, asset_path):
        require(asset_path.endswith(".png") and "\\" not in asset_path, f"Not a fixed PNG: {asset_path}")
        path = (self.assets / asset_path).resolve()
        require(path.is_relative_to(self.assets.resolve()), f"PNG escapes asset root: {asset_path}")
        asset_path = path.relative_to(self.assets.resolve()).as_posix()
        if asset_path not in self.images:
            data = path.read_bytes()
            require(24 <= len(data) <= 1024 * 1024 and data[:8] == b"\x89PNG\r\n\x1a\n" and data[12:16] == b"IHDR",
                    f"Invalid or oversized PNG: {asset_path}")
            width, height = struct.unpack(">II", data[16:24])
            require(0 < width <= 4096 and 0 < height <= 4096, f"Unbounded image: {asset_path}")
            self.images[asset_path] = {"path": asset_path, "width": width, "height": height, "sha256": digest(data)}
        return asset_path

    def image_path(self, text, ini_path):
        require("${" not in text, f"Unresolved image expression: {text}")
        shadow = False
        while text.startswith("SHADOW:"):
            shadow = True
            text = text[7:]
        if text.upper() in ("NONE", "IGNORE", ""):
            return None, shadow
        if text.startswith("SHARED:"):
            path = "units/shared/" + text[7:]
        elif text.startswith(("CORE:", "ROOT:")):
            path = "units/" + text.split(":", 1)[1]
        else:
            path = (Path(ini_path).parent / text).as_posix()
        return self.png(path), shadow

    @staticmethod
    def number(text, default=0):
        if text is None:
            return default
        require(re.fullmatch(r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?", text) is not None,
                f"Nonliteral numeric display value: {text}")
        value = float(text)
        require(math.isfinite(value) and abs(value) <= 10000, f"Unbounded display value: {text}")
        return value

    def layer(self, image, frame=None, x=0, y=0, angle=0, scale=1, tint=True, shadow=False, source=None):
        if image is None:
            return None
        spec = self.images[image]
        sx, sy = source or (0, 0)
        width, height = frame or (spec["width"], spec["height"])
        require(all(type(v) is int for v in (sx, sy, width, height)) and 0 <= sx < sx + width <= spec["width"]
                and 0 <= sy < sy + height <= spec["height"], f"Invalid crop: {image}")
        require(scale > 0 and math.isfinite(scale), f"Invalid scale: {image}")
        return dict(image=image, sourceX=sx, sourceY=sy, width=width, height=height,
                    x=float(x), y=float(y), angle=float(angle), scale=float(scale), tint=tint, shadow=shadow)

    def custom(self, name, ini_path, key):
        sections = self.inherited_ini(self.assets / ini_path)
        graphics = sections.get("graphics", {})
        core = sections.get("core", {})
        body_path, body_shadow = self.image_path(graphics["image"], ini_path)
        require(body_path is not None, f"No body image: {name}")
        image = self.images[body_path]
        frames = int(self.number(graphics.get("total_frames"), 1))
        require(frames >= 1, f"Invalid frame count: {name}")
        fw = int(self.number(graphics.get("frame_width"), max(1, image["width"] // frames)))
        fh = int(self.number(graphics.get("frame_height"), image["height"]))
        frame = int(self.number(graphics.get("default_frame"), 0))
        columns = max(1, image["width"] // fw)
        body = self.layer(body_path, (fw, fh), source=(frame % columns * fw, frame // columns * fh),
                          x=self.number(graphics.get("image_offsetX")), y=self.number(graphics.get("image_offsetY")),
                          shadow=body_shadow)
        back_path, back_shadow = self.image_path(graphics.get("image_back", "NONE"), ini_path)
        full_back = graphics.get("image_back_always_use_full_image", "false").lower() == "true"
        back = self.layer(back_path, None if full_back else (fw, fh), tint=False, shadow=back_shadow)
        limits = []
        if frames > 1 or any(s.startswith("animation_") for s in sections) or any(k.startswith("animation_") for k in graphics):
            limits.append("animation_state_required")
        if graphics.get("lock_body_rotation_with_main_turret", "false").lower() == "true":
            limits.append("body_main_turret_heading_state_required")
        if any(s.startswith(("leg_", "arm_")) for s in sections):
            limits.append("leg_or_arm_pose_state_required")
        if any(s.startswith("attachment_") for s in sections):
            limits.append("attachment_type_and_pose_state_required")
        if any(s.startswith("decal_") for s in sections):
            limits.append("conditional_decal_state_required")
        if body_path == "units/shared/blank.png":
            limits.append("transparent_body_requires_other_visual_state")
        turrets = []
        turret_cache = {}

        def turret_values(section, chain=()):
            require(section not in chain and len(chain) <= 10, f"Turret copy cycle: {name}/{section}")
            if section in turret_cache:
                return turret_cache[section]
            values = dict(sections[section])
            copied = values.get("copyFrom")
            if copied:
                ref = "turret_" + copied
                require(ref in sections, f"Missing copied turret {ref}")
                for k, v in turret_values(ref, chain + (section,)).items():
                    values.setdefault(k, v)
            turret_cache[section] = values
            return values

        def anchor(section, chain=()):
            require(section not in chain, f"Turret attachment cycle: {name}/{section}")
            t = turret_values(section)
            x, y = self.number(t.get("x")), self.number(t.get("y"))
            angle = self.number(t.get("idleDir"))
            attached = t.get("attachedTo")
            if attached:
                px, py, pa = anchor("turret_" + attached, chain + (section,))
                radians = math.radians(pa)
                x, y = px + x * math.cos(radians) + y * math.sin(radians), py - x * math.sin(radians) + y * math.cos(radians)
                angle += pa
            return x, y, angle

        for section in sections:
            if not section.startswith("turret_"):
                continue
            t = turret_values(section)
            invisible = t.get("invisible", "false").lower()
            if invisible == "true":
                continue
            if invisible != "false":
                limits.append(f"conditional_turret_omitted:{section}")
                continue
            try:
                path, shadow = self.image_path(t.get("image", graphics.get("image_turret", "NONE")), ini_path)
                if path is None:
                    continue
                x, y, angle = anchor(section)
                apply_team = t.get("image_applyTeamColors", graphics.get("teamColorsOnTurret", "false")).lower() == "true"
                turrets.append(self.layer(path, x=x, y=-y, angle=angle,
                    scale=self.number(graphics.get("turretImageScale"), 1), tint=apply_team, shadow=shadow))
                limits.append("independent_turret_state_required")
            except ManifestError as error:
                limits.append(f"unresolved_turret_omitted:{section}:{error}")
        variants = {}
        for section, values in sections.items():
            for field, text in values.items():
                if section == "graphics" and field in ("image_wreak", "image_shield") or (
                    section.startswith(("leg_", "arm_", "decal_")) and field in {
                        "image", "image_leg", "image_foot", "image_foot_shadow", "image_end", "image_middle", "image_end_shadow"}):
                    try:
                        path, _ = self.image_path(text, ini_path)
                        if path:
                            variants[f"{section}.{field}"] = path
                    except (ManifestError, FileNotFoundError) as error:
                        limits.append(f"unresolved_auxiliary_omitted:{section}.{field}:{error}")
        shadow_text = graphics.get("image_shadow", "NONE")
        auto_shadow = shadow_text.upper() in ("AUTO", "AUTO_ANIMATED")
        shadow_path, shadow_mask = (None, False) if auto_shadow else self.image_path(shadow_text, ini_path)
        shadow = self.layer(shadow_path, tint=False, shadow=shadow_mask)
        if auto_shadow and back is not None:
            limits.append("automatic_shadow_uses_body_reference_pose")
        return dict(key=key, source=ini_path, body=body, back=back, turrets=turrets, shadow=shadow,
                    autoShadow=auto_shadow, scale=self.number(graphics.get("imageScale"), 1),
                    rotates=core.get("isBuilding", "false").lower() != "true",
                    colorMode=graphics.get("teamColoringMode", "pureGreen"),
                    shadowX=self.number(graphics.get("shadowOffsetX"), 1), shadowY=self.number(graphics.get("shadowOffsetY"), 1),
                    shadowAlpha=85, shadowCondition="reference_pose",
                    variants=variants, limitations=sorted(set(limits)))

    def native(self, key, definition):
        matches = list((self.root / "core/src/main/java").rglob(definition["class"] + ".java"))
        require(len(matches) == 1, f"Ambiguous native source: {key}")
        self.source(matches[0])
        shadow_alpha = definition.get("shadowAlpha", 85)
        shadow_condition = definition.get("shadowCondition", "reference_pose")
        require(type(shadow_alpha) is int and 0 <= shadow_alpha <= 255, f"Invalid native shadow alpha: {key}")
        require(shadow_condition in {"reference_pose", "surface_gt_minus2", "surface_ge_zero", "completed_non_invulnerable"},
                f"Unreviewed native shadow condition: {key}")
        def png(name):
            return self.png(f"drawable/{name}.png") if name else None
        body = self.layer(png(definition.get("body")), definition.get("frame"), tint=definition.get("teamColors", True))
        return dict(key=key, source=matches[0].relative_to(self.root).as_posix(), body=body,
            back=self.layer(png(definition.get("back")), definition.get("backFrame"),
                source=definition.get("backSource"), tint=False),
            turrets=[self.layer(png(t["image"]), x=t.get("x", 0), y=-t.get("y", 0),
                tint=False) for t in definition.get("turrets", [])],
            shadow=self.layer(png(definition.get("shadow")), tint=False), autoShadow=definition.get("autoShadow", False),
            scale=1.0, rotates=not definition.get("building", False), colorMode="pureGreen",
            shadowX=float(definition.get("shadowX", 1)), shadowY=float(definition.get("shadowY", 1)),
            shadowAlpha=shadow_alpha, shadowCondition=shadow_condition,
            variants={name: png(image) for name, image in definition.get("variants", {}).items()},
            limitations=definition.get("limitations", []))

    def compile(self):
        manifest = self.source(self.root / SERVER_MANIFEST.relative_to(ROOT))
        specs = re.findall(r'Spec\("([^"\n]+)", "([^"\n]+)"(?:, UnitTypeEnum\.([A-Za-z0-9_]+))?\)',
                           manifest.split("// This config")[0])
        require(len(specs) == 120, "Review changed stock INI inventory before regenerating art")
        natives = re.findall(r"^    ([A-Za-z_][A-Za-z0-9_]*) \{", self.source(self.root / ENUM_SOURCE.relative_to(ROOT)), re.M)
        excluded = {"editorOrBuilder", "dummyNonUnitWithTeam"}
        native_defs = json.loads(self.source(self.root / NATIVE_SOURCE.relative_to(ROOT)))["units"]
        replacements = {native for _, _, native in specs if native}
        require(set(native_defs) == set(natives) - excluded - replacements, "Native art table differs from closed public inventory")
        entries = [self.custom(name, path, native or name) for name, path, native in specs]
        entries += [self.native(key, value) for key, value in native_defs.items()]
        require(len(entries) == 152 and len({e["key"] for e in entries}) == 152, "Public visual inventory is not exactly 152 keys")
        return {"schema": 1, "fidelity": "Static reference poses only; limits enumerate unavailable visual state",
                "entries": sorted(entries, key=lambda e: e["key"]),
                "images": dict(sorted(self.images.items())), "sources": dict(sorted(self.sources.items()))}


def kotlin(data):
    def q(value):
        return json.dumps(value, ensure_ascii=True)
    def f(value):
        return repr(float(value)) + "f"
    def layer(value):
        if value is None:
            return "null"
        return "Layer(" + ", ".join([q(value["image"]), *(str(value[k]) for k in ("sourceX", "sourceY", "width", "height")),
            *(f(value[k]) for k in ("x", "y", "angle", "scale")), str(value["tint"]).lower(), str(value["shadow"]).lower()]) + ")"
    lines = ["// Generated offline by generate_authoritative_visual_manifest.py. Do not edit.",
        "package io.github.rwx.authoritative", "", "/** Static approved artwork, not a native simulation or animation-state evaluator. */",
        "internal object AuthoritativeUnitVisualManifest {",
        "    data class Image(val path: String, val width: Int, val height: Int, val sha256: String)",
        "    data class Layer(val image: String, val sourceX: Int, val sourceY: Int, val width: Int, val height: Int,",
        "        val x: Float, val y: Float, val angle: Float, val scale: Float, val tint: Boolean, val shadow: Boolean)",
        "    data class Spec(val key: String, val source: String, val body: Layer?, val back: Layer?, val turrets: List<Layer>,",
        "        val shadow: Layer?, val autoShadow: Boolean, val scale: Float, val rotates: Boolean, val colorMode: String,",
        "        val shadowX: Float, val shadowY: Float, val variants: Map<String, String>, val limitations: List<String>,",
        "        val shadowAlpha: Int, val shadowCondition: String)", ""]
    images = list(data["images"].values())
    chunks = [images[i:i + 24] for i in range(0, len(images), 24)]
    lines += ["    val images: Map<String, Image> = buildMap {"] + [f"        putAll(images{i}())" for i in range(len(chunks))] + ["    }"]
    for index, chunk in enumerate(chunks):
        lines += [f"    private fun images{index}(): Map<String, Image> = mapOf("]
        lines += [f'        {q(v["path"])} to Image({q(v["path"])}, {v["width"]}, {v["height"]}, {q(v["sha256"])}),' for v in chunk]
        lines += ["    )"]
    entries = data["entries"]
    chunks = [entries[i:i + 12] for i in range(0, len(entries), 12)]
    lines += ["", "    val entries: Map<String, Spec> = buildMap {"] + [f"        putAll(entries{i}())" for i in range(len(chunks))] + ["    }"]
    for index, chunk in enumerate(chunks):
        lines += [f"    private fun entries{index}(): Map<String, Spec> = mapOf("]
        for e in chunk:
            turrets = "listOf(" + ", ".join(map(layer, e["turrets"])) + ")"
            variants = "mapOf(" + ", ".join(q(k) + " to " + q(v) for k, v in e["variants"].items()) + ")"
            limitations = "listOf(" + ", ".join(map(q, e["limitations"])) + ")"
            values = [q(e["key"]), q(e["source"]), layer(e["body"]), layer(e["back"]), turrets, layer(e["shadow"]),
                str(e["autoShadow"]).lower(), f(e["scale"]), str(e["rotates"]).lower(), q(e["colorMode"]),
                f(e["shadowX"]), f(e["shadowY"]), variants, limitations, str(e["shadowAlpha"]), q(e["shadowCondition"])]
            lines += [f'        {q(e["key"])} to Spec(' + ", ".join(values) + "),"]
        lines += ["    )"]
    lines += ["}", ""]
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Compare generated files without changing them")
    args = parser.parse_args(argv)
    data = Compiler().compile()
    outputs = {OUTPUT: kotlin(data), AUDIT_OUTPUT: json.dumps(data, indent=2, ensure_ascii=True) + "\n"}
    for path, text in outputs.items():
        if args.check:
            require(path.is_file() and path.read_text() == text, f"Generated visual manifest is stale: {path}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
    print(f'{len(data["entries"])} public visual entries; {len(data["images"])} approved PNGs; '
          f'{sum(bool(e["limitations"]) for e in data["entries"])} entries declare remaining state requirements')


if __name__ == "__main__":
    main()
