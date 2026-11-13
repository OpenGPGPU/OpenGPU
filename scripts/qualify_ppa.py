#!/usr/bin/env python3
"""Measure emitted RTL with explicit clocks, SRAM libraries and an input manifest.

Use ChipAgent's Python environment. Set CHIPAGENT_DIR for a non-sibling checkout.
Outputs are immutable run directories; no historical result is overwritten.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]

def nonnegative_picoseconds(value):
    margin = float(value)
    if not math.isfinite(margin) or margin < 0:
        raise argparse.ArgumentTypeError('Expected finite nonnegative picoseconds')
    return margin


def positive_picoseconds(value):
    period = float(value)
    if not math.isfinite(period) or period <= 0:
        raise argparse.ArgumentTypeError('Expected finite positive picoseconds')
    return period


def positive_integer(value):
    try:
        result = int(value)
    except ValueError:
        raise argparse.ArgumentTypeError('Expected a positive integer') from None
    if result < 1:
        raise argparse.ArgumentTypeError('Expected a positive integer')
    return result


def coverage_memory(value):
    if value == 'unlimited':
        return None
    return positive_integer(value)


def expected_sram_macro(value):
    match = re.fullmatch(r'([A-Za-z_][A-Za-z0-9_$]*)=(0|[1-9]\d*)', value)
    if not match:
        raise argparse.ArgumentTypeError('Expected SRAM_MASTER=NONNEGATIVE_COUNT')
    return match.group(1), int(match.group(2))


def sram_macro_gate(report, returncode, expected):
    """Compare caller-declared physical macro counts with the loaded ODB."""
    begin, end = 'OPENGPU_SRAM_MACROS_BEGIN\n', 'OPENGPU_SRAM_MACROS_END\n'
    complete = report.count(begin) == 1 and report.count(end) == 1
    actual, nonphysical, invalid = {}, {}, []
    if complete:
        before, rest = report.split(begin, 1)
        if end in before:
            complete = False
        else:
            body, _ = rest.split(end, 1)
            for line in body.splitlines():
                match = re.fullmatch(r'OPENGPU_SRAM_MACRO ([A-Za-z_][A-Za-z0-9_$]*) ([1-9]\d*) (0|[1-9]\d*)', line)
                if not match or match[1] in actual:
                    invalid.append(line)
                    continue
                name, count, blocks = match[1], int(match[2]), int(match[3])
                actual[name] = count
                if count != blocks:
                    nonphysical[name] = dict(instances=count, physical_blocks=blocks)
    valid_expected = isinstance(expected, dict) and all(
        isinstance(name, str) and re.fullmatch(r'[A-Za-z_][A-Za-z0-9_$]*', name)
        and isinstance(count, int) and not isinstance(count, bool) and count >= 0
        for name, count in expected.items())
    mismatches = ({name: dict(expected=expected.get(name), actual=actual.get(name, 0))
                   for name in sorted(set(expected) | set(actual))
                   if name not in expected or expected[name] != actual.get(name, 0)}
                  if valid_expected else {})
    errors = bool(re.search(r'(?mi)^\s*(?:\[ERROR\b|ERROR:)', report))
    return dict(passed=returncode == 0 and complete and valid_expected
                and not invalid and not mismatches and not nonphysical and not errors,
                complete=complete, expected=expected, actual=actual,
                mismatches=mismatches, nonphysical=nonphysical,
                invalid_rows=invalid, valid_expected=valid_expected,
                tool_errors=errors, returncode=returncode,
                scope='Exact physical SRAM master counts in the loaded OpenDB block')


def sram_macro_tcl():
    # Count every physical block, plus SRAM-named instances even when their
    # master lost its BLOCK classification. Unknown physical macros fail the
    # caller's exact per-type contract rather than disappearing from the total.
    return '''puts "OPENGPU_SRAM_MACROS_BEGIN"
set opengpu_counts [dict create]
set opengpu_blocks [dict create]
set opengpu_block [[ord::get_db] getChip]
set opengpu_block [$opengpu_block getBlock]
foreach opengpu_inst [$opengpu_block getInsts] {
  set opengpu_master [$opengpu_inst getMaster]
  set opengpu_name [$opengpu_master getName]
  set opengpu_physical [$opengpu_master isBlock]
  if {$opengpu_physical || [string match "srambank_*" $opengpu_name]} {
    dict incr opengpu_counts $opengpu_name
    dict incr opengpu_blocks $opengpu_name $opengpu_physical
  }
}
foreach opengpu_name [lsort [dict keys $opengpu_counts]] {
  puts "OPENGPU_SRAM_MACRO $opengpu_name [dict get $opengpu_counts $opengpu_name] [dict get $opengpu_blocks $opengpu_name]"
}
puts "OPENGPU_SRAM_MACROS_END"
'''


def run_odb_check(command, timeout, memory_gib, name='coverage'):
    """Bound a named check and stop its owned container on interruption."""
    source, command = list(command), []
    index = 0
    while index < len(source):
        argument = source[index]
        if argument in ('--cpus', '--memory', '--memory-swap'):
            index += 2
            continue
        if not argument.startswith(('--cpus=', '--memory=', '--memory-swap=')):
            command.append(argument)
        index += 1
    container = 'opengpu-' + name + '-' + uuid.uuid4().hex
    resources = ['--name', container, '--cpus=1']
    if memory_gib is not None:
        resources += [f'--memory={memory_gib}g', f'--memory-swap={memory_gib}g']
    command[3:3] = resources

    def stop():
        try:
            stopped = subprocess.run(['docker', 'stop', '--time', '5', container],
                                     capture_output=True, text=True, timeout=15)
            return '' if not stopped.returncode else '\nContainer stop did not succeed: ' + stopped.stderr
        except (OSError, subprocess.TimeoutExpired) as exc:
            return '\nContainer stop could not be confirmed: ' + str(exc)

    try:
        proc = subprocess.run(command, capture_output=True, text=True, timeout=timeout + 20)
        report = proc.stdout + ('\n[stderr]\n' + proc.stderr if proc.stderr else '')
        code = proc.returncode
    except subprocess.TimeoutExpired as exc:
        partial = exc.stdout or ''
        if isinstance(partial, bytes):
            partial = partial.decode(errors='replace')
        report, code = 'OpenDB check timed out.\n' + partial + stop(), 124
    except OSError as exc:
        report, code = 'OpenDB check could not start: ' + str(exc) + '\n', 1
    except BaseException:
        stop()
        raise
    return report, code, container, command


def macro_collateral_inputs(work, module, expected):
    """Verify the exact copied LIB/LEF collateral captured before the flow."""
    paths = {}
    expected = {} if expected is None else expected
    if not isinstance(expected, dict):
        return None
    for name, digest in expected.items():
        if (not isinstance(name, str) or not isinstance(digest, str)
                or not re.fullmatch(r'[A-Za-z0-9_$.-]+\.(?:lib|lef)', name)
                or not re.fullmatch(r'[0-9a-f]{64}', digest)):
            return None
        path = work / 'designs/asap7' / module / 'macros' / name
        if not path.is_file() or path.is_symlink() or sha(path) != digest:
            return None
        paths['macro:' + name] = path
    return paths


def exact_odb_mounts(command, required):
    """Reject replacement or overlapping mounts of checked inputs."""
    if any(arg.startswith(('--mount', '--volume', '-v=')) or arg.startswith('-v') and arg != '-v'
           for arg in command):
        return False
    volumes = [command[i + 1] for i, arg in enumerate(command[:-1]) if arg == '-v']
    if any(volumes.count(v) != 1 for v in required):
        return False
    destinations = [v.split(':')[1] for v in required]
    for volume in volumes:
        parts = volume.split(':')
        if len(parts) != 2 or not parts[0].startswith('/') or not parts[1].startswith('/'):
            return False
        if volume in required:
            continue
        other = parts[1].rstrip('/')
        if any(other == p or other.startswith(p + '/') or p.startswith(other + '/') for p in destinations):
            return False
    return True


def valid_check_resources(timeout, memory_gib):
    return (isinstance(timeout, int) and not isinstance(timeout, bool) and timeout > 0
            and (memory_gib is None or isinstance(memory_gib, int)
                 and not isinstance(memory_gib, bool) and memory_gib > 0))

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pin_openroad_image():
    from chipagent.toolchain import configured_openroad_image
    image = subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}',
                                     configured_openroad_image()], text=True).strip()
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', image):
        raise RuntimeError('Docker did not return a pinned tool image ID')
    return image

def prepare_hierarchy_image(base_image, output):
    """Pin the tool image and derive only a declared ORFS environment profile."""
    recipe = ROOT / 'timing/yosys-hierarchy.Dockerfile'
    base = subprocess.check_output(
        ['docker', 'image', 'inspect', '--format', '{{.Id}}', base_image], text=True).strip()
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', base):
        raise RuntimeError('Docker did not return a pinned base image ID')
    base_tag = 'opengpgpu-ppa-base:' + base.split(':', 1)[1]
    subprocess.run(['docker', 'image', 'tag', base, base_tag], check=True)
    iid = output / 'hierarchy-image.id'
    subprocess.run(['docker', 'build', '--pull=false', '--iidfile', str(iid),
                    '--build-arg', 'BASE_IMAGE=' + base_tag, '-f', str(recipe),
                    str(recipe.parent)], check=True)
    image = iid.read_text().strip()
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', image):
        raise RuntimeError('Docker did not return a pinned derived image ID')
    configured = json.loads(subprocess.check_output(
        ['docker', 'image', 'inspect', '--format', '{{json .Config.Env}}', image], text=True))
    env = dict(entry.split('=', 1) for entry in configured if '=' in entry)
    expected = dict(SYNTH_KEEP_MODULES='*', SYNTH_HIERARCHICAL='0', SYNTH_ARGS='-noshare',
                    SYNTH_MOCK_LARGE_MEMORIES='0', SYNTH_KEEP_MOCKED_MEMORIES='0',
                    SYNTH_GUT='0', SYNTH_BLACKBOXES='', SYNTH_RETIME_MODULES='')
    if any(env.get(k) != v for k, v in expected.items()):
        raise RuntimeError('Derived image does not implement the declared hierarchy profile')
    layers = subprocess.check_output(['docker', 'image', 'inspect', '--format',
                                      '{{json .RootFS.Layers}}', base, image], text=True).splitlines()
    if len(layers) != 2 or json.loads(layers[0]) != json.loads(layers[1]):
        raise RuntimeError('Hierarchy profile changed the pinned tool filesystem')
    return dict(base_image_id=base, image_id=image, recipe_sha256=sha(recipe),
                tool_filesystem_layers_identical=True,
                orfs_environment=expected, abstracted_dut_modules=[], memory_mocks=False,
                retiming=False, scope='Mapping hierarchy only; linked whole-design timing is still required')


def synthesis_setup_gate(report):
    """Include every reported path group, including the virtual I/O clock."""
    paths = re.findall(
        r'^\s*([-+]?\d+(?:\.\d+)?)\s+slack\s+\((MET|VIOLATED)\)\s*$',
        report, re.MULTILINE)
    slacks = [float(value) for value, _ in paths]
    return dict(passed=bool(paths) and all(value >= 0 for value in slacks)
                and all(verdict == 'MET' for _, verdict in paths),
                reported_setup_slacks_ps=slacks,
                reported_path_groups=len(paths))


def synthesis_constraint_gate(report, returncode):
    """Fail closed unless OpenROAD checked every setup endpoint."""
    begin, end = 'OPENGPU_CHECK_SETUP_BEGIN\n', 'OPENGPU_CHECK_SETUP_END\n'
    complete = report.count(begin) == 1 and report.count(end) == 1
    issues = ''
    if complete:
        before, rest = report.split(begin, 1)
        if end in before:
            complete = False
        else:
            issues, _ = rest.split(end, 1)
            issues = issues.strip()
    errors = bool(re.search(r'(?mi)^\s*(?:\[ERROR\b|ERROR:)', report))
    return dict(passed=returncode == 0 and complete and not issues and not errors,
                complete=complete, issues=issues, tool_errors=errors, returncode=returncode,
                scope='OpenROAD check_setup constraint coverage; not physical or formal qualification')


def constraint_coverage_tcl():
    return ('puts "OPENGPU_CHECK_SETUP_BEGIN"\ncheck_setup\n'
            'puts "OPENGPU_CHECK_SETUP_END"\n' + sram_macro_tcl())


def synthesis_constraint_proof(result, output, timeout, memory_gib=6, expected_macros=None,
                               expected_collateral=None):
    """Run check_setup against the exact ODB/SDC and tool command used for STA."""
    output = Path(output).resolve()
    module = result.get('module_name', '')
    command = result.get('command')
    artifacts = result.get('artifacts') or {}
    image = (result.get('reproducibility') or {}).get('openroad_image')
    flow = '/OpenROAD-flow-scripts/flow'
    if (not valid_check_resources(timeout, memory_gib) or
            not isinstance(module, str) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_$]*', module) or
            not isinstance(command, list) or not all(isinstance(arg, str) for arg in command) or
            len(command) < 5 or command[:3] != ['docker', 'run', '--rm'] or
            command[-2:] != ['openroad', '/tmp/synthesis_sta.tcl'] or
            not isinstance(image, str) or not re.fullmatch(r'sha256:[0-9a-f]{64}', image)
            or command[-3] != image):
        return dict(passed=False, reason='Missing or unsupported synthesis STA command')
    script = Path(artifacts.get('synthesis_sta.tcl', '')).resolve()
    work = output / 'flow/orfs-work'
    collateral = macro_collateral_inputs(work, module, expected_collateral)
    if collateral is None:
        return dict(passed=False, reason='Missing or changed physical SRAM collateral')
    odb, sdc = work / 'results/base/1_synth.odb', work / 'results/base/1_synth.sdc'
    mount = str(script) + ':/tmp/synthesis_sta.tcl'
    results_mount = str(work / 'results') + f':{flow}/results/asap7/{module}'
    required_mounts = [mount, results_mount]
    if collateral:
        required_mounts.append(str(work / 'designs/asap7' / module) + f':{flow}/designs/asap7/{module}')
    if (script != (output / 'flow/synthesis_sta.tcl').resolve() or
            not script.is_file() or not odb.is_file() or not sdc.is_file() or
            not exact_odb_mounts(command, required_mounts)):
        return dict(passed=False, reason='Missing or ambiguous synthesis STA inputs')
    sta = script.read_text()
    required = [f'read_db {flow}/results/asap7/{module}/base/1_synth.odb',
                f'read_sdc {flow}/results/asap7/{module}/base/1_synth.sdc',
                'report_checks -path_delay max -digits 3']
    if any(sta.splitlines().count(line) != 1 for line in required):
        return dict(passed=False, reason='Unsupported synthesis STA script')

    def digest(path):
        h = hashlib.sha256()
        with path.open('rb') as stream:
            for data in iter(lambda: stream.read(1024 * 1024), b''):
                h.update(data)
        return h.hexdigest()

    checked = dict(odb=odb, sdc=sdc, sta_script=script, **collateral)
    inputs = {name: digest(path) for name, path in checked.items()}
    coverage_script = output / 'constraint_coverage.tcl'
    coverage_log = output / 'constraint_coverage.log'
    replacement = constraint_coverage_tcl()
    with coverage_script.open('x') as stream:
        stream.write(sta.replace(required[-1], replacement))
    inputs['coverage_script'] = digest(coverage_script)
    checked['coverage_script'] = coverage_script
    coverage_command = list(command)
    coverage_command[coverage_command.index(mount)] = str(coverage_script) + ':/tmp/synthesis_sta.tcl'
    coverage_command[-2:] = ['timeout', str(timeout), 'openroad', '-exit', '-no_init', '-no_splash',
                             '/tmp/synthesis_sta.tcl']
    report, code, container, coverage_command = run_odb_check(coverage_command, timeout, memory_gib)
    with coverage_log.open('x') as stream:
        stream.write(report)
    gate = synthesis_constraint_gate(report, code)
    gate['sram_macros'] = sram_macro_gate(report, code, {} if expected_macros is None else expected_macros)
    after = {name: digest(path) if path.is_file() else None for name, path in checked.items()}
    gate.update(input_sha256=inputs, inputs_unchanged=inputs == after, image=image,
                container=container, command=coverage_command,
                report_sha256=hashlib.sha256(report.encode()).hexdigest())
    gate['passed'] = gate['passed'] and gate['inputs_unchanged'] and gate['sram_macros']['passed']
    return gate


def route_macro_proof(result, output, expected_macros, expected_collateral, timeout, memory_gib=6):
    """Check final ODB/SDC endpoint coverage and exact physical macro counts."""
    output = Path(output).resolve()
    work = output / 'flow/orfs-work'
    module, original = result.get('module_name'), result.get('command')
    image = (result.get('reproducibility') or {}).get('openroad_image')
    if (not valid_check_resources(timeout, memory_gib)
            or not isinstance(module, str) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_$]*', module)
            or not isinstance(image, str) or not re.fullmatch(r'sha256:[0-9a-f]{64}', image)
            or not isinstance(original, list) or not all(isinstance(x, str) for x in original)
            or original[:3] != ['docker', 'run', '--rm'] or len(original) < 7
            or original[-4:-1] != [image, 'bash', '-lc']):
        return dict(passed=False, reason='Missing or unsupported routed tool command')
    artifacts = result.get('artifacts') or {}
    odb = Path(artifacts.get('final_odb', '')).resolve()
    if odb != (work / 'results/base/6_final.odb').resolve() or not odb.is_file():
        return dict(passed=False, reason='Missing or mismatched final routed ODB')
    sdc = Path(artifacts.get('final_sdc', '')).resolve()
    if sdc != (work / 'results/base/6_final.sdc').resolve() or not sdc.is_file():
        return dict(passed=False, reason='Missing or mismatched final routed SDC')
    parameters = (result.get('reproducibility') or {}).get('parameters') or {}
    if parameters.get('corner') != 'TC' or parameters.get('cell_vt') != 'SLVT':
        return dict(passed=False, reason='Unsupported final timing library corner')
    collateral = macro_collateral_inputs(work, module, expected_collateral)
    if collateral is None:
        return dict(passed=False, reason='Missing or changed physical SRAM collateral')
    flow = '/OpenROAD-flow-scripts/flow'
    required = [str(work / 'results') + f':{flow}/results/asap7/{module}',
                str(work / 'designs/asap7' / module) + f':{flow}/designs/asap7/{module}']
    if not exact_odb_mounts(original, required):
        return dict(passed=False, reason='Missing or ambiguous routed OpenDB mounts')
    script, log = output / 'route_macro_check.tcl', output / 'route_macro_check.log'
    # read_db restores the same physical masters/LEFs. Load only the exact
    # copied timing libraries whose hashes were captured before this flow.
    libraries = sorted(p for p in collateral.values() if p.suffix == '.lib')
    standard_libraries = ('asap7sc7p5t_AO_SLVT_TT_nldm_211120.lib.gz',
        'asap7sc7p5t_INVBUF_SLVT_TT_nldm_220122.lib.gz',
        'asap7sc7p5t_OA_SLVT_TT_nldm_211120.lib.gz',
        'asap7sc7p5t_SEQ_SLVT_TT_nldm_220123.lib',
        'asap7sc7p5t_SIMPLE_SLVT_TT_nldm_211120.lib.gz')
    source = ''.join(f'read_liberty {flow}/platforms/asap7/lib/NLDM/{name}\n'
                     for name in standard_libraries)
    source += ''.join(f'read_liberty {flow}/designs/asap7/{module}/macros/{p.name}\n' for p in libraries)
    source += (f'read_db {flow}/results/asap7/{module}/base/6_final.odb\n'
               f'read_sdc {flow}/results/asap7/{module}/base/6_final.sdc\n' + constraint_coverage_tcl())
    with script.open('x') as stream:
        stream.write(source)
    checked = dict(odb=odb, sdc=sdc, script=script, **collateral)
    before = {name: sha(p) for name, p in checked.items()}
    command = ['docker', 'run', '--rm']
    for volume in required:
        command += ['-v', volume]
    command += ['-v', str(script) + ':/tmp/route_macro_check.tcl', image,
                'timeout', str(timeout), 'openroad', '-exit', '-no_init', '-no_splash', '/tmp/route_macro_check.tcl']
    report, code, container, command = run_odb_check(command, timeout, memory_gib, 'route-macros')
    with log.open('x') as stream:
        stream.write(report)
    gate = sram_macro_gate(report, code, expected_macros)
    gate['constraint_coverage'] = synthesis_constraint_gate(report, code)
    after = {name: sha(p) if p.is_file() else None for name, p in checked.items()}
    gate.update(input_sha256=before, inputs_unchanged=before == after, image=image,
                container=container, command=command,
                report_sha256=hashlib.sha256(report.encode()).hexdigest())
    gate['passed'] = gate['passed'] and gate['inputs_unchanged'] and gate['constraint_coverage']['passed']
    return gate


def synthesis_mapping_gate(log):
    """Timing cannot qualify a netlist that omitted RTL operators or drivers."""
    skipped = re.findall(r"skipping unexpected instance type ['\"]([^'\"]+)['\"]", log or '')
    unresolved = [line.strip() for line in (log or '').splitlines()
                  if re.search(r'used but has no driver|unmapped (?:cell|instance)|'
                               r'found and reported [1-9]\d* problems', line, re.I)
                  or re.search(r'^\s*[1-9]\d*\s+(?:\S+\s+\d+\s+\S+\s+)?\$\S+\s*$', line)]
    mapped = bool(re.search(r'Export to ODB: created \d+ instances|ABC RESULTS:|Number of cells:|'
                            r'^\s*[1-9]\d*\s+\S+\s+\d+\s+\S+\s+cells\s*$',
                            log or '', re.MULTILINE))
    return dict(passed=mapped and not skipped and not unresolved,
                mapping_report_present=mapped, skipped_instance_types=sorted(set(skipped)),
                skipped_instance_count=len(skipped),
                skipped_instances_by_type={name: skipped.count(name) for name in sorted(set(skipped))},
                unresolved_diagnostics=unresolved,
                scope='Synthesis mapping report; omitted operators and undriven logic fail qualification')


def mapping_proof(result, output):
    candidates = [output / 'flow/orfs-work/logs/base/1_synth.log',
                  output / 'flow/orfs-work/logs/base/1_2_yosys.log',
                  output / 'flow/orfs-work/reports/base/synth_stat.txt']
    paths = [p for p in candidates if p.is_file()]
    run_log = (result.get('artifacts') or {}).get('run.log')
    if not paths and run_log and Path(run_log).is_file():
        paths.append(Path(run_log))
    logs = [(path, path.read_text()) for path in paths]
    combined = '\n'.join(log for _, log in logs)
    gate = synthesis_mapping_gate(combined)
    # A Yosys run can have an OpenROAD-only 1_synth.log and put its mapping
    # statistics in a separate report. Inspect every engine log for failures.
    primary = next(((p, log) for p, log in logs
                    if synthesis_mapping_gate(log)['mapping_report_present']),
                   logs[0] if logs else (None, ''))
    gate.update(log=str(primary[0]) if primary[0] else None,
                log_sha256=hashlib.sha256(primary[1].encode()).hexdigest() if primary[0] else None,
                logs=[dict(path=str(p), sha256=hashlib.sha256(log.encode()).hexdigest())
                      for p, log in logs],
                combined_log_sha256=hashlib.sha256(combined.encode()).hexdigest() if logs else None)
    return gate


def post_route_gate(result):
    """Require explicit global setup/hold and physical checks, not tool completion."""
    qor = result.get('qor') or {}
    slacks = ('setup_worst_slack_ps', 'hold_worst_slack_ps')
    zero_counts = ('setup_violation_count', 'hold_violation_count',
                   'route_drc_errors', 'antenna_violating_nets',
                   'antenna_violating_pins', 'placement_violations', 'flow_errors')
    def number(value):
        return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)
    checks = {name: number(qor.get(name)) and qor[name] >= 0 for name in slacks}
    checks.update({name: number(qor.get(name)) and qor[name] == 0 for name in zero_counts})
    checks['flow_completed'] = result.get('status') == 'success'
    checks['tool_verdict'] = (result.get('overview') or {}).get('verdict') == 'PASS'
    return dict(passed=all(checks.values()), checks=checks,
                missing_metrics=[name for name in slacks + zero_counts if not number(qor.get(name))],
                scope='Post-route global setup/hold including I/O; placement, DRC, antenna and flow errors')

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('rtl', type=Path)
    parser.add_argument('module')
    parser.add_argument('--stage', choices=['synthesis', 'route'], default='synthesis')
    parser.add_argument('--clock', default='clock')
    parser.add_argument('--sram-root', type=Path)
    parser.add_argument('--period-ps', type=positive_picoseconds, default=1000)
    parser.add_argument('--io-delay', type=float, default=0.25)
    parser.add_argument('--utilization', type=int, default=25)
    parser.add_argument('--density', type=float, default=0.6)
    parser.add_argument('--timeout', type=int, default=7200)
    parser.add_argument('--docker-cpus', type=positive_integer, default=2)
    parser.add_argument('--docker-memory-gib', type=positive_integer, default=12)
    parser.add_argument('--coverage-timeout', type=positive_integer, default=600,
                        help='Maximum seconds for the constraint-coverage check (default: 600)')
    parser.add_argument('--coverage-memory-gib', type=coverage_memory, default=6,
                        help='Constraint-check memory in GiB, or unlimited (default: 6)')
    parser.add_argument('--expected-sram-macro', action='append', type=expected_sram_macro, default=[],
                        metavar='MASTER=COUNT',
                        help='Explicit physical SRAM count; repeat for every supplied SRAM master, including zero')
    parser.add_argument('--timing-effort', choices=['explore', 'closure', 'closure_no_cts'])
    parser.add_argument('--setup-slack-margin-ps', type=nonnegative_picoseconds, default=0.0,
                        help='Extra setup repair headroom; the final timing gate is unchanged')
    parser.add_argument('--abc-clock-period-ps', type=positive_picoseconds,
                        help='Yosys/ABC mapping target; the declared --period-ps timing gate is unchanged')
    parser.add_argument('--synthesis-engine', choices=['syn', 'yosys'], default='syn')
    parser.add_argument('--yosys-preserve-hierarchy', action='store_true',
                        help='Preserve RTL modules during Yosys mapping; retain linked design STA')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    if args.abc_clock_period_ps is not None and args.synthesis_engine != 'yosys':
        parser.error('--abc-clock-period-ps requires --synthesis-engine yosys')
    if args.yosys_preserve_hierarchy and (args.synthesis_engine != 'yosys' or args.stage != 'synthesis'):
        parser.error('--yosys-preserve-hierarchy requires Yosys synthesis-only qualification')
    expected_macros = dict(args.expected_sram_macro)
    if len(expected_macros) != len(args.expected_sram_macro):
        parser.error('Duplicate --expected-sram-macro master')
    rtl = args.rtl.resolve()
    filelist = rtl / 'filelist.f'
    files = ([rtl / line.strip() for line in filelist.read_text().splitlines()
              if line.strip() and not line.startswith('verification/')]
             if filelist.exists() else sorted(rtl.glob('*.sv')))
    if not files:
        raise SystemExit('No emitted RTL files')
    source = '\n'.join(path.read_text() for path in files)
    top = re.search(r'\bmodule\s+' + re.escape(args.module) + r'\b(.*?);', source, re.S)
    if not top or not re.search(r'\b' + re.escape(args.clock) + r'\b', top[1]):
        raise SystemExit('Requested clock is absent from the top-level ports')
    sram = (args.sram_root or ROOT / 'depends/asap7_sram_0p0/generated').resolve()
    from extract_emitted_rtl import code_only
    macros = sorted(set(re.findall(r'\bsrambank_\w+', code_only(source))))
    if set(expected_macros) != set(macros):
        parser.error('--expected-sram-macro must explicitly declare every supplied SRAM master; '
                     f'expected declarations for {macros}, received {sorted(expected_macros)}')
    libs = [sram / 'LIB' / (name + '.lib') for name in macros]
    lefs = [sram / 'LEF' / (name + '.lef') for name in macros]
    for path in files + libs + lefs:
        if not path.is_file():
            raise SystemExit(f'Missing input: {path}')
    expected_collateral = {path.name: sha(path) for path in libs + lefs}
    args.output.mkdir(parents=True, exist_ok=False)
    chipagent = Path(os.environ.get('CHIPAGENT_DIR', ROOT.parent / 'chipagent'))
    sys.path.insert(0, str(chipagent))
    from chipagent.models import TaskObject
    from chipagent.tools.base import ToolContext
    from chipagent.tools.phys_flow_asap7 import ASAP7PhysicalFlowTool, docker_resource_args
    docker_resource_args(args.docker_cpus, args.docker_memory_gib)
    inputs = dict(reg_code='', rtl_files=[str(p) for p in files],
        module_name=args.module, clock_port=args.clock, clock_period=args.period_ps,
        io_delay_percent=args.io_delay, io_false_path_ports=None,
        docker_cpus=args.docker_cpus, docker_memory_gib=args.docker_memory_gib,
        cell_vt='SLVT', corner='TC', synthesis_engine=args.synthesis_engine,
        enable_retiming=False, sv_frontend='native',
        core_utilization=args.utilization, place_density=args.density,
        setup_slack_margin=args.setup_slack_margin_ps,
        abc_clock_period_ps=args.abc_clock_period_ps,
        output_dir=str(args.output.resolve() / 'flow'), timeout=args.timeout,
        synthesis_only=args.stage == 'synthesis', cache=False, clean=False,
        generate_gds=False,
        timing_effort=args.timing_effort or ('closure' if args.stage == 'route' else 'explore'),
        macro_libs=[str(p) for p in libs] or None,
        macro_lefs=[str(p) for p in lefs] or None,
        macro_placement_tcl=str(ROOT / 'timing/asap7/compute_unit_sram_macro_placement.tcl') if macros else None)
    tracked = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard',
        'src/main', 'build.sbt',
        'scripts/qualify_ppa.py', 'scripts/extract_emitted_rtl.py',
        'scripts/report_ppa_sram_inventory.py', 'timing/asap7'], cwd=ROOT, text=True).splitlines()
    manifest = dict(timestamp=datetime.now(timezone.utc).isoformat(),
        commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        dirty=bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT)),
        chipagent_commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=chipagent, text=True).strip(),
        source_sha256={name:sha(ROOT / name) for name in tracked},
        emitted_sha256={str(p.relative_to(rtl)):sha(p) for p in files},
        macro_sha256={str(p.resolve()):sha(p) for p in libs + lefs},
        scope='synthesis estimate' if args.stage == 'synthesis' else 'post-route; includes IO timing',
        expected_sram_macros=expected_macros,
        constraint_coverage_resources=dict(memory_gib=args.coverage_memory_gib,
                                           timeout_seconds=min(args.timeout, args.coverage_timeout)),
        inputs=inputs)
    hierarchy = None
    if args.yosys_preserve_hierarchy:
        from chipagent.toolchain import configured_openroad_image
        hierarchy = prepare_hierarchy_image(configured_openroad_image(), args.output)
        manifest['hierarchy_profile'] = hierarchy
        image = hierarchy['image_id']
    else:
        image = pin_openroad_image()
    manifest['tool_image_id'] = image
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    previous_image = os.environ.get('CHIPAGENT_OPENROAD_IMAGE')
    try:
        os.environ['CHIPAGENT_OPENROAD_IMAGE'] = image
        result = ASAP7PhysicalFlowTool().run(ToolContext(
            task=TaskObject(task_type='physical_flow_asap7', module_name=args.module, description='Current RTL qualification'),
            inputs=inputs))
    finally:
        if previous_image is None:
            os.environ.pop('CHIPAGENT_OPENROAD_IMAGE', None)
        else:
            os.environ['CHIPAGENT_OPENROAD_IMAGE'] = previous_image
    (args.output / 'result.json').write_text(json.dumps(result.result, indent=2, default=str) + '\n')
    print(json.dumps({k: result.result.get(k) for k in ('status', 'qor', 'overview', 'diagnosis')}, indent=2, default=str), flush=True)
    status = result.result.get('status')
    if status in ('error', 'missing_tool'):
        raise SystemExit(1)
    if status == 'failed' and result.result.get('overview', {}).get('verdict') != 'FAIL':
        raise SystemExit(1)
    mapping = mapping_proof(result.result, args.output)
    mapping['tool_image_matches'] = (result.result.get('reproducibility') or {}).get('openroad_image') == image
    mapping['passed'] = mapping['passed'] and mapping['tool_image_matches']
    if args.stage == 'synthesis':
        report_file = result.result.get('artifacts', {}).get('synthesis_sta.log')
        report_path = Path(report_file) if report_file else None
        report = (report_path.read_text() if report_path and report_path.is_file()
                  else result.result.get('report_tail', ''))
        gate = synthesis_setup_gate(report)
        gate.update(scope='All reported synthesis setup paths, including I/O',
                    report_sha256=hashlib.sha256(report.encode()).hexdigest())
        gate['setup_passed'] = gate['passed']
        gate['mapping'] = mapping
        if gate['passed'] and mapping['passed']:
            gate['constraint_coverage'] = synthesis_constraint_proof(
                result.result, args.output, min(args.timeout, args.coverage_timeout),
                args.coverage_memory_gib, expected_macros, expected_collateral)
        else:
            gate['constraint_coverage'] = dict(passed=False, reason='Setup or mapping failed before coverage')
        gate['passed'] = gate['passed'] and mapping['passed'] and gate['constraint_coverage']['passed']
        (args.output / 'qualification.json').write_text(json.dumps(gate, indent=2) + '\n')
        print(json.dumps({'synthesis_setup_gate': gate}), flush=True)
        if not gate['passed']:
            raise SystemExit(2)
    else:
        gate = post_route_gate(result.result)
        gate['mapping'] = mapping
        if gate['passed'] and mapping['passed']:
            gate['sram_macros'] = route_macro_proof(result.result, args.output,
                expected_macros, expected_collateral, min(args.timeout, args.coverage_timeout),
                args.coverage_memory_gib)
        else:
            gate['sram_macros'] = dict(passed=False, reason='Physical or mapping gates failed before macro checks')
        gate['passed'] = gate['passed'] and mapping['passed'] and gate['sram_macros']['passed']
        (args.output / 'qualification.json').write_text(json.dumps(gate, indent=2) + '\n')
        print(json.dumps({'post_route_gate': gate}), flush=True)
        if not gate['passed']:
            raise SystemExit(2)
    if result.result.get('overview', {}).get('verdict') == 'FAIL':
        raise SystemExit(2)  # Flow completed, but physical qualification failed.
    if status == 'failed':
        raise SystemExit(1)

if __name__ == '__main__':
    main()
