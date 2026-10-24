#!/usr/bin/env python3
"""Limit assertion-only host-test edits using registered ScalaTest locations.

Only additions strictly inside existing `it should ... in { ... }` bodies
qualify. Fixture, registration, deletion and production changes keep the
existing suite selection. Missing or ambiguous metadata also keeps it broad.
"""
import base64
from collections import Counter
from pathlib import Path
import re
import subprocess

from changed_test_suites import changed_suites


def mask_literals(source):
    # Preserve offsets and lines while ignoring braces inside strings/comments.
    tokens = r'/\*.*?\*/|//[^\n]*|""".*?"""|"(?:\\.|[^"\\])*"|\x27(?:\\.|[^\x27\\])*\x27'
    return re.sub(tokens, lambda m: re.sub(r'[^\n]', ' ', m[0]), source, flags=re.S)


def added_test_bodies(source, diff):
    """Return registration line ranges, or None when any edit is broader."""
    # Be conservative about nested block comments, which require a Scala lexer.
    if re.search(r'/\*(?:(?!\*/).)*?/\*', source, re.S):
        return None
    additions = []
    for old_count, new_start, new_count in re.findall(
            r'^@@ -\d+(?:,(\d+))? \+(\d+)(?:,(\d+))? @@', diff, re.M):
        if old_count != '0':
            return None
        additions.extend(range(int(new_start), int(new_start) + int(new_count or '1')))
    if not additions:
        return None
    code = mask_literals(source)
    bodies = []
    for match in re.finditer(r'\bit\s+should\b[^{};]*?\bin\s*\{', code):
        opening = match.end() - 1
        depth = 1
        closing = opening
        while depth and closing + 1 < len(code):
            closing += 1
            depth += (code[closing] == '{') - (code[closing] == '}')
        if depth:
            return None
        first = source.count('\n', 0, match.start()) + 1
        begin = source.count('\n', 0, opening) + 1
        end = source.count('\n', 0, closing) + 1
        bodies.append((first, begin, end))
    modified = [body for body in bodies if any(body[1] < line < body[2] for line in additions)]
    if any(sum(begin < line < end for _, begin, end in bodies) != 1 for line in additions):
        return None
    return modified


def locations_from(path):
    locations = {}
    if Path(path).exists():
        for line in Path(path).read_text().splitlines():
            suite, name, filename, number = line.split('\t')
            key = (suite, base64.b64decode(name, validate=True).decode())
            if key in locations:
                raise ValueError('Duplicate ScalaTest location')
            locations[key] = (base64.b64decode(filename, validate=True).decode(), int(number))
    return locations


def narrow(selection, catalog, root, base, locations):
    if not isinstance(selection, list):
        raise ValueError('Changed-case selection is only supported for PR suite lists')
    def git(*args):
        return subprocess.check_output(['git', *args], cwd=root, text=True)
    changed = git('diff', '--name-only', base, 'HEAD').splitlines()
    filenames = Counter(path.name for path in (root / 'src/test/scala').rglob('*.scala'))
    safe = {}
    for path in changed:
        if not path.startswith('src/test/scala/') or not path.endswith('HostAxiSpec.scala'):
            continue
        source = root / path
        if not source.exists() or filenames[source.name] != 1:
            continue
        bodies = added_test_bodies(source.read_text(), git('diff', '--no-ext-diff', '-U0', base, 'HEAD', '--', path))
        # Every edited body must have at least one real registered consumer.
        if bodies and all(any(filename == source.name and first <= line <= begin
                             for (suite, name), (filename, line) in locations.items()
                             if name in catalog.get(suite, [])) for first, begin, _ in bodies):
            safe[path] = bodies
    broad = set(changed_suites(root, [path for path in changed if path not in safe]))
    ranges = {Path(path).name: bodies for path, bodies in safe.items()}
    filtered = dict(catalog)
    selected = []
    exact = set()
    for suite in selection:
        cases = catalog.get(suite)
        if suite in broad or cases is None or any((suite, name) not in locations for name in cases):
            selected.append(suite)
            continue
        kept = [name for name in cases if any(first <= locations[suite, name][1] <= begin
                for first, begin, _ in ranges.get(locations[suite, name][0], []))]
        if kept:
            selected.append(suite)
            filtered[suite] = kept
            # Even a one-case subset must use an explicit -t, never a whole suite.
            exact.add(suite)
    return selected, filtered, exact
