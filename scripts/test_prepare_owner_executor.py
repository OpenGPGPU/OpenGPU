#!/usr/bin/env python3
"""CPU-only rejection of stale/mixed executor identities; no GPU model runs."""
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import prepare_owner_executor as q


class ExecutorTests(unittest.TestCase):
    def setUp(self):
        parent = q.ROOT / '.artifacts'
        parent.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix='executor-guard-', dir=parent)
        self.addCleanup(self.temporary.cleanup)
        self.out = Path(self.temporary.name)
        dut = self.out / 'flashsim-dut'
        dut.mkdir()
        (dut / 'dut.h').write_text('#pragma once\nstruct CpuFixture { static constexpr unsigned assertion_count=1; };\n')
        (dut / 'dut_0.cpp').write_text('#include "dut.h"\nint cpu_fixture(){return 1;}\n')
        (dut / 'hw.mlir').write_text('// CPU fixture; not a GPU model.\n')
        (self.out / 'preparation.json').write_text('{"cpu_fixture":true}')
        (self.out / 'owner-manifest.json').write_text(json.dumps(dict(profile=q.owner.PROFILE)))
        self.preparation = prepared = dict(rtl_sha256={'CpuFixture.sv':'cpu-fixture'},
            manifest_sha256=q.common.sha(self.out / 'owner-manifest.json'))
        self.prepared = patch.object(q.owner, 'prepared', return_value=prepared).start()
        self.addCleanup(patch.stopall)
        self.generator = {'source': {'cpu.py': 'fixture'}, 'python': 'cpu-test', 'circt_sha256': 'cpu-tool'}
        patch.object(q.flash, 'generator_inputs', return_value=self.generator).start()
        self.flashsim = self.out / 'cpu-tool'
        self.flashsim.mkdir()
        generated = q.flash.generated_sources(dut, 1)
        q.common.write_json(dut / 'generation.json', dict(
            context=dict(generator=self.generator, rtl_sha256=prepared['rtl_sha256']),
            generated=generated, mlir_sha256=q.common.sha(dut / 'hw.mlir')))
        self.compiler = q.compiler_identity('/usr/bin/c++')
        self.options = dict(shard_opt='-O2', commit_opt='-O0', conservative_alias=False,
                            strict_flex_arrays=False, precompiled_header=False)

    def fixture(self, kind='compute', profile=None):
        profile = profile or q.owner.PROFILE
        (self.out / 'owner-manifest.json').write_text(json.dumps(dict(profile=profile)))
        self.preparation['manifest_sha256'] = q.common.sha(self.out / 'owner-manifest.json')
        files = q.driver_files(kind, profile)
        target = self.out / 'executors' / kind
        target.mkdir(parents=True)
        # An actual tiny CPU binary makes executable/hash checks independent
        # of fixture strings. Mocked owner/tool preparation is never GPU proof.
        source = self.out / 'cpu.cpp'
        source.write_text('int main(){return 0;}\n')
        subprocess.run(['/usr/bin/c++', str(source), '-o', str(target / 'executor')],
                       env=dict(os.environ, TMPDIR=str(self.out)), check=True)
        model = q.model_identity(self.out, self.flashsim, 1)
        built = dict(dut_header_sha256=model['generated']['files']['dut.h'],
            dut_sources={n:h for n,h in model['generated']['files'].items() if n!='dut.h'},
            driver_source=str((q.ROOT/files[0]).resolve()),
            driver_inputs={str((q.ROOT/n).resolve()):h for n,h in
                q.hashes([files[0], *q.DEFAULT_DEPENDENCIES, *files[1:]]).items()},
            compiler=self.compiler['path'], compiler_version=self.compiler['version'],
            environment={k:os.environ.get(k,'') for k in q.builder.ENVIRONMENT},
            executable_sha256=q.common.sha(target/'executor'), **self.options)
        flags=['c++','-std=gnu++17','-fPIC','-fPIE','-w']
        if 'clang' in self.compiler['version'].lower():flags+=['-Wno-parentheses-equality','-fbracket-depth=4096']
        flags+=['-I',model['dut_directory']]
        built.update(base_flags=flags,driver_flags=flags[:-2]+['-I',str(q.ROOT/'scripts/flashsim_native'),
            '-I',str(q.ROOT/'scripts'),*flags[-2:],'-O1'],
            objects={Path(n).stem+'.o':'0'*64 for n in built['dut_sources']})
        q.common.write_json(target/'builder.json',built)
        record=dict(schema='opengpu-owner-executor-v1',version=1,kind=kind,**q.profile_contract(profile, kind),
            executorBuildVerified=True,sourceQualificationRequired=True,gpuExecution=False,
            clockQualified=False,fpsQualified=False,source_root=str(q.ROOT),owner_output=str(self.out),
            flashsim=str(self.flashsim),shards=1,source_sha256=q.hashes(
                [*files,*q.DEFAULT_DEPENDENCIES,*q.checker_files(kind)]),
            model=model,compiler=self.compiler,options=self.options,
            builder_sha256=q.common.sha(target/'builder.json'),executable_sha256=q.common.sha(target/'executor'))
        path=target/'qualification.json';q.common.write_json(path,record)
        q.verify_qualification(path,executor=target/'executor',kind=kind)
        return path,record

    def test_stage_qualification_requires_its_actual_factory_and_distinct_driver(self):
        path,record=self.fixture(profile=q.COMPUTE_STAGE)
        files=q.driver_files('compute',q.COMPUTE_STAGE)
        self.assertEqual(files[0],'scripts/compute_stage_owner_driver.cpp')
        self.assertIn('scripts/compute_owner_driver.cpp',files)
        q.builder.validate_driver_includes([q.ROOT/name for name in
            [files[0],*q.DEFAULT_DEPENDENCIES,*files[1:]]],self.out/'flashsim-dut/dut.h',self.out/'flashsim-dut')
        for key,value in (('profile',q.owner.PROFILE),('ownerResourceVersion',True),
                          ('ownerResourceStage','final'),('fullResourceQualification',True)):
            bad=copy.deepcopy(record);bad[key]=value;q.common.write_json(path,bad)
            with self.subTest(key=key),self.assertRaises(RuntimeError):q.verify_qualification(path)
        q.common.write_json(path,record)
        # Neither a relabeled record nor a changed prepared factory can bind
        # an old driver/model to the new source contract.
        (self.out/'owner-manifest.json').write_text(json.dumps(dict(profile=q.owner.PROFILE)))
        with self.assertRaises(RuntimeError):q.verify_qualification(path)
        (self.out/'owner-manifest.json').write_text(json.dumps(dict(profile=q.COMPUTE_STAGE)))
        original=json.loads((path.parent/'builder.json').read_text())
        # The same complete input set is insufficient: the wrapper must be
        # the actual translation unit, rather than a declared unused include.
        swapped=copy.deepcopy(original)
        swapped['driver_source']=str((q.ROOT/'scripts/compute_owner_driver.cpp').resolve())
        q.common.write_json(path.parent/'builder.json',swapped)
        record['builder_sha256']=q.common.sha(path.parent/'builder.json');q.common.write_json(path,record)
        with self.assertRaises(RuntimeError):q.verify_qualification(path)
        mixed=copy.deepcopy(original)
        mixed['driver_inputs']={str((q.ROOT/n).resolve()):h for n,h in q.hashes(
            [q.driver_files('compute')[0],*q.DEFAULT_DEPENDENCIES,*q.driver_files('compute')[1:]]).items()}
        q.common.write_json(path.parent/'builder.json',mixed)
        record['builder_sha256']=q.common.sha(path.parent/'builder.json');q.common.write_json(path,record)
        with self.assertRaises(RuntimeError):q.verify_qualification(path)
        with self.assertRaises(RuntimeError):q.driver_files('native',q.COMPUTE_STAGE)

    def test_blit112_requires_its_actual_translation_unit_factory_and_include_closure(self):
        path,record=self.fixture(kind=q.BLIT_KIND,profile=q.SAMPLE_PROFILE)
        files=q.driver_files(q.BLIT_KIND,q.SAMPLE_PROFILE)
        self.assertEqual(files[0],'scripts/native_framebuffer_blit_sample_driver.cpp')
        self.assertIn('scripts/native_framebuffer_blit_driver.cpp',files)
        q.builder.validate_driver_includes([q.ROOT/name for name in
            [files[0],*q.DEFAULT_DEPENDENCIES,*files[1:]]],self.out/'flashsim-dut/dut.h',self.out/'flashsim-dut')
        for profile in (q.owner.PROFILE,q.COMPUTE_STAGE,q.FULL_PROFILE):
            with self.subTest(profile=profile),self.assertRaises(RuntimeError):q.profile_contract(profile,q.BLIT_KIND)
        for kind in ('native','compute','native-sample','native-sample-graphics'):
            with self.subTest(kind=kind),self.assertRaises(RuntimeError):
                q.verify_qualification(path,executor=path.parent/'executor',kind=kind)
        built=json.loads((path.parent/'builder.json').read_text())
        for driver in ('scripts/native_framebuffer_blit_driver.cpp','scripts/native_sample_owner_driver.cpp',
                       'scripts/native_sample_graphics_driver.cpp'):
            changed=copy.deepcopy(built);changed['driver_source']=str((q.ROOT/driver).resolve())
            q.common.write_json(path.parent/'builder.json',changed)
            bad=copy.deepcopy(record);bad['builder_sha256']=q.common.sha(path.parent/'builder.json')
            q.common.write_json(path,bad)
            with self.subTest(driver=driver),self.assertRaises(RuntimeError):q.verify_qualification(path,kind=q.BLIT_KIND)
        for missing in ('scripts/native_framebuffer_blit_driver.cpp','scripts/native_framebuffer_blit_lease.hpp',
                        'scripts/native_sample_owner_capture.hpp','driver/opengpu_storage_command_abi.h'):
            changed=copy.deepcopy(built);del changed['driver_inputs'][str((q.ROOT/missing).resolve())]
            q.common.write_json(path.parent/'builder.json',changed)
            bad=copy.deepcopy(record);bad['builder_sha256']=q.common.sha(path.parent/'builder.json')
            q.common.write_json(path,bad)
            with self.subTest(missing=missing),self.assertRaises(RuntimeError):q.verify_qualification(path,kind=q.BLIT_KIND)
        for missing in q.BLIT_CHECKERS:
            bad=copy.deepcopy(record);del bad['source_sha256'][missing];q.common.write_json(path,bad)
            with self.subTest(checker=missing),self.assertRaises(RuntimeError):q.verify_qualification(path,kind=q.BLIT_KIND)

    def test_blit112_build_and_check_reject_unregistered_macro_and_external_shadow_includes(self):
        path,record=self.sample_fixture(q.BLIT_KIND)
        runtime=q.ROOT/'scripts/native_framebuffer_blit_driver.cpp';original=runtime.read_bytes()
        external=self.out/'external/native_owner_runtime.hpp';external.parent.mkdir()
        external.write_text('// external shadow of a declared runtime\n')
        directives=('#include "unregistered-blit.hpp"', '#include UNKNOWN_BLIT_HEADER',
                    '#include "'+str(external)+'"')
        (runtime.parent/'unregistered-blit.hpp').write_text('// omitted actual dependency\n')
        for directive in directives:
            runtime.write_bytes(original+('\n'+directive+'\n').encode())
            # Rehash the declared files: source hashes alone must not authorize
            # an undeclared dependency or a different actual resolution.
            forged=copy.deepcopy(record);forged['source_sha256']=q.hashes([
                *q.driver_files(q.BLIT_KIND,q.SAMPLE_PROFILE),*q.DEFAULT_DEPENDENCIES,*q.checker_files(q.BLIT_KIND)])
            q.common.write_json(path,forged)
            with self.subTest(directive=directive),self.assertRaisesRegex(ValueError,'driver include'):
                q.verify_qualification(path,kind=q.BLIT_KIND)
            with patch.object(q.subprocess,'Popen') as start:
                with self.assertRaisesRegex(ValueError,'driver include'):
                    q.build_executor(self.out,self.flashsim,q.BLIT_KIND,shards=1)
                start.assert_not_called()
            self.assertFalse(path.exists())
        runtime.write_bytes(original)
        self.assertEqual(q.build_lock_paths(self.out,q.BLIT_KIND),(self.out/'.lock',path.parent/'.lock'))

    def test_full_factory_qualifications_bind_distinct_wrappers_and_reject_cross_models(self):
        for name, (stage, words) in q.FULL_COMPUTE_CONTRACTS.items():
            with self.subTest(profile=name):
                # Remove only this CPU fixture's completed previous executor.
                target=self.out/'executors/compute'
                if target.exists():
                    import shutil
                    shutil.rmtree(target)
                path, record=self.fixture(profile=name)
                files=q.driver_files('compute',name)
                expected='geometry_compute_sample' if name==q.SAMPLE_PROFILE else 'geometry_compute'
                self.assertEqual(files[0],f'scripts/{expected}_owner_driver.cpp')
                q.builder.validate_driver_includes([q.ROOT/n for n in
                    [files[0],*q.DEFAULT_DEPENDENCIES,*files[1:]]],self.out/'flashsim-dut/dut.h',self.out/'flashsim-dut')
                for key, value in [('profile',q.COMPUTE_STAGE),('profile',q.owner.PROFILE),
                        ('ownerResourceStage','compute'),('ownerResourceVersion',True),
                        ('fullResourceQualification',True),('source_sha256',{})]:
                    wrong=copy.deepcopy(record);wrong[key]=value;q.common.write_json(path,wrong)
                    with self.assertRaises(RuntimeError):q.verify_qualification(path)
                q.common.write_json(path,record)
                original=json.loads((path.parent/'builder.json').read_text())
                for driver in ('compute_owner','compute_stage_owner','geometry_compute_owner','geometry_compute_sample_owner'):
                    if 'scripts/'+driver+'.cpp'==files[0]:continue
                    wrong=copy.deepcopy(original)
                    wrong['driver_source']=str((q.ROOT/'scripts'/str(driver+'.cpp')).resolve())
                    q.common.write_json(path.parent/'builder.json',wrong)
                    record['builder_sha256']=q.common.sha(path.parent/'builder.json');q.common.write_json(path,record)
                    with self.assertRaises(RuntimeError):q.verify_qualification(path)
                with self.assertRaises(RuntimeError):q.driver_files('native',name)

    def test_frozen_qualification_cannot_acquire_stage_labels(self):
        path,record=self.fixture()
        for fields in (q.profile_contract(q.COMPUTE_STAGE,'compute'),
                       dict(ownerResourceVersion=2),dict(ownerResourceStage='compute'),
                       dict(fullResourceQualification=False)):
            bad=copy.deepcopy(record);bad.update(fields);q.common.write_json(path,bad)
            with self.assertRaises(RuntimeError):q.verify_qualification(path)

    def test_distinct_kind_and_exact_dependencies_with_reusable_shared_cache(self):
        for kind in ('native', 'compute'):
            path,record=self.fixture(kind)
            with self.assertRaises(RuntimeError):q.verify_qualification(path,kind='native' if kind=='compute' else 'compute')
            cache=self.out/'flashsim-build';cache.mkdir(exist_ok=True)
            (cache/'native-flashsim-build.json').write_text('another driver now uses the shared cache')
            q.verify_qualification(path,kind=kind)
            names=q.driver_files(kind)
            files=[q.ROOT/n for n in [names[0],*q.DEFAULT_DEPENDENCIES,*names[1:]]]
            q.builder.validate_driver_includes(files,self.out/'flashsim-dut/dut.h',self.out/'flashsim-dut')
            built=json.loads((path.parent/'builder.json').read_text())
            del built['driver_inputs'][str((q.ROOT/'scripts/native_owner_runtime.hpp').resolve())]
            q.common.write_json(path.parent/'builder.json',built)
            record['builder_sha256']=q.common.sha(path.parent/'builder.json');q.common.write_json(path,record)
            with self.assertRaises(RuntimeError):q.verify_qualification(path,kind=kind)

    def test_rejects_old_profiles_missing_scope_partial_options_and_source_identity(self):
        path,record=self.fixture()
        for key,value in (('profile','two-cu-vga60-v2'),('kind','native'),('version',True),('shards',True),
                          ('gpuExecution',True),('clockQualified',True),('executorBuildVerified',1),
                          ('options',{}),('source_sha256',{}),('source_root','/another/check-out')):
            bad=copy.deepcopy(record);bad[key]=value;q.common.write_json(path,bad)
            with self.subTest(key=key),self.assertRaises(RuntimeError):q.verify_qualification(path)
        q.common.write_json(path,record)
        with patch.dict(os.environ,{'CPATH':str(self.out)}):
            with self.assertRaises(RuntimeError):q.verify_qualification(path)
        q.verify_qualification(path)

    def test_rejects_wrong_binary_moved_snapshot_aliases_and_compiler(self):
        path,record=self.fixture();exe=path.parent/'executor'
        other=self.out/'other';other.write_bytes(exe.read_bytes());other.chmod(0o755)
        with self.assertRaises(RuntimeError):q.verify_qualification(path,executor=other)
        link=self.out/'alias';link.symlink_to(exe)
        with self.assertRaises(RuntimeError):q.verify_qualification(path,executor=link)
        executable=exe.read_bytes();exe.write_bytes(executable+b'changed')
        with self.assertRaises(RuntimeError):q.verify_qualification(path)
        exe.write_bytes(executable)
        original=(path.parent/'builder.json').read_bytes();(path.parent/'builder.json').write_bytes(original+b' ')
        with self.assertRaises(RuntimeError):q.verify_qualification(path)
        (path.parent/'builder.json').write_bytes(original)
        copied=self.out/'builder-copy.json';copied.write_bytes(original)
        (path.parent/'builder.json').unlink();(path.parent/'builder.json').symlink_to(copied)
        with self.assertRaises(RuntimeError):q.verify_qualification(path)
        (path.parent/'builder.json').unlink();(path.parent/'builder.json').write_bytes(original)
        moved=self.out/'qualification.json';moved.write_bytes(path.read_bytes())
        with self.assertRaises(RuntimeError):q.verify_qualification(moved)
        bad=copy.deepcopy(record);bad['compiler']['sha256']='changed';q.common.write_json(path,bad)
        with self.assertRaises(RuntimeError):q.verify_qualification(path)

    def test_rejects_changed_mlir_assertions_shards_generation_and_toolchain(self):
        path,record=self.fixture();dut=self.out/'flashsim-dut'
        for name,changed in (('hw.mlir','changed'),('dut.h','struct CpuFixture {static constexpr unsigned assertion_count=0;};\n'),
                             ('dut_0.cpp','changed shard')):
            item=dut/name;original=item.read_bytes();item.write_text(changed)
            with self.subTest(name=name),self.assertRaises(RuntimeError):q.verify_qualification(path)
            item.write_bytes(original)
        self.generator['circt_sha256']='changed'
        with self.assertRaises(RuntimeError):q.verify_qualification(path)
        self.generator['circt_sha256']='cpu-tool'
        (dut/'generation.json').unlink()
        with self.assertRaises(FileNotFoundError):q.verify_qualification(path)

    def test_failed_fresh_build_invalidates_previous_success_before_model_validation(self):
        path,_=self.fixture()
        with patch.object(q,'model_identity',side_effect=RuntimeError('incomplete or stale model')):
            with self.assertRaisesRegex(RuntimeError,'incomplete or stale model'):
                q.build_executor(self.out,self.flashsim,'compute',shards=1)
        self.assertFalse(path.exists())

    def test_rejects_changed_compile_flags_or_omitted_linked_shards(self):
        path,record=self.fixture();original=json.loads((path.parent/'builder.json').read_text())
        for key,value in (('base_flags',original['base_flags']+['-DCPU_FAKE']),
                          ('driver_flags',original['driver_flags'][:-1]),('objects',{})):
            bad=copy.deepcopy(original);bad[key]=value;q.common.write_json(path.parent/'builder.json',bad)
            record['builder_sha256']=q.common.sha(path.parent/'builder.json');q.common.write_json(path,record)
            with self.subTest(key=key),self.assertRaises(RuntimeError):q.verify_qualification(path)

    def sample_fixture(self, kind='native-sample'):
        """Private copied source tree: mutate real dependencies without editing
        *any* active original ESSL, producer, driver or shared header."""
        names = set((*q.driver_files(kind, q.SAMPLE_PROFILE),
                     *q.DEFAULT_DEPENDENCIES, *q.checker_files(kind)))
        original = self.out
        source = original / 'private-source'
        for name in names:
            destination = source / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(q.ROOT / name, destination)
        model = source / '.artifacts' / 'owner'
        model.mkdir(parents=True)
        for name in ('flashsim-dut', 'preparation.json', 'owner-manifest.json', 'cpu-tool'):
            item = original / name
            if item.is_dir():shutil.copytree(item, model / name)
            else:shutil.copyfile(item, model / name)
        self.out = model
        self.flashsim = model / 'cpu-tool'
        patch.object(q, 'ROOT', source).start()
        patch.object(q.builder, 'ROOT', source).start()
        patch.object(q.builder, 'ADAPTER', source / 'scripts/flashsim_native').start()
        return self.fixture(kind, q.SAMPLE_PROFILE)

    def test_graphics_sample_exact_factory_driver_and_mandatory_producer_closure(self):
        kind='native-sample-graphics';path,record=self.sample_fixture(kind)
        self.assertEqual(path,self.out/'executors'/kind/'qualification.json')
        self.assertEqual(record['ownerResourceStage'],'geometry-compute-sample')
        files=q.driver_files(kind,q.SAMPLE_PROFILE)
        self.assertEqual(files[:4],['scripts/native_sample_graphics_driver.cpp',
            'scripts/native_sample_graphics_runtime.hpp','scripts/native_sample_owner_runtime.hpp',
            'scripts/native_sample_owner_capture.hpp'])
        self.assertEqual(q.build_lock_paths(self.out,kind),(self.out/'.lock',path.parent/'.lock'))
        for other in ('native','compute','native-sample'):
            with self.assertRaises(RuntimeError):q.verify_qualification(path,kind=other)
        for profile in (q.owner.PROFILE,q.COMPUTE_STAGE,q.FULL_PROFILE,'renamed-sample'):
            with self.assertRaises(RuntimeError):q.driver_files(kind,profile)
        mandatory=(*q.GRAPHICS_CHECKERS,*q.GRAPHICS_PRODUCER_ROOTS,
            'scripts/qualify_native_sample_graphics_owner.py','scripts/native_sample_graphics_inputs.py',
            'userspace/tests/graphics_sample_live.inc','userspace/tests/graphics_sample_source_plan.inc',
            'userspace/tests/graphics_sample_entry.h','userspace/tests/graphics_sample_owner_ipc.h',
            'userspace/gallium/opengpu_native_graphics_internal.h',
            'driver/opengpu_native_program_snapshot.c','driver/opengpu_shader_validator.h',
            'driver/tests/private_snapshot_stubs/linux/slab.h')
        for name in mandatory:
            self.assertIn(name,record['source_sha256'])
        for key,value in (('kind','native-sample'),('profile',q.owner.PROFILE),
                ('ownerResourceStage','geometry-compute'),('source_sha256',{})):
            bad=copy.deepcopy(record);bad[key]=value;q.common.write_json(path,bad)
            with self.subTest(key=key),self.assertRaises(RuntimeError):q.verify_qualification(path,kind=kind)
        q.common.write_json(path,record)
        (path.parent/'.lock').symlink_to(path)
        with self.assertRaises(RuntimeError):q.build_lock_paths(self.out,kind)

    def test_graphics_sample_rejects_changed_real_flow_include_oracle_and_schema_sources(self):
        kind='native-sample-graphics';path,record=self.sample_fixture(kind)
        names=(*q.GRAPHICS_CHECKERS,'userspace/tests/graphics_sample_flow.c',
            'userspace/tests/graphics_sample_kernel.c','userspace/tests/graphics_sample_live.inc',
            'userspace/tests/graphics_sample_source_plan.inc','userspace/tests/graphics_sample_entry.h',
            'userspace/tests/graphics_sample_owner_ipc.c','userspace/tests/graphics_sample_owner_ipc.h',
            'userspace/tests/graphics_uniform_frontend.cpp','userspace/tests/graphics_uniform_flow.c',
            'userspace/gallium/opengpu_native_sample_program.c','userspace/gallium/opengpu_native_uniform_emit.c',
            'scripts/native_sample_graphics_driver.cpp','scripts/native_sample_graphics_runtime.hpp')
        for name in names:
            item=q.ROOT/name;original=item.read_bytes();item.write_bytes(original+b'\n/* stale CPU source */\n')
            with self.subTest(name=name),self.assertRaises(RuntimeError):q.verify_qualification(path,kind=kind)
            item.write_bytes(original)
        # Missing mandatory consumers cannot yield a smaller accepted closure.
        for name in (*q.GRAPHICS_CHECKERS,'userspace/tests/graphics_sample_live.inc',
                'userspace/tests/graphics_sample_source_plan.inc','userspace/tests/graphics_sample_entry.h'):
            item=q.ROOT/name;original=item.read_bytes();item.unlink()
            with self.subTest(missing=name),self.assertRaises(RuntimeError):q.verify_qualification(path,kind=kind)
            item.write_bytes(original)
        q.verify_qualification(path,kind=kind)

    def test_graphics_sample_new_gpu_includes_cannot_escape_or_be_silently_omitted(self):
        kind='native-sample-graphics';path,record=self.sample_fixture(kind)
        item=q.ROOT/'userspace/tests/graphics_sample_live.inc';original=item.read_bytes()
        for name in ('new-required-graphics.h','pipe/unregistered-mesa-header.h'):
            item.write_bytes(original+('\n#include "'+name+'"\n').encode())
            with self.assertRaisesRegex(RuntimeError,'missing native sample graphics producer dependency'):
                q.verify_qualification(path,kind=kind)
        local=item.parent/'new-required-graphics.h';local.write_text('// newly added GPU include\n')
        item.write_bytes(original+b'\n#include "new-required-graphics.h"\n')
        with self.assertRaises(RuntimeError):q.verify_qualification(path,kind=kind)
        self.assertIn('userspace/tests/new-required-graphics.h',q.checker_files(kind))
        local.unlink();local.symlink_to(self.out/'preparation.json')
        with self.assertRaisesRegex(RuntimeError,'aliased native sample graphics producer'):
            q.verify_qualification(path,kind=kind)
        local.unlink();item.write_bytes(original+b'\n#include UNKNOWN_GENERATED_HEADER\n')
        with self.assertRaisesRegex(RuntimeError,'unsupported native sample graphics producer include'):
            q.verify_qualification(path,kind=kind)
        item.write_bytes(original)
        # A local shadow of an explicitly external Mesa include is still bound.
        shadow=item.parent/'pipe/p_context.h';shadow.parent.mkdir();shadow.write_text('// local Mesa shadow\n')
        self.assertIn('userspace/tests/pipe/p_context.h',q.checker_files(kind))
        with self.assertRaises(RuntimeError):q.verify_qualification(path,kind=kind)
        shadow.unlink();shadow.symlink_to('missing-external-target.h')
        with self.assertRaisesRegex(RuntimeError,'missing native sample graphics producer'):
            q.verify_qualification(path,kind=kind)

    def test_graphics_sample_strict_driver_closure_model_and_failed_build_identity(self):
        kind='native-sample-graphics';path,record=self.sample_fixture(kind);snapshot=path.parent/'builder.json'
        original=json.loads(snapshot.read_text())
        for name in ('scripts/native_sample_graphics_runtime.hpp','scripts/native_sample_owner_runtime.hpp',
                     'scripts/native_sample_owner_capture.hpp'):
            bad=copy.deepcopy(original);del bad['driver_inputs'][str(q.ROOT/name)]
            q.common.write_json(snapshot,bad);record['builder_sha256']=q.common.sha(snapshot);q.common.write_json(path,record)
            with self.assertRaises(RuntimeError):q.verify_qualification(path,kind=kind)
        bad=copy.deepcopy(original);bad['driver_source']=str(q.ROOT/'scripts/native_sample_owner_driver.cpp')
        q.common.write_json(snapshot,bad);record['builder_sha256']=q.common.sha(snapshot);q.common.write_json(path,record)
        with self.assertRaises(RuntimeError):q.verify_qualification(path,kind=kind)
        q.common.write_json(snapshot,original);record['builder_sha256']=q.common.sha(snapshot);q.common.write_json(path,record)
        for name in ('dut.h','dut_0.cpp','hw.mlir'):
            item=self.out/'flashsim-dut'/name;data=item.read_bytes();item.write_bytes(data+b'\nchanged')
            with self.subTest(model=name),self.assertRaises(RuntimeError):q.verify_qualification(path,kind=kind)
            item.write_bytes(data)
        runtime=q.ROOT/'scripts/native_sample_graphics_runtime.hpp';data=runtime.read_bytes()
        runtime.write_bytes(data+b'\n#include "new-graphics-runtime.hpp"\n')
        (runtime.parent/'new-graphics-runtime.hpp').write_text('// omitted build dependency\n')
        record['source_sha256']=q.hashes([*q.driver_files(kind,q.SAMPLE_PROFILE),
            *q.DEFAULT_DEPENDENCIES,*q.checker_files(kind)])
        q.common.write_json(path,record)
        with self.assertRaisesRegex(ValueError,'unsupported or shadowed driver include'):
            q.verify_qualification(path,kind=kind)
        runtime.write_bytes(data)
        with patch.object(q,'model_identity',side_effect=RuntimeError('stale full model')):
            with self.assertRaisesRegex(RuntimeError,'stale full model'):
                q.build_executor(self.out,self.flashsim,kind,shards=1)
        self.assertFalse(path.exists())

    def test_native_sample_has_one_exact_factory_distinct_path_and_include_closure(self):
        path,record=self.sample_fixture()
        self.assertEqual(path,self.out/'executors/native-sample/qualification.json')
        self.assertEqual(record['ownerResourceStage'],'geometry-compute-sample')
        self.assertEqual(record['ownerResourceVersion'],2)
        self.assertIs(record['fullResourceQualification'],False)
        self.assertEqual(len(q.FULL_COMPUTE_CONTRACTS[q.SAMPLE_PROFILE][1]),112)
        files=q.driver_files('native-sample',q.SAMPLE_PROFILE)
        self.assertEqual(files[0],'scripts/native_sample_owner_driver.cpp')
        self.assertIn('scripts/native_sample_owner_runtime.hpp',files)
        self.assertIn('scripts/native_sample_owner_capture.hpp',files)
        for kind in ('native','compute'):
            self.assertEqual(q.checker_files(kind),q.CHECKERS)
            with self.assertRaises(RuntimeError):q.verify_qualification(path,kind=kind)
        for profile in (q.owner.PROFILE,q.COMPUTE_STAGE,q.FULL_PROFILE,'another-sample-profile'):
            with self.assertRaises(RuntimeError):q.driver_files('native-sample',profile)
        q.builder.validate_driver_includes([q.ROOT/n for n in
            [files[0],*q.DEFAULT_DEPENDENCIES,*files[1:]]],self.out/'flashsim-dut/dut.h',self.out/'flashsim-dut')
        for name in ('scripts/native_sample_owner_controller.py','scripts/native_sample_owner_oracle.py',
                     'scripts/native_sample_gny2_oracle.py',
                     'scripts/native_sample_owner_recipe.py', 'scripts/qualify_native_sample_owner.py',
                     'scripts/native_sample_compiler_inputs.py',
                     'userspace/opengpu_native_sample_graphics.c','userspace/opengpu_native_sample_graphics.h',
                     'driver/opengpu_native_frame_internal.h',
                     'docs/abi/native-layered-graphics-v2.json','scripts/owner_process_lifetime.py',
                     'scripts/native_sample_owner_protocol.py','scripts/test_native_mmio_capture.py',
                     'driver/opengpu_native_program_snapshot.c','driver/opengpu_shader_validator.h',
                     'driver/tests/private_snapshot_stubs/linux/slab.h',
                     'userspace/tests/native_sample_essl/mask-depth-keep.frag'):
            self.assertIn(name,record['source_sha256'])

    def test_native_sample_rejects_real_source_essl_headers_and_missing_consumers(self):
        path,record=self.sample_fixture()
        for name in ('scripts/native_sample_live_producer.c','scripts/native_sample_submit_producer.c',
                     'userspace/tests/native_sample_essl/sample.frag',
                     'userspace/gallium/opengpu_native_fs_emit.c','userspace/tests/fs_glsl_frontend.cpp',
                     'scripts/native_sample_owner_runtime.hpp','scripts/native_sample_owner_capture.hpp',
                     'scripts/native_sample_owner_controller.py','scripts/native_sample_owner_oracle.py',
                     'scripts/qualify_native_sample_owner.py','scripts/native_sample_compiler_inputs.py',
                     'scripts/native_sample_owner_recipe.py',
                     'userspace/opengpu_native_sample_graphics.c','userspace/opengpu_native_sample_graphics.h',
                     'driver/opengpu_native_frame_internal.h',
                     'docs/abi/native-layered-graphics-v2.json','scripts/owner_process_lifetime.py',
                     'driver/opengpu_native_submit.c','driver/opengpu_native_program_snapshot.c',
                     'driver/opengpu_shader_validator.h'):
            item=q.ROOT/name;original=item.read_bytes();item.write_bytes(original+b'\n/* stale CPU fixture */\n')
            with self.subTest(name=name),self.assertRaises(RuntimeError):q.verify_qualification(path,kind='native-sample')
            item.write_bytes(original)
        for name in ('scripts/native_sample_owner_controller.py','scripts/native_sample_owner_oracle.py',
                     'scripts/native_sample_gny2_oracle.py','scripts/native_sample_owner_recipe.py',
                     'scripts/qualify_native_sample_owner.py','scripts/native_sample_compiler_inputs.py'):
            item=q.ROOT/name;original=item.read_bytes();item.unlink()
            with self.assertRaisesRegex(RuntimeError,'missing required native sample checker'):
                q.verify_qualification(path,kind='native-sample')
            item.write_bytes(original)
        item=q.ROOT/'scripts/native_sample_live_producer.c';original=item.read_bytes()
        item.write_bytes(original+b'\n#include "unregistered.inc"\n')
        with self.assertRaisesRegex(RuntimeError,'missing native sample producer dependency'):
            q.verify_qualification(path,kind='native-sample')
        item.write_bytes(original)
        new=q.ROOT/'scripts/new-live-dependency.hpp';new.write_text('// new source dependency\n')
        item.write_bytes(original+b'\n#include "new-live-dependency.hpp"\n')
        with self.assertRaises(RuntimeError):q.verify_qualification(path,kind='native-sample')
        item.write_bytes(original)
        q.verify_qualification(path,kind='native-sample')
        item=q.ROOT/'scripts/native_sample_owner_oracle.py';original=item.read_bytes()
        alternate=self.out/'external-oracle.py';alternate.write_bytes(original)
        item.unlink();item.symlink_to(alternate)
        with self.assertRaisesRegex(RuntimeError,'aliased native sample checker source'):
            q.verify_qualification(path,kind='native-sample')
        item.unlink();item.write_bytes(original)

    def test_native_sample_rejects_incomplete_dependencies_driver_or_updated_include(self):
        path,record=self.sample_fixture();snapshot=path.parent/'builder.json'
        original=json.loads(snapshot.read_text())
        for name in ('scripts/native_sample_owner_capture.hpp','scripts/native_sample_owner_runtime.hpp'):
            bad=copy.deepcopy(original);del bad['driver_inputs'][str((q.ROOT/name).resolve())]
            q.common.write_json(snapshot,bad);record['builder_sha256']=q.common.sha(snapshot);q.common.write_json(path,record)
            with self.assertRaises(RuntimeError):q.verify_qualification(path,kind='native-sample')
        q.common.write_json(snapshot,original);record['builder_sha256']=q.common.sha(snapshot)
        # Even a freshly rehashed forged record cannot omit a newly included
        # shared runtime header from the builder's exact declared closure.
        runtime=q.ROOT/'scripts/native_sample_owner_runtime.hpp'
        runtime.write_bytes(runtime.read_bytes()+b'\n#include "new-runtime.hpp"\n')
        (runtime.parent/'new-runtime.hpp').write_text('// new runtime dependency\n')
        record['source_sha256']=q.hashes([*q.driver_files('native-sample',q.SAMPLE_PROFILE),
            *q.DEFAULT_DEPENDENCIES,*q.checker_files('native-sample')])
        q.common.write_json(path,record)
        with self.assertRaisesRegex(ValueError,'unsupported or shadowed driver include'):
            q.verify_qualification(path,kind='native-sample')

    def test_native_sample_rejects_stale_model_kind_and_failed_attempt_cannot_reuse_success(self):
        path,record=self.sample_fixture()
        for name in ('dut.h','dut_0.cpp','hw.mlir'):
            item=self.out/'flashsim-dut'/name;original=item.read_bytes();item.write_bytes(original+b'\nchanged')
            with self.assertRaises(RuntimeError):q.verify_qualification(path,kind='native-sample')
            item.write_bytes(original)
        for key,value in (('kind','compute'),('profile',q.FULL_PROFILE),
                          ('ownerResourceStage','geometry-compute'),('source_sha256',{})):
            bad=copy.deepcopy(record);bad[key]=value;q.common.write_json(path,bad)
            with self.assertRaises(RuntimeError):q.verify_qualification(path,kind='native-sample')
        q.common.write_json(path,record)
        cache=self.out/'executors/compute';cache.mkdir();other=cache/'qualification.json';other.write_text('other identity')
        locks=q.build_lock_paths(self.out,'native-sample')
        self.assertEqual(locks,(self.out/'.lock',path.parent/'.lock'))
        self.assertEqual(q.build_lock_paths(self.out,'compute'),(self.out/'.lock',))
        (path.parent/'.lock').symlink_to(other)
        with self.assertRaises(RuntimeError):q.build_lock_paths(self.out,'native-sample')
        (path.parent/'.lock').unlink()
        with patch.object(q,'model_identity',side_effect=RuntimeError('stale full model')):
            with self.assertRaisesRegex(RuntimeError,'stale full model'):
                q.build_executor(self.out,self.flashsim,'native-sample',shards=1)
        self.assertFalse(path.exists());self.assertEqual(other.read_text(),'other identity')


if __name__=='__main__':unittest.main()
