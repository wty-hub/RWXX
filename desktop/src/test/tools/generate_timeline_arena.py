#!/usr/bin/env python3
"""Generate an approved static Timeline Arena; no engine, GUI or dynamic world is read.

The tileset definitions come from the unchanged bundled Small Island source. Geometry matches
TimelineStaticSpawns: fixed public anchors, row-major tie breaking and nine-tile separation.
Run with --check to compare the checked-in 128-cell artifact without writing it.
Use --large to select the separate 512-cell Large Arena. The default artifact is unchanged.
"""
from __future__ import annotations

import argparse
import base64
import copy
import gzip
import hashlib
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
SOURCE = ROOT / "assets/maps/skirmish/[p2]Small_Island (2p).tmx"
OUTPUT = ROOT / "assets/maps/skirmish/[p2]RWXX Timeline Arena.tmx"
LARGE_OUTPUT = ROOT / "assets/maps/skirmish/[p2]RWXX Timeline Large Arena.tmx"
SIZE = 128
STARTS = ((8, 8), (119, 119))
SPACING = 9


def patches(terrain: list[int], size: int = SIZE,
            starts: tuple[tuple[int, int], ...] = STARTS) -> dict[int, tuple[int, int]]:
    candidates = []
    for y in range(2, size - 3):
        for x in range(2, size - 5):
            if all(terrain[(y + dy) * size + x + dx] == 0
                   for dy in range(-2, 4) for dx in range(-2, 6)):
                candidates.append((x, y))
    # Same minimum squared distance and row-major tie break as the room planner.
    # Only the newly chosen anchor can decrease a candidate's previous minimum.
    distances = [min((x - a) ** 2 + (y - b) ** 2 for a, b in starts)
                 if all(abs(x - a) >= SPACING or abs(y - b) >= SPACING for a, b in starts)
                 else -1 for x, y in candidates]
    result = {}
    for seat in range(2, 64):
        if not distances:
            break
        index = max(range(len(distances)), key=distances.__getitem__)
        if distances[index] < 0:
            break
        chosen = candidates[index]
        result[seat] = chosen
        a, b = chosen
        for index, (x, y) in enumerate(candidates):
            if distances[index] < 0:
                continue
            if abs(x - a) < SPACING and abs(y - b) < SPACING:
                distances[index] = -1
            else:
                distances[index] = min(distances[index], (x - a) ** 2 + (y - b) ** 2)
    return result


def closest_pool(x: int, y: int, size: int, occupied: set[tuple[int, int]],
                 pools: list[tuple[int, int]]) -> tuple[int, int]:
    # Enumerate the exact (Manhattan distance, row, column) ordering without scanning
    # every far-away cell for each pool. No topology or dynamic occupancy is consulted.
    for distance in range(size * 2):
        for b in range(max(2, y - distance), min(size - 3, y + distance) + 1):
            dx = distance - abs(b - y)
            for a in sorted({x - dx, x + dx}):
                if 2 <= a < size - 2 and (a, b) not in occupied and all(
                        max(abs(a - p), abs(b - q)) >= 4 for p, q in pools):
                    return a, b
    raise RuntimeError("No public resource location remains")


def generate(large: bool = False) -> tuple[bytes, dict]:
    size = 512 if large else SIZE
    starts = ((8, 8), (size - 9, size - 9))
    output = LARGE_OUTPUT if large else OUTPUT
    source = ET.parse(SOURCE).getroot()
    tilesets = {item.attrib["name"]: item for item in source.findall("tileset") if "name" in item.attrib}
    terrain = [0] * (size * size)
    dynamic = patches(terrain, size, starts)
    if len(dynamic) != 62:
        raise RuntimeError("The approved static layout must contain all 62 optional patches")
    all_starts = list(starts) + list(dynamic.values())
    occupied = {(x + dx, y + dy) for x, y in all_starts for dy in range(-2, 4) for dx in range(-2, 6)}
    pools: list[tuple[int, int]] = []
    for x, y in all_starts:
        pool = closest_pool(x, y, size, occupied, pools)
        if max(abs(pool[0] - x), abs(pool[1] - y)) > 12:
            raise RuntimeError("Resource pool would be unreasonably far from its public start")
        pools.append(pool)
        terrain[pool[1] * size + pool[0]] = 4
    if patches(terrain, size, starts) != dynamic:
        raise RuntimeError("Adding resources changed an approved starting patch")

    root = ET.Element("map", {"version": "1.0", "tiledversion": "1.0.3", "orientation": "orthogonal",
                              "renderorder": "right-down", "width": str(size), "height": str(size),
                              "tilewidth": "20", "tileheight": "20"})
    for name, gid, count in (("Snow", 1, 80), ("misc", 81, 9), ("units", 90, 279)):
        item = copy.deepcopy(tilesets[name])
        item.set("firstgid", str(gid)); item.set("tilecount", str(count))
        if name == "misc":
            item.find("image").set("height", "160")  # The actual closed misc PNG is 79x160.
        if name == "units":
            for tile in list(item.findall("tile")):
                if int(tile.attrib["id"]) not in (1, 6, 46, 51):
                    item.remove(tile)
        root.append(item)
    ground = [1] * (size * size)
    items = [0] * (size * size)
    units = [0] * (size * size)
    for x, y in pools:
        items[y * size + x] = 81 + 4
    for team, (x, y) in enumerate(starts):
        units[y * size + x] = 90 + (6 if team == 0 else 51)
        units[y * size + x + 4] = 90 + (1 if team == 0 else 46)
    for name, plane in (("Ground", ground), ("Items", items), ("Units", units)):
        layer = ET.SubElement(root, "layer", {"name": name, "width": str(size), "height": str(size)})
        data = ET.SubElement(layer, "data", {"encoding": "base64", "compression": "gzip"})
        raw = struct.pack("<" + "I" * len(plane), *plane)
        encoded = base64.b64encode(gzip.compress(raw, compresslevel=9, mtime=0)).decode("ascii")
        data.text = "\n" + "\n".join(encoded[index:index + 76] for index in range(0, len(encoded), 76)) + "\n"
    ET.indent(root, space=" ")
    content = ET.tostring(root, encoding="utf-8", xml_declaration=True) + b"\n"
    report = {"map": str(output.relative_to(ROOT)), "width": size, "height": size,
              "initialSeats": 2, "optionalStaticPatches": len(dynamic), "publicResourcePools": len(pools),
              "spacingTiles": SPACING, "sourceSha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
              "artifactSha256": hashlib.sha256(content).hexdigest(),
              "scope": "Static approved terrain/start geometry only; no simulation capacity claim"}
    return content, report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--large", action="store_true", help="Select the separate 512x512 Large Arena")
    options = parser.parse_args()
    content, report = generate(options.large)
    output = LARGE_OUTPUT if options.large else OUTPUT
    if options.check:
        if not output.is_file() or output.read_bytes() != content:
            raise SystemExit("Checked-in Timeline Arena differs from deterministic generator output")
    else:
        output.write_bytes(content)
    print(json.dumps(report, sort_keys=True))


if __name__ == "__main__":
    main()
