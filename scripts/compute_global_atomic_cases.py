"""Bounded original RV32 kernels for the production writable-kernarg gate.

These are driver-level programs, not Mesa shader output. Four workgroups each
launch one sixteen-lane warp: a scalar AMO occurs once per warp, not per lane.
Every source event owns a distinct old-value slot in the original kernarg.
"""
from pathlib import Path
import struct

from compute_atomic_order import linearization

GROUPS, LOCAL, KERNARG_BYTES = 4, 16, 8192
OUTPUT_OFFSET, OUTPUT_GUARD = 128, 0xdeadbeef
CEASE, FENCE = 0x30500073, 0x0330000f
PAIRS = ('swap-add', 'xor-or', 'and-min', 'max-cas',
         'unsigned-min-max', 'cas-pair')
VIEWS = {'base': 0, 'page-tail': 4092, 'next-page': 4096, 'endpoint': 8188}
SEEDS = (0xfffffff0, 0x7ffff000, 0x7fffffff, 0x80000000, 0xffffffff, 0)
CASES = tuple('global-atomic-' + pair + '-' + view
              for pair in PAIRS for view in VIEWS)
FUNCT5 = {'add': 0, 'exchange': 1, 'xor': 4, 'compare-swap': 5,
          'or': 8, 'and': 12, 'signed-min': 16, 'signed-max': 20,
          'unsigned-min': 24, 'unsigned-max': 28}


def parameters(case):
    if type(case) is not str or case not in CASES:
        raise ValueError('unknown original global atomic case')
    index = CASES.index(case)
    pair, view = PAIRS[index // len(VIEWS)], tuple(VIEWS)[index % len(VIEWS)]
    return dict(case=case, pair=pair, view=view, seed=SEEDS[PAIRS.index(pair)],
                target_offset=VIEWS[view], groups=GROUPS, local=LOCAL,
                scalar_callers=GROUPS, events=2 * GROUPS,
                kernarg_bytes=KERNARG_BYTES, output_offset=OUTPUT_OFFSET)


def events(case):
    pair = parameters(case)['pair']
    result = []
    for group in range(GROUPS):
        calls = {
            'swap-add': (('exchange', 0x80000001 + group, 0),
                         ('add', 0x7fffffff + group, 0)),
            'xor-or': (('xor', 0x80000007 ^ (group << 4), 0),
                       ('or', 1 << (group + 8), 0)),
            'and-min': (('and', 0x7ffffff0 | (1 << group), 0),
                        ('signed-min', 0x80000007 + group, 0)),
            'max-cas': (('signed-max', 7 + group, 0),
                        ('compare-swap', 0xa2000000 + group, 10)),
            'unsigned-min-max': (('unsigned-min', 7 + group, 0),
                                 ('unsigned-max', 0x80000007 + group, 0)),
            'cas-pair': (('compare-swap', group + 1, 0),
                         ('compare-swap', 0xa1000000 + group, group + 1)),
        }[pair]
        result.extend(dict(operation=name, operand=operand, compare=compare)
                      for name, operand, compare in calls)
    return result


def predecessors(case):
    parameters(case)
    return tuple(0 if index % 2 == 0 else 1 << (index - 1)
                 for index in range(2 * GROUPS))


def check_results(case, observed, final):
    p = parameters(case)
    return linearization(p['seed'], final, events(case), observed, predecessors(case))


def _addi(rd, base, immediate):
    if not -2048 <= immediate <= 2047:
        raise ValueError('RV32 ADDI immediate out of range')
    return ((immediate & 0xfff) << 20) | (base << 15) | (rd << 7) | 0x13


def _constant(rd, value):
    signed = value if value < 0x80000000 else value - 0x100000000
    if -2048 <= signed <= 2047:
        return [_addi(rd, 0, signed)]
    upper = (signed + 0x800) >> 12
    return [((upper & 0xfffff) << 12) | (rd << 7) | 0x37,
            _addi(rd, rd, signed - (upper << 12))]


def _bne(left, right, displacement):
    if displacement <= 0 or displacement & 3 or displacement > 4092:
        raise ValueError('global source requires a bounded forward branch')
    return (((displacement >> 12) & 1) << 31 |
            ((displacement >> 5) & 0x3f) << 25 | right << 20 | left << 15 |
            1 << 12 | ((displacement >> 1) & 15) << 8 |
            ((displacement >> 11) & 1) << 7 | 0x63)


def words(case):
    p, calls = parameters(case), events(case)
    # x1 is the immutable original kernarg root. Preserve its proof while
    # reaching page endpoints using aligned, individually bounded ADDI steps.
    code, remaining = [_addi(11, 1, 0)], p['target_offset']
    while remaining:
        step = min(2044, remaining)
        code.append(_addi(11, 11, step))
        remaining -= step
    for group in range(GROUPS):
        arm = []
        for call in range(2):
            index = 2 * group + call
            event = calls[index]
            arm.extend(_constant(13, event['operand']))
            if event['operation'] == 'compare-swap':
                # CAS consumes the original rd as comparison, then returns
                # the old word in that same register. Define it for each call.
                arm.extend(_constant(12, event['compare']))
            arm.append(FUNCT5[event['operation']] << 27 | 13 << 20 |
                       11 << 15 | 2 << 12 | 12 << 7 | 0x2f)
            offset = OUTPUT_OFFSET + 4 * index
            arm.append((offset >> 5) << 25 | 12 << 20 | 1 << 15 |
                       2 << 12 | (offset & 31) << 7 | 0x23)
            if call == 0:
                arm.append(FENCE)
        arm.append(CEASE)
        if group < GROUPS - 1:
            # Launch x2 carries group.x. Different groups take distinct
            # scalar arms with fixed, admitted sw offsets from the same root.
            code.extend((_addi(10, 0, group), _bne(2, 10, 4 * (len(arm) + 1))))
        code.extend(arm)
    return tuple(code)


def initial_kernarg(case):
    p = parameters(case)
    data = bytearray(b'\x6b' * KERNARG_BYTES)
    struct.pack_into('<8I', data, OUTPUT_OFFSET, *([OUTPUT_GUARD] * (2 * GROUPS)))
    struct.pack_into('<I', data, p['target_offset'], p['seed'])
    return bytes(data)


def bound_words(case):
    # The production shader binding requires a 64-byte extent. The caller
    # zeroes only the padding after the last CEASE, never any result storage.
    code = words(case)
    return code + (0,) * (-len(code) % 16)


def write_sources(out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    for case in CASES:
        code = words(case)
        (out / (case + '.bin')).write_bytes(struct.pack('<%uI' % len(code), *code))
