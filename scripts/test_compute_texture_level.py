#!/usr/bin/env python3
"""Compare bounded level-layout admission with independent integer arithmetic."""
import os, random, shlex, subprocess, tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
temporary = tempfile.TemporaryDirectory(prefix="opengpu-level-oracle-")
binary = Path(temporary.name) / "oracle"
subprocess.run(shlex.split(os.environ.get("CC", "cc")) + [
    "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-I" + str(root / "driver"),
    str(root / "driver/tests/opengpu_compute_texture_level_oracle.c"), "-o", str(binary),
], check=True)
r=random.Random(377378)
def oracle(q):
    t,w,h,d,n,lo,base,hi,row,sl,ly,block,b=q
    if t>3 or min(w,h,d,n)==0 or max(w,h,d)>16383 or n>65535 or hi>2**32 or max(row,sl,ly)>=2**33: return False
    ar=t==1; cube=t==2; vol=t==3
    if (not vol and d!=1) or (not ar and n!=1) or (cube and w!=h): return False
    if (not vol and sl!=0) or (not (ar or cube) and ly!=0): return False
    if b not in ((8,16) if block else (1,2,3,4,6,8,12,16)): return False
    if hi<=lo or base<lo: return False
    columns=(w+3)//4 if block else w
    rows=(h+3)//4 if block else h
    row_bytes=columns*b
    plane=(rows-1)*row+row_bytes
    if row<row_bytes or ((vol or ar or cube) and (sl if vol else ly)<plane): return False
    end=base+plane+((d-1)*sl if vol else 0)+((5 if cube else n-1 if ar else 0)*ly)
    return end<=hi
cases=[]
for _ in range(5000):
    t=r.randrange(4); block=r.randrange(2); b=r.choice((8,16) if block else (1,2,3,4,6,8,12,16))
    w=r.randint(1,12); h=w if t==2 else r.randint(1,12); d=r.randint(1,6) if t==3 else 1; n=r.randint(1,6) if t==1 else 1
    row=((w+3)//4 if block else w)*b+r.randint(0,7); rows=(h+3)//4 if block else h
    plane=(rows-1)*row+((w+3)//4 if block else w)*b
    sl=plane+r.randint(0,7) if t==3 else 0; ly=plane+r.randint(0,7) if t in (1,2) else 0
    lo=4096; base=lo+r.randint(0,32); end=base+plane+(d-1)*sl+(5 if t==2 else n-1 if t==1 else 0)*ly
    q=[t,w,h,d,n,lo,base,end+r.randint(0,16),row,sl,ly,block,b]
    if r.randrange(2):
        j=r.randrange(len(q)); q[j]=r.choice([0,1,2,3,4,7,8,16,63,64,255,256,16383,16384,65535,65536,2**32,2**33-1,2**33])
        if j in (0,1,2,3,4,5,6,11,12) and q[j]>0xffffffff: q[j]=0xffffffff
        if j==11: q[j]=min(q[j],1)
    cases.append(q)
raw=''.join(' '.join(map(str,q))+'\n' for q in cases)
res=subprocess.run([str(binary)],input=raw,text=True,capture_output=True,check=True)
actual=res.stdout.splitlines()
assert len(actual)==len(cases),(len(actual),len(cases))
for i,(q,a) in enumerate(zip(cases,actual)):
    expected=oracle(q)
    assert (a=='1')==expected,(i,q,a,expected)
print(f'{len(cases)} level layouts match independent wide-arithmetic model')

temporary.cleanup()
