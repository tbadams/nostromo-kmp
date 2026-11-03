# Alien AI, timers, self-destruct, end conditions & scoring (0x9300–0xAF7C, block5)

All addresses are hex. "tick" means one pass of the main loop L_8E81. Probabilities assume the RNG returns a uniform 0..15 (it doesn't really, see the RNG section).

## Character record layout (0x737E + 8*i; i=0 Alien, 1..7 crew)
| off | meaning (evidence) |
|---|---|
| +0 | action countdown timer. L_8C85 decrements it each tick; when it reaches 0 it dispatches via 8454[+3] |
| +1 | room (bit6 = in duct, &3F = room#). FF = removed from ship (hypersleep, blown out the airlock, hatched host) |
| +2 | destination room (set by L_8F6D random exit) |
| +3 | state/action: 0 idle, 1 move (8CC2), 2 toggle duct (8D05), 3 remove grille (8D36), 4 attack (9290), 5 alien attacks (9958), 6 hypersleep (9780), 7 android attacks (9F35) |
| +4 | health/morale level 0..7 (labels at 759E: Dead..Confident). Crew score = 2*this. Alien: **+4 (0x7382) = damage taken** |
| +5 | base courage (0..7F). Alien: +5 = index of the body it is carrying (?) |
| +6 | effective courage = +5 + Σ(companions' +5 − 2) + 1 bonus for tracker/cat (L_8D5B/8DBB, 8E2A) |
| +7 | 0 = alive, 1 = dead (body not yet found), 2 = body found / dead at end, FF = gone (hypersleep or removed) |

Other globals: 83A7 = android index (9 = none), 83A5 = pointer to the android record, 83A8 = android hostile flag, 83AA = Jones' room (FF = captured), 7A15 = event flags (bit0 destruct armed, bit1/2 Airlock#1/#2 blown, bit3/4/5 Engine#1/2/3 on fire, bit6 Narcissus gone?(?)), 7536+2*room = room damage as two ASCII digits, 757C+room = grille state (FF in place, 0 removed), 83D2+item = item charges.

## RNG (L_8335 / L_8340)
- `L_8335`: `HL=(7A21)+1; (7A21)=HL; A=(HL) AND 0Fh`. It is not arithmetic. It walks a pointer through memory and returns the low nibble of whatever byte it finds (ROM and RAM contents), so the values are 0..15 and not uniform.
- `L_8340` reseeds the pointer from FRAMES (5C78). It also computes `H AND 1Fh` but throws the result away (bug). Every main loop iteration calls 8335, calls it again if the result was 0, and reseeds if it was 0 twice (8EBB..8EC5).
- `L_8F9B` puts a nibble into 5 buckets: 0-2→0, 3-5→1, 6-8→2, 9-11→3, 12-15→4 (weights 3/3/3/3/4 out of 16). It is used to pick one of a room's 5 connections (L_8F6D, tables 71F3 rooms and 72A2 ducts).

## Alien AI: L_909A (runs every tick, acts only when the alien timer 737E = 0)
The alien does **not hunt**. It walks at random and attacks whoever it bumps into.
1. It lists the crew whose room byte equals the alien's, including the duct bit, so crew in the same duct segment count. If anyone is there: timer = 60 (3Ch), state 5 = attack.
2. Otherwise, if the grille of the current room is still in place (757C[room&3F]=FF): 3/16 chance (bucket 0) to set state 3 "remove grille" with timer 50. This plays the grille sound (83A9=1, L_AF50) and sets 757C[room]=0 (L_8D36).
3. Otherwise (grille removed or not chosen): 6/16 chance (bucket <2) to toggle the duct bit, i.e. enter or leave the ducting (state 2, timer 50).
4. Otherwise it moves to a random connection of the current room (L_8F6D), with state 1 and timer 80 (50h). If the chosen connection is its own room (table padding), the alien stays and **damages the room by +5%** (call 9526 A=1). Damage only applies in a room, not in a duct.
- Moving (L_8CC2): anyone who enters a room that is not a duct sets 83A3, which plays the **door sound** (L_9D7F). Anyone moving into Airlock#1/#2 while it is blown (7A15 bit1/bit2) is redirected to Corridor#6 (0Dh).
- Speed: about one action per 50–80 ticks. When netted, the timer is set to 255 (FF) (92FA).
- Start position: a random **duct** chosen from A18E (rooms 3,4,5,9,10,11,13,14,18,19,20,20,22,24,25,30) OR 40h (A5CC).

### Alien attack: state 5, L_9958
- Victim choice among the crew in the room: if 1, that one; if 2, rnd&1; if 3, bucket(rnd&7), which gives 3/8, 3/8, 2/8.
- The victim's +4 drops by 1. At ≥4: message "The ALIEN is attacking X". At 2..3: "X is being wounded". Below 2: the victim is dead (+7=1, +0=0), their carried items drop in the room, and "X has collapsed" appears if X is the selected character (99E8).
- If the chosen victim is already dead and the alien is not carrying a body (+5=0): 4/16 chance it **takes the body**. The corpse's +1 becomes FF and alien +5 becomes the victim index (9A23) (?). Otherwise it tries the next listed victim. If there is none, it moves away (state 1, timer 40).

### Alien hit by crew: state 4, L_9290 → L_92D0
The attacker needs +4≥2 and +6≥2. The effect depends on the item in hand (7A10, item values A0+char):
| item idx | item | effect |
|---|---|---|
| 0-2, 3-5, 18-19 | Electric Prod, Incinerator, Spanner | alien damage +1; unlimited uses |
| 6-7 | Tracker | "Tracker is broken" (item destroyed), damage +1 |
| 8-11 | Fire Extinguisher | uses a charge (3 each) and causes **no damage**, but still triggers the retreat roll |
| 12 | Harpoon gun (1 charge) | **kills everyone in the room** (including the attacker), destroys every item carried by them or lying in the room, room damage +75%, alien damage +5 |
| 13-15 | Laser Pistol (8 charges each) | room damage +30%, alien damage +2 |
| 16 | Net | alien frozen for 255 ticks, net used up, "nets the ALIEN" |
| 17, 20, 21 | Cat box, cat in net, cat in box | no attack |
- **The alien dies when damage (7382) reaches 15** (83D1=0Fh). For +1 hits the check is `==15`, for +2/+5 it is `>=15`, and death jumps to L_AC5D.
- **Retreat (L_9468):** score S = Σ over attackers in the room with +4>2 and state 4 of ([+6>3] + damage/4). Note `7382>>2` is added once per attacker. Roll r=rnd 0..15; the alien flees if r≠15 and r<S, so P = min(S,15)/16. On a flee the alien jumps at once to a random adjacent room (not the same one), its timer becomes 0, and each attacker's +5 courage goes up by 1 (cap 8). There is a bug at 94AC: the "+1 if laser" bonus can never fire (it tests ==6 AND ==7).

## Hatching, android, Jones
- **Long game (L_A524)**: crew from A1E4. The host is chosen from A19E: Dallas 5/16, Kane 6/16, Lambert 5/16. Host +1 and +7 become FF (removed, not listed as dead). Brett's room (73B7) is set to the host's old room (?). The queued message is "Alien has hatched from <host>". The android is chosen from A1AE: Dallas, Kane, Ash or Parker, 4/16 each, re-rolled if it is the host.
- **Short game (L_AEC2)**: crew from A224. Only Ripley, Lambert and Parker take part, all in CommdCentr; the others get +1=FF and +7=FF. There is no android (83A7=9) and no hatch message.
- Both games (L_A58C): items from A1BE (see table below), every grille in place except Corridor#6 (757C+0Dh=0), Jones in a random room 0..15, flags cleared, charges from A1D4 (extinguishers 3, harpoon 1, lasers 8), room damage "00", oxygen "7500".
- **Android activation (L_A130)**: when alien damage ≥6, the android exists and is alive, 83A8 is set to FF.
  - L_9F7A: every 80 ticks the android checks its room. If a living crew member is there, it shows "<android> is attacking <victim>", state 7, timer 40. Otherwise it wanders (timer 60).
  - In state 7 (9F35) the victim's +4 drops by 1, and the victim dies below 2.
  - Crew fight back with the same weapon table (L_9FED): prod/spanner/laser −1 android health, harpoon kills the whole room, net freezes it for 255 ticks. Below 2 health the android collapses (+7=2, 83A8=0).
- **Jones (L_8FFB)**: every 255 ticks (83AB) he moves to a random connected room (not ducts). If the alien is in his new room he moves again straight away. Any living crew member in the same room produces "X Sees Jones the Cat".
  - Holding Cat-in-Net or Cat-in-Box (items 20/21) while something alive is in an adjacent room produces "Jones is looking uneasy" (9903/993C), so the cat works as a second detector.

## Tracker and sound clue system (intro text at AA07, demonstrated in L_A8EA)
- Door sound (9D7F, flag 83A3): someone enters a room that is not a duct. This is any character, not only the alien.
- Grille sound (AF50, flag 83A9): a grille is removed (alien state 3, or crew).
- Positive tracker reading (L_97F7/L_981D): for each Tracker (items 6, 7) or cat container (20, 21) held in hand, it scans the holder's 5 connected rooms (the duct graph if the holder is in a duct). It ignores the holder's own room. If any living record (the **alien or crew**) or Jones is in an adjacent room, the slot 839C/839D/839E is set to FF (positive). Otherwise the slot holds the holder's room.
  - If the selected character holds a positive tracker, the ROM BEEPER sounds every 8 ticks (994A).
  - A non-positive slot gives +1 effective courage to crew in that room (8E2A).

## Room damage, fire, explosion (L_9526 / L_952A)
- Damage is stored as two ASCII digits per room. Each step adds 5% (the second digit toggles 0/5).
- When the tens digit reaches **3 (30%)**:
  - CommdCentr: "Partial loss of control systems"
  - Computer: "Computer Malfunction detected"
  - Life Suppt: "Environmental irregularities"
  - ShuttleBay: "Narcissus status RED"
  - Engine #1-3: **fire starts**, 7A15 bit3/4/5 set, "WARNING:Fire in X"
- At **70%**: "Structural Damage in X". At **100%** in any room: L_95AD flashes and goes to the "Nostromo has exploded" ending.
- Fire (L_9881): every 100 ticks (95D2) each burning engine room takes +5%. "Fight Fire" (L_9692, extinguisher in hand, uses 1 charge) increments 95CF+engine. The fire goes out ("Fire extinguished in X") when that count ≥ C, where C = 1 (<60%), 2 (60–79%), 3 (≥80%). The counts are never reset (?) (bug: a later fire would be put out at once). Fire only happens in the engine rooms; it does not spread.
- Damage sources: alien idling (+5), laser shot (+30), harpoon (+75), fire (+5 per 100 ticks).

## Airlocks (from Corridor#6 menu, 7D59)
- BlowLock#n (L_96F5) sets the blown bit and kills or removes everything in the airlock (+1=+7=FF, so they are not counted as dead). Items there are destroyed. Message "WARNING:<lock> DEPRESSURISED".
- If the alien was in that airlock: it dies (L_AC5D) if its damage is ≥3. Otherwise it dies with 14/16 probability. In the remaining 2/16 it **survives and reappears in ShuttleBay** with "INTRUDER ENTERING ShuttleBay".
- SealLock#n (L_96BC) clears the bit and shows "PRESSURE RESTORED".

## Life support "TOOH: 7500" (L_9A6B, shown by 9AC2 when destruct is not armed)
- A 4-digit counter (95E6..95E9) starts at 7500. It loses 1 unit every 2*N ticks, where N = number of records with +7≠0 (dead, removed or in hypersleep).
- With N=0 the reload is 0, which wraps to 256 ticks. So consumption is very slow while nobody is missing, and fewer living crew means slower consumption.
  - Long game, start (host removed, N=1): 2 ticks/unit, about 15,000 ticks in total.
  - Short game (N=4): 8 ticks/unit, about 60,000 ticks.
- At 0500: "Oxygen status CRITICAL". Below 0000 (borrow into 95E5): L_ACF5 "Life Support Systems exhausted". All crew are marked dead.

## Self-destruct (CommdCentr menu, 7D33)
- "Scuttle Nostromo" (9636): "DESTRUCT SEQUENCE INITIATED". The clock is set to 5:00, 7A15 bit0 is toggled, and the menu changes to "Override Destruct".
- "Override Destruct" (9653): "DESTRUCT SEQUENCE ABORTED" and the clock goes back to 5:00. There is no limit on how many times you can re-arm it.
- Countdown (L_97BE): the border flashes and one "second" passes every 20 ticks (95D3). That is 300 s = **6000 ticks**. Going below 0:00 triggers the explosion ending.
- While armed, the HUD shows "TIME: m:ss" instead of the oxygen counter.

## Narcissus launch (L_9D14, menu in Narcissus room 22h unless 7A15 bit6)
1. **Every living crew record (+7=0), android included, must be in the Narcissus.** Otherwise the game shows "LAUNCH COUNTERMANDED". The alien's location is **not** checked.
2. Jones must be aboard. Either he is loose in room 22h (83AA=22h), or he is captured (83AA=FF) and Cat-in-Net/Cat-in-Box (7508/7509) is in room 22h or carried. If he is loose elsewhere, or the container is somewhere else, the game shows "BioUnit Jones not present" plus "LAUNCH COUNTERMANDED".
3. If both checks pass, there are 75 flashes with the door sound, then the ending L_AC7F.

## End conditions & Competence Rating (accumulator 9E37, cap 100, printed by L_ADA4)
Rating parts:
- CRW = L_AE6B: Σ 2*(+4) over living (+7=0), non-android crew. Maximum 7×2×7.
- DAM = L_AE9A: +1 for each of the 34 rooms whose damage is <20%.
- TIM = L_AEB2: 2 × the thousands digit of the oxygen counter (0..14).

| trigger | text | rating |
|---|---|---|
| Alien damage ≥15, or alien blown out of an airlock (L_AC5D) | "The Alien has been killed" + dead list + "X was the Android" | CRW+DAM+TIM |
| Narcissus launched with destruct armed (L_AC7F→ACA1) | "The Narcissus is launched / The Nostromo will explode in m minutes ss seconds" | CRW+TIM |
| Narcissus launched without destruct | "...The Alien will be unleashed upon the Earth" | 0 |
| All crew not alive, checked every tick by L_AD19 (hypersleep counts as not alive) (L_ACD2) | "Humanoid life levels minimal", then as the launch rows depending on bit0 | CRW+TIM or 0 |
| Oxygen exhausted (L_ACF5) | "Life Support Systems exhausted", all crew dead | TIM if destruct armed, otherwise 0 |
| Any room at 100% damage, or destruct clock expires (L_ADEC) | "Nostromo has exploded" + "The Alien has been killed" | TIM only (all crew are marked dead) |

After the ending: "PRESS ANY KEY" (AE2F), then a restart at 8E69.

### block5 F566..F618 symbol table
These look like leftover assembler symbols in the form `01, name (last char |80h), addr16, link16`: KUSER A764, KUP A7E6, KDOWN A7F0, KFIRE A7FA, KQUIT A804, SCAN1 A810, CRWRAT AA1B, DAMRAT AA4A, ENDEXP A82A, TIMRAT AA62, ALDED A8E3, ENDEND A830, CRWDED A8F2.
- They come from an earlier build that was offset by −0x450: CRWRAT→AE6B, DAMRAT→AE9A, TIMRAT→AEB2, ALDED→AD33 "Alien killed", CRWDED→AD42 dead list. ENDEXP and ENDEND land near AC7A/AC80 (end flow) (?).
- So the scoring formula is crew + damage + time ratings, as described above. There is no separate formula in block5.
- The rest of block5 is graphics: EA60, EBDA, ED54 and EECE are alien portrait frames (table 9B3E, animated by 9B54); F048.. is cat frames (?); F230 is a font (used by A655).

## Initial item locations (A1BE → 74F4)
Prods: Lab Stores, Infirmary, Eng Stores. Incinerators: CommdCentr ×2, Engineering. Trackers: CommdCentr, Engineering. Extinguishers: Mess, Engine #1/#2/#3. Harpoon: ShuttleBay. Lasers ×3: Armoury. Net: Lab Stores. Cat Box: Laboratory. Spanners: Stores#3, Eng Stores.

## Other routines in range
- 9165/91F3: message queue display. L_9138(A=msg, L=subject A, H=subject B) enqueues at 83B2 and skips a duplicate of the last message. The strings are at 8464 (index list in the notes above).
- 95EB per-tick: 97BE destruct clock, 9903 tracker beep, 9A6B oxygen, 97F7 tracker/cat scan, 9881 fire, 9B26 portrait animation.
- A655 title, A745 menu, AA5A control select, A8EA intro (map key and sound demo), AF0F pause ("PRESS 1 FOR NEW GAME").
- Tick rate (?): each main loop spends at least two calls to the ~36 ms delay L_8E60 (inside 9D7F/AF50 when no sound is playing), so roughly 10 ticks/s. That makes the destruct about 10 real minutes (?).

## Related crew behaviour outside the range (for context)
- L_8E4C: finding a body makes **every** crew member lose 1 base courage, and the message "X finds Y's body" appears.
- L_8F03: at most 3 crew can be in a room, and a duct segment must be empty to enter.
- L_8F38/8F4C: a crew member with effective courage 0 **panics**. They drop everything they carry and flee to a random room.
