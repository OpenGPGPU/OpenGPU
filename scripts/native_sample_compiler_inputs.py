"""Read-only identities for original Mesa sample-compiler inputs.

Only used after the actual sample executor guard. No compilation or probing.
"""
import hashlib
import os
from pathlib import Path
import shlex

MESA_LIBRARIES = ('src/compiler/glsl/libglsl.a', 'src/compiler/nir/libnir.a',
    'src/compiler/libcompiler.a', 'src/compiler/glsl/glcpp/libglcpp.a',
    'src/gallium/auxiliary/libgallium.a', 'src/util/libmesa_util.a',
    'src/util/format/libmesa_format.a', 'src/c11/impl/libmesa_util_c11.a')
MESA_UNITS = ('compiler/glsl/standalone.cpp', 'compiler/glsl/standalone_scaffolding.cpp',
    'compiler/glsl/ir_builder_print_visitor.cpp', 'compiler/glsl/glcpp/pp_standalone_scaffolding.c',
    'mesa/main/extensions_table.c', 'mesa/program/prog_parameter.c',
    'mesa/program/symbol_table.c', 'mesa/program/dummy_errors.c')


def require(value, message):
    if not value:
        raise ValueError(message)


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def input_tree(root, *, build=False):
    require(root.is_dir() and not root.is_symlink(), 'missing/aliased compiler input tree: ' + str(root))
    for directory, directories, names in os.walk(root, followlinks=False):
        directories[:] = [n for n in directories if n not in ('.git', '__pycache__')]
        require(not any((Path(directory)/n).is_symlink() for n in directories), 'aliased compiler include directory')
        for name in names:
            # Existing unrelated object/archive outputs are not header inputs.
            # The eight actual link archives are mandatory separate entries.
            if build and (name.endswith(('.o', '.a', '.so')) or '.so.' in name):
                continue
            path = Path(directory)/name
            if path.is_file():
                yield path


def snapshot(mesa, build, libraries, gpu):
    require((mesa/'VERSION').read_text().strip() == '22.3.6', 'original Mesa22.3.6 required')
    required = [mesa/'VERSION', build/'compile_commands.json',
                *(build/name for name in MESA_LIBRARIES), libraries/'libexpat.so.1.6.7']
    inputs = set(required)
    for root in (mesa/'src', mesa/'include', build/'src', build/'include'):
        inputs.update(input_tree(root, build=root.is_relative_to(build)))
    # LD_LIBRARY_PATH can affect every newly linked/run tool. Bind its actual
    # files, not just the explicitly named expat link input.
    inputs.update(input_tree(libraries))
    result = {}
    for path in sorted(inputs):
        resolved = path.resolve(strict=True)
        require(resolved.is_file() and resolved.is_relative_to(gpu), 'compiler input escapes GPU workspace: ' + str(path))
        result[str(path)] = (str(resolved), sha256(resolved))
    return result


def check_dependencies(cpu, source, bound, qualified_sources=None):
    """Actual compiler-generated make dependencies must have been bound.

    -MMD excludes immutable image system headers. Fresh private scaffold and
    generated fixture includes live below cpu; archive sources live below
    source; the live qualifier also requires each archive input's recorded
    source hash. Every other actual header/source must match the pre-build map.
    """
    dependencies = sorted(cpu.rglob('*.d'))
    require(len(dependencies) == len(MESA_UNITS)+5, 'incomplete actual compiler dependency set')
    observed = set()
    for path in dependencies:
        text = path.read_text().replace('\\\n', '')
        require(':' in text, 'malformed actual compiler dependencies')
        for name in shlex.split(text.split(':', 1)[1]):
            item = Path(name)
            require(item.is_absolute(), 'relative actual compiler input')
            resolved = item.resolve(strict=True)
            if resolved.is_relative_to(cpu):
                observed.add(str(resolved))
                continue
            if resolved.is_relative_to(source):
                if qualified_sources is not None:
                    name = str(resolved.relative_to(source))
                    require(qualified_sources.get(name) == sha256(resolved),
                            'actual archive compiler input is not qualified: ' + name)
                observed.add(str(resolved))
                continue
            expected = bound.get(str(item), bound.get(str(resolved)))
            require(expected == (str(resolved), sha256(resolved)),
                    'unbound/changed actual compiler source or header: ' + str(item))
            observed.add(str(item))
    require(observed, 'empty actual compiler dependencies')
    return observed
