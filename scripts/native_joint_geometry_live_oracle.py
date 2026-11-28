#!/usr/bin/env python3
from dataclasses import dataclass
import struct

from compute_owner_protocol import required_flags, uint
from native_graphics_uniform_oracle import require

WORDS = 46
PREFIX_BASE = 0x180
TAIL_BASE = 0x540
GEOMETRY_BASE = 0x560
RESERVED_BASE = 0x5d8
END = 0x5e8
MAGIC = 0x474e5a03
PROFILE = (0x474a5002, 0x8038ff, 0x08100207, 0x8ce58b2c)


def address(word):
    require(0 <= word < WORDS, 'invalid GNZ3 word')
    if word < 12:
        return PREFIX_BASE + 4 * word
    if word < 16:
        return TAIL_BASE + 4 * (word - 12)
    return GEOMETRY_BASE + 4 * (word - 16)


def wide(words, first, count):
    return sum(words[first + index] << (32 * index) for index in range(count))


def decode(words):
    require(type(words) in (list, tuple) and len(words) == WORDS, 'held GNZ3 is incomplete')
    words = tuple(uint(word) for word in words)
    require(words[0] == MAGIC and not words[1] & ~0xff and not words[20] & ~0x3fffffff,
            'GNZ3 magic, flags or padding is invalid')
    for index in (26, 32, 36, 41, 45):
        require(not words[index] & ~1, f'GNZ3 word{index} padding is invalid')
    status, stage = words[1] & 7, words[1] >> 3 & 7
    require(stage != 0 or words[2] == 0, 'GNZ3 fault address has no stage')
    require(stage == 0 or status != 0, 'GNZ3 fault stage has success status')
    geometry_valid = bool(words[1] & 0x80)
    require(status != 0 or geometry_valid, 'GNZ3 success lacks a geometry report')
    received = wide(words, 21, 6)
    dropped = wide(words, 27, 6)
    admitted = wide(words, 33, 4)
    require(received == admitted + dropped, 'GNZ3 received is not admitted plus dropped')
    require(wide(words, 42, 2) == wide(words, 39, 3) & 0xffffffffffffffff,
            'GNZ3 GS sequence low64 mismatch')
    if not geometry_valid:
        require(not any(words[16:]), 'invalid GNZ3 geometry report has nonzero detail')
    return words


@dataclass(frozen=True)
class GeometryProfile:
    words: tuple[tuple[int, int], ...]
    registers: tuple[tuple[int, int], ...]

    @property
    def discovery(self):
        return self.words + self.registers

    def packet(self):
        wire = bytearray(b'OGNP0004')
        for section in (self.words, self.registers):
            wire.extend(struct.pack('<I', len(section)))
            for item in section:
                wire.extend(struct.pack('<II', *item))
        return bytes(wire)


def profile_words(profile):
    required_flags(profile, dict(schema='opengpu-protected-joint-geometry-owner-profile-v1',
                                 resetCount=1, gpuExecution=False))
    sections = []
    for key, count in (('words', 100), ('registers', 5)):
        rows = profile.get(key)
        require(type(rows) is list and len(rows) == count, 'incomplete joint geometry discovery from DUT')
        require(all(type(row) is dict and set(row) == {'address', 'value'} for row in rows),
                'invalid joint geometry discovery word')
        sections.append(tuple((uint(row['address']), uint(row['value'])) for row in rows))
    words, registers = sections
    values = dict(words + registers)
    require(len(values) == 105 and [item[0] for item in registers] == [0, 0x60, 0x68, 0x320, 0x158],
            'joint geometry discovery aliases or reorders auxiliary words')
    require(tuple(values.get(0x600 + 4 * index) for index in range(4)) == PROFILE and
            values.get(0x368) == 0x8b67ff and values.get(0x160) == 0x474e4305 and
            values.get(0x168) == 0x474e4a02 and values.get(0x17c) == 0x8813db02 and
            tuple(values.get(0x500 + 4 * index) for index in range(8)) ==
            (0x47475301, 8, 0x1f, 0x474e4a02, 0x474e4603, 0x474e5902, 0xff840206, 0x04001000),
            'DUT discovery lacks the exact protected geometry profile')
    return GeometryProfile(words, registers)


