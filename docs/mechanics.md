# Alien (Argus Press / Mind Games, 1984, ZX Spectrum): game mechanics

This document describes how the 1984 Spectrum game works, reverse-engineered from the tape (`Alien.tzx`). It is meant as a design reference for a new, original game, so it records rules and numbers, not the original's assets.

- Addresses in `code` are Z80 addresses in `reference/disasm/alien.asm`.
- Detailed notes with more evidence are in `reference/notes/10_data_map.md`, `20_commands_crew.md` and `30_ai_timers_end.md`.
- **Odds.** Every chance below is out of 16, because the random number routine returns 0–15. The routine is not uniform (see section 11), so treat all odds as approximate.
- **Time.** Times are in **ticks**, one pass of the main loop. A tick is very roughly 0.1 s; this has not been measured.

---

## 1. Overview

- You command a crew of up to seven on a damaged ship. A creature is loose aboard.
- You never control anyone directly. You pick a crew member and give an order, which completes after a delay. Meanwhile everything else (the alien, the cat, fires, oxygen, other crew finishing their orders) keeps running.
- **Win:** kill the alien. Or arm self-destruct and escape on the shuttle with every survivor **and the cat**.
- **Lose:**
  - everyone dies;
  - oxygen runs out;
  - any room reaches 100% damage;
  - the destruct clock reaches zero;
  - you launch without arming self-destruct ("the creature reaches Earth"), which scores 0.
- At the end you get a **Competence Rating** out of 100 (section 10).
- The menu offers a short game and a long game:

