import json
from pathlib import Path

import pytest

import qualify_native_owner_ppa as q
import test_prepare_native_owner_flashsim as owner_tests


@pytest.fixture
def owner_fixture(tmp_path, monkeypatch):
    monkeypatch.setattr(q, 'ROOT', tmp_path)
    prepared = tmp_path / '.artifacts/prepared'
    prepared.mkdir(parents=True)
    rtl = prepared / 'rtl'
    (rtl / 'verification/assert').mkdir(parents=True)
    (rtl / 'GpuHostSystemAxi.sv').write_text('module GpuHostSystemAxi(input io_s_axi_aclk); endmodule\n')
    (rtl / 'verification/assert/layers-owner.sv').write_text('// binding fixture\n')
    manifest = prepared / 'owner-manifest.json'
    manifest.write_text(json.dumps(owner_tests.OwnerInputTests().manifest()))
    sources = {'factory': 'original'}
    monkeypatch.setattr(q.owner, 'source_inputs', lambda: dict(sources))
    record = dict(version=1, exit_code=0, source_sha256=dict(sources),
                  manifest_sha256=q.ppa.sha(manifest), rtl_sha256=q.owner.common.tree_hashes(rtl))
    (prepared / 'preparation.json').write_text(json.dumps(record))
    monkeypatch.setattr(q, 'inventory', lambda directory, top: dict(q.MACROS))
    tools = {'tool': 'original'}
    monkeypatch.setattr(q, 'tool_inputs', lambda root: dict(tools))
    monkeypatch.setattr(q, 'owner_prepared', lambda root, directory: q.owner.prepared(directory))
    return prepared, tmp_path / '.artifacts/physical', sources, tools


@pytest.mark.parametrize('mutation', [None, 'source', 'rtl', 'manifest', 'assertion', 'tools', 'preparation'])
def test_owner_binding_rejects_mutation_and_retires_passing_gate(owner_fixture, monkeypatch, mutation):
    prepared, output, sources, tools = owner_fixture
    called = []

    def flow(arguments):
        called.append(arguments)
        assert arguments[:4] == [str(prepared / 'rtl'), 'GpuHostSystemAxi', '--clock', 'io_s_axi_aclk']
        assert 'srambank_64x4x32_6t122=752' in arguments
        assert 'srambank_64x4x64_6t122=992' in arguments
        output.mkdir()
        (output / 'qualification.json').write_text('{"passed": true}\n')
        if mutation == 'source': sources['factory'] = 'changed'
        if mutation == 'tools': tools['tool'] = 'changed'
        if mutation == 'rtl': (prepared / 'rtl/GpuHostSystemAxi.sv').write_text('changed')
        if mutation == 'manifest': (prepared / 'owner-manifest.json').write_text('{}')
        if mutation == 'assertion': (prepared / 'rtl/verification/assert/layers-owner.sv').unlink()
        if mutation == 'preparation': (prepared / 'preparation.json').write_text('{}')

    monkeypatch.setattr(q.ppa, 'main', flow)
    if mutation is None:
        binding = q.qualify(prepared, output, ['--stage', 'route'])
        assert binding['profile'] == 'two-cu-vga60-owner-v1'
        assert binding['source_and_tools_unchanged'] and binding['fpsQualified'] is False
        assert json.loads((output / 'owner-physical-qualification.json').read_text()) == binding
    else:
        with pytest.raises((RuntimeError, KeyError)):
            q.qualify(prepared, output, [])
        assert not (output / 'qualification.json').exists()
        assert not (output / 'owner-physical-qualification.json').exists()
    assert len(called) == 1


@pytest.mark.parametrize('failure', ['old-profile', 'wrong-capacity', 'wrong-macro', 'existing-output'])
def test_wrong_owner_input_cannot_start_physical_flow(owner_fixture, monkeypatch, failure):
    prepared, output, _, _ = owner_fixture
    if failure in ('old-profile', 'wrong-capacity'):
        path = prepared / 'owner-manifest.json'
        manifest = json.loads(path.read_text())
        manifest['resourceDiscoveryWords' if failure == 'old-profile' else 'payloadBytes'] = 72
        path.write_text(json.dumps(manifest))
    if failure == 'wrong-macro':
        monkeypatch.setattr(q, 'inventory', lambda *args: {**q.MACROS, 'srambank_64x4x64_6t122': 1232})
    if failure == 'existing-output': output.mkdir()
    monkeypatch.setattr(q.ppa, 'main', lambda args: pytest.fail('Rejected input started a physical flow'))
    with pytest.raises(RuntimeError): q.qualify(prepared, output, [])


