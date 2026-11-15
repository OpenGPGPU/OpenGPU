#!/usr/bin/env python3
"""Export literal address microsequences using the real mapped emitter helper.

Only supplemental parser/arithmetic inputs; not original ESSL or shader execution.
Run TessellationMappedAddressSpec with opengpu.mappedAddressPrograms set to --dump.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
from owner_process_lifetime import CleanupIncomplete, stop_group
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--dump',required=True,type=Path)
    ap.add_argument('--wrong-shift-control',action='store_true',help='test-only old bug; RTL must reject index1')
    args=ap.parse_args();dump=args.dump.resolve();dump.mkdir(parents=True,exist_ok=True)
    assert not any(dump.iterdir()),'dump must be empty'
    mesa=Path(os.environ.get('OPENGPU_MESA_DIR',ROOT/'depends/mesa')).resolve()
    assert (mesa/'VERSION').read_text().strip()=='22.3.6'
    units=[ROOT/'userspace/tests/tessellation_mapped_address_test.c',ROOT/'userspace/gallium/opengpu_native_uniform_emit.c',ROOT/'userspace/gallium/opengpu_native_fragment_v2.c',mesa/'src/gallium/auxiliary/tgsi/tgsi_build.c',mesa/'src/gallium/auxiliary/tgsi/tgsi_parse.c']
    includes=[ROOT/'userspace/gallium',mesa/'src/gallium/include',mesa/'src/gallium/auxiliary',mesa/'src',mesa/'include']
    bound={p.resolve():sha(p) for base in (ROOT/'userspace',mesa/'src',mesa/'include') for p in base.rglob('*') if p.is_file() and not p.is_symlink() and p.suffix in ('.c','.h','.inc')}
    bound[Path(__file__).resolve()]=sha(Path(__file__))
    def unchanged():assert all(sha(p)==h for p,h in bound.items()),'mapped input changed'
    temporary=Path(tempfile.mkdtemp(prefix='mapped-addr-',dir=os.environ.get('TMPDIR',ROOT/'.artifacts'))).resolve();preserve=False
    try:
        flags=['-std=c11','-O1','-Wall','-Wextra','-Werror','-Wno-unused-parameter','-Wno-sign-compare','-ffunction-sections','-fdata-sections','-fsanitize=address,undefined','-fno-omit-frame-pointer']
        if sys.platform=='linux':flags+=['-DHAVE_ENDIAN_H']
        if args.wrong_shift_control:
            source=(ROOT/'userspace/gallium/opengpu_native_tess_uniform_emit.c').read_text()
            a=source.index('    if(width==3){',source.index('static void mapped_descriptor('))
            z=source.index('    scalar(b,10,base);',a)
            mutant=temporary/'wrong-shift.c';mutant.write_text(source[:a]+'    scalar(b,10,width*8);word(b,vec(0x25,4,29,10,reg));\n'+source[z:]);bound[mutant]=sha(mutant)
            flags+=['-DOPENGPU_TESS_MAPPED_SOURCE="'+str(mutant)+'"']
        compiler=shlex.split(os.environ.get('CC','cc'));exe=Path(shutil.which(compiler[0]) or compiler[0]).resolve();bound[exe]=sha(exe)
        def run(argv):
            unchanged();p=subprocess.Popen(list(map(str,argv)),start_new_session=True)
            try:code=p.wait(timeout=180)
            finally:stop_group(p)
            unchanged();assert code==0
        for i,unit in enumerate(units):
            obj=temporary/(str(i)+'.o');dep=obj.with_suffix('.d')
            run(compiler+flags+['-I'+str(p) for p in includes]+['-MMD','-MF',dep,'-c',unit,'-o',obj])
            for name in shlex.split(dep.read_text().replace('\\\n','').split(':',1)[1]):assert Path(name).resolve() in bound,'unbound MMD '+name
            bound[obj]=sha(obj);bound[dep]=sha(dep)
        binary=temporary/'export';run(compiler+flags+[temporary/(str(i)+'.o') for i in range(len(units))]+(['-Wl,-dead_strip'] if sys.platform=='darwin' else ['-Wl,--gc-sections'])+['-o',binary]);bound[binary]=sha(binary)
        run([binary,dump]);print('Mapped literal original helper words exported; CPU only, GPU=false')
    except CleanupIncomplete:preserve=True;raise
    finally:
        if not preserve:shutil.rmtree(temporary)
if __name__=='__main__':main()
