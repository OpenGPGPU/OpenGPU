import importlib.util
import json
from pathlib import Path
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


def test_synthesis_mapping_requires_explicit_evidence():
    assert not qualify.synthesis_mapping_gate('')['passed']
    assert not qualify.synthesis_mapping_gate('Timing passed without a mapping report')['passed']
    assert qualify.synthesis_mapping_gate('Export to ODB: created 100 instances, 120 nets')['passed']
    assert qualify.synthesis_mapping_gate('ABC RESULTS: 100 NAND cells')['passed']


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


def test_cli_rejects_io_failure_and_preserves_raw_tool_result(tmp_path, monkeypatch):
    rtl = tmp_path / 'rtl'
    rtl.mkdir()
    (rtl / 'Top.sv').write_text('module Top(input clock); endmodule\n')
    report = tmp_path / 'synthesis_sta.log'
    report.write_text('101.675 slack (MET)\n-1.068 slack (VIOLATED)\n')
    mapping_log = tmp_path / 'synthesis.log'
    mapping_log.write_text('Export to ODB: created 100 instances\n')
    payload = dict(status='success', overview=dict(verdict='PASS'),
                   artifacts={'synthesis_sta.log': str(report), 'run.log': str(mapping_log)},
                   report_tail='101.675 slack (MET)\n')
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
    monkeypatch.setattr(qualify.sys, 'argv',
                        ['qualify_ppa.py', str(rtl), 'Top', '--output', str(output)])
    with pytest.raises(SystemExit) as error:
        qualify.main()
    assert error.value.code == 2
    assert not json.loads((output / 'qualification.json').read_text())['passed']
    assert json.loads((output / 'qualification.json').read_text())['mapping']['passed']
    assert json.loads((output / 'result.json').read_text()) == payload
