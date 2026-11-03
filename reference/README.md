# Reference: reverse-engineering Alien (1984)

This folder holds the analysis of the original ZX Spectrum game. The readable summary is [`docs/mechanics.md`](../docs/mechanics.md).

## What's committed and what isn't

| Path | Committed? | What it is |
|---|---|---|
| `notes/*.md` | yes | Analysis notes, with addresses |
| `disasm/symbols.txt` | yes | Address → name/comment map applied to the listing |
| `disasm/entries.txt` | yes | Code entry points for the tracing disassembler |
| `extract/` | **no** | Raw tape blocks (the original program and data) |
| `disasm/alien.asm` | **no** | Full disassembly listing |
| `listing/loader.bas` | **no** | The tape's BASIC loader |
| `loading_screen.png` | **no** | Loading screen render (optional) |

The uncommitted files are the original game's copyrighted contents, so they're in `.gitignore`. You can regenerate all of them from your own copy of the tape with the scripts in `tools/`, as below.

## Regenerating

You need your own copy of the tape image `Alien.tzx` (standard-speed TZX, 4 data blocks), placed in the repo root, and Python 3.9+ (standard library only).

Run everything from the repo root. `-I` runs Python in isolated mode, which is good practice when reading untrusted files.

```sh
# 1. Split the tape into blocks -> reference/extract/block{2..5}.bin + manifest.txt
python3 -I tools/tzx_extract.py Alien.tzx reference/extract

# 2. Detokenize the BASIC loader (block 2; its variables start at offset 516)
mkdir -p reference/listing
python3 -I tools/basic_list.py reference/extract/block2.bin 0 516 > reference/listing/loader.bas

# 3. Disassemble, with names from symbols.txt
#    block4 loads at 0x6000, block5 at 0xEA60; tracing starts from entries.txt
python3 -I tools/z80dis.py --symbols reference/disasm/symbols.txt reference/disasm/alien.asm \
  $(cat reference/disasm/entries.txt) \
  reference/extract/block4.bin@0x6000 reference/extract/block5.bin@0xEA60
```

**Expected results:**
- **Step 1** prints 6 tape blocks with `chk=ok` on each data block. The data lengths are 528, 6784, 21500 and 3000.
- **Step 3** prints `traced 5270 instructions, 10641/24500 bytes as code`.
- If the numbers differ, your tape is a different dump or version, and the addresses in the notes may not match.

## Other tools

These read `reference/extract/block4.bin`, so run step 1 first.

```sh
python3 -I tools/dump_map.py               # room graph, duct network, decks
python3 -I tools/dump_tables.py text       # the 10-character text table (rooms, items, menu words)
python3 -I tools/dump_tables.py hex ADDR LEN   # hex dump of memory, e.g. hex 737E 40
```

## Improving the disassembly

- **Names and comments:** add `ADDR  NAME  ; comment` lines to `disasm/symbols.txt`, then run step 3 again. Mark guesses with `(?)`.
- **Code shown as `DB` data:** if you find code the tracer didn't reach, usually through a jump table, add its address to `disasm/entries.txt` (one comma-separated list), then run step 3 again.
