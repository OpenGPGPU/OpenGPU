#!/usr/bin/env python3
"""Small real-Mesa parser/emitter CPU guards; no Mesa build, VM or GPU."""
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
ROOT=Path(__file__).resolve().parents[1]
def main():
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
        units=[ROOT/'userspace/tests/tessellation_uniform_emit_test.c',ROOT/'userspace/gallium/opengpu_native_tess_uniform_emit.c',ROOT/'userspace/gallium/opengpu_native_uniform_emit.c',
            mesa/'src/gallium/auxiliary/tgsi/tgsi_build.c',mesa/'src/gallium/auxiliary/tgsi/tgsi_parse.c']
        subprocess.run(shlex.split(os.environ.get('CC','cc'))+flags+['-I'+str(p) for p in includes]+list(map(str,units))+link+['-o',str(binary)],
            check=True,env=dict(os.environ,TMPDIR=tmp))
        subprocess.run([str(binary)],check=True,env=dict(os.environ,TMPDIR=tmp))
if __name__=='__main__':main()
