"""Independent fixed ABI1 source/image checks; no shader VM, GNY2 or GPU claim.

Compiler words/reflection are separate immutable caller inputs, never authorized
by the sidecar. Synthetic CPU fixtures can test this checker but cannot establish
original-Mesa provenance or device execution.
"""
from functools import lru_cache
import json
from pathlib import Path
import struct


def require(ok, text):
    if not ok:
        raise ValueError('graphics source: ' + text)


def uint(value, bits=32):
    require(type(value) is int and 0 <= value < 1 << bits, 'noncanonical integer')
    return value


def value(binding, word):
    return ((0x71005013 + binding * 0x10203 + word * 0x9e3779b9) ^
            ((binding + 13) << (word % 23))) & 0xffffffff


def uniform_bytes(record, binding):
    require(record in (0, 1) and (binding in (40, 41) or binding in range(12) or binding in range(24, 36)),
            'wrong original binding/record')
    words = [value(binding, word) for word in range(1024 if binding in (40, 41) else 4096)]
    if record == 1 and binding in (35, 40):
        words[0] ^= 0x12345678 if binding == 35 else 0xfedc1234
    return struct.pack('<%dI' % len(words), *words)


def expected_color(record):
    sums = []
    for stage in range(2):
        words = struct.unpack('<1024I', uniform_bytes(record, 40 + stage))
        result = words[0] ^ words[1023]
        # Source ESSL declaration/body order, not linked slot/table order.
        for binding in range(stage * 24, stage * 24 + 12):
            block = struct.unpack('<4096I', uniform_bytes(record, binding))
            result = ((result * 33) ^ block[0] ^ block[4095]) & 0xffffffff
        sums.append(result)
    return (sums[0] ^ sums[1],) * 4


class Space:
    def __init__(self, memory, description):
        self.memory, self.description = memory, description
        root, asid, satp = (uint(description[k]) for k in ('rootPhysical', 'asid', 'satp'))
        require(root % 4096 == 0 and 0 < asid < 512 and satp == 0x80000000 | asid << 22 | root >> 12,
                'wrong actual Sv32 identity')
        self.root = root
        self.maps = description['privateSegments'] + description['resourceMaps']
        self.ptes = [(uint(p['first']), uint(p['end'])) for p in description['pteRanges']]
        require(len(self.ptes) == len(set(self.ptes)), 'duplicate PTE allocation')
        self.entries = {}
        for entry in description['pteEntries']:
            at = uint(entry['va'])
            require(at % 4096 == 0 and at not in self.entries, 'duplicate/unaligned PTE observation')
            self.entries[at] = entry
        self.page(root)
        pages = set()
        for mapping in self.maps:
            va, size, physical = (uint(mapping[k]) for k in ('va', 'bytes', 'physical'))
            require(size > 0 and va % 4096 == physical % 4096, 'invalid owner mapping')
            mapped = set(range(va & ~4095, (va + size + 4095) & ~4095, 4096))
            require(not pages.intersection(mapped), 'overlapping owner page mappings')
            pages.update(mapped)
            self.physical(va, size, writable=bool(mapping.get('writable', False)),
                          executable=bool(mapping.get('executable', False)))
        require(pages == self.entries.keys(), 'missing/extraneous PTE observations')

    def page(self, address):
        require(any(a <= address and address + 4096 <= b for a, b in self.ptes), 'unbound actual PTE page')

    def word(self, address):
        return struct.unpack('<I', self.memory.read(address, 4))[0]

    def physical(self, va, size, writable=False, executable=False):
        uint(va); uint(size)
        require(size and va + size <= 1 << 32, 'wrong virtual extent')
        candidates = [m for m in self.maps if uint(m['va']) <= va and va + size <= uint(m['va']) + uint(m['bytes'])]
        require(len(candidates) == 1, 'ambiguous/missing actual owner mapping')
        m = candidates[0]
        require(bool(m.get('writable', False)) == writable and bool(m.get('executable', False)) == executable,
                'map permission differs from source role')
        first = uint(m['physical']) + va - m['va']
        for at in range(va & ~4095, (va + size + 4095) & ~4095, 4096):
            root_address = self.root + (at >> 22) * 4
            root = self.word(root_address)
            require(root & 1023 == 1, 'noncanonical nonleaf Sv32 entry')
            table = (root >> 10) << 12; self.page(table)
            leaf_address = table + (at >> 12 & 1023) * 4
            leaf = self.word(leaf_address)
            # Driver leaves are V/R/A, D iff W, X iff executable, non-global/non-user.
            flags = 0x243 | (0x84 if writable else 0) | (8 if executable else 0)
            require(leaf & 1023 == flags, 'actual PTE permission/reserved bits differ')
            require((leaf >> 10) << 12 == (first & ~4095) + at - (va & ~4095), 'wrong physical source mapping')
            require(self.entries.get(at) == dict(va=at, rootAddress=root_address, rootWord=root,
                    leafAddress=leaf_address, leafWord=leaf), 'sidecar PTE observation differs from RAM')
        self.memory.read(first, size)
        return first

    def read(self, va, size, **permissions):
        return self.memory.read(self.physical(va, size, **permissions), size)


