# Known facts (seed for analysis)

## Files
- reference/extract/block4.bin loads at 0x6000 (21500 B), block5.bin at 0xEA60 (3000 B).
- reference/disasm/alien.asm: traced listing (code + DB rows w/ ASCII). Entry points in entries.txt.
- Regenerate with more entries: `python3 -I tools/z80dis.py reference/disasm/alien.asm <comma-entries> reference/extract/block4.bin@0x6000 reference/extract/block5.bin@0xEA60`
- tools/z80dis.py also importable: decode(mem, pc).

## Entry / main loop (0x8E69)
Init: 8340, A655, A745, AA5A, AE11, 91CA, 7AB9
Loop L_8E81: 877C, 9D7F, 9F7A, A130, 909A, 91F3, 8FFB, 8C85, 8897, 88C8, AF50, 8897, 88C8, 91F3, 95EB, AD19,
  then key '1' (port F7FE bit0) -> AF0F (pause); 8335 twice; if 0 -> 8340 (restart?) ; JR 8E81

## Jump tables
- 0x8402: 5 handlers indexed by (7A14h) = menu mode. Modes 0/1 go via 89F2 to row tables 0x840C / 0x8430 (18 entries each, indexed by cursor row 8393h). (Earlier note said one 41-entry table: wrong.)
- 0x8454: indexed by crew record +3 (state) -> 8A78,8CC2,8D05,8D36,9290,9958,9780,9F35
- 0xA87F: indexed by (8393h) -> AEC2, A524, A8EA (title menu: short game/long game/intro)

## Data
- 0x734C: names, FF-terminated: ALIEN, Dallas, Kane, Ripley, Ash, Lambert, Parker, Brett (index 0..7)
- 0x737E: 8-byte records x8 (index 0 = Alien, 1..7 crew). (7A09h)=selected character idx.
  +1 = room (bit7/6 flags? AND 3Fh = room#), +3 = state, ... initial bytes at 7386: `00 40 0C 00 00 02 02 02` etc.
- 0x83A4 loop counter over records; 0x83A7, 0x83A5/6 (ptr), 0x83A8, 0x83AA related to alien/Jones
- 0x6E3D: 10-char text entries (room names 0..~33, items, menu words, "North"...). Index via L_7B92 (A*10 + 6E3D).
  Rooms: Airlock#1, Airlock#2, Armoury, Cargopod#1-3, CommdCentr, Computer, Corridor#1-7, Cryo Vault, Engineerng,
  Engine#1-3, Eng Stores, Infirmary, Inf Stores, Laboratory, Lab Stores, Life Suppt, Livng Qtrs, Mess, RecrtnArea,
  Stores#1-3, ShuttleBay, Shttlstore, then Narcissus, Elctrc Prd, Incineratr, Tracker, Fire Extng, Harpn Gun, Laser Pist, Net, Cat Box, Spanner...
- 0x71F3: 5 bytes per room = connected room list (L_7C0F).
- 0x74D1: room -> deck (0/1/2 = Upper/Middle/Lower).
- 0x7509 backwards 22 entries: item location table (L_7C34); values room# or 0x80+char (carried) / 0xA0+char.
- 0x759E: morale labels Dead/Collapsed/Wounded/Broken/Shaken/Uneasy/Stable/Confident
- 0x8465..0x8750: event message strings; 0xA40D..: end-game strings, "Competence Rating"
- block5 F5C4..: labels CRWRAT, DAMRAT, ENDEXP, TIMRAT, ENDEND, CRWDED (scoring?)
- 0x95D6 "TIME:", 0x95E0 "TOOH: 7500"
