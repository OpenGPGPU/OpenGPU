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
import test_protected_tessellation_joint as runner
from owner_process_lifetime import CleanupIncomplete, group_dead, stop_group


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
