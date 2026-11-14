"""Independent sample images/GNY2 and exact original physical byte guards.

This checks the fixed fresh 4x4 point recipe, not arbitrary native draws. It
never interprets shader instructions. A supplied OGNSF packet is byte-bound to
private RX; its original ESSL compilation provenance is a separate obligation.
"""
from pathlib import Path
import re
import struct

from compute_owner_oracle import SparseMemory
from compute_owner_protocol import required_flags, uint
from native_sample_owner_protocol import profile_bytes

ROOT = Path(__file__).resolve().parents[1]
CASES = {'sample': (7, 1, 0, 0), 'mask-depth': (7, 1, 1, 1),
         'mask-depth-keep': (7, 1, 1, 1), 'mrt': (3, 15, 0, 0),
         'varying': (18, 8, 0, 0), 'signedness': (5, 1, 0, 0),
         'truncation': (3, 1, 0, 0)}


def require(value, message):
    if not value:
        raise ValueError(message)


def program(data, case):
    require(case in CASES and type(data) is bytes and len(data) >= 68 and
            data[:8] == b'OGNSF001', 'invalid fixed sample program')
    meta = struct.unpack_from('<15I', data, 8)
    require(meta[:5] == (1, 6, 75, 512, 93 * 512) and
            meta[5:9] == CASES[case] and meta[9:14] == (1, 0, 128, 16, 4) and
            0 < meta[14] <= 512 and len(data) == 68 + meta[14] * 4,
            'wrong sample case ABI/capacity/metadata')
    return meta, data[68:]


def capture(data, observed_profile):
    require(type(data) is bytes and data[:8] == b'OGCAP002', 'wrong sample capture magic')
    cursor = 8

    def take(fmt):
        nonlocal cursor
        size = struct.calcsize('<' + fmt)
        require(cursor + size <= len(data), 'truncated sample capture')
        result = struct.unpack_from('<' + fmt, data, cursor)
        cursor += size
        return result

    def section(fmt, maximum):
        count, = take('I')
        require(count <= maximum, 'oversized capture section')
        return [take(fmt) for _ in range(count)]

    budget, hold, identity = take('QII')
    require(0 < budget <= 1000000000 and 8 <= hold <= 4096 and 0 < identity < 256,
            'invalid capture command identity/budget')
    discovery = section('II', 256)
    # Reuse the exact public Python profile contract, not a second permissive
    # table. C++ SampleCapture independently validates the complete enqueue.
    wire = profile_bytes(observed_profile)
    expected = [struct.unpack_from('<II', wire, 12 + i * 8) for i in range(112)]
    expected += [struct.unpack_from('<II', wire, 912 + i * 8) for i in range(5)]
    require(discovery == expected, 'capture/profile discovery mismatch')
    writable = section('QQ', 4096)
    trace = section('IIII', 65536)
    checks = section('III', 256)
    finish = section('II', 256)
    require(cursor == len(data) and len(checks) == 13 and not finish,
            'noncanonical active capture tail')
    registers = {}
    for kind, address, value, mask in trace:
        if kind == 1:
            registers[address] = value
    require(registers.get(0xc4) == identity and registers.get(0xc8) == 7 and
            registers.get(0x11c) == 1, 'capture did not enqueue its native command')
    return identity, registers, writable


