#!/usr/bin/env python3
"""Compare default standalone emissions; retain every quad field and cycle count.

The baseline must be the pre-preparation-pipeline component, and the candidate
must add two setup cycles for points/basic lines and three for MSAA lines.
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
                  build_jobs=args.jobs, cxx_standard='c++17',
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
        frozen = folder / rtl.name
        shutil.copyfile(rtl, frozen)
        assert sha(frozen) == sha(rtl)
        command = ['verilator', '--cc', '--exe', '--build', '-j', str(args.jobs),
                   '--top-module', 'PointLineRasterizer', '-Wno-fatal',
                   '-CFLAGS', '-std=c++17',
                   '--Mdir', str(folder / 'obj'), str(frozen), str(frozen_harness)]
        with (folder / 'build.log').open('x') as log:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
        binary = folder / 'obj' / 'VPointLineRasterizer'
        with (folder / 'results.jsonl').open('x') as results:
            subprocess.run([str(binary)], stdout=results, check=True)
        rows = [json.loads(line) for line in (folder / 'results.jsonl').read_text().splitlines()]
        assert len(rows) == 15
        runs[name] = rows
        report['models'][name] = dict(rtl_path=str(rtl), rtl_sha256=sha(rtl),
                                     executable_sha256=sha(binary),
                                     results_sha256=sha(folder / 'results.jsonl'))
        assert sha(frozen) == sha(rtl), 'RTL changed during benchmark'
    report['comparisons'] = []
    for before, after in zip(runs['baseline'], runs['candidate']):
        key = ('point', 'size_fp32', 'sample_mode')
        assert all(before[k] == after[k] for k in key)
        assert before['quads'] == after['quads'], ('quad mismatch', {k: before[k] for k in key})
        expected = 2 if before['point'] or before['sample_mode'] == 0 else 3
        delta = after['cycles_including_acceptance'] - before['cycles_including_acceptance']
        first_delta = after['first_offer_after_acceptance'] - before['first_offer_after_acceptance']
        assert delta == first_delta == expected, (delta, first_delta, expected)
        report['comparisons'].append({**{k: before[k] for k in key},
            'baseline_cycles': before['cycles_including_acceptance'],
            'candidate_cycles': after['cycles_including_acceptance'],
            'added_setup_cycles': delta, 'quads': len(before['quads']),
            'all_quad_fields_equal': True,
            'output_sha256': hashlib.sha256(json.dumps(before['quads'], separators=(',', ':')).encode()).hexdigest()})
    assert sha(harness) == sha(frozen_harness) == report['harness_sha256']
    assert sha(Path(__file__)) == report['runner_sha256']
    report['passed'] = True
    report['finished_utc'] = datetime.now(timezone.utc).isoformat()
    (output / 'comparison.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(dict(passed=True, cases=15, report=str(output / 'comparison.json'))))


if __name__ == '__main__':
    main()
