#!/usr/bin/env python3
"""Compare default standalone emissions; retain every quad field and cycle count.

The default setup policy requires the pre-preparation-pipeline baseline and
two added setup cycles for points/basic lines or three for MSAA lines. The
report policy measures arbitrary revisions while requiring every output field
to match. Each RTL argument can be a single file or an emitted RTL directory.
This common-domain comparison supplements independent numeric/fault tests.
It neither qualifies extreme inputs nor estimates a whole frame's throughput.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline-rtl', type=Path, required=True)
    parser.add_argument('--candidate-rtl', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--jobs', type=int, default=2)
    parser.add_argument('--cycle-policy', choices=['setup', 'report'], default='setup')
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error('--jobs must be positive')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    harness = Path(__file__).with_name('point_line_cycles.cpp').resolve()
    frozen_harness = output / harness.name
    shutil.copyfile(harness, frozen_harness)
    report = dict(started_utc=datetime.now(timezone.utc).isoformat(),
                  harness_sha256=sha(harness), runner_sha256=sha(Path(__file__)),
                  verilator_version=subprocess.check_output(['verilator', '--version'], text=True).strip(),
                  build_jobs=args.jobs, cxx_standard='c++17', cycle_policy=args.cycle_policy,
                  scope='Standalone default component, 8x8 view, always-ready output; 15 common-domain primitives',
                  cycle_definition='Acceptance edge counts as cycle 1; count through the edge that clears busy. First offer is observed before its accepting edge.',
                  quad_layout='Four lanes each containing raw unsigned x,y,e0,e1,e2,area,covered,coverageMask',
                  inputs=dict(subpixel_bits=8, screen=[8, 8], state_version=1,
                              line_endpoints=[[128, 128], [1792, 1280]],
                              point_center=[896, 1024], scissor=False), models={})
    runs = {}
    for name, rtl in [('baseline', args.baseline_rtl), ('candidate', args.candidate_rtl)]:
        rtl = rtl.resolve()
        folder = output / name
        folder.mkdir()
        if rtl.is_dir():
            filelist = rtl / 'filelist.f'
            sources = ([rtl / line.strip() for line in filelist.read_text().splitlines()
                        if line.strip() and not line.startswith('verification/')]
                       if filelist.exists() else sorted(rtl.glob('*.sv')))
        else:
            sources = [rtl]
        if not sources:
            raise ValueError(f'No RTL files in {rtl}')
        frozen_sources = []
        for source in sources:
            relative = source.relative_to(rtl) if rtl.is_dir() else Path(source.name)
            frozen = folder / relative
            frozen.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, frozen)
            assert sha(frozen) == sha(source)
            frozen_sources.append(frozen)
        top_source = next(source for source in sources if source.name == 'PointLineRasterizer.sv')
        top_text = top_source.read_text()
        defines = []
        # Composed emission can prune ignored ports. Older emissions predate
        # polygon offset. Initialize each optional input only when it exists.
        if 'io_in_bits_state_frontFaceCCW' in top_text:
            defines.append('-DOPENGPU_POINT_LINE_HAS_FRONT_FACE')
        if 'io_in_bits_state_polygonOffsetEnable' in top_text:
            defines.append('-DOPENGPU_POINT_LINE_HAS_POLYGON_OFFSET')
        command = ['verilator', '--cc', '--exe', '--build', '-j', str(args.jobs),
                   '--top-module', 'PointLineRasterizer', '-Wno-fatal',
                   '-CFLAGS', ' '.join(['-std=c++17', *defines]),
                   '--Mdir', str(folder / 'obj'), *map(str, frozen_sources), str(frozen_harness)]
        with (folder / 'build.log').open('x') as log:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
        binary = folder / 'obj' / 'VPointLineRasterizer'
        with (folder / 'results.jsonl').open('x') as results:
            subprocess.run([str(binary)], stdout=results, check=True)
        rows = [json.loads(line) for line in (folder / 'results.jsonl').read_text().splitlines()]
        assert len(rows) == 15
        runs[name] = rows
        report['models'][name] = dict(rtl_path=str(rtl), rtl_sha256=sha(top_source),
                                     rtl_files_sha256={str(p.relative_to(rtl) if rtl.is_dir() else p.name): sha(p)
                                                       for p in sources},
                                     optional_input_defines=defines,
                                     executable_sha256=sha(binary),
                                     results_sha256=sha(folder / 'results.jsonl'))
        assert all(sha(a) == sha(b) for a, b in zip(sources, frozen_sources)), 'RTL changed during benchmark'
    report['comparisons'] = []
    for before, after in zip(runs['baseline'], runs['candidate']):
        key = ('point', 'size_fp32', 'sample_mode')
        assert all(before[k] == after[k] for k in key)
        assert before['quads'] == after['quads'], ('quad mismatch', {k: before[k] for k in key})
        expected = 2 if before['point'] or before['sample_mode'] == 0 else 3
        delta = after['cycles_including_acceptance'] - before['cycles_including_acceptance']
        first_delta = after['first_offer_after_acceptance'] - before['first_offer_after_acceptance']
        if args.cycle_policy == 'setup':
            assert delta == first_delta == expected, (delta, first_delta, expected)
        comparison = {**{k: before[k] for k in key},
            'baseline_cycles': before['cycles_including_acceptance'],
            'candidate_cycles': after['cycles_including_acceptance'],
            'added_cycles': delta, 'first_offer_delta': first_delta, 'quads': len(before['quads']),
            'all_quad_fields_equal': True,
            'output_sha256': hashlib.sha256(json.dumps(before['quads'], separators=(',', ':')).encode()).hexdigest()}
        if args.cycle_policy == 'setup':
            comparison['added_setup_cycles'] = delta
        report['comparisons'].append(comparison)
    assert sha(harness) == sha(frozen_harness) == report['harness_sha256']
    assert sha(Path(__file__)) == report['runner_sha256']
    report['passed'] = True
    report['finished_utc'] = datetime.now(timezone.utc).isoformat()
    (output / 'comparison.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(dict(passed=True, cases=15, report=str(output / 'comparison.json'))))


if __name__ == '__main__':
    main()
