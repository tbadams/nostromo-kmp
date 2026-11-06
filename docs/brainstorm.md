# Brainstorm: changes from the original

These are ideas for improving on or departing from the 1984 game. The original's rules are in [mechanics.md](mechanics.md), and section references (§) point there.

Ideas marked **(nostromo-js)** were already started in `~/dev/nostromo-js`, the earlier JS attempt, so they show intent and in some cases existing code to reuse.

---

## 1. Event log

- Keep a **running, scrollable log** of important events on screen, like the message line at the bottom of the original screen, but with history.
  - The original queues at most 3 messages and drops repeats (`9138`). A visible log removes that limit, so we can log far more without worrying about a backlog.
- **Scope what's logged by who could see it.** **(nostromo-js)** already filters events by "observers": only events seen by a living crew member are printed, and events involving the selected character are highlighted. Its TODOs also list "scopable event log, different messages for diff scopes".
- **Debug switch** to raise log verbosity, for clarity and debugging. **(nostromo-js)** has `config.debug` in its JSON.
  - Normal: what the crew could perceive.
  - Debug: everything, including alien decisions, dice rolls with their thresholds, timers and hidden roles.
- Open questions:
  - Newest at the top or the bottom? (nostromo-js TODO: "upside down event log")
  - Should log entries be filterable or color-coded by type (combat, sound, ship status)?
  - Should log entries also go to the browser console? (nostromo-js TODO)

## 2. Interface

- **Mouse first.** Every action is a button or a click target.
- **Flatter action menu.** It should be clearer and easier to use than the original's.
  - No nested menus. "Get Item" currently opens a sub-list (mode 4, `8AFA`); instead, list every item in the room directly in the main action bar ("Get Laser Pistol", "Get Net"…), with a scrollbar if the list gets long.
  - Only show actions that are possible now. The original already does this (§11, `7CDC`).
- **Show actions in progress.** When a character starts a timed action, show it somewhere, e.g. "Ripley: moving to Corridor 3, 40%". This is also a nostromo-js TODO ("show pending action").
- **What happens to input while a character is busy?**
  - (a) **Reject it** and disable that character's action buttons. This is clearer and avoids accidental overrides.
  - (b) **Leave buttons enabled** and let a new order replace the current one. This is arguably more immersive: you can shout "get out of there!" mid-action.
  - Options in between: allow a cancel or interrupt button; or allow only certain orders, such as move or flee, to interrupt.
- **Click a room to go there.** This is good QOL, especially if it's smart enough to **queue the moves** along a path.
  - Should the path avoid known danger, or follow the shortest route regardless?
  - What happens if a queued step is blocked (full room, §2) or the character panics partway along?
  - Should ducts be included in paths automatically, or only when chosen explicitly?
- **Crew tabs.** Every character has a tab that's always visible and can be clicked to select them.
  - Tabs could also show some status at a glance: health or morale indicators, a busy or progress marker for the current action, an alert when they're under threat or in the same room as something dangerous, and graying out when dead or in hypersleep.
  - Should a tab flash when something happens to that character, to draw attention without having to read the log?
- **Keyboard shortcuts:**
  - Number keys select crew members (1 = first crew member, and so on), matching the tab order.
  - Shortcuts to cycle to the next and previous crew member.
- **Optional stats screen** showing every character's health, morale, items, current action and location.
- **Map display.** This was on the nostromo-js TODO list ("map display").

## 3. Crew autonomy

- **Crew act on their own when threatened** by the alien or the android: they attack or flee.
  - In the original, crew with no orders never act; even a Broken crew member under attack just stands there (§5).
  - The odds could depend on morale, whether they're holding a weapon, health, who else is in the room, and so on.
  - There should still be **a chance they do nothing**, so the player still needs to give orders.
- Possible shape of the roll, to tune later: P(attack) rises with morale and with holding a weapon; P(flee) rises as morale falls; P(freeze) is what's left over, and is never zero.
- Should autonomous actions be logged distinctly ("Parker panics and runs to the Mess"), so the player can tell what they ordered from what the crew did on their own?

## 4. Combat and threats

- **Friendly fire.** Allowing attacks on any crew member is potentially more immersive: you could take down a suspected android, or someone wildly panicking.
  - Should it cost morale for everyone ("X attacked Y!")? That would mirror the "body found" morale penalty (§5).
  - The harpoon already kills everyone in the room (§7), so the original already has friendly fire of a kind.
- **Acid blood.** **(nostromo-js)** already has `acidBlood`: hitting the creature damages the room, unless the weapon is marked `nobleed` (the prod). That links combat to ship damage, which section 5 wants more of.

## 5. Ship damage

The original rarely damages the ship (§3, §10). Ideas to make it come up more often:

- **More chances for fire:** more rooms that can burn, not only the engines; weapon fire, electrical faults.
- **Fire spreading** to neighboring rooms over time, if not put out.
  - Note: the original never resets its fire-fighting counter (§10). Don't copy that bug.
- **The alien damages rooms on purpose.** For example, after being driven off it attacks a room's systems to **lure crew out** into the open.
  - This also answers the problem that the original alien only wanders at random (§6, §12).
- Should damage effects change the map? A damaged room becomes impassable, a breach depressurizes a room, a door jams.

## 6. Hidden roles

- **Anyone can be the android.** The original limits it to a fixed list of 4 (`A1AE`).
- **Anyone can be the facehugger victim** and host the chestburster. The original uses only a fixed 3 (`A19E`).
- Both should probably come from scenario settings (section 7), e.g. a weighted list, "random from all", or "none".
- Open questions:
  - Should the android reveal itself on some other trigger than the alien taking 6 damage? The original's trigger is in §8.
  - Should there be tells or clues before the reveal?

## 7. Data-driven scenarios (secondary priority for the hack)

- **A flexible data format** that makes scenario settings easy to load or randomize:
  - map: rooms, connections, ducts, decks
  - crew: how many, their stats
  - starting items
  - alien difficulty features
- **(nostromo-js)** already does much of this in `nostromo.json`:
  - `defs` hold types (actors, items, rooms, doors, specials) with a `default` that each type deep-merges into (`mergeDeep` in `util.js`).
  - Doors are typed (grate = locked, ladder = floor change, vent).
  - Specials (self-destruct, hypersleep, launch, airlock, oxygen) are generic counters or triggers using **selectors**, e.g. oxygen counts actors with `breathe: true`.
  - `ai` names a behavior on each actor, e.g. `"wumpus"` for the creature.
  - The map is checked for connectivity on load.
- Possible alien difficulty features to make into settings:
  - speed
  - damage needed to kill it
  - how often it flees
  - whether it hunts, is drawn to noise, or picks off isolated crew
  - how much it sabotages rooms (section 5)
  - whether it carries off bodies
- A **randomize** option with a seed, so a scenario can be shared or replayed. This also helps debugging (section 1).

## 8. Fixes to the original's bugs

These are documented in mechanics.md §12. Don't copy them unless doing so is deliberate:
- oxygen drains fastest with exactly one crew member missing
- the fire-fighting counter never resets
- the tracker flee bonus can never trigger
- the speed penalty is taken from the selected character rather than the one acting
- the random number generator isn't uniform
