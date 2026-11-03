#!/usr/bin/env python3
"""Extract standard-speed data blocks from a TZX tape image.

Usage: python3 -I tools/tzx_extract.py <tape.tzx> <out_dir>
Writes blockN.bin (payload without flag/checksum) and manifest.txt.
"""
import struct
import sys
from pathlib import Path


def main(tape, out_dir):
    d = Path(tape).read_bytes()
    if d[:8] != b"ZXTape!\x1a":
        sys.exit("not a TZX file")
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    p, n, manifest = 10, 0, []
    while p < len(d):
        t = d[p]
        p += 1
        if t == 0x10:
            ln = struct.unpack("<H", d[p + 2:p + 4])[0]
            b = d[p + 4:p + 4 + ln]
            p += 4 + ln
            x = 0
            for c in b:
                x ^= c
            ok = "ok" if x == 0 else "BAD"
            payload = b[1:-1]
            if b[0] == 0 and ln == 19:
                typ = payload[0]
                name = payload[1:11].decode("latin-1").rstrip()
                dlen, p1, p2 = struct.unpack("<HHH", payload[11:17])
                manifest.append(f"{n:2} HEADER type={typ} name={name!r} len={dlen} param1={p1} param2={p2} chk={ok}")
            else:
                f = out / f"block{n}.bin"
                f.write_bytes(payload)
                manifest.append(f"{n:2} DATA flag={b[0]} len={len(payload)} file={f.name} chk={ok}")
        elif t == 0x30:
            l = d[p]
            manifest.append(f"{n:2} TEXT {d[p + 1:p + 1 + l].decode('latin-1')!r}")
            p += 1 + l
        elif t == 0x32:
            l = struct.unpack("<H", d[p:p + 2])[0]
            manifest.append(f"{n:2} ARCHIVE-INFO ({l} bytes)")
            p += 2 + l
        else:
            manifest.append(f"{n:2} unsupported block 0x{t:02x}, stopping")
            break
        n += 1
    (out / "manifest.txt").write_text("\n".join(manifest) + "\n")
    print("\n".join(manifest))


if __name__ == "__main__":
    main(*sys.argv[1:3])
