import importlib.util
import json
from pathlib import Path
import subprocess
import types

import pytest


spec = importlib.util.spec_from_file_location(
    'qualify_ppa', Path(__file__).with_name('qualify_ppa.py'))
qualify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qualify)


def test_positive_core_slack_does_not_hide_failed_io_path():
    # The actual bounded texture reply-flow screen passed the tool's
    # core-only overview while its virtual-clock endpoint failed setup.
    report = '''Path Group: core_clock
          101.675   slack (MET)
Path Group: vclk_core_clock
           -1.068   slack (VIOLATED)
'''
    gate = qualify.synthesis_setup_gate(report)
    assert not gate['passed']
    assert gate['reported_setup_slacks_ps'] == [101.675, -1.068]


def test_positive_core_and_io_slack_pass():
    report = '''Path Group: core_clock
          290.722   slack (MET)
Path Group: vclk_core_clock
          240.605   slack (MET)
'''
    assert qualify.synthesis_setup_gate(report)['passed']


def test_violation_rounded_to_zero_still_fails():
    assert not qualify.synthesis_setup_gate('  -0.000 slack (VIOLATED)\n')['passed']


def test_missing_setup_paths_cannot_qualify_a_design():
    assert not qualify.synthesis_setup_gate('No paths found.\n')['passed']


def test_constraint_coverage_requires_a_complete_empty_report():
    begin = 'OPENGPU_CHECK_SETUP_BEGIN\n'
    end = 'OPENGPU_CHECK_SETUP_END\n'
    assert qualify.synthesis_constraint_gate(begin + end, 0)['passed']
    for report, code in [
        ('116.194 slack (MET)\n', 0),
        (begin + 'Warning: There are 32 unclocked register/latch pins.\n' + end, 0),
        (begin + 'Warning: There are 16 unconstrained endpoints.\n' + end, 0),
        (begin + end + end, 0),
        (end + begin, 0),
        ('[ERROR STA-0001] failed\n' + begin + end, 0),
        (begin + end, 1),
    ]:
        assert not qualify.synthesis_constraint_gate(report, code)['passed']


@pytest.mark.parametrize('failure', ['input-mutation', 'timeout'])
@pytest.mark.parametrize('memory_gib', [6, 24, None])
def test_constraint_coverage_reuses_inputs_and_fails_safely(tmp_path, monkeypatch, failure, memory_gib):
    flow = '/OpenROAD-flow-scripts/flow'
    output = tmp_path / 'proof'
    work = output / 'flow/orfs-work'
    results = work / 'results/base'
    results.mkdir(parents=True)
    odb, sdc = results / '1_synth.odb', results / '1_synth.sdc'
    odb.write_text('original odb')
    sdc.write_text('original sdc')
    sta = output / 'flow/synthesis_sta.tcl'
    sta.write_text(f'read_db {flow}/results/asap7/Top/base/1_synth.odb\n'
                   f'read_sdc {flow}/results/asap7/Top/base/1_synth.sdc\n'
                   'report_checks -path_delay max -digits 3\n')
    image = 'sha256:' + 'a' * 64
    result = dict(module_name='Top', artifacts={'synthesis_sta.tcl': str(sta)},
                  reproducibility={'openroad_image': image},
                  command=['docker', 'run', '--rm', '-v',
                           str(work / 'results') + f':{flow}/results/asap7/Top',
                           '-v', str(sta) + ':/tmp/synthesis_sta.tcl', '-w', flow,
                           image, 'openroad', '/tmp/synthesis_sta.tcl'])
    called = []

    def run(command, **kwargs):
        called.append(command)
        if command[:2] == ['docker', 'stop']:
            assert failure == 'timeout' and command[-1].startswith('opengpu-coverage-')
            return types.SimpleNamespace(returncode=0, stdout='', stderr='')
        assert '--cpus=1' in command
        if memory_gib is None:
            assert not any(arg.startswith(('--memory=', '--memory-swap=')) for arg in command)
        else:
            assert f'--memory={memory_gib}g' in command
            assert f'--memory-swap={memory_gib}g' in command
        assert str(output / 'constraint_coverage.tcl') + ':/tmp/synthesis_sta.tcl' in command
        assert command[-7:-5] == ['timeout', '30'] and kwargs['timeout'] == 50
        if failure == 'timeout':
            raise subprocess.TimeoutExpired(command, 50, output=b'partial output')
        odb.write_text('changed during check')
        return types.SimpleNamespace(returncode=0, stdout='OPENGPU_CHECK_SETUP_BEGIN\nOPENGPU_CHECK_SETUP_END\n', stderr='')

    monkeypatch.setattr(qualify.subprocess, 'run', run)
    gate = qualify.synthesis_constraint_proof(result, output, 30, memory_gib)
    assert not gate['passed']
    if failure == 'input-mutation':
        assert len(called) == 1 and not gate['inputs_unchanged']
    else:
        assert len(called) == 2 and gate['returncode'] == 124 and gate['inputs_unchanged']