def original(memory, view, size, offset, seen):
    handle, physical, allocation = (uint(view[k]) for k in ('handle', 'allocationPhysical', 'allocationBytes'))
    require(handle and physical % 4096 == 0 and allocation % 4096 == 0 and allocation > 0 and
            view['bytes'] == size and view['offset'] == offset and offset + size <= allocation,
            'original GEM window differs from fixed recipe')
    require(any(a == physical and b == physical + allocation for a, b, *_ in memory.ranges),
            'original allocation absent from actual union memory')
    identity = physical, allocation
    require(handle not in seen or seen[handle] == identity, 'one handle names different original backing')
    require(all(h == handle or old[0] != physical for h, old in seen.items()), 'different handles alias original backing')
    seen[handle] = identity
    return memory.read(physical + offset, size)


def reflection_bindings(reflection):
    require(reflection.get('schema') == 'opengpu-linked-graphics-reflection-v1' and len(reflection.get('stages', [])) == 2,
            'missing independent original compiler reflection')
    result = []
    for stage, item in enumerate(reflection['stages']):
        bindings = item.get('bindings')
        require(item.get('stage') == stage and item.get('defaultComponents') == 1024 and type(bindings) is list and
                all(type(v) is int for v in bindings) and sorted(bindings) == list(range(stage * 24, stage * 24 + 12)),
                'compiler reflection does not cover all original blocks')
        result.append(bindings)
    return result


@lru_cache(maxsize=1)
def descriptor_schemas():
    root = Path(__file__).resolve().parents[1] / 'docs/abi'
    layered = json.loads((root / 'native-layered-graphics-v2.json').read_text())
    ordinary = json.loads((root / 'native-binned-graphics-v1.json').read_text())
    fragment = json.loads((root / 'typed-fragment-state-v1.json').read_text())
    return dict(job=layered['job'], frame=layered['frame'], draw=ordinary['draw'], state=fragment)


