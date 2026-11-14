#!/usr/bin/env python3
"""OPT IN actual Mesa/Linux LIVE C -> original controller negative CPU test.

The test-only peer executes no ISA. The original controller must reject its
unchanged RAM and its synthetic-image/unwritten-GNY2 RAM before responding to
record0. No executor qualification, GPU result, successful cleanup ACK or FPS
claim is produced. Requires an immutable AArch64 Mesa tool image and SDK.
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

import native_sample_graphics_controller as controller
import native_sample_graphics_inputs as inputs
import prepare_owner_executor as prepared
import qualify_native_sample_graphics_owner as qualifier
from native_sample_graphics_protocol import profile_bytes
from native_sample_owner_controller import atomic, wait_file
from native_sample_owner_protocol import SCHEMA, AUXILIARY
from owner_process_lifetime import CleanupIncomplete, stop_group, remove_container
from owner_resource_contract import SAMPLE_PROFILE, SAMPLE_STAGE, SAMPLE_WORDS

ROOT = Path(__file__).resolve().parents[1]
OWN = ('scripts/test_native_sample_graphics_live_failure.py',
       'scripts/native_sample_graphics_failure_peer.py')
FORBIDDEN = ('record-0.response.bin','record-1.capture','record-1.admit',
             'cleanup.authorize.bin','cleanup.json','response.bin')


def require(ok, text):
    if not ok: raise ValueError('opt-in CPU LIVE rejection: '+text)


def synthetic_profile():
    """Explicit synthetic observations; never an actual device probe."""
    caps = sum(1 << bit for bit in (6,22,23,25,29)) | 128 << 8
    return dict(schema=SCHEMA,ownerProfile=SAMPLE_PROFILE,ownerResourceStage=SAMPLE_STAGE,
        ownerResourceVersion=2,fullResourceQualification=False,resetCount=1,
        sourceQualificationRequired=True,gpuExecution=False,clockQualified=False,fpsQualified=False,
        words=[dict(w) for w in SAMPLE_WORDS],registers=[dict(address=a,value=v) for a,v in
            zip(AUXILIARY,(0x47550002,caps,0x160,1048576,0x02020810))])


def clean_phase(directory):
    require(not any((directory/name).exists() or (directory/name).is_symlink() for name in FORBIDDEN),
            'bad RAM authorized a later original C/controller phase')


def exercise(args, *, gpu, source, cpu, temporary, image, case):
    directory = temporary/('ipc-'+case);directory.mkdir()
    profile = synthetic_profile()
    atomic(directory/'profile.json',(json.dumps(profile)+'\n').encode())
    atomic(directory/'profile.bin',profile_bytes(profile))
    name = 'opengpu-test-only-live-reject-'+uuid.uuid4().hex
    command = [a.replace('{ipc}',str(directory)) for a in qualifier.producer_command(
        args,gpu=gpu,source=source,cpu=cpu,image=image,name=name)]
    bridge = running = None
    try:
        with (directory/'bridge.log').open('w') as log, (directory/'peer.log').open('w') as peer_log:
            bridge = subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
            wait_file(directory/'ready.json',bridge,None,args.timeout)
            require((directory/'native-real-vs.words').stat().st_size == 308*4 and
                    (directory/'native-real-fs.words').stat().st_size == 301*4,
                    'actual original Mesa compiler programs have changed')
            def start(initial, results):
                nonlocal running
                running = subprocess.Popen([sys.executable,ROOT/OWN[1],
                    '--test-only-synthetic-no-isa','--initial',initial,'--results',results,
                    '--ipc',directory,'--case',case,'--timeout',str(args.timeout)],
                    stdout=peer_log,stderr=subprocess.STDOUT,start_new_session=True,
                    env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1',TMPDIR=str(directory)))
                return running
            def never_qualified():
                raise AssertionError('deliberately rejected CPU RAM reached successful qualification')
            try:
                # Deliberately no monkeypatch: real preflight, source/recipe,
                # capture, wire codecs and verify_result must all execute.
                controller.exchange(directory,profile,bridge,start,never_qualified,args.timeout)
            except ValueError as error:
                expected = 'ABI1 RGBA32UI image/prefix/suffix guard differs' if case == 'unchanged' else 'ordinary112 GNY2:'
                require(expected in str(error),'did not reach the intended independent result rejection: '+str(error))
            else:
                raise AssertionError('original controller accepted deliberately unexecuted CPU RAM')
            witness = inputs.strict_json(directory/'synthetic-peer.json')
            require(witness == dict(schema='opengpu-test-only-synthetic-peer-v1',isaExecuted=False,
                expectControllerRejection=True,case=case,actualCRetirementRead=True),
                'test peer did not observe the real C held-to-retire transition')
            clean_phase(directory)
            atomic(directory/'error',b'EXPECTED CPU negative result; no GPU execution or release authorization\n')
            require(bridge.wait(timeout=args.timeout) == 1,'real C did not fail its canceled LIVE wait')
            require(running.wait(timeout=args.timeout) == 0,'synthetic test peer did not terminate')
        clean_phase(directory)
        lines = (directory/'bridge.log').read_text().splitlines()
        require(controller.PRODUCER_SUCCESS not in lines and not any(
            'ACTUAL_SAMPLE_GRAPHICS_CPU_CAPTURE_PASS' in line for line in lines),
            'real LIVE C unexpectedly reported a success/CPU-peer marker')
        require('ordinary112 LIVE owner exchange failed; no cleanup or success inferred' in lines,
                'real C did not report the expected LIVE rejection')
    finally:
        try: stop_group(running)
        finally:
            try: stop_group(bridge)
            finally: remove_container(name)


def main():
    gpu = next(p for p in (ROOT,*ROOT.parents) if (p/'.git').is_dir())
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-original-mesa-live-cpu-failure',action='store_true',required=True)
    parser.add_argument('--compiler-image',required=True,help='immutable sha256 image ID, never a mutable tag')
    for name,default in (('mesa','depends/mesa'),('mesa-build','depends/mesa-build'),
                         ('drm-include','depends/aarch64-root/include'),('library-directory','depends/aarch64-root/lib')):
        parser.add_argument('--'+name,type=Path,default=gpu/default)
    parser.add_argument('--timeout',type=int,default=900)
    args = parser.parse_args()
    require(0 < args.timeout <= 3600,'bounded timeout must be 1..3600 seconds')
    require(re.fullmatch('sha256:[0-9a-f]{64}',args.compiler_image) is not None,'immutable compiler image ID required')
    require(not any(name in os.environ for name in ('OPENGPU_NATIVE_HOST_DIRECTORY','OPENGPU_NATIVE_HOST_PROFILE',
        'OPENGPU_SAMPLE_GRAPHICS_HOST_DIRECTORY','OPENGPU_SAMPLE_GRAPHICS_HOST_PROFILE')),
        'test must start without an inherited producer session')
    original = qualifier.revision()
    for key in ('mesa','mesa_build','drm_include','library_directory'):
        path = getattr(args,key).resolve(strict=True)
        require(path.is_dir() and path.is_relative_to(gpu),'compiler SDK escaped GPU workspace')
        setattr(args,key,path)
    image = subprocess.check_output(['docker','image','inspect',args.compiler_image,'--format','{{.Id}}'],text=True).strip()
    require(image == args.compiler_image,'compiler image identity differs')
    compiler = inputs.snapshot(args.mesa,args.mesa_build,args.library_directory,args.drm_include,gpu)
    production = set((*prepared.checker_files('native-sample-graphics'),
        *prepared.driver_files('native-sample-graphics',SAMPLE_PROFILE),*prepared.DEFAULT_DEPENDENCIES))
    # A CPU source map is NOT an executor qualification or a fabricated probe.
    sources = {name:inputs.sha256(inputs.regular(ROOT/name)) for name in sorted(production | set(OWN))}
    temporary = None;preserve = False
    def terminate(_signal,_frame): raise KeyboardInterrupt('opt-in CPU LIVE rejection interrupted')
    previous = signal.signal(signal.SIGTERM,terminate)
    try:
        temporary = Path(tempfile.mkdtemp(prefix='test-only-live-reject-',dir=gpu/'.artifacts'))
        source = temporary/'source';source.mkdir()
        archive = subprocess.Popen(['git','-C',ROOT,'archive',original],stdout=subprocess.PIPE,start_new_session=True)
        try:
            subprocess.run(['tar','-x','-C',source],stdin=archive.stdout,timeout=args.timeout,check=True)
            require(archive.wait(timeout=args.timeout) == 0,'fixed committed archive failed')
        finally:
            archive.stdout.close();stop_group(archive)
        qualifier.check_archive(source,sources)
        cpu,generated = qualifier.run_build(args,gpu=gpu,source=source,temporary=temporary,
            image=image,compiler=compiler,qualified=sources)
        products = inputs.compiled_outputs(cpu)
        def checked():
            require(qualifier.revision() == original,'source revision changed during CPU negative test')
            qualifier.check_archive(source,sources)
            require(inputs.snapshot(args.mesa,args.mesa_build,args.library_directory,args.drm_include,gpu) == compiler,
                    'Mesa/DRM/library input identity changed')
            dependencies = inputs.check_build(cpu,source,mesa=args.mesa,mesa_build=args.mesa_build,
                libraries=args.library_directory,bound=compiler,qualified=sources,generated=generated)
            require(all(str(Path(p).relative_to(source)) in production for p in dependencies
                        if Path(p).is_relative_to(source)), 'actual GPU MMD escaped production qualification closure')
            require(inputs.compiled_outputs(cpu) == products,'compiled/generated input changed during real C execution')
        checked()
        for case in ('unchanged','unwritten-gny2'):
            exercise(args,gpu=gpu,source=source,cpu=cpu,temporary=temporary,image=image,case=case)
            checked()
            print('EXPECTED_CPU_REJECTION case='+case+' actualMesaLiveC=true originalController=true isaExecuted=false')
    except CleanupIncomplete:
        preserve = True
        print('Preserved possibly active CPU test inputs: '+str(temporary),file=sys.stderr)
        raise
    finally:
        try:
            if temporary is not None and not preserve: shutil.rmtree(temporary)
        finally: signal.signal(signal.SIGTERM,previous)
    print('CPU wire/failure integration PASS; no DUT, GPU image/GNY2, cleanup qualification or FPS result')


if __name__ == '__main__': main()
