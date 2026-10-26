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

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

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

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('rtl', type=Path)
    parser.add_argument('module')
    parser.add_argument('--stage', choices=['synthesis', 'route'], default='synthesis')
    parser.add_argument('--clock', default='clock')
    parser.add_argument('--period-ps', type=float, default=1000)
    parser.add_argument('--io-delay', type=float, default=0.25)
    parser.add_argument('--utilization', type=int, default=25)
    parser.add_argument('--density', type=float, default=0.6)
    parser.add_argument('--timeout', type=int, default=7200)
    parser.add_argument('--timing-effort', choices=['explore', 'closure', 'closure_no_cts'])
    parser.add_argument('--setup-slack-margin-ps', type=nonnegative_picoseconds, default=0.0,
                        help='Extra setup repair headroom; the final timing gate is unchanged')
    parser.add_argument('--abc-clock-period-ps', type=positive_picoseconds,
                        help='Yosys/ABC mapping target; the declared --period-ps timing gate is unchanged')
    parser.add_argument('--synthesis-engine', choices=['syn', 'yosys'], default='syn')
    parser.add_argument('--yosys-preserve-hierarchy', action='store_true',
                        help='Preserve RTL modules during Yosys mapping; retain linked design STA')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.abc_clock_period_ps is not None and args.synthesis_engine != 'yosys':
        parser.error('--abc-clock-period-ps requires --synthesis-engine yosys')
    if args.yosys_preserve_hierarchy and (args.synthesis_engine != 'yosys' or args.stage != 'synthesis'):
        parser.error('--yosys-preserve-hierarchy requires Yosys synthesis-only qualification')
    args.output.mkdir(parents=True, exist_ok=False)
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
    sram = ROOT / 'depends/asap7_sram_0p0/generated'
    macros = sorted(set(re.findall(r'\bsrambank_\w+', source)))
    libs = [sram / 'LIB' / (name + '.lib') for name in macros]
    lefs = [sram / 'LEF' / (name + '.lef') for name in macros]
    for path in files + libs + lefs:
        if not path.is_file():
            raise SystemExit(f'Missing input: {path}')
    chipagent = Path(os.environ.get('CHIPAGENT_DIR', ROOT.parent / 'chipagent'))
    sys.path.insert(0, str(chipagent))
    from chipagent.models import TaskObject
    from chipagent.tools.base import ToolContext
    from chipagent.tools.phys_flow_asap7 import ASAP7PhysicalFlowTool
    inputs = dict(reg_code='', rtl_files=[str(p) for p in files],
        module_name=args.module, clock_port=args.clock, clock_period=args.period_ps,
        io_delay_percent=args.io_delay, io_false_path_ports=None,
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
        'scripts/qualify_ppa.py', 'timing/asap7'], cwd=ROOT, text=True).splitlines()
    manifest = dict(timestamp=datetime.now(timezone.utc).isoformat(),
        commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        dirty=bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT)),
        chipagent_commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=chipagent, text=True).strip(),
        source_sha256={name:sha(ROOT / name) for name in tracked},
        emitted_sha256={str(p.relative_to(rtl)):sha(p) for p in files},
        macro_sha256={str(p.relative_to(ROOT)):sha(p) for p in libs + lefs},
        scope='synthesis estimate' if args.stage == 'synthesis' else 'post-route; includes IO timing',
        inputs=inputs)
    hierarchy = None
    if args.yosys_preserve_hierarchy:
        from chipagent.toolchain import configured_openroad_image
        hierarchy = prepare_hierarchy_image(configured_openroad_image(), args.output)
        manifest['hierarchy_profile'] = hierarchy
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    previous_image = os.environ.get('CHIPAGENT_OPENROAD_IMAGE')
    try:
        if hierarchy:
            os.environ['CHIPAGENT_OPENROAD_IMAGE'] = hierarchy['image_id']
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
        gate['passed'] = gate['passed'] and mapping['passed']
        (args.output / 'qualification.json').write_text(json.dumps(gate, indent=2) + '\n')
        print(json.dumps({'synthesis_setup_gate': gate}), flush=True)
        if not gate['passed']:
            raise SystemExit(2)
    else:
        gate = post_route_gate(result.result)
        gate['mapping'] = mapping
        gate['passed'] = gate['passed'] and mapping['passed']
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