def reachable_sources(space, d):
    """Only source-link admission, not a complete descriptor/GNY2 oracle."""
    schemas, records = descriptor_schemas(), {}
    for name, schema in schemas.items():
        address, size = uint(d[name + 'VA']), schema['bytes']
        segments = [p for p in d['privateSegments'] if p['va'] == address]
        require(len(segments) == 1 and segments[0]['bytes'] == size and not segments[0]['executable'],
                'source descriptor is not its own exact private RO segment')
        words = struct.unpack('<%dI' % (size // 4), space.read(address, size))
        header = (int(schema['magic'], 16), size, schema.get('version', 1))
        header += ((4, d['context'], 11, 13, 0) if name == 'state' else (16, 16, 16, 4, 4))
        require(words[:8] == header, 'source descriptor header/ABI/identity differs')
        records[name] = words, {f['name']: f for f in schema['fields']}
    def equals(record, field, expected):
        words, fields = records[record]
        f = fields[field]; count = (f['bits'] + 31) // 32
        actual = sum(words[f['word'] + i] << (32 * i) for i in range(count))
        require(actual < 1 << f['bits'] and actual == expected, 'unreachable source link: ' + field)
    for field, expected in [('job.drawBase', d['drawVA']), ('job.drawLimit', d['drawVA'] + 2048),
                            ('job.drawCount', 1), ('job.drawStride', 2048),
                            ('job.frameBase', d['frameVA']), ('job.frameLimit', d['frameVA'] + 2048)]:
        equals('job', field, expected)
    for record, prefix in [('job', 'job.identity.'), ('frame', 'frame.states.identity.'),
                           ('draw', 'draw.vertex.geometry.vertex.input.identity.'), ('state', 'fragment.identity.')]:
        for field, expected in [('context', d['context']), ('pass', 11), ('generation', 13)]:
            equals(record, prefix + field, expected)
    for field, expected in [('frame.states.base', d['stateVA']), ('frame.states.limit', d['stateVA'] + 512),
                            ('frame.states.version', 1), ('frame.version', 3)]:
        equals('frame', field, expected)
    equals('draw', 'draw.state', 0)
    for record, prefix, stage in [('draw', 'draw.vertex.geometry.vertex.', 0), ('state', 'fragment.', 1)]:
        equals(record, prefix + 'version', 1)
        equals(record, prefix + 'shaderPc', d['programs'][stage]['privateVA'])
        equals(record, prefix + 'uniforms.version', 1)
        root = next(u['rootVA'] for u in d['uniforms'] if (u['stage'], u['slot']) == (stage, 0))
        equals(record, prefix + 'uniforms.address', root)


def _sources(memory, d, programs, reflection):
    require(d.get('schema') == 'opengpu-native-sample-graphics-source-v1' and
            d.get('recipe') == 'original-abi1-uniform-triangle-v1' and d.get('shaderABI') == 1 and
            d.get('gpuExecution') is False,
            'wrong versioned source sidecar')
    record = uint(d['record']); require(record in (0, 1), 'wrong source record')
    require((d['pass'], d['generation'], d['width'], d['height'], d['samples']) == (11, 13, 4, 4, 1) and uint(d['context']) > 0,
            'wrong ordinary graphics recipe')
    binding_order = reflection_bindings(reflection)
    require(len(programs) == 2 and all(type(p) is bytes for p in programs) and
            [len(p) for p in programs] == [308 * 4, 301 * 4], 'missing original compiler words')
    require(len(d['programs']) == 2 and len(d['uniforms']) == 26, 'incomplete two-stage source inventory')
    space, seen = Space(memory, d), {}
    require({p['va'] for p in d['privateSegments'] if p.get('executable')} ==
            {p['privateVA'] for p in d['programs']}, 'unexpected private executable segment')
    for stage, p in enumerate(d['programs']):
        require(p['stage'] == stage and p['layout'] == ([0, 128, 1, 1, 0] if stage == 0 else [1, 128, 0, 1, 1]) and
                p['entryOffset'] == 0 and p['words'] == list(struct.unpack('<%dI' % (len(programs[stage]) // 4), programs[stage])),
                'compiler code/layout changed')
        require(original(memory, p, len(programs[stage]), 0, seen) == programs[stage] and
                space.read(p['privateVA'], len(programs[stage]), executable=True) == programs[stage],
                'original/private RX differs from independent compiler bytes')
        segments = [s for s in d['privateSegments'] if s['va'] == p['privateVA']]
        require(len(segments) == 1 and segments[0]['bytes'] == len(programs[stage]) and
                segments[0]['allocationBytes'] == (len(programs[stage]) + 4095) & ~4095,
                'wrong exact private RX extent')
        allocation = segments[0]['allocationBytes']
        require(memory.read(segments[0]['physical'], allocation) == programs[stage] + bytes(allocation - len(programs[stage])),
                'private RX allocation tail changed')
    uniforms = {}
    for u in d['uniforms']:
        key = u['stage'], u['slot']
        require(key not in uniforms and key[0] in (0, 1) and type(key[1]) is int and 0 <= key[1] <= 12,
                'duplicate/wrong linked uniform slot')
        uniforms[key] = u
    require(len(uniforms) == 26, 'incomplete uniform slots')
    root = uint(uniforms[0, 0]['rootVA']); require(root % 4096 == 0, 'unaligned private uniform segment')
    # Independent checked source-plan packing: two roots, 24 table entries,
    # two default records; original data slices are aligned to 256 bytes.
    copies, defaults, mapping, next_data = [], [], [[], []], 1792
    for stage in range(2):
        for slot in range(1, 13):
            u = uniforms[stage, slot]; binding = binding_order[stage][slot - 1]
            require(u['globalBinding'] == binding, 'linked slot differs from original compiler reflection')
            expected = uniform_bytes(record, binding)
            offset = 68 if record == 1 and binding == 35 else 4 + 64 * (binding % 16)
            require(original(memory, u, 16384, offset, seen) == expected, 'original UBO byte differs from fixed inputs')
            key = u['handle'], u['offset'], u['bytes']
            indices = [i for i, item in enumerate(copies) if item[0] == key]
            if indices:
                index = indices[0]; copies[index][3] |= 1 << stage
            else:
                index = len(copies); next_data = (next_data + 255) & ~255
                copies.append([key, next_data, expected, 1 << stage]); next_data += len(expected)
            mapping[stage].append(index)
        u = uniforms[stage, 0]
        require(u['globalBinding'] == 40 + stage, 'default source identity changed')
        expected = uniform_bytes(record, 40 + stage)
        require(original(memory, u, 4096, 0, seen) == expected, 'original default byte differs from fixed inputs')
        next_data = (next_data + 255) & ~255; defaults.append((next_data, expected)); next_data += len(expected)
    require(len(copies) == 24 and len({u['handle'] for u in uniforms.values()}) == 26,
            'fixed full24 UBO/two-default recipe was aliased or reduced')
    length = (next_data + 63) & ~63
    expected = bytearray(length)
    def put(offset, words):
        expected[offset:offset + 4 * len(words)] = struct.pack('<%dI' % len(words), *words)
    identity = [d['context'], 11, 13]
    for stage in range(2):
        low = sum(mapping[stage][i] << (5 * i) for i in range(6))
        high = sum(mapping[stage][6 + i] << (5 * i) for i in range(6))
        put(stage * 64, [0x47555201, 64, 1, *identity, stage, 4095, low, high,
                        root + 128, root + 1664, 0, root + 1664 + stage * 64, 1, 0])
    records = [(128 + i * 64, i, item[1], item[2], item[3]) for i, item in enumerate(copies)]
    records += [(1664 + stage * 64, 31, offset, data, 1 << stage) for stage, (offset, data) in enumerate(defaults)]
    for at, index, offset, data, stages in records:
        put(at, [0x47554201, 64, 1, *identity, index, stages, root + offset,
                 root + offset + len(data), 0, root + offset,
                 root + ((offset + len(data) + 63) & ~63), 0, 1, 0])
        expected[offset:offset + len(data)] = data
    require(space.read(root, length) == expected, 'canonical GUR/GUB, mapping, padding or private uniform bytes differ')
    segment = [p for p in d['privateSegments'] if p['va'] == root]
    require(len(segment) == 1 and segment[0]['bytes'] == length and
            segment[0]['allocationBytes'] == (length + 4095) & ~4095,
            'wrong canonical private uniform segment extent')
    require(memory.read(segment[0]['physical'], segment[0]['allocationBytes']) ==
            expected + bytes(segment[0]['allocationBytes'] - length), 'nonzero private uniform allocation padding')
    for stage in range(2):
        for slot in range(13):
            u = uniforms[stage, slot]
            index = mapping[stage][slot - 1] if slot else 31
            gub = root + 128 + index * 64 if slot else root + 1664 + stage * 64
            offset, data = (copies[index][1:3] if slot else defaults[stage])
            require((u['rootVA'], u['bindingVA'], u['bindingIndex'], u['privateVA']) ==
                    (root + stage * 64, gub, index, root + offset), 'observed GUR/GUB window differs from independent packing')
            require(space.read(u['privateVA'], len(data)) == data, 'private uniform window byte differs')
    sizes = {(1, 0): 256, (3, 0): 65536, (5, 0): 4096, (4, 0): 4096, **{(6, i): 8192 for i in range(4)}}
    roles = {}
    for v in d['resources']:
        key = uint(v['role']), uint(v['index']); require(key in sizes and key not in roles, 'wrong/duplicate frame role')
        offset = 64 if key == (1, 0) else 0
        original(memory, v, sizes[key], offset, seen)
        require(space.physical(v['viewVA'], sizes[key], writable=True) == v['allocationPhysical'] + offset,
                'frame original/private map differs')
        roles[key] = v
    require(roles.keys() == sizes.keys(), 'missing frame/scratch roles')
    for name, size, offset, writable in [('vertexScratch', 7680, 0, True), ('vertexInput', 48, 4, False), ('completion', 16384, 0, True)]:
        v = d[name]; original(memory, v, size, offset, seen)
        require(space.physical(v['viewVA'], size, writable=writable) == v['allocationPhysical'] + offset,
                'source/scratch/completion view differs')
    triangle = struct.pack('<12f', -1, -1, 0, 1, 3, -1, 0, 1, -1, 3, 0, 1)
    require(space.read(d['vertexInput']['viewVA'], 48) == triangle, 'original triangle positions changed')
    reachable_sources(space, d)
    return expected_color(record), roles[1, 0]


def verify(initial, description, programs, reflection, returned=None, *, image=False):
    """Check sources against fixed input math, never initial bytes as expected.

    Optional image checking is an oracle capability, not a claim of GPU output.
    This function neither reads GNY2 counters nor authorizes resource cleanup.
    """
    color, view = _sources(initial, description, programs, reflection)
    first, size = view['allocationPhysical'], view['allocationBytes']
    clear = bytes([0x5a]) * 384 + bytes([0xa5]) * (size - 384)
    require(initial.read(first, size) == clear, 'original image clear/guard pattern changed')
    if returned is not None:
        require(initial.ranges == returned.ranges, 'returned memory replaced original allocations')
        _sources(returned, description, programs, reflection)
    if image:
        require(returned is not None, 'image oracle needs returned memory')
        expected = bytearray(clear)
        expected[64:320] = struct.pack('<4I', *color) * 16
        require(returned.read(first, size) == expected, 'ABI1 RGBA32UI image/prefix/suffix guard differs')
    return color
