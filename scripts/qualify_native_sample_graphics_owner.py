#!/usr/bin/env python3
"""Original Mesa ABI1 -> two live protected owners on a guarded ordinary112 DUT.

No model generation, CPU completion peer or sample-point relabeling is
available. The independent controller checks both images/GNY2 and cleanup.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import uuid

import native_sample_graphics_inputs as inputs
from native_sample_owner_protocol import strict_json
from owner_process_lifetime import CleanupIncomplete, stop_group, remove_container
from owner_resource_contract import SAMPLE_PROFILE

ROOT = Path(__file__).resolve().parents[1]
KIND = 'native-sample-graphics'
SUCCESS = 'Ordinary112 two-owner execution, independent images/GNY2 and actual cleanup PASS; clock/FPS unqualified'
PROVENANCE = (
    'scripts/qualify_native_sample_graphics_owner.py', 'scripts/native_sample_graphics_inputs.py',
    'scripts/native_sample_graphics_controller.py', 'scripts/native_sample_graphics_protocol.py',
    'scripts/native_sample_graphics_owner_oracle.py', 'scripts/native_sample_graphics_recipe.py',
    'scripts/native_sample_graphics_source_oracle.py', 'scripts/native_sample_graphics_gny2_oracle.py',
    'scripts/test_gallium_compute_uniforms.py', 'scripts/test_gallium_sample_graphics_uniforms.py',
    'scripts/test_gallium_graphics_uniforms.py', 'scripts/native_sample_compiler_inputs.py',
    'scripts/prepare_owner_executor.py', 'scripts/native_sample_owner_protocol.py',
    'scripts/compute_owner_protocol.py', 'scripts/owner_resource_contract.py',
    'scripts/apply_mesa_uniform_index_patch.py', 'scripts/test_native_mmio_capture.py',
    'scripts/test_native_submit.py', 'scripts/owner_process_lifetime.py',
    'userspace/tests/graphics_sample_live.inc', 'userspace/tests/graphics_sample_entry.h',
    'userspace/tests/graphics_sample_source_plan.inc', 'userspace/tests/graphics_sample_owner_ipc.h',
    *inputs.SCHEMAS, *inputs.GPU_UNITS)


def require(ok, message):
    if not ok: raise ValueError('ordinary112 qualification: '+message)


def regular(path): return inputs.regular(path)


def checked_inputs(args):
    """Invalid qualification must fail before archive, Docker or compiler scans."""
    require(args.executor_qualification.is_file() and not args.executor_qualification.is_symlink(),
            'a real guarded native-sample-graphics executor qualification is required')
    regular(args.executor)
    python = regular(args.executor_checker_python.resolve(strict=True))
    require(os.access(args.executor,os.X_OK) and os.access(python,os.X_OK),
            'executor and original checker Python must be executable')
    checker = args.executor_checker.resolve(strict=True)
    require(checker == ROOT/'scripts/prepare_owner_executor.py', 'original ordinary112 source checker required')
    record = strict_json(args.executor_qualification)
    expected = dict(schema='opengpu-owner-executor-v1',version=1,kind=KIND,profile=SAMPLE_PROFILE,
        source_root=str(ROOT),ownerResourceVersion=2,ownerResourceStage='geometry-compute-sample',
        fullResourceQualification=False,executorBuildVerified=True,sourceQualificationRequired=True,
        gpuExecution=False,clockQualified=False,fpsQualified=False)
    require(all(type(record.get(k)) is type(v) and record[k] == v for k,v in expected.items()),
            'wrong exact kind/factory/source/scope; single-record and old96 contracts are separate')
    sources = record.get('source_sha256')
    require(type(sources) is dict and all(name in sources for name in PROVENANCE),
            'qualification omits mandatory ordinary112 producer/oracle/input provenance')
    check_archive(ROOT,sources)
    subprocess.run([python,checker,'--stage','check','--kind',KIND,
        '--qualification',args.executor_qualification,'--executor',args.executor],check=True)
    return (inputs.sha256(args.executor_qualification),inputs.sha256(args.executor),inputs.sha256(python)), sources


def revision():
    require(not subprocess.check_output(['git','-C',ROOT,'status','--porcelain']).strip(),
            'requires a clean committed original checkout')
    return subprocess.check_output(['git','-C',ROOT,'rev-parse','HEAD'],text=True).strip()


def check_archive(source, sources):
    for name,digest in sources.items():
        require(type(name) is str and type(digest) is str, 'invalid qualified source identity types')
        relative = Path(name)
        require(not relative.is_absolute() and '..' not in relative.parts and
                re.fullmatch('[0-9a-f]{64}',digest) is not None, 'invalid qualified source identity')
        require(inputs.sha256(regular(source/relative)) == digest == inputs.sha256(regular(ROOT/relative)),
                'fixed archive differs from its original qualified source: '+name)


def docker_command(args, *, gpu, source, image, name, writable):
    require(re.fullmatch('sha256:[0-9a-f]{64}',image) is not None,'immutable compiler image ID required')
    command = ['docker','run','--rm','--init','--name',name,'--memory','512m','--memory-swap','512m',
        '--cpus','1','--network=none','--read-only','-v',f'{gpu}:{gpu}:ro',
        '-v',f'{writable}:{writable}:rw','-w',str(source)]
    environment = dict(PYTHONDONTWRITEBYTECODE='1',CC='cc',CXX='c++',
        OPENGPU_MESA_DIR=str(args.mesa),OPENGPU_MESA_BUILD=str(args.mesa_build),
        OPENGPU_DRM_INCLUDE=str(args.drm_include),
        OPENGPU_MESA_LINK_LIBS='-l:libz.so.1 '+str(args.library_directory/'libexpat.so.1.6.7'),
        LD_LIBRARY_PATH=str(args.library_directory),TMPDIR=str(writable),
        XDG_CACHE_HOME=str(writable)+'/cache',MESA_SHADER_CACHE_DISABLE='true',
        ASAN_OPTIONS='detect_leaks=0:abort_on_error=1',UBSAN_OPTIONS='halt_on_error=1:print_stacktrace=1',
        OPENGPU_NATIVE_HOST_TIMEOUT=str(args.timeout))
    for key,value in environment.items():command += ['-e',key+'='+value]
    return command+[image]


def run_build(args, *, gpu, source, temporary, image, compiler, qualified):
    cpu = temporary/'cpu';cpu.mkdir()
    request = cpu/'build-request.json'
    request.write_text(json.dumps(dict(cpu=str(cpu),source=str(source),mesa=str(args.mesa),
        mesa_build=str(args.mesa_build),libraries=str(args.library_directory),drm=str(args.drm_include),
        gpu=str(gpu),bound=compiler,qualified=qualified))+'\n')
    identity = inputs.sha256(request)
    name = 'opengpu-ordinary112-build-'+uuid.uuid4().hex
    command = docker_command(args,gpu=gpu,source=source,image=image,name=name,writable=cpu)
    process = None
    try:
        with (temporary/'build.log').open('w') as output:
            process = subprocess.Popen(command+['python3','-B',str(source/'scripts/native_sample_graphics_inputs.py'),
                '--build-request',str(request)],stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
            require(process.wait(timeout=args.timeout) == 0,'actual Linux LIVE producer build failed; no DUT ran')
    finally:
        try:stop_group(process)
        finally:remove_container(name)
    require(inputs.sha256(request) == identity,'compiler/qualified-source build request changed')
    report = strict_json(regular(cpu/'build-inputs.json'))
    require(type(report) is dict and set(report) == {'schema','execution','generated'} and
            report['schema'] == inputs.BUILD_SCHEMA and report['execution'] is False,
            'wrong prepare-only compiler input report')
    return cpu, report['generated']


def producer_command(args, *, gpu, source, cpu, image, name):
    command = docker_command(args,gpu=gpu,source=source,image=image,name=name,writable='{ipc}')
    at = command.index(image)
    command[at:at] = ['-e','OPENGPU_SAMPLE_GRAPHICS_HOST_DIRECTORY={ipc}',
                     '-e','OPENGPU_SAMPLE_GRAPHICS_HOST_PROFILE={ipc}/profile.bin']
    return command+[str(cpu/'producer/cs_uniform_flow'),str(cpu/'producer/glsl')]


def consumer_cleanup(path, args, pid):
    expected = dict(schema='opengpu-native-sample-graphics-consumer-cleanup-v1',kind=KIND,
        controllerPID=pid,sourceRoot=str(ROOT),executor=str(args.executor.absolute()),
        qualification=str(args.executor_qualification.absolute()),consumerCleanupConfirmed=True)
    try:
        record = strict_json(regular(path))
        require(type(record) is dict and set(record) == set(expected) and all(
            type(record[k]) is type(value) and record[k] == value for k,value in expected.items()),
            'wrong controller consumer-exit confirmation')
    except (OSError,ValueError,TypeError) as error:
        raise CleanupIncomplete('ordinary112 consumer cleanup is unconfirmed; preserve owned inputs') from error


def run_controller(args, *, gpu, source, cpu, temporary, image):
    name = 'opengpu-ordinary112-'+uuid.uuid4().hex
    argv_file = temporary/'bridge-command.json'
    argv_file.write_text(json.dumps(producer_command(args,gpu=gpu,source=source,cpu=cpu,image=image,name=name))+'\n')
    cleanup = temporary/'controller-cleanup.json'
    require(not cleanup.exists() and not cleanup.is_symlink(),'consumer cleanup output must start absent')
    process = None
    try:
        with (temporary/'controller.log').open('w') as output:
            process = subprocess.Popen([sys.executable,ROOT/'scripts/native_sample_graphics_controller.py',
                '--executor',args.executor,'--executor-qualification',args.executor_qualification,
                '--executor-checker',args.executor_checker,'--executor-checker-python',args.executor_checker_python,
                '--bridge-command',argv_file,'--bridge-container-name',name,'--timeout',str(args.timeout),
                '--cleanup-receipt',cleanup],
                stdout=output,stderr=subprocess.STDOUT,start_new_session=True,
                env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1',TMPDIR=str(temporary)))
            require(process.wait() == 0,'actual ordinary112 images/GNY2/cleanup or producer marker failed')
        require((temporary/'controller.log').read_text().splitlines().count(SUCCESS) == 1,
                'original controller did not report its unique checked two-owner success')
    finally:
        try:stop_group(process)
        finally:remove_container(name)
        if process is not None:consumer_cleanup(cleanup,args,process.pid)


def main():
    gpu = next(p for p in (ROOT,*ROOT.parents) if (p/'.git').is_dir())
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('executor','executor-qualification','executor-checker','executor-checker-python'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--mesa',type=Path,default=gpu/'depends/mesa')
    parser.add_argument('--mesa-build',type=Path,default=gpu/'depends/mesa-build')
    parser.add_argument('--drm-include',type=Path,default=gpu/'depends/aarch64-root/include')
    parser.add_argument('--library-directory',type=Path,default=gpu/'depends/aarch64-root/lib')
    parser.add_argument('--compiler-image',default='gpu-gtk-ci-check:latest')
    parser.add_argument('--timeout',type=int,default=3600)
    args = parser.parse_args()
    require(0 < args.timeout <= 86400,'timeout must be 1..86400 seconds')
    for key in ('executor','executor_qualification','executor_checker','executor_checker_python'):
        setattr(args,key,getattr(args,key).absolute())
    identity,qualified = checked_inputs(args)
    require(not any(name in os.environ for name in ('OPENGPU_NATIVE_HOST_DIRECTORY','OPENGPU_NATIVE_HOST_PROFILE',
        'OPENGPU_SAMPLE_GRAPHICS_HOST_DIRECTORY','OPENGPU_SAMPLE_GRAPHICS_HOST_PROFILE')),
        'standalone qualifier must start without an inherited live producer environment')
    original = revision()
    for key in ('mesa','mesa_build','library_directory','drm_include'):
        path = getattr(args,key).resolve(strict=True)
        require(path.is_dir() and path.is_relative_to(gpu),'compiler inputs must remain inside GPU workspace')
        setattr(args,key,path)
    compiler = inputs.snapshot(args.mesa,args.mesa_build,args.library_directory,args.drm_include,gpu)
    image = subprocess.check_output(['docker','image','inspect',args.compiler_image,'--format','{{.Id}}'],text=True).strip()
    require(re.fullmatch('sha256:[0-9a-f]{64}',image) is not None,'local immutable compiler image required')
    def terminate(_signal,_frame):raise KeyboardInterrupt('ordinary112 qualification terminated')
    previous = signal.signal(signal.SIGTERM,terminate)
    temporary = None;preserve = False
    try:
        temporary = Path(tempfile.mkdtemp(prefix='ordinary112-original-',dir=gpu/'.artifacts'))
        source = temporary/'source';source.mkdir()
        archive = subprocess.Popen(['git','-C',ROOT,'archive',original],stdout=subprocess.PIPE,start_new_session=True)
        try:
            subprocess.run(['tar','-x','-C',source],stdin=archive.stdout,check=True,timeout=args.timeout)
            require(archive.wait(timeout=args.timeout) == 0,'fixed committed source archive failed')
        finally:
            archive.stdout.close();stop_group(archive)
        check_archive(source,qualified)
        cpu,generated = run_build(args,gpu=gpu,source=source,temporary=temporary,image=image,
                                  compiler=compiler,qualified=qualified)
        products = inputs.compiled_outputs(cpu)
        def check(stage):
            require(checked_inputs(args) == (identity,qualified) and revision() == original,
                    'qualified original source/model changed '+stage)
            check_archive(source,qualified)
            require(inputs.snapshot(args.mesa,args.mesa_build,args.library_directory,args.drm_include,gpu) == compiler,
                    'original compiler/DRM/header/library changed '+stage)
            inputs.check_build(cpu,source,mesa=args.mesa,mesa_build=args.mesa_build,libraries=args.library_directory,
                bound=compiler,qualified=qualified,generated=generated)
            require(inputs.compiled_outputs(cpu) == products,'compiled binary/ESSL/MMD/input report changed '+stage)
        check('after build and before actual C execution')
        run_controller(args,gpu=gpu,source=source,cpu=cpu,temporary=temporary,image=image)
        check('after actual C execution')
    except CleanupIncomplete:
        preserve=True
        print('Preserved inputs for possibly active ordinary112 consumers: '+str(temporary),file=sys.stderr)
        raise
    finally:
        try:
            if temporary is not None and not preserve:shutil.rmtree(temporary)
        finally:signal.signal(signal.SIGTERM,previous)
    print('Original Mesa22.3.6 ABI1 two-owner ordinary112 actual execution: PASS; clock/FPS remain unqualified')


if __name__ == '__main__':main()
