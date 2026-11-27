#!/usr/bin/env python3
"""Exact joint source ABI/catalog mutation tests, without hardware tools."""
import copy
import hashlib
import os
import shutil
import signal
import subprocess
import sys
import time
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import generate_protected_tessellation_joint_abi as abi
import generate_protected_tessellation_joint_geometry_abi as geometry_abi
import test_protected_tessellation_joint as runner
from owner_process_lifetime import CleanupIncomplete, group_dead, stop_group


class JointGeometryAbi(unittest.TestCase):
    def test_exact_generated_header_and_reserved_feedback_area(self):
        self.assertEqual(geometry_abi.OUTPUT.read_text(), geometry_abi.generate())
        schema = json.loads(geometry_abi.SCHEMA.read_text())
        self.assertEqual(schema['root']['domain_magics'], [hex(0x474e5107+i) for i in range(3)])
        self.assertEqual((schema['root']['bytes'], schema['root']['version']), (512, 3))
        self.assertEqual(schema['attachment']['entry']['feedback_reserved_zero'], {'word': 80, 'count': 100})
        self.assertEqual(schema['attachment']['entry']['flags'], 3)
        header = geometry_abi.OUTPUT.read_text()
        self.assertIn('#define OPENGPU_JOINT_GEOMETRY_ENTRY_FEEDBACK_RESERVED_ZERO_WORD 80u', header)
        self.assertIn('#define OPENGPU_JOINT_GEOMETRY_ENTRY_FEEDBACK_RESERVED_ZERO_COUNT 100u', header)
        self.assertNotIn('OPENGPU_JOINT_GEOMETRY_ENTRY_TF_', header)

    def test_host_command_completion_and_profile_constants(self):
        schema = json.loads(geometry_abi.SCHEMA.read_text())
        host = schema['hostSubmission']
        completion = schema['completion']
        profile = schema['profile']
        self.assertEqual(host['commandTag'], '0x474a0200')
        self.assertEqual((host['rootBytes'], host['rootAllocationBytes']), (512, 4096))
        self.assertEqual(host['ownerLimitMaximum'], '0x100000000')
        self.assertEqual((completion['magic'], completion['words']), ('0x474e5a03', 46))
        self.assertEqual((completion['prefixBase'], completion['legacyTailBase'],
                          completion['geometryBase'], completion['geometryEnd'],
                          completion['reservedBase'], completion['reservedWords'],
                          completion['end']),
                          ('0x180', '0x540', '0x560', '0x5d8', '0x5d8', 4, '0x5e8'))
        self.assertIn('status zero requires geometryReportValid true', completion['rules'])
        self.assertEqual(geometry_abi.profile_words(profile),
                         [0x474a5002, 0x8038ff, 0x08100207, 0x8ce58b2c])
        flags = {item['name']: item for item in profile['flags']}
        self.assertEqual(set(flags), {
            'OWNED_VERTEX_SOURCE', 'PATCH_ZERO_INITIALIZED', 'OWNED_PATCH_READS',
            'OWNED_TESSELLATION_INFO', 'GEOMETRY_INPUTS', 'JOINT_DIRECTORY_ROUTE',
            'WHOLE_OWNER_RETIREMENT', 'SCALAR_FPU', 'ADVANCED_BLEND', 'SAMPLE_INPUTS',
            'FEEDBACK', 'GEOMETRY_ROUTE', 'GEOMETRY_UNIFORMS', 'SHARED_THREE_DOMAIN_OWNER',
        })
        self.assertEqual(profile['requiredOwnerMask'], '0x7f')
        self.assertEqual(profile['targetFlags'], '0x38ff')
        self.assertTrue(all(flags[name]['required'] for name in (
            'OWNED_VERTEX_SOURCE', 'PATCH_ZERO_INITIALIZED', 'OWNED_PATCH_READS',
            'OWNED_TESSELLATION_INFO', 'GEOMETRY_INPUTS', 'JOINT_DIRECTORY_ROUTE',
            'WHOLE_OWNER_RETIREMENT')))
        self.assertEqual((flags['SCALAR_FPU']['bit'], flags['SCALAR_FPU']['required']), (7, True))
        self.assertEqual((flags['ADVANCED_BLEND']['bit'], flags['ADVANCED_BLEND']['required']), (8, False))
        self.assertEqual((flags['SAMPLE_INPUTS']['bit'], flags['SAMPLE_INPUTS']['required']), (9, False))
        self.assertEqual((flags['FEEDBACK']['bit'], flags['FEEDBACK']['required']), (10, False))
        self.assertEqual((flags['GEOMETRY_ROUTE']['bit'], flags['GEOMETRY_ROUTE']['required']), (11, True))
        self.assertEqual((flags['GEOMETRY_UNIFORMS']['bit'], flags['GEOMETRY_UNIFORMS']['required']), (12, True))
        self.assertEqual((flags['SHARED_THREE_DOMAIN_OWNER']['bit'],
                          flags['SHARED_THREE_DOMAIN_OWNER']['required']), (13, True))
        self.assertIn('feedback bit10 must be zero', profile['rules'])
        header = geometry_abi.OUTPUT.read_text()
        self.assertIn('#define OPENGPU_JOINT_GEOMETRY_HOST_COMMAND_TAG 0x474a0200u', header)
        self.assertIn('#define OPENGPU_JOINT_GEOMETRY_COMPLETION_WORDS 0x2eu', header)
        self.assertIn('#define OPENGPU_JOINT_GEOMETRY_COMPLETION_GEOMETRY_BASE 0x560u', header)
        self.assertIn('#define OPENGPU_JOINT_GEOMETRY_PROFILE_CAPACITY_WORD 0x8ce58b2cu', header)
        self.assertIn('#define OPENGPU_JOINT_GEOMETRY_PROFILE_GCR1_PRESENCE_BIT 0x17u', header)
        self.assertIn('#define OPENGPU_JOINT_GEOMETRY_PROFILE_REQUIRED_OWNER_MASK 0x7fu', header)
        self.assertIn('#define OPENGPU_JOINT_GEOMETRY_PROFILE_TARGET_FLAGS 0x38ffu', header)
        self.assertIn('#define OPENGPU_JOINT_GEOMETRY_PROFILE_FLAGS 0x8038ffu', header)
        self.assertIn('#define OPENGPU_JOINT_GEOMETRY_PROFILE_FLAG_FEEDBACK_REQUIRED 0x0u', header)
        self.assertNotIn('TRANSFORM_FEEDBACK', header)

    def test_changed_schema_fails_closed(self):
        original = json.loads(geometry_abi.SCHEMA.read_text())
        mutations = [
            lambda s: s['root'].update(version=2),
            lambda s: s['root']['domain_magics'].__setitem__(1, '0x474e5107'),
            lambda s: s['root']['geometry_limit'].update(bits=32),
            lambda s: s['root'].update(geometry_count=4),
            lambda s: s['attachment'].update(entry_bytes=512),
            lambda s: s['attachment']['header'].update(count=5),
            lambda s: s['attachment']['entry'].update(flags=4),
            lambda s: s['attachment']['entry']['feedback_reserved_zero'].update(count=99),
            lambda s: s['attachment']['entry']['input_sequence'].update(bits=32),
            lambda s: s['attachment']['entry']['reserved_zero'][0].update(word=180),
            lambda s: s['hostSubmission'].update(commandTag='0x474a0100'),
            lambda s: s['hostSubmission'].update(rootAllocationBytes=512),
            lambda s: s['hostSubmission'].update(ownerLimitMaximum='0x100000001'),
            lambda s: s['hostSubmission']['rejectedFlags'].pop(),
            lambda s: s['completion'].update(words=45),
            lambda s: s['completion'].update(geometryBase='0x55c'),
            lambda s: s['completion'].update(reservedWords=3),
            lambda s: s['completion']['fields'][-3].update(bits=64),
            lambda s: s['completion']['rules'].pop(),
            lambda s: s['profile'].update(magic='0x474a5001'),
            lambda s: s['profile'].update(gcr1PresenceBit=22),
            lambda s: s['profile'].update(requiredOwnerMask='0x3f'),
            lambda s: s['profile'].update(targetFlags='0x387e'),
            lambda s: next(f for f in s['profile']['flags'] if f['name'] == 'FEEDBACK').update(required=True),
            lambda s: next(f for f in s['profile']['flags']
                           if f['name'] == 'SHARED_THREE_DOMAIN_OWNER').update(required=False),
            lambda s: s['profile']['capacities'].update(payloadBytes=262144),
            lambda s: s['profile']['capacityEncoding'][-1].update(shift=26),
            lambda s: s['profile'].update(provider='two-cu-vga60-owner-v2'),
            lambda s: s['source_rules'].pop(),
        ]
        artifacts = geometry_abi.ROOT/'.artifacts'
        artifacts.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='joint-geometry-abi-', dir=artifacts) as tmp:
            root = Path(tmp)
            (root/'docs/abi').mkdir(parents=True)
            for mutate in mutations:
                schema = copy.deepcopy(original)
                mutate(schema)
                (root/'docs/abi/protected-tessellation-joint-geometry-v1.json').write_text(json.dumps(schema))
                with patch.object(geometry_abi, 'ROOT', root), self.assertRaises(ValueError):
                    geometry_abi.generate()