def test_constraint_coverage_cannot_run_without_original_sta_evidence(tmp_path):
    gate = qualify.synthesis_constraint_proof(dict(module_name='Top'), tmp_path, 30)
    assert not gate['passed'] and 'STA command' in gate['reason']


def test_coverage_resource_arguments():
    assert qualify.coverage_memory('unlimited') is None
    assert qualify.coverage_memory('24') == 24
    assert qualify.positive_integer('7200') == 7200
    for invalid in ('0', '-1', '1.5', 'garbage'):
        with pytest.raises(qualify.argparse.ArgumentTypeError):
            qualify.coverage_memory(invalid)


def test_synthesis_mapping_requires_explicit_evidence():
    assert not qualify.synthesis_mapping_gate('')['passed']
    assert not qualify.synthesis_mapping_gate('Timing passed without a mapping report')['passed']
    assert qualify.synthesis_mapping_gate('Export to ODB: created 100 instances, 120 nets')['passed']
    assert qualify.synthesis_mapping_gate('ABC RESULTS: 100 NAND cells')['passed']


def test_mapping_proof_reads_the_yosys_mapping_log(tmp_path):
    log = tmp_path / 'flow/orfs-work/logs/base/1_2_yosys.log'
    log.parent.mkdir(parents=True)
    log.write_text('ABC RESULTS: 100 NAND cells\n')
    gate = qualify.mapping_proof({}, tmp_path)
    assert gate['passed']
    assert gate['log'] == str(log)


def test_mapping_proof_uses_separate_yosys_statistics(tmp_path):
    logs = tmp_path / 'flow/orfs-work/logs/base'
    reports = tmp_path / 'flow/orfs-work/reports/base'
    logs.mkdir(parents=True)
    reports.mkdir(parents=True)
    (logs / '1_synth.log').write_text('OpenROAD\nlink_design Top\n')
    (logs / '1_2_yosys.log').write_text('15.1.1. Executed ABC.\n')
    stats = reports / 'synth_stat.txt'
    stats.write_text('=== Top ===\n 100 1.25E+04 100 1.25E+04 cells\n'
                     ' 100 1.25E+04 100 1.25E+04 AND2x2_ASAP7_75t_SL\n')
    gate = qualify.mapping_proof({}, tmp_path)
    assert gate['passed']
    assert gate['log'] == str(stats)
    assert len(gate['logs']) == 3


@pytest.mark.parametrize('source', ['1_synth.log', '1_2_yosys.log'])
def test_mapping_proof_retains_failures_in_every_engine_log(tmp_path, source):
    logs = tmp_path / 'flow/orfs-work/logs/base'
    logs.mkdir(parents=True)
    (logs / '1_synth.log').write_text('Export to ODB: created 100 instances\n')
    (logs / '1_2_yosys.log').write_text('Number of cells: 100\n')
    with (logs / source).open('a') as out:
        out.write('unmapped cell $div\n')
    assert not qualify.mapping_proof({}, tmp_path)['passed']


@pytest.mark.parametrize('row', ['1 - 1 - $div', '1 - 0 - $div', '1 $div'])
def test_yosys_statistics_reject_internal_operators(row):
    report = ' 100 1.25E+04 100 1.25E+04 cells\n ' + row + '\n'
    gate = qualify.synthesis_mapping_gate(report)
    assert gate['mapping_report_present']
    assert not gate['passed']
    assert gate['unresolved_diagnostics'] == [row]


