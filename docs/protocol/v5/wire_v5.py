#!/usr/bin/env python3
"""RWXX v5 frame codec and offline vector check. Python 3, standard library only.

No sockets, TLS, authentication, game engine or rendering. Public entry points:
decode_frame(direction, bytes) -> (message_name, fields)
encode_frame(direction, message_name, fields) -> bytes
i64 fields are decimal strings in the returned JSON-compatible dictionaries.
"""
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import sys

MAGIC, VERSION, MAX_FRAME, MAX_PANEL = 0x52575841, 5, 4194304, 65536
CLIENT, SERVER = "client-to-server", "server-to-client"
TYPES = {
    CLIENT: {1: "Hello", 2: "KeepAlive", 3: "Order", 4: "ActionPanelRequest", 5: "ActionIntent", 6: "ResyncRequest", 7: "MapReady"},
    SERVER: {101: "Welcome", 102: "MapInit", 103: "Baseline", 104: "Delta", 105: "Rejected",
             106: "ActionPanelBaseline", 107: "ActionPanelDelta", 108: "MapCached", 109: "MapOffer"},
}
ORDERS = ("MOVE", "ATTACK_MOVE", "STOP", "ATTACK_UNIT")
ACTIONS = ("BUILD", "PRODUCE", "UPGRADE", "RALLY", "REPAIR", "RECLAIM", "LOAD_INTO",
           "LOAD_UP", "UNLOAD", "STOP_UNLOAD", "TARGET_GROUND", "TOGGLE", "GUARD", "PATROL", "ATTACK_MODE")
TARGETS, READINESS, PHASES = ("NONE", "POINT", "VISIBLE_UNIT"), ("READY", "BLOCKED", "UNKNOWN"), ("RUNNING", "FINISHED")
SPACE = set("\u0020\u00a0\u1680\u2028\u2029\u202f\u205f\u3000") | {chr(i) for i in range(0x2000, 0x200b)}


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def check_text(value, nonblank=False):
    require(isinstance(value, str), "text must be a string")
    require(not any(ord(c) <= 31 or 127 <= ord(c) <= 159 for c in value), "control character in text")
    require(not nonblank or any(c not in SPACE for c in value), "blank text")


def action_key(value, empty=False):
    require((empty or bool(value)) and len(value) <= 128, "invalid action key length")
    require(all(c.isascii() and (c.isalnum() or c in "_.-") for c in value), "invalid action key")


class Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    @property
    def remaining(self):
        return len(self.data) - self.pos

    def take(self, count):
        require(0 <= count <= self.remaining, "truncated field")
        result = self.data[self.pos:self.pos + count]
        self.pos += count
        return result

    def number(self, fmt):
        result = struct.unpack(">" + fmt, self.take(struct.calcsize(">" + fmt)))[0]
        if fmt in ("f", "d"):
            require(math.isfinite(result), "non-finite float")
        return result

    def long(self):
        return str(self.number("q"))

    def boolean(self):
        value = self.number("B")
        require(value in (0, 1), "invalid boolean")
        return bool(value)

    def text(self, limit, nonblank=False):
        length = self.number("i")
        require(0 <= length <= limit, "invalid text length")
        value = self.take(length).decode("utf-8", errors="strict")
        check_text(value, nonblank)
        return value

    def enum(self, values, first=0):
        index = self.number("B") - first
        require(0 <= index < len(values), "unknown enum")
        return values[index]

    def count(self, maximum, minimum_bytes=0):
        count = self.number("i")
        require(0 <= count <= maximum, "invalid count")
        require(not minimum_bytes or count <= self.remaining // minimum_bytes, "count exceeds frame")
        return count


class Writer:
    def __init__(self):
        self.data = bytearray()

    def put(self, data):
        require(len(self.data) + len(data) <= MAX_FRAME, "frame exceeds limit")
        self.data.extend(data)

    def number(self, fmt, value):
        if fmt in ("f", "d"):
            require(type(value) in (int, float), "numeric float required")
            require(math.isfinite(value), "non-finite float")
        else:
            require(type(value) is int, "exact integer required")
        self.put(struct.pack(">" + fmt, value))

    def long(self, value):
        if type(value) is int:
            number = value
        elif type(value) is str and re.fullmatch(r"-?(?:0|[1-9][0-9]*)", value):
            number = int(value)
        else:
            raise ValueError("i64 requires an exact integer or a decimal string")
        require(-(1 << 63) <= number < (1 << 63), "i64 out of range")
        self.number("q", number)

    def boolean(self, value):
        require(type(value) is bool, "boolean required")
        self.number("B", int(value))

    def text(self, value, limit, nonblank=False):
        check_text(value, nonblank)
        data = value.encode("utf-8", errors="strict")
        require(len(data) <= limit, "text exceeds limit")
        self.number("i", len(data))
        self.put(data)

    def enum(self, values, value, first=0):
        self.number("B", values.index(value) + first)


def read_cost(r):
    if not r.boolean():
        return None
    return dict(credits=r.number("i"), energy=r.number("f"), hp=r.number("f"),
                shield=r.number("f"), ammo=r.number("i"))


def write_cost(w, cost):
    w.boolean(cost is not None)
    if cost is not None:
        for key, fmt in (("credits", "i"), ("energy", "f"), ("hp", "f"), ("shield", "f"), ("ammo", "i")):
            w.number(fmt, cost[key])


def read_panel(r):
    p = dict(querySequence=r.long(), panelSequence=r.long(), viewSequence=r.long(), teamId=r.number("i"), unitHandle=r.long())
    actions = []
    for _ in range(r.count(128, 35)):
        a = dict(handle=r.long(), kind=r.enum(ACTIONS, 1), targetMode=r.enum(TARGETS),
                 presentationKey=r.text(128, True), producedTypeKey=r.text(128))
        a["upfrontCost"], a["streamingCost"] = read_cost(r), read_cost(r)
        a["readiness"] = r.enum(READINESS)
        a["queuedCount"], a["stockpileCount"] = r.number("i"), r.number("i")
        a["progress"], a["canCancel"] = r.number("f"), r.boolean()
        action_key(a["presentationKey"]); action_key(a["producedTypeKey"], True)
        require(int(a["handle"]) > 0, "invalid action handle")
        require(-1 <= a["queuedCount"] <= 65536 and -1 <= a["stockpileCount"] <= 65536, "invalid action count")
        require(a["progress"] == -1 or 0 <= a["progress"] <= 1, "invalid action progress")
        actions.append(a)
    p["actions"] = actions
    require(int(p["querySequence"]) > 0 and int(p["panelSequence"]) > 0 and int(p["viewSequence"]) > 0 and
            0 <= p["teamId"] <= 63 and int(p["unitHandle"]) >= 0, "invalid panel identity")
    require(int(p["unitHandle"]) != 0 or not actions, "cleared panel contains actions")
    require(len({a["handle"] for a in actions}) == len(actions), "duplicate action handle")
    return p


def write_panel(w, p):
    for key in ("querySequence", "panelSequence", "viewSequence"): w.long(p[key])
    w.number("i", p["teamId"]); w.long(p["unitHandle"]); w.number("i", len(p["actions"]))
    for a in p["actions"]:
        w.long(a["handle"]); w.enum(ACTIONS, a["kind"], 1); w.enum(TARGETS, a["targetMode"])
        w.text(a["presentationKey"], 128, True); w.text(a["producedTypeKey"], 128)
        write_cost(w, a["upfrontCost"]); write_cost(w, a["streamingCost"])
        w.enum(READINESS, a["readiness"])
        w.number("i", a["queuedCount"]); w.number("i", a["stockpileCount"])
        w.number("f", a["progress"]); w.boolean(a["canCancel"])


UNIT_FIELDS = (("typeName", "text"), ("teamId", "i"), ("x", "f"), ("y", "f"),
               ("height", "f"), ("heading", "f"), ("health", "f"), ("maxHealth", "f"),
               ("buildProgress", "f"), ("controllable", "bool"), ("upgradeLevel", "B"))
ACTION_FIELDS = (("readiness", "readiness"), ("queuedCount", "i"), ("stockpileCount", "i"),
                 ("progress", "f"), ("canCancel", "bool"))


def read_bytes(r, maximum=1048576, minimum=0):
    count = r.count(maximum, 1)
    require(count >= minimum, "empty array")
    return list(r.take(count))


def write_bytes(w, values):
    require(all(type(v) is int and 0 <= v <= 255 for v in values), "byte array required")
    w.number("i", len(values)); w.put(bytes(values))


def read_map(r):
    m = {key: r.number("i") for key in ("width", "height", "tileWidth", "tileHeight")}
    require(all(1 <= v <= 4096 for v in m.values()), "invalid map dimensions")
    cells = m["width"] * m["height"]
    require(cells <= 1048576, "map cell capacity")
    m["terrain"] = read_bytes(r)
    require(len(m["terrain"]) == cells and all(v <= 15 for v in m["terrain"]), "invalid terrain")
    visual = None
    if r.boolean():
        require(cells <= 16384 and m["tileWidth"] == m["tileHeight"] == 20, "invalid visual dimensions")
        visual = dict(catalogVersion=r.number("i"))
        require(visual["catalogVersion"] == 1, "unknown terrain catalog")
        for key in ("groundTileIds", "decorationTileIds"):
            count = r.count(16384, 2)
            require(count == cells, "invalid visual array count")
            visual[key] = [r.number("H") for _ in range(count)]
            require(all(v <= 234 for v in visual[key]), "unknown terrain tile")
    m["visual"] = visual
    return m


def write_map(w, m):
    for key in ("width", "height", "tileWidth", "tileHeight"): w.number("i", m[key])
    write_bytes(w, m["terrain"])
    visual = m["visual"]
    w.boolean(visual is not None)
    if visual is not None:
        w.number("i", visual["catalogVersion"])
        for key in ("groundTileIds", "decorationTileIds"):
            w.number("i", len(visual[key]))
            for tile in visual[key]: w.number("H", tile)


def map_content_key(map_definition):
    """Lowercase SHA-256 of the validated canonical MapInit body, without framing."""
    w = Writer(); write_map(w, map_definition)
    r = Reader(w.data); read_map(r)
    require(r.remaining == 0 and len(w.data) <= MAX_FRAME - 9, "invalid map body")
    return hashlib.sha256(w.data).hexdigest()


def check_key(key):
    require(type(key) is str and re.fullmatch(r"[0-9a-f]{64}", key), "invalid map content key")


def read_status(r):
    status = dict(phase=r.enum(PHASES))
    for key in ("winnerTeamIds", "defeatedTeamIds"):
        values = [r.number("i") for _ in range(r.count(64, 4))]
        require(all(0 <= v <= 63 for v in values) and len(set(values)) == len(values), "invalid result teams")
        status[key] = values
    wins, defeats = status["winnerTeamIds"], status["defeatedTeamIds"]
    require(not set(wins).intersection(defeats), "overlapping result teams")
    require((not wins and not defeats) if status["phase"] == "RUNNING" else bool(wins or defeats), "invalid phase results")
    return status


def write_status(w, status):
    w.enum(PHASES, status["phase"])
    for key in ("winnerTeamIds", "defeatedTeamIds"):
        w.number("i", len(status[key]))
        for team in status[key]: w.number("i", team)


def read_field(r, fmt):
    if fmt == "text": return r.text(256, True)
    if fmt == "bool": return r.boolean()
    if fmt == "readiness": return r.enum(READINESS)
    return r.number(fmt)


def write_field(w, fmt, value):
    if fmt == "text": w.text(value, 256, True)
    elif fmt == "bool": w.boolean(value)
    elif fmt == "readiness": w.enum(READINESS, value)
    else: w.number(fmt, value)


def validate_unit(u):
    require(int(u["handle"]) > 0, "invalid unit handle")
    if u["teamId"] is not None: require(-2 <= u["teamId"] <= 63, "invalid unit team")
    for key in ("x", "y", "health"):
        if u[key] is not None: require(u[key] >= 0, "negative unit field")
    if u["maxHealth"] is not None: require(u["maxHealth"] > 0, "invalid maximum health")
    if u["buildProgress"] is not None: require(0 <= u["buildProgress"] <= 1, "invalid build progress")
    if u["upgradeLevel"] is not None: require(1 <= u["upgradeLevel"] <= 3, "invalid upgrade level")


def read_unit(r):
    u = dict(handle=r.long())
    for key, fmt in UNIT_FIELDS: u[key] = read_field(r, fmt)
    validate_unit(u)
    return u


def write_unit(w, u):
    w.long(u["handle"])
    for key, fmt in UNIT_FIELDS: write_field(w, fmt, u[key])


def read_patch(r, fields, mask_format):
    p = dict(handle=r.long())
    mask = r.number(mask_format)
    require(0 < mask < 1 << len(fields), "invalid patch mask")
    for bit, (key, fmt) in enumerate(fields):
        p[key] = read_field(r, fmt) if mask & (1 << bit) else None
    require(int(p["handle"]) > 0, "invalid patch handle")
    if fields is UNIT_FIELDS:
        validate_unit(p)
    else:
        for key in ("queuedCount", "stockpileCount"):
            if p[key] is not None: require(-1 <= p[key] <= 65536, "invalid action count")
        if p["progress"] is not None:
            require(p["progress"] == -1 or 0 <= p["progress"] <= 1, "invalid action progress")
    return p


def write_patch(w, p, fields, mask_format):
    w.long(p["handle"])
    mask = sum(1 << bit for bit, (key, _) in enumerate(fields) if p.get(key) is not None)
    w.number(mask_format, mask)
    for bit, (key, fmt) in enumerate(fields):
        if mask & (1 << bit): write_field(w, fmt, p[key])


def world_identity(v):
    require(int(v["sequence"]) > 0 and v["tick"] >= 0 and 0 <= v["teamId"] <= 63, "invalid world identity")


def read_baseline(r):
    v = dict(sequence=r.long(), tick=r.number("i"), teamId=r.number("i"), credits=r.number("d"))
    v["fog"] = read_bytes(r, minimum=1)
    require(all(x <= 10 for x in v["fog"]), "invalid fog")
    v["units"] = [read_unit(r) for _ in range(r.count(65536, 47))]
    require(len({u["handle"] for u in v["units"]}) == len(v["units"]), "duplicate unit handle")
    v["matchStatus"] = read_status(r)
    require(v["matchStatus"]["phase"] != "FINISHED" or not v["units"], "terminal baseline has live units")
    world_identity(v)
    return v


def write_baseline(w, v):
    w.long(v["sequence"]); w.number("i", v["tick"]); w.number("i", v["teamId"]); w.number("d", v["credits"])
    write_bytes(w, v["fog"])
    w.number("i", len(v["units"]))
    for u in v["units"]: write_unit(w, u)
    write_status(w, v["matchStatus"])


def read_fog(r):
    kind = r.number("B")
    if kind == 0:
        values = read_bytes(r, minimum=1)
        require(all(v <= 10 for v in values), "invalid fog")
        return dict(kind="REPLACE", values=values)
    require(kind == 1, "unknown fog encoding")
    count = r.count(1048576, 9)
    require(count > 0, "empty fog runs")
    patches, end = [], 0
    for _ in range(count):
        start, values = r.number("i"), read_bytes(r, minimum=1)
        require(start >= end and start + len(values) <= 1048576, "invalid fog range")
        require(all(v <= 10 for v in values), "invalid fog")
        end = start + len(values)
        patches.append(dict(start=start, values=values))
    return dict(kind="RUNS", patches=patches)


def write_fog(w, fog):
    require(fog["kind"] in ("REPLACE", "RUNS"), "unknown fog encoding")
    if fog["kind"] == "REPLACE":
        w.number("B", 0); write_bytes(w, fog["values"])
    else:
        w.number("B", 1); w.number("i", len(fog["patches"]))
        for patch in fog["patches"]:
            w.number("i", patch["start"]); write_bytes(w, patch["values"])


def read_delta(r):
    d = dict(baseSequence=r.long(), sequence=r.long(), tick=r.number("i"), teamId=r.number("i"))
    flags = r.number("B")
    require(flags & ~7 == 0, "unknown world flags")
    d["credits"] = r.number("d") if flags & 1 else None
    d["fog"] = read_fog(r) if flags & 2 else None
    d["enteredUnits"] = [read_unit(r) for _ in range(r.count(65536, 47))]
    d["unitPatches"] = [read_patch(r, UNIT_FIELDS, "H") for _ in range(r.count(65536, 11))]
    d["removedHandles"] = [r.long() for _ in range(r.count(65536, 8))]
    d["matchStatus"] = read_status(r) if flags & 4 else None
    world_identity(d)
    require(0 < int(d["baseSequence"]) < int(d["sequence"]), "invalid delta base")
    handles = [u["handle"] for u in d["enteredUnits"] + d["unitPatches"]] + d["removedHandles"]
    require(all(int(h) > 0 for h in handles) and len(set(handles)) == len(handles), "conflicting delta handles")
    require(d["matchStatus"] is None or d["matchStatus"]["phase"] != "FINISHED" or
            not d["enteredUnits"] and not d["unitPatches"], "terminal delta has live updates")
    return d


def write_delta(w, d):
    w.long(d["baseSequence"]); w.long(d["sequence"]); w.number("i", d["tick"]); w.number("i", d["teamId"])
    flags = (1 if d.get("credits") is not None else 0) | (2 if d.get("fog") is not None else 0) | (4 if d.get("matchStatus") is not None else 0)
    w.number("B", flags)
    if flags & 1: w.number("d", d["credits"])
    if flags & 2: write_fog(w, d["fog"])
    for key in ("enteredUnits", "unitPatches", "removedHandles"):
        w.number("i", len(d[key]))
        for value in d[key]:
            if key == "enteredUnits": write_unit(w, value)
            elif key == "unitPatches": write_patch(w, value, UNIT_FIELDS, "H")
            else: w.long(value)
    if flags & 4: write_status(w, d["matchStatus"])


def read_panel_delta(r):
    p = {key: r.long() for key in ("basePanelSequence", "querySequence", "panelSequence", "viewSequence")}
    p["teamId"], p["unitHandle"] = r.number("i"), r.long()
    p["updates"] = [read_patch(r, ACTION_FIELDS, "B") for _ in range(r.count(128, 10))]
    require(0 < int(p["basePanelSequence"]) < int(p["panelSequence"]) and int(p["querySequence"]) > 0 and
            int(p["viewSequence"]) > 0 and 0 <= p["teamId"] <= 63 and int(p["unitHandle"]) >= 0, "invalid panel delta identity")
    require(int(p["unitHandle"]) != 0 or not p["updates"], "cleared panel has updates")
    require(len({p["handle"] for p in p["updates"]}) == len(p["updates"]), "duplicate action patch")
    return p


def write_panel_delta(w, p):
    for key in ("basePanelSequence", "querySequence", "panelSequence", "viewSequence"): w.long(p[key])
    w.number("i", p["teamId"]); w.long(p["unitHandle"]); w.number("i", len(p["updates"]))
    for patch in p["updates"]: write_patch(w, patch, ACTION_FIELDS, "B")


def read_target(r):
    tag = r.number("B")
    if tag == 0: return dict(kind="NONE")
    if tag == 1: return dict(kind="POINT", x=r.number("f"), y=r.number("f"))
    require(tag == 2, "unknown target tag")
    handle = r.long(); require(int(handle) > 0, "invalid target handle")
    return dict(kind="UNIT", handle=handle)


def write_target(w, target):
    tag = ("NONE", "POINT", "UNIT").index(target["kind"])
    w.number("B", tag)
    if tag == 1: w.number("f", target["x"]); w.number("f", target["y"])
    if tag == 2: w.long(target["handle"])


def decode_frame(direction, data):
    require(direction in TYPES, "unknown direction")
    r = Reader(data)
    length = r.number("i")
    require(9 <= length <= MAX_FRAME and len(data) == length + 4, "invalid frame length")
    require(r.number("i") == MAGIC and r.number("i") == VERSION, "invalid magic or wire version")
    code = r.number("B")
    require(code in TYPES[direction], "unknown message or direction")
    require(code not in (106, 107) or length <= MAX_PANEL, "oversized action panel")
    fixed = {2: (9,), 4: (33,), 5: (51, 59), 6: (10,), 7: (42,), 101: (17,), 108: (41,), 109: (41,)}
    require(code not in fixed or length in fixed[code], "invalid fixed frame length")
    name = TYPES[direction][code]
    if name == "Hello":
        m = dict(playerName=r.text(128, True), joinToken=r.text(512))
    elif name == "KeepAlive": m = {}
    elif name == "Order":
        m = dict(sequence=r.long(), viewSequence=r.long(), kind=r.enum(ORDERS, 1))
        count = r.count(256, 8); require(count >= 1, "empty order selection")
        m["unitHandles"] = [r.long() for _ in range(count)]
        m["target"] = read_target(r)
        require(all(int(h) > 0 for h in m["unitHandles"]) and len(set(m["unitHandles"])) == count, "invalid selection")
        expected = {"MOVE": "POINT", "ATTACK_MOVE": "POINT", "STOP": "NONE", "ATTACK_UNIT": "UNIT"}
        require(m["target"]["kind"] == expected[m["kind"]], "wrong order target kind")
    elif name == "ActionPanelRequest":
        m = dict(querySequence=r.long(), viewSequence=r.long(), unitHandle=r.long())
        require(int(m["querySequence"]) > 0 and int(m["unitHandle"]) >= 0, "invalid action query")
    elif name == "ActionIntent":
        m = {key: r.long() for key in ("sequence", "viewSequence", "panelSequence", "unitHandle", "actionHandle")}
        m["target"] = read_target(r)
        flags = r.number("B"); require(flags & ~3 == 0, "unknown action flags")
        m["queued"], m["cancel"] = bool(flags & 1), bool(flags & 2)
        require(all(int(m[k]) > 0 for k in ("panelSequence", "unitHandle", "actionHandle")), "invalid action identity")
        require(not m["cancel"] or m["target"]["kind"] == "NONE" and not m["queued"], "invalid cancellation")
    elif name == "ResyncRequest":
        m = dict(channels=r.number("B")); require(1 <= m["channels"] <= 3, "invalid resync channels")
    elif name == "Welcome":
        m = dict(teamId=r.number("i"), ticksPerSecond=r.number("i"))
        require(0 <= m["teamId"] <= 63 and 1 <= m["ticksPerSecond"] <= 1000, "invalid welcome")
    elif name == "MapInit": m = dict(map=read_map(r))
    elif name in ("MapCached", "MapOffer"): m = dict(contentKey=r.take(32).hex())
    elif name == "MapReady": m = dict(contentKey=r.take(32).hex(), haveMap=r.boolean())
    elif name == "Baseline": m = dict(baseline=read_baseline(r))
    elif name == "Delta": m = dict(delta=read_delta(r))
    elif name == "Rejected": m = dict(reason=r.text(1024, True))
    elif name == "ActionPanelBaseline": m = dict(panel=read_panel(r))
    elif name == "ActionPanelDelta": m = dict(delta=read_panel_delta(r))
    if name in ("Order", "ActionIntent", "ActionPanelRequest"):
        require(int(m["viewSequence"]) > 0 and ("sequence" not in m or int(m["sequence"]) > 0), "nonpositive sequence")
    require(r.remaining == 0, "trailing bytes")
    return name, m


def encode_frame(direction, name, m):
    require(direction in TYPES, "unknown direction")
    codes = {v: k for k, v in TYPES[direction].items()}
    require(name in codes, "unknown message or direction")
    w = Writer(); w.number("i", MAGIC); w.number("i", VERSION); w.number("B", codes[name])
    if name == "Hello":
        w.text(m["playerName"], 128, True); w.text(m["joinToken"], 512)
    elif name == "Order":
        w.long(m["sequence"]); w.long(m["viewSequence"]); w.enum(ORDERS, m["kind"], 1)
        w.number("i", len(m["unitHandles"]))
        for handle in m["unitHandles"]: w.long(handle)
        write_target(w, m["target"])
    elif name == "ActionPanelRequest":
        for key in ("querySequence", "viewSequence", "unitHandle"): w.long(m[key])
    elif name == "ActionIntent":
        for key in ("sequence", "viewSequence", "panelSequence", "unitHandle", "actionHandle"): w.long(m[key])
        write_target(w, m["target"])
        require(type(m["queued"]) is bool and type(m["cancel"]) is bool, "boolean required")
        w.number("B", int(m["queued"]) | int(m["cancel"]) << 1)
    elif name == "ResyncRequest": w.number("B", m["channels"])
    elif name == "Welcome": w.number("i", m["teamId"]); w.number("i", m["ticksPerSecond"])
    elif name == "MapInit": write_map(w, m["map"])
    elif name in ("MapCached", "MapOffer", "MapReady"):
        check_key(m["contentKey"]); w.put(bytes.fromhex(m["contentKey"]))
        if name == "MapReady": w.boolean(m["haveMap"])
    elif name == "Baseline": write_baseline(w, m["baseline"])
    elif name == "Delta": write_delta(w, m["delta"])
    elif name == "Rejected": w.text(m["reason"], 1024, True)
    elif name == "ActionPanelBaseline": write_panel(w, m["panel"])
    elif name == "ActionPanelDelta": write_panel_delta(w, m["delta"])
    packet = struct.pack(">i", len(w.data)) + w.data
    decode_frame(direction, packet)
    return bytes(packet)


def verify_vectors(path):
    path = Path(path)
    require(0 < path.stat().st_size <= 16 * MAX_FRAME, "vector file exceeds limit")
    doc = json.loads(path.read_text(encoding="utf-8"))
    require(doc["protocolVersion"] == VERSION and doc["vectorCount"] == len(doc["vectors"]), "invalid vector metadata")
    accepted = rejected = 0
    covered = set()
    for v in doc["vectors"]:
        packet = bytes.fromhex(v["hex"])
        require(len(packet) == v["frameBytes"] and struct.unpack(">i", packet[:4])[0] == v["declaredPayloadBytes"], "vector length metadata differs")
        if v["accepted"]:
            name, fields = decode_frame(v["direction"], packet)
            require(name == v["message"] and fields == v["expected"], v["id"] + ": expected fields differ")
            require(encode_frame(v["direction"], name, fields) == packet, v["id"] + ": decoded re-encode differs")
            require(encode_frame(v["direction"], name, v["expected"]) == packet, v["id"] + ": expected re-encode differs")
            accepted += 1; covered.add((v["direction"], name))
        else:
            try: decode_frame(v["direction"], packet)
            except (ValueError, UnicodeError, struct.error, OverflowError): pass
            else: raise ValueError(v["id"] + ": negative frame accepted")
            rejected += 1
    require(covered == {(d, name) for d, types in TYPES.items() for name in types.values()}, "missing message coverage")
    for example in doc["mapContentKeyExamples"]:
        require(map_content_key(example["map"]) == example["sha256"], "map content key differs")
    print(f"OK: {accepted} accepted vectors, {rejected} rejected vectors; all 16 messages, byte re-encoding and map keys match")


if __name__ == "__main__":
    require(len(sys.argv) == 2, "usage: python3 wire_v5.py vectors.json")
    verify_vectors(sys.argv[1])
