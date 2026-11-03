#!/usr/bin/env python3
"""Local-only ASIC mapping and equivalence control for checkpoint slices.

Requires the ChipAgent Python environment and its OpenROAD Docker image. This
small two-module control does not qualify any GPU host or product timing.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

from qualify_ppa import synthesis_setup_gate
from split_yosys_rtlil import prepare
from check_yosys_partition import check as check_partition

ROOT = Path(__file__).resolve().parents[1]
RTL = ROOT / 'timing/tests/partition_probe'
FLOW = '/OpenROAD-flow-scripts/flow'
TOP = 'HierarchyProbe'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def run(output, stage, command, expected=0):
    with (output / (stage + '.log')).open('w') as stream:
        proc = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
    print(json.dumps(dict(stage=stage, exit_code=proc.returncode)), flush=True)
    if proc.returncode != expected:
        raise RuntimeError(f'{stage} exited {proc.returncode}; inspect {output / (stage + ".log")}')


def mounts(directory, top):
    pairs = [(directory / 'src', f'{FLOW}/designs/src/{top}'),
             (directory / 'designs', f'{FLOW}/designs/asap7/{top}')]
    pairs += [(directory / name, f'{FLOW}/{name}/asap7/{top}')
              for name in ('logs', 'reports', 'results', 'objects')]
    return [arg for host, container in pairs for arg in ('-v', str(host) + ':' + container)]


def docker(image, directory, top, *command):
    return ['docker', 'run', '--rm', '--cpus=1', '--memory=2g', '--memory-swap=2g',
            *mounts(directory, top), '-w', FLOW, image, *command]


def definitions(text):
    return re.findall(r'(?m)^module\s+([^\s(]+)', text)


def assemble(parts, expected):
    modules = [name for text in parts for name in definitions(text)]
    if len(modules) != len(set(modules)) or set(modules) != set(expected):
        raise ValueError('Partition implementations are incomplete or duplicated')
    return '\n'.join(parts)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--rtl', type=Path, default=RTL, help='Small HierarchyProbe fixture directory')
    parser.add_argument('--expect-dead-logic', action='store_true', help='Require dead instances to be removed after linking')
    args = parser.parse_args()
    rtl = args.rtl.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    baseline = output / 'baseline'
    command = [sys.executable, str(ROOT / 'scripts/qualify_ppa.py'), str(rtl), TOP,
               '--synthesis-engine', 'yosys', '--yosys-preserve-hierarchy', '--clock', 'clock',
               '--period-ps', '1000', '--io-delay', '.25', '--timeout', '180', '--output', str(baseline)]
    run(output, 'baseline', command)
    manifest = json.loads((baseline / 'manifest.json').read_text())
    image = manifest['hierarchy_profile']['image_id']
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', image):
        raise RuntimeError('Baseline tool image is not pinned')
    checkpoint = baseline / 'flow/orfs-work/results/base/1_1_yosys_canonicalize.rtlil'
    baseline_work = baseline / 'flow/orfs-work'
    parts = {}
    for top in ('Leaf', TOP):
        partition = output / 'slices' / top
        plan = prepare(rtl, checkpoint, TOP, top, partition)
        if plan['emitted_sha256'] != manifest['emitted_sha256']:
            raise RuntimeError('Baseline emission changed before partition preparation')
        directory = output / 'mapped' / top
        for name in ('src', 'designs', 'logs', 'reports', 'results', 'objects'):
            (directory / name).mkdir(parents=True)
        shutil.copy2(baseline_work / 'src' / TOP / (TOP + '.sv'), directory / 'src/design.sv')
        config = (baseline_work / 'designs/asap7' / TOP / 'config.mk').read_text()
        if config.count('export DESIGN_NAME = ' + TOP) != 1:
            raise RuntimeError('Unexpected baseline design configuration')
        config = config.replace('export DESIGN_NAME = ' + TOP, 'export DESIGN_NAME = ' + top)
        config += (f'\nexport SYNTH_CHECKPOINT = {FLOW}/designs/asap7/{top}/partition.rtlil\n'
                   'export SYNTH_SKIP_KEEP = 1\nexport SYNTH_BLACKBOXES = '
                   + ' '.join(plan['direct_dut_children_to_blackbox']) + '\n')
        (directory / 'designs/config.mk').write_text(config)
        sdc = (baseline_work / 'designs/asap7' / TOP / 'constraint.sdc').read_text()
        if sdc.count('current_design ' + TOP) != 1:
            raise RuntimeError('Unexpected baseline clock constraint')
        (directory / 'designs/constraint.sdc').write_text(sdc.replace('current_design ' + TOP, 'current_design ' + top))
        shutil.copy2(partition / 'partition.rtlil', directory / 'designs/partition.rtlil')
        if sha(directory / 'designs/partition.rtlil') != plan['partition_sha256']:
            raise RuntimeError('Partition changed when copied to the mapping flow')
        shell = f'source /OpenROAD-flow-scripts/env.sh >/dev/null && make DESIGN_CONFIG=./designs/asap7/{top}/config.mk do-yosys'
        run(output, 'map-' + top, docker(image, directory, top, 'bash', '-lc', shell))
        mapped = directory / 'results/base/1_2_yosys.v'
        structural = check_partition(partition / 'partition.rtlil', partition / 'manifest.json', mapped)
        if structural['module'] != top:
            raise RuntimeError('Mapped partition structure did not match: ' + top)
        parts[top] = mapped.read_text()
        if definitions(parts[top]) != [top]:
            raise RuntimeError('Unexpected mapped module inventory: ' + top)
        if 'Found and reported 0 problems' not in (directory / 'reports/base/synth_check.txt').read_text():
            raise RuntimeError('Partition mapping check failed: ' + top)
        log = (output / ('map-' + top + '.log')).read_text()
        if re.search(r'skipping unexpected instance type|used but has no driver|Found and reported [1-9]\d* problems', log, re.I):
            raise RuntimeError('Partition omitted or failed to drive logic: ' + top)

    try:
        assemble([parts[TOP]], (TOP, 'Leaf'))
    except ValueError:
        pass
    else:
        raise RuntimeError('Missing-child control was not rejected')
    directory = output / 'mapped' / TOP
    mapped = directory / 'results/base/1_2_yosys.v'
    mapped.write_text(assemble([parts[TOP], parts['Leaf']], (TOP, 'Leaf')))
    mapped_hash = sha(mapped)
    target = f'results/asap7/{TOP}/base/1_2_yosys'
    shell = (f'source /OpenROAD-flow-scripts/env.sh >/dev/null && make -o {target}.v -o {target}.sdc '
             f'DESIGN_CONFIG=./designs/asap7/{TOP}/config.mk synth')
    run(output, 'link', docker(image, directory, TOP, 'bash', '-lc', shell))
    link_log = (output / 'link.log').read_text()
    if sha(mapped) != mapped_hash or re.search(r'creating black box|module .* not found|\[ERROR ', link_log, re.I):
        raise RuntimeError('Linked netlist changed or contains unresolved logic')
    removed = re.findall(r'(?m)^Removed (\d+) unused instances and (\d+) unused nets\.$', link_log)
    if args.expect_dead_logic and (len(removed) != 1 or int(removed[0][0]) == 0):
        raise RuntimeError('Dead-output control did not remove unused instances')

    sta_script = (baseline / 'flow/synthesis_sta.tcl').read_text()
    (directory / 'designs/linked_sta.tcl').write_text(sta_script)
    run(output, 'sta', docker(image, directory, TOP, 'openroad', '-exit', '-no_init', '-no_splash',
                             f'./designs/asap7/{TOP}/linked_sta.tcl'))
    timing = synthesis_setup_gate((output / 'sta.log').read_text())
    prior = json.loads((baseline / 'qualification.json').read_text())
    if (not prior['passed'] or not timing['passed'] or
            timing['reported_setup_slacks_ps'] != prior['reported_setup_slacks_ps']):
        raise RuntimeError('Linked setup differs from the complete baseline')
    cross_script = (sta_script + '\nputs "CROSS_START"\n'
                    'report_checks -from [get_pins u0/*/QN] -to [get_pins u1/*/D] -path_delay max -digits 3\n'
                    'puts "CROSS_END"\n')
    (directory / 'designs/cross_sta.tcl').write_text(cross_script)
    run(output, 'cross-sta', docker(image, directory, TOP, 'openroad', '-exit', '-no_init', '-no_splash',
                                   f'./designs/asap7/{TOP}/cross_sta.tcl'))
    cross_log = (output / 'cross-sta.log').read_text()
    cross = cross_log.split('CROSS_START\n', 1)[-1].split('CROSS_END', 1)[0]
    crossing = synthesis_setup_gate(cross)
    if ('CROSS_START\n' not in cross_log or 'CROSS_END' not in cross_log or
            not re.search(r'(?m)^Startpoint: u0/', cross) or
            not re.search(r'(?m)^Endpoint: u1/', cross) or not crossing['passed']):
        raise RuntimeError('Linked timing did not report the cross-module register path')

    gold = (rtl / 'Leaf.sv').read_text() + '\n' + (rtl / (TOP + '.sv')).read_text()
    if gold.count("16'h35") != 1:
        raise RuntimeError('Unexpected arithmetic control fixture')
    libs = re.findall(r'(?m)^read_liberty (\S+)\s*$', sta_script)
    if not libs:
        raise RuntimeError('Baseline timing did not identify its cell libraries')
    design = directory / 'designs'
    (design / 'gold.sv').write_text(gold)
    (design / 'gold-negative.sv').write_text(gold.replace("16'h35", "16'h34"))
    def equivalence(gold_name):
        return (f'read_verilog -sv ./designs/asap7/{TOP}/{gold_name}\n'
                f'hierarchy -check -top {TOP}\nproc\nflatten\nopt\nrename {TOP} gold\n'
                'design -stash gold_source\n'
                + ''.join('read_liberty -ignore_miss_func ' + lib + '\n' for lib in libs)
                + f'read_verilog ./results/asap7/{TOP}/base/1_2_yosys.v\n'
                f'hierarchy -check -top {TOP}\nproc\nsetattr -mod -unset keep_hierarchy\n'
                f'flatten\nopt\ncheck -assert\nrename {TOP} gate\n'
                'design -copy-from gold_source gold\nequiv_make gold gate equiv\n'
                'hierarchy -top equiv\nequiv_simple -seq 3\nequiv_status -assert\n')
    for stage, name, expected in (('equivalence', 'gold.sv', 0), ('equivalence-negative', 'gold-negative.sv', 1)):
        (design / (stage + '.ys')).write_text(equivalence(name))
        command = docker(image, directory, TOP, 'yosys', '-T', '-s', f'./designs/asap7/{TOP}/{stage}.ys')
        run(output, stage, command, expected)
    if 'Equivalence successfully proven!' not in (output / 'equivalence.log').read_text():
        raise RuntimeError('Formal equivalence did not prove its checks')
    if not re.search(r'ERROR: Found [1-9]\d* unproven \$equiv cells', (output / 'equivalence-negative.log').read_text()):
        raise RuntimeError('Changed-constant negative control did not fail equivalence')
    summary = dict(passed=True, image=image, structural_partitions_checked=True,
                   unused_instances_removed=int(removed[0][0]) if len(removed) == 1 else None,
                   linked_setup_slacks_ps=timing['reported_setup_slacks_ps'],
                   cross_module_setup_slacks_ps=crossing['reported_setup_slacks_ps'],
                   missing_child_rejected=True, changed_constant_rejected=True,
                   scope='Two-module control only; no GPU host, product, or FPS qualification')
    (output / 'control.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
