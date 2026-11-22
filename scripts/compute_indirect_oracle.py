"""Check actual RAM against original ESSL stores without executing instructions."""
import bisect
from math import prod
from dataclasses import dataclass
from pathlib import Path
import re
import struct
from compute_owner_protocol import required_flags, uint
from compute_indirect_sources import GRIDS, PHASES
from compute_counter_source import translate


def require(ok, message):
    if not ok:
        raise ValueError('indirect bytes: ' + message)


def consumer_result(grid):
    seed = 7
    for step in range(grid[0]):
        seed = (seed * 3 + step) & 0xffffffff
    return b''.join(struct.pack('<4I', *grid, index ^ seed)
                    for index in range(prod(grid) * 8))


class Reader:
    def __init__(self, data):
        self.data, self.offset = data, 0

    def take(self, fmt):
        size = struct.calcsize(fmt)
        require(size <= len(self.data) - self.offset, 'truncated source record')
        result = struct.unpack_from(fmt, self.data, self.offset)
        self.offset += size
        return result

    def finish(self):
        require(self.offset == len(self.data), 'trailing source record bytes')


@dataclass(frozen=True)
class Original:
    handle: int
    physical: int
    data: bytes

    @property
    def extent(self):
        return self.physical, self.physical + len(self.data)


class Originals:
    def __init__(self, data):
        r = Reader(data)
        magic, self.command, self.opcode, self.epoch, count = r.take('<8s4I')
        require(magic == b'OGCOB001' and 0 < self.command < 256 and
                self.opcode in (0, 14) and 0 < count < 512, 'invalid original identity header')
        self.objects = {}
        previous = 0
        for _ in range(count):
            handle, pad, physical, size = r.take('<IIQQ')
            require(previous < handle < 512 and not pad and physical and size and
                    not (physical | size) & 4095 and physical + size <= 1 << 32 and
                    size <= len(data) - r.offset, 'invalid original allocation')
            require(all(physical + size <= o.physical or o.extent[1] <= physical
                        for o in self.objects.values()), 'aliased original GEM')
            self.objects[handle] = Original(handle, physical, data[r.offset:r.offset + size])
            r.offset += size
            previous = handle
        r.finish()

    def verify(self, memory, index, opcode, command):
        require((self.epoch, self.opcode, self.command) == (index, opcode, command),
                'original bytes belong to another phase/command')
        for o in self.objects.values():
            require(any((first, end) == o.extent for first, end, _ in memory.ranges),
                    'original GEM lacks its exact allocation')
            require(memory.read(o.physical, len(o.data)) == o.data, 'original GEM bytes differ from actual RAM')


class RetainedMemory:
    """Measured prior DUT RAM plus unchanged permitted overlaps and fresh uploads."""
    def __init__(self, previous, upload, permitted):
        self.previous, self.upload = previous, upload
        additions = []
        for first, end, fill in upload.ranges:
            require(not (first | end) & 4095, 'upload allocation is not page aligned')
            overlap = [r for r in previous.ranges if first < r[1] and r[0] < end]
            if overlap:
                require(overlap == [(first, end, fill)] and (first, end) in permitted,
                        'upload overlaps an unauthorized retained allocation')
                for address in range(first, end, 4096):
                    require(previous.read(address, 4096) == upload.read(address, 4096),
                            'CPU upload changes measured DUT bytes')
            else:
                additions.append((first, end, fill))
        require(upload.ranges, 'empty original upload')
        self.additions = additions
        self.ranges = sorted([*previous.ranges, *additions])
        self.starts = [r[0] for r in self.ranges]
        self.old_starts = [r[0] for r in previous.ranges]

    def read(self, address, size):
        index = bisect.bisect_right(self.old_starts, address) - 1
        if index >= 0 and address < self.previous.ranges[index][1]:
            require(address + size <= self.previous.ranges[index][1], 'cross-allocation retained read')
            return self.previous.read(address, size)
        return self.upload.read(address, size)


def retained_callers(originals, previous):
    # The caller's count/output GEMs outlive all phases. Per-context original
    # shader/kernarg GEMs are normally closed by Gallium after the quiet ACK;
    # their old physical RAM remains guarded, but their public handles need
    # not appear in the next actual live-original export.
    for handle, extent in previous.items():
        require(handle in originals.objects and originals.objects[handle].extent == extent,
                'caller GEM was lost or rebound between phases')


def equal_bytes(before, after, writes=()):
    require(before.ranges == after.ranges, 'physical allocation/fill identity changed')
    for first, end, _ in before.ranges:
        for address in range(first, end, 4096):
            size = min(4096, end - address)
            expected = bytearray(before.read(address, size))
            for target, data in writes:
                low, high = max(address, target), min(address + size, target + len(data))
                if low < high:
                    expected[low-address:high-address] = data[low-target:high-target]
            require(after.read(address, size) == expected,
                    'ESSL output or read-only physical guard differs at 0x%x' % address)


