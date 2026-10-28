#!/usr/bin/env python3
"""Prepare the unchanged emitted native pixel owner for explicit local testing."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def sources(repo):
    paths = ['src/main', 'build.sbt', 'project']
    assert not subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard', *paths], cwd=repo).strip()
    names = subprocess.check_output(['git', 'ls-files', *paths], cwd=repo, text=True).splitlines()
    return {n: sha(repo / n) for n in names if (repo / n).is_file()}


def ports(path):
    code = re.sub(r'//[^\n]*|/\*.*?\*/', '', Path(path).read_text(), flags=re.S)
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
            assert re.fullmatch(r'\w+', item), item
            name = item
        assert direction and width > 0 and name not in result
        result[name] = (direction, width)
    return result


def emit(output, repo):
    output = Path(output).resolve()
    assert not output.exists(), output
    before = sources(repo)
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
    dependency = repo / 'depends/asap7_sram_0p0'
    pin = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=dependency, text=True).strip()
    vendor = dependency / 'generated/verilog/srambank_64x4x64_6t122.v'
    vendor_sha = sha(vendor)
    output.mkdir(parents=True)
    rtl = output / 'emitted'
    command = ['sbt', '-J-Xmx4G', '-J-XX:ActiveProcessorCount=2', '-Dopengpu.productQualification=0',
               'runMain opengpu.elaboration.EmitPpaRtl mobile-public-frame-pixel-child ' + str(rtl)]
    with (output / 'emission.log').open('x') as log:
        actual = subprocess.run(command, cwd=repo, stdout=log, stderr=subprocess.STDOUT).returncode
    assert actual == 0, f'Actual emission failed: {actual}; inspect emission.log'
    assert sources(repo) == before and sha(vendor) == vendor_sha
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip() == head
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=dependency, text=True).strip() == pin
    data = dict(profile='mobile-public-frame-pixel-child', actual_exit=actual, head=head,
                source_sha256=before, dependency_pin=pin, vendor_SRAM_sha256=vendor_sha,
                rtl=str(rtl), rtl_sha256={str(p.relative_to(rtl)): sha(p) for p in rtl.rglob('*.sv')}, command=command)
    assert data['rtl_sha256']
    manifest = output / 'emission.json'
    manifest.write_text(json.dumps(data, indent=2) + '\n')
    print(manifest)


def prepare(provenance, output, repo):
    provenance, output = Path(provenance).resolve(), Path(output).resolve()
    data = json.loads(provenance.read_text())
    assert data['profile'] == 'mobile-public-frame-pixel-child' and data['actual_exit'] == 0
    assert not output.exists(), output
    assert data['source_sha256'] and sources(repo) == data['source_sha256'], 'Production/build source inventory or bytes changed'
    dependency = repo / 'depends/asap7_sram_0p0'
    pin = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=dependency, text=True).strip()
    assert pin == data['dependency_pin']
    vendor = dependency / 'generated/verilog/srambank_64x4x64_6t122.v'
    assert sha(vendor) == data['vendor_SRAM_sha256']
    rtl = Path(data['rtl'])
    assert {str(p.relative_to(rtl)) for p in rtl.rglob('*.sv')} == set(data['rtl_sha256'])
    assert all(sha(rtl / n) == digest for n, digest in data['rtl_sha256'].items())
    subprocess.run([sys.executable, str(repo / 'scripts/extract_emitted_rtl.py'), str(rtl),
                    'TypedTilePixelBackend', str(output / 'rtl')], check=True)
    closure = json.loads((output / 'rtl/manifest.json').read_text())
    assert len(closure['files']) == 128 and closure['external_macros'] == {'srambank_64x4x64_6t122': 16}
    assert all(data['rtl_sha256'][n] == digest and sha(output / 'rtl' / n) == digest for n, digest in closure['files'].items())
    assert sources(repo) == data['source_sha256'] and sha(vendor) == data['vendor_SRAM_sha256']
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=dependency, text=True).strip() == pin
    top_ports = ports(output / 'rtl/TypedTilePixelBackend.sv')
    result = dict(schema=1, top='TypedTilePixelBackend', definitions=128, real_SRAMs=16,
                  tile_bytes=32768, streaming_word_ports=8, source_sha256=data['source_sha256'],
                  dependency_pin=pin, vendor=str(vendor), vendor_SRAM_sha256=sha(vendor),
                  provenance=str(provenance), provenance_sha256=sha(provenance),
                  rtl=str(output / 'rtl'), rtl_sha256=closure['files'],
                  ports=[dict(name=n, direction=d, width=w) for n, (d, w) in top_ports.items()],
                  production_RTL_copied_byte_exact=True, no_DUT_ports_or_logic_modified=True)
    (output / 'manifest.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(dict(prepared=True, definitions=128, real_SRAMs=16, ports=len(top_ports))))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    emitter = sub.add_parser('emit', help='Emit the full eight-port native owner with provenance')
    emitter.add_argument('output')
    preparer = sub.add_parser('prepare', help='Copy the complete unchanged production RTL closure')
    preparer.add_argument('provenance')
    preparer.add_argument('output')
    checker = sub.add_parser('check-wrapper', help='CPU-only exact public port signature check')
    checker.add_argument('model')
    checker.add_argument('wrapper')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    if args.mode == 'emit':
        emit(args.output, repo)
    elif args.mode == 'prepare':
        prepare(args.provenance, args.output, repo)
    else:
        model = json.loads((Path(args.model) / 'manifest.json').read_text())
        expected = {p['name']: (p['direction'], p['width']) for p in model['ports']}
        assert ports(args.wrapper) == expected, 'Wrapper public interface differs from production DUT'
        wrapper = Path(args.wrapper).read_text()
        assert re.search(r'TypedTilePixelBackend\s+\w+\s*\(', wrapper)
        assert set(re.findall(r'\.(\w+)\s*\(', wrapper)) == set(expected), 'DUT named connections differ'
        print(json.dumps(dict(passed=True, ports=len(expected), hardware_executed=False)))


if __name__ == '__main__':
    main()
