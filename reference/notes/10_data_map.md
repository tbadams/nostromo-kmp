# 10 — Data map: character records, ship map, items, menus, RNG

Scope: code 0x7A23–0x8400 plus the tables it reads; record fields cross-checked against every `(IX+n)` use in `reference/disasm/alien.asm`.
Dump scripts: `tools/dump_tables.py` (`text` = 10-char text table, `hex ADDR LEN`), `tools/dump_map.py` (room graph, ducts, map coords).
Run them with `python3 -I`. Uncertain inferences are marked (?).

## 1. RNG — L_8335 / L_8340

- `L_8340` (seed): `HL = (5C78h)` (ROM FRAMES counter), stored to the pointer at `7A21h`.
- `L_8335` (next): `HL=(7A21h)+1; (7A21h)=HL; A=(HL) AND 0Fh`. So the "random" value is the low nibble of successive bytes of memory (ROM/RAM), from 0 to 15.
- Main loop (8EBB–8EC5): calls 8335. If the result is 0 it calls it again, and if that is also 0 it reseeds from FRAMES.
- `L_8F9B` buckets r=0..15 into 0..4: 0–2→0, 3–5→1, 6–8→2, 9–11→3, 12–15→4. It is used to pick one of 5 exits (so exit 4 is favoured: 4/16 vs 3/16).
- No `LD A,R` anywhere in the listing.

## 2. Character records — 0x737E, 8 bytes × 8 (idx 0 = Alien, 1..7 = Dallas, Kane, Ripley, Ash, Lambert, Parker, Brett)

| off | meaning | evidence |
|---|---|---|
| +0 | **action timer** (ticks until the current action resolves). 8C85 decrements it every main-loop pass; when it reaches 0, 8CAE dispatches on +3. FFh = frozen (netted alien 92FA; hypersleep prep 9685) | 8C92–8C9C, 8A7E, 8C01, 90D2, 9122 |
| +1 | **room**: bits0–5 = room#, bit6 = *inside the duct of that room*, FFh = gone from the ship | 7BF8, 8CC9, 8D1B (XOR 40h), 9737 |
| +2 | **destination room** for the pending move/duct action | 8A49, 8A6C, 8AF6, 8F97, 8CC2 |
| +3 | **state/action** → jump table 0x8454 | see below |
| +4 | crew: **health** (0 dead, 1 "Collapsed", 2–3 "Wounded", ≥4 "OK"; label via 759E at 7EA0). Alien (rec0 +4 = 7382h): **damage taken** (kill at (83D1h)=15) | 7EA0, 99B4, 9325, 93B3 |
| +5 | crew: **base morale** (clamped to 0..7Eh; +1 per successful repel, capped at 8 at 9511) | 8A93, 8B1A, 8D08, 8E14 |
| +6 | crew: **effective morale** = +5 + modifiers (8E3C). Label via 75BC: 0 Broken, 1 Shaken, 2 Uneasy, 3 Stable, ≥4 Confident. 0 → panic | 7EE0, 8EF8, 8E48 |
| +7 | **life status**: 0 active, 1 dead body (unfound), 2 body found / android destroyed, FFh removed (hypersleep, airlock, absent) | 896F, 8DF0–8E0B, 99EB, 973B, 9788 |

State table at 0x8454 (the index is +3):
0 idle (8A78 RET), 1 move→+2 (8CC2), 2 duct enter/exit (8D05), 3 remove grille (8D36), 4 resolve attack (9290), 5 alien attacks (9958), 6 enter hypersleep (9780), 7 android attacks (9F35).

Action time (`L_8BE6`): timer = base + (83FAh)[char] + (83F2h)[health&7].
- Per-char slowness 83FA (idx 0..7): `00 07 05 00 07 03 0A 05`. Dallas +7, Kane +5, Ripley +0, Ash +7, Lambert +3, Parker +10, Brett +5.
- Health penalty 83F2: health 2 → +30h, health 3 → +10h, otherwise 0.
- Base: move/duct 40h (8A36/8AE8), attack 28h (9275), "room crowded" wait 20h (8F31), remove grille (83EAh)[char] = `00 64 78 B4 64 B4 50 50` (Dallas 100, Kane 120, Ripley 180, Ash 100, Lambert 180, Parker 80, Brett 80).

