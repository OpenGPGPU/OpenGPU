#!/usr/bin/env python3
"""Independent whole-byte oracle for actual GUR1/GUR3/GUR2 private uniforms."""
import argparse
from pathlib import Path
import struct

from five_stage_inputs import (CASES, PRIVATE_FILE_BYTES, PRIVATE_ROOTS,
                               PRIVATE_SEGMENT_BYTES, PRIVATE_SEGMENTS, STAGES,
                               case_directory, read_regular, require)
from five_stage_source_preflight import verify_case


def _record(buffer, offset, values):
    require(len(values) == 16 and offset + 64 <= len(buffer), "private record extent")
    struct.pack_into("<16I", buffer, offset, *values)


def _expected_segment(base, stage_indices, stage_ids, version, views):
    table = 64 * len(stage_indices)
    end_table = table + 24 * 64
    data_start = end_table + len(stage_indices) * 64
    expected = bytearray(data_start)
    copies = []
    mappings = []
    defaults = []
    cursor = data_start
    for stage in stage_indices:
        row = []
        for index in range(1, 13):
            view = views[13 * stage + index]
            slot = len(copies)
            row.append(slot)
            cursor = (cursor + 255) & ~255
            copies.append((view, cursor, 1 << STAGES[stage], slot, table + slot * 64))
            cursor += len(view[2])
        mappings.append(row)
        view = views[13 * stage]
        cursor = (cursor + 255) & ~255
        defaults.append((view, cursor, 1 << STAGES[stage], 31,
                         end_table + len(defaults) * 64))
        cursor += len(view[2])
    cursor = (cursor + 63) & ~63
    expected.extend(bytes(cursor - len(expected)))
    for row_index, stage_id in enumerate(stage_ids):
        mapping = mappings[row_index]
        low = sum(mapping[index] << (5 * index) for index in range(6))
        high = sum(mapping[6 + index] << (5 * index) for index in range(6))
        _record(expected, row_index * 64, [
            0x47555200 + version, 64, version, 7, 8, 9, stage_id, 4095,
            low, high, base + table, base + end_table, 0,
            base + end_table + row_index * 64, 1, 0,
        ])
    for view, offset, mask, index, record_offset in copies + defaults:
        data = view[2]
        _record(expected, record_offset, [
            0x47554200 + version, 64, version, 7, 8, 9, index, mask,
            base + offset, base + offset + len(data), 0,
            base + offset, base + offset + len(data), 0, 1, 0,
        ])
        expected[offset:offset + len(data)] = data
    return bytes(expected)


def verify(build, name, data=None):
    directory = case_directory(build, name)
    case = verify_case(directory, name)
    if data is None:
        data = read_regular(directory / f"{name}.uniform-private.bin")
    require(type(data) is bytes and len(data) == PRIVATE_FILE_BYTES, "private exact file extent")
    require(data[:8] == b"OG5PRV01" and struct.unpack_from("<4I", data, 8) == (1, 3, 5, 0),
            "private exact header")
    roots = struct.unpack_from("<5I", data, 24)
    require(roots == PRIVATE_ROOTS, "five actual private roots")
    offset = 44
    segments = []
    for expected_base, expected_size in PRIVATE_SEGMENTS:
        base, size = struct.unpack_from("<2I", data, offset)
        offset += 8
        require((base, size) == (expected_base, expected_size), "actual private segment extent")
        require(base % 4096 == 0 and size % 64 == 0 and base + size <= 1 << 32,
                "private segment bounds")
        segment = data[offset:offset + size]
        require(len(segment) == size, "private segment EOF")
        segments.append((base, segment))
        offset += size
    require(offset == len(data) and sum(len(segment) for _, segment in segments) == PRIVATE_SEGMENT_BYTES,
            "private trailing bytes")
    for index, (base, segment) in enumerate(segments):
        for other_base, other_segment in segments[:index]:
            require(base + len(segment) <= other_base or
                    other_base + len(other_segment) <= base,
                    "private physical VA overlap")
    require(roots == (segments[0][0], segments[1][0], segments[1][0] + 64,
                      segments[2][0], segments[0][0] + 64), "five actual root order")
    groups = (((0, 4), (0, 1), 1), ((1, 2), (4, 5), 3), ((3,), (3,), 2))
    for group_index, ((base, actual), (indices, stage_ids, version)) in enumerate(zip(segments, groups)):
        expected = _expected_segment(base, indices, stage_ids, version, case.views)
        require(len(expected) == len(actual), "canonical private segment size")
        require(actual == expected, f"full canonical GUR metadata/source/default/padding group{group_index}")
    return roots, tuple((base, len(segment)) for base, segment in segments)


def mutation_checks(build, name, data):
    offsets = (8, 24, 44 + 8 + 60, 44 + 8 + 128 + 56, len(data) - 1)
    for offset in offsets:
        changed = bytearray(data)
        changed[offset] ^= 1
        try:
            verify(build, name, bytes(changed))
        except ValueError:
            continue
        raise AssertionError(f"mutated private image escaped: {offset}")
    return len(offsets)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--case", choices=tuple(dict(CASES)))
    parser.add_argument("--skip-mutations", action="store_true")
    args = parser.parse_args()
    build = args.build.resolve(strict=True)
    for name, _ in CASES:
        if args.case and args.case != name:
            continue
        directory = case_directory(build, name)
        data = read_regular(directory / f"{name}.uniform-private.bin")
        roots, segments = verify(build, name, data)
        rejected = 0 if args.skip_mutations else mutation_checks(build, name, data)
        print(f"{name}: whole-private-byte canonical PASS; roots={tuple(hex(root) for root in roots)}; "
              f"segments={segments}; rejects={rejected}")


if __name__ == "__main__":
    main()
