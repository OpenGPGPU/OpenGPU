#!/usr/bin/env python3
"""Local-only two-module control for checkpointed and resumed ABC mapping.

Requires ChipAgent and its OpenROAD Docker image. This checks a small control,
not GPU host timing, product execution, or the inputs of a different run.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

from prepare_yosys_abc_checkpoint import prepare
from test_split_yosys_integration import FLOW, ROOT, TOP, docker, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    control = output / 'control'
    run(output, 'partition-control', [sys.executable, str(ROOT / 'scripts/test_split_yosys_integration.py'),
                                      '--output', str(control)])
    control_result = json.loads((control / 'control.json').read_text())
    if not control_result['passed'] or not control_result['changed_constant_rejected']:
        raise RuntimeError('Partition equivalence and timing control did not pass')
    image = control_result['image']
    original = output / 'synth.tcl'
    with original.open('wb') as stream:
        subprocess.run(['docker', 'run', '--rm', '--cpus=0.3', '--memory=512m', '--entrypoint', 'cat',
                        image, FLOW + '/scripts/synth.tcl'], stdout=stream, check=True)
    scripts = output / 'scripts'
    prepare(original, scripts)
    baseline = control / 'mapped' / TOP

    def setup(name, script):
        directory = output / name
        for subdir in ('src', 'designs', 'logs', 'reports', 'results', 'objects'):
            (directory / subdir).mkdir(parents=True)
        shutil.copy2(baseline / 'src/design.sv', directory / 'src/design.sv')
        for filename in ('config.mk', 'constraint.sdc', 'partition.rtlil'):
            shutil.copy2(baseline / 'designs' / filename, directory / 'designs' / filename)
        shutil.copy2(scripts / (script + '-synth.tcl'), directory / 'designs/synth.tcl')
        with (directory / 'designs/config.mk').open('a') as config:
            config.write(f'\nexport SYNTH_SCRIPT = {FLOW}/designs/asap7/{TOP}/synth.tcl\n')
        (directory / 'results/base').mkdir()
        return directory

    shell = (f'source /OpenROAD-flow-scripts/env.sh >/dev/null && '
             f'make DESIGN_CONFIG=./designs/asap7/{TOP}/config.mk do-yosys')
    first = setup('checkpoint', 'checkpoint')
    run(output, 'checkpoint', docker(image, first, TOP, 'bash', '-lc', shell))
    saved = first / 'results/base/pre_abc.rtlil'
    sidecar = first / 'results/base/pre_abc.rtlil.sha256'
    checksum = hashlib.sha256(saved.read_bytes()).hexdigest()
    if sidecar.read_text().strip() != checksum or 'OPENGPU_PRE_ABC_COMPLETE ' + checksum not in (output / 'checkpoint.log').read_text():
        raise RuntimeError('Checkpoint was not fully saved')

    def resume_setup(name):
        directory = setup(name, 'resume')
        for path in (saved, sidecar):
            shutil.copy2(path, directory / 'results/base' / path.name)
        return directory

    second = resume_setup('resume')
    run(output, 'resume', docker(image, second, TOP, 'bash', '-lc', shell))
    mapped = (first / 'results/base/1_2_yosys.v').read_bytes()
    resumed = (second / 'results/base/1_2_yosys.v').read_bytes()
    linked = (baseline / 'results/base/1_2_yosys.v').read_bytes()
    if mapped != resumed or not linked.startswith(resumed + b'\n'):
        raise RuntimeError('Checkpointed or resumed mapping differs from the linked reference')
    if 'Found and reported 0 problems' not in (second / 'reports/base/synth_check.txt').read_text():
        raise RuntimeError('Resumed mapped design check failed')

    for name, corruption, message in (
        ('corrupt', 'checkpoint', 'pre-ABC checkpoint checksum mismatch'),
        ('incomplete', 'sidecar', 'missing or incomplete pre-ABC checkpoint'),
    ):
        directory = resume_setup(name)
        if corruption == 'checkpoint':
            with (directory / 'results/base/pre_abc.rtlil').open('ab') as stream:
                stream.write(b'\n')
        else:
            (directory / 'results/base/pre_abc.rtlil.sha256.tmp').touch()
        run(output, name, docker(image, directory, TOP, 'bash', '-lc', shell), expected=2)
        if message not in (output / (name + '.log')).read_text() or (directory / 'results/base/1_2_yosys.v').exists():
            raise RuntimeError(name + ' checkpoint was not rejected before mapping')

    wrapped = resume_setup('wrapped')
    with (wrapped / 'designs/config.mk').open('a') as config:
        config.write('\nexport SYNTH_WRAPPED_OPERATORS = 1\n')
    run(output, 'wrapped', docker(image, wrapped, TOP, 'bash', '-lc', shell), expected=2)
    if ('pre-ABC checkpoints require the standard ABC flow' not in (output / 'wrapped.log').read_text() or
            (wrapped / 'results/base/1_2_yosys.v').exists()):
        raise RuntimeError('Wrapped-operator resume was not rejected')

    stale = setup('stale', 'checkpoint')
    (stale / 'results/base/pre_abc.rtlil.sha256.tmp').touch()
    run(output, 'stale', docker(image, stale, TOP, 'bash', '-lc', shell), expected=2)
    if ('pre-ABC checkpoint already exists or is incomplete' not in (output / 'stale.log').read_text() or
            (stale / 'results/base/pre_abc.rtlil').exists()):
        raise RuntimeError('Stale checkpoint was not rejected before writing')

    result = dict(passed=True, image=image, linked_setup_slacks_ps=control_result['linked_setup_slacks_ps'],
                  cross_module_setup_slacks_ps=control_result['cross_module_setup_slacks_ps'],
                  changed_constant_rejected=True, corrupt_checkpoint_rejected=True, incomplete_checkpoint_rejected=True,
                  wrapped_operators_rejected=True, stale_checkpoint_rejected=True,
                  scope='Two-module control only; no host, product or FPS qualification')
    (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
