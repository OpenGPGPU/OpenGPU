#!/usr/bin/env python3
"""Real Linux source snapshots with synthetic returned pixels/GNY2, no GPU."""
import copy
import struct
import unittest

from compute_owner_oracle import SparseMemory
from test_native_sample_owner import profile as cpu_profile
import test_native_sample_graphics_recipe as recipe_tests
from test_native_sample_graphics_source import Overlay
from native_sample_graphics_source_oracle import expected_color
from native_sample_graphics_gny2_oracle import _expected_words
import native_sample_graphics_owner_oracle as oracle


def capture(d, profile, command):
    """Explicit synthetic enqueue, not an actual producer/DUT capture."""
    words = profile['words'] + profile['registers']
    views = [oracle.view_interval(v) for v in oracle.writable_views(d)]
    writable = oracle.merged((a & ~4095, (b+4095) & ~4095) for a, b in views)
    registers = [(0x14c, d['satp']), (0x150, d['satp']), (0xc4, command),
                 (0xc8, 7), (0xf4, d['jobVA']), (0xfc, 512),
                 (0xf8, d['jobVA']+512), (0x100, 0), (0xec, 0), (0x11c, 1)]
    checks = [(0x124, 0xffffffff, 0x8700|command)]
    for i in range(12):
        value = 0x474e5801 if i == 0 else d['context'] if i == 6 else 11 if i == 7 else 13 if i == 8 else 0
        checks.append((0x180+4*i, 0 if i in (2, 3) else 0xffffffff, value))
    out = bytearray(b'OGCAP002'+struct.pack('<QII', 1000000, 16, command))
    out += struct.pack('<I', len(words))
    out += b''.join(struct.pack('<II', w['address'], w['value']) for w in words)
    out += struct.pack('<I', len(writable))+b''.join(struct.pack('<QQ', *v) for v in writable)
    out += struct.pack('<I', len(registers))+b''.join(struct.pack('<IIII', 1, a, v, 0) for a, v in registers)
    out += struct.pack('<I', 13)+b''.join(struct.pack('<III', *c) for c in checks)+struct.pack('<I', 0)
    return bytes(out)


def synthetic_result(before, d, command):
    """Hand-supplied oracle success input only, never execution evidence."""
    color = next(v for v in d['resources'] if (v['role'], v['index']) == (1, 0))
    first, _ = oracle.view_interval(color)
    completed, _ = oracle.view_interval(d['completion'])
    return Overlay(before, [(first, struct.pack('<4I', *expected_color(d['record']))*16),
        (completed, struct.pack('<4096I', *_expected_words(command, d['context'])))])


class OrdinaryOwnerOracleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        recipe_tests.RecipeTests.setUpClass()
        cls.addClassCleanup(recipe_tests.RecipeTests.tearDownClass)
        cls.fixture = recipe_tests.RecipeTests
        cls.d = cls.fixture.descriptions
        cls.profile = cpu_profile(); cls.profile['registers'][-1]['value'] = 0x02020810
        cls.captures = [capture(d, cls.profile, 41+i) for i, d in enumerate(cls.d)]

    def memory(self): return SparseMemory(self.fixture.out/'union.memory')
    def preflight(self, memory, descriptions=None, raw=None):
        return oracle.preflight(memory, self.d if descriptions is None else descriptions,
            self.fixture.programs, self.fixture.reflection, self.profile,
            self.captures[0] if raw is None else raw)
    def result(self, memory, returned, record=0, previous=None, raw=None):
        return oracle.verify_result(memory, returned, self.d, self.fixture.programs,
            self.fixture.reflection, self.profile, self.captures[record] if raw is None else raw,
            record, previous)

    def test_full_source_capture_image_completion_and_original_byte_guards_for_two_records(self):
        with self.memory() as memory:
            self.assertEqual(self.preflight(memory), 41)
            first = synthetic_result(memory, self.d[0], 41)
            second = synthetic_result(first, self.d[1], 42)
            self.assertEqual(self.result(memory, first), 41)
            self.assertEqual(self.result(memory, second, 1, first), 42)
            for record, returned in enumerate((first, second)):
                self.assertEqual(returned.ranges, memory.ranges)
                self.assertEqual(sum(b-a for a,b in oracle.writable_bytes(self.d[record])), 60160)

    def test_exact_job_bounds_context_flags_and_completion_predicates(self):
        raw = self.captures[0]
        # Mutate each command field while keeping the binary envelope valid.
        start = 24+4+(112+5)*8
        count, = struct.unpack_from('<I', raw, start)
        trace = start+4+count*16
        length, = struct.unpack_from('<I', raw, trace)
        for i in range(length):
            changed = bytearray(raw); at = trace+4+16*i+8
            value, = struct.unpack_from('<I', changed, at)
            struct.pack_into('<I', changed, at, value^1)
            with self.assertRaises(ValueError):oracle.capture_identity(bytes(changed), self.profile, self.d[0])
        checks = len(raw)-4-13*12
        for index in range(13):
            for part in (0, 1, 2):
                changed=bytearray(raw); at=checks+12*index+4*part
                value,=struct.unpack_from('<I', changed, at);struct.pack_into('<I', changed, at, value^1)
                with self.assertRaises(ValueError):oracle.capture_identity(bytes(changed), self.profile, self.d[0])
        for wrong in (b'OGCAP001'+raw[8:], raw[:-1], raw+b'x'):
            with self.assertRaises(ValueError):oracle.capture_identity(wrong, self.profile, self.d[0])

    def test_capture_page_permission_cannot_admit_nonowner_bytes_or_drop_a_view(self):
        raw = self.captures[0]
        start=24+4+(112+5)*8
        count,=struct.unpack_from('<I',raw,start)
        for first_end in ((0, 1<<32), (0x1000, 0x2000)):
            changed=bytearray(raw)
            struct.pack_into('<QQ',changed,start+4,*first_end)
            with self.assertRaises(ValueError):oracle.capture_identity(bytes(changed),self.profile,self.d[0])
        changed=raw[:start]+struct.pack('<I',count-1)+raw[start+4+16:]
        with self.assertRaises(ValueError):oracle.capture_identity(changed,self.profile,self.d[0])

    def test_precise_work_prefixes_and_allocation_guards_are_narrower_than_page_permissions(self):
        with self.memory() as memory:
            first=synthetic_result(memory,self.d[0],41)
            for view in self.d[0]['resources']:
                key=view['role'],view['index']
                if key[0] not in (3,4,5):continue
                used={(3,0):2944,(4,0):64,(5,0):64}[key]
                physical,_=oracle.view_interval(view)
                at=physical+used
                changed=Overlay(first,[(at,bytes([first.read(at,1)[0]^1]))])
                with self.assertRaisesRegex(ValueError,'guard changed'):self.result(memory,changed)
            # A legal scratch-prefix write is allowed, but the unused tail of
            # its rounded allocation remains guarded.
            first_byte,end=oracle.view_interval(self.d[0]['vertexScratch'])
            oracle.guards(memory,Overlay(memory,[(first_byte,b'\x11')]),oracle.writable_bytes(self.d[0]))
            changed=Overlay(first,[(end,b'\x11')])
            with self.assertRaisesRegex(ValueError,'guard changed'):self.result(memory,changed)
            color=next(v for v in self.d[0]['resources'] if v['role']==1)
            physical,end=oracle.view_interval(color)
            for at in (physical-1,end):
                changed=Overlay(first,[(at,b'\x11')])
                with self.assertRaises(ValueError):self.result(memory,changed)

    def test_second_result_preserves_first_image_gny2_and_all_other_original_bytes(self):
        with self.memory() as memory:
            first=synthetic_result(memory,self.d[0],41); second=synthetic_result(first,self.d[1],42)
            color=next(v for v in self.d[0]['resources'] if v['role']==1)
            sites=[oracle.view_interval(color)[0],oracle.view_interval(self.d[0]['completion'])[0]]
            sites += [self.d[0]['privateSegments'][0]['physical'],self.d[0]['rootPhysical']]
            for at in sites:
                changed=Overlay(second,[(at,bytes([second.read(at,1)[0]^1]))])
                with self.assertRaises(ValueError):self.result(memory,changed,1,first)
            with self.assertRaises(ValueError):self.result(memory,second,1)
            with self.assertRaises(ValueError):self.result(memory,first,0,first)
            # Swapping a valid GNY2 lease's enqueue identity still fails.
            bad=synthetic_result(first,self.d[1],41)
            with self.assertRaisesRegex(ValueError,'GNY2'):self.result(memory,bad,1,first)

    def test_pair_identity_alias_and_order_cannot_authorize_another_owner(self):
        for field in ('context','asid','satp','rootPhysical'):
            changed=copy.deepcopy(self.d);changed[1][field]^=1
            with self.assertRaises(ValueError):oracle.checked_pair(changed)
        for field in ('jobVA','frameVA','drawVA','stateVA'):
            changed=copy.deepcopy(self.d);changed[1][field]=changed[0][field]
            with self.assertRaises(ValueError):oracle.checked_pair(changed)
        for name in ('resources','privateSegments'):
            changed=copy.deepcopy(self.d)
            if name=='resources':changed[1][name][0]=changed[0][name][0]
            else:changed[1][name][0]['physical']=changed[0][name][0]['physical']
            with self.assertRaises(ValueError):oracle.checked_pair(changed)
        with self.assertRaises(ValueError):oracle.checked_pair(self.d[::-1])


if __name__=='__main__':unittest.main()
