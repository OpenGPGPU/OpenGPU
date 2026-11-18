"""Guard the original raw RV32 caller's three native units inside its fixed image.

Actual system headers are discovered before compilation with the same compiler
options. Full MD closure, exact link objects, immutable image tools and all
qualified source inputs remain bound through the original source process.
"""
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess

from compute_owner_protocol import strict_json
from native_sample_compiler_inputs import sha256

UNITS = ('driver/tests/opengpu_compute_host_fixture.c', 'userspace/opengpu.c',
         'userspace/tests/compute_global_host.c')
REQUIRED = set(UNITS) | {'driver/tests/opengpu_compute_host_fixture.h',
    'driver/opengpu_compute.c', 'driver/opengpu_memory.c', 'driver/opengpu_hw.c',
    'driver/opengpu_scheduler.c', 'driver/opengpu_admission.c', 'driver/opengpu_drm_device.c',
    'scripts/test_compute_host.py', 'scripts/compute_global_host_producer.py',
    'scripts/compute_global_host_inputs.py', 'scripts/compute_global_atomic_cases.py',
    'scripts/compute_global_atomic_source.py', 'scripts/compute_global_recovery.py',
    'scripts/compute_global_accepted_recovery.py', 'scripts/compute_cache_alias_cases.py',
    'scripts/compute_cache_alias_source.py', 'scripts/compute_cache_alias_recovery.py'}
UNRECORDED_ENV = ('CPATH', 'C_INCLUDE_PATH', 'CPLUS_INCLUDE_PATH', 'COMPILER_PATH',
    'GCC_EXEC_PREFIX', 'LIBRARY_PATH', 'LD_PRELOAD', 'LD_LIBRARY_PATH', 'CCC_OVERRIDE_OPTIONS',
    'CFLAGS', 'CPPFLAGS', 'LDFLAGS', 'SDKROOT')


def require(ok, message):
    if not ok:
        raise ValueError('global compiler inputs: ' + message)


def dependency_names(path):
    text = path.read_text().replace('\\\n', ' ')
    require(':' in text, 'malformed full make dependencies')
    names = set(shlex.split(text.split(':', 1)[1]))
    require(names and all(Path(name).is_absolute() for name in names), 'empty or relative full make dependencies')
    return names


