#!/usr/bin/env python3
"""Qualify the complete shared-bank 96-word owner host without donor relabeling."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

import prepare_native_owner_flashsim as owner
import qualify_ppa as ppa
from report_ppa_sram_inventory import inventory

ROOT = Path(__file__).resolve().parents[1]
SRAM_COMMIT = '9f5af0939e8dd3cc1a9693a50b23441691dd7d25'
MACROS = {'srambank_64x4x32_6t122': 752, 'srambank_64x4x64_6t122': 992}


def tool_inputs(owner_root):
    chipagent = ppa.ROOT.parent / 'chipagent'
    chipagent = Path(os.environ.get('CHIPAGENT_DIR', chipagent)).resolve()
    paths = [Path(__file__), Path(ppa.__file__),
             ROOT / 'scripts/report_ppa_sram_inventory.py', ROOT / 'scripts/extract_emitted_rtl.py']
    files = subprocess.check_output(['git', 'ls-files', '--cached', '--others',
                                    '--exclude-standard', '*.py'], cwd=chipagent, text=True).splitlines()
    if not files:
        raise RuntimeError('Missing ChipAgent tool sources')
    paths += [chipagent / name for name in files]
    paths += [path for path in (ROOT / 'timing').rglob('*') if path.is_file()]
    paths += [owner_root / 'scripts' / name for name in
              ('prepare_native_owner_flashsim.py', 'qualify_native_product_compiled.py',
               'qualify_native_product_flashsim.py')]
    sram = owner_root / 'depends/asap7_sram_0p0'
    if subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=sram, text=True).strip() != SRAM_COMMIT:
        raise RuntimeError('Owner physical SRAM revision differs from the pinned collateral')
    physical_files = [f'generated/{directory}/{master}.{extension}' for master in MACROS
                      for directory, extension in (('LIB', 'lib'), ('LEF', 'lef'))]
    if subprocess.check_output(['git', 'status', '--porcelain', '--', *physical_files], cwd=sram, text=True).strip():
        raise RuntimeError('Owner physical SRAM collateral has unpublished modifications')
    for master in MACROS:
        paths += [owner_root / f'depends/asap7_sram_0p0/generated/{directory}/{master}.{extension}'
                  for directory, extension in (('LIB', 'lib'), ('LEF', 'lef'))]
    return {str(path.resolve()): ppa.sha(path) for path in paths}


def owner_prepared(owner_root, prepared):
    command = [sys.executable, '-B', str(owner_root / 'scripts/prepare_native_owner_flashsim.py'),
               '--stage', 'check', '--output', str(prepared)]
    result = subprocess.run(command, cwd=owner_root, capture_output=True, text=True, check=True,
                            env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'))
    expected = 'NATIVE_OWNER_HOST_INPUTS_PASS profile=' + owner.PROFILE + ' gpuExecution=false clockQualified=false fpsQualified=false'
    if result.stdout.splitlines().count(expected) != 1:
        raise RuntimeError('Owner source checkout did not confirm complete protected host inputs')
    return json.loads((prepared / 'preparation.json').read_text())


def retire_gate(output):
    for name in ('qualification.json', 'owner-physical-qualification.json'):
        path = output / name
        if path.is_file():
            path.unlink()


def qualify(prepared, output, arguments, owner_root=ROOT):
    prepared, output, owner_root = prepared.resolve(), output.resolve(), owner_root.resolve()
    if not output.is_relative_to(ROOT / '.artifacts') or output.exists():
        raise RuntimeError('Physical output must be a new directory inside this checkout .artifacts')
    before = owner_prepared(owner_root, prepared)
    if dict(inventory(prepared / 'rtl', 'GpuHostSystemAxi')) != MACROS:
        raise RuntimeError('Owner shared-bank physical SRAM contract differs from 752/992')
    tools = tool_inputs(owner_root)
    try:
        ppa.main([str(prepared / 'rtl'), 'GpuHostSystemAxi', '--clock', 'io_s_axi_aclk',
                  '--io-delay', '0.25', '--output', str(output),
                  '--sram-root', str(owner_root / 'depends/asap7_sram_0p0/generated'),
                  *[item for name, count in MACROS.items()
                    for item in ('--expected-sram-macro', f'{name}={count}')], *arguments])
    except BaseException:
        retire_gate(output)
        raise
    finally:
        try:
            if owner_prepared(owner_root, prepared) != before or tool_inputs(owner_root) != tools:
                raise RuntimeError('Owner source, RTL or physical tools changed during qualification')
        except BaseException:
            retire_gate(output)
            raise
    gate = json.loads((output / 'qualification.json').read_text())
    if gate.get('passed') is not True:
        raise RuntimeError('Physical qualification did not pass')
    binding = dict(profile=owner.PROFILE, owner_root=str(owner_root), preparation_sha256=ppa.sha(prepared / 'preparation.json'),
                   source_and_tools_unchanged=True, physical_sram_contract=MACROS, sram_commit=SRAM_COMMIT,
                   source_scope='DUT from owner_root preparation; this checkout supplies physical tools',
                   physical_gate_sha256=ppa.sha(output / 'qualification.json'),
                   tool_inputs=tools, fpsQualified=False)
    (output / 'owner-physical-qualification.json').write_text(json.dumps(binding, indent=2) + '\n')
    return binding


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--owner-root', type=Path, default=ROOT)
    parser.add_argument('--prepared', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--stage', choices=('synthesis', 'route'), required=True)
    parser.add_argument('--period-ps', type=ppa.positive_picoseconds, default=1000)
    parser.add_argument('--timeout', type=ppa.positive_integer, default=14400)
    parser.add_argument('--docker-cpus', type=ppa.positive_integer, default=2)
    parser.add_argument('--docker-memory-gib', type=ppa.positive_integer, default=12)
    parser.add_argument('--coverage-timeout', type=ppa.positive_integer, default=1800)
    parser.add_argument('--coverage-memory-gib', type=ppa.positive_integer, default=10)
    args = parser.parse_args()
    arguments = ['--stage', args.stage, '--synthesis-engine', 'yosys']
    arguments += ['--yosys-preserve-hierarchy'] if args.stage == 'synthesis' else ['--timing-effort', 'closure']
    for name in ('period_ps', 'timeout', 'docker_cpus', 'docker_memory_gib',
                 'coverage_timeout', 'coverage_memory_gib'):
        arguments += ['--' + name.replace('_', '-'), str(getattr(args, name))]
    qualify(args.prepared, args.output, arguments, args.owner_root)
    print('NATIVE_OWNER_PHYSICAL_PASS profile=' + owner.PROFILE + ' stage=' + args.stage + ' fpsQualified=false')


if __name__ == '__main__':
    main()