Morale modifiers (L_8D5B/8DBB, run on each move):
- Each other living crewmate in the room adds (their +5 − 2).
- Being in a room listed in 839C–839E adds +1. That list is filled by 97F7 from held Trackers and caged-cat items (?).
- Finding a body (crewmate +7 = 1) triggers msg 14h "X finds Y's body", sets +7 = 2, and calls 8E4C, which gives **−1 base morale to all 7 crew**.
- Entering a duct gives −1 base morale; leaving one gives +1 (8D05).
- Holding an item adds the item's morale value (7520h) to both +5 and +6 (8B1A, 8A93).

Initial records (copied over 737E at game start):
- **Long game** (A524, from A1E4):

  | crew | room | health | base morale | effective morale |
  |---|---|---|---|---|
  | Dallas | 06 CommdCentr | 6 | 4 | 4 |
  | Kane | 06 | 5 | 4 | 4 |
  | Ripley | 06 | 4 | 3 | 3 |
  | Ash | 1B Mess | 5 | 4 | 4 |
  | Lambert | 1B | 6 | 4 | 4 |
  | Parker | 1B | 6 | 4 | 4 |
  | Brett | 00 | 5 | 3 | 3 |

  After the copy, a random crewman X is chosen from table A19E. A19E holds only Dallas, Kane and Lambert, so X is one of those three. X's room and +7 are set to FFh, and msg 21h "Alien has hatched from X" (A578) is shown. Brett's room (73B7) is then set to X's old room (?: purpose unclear).
- **Short game** (AEC2, from A224): only Ripley, Lambert and Parker are present, all in 06 (health 4/4/6, morale 3/3/4). Dallas, Kane, Ash and Brett have room FFh and +7 FFh. There is no android ((83A7h)=9).
- The bytes in the file at 737E (Dallas 0C, Kane 16, Ripley 4B?, Ash 09…) are leftovers from an earlier state and are overwritten at game start.

