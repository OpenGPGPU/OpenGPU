#!/usr/bin/env python3
"""Validate independent joint BUILD with actual owner/MMU, never a GPU model."""
import argparse
from contextlib import contextmanager
import shutil
import os
import re
import hashlib
import errno
import signal
import time
from pathlib import Path
import shlex
import subprocess
import tempfile

from owner_process_lifetime import CleanupIncomplete, stop_group

ROOT = Path(__file__).resolve().parents[1]


@contextmanager
def workspace(artifacts):
    directory = Path(tempfile.mkdtemp(prefix='joint-', dir=artifacts))
    try:
        yield directory
    except CleanupIncomplete:
        print('Preserved joint inputs for a possibly active consumer:', directory)
        raise
    except BaseException:
        shutil.rmtree(directory)
        raise
    else:
        shutil.rmtree(directory)


def bounded(command, **kwargs):
    """Each group: 180s/512MiB. Leader exit never proves descendant exit."""
    directory = Path(kwargs.get('env', {}).get('TMPDIR', ROOT/'.artifacts'))
    fd, name = tempfile.mkstemp(prefix='group-', suffix='.log', dir=directory)
    path = Path(name)
    with os.fdopen(fd, 'w+') as output:
        p = None
        start = time.monotonic()
        peak = 0
        try:
            p = subprocess.Popen(command, start_new_session=True, stdout=output,
                                 stderr=subprocess.STDOUT, **kwargs)
            while p.poll() is None:
                lines = subprocess.check_output(['ps', '-axo', 'pgid=,rss='], text=True).splitlines()
                rss = sum(int(x.split()[1]) for x in lines if len(x.split()) == 2 and x.split()[0] == str(p.pid))
                peak = max(peak, rss)
                if time.monotonic()-start > 180 or rss > 512*1024:
                    raise RuntimeError('joint owned group exceeded 180s/512MiB')
                time.sleep(.1)
        finally:
            # This also runs after leader exit, or after an observation error.
            # CleanupIncomplete escapes with this log and the workspace intact.
            stop_group(p)
        output.seek(0)
        result = subprocess.CompletedProcess(command, p.returncode, output.read())
    path.unlink()
    print('joint group:', round(time.monotonic()-start, 2), 's; sampled peak KiB', peak)
    return result


def dependencies(path, source, target, bound, generated):
    """Validate actual -MMD input closure against pre-compile source identities."""
    text = path.read_text().replace('\\\n', ' ')
    if ':' not in text:
        raise RuntimeError('malformed joint MMD')
    left, right = text.split(':', 1)
    if shlex.split(left) != [str(target)]:
        raise RuntimeError('joint MMD names another output')
    observed = set()
    for name in shlex.split(right):
        item = Path(name)
        if not item.is_absolute():
            raise RuntimeError('relative joint compiler dependency')
        item = item.resolve(strict=True)
        if not item.is_file():
            raise RuntimeError('non-file joint compiler dependency')
        digest = hashlib.sha256(item.read_bytes()).hexdigest()
        expected = bound.get(str(item))
        if expected is None:
            if generated.get(str(item)) != digest:
                raise RuntimeError('unbound/changed generated joint dependency: '+str(item))
        else:
            stat = item.stat()
            if expected != (stat.st_size, stat.st_mtime_ns, stat.st_ino, digest):
                raise RuntimeError('changed bound joint dependency: '+str(item))
        observed.add(item)
    if source.resolve() not in observed:
        raise RuntimeError('joint MMD omits actual translation unit')
    return observed


def inputs():
    paths = [p for p in (ROOT/'driver').rglob('*') if p.is_file() and p.suffix in ('.c','.h','.inc')]
    paths += [ROOT/'scripts/test_protected_tessellation_joint.py',
              ROOT/'scripts/generate_protected_tessellation_joint_abi.py',
              ROOT/'scripts/generate_protected_tessellation_joint_geometry_abi.py',
              ROOT/'scripts/test_protected_tessellation_joint_abi.py',
              ROOT/'scripts/test_driver.py', ROOT/'scripts/test_scripts.sh',
              ROOT/'scripts/generate_tessellation_vertex_source_abi.py',
              ROOT/'scripts/owner_process_lifetime.py']
    paths += list((ROOT/'docs/abi').glob('*.json'))
    return {str(p): (p.stat().st_size, p.stat().st_mtime_ns, p.stat().st_ino,
                     hashlib.sha256(p.read_bytes()).hexdigest()) for p in paths}

