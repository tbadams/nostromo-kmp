# Commands, crew state machine, morale, combat (code 0x8400-0x9300 + callees)

Notation: `rec[i]` = 8-byte record at 0x737E+8*i (0 = Alien, 1..7 = Dallas, Kane, Ripley, Ash, Lambert, Parker, Brett).
"tick" = one pass of main loop L_8E81. "rand" = L_8335 = next ROM byte & 0Fh (0..15). The pointer starts at FRAMES & 1Fxxh (8340),
so it walks sequential ROM bytes. That is NOT uniform. Every probability below assumes uniform 0..15 (?).
`bucket(r)` = L_8F9B: r 0-2->0, 3-5->1, 6-8->2, 9-11->3, 12-15->4 (weights 3,3,3,3,4 /16).

## 1. Record layout (resolved)
| off | Crew meaning | Alien (rec0) meaning |
|---|---|---|
| +0 | action timer. 8C85 decrements it; on 1->0 the action fires (8CAE) | same. FF = netted (frozen ~255 ticks) |
| +1 | current room. bit6 = inside the duct of that room; FF = gone (blown out / hypersleep) | room (start: in a duct) |
| +2 | destination room (bit6 = duct) | destination |
| +3 | state (index into 0x8454) | state |
| +4 | **health**: 0 Dead, 1 Collapsed, 2-3 Wounded, >=4 OK (7E81 label logic) | **damage taken** (7382). 15 (=83D1) ends game |
| +5 | **base morale** (0..8) | index of body it carried off (9A23) (?) |
| +6 | **effective morale**, recomputed (8DBB): 0 Broken, 1 Shaken, 2 Uneasy, 3 Stable, >=4 Confident (75BC) | - |
| +7 | 0 active; 1 collapsed body, not yet found; 2 body found / Android destroyed; FF removed | - |

Initial values (A1E4, copied by A524). Columns are room, hp, base/eff morale:
Dallas CommdCentr 6 4/4; Kane CommdCentr 5 4/4; Ripley CommdCentr 4 3/3; Ash Mess 5 4/4; Lambert Mess 6 4/4;
Parker Mess 6 4/4; Brett Airlock#1 5 3/3.

Game setup (A53F-A5FF):
- **Chestburster victim** = A19E[rand], one of Dallas (5/16), Kane (6/16) or Lambert (5/16). Its +1 and +7 are set to FF (removed).
  Message 21h "Alien has hatched from <victim>".
- **Android** 83A7 = A1AE[rand] in {Dallas, Kane, Ash, Parker}, each 1/4. It is rerolled if it equals the victim. 83A5 = pointer to its record.
- Alien starts in the duct (|40h) of A18E[rand]: Cargopod1-3, Corr2/3/4/6/7, Engine2/3, EngStores (x2), InfStores, LabStores, LifeSuppt, Stores2.
- Jones (83AA) starts in room rand (0..15). Grilles 757C[] are all FF (in place) except Corridor#6 (0Dh), which starts removed. 7A15 = 0.
- Weapon charges 83D2[item] come from A1D4: extinguishers (items 8-11) have 3 each, harpoon (12) has 1 (unused), lasers (13-15) have 8 each.

Per-character speed constants (used by L_8BE6: timer = base + 83FA[char] + 83F2[hp&7]):
- 83FA speed penalty: Dallas 7, Kane 5, Ripley 0, Ash 7, Lambert 3, Parker 10, Brett 5.
- 83F2 wound penalty: hp 2 -> +48, hp 3 -> +16, hp >= 4 -> 0.
- Base delays: move 40h(64), grille entry 40h, attack 28h(40), crowd-retry 20h(32). Grille removal uses 83EA[char] (see RmveGrille).

## 2. Menu / command dispatch (877C -> 8818)
877C reads keys into 8392 (8750): 8=bit0, 5=bit1, 7=bit2 (up), 6=bit3 (down), 0=bit4 (fire). A14D.. are alternative joystick readers.
- Fire: waits for release while flashing the border, then dispatches.
- Up/down: move the cursor 8393. It skips entries >=52h (blank FF / label rows). A 2-row item (text code >=46h) moves the cursor 2 rows.
- Left/right (only when not in the main menu): jump the cursor to QUIT.

**Dispatch is two-level.** 0x8402 is a 5-entry table indexed by 7A14 (menu mode). Then 89F2 indexes a row table by the cursor row 8393:
840Ch for mode 0 and 8430h for mode 1, 18 rows each. 5 + 18 + 18 = 41 words. The 8 words at 8454 are the state table.

