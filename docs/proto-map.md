# Proto: a generated visual map

Not scheduled. The screen's center today is a text list of rooms by deck (`ShipMap` in `shared/.../game/GameScreen.kt`). This is a sketch of a real Canvas map, kept for later.

## Goal

- Draw the ship as boxes and lines rather than a list.
- **No coordinates in scenario JSON.** Hand-placing every room is too fiddly.
- **Nothing about our rooms hard-coded.** It must work for any scenario, using only what the scenario already has: rooms, links (with their kinds), and decks.

## Approach: automatic layout

A pure function from `Scenario` to positions, run once at load. It's deterministic: the same scenario always gives the same picture. It lives in `:shared`, at roughly 100–150 lines.

1. **Decks become bands.** Each deck gets its own horizontal strip. Rooms with no deck go in a strip of their own.
2. **Rooms snap to a grid within their deck.**
   - Start from the room with the most links and walk outwards breadth-first.
   - Each neighbor goes in the nearest free grid cell next to the room it was reached from.
   - On a map shaped like a ship's corridors, this gives tidy boxes joined by short straight lines.
3. **Ducts sit under their room.**
   - Draw each duct segment as a small marker below or beside its room, not as a full room on the map.
   - Draw duct links as dashed lines.
   - That keeps the room map readable and still shows the shortcuts.
4. **Links between decks** (ladders, cross-deck ducts) are drawn as lines between the bands.

The look is a schematic, not a ship plan, which suits the terminal style (green outlines, faded fill; see `ui/Colors.kt`).

## Limits

- **Crossed lines.** A densely cross-linked map can get crossing lines or awkward long links. The corridor shape of the original map should be fine.
- **No real-world layout.** The left/right of the screen won't match left/right on the ship.

## Optional escape hatch

If a particular map ever needs tidying, the scenario could take an *optional* position for a room. That would override only that room and leave the rest automatic. You'd never have to fill them in.

## Open questions

- Click a room to move there? That ties into click-to-move queues (build step 6; brainstorm §2).
- How should visibility show on the map? Hidden ducts and unseen occupants already have rules in the text map. Rooms nobody can see could be drawn dimmed.
- Should the map be testable as layout data (positions only), with drawing kept separate?