| | Short game (`AEC2`) | Long game (`A524`) |
|---|---|---|
| Crew | 3 (Ripley, Lambert, Parker), all in the command centre | 6 (7 minus the chestburster host) |
| Chestburster | No | Yes: alien hatches from a random crew member (Dallas 5/16, Kane 6/16, Lambert 5/16) |
| Android | No | Yes: hidden traitor chosen from Dallas, Kane, Ash, Parker (re-rolled if it's the host) |

## 2. Time and action model

- Each of the 8 actors (the alien plus 7 crew) has an **action timer** (`record+0`). Each tick it counts down, and when it reaches 0 the queued action happens (`8C85` → `8CAE`, then the state table at `8454`).
- So an order is "wait, then do it", not instant. Picking up and dropping items is the exception: those are instant.
- **Action delay** (`8BE6`) = base delay + the character's speed penalty + their wound penalty:

| Base action | Ticks |
|---|---|
| Move or go through a grille | 64 |
| Attack | 40 |
| Retry because the room is full | 32 |
| Remove a grille | per character (below) |

| | Dallas | Kane | Ripley | Ash | Lambert | Parker | Brett |
|---|---|---|---|---|---|---|---|
| Speed penalty (`83FA`) | +7 | +5 | +0 | +7 | +3 | +10 | +5 |
| Grille removal time (`83EA`) | 100 | 120 | 180 | 100 | 180 | 80 | 80 |
| Cat-catching difficulty (`83E2`) | 14 | 14 | 13 | 13 | 13 | 15 | 14 |

- Wound penalty (`83F2`): health 3 → +16, health 2 → +48.
- The table gives each character a different profile. Parker is slow to move but quick with grilles; Ripley is fast but slow with grilles.

## 3. The ship

- There are **35 locations on 3 decks** plus the shuttle. The full room and duct tables are in `10_data_map.md` §3.
- **Walking network** (`71F3`): every room has up to 5 exits, and every link is two-way. Only two links join the decks:
  - Upper ↔ Middle: Living Quarters – Corridor 1
  - Middle ↔ Lower: Corridor 2 – Cargo Pod 2
- **Duct network** (`72A2`): a second map running parallel to the rooms.
  - Each room has a duct segment. You get from the room into its duct through a **grille**.
  - Duct exits are N, E, S and W. There is also a 5th link that only the AI uses.
  - The ducts cut between decks in three places, and two duct links are one-way. So the ducts give shortcuts that the walking map doesn't.
- **Grilles** (`757C`): at the start all are in place except one (Corridor 6). Removing a grille takes time, and the alien removes them too.
- **Capacity** (`8F03`): a room holds at most **3** people (dead bodies count) and a duct segment holds **1**. Anyone ordered into a full space waits and tries again.
- **Room damage** (`7536`) goes up in 5% steps:
  - **30%:** a key room fails (command centre, computer, life support, shuttle bay all send warnings), and an engine room catches **fire**.
  - **70%:** "Structural damage" warning.
  - **100%** in any room: the ship explodes and the game ends.
- **Airlocks:** from Corridor 6 you can blow either airlock or re-seal it. Blowing one removes everything inside, people included (section 7).

## 4. Characters

Each character has an 8-byte record at `737E`; record 0 is the alien.

| Byte | Crew | Alien |
|---|---|---|
| +0 | action timer (FF = frozen) | same |
| +1 | room; bit 6 = inside that room's duct; FF = gone | same |
| +2 | destination | same |
| +3 | state: 0 idle, 1 move, 2 through grille, 3 remove grille, 4 attack, 5 alien attacks, 6 hypersleep, 7 android attacks | same |
| +4 | **health**: <2 dead, 2–3 Wounded, ≥4 OK | **damage taken** (dies at 15) |
| +5 | **base morale** | which body it's carrying (?) |
| +6 | **effective morale** | – |
| +7 | 0 active, 1 unfound body, 2 found body, FF removed | – |

- Starting health is 4–6 and starting morale is 3–4 (`A1E4`).
- **Health never recovers.** There is no healing code.
- Each character has one **in-use** item and one **carried** item. Only the in-use item gives its morale bonus or works as a weapon.

## 5. Morale and obedience: the core of the game

Morale decides whether your orders are obeyed, so it is the main thing to manage.

**Effective morale** is recalculated whenever someone enters a room or passes through a grille (`8DBB`):

```
effective = base
          + Σ (base − 2) for each other living crew member in the room
          − 1 for each body in the room
          + 1 if a nearby tracker reads "all clear"
```

So company of brave crew lifts morale, and company of frightened crew drags it down.

**Base morale changes:**

| Event | Change |
|---|---|
| Put an item in use | +item bonus: incinerator, harpoon, laser +2; prod, extinguisher, spanner, caged cat +1; tracker, net, cat box 0 |
| Take an item out of use | −that bonus |
| Enter / leave a duct | −1 / +1 |
| Anyone finds a body ("X finds Y's body") | **−1 to every crew member** |
| Drive the alien off while attacking it | +1 to each attacker (max 8) |

**Labels:** 0 Broken, 1 Shaken, 2 Uneasy, 3 Stable, 4+ Confident.

**Obedience gate** (`8EE3`), checked before every crew action:
- **Morale 0 (Broken):** the character panics. They drop everything, run to a random neighbouring room and ignore the order.
- **Morale below 2:** won't attack and won't enter or travel ducts.
- **Health below 2:** can't attack.
- **Android after it's revealed:** can no longer be selected or ordered.

## 6. The alien

**It doesn't hunt.** It wanders at random and attacks whoever it runs into (`909A`). Tension comes from not knowing where it is.

**Each time its timer runs out:**
1. If anyone, bodies included, is in its room or duct segment: attack (timer 60).
2. Otherwise, if the room's grille is in place: 3/16 chance to remove it (timer 50). You hear this.
3. Otherwise, if the grille is open: 6/16 chance to go into or out of the duct (timer 50).
4. Otherwise it moves to a random exit (timer 80). The exit table is padded with the room itself, so it often stays put. When it stays in a room (not a duct), the room takes **+5% damage**.

**Other rules:**
- **Start:** inside a random duct, chosen from 16 entries (`A18E`).
- **Attacking** (`9958`):
  - It picks a victim from up to three people (3/8, 3/8, 2/8 when there are three).
  - It **never misses**: the victim loses 1 health.
  - Below 2 health the victim collapses and becomes a body, and their items drop.
  - If the chosen victim is already a body, there is a 4/16 chance the alien drags it away and it disappears.
- **Taking damage:** it dies at **15 damage**. Hits after the first can scare it off (section 7).
- **Senses:** none are modelled. It doesn't track sound or the scent of crew.

## 7. Combat

**Requirements:** the attacker must be in the same room or duct segment as the target, with health ≥2 and morale ≥2. Only the **in-use** item counts.

| Item (count) | Uses | Effect on the alien |
|---|---|---|
| Electric prod (3), incinerator (3), spanner (2) | unlimited | +1 damage |
| Tracker (2) | 1 (then it breaks) | +1 damage |
| Fire extinguisher (4) | 3 charges | no damage, but can scare it off |
| Laser pistol (3) | 8 charges | +2 damage; +30% damage to the room |
| Harpoon gun (1) | 1 | +5 damage; +75% damage to the room; **kills everyone in the room, shooter included**, and destroys all items there |
| Net (1) | 1 | **freezes the alien for 255 ticks** |
| Cat box (1) | – | no effect (needed to catch the cat) |

**Scaring it off** (`9468`), after any hit that doesn't kill it:

```
score = Σ over attackers in good health (health > 2):
          (+1 if Confident) + (alien damage ÷ 4)
```

- The alien flees with chance `score/16`, and never on a roll of 15. It jumps straight to a random neighbouring room.
- Each attacker then gains +1 base morale.
- Examples: one Confident attacker against a fresh alien: 1/16. Three Confident attackers against an alien with 8 damage: 9/16.
- **Bug in the original:** the code at `94AC` was meant to give an extra +1 for holding a Tracker. It tests "item == 6" and then "item == 7", which can never both be true, so the bonus never applies.

**Airlock kill** (`96F5`):
- If the alien is in an airlock when you blow it, it dies if it has ≥3 damage.
- Otherwise it still dies 14/16 of the time. The other 2/16 it survives and reappears in the shuttle bay ("INTRUDER ENTERING").
- **Anyone else in the airlock dies too.**

## 8. Hidden roles: chestburster and android

**Chestburster** (long game only):
- At the start, one of Dallas, Kane or Lambert is removed and the alien is "hatched from" them.
- They are removed from play but not counted as a body.

**Android** (long game only):
- One of Dallas, Kane, Ash or Parker (1/4 each, never the host) is secretly an android.
- **Before it's revealed:** it obeys orders, except that it never attacks the alien (an attack order turns into a random move).
- **Reveal:** once the alien has taken **6 damage** (`A130`), so the betrayal comes just as you start winning.
- **After the reveal** (`9F7A`):
  - It attacks a living crew member in its room every 80 ticks (−1 health per hit) and otherwise wanders.
  - Crew can fight it with the same weapons. Most do −1 health, the net freezes it, and the harpoon kills the whole room. It is destroyed below 2 health.

## 9. Clues and information

**Sounds**, heard wherever you are:
- **Door:** anyone, crew or alien, enters a room (not a duct).
- **Grille:** a grille is removed.
- **Tracker beep:** the selected crew member is holding a tracker and something is in an adjacent room.

**Tracker** (`97F7`):
- It scans the holder's 5 neighbouring rooms (duct neighbours if the holder is in a duct), but not the holder's own room.
- It detects **anything alive**, including crew and the cat. So it is a motion detector, not an alien detector.
- An "all clear" reading gives +1 morale to crew in the holder's room.

**Jones the cat:**
- **Detector:** a captured cat in a box or net works as a second detector ("Jones is looking uneasy").
- **Movement** (`8FFB`): every 255 ticks he moves to a random neighbouring room. He never uses ducts, and he moves again straight away if he lands with the alien.
- **Catching him** (`8C05`): needs the box or the net in use. You succeed if a roll of 0–15 is at least the character's difficulty (table in section 2).
  - With the box: 1–3 chances in 16.
  - The net lowers the difficulty by 4.
  - If you fail, he bolts at once.
- **Escape:** he must be aboard the shuttle when you launch (section 10).

**Indicate Location / deck maps:** you can view any deck and see where a chosen crew member or room is. This is your situational overview.

## 10. Timers, endings and scoring

**Oxygen** (`9A6B`):
- The counter starts at 7500 and drops one unit every **2 × N ticks**, where N is the number of crew who are dead, removed or in hypersleep.
- When N = 0 the interval wraps round to 256 ticks.
- So consumption is slowest while nobody is missing, fastest with exactly one missing, and slows again as more are lost. The long game starts with N = 1 because of the host, giving about 15,000 ticks of oxygen. This is probably a design accident, but it's what the code does.
- At 0500: "Oxygen CRITICAL". At 0: the game is lost.

**Fire:**
- An engine room catches fire when it reaches 30% damage. A burning room takes +5% every 100 ticks.
- To put it out, use "Fight Fire" with an extinguisher in use: 1 use if the room is under 60% damage, 2 if under 80%, 3 otherwise.
- Fire never spreads to other rooms.
- **Bug:** the count of fire-fighting attempts is never reset, so a later fire in the same room may go out at once.

**Self-destruct:**
- "Scuttle Nostromo" in the command centre starts a **5:00** clock. Each "second" takes 20 ticks, so 6000 ticks in all.
- "Override" stops it and resets it to 5:00, and you can re-arm it any number of times.
- If it reaches zero, the ship explodes.

**Launching the shuttle** (`9D14`):
- **Every living crew member** (including an unrevealed android) must be aboard, and Jones must be aboard, either loose or caged.
- The alien's location is not checked.
- If either condition fails: "LAUNCH COUNTERMANDED".

**Hypersleep** (Cryo Vault): the crew member leaves play safely, and their items stay in the vault. This counts as "not alive" for the endings.

**Endings and Competence Rating** (capped at 100):
- **CRW** = 2 × health, summed over living non-android crew.
- **DAM** = 1 point for each room under 20% damage.
- **TIM** = 2 × the thousands digit of the oxygen counter.

| Ending | Rating |
|---|---|
| Alien killed (in combat or blown out an airlock) | CRW + DAM + TIM |
| Shuttle launched with destruct armed | CRW + TIM |
| Shuttle launched without destruct | 0 |
| Every crew member dead, removed or in hypersleep | CRW + TIM if destruct is armed, otherwise 0 |
| Oxygen exhausted | TIM if destruct is armed, otherwise 0 |
| Ship explodes (100% damage or the destruct clock) | TIM only |

The labels CRWRAT, DAMRAT and TIMRAT in block 5 are leftover assembler symbols from an earlier build, and they confirm these three parts.

## 11. Implementation notes

- **Random numbers** (`8335`): there is no arithmetic generator. A pointer walks through memory (ROM and RAM) and returns the low 4 bits of each byte. It is seeded from the frame counter (`8340`).
  - The main loop draws numbers every tick, so the result depends on timing.
  - Choosing one of 5 exits weights the slots 3/3/3/3/4 (`8F9B`).
- **Main loop** (`8E81`), in order: input and menu → door sound → android → android reveal check → alien AI → message ticker → Jones → crew state machine → map blinking → grille sound → per-tick systems (`95EB`: destruct clock, tracker, oxygen, fire, portrait) → end-game check → pause key.
- **Menus** are rebuilt for each room (`7CDC`). Special options (Scuttle/Override, Blow/Seal airlocks, Hypersleep, Fight Fire, Launch, Attack, Get Jones) appear only where they apply.

## 12. Design notes for an original game

These mechanics are worth keeping, with the setting, names and look replaced by your own:

1. **Orders as delayed actions plus a world that keeps running.** This is where the tension comes from. Keep it, and consider showing the order queue.
2. **Morale-gated obedience with group effects.** Who you pair up matters more than what they carry. The "a body costs everyone morale" rule ties the story to the systems well.
3. **Uncertain information.** Sound cues and a detector that can't tell crew from the enemy are cheap to build and very effective.
4. **Hidden roles that trigger when you start winning.** The "traitor revealed at 6 damage" timing is the cleverest rule in the game.
5. **Trade-offs between weapons:** a strong weapon that wrecks the ship or kills everyone in the room, a non-lethal freeze, and a weak weapon that can make it flee.
6. **Escape with conditions:** everyone aboard, plus a fragile, hard-to-catch extra (the cat) that's required.

Things to change or fix:
- An enemy that only wanders randomly makes the endgame grindy. Give it some light pursuit, attraction to noise, or a preference for isolated crew.
- Fix the oxygen rate (it drains fastest with one missing), the fire counter that never resets, and the dead tracker bonus.
- Use a proper random number generator.
- Consider limited healing, or a medbay that trades time for health.
