"""Independent ordinary112 source, enqueue, image, GNY2 and byte guards.

This fixed two-owner recipe executes no instruction and trusts no returned
counter/color as its expected value. Compiler bytes and linked reflection are
separate immutable inputs. Calling it on synthetic RAM is a CPU oracle test,
never proof that a real device executed or permission to synthesize an ACK.
"""
import struct

from native_sample_owner_oracle import capture as read_capture
from native_sample_graphics_recipe import verify as verify_records
from native_sample_graphics_source_oracle import verify as verify_sources, uint
from native_sample_graphics_gny2_oracle import (verify as verify_gny2,
    clipped_polygon, record_layout)


def require(ok, text):
    if not ok:
        raise ValueError('ordinary112 owner: ' + text)


def merged(intervals):
    result = []
    for first, end in sorted(intervals):
        require(type(first) is int and type(end) is int and 0 <= first < end <= 1 << 32,
                'invalid physical byte extent')
        if result and first <= result[-1][1]:
            result[-1] = result[-1][0], max(end, result[-1][1])
        else:
            result.append((first, end))
    return result


def view_interval(view):
    first = uint(view['allocationPhysical']) + uint(view['offset'], 64)
    return first, first + uint(view['bytes'])


def writable_views(d):
    """Actual logical writable views, never whole original GEM allocations."""
    return [*d['resources'], d['vertexScratch'], d['completion']]


def checked_pair(descriptions):
    require(type(descriptions) is list and len(descriptions) == 2 and
            [d.get('record') for d in descriptions] == [0, 1], 'two ordered original source plans required')
    left, right = descriptions
    require(all(left[k] == right[k] for k in ('context', 'asid', 'satp', 'rootPhysical')),
            'two original owners must retain one protected context')
    for name in ('jobVA', 'frameVA', 'drawVA', 'stateVA'):
        require(left[name] != right[name], 'two owners reused a private record')
    sets = []
    for d in descriptions:
        views = [(first & ~4095, (end+4095) & ~4095)
                 for first, end in map(view_interval, writable_views(d))]
        views += [(s['physical'], s['physical']+s['allocationBytes']) for s in d['privateSegments']]
        sets.append(merged(views))
    require(all(a[1] <= b[0] or b[1] <= a[0] for a in sets[0] for b in sets[1]),
            'two owners alias mutable/private source pages')


def capture_identity(data, profile, d):
    command, registers, writable = read_capture(data, profile)
    expected = {0xc4: command, 0xc8: 7, 0xec: 0, 0xf4: d['jobVA'],
        0xf8: d['jobVA'] + 512, 0xfc: 512, 0x100: 0,
        0x11c: 1, 0x14c: d['satp'], 0x150: d['satp']}
    require(all(registers.get(k) == v for k, v in expected.items()),
            'capture changed the exact private job/context/bounds or ordinary flags')
    # read_capture already checked the entire section envelope/EOF. The
    # actual producer's final checks are canonical 13 triples then count0.
    start = len(data) - 4 - 13*12 - 4
    require(struct.unpack_from('<I', data, start)[0] == 13, 'missing held predicates')
    checks = [struct.unpack_from('<III', data, start+4+12*i) for i in range(13)]
    expected_checks = [(0x124, 0xffffffff, 0x8700 | command)]
    for index in range(12):
        value = (0x474e5801 if index == 0 else d['context'] if index == 6 else
                 11 if index == 7 else 13 if index == 8 else 0)
        expected_checks.append((0x180+4*index, 0 if index in (2, 3) else 0xffffffff, value))
    require(checks == expected_checks, 'capture changed completion fault/identity predicates')
    previous = 0
    for first, end in writable:
        require(previous <= first < end <= 1 << 32, 'unordered/overlapping capture write permission')
        previous = end
    # Hardware mappings are page envelopes; precise byte guards below remain
    # narrower. Derive these envelopes from checked logical source roles.
    pages = [(first & ~4095, (end+4095) & ~4095)
             for first, end in map(view_interval, writable_views(d))]
    require(merged(writable) == merged(pages), 'capture grants a missing/extraneous writable page')
    return command


def preflight(initial, descriptions, programs, reflection, profile, first_capture):
    require(type(descriptions) is list and len(descriptions) == 2 and
            [d.get('record') for d in descriptions] == [0, 1], 'two ordered original source plans required')
    colors = []
    for d in descriptions:
        verify_records(initial, d, programs, reflection)
        colors.append(verify_sources(initial, d, programs, reflection))
    require(colors[0] != colors[1] and all(all(color) for color in colors),
            'fixed original patches must produce distinct nonzero colors')
    checked_pair(descriptions)
    return capture_identity(first_capture, profile, descriptions[0])


def writable_bytes(d):
    """Conservative ABI work prefixes with exact bin/pool/image guards.

    All four FS scratch prefixes retain their full supported 128-thread ABI;
    no scheduler choice or returned pixel/counter narrows expected permission.
    """
    parts, lines = len(clipped_polygon()) - 2, record_layout()[1]
    used = {(1, 0): 256, (3, 0): parts*lines*64, (5, 0): 64, (4, 0): 64,
            **{(6, i): 8192 for i in range(4)}}
    intervals = []
    for view in d['resources']:
        key = view['role'], view['index']
        first, end = view_interval(view)
        require(key in used and 0 < used[key] <= end-first, 'unsupported work prefix')
        intervals.append((first, first+used[key]))
    intervals += [view_interval(d['vertexScratch']), view_interval(d['completion'])]
    return merged(intervals)


def guards(previous, returned, allowed):
    require(previous.ranges == returned.ranges, 'returned memory replaced original physical allocations')
    allowed = merged(allowed)
    for first, end, _ in previous.ranges:
        for address in range(first, end, 4096):
            size = min(4096, end-address)
            old, new = previous.read(address, size), returned.read(address, size)
            keep = bytearray(b'\x01')*size
            for lo, hi in allowed:
                lo, hi = max(lo, address)-address, min(hi, address+size)-address
                if lo < hi: keep[lo:hi] = bytes(hi-lo)
            require(not any(a != b for a, b, kept in zip(old, new, keep) if kept),
                    'source/PTE/descriptor/original allocation or work-prefix guard changed')


def verify_result(initial, returned, descriptions, programs, reflection, profile,
                  capture_data, record, previous=None):
    """Validate one result before its execution response; no cleanup ACK."""
    require(type(record) is int and record in (0, 1), 'record must be zero or one')
    require((previous is None) == (record == 0), 'record1 needs the first observed RAM snapshot')
    require(type(descriptions) is list and len(descriptions) == 2 and
            [d.get('record') for d in descriptions] == [0, 1], 'wrong original owner order')
    d = descriptions[record]
    command = capture_identity(capture_data, profile, d)
    for original in descriptions:
        verify_records(initial, original, programs, reflection, returned=returned)
    checked_pair(descriptions)
    verify_sources(initial, d, programs, reflection, returned=returned, image=True)
    if record == 1:
        verify_sources(initial, descriptions[0], programs, reflection, returned=previous, image=True)
        verify_sources(initial, descriptions[0], programs, reflection, returned=returned, image=True)
    completion, end = view_interval(d['completion'])
    require(end-completion == 16384, 'wrong original GNY2 lease')
    verify_gny2(returned.read(completion, 16384), command_id=command, context=d['context'])
    guards(initial if previous is None else previous, returned, writable_bytes(d))
    return command
