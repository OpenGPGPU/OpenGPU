#!/usr/bin/env python3
"""Local-only positive and corruption controls for checkpoint provenance."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from verify_yosys_checkpoint import clean_checkout, git, verify


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-worktree', type=Path, required=True)
    parser.add_argument('--chipagent-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    source, chipagent, output = (path.resolve() for path in (args.source_worktree, args.chipagent_dir, args.output))
    clean_checkout(source, git(source, 'rev-parse', 'HEAD'))
    output.mkdir(parents=True, exist_ok=False)
    rtl = source / 'timing/tests/partition_probe'
    baseline = output / 'baseline'
    env = dict(os.environ, CHIPAGENT_DIR=str(chipagent))
    command = [sys.executable, str(source / 'scripts/qualify_ppa.py'), str(rtl), 'HierarchyProbe',
               '--synthesis-engine', 'yosys', '--yosys-preserve-hierarchy', '--clock', 'clock',
               '--period-ps', '1000', '--io-delay', '.25', '--timeout', '180', '--output', str(baseline)]
    with (output / 'baseline.log').open('w') as stream:
        proc = subprocess.run(command, cwd=source, env=env, stdout=stream, stderr=subprocess.STDOUT)
    if proc.returncode:
        raise RuntimeError('Baseline synthesis failed; inspect ' + str(output / 'baseline.log'))
    positive = verify(baseline, rtl, source, chipagent)
    if positive['physical_qualified'] or positive['reachable_definitions'] != 2:
        raise RuntimeError('Unexpected provenance scope or fixture hierarchy')

    cases = [('rtl', 'Emitted RTL bytes differ'), ('sdc', 'Mapping configuration or timing constraints differ'),
             ('checkpoint', 'Canonical checkpoint differs from the run log'),
             ('profile', 'Unsafe recorded hierarchy profile'),
             ('flowhash', 'Complete ChipAgent flow input hash differs'),
             ('extra-hdl', 'Unexpected input files'), ('extra-lib', 'Unexpected input files')]
    for name, expected in cases:
        current = output / name
        saved, copy_rtl = current / 'saved', current / 'rtl'
        shutil.copytree(baseline, saved)
        shutil.copytree(rtl, copy_rtl)
        if name == 'rtl':
            path = copy_rtl / 'Leaf.sv'
            data = path.read_text()
            if data.count("16'h35") != 1:
                raise RuntimeError('Unexpected arithmetic fixture')
            path.write_text(data.replace("16'h35", "16'h34"))
        elif name == 'sdc':
            path = saved / 'flow/orfs-work/designs/asap7/HierarchyProbe/constraint.sdc'
            path.write_text(path.read_text() + '\nset_false_path -from [all_inputs]\n')
        elif name == 'checkpoint':
            path = saved / 'flow/orfs-work/results/base/1_1_yosys_canonicalize.rtlil'
            with path.open('ab') as stream:
                stream.write(b'\n')
        elif name == 'profile':
            path = saved / 'manifest.json'
            data = json.loads(path.read_text())
            data['hierarchy_profile']['orfs_environment']['SYNTH_BLACKBOXES'] = 'Leaf'
            path.write_text(json.dumps(data, indent=2) + '\n')
        elif name == 'flowhash':
            path = saved / 'flow/flow_manifest.json'
            data = json.loads(path.read_text())
            data['input_hash'] = '0' * 64
            path.write_text(json.dumps(data, indent=2) + '\n')
        elif name == 'extra-hdl':
            (saved / 'flow/orfs-work/src/HierarchyProbe/Extra.sv').write_text('module Extra(); endmodule\n')
        else:
            path = saved / 'flow/orfs-work/designs/asap7/HierarchyProbe/macros/extra.lib'
            path.parent.mkdir(exist_ok=True)
            path.write_text('unrecorded library\n')
        try:
            verify(saved, copy_rtl, source, chipagent)
        except ValueError as error:
            if expected not in str(error):
                raise
        else:
            raise RuntimeError('Corruption was not rejected: ' + name)
        print(name + ': rejected', flush=True)
    result = dict(passed=True, positive_scope=positive['scope'], rejected=[name for name, _ in cases],
                  physical_qualified=False,
                  scope='Small provenance control only; no GPU host timing or product qualification')
    (output / 'control.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