class JointAbi(unittest.TestCase):
    def test_exact_generated_header_and_frozen_domain_pairs(self):
        self.assertEqual(abi.OUTPUT.read_text(), abi.generate())
        s = json.loads((abi.ROOT/'docs/abi/protected-tessellation-joint-v2.json').read_text())
        self.assertEqual([(x['magic'],x['sequenceMagic'],x['sequenceVersion']) for x in s['records']],
                         [(hex(0x474e5104+i),hex(0x474e5407+i),7+i) for i in range(3)])
        self.assertEqual((s['bytes'],s['words'],s['version']),(512,128,2))
        widths = abi.rules([dict(name='header'+str(i),word=i,bits=32,signed=False) for i in range(8)] + s['fields'],128)
        self.assertEqual([i for i,x in enumerate(widths) if not x],list(range(8,16))+list(range(36,128)))
        self.assertEqual([widths[i] for i in (19,23,30)],[1,1,1])
        self.assertEqual([x['word'] for x in s['fields'] if x['name'].startswith('directory.')],[27,28,29,31,32,33,34,35])

    def test_directory_uses_original_64bit_invocation_and_limit33(self):
        s = json.loads((abi.ROOT/'docs/abi/protected-tessellation-joint-v2.json').read_text())['directory']
        self.assertEqual((s['magic'],s['version'],s['headerBytes'],s['entryBytes']),('0x47564d02',2,64,64))
        h,e = abi.rules(s['headerFields'],16),abi.rules(s['entryFields'],16)
        self.assertEqual(h,[32]*15+[0])
        self.assertEqual(e,[32,32,32,32,3,32,32,32,32,32,32,32,32,32,1,0])

    def test_changed_schema_fails_closed_in_private_copies(self):
        original = json.loads((abi.ROOT/'docs/abi/protected-tessellation-joint-v2.json').read_text())
        mutations = [lambda s:s.update(version=1),lambda s:s.update(bytes=2048),
                     lambda s:s['records'][1].update(magic='0x474e5104'),
                     lambda s:s['records'][0].update(sequenceVersion=4),
                     lambda s:s['fields'][2].update(bits=32),
                     lambda s:s['fields'][-1].update(word=36),
                     lambda s:s['directory']['entryFields'][2].update(bits=32),
                     lambda s:s['directory']['entryFields'][-1].update(bits=32),
                     lambda s:s['directory'].update(entryBytes=128),
                     lambda s:s['sourceAddressesZero'].pop(),
                     lambda s:s['hostSubmission'].update(commandTag='0x474a0200'),
                     lambda s:s['hostSubmission'].update(rootAllocationBytes=512),
                     lambda s:s['hostSubmission'].update(completionTailBase='0x1b0')]
        artifacts = abi.ROOT/'.artifacts'
        artifacts.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='joint-abi-', dir=artifacts) as tmp:
            root = Path(tmp);(root/'docs/abi').mkdir(parents=True)
            for mutate in mutations:
                s = copy.deepcopy(original);mutate(s)
                (root/'docs/abi/protected-tessellation-joint-v2.json').write_text(json.dumps(s))
                with patch.object(abi,'ROOT',root),self.assertRaises(ValueError):
                    abi.generate()