def test_yosys_execution_message_is_not_mapping_evidence():
    assert not qualify.synthesis_mapping_gate('15.1.1. Executed ABC.\n')['passed']


@pytest.mark.parametrize('operator', ['sdiv_trunc', 'udiv', 'unknown_operator'])
def test_skipped_operators_fail_even_when_a_netlist_was_exported(operator):
    log = f"Export to ODB: created 100 instances\n[WARNING SYN-0030] Export to ODB: skipping unexpected instance type '{operator}'\n"
    gate = qualify.synthesis_mapping_gate(log)
    assert not gate['passed']
    assert gate['skipped_instance_types'] == [operator]
    assert gate['skipped_instance_count'] == 1


@pytest.mark.parametrize('diagnostic', ['Warning: Wire Top.foo is used but has no driver.',
                                      'unmapped cell $div', 'Found and reported 3 problems.'])
def test_unresolved_logic_fails_mapping(diagnostic):
    assert not qualify.synthesis_mapping_gate('ABC RESULTS: 100 NAND cells\n' + diagnostic)['passed']


def routed_result():
    return dict(status='success', overview=dict(verdict='PASS'), qor=dict(
        setup_worst_slack_ps=20.0, hold_worst_slack_ps=1.0,
        setup_violation_count=0, hold_violation_count=0, route_drc_errors=0,
        antenna_violating_nets=0, antenna_violating_pins=0, placement_violations=0, flow_errors=0))


def test_route_requires_explicit_global_setup_hold_and_physical_metrics():
    assert qualify.post_route_gate(routed_result())['passed']
    assert not qualify.post_route_gate(dict(status='success', overview=dict(verdict='PASS')))['passed']
    result = routed_result()
    del result['qor']['hold_worst_slack_ps']
    assert 'hold_worst_slack_ps' in qualify.post_route_gate(result)['missing_metrics']


@pytest.mark.parametrize('metric,value', [
    ('setup_worst_slack_ps', -1), ('hold_worst_slack_ps', -0.1),
    ('setup_violation_count', 1), ('hold_violation_count', 1),
    ('route_drc_errors', 1), ('antenna_violating_nets', 1),
    ('antenna_violating_pins', 1), ('placement_violations', 1),
    ('flow_errors', 1), ('hold_worst_slack_ps', float('nan')),
])
def test_route_cannot_hide_failed_or_invalid_checks_behind_a_pass_verdict(metric, value):
    result = routed_result()
    result['qor'][metric] = value
    assert not qualify.post_route_gate(result)['passed']


