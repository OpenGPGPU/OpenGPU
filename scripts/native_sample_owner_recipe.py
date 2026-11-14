"""Independent canonical records for the fixed ordinary native sample recipe.

Build expected words from the committed ABI catalog, fixed input mathematics
and retained original plan/map identities. Never copy the initial descriptor
bytes into the expected record, and never execute a shader/producer as oracle.
"""
from functools import lru_cache
from pathlib import Path
import re
import struct

ROOT = Path(__file__).resolve().parents[1]


def require(ok, message):
    if not ok:
        raise ValueError('sample recipe: ' + message)


def number(value, bits=32):
    require(type(value) is int and 0 <= value < 1 << bits, 'noncanonical integer')
    return value


@lru_cache(None)
def catalog(filename, prefix):
    text = (ROOT / 'driver' / filename).read_text()
    words = dict((n, int(v)) for n, v in re.findall(r'#define ' + prefix + r'(\w+)_WORD (\d+)u', text))
    widths = dict((n, int(v)) for n, v in re.findall(r'#define ' + prefix + r'(\w+)_BITS (\d+)u', text))
    require(widths.keys() <= words.keys(), 'incomplete public ABI catalog')
    # GFS also publishes unrelated capability/header aliases without BITS.
    # Canonical raw headers below bind those separately; only full fields
    # participate in named setters. All record words are still compared.
    return {name: (words[name], widths[name]) for name in widths}