def test_failed_flow_still_checks_owner_and_never_binds_success(owner_fixture, monkeypatch):
    prepared, output, _, tools = owner_fixture
    def failed(arguments):
        output.mkdir()
        (output / 'qualification.json').write_text('{"passed": true}')
        tools['tool'] = 'changed'
        raise SystemExit(2)
    monkeypatch.setattr(q.ppa, 'main', failed)
    with pytest.raises(RuntimeError, match='changed during'): q.qualify(prepared, output, [])
    assert not (output / 'qualification.json').exists()


def test_cross_checkout_guard_uses_frozen_owner_entrypoint_not_its_own_sources(tmp_path, monkeypatch):
    frozen = tmp_path / 'frozen-owner'
    frozen.mkdir()
    prepared = frozen / '.artifacts/prepared'
    prepared.mkdir(parents=True)
    (prepared / 'preparation.json').write_text('{"bound": "frozen"}')
    calls = []
    def run(command, **kwargs):
        calls.append((command, kwargs))
        assert command[2] == str(frozen / 'scripts/prepare_native_owner_flashsim.py')
        assert command[-1] == str(prepared)
        assert kwargs['cwd'] == frozen and kwargs['check']
        assert kwargs['env']['PYTHONDONTWRITEBYTECODE'] == '1'
        import types
        return types.SimpleNamespace(stdout='NATIVE_OWNER_HOST_INPUTS_PASS profile=' + q.owner.PROFILE +
                                     ' gpuExecution=false clockQualified=false fpsQualified=false\n')
    monkeypatch.setattr(q.subprocess, 'run', run)
    assert q.owner_prepared(frozen, prepared) == {'bound': 'frozen'}
    assert len(calls) == 1


def test_failed_flow_with_unchanged_sources_cannot_leave_a_success_gate(owner_fixture, monkeypatch):
    prepared, output, _, _ = owner_fixture
    def failed(arguments):
        output.mkdir()
        (output / 'qualification.json').write_text('{"passed": true}')
        raise SystemExit(2)
    monkeypatch.setattr(q.ppa, 'main', failed)
    with pytest.raises(SystemExit): q.qualify(prepared, output, [])
    assert not (output / 'qualification.json').exists()


@pytest.mark.parametrize('mutation', ['wrong-pin', 'dirty-collateral', 'changed-lib'])
def test_physical_tools_bind_the_pinned_clean_owner_sram(tmp_path, monkeypatch, mutation):
    frozen, chipagent = tmp_path / 'owner', tmp_path / 'chipagent'
    chipagent.mkdir()
    (chipagent / 'agent.py').write_text('tool = 1\n')
    (frozen / 'scripts').mkdir(parents=True)
    for name in ('prepare_native_owner_flashsim.py', 'qualify_native_product_compiled.py',
                 'qualify_native_product_flashsim.py'):
        (frozen / 'scripts' / name).write_text('bound = 1\n')
    sram = frozen / 'depends/asap7_sram_0p0'
    for name in q.MACROS:
        for folder, suffix in (('LIB', 'lib'), ('LEF', 'lef')):
            path = sram / 'generated' / folder / (name + '.' + suffix)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('physical collateral')
    monkeypatch.setenv('CHIPAGENT_DIR', str(chipagent))
    def git(arguments, **kwargs):
        if arguments[1] == 'ls-files': return 'agent.py\n'
        if arguments[1] == 'rev-parse': return ('wrong' if mutation == 'wrong-pin' else q.SRAM_COMMIT) + '\n'
        assert arguments[1] == 'status'
        return ' M generated/LIB/srambank_64x4x32_6t122.lib\n' if mutation == 'dirty-collateral' else ''
    monkeypatch.setattr(q.subprocess, 'check_output', git)
    if mutation == 'changed-lib':
        before = q.tool_inputs(frozen)
        (sram / 'generated/LIB/srambank_64x4x32_6t122.lib').write_text('changed during flow')
        assert q.tool_inputs(frozen) != before
    else:
        with pytest.raises(RuntimeError): q.tool_inputs(frozen)


@pytest.mark.parametrize('receipt', ['', 'NATIVE_OWNER_HOST_INPUTS_PASS profile=two-cu-vga60-v2\n', 'duplicate'])
def test_cross_checkout_zero_exit_without_exact_owner_receipt_is_rejected(tmp_path, monkeypatch, receipt):
    import types
    if receipt == 'duplicate':
        receipt = ('NATIVE_OWNER_HOST_INPUTS_PASS profile=' + q.owner.PROFILE +
                   ' gpuExecution=false clockQualified=false fpsQualified=false\n') * 2
    monkeypatch.setattr(q.subprocess, 'run', lambda *args, **kwargs: types.SimpleNamespace(stdout=receipt))
    with pytest.raises(RuntimeError, match='did not confirm'): q.owner_prepared(tmp_path, tmp_path)