Common setup (A58C):
- Items are reset from A1BE.
- Grilles: all FFh except room 0D (Corridor#6), which starts **removed**.
- Jones's room (83AAh) = rand 0..15.
- The Alien starts **in the duct** of A18E[rand] = one of {03,04,05,09,0A,0B,0D,0E,12,13,14,14,16,18,19,1E}.
- Weapon charges are copied A1D4→83D2.
- Room damage 7536 is set to all "00".
- The "TOOH" counter (95E6) is set to "7500".
- Long game only: the Android (83A7h) = A1AE[rand] ∈ {Dallas, Kane, Ash, Parker}, never X.

## 3. Rooms (text idx = room#, names at 6E3D + 10·n) and decks (74D1)

Deck 0 = Upper, 1 = Middle, 2 = Lower, 3 = Narcissus (7A23: deck 3 draws the 7800 picture; 7A0B = 4 is the alien close-up screen).

### 3a. Walking connections — 0x71F3, 5 bytes/room (bytes equal to the room itself = unused)

Used by L_7C0F (menu exits), by the AI's random move via 8F6D (`r → 8F9B → byte index`), and by Jones (900C). All links are symmetric.

| # | Room | Deck | Exits |
|---|---|---|---|
| 00 | Airlock #1 | U | Corridor#6 |
| 01 | Airlock #2 | U | Corridor#6 |
| 02 | Armoury | M | Corridor#3 |
| 03 | Cargopod#1 | L | Cargopod#2 |
| 04 | Cargopod#2 | L | ShuttleBay, Cargopod#1, Corridor#7, Cargopod#3, **Corridor#2 (M)** |
| 05 | Cargopod#3 | L | Cargopod#2 |
| 06 | CommdCentr | M | Corridor#1 |
| 07 | Computer | U | Corridor#6, Stores #2 |
| 08 | Corridor#1 | M | CommdCentr, Infirmary, Corridor#2, Laboratory, **Livng Qtrs (U)** |
| 09 | Corridor#2 | M | Corridor#1, **Cargopod#2 (L)**, Corridor#3, Cryo Vault, Corridor#5 |
| 0A | Corridor#3 | M | Corridor#2, Inf Stores, Armoury, Engine #1, Corridor#4 |
| 0B | Corridor#4 | M | Corridor#3, Corridor#5, Engine #2 |
| 0C | Corridor#5 | M | Corridor#2, Lab Stores, Stores #1, Engine #3, Corridor#4 |
| 0D | Corridor#6 | U | Mess, Airlock #1, Computer, Stores #3, Airlock #2 |
| 0E | Corridor#7 | L | Cargopod#2, Life Suppt |
| 0F | Cryo Vault | M | Corridor#2 |
| 10 | Engineerng | L | ShuttleBay, Eng Stores |
| 11 | Engine #1 | M | Corridor#3 |
| 12 | Engine #2 | M | Corridor#4 |
| 13 | Engine #3 | M | Corridor#5 |
| 14 | Eng Stores | L | Engineerng |
| 15 | Infirmary | M | Corridor#1 |
| 16 | Inf Stores | M | Corridor#3 |
| 17 | Laboratory | M | Corridor#1 |
| 18 | Lab Stores | M | Corridor#5 |
| 19 | Life Suppt | L | Corridor#7 |
| 1A | Livng Qtrs | U | RecrtnArea, **Corridor#1 (M)**, Mess |
| 1B | Mess | U | Livng Qtrs, RecrtnArea, Corridor#6 |
| 1C | RecrtnArea | U | Livng Qtrs, Mess |
| 1D | Stores #1 | M | Corridor#5 |
| 1E | Stores #2 | U | Computer |
| 1F | Stores #3 | U | Corridor#6 |
| 20 | ShuttleBay | L | Shttlstore, Narcissus, Cargopod#2, Engineerng |
| 21 | Shttlstore | L | ShuttleBay |
| 22 | Narcissus | N | ShuttleBay |

The decks are joined only by two walking links (ladders/hatches (?)): Upper↔Middle through Livng Qtrs–Corridor#1, and Middle↔Lower through Corridor#2–Cargopod#2.

### 3b. Duct network — 0x72A2, 5 bytes/room: N, E, S, W, extra (value = self → no exit)

- The player menu (828D) uses only the first 4 bytes, shown as North/East/South/West (text 39h–3Ch).
- AI random moves (8F6D with bit 6 set) use all 5, so the extra byte is an AI-only link.
- Moving inside the duct keeps bit 6 set (8A4D: `72A2[room*5 + dir] OR 40h`).

| Room | N | E | S | W | extra |
|---|---|---|---|---|---|
| Airlock #1 | – | Stores #2 | Mess | Livng Qtrs | – |
| Airlock #2 | Mess | Cargopod#1 (L!) | – | RecrtnArea | Mess |
| Armoury | – | Corridor#4 | Corridor#4 | Inf Stores | Inf Stores |
| Cargopod#1 | Shttlstore | Corridor#7 | Corridor#7 | – | – |
| Cargopod#2 | – | Corridor#7 | Corridor#7 | Corridor#7 | – |
| Cargopod#3 | – | Corridor#7 | Eng Stores | Eng Stores | – |
| CommdCentr | – | Corridor#1 | Corridor#1 | – | – |
| Computer | – | Engine #2 (M) | Engine #2 | Corridor#6 | Corridor#6 |
| Corridor#1 | Infirmary | – | Laboratory | CommdCentr | – |
| Corridor#2 | Corridor#3 | Corridor#3 | Corridor#5 | Corridor#5 | – |
| Corridor#3 | Inf Stores | – | Corridor#2 | Infirmary | – |
| Corridor#4 | Armoury | Engine #2 | Stores #1 | Cryo Vault | Armoury |
| Corridor#5 | Corridor#2 | Corridor#2 | Lab Stores | Laboratory | Laboratory |
| Corridor#6 | Stores #2 | Computer | Stores #3 | Mess | – |
| Corridor#7 | Cargopod#1 | Life Suppt | Cargopod#3 | Cargopod#2 | – |
| Cryo Vault | – | Corridor#4 | Corridor#4 | – | – |
| Engineerng | – | – | Eng Stores | ShuttleBay | ShuttleBay |
| Engine #1 | – | Engine #2 | Engine #2 | – | – |
| Engine #2 | Engine #1 | Computer (U) | Engine #3 | Corridor#4 | Life Suppt (L) |
| Engine #3 | – | Engine #2 | Engine #2 | – | – |
| Eng Stores | Engineerng | Cargopod#3 | Cargopod#3 | – | – |
| Infirmary | – | Corridor#3 | Corridor#1 | Corridor#1 | – |
| Inf Stores | Armoury | Corridor#3 | Corridor#3 | – | – |
| Laboratory | Corridor#1 | Corridor#5 | Corridor#5 | – | – |
| Lab Stores | – | Corridor#5 | Stores #1 | Stores #1 | – |
| Life Suppt | – | – | Corridor#7 | Engine #2 (M) | Engine #2 |
| Livng Qtrs | – | Airlock #1 | RecrtnArea | RecrtnArea | – |
| Mess | Airlock #1 | Corridor#6 | Airlock #2 | – | – |
| RecrtnArea | Livng Qtrs | Airlock #2 | Airlock #2 | – | – |
| Stores #1 | – | Corridor#4 | Corridor#4 | Lab Stores | – |
| Stores #2 | – | – | Corridor#6 | Airlock #1 | Corridor#6 |
| Stores #3 | Corridor#6 | Corridor#6 | – | Airlock #2 | – |
| ShuttleBay | Shttlstore | Shttlstore | Engineerng | – | – |
| Shttlstore | – | Cargopod#1 | Cargopod#1 | ShuttleBay | ShuttleBay |

- Two duct links are one-way: Airlock#2→Cargopod#1 and Stores#3→Airlock#2.
- The ducts cross decks at Computer↔Engine#2, Engine#2↔Life Suppt, and Airlock#2→Cargopod#1.
- Narcissus has no duct; its slot at 7349 is the "ALIEN" name text.
- 0x6785 holds 2 bytes/room (row, col) giving each room's position on the duct-map screen (81A3).

### 3c. Grilles — 0x757C (1 byte/room, 21h rooms)

- FFh = grille in place. Any other value = removed.
- At start every grille is in place except room 0D (Corridor#6) (A5B3).
- Room view (7E58): grille in place → "Grille is in place" plus menu option **RmveGrille** (31h, slot 73CB). Grille removed → "removed" plus option **Grille** (32h, slot 73BF), which enters/leaves the duct (8AE8: state 2, dest = room XOR 40h).
- Removing a grille is state 3 → 8D36 sets 757C[room] = 0. The Alien removes grilles too (90F7).

### 3d. Room damage — 0x7536, 2 ASCII digits/room (×10 %)

- `L_9526/952A` adds A × 5 % to a room. Displayed as "Damage nn%" (7D8F).
- When the tens digit reaches 3 (30 %):
  - CommdCentr: msg 16h "Partial loss of control systems"
  - Computer: msg 17h "Computer Malfunction detected"
  - Life Suppt: msg 18h "Environmental irregularities"
  - ShuttleBay: msg 19h "Narcissus status RED"
  - Engine #1–3: start a **fire** (7A15 bits 3–5), msg 9 "WARNING:Fire in".
- At 70 %: msg 8 "Structural Damage in X".
- At 100 % (tens digit ':'): 95AD, **the ship explodes**, and the game ends via ADEC.
- The Alien does 5 % damage when its random move fails (9130). A burning engine does 5 % every 100 ticks (98F7, counter 95D2).

## 4. Items — location table 0x74F4..0x7509 (22 entries), morale value 0x7520, names via L_7C71

Location codes:
- `room#` = lying in that room.
- `80h+char` = carried as the 2nd item.
- `A0h+char` = **in use** (the active item, with its morale bonus applied).
- FFh = gone.

Each character can hold at most one active and one carried item. Initial values come from A1BE.

| idx | item (7C71) | start room | morale (7520) | notes |
|---|---|---|---|---|
| 0–2 | Elctrc Prd | Lab Stores, Infirmary, Eng Stores | 1 | 1 damage to the Alien |
| 3–5 | Incineratr | CommdCentr, CommdCentr, Engineerng | 2 | 1 damage to the Alien |
| 6–7 | Tracker | CommdCentr, Engineerng | 0 | Hitting the Alien with it breaks it (msg 5) and still deals 1 damage. While held as the active item it feeds 839C (?) |
| 8–11 | Fire Extng | Mess, Engine #1, Engine #2, Engine #3 | 1 | 3 charges each (83DA–83DD). Used as a weapon it costs 1 charge and gives a repel check. Also used for "Fight Fire" |
| 12 | Harpn Gun | ShuttleBay | 2 | 1 shot (83DE). Kills **every** crewman in the room (sets +7=1), adds 75 % damage to the room, and +5 damage to the Alien (9362–93C4) (?) |
| 13–15 | Laser Pist | Armoury ×3 | 2 | 8 charges each. Per shot: +30 % room damage, Alien damage +2, repel check |
| 16 | Net | Lab Stores | 0 | Freezes the Alien (timer FFh) and is used up. Needed for "Get Jones" (easier) |
| 17 | Cat Box | Laboratory | 0 | Needed for "Get Jones" |
| 18–19 | Spanner | Stores #3, Eng Stores | 1 | 1 damage to the Alien |
| 20 | (Cat in Net, 44h) | – | 1 | created on capture = Net idx + 4 |
| 21 | (Cat in Box, 45h) | – | 1 | created on capture = Box idx + 4 |

- `L_7C34`: fills 750A.. with the indices of items in the current room (7A0A).
- `L_7C4C/7C4F`: finds the character's active item → (7A10h) and carried item → (7A11h).
- `L_7C71`: item index → text id. Its CP chain encodes the groups in the table above.

## 5. Contextual menu builders (what L_7CAB / L_7CDC / L_7C71 do)

The order menu is a 19-slot list at 73BE, initialised from 75E2 by L_7FAC and drawn by 8030. The cursor row is (8393h) and the selected text is (8395h).
Commands dispatch through 0x8402 [mode (7A14h)]. Modes 0 and 1 go through 89F2 → table 840C+row (mode 0) or 8430+row (mode 1).

| slot | row | content | handler |
|---|---|---|---|
| 73BE | 0 | "move to:" | – |
| 73BF | 1 | Grille (when removed) | 8AE8 enter/leave duct |
| 73C0–C4 | 2–6 | exits (7C0F; in a duct, N/E/S/W via 828D) | 8A36 move (state 1) |
| 73C5 | 7 | "use:" | – |
| 73C6 | 8 | active item name (7CAB) | RET |
| 73C7 | 9 | carried item name | 8A7B: swap active/carried, re-applying the morale bonus |
| 73C8 | 10 | Get Item: shown when items are present and the carried slot is free | 8B36 → item list (mode 4, 8AFA) |
| 73C9 | 11 | Leave Item: shown when there is an active item. Drops the carried item first, if any | 8B8D |
| 73CA | 12 | "Special" | – |
| 73CB | 13 | ATTACK (Alien or active Android in the room) / RmveGrille | 8BCB → 9275 attack, or grille removal |
| 73CC | 14 | Get Jones (Jones in the room) | 8C05 |
| 73CD–CE | 15–16 | room specials (7CDC) | 95FE |
| 73CF | 17 | QUIT | 8A70 |

- **L_7CAB** fills the "use:" item slots and the Get/Leave options.
- **L_7CDC** builds the room-specific "Special" options:
  - ATTACK, if the active android is in the room (7CE3) or the Alien is (7CFE).
  - Get Jones, if Jones's room (83AAh) is this room.
  - CommdCentr (06): **Scuttle Nostromo**, or **Override Destruct** when 7A15 bit0 is set (destruct running).
  - Corridor#6 (0D): **BlowLock#1/#2**, or **SealLock#1/#2** when bits 1 and 2 are set.
  - Cryo Vault (0F): **Enter Hypersleep**.
  - Engine #1/2/3: **Fight Fire**, if bits 3/4/5 are set.
  - Narcissus (22): **Launch Narcissus**, unless bit6 is set.
- Top-level menu (mode 0, 75F5): Order: / 7 crew names (895B selects one) / Indicate Location (8996 → room list) / Display Level / Upper/Middle/Lower Deck (898A).

Global flags at 7A15:
- bit0: destruct sequence active
- bit1/bit2: Airlock #1/#2 blown. Anyone walking into a blown airlock is redirected to Corridor#6 (8CD8).
- bits3–5: fire in Engine #1–#3
- bit6: launch suppressed (?)

## 6. Mechanics notes (code outside 7A23–8400, kept short)

- **Crew automation, 8EE3.** Before an order resolves:
  - If effective morale is 0, the crewman **panics**: he drops his items into the room and moves at random (8F38).
  - Moving into a room that already holds ≥3 crew waits 20h and retries.
  - Entering a duct cell that is occupied is refused, and it needs morale ≥2.
  - So rooms hold at most 3 crew and duct cells hold 1 (?).
- **Alien AI, 909A**, runs when rec0 +0 = 0:
  - Crew in the room → attack (state 5, timer 3Ch).
  - Otherwise, if the grille is in place: bucket 0 → remove grille (state 3, 32h), else move.
  - Otherwise, if the grille is removed: bucket <2 → enter/leave duct, else random move (state 1, timer 50h).
- **Alien attack, 9958.**
  - Picks a victim from up to 3 crew in the room. 1 crew → that one. 2 → rand&1. 3 → bucket(rand&7).
  - The victim loses 1 health. ≥4 → msg 1. 2–3 → msg 0Ah "being wounded". <2 → death: +7=1, items dropped, msg 15h.
  - A dead victim may be carried off: the body's room is set to FFh with probability 4/16, and alien +5 = victim (?).
- **Crew attack, 92D0.** Needs health ≥2 and morale ≥2. The effect depends on the active item (see section 4).
  - The Alien dies when its damage (7382h) reaches 15 (83D1h) → AC5D.
  - **Repel check, 9468:**
    1. Count the attackers in state 4 with health >2 and morale >3.
    2. Add (alien damage)/4.
    3. Roll r (0..15). If r <15 and r < count, the Alien flees to a random exit and each attacker gets +1 base morale (cap 8).
- **Android, A130/9F7A.**
  - It activates once alien damage ≥6.
  - It then attacks crew in its room (msg 1Eh "X is attacking Y").
  - Crew can hit it: Prod, Incinerator, Spanner, Tracker and Laser each do −1 health, and it is destroyed at <2. The Net freezes it.
- **Airlock, 96F5.**
  - Blowing a lock kills/removes everyone in that airlock and destroys any items there.
  - If the Alien is in the airlock: it dies if its damage ≥3 or r ≥2. Otherwise it survives with damage <3 and r <2 (2/16) and reappears in the ShuttleBay (msg 12h).
- **Jones, 8FFB.**
  - Moves every 255 ticks to a random walking exit. He re-rolls if that room holds the Alien.
  - When he meets a crewman: msg 0 "X Sees Jones the Cat".
  - Capture (8C05) needs the Net or Cat Box as the active item. It succeeds if r ≥ (83E2h)[char] (Dallas 14, Kane 14, Ripley 13, Ash 13, Lambert 13, Parker 15, Brett 14), with the threshold lowered by 4 for the Net.
  - On failure Jones bolts immediately.
- **Launch, 9D14.**
  - Every active crewman must be in Narcissus (22).
  - Jones must be in Narcissus, or caged and in Narcissus/carried. Otherwise msg 1Bh "BioUnit Jones not present" / 1Ch "LAUNCH COUNTERMANDED".
- **Hypersleep, 9681/9780.** Timer FFh, then the crewman is removed from play (+7=FFh). His items go to the Cryo Vault.
- **Destruct, 9636/97BE.** Starts a 5:00 countdown (95DC–95DF). At zero → explosion (95AD).
- **"TOOH" counter, 95E6.**
  - Starts at "7500" and is decremented every (95D5) ticks. The reload is 2 × (number of records with +7 ≠ 0), and 0 means 256.
  - At "0500" → msg 1Ah "Oxygen status CRITICAL". On underflow → ACF5 (game over). So this is the **oxygen supply** (?).
  - Note the reload logic makes the clock run *faster* when people are absent (short game: interval 8; long game: interval 256 until the first casualty, then 2) — looks odd, verify (?).
- **Scoring (AE6B/AE9A/AEB2 → 9E37 "Competence Rating"):** Σ 2·health over surviving non-android crew + the number of rooms with damage <20 % + 2 × (oxygen thousands digit).

## 7. Work variables (7A09–7A22, 839x–83Dx)

| address | meaning |
|---|---|
| 7A09 | selected character |
| 7A0A | its room (with bit 6) |
| 7A0B | display deck/screen |
| 7A10 / 7A11 | active / carried item of the selected character |
| 7A12 / 7A13 | the other characters in the room (up to 2) |
| 7A14 | menu mode (0 top, 1 orders, 2/3 location list, 4 item pick) |
| 7A15 | ship flags |
| 7A1F / 7A20 | animation sprite frames |
| 7A21 | RNG pointer |
| 8393 / 8395 | cursor row / selected text |
| 839C–839E | tracker-marked rooms |
| 839F–83A2 | scratch crew list / morale sum |
| 83A4 | record loop index |
| 83A5 / 83A7 | android record pointer / index (9 = none) |
| 83A8 | android active |
| 83AA / 83AB | Jones room / move timer |
| 83CD / 83CF | message queue pointer / last message (9138 queues messages for 9165) |
| 83D2+i | weapon charges per item index |
