#!/usr/bin/env python3
"""Copy a complete component from FIRRTL's unrolled, root-level split SV.

Characterized srambank_*_6t122 cells remain external. This intentionally rejects
parameterized/generate/array instances rather than guessing their hierarchy.
"""
import argparse
from collections import Counter
from functools import cache
import hashlib
import json
from pathlib import Path
import re
import shutil

IDENT = r'[A-Za-z_][A-Za-z0-9_$]*'
NAME = r'(?:\\[^\s]+|' + IDENT + r')'
MACRO = re.compile(r'srambank_[1-9]\d*x[1-9]\d*x[1-9]\d*_6t122\Z')
STATEMENT = r'(?m)(?:^[ \t]*|(?<=;)[ \t]*)'
INSTANCE = re.compile(STATEMENT + r'(' + IDENT + r')\s+(' + NAME + r')\s*\(')
KEYWORDS = {'module', 'if', 'else', 'for', 'while', 'case', 'begin', 'end',
            'function', 'task', 'assert', 'assume', 'cover', 'return'}


def code_only(source):
    # Ignore strings as well as comments: assertion text can contain HDL-like
    # names. Preserve line boundaries for the statement recognizer.
    return re.sub(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"',
                  lambda m: ''.join('\n' if c == '\n' else ' ' for c in m[0]), source, flags=re.S)


def children(code):
    if re.search(r'`include\b|\$readmem[hb]\b', code):
        raise ValueError('External HDL/data dependencies are unsupported')
    if re.search(r'\b(generate|genvar)\b', code) or re.search(STATEMENT + IDENT + r'\s*#\s*\(', code):
        raise ValueError('Parameterized or generated hierarchy is unsupported')
    if re.search(STATEMENT + r'\\[^\s]+\s+' + NAME + r'\s*\(', code):
        raise ValueError('Escaped module types are unsupported')
    if re.search(STATEMENT + IDENT + r'\s+' + NAME + r'\s*\[[^;]+\]\s*\(', code):
        raise ValueError('Array instances are unsupported')
    result = Counter()
    names = set()
    for match in INSTANCE.finditer(code):
        kind, name = match.groups()
        if kind in KEYWORDS:
            continue
        name = name.removeprefix('\\')
        if name in names:
            raise ValueError('Duplicate instance: ' + name)
        names.add(name)
        depth, cursor = 1, match.end()
        while depth and cursor < len(code):
            depth += (code[cursor] == '(') - (code[cursor] == ')')
            cursor += 1
        if depth or not code[cursor:].lstrip().startswith(';'):
            raise ValueError('Unsupported instance statement: ' + name)
        result[kind] += 1
    return result


def extract(directory, top, output):
    directory, output = Path(directory), Path(output)
    modules = {}
    for path in sorted(directory.glob('*.sv')):
        code = code_only(path.read_text())
        definitions = re.findall(r'\bmodule\s+(' + IDENT + r')\s*([(#])', code)
        if definitions != [(path.stem, '(')] or len(re.findall(r'\bendmodule\b', code)) != 1:
            raise ValueError('Expected one non-parameterized module matching filename: ' + str(path))
        if MACRO.fullmatch(path.stem):
            raise ValueError('Physical SRAM cells must be external to this controller emission')
        modules[path.stem] = (path, children(code))
    visiting = set()

    @cache
    def visit(name):
        result = Counter({name: 1})
        if MACRO.fullmatch(name):
            return result
        if name not in modules:
            raise ValueError('Missing DUT module: ' + name)
        if name in visiting:
            raise ValueError('Recursive hierarchy: ' + name)
        visiting.add(name)
        for child, count in modules[name][1].items():
            result.update({kind: amount * count for kind, amount in visit(child).items()})
        visiting.remove(name)
        return result

    counts = visit(top)
    selected = set(counts) & modules.keys()
    files = {modules[name][0].name: hashlib.sha256(modules[name][0].read_bytes()).hexdigest()
             for name in sorted(selected)}
    output.mkdir(parents=True, exist_ok=False)
    for name in selected:
        path = modules[name][0]
        shutil.copy2(path, output / path.name)
        if hashlib.sha256((output / path.name).read_bytes()).hexdigest() != files[path.name]:
            raise ValueError('Copied RTL changed: ' + path.name)
    (output / 'filelist.f').write_text(''.join(name + '\n' for name in sorted(files)))
    manifest = dict(top=top, source=str(directory.resolve()), files=files,
        expanded_instances=dict(counts),
        external_macros={name: count for name, count in counts.items() if MACRO.fullmatch(name)},
        scope='Byte-exact complete reachable DUT closure; external characterized SRAM cells require their real libraries. No synthesis/timing claim.')
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('top')
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    result = extract(args.directory, args.top, args.output)
    print(json.dumps(dict(top=result['top'], copied_files=len(result['files']),
                          external_macros=result['external_macros'])))


if __name__ == '__main__':
    main()