def executable_physical(memory, satp, va):
    # opengpu_binding_map_code currently uses the cached private mapping API,
    # whose exact leaf is V/R/W/X/A/D with policy zero. Snapshot data uses a
    # separate RO/NX/UC2 API; it must not determine code-page permissions.
    require(satp & 0x80000000 and (satp >> 22) & 511, 'code lacks a protected Sv32 owner')
    root = (satp & 0x3fffff) << 12
    first, = struct.unpack('<I', memory.read(root + ((va >> 22) & 1023) * 4, 4))
    require(first & 1023 == 1, 'code Sv32 root is not an exact nonleaf')
    leaf, = struct.unpack('<I', memory.read((first >> 10) * 4096 + ((va >> 12) & 1023) * 4, 4))
    require(leaf & 1023 == 0xcf, 'code differs from the actual cached private mapping')
    return (leaf >> 10) * 4096 + (va & 4095)


def capture(data, opcode, profile):
    r = Reader(data)
    magic, budget, hold, command, count = r.take('<8sQIII')
    require(magic == (b'OGCI0001' if opcode == 14 else b'OGCMP003') and
            0 < budget <= 1000000000 and 8 <= hold <= 4096 and 0 < command < 256 and
            count == 122, 'wrong original capture kind or bounds')
    discovery = [r.take('<II') for _ in range(count)]
    require(discovery == [(w['address'], w['value']) for w in profile['words'] + profile['registers']],
            'capture differs from actual complete DUT discovery')
    args = None
    if opcode == 14:
        fields = r.take('<4I5Q2I2Q6I')
        keys = ('version', 'flags', 'handle', 'context', 'source', 'size', 'offset',
                'snapshot', 'snapshot_size', 'va', 'snapshot_offset', 'kernarg', 'kernarg_size',
                'max_x', 'max_y', 'max_z', 'x', 'y', 'z')
        args = dict(zip(keys, fields))
    count, = r.take('<I')
    require(count <= 4096, 'oversized writable capture')
    writable = [r.take('<QQ') for _ in range(count)]
    count, = r.take('<I')
    require(count < 512, 'oversized empty-context capture')
    inactive = [r.take('<II') for _ in range(count)]
    count, = r.take('<I')
    require(0 < count <= 65536, 'oversized trace')
    actions = [r.take('<4I') for _ in range(count)]
    r.finish()
    staged = {a: v for k, a, v, _ in actions if k == 1}
    require(staged.get(0xc8) == opcode and staged.get(0xc4) == command,
            'original MMIO opcode/command differs')
    return command, args, writable, inactive, actions, staged


def retained_extents(memory, originals, inactive, actions):
    permitted = {o.extent for o in originals.objects.values()}
    def table(address):
        require(any((a, b) == (address, address + 4096) for a, b, _ in memory.ranges),
                'retained table lacks its exact allocation')
        permitted.add((address, address + 4096))
    for k, register, satp, _ in actions:
        if k != 1 or register not in (0x14c, 0x150) or not satp & 0x80000000 or (satp >> 22) & 511:
            continue
        root = (satp & 0x3fffff) << 12
        table(root)
        for word in struct.unpack('<1024I', memory.read(root, 4096)):
            if word & 1023 == 1:
                table((word >> 10) << 12)
    for context, satp in inactive:
        require(0 < context < 512 and satp & 0x80000000 and (satp >> 22) & 511,
                'invalid original inactive context')
        root = (satp & 0x3fffff) << 12
        require(memory.read(root, 4096) == bytes(4096), 'original inactive context is not empty')
        table(root)
    return permitted


