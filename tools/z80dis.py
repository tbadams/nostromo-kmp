#!/usr/bin/env python3
"""Tracing Z80 disassembler.

Usage:
  python3 -I tools/z80dis.py <out.asm> <entry,...> <file.bin>@<loadaddr> [...]

Loads each binary at its address into a 64K image, traces code from the entry
points (following jumps/calls, stopping at RET/JP/JR unconditional), and
writes a listing where traced bytes are instructions and the rest is shown as
DB rows with an ASCII column. Labels: L_xxxx for jump/call targets.
Also usable as a module: decode(mem, pc) -> (text, length, targets, flow).

Optional: --symbols FILE  (place anywhere in the args). One symbol per line:
    ADDR  NAME  [; comment]        e.g.  8E69  main  ; entry point
ADDR is hex (with or without 0x/h). NAME replaces L_ADDR labels and any
16-bit operand equal to ADDR; the comment is emitted above the label.
Blank lines and lines starting with ';' or '#' are ignored.
"""
import re
import sys
from pathlib import Path

R = ["B", "C", "D", "E", "H", "L", "(HL)", "A"]
RP = ["BC", "DE", "HL", "SP"]
RP2 = ["BC", "DE", "HL", "AF"]
CC = ["NZ", "Z", "NC", "C", "PO", "PE", "P", "M"]
ALU = ["ADD A,", "ADC A,", "SUB ", "SBC A,", "AND ", "XOR ", "OR ", "CP "]
ROT = ["RLC", "RRC", "RL", "RR", "SLA", "SRA", "SLL", "SRL"]
IM = ["0", "0", "1", "2", "0", "0", "1", "2"]
BLI = {(4, 0): "LDI", (4, 1): "CPI", (4, 2): "INI", (4, 3): "OUTI",
       (5, 0): "LDD", (5, 1): "CPD", (5, 2): "IND", (5, 3): "OUTD",
       (6, 0): "LDIR", (6, 1): "CPIR", (6, 2): "INIR", (6, 3): "OTIR",
       (7, 0): "LDDR", (7, 1): "CPDR", (7, 2): "INDR", (7, 3): "OTDR"}

# flow: None = continues, "jump" = unconditional end, "ret" = end
def h8(v): return f"{v:02X}h" if v < 0xA0 else f"0{v:02X}h"
def h16(v): return f"{v:04X}h" if v < 0xA000 else f"0{v:04X}h"


