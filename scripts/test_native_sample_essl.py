#!/usr/bin/env python3
"""Private actual Mesa ESSL310 -> native ABI6/7 compiler CPU qualification.

Uses existing immutable Mesa 22.3.6 source/static libraries; no device screen,
RTL generation, shared Mesa edits or GPU execution. Run in a bounded container.
"""
import argparse
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--build', type=Path, required=True)
    ap.add_argument('--artifacts', type=Path, required=True)
    ap.add_argument('--sanitize', action='store_true')
    args = ap.parse_args()
    out = args.build.resolve()
    out.mkdir(parents=True, exist_ok=False)
    artifacts = args.artifacts.resolve()
    artifacts.mkdir(parents=True, exist_ok=False)
    mesa = Path(os.environ.get('OPENGPU_MESA_DIR', ROOT / 'depends/mesa')).resolve()
    mb = Path(os.environ.get('OPENGPU_MESA_BUILD', ROOT / 'depends/mesa-build')).resolve()
    if (mesa / 'VERSION').read_text().strip() != '22.3.6':
        raise RuntimeError('original pinned Mesa 22.3.6 required')
    database = json.loads((mb / 'compile_commands.json').read_text())
    cc, cxx = (shlex.split(os.environ.get(k, d)) for k, d in (('CC', 'cc'), ('CXX', 'c++')))
    san = ['-fsanitize=address,undefined', '-fno-omit-frame-pointer'] if args.sanitize else []

    def flags(relative):
        source = mesa / 'src' / relative
        entry = next(e for e in database if (Path(e['directory']) / e['file']).resolve() == source)
        raw = entry.get('arguments') or shlex.split(entry['command'])
        result, skip = [], False
        for flag in raw[1:]:
            if skip:
                skip = False
                continue
            if flag in ('-o', '-MF', '-MQ', '-MT'):
                skip = True
                continue
            if flag in ('-c', '-MD', '-MMD'):
                continue
            if not flag.startswith('-') and (Path(entry['directory']) / flag).resolve() == source:
                continue
            if flag.startswith('-I') and not Path(flag[2:]).is_absolute():
                flag = '-I' + str((Path(entry['directory']) / flag[2:]).resolve())
            result.append(flag)
        return result

    def run(argv, label, expected=0):
        with (out / (label + '.log')).open('w') as log:
            r = subprocess.run(list(map(str, argv)), cwd=out, stdout=log, stderr=subprocess.STDOUT)
        if r.returncode != expected:
            raise RuntimeError(label + ': exit ' + str(r.returncode) + '\n' + (out / (label + '.log')).read_text()[-6000:])

    standalone = (mesa / 'src/compiler/glsl/standalone.cpp').read_text()
    anchor = '   if (options->lower_precision) {'
    assert standalone.count(anchor) == 1
    standalone = standalone.replace(anchor, '   opengpu_frontend_compiler_limits(ctx);\n\n' + anchor)
    # Same GL_PROGRAM_SEPARABLE intent as a real independently linked FS:
    # fragment-only compilation must not invent a matching vertex shader.
    anchor = '   whole_program->data->InfoLog = ralloc_strdup(whole_program->data, "");'
    assert standalone.count(anchor) == 1
    standalone = standalone.replace(anchor, anchor + '\n   whole_program->SeparateShader = true;')
    private = out / 'standalone-native-fs.cpp'
    private.write_text('#include "main/mtypes.h"\nextern void opengpu_frontend_compiler_limits(gl_context *);\n' + standalone)
    units = ['compiler/glsl/standalone.cpp', 'compiler/glsl/standalone_scaffolding.cpp',
             'compiler/glsl/ir_builder_print_visitor.cpp', 'compiler/glsl/glcpp/pp_standalone_scaffolding.c',
             'mesa/main/extensions_table.c', 'mesa/program/prog_parameter.c',
             'mesa/program/symbol_table.c', 'mesa/program/dummy_errors.c']
    objects = []
    for relative in units:
        obj = out / (relative.replace('/', '_') + '.o')
        run((cxx if relative.endswith('.cpp') else cc) + flags(relative) + ['-UNDEBUG'] + san +
            ['-c', private if relative.endswith('/standalone.cpp') else mesa / 'src' / relative, '-o', obj], obj.stem)
        objects.append(obj)
    inc = ['-I' + str(p) for p in (ROOT / 'userspace/tests', ROOT / 'userspace/gallium', ROOT / 'driver',
        mesa / 'src/gallium/include', mesa / 'src/gallium/auxiliary', mesa / 'src', mesa / 'include',
        mesa / 'src/compiler/nir', mb / 'src/compiler/nir')]
    for relative in ('userspace/tests/fs_glsl_frontend.cpp', 'userspace/tests/native_fs_compile.c',
                     'userspace/gallium/opengpu_native_fs_emit.c'):
        obj = out / (Path(relative).stem + '.o')
        opts = flags('compiler/glsl/standalone.cpp') if relative.endswith('.cpp') else ['-std=c11', '-O1', '-DHAVE_ENDIAN_H', '-D_POSIX_C_SOURCE=200809L']
        run((cxx if relative.endswith('.cpp') else cc) + opts + ['-UNDEBUG', '-Wall', '-Wextra', '-Werror',
            '-Wno-unused-parameter', '-ffunction-sections', '-fdata-sections'] + inc + san +
            ['-c', ROOT / relative, '-o', obj], obj.stem)
        objects.append(obj)
    libraries = [mb / p for p in ('src/compiler/glsl/libglsl.a', 'src/compiler/nir/libnir.a', 'src/compiler/libcompiler.a',
        'src/compiler/glsl/glcpp/libglcpp.a', 'src/gallium/auxiliary/libgallium.a', 'src/util/libmesa_util.a',
        'src/util/format/libmesa_format.a', 'src/c11/impl/libmesa_util_c11.a')]
    binary = out / 'native-fs-compile'
    run(cxx + san + ['-Wl,--gc-sections'] + objects + ['-Wl,--start-group'] + libraries + ['-Wl,--end-group'] +
        shlex.split(os.environ.get('OPENGPU_MESA_LINK_LIBS', '-lz -lexpat')) + ['-ldl', '-lm', '-pthread', '-o', binary], 'link')
    prefix = '#version 310 es\n#extension GL_OES_sample_variables : require\nprecision highp float;\nprecision highp int;\n'
    expected = {'sample': (7, 1, 0, 0), 'mask-depth': (7, 1, 1, 1), 'mrt': (3, 15, 0, 0),
                'varying': (18, 8, 0, 0), 'signedness': (5, 1, 0, 0), 'truncation': (3, 1, 0, 0)}
    for name in expected:
        source = ROOT / 'userspace/tests/native_sample_essl' / (name + '.frag')
        for version in (6, 7):
            output = artifacts / (name + '-abi' + str(version) + '.ognsf')
            run([binary, source, output, version], name + '-abi' + str(version))
            data = output.read_bytes()
            assert data[:8] == b'OGNSF001'
            fields = struct.unpack_from('<15I', data, 8)
            assert fields[:5] == (1, version, 75 if version == 6 else 77, 512, (93 if version == 6 else 95) * 512)
            assert fields[5:9] == expected[name], (name, fields)
            assert fields[9:14] == (1, int(version == 7), 128, 16, 4)
            assert 0 < fields[14] <= 512 and len(data) == 68 + fields[14] * 4
            from native_fs_cpu_oracle import verify
            for samples in (1, 2, 4):
                verify(output, name, samples)
    for name, body in {
        'uniform': 'uniform float u;layout(location=0) out vec4 color;void main(){color=vec4(u);}',
        'texture': 'uniform sampler2D tex;layout(location=0) out vec4 color;void main(){color=texture(tex,gl_SamplePosition);}',
        'flow': 'layout(location=0) out vec4 color;void main(){if(gl_SampleID==0)discard;color=vec4(1.0);}',
        'flat': 'layout(location=0) flat in vec4 v;layout(location=0) out vec4 color;void main(){color=v;}',
        'over-budget': 'layout(location=0) out vec4 color;void main(){uint v=uint(gl_SampleID);' +
                       'v=(v<<1u)^uint(gl_SampleMaskIn[0]);' * 200 + 'color=vec4(float(v));}',
    }.items():
        source = out / (name + '.frag');source.write_text(prefix + body)
        # Resource frontend refuses before TGSI; unsupported flow reaches the
        # native emitter and fails. Neither writes a qualification artifact.
        output = artifacts / (name + '.ognsf')
        run([binary, source, output, 6], 'reject-' + name, 4 if name in ('flow', 'flat', 'over-budget') else 3)
        assert not output.exists()
    print('NATIVE_SAMPLE_ESSL_CPU PASS actualMesa=22.3.6 programs=12 ABI=6,7 oracleCases=36 capacities=128/16/4 gpuExecution=false')


if __name__ == '__main__':
    main()
