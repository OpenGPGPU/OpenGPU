#!/usr/bin/env python3
"""Prepare a module-sized view of a fully elaborated Yosys RTLIL checkpoint.

This checks hierarchy and collateral structure. It does not prove that the
checkpoint came from the supplied RTL, map logic, or qualify timing.
"""
import argparse
from collections import Counter
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re

from extract_emitted_rtl import MACRO, expanded_counts, inventory

NAME = rb'[A-Za-z_][A-Za-z0-9_$]*'
MODULE = re.compile(rb'^module \\(' + NAME + rb')\n$')
ATTRIBUTE = re.compile(rb'^attribute \\(' + NAME + rb') (.+)\n$')
CELL = re.compile(rb'^  cell (\\(' + NAME + rb')|\$[^\s]+)\s+')


@dataclass(frozen=True)
class Block:
    start: int
    end: int
    sha256: str
    attributes: dict
    children: Counter


@dataclass(frozen=True)
class Checkpoint:
    path: Path
    identity: tuple
    sha256: str
    prefix: bytes
    blocks: dict


def identity(stat):
    return stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns


def file_sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def index_checkpoint(path):
    """Index module byte ranges without loading the complete checkpoint."""
    path = Path(path).resolve()
    digest = hashlib.sha256()
    prefix, pending, attributes = [], [], {}
    blocks = {}
    offset = 0
    block_start = None
    module_name = None
    module_digest = None
    module_children = None
    found_autoidx = False
    with path.open('rb') as source:
        before = identity(os.fstat(source.fileno()))
        for line in source:
            start = offset
            offset += len(line)
            digest.update(line)
            if module_name is not None:
                module_digest.update(line)
                if line.startswith(b'  cell '):
                    match = CELL.match(line)
                    if not match:
                        raise ValueError('Unsupported RTLIL cell declaration')
                    if match[2]:
                        module_children[match[2].decode('ascii')] += 1
                if line == b'end\n':
                    blocks[module_name] = Block(block_start, offset, module_digest.hexdigest(),
                                                attributes, module_children)
                    module_name, module_digest, module_children = None, None, None
                    attributes, pending = {}, []
                continue
            if line.startswith(b'attribute '):
                match = ATTRIBUTE.fullmatch(line)
                if not match:
                    raise ValueError('Unsupported top-level RTLIL attribute')
                name, value = match[1].decode('ascii'), match[2].decode('utf-8')
                if name in attributes:
                    raise ValueError('Duplicate module attribute: ' + name)
                attributes[name] = value
                if not pending:
                    block_start = start
                pending.append(line)
            elif line.startswith(b'module '):
                match = MODULE.fullmatch(line)
                if not match or not found_autoidx:
                    raise ValueError('Unsupported RTLIL module declaration')
                module_name = match[1].decode('ascii')
                if module_name in blocks:
                    raise ValueError('Duplicate RTLIL module: ' + module_name)
                if not pending:
                    block_start = start
                module_digest = hashlib.sha256()
                for item in pending:
                    module_digest.update(item)
                module_digest.update(line)
                module_children = Counter()
            elif line.startswith(b'autoidx '):
                if found_autoidx or blocks or pending or not re.fullmatch(rb'autoidx [0-9]+\n', line):
                    raise ValueError('Unsupported RTLIL autoidx')
                found_autoidx = True
                prefix.append(line)
            elif line.startswith(b'#') or not line.strip():
                if pending:
                    raise ValueError('Unexpected text among module attributes')
                if not blocks:
                    prefix.append(line)
            else:
                raise ValueError('Unsupported top-level RTLIL syntax')
        after = identity(os.fstat(source.fileno()))
    if module_name is not None or pending or not found_autoidx or not blocks:
        raise ValueError('Incomplete RTLIL checkpoint')
    if before != after or offset != before[2]:
        raise ValueError('RTLIL checkpoint changed while indexing')
    return Checkpoint(path, before, digest.hexdigest(), b''.join(prefix), blocks)


def validate_hierarchy(checkpoint, modules, top):
    """Require the canonical modules and direct cells to match emitted RTL."""
    counts = expanded_counts(modules, top)
    reachable = set(counts) & modules.keys()
    indexed_rtl = checkpoint.blocks.keys() & modules.keys()
    if indexed_rtl != reachable:
        raise ValueError('Canonical RTL definitions differ from reachable emission')
    for name in reachable:
        block = checkpoint.blocks[name]
        if {'blackbox', 'whitebox', 'liberty_cell'} & block.attributes.keys():
            raise ValueError('DUT module is abstracted or library-marked: ' + name)
        if block.children != modules[name][1]:
            raise ValueError('Canonical child instances differ from emitted RTL: ' + name)
    libraries = set(checkpoint.blocks) - reachable
    if any(checkpoint.blocks[name].attributes.get('liberty_cell') != '1' for name in libraries):
        raise ValueError('Unexpected non-library module in canonical checkpoint')
    macros = {name: count for name, count in counts.items() if MACRO.fullmatch(name)}
    for name in macros:
        if name not in libraries or checkpoint.blocks[name].attributes.get('blackbox') != '1':
            raise ValueError('Characterized SRAM library is absent: ' + name)
    return reachable, libraries, macros