@pytest.mark.parametrize('margin', [0.0, 0.5, 30.0])
def test_cli_rejects_io_failure_and_preserves_raw_tool_result(tmp_path, monkeypatch, margin):
    monkeypatch.setattr(qualify, 'pin_openroad_image', lambda: 'sha256:' + 'a' * 64)
    rtl = tmp_path / 'rtl'
    rtl.mkdir()
    (rtl / 'Top.sv').write_text('module Top(input clock); endmodule\n')
    report = tmp_path / 'synthesis_sta.log'
    report.write_text('101.675 slack (MET)\n-1.068 slack (VIOLATED)\n')
    mapping_log = tmp_path / 'synthesis.log'
    mapping_log.write_text('Export to ODB: created 100 instances\n')
    payload = dict(status='success', overview=dict(verdict='PASS'),
                   artifacts={'synthesis_sta.log': str(report), 'run.log': str(mapping_log)},
                   reproducibility={'openroad_image': 'sha256:' + 'a' * 64},
                   report_tail='101.675 slack (MET)\n')
    captured_inputs = {}

    def run(context):
        captured_inputs.update(context.inputs)
        return types.SimpleNamespace(result=payload)

    modules = {
        'chipagent': types.ModuleType('chipagent'),
        'chipagent.models': types.SimpleNamespace(TaskObject=types.SimpleNamespace),
        'chipagent.tools': types.ModuleType('chipagent.tools'),
        'chipagent.tools.base': types.SimpleNamespace(ToolContext=types.SimpleNamespace),
        'chipagent.tools.phys_flow_asap7': types.SimpleNamespace(
            ASAP7PhysicalFlowTool=lambda: types.SimpleNamespace(
                run=run)),
    }
    for name, module in modules.items():
        monkeypatch.setitem(qualify.sys.modules, name, module)
    monkeypatch.setattr(qualify, 'ROOT', tmp_path)
    monkeypatch.setattr(qualify.subprocess, 'check_output',
                        lambda args, **kwargs: 'abc123\n' if args[1] == 'rev-parse' else '')
    output = tmp_path / 'proof'
    monkeypatch.setattr(qualify.sys, 'argv',
                        ['qualify_ppa.py', str(rtl), 'Top', '--output', str(output),
                         '--setup-slack-margin-ps', str(margin)])
    with pytest.raises(SystemExit) as error:
        qualify.main()
    assert error.value.code == 2
    assert not json.loads((output / 'qualification.json').read_text())['passed']
    assert json.loads((output / 'qualification.json').read_text())['mapping']['passed']
    assert json.loads((output / 'result.json').read_text()) == payload
    inputs = json.loads((output / 'manifest.json').read_text())['inputs']
    assert inputs['setup_slack_margin'] == margin == captured_inputs['setup_slack_margin']
    assert inputs['clock_period'] == 1000
    assert inputs['io_delay_percent'] == 0.25
    assert inputs['io_false_path_ports'] is None
    assert inputs['enable_retiming'] is False


def test_cli_rejects_positive_slack_without_constraint_evidence(tmp_path, monkeypatch):
    monkeypatch.setattr(qualify, 'pin_openroad_image', lambda: 'sha256:' + 'a' * 64)
    rtl = tmp_path / 'rtl'
    rtl.mkdir()
    (rtl / 'Top.sv').write_text('module Top(input clock); endmodule\n')
    report = tmp_path / 'synthesis_sta.log'
    report.write_text('116.194 slack (MET)\n')
    mapping_log = tmp_path / 'synthesis.log'
    mapping_log.write_text('Export to ODB: created 100 instances\n')
    payload = dict(status='success', module_name='Top', overview=dict(verdict='PASS'),
                   reproducibility={'openroad_image': 'sha256:' + 'a' * 64},
                   artifacts={'synthesis_sta.log': str(report), 'run.log': str(mapping_log)})
    modules = {
        'chipagent': types.ModuleType('chipagent'),
        'chipagent.models': types.SimpleNamespace(TaskObject=types.SimpleNamespace),
        'chipagent.tools': types.ModuleType('chipagent.tools'),
        'chipagent.tools.base': types.SimpleNamespace(ToolContext=types.SimpleNamespace),
        'chipagent.tools.phys_flow_asap7': types.SimpleNamespace(
            ASAP7PhysicalFlowTool=lambda: types.SimpleNamespace(
                run=lambda context: types.SimpleNamespace(result=payload))),
    }
    for name, module in modules.items():
        monkeypatch.setitem(qualify.sys.modules, name, module)
    monkeypatch.setattr(qualify, 'ROOT', tmp_path)
    monkeypatch.setattr(qualify.subprocess, 'check_output',
                        lambda args, **kwargs: 'abc123\n' if args[1] == 'rev-parse' else '')
    output = tmp_path / 'proof'
    monkeypatch.setattr(qualify.sys, 'argv', ['qualify_ppa.py', str(rtl), 'Top', '--output', str(output)])
    with pytest.raises(SystemExit) as error:
        qualify.main()
    gate = json.loads((output / 'qualification.json').read_text())
    assert error.value.code == 2 and gate['setup_passed'] and gate['mapping']['passed']
    assert not gate['constraint_coverage']['passed'] and not gate['passed']


@pytest.mark.parametrize('margin', ['-1', 'nan', 'inf', '-inf'])
def test_invalid_repair_margin_cannot_start_a_physical_run(tmp_path, monkeypatch, margin):
    output = tmp_path / 'never-created'
    monkeypatch.setattr(qualify.sys, 'argv', [
        'qualify_ppa.py', str(tmp_path / 'rtl'), 'Top', '--output', str(output),
        '--setup-slack-margin-ps=' + margin])
    with pytest.raises(SystemExit) as error:
        qualify.main()
    assert error.value.code == 2
    assert not output.exists()


