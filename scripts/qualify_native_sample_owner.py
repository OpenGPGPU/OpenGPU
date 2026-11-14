#!/usr/bin/env python3
"""Original ESSL -> live protected sample owner on a prequalified full112 DUT.

One immutable-image CPU build supplies all programs and the actual Linux
producer. This entry never generates a model or supplies completion data.
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

from native_sample_compiler_inputs import snapshot, check_dependencies, sha256
from native_sample_owner_protocol import strict_json
from native_sample_owner_oracle import CASES, program
from owner_resource_contract import SAMPLE_PROFILE
from owner_process_lifetime import CleanupIncomplete, stop_group, remove_container

ROOT = Path(__file__).resolve().parents[1]
BUILD_SOURCE = '''import pathlib,subprocess,sys
sys.path.insert(0,str(pathlib.Path.cwd()/"scripts"))
out=pathlib.Path(sys.argv[1])
subprocess.run([sys.executable,"scripts/test_native_sample_essl.py","--sanitize",
    "--build",str(out/"compiler"),"--artifacts",str(out/"programs")],check=True)
from test_native_sample_submit_bridge import build_bridge
(out/"live").mkdir()
build_bridge(out/"live",sanitize=True,live=True)
'''


def require(value, message):
    if not value:
        raise ValueError(message)


def regular(path):
    require(path.is_file() and not path.is_symlink(), 'missing/aliased input: ' + str(path))
    return path


def checked_inputs(args):
    # This must precede Git archive, image inspection, Mesa scans and CPU builds.
    require(args.executor_qualification.is_file() and not args.executor_qualification.is_symlink(),
            'a real guarded native sample executor qualification is required')
    regular(args.executor)
    checker = args.executor_checker.resolve(strict=True)
    require(checker == ROOT/'scripts/prepare_owner_executor.py', 'original sample source checker required')
    subprocess.run([args.executor_checker_python, checker, '--stage', 'check', '--kind', 'native-sample',
        '--qualification', args.executor_qualification, '--executor', args.executor], check=True)
    record = strict_json(args.executor_qualification)
    expected = dict(schema='opengpu-owner-executor-v1', kind='native-sample', profile=SAMPLE_PROFILE,
        source_root=str(ROOT), ownerResourceVersion=2, ownerResourceStage='geometry-compute-sample',
        fullResourceQualification=False, executorBuildVerified=True, sourceQualificationRequired=True,
        gpuExecution=False, clockQualified=False, fpsQualified=False)
    require(all(type(record.get(k)) is type(v) and record[k] == v for k,v in expected.items()),
            'wrong native sample qualification profile/source/scope')
    for name in ('scripts/qualify_native_sample_owner.py','scripts/native_sample_compiler_inputs.py',
                 'scripts/native_sample_owner_controller.py'):
        require(record.get('source_sha256',{}).get(name) == sha256(regular(ROOT/name)),
                'qualification omits original sample provenance source: ' + name)
    return (sha256(args.executor_qualification), sha256(args.executor)), record['source_sha256']


def revision():
    require(not subprocess.check_output(['git','-C',ROOT,'status','--porcelain']).strip(),
            'original sample qualification requires a clean committed checkout')
    return subprocess.check_output(['git','-C',ROOT,'rev-parse','HEAD'],text=True).strip()


def check_archive(source, sources):
    for name, digest in sources.items():
        relative = Path(name)
        require(not relative.is_absolute() and '..' not in relative.parts, 'invalid qualified source path')
        require(sha256(regular(source/relative)) == digest == sha256(regular(ROOT/relative)),
                'fixed sample archive differs from its qualified original source: ' + name)


def docker_command(args, *, gpu, source, image, name, writable):
    require(re.fullmatch(r'sha256:[0-9a-f]{64}',image) is not None, 'immutable compiler image ID required')
    command = ['docker','run','--rm','--init','--name',name,'--memory','512m','--memory-swap','512m',
        '--cpus','1','--network=none','--read-only','-v',f'{gpu}:{gpu}:ro',
        '-v',f'{writable}:{writable}:rw','-w',str(source)]
    environment = dict(PYTHONDONTWRITEBYTECODE='1', CC='cc', CXX='c++',
        OPENGPU_MESA_DIR=str(args.mesa), OPENGPU_MESA_BUILD=str(args.mesa_build),
        OPENGPU_MESA_LINK_LIBS='-l:libz.so.1 '+str(args.library_directory/'libexpat.so.1.6.7'),
        LD_LIBRARY_PATH=str(args.library_directory), TMPDIR=str(writable),
        XDG_CACHE_HOME=str(writable)+'/cache', MESA_SHADER_CACHE_DISABLE='true',
        ASAN_OPTIONS='detect_leaks=0', OPENGPU_NATIVE_HOST_TIMEOUT=str(args.timeout))
    for key,value in environment.items():
        command += ['-e',key+'='+value]
    return command+[image]


def run_build(args, *, gpu, source, temporary, image):
    name = 'opengpu-sample-build-'+uuid.uuid4().hex
    cpu = temporary/'cpu';cpu.mkdir()
    command = docker_command(args,gpu=gpu,source=source,image=image,name=name,writable=cpu)
    process = None
    try:
        process = subprocess.Popen(command+['python3','-c',BUILD_SOURCE,str(cpu)], start_new_session=True)
        require(process.wait(timeout=args.timeout) == 0, 'original sample CPU build failed; no DUT case ran')
    finally:
        try:
            stop_group(process)
        finally:
            remove_container(name)
    return cpu


def compiled_outputs(cpu):
    paths = [cpu/'live/native-sample-live-producer']
    require(os.access(regular(paths[0]),os.X_OK), 'actual Linux sample producer is not executable')
    for case in CASES:
        for version in (6,7):
            path = regular(cpu/'programs'/f'{case}-abi{version}.ognsf')
            if version == 6:
                program(path.read_bytes(),case)
            paths.append(path)
    return {str(path):sha256(regular(path)) for path in paths}


def producer_command(args, *, gpu, source, cpu, image, name, case, samples):
    require(case in CASES and samples in (1,2,4), 'unknown original sample source/sample count')
    return docker_command(args,gpu=gpu,source=source,image=image,name=name,writable='{ipc}')+[
        str(cpu/'live/native-sample-live-producer'),str(cpu/'programs'/f'{case}-abi6.ognsf'),
        '{ipc}/profile.bin','{ipc}',str(samples),case]


def case_matrix(case, samples):
    require(case == 'all' or case in CASES, 'unknown original sample source')
    require(samples in ('all','1','2','4'), 'invalid sample selection')
    selected = CASES if case == 'all' else (case,)
    counts = (1,2,4) if samples == 'all' else (int(samples),)
    return tuple((name,count) for name in selected for count in counts)


def run_case(args, *, gpu, source, cpu, temporary, image, case, samples):
    name = 'opengpu-sample-'+uuid.uuid4().hex
    command = producer_command(args,gpu=gpu,source=source,cpu=cpu,image=image,name=name,case=case,samples=samples)
    argv_file = temporary/f'{case}-{samples}.argv.json';argv_file.write_text(json.dumps(command)+'\n')
    process = None
    try:
        # The guarded controller stays in its qualified checkout. Only compiler
        # and C producer inputs run from the byte-checked fixed Git archive.
        process = subprocess.Popen([sys.executable,ROOT/'scripts/native_sample_owner_controller.py',
            '--executor',args.executor,'--executor-qualification',args.executor_qualification,
            '--executor-checker',args.executor_checker,'--executor-checker-python',args.executor_checker_python,
            '--bridge-command',argv_file,'--bridge-container-name',name,
            '--program',cpu/'programs'/f'{case}-abi6.ognsf','--case',case,'--timeout',str(args.timeout)],
            start_new_session=True,env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1',TMPDIR=str(temporary)))
        require(process.wait() == 0, 'actual original ESSL sample case failed: '+case+'/'+str(samples))
    finally:
        try:
            stop_group(process)
        finally:
            remove_container(name)


def main():
    gpu = next(p for p in (ROOT,*ROOT.parents) if (p/'.git').is_dir())
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--executor',type=Path,required=True)
    parser.add_argument('--executor-qualification',type=Path,required=True)
    parser.add_argument('--executor-checker',type=Path,required=True)
    parser.add_argument('--executor-checker-python',type=Path,required=True)
    parser.add_argument('--mesa',type=Path,default=gpu/'depends/mesa')
    parser.add_argument('--mesa-build',type=Path,default=gpu/'depends/mesa-build')
    parser.add_argument('--library-directory',type=Path,default=gpu/'depends/aarch64-root/lib')
    parser.add_argument('--compiler-image',default='gpu-gtk-ci-check:latest')
    parser.add_argument('--case',choices=('all',*CASES),default='all')
    parser.add_argument('--samples',choices=('all','1','2','4'),default='all')
    parser.add_argument('--timeout',type=int,default=3600)
    args = parser.parse_args()
    require(0 < args.timeout <= 86400,'timeout must be 1..86400 seconds')
    for key in ('executor','executor_qualification','executor_checker'):
        setattr(args,key,getattr(args,key).absolute())
    identity,sources = checked_inputs(args)
    for key in ('mesa','mesa_build','library_directory'):
        value = getattr(args,key).resolve(strict=True)
        require(value.is_relative_to(gpu),'compiler inputs must be inside GPU workspace')
        setattr(args,key,value)
    args.executor_checker_python = args.executor_checker_python.resolve(strict=True)
    original = revision()
    compiler = snapshot(args.mesa,args.mesa_build,args.library_directory,gpu)
    image = subprocess.check_output(['docker','image','inspect',args.compiler_image,'--format','{{.Id}}'],text=True).strip()
    require(re.fullmatch(r'sha256:[0-9a-f]{64}',image) is not None,'local immutable compiler image required')
    def terminate(_signal,_frame):
        raise KeyboardInterrupt('original sample qualification terminated')
    previous = signal.signal(signal.SIGTERM,terminate)
    temporary = None;preserve = False
    try:
        temporary = Path(tempfile.mkdtemp(prefix='sample-original-',dir=gpu/'.artifacts'))
        source = temporary/'source';source.mkdir()
        archive = subprocess.Popen(['git','-C',ROOT,'archive',original],stdout=subprocess.PIPE,start_new_session=True)
        try:
            subprocess.run(['tar','-x','-C',source],stdin=archive.stdout,check=True)
            require(archive.wait() == 0,'fixed original source archive failed')
        finally:
            archive.stdout.close();stop_group(archive)
        check_archive(source,sources)
        cpu = run_build(args,gpu=gpu,source=source,temporary=temporary,image=image)
        require(snapshot(args.mesa,args.mesa_build,args.library_directory,gpu) == compiler,
                'actual compiler inputs changed during original ESSL build')
        check_dependencies(cpu,source,compiler,sources)
        outputs = compiled_outputs(cpu)
        for case,count in case_matrix(args.case,args.samples):
            for stage in ('before','after'):
                require(checked_inputs(args) == (identity,sources) and revision() == original,
                        'qualified source/model changed '+stage+' original sample execution')
                check_archive(source,sources)
                require(compiled_outputs(cpu) == outputs,'original compiled artifacts/producer changed')
                require(snapshot(args.mesa,args.mesa_build,args.library_directory,gpu) == compiler,
                        'actual compiler source/header/library identity changed')
                if stage == 'before':
                    run_case(args,gpu=gpu,source=source,cpu=cpu,temporary=temporary,
                             image=image,case=case,samples=count)
    except CleanupIncomplete:
        preserve=True
        print('Preserved inputs for possibly active original sample consumers: '+str(temporary),file=sys.stderr)
        raise
    finally:
        try:
            if temporary is not None and not preserve:
                shutil.rmtree(temporary)
        finally:
            signal.signal(signal.SIGTERM,previous)
    print('original Mesa22.3.6 ESSL -> actual full112 sample owner: PASS; clock/FPS remain unqualified')


if __name__ == '__main__':
    main()
