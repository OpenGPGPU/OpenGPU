#!/usr/bin/env python3
"""Prepare a private Linux 7.2 single-object relink; never compiles or boots.

Requires the already configured non-LTO AArch64 build used by the guest. Keep
that source/build read-only. Generated commands and input hashes stay local.
The tested host uses macOS clonefile: no hard links or whole-tree make.
"""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            h.update(block)
    return h.hexdigest()


def need(ok, why):
    if not ok:
        raise ValueError(why)


def saved_command(build, relative):
    lines = (build / relative).read_text().splitlines()
    lines = [line.split(' := ', 1)[1] for line in lines if line.startswith('savedcmd_')]
    need(len(lines) == 1, 'expected one cached command: ' + relative)
    lexer = shlex.shlex(lines[0], posix=True, punctuation_chars=';&|<>')
    lexer.whitespace_split = True
    argv = list(lexer)
    need(not any('$' in arg for arg in argv), 'unexpanded make variable in cached command')
    return argv


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--linux-source', type=Path, required=True)
    parser.add_argument('--linux-build', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--cross-prefix', default='aarch64-linux-gnu-')
    args = parser.parse_args()
    source, build, out = (p.resolve() for p in (args.linux_source, args.linux_build, args.out))
    need(sys.platform == 'darwin', 'this cache-preserving recipe requires macOS clonefile')
    need(not out.exists() and not out.is_relative_to(source) and not out.is_relative_to(build),
         'choose a fresh output outside the frozen source/build')
    need((build / 'include/config/kernel.release').read_text().strip() == '7.2.0', 'expected Linux 7.2.0')
    config = (build / '.config').read_text().splitlines()
    need('CONFIG_LTO_NONE=y' in config, 'only the qualified non-LTO configuration is supported')
    need('CONFIG_ARM64_4K_PAGES=y' in config, 'only 4 KiB ARM64 pages are qualified')
    for flag in ('CONFIG_ARCH_WANTS_PRE_LINK_VMLINUX', 'CONFIG_LIVEPATCH', 'CONFIG_KLP_BUILD',
                 'CONFIG_X86_KERNEL_IBT', 'CONFIG_DEBUG_INFO_BTF', 'CONFIG_GENERIC_BUILTIN_DTB'):
        need(flag + '=y' not in config, 'unsupported cached-link dependency: ' + flag)
    tools = {}
    for name in ('gcc', 'as', 'ld', 'nm', 'ar', 'objcopy'):
        resolved = shutil.which(args.cross_prefix + name)
        need(resolved is not None, 'missing matching cross-tool: ' + name)
        tools[name] = str(Path(resolved).resolve())
    private = out / 'private-o'
    patch = Path(__file__).resolve().parents[1] / 'patches/linux-pagemap-pfnmap-qualification.patch'
    clone = {'.config', 'include/config/auto.conf', 'include/config/kernel.release',
             'arch/arm64/kernel/vmlinux.lds', 'scripts/kallsyms', 'scripts/sorttable',
             '.vmlinux.export.o', 'init/version-timestamp.o'}
    for archive in ('vmlinux.a', 'drivers/firmware/efi/libstub/lib.a'):
        need((build / archive).read_bytes()[:8] == b'!<thin>\n', 'expected a GNU thin archive')
        clone.add(archive)
        members = subprocess.check_output([tools['ar'], 't', archive], cwd=build, text=True).splitlines()
        need(members, 'empty thin archive')
        for member in members:
            path = (build / member).resolve()
            need(path.is_file() and path.is_relative_to(build), 'missing/escaping thin member: ' + member)
            # ar -t already includes the libstub prefix. Do not add archive.parent.
            clone.add(str(path.relative_to(build)))
    for directory in ('include/generated', 'arch/arm64/include/generated'):
        clone.update(str(p.relative_to(build)) for p in (build / directory).rglob('*') if p.is_file())
    inputs = {build / relative for relative in clone}
    cached = ('fs/proc/.task_mmu.o.cmd', 'arch/arm64/kernel/.head.o.cmd',
              '.vmlinux.unstripped.cmd', '.vmlinux.cmd', 'arch/arm64/boot/.Image.cmd')
    inputs.update(build / relative for relative in cached)
    text = (build / cached[0]).read_text()
    deps = text.split('deps_fs/proc/task_mmu.o :=', 1)[1].split('\n\n', 1)[0]
    for line in deps.splitlines():
        value = line.strip().removesuffix('\\').strip()
        if not value:
            continue
        wildcard = re.fullmatch(r'\$\(wildcard ([^)]+)\)', value)
        if wildcard:
            # Make-only zero-byte config sentinels are inputs, not C includes.
            inputs.update(path for path in build.glob(wildcard[1]) if path.is_file())
            continue
        path = Path(value) if Path(value).is_absolute() else build / value
        need(path.is_file(), 'cached C dependency absent: ' + str(path))
        inputs.add(path)
        if path.is_relative_to(build):
            clone.add(str(path.relative_to(build)))
    for relative in ('fs/proc/task_mmu.c', 'mm/pagewalk.c', 'scripts/link-vmlinux.sh',
                     'scripts/mksysmap', 'scripts/file-size.sh', 'scripts/Makefile.build'):
        inputs.add(source / relative)
    inputs.update(Path(value) for value in tools.values())
    inputs.add(Path(subprocess.check_output([tools['gcc'], '-print-prog-name=cc1'], text=True).strip()).resolve())
    inputs.update((patch, Path(sys.executable).resolve(), build / 'arch/arm64/boot/Image', build / 'Module.symvers'))
    inputs.update(Path(__file__).resolve().parent/name for name in
                  ('prepare_pagemap_kernel.py', 'run_pagemap_kernel.py', 'resource_guest_once.py'))
    hashes = {str(path): digest(path) for path in sorted(inputs)}
    total = sum((build / name).stat().st_size for name in clone)
    need(total < 1536 * 1024**2, 'input copy alone exceeds the qualification output budget')
    out.mkdir(parents=True)
    private.mkdir()
    clonefile = ctypes.CDLL('/usr/lib/libSystem.B.dylib', use_errno=True).clonefile
    clonefile.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int]
    for relative in sorted(clone):
        need(not Path(relative).is_absolute() and '..' not in Path(relative).parts,
             'copy path escapes the private output: ' + relative)
        original, destination = build / relative, private / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        need(clonefile(os.fsencode(original), os.fsencode(destination), 0) == 0,
             'clonefile failed: ' + relative)
        a, b = original.stat(), destination.stat()
        need(b.st_nlink == 1 and (a.st_dev, a.st_ino) != (b.st_dev, b.st_ino)
             and digest(destination) == hashes[str(original)], 'COW copy is not independent: ' + relative)
    patched = out / 'source/fs/proc/task_mmu.c'
    patched.parent.mkdir(parents=True)
    shutil.copyfile(source / 'fs/proc/task_mmu.c', patched)
    subprocess.run(['patch', '--batch', '--fuzz=0', '-p1', '-i', str(patch)], cwd=out / 'source', check=True)
    (private / 'arch/arm64/boot').mkdir(parents=True, exist_ok=True)
    compile_argv = saved_command(build, cached[0])
    need(compile_argv[-4:] == ['-c', '-o', 'fs/proc/task_mmu.o', str(source / 'fs/proc/task_mmu.c')],
         'unexpected cached task_mmu compile command')
    compile_argv[0] = tools['gcc']
    compile_argv[-1] = str(patched)
    compile_argv.insert(-4, '-fmacro-prefix-map=' + str(out / 'source') + '/=')
    assembly = saved_command(build, cached[1])
    need(assembly[-4:] == ['-c', '-o', 'arch/arm64/kernel/head.o', str(source / 'arch/arm64/kernel/head.S')],
         'unexpected cached ARM64 assembler command')
    assembly[0] = tools['gcc']
    assembly = [arg for arg in assembly[:-4] if not arg.startswith(
        ('-Wp,-MMD,', '-DKBUILD_MODFILE=', '-DKBUILD_MODNAME=', '-D__KBUILD_MODNAME='))]
    original_script = (source / 'scripts/link-vmlinux.sh').read_text()
    implicit = '${MAKE} -f "${srctree}/scripts/Makefile.build" obj=init init/version-timestamp.o'
    need(original_script.count(implicit) == 1, 'link script implicit make call changed')
    (out / 'link-vmlinux.private.sh').write_text(original_script.replace(implicit,
        '"${QUALIFICATION_REUSE_VERSION_GUARD}" -f "${srctree}/scripts/Makefile.build" obj=init init/version-timestamp.o'))
    version_hash = hashes[str(build / 'init/version-timestamp.o')]
    wrappers = {
        'reuse_version.py': f'''import hashlib,sys
from pathlib import Path
if Path.cwd().resolve()!=Path({str(private)!r}) or sys.argv[1:]!=['-f',{str(source / 'scripts/Makefile.build')!r},'obj=init','init/version-timestamp.o']:
    raise SystemExit('unexpected version-object make invocation')
if hashlib.sha256(Path('init/version-timestamp.o').read_bytes()).hexdigest()!={version_hash!r}:
    raise SystemExit('frozen version object changed')
''',
        'kallsyms_cc.py': f'''import json,re,subprocess,sys
from pathlib import Path
args=sys.argv[1:]
if Path.cwd().resolve()!=Path({str(private)!r}) or len(args)!=4 or args[:2]!=['-c','-o']:
    raise SystemExit('unexpected generated-assembly invocation')
if not re.fullmatch(r'\\.tmp_vmlinux[0-3]\\.kallsyms\\.o',args[2]) or args[3]!=args[2][:-2]+'.S':
    raise SystemExit('unexpected kallsyms input/output')
argv={assembly!r}+args
with Path('actual-kallsyms-argv.jsonl').open('a') as stream: stream.write(json.dumps(argv)+'\\n')
raise SystemExit(subprocess.run(argv).returncode)
'''}
    for name, body in wrappers.items():
        path = out / name
        path.write_text('#!' + sys.executable + '\n' + body)
        path.chmod(0o755)
    # These are the actual private files read during compilation/linking. The
    # one overwritten C object and all newly generated link outputs are absent.
    read_only = [private/relative for relative in sorted(clone) if relative != 'fs/proc/task_mmu.o']
    read_only += [patched, out/'link-vmlinux.private.sh', out/'reuse_version.py', out/'kallsyms_cc.py']
    hashes.update((str(path), digest(path)) for path in read_only)
    link = saved_command(build, cached[2])
    need(link[0] == str(source / 'scripts/link-vmlinux.sh') and link[4:] == ['vmlinux.unstripped', ';', 'true'],
         'unexpected cached final link')
    link = ['/bin/sh', str(out / 'link-vmlinux.private.sh'), tools['ld'], *link[2:5]]
    final = saved_command(build, cached[3])
    need(final.count(';') == 1, 'unexpected vmlinux objcopy pipeline')
    split = final.index(';')
    elf1, elf2 = final[:split], final[split+1:]
    image = saved_command(build, cached[4])
    for argv in (elf1, elf2, image):
        argv[0] = tools['objcopy']
        need(not any(arg in (';', '&', '|', '>', '<') for arg in argv), 'unexpected shell operation')
    env = dict(PATH=os.environ['PATH'], LANG='C', LC_ALL='C', TZ='UTC', ARCH='arm64', SRCARCH='arm64',
               srctree=str(source), objtree='.', KBUILD_LDS='arch/arm64/kernel/vmlinux.lds',
               KBUILD_VMLINUX_LIBS='drivers/firmware/efi/libstub/lib.a', NM=tools['nm'],
               CC=str(out / 'kallsyms_cc.py'), CONFIG_SHELL='/bin/sh', MAKE='/usr/bin/false',
               QUALIFICATION_REUSE_VERSION_GUARD=str(out / 'reuse_version.py'),
               NOSTDINC_FLAGS='', LINUXINCLUDE='', KBUILD_CPPFLAGS='', KBUILD_AFLAGS='',
               KBUILD_AFLAGS_KERNEL='', OMP_NUM_THREADS='1', PYTHONDONTWRITEBYTECODE='1')
    plan = dict(cwd=str(private), environment=env, stages=[compile_argv, link, elf1, elf2, image],
                input_sha256=hashes, clone_files=len(clone), clone_logical_bytes=total,
                source=str(source), original_build=str(build), ordinary_C_objects=1,
                generated_assembly='original kallsyms passes 0/1/2 and conditional 3',
                scope='private privileged 4KiB GEM DMA/CMA PFNMAP observation, not stock Linux')
    (out / 'commands.json').write_text(json.dumps(plan, indent=2) + '\n')
    need(all(digest(Path(path)) == value for path, value in hashes.items()), 'original inputs changed during preparation')
    print(json.dumps(dict(status='PREPARED_NOT_COMPILED', commands=str(out / 'commands.json'),
                          plan_sha256=digest(out / 'commands.json'),
                          clone_files=len(clone), clone_logical_bytes=total)))


if __name__ == '__main__':
    main()