M32, M64 = 'srambank_64x4x32_6t122', 'srambank_64x4x64_6t122'
IMAGE = 'sha256:' + 'a' * 64


def macro_report(rows=''):
    return 'OPENGPU_SRAM_MACROS_BEGIN\n' + rows + 'OPENGPU_SRAM_MACROS_END\n'


def test_macro_counts_are_exact_per_type_and_allow_explicit_zero():
    assert qualify.expected_sram_macro(M32 + '=0') == (M32, 0)
    report = macro_report(f'OPENGPU_SRAM_MACRO {M32} 2 2\nOPENGPU_SRAM_MACRO {M64} 1 1\n')
    assert qualify.sram_macro_gate(report, 0, {M32: 2, M64: 1})['passed']
    assert qualify.sram_macro_gate(macro_report(), 0, {M32: 0})['passed']
    assert qualify.sram_macro_gate(macro_report(), 0, {})['passed']
    assert not qualify.sram_macro_gate(report, 0, {})['passed']


@pytest.mark.parametrize('report,code', [
    ('', 0), ('OPENGPU_SRAM_MACROS_BEGIN\n', 0),
    (macro_report() * 2, 0),
    ('OPENGPU_SRAM_MACROS_END\nOPENGPU_SRAM_MACROS_BEGIN\n', 0),
    (macro_report(), 0),  # both expected macros disappeared
    (macro_report(f'OPENGPU_SRAM_MACRO {M32} 1 1\n'), 0),
    (macro_report(f'OPENGPU_SRAM_MACRO {M32} 1 1\nOPENGPU_SRAM_MACRO {M64} 2 2\n'), 0),
    (macro_report(f'OPENGPU_SRAM_MACRO {M32} 2 1\nOPENGPU_SRAM_MACRO {M64} 1 1\n'), 0),
    (macro_report(f'OPENGPU_SRAM_MACRO {M32} 2 3\nOPENGPU_SRAM_MACRO {M64} 1 1\n'), 0),
    (macro_report(f'OPENGPU_SRAM_MACRO {M32} 2 2\nOPENGPU_SRAM_MACRO other_macro 1 1\n'), 0),
    (macro_report(f'OPENGPU_SRAM_MACRO {M32} 2 2\nOPENGPU_SRAM_MACRO {M32} 2 2\n'), 0),
    (macro_report(f'OPENGPU_SRAM_MACRO {M32} 0 0\n'), 0),
    (macro_report(f'OPENGPU_SRAM_MACRO {M32} 2 2 garbage\n'), 0),
    (macro_report('Warning: incomplete traversal\n'), 0),
    (macro_report(f'OPENGPU_SRAM_MACRO {M32} 2 2\nOPENGPU_SRAM_MACRO {M64} 1 1\n'), 1),
    ('[ERROR ODB-0001] failed\n' + macro_report(f'OPENGPU_SRAM_MACRO {M32} 2 2\nOPENGPU_SRAM_MACRO {M64} 1 1\n'), 0),
])
def test_missing_dropped_retyped_nonphysical_or_incomplete_macros_fail(report, code):
    assert not qualify.sram_macro_gate(report, code, {M32: 2, M64: 1})['passed']


@pytest.mark.parametrize('expected', [None, [], {M32: True}, {M32: -1}, {M32: '2'}, {'bad name': 2}])
def test_invalid_macro_contract_cannot_pass(expected):
    assert not qualify.sram_macro_gate(macro_report(), 0, expected)['passed']


@pytest.mark.parametrize('value', ['bad name=1', 'macro=-1', 'macro=1.5', 'macro=true', 'macro=01', 'macro='])
def test_macro_arguments_reject_ambiguous_counts(value):
    with pytest.raises(qualify.argparse.ArgumentTypeError):
        qualify.expected_sram_macro(value)