class AddressSpace:
    """Walk actual original Sv32 leaves and cross-check published owner maps."""
    def __init__(self, memory, description, satp):
        self.memory = memory
        require(satp >> 31 == 1 and satp >> 22 & 511, 'sample owner requires Sv32/nonzero ASID')
        self.root = (satp & 0x3fffff) << 12
        self.maps = description.get('privateSegments', []) + description.get('resourceMaps', [])
        require(self.maps and type(description.get('pteRanges')) is list, 'missing original maps/PTEs')
        self.ptes = [(uint(r['first']), uint(r['end'])) for r in description['pteRanges']]
        self.check_pte(self.root)

    def check_pte(self, physical):
        require(any(a <= physical and physical + 4096 <= b for a, b in self.ptes),
                'page table is not an actual original owner page')

    def word(self, address):
        return struct.unpack('<I', self.memory.read(address, 4))[0]

    def physical(self, va, count, *, writable=False, executable=False):
        uint(va); uint(count)
        require(count and va + count <= 1 << 32, 'invalid original virtual view')
        first = None
        for at in range(va & ~4095, (va + count + 4095) & ~4095, 4096):
            l0 = self.word(self.root + (at >> 22) * 4)
            require(l0 & 1 and not l0 & 14, 'invalid/nonleaf Sv32 root')
            table = (l0 >> 10) << 12
            self.check_pte(table)
            leaf = self.word(table + (at >> 12 & 1023) * 4)
            require(leaf & 3 == 3 and bool(leaf & 4) == writable and bool(leaf & 8) == executable,
                    'actual owner PTE permission differs from exact view')
            pa = (leaf >> 10) << 12
            if first is None:
                first = pa + (va & 4095)
            require(pa == (first & ~4095) + at - (va & ~4095), 'noncontiguous fixed original view')
        candidates = [m for m in self.maps if uint(m['va']) <= va and
                      va + count <= uint(m['va']) + uint(m['bytes'])]
        require(len(candidates) == 1, 'ambiguous/unbound original owner view')
        m = candidates[0]
        require(first == uint(m['physical']) + va - m['va'] and
                (not executable or m.get('executable') is True) and
                (not writable or m.get('writable') is True), 'map and actual leaf differ')
        self.memory.read(first, count)
        return first

    def read(self, va, count):
        return self.memory.read(self.physical(va, count), count)


def fields(path, prefix):
    text = path.read_text()
    words = dict((k, int(v)) for k, v in re.findall(r'#define ' + prefix + r'(\w+)_WORD (\d+)u', text))
    bits = dict((k, int(v)) for k, v in re.findall(r'#define ' + prefix + r'(\w+)_BITS (\d+)u', text))
    require(words.keys() == bits.keys(), 'incomplete committed descriptor catalog')
    return {k: (words[k], bits[k]) for k in words}


