#!/usr/bin/env python3
"""Independent GNY2 oracle for the fixed live 4x4 ordinary-point recipe.

This is an expected-result check, not evidence that a DUT has executed. It is
specific to one full-frame point, identities 7/8/9, four RGBA32F plus D32F
clear/store views, no resolve, geometry, spill or restart, and 1/2/4 samples.
The caller must establish the original recipe, successful held publication and
enqueue command identity independently before calling verify.
"""
import json
from functools import lru_cache
from pathlib import Path
import struct


_CATALOG = Path(__file__).resolve().parents[1] / 'docs/abi/native-layered-graphics-v2.json'
_ZERO_TREES = ('frame.sourceFault.', 'frame.images.producer.restart.',
               'frame.images.images.fault.')
_IDENTITIES = ('identity.', 'frame.identity.', 'frame.images.identity.',
               'frame.images.producer.identity.', 'frame.images.directory.identity.',
               'frame.images.images.identity.')
_ONES = (
    'completedDraws', 'issuedDraws', 'frame.admittedParts', 'frame.draws',
    'frame.receivedParts', 'frame.version', 'frame.images.version',
    'frame.images.directory.allocatedPages', 'frame.images.directory.committedCount',
    'frame.images.directory.directoryMisses', 'frame.images.directory.tilesOpened',
    'frame.images.images.accepted', 'frame.images.images.completed',
    'frame.images.images.issued', 'frame.images.images.scanned',
    'frame.images.images.selected', 'frame.images.images.tiles',
    'frame.images.images.pageReadLines', 'frame.images.images.version',
    'frame.images.producer.accepted', 'frame.images.producer.references',
    'frame.images.producer.stored',
)
_ZEROS = (
    'faultAddress', 'faultDraw', 'faultStage', 'status',
    'frame.completedGeometryInvocations', 'frame.droppedParts',
    'frame.emittedGeometryPrimitives', 'frame.faultDraw', 'frame.faultDrawValid',
    'frame.faultStage', 'frame.sourceFaultValid', 'frame.status',
    'frame.submittedGeometryPrimitives', 'frame.images.directory.spillTransitions',
    'frame.images.directory.status', 'frame.images.images.faultValid',
    'frame.images.images.loadBytes', 'frame.images.images.overflow',
    'frame.images.images.resolvedBytes', 'frame.images.images.status',
    'frame.images.producer.binStatus', 'frame.images.producer.faultTile',
    'frame.images.producer.restartValid', 'frame.images.producer.status',
    'frame.images.status',
)


def _values(command_id, samples):
    values = {name: 0 for name in _ZEROS}
    values.update((name, 1) for name in _ONES)
    for prefix in _IDENTITIES:
        values.update((prefix + key, value) for key, value in
                      (('context', 7), ('pass', 8), ('generation', 9)))
    # Job/frame/preflight: (8+32+8)*64; draw scan plus execution: 2*32*64.
    # One fresh tile: one directory read, initialization and dirty-flush writes.
    # The 32-byte page header and its first 16-byte reference share one line.
    # Full-p16 window record packs into 23 lines; it is stored/read once.
    # Clear/store flushes all 16 pixels: (4*16+4)*16 bytes per sample.
    values.update({
        'commandId': command_id,
        'descriptorReadBytes': 3072,
        'drawReadBytes': 4096,
        'frame.images.directory.directoryReadBytes': 64,
        'frame.images.directory.directoryWriteBytes': 128,
        'frame.images.images.recordReadLines': 23,
        'frame.images.producer.recordWriteLines': 23,
        'frame.images.images.storedBytes': 1088 * samples,
    })
    return values


@lru_cache(maxsize=1)
def _layout():
    catalog = json.loads(_CATALOG.read_text())['completion']
    if (catalog['magic'], catalog['version'], catalog['words'], catalog['bytes']) != (
            '0x474e5902', 2, 4096, 16384):
        raise ValueError('unsupported GNY2 catalog header')
    fields = catalog['fields']
    if len(fields) != 628:
        raise ValueError('unsupported GNY2 catalog field count')
    expected_names = set(_values(1, 1))
    names, occupied, layout = set(), {0, 1, 2}, []
    zero_count = 0
    for field in fields:
        name, word, bits, signed = (field[key] for key in ('name', 'word', 'bits', 'signed'))
        if (type(name) is not str or name in names or type(word) is not int or
                type(bits) is not int or type(signed) is not bool or bits <= 0):
            raise ValueError('invalid GNY2 catalog field')
        size = (bits + 31) // 32
        slots = set(range(word, word + size))
        if (word < 3 or word + size > 4096 or word // 16 != (word + size - 1) // 16
                or occupied & slots):
            raise ValueError('invalid or overlapping GNY2 catalog placement')
        if name.startswith(_ZERO_TREES):
            zero_count += 1
        elif name not in expected_names:
            raise ValueError('unclassified GNY2 field: ' + name)
        names.add(name)
        occupied.update(slots)
        layout.append((name, word, bits, signed))
    if (zero_count != 555 or expected_names - names or
            len(expected_names) != 73 or len(occupied) != 766):
        raise ValueError('incomplete GNY2 oracle classification')
    return tuple(layout)


def _expected_words(command_id, samples):
    if type(command_id) is not int or not 0 < command_id < 256:
        raise ValueError('command_id must be the original nonzero 8-bit enqueue ID')
    if type(samples) is not int or samples not in (1, 2, 4):
        raise ValueError('samples must be 1, 2 or 4')
    values = _values(command_id, samples)
    words = [0] * 4096
    # NativeLayeredCompletionEncoding.words: magic, byte length, version.
    words[:3] = (0x474e5902, 16384, 2)
    for name, word, bits, signed in _layout():
        value = 0 if name.startswith(_ZERO_TREES) else values[name]
        if not 0 <= value < (1 << (bits - int(signed))):
            raise ValueError('expected GNY2 value does not fit: ' + name)
        # Zero initialization also enforces every high padding bit and gap.
        for offset in range((bits + 31) // 32):
            words[word + offset] = value >> (32 * offset) & 0xffffffff
    return words


def verify(data: bytes, command_id: int, samples: int):
    """Raise ValueError unless all 4096 little-endian GNY2 words are exact.

    In particular this checks all 628 fields, the full 145-bit counters and
    zero fault/restart payloads, plus reserved words and high field padding.
    Returns None on success; it does not qualify cycles, clocks or FPS.
    """
    if type(data) is not bytes or len(data) != 16384:
        raise ValueError('GNY2 must be exactly 16384 original bytes')
    expected = _expected_words(command_id, samples)
    actual = struct.unpack('<4096I', data)
    for index, (got, want) in enumerate(zip(actual, expected)):
        if got != want:
            raise ValueError(f'GNY2 word {index}: expected {want:#x}, got {got:#x}')