class Inputs:
    def __init__(self, request_path, source):
        self.source = source.resolve(strict=True)
        self.request_path = request_path.resolve(strict=True)
        request = strict_json(self.request_path)
        require(set(request) == {'schema', 'source', 'qualified', 'compilerImage'} and
                request['schema'] == 'opengpu-global-host-inputs-v1', 'foreign bound-input request')
        require(request['source'] == str(self.source), 'source archive differs from qualification')
        image = request['compilerImage']
        require(type(image) is str and re.fullmatch('sha256:[0-9a-f]{64}', image) and
                os.environ.get('OPENGPU_COMPILER_IMAGE') == image, 'immutable compiler image binding differs')
        require(not any(os.environ.get(name) for name in UNRECORDED_ENV), 'unrecorded compiler/include/link environment')
        self.qualified = request['qualified']
        require(type(self.qualified) is dict and REQUIRED <= set(self.qualified), 'missing original compiler source closure')
        for name, digest in self.qualified.items():
            require(type(name) is str and not Path(name).is_absolute() and '..' not in Path(name).parts and
                    type(digest) is str and re.fullmatch('[0-9a-f]{64}', digest), 'invalid qualified source entry')
        self.request_digest = sha256(self.request_path)
        self.generated, self.dependencies, self.tools, self.objects, self.link_files, self.proofs = {}, {}, {}, {}, {}, {}
        self.binary = None
        self.source_unchanged()
        cc = shlex.split(os.environ.get('CC', 'cc'))
        require(len(cc) == 1 and shutil.which(cc[0]), 'a single real C compiler is required')
        self.cc = cc[0]
        self.system_file(Path(shutil.which(self.cc)), self.tools)
        for program in ('cc1', 'as', 'collect2', 'ld'):
            name = subprocess.check_output([self.cc, '-print-prog-name=' + program], text=True).strip()
            path = Path(name) if Path(name).is_absolute() else Path(shutil.which(name) or '')
            self.system_file(path, self.tools)
        for library in ('libasan.so', 'libubsan.so', 'libgcc_s.so', 'libc.so'):
            name = subprocess.check_output([self.cc, '-print-file-name=' + library], text=True).strip()
            self.system_file(Path(name), self.tools)

    def source_unchanged(self):
        require(sha256(self.request_path) == self.request_digest, 'bound-input request changed')
        for name, digest in self.qualified.items():
            path = self.source / name
            require(path.is_file() and path.resolve(strict=True).is_relative_to(self.source) and sha256(path) == digest,
                    'original qualified source changed: ' + name)

    def system_file(self, path, target):
        require(path.is_absolute() and path.is_file(), 'missing real compiler/SDK/link input: ' + str(path))
        resolved = path.resolve(strict=True)
        require(resolved.is_relative_to('/usr'), 'compiler/SDK input escaped immutable system image: ' + str(path))
        target[str(path)] = (str(resolved), sha256(resolved))

    def input_file(self, path):
        resolved = path.resolve(strict=True)
        require(path.is_absolute() and resolved.is_file(), 'missing actual compiler dependency')
        if resolved.is_relative_to(self.out / 'kernel'):
            require(self.generated.get(str(resolved)) == sha256(resolved), 'changed/unbound generated Linux body')
        elif resolved.is_relative_to(self.source):
            name = str(resolved.relative_to(self.source))
            require(self.qualified.get(name) == sha256(resolved), 'unqualified actual source dependency: ' + name)
        else:
            self.system_file(path, self.tools)
        return str(resolved), sha256(resolved)

    def prepare(self, out, plans, link):
        self.out = out.resolve(strict=True)
        require(self.out.is_relative_to(self.request_path.parent), 'native outputs escaped private qualification IPC')
        self.generated = {str(p.resolve()): sha256(p) for p in (self.out / 'kernel').glob('*.inc')}
        require(self.generated, 'original extracted Linux bodies missing')
        require(len(plans) == 3, 'exact three native compilation units required')
        self.plans = [(label, list(map(str, argv))) for label, argv in plans]
        self.link = list(map(str, link))
        seen = set()
        for index, (_label, argv) in enumerate(self.plans):
            require(all(argv.count(flag) == 1 for flag in ('-MD', '-MF', '-c', '-o')) and '-MMD' not in argv and
                    '-fsanitize=address,undefined' in argv and not any('OPENGPU_COMPUTE_HOST_TEST_MAIN' in flag for flag in argv),
                    'actual native compile lacks full sanitized production closure')
            unit = Path(argv[argv.index('-c') + 1])
            relative = str(unit.relative_to(self.source))
            require(relative in UNITS and relative not in seen, 'foreign/repeated native compilation unit')
            seen.add(relative)
            obj, dep = Path(argv[argv.index('-o') + 1]), Path(argv[argv.index('-MF') + 1])
            require(obj.parent == self.out and dep == obj.with_suffix('.d'), 'native compile output escaped private build')
            before = self.out / ('preflight-%u.d' % index)
            preflight, skip = [], False
            for flag in argv:
                if skip:
                    skip = False
                elif flag in ('-o', '-MF'):
                    skip = True
                elif flag not in ('-c', '-MD'):
                    preflight.append(flag)
            done = subprocess.run(preflight + ['-M', '-MF', str(before), '-MT', str(obj)],
                                  text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            require(done.returncode == 0, 'original preprocessor failed: ' + done.stderr[-4000:])
            self.dependencies[str(dep)] = {name: self.input_file(Path(name)) for name in dependency_names(before)}
        require(seen == set(UNITS), 'incomplete original native unit set')
        self.unchanged(full=True)

    def before_link(self):
        self.unchanged(full=True)
        for _label, argv in self.plans:
            obj = Path(argv[argv.index('-o') + 1])
            require(obj.is_file(), 'missing actual native object')
            self.objects[str(obj)] = sha256(obj)

    def built(self, commands, binary):
        expected = [dict(label=label, argv=argv, cwd=str(self.out)) for label, argv in self.plans]
        expected.append(dict(label='link-original-global-caller', argv=self.link, cwd=str(self.out)))
        require(commands == expected and strict_json(self.out / 'commands.json') == expected, 'actual native command log differs')
        for name, bound in self.dependencies.items():
            require(dependency_names(Path(name)) == set(bound), 'actual compiler header closure differs from preflight')
            for path, record in bound.items():
                require(self.input_file(Path(path)) == record, 'actual compiler input changed')
        traced = set()
        for line in (self.out / 'link-original-global-caller.log').read_text().splitlines():
            require(line.startswith('/'), 'unexpected original native link trace')
            path = Path(line)
            if str(path) in self.objects:
                traced.add(str(path))
            else:
                self.system_file(path, self.link_files)
        require(traced == set(self.objects) and self.link_files, 'original native link objects/runtime closure differs')
        self.proofs = {str(path): sha256(path) for path in
                       [self.out / 'commands.json', self.out / 'link-original-global-caller.log',
                        *self.out.glob('*.d')]}
        self.binary = (binary, sha256(binary))
        self.unchanged(full=True)

    def unchanged(self, *, full=False):
        self.source_unchanged()
        for name, digest in self.generated.items():
            require(sha256(Path(name)) == digest, 'generated original Linux body changed')
        for name, digest in self.objects.items():
            require(sha256(Path(name)) == digest, 'original native object changed')
        for name, digest in self.proofs.items():
            require(sha256(Path(name)) == digest, 'original native dependency/link receipt changed')
        if self.binary is not None:
            require(sha256(self.binary[0]) == self.binary[1], 'original native producer binary changed')
        if full:
            for name, record in {**self.tools, **self.link_files}.items():
                path = Path(name)
                require((str(path.resolve(strict=True)), sha256(path)) == record, 'compiler/SDK/runtime image input changed')
