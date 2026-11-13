#!/usr/bin/env python3
"""Verify a saved hierarchical Yosys canonical checkpoint's local provenance.

The result binds recorded source, RTL, tools and checkpoint bytes. It does not
qualify synthesis mapping, a linked design, physical timing or product FPS.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys

from split_yosys_rtlil import index_checkpoint, validate_hierarchy
from extract_emitted_rtl import inventory

ENVIRONMENT = dict(SYNTH_KEEP_MODULES='*', SYNTH_HIERARCHICAL='0', SYNTH_ARGS='-noshare',
                   SYNTH_MOCK_LARGE_MEMORIES='0', SYNTH_KEEP_MOCKED_MEMORIES='0',
                   SYNTH_GUT='0', SYNTH_BLACKBOXES='', SYNTH_RETIME_MODULES='')
IMAGE_ID = re.compile(r'sha256:[0-9a-f]{64}\Z')
IDENTIFIER = re.compile(r'[A-Za-z_][A-Za-z0-9_$]*\Z')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path, algorithm='sha256'):
    result = hashlib.new(algorithm)
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def git(root, *arguments):
    return subprocess.check_output(['git', *arguments], cwd=root, text=True).strip()


def clean_checkout(root, expected):
    root = Path(root).resolve()
    require(Path(git(root, 'rev-parse', '--show-toplevel')).resolve() == root,
            'Expected a Git worktree root: ' + str(root))
    require(git(root, 'rev-parse', 'HEAD') == expected, 'Checkout commit differs: ' + str(root))
    require(not git(root, 'status', '--porcelain', '--untracked-files=all'), 'Checkout is dirty: ' + str(root))
    return root


def within(root, relative):
    root = Path(root).resolve()
    relative = Path(relative)
    require(not relative.is_absolute() and '..' not in relative.parts, 'Unsafe recorded path: ' + str(relative))
    result = (root / relative).resolve()
    require(result.is_relative_to(root), 'Recorded path escapes checkout: ' + str(relative))
    return result


def verify_sources(root, recorded):
    names = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard',
                                     'src/main', 'build.sbt', 'scripts/qualify_ppa.py',
                                     'scripts/extract_emitted_rtl.py', 'scripts/report_ppa_sram_inventory.py',
                                     'timing/asap7'],
                                    cwd=root, text=True).splitlines()
    require(len(names) == len(set(names)) and set(names) == set(recorded), 'Source inventory differs')
    for name in names:
        require(sha(within(root, name)) == recorded[name], 'Source bytes differ: ' + name)


def exact_files(directory, names, suffixes=None):
    directory = Path(directory)
    if not directory.exists():
        require(not directory.is_symlink() and not names, 'Required input directory is missing: ' + str(directory))
        return
    require(directory.is_dir() and not directory.is_symlink(), 'Input directory is not a real directory')
    paths = [p for p in directory.iterdir() if suffixes is None or p.suffix in suffixes]
    require(all(p.is_file() and not p.is_symlink() for p in paths) and
            {p.name for p in paths} == set(names), 'Unexpected input files: ' + str(directory))


def verify_macro_collateral(source, design, macro_hashes, inputs):
    require(len({Path(name).name for name in macro_hashes}) == len(macro_hashes), 'Duplicate macro collateral name')
    exact_files(design / 'macros', {Path(name).name for name in macro_hashes})
    for name, expected in macro_hashes.items():
        path = within(source, name)
        require(path.suffix in {'.lib', '.lef'} and sha(path) == expected and
                sha(design / 'macros' / path.name) == expected, 'Macro collateral differs: ' + name)
    libs = [within(source, name) for name in macro_hashes if Path(name).suffix == '.lib']
    lefs = [within(source, name) for name in macro_hashes if Path(name).suffix == '.lef']
    require({p.stem for p in libs} == {p.stem for p in lefs}, 'Macro libraries and LEFs differ')
    require(sorted(p.name for p in libs) == sorted(Path(p).name for p in (inputs['macro_libs'] or [])) and
            sorted(p.name for p in lefs) == sorted(Path(p).name for p in (inputs['macro_lefs'] or [])),
            'Requested macro collateral differs')
    return libs, lefs


def verify_image(profile, inspect=subprocess.check_output):
    image, base = profile.get('image_id'), profile.get('base_image_id')
    require(isinstance(image, str) and IMAGE_ID.fullmatch(image) and
            isinstance(base, str) and IMAGE_ID.fullmatch(base), 'Unpinned tool image')
    require(profile.get('orfs_environment') == ENVIRONMENT and
            profile.get('tool_filesystem_layers_identical') is True and
            profile.get('abstracted_dut_modules') == [] and
            profile.get('memory_mocks') is False and profile.get('retiming') is False,
            'Unsafe recorded hierarchy profile')
    data = json.loads(inspect(['docker', 'image', 'inspect', image, base], text=True))
    require(len(data) == 2 and data[0]['Id'] == image and data[1]['Id'] == base,
            'Tool image IDs differ from the recorded profile')
    require(data[0]['RootFS']['Layers'] == data[1]['RootFS']['Layers'], 'Tool filesystem layers differ')
    entries = [item.split('=', 1) for item in data[0]['Config']['Env'] if '=' in item]
    require(len(entries) == len({key for key, _ in entries}), 'Duplicate tool image environment key')
    env = dict(entries)
    require(all(env.get(key) == value for key, value in ENVIRONMENT.items()), 'Tool image environment differs')
    require(not any(key.startswith(('SYNTH_', 'ABC_', 'LIB_', 'DONT_USE', 'SWAP_', 'YOSYS_', 'OPENROAD_'))
                    and key not in ENVIRONMENT for key in env), 'Unexpected synthesis environment override')
    return image


def verify_canonical_log(log, checkpoint):
    text = Path(log).read_text()
    rows = re.findall(r'^1_1_yosys_canonicalize\s+\.rtlil\s+\d+\s+\d+\s+([0-9a-f]{20})\s*$', text, re.M)
    require('End of script.' in text and len(rows) == 1, 'Canonicalization did not report one completed checkpoint')
    unexpected = [line for line in text.splitlines() if re.match(r'^\s*warning:', line, re.I) and
                  not re.fullmatch(r"Warning: Ignoring module .+ because it contains processes \(run 'proc' command first\)\.", line.strip())]
    require(not unexpected and not re.search(r'(?mi)^\s*error\b', text), 'Unexpected canonicalization warning or error')
    require(sha(checkpoint, 'sha1').startswith(rows[0]), 'Canonical checkpoint differs from the run log')


def check_inputs(manifest, flow):
    inputs = manifest['inputs']
    params = flow['parameters']
    require(manifest.get('scope') == 'synthesis estimate' and manifest.get('dirty') is False,
            'Source run is not a clean synthesis estimate')
    require(inputs.get('reg_code') == '' and inputs.get('synthesis_engine') == 'yosys' and
            inputs.get('synthesis_only') is True and inputs.get('sv_frontend') == 'native' and
            inputs.get('enable_retiming') is False and inputs.get('generate_gds') is False and
            inputs.get('io_false_path_ports') is None and inputs.get('cache') is False and
            inputs.get('clean') is False,
            'Source run used an unsupported synthesis profile')
    require(params.get('clock_port_auto') is False and params.get('swap_arithmetic_operators') is False and
            params.get('max_fanout') is None and params.get('high_fanout_nets') is None and
            params.get('post_floorplan_tcl') is None and params.get('io_false_path_ports') is None,
            'Source run used unsupported flow transformations')
    require(isinstance(inputs.get('module_name'), str) and IDENTIFIER.fullmatch(inputs['module_name']) and
            isinstance(inputs.get('clock_port'), str) and IDENTIFIER.fullmatch(inputs['clock_port']),
            'Invalid top or clock name')
    for key in ('clock_period', 'io_delay_percent'):
        value = inputs.get(key)
        require(isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value),
                'Invalid timing input: ' + key)
    require(inputs['clock_period'] > 0 and 0 <= inputs['io_delay_percent'] <= .5, 'Invalid timing constraints')
    for source, target in (('clock_port', 'clock_port'), ('clock_period', 'clock_period'),
                           ('io_delay_percent', 'io_delay_percent'), ('cell_vt', 'cell_vt'),
                           ('corner', 'corner'), ('synthesis_engine', 'synthesis_engine'),
                           ('sv_frontend', 'sv_frontend'), ('enable_retiming', 'enable_retiming'),
                           ('generate_gds', 'generate_gds'), ('io_false_path_ports', 'io_false_path_ports'),
                           ('core_utilization', 'core_utilization'), ('place_density', 'place_density'),
                           ('setup_slack_margin', 'setup_slack_margin'),
                           ('abc_clock_period_ps', 'abc_clock_period_ps'), ('timing_effort', 'timing_effort')):
        require(inputs.get(source) == params.get(target), 'Flow parameter differs: ' + source)
    require(inputs.get('module_name') == flow.get('module_name'), 'Flow top module differs')
    return inputs, params


def verify(qualification, rtl, source_worktree, chipagent_dir):
    qualification, rtl = Path(qualification).resolve(), Path(rtl).resolve()
    manifest_path = qualification / 'manifest.json'
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes)
    flow_dir = qualification / 'flow'
    flow_path = flow_dir / 'flow_manifest.json'
    flow_bytes = flow_path.read_bytes()
    flow = json.loads(flow_bytes)
    source = clean_checkout(source_worktree, manifest['commit'])
    chipagent = clean_checkout(chipagent_dir, manifest['chipagent_commit'])
    verify_sources(source, manifest['source_sha256'])
    inputs, params = check_inputs(manifest, flow)
    image = verify_image(manifest['hierarchy_profile'])
    require(image == flow.get('openroad_image'), 'Flow image differs from the pinned hierarchy image')
    require(sha(source / 'timing/yosys-hierarchy.Dockerfile') == manifest['hierarchy_profile']['recipe_sha256'],
            'Hierarchy image recipe differs')

    filelist = rtl / 'filelist.f'
    filelist_bytes = None
    if filelist.is_file():
        filelist_bytes = filelist.read_bytes()
        names = [line.strip() for line in filelist_bytes.decode().splitlines()
                 if line.strip() and not line.strip().startswith('verification/')]
    else:
        names = [path.name for path in sorted(rtl.glob('*.sv'))]
    require(len(names) == len(set(names)) and all(Path(n).name == n for n in names), 'Invalid RTL filelist')
    require(names == [Path(name).name for name in inputs['rtl_files']], 'RTL file order differs')
    modules = inventory(rtl)
    require(set(names) == {module[0].name for module in modules.values()}, 'RTL inventory differs')
    require({module[0].name: module[2] for module in modules.values()} == manifest['emitted_sha256'],
            'Emitted RTL bytes differ')

    macro_hashes = manifest['macro_sha256']
    design = flow_dir / 'orfs-work/designs/asap7' / inputs['module_name']
    libs, lefs = verify_macro_collateral(source, design, macro_hashes, inputs)
    placement = source / 'timing/asap7/compute_unit_sram_macro_placement.tcl' if libs else None
    if placement:
        pin = git(source, 'rev-parse', 'HEAD:depends/asap7_sram_0p0')
        require(git(source / 'depends/asap7_sram_0p0', 'rev-parse', 'HEAD') == pin,
                'SRAM submodule differs from the source commit')
        require(inputs['macro_placement_tcl'] and sha(placement) == sha(design / 'macro_placement.tcl'),
                'Macro placement input differs')
    else:
        require(inputs['macro_placement_tcl'] is None, 'Unexpected macro placement input')

    sys.path.insert(0, str(chipagent))
    from chipagent.tools.phys_flow_asap7 import _config, _manifest, _rtl_source, _sdc
    reg_code = _rtl_source('', [str(rtl / name) for name in names])
    merged = flow_dir / 'orfs-work/src' / inputs['module_name'] / (inputs['module_name'] + '.sv')
    exact_files(merged.parent, {merged.name}, {'.v', '.sv'})
    merged_hash = hashlib.sha256((reg_code + '\n').encode()).hexdigest()
    require(merged_hash == sha(merged), 'Combined RTL differs')
    keys = ('clock_port', 'clock_port_auto', 'clock_period', 'core_utilization', 'place_density', 'corner',
            'cell_vt', 'timing_effort', 'synthesis_engine', 'sv_frontend', 'enable_retiming',
            'swap_arithmetic_operators', 'generate_gds', 'max_fanout', 'high_fanout_nets',
            'high_fanout_max', 'high_fanout_buffer_cell', 'setup_slack_margin', 'abc_clock_period_ps',
            'io_delay_percent', 'io_false_path_ports')
    arguments = {key: params[key] for key in keys}
    arguments.update(reg_code=reg_code, module_name=inputs['module_name'], image=image,
                     macro_libs=libs, macro_lefs=lefs, macro_placement_tcl=placement)
    require(_manifest(**arguments) == flow, 'Complete ChipAgent flow input hash differs')
    expected_config = _config(inputs['module_name'], params['core_utilization'], params['place_density'],
                              params['corner'], cell_vt=params['cell_vt'], has_macros=bool(libs),
                              has_macro_placement=bool(placement), timing_effort=params['timing_effort'],
                              synthesis_engine=params['synthesis_engine'], sv_frontend=params['sv_frontend'],
                              enable_retiming=params['enable_retiming'],
                              swap_arithmetic_operators=params['swap_arithmetic_operators'],
                              setup_slack_margin=params['setup_slack_margin'],
                              abc_clock_period_ps=params['abc_clock_period_ps'])
    expected_sdc = _sdc(inputs['module_name'], params['clock_port'], params['clock_period'],
                        max_fanout=params['max_fanout'], io_delay_percent=params['io_delay_percent'],
                        io_false_path_ports=params['io_false_path_ports'])
    require((design / 'config.mk').read_text() == expected_config and
            (design / 'constraint.sdc').read_text() == expected_sdc, 'Mapping configuration or timing constraints differ')
    del reg_code

    checkpoint = flow_dir / 'orfs-work/results/base/1_1_yosys_canonicalize.rtlil'
    log = flow_dir / 'orfs-work/logs/base/1_1_yosys_canonicalize.log'
    log_hash = sha(log)
    verify_canonical_log(log, checkpoint)
    indexed = index_checkpoint(checkpoint)
    reachable, libraries, macros = validate_hierarchy(indexed, modules, inputs['module_name'])
    require(set(macros) == {p.stem for p in libs}, 'Referenced SRAMs differ from characterized libraries')
    require(sha(checkpoint) == indexed.sha256 and sha(log) == log_hash, 'Canonical checkpoint or log changed')
    require(manifest_path.read_bytes() == manifest_bytes and flow_path.read_bytes() == flow_bytes,
            'Source or flow manifest changed')
    require(sha(merged) == merged_hash and (design / 'config.mk').read_text() == expected_config and
            (design / 'constraint.sdc').read_text() == expected_sdc, 'Flow inputs changed')
    require((filelist_bytes is None and not filelist.exists()) or
            (filelist_bytes is not None and filelist.is_file() and filelist.read_bytes() == filelist_bytes),
            'RTL filelist changed')
    require({path.name for path in rtl.glob('*.sv')} == set(names), 'RTL inventory changed')
    for name, expected in manifest['emitted_sha256'].items():
        require(sha(rtl / name) == expected, 'Emitted RTL changed: ' + name)
    require(verify_macro_collateral(source, design, macro_hashes, inputs) == (libs, lefs),
            'Macro collateral changed')
    exact_files(merged.parent, {merged.name}, {'.v', '.sv'})
    if placement:
        require(sha(placement) == sha(design / 'macro_placement.tcl'), 'Macro placement input changed')
    clean_checkout(source, manifest['commit'])
    clean_checkout(chipagent, manifest['chipagent_commit'])
    verify_sources(source, manifest['source_sha256'])
    require(sha(source / 'timing/yosys-hierarchy.Dockerfile') == manifest['hierarchy_profile']['recipe_sha256'],
            'Hierarchy image recipe changed')
    return dict(source_commit=manifest['commit'], chipagent_commit=manifest['chipagent_commit'],
                source_manifest_sha256=hashlib.sha256(manifest_bytes).hexdigest(),
                flow_manifest_sha256=hashlib.sha256(flow_bytes).hexdigest(),
                canonical_log_sha256=log_hash, checkpoint_sha256=indexed.sha256,
                config_sha256=sha(design / 'config.mk'), sdc_sha256=sha(design / 'constraint.sdc'),
                tool_image=image, rtl_sha256=manifest['emitted_sha256'],
                filelist_sha256=hashlib.sha256(filelist_bytes).hexdigest() if filelist_bytes is not None else None,
                macro_sha256=macro_hashes,
                top=inputs['module_name'], reachable_definitions=len(reachable), library_definitions=len(libraries),
                expanded_sram_instances=macros, physical_qualified=False,
                scope='Recorded local provenance and hierarchy only; complete mapping and linked timing remain required')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('qualification', type=Path)
    parser.add_argument('rtl', type=Path)
    parser.add_argument('--source-worktree', type=Path, required=True)
    parser.add_argument('--chipagent-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(args.output)
    result = verify(args.qualification, args.rtl, args.source_worktree, args.chipagent_dir)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    print(json.dumps({key: value for key, value in result.items() if key != 'rtl_sha256'}, indent=2))


if __name__ == '__main__':
    main()
