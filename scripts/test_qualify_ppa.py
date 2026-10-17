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


def test_cli_rejects_io_failure_and_preserves_raw_tool_result(tmp_path, monkeypatch):
    rtl = tmp_path / 'rtl'
    rtl.mkdir()
    (rtl / 'Top.sv').write_text('module Top(input clock); endmodule\n')
    report = tmp_path / 'synthesis_sta.log'
    report.write_text('101.675 slack (MET)\n-1.068 slack (VIOLATED)\n')
    payload = dict(status='success', overview=dict(verdict='PASS'),
                   artifacts={'synthesis_sta.log': str(report)},
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
    assert json.loads((output / 'result.json').read_text()) == payload
