#!/usr/bin/env python3
"""Explicit CPU byte fixtures and read-only original Linux capture controls.

Literal arithmetic histories below are fabricated CPU oracle inputs, never GPU
evidence. --captures checks fresh original CPU captures without executing code.
"""
import argparse
from copy import deepcopy
from pathlib import Path
import struct
import sys
import unittest

from compute_global_atomic_cases import CASES, bound_words, events, initial_kernarg, parameters
from compute_global_atomic_source import SCHEMA, ready, recipe
from compute_indirect_oracle import capture
from compute_owner_oracle import SparseMemory, verify
from compute_owner_protocol import strict_json
from test_compute_atomic_order import Memory

HISTORIES = {
    'swap-add': ((0xfffffff0, 0x80000001, 0, 0x80000002, 2, 0x80000003, 4, 0x80000004), 6),
    'xor-or': ((0x7ffff000, 0xfffff007, 0xfffff107, 0x7ffff110,
                0x7ffff310, 0xfffff337, 0xfffff737, 0x7ffff700), 0x7fffff00),
    'and-min': ((0x7fffffff, 0x7ffffff1, 0x80000007, 2,
                0x80000008, 0, 0x80000009, 8), 0x8000000a),
    'max-cas': ((0x80000000, 7, 7, 8, 8, 9, 9, 10), 0xa2000003),
    'unsigned-min-max': ((0xffffffff, 7, 0x80000007, 8, 0x80000008, 9, 0x80000009, 10), 0x8000000a),
    'cas-pair': ((0, 1, 0xa1000000, 0xa1000000, 0xa1000000, 0xa1000000, 0xa1000000, 0xa1000000), 0xa1000000),
}


def fixture(case):
    code = struct.pack('<%uI' % len(bound_words(case)), *bound_words(case))
    initial = Memory({0x10000: bytearray(code + bytes(4096 - len(code))),
                      0x20000: bytearray(initial_kernarg(case)),
                      0x30000: bytearray(code + b'\xf9' * (4096 - len(code))),
                      0x40000: bytearray(b'\x5a' * 4096)})
    d = dict(schema=SCHEMA, sourceCase=case, viewBytes=8192, groups=4, local=16,
             guard=0xdeadbeef, context=1, codePhysical=0x30000, codeBytes=len(code),
             shader=dict(handle=1, allocationPhysical=0x10000, allocationBytes=4096, offset=0, bytes=len(code)),
             kernarg=dict(handle=2, allocationPhysical=0x20000, allocationBytes=8192, offset=0, bytes=8192))
    returned = Memory({a: bytearray(data) for a, data in initial.data.items()})
    old, final = HISTORIES[parameters(case)['pair']]
    struct.pack_into('<8I', returned.data[0x20000], 128, *old)
    struct.pack_into('<I', returned.data[0x20000], parameters(case)['target_offset'], final)
    return initial, returned, d


class GlobalBytes(unittest.TestCase):
    def test_all_original_families_and_page_endpoints_accept_independent_literal_histories(self):
        for case in CASES:
            initial, returned, d = fixture(case)
            recipe(initial, d)
            verify(initial, returned, d)
            old, final = HISTORIES[parameters(case)['pair']]
            # Even the group-serial schedule must expose a real state change
            # for every non-CAS operation. CAS deliberately covers misses too.
            for index, event in enumerate(events(case)):
                if event['operation'] != 'compare-swap':
                    self.assertNotEqual(old[index], (*old, final)[index + 1])
            with self.assertRaises(ValueError):
                verify(initial, initial, d)

    def test_each_old_word_final_word_and_all_other_physical_bytes_are_checked(self):
        for case in CASES:
            initial, returned, d = fixture(case)
            target = parameters(case)['target_offset']
            for offset in (*range(128, 160, 4), target):
                bad = deepcopy(returned)
                bad.data[0x20000][offset + 3] ^= 1
                with self.assertRaises(ValueError, msg=(case, offset)):
                    verify(initial, bad, d)
            for address, offset in ((0x10000, 0), (0x30000, 4095), (0x40000, 0), (0x20000, 127),
                                    (0x20000, 160), (0x20000, 4091)):
                bad = deepcopy(returned)
                bad.data[address][offset] ^= 1
                with self.assertRaisesRegex(ValueError, 'guard bytes changed'):
                    verify(initial, bad, d)

    def test_raw_code_private_copy_original_identity_and_upload_guards_cannot_be_substituted(self):
        initial, _, d = fixture(CASES[0])
        for address, offset in ((0x10000, 0), (0x30000, 0), (0x20000, 128), (0x20000, 0)):
            changed = deepcopy(initial)
            changed.data[address][offset] ^= 1
            with self.assertRaises(ValueError):
                recipe(changed, d)
        for key, value in (('context', 2), ('codePhysical', 0x10000), ('codeBytes', d['codeBytes'] - 4)):
            changed = deepcopy(d)
            changed[key] = value
            with self.assertRaises(ValueError):
                recipe(initial, changed)