def odb_fixture(tmp_path, route=False):
    output = tmp_path / 'proof'
    work = output / 'flow/orfs-work'
    results = work / 'results/base'
    results.mkdir(parents=True)
    odb = results / ('6_final.odb' if route else '1_synth.odb')
    odb.write_text('original odb')
    sdc = results / '1_synth.sdc'
    sdc.write_text('original sdc')
    macro_dir = work / 'designs/asap7/Top/macros'
    macro_dir.mkdir(parents=True)
    collateral = {}
    for ext in ('lib', 'lef'):
        p = macro_dir / f'{M32}.{ext}'
        p.write_text('original ' + ext)
        collateral[p.name] = qualify.sha(p)
    flow = '/OpenROAD-flow-scripts/flow'
    volumes = ['-v', str(work / 'results') + f':{flow}/results/asap7/Top',
               '-v', str(work / 'designs/asap7/Top') + f':{flow}/designs/asap7/Top']
    if route:
        artifacts = {'final_odb': str(odb)}
        command = ['docker', 'run', '--rm', *volumes, IMAGE, 'bash', '-lc', 'make route']
    else:
        sta = output / 'flow/synthesis_sta.tcl'
        sta.write_text(f'read_db {flow}/results/asap7/Top/base/1_synth.odb\n'
                       f'read_sdc {flow}/results/asap7/Top/base/1_synth.sdc\n'
                       'report_checks -path_delay max -digits 3\n')
        artifacts = {'synthesis_sta.tcl': str(sta)}
        command = ['docker', 'run', '--rm', *volumes, '-v', str(sta) + ':/tmp/synthesis_sta.tcl',
                   IMAGE, 'openroad', '/tmp/synthesis_sta.tcl']
    return output, work, dict(module_name='Top', artifacts=artifacts, command=command,
                             reproducibility={'openroad_image': IMAGE}), collateral


@pytest.mark.parametrize('route', [False, True])
@pytest.mark.parametrize('mutation', [None, 'odb', 'lib', 'lef', 'script', 'drop', 'retype'])
def test_actual_odb_proof_binds_counts_collateral_and_inputs(tmp_path, monkeypatch, route, mutation):
    output, work, result, collateral = odb_fixture(tmp_path, route)
    calls = []

    def run(command, **kwargs):
        calls.append(command)
        assert '--cpus=1' in command and '--memory=1g' in command and '--memory-swap=1g' in command
        assert IMAGE in command and kwargs['timeout'] == 50
        script = output / ('route_macro_check.tcl' if route else 'constraint_coverage.tcl')
        source = script.read_text()
        assert 'getInsts' in source and 'isBlock' in source
        assert source.count('OPENGPU_SRAM_MACROS_BEGIN') == 1
        assert '6_final.odb' in source if route else '1_synth.odb' in source
        if mutation == 'odb':
            (work / 'results/base' / ('6_final.odb' if route else '1_synth.odb')).write_text('changed odb')
        elif mutation == 'lib':
            (work / f'designs/asap7/Top/macros/{M32}.lib').write_text('changed lib')
        elif mutation == 'lef':
            (work / f'designs/asap7/Top/macros/{M32}.lef').write_text('changed lef')
        elif mutation == 'script':
            script.write_text(source + '\n# changed script\n')
        name, count = ('other_macro', 2) if mutation == 'retype' else (M32, 1 if mutation == 'drop' else 2)
        report = 'OPENGPU_CHECK_SETUP_BEGIN\nOPENGPU_CHECK_SETUP_END\n' + macro_report(f'OPENGPU_SRAM_MACRO {name} {count} {count}\n')
        return types.SimpleNamespace(returncode=0, stdout=report, stderr='')

    monkeypatch.setattr(qualify.subprocess, 'run', run)
    if route:
        gate = qualify.route_macro_proof(result, output, {M32: 2}, collateral, 30, 1)
    else:
        gate = qualify.synthesis_constraint_proof(result, output, 30, 1, {M32: 2}, collateral)
    assert gate['passed'] == (mutation is None)
    assert len(calls) == 1
    assert gate['inputs_unchanged'] == (mutation not in ('odb', 'lib', 'lef', 'script'))


