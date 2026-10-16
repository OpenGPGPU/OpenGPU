#!/usr/bin/env python3
"""Run pixel-checked render fixtures on an existing native ARTI model archive.

Uses the runtime shaders and resource layout from a matched RTL qualification
report. The model keeps its desktop resolution; descriptor viewport fields
select a small offscreen render. This does not boot or qualify Debian/Mesa.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def make_fixture(m, pattern='uniform'):
    assert m['pixel_checks_passed'] and m['runtime_shader'] and m['vertex_stage']
    assert 0 < m['side'] <= 64 and m['side'] & (m['side'] - 1) == 0
    assert 0 < m['texture_extent'] <= 64
    data = bytearray(1 << 20)

    def store(address, words):
        for i, word in enumerate(words):
            struct.pack_into('<I', data, address + 4 * i, word & 0xffffffff)

    extent = m['texture_extent']
    stride = 4 * m['lanes'] * m['warps']
    side = m['side']
    store(m['program'], [int(w, 16) for w in m['shader_words']])
    store(m['vertex_program'], [int(w, 16) for w in m['vertex_shader_words']])
    positions = [[-65536, -65536, 0, 65536], [65536, -65536, 0, 65536],
                 [-65536, 65536, 0, 65536]]
    for v in range(3):
        store(m['vertex_buffer'] + v * 32, positions[v] + [
            [0xff0000ff, 0x00ff00ff, 0x0000ffff][v], [0, 4096, 2048][v],
            65536 if v == 1 else 0, 65536 if v == 2 else 0])
    record = ([m['vertex_buffer'], 3, 32, m['vertex_program'], m['vertex_kernarg'],
               m['vertex_bank_stride'], 0] + [0] * 17 + [m['program'], m['kernarg']]
              + [0] * 8 + [m['bank_stride']] + [0] * 5)
    assert len(record) * 4 == m['command_bytes']
    store(m['commands'], record)
    store(m['descriptor'], [1 << 16, m['commands'], m['color'], m['depth'],
          side * 4, 0x81, m['texture'], extent | (extent << 16), 0x101,
          0, 0, 0, 0, 0, side, side])
    if pattern == 'uniform':
        texels = [0x115395ff] * (extent * extent)
    else:
        texels = [(((37 * x + 13 * y + 7) & 255) << 24) |
                  (((3 * x + 59 * y + 17) & 255) << 16) |
                  (((x * x + 11 * y + (x ^ y) * 23) & 255) << 8) |
                  (128 + ((x + 3 * y) & 127))
                  for y in range(extent) for x in range(extent)]
    store(m['texture'], texels)
    store(m['color'], [0x12345678] * (side * side))
    store(m['depth'], [0x00ffffff] * (side * side))
    for bank in range(m['bank_count']):
        store(m['kernarg'] + bank * m['bank_stride'] + 9 * stride,
              [0x13579bdf] * ((m['bank_stride'] - 9 * stride) // 4))
    return bytes(data)


def verify(m, initial, data):
    assert len(initial) == len(data) == 1 << 20

    def word(address):
        return struct.unpack_from('<I', data, address)[0]

    side = m['side']
    stride = 4 * m['lanes'] * m['warps']

    def sampled_color(x, y):
        # Identity VS and equal clip W give exact affine Q16 UVs on this
        # power-of-two viewport. Apply half-texel bias, CLAMP and the independent
        # integer four-tap reference, including alpha and truncation.
        extent = m['texture_extent']
        bx = (((x << 16) // side) * extent >> 8) - 128
        by = (((y << 16) // side) * extent >> 8) - 128
        fx, fy = bx & 255, by & 255
        xs = [max(0, min(extent - 1, (bx >> 8) + i)) for i in range(2)]
        ys = [max(0, min(extent - 1, (by >> 8) + i)) for i in range(2)]
        taps = [struct.unpack_from('<I', initial, m['texture'] + 4 * (yy * extent + xx))[0]
                for yy in ys for xx in xs]
        weights = [(256 - fx) * (256 - fy), fx * (256 - fy), (256 - fx) * fy, fx * fy]
        result = 0
        for shift in (24, 16, 8, 0):
            channel = sum(((tap >> shift) & 255) * weight for tap, weight in zip(taps, weights)) >> 16
            result |= channel << shift
        return result

    for y in range(side):
        for x in range(side):
            offset = 4 * (y * side + x)
            covered = x > 0 and y > 0 and x + y <= side
            color = sampled_color(x, y) if covered else 0x12345678
            depth = 4096 * x // side + 2048 * y // side if covered else 0x00ffffff
            assert word(m['color'] + offset) == color, ('color', x, y)
            assert word(m['depth'] + offset) == depth, ('depth', x, y)
    allowed = [(m['color'], m['color'] + 4 * side * side),
               (m['depth'], m['depth'] + 4 * side * side),
               (m['vertex_kernarg'], m['vertex_kernarg'] + 2 * m['vertex_bank_stride'])]
    for bank in range(m['bank_count']):
        base = m['kernarg'] + bank * m['bank_stride']
        allowed.append((base, base + 9 * stride))
        for w in range((m['bank_stride'] - 9 * stride) // 4):
            assert word(base + 9 * stride + 4 * w) == 0x13579bdf, ('staging canary', bank, w)
    cursor = 0
    for lo, hi in sorted(allowed) + [(len(data), len(data))]:
        assert lo >= cursor and hi >= lo
        assert data[cursor:lo] == initial[cursor:lo], ('unexpected memory modification', cursor, lo)
        cursor = hi
    return dict(pixel_depth_pairs=side * side, staging_canaries=True,
                all_other_memory_unchanged=True,
                output_sha256=hashlib.sha256(data).hexdigest())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', type=Path, required=True, help='generated native model directory')
    parser.add_argument('--archive', type=Path, required=True, help='compiled libarti_rtl_model.a')
    parser.add_argument('--report', type=Path, required=True, help='matched RTL report with main_scenes')
    parser.add_argument('--cus', type=int, default=4)
    parser.add_argument('--cases', default='solid,spatial')
    parser.add_argument('--pattern', choices=('uniform', 'gradient'), default='uniform',
                        help='vary RGBA texels to exercise changing sampler and shader operands')
    parser.add_argument('--repeat', type=int, default=1)
    parser.add_argument('--timeout', type=float, default=300)
    parser.add_argument('--profile-runtime', action='store_true', help='link an instrumented training archive')
    parser.add_argument('--output', type=Path, required=True, help='fresh evidence directory')
    args = parser.parse_args()
    if args.repeat < 1 or args.timeout <= 0:
        parser.error('repeat and timeout must be positive')
    args.model = args.model.resolve()
    args.archive = args.archive.resolve()
    report = json.loads(args.report.read_text())
    archive_manifest = json.loads((args.archive.parent / 'manifest.json').read_text())
    assert 'finished' in archive_manifest
    assert sha(args.archive) == archive_manifest['archive_sha256']
    assert sha(args.model / 'dut.h') == archive_manifest['header_sha256']
    layouts = {}
    for scene in args.cases.split(','):
        layouts[scene] = next(t['configuration'] for t in report['main_scenes'][scene]['trace']
                              if t['cus'] == args.cus)
    args.output.mkdir(parents=True, exist_ok=False)
    source = ROOT / 'scripts/native_render_fixture.cpp'
    state = dict(started=datetime.now(timezone.utc).isoformat(), model=str(args.model),
                 archive_sha256=sha(args.archive), header_sha256=sha(args.model / 'dut.h'),
                 report_sha256=sha(args.report), runner_sha256=sha(source), texture_pattern=args.pattern,
                 scope='Bare native rendering with explicit small offscreen viewport; no guest boot. '
                       'MMIO submit may settle the entire job; ticks include all API-driven model work. '
                       'Compare host times with paired runs on an otherwise idle host.', cases=[])

    def save():
        (args.output / 'results.json').write_text(json.dumps(state, indent=2) + '\n')

    save()
    executable = (args.output / 'render').resolve()
    command = shlex.split(os.environ.get('CXX', 'clang++')) + ['-std=c++17', '-O2',
               '-I' + str(args.model), '-I' + str(ROOT / 'driver'), str(source),
               str(args.archive), '-o', str(executable)]
    if args.profile_runtime:
        command.append('-fprofile-instr-generate')
    with (args.output / 'compile.log').open('w') as log:
        subprocess.run(command, check=True, stdout=log, stderr=subprocess.STDOUT)
    for scene, m in layouts.items():
        initial = make_fixture(m, args.pattern)
        fixture = (args.output / (scene + '.bin')).resolve()
        fixture.write_bytes(initial)
        expected_config = m['lanes'] | m['warps'] << 8 | m['bank_count'] << 16 | m['cus'] << 24
        for trial in range(args.repeat):
            name = f'{scene}-{trial}'
            output = (args.output / (name + '.bin')).resolve()
            env = {k: v for k, v in os.environ.items() if not k.startswith(('ARTI_', 'LLVM_PROFILE_'))}
            env['LLVM_PROFILE_FILE'] = str((args.output / (name + '-%p.profraw')).resolve())
            with (args.output / (name + '.json')).open('w') as log, (args.output / (name + '.stderr')).open('w') as err:
                subprocess.run([str(executable), str(fixture), str(output), hex(expected_config)],
                               check=True, stdout=log, stderr=err, env=env, timeout=args.timeout)
            result = json.loads((args.output / (name + '.json')).read_text())
            assert result['passed'] and result['shader_config'] == expected_config
            result.update(verify(m, initial, output.read_bytes()))
            result.update(scene=scene, trial=trial, fixture_sha256=hashlib.sha256(initial).hexdigest())
            state['cases'].append(result)
            save()
            print(json.dumps(result), flush=True)
    state['passed'] = True
    state['finished'] = datetime.now(timezone.utc).isoformat()
    save()


if __name__ == '__main__':
    main()