class Chain:
    def __init__(self, profile):
        self.profile = profile
        self.index = 0
        self.source = None
        self.originals = {}
        self.callers = {}
        self.outputs = set()
        self.retained = set()

    def ready(self, directory, before):
        from compute_owner_protocol import strict_json
        directory = Path(directory)
        d = strict_json(directory / 'oracle.json')
        program, status, grid_index = PHASES[self.index]
        opcode = 14 if program == 4 else 0
        grid = GRIDS[grid_index]
        required_flags(d, dict(schema='opengpu-compute-indirect-recipe-v1', phase=self.index,
            program=program, opcode=opcode, resultStatus=status, grid=list(grid),
            driverBoundaryNegatives=self.index == 1))
        fields = {'schema', 'phase', 'program', 'opcode', 'resultStatus', 'context', 'grid',
                  'sourceHandle', 'outputHandle', 'codePhysical', 'codeBytes', 'source', 'output',
                  'driverBoundaryNegatives'}
        require(set(d) == fields and 0 < uint(d['context']) < 512, 'foreign recipe fields/context')
        self.description = d
        command, args, writable, inactive, actions, staged = capture(
            (directory / 'record-0.capture').read_bytes(), opcode, self.profile)
        originals = Originals((directory / 'originals.bytes').read_bytes())
        originals.verify(before, self.index, opcode, command)
        retained_callers(originals, self.callers)
        self.originals = {handle: o.extent for handle, o in originals.objects.items()}
        source = originals.objects.get(uint(d['sourceHandle']))
        output = originals.objects.get(uint(d['outputHandle']))
        require(source is not None and output is not None, 'recipe does not name original handles')
        self.callers.update({source.handle: source.extent, output.handle: output.extent})
        def view(value, obj):
            required_flags(value, dict(globalBinding=2, allocationPhysical=obj.physical,
                allocationBytes=8192, offset=0, bytes=8192))
            require(len(obj.data) == 8192 and len(value) == 5, 'unexpected original storage window')
        view(d['source'], source); view(d['output'], output)
        identity = (source.handle, source.extent)
        if self.index == 0:
            require(source.data == b'\x6b' * 8192 and source == output,
                    'first producer source was prefilled with expected counts')
            self.source = identity
        else:
            require(identity == self.source, 'indirect count GEM identity changed')
        if opcode == 0:
            require(source == output, 'producer writes another GEM')
        else:
            require(output.handle != source.handle and output.handle not in self.outputs and
                    output.data == b'\xa7' * 8192, 'consumer output is reused or prefilled')
            self.outputs.add(output.handle)
            require(source.data[4092:4104] == struct.pack('<3I', *grid),
                    'consumer counts differ from preceding actual GPU producer')
            require(args is not None and
                [args[k] for k in ('version', 'flags', 'handle', 'context', 'source', 'size', 'offset')] ==
                [2, 1, source.handle, d['context'], source.physical, 8192, 4092] and
                tuple(args[k] for k in ('x', 'y', 'z')) == grid,
                'private counts/source/actual grid identity differs')
            require(args['va'] == 0xb2000000 and args['snapshot_offset'] == 4092 and
                    args['snapshot_size'] == 8192 and
                    before.read(args['snapshot'] + 4092, 12) == source.data[4092:4104],
                    'private cross-page count snapshot differs')
        satp = staged.get(0x14c, 0)
        require(satp == staged.get(0x150), 'code/data MMU identity differs')
        require([staged.get(0xe0 + 4*n) for n in range(3)] == ([4, 2, 1] if opcode == 14 else [4, 1, 1]) and
                [staged.get(0xd4 + 4*n) for n in range(3)] == (list(grid) if opcode == 14 else [1, 1, 1]),
                'actual original local/grid shape differs')
        if args is not None:
            require([staged.get(a) for a in (0xf4, 0xfc, 0x104, 0xd0)] ==
                    [args['va'], args['snapshot_size'], args['snapshot_offset'], args['va'] + args['snapshot_offset']],
                    'actual source/width/kernarg does not use the same private count window')
            for offset in (4092, 4103):
                require(translate(before, satp, args['va'] + offset, 3) == args['snapshot'] + offset,
                        'private counts lack their exact RO/NX/UC2 mapping')
        words = (directory / 'code.words').read_text().splitlines()
        require(words and all(re.fullmatch('[0-9a-f]{8}', word) for word in words), 'invalid actual code words')
        instructions = tuple(int(word, 16) for word in words)
        if opcode == 14:
            require(any((word & 0x707f) == 0x305b for word in instructions) and
                    any((word & 0x80000fff) == 0x8000006f for word in instructions),
                    'actual indirect consumer lost its loop scope or backward jump')
        code = struct.pack('<%dI' % len(instructions), *instructions)
        require(len(code) == uint(d['codeBytes'], 64) and before.read(uint(d['codePhysical']), len(code)) == code,
                'actual compiler code snapshot differs')
        for offset in range(0, len(code), 4):
            pa = executable_physical(before, satp, staged.get(0xcc, 0) + offset)
            require(before.read(pa, 4) == code[offset:offset + 4], 'staged executable is not actual Mesa code')
        self.current = originals
        self.command, self.context, self.opcode = command, d['context'], opcode
        self.args, self.actions, self.inactive = args, actions, inactive
        self.expected = [(source.physical + 4092, struct.pack('<3I', *grid))] if opcode == 0 else (
            [(output.physical, consumer_result(grid))]
            if self.index in (1, 3) else [])
        return d

    def fault_plan(self):
        require(self.index == 2 and self.args is not None, 'fault is only allowed on its dedicated phase')
        base, offset = self.args['snapshot'], self.args['snapshot_offset']
        return b'OGCIF001' + struct.pack('<QQ', base + (offset & ~63), base + ((offset + 12 + 63) & ~63))

    def returned(self, before, after, originals):
        equal_bytes(before, after, self.expected)
        result = Originals(originals)
        result.verify(after, self.index, self.opcode, self.command)
        require({h: o.extent for h, o in result.objects.items()} == self.originals,
                'returned original handle/allocation set differs')

    def finish(self, memory):
        self.retained = retained_extents(memory, self.current, self.inactive, self.actions)
        self.index += 1