| 7A14 mode | 8402 handler | meaning |
|---|---|---|
| 0 main menu | 89F2 -> 840C[row] | |
| 1 character menu | 89F2 -> 8430[row] | |
| 2 / 3 room lists (Indicate Location) | 8A0A | QUIT -> main; "Other List" toggles rooms 0-16 / 17-33; a room -> show its deck (74D1) and flash it on the map (8938) |
| 4 item list (Get Item) | 8AFA | pick up the chosen item |

Main menu rows (labels from 75F5):
| row | text | handler | action |
|---|---|---|---|
| 0 | Order: | 8A78 RET | label |
| 1-7 | crew names | 895B | select char 7A09=row. Refused (back to main) if +7!=0 (dead/sleeping), or if the Android is revealed (83A8!=0) and this is the Android. Otherwise opens the char menu (7FCF) |
| 8-9 | Indicate / Location | 8996 / RET | room list mode 2 |
| 10-11 | Display / Level | RET | labels |
| 12-14 | Upper/Middle/Lower Deck | 898A | 7A0B = row-12, draw the deck map |

Character menu rows (template 75E2, filled by 7C0F/7CAB/7CDC/7E18):
| row | slot | text shown | handler | action |
|---|---|---|---|---|
| 0 | | move to: | RET | |
| 1 | 73BF | Grille (only if the room's grille is removed) | 8AE8 | state 2, dest = room ^ 40h (enter/leave duct), timer 40h+pen |
| 2-6 | 73C0-C4 | adjacent rooms (71F3; 72A2 when in a duct) | 8A36 | state 1 (move), dest = chosen room, timer 40h+pen |
| 7 | | use: | RET | |
| 8 | 73C6 | primary item (slot "A0") | 8A79 RET | display only |
| 9 | 73C7 | secondary item (slot "80") | 8A7B | swap primary/secondary. +5/+6 morale -= bonus(old primary), += bonus(new primary) |
| 10 | 73C8 | Get Item (items in room and secondary slot empty) | 8B36 -> mode 4 -> 8AFA | instant. The first item goes to the primary slot and adds morale bonus 7520[item] to +5 and +6. A second item goes to the secondary slot (no bonus) |
| 11 | 73C9 | Leave Item (if primary held) | 8B8D | drops the secondary if held, else the primary (and removes its bonus). Instant |
| 12 | | Special | RET | |
| 13 | 73CB | RmveGrille (grille in place) or ATTACK (alien here, or revealed Android here) | 8BCB | ATTACK -> 9275: state 4, timer 28h+pen. RmveGrille: state 3, timer 83EA[char]+pen. 83EA: Dallas 100, Kane 120, Ripley 180, Ash 100, Lambert 180, Parker 80, Brett 80 |
| 14 | 73CC | Get Jones (Jones in room) | 8C05 | see section 5 |
| 15-16 | 73CD/CE | room specials | 95FE | see below |
| 17 | 73CF | QUIT | 8A70 | back to main menu |

Room specials (95FE; menu set by 7CDC):
- **CommdCentr (06)**: "Scuttle Nostromo" (46h) -> 9636.
  Message "DESTRUCT SEQUENCE INITIATED", sets the countdown to 5:00 (95DC/95DE), toggles 7A15 bit0, menu becomes "Override Destruct".
  Override (9653) prints "...ABORTED" and toggles bit0. 97BE ticks the countdown down one unit every 20 ticks while bit0 is set.
  At 0 it calls 95AD (explosion).
- **Cryo Vault (0F)**: "Enter Hypersleep" -> 9681: state 6, timer FF, message "prepares for hypersleep".
- **Engine #1-3 (11-13h) when on fire**: "Fight Fire" -> 9692. Needs an extinguisher (items 8-0Bh) as primary. It uses 1 of the 3 charges
  (or shows "extinguisher is empty") and increments the fire-fight counter 95CF[engine].
- **Narcissus (22h)**: "Launch Narcissus" -> 9D14. Every active crew member must be in room 22h, otherwise "LAUNCH COUNTERMANDED".
  Jones must be in 22h, or captured with the Cat-in-Net/Box item in 22h or carried by anyone. Otherwise "BioUnit Jones not present".
- **Corridor#6 (0Dh)**: BlowLock#1/#2 -> 96F5, SealLock#1/#2 -> 96BC (section 6).

## 3. Per-character state machine (8C85 -> 8CAE -> 8EE3 gate -> 8454[+3])
Each tick, 8C85 walks the 8 records (83A4 = index). If +0 is nonzero it is decremented; on reaching 0 the action fires.
So orders are timed actions: the delay elapses first, then the effect happens.

| +3 | handler | state |
|---|---|---|
| 0 | 8A78 | idle (RET) |
| 1 | 8CC2 | **moving**. +1 = +2 (one step). Footstep sound if not in a duct. Recompute room morale (8D5B). Entering a blown airlock (room 0 with 7A15 bit1, or room 1 with bit2) puts you in Corridor#6 instead |
| 2 | 8D05 | **through grille**. Toggles the duct bit of +1. Base morale +5: entering a duct -1, leaving +1. Recompute morale |
| 3 | 8D36 | **removing grille**. 757C[room] = 0 (removed), plays sound 83A9 |
| 4 | 9290 | **attacking**. Alien here -> 92D0. Else the revealed Android here -> 9FED. Then +3 = 0 |
| 5 | 9958 | **alien attacks crew** (alien only) |
| 6 | 9780 | **hypersleep**. Message "enters hypersleep", +1 = +7 = FF, carried items left in Cryo Vault (0Fh) |
| 7 | 9F35 | **Android attacks crew** |

**Obedience gate 8EE3** runs before every crew action. It returns 0 = the action does not happen this time.
- The alien always passes. State 0 does nothing.
- **Android (83A7)**: if ordered to ATTACK (state 4), it picks a random adjacent room instead (state 1, timer 14h). It never attacks the alien.
  States 1-2 go through the capacity check. The morale check is skipped for the Android.
- **Morale 0 (Broken) -> panic 8F38**:
  - Not in a duct: drops every carried item in the current room, replaces the destination with a random adjacent room (8F6D),
    then runs the capacity check. A Broken character sent to move goes somewhere random. An ATTACK still fails at 92D0 (needs morale >= 2).
  - In a duct while moving: rips out the grille (8D36) and climbs out (8AE8).
- **States 1-2 capacity check 8F03**: counts crew records (bodies included, since they keep their room) whose +1 equals the destination.
  - Destination is a duct: if morale < 2 (Broken/Shaken) -> panic. Else the duct must be empty, otherwise the character waits:
    timer 20h+pen, retried later.
  - Destination is a room: the action waits if 3 or more crew are already there. **Rooms hold max 3, a duct holds 1.**
  - (?) A low-morale character ordered INTO a duct panics with a non-duct destination. But 8D05 ignores +2, so they still enter the duct. Looks like a bug.

## 4. Morale system
**Effective morale** (8DBB, run for everyone in the room whenever someone arrives or passes a grille):
`+6 = max(0, +5 + Σ companion terms + tracker term)`. Companions are at most the 2 other crew records with the same +1 (alien not counted).
- Living companion (+7=0): adds `companion.+5 - 2`. Company of brave people helps, company of base-morale-0/1 people hurts.
- Unfound body (+7=1): -1. **All 7 crew lose 1 base morale (8E4C, floor 0).** Message 14h "<X> finds <Y>'s body". The body's +7 becomes 2.
- Found body / destroyed Android (+7=2): -1.
- Tracker term: +1 if the character's room equals one of 839C-839E (?). 97F7 sets those bytes to a tracker/cat-carrier's room when nothing
  alive (alien or crew) or Jones is in an adjacent room, and to FF otherwise. So "all clear" from a tracker in the room gives +1 (?).

**Base morale (+5) changes:**
| event | change |
|---|---|
| pick up a primary item | +7520[item]: Prod +1, Incinerator +2, Tracker 0, Extinguisher +1, Harpoon +2, Laser +2, Net 0, Cat Box 0, Spanner +1, Cat-in-Net/Box +1 |
| drop/swap the primary | -bonus |
| enter a duct / leave a duct | -1 / +1 |
| anyone discovers a body | -1 to every crew member |
| alien driven off while attacking it | +1 to each listed attacker (cap 8) |

**Thresholds:** 0 -> panic (drop items, random moves). <2 -> won't enter or travel ducts, and won't attack. >=4 counts toward scaring the alien (section 5).

## 5. Combat
**Crew attacks the alien (92D0)**. Needs: same +1 (duct bit included), health >= 2, morale >= 2. Otherwise the order silently fails.
Primary item only (slot A0):
| weapon (items) | effect |
|---|---|
| none / Cat Box (11h) / Cat-in-Net/Box | nothing |
| **Net** (10h) | alien +0 = FF (frozen ~255 ticks). Net consumed. Message "nets the ALIEN". No damage |
| **Electric prod** (0-2), **Incinerator** (3-5), **Spanner** (12-13h) | "hits the ALIEN", damage +1, unlimited use |
| **Tracker** (6-7) | "Tracker is broken" (destroyed), damage +1 |
| **Fire extinguisher** (8-0Bh) | uses 1 of 3 charges, **no damage**, but triggers the flee roll. When empty: "extinguisher is empty" |
| **Laser pistol** (0Dh-0Fh) | uses 1 of 8 charges, damage +2, 6 units of structural damage to the room (+30%). When empty: "Laser is exhausted" |
| **Harpoon gun** (0Ch) | damage +5 and 15 units of structural damage (+75%). **Kills everyone in the room including the shooter** (+7=1, +4=0). Destroys all their items and every item lying in the room |

- **Alien death**: damage reaches 15 (83D1) -> AC5D (end-game/scoring sequence).
- **Flee roll 9468** (after any hit that is not lethal). Score = Σ over people in the room who are in state 4 with health > 2:
  (+1 if morale >= 4) + (alien damage >> 2).
  - The alien flees if rand != 15 and rand < score. It jumps to a random different adjacent room immediately.
  - Each attacker gets base morale +1.
  - Example: one Confident attacker at damage 0-3 -> 1/16. Damage 8 -> 3/16. Three Confident attackers at damage 8 -> 9/16.
  - The tracker branch at 94AC-94B8 can never fire (dead code bug).
- **Structural damage 9526/952A**: per-room 2-digit percent at 7536+2r, +5% per unit, skipped if the alien is in a duct.
  - At 30%: CommdCentr "Partial loss of control systems", Computer "Computer Malfunction", Life Suppt "Environmental irregularities",
    ShuttleBay "Narcissus status RED", Engine n -> fire (7A15 bit 3/4/5, "WARNING: Fire in").
  - At 70%: "Structural Damage in".
  - At 100%: 95AD, the ship explodes.
  - The alien also adds +5% when its random move picks its own room.
  - A burning engine adds +5% every 100 ticks until enough "Fight Fire" uses (counter >= 1/2/3 for damage <60/<80/>=80%) -> "Fire extinguished".

**Alien AI 909A** runs each tick while alien +0 == 0.
- Crew (any record with the same +1, bodies included) present -> state 5, timer 60.
- Else, grille in place: bucket 0 (3/16) -> break the grille (state 3, timer 50). Else move.
- Else, grille removed: bucket 0-1 (6/16) -> go through the grille (state 2, timer 50).
- Else move: random adjacent room (state 1, timer 80).
- Connection lists are padded with the room itself, so "stay" happens often.

**State 5, alien attacks (9958)**. Picks a victim among the up to 3 people in the room: 1 -> that one, 2 -> 1/2 each, 3 -> 3/8, 3/8, 2/8.
- Victim is a body: if alien +5 == 0 and rand < 4 (1/4), the body vanishes (+1 = FF, carried off (?)). Otherwise try the next person.
  If nobody is attackable, wander (state 1, timer 40).
- Victim alive: health -1.
  - New health >= 4: "The ALIEN is attacking X".
  - 2-3: "X is being wounded".
  - < 2: collapse. +7 = 1 (a body), items dropped in the room, "has collapsed" (shown only if X is the viewed character).
- With crew still present, it repeats every 60 ticks. Crew health is never restored (no healing code seen).

**Android (Ash or whoever 83A7 is)**:
- Reveal A130: once alien damage >= 6 and the Android is still active, 83A8 = FF.
- From then on (9F7A), whenever its timer is 0 (timer reload 80):
  - If a living crew member shares its room: "<Android> is attacking <victim>", state 7, timer 40.
  - Else it wanders (state 1, timer 60).
- State 7 (9F35): victim (first living other in the room) health -1. <2 -> collapse. 2-3 -> message "<Android> is attacking <victim>".
- It cannot be ordered after the reveal, and it shows as ATTACK target in rooms it occupies.

**Crew attacks the Android (9FED)**, same health/morale >= 2 requirement:
- Net: freezes it (+0 = FF), "nets <Android>".
- Prod / Incinerator / Spanner: health -1, "hits the Android".
- Tracker: broken, then health -1.
- Extinguisher: uses a charge, no damage.
- Laser: uses a charge, +30% room damage, health -1.
- Harpoon: kills everyone in the room (items carried are destroyed, items on the floor are kept), +75% room damage.
- At Android health < 2: +7 = 2, "has collapsed", 83A8 = 0 (threat over).

## 6. Airlocks (96F5 / 96BC)
- **BlowLock#n** (from Corridor#6): sets 7A15 bit1/bit2. Everyone in Airlock n (alien included) is gone: +1 = +7 = FF.
  Items there are destroyed. Message "WARNING: Airlock DEPRESSURISED".
- If the alien was blown out:
  - Damage >= 3 -> AC5D (end game).
  - Else rand >= 2 (14/16) -> AC5D.
  - Else (2/16) it survives: alien room = ShuttleBay (20h), "INTRUDER ENTERING ShuttleBay".
- While blown, moving into that airlock lands you in Corridor#6. SealLock clears the bit ("PRESSURE RESTORED").

## 7. Jones the cat (8FFB, 8C05)
- **Movement 8FFB**: 83AB counts down; when it hits 0 it reloads to 255 and Jones moves to a random adjacent room (71F3, never ducts).
  - If the alien is there he moves again at once.
  - If living crew are there: message "<X> Sees Jones the Cat", and Get Jones appears in the menu.
- **Get Jones 8C05**: needs Cat Box (11h) or Net (10h) as primary. E = 83E2[char]: Dallas 14, Kane 14, Ripley 13, Ash 13, Lambert 13, Parker 15, Brett 14.
  For the Net, E -= 4. Success if rand >= E.
  - Box success chance: Parker 1/16; Dallas, Kane, Brett 2/16; Ripley, Ash, Lambert 3/16. Net adds +4/16.
  - Success: the box/net becomes "Cat in Box"/"Cat in Net" held as primary, 83AA = FF, "has captured Jones".
  - Failure: 83AB = 1, so Jones bolts next tick.
- Cat carrier: if a living thing or Jones is adjacent, "Jones is looking uneasy" (9903/97F7).
- Tracker carrier (selected character only): beeps every 8th tick while something is adjacent (994A). It works as a motion detector.

## 8. Main-loop subroutines in range
- **877C**: input and menu dispatch (section 2).
- **8897 / 88C8**: map blink, called twice per loop. 8897 flashes the selected character's room every 10 ticks (8396, frames 68E5).
  88C8 flashes the Indicate-Location room every 5 ticks (8398, frames 68ED). Cosmetic.
- **8C85**: timer and state machine (section 3).
- **8FFB**: Jones movement (section 7).
- **909A**: alien AI decision (section 5).
- **91CA**: clears the message line (screen 50E0, attributes 5AE0) and resets the ticker.
- **91F3**: message ticker, called twice per loop.
  - 9138(A=msg#, L=actor, H=other) pushes onto a 3-byte queue at 83B2, skipping a repeat of the last message. 9165 prints it.
    Placeholders: FE = actor name, FD = other name, FC = room name.
  - The highlight bar sweeps across, then holds 50 calls (2 if more are queued).
- Related, outside the range: 9A6B oxygen counter ("TOOH: 7500").
  It decrements once every 2×(number of inactive records) ticks. At 0500: "Oxygen status CRITICAL" (?).

## 9. Message index -> source (9138 callers)
0 sees Jones 9078 · 1 ALIEN attacking 99C1 · 2 captured Jones 8C81 · 3 nets ALIEN 9308 · 4 hits ALIEN 9315 · 5 tracker broken 933F/A072 ·
6 extinguisher empty 935C/96AB/A08F · 7 laser exhausted 93EE/A107 · 8 structural 958E · 9 fire 9584 · 0Ah wounded 99C8 ·
0Bh prepares hypersleep 968E · 0Ch enters hypersleep 9785 · 0Dh/0Eh destruct init/abort 9638/9655 · 0Fh fire out 98F1 ·
10h depressurised 9755 · 11h pressure restored 96F1 · 12h intruder 9777 · 13h Jones uneasy 9946 · 14h finds body 8E08 ·
15h collapsed 9A1D/A05D · 16h-19h 30% damage 9550-9568 · 1Ah oxygen 9AAE · 1Bh/1Ch Jones absent / countermanded 9D50/9D55 ·
1Dh/1Eh Android attacking 9F76/9FDF · 1Fh nets Android A021 · 20h hits Android A02E · 21h hatched A57A.
