#!/usr/bin/env python3
"""Generate the "original layout" scenario JSON from the 1984 game's map tables.

Usage: python3 -I tools/make_scenario.py <out.json>

Needs reference/extract/block4.bin (see reference/README.md). Only the map's structure
(which rooms connect to which, on which deck, through which ducts) is taken from the
original; names, crew and stats are our own.
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
mem = bytearray(0x10000)
block = (ROOT / "reference/extract/block4.bin").read_bytes()
mem[0x6000:0x6000 + len(block)] = block

ROOM_EXITS = 0x71F3   # 5 bytes per room: walking connections (padded with the room itself)
DUCT_EXITS = 0x72A2   # 5 bytes per room: duct connections N, E, S, W, AI-only
# TODO if the original's North/East/South/West duct labels come back (see EventText.option), write
#  each duct link's direction (its slot in DUCT_EXITS) into the scenario instead of discarding it.
ROOM_DECK = 0x74D1    # 1 byte per room: 0 upper, 1 middle, 2 lower, 3 shuttle
ITEM_ROOMS = 0xA1BE   # 1 byte per item: starting room (22 entries; the last two are the caught cat)
SHUTTLE = 0x22

# Original room index -> (id, name). Our own names for the same rooms.
ROOMS = [
    ("airlock-1", "Airlock 1"), ("airlock-2", "Airlock 2"), ("armoury", "Armoury"),
    ("cargo-pod-1", "Cargo Pod 1"), ("cargo-pod-2", "Cargo Pod 2"), ("cargo-pod-3", "Cargo Pod 3"),
    ("command-centre", "Command Centre"), ("computer-room", "Computer Room"),
    ("corridor-1", "Corridor 1"), ("corridor-2", "Corridor 2"), ("corridor-3", "Corridor 3"),
    ("corridor-4", "Corridor 4"), ("corridor-5", "Corridor 5"), ("corridor-6", "Corridor 6"),
    ("corridor-7", "Corridor 7"), ("cryo-vault", "Cryo Vault"), ("engineering", "Engineering"),
    ("engine-1", "Engine Room 1"), ("engine-2", "Engine Room 2"), ("engine-3", "Engine Room 3"),
    ("engineering-stores", "Engineering Stores"), ("infirmary", "Infirmary"),
    ("infirmary-stores", "Infirmary Stores"), ("laboratory", "Laboratory"),
    ("lab-stores", "Laboratory Stores"), ("life-support", "Life Support"),
    ("living-quarters", "Living Quarters"), ("mess", "Mess"), ("recreation", "Recreation Area"),
    ("stores-1", "Stores 1"), ("stores-2", "Stores 2"), ("stores-3", "Stores 3"),
    ("shuttle-bay", "Shuttle Bay"), ("shuttle-stores", "Shuttle Stores"), ("shuttle", "Shuttle"),
]
DECKS = ["upper", "middle", "lower", "shuttle"]
SHIP_DECKS = {"upper", "middle", "lower"}

# Crew, in the original's record order (docs/mechanics.md §4), each standing in for one of the
# original's seven. Health is "hits until down": the original's starting health minus one, since
# its crew collapsed below 2. Speed penalties are its per-character delays (ticks) converted to
# seconds (÷ 13.5). Start rooms follow the original's long game.
# Grille removal time is the original's per-character value (ticks ÷ 13.5, docs/mechanics.md §2).
# Cat-catching difficulty is the original's per-character value (docs/mechanics.md §2).
CREW = [
    # name,       original, start room,      health, speed penalty s, base morale, grille removal s, catch
    ("Martin",    "Dallas",  "command-centre", 5, 0.5, 4, 7.4, 14),
    ("Broussard", "Kane",    "command-centre", 4, 0.4, 4, 8.9, 14),
    ("Roby",      "Ripley",  "command-centre", 3, 0.0, 3, 13.3, 13),
    ("Hill",      "Ash",     "mess",           4, 0.5, 4, 7.4, 13),
    ("Melkonis",  "Lambert", "mess",           5, 0.2, 4, 13.3, 13),
    ("Hunter",    "Parker",  "mess",           5, 0.7, 4, 5.9, 15),
    ("Faust",     "Brett",   "airlock-1",      4, 0.4, 3, 5.9, 14),
]

# Hidden roles: anyone can be the android or the creature's host (docs/brainstorm.md §6), rather
# than the original's fixed lists.
ROLES = {"android": {"pick": "any"}, "host": {"pick": "any"}}

# Every grille starts in place except this room's (docs/mechanics.md §3).
OPEN_GRILLES = {"corridor-6"}

# What's special about each room (docs/mechanics.md §3, §7, §10): the original's room menus and
# damage effects.
FEATURES = {
    "command-centre": ["destructControls", "keySystem"],
    "computer-room": ["keySystem"],
    "life-support": ["keySystem"],
    "shuttle-bay": ["keySystem", "intruderEntry"],
    "corridor-6": ["airlockControls"],
    "airlock-1": ["airlock"],
    "airlock-2": ["airlock"],
    "cryo-vault": ["hypersleep"],
    "shuttle": ["launch"],
    "engine-1": ["flammable"],
    "engine-2": ["flammable"],
    "engine-3": ["flammable"],
}

# Item types, in the original's item-table order (docs/mechanics.md §7, reference/notes/10_data_map.md §4):
# id, name, how many, damage to the creature, morale bonus while in use, then extra fields: charges,
# special effects, detecting and catching.
ITEM_TYPES = [
    ("prod",         "Electric Prod",    3, 1, 1, {}),
    ("incinerator",  "Incinerator",      3, 1, 2, {}),
    ("tracker",      "Tracker",          2, 1, 0, {"charges": 1, "breaksWhenSpent": True, "detects": True}),
    ("extinguisher", "Fire Extinguisher", 4, 0, 1, {"charges": 3, "scares": True, "fightsFire": True}),
    ("harpoon",      "Harpoon Gun",      1, 5, 2, {"charges": 1, "roomDamagePercent": 75, "effect": "killRoom"}),
    ("laser",        "Laser Pistol",     3, 2, 2, {"charges": 8, "roomDamagePercent": 30}),
    ("net",          "Net",              1, 0, 0, {"charges": 1, "breaksWhenSpent": True, "effect": "freeze", "catchBonus": 4}),
    ("cat-box",      "Cat Box",          1, 0, 0, {"catchBonus": 0}),
    ("spanner",      "Spanner",          2, 1, 1, {}),
]

# The original's creature: fixed timers (80 ticks to move, 50 through a grille, 60 to attack),
# dies at 15 damage, starts in a duct.
CREATURE = {
    "id": "creature", "name": "Creature", "kind": "creature", "room": "corridor-4-duct",
    "brain": "wumpus", "health": 15,
    "actionSeconds": {"move": 5.9, "attack": 4.4, "prowl": 5.9, "removeGrille": 3.7},
    "grilleMoveSeconds": 3.7,
}

# The cat: moves every 255 ticks (18.9 s), never into ducts. The original started him in a random
# room 0-15; ours starts in the Recreation Area.
CAT = {
    "id": "cat", "name": "Spot", "kind": "animal", "room": "recreation", "brain": "cat", "health": 1,
    "actionSeconds": {"move": 18.9},
}


def exits(table, room):
    raw = mem[table + room * 5:table + room * 5 + 5]
    out = []
    for target in raw:
        if target != room and target <= SHUTTLE and target not in out:
            out.append(target)
    return out


def deck(room):
    return DECKS[mem[ROOM_DECK + room]]


def duct_id(room):
    return ROOMS[room][0] + "-duct"


def links_from(directed, kind_of):
    """Turns directed edges into links: two-way where both directions exist, else one-way."""
    links, done = [], set()
    for a, b in directed:
        if (a, b) in done:
            continue
        both = (b, a) in directed
        done.add((a, b))
        if both:
            done.add((b, a))
        link = {"from": a, "to": b}
        kind = kind_of(a, b)
        if kind != "door":
            link["kind"] = kind
        if not both:
            link["oneWay"] = True
        links.append(link)
    return links


def main(out_path):
    room_count = len(ROOMS)
    rooms = [{"id": rid, "name": name, "deck": deck(i), **({"grilleRemoved": True} if rid in OPEN_GRILLES else {}),
              **({"features": FEATURES[rid]} if rid in FEATURES else {})}
             for i, (rid, name) in enumerate(ROOMS)]
    rooms += [{"id": duct_id(i), "name": ROOMS[i][1] + " duct", "deck": deck(i), "kind": "duct"}
              for i in range(room_count - 1)]  # the shuttle has no duct

    walk = [(ROOMS[a][0], ROOMS[b][0]) for a in range(room_count) for b in exits(ROOM_EXITS, a)]
    decks = {ROOMS[i][0]: deck(i) for i in range(room_count)}

    def walk_kind(a, b):
        cross = decks[a] != decks[b] and {decks[a], decks[b]} <= SHIP_DECKS
        return "ladder" if cross else "door"

    links = links_from(walk, walk_kind)
    links += [{"from": ROOMS[i][0], "to": duct_id(i), "kind": "grille"} for i in range(room_count - 1)]
    # All five duct slots, including the AI-only one: in our model anyone may use any duct link.
    ducts = [(duct_id(a), duct_id(b)) for a in range(room_count - 1)
             for b in exits(DUCT_EXITS, a) if b != SHUTTLE]
    links += links_from(ducts, lambda a, b: "duct")

    actors = [{"id": name.lower(), "name": name, "kind": "crew", "room": room, "health": health,
               **({"speedPenaltySeconds": speed} if speed else {}), "morale": morale,
               "actionSeconds": {"removeGrille": grille}, "catchDifficulty": catch}
              for name, _original, room, health, speed, morale, grille, catch in CREW]
    actors.append(CREATURE)
    actors.append(CAT)

    item_types = [{"id": tid, "name": name, "damage": damage, "morale": morale, **extra}
                  for tid, name, _count, damage, morale, extra in ITEM_TYPES]
    items, index = [], 0
    for tid, _name, count, _damage, _morale, _extra in ITEM_TYPES:
        for n in range(1, count + 1):
            items.append({"id": f"{tid}-{n}", "type": tid, "room": ROOMS[mem[ITEM_ROOMS + index]][0]})
            index += 1

    scenario = {"id": "original", "name": "Original layout", "rooms": rooms, "links": links, "actors": actors,
                "roles": ROLES, "itemTypes": item_types, "items": items}
    Path(out_path).parent.mkdir(parents=True, exist_ok=True)
    Path(out_path).write_text(json.dumps(scenario, indent=2) + "\n")

    kinds = {}
    for link in links:
        kinds[link.get("kind", "door")] = kinds.get(link.get("kind", "door"), 0) + 1
    one_way = [f"{l['from']} -> {l['to']}" for l in links if l.get("oneWay")]
    print(f"{len(rooms)} locations, {len(links)} links {kinds}, one-way: {one_way}")


if __name__ == "__main__":
    main(sys.argv[1])
