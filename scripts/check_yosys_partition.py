#!/usr/bin/env python3
"""Check a mapped partition's ports and retained direct DUT instances.

This is a structural check, not a proof of functional equivalence, complete
host linkage, SRAM characterization, physical timing or product performance.
"""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re

from split_yosys_rtlil import identity, index_checkpoint

IDENT = r'(?:\\[^\s]+|[A-Za-z_][A-Za-z0-9_$]*)'
MODULE = re.compile(r'^module (' + IDENT + r')\(')
VPORT = re.compile(r'^\s*(input|output|inout)(?:\s+(signed))?(?:\s+\[(-?\d+):(-?\d+)\])?\s+('
                   + IDENT + r')\s*;\s*$')
INSTANCE = re.compile(r'^  (' + IDENT + r')\s+(' + IDENT + r')\s+\(\s*$')
VCONNECTION = re.compile(r'^\s*\.(' + IDENT + r')\s*\((.*)$')
ILCELL = re.compile(r'^  cell \\(\S+) (\\\S+|\$\S+)\s*$')
ILCONNECTION = re.compile(r'^    connect \\(\S+)\s+(.*)$')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def name(token):
    return token.removeprefix('\\')


def il_port(line):
    tokens = line.split()
    if not tokens or tokens[0] != 'wire' or not {'input', 'output', 'inout'}.intersection(tokens):
        return None
    require(tokens[-1].startswith('\\'), 'Unsupported RTLIL port name')
    port = name(tokens[-1])
    width, offset, upto, signed, direction, order = 1, 0, False, False, None, None
    fields = tokens[1:-1]
    seen = set()
    while fields:
        field = fields.pop(0)
        require(field not in seen, 'Duplicate RTLIL port attribute')
        seen.add(field)
        if field in ('width', 'offset', 'input', 'output', 'inout'):
            require(fields, 'Incomplete RTLIL port attribute')
            value = int(fields.pop(0))
            if field == 'width': width = value
            elif field == 'offset': offset = value
            else:
                require(direction is None, 'Unsupported RTLIL port direction')
                direction, order = field, value
        elif field == 'upto': upto = True
        elif field == 'signed': signed = True
        else: raise ValueError('Unsupported RTLIL port attribute: ' + field)
    require(width > 0 and direction and order > 0, 'Invalid RTLIL port')
    msb, lsb = (offset, offset + width - 1) if upto else (offset + width - 1, offset)
    return port, (direction, signed, msb, lsb), order


def il_block(checkpoint, module, children=()):
    require(module in checkpoint.blocks, 'Missing canonical module: ' + module)
    block = checkpoint.blocks[module]
    ports, instances, active = {}, {}, None
    with checkpoint.path.open('rb') as stream:
        require(identity(os.fstat(stream.fileno())) == checkpoint.identity, 'Partition changed before checking')
        stream.seek(block.start)
        while stream.tell() < block.end:
            line = stream.readline().decode('utf-8')
            port = il_port(line) if line.startswith('  wire ') else None
            if port:
                n, signature, order = port
                require(n not in ports, 'Duplicate canonical port: ' + n)
                ports[n] = (signature, order)
            match = ILCELL.fullmatch(line.strip('\n'))
            if match and match[1] in children:
                active = (match[1], name(match[2]))
                require(active not in instances, 'Duplicate canonical child instance')
                instances[active] = {}
            elif active and line == '  end\n':
                active = None
            elif active:
                match = ILCONNECTION.fullmatch(line.strip('\n'))
                if match:
                    require(match[1] not in instances[active], 'Duplicate canonical child connection')
                    instances[active][match[1]] = match[2].strip()
        require(active is None, 'Incomplete canonical child instance')
        require(identity(os.fstat(stream.fileno())) == checkpoint.identity and
                identity(checkpoint.path.stat()) == checkpoint.identity, 'Partition changed while checking')
    require(sorted(order for _, order in ports.values()) == list(range(1, len(ports) + 1)),
            'Invalid canonical port ordering')
    return ports, instances


def header_names(text):
    result, index = [], 0
    while index < len(text):
        while index < len(text) and text[index].isspace(): index += 1
        if index == len(text): break
        match = re.match(IDENT, text[index:])
        require(match is not None, 'Unsupported mapped module header')
        token = match[0]
        index += len(token)
        if token.startswith('\\'):
            require(index == len(text) or text[index].isspace(), 'Unterminated escaped port')
        result.append(name(token))
        while index < len(text) and text[index].isspace(): index += 1
        if index < len(text):
            require(text[index] == ',', 'Unsupported mapped module header separator')
            index += 1
            require(text[index:].strip(), 'Trailing mapped module header separator')
    require(len(result) == len(set(result)), 'Duplicate mapped header port')
    return result