def decode(mem, pc):
    start = pc
    ix = None  # "IX"/"IY" when prefixed
    op = mem[pc]
    if op in (0xDD, 0xFD):
        ix = "IX" if op == 0xDD else "IY"
        pc += 1
        op = mem[pc]
        if op in (0xDD, 0xFD, 0xED):
            return ("DB " + h8(mem[start]) + " ; lone prefix", 1, [], None)
    pc += 1
    targets, flow = [], None

    def b():
        nonlocal pc
        v = mem[pc]; pc += 1; return v

    def w():
        nonlocal pc
        v = mem[pc] | (mem[pc + 1] << 8); pc += 2; return v

    def d_ix():
        v = b()
        v = v - 256 if v > 127 else v
        return f"({ix}{'+' if v >= 0 else '-'}{abs(v):02X}h)"

    def r(i, allow_ixh=True):
        if ix is None:
            return R[i]
        if i == 6:
            return d_ix()
        if allow_ixh and i in (4, 5):
            return ix + ("H" if i == 4 else "L")
        return R[i]

    def rp(i):
        return ix if (ix and i == 2) else RP[i]

    def rp2(i):
        return ix if (ix and i == 2) else RP2[i]

    if op == 0xCB:
        if ix:
            disp = d_ix()
            o = b()
        else:
            o = b()
        x, y, z = o >> 6, (o >> 3) & 7, o & 7
        reg = disp if ix else R[z]
        if x == 0:
            t = f"{ROT[y]} {reg}"
        elif x == 1:
            t = f"BIT {y},{reg}"
        elif x == 2:
            t = f"RES {y},{reg}"
        else:
            t = f"SET {y},{reg}"
        return (t, pc - start, [], None)

    if op == 0xED:
        o = b()
        x, y, z = o >> 6, (o >> 3) & 7, o & 7
        p, q = y >> 1, y & 1
        t = None
        if x == 1:
            if z == 0: t = f"IN {R[y]},(C)" if y != 6 else "IN (C)"
            elif z == 1: t = f"OUT (C),{R[y]}" if y != 6 else "OUT (C),0"
            elif z == 2: t = f"{'SBC' if q == 0 else 'ADC'} HL,{RP[p]}"
            elif z == 3:
                nn = w()
                t = f"LD ({h16(nn)}),{RP[p]}" if q == 0 else f"LD {RP[p]},({h16(nn)})"
            elif z == 4: t = "NEG"
            elif z == 5:
                t = "RETI" if y == 1 else "RETN"; flow = "ret"
            elif z == 6: t = f"IM {IM[y]}"
            elif z == 7: t = ["LD I,A", "LD R,A", "LD A,I", "LD A,R", "RRD", "RLD", "NOP*", "NOP*"][y]
        elif x == 2 and (y, z) in BLI:
            t = BLI[(y, z)]
        if t is None:
            t = f"DB 0EDh,{h8(o)} ; invalid ED"
        return (t, pc - start, [], flow)

    x, y, z = op >> 6, (op >> 3) & 7, op & 7
    p, q = y >> 1, y & 1
    t = None
    if x == 0:
        if z == 0:
            if y == 0: t = "NOP"
            elif y == 1: t = "EX AF,AF'"
            else:
                e = b(); e = e - 256 if e > 127 else e
                tgt = (pc + e) & 0xFFFF
                targets.append(tgt)
                if y == 2: t = f"DJNZ L_{tgt:04X}"
                elif y == 3: t = f"JR L_{tgt:04X}"; flow = "jump"
                else: t = f"JR {CC[y - 4]},L_{tgt:04X}"
        elif z == 1:
            if q == 0: t = f"LD {rp(p)},{h16(w())}"
            else: t = f"ADD {rp(2)},{rp(p)}"
        elif z == 2:
            if q == 0:
                t = ["LD (BC),A", "LD (DE),A", None, None][p]
                if p == 2: t = f"LD ({h16(w())}),{rp(2)}"
                if p == 3: t = f"LD ({h16(w())}),A"
            else:
                t = ["LD A,(BC)", "LD A,(DE)", None, None][p]
                if p == 2: t = f"LD {rp(2)},({h16(w())})"
                if p == 3: t = f"LD A,({h16(w())})"
        elif z == 3:
            t = f"{'INC' if q == 0 else 'DEC'} {rp(p)}"
        elif z in (4, 5):
            t = f"{'INC' if z == 4 else 'DEC'} {r(y)}"
        elif z == 6:
            dst = r(y)
            t = f"LD {dst},{h8(b())}"
        else:
            t = ["RLCA", "RRCA", "RLA", "RRA", "DAA", "CPL", "SCF", "CCF"][y]
    elif x == 1:
        if y == 6 and z == 6:
            t = "HALT"
        else:
            # with index prefix, if one side is (IX+d) the other uses plain H/L
            mem_side = (y == 6 or z == 6)
            dst = r(y, not mem_side)
            src = r(z, not mem_side)
            t = f"LD {dst},{src}"
    elif x == 2:
        t = f"{ALU[y]}{r(z)}"
    else:
        if z == 0:
            t = f"RET {CC[y]}"
        elif z == 1:
            if q == 0: t = f"POP {rp2(p)}"
            else:
                if p == 0: t = "RET"; flow = "ret"
                elif p == 1: t = "EXX"
                elif p == 2: t = f"JP ({ix or 'HL'})"; flow = "ret"
                else: t = f"LD SP,{ix or 'HL'}"
        elif z == 2:
            nn = w(); targets.append(nn); t = f"JP {CC[y]},L_{nn:04X}"
        elif z == 3:
            if y == 0:
                nn = w(); targets.append(nn); t = f"JP L_{nn:04X}"; flow = "jump"
            elif y == 2: t = f"OUT ({h8(b())}),A"
            elif y == 3: t = f"IN A,({h8(b())})"
            elif y == 4: t = f"EX (SP),{ix or 'HL'}"
            elif y == 5: t = "EX DE,HL"
            elif y == 6: t = "DI"
            elif y == 7: t = "EI"
        elif z == 4:
            nn = w(); targets.append(nn); t = f"CALL {CC[y]},L_{nn:04X}"
        elif z == 5:
            if q == 0: t = f"PUSH {rp2(p)}"
            elif p == 0:
                nn = w(); targets.append(nn); t = f"CALL L_{nn:04X}"
        elif z == 6:
            t = f"{ALU[y]}{h8(b())}"
        else:
            tgt = y * 8; t = f"RST {h8(tgt)}"
            if tgt == 0: flow = "ret"
    return (t, pc - start, targets, flow)


