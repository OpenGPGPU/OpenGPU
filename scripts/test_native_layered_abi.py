#!/usr/bin/env python3
"""Check published GNJ2/GNF3/GNY2 words against the compiled C validator."""
import ctypes
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    schemas = json.loads((ROOT / 'docs/abi/native-layered-graphics-v2.json').read_text())
    compiler = shlex.split(os.environ.get('CC', 'cc'))
    temporary_root = ROOT / '.artifacts'
    temporary_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='l', dir=temporary_root) as temporary:
        out = Path(temporary)
        environment = dict(os.environ, TMPDIR=temporary)
        source = out / 'abi.c'
        source.write_text('''#include "opengpu_native_layered_abi.h"
_Static_assert(sizeof(struct opengpu_native_layered_job)==512,"GNJ2");
_Static_assert(sizeof(struct opengpu_native_layered_frame)==2048,"GNF3");
_Static_assert(sizeof(struct opengpu_native_layered_completion)==16384,"GNY2");
_Static_assert(OPENGPU_NATIVE_LAYERED_JOB_COMPLETION_BASE_WORD==48,"completion base");
_Static_assert(OPENGPU_NATIVE_LAYERED_JOB_COMPLETION_LIMIT_WORD==49,"completion limit33");
_Static_assert(OPENGPU_NATIVE_LAYERED_FRAME_LAYERED_VERSION_WORD==320,"layer extension");
_Static_assert(OPENGPU_NATIVE_LAYERED_FRAME_LAYERED_SOURCEPITCH0_WORD==322,"source layers");
_Static_assert(OPENGPU_NATIVE_LAYERED_FRAME_LAYERED_RESOLVEPITCH5_WORD==344,"resolve layers");
_Static_assert(OPENGPU_NATIVE_LAYERED_COMPLETION_FRAME_RECEIVEDPARTS_BITS==145,"full GS parts");
_Static_assert(OPENGPU_NATIVE_LAYERED_COMPLETION_FRAME_DROPPEDPARTS_BITS==145,"full GS drops");
_Static_assert(OPENGPU_NATIVE_LAYERED_COMPLETION_FRAME_COMPLETEDGEOMETRYINVOCATIONS_BITS==134,"full GS invocations");
_Static_assert(OPENGPU_NATIVE_LAYERED_COMPLETION_FRAME_IMAGES_IMAGES_SCANNED_BITS==64,"all scans");
int validate(unsigned k,const opengpu_native_layered_word *w,unsigned n) {
    return opengpu_native_layered_wire_valid(k,w,n);
}
''')
        flags = ['-std=c11', '-O2', '-Wall', '-Wextra', '-Werror', '-I' + str(ROOT / 'driver')]
        subprocess.run(compiler + flags + ['-D__KERNEL__', '-nostdinc', '-fsyntax-only', str(source)],
                       check=True, env=environment)
        library = out / 'abi.so'
        subprocess.run(compiler + flags + (['-dynamiclib'] if sys.platform == 'darwin' else ['-shared']) +
                       ['-fPIC', str(source), '-o', str(library)], check=True, env=environment)
        api = ctypes.CDLL(str(library)).validate
        api.argtypes = [ctypes.c_uint, ctypes.POINTER(ctypes.c_uint32), ctypes.c_uint]
        api.restype = ctypes.c_int
        negative = 0
        for kind, name in enumerate(('job', 'frame', 'completion')):
            schema = schemas[name]
            count = schema['words']
            array = ctypes.c_uint32 * count
            words = array()
            words[0] = int(schema['magic'], 16)
            words[1] = schema['bytes']
            words[2] = schema['version']
            occupied = {0, 1, 2}
            for field in schema['fields']:
                width = field['bits']
                length = (width + 31) // 32
                first = field['word']
                assert 3 <= first and first + length <= count
                assert first % 16 + length <= 16
                assert not occupied.intersection(range(first, first + length))
                occupied.update(range(first, first + length))
                # Exercise every bit of wide unsigned fields, and canonical
                # sign-extension for signed fields, independently of C masks.
                value = (1 << (length * 32 if field['signed'] else width)) - 1
                for i in range(length):
                    words[first + i] = (value >> (i * 32)) & 0xffffffff
            assert api(kind, words, count) == 1
            for index in range(count):
                if index not in occupied:
                    words[index] = 1
                    assert api(kind, words, count) == 0, (name, 'reserved', index)
                    words[index] = 0
                    negative += 1
            for field in schema['fields']:
                tail = field['bits'] % 32
                if tail:
                    index = field['word'] + field['bits'] // 32
                    before = words[index]
                    words[index] ^= 1 << tail
                    assert api(kind, words, count) == 0, (name, field['name'], 'padding')
                    words[index] = before
                    negative += 1
            for index in (0, 1, 2):
                before = words[index]
                words[index] ^= 0x80000000
                assert api(kind, words, count) == 0
                words[index] = before
                negative += 1
            assert api(kind, words, count - 1) == 0
            assert api(kind, words, count + 1) == 0
            assert api(kind, None, count) == 0
            assert api(3, words, count) == 0
            negative += 4
        print(f'native layered ABI C/kernel layouts and {negative} malformed encodings: PASS', flush=True)


if __name__ == '__main__':
    main()
