import sys
mem = bytearray(0x10000)
b = open('/Users/trevora/dev/hack2026/reference/extract/block4.bin','rb').read()
mem[0x6000:0x6000+len(b)] = b
def txt(i):
    return bytes(mem[0x6E3D+i*10:0x6E3D+i*10+10]).decode('latin1')
def names():
    out=[];a=0x734C;cur=''
    while len(out)<8:
        c=mem[a];a+=1
        if c==0xFF: out.append(cur);cur=''
        else: cur+=chr(c) if 32<=c<127 else '?'
    return out
if __name__=='__main__':
    what=sys.argv[1]
    if what=='text':
        for i in range(0,0xA0):
            print('%02X %3d %r'%(i,i,txt(i)))
    elif what=='hex':
        a=int(sys.argv[2],16);n=int(sys.argv[3],16)
        for x in range(a,a+n,16):
            print('%04X '%x+' '.join('%02X'%mem[y] for y in range(x,min(x+16,a+n))))