def hw_bodies():
    source = (ROOT / 'driver/opengpu_hw.c').read_text()
    snapshot = source.index('int opengpu_hw_snapshot_submission_lock(')
    snapshot_end = source.index('\n}\n', snapshot) + 2
    submission = source.index('struct opengpu_hw_joint_submission {')
    submission_end = source.index('\nstatic int opengpu_hw_unified_submit_locked(', submission)
    profile = source.index('int opengpu_hw_protected_tessellation_joint_profile(')
    submit = source.index('int opengpu_hw_protected_tessellation_joint_async(', profile)
    submit_end = source.index('\n}\n', submit) + 2
    geometry_profile = source.index('int opengpu_hw_protected_tessellation_joint_geometry_profile(', submit_end)
    geometry_submit = source.index('int opengpu_hw_protected_tessellation_joint_geometry_async(', geometry_profile)
    geometry_end = source.index('\n}\n', geometry_submit) + 2
    return {
        'snapshot_submission_under_test.inc': source[snapshot:snapshot_end],
        'joint_hw_submission_under_test.inc': source[submission:submission_end],
        'joint_enqueue_under_test.inc': source[profile:submit_end],
        'joint_geometry_enqueue_under_test.inc': source[geometry_profile:geometry_end],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sanitize', action='store_true')
    parser.add_argument('--negative-controls', action='store_true')
    args = parser.parse_args()
    before = inputs()
    temporary_root = ROOT / '.artifacts'
    temporary_root.mkdir(exist_ok=True)
    with workspace(temporary_root) as out:
        temporary = str(out)
        for name, body in hw_bodies().items():
            (out / name).write_text(body)
        flags = ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror']
        if args.sanitize or os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
            flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        includes = [out, ROOT / 'driver/tests', ROOT / 'driver',
                    ROOT / 'driver/tests/private_snapshot_stubs',
                    ROOT / 'driver/tests/mmu_stubs', ROOT / 'driver/tests/compute_submit_stubs']
        environment = dict(os.environ, TMPDIR=temporary)
        environment.setdefault('ASAN_OPTIONS', 'quarantine_size_mb=16')
        compiler = shlex.split(os.environ.get('CC', 'cc'))
        base = compiler + flags + ['-I' + str(path) for path in includes]
        fixture = str(ROOT / 'driver/tests/opengpu_protected_tessellation_joint_test.c')
        generated = {str(path): hashlib.sha256(path.read_bytes()).hexdigest()
                     for path in out.glob('*_under_test.inc')}
        all_dependencies = set()
        compiled_units = 0

        def compile_unit(source, name, defines=()):
            nonlocal compiled_units
            obj, dep = out/(name+'.o'), out/(name+'.d')
            result = bounded([*base, *defines, '-MMD', '-MF', str(dep), '-MT', str(obj),
                              '-c', str(source), '-o', str(obj)], env=environment)
            if result.returncode:
                raise SystemExit('joint TU compile failed: '+name+'\n'+result.stdout)
            all_dependencies.update(dependencies(dep, source, obj, before, generated))
            compiled_units += 1
            return obj

        def link(name, objects):
            binary = out/name
            result = bounded([*compiler, *flags, *(str(obj) for obj in objects),
                              '-o', str(binary)], env=environment)
            if result.returncode:
                raise SystemExit('joint link failed: '+name+'\n'+result.stdout)
            return binary

        frame_object = compile_unit(ROOT/'driver/opengpu_native_frame.c', 'native-frame')
        fixture_object = compile_unit(Path(fixture), 'positive')
        link('test', [fixture_object, frame_object])
        result = bounded([str(out/'test')], env=environment)
        print(result.stdout, end='')
        if result.returncode:
            raise SystemExit('joint fixture failed: ' + str(result.returncode))
        geometry_fixture = ROOT/'driver/tests/opengpu_protected_tessellation_joint_geometry_test.c'
        geometry_object = compile_unit(Path(geometry_fixture), 'geometry-positive')
        geometry_binary = link('geometry-test', [geometry_object, frame_object])
        result = bounded([str(geometry_binary)], env=environment)
        print(result.stdout, end='')
        if result.returncode:
            raise SystemExit('joint geometry fixture failed: ' + str(result.returncode))
        if args.negative_controls:
            production = (ROOT / 'driver/opengpu_protected_tessellation_joint.c').read_text()
            mutations = {
                'unverified-availability': ('if(!opengpu_protected_tessellation_joint_profile_valid(&source->profile))return -EOPNOTSUPP;',
                    'if(!opengpu_protected_tessellation_joint_profile_valid(&source->profile) && false)return -EOPNOTSUPP;'),
                'skip-physical-alias': ('if(!ret)ret=jt_alias(&aliases);',
                    'if(!ret){int alias_ret=jt_alias(&aliases);ret=alias_ret==-EINVAL?0:alias_ret;}'),
                'truncate-limit33': ('return jt_get(raw,w)|((u64)jt_get(raw,w+1)<<32);',
                    'return jt_get(raw,w)|((u64)jt_get(raw,w+1)*0);'),
                'executable-job-metadata': ('opengpu_private_snapshot_append(owner,s->job,512,&job)',
                    'opengpu_private_snapshot_append_code(owner,s->job,512,&job)'),
                'primitive-info-without-profile': ('p->owned_tessellation_info && p->primitive_inputs &&',
                    'p->owned_tessellation_info &&'),
                'directory-other-root': ('roots.root_segments[n],0,false', 'roots.root_segments[(n+1)%s->range_count],0,false'),
                'live-catalog': ('s->draws=c->owned[4];s->draw_plans=c->owned[5];',
                    's->draws=inputs[4];s->draw_plans=inputs[5];'),
                'omit-frame-source-alias': ('if(writer_end>v->first)return -EINVAL;',
                    'if(false && writer_end>v->first)return -EINVAL;'),
                'state-payload-version': ('payload==1 && blend!=2 && blend!=3',
                    '(payload==0 || payload==1) && blend!=2 && blend!=3'),
                'state-late-test-version': ('jt_get(f,JF(TESTS_VERSION))!=1', 'false'),
                'state-active-color-type': ('if(bad_type)return false;', 'if(false && bad_type)return false;'),
                'state-indexed-blend': ('if(jt_get(f,JF(BLEND_BUFFERS0)+i))return false;',
                    'if(false && jt_get(f,JF(BLEND_BUFFERS0)+i))return false;'),
                'bypass-prepared-placement': ('if(!ret)ret=opengpu_private_snapshot_check_prepared_joint(owner,segment);',
                    'if(!ret){(void)segment;ret=0;}'),
                'state-frame-samples': ('if(jt_get(f,JF(FRAGMENT_SAMPLEMODE))!=jt_get(s->frame,JW(IMAGE_PIXELS_COLORS_SAMPLEMODE)))return false;',
                    'if(false && jt_get(f,JF(FRAGMENT_SAMPLEMODE))!=jt_get(s->frame,JW(IMAGE_PIXELS_COLORS_SAMPLEMODE)))return false;'),
                'state-attachment-kind': ('type!=opengpu_compute_texture_formats[format].kind',
                    '(type!=opengpu_compute_texture_formats[format].kind && type!=1)'),
                'gnt-frame-samples': ('if(jt_get(d,31)!=jt_get(s->frame,JW(IMAGE_PIXELS_COLORS_SAMPLEMODE)))return -EINVAL;',
                    'if(false && jt_get(d,31)!=jt_get(s->frame,JW(IMAGE_PIXELS_COLORS_SAMPLEMODE)))return -EINVAL;'),
            }
            expected = {
                'unverified-availability': f'joint case0 expected-{errno.EOPNOTSUPP} got0',
                'skip-physical-alias': 'joint case100 expected-22 got0',
                'truncate-limit33': 'joint endpoint source-relocation word=19',
                'executable-job-metadata': 'joint prepare root must be RO/NX',
                'primitive-info-without-profile': f'joint case3 expected-{errno.EOPNOTSUPP} got0',
                'directory-other-root': 'joint directory same-root sequence=0',
                'live-catalog': 'joint caller-capture boundary domain=2',
                'omit-frame-source-alias': 'joint case105 expected-22 got0',
                'state-payload-version': 'joint case200 expected-22 got0',
                'state-late-test-version': 'joint case201 expected-22 got0',
                'state-active-color-type': 'joint case214 expected-22 got0',
                'state-indexed-blend': 'joint case208 expected-22 got0',
                'bypass-prepared-placement': 'joint placement case301 expected-22 got0',
                'state-frame-samples': 'joint case223 expected-22 got0',
                'state-attachment-kind': 'joint case224 expected-22 got0',
                'gnt-frame-samples': 'joint case225 expected-22 got0',
            }
            for name, (old, new) in mutations.items():
                assert production.count(old) == 1, name
                mutant = out/(name+'.c')
                mutant.write_text(production.replace(old, new))
                binary = out/name
                generated[str(mutant)] = hashlib.sha256(mutant.read_bytes()).hexdigest()
                obj = compile_unit(Path(fixture), name, ['-DOPENGPU_JOINT_SOURCE="'+str(mutant)+'"'])
                link(name, [obj, frame_object])
                result = bounded([str(binary)], env=environment)
                if (not result.returncode or expected[name] not in result.stdout or
                        not re.search(r'assertion.*failed', result.stdout, re.I | re.S)):
                    raise SystemExit('negative control did not fail a semantic assertion: '+name+'\n'+result.stdout)
                print('joint semantic negative control rejected:', name, '; boundary:', expected[name])
            geometry_production = (ROOT/'driver/opengpu_protected_tessellation_joint_geometry.c').read_text()
            geometry_mutations = {
                'geometry-unverified-availability': (
                    'if(!opengpu_protected_tessellation_joint_geometry_profile_valid(&source->profile))return -EOPNOTSUPP;',
                    'if(!opengpu_protected_tessellation_joint_geometry_profile_valid(&source->profile) && false)return -EOPNOTSUPP;'),
                'geometry-skip-physical-alias': (
                    'if(!ret)ret=jg_alias(&aliases);',
                    'if(!ret){int alias_ret=jg_alias(&aliases);ret=alias_ret==-EINVAL?0:alias_ret;}'),
                'geometry-admit-abi10': (
                    '(abi!=5 && abi!=7) ||', '(abi!=5 && abi!=7 && abi!=10) ||'),
                'geometry-understate-abi5-prefix': (
                    'u64 prefix=((abi==5?12u:15u)+4u*varyings+4u*colors)*128u*4u;',
                    'u64 prefix=((abi==5?11u:15u)+4u*varyings+4u*colors)*128u*4u;'),
                'geometry-unbound-evaluation-export': (
                    '(v&127u)>=64 || (v&127u)>=exports', '(v&127u)>=64 || ((void)exports,false)'),
                'geometry-unbounded-command-id': (
                    'g->command_id>=32 ||', 'false ||'),
                'geometry-inconsistent-draw-source': (
                    'a->state_handle==b->state_handle && a->raster_discard==b->raster_discard &&',
                    'a->state_handle==b->state_handle &&'),
                'geometry-inconsistent-invocation-seeds': (
                    'a->input_sequence==b->input_sequence && a->first_output_sequence==b->first_output_sequence;',
                    'a->first_output_sequence==b->first_output_sequence;'),
                'geometry-omit-table-relocation': (
                    'if(!ret && s->range_count)ret=jg_reloc(owner,job,0,37,geometry,0,false);',
                    'if(false && s->range_count)ret=jg_reloc(owner,job,0,37,geometry,0,false);'),
            }
            geometry_expected = {
                'geometry-unverified-availability': f'geometry negative owner-version expected-{errno.EOPNOTSUPP} got0',
                'geometry-skip-physical-alias': 'geometry negative GS-source-frame-writer-alias expected-22 got0',
                'geometry-admit-abi10': 'Assertion failed: (graph_copies==copies)',
                'geometry-understate-abi5-prefix': 'Assertion failed: (graph_copies==copies)',
                'geometry-unbound-evaluation-export': 'geometry negative export-bound expected-22 got0',
                'geometry-unbounded-command-id': 'geometry negative command-id expected-22 got0',
                'geometry-inconsistent-draw-source': 'Assertion failed',
                'geometry-inconsistent-invocation-seeds': 'Assertion failed',
                'geometry-omit-table-relocation': 'Assertion failed',
            }
            for name, (old, new) in geometry_mutations.items():
                assert geometry_production.count(old) == 1, name
                mutant = out/(name+'.c')
                mutant.write_text(geometry_production.replace(old, new))
                generated[str(mutant)] = hashlib.sha256(mutant.read_bytes()).hexdigest()
                obj = compile_unit(Path(geometry_fixture), name,
                                   ['-DOPENGPU_JOINT_GEOMETRY_SOURCE="'+str(mutant)+'"'])
                binary = link(name, [obj, frame_object])
                result = bounded([str(binary)], env=environment)
                if (not result.returncode or geometry_expected[name] not in result.stdout or
                        not re.search(r'assertion.*failed', result.stdout, re.I | re.S)):
                    raise SystemExit('geometry negative control did not fail a semantic assertion: '+name+'\n'+result.stdout)
                print('joint geometry semantic negative control rejected:', name, '; boundary:', geometry_expected[name])
        print('joint actual MMD closure PASS:', compiled_units, 'TUs;', len(all_dependencies), 'dependencies')
    if inputs() != before:
        raise SystemExit('joint consumed source inputs changed during validation')
    print('joint consumed source guard PASS:', len(before), 'inputs; no hardware execution')


if __name__ == '__main__':
    def interrupted(signum, frame):
        raise SystemExit(128+signum)
    signal.signal(signal.SIGTERM, interrupted)
    main()