def trace(mem, loaded, entries):
    code = {}  # addr -> (text, len)
    labels = set(entries)
    work = list(entries)
    while work:
        pc = work.pop()
        while loaded[pc] and pc not in code:
            t, ln, tg, flow = decode(mem, pc)
            code[pc] = (t, ln)
            for a in tg:
                labels.add(a)
                if loaded[a] and a not in code:
                    work.append(a)
            # RST 28h = FP calculator: following bytes are calc literals, stop
            if t == "RST 28h":
                break
            if flow:
                break
            pc = (pc + ln) & 0xFFFF
    return code, labels


def load_symbols(path):
    syms = {}
    for line in Path(path).read_text().splitlines():
        line = line.strip()
        if not line or line[0] in ";#":
            continue
        body, _, comment = line.partition(";")
        addr, name = body.split()[:2]
        addr = int(addr.lower().removeprefix("0x").removesuffix("h"), 16)
        syms[addr] = (name, comment.strip())
    return syms


def apply_symbols(text, syms):
    def label(m):
        a = int(m.group(1), 16)
        return syms[a][0] if a in syms else m.group(0)

    def word(m):
        a = int(m.group(1), 16)
        return syms[a][0] if a in syms else m.group(0)

    text = re.sub(r"L_([0-9A-F]{4})", label, text)
    return re.sub(r"\b0?([0-9A-F]{4})h\b", word, text)


def main():
    args = sys.argv[1:]
    syms = {}
    if "--symbols" in args:
        i = args.index("--symbols")
        syms = load_symbols(args[i + 1])
        del args[i:i + 2]
    out = args[0]
    entries = [int(e, 0) for e in args[1].split(",") if e]
    mem = bytearray(65536)
    loaded = bytearray(65536)
    ranges = []
    for spec in args[2:]:
        f, a = spec.rsplit("@", 1)
        a = int(a, 0)
        data = Path(f).read_bytes()
        mem[a:a + len(data)] = data
        for i in range(a, a + len(data)):
            loaded[i] = 1
        ranges.append((a, a + len(data)))
    code, labels = trace(mem, loaded, entries)
    labels |= set(syms)
    lines = []

    def label_for(a):
        if a in syms:
            name, comment = syms[a]
            if comment:
                lines.append(f"; {comment}")
            return name + ":"
        return f"L_{a:04X}:" if a in labels else ""

    for lo, hi in sorted(ranges):
        lines.append(f"\n        ORG {h16(lo)}")
        a = lo
        while a < hi:
            if a in code:
                t, ln = code[a]
                lab = label_for(a)
                raw = " ".join(f"{mem[a + i]:02X}" for i in range(ln))
                if len(lab) >= 8:
                    lines.append(lab)
                    lab = ""
                lines.append(f"{lab:<8}{apply_symbols(t, syms):<26}; {a:04X}  {raw}")
                a += ln
            else:
                n = 0
                while a + n < hi and (a + n) not in code and n < 16 and not (n and (a + n) in labels):
                    n += 1
                chunk = mem[a:a + n]
                asc = "".join(chr(c & 0x7F) if 32 <= (c & 0x7F) < 127 else "." for c in chunk)
                lab = label_for(a)
                if len(lab) >= 8:
                    lines.append(lab)
                    lab = ""
                lines.append(f"{lab:<8}DB {','.join(h8(c) for c in chunk):<70}; {a:04X} |{asc}|")
                a += n
    Path(out).parent.mkdir(parents=True, exist_ok=True)
    Path(out).write_text("\n".join(lines) + "\n")
    total = sum(hi - lo for lo, hi in ranges)
    covered = sum(ln for t, ln in code.values())
    print(f"traced {len(code)} instructions, {covered}/{total} bytes as code, {len(labels)} labels")


if __name__ == "__main__":
    main()
