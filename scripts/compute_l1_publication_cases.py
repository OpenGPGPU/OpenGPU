"""Derived original RV32 cached-alias publication sources for complete-owner P11.

Warm and reload callers use the ordinary cached kernarg vector path; the
producer writes the same retained physical words through the checked UC
storage view.  All three phases run on one DUT/RAM/single reset.
"""
import struct
from pathlib import Path

from compute_global_atomic_cases import (CEASE, FENCE, _addi, _bne,
    GROUPS as ORIGINAL_GROUPS, OUTPUT_OFFSET, OUTPUT_GUARD, KERNARG_BYTES)
from compute_cache_alias_cases import (VSET_ONE, GUARD, WARM_OFFSET, REFETCH_OFFSET,
    _store, _target, _vector_read)
from compute_uc_cache_alias_cases import _extract, _parameters, _request

CASES = ('l1-publication-warm', 'l1-publication-1', 'l1-publication-reload')
DATA_OFFSET = 4
FLAG_OFFSET = 68
DATA_WORD = 7
FLAG_WORD = 1
STORAGE_BASE = 0xcfffc000
CACHED_BASE = 0x80800000


def require(ok, message):
    if not ok:
        raise ValueError('L1 publication cases: ' + message)


def parameters(case):
    require(case in CASES, 'unknown L1 publication case')
    role = ('warm', 'producer', 'reload')[CASES.index(case)]
    return dict(case=case, role=role, groups=ORIGINAL_GROUPS, local=16, epoch=1,
                data_offset=DATA_OFFSET, flag_offset=FLAG_OFFSET,
                data_word=DATA_WORD if role != 'warm' else 0,
                flag_word=FLAG_WORD if role != 'warm' else 0,
                warm_offset=WARM_OFFSET, refetch_offset=REFETCH_OFFSET,
                output_offset=OUTPUT_OFFSET, kernarg_bytes=KERNARG_BYTES,
                target_offset=DATA_OFFSET, seed=0)


def _reader(output):
    return _vector_read(DATA_OFFSET, output) + [
        *_target(FLAG_OFFSET), VSET_ONE, 0x0205e187, 0x42302757,
        _store(14, output + 4), FENCE, CEASE]


def words(case):
    p = parameters(case)
    if p['role'] == 'producer':
        return (*_parameters(DATA_OFFSET, DATA_WORD), _request('exchange'),
                _extract(7, 12), _store(12, OUTPUT_OFFSET), FENCE, CEASE)
    else:
        output = p['warm_offset'] if p['role'] == 'warm' else p['refetch_offset']
        arms = [_reader(output + 4 * group) for group in range(ORIGINAL_GROUPS)]
    code = []
    for group, arm in enumerate(arms):
        if group + 1 < len(arms):
            code += [_addi(10, 0, group), _bne(2, 10, 4 * (len(arm) + 1))]
        code += arm
    require(len(code) * 4 <= 4096, 'L1 publication source exceeds its code binding')
    return tuple(code)


def bound_words(case):
    code = words(case)
    return code + (0,) * (-len(code) % 16)


def initial_kernarg(case):
    data = bytearray(b'\x6b' * KERNARG_BYTES)
    struct.pack_into('<%uI' % (2 * ORIGINAL_GROUPS), data, OUTPUT_OFFSET,
                     *([OUTPUT_GUARD] * (2 * ORIGINAL_GROUPS)))
    struct.pack_into('<I', data, DATA_OFFSET, 0)
    struct.pack_into('<I', data, FLAG_OFFSET, 0)
    struct.pack_into('<4I', data, WARM_OFFSET, *([GUARD] * 4))
    struct.pack_into('<4I', data, REFETCH_OFFSET, *([GUARD] * 4))
    return bytes(data)


def write_sources(out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    for case in CASES:
        code = bound_words(case)
        (out / (case + '.bin')).write_bytes(struct.pack('<%uI' % len(code), *code))
        (out / (case + '.kernarg')).write_bytes(initial_kernarg(case))
