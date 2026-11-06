"""Strict shared 3 GiB boot profile. This file does not create a DTB or boot."""
from pathlib import Path
import re
import sys

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from set_arti_guest_extent import patch_extent

RAM_BASE=0x40000000
RAM_BYTES=3*1024**3
RAM_END=0x100000000
CMA_BASE=0x80000000
CMA_BYTES=1536*1024**2
CMA_END=0xe0000000

def check_memory(decoded):
    if len(re.findall(r'(?m)^\tmemory(?:@[0-9a-f]+)?\s*\{',decoded))!=1:
        raise ValueError('expected exactly one root RAM node')
    nodes=re.findall(r'(?ms)^\tmemory@40000000\s*\{(.*?)^\t\};',decoded)
    if len(nodes)!=1:
        raise ValueError('expected one QEMU RAM node at 0x40000000')
    reg=re.findall(r'\breg\s*=\s*<([^>]+)>;',nodes[0])
    cells=[int(c,0)for c in reg[0].split()]if len(reg)==1 else[]
    if cells!=[0,RAM_BASE,0,RAM_BYTES]:
        raise ValueError('actual DTB must describe exactly 3 GiB ending at exclusive 2^32')
    if not RAM_BASE<=CMA_BASE<CMA_END<=RAM_END or CMA_END-CMA_BASE!=CMA_BYTES:
        raise ValueError('pinned CMA does not fit entirely in actual RAM')
    return dict(ram_base=RAM_BASE,ram_bytes=RAM_BYTES,ram_end=RAM_END,
                cma_base=CMA_BASE,cma_bytes=CMA_BYTES,cma_end=CMA_END,
                cma_authority='cma=1536M@0x80000000 kernel argument; actual reservation must be observed after boot')

def check_dtb(decoded):
    result=check_memory(decoded)
    patched,_=patch_extent(decoded,16,16,64)
    if patched!=decoded:
        raise ValueError('actual GPU DTB metadata must already match 16x16 and stride64')
    return result

def kernel_cmdline(app):
    if app not in ('h7','h8'):
        raise ValueError('one explicit independent H7 or H8 run is required')
    return 'console=ttyAMA0 cma=1536M@0x80000000 rdinit=/init opengpu.qual='+app
