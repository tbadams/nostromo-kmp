# Dump Alien (Argus 1984) ship map tables from block4.bin (loads at 0x6000)
mem = bytearray(0x10000)
b = open('/Users/trevora/dev/hack2026/reference/extract/block4.bin','rb').read()
mem[0x6000:0x6000+len(b)] = b
def txt(i): return bytes(mem[0x6E3D+i*10:0x6E3D+i*10+10]).decode('latin1').strip()
DECK=['Upper','Middle','Lower','Narcissus']
N=0x23
def nm(r): return '%02X:%s'%(r,txt(r)) if r<0x22+1 else '%02X:?'%r
print('== Room connections 0x71F3 (5 B/room) ==')
for r in range(N):
    e=list(mem[0x71F3+r*5:0x71F3+r*5+5])
    exits=[]
    for c in e:
        if c!=r and c not in exits: exits.append(c)
    print('%-14s deck=%-9s raw=%s -> %s'%(nm(r),DECK[mem[0x74D1+r]],' '.join('%02X'%c for c in e),', '.join(nm(c) for c in exits)))
print('== Duct table 0x72A2 (5 B/room, N E S W ?) ==')
for r in range(N):
    e=list(mem[0x72A2+r*5:0x72A2+r*5+5])
    d=['%s=%s'%(k,nm(c)) for k,c in zip('NESW?',e) if c!=r]
    print('%-14s raw=%s  %s'%(nm(r),' '.join('%02X'%c for c in e),'  '.join(d)))
print('== map coords 0x6785 (row,col) ==')
for r in range(N): print(nm(r), mem[0x6785+r*2], mem[0x6785+r*2+1])