class Overlay:
    def __init__(self, memory, address, data):
        self.memory, self.address, self.data = memory, address, data
        self.ranges = memory.ranges

    def read(self, first, count):
        data = bytearray(self.memory.read(first, count))
        low, high = max(first, self.address), min(first + count, self.address + len(self.data))
        if low < high:
            data[low - first:high - first] = self.data[low - self.address:high - self.address]
        return bytes(data)


def check_capture(directory, profile):
    d = strict_json(directory / 'oracle.json')
    data = (directory / 'record-0.capture').read_bytes()
    code = (directory / 'code.words').read_text().splitlines()
    checked = 0
    with SparseMemory(directory / 'union.memory') as memory:
        ready(memory, d, data, code, profile)
        controls = []
        for mutate in (lambda x: x.update(context=2), lambda x: x.update(codeBytes=x['codeBytes'] - 4),
                       lambda x: x.update(codePhysical=x['shader']['allocationPhysical']),
                       lambda x: x['kernarg'].update(handle=3), lambda x: x['shader'].update(handle=3),
                       lambda x: x['kernarg'].update(offset=4), lambda x: x['kernarg'].update(bytes=8188),
                       lambda x: x['kernarg'].update(allocationPhysical=x['shader']['allocationPhysical']),
                       lambda x: x.update(sourceCase='global-atomic-unknown'), lambda x: x.update(extra=True)):
            wrong = deepcopy(d)
            mutate(wrong)
            controls.append((memory, wrong, data, code, profile))
        wrong_profile = dict(profile, ownerProfile='foreign-owner')
        controls.append((memory, d, data, code, wrong_profile))
        changed_code = list(code)
        changed_code[0] = '%08x' % (int(code[0], 16) ^ 1)
        controls.append((memory, d, data, changed_code, profile))
        for address in (d['shader']['allocationPhysical'], d['codePhysical'], d['kernarg']['allocationPhysical'] + 128):
            changed = Overlay(memory, address, bytes([memory.read(address, 1)[0] ^ 1]))
            controls.append((changed, d, data, code, profile))
        _, _, _, _, _, staged = capture(data, 0, profile)
        root = (staged[0x150] & 0x3fffff) << 12
        first, = struct.unpack('<I', memory.read(root + (0x80800000 >> 22) * 4, 4))
        leaf_address = (first >> 10) * 4096
        leaf, = struct.unpack('<I', memory.read(leaf_address, 4))
        for flag in (4, 0x200):
            changed = Overlay(memory, leaf_address, struct.pack('<I', leaf ^ flag))
            controls.append((changed, d, data, code, profile))
        widened = bytearray(data)
        grant = struct.calcsize('<8sQIII') + 122 * 8 + 4
        end, = struct.unpack_from('<Q', widened, grant + 8)
        struct.pack_into('<Q', widened, grant + 8, end + 4)
        controls.append((memory, d, bytes(widened), code, profile))
        for args in controls:
            try:
                ready(*args)
            except ValueError:
                checked += 1
            else:
                raise AssertionError('accepted changed original global capture: ' + d['sourceCase'])
        try:
            verify(memory, memory, d)
        except ValueError:
            pass
        else:
            raise AssertionError('untouched raw RV32 upload accepted as an execution result')
    assert checked == 18, checked
    return checked


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--captures', type=Path)
    parser.add_argument('--profile-json', type=Path)
    args = parser.parse_args()
    if args.captures is None:
        if args.profile_json is not None:
            parser.error('--profile-json requires --captures')
        unittest.main(argv=[sys.argv[0]])
    else:
        if args.profile_json is None:
            parser.error('--captures requires its explicit original --profile-json')
        profile = strict_json(args.profile_json)
        checked = sum(check_capture(args.captures / case, profile) for case in CASES)
        print('24 original Linux capture checks and %u rejection controls PASS (CPU only)' % checked)