@pytest.mark.parametrize('route', [False, True])
@pytest.mark.parametrize('mutation', ['missing-lib', 'changed-lib', 'wrong-image', 'wrong-mount', 'wrong-cfg-mount', 'overlap', 'wrong-odb'])
def test_odb_proof_rejects_wrong_inputs_before_start(tmp_path, monkeypatch, route, mutation):
    output, work, result, collateral = odb_fixture(tmp_path, route)
    lib = work / f'designs/asap7/Top/macros/{M32}.lib'
    if mutation == 'missing-lib': lib.unlink()
    elif mutation == 'changed-lib': lib.write_text('replacement lib')
    elif mutation == 'wrong-image': result['reproducibility']['openroad_image'] = 'sha256:' + 'b' * 64
    elif mutation == 'wrong-mount': result['command'][4] += '-replacement'
    elif mutation == 'wrong-cfg-mount': result['command'][6] += '-replacement'
    elif mutation == 'overlap': result['command'][3:3] = ['-v', '/replacement:/OpenROAD-flow-scripts/flow/results/asap7/Top/base']
    elif mutation == 'wrong-odb':
        if route: result['artifacts']['final_odb'] = str(work / 'results/base/1_synth.odb')
        else: (work / 'results/base/1_synth.odb').unlink()
    monkeypatch.setattr(qualify.subprocess, 'run', lambda *a, **k: pytest.fail('wrong inputs started Docker'))
    if route: gate = qualify.route_macro_proof(result, output, {M32: 2}, collateral, 30, 1)
    else: gate = qualify.synthesis_constraint_proof(result, output, 30, 1, {M32: 2}, collateral)
    assert not gate['passed']


def test_macro_contract_is_required_before_the_flow_starts(tmp_path, monkeypatch):
    rtl = tmp_path / 'rtl'
    rtl.mkdir()
    (rtl / 'Top.sv').write_text(f'module Top(input clock); {M32} memory(); endmodule\n')
    output = tmp_path / 'never-created'
    monkeypatch.setattr(qualify.sys, 'argv', ['qualify_ppa.py', str(rtl), 'Top', '--output', str(output)])
    with pytest.raises(SystemExit) as error:
        qualify.main()
    assert error.value.code == 2 and not output.exists()


def test_duplicate_macro_contract_cannot_override_an_expectation(tmp_path, monkeypatch):
    output = tmp_path / 'never-created'
    monkeypatch.setattr(qualify.sys, 'argv', ['qualify_ppa.py', str(tmp_path), 'Top', '--output', str(output),
        '--expected-sram-macro', M32 + '=2', '--expected-sram-macro', M32 + '=1'])
    with pytest.raises(SystemExit) as error:
        qualify.main()
    assert error.value.code == 2 and not output.exists()


@pytest.mark.parametrize('timeout,memory', [(0, 1), (True, 1), (30, 0), (30, True)])
@pytest.mark.parametrize('route', [False, True])
def test_invalid_check_resources_cannot_start_a_container(tmp_path, monkeypatch, timeout, memory, route):
    output, work, result, collateral = odb_fixture(tmp_path, route)
    monkeypatch.setattr(qualify.subprocess, 'run', lambda *a, **k: pytest.fail('invalid resource allocation started'))
    if route: gate = qualify.route_macro_proof(result, output, {M32: 2}, collateral, timeout, memory)
    else: gate = qualify.synthesis_constraint_proof(result, output, timeout, memory, {M32: 2}, collateral)
    assert not gate['passed']


@pytest.mark.parametrize('exception', [KeyboardInterrupt, SystemExit])
def test_interruption_stops_only_the_owned_check_container(monkeypatch, exception):
    calls = []
    def run(command, **kwargs):
        calls.append(command)
        if command[:2] == ['docker', 'stop']:
            assert command[-1] == calls[0][calls[0].index('--name') + 1]
            return types.SimpleNamespace(returncode=0, stdout='', stderr='')
        raise exception()
    monkeypatch.setattr(qualify.subprocess, 'run', run)
    with pytest.raises(exception):
        qualify.run_odb_check(['docker', 'run', '--rm', IMAGE, 'openroad', 'fixture.tcl'], 30, 1)
    assert len(calls) == 2