class Record:
    def __init__(self, words, filename, prefix, header):
        self.words = [0] * words  # Every unspecified/reserved/high word is zero.
        self.fields = catalog(filename, prefix)
        self.words[:len(header)] = header

    def set(self, **values):
        for name, value in values.items():
            word, width = self.fields[name]
            self.put(word, value, width)
        return self

    def put(self, word, value, width=32):
        number(value, width)
        for part in range((width + 31) // 32):
            self.words[word + part] = value >> (32 * part) & 0xffffffff

    def bytes(self):
        return struct.pack('<%dI' % len(self.words), *self.words)

    def compare(self, space, va, name):
        actual = space.read(number(va), len(self.words) * 4)
        expected = self.bytes()
        if actual != expected:
            for word, (a, b) in enumerate(zip(struct.unpack('<%dI' % len(self.words), actual), self.words)):
                require(a == b, '%s word %d differs: %#x != %#x' % (name, word, a, b))


def rv_i(opcode, rd, function, rs, immediate):
    return (immediate & 4095) << 20 | rs << 15 | function << 12 | rd << 7 | opcode


def rv_add(rd, left, right):
    return right << 20 | left << 15 | rd << 7 | 0x33


def vector_memory(store, base, vector):
    # Unit-stride 32-bit elements, vm=1, no segment/index/mask extensions.
    return 1 << 25 | base << 15 | 6 << 12 | vector << 7 | (0x27 if store else 7)


def vertex_program():
    """RV32/SoA native VS: position attribute + 64 distinct FP32 constants."""
    code = [rv_i(0x13, 7, 1, 8, 2), rv_add(7, 1, 7), rv_i(0x57, 10, 7, 0, 16)]

    def address(destination, array):
        code.extend((rv_i(0x13, 10, 0, 0, array), rv_i(0x13, 10, 1, 10, 9),
                     rv_add(destination, 7, 10)))

    # Input ABI1: four position attributes, then three identity input arrays.
    # Outputs: four position arrays, then slot-major 16*4 varying components.
    for component in range(4):
        address(5, component)
        code.append(vector_memory(False, 5, 1))
        address(6, 7 + component)
        code.append(vector_memory(True, 6, 1))
    for slot in range(16):
        for component in range(4):
            value = (4 * slot + component + 1) / 16
            bits, = struct.unpack('<I', struct.pack('<f', value))
            high = (bits + 2048) & 0xfffff000
            code.append(high | 12 << 7 | 0x37)  # LUI x12: exact float bits.
            if bits & 4095:
                code.append(rv_i(0x13, 12, 0, 12, bits & 4095))
            code.append(0x17 << 26 | 1 << 25 | 12 << 15 | 4 << 12 | 1 << 7 | 0x57)  # vmv.v.x v1,x12
            address(6, 11 + 4 * slot + component)
            code.append(vector_memory(True, 6, 1))
    code.append(0x30500073)  # CEASE after every output store.
    require(len(code) == 420, 'independent VS length')
    return struct.pack('<420I', *code)


def original_view(space, description, view, size):
    """Bind plan handle -> original GEM -> exact physical view -> real PTE."""
    require(type(view) is dict, 'missing original plan view')
    handle, offset = number(view.get('handle')), number(view.get('offset'), 64)
    require(handle and offset == 0 and view.get('viewBytes') == size, 'wrong fixed original view size/offset')
    originals = [o for o in description.get('originalObjects', []) if o.get('handle') == handle]
    require(len(originals) == 1, 'nonunique original handle')
    original = originals[0]
    pa, allocation = number(view.get('allocationPhysical')), number(view.get('allocationBytes'))
    require(original.get('physical') == pa and original.get('bytes') == allocation and
            original.get('visible') is True and number(original.get('references')) >= 2 and
            allocation == (size + 4095) & ~4095 and pa % 4096 == 0,
            'plan does not match retained page-rounded original GEM')
    require((pa, pa + allocation) in [(r['first'], r['end']) for r in description.get('allocations', [])],
            'original GEM outside actual allocation inventory')
    va = number(view.get('viewVA'))
    require(va % 4096 == 0 and space.physical(va, size, writable=True) == pa,
            'plan view does not match actual owner mapping/PTE')
    return va


def records(space, description, meta, code):
    """Construct canonical bytes without reading the records under test."""
    require(len(meta) == 15 and tuple(meta[:5]) == (1, 6, 75, 512, 47616) and
            tuple(meta[9:14]) == (1, 0, 128, 16, 4) and meta[7] in (0, 1) and
            type(code) is bytes and len(code) == meta[14] * 4,
            'wrong ordinary compiler layout')
    require((description.get('width'), description.get('height'), description.get('context'),
             description.get('pass'), description.get('generation'), description.get('vertexWords')) == (4, 4, 7, 8, 9, 420),
            'wrong fixed geometry or identity')
    samples = number(description.get('samples'))
    require(samples in (1, 2, 4), 'unsupported sample count')
    mode = {1: 0, 2: 1, 4: 2}[samples]
    plans = description.get('frameResources')
    require(type(plans) is list and len(plans) == 12, 'missing original frame resource plans')
    expected_sizes = {(1, i): 16 * samples * (4 if i == 4 else 16) for i in range(5)}
    # Above: width*height*samples*bytes-per-pixel; no page-rounded image view.
    expected_sizes.update({(3, 0): 4096, (5, 0): 64, (4, 0): 4096})
    expected_sizes.update({(6, i): 49152 for i in range(4)})
    resources, handles = {}, set()
    for plan in plans:
        key = (number(plan.get('role')), number(plan.get('index')))
        require(key in expected_sizes and key not in resources and plan.get('handle') not in handles,
                'duplicate/unknown original frame role or aliased handle')
        handles.add(plan['handle'])
        resources[key] = original_view(space, description, plan, expected_sizes[key])
    require(resources.keys() == expected_sizes.keys(), 'incomplete frame resource roles')
    vs = description.get('vertexScratch')
    require(type(vs) is dict and vs.get('handle') not in handles, 'aliased VS scratch handle')
    scratch = original_view(space, description, vs, 40960)
    completion = dict(description.get('completionOriginal', {}), viewVA=description.get('completionVA'))
    require(completion.get('handle') not in handles | {vs.get('handle')}, 'aliased completion handle')
    completion_va = original_view(space, description, completion, 16384)
    images = {i.get('slot'): i for i in description.get('images', [])}
    require(len(images) == 5, 'wrong attachment inventory')
    for index in range(5):
        require(images[index].get('viewVA') == resources[1, index], 'image/plan VA mismatch')
        require(original_view(space, description, images[index], expected_sizes[1, index]) == resources[1, index],
                'image/plan original mismatch')
    job_va, frame_va, draw_va, state_va, fs_pc, vs_pc = [number(description.get(k)) for k in
        ('jobVA', 'frameVA', 'drawVA', 'stateVA', 'fragmentPC', 'vertexPC')]
    require(len({job_va, frame_va, draw_va, state_va, fs_pc, vs_pc}) == 6, 'aliased private records/code')
    vs_code = vertex_program()
    require(space.memory.read(space.physical(vs_pc, len(vs_code), executable=True), len(vs_code)) == vs_code,
            'VS RX differs from independent fixed native program')
    require(space.memory.read(space.physical(fs_pc, len(code), executable=True), len(code)) == code,
            'FS RX differs from original compiled bytes')
    header = (16, 16, 16, 4, 4)
    job = Record(128, 'opengpu_native_layered_abi.h', 'OPENGPU_NATIVE_LAYERED_JOB_',
                 [0x474e4a02, 512, 2, *header])
    job.set(JOB_DRAWBASE=draw_va, JOB_DRAWLIMIT=draw_va + 2048, JOB_DRAWCOUNT=1, JOB_DRAWSTRIDE=2048,
            JOB_FRAMEBASE=frame_va, JOB_FRAMELIMIT=frame_va + 2048, JOB_IDENTITY_CONTEXT=7,
            JOB_IDENTITY_GENERATION=9, JOB_IDENTITY_PASS=8, COMPLETION_BASE=completion_va,
            COMPLETION_LIMIT=completion_va + 16384, COMPLETION_ALLOCATIONBASE=completion_va,
            COMPLETION_ALLOCATIONLIMIT=completion_va + 16384, COMPLETION_WRITABLE=1)
    frame = Record(512, 'opengpu_native_layered_abi.h', 'OPENGPU_NATIVE_LAYERED_FRAME_',
                   [0x474e4603, 2048, 3, *header])
    # 32KiB resident store, 4 RGBA32F MRTs + D32F: largest legal tile is 16
    # at 1x and 8 at 2x/4x. This derives layout from sizes, not frame bytes.
    edge = 16 if 16 * 16 * samples * 68 <= 32768 else 8
    texels = edge * edge * samples
    for prefix in ('FRAME_BINS_PRODUCER_ARENA_IDENTITY_', 'FRAME_BINS_BINS_POOL_IDENTITY_',
                   'FRAME_STATES_IDENTITY_', 'FRAME_IMAGE_PIXELS_COLORS_IDENTITY_'):
        frame.set(**{prefix + 'CONTEXT': 7, prefix + 'GENERATION': 9, prefix + 'PASS': 8})
    frame.set(FRAME_VERSION=3, FRAME_BINS_PRODUCER_VERSION=1, FRAME_BINS_PRODUCER_ARENA_VERSION=1,
              FRAME_BINS_PRODUCER_SCREENWIDTH=4, FRAME_BINS_PRODUCER_SCREENHEIGHT=4,
              FRAME_BINS_PRODUCER_TILELOG2=4 if edge == 16 else 3, FRAME_BINS_BINS_VERSION=3,
              FRAME_BINS_BINS_TILECOUNT=1, FRAME_BINS_BINS_POOL_VERSION=1,
              FRAME_BINS_BINS_POOL_PAGESIZELOG2=12, FRAME_STATES_VERSION=1, FRAME_STATES_BASE=state_va,
              FRAME_STATES_LIMIT=state_va + 512, FRAME_SCRATCHSTRIDE=49152, FRAME_IMAGE_VERSION=1,
              FRAME_IMAGE_PIXELS_VERSION=1, FRAME_IMAGE_PIXELS_COLORS_VERSION=1,
              FRAME_IMAGE_PIXELS_COLORS_TILELOG2=4 if edge == 16 else 3,
              FRAME_IMAGE_PIXELS_COLORS_TILEWIDTH=edge, FRAME_IMAGE_PIXELS_COLORS_TILEHEIGHT=edge,
              FRAME_IMAGE_PIXELS_COLORS_SAMPLEMODE=mode, FRAME_IMAGE_PIXELS_COLORS_RESERVEDBYTES=4 * texels,
              FRAME_IMAGE_PIXELS_DEPTH_FORMAT=3, FRAME_IMAGE_PIXELS_DEPTH_LOADOP=1,
              FRAME_IMAGE_PIXELS_DEPTH_CLEARDEPTH=0x3f800000, LAYERED_VERSION=1, LAYERED_LAYERS=1,
              LAYERED_SOURCEPITCH0=4096)
    for start, end, key in (('FRAME_BINS_PRODUCER_ARENA_BASE', 'FRAME_BINS_PRODUCER_ARENA_LIMIT', (3, 0)),
                            ('FRAME_BINS_BINS_DIRECTORYBASE', 'FRAME_BINS_BINS_DIRECTORYLIMIT', (5, 0)),
                            ('FRAME_BINS_BINS_POOL_BASE', 'FRAME_BINS_BINS_POOL_LIMIT', (4, 0))):
        frame.set(**{start: resources[key], end: resources[key] + expected_sizes[key]})
    frame.set(FRAME_BINS_BINS_POOL_INITIALLIMIT=resources[4, 0] + 4096)
    for index in range(4):
        prefix = 'FRAME_IMAGE_PIXELS_COLORS_COLORS%d_' % index
        frame.set(**{prefix + 'ENABLED': 1, prefix + 'FORMAT': 35, prefix + 'LOADOP': 1,
                     prefix + 'CHANNELMASK': 15, prefix + 'OFFSET': (4 + 16 * index) * texels,
                     **{prefix + 'CLEAR%d' % c: 0x3e800000 for c in range(4)}})
    for index in range(5):
        prefix = 'FRAME_IMAGE_VIEWS%d_' % index
        base, size = resources[1, index], expected_sizes[1, index]
        frame.set(**{prefix + 'BASE': base, prefix + 'LIMIT': base + size,
                     prefix + 'ALLOCATIONBASE': base, prefix + 'ALLOCATIONLIMIT': base + size,
                     prefix + 'IMAGEWIDTH': 4, prefix + 'IMAGEHEIGHT': 4,
                     prefix + 'ROWPITCH': 4 * samples * (4 if index == 4 else 16),
                     prefix + 'WRITABLE': 1, prefix + 'STORE': 1})
    for engine in range(4):
        frame.set(**{'FRAME_SCRATCH%d_BASE' % engine: resources[6, engine],
                     'FRAME_SCRATCH%d_LIMIT' % engine: resources[6, engine] + 49152})
    draw = Record(512, 'opengpu_native_binned_abi.h', 'OPENGPU_NATIVE_BINNED_DRAW_',
                  [0x474e4401, 2048, 1, *header])
    # Explicit literal fixed fields include all input defaults and no GS,
    # query, TF, indirect draw, resource, sampler or uniform extension.
    for word, value in {17: 1, 19: 0x40800000, 20: mode, 26: 4, 27: 4, 28: 1, 31: 1,
                        35: 1, 37: 4, 40: 0xbf000000, 41: 0x3f800000, 311: 1,
                        314: 7, 315: 9, 316: 8, 325: 1, 327: 2, 334: 1, 335: 1,
                        336: scratch, 337: scratch + 40960, 339: scratch, 340: scratch + 40960,
                        342: vs_pc, 343: 0xffffffff, 344: 0xffffffff, 345: 16, 346: 1,
                        347: 4, 348: 4, 349: 0x3f800000, 351: 1}.items():
        draw.put(word, value, 33 if word in (337, 340) else 32)
    state = Record(128, 'opengpu_typed_fragment_state_abi.h', 'OPENGPU_TYPED_FRAGMENT_STATE_',
                   [0x47465301, 512, 1, 4, 7, 8, 9])
    for word, value in {27: 1, 28: 4, 29: meta[7], 30: 15, 31: 4, 36: 7, 37: 9, 38: 8,
                        39: mode, 46: 1, 47: 1, 54: fs_pc, 55: 16, 56: 6,
                        82: 1, 83: 7, 84: 1, 93: 1, 94: 1}.items():
        state.put(word, value)
    for engine, start in enumerate((48, 57, 63, 69)):
        base = resources[6, engine]
        # GFS scratch view order: allocationBase, allocationLimit(33), base,
        # limit(33); all six words are explicit, including upper zero words.
        for word, value in ((start, base), (start + 1, base + 49152), (start + 3, base), (start + 4, base + 49152)):
            state.put(word, value, 33 if word in (start + 1, start + 4) else 32)
    return {'GNJ2': (job_va, job), 'GNF3': (frame_va, frame), 'GND1': (draw_va, draw), 'GFS1': (state_va, state)}


def verify(space, description, meta, code):
    """Fail unless every full descriptor and native VS byte is canonical."""
    for name, (va, expected) in records(space, description, meta, code).items():
        expected.compare(space, va, name)
