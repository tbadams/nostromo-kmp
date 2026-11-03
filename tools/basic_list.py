#!/usr/bin/env python3
"""ZX Spectrum 48K BASIC detokenizer.

Usage: python3 -I tools/basic_list.py <file.bin> [prog_offset] [vars_offset]
Lists BASIC lines from prog_offset (default 0) until vars_offset (default:
stop at first invalid line), then dumps the variables area if given.
"""
import struct
import sys
from pathlib import Path

TOKENS = (
    "RND INKEY$ PI FN POINT SCREEN$ ATTR AT TAB VAL$ CODE VAL LEN SIN COS TAN ASN ACS ATN "
    "LN EXP INT SQR SGN ABS PEEK IN USR STR$ CHR$ NOT BIN OR AND <= >= <> LINE THEN TO STEP "
    "DEF_FN CAT FORMAT MOVE ERASE OPEN_# CLOSE_# MERGE VERIFY BEEP CIRCLE INK PAPER FLASH "
    "BRIGHT INVERSE OVER OUT LPRINT LLIST STOP READ DATA RESTORE NEW BORDER CONTINUE DIM REM "
    "FOR GO_TO GO_SUB INPUT LOAD LIST LET PAUSE NEXT POKE PRINT PLOT RUN SAVE RANDOMIZE IF CLS "
    "DRAW CLEAR RETURN COPY"
).split()
TOKENS = {0xA5 + i: t.replace("_", " ") for i, t in enumerate(TOKENS)}


def zx_float(b):
    """Decode 5-byte Spectrum number."""
    if b[0] == 0:  # small integer form
        v = b[2] | (b[3] << 8)
        return -(65536 - v) if b[1] == 0xFF else v
    exp = b[0] - 128
    mant = ((b[1] | 0x80) << 24) | (b[2] << 16) | (b[3] << 8) | b[4]
    v = mant / (1 << 32) * (2 ** exp)
    return -v if b[1] & 0x80 else v


def fmt_num(v):
    return str(int(v)) if float(v).is_integer() else repr(round(v, 9))


def detok(body):
    out, i, in_rem, in_str = [], 0, False, False
    while i < len(body):
        c = body[i]
        if c == 0x0E and not in_rem and not in_str:
            i += 6
            continue
        if c == 0x0D:
            break
        if in_rem:
            out.append(chr(c) if 32 <= c < 127 else f"\\x{c:02X}")
        elif c == 0x22:
            in_str = not in_str
            out.append('"')
        elif c >= 0xA5 and not in_str:
            t = TOKENS[c]
            if out and out[-1] and out[-1][-1:] not in " (":
                t = " " + t
            out.append(t + " ")
            if c == 0xEA:
                in_rem = True
        elif 0x90 <= c <= 0xA4:
            out.append(f"{{UDG-{chr(c - 0x90 + 65)}}}")
        elif c == 0x7F:
            out.append("(c)")
        elif c == 0x60:
            out.append("£")
        elif 32 <= c < 127:
            out.append(chr(c))
        elif c in (0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17):
            names = {0x10: "INK", 0x11: "PAPER", 0x12: "FLASH", 0x13: "BRIGHT",
                     0x14: "INVERSE", 0x15: "OVER", 0x16: "AT", 0x17: "TAB"}
            n = 2 if c in (0x16, 0x17) else 1
            out.append(f"{{{names[c]} {','.join(str(x) for x in body[i + 1:i + 1 + n])}}}")
            i += n
        else:
            out.append(f"{{{c:02X}}}")
        i += 1
    return "".join(out).rstrip()


def list_prog(d, start, end):
    p = start
    while p + 4 <= end:
        ln = (d[p] << 8) | d[p + 1]
        if ln > 9999:
            break
        size = d[p + 2] | (d[p + 3] << 8)
        body = d[p + 4:p + 4 + size]
        print(f"{ln:4} {detok(body)}")
        p += 4 + size
    return p


def dump_vars(d, p, end):
    print("\n; ---- variables ----")
    while p < end:
        c = d[p]
        if c == 0x80:
            break
        kind, letter = c >> 5, chr((c & 0x1F) + 0x60)
        if kind == 3:  # single-letter number
            print(f"{letter} = {fmt_num(zx_float(d[p + 1:p + 6]))}")
            p += 6
        elif kind == 5:  # multi-letter number
            q = p + 1
            name = letter
            while not d[q] & 0x80:
                name += chr(d[q]); q += 1
            name += chr(d[q] & 0x7F)
            print(f"{name} = {fmt_num(zx_float(d[q + 1:q + 6]))}")
            p = q + 6
        elif kind == 2:  # string
            ln = d[p + 1] | (d[p + 2] << 8)
            s = d[p + 3:p + 3 + ln].decode("latin-1")
            print(f"{letter}$ = {s!r}")
            p += 3 + ln
        elif kind in (4, 6):  # number array / char array
            ln = d[p + 1] | (d[p + 2] << 8)
            ndim = d[p + 3]
            dims = struct.unpack(f"<{ndim}H", d[p + 4:p + 4 + 2 * ndim])
            data = d[p + 4 + 2 * ndim:p + 3 + ln]
            if kind == 4:
                vals = [fmt_num(zx_float(data[i:i + 5])) for i in range(0, len(data), 5)]
                print(f"DIM {letter}{dims}:")
                row = dims[-1]
                for i in range(0, len(vals), row):
                    print("   ", " ".join(vals[i:i + row]))
            else:
                print(f"DIM {letter}$({','.join(map(str, dims))}):")
                row = dims[-1]
                for i in range(0, len(data), row):
                    print("   ", repr(data[i:i + row].decode("latin-1")))
            p += 3 + ln
        elif kind == 7:  # FOR control variable
            print(f"FOR {letter} = {fmt_num(zx_float(d[p + 1:p + 6]))} TO {fmt_num(zx_float(d[p + 6:p + 11]))} "
                  f"STEP {fmt_num(zx_float(d[p + 11:p + 16]))} loop-line {d[p + 16] | d[p + 17] << 8}")
            p += 19
        else:
            print(f"; unknown var byte {c:02X} at offset {p}")
            break


if __name__ == "__main__":
    data = Path(sys.argv[1]).read_bytes()
    start = int(sys.argv[2], 0) if len(sys.argv) > 2 else 0
    vend = int(sys.argv[3], 0) if len(sys.argv) > 3 else None
    p = list_prog(data, start, vend if vend is not None else len(data))
    if vend is not None:
        dump_vars(data, vend, len(data))
