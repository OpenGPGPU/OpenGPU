#!/usr/bin/env python3
"""Small real-Mesa parser/emitter CPU guards; no Mesa build, VM or GPU."""
import argparse
import os
import re
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
ROOT=Path(__file__).resolve().parents[1]
def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--language-negative-controls',action='store_true');args=ap.parse_args()
    mesa=Path(os.environ.get('OPENGPU_MESA_DIR',ROOT/'depends/mesa')).resolve()
    if not (mesa/'VERSION').is_file() or (mesa/'VERSION').read_text().strip()!='22.3.6':
        raise SystemExit('set OPENGPU_MESA_DIR to actual Mesa22.3.6 sources')
    includes=[ROOT/'userspace/gallium',mesa/'src/gallium/include',mesa/'src/gallium/auxiliary',mesa/'src',mesa/'include']
    flags=['-std=c11','-O1','-Wall','-Wextra','-Werror','-Wno-unused-parameter','-Wno-sign-compare','-ffunction-sections','-fdata-sections']
    if sys.platform=='linux':flags+=['-DHAVE_ENDIAN_H']
    if os.environ.get('OPENGPU_TEST_SANITIZERS')=='1':flags+=['-fsanitize=address,undefined','-fno-omit-frame-pointer']
    link=['-Wl,-dead_strip'] if sys.platform=='darwin' else ['-Wl,--gc-sections']
    (ROOT/'.artifacts').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='tess-emit-',dir=ROOT/'.artifacts') as tmp:
        out=Path(tmp);binary=out/'test'
        units=[ROOT/'userspace/tests/tessellation_uniform_emit_test.c',ROOT/'userspace/gallium/opengpu_native_tess_uniform_emit.c',ROOT/'userspace/gallium/opengpu_native_uniform_emit.c',ROOT/'userspace/gallium/opengpu_native_fragment_v2.c',
            mesa/'src/gallium/auxiliary/tgsi/tgsi_build.c',mesa/'src/gallium/auxiliary/tgsi/tgsi_parse.c']
        subprocess.run(shlex.split(os.environ.get('CC','cc'))+flags+['-I'+str(p) for p in includes]+list(map(str,units))+link+['-o',str(binary)],
            check=True,env=dict(os.environ,TMPDIR=tmp))
        subprocess.run([str(binary)],check=True,env=dict(os.environ,TMPDIR=tmp))
        if args.language_negative_controls:
            original=(ROOT/'userspace/gallium/opengpu_native_tess_uniform_emit.c').read_text()
            controls={
                'info-permission': [('b->p.layout.owned_tessellation_info!=1','0'),('l->owned_tessellation_info!=1','0')],
                'old-dialect-info-flag': [('&&!l->owned_tessellation_info',''),('l->owned_tessellation_info||','')],
                'selector-is-six': [('scalar(b,10,6);','scalar(b,10,1);')],
                'real-language-read': [('word(b,geo(37,10,10,0));','scalar(b,10,0);')],
                'simt-selector-rematerialization': [('scalar(b,10,6);','if(!b->depth)scalar(b,10,6);')],
            }
            for name,changes in controls.items():
                source=original
                for before,after in changes:
                    assert before in source,(name,before)
                    source=re.sub(r'(?<![A-Za-z0-9_])'+re.escape(before),lambda _:after,source) if before.startswith('l->') else source.replace(before,after)
                mutant=out/(name+'.c');mutant.write_text(source)
                changed=list(units);changed[1]=mutant
                test=out/name
                subprocess.run(shlex.split(os.environ.get('CC','cc'))+flags+['-I'+str(p) for p in includes]+list(map(str,changed))+link+['-o',str(test)],
                    check=True,env=dict(os.environ,TMPDIR=tmp))
                result=subprocess.run([str(test)],capture_output=True,text=True,env=dict(os.environ,TMPDIR=tmp))
                assert result.returncode and re.search(r'assertion.*failed',result.stderr,re.I|re.S),(name,result.stderr)
                print('language emitter semantic negative control rejected:',name)

if __name__=='__main__':main()
