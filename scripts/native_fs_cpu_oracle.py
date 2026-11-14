"""Independent bounded RV32/RVV word oracle for the real Mesa FS fixtures.

This is supplementary compiler semantics validation, never DUT execution.
The actual kernel validator and protected RX consumer are separate checks.
"""
import struct


def bits(value):
    return struct.unpack('<I', struct.pack('<f', value))[0]


def floating(value):
    return struct.unpack('<f', struct.pack('<I', value))[0]


def signed(value, width=32):
    return value - (1 << width) if value & (1 << (width - 1)) else value


def execute(code, memory, base):
    x = [0] * 32
    v = [[0] * 16 for _ in range(32)]
    x[8] = base
    x[9] = 128
    vl = 0
    for pc, w in enumerate(code):
        op, d, f3, a, z, fn = w & 127, w >> 7 & 31, w >> 12 & 7, w >> 15 & 31, w >> 20 & 31, w >> 26
        if w == 0x30500073:
            assert pc == len(code) - 1
            return
        if w == 0x0330000f:
            continue
        if op == 0x37:
            x[d] = w & 0xfffff000
        elif op == 0x13 and f3 == 0:
            x[d] = (x[a] + signed(w >> 20, 12)) & 0xffffffff
        elif op == 0x13 and f3 == 1 and not w >> 25:
            x[d] = x[a] << z & 0xffffffff
        elif op == 0x33 and f3 == 0 and not w >> 25:
            x[d] = x[a] + x[z] & 0xffffffff
        elif op == 0x57 and f3 == 7:
            assert w == 0x01007557
            vl = 16
            x[d] = vl
        elif op in (0x07, 0x27) and f3 == 6:
            assert w >> 20 == 0x20 and vl
            for lane in range(vl):
                at = x[a] + lane * 4
                assert at % 4 == 0 and 0 <= at <= len(memory) - 4
                if op == 0x07:
                    v[d][lane] = struct.unpack_from('<I', memory, at)[0]
                else:
                    struct.pack_into('<I', memory, at, v[d][lane])
        elif op == 0x57 and w & (1 << 25) and vl:
            for lane in range(vl):
                lhs = v[z][lane]
                rhs = v[a][lane] if f3 in (0, 1) else x[a] if f3 == 4 else signed(a, 5) & 0xffffffff
                if fn == 0x17 and f3 == 4:
                    value = rhs
                elif fn == 0 and f3 in (0, 4):
                    value = lhs + rhs
                elif fn == 0 and f3 == 1:
                    value = bits(floating(lhs) + floating(rhs))
                elif fn == 0x24 and f3 == 1:
                    value = bits(floating(lhs) * floating(rhs))
                elif fn == 0x12 and f3 == 1 and a in (2, 3):
                    value = bits(float(lhs if a == 2 else signed(lhs)))
                elif fn == 0x12 and f3 == 1 and a in (6, 7):
                    value = int(floating(lhs))
                elif fn == 9 and f3 in (0, 3):
                    value = lhs & rhs
                elif fn == 10 and f3 == 0:
                    value = lhs | rhs
                elif fn == 11 and f3 == 0:
                    value = lhs ^ rhs
                elif fn == 0x25 and f3 in (0, 3):
                    value = lhs << (rhs & 31)
                elif fn == 0x28 and f3 == 0:
                    value = lhs >> (rhs & 31)
                elif fn == 0x29 and f3 == 0:
                    value = signed(lhs) >> (rhs & 31)
                else:
                    raise AssertionError('unsupported oracle word %08x at %d' % (w, pc))
                v[d][lane] = value & 0xffffffff
        else:
            raise AssertionError('unsupported oracle word %08x at %d' % (w, pc))
        x[0] = 0
    raise AssertionError('missing CEASE')


def verify(path, name, samples):
    data = path.read_bytes()
    assert data[:8] == b'OGNSF001'
    m = struct.unpack_from('<15I', data, 8)
    assert len(data) == 68 + m[14] * 4 and samples in (1, 2, 4)
    code = struct.unpack_from('<%dI' % m[14], data, 68)
    first, stride = m[2], m[3]
    memory = bytearray(b'\xcd' * (m[4] + 64))
    expected = bytearray(memory)

    def put(destination, array, lane, value):
        struct.pack_into('<I', destination, array * stride + 4 * lane, value)

    # Distinct values in all 128 lanes and every declared varying component.
    # The sample pattern matches the public native 1x/2x/4x transport layout.
    for lane in range(128):
        sample = lane % samples
        px = .5 if samples == 1 else .75 if sample & 1 else .25
        py = .5 if samples == 1 else .75 if (sample & 1 if samples == 2 else sample & 2) else .25
        mask = (1 << samples) - 1 if lane & 1 else 1 << sample
        for destination in (memory, expected):
            put(destination, first - 3, lane, sample)
            put(destination, first - 2, lane, bits(px))
            put(destination, first - 1, lane, bits(py))
            put(destination, 5, lane, mask)
            for c in range(4):
                put(destination, 6 + 4 * 15 + c, lane, bits(lane + c / 4))
        if name == 'sample':
            colors = {0: (sample, px, py, mask)}
        elif name in ('mask-depth', 'mask-depth-keep'):
            colors = {0: (px, py, .25, 1)}
        elif name == 'mrt':
            colors = {i: (px, py, sample + i, 1) for i in range(4)}
        elif name == 'varying':
            colors = {3: (lane + px, lane + .25 + py, lane + .5, lane + .75)}
        elif name == 'signedness':
            value = mask ^ 0x80000000
            colors = {0: (value, signed(value), sample, 1)}
        elif name == 'truncation':
            colors = {0: (int(px * 3), int(py * 3) + sample, px, py)}
        else:
            raise AssertionError(name)
        for mrt, components in colors.items():
            for c, component in enumerate(components):
                put(expected, first + 4 * mrt + c, lane, bits(float(component)))
        control = 1 | (sum(1 << mrt for mrt in colors) << 2)
        if name in ('mask-depth', 'mask-depth-keep'):
            put(expected, first + 16, lane, bits(.5))
            shader_mask = (mask & 15) ^ (1 << sample) if name == 'mask-depth' else mask & (1 << sample)
            control |= 2 | 64 | ((shader_mask & 15) << 7)
        put(expected, first + 17, lane, control)
    for base in range(0, 128, 16):
        execute(code, memory, base)
    assert memory == expected, (name, samples, next(i for i, (a, b) in enumerate(zip(memory, expected)) if a != b))