def field(data, catalog, name):
    word, width = catalog[name]
    raw = int.from_bytes(data[word * 4:(word + (width + 31) // 32) * 4], 'little')
    require(raw < 1 << width, 'nonzero descriptor high padding: ' + name)
    return raw


def sample_value(case, slot, sample, samples):
    px = .5 if samples == 1 else .75 if sample & 1 else .25
    py = .5 if samples == 1 else .75 if (sample & 1 if samples == 2 else sample & 2) else .25
    if slot == 4:
        return (1.0 if case == 'mask-depth' else .5 if case == 'mask-depth-keep' else .25,)
    if case == 'mask-depth':
        return (.25,) * 4
    if case == 'mrt':
        return px, py, sample + slot, 1.0
    if case == 'varying' and slot == 3:
        return 61 / 16 + px, 62 / 16 + py, 63 / 16, 4.0
    if slot:
        return (.25,) * 4
    if case == 'sample':
        return sample, px, py, 1 << sample
    if case == 'mask-depth-keep':
        return px, py, .25, 1.0
    if case == 'signedness':
        value = (1 << sample) ^ 0x80000000
        return value, value - (1 << 32), sample, 1.0
    if case == 'truncation':
        return int(px * 3), int(py * 3) + sample, px, py
    return (.25,) * 4


def verify(initial, returned, description, program_data, capture_data, observed_profile):
    required_flags(description, dict(schema='opengpu-native-sample-inputs-v1', gpuExecuted=False,
        width=4, height=4, context=7, **{'pass': 8}, generation=9,
        origin='lower-left-half-integer', vertexWords=420, completionBytes=16384))
    case, samples = description.get('caseId'), uint(description.get('samples'))
    require(samples in (1, 2, 4), 'unsupported sample count')
    meta, code = program(program_data, case)
    for name in ('fragmentMetadata', 'fragmentWords'):
        require(type(description.get(name)) is list, 'missing original program words')
        for value in description[name]:
            uint(value)
    require(description['fragmentMetadata'] == list(meta) and
            description['fragmentWords'] == list(struct.unpack('<%dI' % meta[14], code)),
            'original program metadata/words changed')
    require(returned is None or initial.ranges == returned.ranges, 'returned memory replaced original physical allocations')
    allocations = sorted((uint(r['first']), uint(r['end'])) for r in description.get('allocations', []))
    require(allocations == [(a, b) for a, b, _ in initial.ranges], 'original allocations differ from live driver')
    command, registers, _ = capture(capture_data, observed_profile)
    require(registers.get(0xf4) == description.get('jobVA') and
            registers.get(0x14c) == registers.get(0x150), 'capture did not bind the exact job/address space')
    space = AddressSpace(initial, description, registers.get(0x14c, 0))
    job = space.read(uint(description['jobVA']), 512)
    frame_va, draw_va = struct.unpack_from('<I', job, 21 * 4)[0], struct.unpack_from('<I', job, 16 * 4)[0]
    require(struct.unpack_from('<3I', job) == (0x474e4a02, 512, 2) and
            struct.unpack_from('<I', job, 17 * 4)[0] == 1 and
            frame_va == description['frameVA'] and draw_va == description['drawVA'], 'wrong fresh layered job')
    frame, draw = space.read(frame_va, 2048), space.read(draw_va, 2048)
    fc = fields(ROOT / 'driver/opengpu_native_layered_abi.h', 'OPENGPU_NATIVE_LAYERED_FRAME_')
    dc = fields(ROOT / 'driver/opengpu_native_binned_abi.h', 'OPENGPU_NATIVE_BINNED_DRAW_')
    get = lambda name: field(frame, fc, name)
    require(struct.unpack_from('<3I', frame) == (0x474e4603, 2048, 3) and
            get('LAYERED_LAYERS') == 1 and get('FRAME_BINS_BINS_RESUME') == 0 and
            get('FRAME_BINS_BINS_TILECOUNT') == 1 and
            get('FRAME_STATES_LIMIT') - get('FRAME_STATES_BASE') == 512,
            'oracle requires fresh one-state/one-layer/one-tile recipe')
    state_va = get('FRAME_STATES_BASE')
    require(state_va == description['stateVA'], 'original state VA differs')
    state = space.read(state_va, 512)
    sw = struct.unpack('<128I', state)
    require(sw[29] == meta[7] and sw[30:32] == (15, 4) and sw[39] == {1: 0, 2: 1, 4: 2}[samples] and
            sw[54:57] == (description['fragmentPC'], 16, 6) and sw[82:85] == (1, 7, 1),
            'rewritten GFS sample/depth/MRT policy differs')
    rx = space.physical(uint(description['fragmentPC']), len(code), executable=True)
    require(initial.read(rx, len(code)) == code, 'private RX differs from supplied compiled words')
    space.physical(uint(description['vertexPC']), 420 * 4, executable=True)
    allowed, outputs = [], []

    def view(va, size, written=None):
        require(0 < size <= 49152, 'unbounded writable recipe view')
        pa = space.physical(va, size, writable=True)
        allowed.append((pa, pa + (size if written is None else written)))
        return pa

    images = description.get('images')
    require(type(images) is list and len(images) == 5 and
            sorted(uint(i.get('slot')) for i in images) == list(range(5)), 'missing original image planes')
    for image in images:
        slot = image['slot']; bpp = 4 if slot == 4 else 16
        row = 4 * bpp * samples; size = row * 4
        required_flags(image, dict(viewBytes=size, rowPitch=row, format=3 if slot == 4 else 35,
            clear=0x3f800000 if slot == 4 else 0x3e800000, sampleMode={1: 0, 2: 1, 4: 2}[samples]))
        name = 'FRAME_IMAGE_VIEWS%d_' % slot
        va = get(name + 'BASE')
        require(va == image['viewVA'] and get(name + 'LIMIT') == va + size and
                get(name + 'IMAGEWIDTH') == get(name + 'IMAGEHEIGHT') == 4 and
                get(name + 'ROWPITCH') == row and get(name + 'STORE') == get(name + 'WRITABLE') == 1,
                'actual image descriptor differs from independent view')
        pa = view(va, size)
        require(pa == uint(image['allocationPhysical']) + uint(image['offset']) and
                (image['allocationPhysical'], image['allocationPhysical'] + image['allocationBytes']) in allocations,
                'image is not its exact original GEM view')
        expected = b''.join(struct.pack('<%df' % (bpp // 4), *sample_value(case, slot, sample, samples))
                            for _y in range(4) for _x in range(4) for sample in range(samples))
        require(returned is None or returned.read(pa, size) == expected, 'returned original sample image differs: slot %d' % slot)
        outputs.append((pa, pa + size))
    # Only actual source-owned work areas may change, never whole GEM/PTE/RX
    # allocations by inference. Keep all bytes after the precise prefix guarded.
    for engine in range(4):
        base = get('FRAME_SCRATCH%d_BASE' % engine)
        limit = get('FRAME_SCRATCH%d_LIMIT' % engine)
        require(limit - base == 49152, 'wrong fragment scratch view')
        view(base, 93 * 512)
    vb = field(draw, dc, 'DRAW_VERTEX_GEOMETRY_VERTEX_SCRATCH_BASE')
    vl = field(draw, dc, 'DRAW_VERTEX_GEOMETRY_VERTEX_SCRATCH_LIMIT')
    require(vl - vb == 40960, 'wrong vertex scratch view')
    view(vb, 75 * 512)
    for first_name, last_name, count, written in (
            ('FRAME_BINS_BINS_DIRECTORYBASE', 'FRAME_BINS_BINS_DIRECTORYLIMIT', 64, 64),
            ('FRAME_BINS_BINS_POOL_BASE', 'FRAME_BINS_BINS_POOL_LIMIT', 4096, 48),
            ('FRAME_BINS_PRODUCER_ARENA_BASE', 'FRAME_BINS_PRODUCER_ARENA_LIMIT', 4096, 23 * 64)):
        va = get(first_name)
        require(get(last_name) - va == count, 'unexpected original work area capacity')
        view(va, count, written)
    completion_va = struct.unpack_from('<I', job, 48 * 4)[0]
    completion = description['completionOriginal']
    require(completion_va == description['completionVA'] and completion['viewBytes'] == 16384,
            'completion lease differs')
    pa = view(completion_va, 16384)
    require(pa == uint(completion['allocationPhysical']) + uint(completion['offset']) and
            (completion['allocationPhysical'], completion['allocationPhysical'] + completion['allocationBytes']) in allocations,
            'completion is not its exact original GEM lease')
    if returned is not None:
        from native_sample_gny2_oracle import verify as verify_gny2
        verify_gny2(returned.read(pa, 16384), command, samples)
    allowed.sort()
    require(all(a[1] <= b[0] for a, b in zip(allowed, allowed[1:])), 'overlapping output/work views')
    if returned is None:
        return allowed
    for begin, end, _ in initial.ranges:
        for address in range(begin, end, 4096):
            size = min(4096, end - address)
            before, after = initial.read(address, size), returned.read(address, size)
            kept = bytearray(b'\x01' * size)
            for first, last in allowed:
                lo, hi = max(address, first) - address, min(address + size, last) - address
                if lo < hi:
                    kept[lo:hi] = b'\x00' * (hi - lo)
            require(not any(before[i] != after[i] for i in range(size) if kept[i]),
                    'original source/program/PTE/descriptor/backing guard changed')
