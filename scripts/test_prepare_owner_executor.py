#!/usr/bin/env python3
"""CPU-only rejection of stale/mixed executor identities; no GPU model runs."""
import copy
import json
import os
from pathlib import Path
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
                [*files,*q.DEFAULT_DEPENDENCIES,*q.CHECKERS]),
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
        for kind in q.KINDS:
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


if __name__=='__main__':unittest.main()
