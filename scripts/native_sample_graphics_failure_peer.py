#!/usr/bin/env python3
"""TEST ONLY: synthetic control receipts and deliberately unexecuted GPU RAM.

This peer never executes shader ISA, writes a valid GNY2, authorizes cleanup,
or implements an executor qualification. Only the opt-in negative integration
test may start it, with the explicit --test-only-synthetic-no-isa flag.
"""
import argparse
import json
from pathlib import Path
import shutil
import struct
import time

from compute_owner_oracle import SparseMemory
from native_sample_graphics_owner_oracle import capture_identity, view_interval
from native_sample_graphics_protocol import held_bytes, retired_bytes, strict_json
from native_sample_graphics_source_oracle import expected_color
from native_sample_owner_controller import atomic
from owner_resource_contract import SAMPLE_PROFILE


def require(ok, text):
    if not ok: raise ValueError('TEST ONLY synthetic peer: '+text)


def wait(path, directory, timeout):
    deadline = time.monotonic()+timeout
    while not path.exists():
        require(not (directory/'error').exists(), 'controller rejected before the expected C phase')
        require(time.monotonic() < deadline, 'timeout waiting for '+path.name)
        time.sleep(.01)
    require(path.is_file() and not path.is_symlink(), 'invalid actual C phase file')


def retirement(path, held):
    """Require raw C IRQ-drain telemetry and POP, not a peer-made retire file."""
    data = path.read_bytes()
    require(len(data) >= 12 and data[:8] == b'OGRTR001', 'missing original C retirement trace')
    count, = struct.unpack_from('<I', data, 8)
    require(0 < count <= 1024 and len(data) == 12+count*16, 'malformed C retirement trace')
    reads = {}; pops = 0
    for kind, address, value, mask in struct.iter_unpack('<4I', data[12:]):
        if kind == 1 and address == 0x130:
            require(value == 1 and mask == 0, 'bad original POP')
            pops += 1
        elif kind in (2, 3) and mask == 0xffffffff:
            require(not pops or address not in (0x124, 0x128, 0x12c, *range(0x180, 0x1b0, 4)),
                    'raw completion read after POP')
            reads[address] = value
    expected = {0x124:held['completion'], 0x128:held['appendReadBytes'] & 0xffffffff,
                0x12c:held['appendReadBytes'] >> 32}
    expected.update((0x180+4*i, value) for i, value in enumerate(held['gnx']))
    require(pops == 1 and all(reads.get(a) == v for a,v in expected.items()),
            'actual C did not drain the supplied synthetic raw telemetry')


def rejected_memory(initial, result, description, case):
    if case == 'unchanged':
        shutil.copyfile(initial, result)
        return
    require(case == 'unwritten-gny2', 'unknown deliberate rejection case')
    # This hand-supplied CPU image exists only to reach the original GNY2
    # rejection. It is neither shader interpretation nor an execution result.
    view = next(v for v in description['resources'] if (v['role'],v['index']) == (1,0))
    first, end = view_interval(view)
    patch = struct.pack('<4I', *expected_color(0))*16
    require(end-first == len(patch), 'unexpected fixed image extent')
    with SparseMemory(initial) as memory, result.open('xb') as output:
        pages = sorted(set(memory.pages) | {a & ~4095 for a in range(first,end,4096)} |
                       {(end-1) & ~4095})
        output.write(b'OGMEM001'+struct.pack('<II',len(memory.ranges),len(pages)))
        for a,b,fill in memory.ranges: output.write(struct.pack('<QQII',a,b,fill,0))
        for page in pages:
            data = bytearray(memory.read(page,4096))
            a,b = max(page,first),min(page+4096,end)
            if a < b: data[a-page:b-page] = patch[a-first:b-first]
            output.write(struct.pack('<Q',page)+data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--test-only-synthetic-no-isa',action='store_true',required=True)
    parser.add_argument('--initial',type=Path,required=True)
    parser.add_argument('--results',type=Path,required=True)
    parser.add_argument('--ipc',type=Path,required=True)
    parser.add_argument('--case',choices=('unchanged','unwritten-gny2'),required=True)
    parser.add_argument('--timeout',type=int,required=True)
    args = parser.parse_args(); directory = args.ipc
    d = strict_json(directory/'record-0.source-plan.json')
    profile = strict_json(directory/'profile.json')
    command = capture_identity((directory/'record-0.capture').read_bytes(),profile,d)
    held = dict(schema='opengpu-native-sample-graphics-held-v1',phase='held',
        ownerProfile=SAMPLE_PROFILE,record=0,recordCount=2,resetCount=1,
        sourceQualificationRequired=True,heldCompletionDrained=True,readOnlyGuards=True,
        heldIrqAsserted=True,clockQualified=False,fpsQualified=False,
        commandId=command,completion=0x8700|command,appendReadBytes=64,
        heldLegacyStatus=0,heldStatus=2,heldIrq=3,
        gnx=[0x474e5801,0,0,0,0,0,d['context'],11,13,0,0,0])
    held_bytes(held,0)  # Same strict production codec as the controller and C.
    atomic(directory/'record-0.held.json',(json.dumps(held)+'\n').encode())
    wait(directory/'record-0.retire',directory,args.timeout)
    retirement(directory/'record-0.retire',held)
    args.results.mkdir()
    rejected_memory(args.initial,args.results/'record-0.memory',d,args.case)
    retired = dict(held,schema='opengpu-native-sample-graphics-retired-v1',phase='retired',
        externalDrained=True,retiredIrqAsserted=False,resets=1,resetsBetweenRecords=False,
        independentImagesRequired=True,retiredStatus=1,retiredIrq=1)
    retired_bytes(retired,held,0)
    atomic(directory/'synthetic-peer.json',(json.dumps(dict(schema='opengpu-test-only-synthetic-peer-v1',
        isaExecuted=False,expectControllerRejection=True,case=args.case,actualCRetirementRead=True))+'\n').encode())
    atomic(directory/'record-0.retired.json',(json.dumps(retired)+'\n').encode())
    # Stay alive while exchange checks the bad RAM. No response/cleanup peer
    # exists; the harness cancels the real C only after the original rejection.
    deadline = time.monotonic()+args.timeout
    while not (directory/'error').exists():
        require(time.monotonic() < deadline, 'controller did not reject the deliberately bad result')
        time.sleep(.01)


if __name__ == '__main__': main()
