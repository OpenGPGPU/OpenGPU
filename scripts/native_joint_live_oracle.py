"""Independent input and RGBA32UI oracle for the three joint Mesa domains."""
from dataclasses import dataclass
from functools import reduce
import hashlib
from operator import xor
import struct

from compute_owner_protocol import required_flags, uint
from native_graphics_uniform_oracle import require
from native_sample_graphics_owner_oracle import guards


CASES = (
    (0, False, False), (0, True, False),
    (1, False, False), (1, True, False),
    (2, False, False), (2, True, False),
    (1, False, True), (1, False, False),
    (1, False, False), (1, False, False),
)
SOURCE_CANCEL_RECORD = 8
RECORDS = len(CASES)

UNIFORM_RECOVERY_PER_DOMAIN = 10


@dataclass(frozen=True)
class UniformRead:
    stage: int
    binding: int | None
    fault: bool


UNIFORM_RECOVERY_READS = {
    2: UniformRead(4, 71, fault=True),
    4: UniformRead(5, 1, fault=True),
    6: UniformRead(4, None, fault=False),
    8: UniformRead(5, None, fault=False),
}


@dataclass(frozen=True)
class ReplayFixture:
    width: int
    height: int
    tile_log2: int
    cases: tuple[tuple[int, bool, bool], ...]
    patch_layout: tuple[int, int] | None = None
    tess_uniforms: bool = False
    recover_uniform_reads: bool = False

    def uniform_read(self, record):
        if self.recover_uniform_reads:
            return UNIFORM_RECOVERY_READS.get(record % UNIFORM_RECOVERY_PER_DOMAIN)
        return None

    def cancels_read(self, record):
        if self.recover_uniform_reads:
            read = self.uniform_read(record)
            return read is not None and not read.fault
        return record == SOURCE_CANCEL_RECORD

    @property
    def image_bytes(self):
        return self.width * self.height * 16

    @property
    def tiles(self):
        tile = 1 << self.tile_log2
        return ((self.width+tile-1)//tile) * ((self.height+tile-1)//tile)


SMALL = ReplayFixture(4,4,3,CASES)
VGA_QUAD = ReplayFixture(640,480,4,CASES[4:6])
PATCH_ENDPOINTS = ReplayFixture(4,4,3,CASES[:6],patch_layout=(60,120))
FULL_PATCH_ENDPOINTS = ReplayFixture(4,4,3,CASES[4:6],patch_layout=(64,0))
TESS_UNIFORMS = ReplayFixture(4,4,3,CASES[:6],tess_uniforms=True)
TESS_UNIFORM_RECOVERY = ReplayFixture(
    4,4,3,tuple((domain,bool(i & 1),False)
                for domain in range(3) for i in range(UNIFORM_RECOVERY_PER_DOMAIN)),
    tess_uniforms=True,recover_uniform_reads=True)

TESS_UBO_BINDINGS = (
    (71,63,32,31,0,18,7,41,55,12,69,23),
    (1,62,33,30,2,19,8,42,56,13,70,24),
)
TESS_BLOCK_WORDS = 4096
TESS_DEFAULT_WORDS = 1024


def original_records(value, initial, engines, *, preflight=False, fixture=SMALL):
    required_flags(value, dict(cpuPreflight=preflight, engines=engines))
    context = uint(value.get('context'))
    rows = value.get('records')
    require(context and type(rows) is list and len(rows) == len(fixture.cases), 'invalid original joint record set')
    allocations = set()
    available = {(first, end) for first, end, _ in initial.ranges}
    image_bytes = fixture.image_bytes
    sentinel = b'\xa5' * image_bytes
    for i, row in enumerate(rows):
        required_flags(row, {'record':i,'domain':fixture.cases[i][0],'pass':11+i,'generation':41+i,
                             'imageOffset':64,'imageBytes':image_bytes})
        allocation, size = uint(row.get('allocation'), 64), uint(row.get('allocationBytes'), 64)
        require(allocation % 4096 == 0 and allocation + size <= 1 << 32 and
                row['imageOffset'] + image_bytes <= size and
                (allocation, allocation + size) in available and allocation not in allocations,
                'missing or aliased original image allocation')
        allocations.add(allocation)
        require(initial.read(allocation + row['imageOffset'], image_bytes) == sentinel,
                'original image was modified before executing the joint command')
    return context, rows


def capture(data, discovery, context, row, *, fixture=SMALL):
    write_fault = fixture.cases[row['record']][2]
    read = fixture.uniform_read(row['record'])
    cancel = fixture.cancels_read(row['record'])
    magic = b'OGCAP003'
    if write_fault:
        magic = b'OGCAP004'
    if cancel:
        magic = b'OGCAP005'
    if read is not None and read.fault:
        magic = b'OGCAP006'
    require(data[:8] == magic, 'wrong protected joint capture format')
    cursor = 8

    def take(fmt):
        nonlocal cursor
        length = struct.calcsize('<' + fmt)
        require(cursor + length <= len(data), 'truncated protected joint capture')
        result = struct.unpack_from('<' + fmt, data, cursor)
        cursor += length
        return result

    def section(fmt, maximum):
        count, = take('I')
        require(count <= maximum, 'oversize protected joint capture section')
        return [take(fmt) for _ in range(count)]

    fault = take('QI') if write_fault else None
    budget, hold, command = take('QII')
    require(0 < budget <= 1000000000 and 8 <= hold <= 4096 and command < 256,
            'invalid protected joint execution bounds')
    require(section('II', 256) == discovery, 'protected capture did not use the probed DUT discovery')
    writable = section('QQ', 4096)
    trace = section('IIII', 65536)
    checks = section('III', 256)
    require(not section('II', 256) and cursor == len(data) and len(checks) == (0 if cancel else 17),
            'invalid protected capture completion witnesses')
    end = 0
    for first, last in writable:
        require(end <= first < last <= 1 << 32, 'invalid protected writable interval')
        end = last
    image = row['allocation'] + row['imageOffset']
    require(any(first <= image and image + fixture.image_bytes <= last for first, last in writable),
            'protected command does not own its original image')
    if fault:
        require(fault[0] == image and fault[1] and not fault[1]&63,
                'write fault is outside the first original image line')
    regs = {address:value for kind, address, value, mask in trace if kind == 1}
    fixed = {0xc4: command, 0xc8: 15, 0xfc: 512, 0x108: context, 0x10c: row['pass'],
             0x110: row['generation']}
    require(all(regs.get(address) == value for address, value in fixed.items()) and
            trace[-1:] == [(1,0x11c,1,0)] and regs.get(0x100, 0) & ~1 == 0x474a0100 | (row['domain'] << 1),
            'protected MMIO command changed its original identity or tessellation domain')
    satp = regs.get(0x14c, 0)
    require(satp >> 31 == 1 and (satp >> 22 & 0x1ff) and satp == regs.get(0x150) and
            regs.get(0xf4,1) % 4096 == 0,
            'protected joint submission lost its original Sv32 context/root')
    return command, regs, writable, fault


def physical(memory, satp, va, count, permission):
    require(0 <= va < va + count <= 1 << 32, 'invalid protected virtual interval')
    root = (satp & 0x3fffff) << 12
    start = va & ~4095
    first = None
    for address in range(start, va + count, 4096):
        table, = struct.unpack('<I', memory.read(root + 4*(address >> 22), 4))
        require(table & 15 == 1, 'missing protected Sv32 upper PTE')
        leaf, = struct.unpack('<I', memory.read(((table >> 10) << 12) + 4*(address >> 12 & 1023), 4))
        require(leaf & 15 == permission, 'protected Sv32 leaf has the wrong permissions')
        page = (leaf >> 10) << 12
        if first is None:
            first = page + (va & 4095)
        require(page == (first & ~4095) + address-start, 'noncontiguous original view')
    memory.read(first, count)
    return first


def tess_ubo_windows(record):
    # The second draw for each domain aliases binding 1 to binding 71.
    return tuple(tuple(71 if record & 1 and binding == 1 else binding for binding in stage)
                 for stage in TESS_UBO_BINDINGS)


def tess_ubo_word(window, record, component):
    return 0x12000000 + 0x10000*window + (component + record*TESS_BLOCK_WORDS)*(13+window)


def tess_default_word(stage, record, component):
    return 0x64000000 | stage << 20 | record << 12 | component


def tess_binding(binding, identity, slot, stage_mask, length):
    base = binding[8]
    end = base + length
    require(base % 256 == 0 and
            binding == (0x47554203,64,3,*identity,slot,stage_mask,base,end,0,base,end,0,1,0),
            'protected GUB3 binding differs from its original read-only view')
    return base


def check_tess_uniforms(words, sequence, identity, record, read=None):
    windows = tess_ubo_windows(record)
    users = {window: sum(1 << stage for stage, owned in enumerate(windows,4) if window in owned)
             for window in set(windows[0]) | set(windows[1])}
    require(sequence[499] == sequence[504] == 3 and sequence[505] == sequence[500]+64 and
            sequence[501:504] == sequence[506:509] == (0,0,0),
            'protected GNT lost its independent TCS/TES roots or exposed writable scratch')
    table = sequence[500]+128
    table_end = table+24*64
    known, spans, defaults = {}, [], []
    for index, owned in enumerate(windows):
        stage = 4+index
        default = table_end+64*index
        root = words(sequence[500+5*index],16)
        require(root[:8] == (0x47555203,64,3,*identity,stage,0xfff) and
                root[10:] == (table,table_end,0,default,1,0) and max(root[8:10]) < 1 << 30,
                'protected GUR3 root changed its owner, stage, table or default')
        slots = tuple(word >> shift & 31 for word in root[8:10] for shift in range(0,30,5))
        require(len(set(slots)) == 12 and max(slots) < 24, 'invalid protected GUR3 stage mapping')
        default_base = tess_binding(words(default,16),identity,31,1 << stage,TESS_DEFAULT_WORDS*4)
        defaults.append(default_base)
        require(words(default_base,TESS_DEFAULT_WORDS) ==
                tuple(tess_default_word(stage,record,word) for word in range(TESS_DEFAULT_WORDS)),
                'protected GUR3 default data changed after rebinding')
        spans.append((default_base,default_base+TESS_DEFAULT_WORDS*4))
        expected = {tess_ubo_word(window,record,0):window for window in owned}
        for slot in slots:
            binding = words(table+64*slot,16)
            first, = words(binding[8],1)
            window = expected.pop(first,None)
            require(window is not None, 'protected GUR3 UBO mapping changed after rebinding')
            base = tess_binding(binding,identity,slot,users[window],TESS_BLOCK_WORDS*4)
            if window in known:
                require(known[window] == (slot,base), 'identical original UBO view was copied twice')
            else:
                known[window] = (slot,base)
                spans.append((base,base+TESS_BLOCK_WORDS*4))
                require(words(base,TESS_BLOCK_WORDS) ==
                        tuple(tess_ubo_word(window,record,word) for word in range(TESS_BLOCK_WORDS)),
                        'protected GUR3 changed an original UBO window')
    spans.sort()
    require(all(right >= left_end for (_,left_end),(right,_) in zip(spans,spans[1:])),
            'different original GUR3 views overlap')
    if read is None:
        return None
    if read.binding is None:
        return defaults[read.stage-4]+TESS_DEFAULT_WORDS*4-64
    require(users.get(read.binding) == 1 << read.stage,'uniform recovery UBO is not private to its tested stage')
    return known[read.binding][1]+TESS_BLOCK_WORDS*4-64


def original_graph(memory, regs, context, row, *, frame_version, fault=None, fixture=SMALL):
    """Check the published owner's descriptors and original input selection."""
    satp, root = regs[0x14c], regs[0xf4]

    def view(va, size, permission=3):
        return memory.read(physical(memory, satp, va, size, permission), size)

    def words(va, count):
        return struct.unpack('<' + 'I'*count, view(va, count*4))

    job = words(root, 128)
    require(job[0] == 0x474e5104 + row['domain'] and job[17] == job[31] == job[34] == job[35] == 1 and
            job[24:27] == (context, row['generation'], row['pass']),
            'published GNQ identity or original directory changed')
    sequence = words(job[16], 512)
    require(sequence[0] == 0x474e5407 + row['domain'] and sequence[16] == 2,
            'published GNT lost the original per-instance patch range')
    tess_code = b''
    read_va = None
    if fixture.patch_layout is not None:
        layout_word = 214 if row['domain'] == 0 else 215
        vertex_words, patch_words = fixture.patch_layout
        require(sequence[layout_word:layout_word+5] == (8,2,vertex_words,32,patch_words) and
                sequence[layout_word+23] == 0,
                'published GNT changed the dynamic patch layout or added static selections')
        tes_pc = sequence[layout_word+18]
        require(tes_pc and tes_pc % 4 == 0, 'missing original TES executable')
        tess_code = view(tes_pc,4096-(tes_pc & 4095),11)
        require(any(word & 0xfc00707f == 0x8400002b for word, in struct.iter_unpack('<I',tess_code)),
                'original TES omitted the owned patch-read instruction')
        uniform = words(sequence[505],16)
        identity = (context,row['pass'],row['generation'])
        require(sequence[504] == 3 and uniform[0] == 0x47555203 and
                uniform[3:8] == (*identity,5,0) and uniform[14] == 1,
                'dynamic TES lost its private default uniform root')
        binding = words(uniform[13],16)
        require(binding[0] == 0x47554203 and binding[3:8] == (*identity,31,32) and
                binding[9:11] == (binding[8]+16,0) and binding[14] == 1,
                'dynamic TES lost its read-only endpoint selectors')
        column_or_patch = 2 if fixture == FULL_PATCH_ENDPOINTS else 29
        expected_indices = (31,3,column_or_patch,3) if row['record'] & 1 else (0,0,0,0)
        require(words(binding[8],4) == expected_indices, 'protected defaults changed the TES endpoint selectors')
    elif fixture.tess_uniforms:
        read_va = check_tess_uniforms(words,sequence,(context,row['pass'],row['generation']),
                                      row['record'],fixture.uniform_read(row['record']))
        for pc in (sequence[210],sequence[232 if row['domain'] == 0 else 233]):
            require(pc and pc % 4 == 0,'missing original TCS/TES executable')
            code = view(pc,4096-(pc & 4095),11)
            uniform_loads = sum((word & 0xfc00707f == 0x1c00002b or
                                 word & 0xfe00707f == 0x0e00000b) for word, in struct.iter_unpack('<I',code))
            require(uniform_loads >= 13,'original TCS/TES omitted a checked uniform load')
            tess_code += code
    directory = words(job[28], 32)
    entry = directory[16:]
    require(entry[:5] == (0, 0, row['record']+3, 1, 3) and entry[6:10] == (0,2,0,1) and entry[11] == 2,
            'published directory lost its original invocation identity or coverage')
    source = words(entry[12], 32)
    require(source[0] == 0x47565301, 'missing protected vertex source')
    draw = words(source[24], 512)
    require(draw[0] == 0x474e4401 and draw[35] == 2 and draw[180:182] == (7,0xffffffff) and
            draw[186] == 2 and draw[189] == draw[310] == 16 and
            draw[311:313] == (2,1) and draw[317] == 1 and draw[325:328] == (2,0,2),
            'published GND lost the original vertex/instance draw or enabled PATCHES restart')
    require(view(draw[322],4) == bytes((4,255,3,0)), 'original fixed-marker index values were replaced')
    first = (-1.5,-1.5,0,1) if row['domain']==1 else (-1,-0.25 if row['domain']==0 else -1,0,1)
    require(view(draw[306]+254*16,16) == struct.pack('<4f',*first) and
            view(draw[306]+2*16,16) == struct.pack('<4f',4,4,0,1) and
            view(draw[185]+7*16,32) == struct.pack('<8I',1,2,4,8,128,64,32,16),
            'vertex/index/divisor route is missing its original distinguishable data')
    frame = words(job[21], 512)
    require(frame[299] == frame_version, 'original GNF payload version differs from DUT discovery')
    shape = (fixture.height,fixture.width)
    require(frame[55] == fixture.tiles and frame[64:67] == (*shape,fixture.tile_log2) and
            frame[112] == fixture.tile_log2 and frame[73:76] == (1,26,1),
            'published frame changed the viewport, tile count or RGBA32UI attachment')
    require(frame[0] == 0x474e4601 and frame[211:213] == shape and
            frame[216:219] == (fixture.width*16,1,1) and
            physical(memory,satp,frame[210],fixture.image_bytes,7) == row['allocation']+row['imageOffset'],
            'actual frame/store view is not the original image allocation')
    require(not fault or fault[1] == frame[210], 'write fault address differs from the published frame view')
    pc = draw[342]
    # Bind read injection to an actual read-only Sv32 leaf. The original
    # source-cancel fixture selects the line containing its first UINT8 index.
    if read_va is None:
        read_va = (draw[322]+draw[312])&~63
    read_line = physical(memory,satp,read_va,64,3)
    return hashlib.sha256(view(pc,4096-(pc & 4095),11)+tess_code).digest(), read_line


def expected_image(domain, mutation, *, fixture=SMALL, record=None):
    # Independent 4x4 sample centers. The isoline runs from viewport x=0
    # to 3.5 through row 1 and ends inside the last pixel's diamond. The
    # triangle's hypotenuse is x+y=0.75 in NDC, between the sample sums.
    require(domain in (0,1,2) and type(mutation) is bool, 'invalid joint image recipe')
    base = (6,17,47,53)
    if fixture == FULL_PATCH_ENDPOINTS:
        front = (0x3e800000,0x3f800004,0x3f800004,0x7f800000)
        back = (0x43000000,0x3f800154,0x3f800136,0x7f800000)
        color = struct.pack('<4I',*(back if mutation else front))
    elif fixture == PATCH_ENDPOINTS:
        # The channels identify matrix columns, the selected vertex's final
        # matrix word, the selected per-patch word and the two tess levels.
        front = (0x3e800000,0x3f800004,153,0x7f800000)
        back = (0x43000000,0x3f800154,956,0x7f800000)
        color = struct.pack('<4I',*(back if mutation else front))
    elif fixture.tess_uniforms:
        require(record is not None or not fixture.recover_uniform_reads,
                'uniform recovery image requires its original record number')
        if record is None:
            record = 2*domain+int(mutation)
        control, evaluation = (reduce(xor,(tess_ubo_word(window,record,TESS_BLOCK_WORDS-1) for window in stage))
                               for stage in tess_ubo_windows(record))
        color = struct.pack('<4I',base[0]^tess_default_word(4,record,TESS_DEFAULT_WORDS-1),control,
                            base[2]^tess_default_word(5,record,TESS_DEFAULT_WORDS-1),base[3]^evaluation)
    else:
        color = struct.pack('<4I',*(c^m for c,m in zip(base,(5,9,17,33)))) if mutation else struct.pack('<4I',*base)
    if domain == 2:
        # The unmodified quad recipe covers all pixel centers in the viewport.
        return color * (fixture.width * fixture.height)
    require((fixture.width,fixture.height) == (4,4), 'line and triangle coverage requires the 4x4 fixture')
    clear = bytes(16)
    output = bytearray()
    for y in range(fixture.height):
        for x in range(fixture.width):
            covered = (y==1 and x<3) if domain==0 else x+y<=4
            output.extend(color if covered else clear)
    return bytes(output)


def verify_image(previous, returned, row, writable, *, fixture=SMALL):
    image = row['allocation'] + row['imageOffset']
    image_bytes = fixture.image_bytes
    actual = returned.read(image, image_bytes)
    record = row['record']
    domain, mutation, write_fault = fixture.cases[record]
    preserved = write_fault or fixture.cancels_read(record) or fixture.uniform_read(record) is not None
    expected = (previous.read(image, image_bytes) if preserved else
                expected_image(domain, mutation, fixture=fixture, record=record))
    if actual != expected:
        for offset in range(0, image_bytes, 16):
            found, wanted = actual[offset:offset+16], expected[offset:offset+16]
            if found != wanted:
                pixel = offset//16
                raise ValueError(
                    f"record {row['record']} pixel {pixel % fixture.width},{pixel // fixture.width} differs "
                    f"(expected {wanted.hex()}, observed {found.hex()})"
                )
    # Each record may update its working buffers. Narrow the image interval
    # to the actual view, so its entire physical allocation guard is checked.
    allocation, end = row['allocation'], row['allocation'] + row['allocationBytes']
    allowed = [] if preserved else [(image,image+image_bytes)]
    for first, last in writable:
        if first < allocation:
            allowed.append((first,min(last,allocation)))
        if last > end:
            allowed.append((max(first,end),last))
    guards(previous, returned, allowed)
