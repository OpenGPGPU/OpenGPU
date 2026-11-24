"""Independent input and RGBA32UI oracle for the three joint Mesa domains."""
import hashlib
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
WIDTH = HEIGHT = 4
IMAGE_BYTES = WIDTH * HEIGHT * 16


def original_records(value, initial, engines, *, preflight=False):
    required_flags(value, dict(cpuPreflight=preflight, engines=engines))
    context = uint(value.get('context'))
    rows = value.get('records')
    require(context and type(rows) is list and len(rows) == RECORDS, 'invalid original joint record set')
    allocations = set()
    available = {(first, end) for first, end, _ in initial.ranges}
    for i, row in enumerate(rows):
        required_flags(row, {'record':i,'domain':CASES[i][0],'pass':11+i,'generation':41+i,
                             'imageOffset':64,'imageBytes':IMAGE_BYTES})
        allocation, size = uint(row.get('allocation'), 64), uint(row.get('allocationBytes'), 64)
        require(allocation % 4096 == 0 and allocation + size <= 1 << 32 and
                row['imageOffset'] + IMAGE_BYTES <= size and
                (allocation, allocation + size) in available and allocation not in allocations,
                'missing or aliased original image allocation')
        allocations.add(allocation)
        require(initial.read(allocation + row['imageOffset'], IMAGE_BYTES) == b'\xa5' * IMAGE_BYTES,
                'original image was modified before executing the joint command')
    return context, rows


def capture(data, discovery, context, row):
    write_fault = CASES[row['record']][2]
    cancel = row['record'] == SOURCE_CANCEL_RECORD
    magic = b'OGCAP005' if cancel else b'OGCAP004' if write_fault else b'OGCAP003'
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
    require(any(first <= image and image + IMAGE_BYTES <= last for first, last in writable),
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


def original_graph(memory, regs, context, row, *, frame_version, fault=None):
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
    require(frame[0] == 0x474e4601 and frame[211:213] == (HEIGHT,WIDTH) and
            frame[216:219] == (WIDTH*16,1,1) and
            physical(memory,satp,frame[210],IMAGE_BYTES,7) == row['allocation']+row['imageOffset'],
            'actual frame/store view is not the original image allocation')
    require(not fault or fault[1] == frame[210], 'write fault address differs from the published frame view')
    pc = draw[342]
    # The original GND declares UINT8 indices. Bind the response hold to the
    # cache line containing the first index through the real Sv32 table.
    index_line = physical(memory,satp,(draw[322]+draw[312])&~63,64,3)
    return hashlib.sha256(view(pc,4096-(pc & 4095),11)).digest(), index_line


def expected_image(domain, mutation):
    # Independent 4x4 sample centers. The isoline runs from viewport x=0
    # to 3.5 through row 1 and ends inside the last pixel's diamond. The
    # triangle's hypotenuse is x+y=0.75 in NDC, between the sample sums.
    require(domain in (0,1,2) and type(mutation) is bool, 'invalid joint image recipe')
    base = (6,17,47,53)
    color = struct.pack('<4I',*(c^m for c,m in zip(base,(5,9,17,33)))) if mutation else struct.pack('<4I',*base)
    clear = bytes(16)
    output = bytearray()
    for y in range(HEIGHT):
        for x in range(WIDTH):
            covered = (y==1 and x<3) if domain==0 else (x+y<=4 if domain==1 else True)
            output.extend(color if covered else clear)
    return bytes(output)


def verify_image(previous, returned, row, writable):
    image = row['allocation'] + row['imageOffset']
    actual = returned.read(image, IMAGE_BYTES)
    domain, mutation, write_fault = CASES[row['record']]
    preserved = write_fault or row['record'] == SOURCE_CANCEL_RECORD
    expected = previous.read(image, IMAGE_BYTES) if preserved else expected_image(domain, mutation)
    for offset in range(0, IMAGE_BYTES, 16):
        require(actual[offset:offset+16] == expected[offset:offset+16],
                f"record {row['record']} pixel {(offset//16) % WIDTH},{offset//(16*WIDTH)} differs")
    # Each record may update its working buffers. Narrow the image interval
    # to the actual view, so its entire physical allocation guard is checked.
    allocation, end = row['allocation'], row['allocation'] + row['allocationBytes']
    allowed = [] if preserved else [(image,image+IMAGE_BYTES)]
    for first, last in writable:
        if first < allocation:
            allowed.append((first,min(last,allocation)))
        if last > end:
            allowed.append((max(first,end),last))
    guards(previous, returned, allowed)