def receipt_words(value, phase, command, context, row):
    required_flags(value, dict(phase=phase, record=row['record'], commandId=command,
        completion=0x18700 | command, physicalWriteFaults=0, physicalReadFaults=0,
        heldIrq=3, heldIrqAsserted=True, heldCompletionDrained=True,
        readOnlyGuards=True, sourceQualificationRequired=True))
    detail = decode(value.get('gnz3'))
    read = uint(value.get('metadataReadBytes'), 64)
    legacy, status = uint(value.get('heldLegacyStatus')), uint(value.get('heldStatus'))
    flags = 0x80 | (0x40 if row.get('clearOnly', False) else 0)
    require(detail[:8] == (MAGIC, flags, 0, context, row['pass'], row['generation'],
                           row.get('capturedEntries', 1), row.get('retiredRanges', 1)) and
            read and not read & 63 and status & 2 and not status & ~0x23,
            'GNZ3 does not describe the expected joint geometry retirement')
    return struct.pack('<3I46IQ4I', row['record'], command, value['completion'], *detail,
                       read, legacy, status, 3, 1)


def retired_receipt(value, held, command, context, row):
    required_flags(value, dict(ownerReplay=True, retiredStatus=1, retiredIrq=1,
        retiredIrqAsserted=False, externalDrained=True, resets=1, resetsBetweenRecords=False,
        independentImagesRequired=True, clockQualified=False, fpsQualified=False))
    payload = receipt_words(value, 'retired', command, context, row)
    require(payload == held, 'retired DUT receipt changed held GNZ3 ownership or counters')
    require(uint(value.get('serviceCycles'), 64) > 0 and uint(value.get('writeBusBytes'), 64) > 0,
            'no observed service or required writes for the geometry record')
    return b'OGJR0001' + payload + struct.pack('<4I', 1, 1, 0, 1)


def capture(data, discovery, context, row):
    require(data[:8] == b'OGCAP007', 'wrong protected joint geometry capture format')
    cursor = 8

    def take(fmt):
        nonlocal cursor
        length = struct.calcsize('<' + fmt)
        require(cursor + length <= len(data), 'truncated protected joint geometry capture')
        result = struct.unpack_from('<' + fmt, data, cursor)
        cursor += length
        return result

    def section(fmt, maximum):
        count, = take('I')
        require(count <= maximum, 'oversize protected joint geometry capture section')
        return [take(fmt) for _ in range(count)]

    budget, hold, command = take('QII')
    require(0 < budget <= 1000000000 and 8 <= hold <= 4096 and command < 256,
            'invalid protected geometry execution bounds')
    require(section('II', 256) == list(discovery),
            'protected geometry capture did not use the probed DUT discovery')
    writable = section('QQ', 4096)
    trace = section('IIII', 65536)
    checks = section('III', 256)
    require(not section('II', 256) and cursor == len(data) and len(checks) == 47,
            'invalid protected geometry completion witnesses')
    end = 0
    for first, last in writable:
        require(end <= first < last <= 1 << 32, 'invalid protected geometry writable interval')
        end = last
    regs = {item[1]: item[2] for item in trace if item[0] == 1}
    require(regs.get(0xc4) == command and regs.get(0xc8) == 15 and regs.get(0xfc) == 512 and
            regs.get(0x108) == context and regs.get(0x10c) == row['pass'] and
            regs.get(0x110) == row['generation'] and
            regs.get(0x100, 0) & ~7 == 0x474a0200 | (row['domain'] << 1) and
            trace[-1:] == [(1, 0x11c, 1, 0)],
            'protected geometry MMIO command changed its original identity or domain')
    require([item[0] for item in checks] == [0x124] + [address(index) for index in range(WORDS)] and
            [item[1] for item in checks] == [0xffffffff] + [0xffffffff] * 8 + [0] * 38,
            'protected geometry capture lost the GNZ3 address map or report kind')
    return command, regs, writable