def write_partition(checkpoint, module, modules, reachable, libraries, path):
    """Copy exact blocks for one module, direct DUT children and library cells."""
    if module not in reachable:
        raise ValueError('Requested module is not reachable: ' + module)
    children = sorted(name for name in modules[module][1] if name in reachable)
    selected = {module, *children} | libraries
    digest = hashlib.sha256()
    with checkpoint.path.open('rb') as source, Path(path).open('xb') as output:
        if identity(os.fstat(source.fileno())) != checkpoint.identity:
            raise ValueError('RTLIL checkpoint changed before partitioning')
        output.write(checkpoint.prefix)
        digest.update(checkpoint.prefix)
        for name, block in checkpoint.blocks.items():
            if name not in selected:
                continue
            source.seek(block.start)
            remaining = block.end - block.start
            block_digest = hashlib.sha256()
            while remaining:
                chunk = source.read(min(1024 * 1024, remaining))
                if not chunk:
                    raise ValueError('Truncated RTLIL module: ' + name)
                output.write(chunk)
                digest.update(chunk)
                block_digest.update(chunk)
                remaining -= len(chunk)
            if block_digest.hexdigest() != block.sha256:
                raise ValueError('RTLIL module changed during partitioning: ' + name)
        if identity(os.fstat(source.fileno())) != checkpoint.identity:
            raise ValueError('RTLIL checkpoint changed during partitioning')
    return children, digest.hexdigest()


def prepare(rtl, checkpoint_path, top, module, output):
    rtl, output = Path(rtl), Path(output)
    if output.exists():
        raise FileExistsError(output)
    modules = inventory(rtl)
    filelist = rtl / 'filelist.f'
    filelist_data = None
    if filelist.is_file():
        filelist_data = filelist.read_bytes()
        names = [line.strip() for line in filelist_data.decode().splitlines()
                 if line.strip() and not line.strip().startswith('verification/')]
        if len(names) != len(set(names)) or set(names) != {m[0].name for m in modules.values()}:
            raise ValueError('RTL filelist does not match split module files')
    elif filelist.exists():
        raise ValueError('RTL filelist is not a file')
    checkpoint = index_checkpoint(checkpoint_path)
    reachable, libraries, macros = validate_hierarchy(checkpoint, modules, top)
    if module not in reachable:
        raise ValueError('Requested module is not reachable: ' + module)
    output.mkdir(parents=True, exist_ok=False)
    children, partition_hash = write_partition(checkpoint, module, modules, reachable, libraries,
                                                output / 'partition.rtlil')
    for path, _, expected in modules.values():
        if file_sha256(path) != expected:
            raise ValueError('Emitted RTL changed while preparing partition: ' + str(path))
    if {p.name for p in rtl.glob('*.sv')} != {m[0].name for m in modules.values()}:
        raise ValueError('Emitted RTL inventory changed while preparing partition')
    if ((filelist_data is None and filelist.exists()) or
            (filelist_data is not None and (not filelist.is_file() or filelist.read_bytes() != filelist_data))):
        raise ValueError('RTL filelist changed while preparing partition')
    manifest = dict(top=top, module=module, checkpoint_sha256=checkpoint.sha256,
                    partition_sha256=partition_hash,
                    emitted_sha256={m[0].name: m[2] for m in modules.values()},
                    filelist_sha256=hashlib.sha256(filelist_data).hexdigest() if filelist_data is not None else None,
                    reachable_definitions=len(reachable), library_definitions=len(libraries),
                    direct_dut_children_to_blackbox=children,
                    direct_child_instances=dict(modules[module][1]),
                    expanded_sram_instances=macros,
                    scope='Partition input only; checkpoint provenance, complete mapping and linked timing remain required',
                    physical_qualified=False)
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('rtl', type=Path)
    parser.add_argument('checkpoint', type=Path)
    parser.add_argument('top')
    parser.add_argument('output', type=Path)
    parser.add_argument('--module', help='Reachable module to partition; defaults to top')
    args = parser.parse_args()
    result = prepare(args.rtl, args.checkpoint, args.top, args.module or args.top, args.output)
    print(json.dumps({key: value for key, value in result.items() if key != 'emitted_sha256'}, indent=2))


if __name__ == '__main__':
    main()
