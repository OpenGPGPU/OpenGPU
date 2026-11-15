#!/usr/bin/env python3
"""Supplemental scalar GS parser/encoding guards; never a GPU/ISA oracle."""
import os
import argparse
import re
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
ROOT=Path(__file__).resolve().parents[1]
def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--negative-controls',action='store_true');ap.add_argument('--temporary-root',type=Path,help='private writable directory under this checkout .artifacts');args=ap.parse_args()
    mesa=Path(os.environ.get('OPENGPU_MESA_DIR',ROOT/'depends/mesa')).resolve()
    if not (mesa/'VERSION').is_file() or (mesa/'VERSION').read_text().strip()!='22.3.6':
        raise SystemExit('set OPENGPU_MESA_DIR to actual Mesa22.3.6')
    inc=[ROOT/'userspace/gallium',mesa/'src/gallium/include',mesa/'src/gallium/auxiliary',mesa/'src',mesa/'include']
    flags=['-std=c11','-O1','-Wall','-Wextra','-Werror','-Wno-unused-parameter','-Wno-sign-compare','-ffunction-sections','-fdata-sections']
    if sys.platform=='linux':flags+=['-DHAVE_ENDIAN_H']
    if os.environ.get('OPENGPU_TEST_SANITIZERS')=='1':flags+=['-fsanitize=address,undefined','-fno-omit-frame-pointer']
    units=[ROOT/'userspace/tests/native_geometry_emit_test.c',ROOT/'userspace/gallium/opengpu_native_geometry_emit.c',ROOT/'userspace/gallium/opengpu_native_tess_uniform_emit.c',
           ROOT/'userspace/gallium/opengpu_native_uniform_emit.c',ROOT/'userspace/gallium/opengpu_native_fragment_v2.c',
           mesa/'src/gallium/auxiliary/tgsi/tgsi_build.c',mesa/'src/gallium/auxiliary/tgsi/tgsi_parse.c']
    temporary=(args.temporary_root or ROOT/'.artifacts').resolve()
    if not temporary.is_relative_to((ROOT/'.artifacts').resolve()):ap.error('--temporary-root must be under this checkout .artifacts')
    temporary.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='gs-emit-',dir=temporary) as tmp:
        out=Path(tmp);env=dict(os.environ,TMPDIR=tmp)
        subprocess.run(shlex.split(os.environ.get('CC','cc'))+flags+['-I'+str(p) for p in inc]+list(map(str,units))+
                       (['-Wl,-dead_strip'] if sys.platform=='darwin' else ['-Wl,--gc-sections'])+['-o',str(out/'test')],check=True,env=env)
        subprocess.run([str(out/'test')],check=True,env=env)
        if args.negative_controls:
            original=units[1].read_text()
            controls={
                'sibling-addr-definition': ([
                    ('memcpy(b->addr_defined,old_addr_defined,sizeof(old_addr_defined));',''),
                    ('memcpy(b->addr_value,old_addr_value,sizeof(old_addr_value));','')], r'GS_UNEXPECTED_ACCEPT sibling-addr-definition\n'),
                'full-width-guard-encoding': ([('branch(7,src,31,','branch(6,src,31,')], r'guards\s*==\s*4'),
                'unused-span-canonical': ([('if(memcmp(copy.spans+i,&zero,sizeof(zero)))return -1;','(void)zero;')], r'GS_UNEXPECTED_ACCEPT unused-span-canonical\n'),
            }
            for name,(changes,expected) in controls.items():
                source=original
                for old,new in changes:
                    assert old in source,(name,old);source=source.replace(old,new)
                private=out/(name+'.c');private.write_text(source);changed=list(units);changed[1]=private
                binary=out/name
                subprocess.run(shlex.split(os.environ.get('CC','cc'))+flags+['-I'+str(p) for p in inc]+list(map(str,changed))+
                    (['-Wl,-dead_strip'] if sys.platform=='darwin' else ['-Wl,--gc-sections'])+['-o',str(binary)],check=True,env=env)
                result=subprocess.run([str(binary)],capture_output=True,text=True,env=env)
                assert result.returncode and re.search(r'assertion.*failed',result.stderr,re.I|re.S) and re.search(expected,result.stderr),(name,result.stderr)
                print('GS source/encoding negative control rejected:',name)

if __name__=='__main__':main()
