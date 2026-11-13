#!/usr/bin/env python3
"""Compare ordinary/fast OPT on byte-identical emitted component closures.

This diagnoses optimization strategy, not mapped timing or whole-host synthesis.
All temporary RTLIL and logs are removed on success and failure.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time
import uuid

from extract_emitted_rtl import children, code_only, expanded_counts, MACRO

YOSYS = '/OpenROAD-flow-scripts/tools/install/yosys/bin/yosys'
IDENT = re.compile(r'[A-Za-z_][A-Za-z0-9_$]*\Z')


def gpu_temporary_root():
    root = Path(subprocess.check_output(
        ['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'],
        cwd=Path(__file__).resolve().parents[1], text=True).strip()).parent
    return root / '.artifacts'


def snapshot(rtl):
    return {str(p.relative_to(rtl)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(rtl.rglob('*.sv'))}


def component_closure(rtl, top):
    """Inspect every reachable definition, without loading unrelated SRAM models."""
    modules = {}

    def visit(name):
        if name in modules:
            return
        if not IDENT.fullmatch(name) or MACRO.fullmatch(name):
            raise ValueError('External macros cannot be abstracted in this equivalence check')
        path = rtl / (name + '.sv')
        code = code_only(path.read_text())
        definitions = re.findall(r'\bmodule\s+([A-Za-z_][A-Za-z0-9_$]*)\s*([(#])', code)
        if definitions != [(name, '(')] or len(re.findall(r'\bendmodule\b', code)) != 1:
            raise ValueError('Expected one unparameterized emitted definition: ' + name)
        modules[name] = (path, children(code), '')
        for child in modules[name][1]:
            visit(child)

    visit(top)
    return modules, expanded_counts(modules, top)


def run_yosys(image, rtl, work, commands, timeout):
    """Own one resource-bounded container and remove it even on client timeout."""
    name = 'opengpu-opt-' + uuid.uuid4().hex
    command = ['docker', 'run', '--rm', '--name', name, '--network', 'none',
               '--cpus', '1', '--memory', '1g', '--memory-swap', '1g',
               '--mount', f'type=bind,source={rtl},target=/src,readonly',
               '--mount', f'type=bind,source={work},target=/work',
               '-e', 'TMPDIR=/work/tmp', '-e', 'HOME=/work/home', '-w', '/work',
               '--entrypoint', YOSYS, image, '-Q', '-T', '-t', '-d', '-p', commands]
    started = time.monotonic()
    try:
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=timeout)
        if result.returncode:
            raise RuntimeError('Yosys failed:\n' + result.stdout[-6000:])
        return time.monotonic() - started, result.stdout
    finally:
        subprocess.run(['docker', 'rm', '--force', name], stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=30)


def equivalence_commands():
    return ('read_rtlil /work/ordinary.il; rename -top gold; design -stash gold; '
            'read_rtlil /work/fast.il; rename -top gate; design -stash gate; '
            'design -copy-from gold -as gold gold; design -copy-from gate -as gate gate; '
            'equiv_make -inames gold gate equiv; hierarchy -check -top equiv; '
            'equiv_simple; equiv_induct -seq 4; equiv_status -assert')


def compare(rtl, top, image, temporary_root, timeout):
    if not IDENT.fullmatch(top):
        raise ValueError('Invalid module identifier')
    rtl = Path(rtl).resolve()
    before = snapshot(rtl)
    modules, closure = component_closure(rtl, top)
    files = sorted(modules[k][0].name for k in closure)
    if any(not IDENT.fullmatch(Path(f).stem) for f in files):
        raise ValueError('Unsupported emitted filename')
    image_id = subprocess.check_output(['docker', 'image', 'inspect', '--format',
        '{{.Id}}', image], text=True).strip()
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', image_id):
        raise ValueError('Docker image is not pinned')
    temporary_root.mkdir(parents=True, exist_ok=True)
    try:
        with tempfile.TemporaryDirectory(prefix='opt-', dir=temporary_root) as name:
            work = Path(name)
            (work / 'tmp').mkdir(); (work / 'home').mkdir()
            # Same prefix as synth's first coarse OPT, followed by FSM. Mapping
            # inferred memories here is common to both branches and permits SAT.
            prefix = ('read_verilog -sv -formal ' + ' '.join('/src/' + f for f in files)
                + f'; hierarchy -check -top {top}; proc; flatten; opt_expr; '
                  'opt_clean; check; opt -nodffe -nosdff; fsm; memory_map; '
                  'write_rtlil /work/base.il')
            base_elapsed, _ = run_yosys(image_id, rtl, work, prefix, timeout)
            elapsed = {}
            sizes = {}
            for variant, opt in [('ordinary', 'opt'), ('fast', 'opt -fast')]:
                elapsed[variant], _ = run_yosys(image_id, rtl, work,
                    f'read_rtlil /work/base.il; {opt}; check -assert; '
                    f'write_rtlil /work/{variant}.il', timeout)
                sizes[variant] = (work / (variant + '.il')).stat().st_size
            proof_elapsed, proof = run_yosys(image_id, rtl, work,
                                           equivalence_commands(), timeout)
            if 'Equivalence successfully proven!' not in proof:
                raise RuntimeError('Missing successful equivalence receipt')
            return dict(top=top, definitions=len(files), physical_instances=sum(closure.values()),
                        ordinary_seconds=elapsed['ordinary'], fast_seconds=elapsed['fast'],
                        rtlil_bytes=sizes, prefix_seconds=base_elapsed,
                        proof_seconds=proof_elapsed, equivalent=True,
                        image_id=image_id, verification_files_guarded=sum('verification/' in p for p in before),
                        source_unchanged=True, mappedTiming=False, wholeHostQualified=False)
    finally:
        if snapshot(rtl) != before:
            raise RuntimeError('Input RTL or assertion layers changed during comparison')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('rtl', type=Path)
    parser.add_argument('--top', action='append', required=True)
    parser.add_argument('--image', required=True)
    parser.add_argument('--timeout', type=int, default=120)
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error('timeout must be positive')
    for top in args.top:
        print(json.dumps(compare(args.rtl, top, args.image,
                                 gpu_temporary_root(), args.timeout)), flush=True)


if __name__ == '__main__':
    main()