class JointRunner(unittest.TestCase):
    def test_leader_exit_does_not_leave_input_consuming_descendant(self):
        with runner.workspace(abi.ROOT/'.artifacts') as out:
            ready = out/'ready'
            child = 'from pathlib import Path; import time; Path('+repr(str(ready))+').write_text("ready"); time.sleep(60)'
            leader = ('import os,subprocess,sys,time; '
                      'p=subprocess.Popen([sys.executable,"-c",'+repr(child)+']); '
                      'print(os.getpid(),p.pid,flush=True); '
                      '\nwhile not __import__("pathlib").Path('+repr(str(ready))+').exists(): time.sleep(.01)')
            result = runner.bounded([sys.executable,'-c',leader],env=dict(os.environ,TMPDIR=str(out)))
            group, child_pid = map(int,result.stdout.split())
            try:
                self.assertEqual(result.returncode,0)
                self.assertTrue(ready.exists())
                self.assertTrue(group_dead(group), 'exited leader left a live input consumer')
                status = subprocess.run(['ps','-p',str(child_pid),'-o','stat='],capture_output=True,text=True)
                self.assertTrue(status.returncode or status.stdout.strip().startswith('Z'))
            finally:
                try: os.killpg(group,signal.SIGKILL)
                except ProcessLookupError: pass
                end=time.monotonic()+10
                while not group_dead(group):
                    if time.monotonic()>end: raise CleanupIncomplete('test descendant still live')
                    time.sleep(.05)

    def test_unknown_exit_preserves_complete_workspace_and_log(self):
        parent=Path(tempfile.mkdtemp(prefix='joint-preserve-test-',dir=abi.ROOT/'.artifacts'))
        processes=[]
        def uncertain(process):
            stop_group(process); processes.append(process)
            raise CleanupIncomplete('test unknown final observation')
        try:
            with self.assertRaises(CleanupIncomplete):
                with runner.workspace(parent) as out:
                    (out/'input').write_bytes(b'original')
                    with patch.object(runner,'stop_group',side_effect=uncertain):
                        runner.bounded([sys.executable,'-c','print("finished")'],env=dict(os.environ,TMPDIR=str(out)))
            self.assertEqual((out/'input').read_bytes(),b'original')
            self.assertEqual(len(list(out.glob('group-*.log'))),1)
            self.assertTrue(processes)
        finally:
            for process in processes: stop_group(process)
            shutil.rmtree(parent)

    @staticmethod
    def identity(path):
        stat=path.stat()
        return (stat.st_size,stat.st_mtime_ns,stat.st_ino,hashlib.sha256(path.read_bytes()).hexdigest())

    def test_actual_mmd_requires_bound_translation_unit_and_every_header(self):
        with runner.workspace(abi.ROOT/'.artifacts') as out:
            source,header,target,dep=out/'source.c',out/'header.h',out/'object.o',out/'object.d'
            source.write_text('source');header.write_text('header')
            bound={str(p):self.identity(p) for p in (source,header)}
            dep.write_text(str(target)+': '+str(source)+' \\\n '+str(header)+'\n')
            self.assertEqual(runner.dependencies(dep,source,target,bound,{}),{source,header})
            with self.assertRaisesRegex(RuntimeError,'another output'):
                runner.dependencies(dep,source,out/'wrong.o',bound,{})
            with self.assertRaisesRegex(RuntimeError,'unbound/changed'):
                runner.dependencies(dep,source,target,{str(source):bound[str(source)]},{})
            header.write_text('mutant')
            with self.assertRaisesRegex(RuntimeError,'changed bound'):
                runner.dependencies(dep,source,target,bound,{})
            dep.write_text(str(target)+': '+str(source)+'\n')
            with self.assertRaisesRegex(RuntimeError,'omits actual'):
                runner.dependencies(dep,header,target,bound,{})

    def test_generated_mmd_input_must_be_bound_before_compilation(self):
        with runner.workspace(abi.ROOT/'.artifacts') as out:
            source,generated,target,dep=out/'source.c',out/'generated.inc',out/'object.o',out/'object.d'
            source.write_text('source');generated.write_text('owned exact preimage')
            bound={str(source):self.identity(source)}
            expected={str(generated):hashlib.sha256(generated.read_bytes()).hexdigest()}
            dep.write_text(str(target)+': '+str(source)+' '+str(generated)+'\n')
            runner.dependencies(dep,source,target,bound,expected)
            generated.write_text('changed generated input')
            with self.assertRaisesRegex(RuntimeError,'unbound/changed'):
                runner.dependencies(dep,source,target,bound,expected)


if __name__ == '__main__':
    unittest.main()