def mapped_design(path, children):
    ports, instances, current = {}, {}, None
    header, collecting, modules, ended = '', False, [], 0
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        before = identity(os.fstat(stream.fileno()))
        for raw in stream:
            digest.update(raw)
            line = raw.decode('utf-8')
            require(not re.search(r'\b(?:blackbox|whitebox)\b', line, re.I),
                    'Mapped module contains an abstraction attribute')
            module = MODULE.match(line)
            if module:
                modules.append(name(module[1])); collecting = True
                header = line[module.end():]
            elif collecting:
                header += line
            if collecting and ');' in header:
                require(header.count(');') == 1 and not header.split(');', 1)[1].strip(),
                        'Unsupported mapped module header')
                header = header.split(');', 1)[0]
                collecting = False
            if line.strip() == 'endmodule': ended += 1
            if re.match(r'^\s*(input|output|inout)\b', line):
                match = VPORT.fullmatch(line)
                require(match is not None, 'Unsupported mapped port declaration')
                direction, signed, msb, lsb, token = match.groups()
                n = name(token)
                require(n not in ports, 'Duplicate mapped port: ' + n)
                ports[n] = (direction, bool(signed), int(msb or 0), int(lsb or 0))
            match = INSTANCE.fullmatch(line.rstrip('\n'))
            if match and name(match[1]) in children:
                require(current is None, 'Nested mapped child instance')
                current = (name(match[1]), name(match[2]))
                require(current not in instances, 'Duplicate mapped child instance')
                instances[current] = {}
            elif current and line.strip() == ');':
                current = None
            elif current:
                match = VCONNECTION.match(line)
                if match:
                    n = name(match[1])
                    require(n not in instances[current], 'Duplicate mapped child connection')
                    instances[current][n] = bool(match[2].strip().startswith(')'))
        after = identity(os.fstat(stream.fileno()))
    require(before == after and identity(Path(path).stat()) == after, 'Mapped netlist changed while checking')
    require(len(modules) == 1 and ended == 1 and not collecting and current is None,
            'Mapped netlist must contain one complete module')
    return modules[0], header_names(header), ports, instances, digest.hexdigest()


def check(partition_path, manifest_path, mapped_path):
    manifest_path = Path(manifest_path)
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes)
    module = manifest['module']
    expected_counts = Counter(manifest['direct_child_instances'])
    require(all(isinstance(n, str) and re.fullmatch(r'[A-Za-z_][A-Za-z0-9_$]*', n) and
                isinstance(c, int) and not isinstance(c, bool) and c > 0
                for n, c in expected_counts.items()), 'Invalid manifest child instances')
    checkpoint = index_checkpoint(partition_path)
    require(checkpoint.sha256 == manifest['partition_sha256'], 'Partition hash differs from manifest')
    require(module in checkpoint.blocks, 'Missing canonical module: ' + module)
    require(checkpoint.blocks[module].children == expected_counts, 'Canonical child counts differ from manifest')
    ports, canonical = il_block(checkpoint, module, expected_counts)
    require(Counter(kind for kind, _ in canonical) == expected_counts, 'Unsupported canonical child declaration')
    child_ports = {child: il_block(checkpoint, child)[0] for child in expected_counts}
    mapped_module, header, mapped_ports, mapped, mapped_hash = mapped_design(mapped_path, expected_counts)
    require(mapped_module == module, 'Mapped module name differs')
    require(header == [n for n, (_, _) in sorted(ports.items(), key=lambda item: item[1][1])],
            'Mapped port ordering differs')
    require(mapped_ports == {n: signature for n, (signature, _) in ports.items()},
            'Mapped port signatures differ')
    require(set(mapped) == set(canonical), 'Mapped child instances differ')
    omitted = 0
    for instance, connections in canonical.items():
        actual = mapped[instance]
        require(not (actual.keys() - connections.keys()), 'Unexpected mapped child connection')
        for port, expression in connections.items():
            require(port in child_ports[instance[0]], 'Canonical connection has unknown child port')
            disconnected = expression == '{ }'
            if port not in actual or actual[port]:
                require(disconnected and child_ports[instance[0]][port][0][0] == 'output',
                        'Mapped child lost a connected port')
                omitted += 1
    require(manifest_path.read_bytes() == manifest_bytes, 'Partition manifest changed while checking')
    return dict(module=module, partition_sha256=checkpoint.sha256, mapped_sha256=mapped_hash,
                port_count=len(ports), direct_instances=len(canonical), omitted_empty_outputs=omitted,
                physical_qualified=False,
                scope='Structural partition check only; equivalence and complete linked timing remain required')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('partition', type=Path)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('mapped', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = check(args.partition, args.manifest, args.mapped)
    with args.output.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
