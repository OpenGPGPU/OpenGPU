#!/usr/bin/env python3
"""Prepare an unchanged emitted-host texture subsystem for local simulation."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def ports(path):
    source = Path(path).read_text()
    code = re.sub(r'//[^\n]*|/\*.*?\*/', '', source, flags=re.S)
    header = code[code.index('(') + 1:code.index(');')]
    result = {}
    direction, width = None, 1
    for item in header.split(','):
        item = item.strip()
        match = re.fullmatch(r'(input|output)\s*(?:\[(\d+):(\d+)\])?\s*(\w+)', item)
        if match:
            direction = match[1]
            width = int(match[2]) - int(match[3]) + 1 if match[2] else 1
            name = match[4]
        else:
            assert re.fullmatch(r'\w+', item), f'Unsupported port declaration: {item}'
            name = item
        assert direction and width > 0 and name not in result
        result[name] = (direction, width)
    return result


def emit(output, repo):
    output, repo = Path(output).resolve(), Path(repo).resolve()
    assert not output.exists(), output
    names = subprocess.check_output(['git', 'ls-files', 'src/main', 'build.sbt', 'project'], cwd=repo, text=True).splitlines()
    sources = {n: sha(repo / n) for n in names if (repo / n).is_file()}
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
    dependency = repo / 'depends/asap7_sram_0p0'
    pin = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=dependency, text=True).strip()
    vendor = dependency / 'generated/verilog/srambank_64x4x32_6t122.v'
    vendor_sha = sha(vendor)
    output.mkdir(parents=True)
    rtl = output / 'rtl'
    command = ['sbt', '-J-Xmx10G', '-J-XX:ActiveProcessorCount=2', '-Dopengpu.productQualification=0',
               'runMain opengpu.elaboration.EmitPpaRtl mobile-native-four-engine-host ' + str(rtl)]
    with (output / 'emission.log').open('x') as log:
        actual = subprocess.run(command, cwd=repo, stdout=log, stderr=subprocess.STDOUT).returncode
    assert actual == 0, f'Actual host emission failed: {actual}; inspect emission.log'
    assert all(sha(repo / n) == digest for n, digest in sources.items()) and sha(vendor) == vendor_sha
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip() == head
    result = dict(profile='mobile-native-four-engine-host', actual_exit=actual, head=head,
                  source_sha256=sources, dependency_pin=pin, vendor_SRAM_sha256=vendor_sha,
                  rtl=str(rtl), rtl_sha256={str(p.relative_to(rtl)): sha(p) for p in rtl.rglob('*.sv')}, command=command)
    assert result['rtl_sha256']
    manifest = output / 'emission.json'
    manifest.write_text(json.dumps(result, indent=2) + '\n')
    print(manifest)


def prepare(provenance, output, repo):
    provenance, output, repo = Path(provenance).resolve(), Path(output).resolve(), Path(repo).resolve()
    data = json.loads(provenance.read_text())
    assert data['actual_exit'] == 0 and data['profile'] == 'mobile-native-four-engine-host'
    assert not output.exists(), output
    assert data['source_sha256'] and data['rtl_sha256']
    for name, digest in data['source_sha256'].items():
        assert not Path(name).is_absolute() and '..' not in Path(name).parts
        assert sha(repo / name) == digest, f'Emission source changed: {name}'
    pin = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo / 'depends/asap7_sram_0p0', text=True).strip()
    assert pin == data['dependency_pin']
    vendor = repo / 'depends/asap7_sram_0p0/generated/verilog/srambank_64x4x32_6t122.v'
    assert sha(vendor) == data['vendor_SRAM_sha256']
    rtl = Path(data['rtl'])
    assert all(sha(rtl / name) == digest for name, digest in data['rtl_sha256'].items())
    output.mkdir(parents=True)
    library = output / 'rtl'
    library.mkdir()
    roots = [('vertex', 'NativeShaderTextureUnit', 101, {}),
             ('fragment', 'NativeShaderTextureUnit_1', 101, {}),
             ('cluster', 'NativeRgba8TextureSharedPool', 96, {'srambank_64x4x32_6t122': 16})]
    files, closures = {}, []
    for label, top, count, macros in roots:
        dest = output / (label + '-closure')
        subprocess.run([sys.executable, str(repo / 'scripts/extract_emitted_rtl.py'), str(rtl), top, str(dest)], check=True)
        manifest = json.loads((dest / 'manifest.json').read_text())
        assert len(manifest['files']) == count and manifest['external_macros'] == macros
        for name, digest in manifest['files'].items():
            assert data['rtl_sha256'][name] == digest and sha(dest / name) == digest
            if name in files:
                assert files[name] == digest, f'Conflicting emitted module: {name}'
            else:
                shutil.copy2(dest / name, library / name)
                files[name] = digest
        closures.append(dict(profile=label, top=top, definitions=count, real_SRAMs=macros,
                             source_sha256=manifest['files']))
    endpoint = [ports(library / (top + '.sv')) for _, top, _, _ in roots[:2]]
    pool = ports(library / 'NativeRgba8TextureSharedPool.sv')
    assert sorted({int(re.match(r'io_client_(\d+)_', n)[1]) for n in pool if re.match(r'io_client_(\d+)_', n)}) == list(range(5))
    top_ports = {'clock': ('input', 1), 'reset': ('input', 1)}
    wires, instances, monitors = {}, [], []
    for client, ep in enumerate(endpoint):
        assert ep['io_uniforms_version'] == ('input', 2)
        assert ep['io_vectorIn_bits_issued_decode_warpId'] == ('input', 3)
        assert ep['io_vectorIn_bits_issued_vs1Data_15'] == ('input', 32)
        group = {n[len('io_grouped_'):]: v for n, v in ep.items() if n.startswith('io_grouped_')}
        target = {n[len(f'io_client_{client}_'):]: v for n, v in pool.items() if n.startswith(f'io_client_{client}_')}
        assert set(group) == set(target)
        assert all(group[n][1] == target[n][1] and group[n][0] != target[n][0] for n in group)
        connections = []
        for name, (direction, width) in ep.items():
            if name in ('clock', 'reset'):
                signal = name
            elif name.startswith('io_grouped_'):
                signal = f'group{client}_' + name[len('io_grouped_'):]
                wires[signal] = width
            else:
                signal = f'n{client}_' + name
                top_ports[signal] = direction, width
            connections.append(f'    .{name}({signal})')
        instances.append(roots[client][1] + f' endpoint{client} (\n' + ',\n'.join(connections) + '\n  );')
        for kind, expression, width in [
            ('requestFire', f'group{client}_request_valid & group{client}_request_ready', 1),
            ('replyFire', f'group{client}_response_valid & group{client}_response_ready', 1),
            ('requestTag', f'group{client}_request_bits_tag', group['request_bits_tag'][1]),
            ('replyTag', f'group{client}_response_bits_tag', group['response_bits_tag'][1])]:
            name = f'monitor{client}_{kind}'
            top_ports[name] = 'output', width
            monitors.append(f'  assign {name} = {expression};')
    connections = []
    for name, (direction, width) in pool.items():
        client = re.match(r'io_client_([01])_(.*)', name)
        if name in ('clock', 'reset'):
            signal = name
        elif client:
            signal = f'group{client[1]}_' + client[2]
        else:
            signal = 'pool_' + name
            top_ports[signal] = direction, width
        connections.append(f'    .{name}({signal})')
    instances.append('NativeRgba8TextureSharedPool pool (\n' + ',\n'.join(connections) + '\n  );')
    def declaration(direction, width, name):
        return f'  {direction} ' + (f'[{width-1}:0] ' if width > 1 else '') + name
    wrapper = output / 'NativeHostTextureProduction.sv'
    wrapper.write_text('module NativeHostTextureProduction(\n' +
                       ',\n'.join(declaration(d, w, n) for n, (d, w) in top_ports.items()) + '\n);\n' +
                       '\n'.join(declaration('wire', w, n) + ';' for n, w in wires.items()) + '\n' +
                       '\n'.join(instances + monitors) + '\nendmodule\n')
    assert sha(vendor) == data['vendor_SRAM_sha256']
    result = dict(schema=1, top='NativeHostTextureProduction', provenance=str(provenance),
                  provenance_sha256=sha(provenance), source_sha256=data['source_sha256'], dependency_pin=pin,
                  rtl=str(library), rtl_sha256=files, components=closures, wrapper=str(wrapper),
                  wrapper_sha256=sha(wrapper), ports=[dict(name=n, direction=d, width=w) for n, (d, w) in top_ports.items()],
                  vendor=str(vendor), vendor_SRAM_sha256=sha(vendor), configured_clients=5, real_SRAMs=16,
                  production_RTL_copied_byte_exact=True, no_DUT_ports_or_logic_modified=True,
                  scope='Production component RTL plus a named-wire test wrapper; monitors observe existing group handshakes/tags only.')
    (output / 'manifest.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(dict(prepared=True, components=[r['definitions'] for r in closures],
                          configured_clients=5, real_SRAMs=16, copied_files=len(files))))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    emitter = sub.add_parser('emit', help='Emit a current 640x480 production host with provenance')
    emitter.add_argument('output')
    preparer = sub.add_parser('prepare', help='Extract byte-exact components and add named test wiring')
    preparer.add_argument('provenance', help='Successful four-engine emission manifest with source and RTL hashes')
    preparer.add_argument('output')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    if args.mode == 'emit':
        emit(args.output, repo)
    else:
        prepare(args.provenance, args.output, repo)


if __name__ == '__main__':
    main()
