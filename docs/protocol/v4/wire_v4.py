#!/usr/bin/env python3
"""RWXX v4 frame codec and offline vector check. Python 3, standard library only.

No sockets, TLS, authentication, game engine or rendering. Public entry points:
decode_frame(direction, bytes) -> (message_name, fields)
encode_frame(direction, message_name, fields) -> bytes
i64 fields are decimal strings in the returned JSON-compatible dictionaries.
"""
import json
import math
from pathlib import Path
import re
import struct
import sys

MAGIC, VERSION, MAX_FRAME, MAX_PANEL = 0x52575841, 4, 4194304, 65536
CLIENT, SERVER = "client-to-server", "server-to-client"
TYPES = {
    CLIENT: {1: "Hello", 2: "Intent", 3: "KeepAlive", 4: "ActionPanelRequest", 5: "ActionIntent"},
    SERVER: {101: "Welcome", 102: "View", 103: "Rejected", 104: "ActionPanel"},
}
INTENTS = ("MOVE", "ATTACK_MOVE", "STOP", "ATTACK_UNIT", "BUILD", "PRODUCE")
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


def read_view(r):
    v = dict(sequence=r.long(), tick=r.number("i"), teamId=r.number("i"), credits=r.number("d"))
    for key in ("mapWidth", "mapHeight", "tileWidth", "tileHeight"):
        v[key] = r.number("i")
        require(1 <= v[key] <= 4096, "invalid map dimension")
    cells = v["mapWidth"] * v["mapHeight"]
    require(cells <= 1048576, "too many cells")
    for key, maximum in (("fog", 10), ("terrain", 15)):
        require(r.number("i") == cells, "wrong map array count")
        v[key] = list(r.take(cells))
        require(all(x <= maximum for x in v[key]), "invalid map cell")
    units = []
    for _ in range(r.count(65536, 47)):
        u = dict(handle=r.long(), typeName=r.text(256, True), teamId=r.number("i"))
        for key in ("x", "y", "height", "heading", "health", "maxHealth", "buildProgress"):
            u[key] = r.number("f")
        u["controllable"], u["upgradeLevel"] = r.boolean(), r.number("B")
        require(int(u["handle"]) > 0 and -2 <= u["teamId"] <= 63, "invalid unit identity")
        require(0 <= u["x"] < v["mapWidth"] * v["tileWidth"] and
                0 <= u["y"] < v["mapHeight"] * v["tileHeight"], "unit outside map")
        require(u["health"] >= 0 and u["maxHealth"] > 0 and 0 <= u["buildProgress"] <= 1, "invalid unit health/progress")
        require(1 <= u["upgradeLevel"] <= 3, "invalid upgrade level")
        units.append(u)
    v["units"] = units
    require(len({u["handle"] for u in units}) == len(units), "duplicate unit handle")
    status = dict(phase=r.enum(PHASES))
    for key in ("winnerTeamIds", "defeatedTeamIds"):
        values = [r.number("i") for _ in range(r.count(64, 4))]
        require(all(0 <= x <= 63 for x in values) and len(set(values)) == len(values), "invalid result teams")
        status[key] = values
    winners, defeated = status["winnerTeamIds"], status["defeatedTeamIds"]
    require(not set(winners).intersection(defeated), "overlapping match results")
    if status["phase"] == "RUNNING":
        require(not winners and not defeated, "running view has results")
    else:
        require((winners or defeated) and not units, "invalid finished view")
    v["matchStatus"] = status
    visual = None
    if r.boolean():
        require(cells <= 16384 and v["tileWidth"] == v["tileHeight"] == 20, "invalid visual dimensions")
        visual = dict(catalogVersion=r.number("i"))
        require(visual["catalogVersion"] == 1, "unknown terrain catalog")
        for key in ("groundTileIds", "decorationTileIds"):
            require(r.number("i") == cells and cells <= r.remaining // 2, "wrong visual array count")
            visual[key] = [r.number("H") for _ in range(cells)]
            require(all(x <= 234 for x in visual[key]), "unknown terrain tile")
    v["mapVisual"] = visual
    require(int(v["sequence"]) >= 0 and v["tick"] >= 0 and 0 <= v["teamId"] <= 63, "invalid view identity")
    return v


def write_view(w, v):
    w.long(v["sequence"])
    w.number("i", v["tick"]); w.number("i", v["teamId"]); w.number("d", v["credits"])
    for key in ("mapWidth", "mapHeight", "tileWidth", "tileHeight"):
        w.number("i", v[key])
    for key in ("fog", "terrain"):
        w.number("i", len(v[key])); w.put(bytes(v[key]))
    w.number("i", len(v["units"]))
    for u in v["units"]:
        w.long(u["handle"]); w.text(u["typeName"], 256, True); w.number("i", u["teamId"])
        for key in ("x", "y", "height", "heading", "health", "maxHealth", "buildProgress"):
            w.number("f", u[key])
        w.boolean(u["controllable"]); w.number("B", u["upgradeLevel"])
    status = v["matchStatus"]
    w.enum(PHASES, status["phase"])
    for key in ("winnerTeamIds", "defeatedTeamIds"):
        w.number("i", len(status[key]))
        for team in status[key]: w.number("i", team)
    visual = v["mapVisual"]
    w.boolean(visual is not None)
    if visual is not None:
        w.number("i", visual["catalogVersion"])
        for key in ("groundTileIds", "decorationTileIds"):
            w.number("i", len(visual[key]))
            for tile in visual[key]: w.number("H", tile)


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
    require(int(p["querySequence"]) >= 0 and int(p["panelSequence"]) > 0 and int(p["viewSequence"]) >= 0 and
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


def decode_frame(direction, data):
    require(direction in TYPES, "unknown direction")
    r = Reader(data)
    length = r.number("i")
    require(9 <= length <= MAX_FRAME and len(data) == length + 4, "invalid frame length")
    require(r.number("i") == MAGIC and r.number("i") == VERSION, "invalid magic or wire version")
    code = r.number("B")
    require(code in TYPES[direction], "unknown message type/direction")
    require(code != 104 or length <= MAX_PANEL, "oversized action panel")
    require(code != 4 or length == 33, "invalid action query length")
    require(code != 5 or length == 67, "invalid action intent length")
    name = TYPES[direction][code]
    if name == "KeepAlive": m = {}
    elif name == "Hello":
        m = dict(protocolVersion=r.number("i"), playerName=r.text(128, True), joinToken=r.text(512))
    elif name == "Intent":
        m = dict(sequence=r.long(), viewSequence=r.long(), kind=r.enum(INTENTS, 1))
        count = r.count(256, 8)
        require(count >= 1, "empty selection")
        m["unitHandles"] = [r.long() for _ in range(count)]
        m["x"], m["y"], m["targetHandle"], m["unitTypeName"] = r.number("f"), r.number("f"), r.long(), r.text(256)
        require(all(int(x) > 0 for x in m["unitHandles"]) and len(set(m["unitHandles"])) == count, "invalid selection")
        require((m["kind"] == "ATTACK_UNIT" and int(m["targetHandle"]) > 0) or
                (m["kind"] != "ATTACK_UNIT" and int(m["targetHandle"]) == 0), "invalid target")
        require(m["kind"] not in ("STOP", "ATTACK_UNIT", "PRODUCE") or m["x"] == m["y"] == 0, "unexpected coordinates")
        if m["kind"] in ("BUILD", "PRODUCE"):
            check_text(m["unitTypeName"], True); require(count == 1, "ambiguous producer")
        else: require(m["unitTypeName"] == "", "unexpected production type")
    elif name == "ActionPanelRequest":
        m = dict(querySequence=r.long(), viewSequence=r.long(), unitHandle=r.long())
        require(int(m["querySequence"]) >= 0 and int(m["unitHandle"]) >= 0, "invalid action query")
    elif name == "ActionIntent":
        m = {key: r.long() for key in ("sequence", "viewSequence", "panelSequence", "unitHandle", "actionHandle")}
        m["x"], m["y"], m["targetHandle"], m["queued"], m["cancel"] = r.number("f"), r.number("f"), r.long(), r.boolean(), r.boolean()
        require(all(int(m[key]) > 0 for key in ("panelSequence", "unitHandle", "actionHandle")) and int(m["targetHandle"]) >= 0, "invalid action identity")
        require(not (int(m["targetHandle"]) > 0 or m["cancel"]) or m["x"] == m["y"] == 0, "unexpected action coordinates")
        require(not m["cancel"] or int(m["targetHandle"]) == 0 and not m["queued"], "invalid cancellation")
    elif name == "Welcome":
        m = dict(protocolVersion=r.number("i"), teamId=r.number("i"))
        require(m["protocolVersion"] == 4 and 0 <= m["teamId"] <= 63, "invalid welcome")
    elif name == "Rejected": m = dict(reason=r.text(1024, True))
    elif name == "View": m = dict(snapshot=read_view(r))
    elif name == "ActionPanel": m = dict(panel=read_panel(r))
    if name in ("Intent", "ActionIntent", "ActionPanelRequest"):
        require(int(m["viewSequence"]) >= 0 and ("sequence" not in m or int(m["sequence"]) >= 0), "negative sequence")
    require(r.remaining == 0, "trailing frame data")
    return name, m


def encode_frame(direction, name, m):
    require(direction in TYPES, "unknown direction")
    codes = {v: k for k, v in TYPES[direction].items()}
    require(name in codes, "unknown message/direction")
    w = Writer(); w.number("i", MAGIC); w.number("i", VERSION); w.number("B", codes[name])
    if name == "Hello":
        w.number("i", m["protocolVersion"]); w.text(m["playerName"], 128, True); w.text(m["joinToken"], 512)
    elif name == "Intent":
        w.long(m["sequence"]); w.long(m["viewSequence"]); w.enum(INTENTS, m["kind"], 1)
        w.number("i", len(m["unitHandles"]))
        for handle in m["unitHandles"]: w.long(handle)
        w.number("f", m["x"]); w.number("f", m["y"]); w.long(m["targetHandle"]); w.text(m["unitTypeName"], 256)
    elif name == "ActionPanelRequest":
        for key in ("querySequence", "viewSequence", "unitHandle"): w.long(m[key])
    elif name == "ActionIntent":
        for key in ("sequence", "viewSequence", "panelSequence", "unitHandle", "actionHandle"): w.long(m[key])
        w.number("f", m["x"]); w.number("f", m["y"]); w.long(m["targetHandle"]); w.boolean(m["queued"]); w.boolean(m["cancel"])
    elif name == "Welcome":
        w.number("i", m["protocolVersion"]); w.number("i", m["teamId"])
    elif name == "Rejected": w.text(m["reason"], 1024, True)
    elif name == "View": write_view(w, m["snapshot"])
    elif name == "ActionPanel": write_panel(w, m["panel"])
    packet = struct.pack(">i", len(w.data)) + w.data
    decode_frame(direction, packet)  # Apply the same syntax validation before returning any bytes.
    return bytes(packet)


def verify_vectors(path):
    path = Path(path)
    require(1 <= path.stat().st_size <= MAX_FRAME, "vector file size out of bounds")
    document = json.loads(path.read_text(encoding="utf-8"))
    require(document["protocolVersion"] == 4 and document["vectorCount"] == len(document["vectors"]), "invalid vector metadata")
    accepted = rejected = 0
    for v in document["vectors"]:
        packet = bytes.fromhex(v["hex"])
        require(len(packet) == v["frameBytes"] and len(packet) - 4 == v["payloadBytes"], v["id"] + ": size mismatch")
        if v["accepted"]:
            name, fields = decode_frame(v["direction"], packet)
            require(name == v["message"] and fields == v["expected"], v["id"] + ": decoded fields differ")
            require(encode_frame(v["direction"], name, fields) == packet, v["id"] + ": re-encoded bytes differ")
            accepted += 1
        else:
            for operation in (lambda: decode_frame(v["direction"], packet),
                              lambda: encode_frame(v["direction"], v["message"], v["expected"])):
                try: operation()
                except (ValueError, UnicodeError, struct.error, OverflowError): pass
                else: raise ValueError(v["id"] + ": invalid text accepted")
            rejected += 1
        offset = 0
        for field in v["bodyAnnotation"]:
            require(field["offset"] == offset, v["id"] + ": annotation gap/overlap")
            data = bytes.fromhex(field["hex"])
            require(len(data) == field["bytes"] and packet[offset:offset + len(data)] == data, v["id"] + ": annotation differs")
            offset += len(data)
        require(offset == len(packet), v["id"] + ": annotations do not cover frame")
    print(f"OK: {accepted} accepted vectors, {rejected} rejected vectors; decode, encode and offsets match")


if __name__ == "__main__":
    require(len(sys.argv) == 2, "usage: python3 wire_v4.py vectors.json")
    verify_vectors(sys.argv[1])
