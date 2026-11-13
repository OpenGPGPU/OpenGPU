#!/usr/bin/env python3
"""Print complete source-hierarchy SRAM counts without copying emitted RTL.

This supplies a reproducible audit for a caller's explicit PPA count contract.
It does not establish mapped counts, area, timing, or profile capacities. Only
unrolled split-module emissions are supported; uncertain hierarchy fails.
"""
import argparse
from collections import Counter
from pathlib import Path
import re

from extract_emitted_rtl import IDENT, MACRO, children, code_only, expanded_counts


def inventory(directory, top):
    directory = Path(directory).resolve()
    modules, seen, declared = {}, set(), set()
    filelist = directory / 'filelist.f'
    sources = filelist.read_text().splitlines() if filelist.is_file() else [p.name for p in sorted(directory.glob('*.sv'))]
    if not sources:
        raise ValueError('No emitted sources')
    for raw in sources:
        if not raw.strip() or raw.lstrip().startswith(('#', '//')):
            continue
        path = (directory / raw.strip()).resolve()
        if not path.is_relative_to(directory) or not path.is_file():
            raise ValueError('Missing or external source: ' + raw)
        if path.relative_to(directory).parts[0] == 'verification':
            continue
        if path in seen:
            raise ValueError('Repeated source: ' + raw)
        seen.add(path)
        code = code_only(path.read_text())
        starts = list(re.finditer(r'\bmodule\s+(' + IDENT + r')\s*([(#])', code))
        ends = list(re.finditer(r'\bendmodule\b', code))
        if not starts or len(starts) != len(ends):
            raise ValueError('Incomplete module definitions: ' + raw)
        definitions = []
        for i, (start, end) in enumerate(zip(starts, ends)):
            name, marker = start.groups()
            if marker != '(' or start.end() > end.start() or i + 1 < len(starts) and end.end() > starts[i + 1].start():
                raise ValueError('Parameterized or nested module: ' + raw)
            if MACRO.fullmatch(name):
                # Simulation macro definitions are external physical leaves;
                # they do not replace the characterized LIB/LEF count gate.
                declared.add(name)
                continue
            if name != path.stem or name in modules:
                raise ValueError('Misnamed or duplicate DUT module: ' + name)
            definitions.append(name)
            modules[name] = (path, children(code[start.end():end.start()]), '')
        if definitions != [path.stem]:
            raise ValueError('Expected one DUT module per source: ' + raw)
    counts = expanded_counts(modules, top)
    declared.update(name for name in counts if MACRO.fullmatch(name))
    return Counter({name: counts.get(name, 0) for name in declared})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('rtl', type=Path)
    parser.add_argument('top')
    args = parser.parse_args()
    counts = inventory(args.rtl, args.top)
    for name, count in sorted(counts.items()):
        print(f'--expected-sram-macro {name}={count}')


if __name__ == '__main__':
    main()
