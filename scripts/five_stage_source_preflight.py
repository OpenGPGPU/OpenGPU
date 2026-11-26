#!/usr/bin/env python3
"""Strict CPU preflight for the original Mesa five-stage handoff."""
import argparse
import copy
import json
from pathlib import Path
import struct

from five_stage_inputs import (BINDINGS, CASES, CODE_WORDS, NAMES, STAGES,
                               STAGE_SUMS, CaseData, case_directory, json_object, read_regular,
                               require, sha256, words)


def _uint_array(value, length, label):
    require(type(value) is list and len(value) == length, f"{label} extent")
    require(all(type(item) is int and 0 <= item <= 0xFFFFFFFF for item in value),
            f"{label} field types")
    return value


def _map(value, label):
    require(type(value) is dict and set(value) == {"version", "count", "spans"},
            f"{label} keys")
    require(type(value["version"]) is int and value["version"] in (0, 1) and
            type(value["count"]) is int and 0 <= value["count"] <= 64,
            f"{label} header")
    spans = value["spans"]
    require(type(spans) is list and len(spans) == 64, f"{label} span extent")
    for span in spans:
        _uint_array(span, 7, f"{label} span")
    require(spans[value["count"]:] == [[0] * 7] * (64 - value["count"]),
            f"{label} reserved spans")
    return value


def verify_case(directory, name=None, contents=None):
    directory = Path(directory).resolve(strict=True)
    require(directory.is_dir(), "case input must be a directory")
    name = directory.name if name is None else name
    require(name in dict(CASES), "only full5 and pointcoord are accepted")
    contents = contents or {}
    files = {}

    def read(filename):
        require(type(filename) is str and Path(filename).name == filename,
                "unsafe artifact name")
        if filename in contents:
            data = contents[filename]
            require(type(data) is bytes, "artifact override must be bytes")
        else:
            data = read_regular(directory / filename)
        files[filename] = sha256(data)
        return data

    metadata = json_object(read(f"{name}.five.json"), "five handoff")
    require(set(metadata) == {
        "schema", "contextId", "vertexLayout", "vertexVaryingMask", "vertexColorMask",
        "tess", "vertexComponents", "fragmentComponents", "linkedMaps",
        "tgsiProperties", "stages",
    }, "five handoff key set")
    require(metadata["schema"] == "opengpu-private-original-linked-five-v1", "five handoff schema")
    require(type(metadata["contextId"]) is int and metadata["contextId"] == 1, "original context ID")
    require(metadata["vertexLayout"] == [0, 128, 1, 16, 0], "VS full layout")
    require(metadata["vertexVaryingMask"] == "f000000000000000" and
            metadata["vertexColorMask"] == 0, "VS source location15")
    vertex_components = [[0, index] for index in range(4)]
    vertex_components += [[1, index] for index in range(60, 64)]
    vertex_components += [[0, 0]] * 56
    require(metadata["vertexComponents"] == vertex_components, "VS to TCS original boundary")
    fragment_components = [[1, index] for index in range(4)]
    fragment_components += [[0, 0]] * 60
    fragment_components += [[1, index] for index in range(4, 8)]
    require(metadata["fragmentComponents"] == fragment_components,
            "GS to FS original raw68 boundary")

    expected_layouts = (
        [3, 4, 128, 2, 2, 8, 8, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 1],
        [3, 5, 128, 2, 2, 8, 8, 0, 1, 2, 0, 0, 1, 1, 2, 8, 2, 8, 0, 1, 1],
    )
    tess = metadata["tess"]
    require(type(tess) is list and len(tess) == 2, "TCS/TES count")
    for index, program in enumerate(tess):
        require(type(program) is dict and set(program) == {
            "layout", "selectionCount", "selections", "inputMask", "outputMask",
            "barriers", "inputMap", "outputMap",
        }, "TCS/TES keys")
        _uint_array(program["layout"], 21, "TCS/TES layout")
        require(program["layout"] == expected_layouts[index], "actual TC/TES layout")
        require(program["selectionCount"] == 0 and
                program["selections"] == [[0, 0, 0, 0]] * 61,
                "owned dynamic TES must not use static selection")
        require(program["inputMask"] == ("00000000000000ff" if index == 0 else "0000000000000000") and
                program["outputMask"] == "00000000000000ff" and
                program["barriers"] == (2 if index == 0 else 0),
                "TCS/TES masks and barriers")
        _map(program["inputMap"], "TCS/TES input map")
        _map(program["outputMap"], "TCS/TES output map")
    expected_properties = [
        [[19, 1]], [[10, 2]], [[11, 1], [12, 2], [13, 0], [14, 0], [19, 3]],
        [[0, 1], [1, 0 if name == "pointcoord" else 3], [2, 2], [8, 1]], [],
    ]
    require(metadata["tgsiProperties"] == expected_properties,
            "TGSI queried domain/GS properties")

    geometry = json_object(read(f"{name}.geometry.json"), "geometry handoff")
    require(set(geometry) == {
        "schema", "codeWords", "tesOutputComponents", "geometry", "inputSources",
        "outputSources", "evaluationComponents", "constantBytes", "activeMask", "uniforms",
    }, "geometry handoff key set")
    require(geometry["schema"] == "opengpu-test-only-mesa-geometry-v1", "geometry schema")
    _uint_array(geometry["geometry"], 11, "GS layout")
    require(geometry["geometry"] == [1, 1, 2, 1, 0 if name == "pointcoord" else 1,
                                     2, 8, 8, 3, 14, 16], "GS raw layout")
    require(geometry["codeWords"] == 289 and geometry["tesOutputComponents"] == 8,
            "GS code/TES extent")
    require(geometry["inputSources"] == list(range(4)) + list(range(56, 60)) + [0] * 56,
            "TES to GS original location13")
    require(geometry["outputSources"] == fragment_components and
            geometry["evaluationComponents"] == [[1, index] for index in range(8)] + [[0, 0]] * 56,
            "TES/GS original maps")
    require(geometry["constantBytes"] == [4096] + [16384] * 12 and
            geometry["activeMask"] == 4095, "GS thirteen-view stage")

    packet = read(f"{name}.ognsf")
    require(packet[:8] == b"OGNSF003" and len(packet) >= 8 + 42 * 4, "actual OGNSF003 packet")
    header = struct.unpack_from("<42I", packet, 8)
    expected_header = (
        3, 5, 74, 512, 47104, 240 if name == "pointcoord" else 208,
        15, 0, 0, 0, 1, 128, 16, 4, 427 if name == "pointcoord" else 401,
        0, 1, 224 if name == "pointcoord" else 192, 0, 0xF0000000, 0,
        0xF0000000, 0, 0, 4096, *([16384] * 12), 1, 1, 1, 1, 0,
    )
    require(header == expected_header, "original FS ABI5/planes/mask/geometry/uintMRT contract")
    require(packet[8 + 42 * 4:] == read(f"{name}.fs.words"), "actual original FS words")

    stages = metadata["stages"]
    require(type(stages) is list and len(stages) == 5, "five stage count")
    all_views = []
    sums = []
    for stage_index, stage in enumerate(stages):
        stage_name = NAMES[stage_index]
        require(type(stage) is dict and set(stage) == {
            "name", "stage", "code", "constantBytes", "activeMask", "bindings", "uniforms",
        }, "stage key set")
        require((stage["name"], stage["stage"]) == (stage_name, STAGES[stage_index]),
                "stage identity")
        code = read(f"{name}.{stage_name}.words")
        require(len(code) == CODE_WORDS[name][stage_index] * 4, "original code extent")
        require(stage["code"] == {"handle": stage_index + 1, "offset": "0",
                                  "bytes": len(code), "entryOffset": 0}, "original code view")
        require(stage["constantBytes"] == [4096] + [16384] * 12 and
                stage["activeMask"] == 4095, "full thirteen-view stage")
        require(type(stage["bindings"]) is list and len(stage["bindings"]) == 12 and
                all(type(binding) is int for binding in stage["bindings"]), "binding field types")
        require(tuple(stage["bindings"]) == BINDINGS[stage_index],
                "Mesa logical to original API permutation")
        uniforms = stage["uniforms"]
        require(type(uniforms) is list and len(uniforms) == 13, "all stage views")
        by_api = {}
        stage_views = []
        for view_index, view in enumerate(uniforms):
            require(type(view) is dict and set(view) == {"handle", "offset", "bytes", "file"},
                    "uniform view fields")
            filename = f"{name}.{stage_name}-uniform-{view_index}.bin"
            require(view["file"] == filename, "uniform view path")
            expected_bytes = 4096 if view_index == 0 else 16384
            require(type(view["bytes"]) is int and view["bytes"] == expected_bytes,
                    "uniform view extent")
            require(type(view["offset"]) is str and
                    str(int(view["offset"])) == view["offset"], "exact original offset")
            offset = int(view["offset"])
            require(offset == (0 if view_index == 0 else
                               4 + stage["bindings"][view_index - 1] * 16448),
                    "original API offset authority")
            require(type(view["handle"]) is int and 0 < view["handle"] <= 0xFFFFFFFF,
                    "original GEM handle")
            data = read(filename)
            require(len(data) == expected_bytes, "uniform view EOF")
            actual = words(data)
            expected_words = tuple(
                ((0x76543210 - index - stage_index * 4096) if view_index == 0 else
                 (0x13579BDF + (offset // 4 + index) * 33)) & 0xFFFFFFFF
                for index in range(len(actual))
            )
            require(actual == expected_words, "every original uniform byte fixture pattern")
            if view_index:
                by_api[stage["bindings"][view_index - 1]] = actual
            else:
                default = actual
            stage_views.append((view["handle"], offset, data))
        require(len({view[0] for view in stage_views[1:]}) == 1 and
                stage_views[0][0] != stage_views[1][0], "original source GEM identity")
        fold = default[0] ^ default[1023]
        for api in range((0, 12, 24, 36, 60)[stage_index],
                         (12, 24, 36, 48, 72)[stage_index]):
            block = by_api[api]
            fold = ((fold * 33) ^ block[0] ^ block[4095]) & 0xFFFFFFFF
        sums.append(fold)
        all_views.extend(stage_views)
    require(tuple(sums) == STAGE_SUMS, "independent stage sums")
    require(len({view[0] for index, view in enumerate(all_views) if index % 13}) == 1,
            "all sixty UBOs share the original GEM")
    require(len({all_views[stage * 13][0] for stage in range(5)}) == 5,
            "five independent defaults")
    require(geometry["uniforms"] == stages[3]["uniforms"], "GS handoff view identity")

    linked = metadata["linkedMaps"]
    require(type(linked) is list and len(linked) == 8, "linked map count")
    expected_spans = [
        [[0, 1, 0, 0, 0, 15, 0], [1, 1, 0, 5, 15, 15, 0]],
        [[0, 1, 1, 0, 0, 15, 0], [1, 1, 1, 5, 15, 15, 0]],
        [[0, 1, 1, 0, 0, 15, 0], [1, 4, 0, 31, 0, 15, 1], [2, 1, 1, 5, 7, 15, 0]],
        [[0, 1, 1, 0, 0, 15, 0], [1, 1, 1, 5, 7, 15, 0]],
        [[0, 1, 0, 0, 0, 15, 0], [1, 1, 0, 5, 13, 15, 0]],
        [[0, 1, 1, 0, 0, 15, 0], [1, 1, 1, 5, 13, 15, 0]],
        [[0, 1, 0, 0, 0, 15, 0], [1, 1, 0, 9, 0, 1, 0],
         [2, 1, 0, 22, 0, 1, 0], [3, 1, 0, 5, 15, 15, 0]],
        [[3 if name == "pointcoord" else 2, 1, 0, 5, 15, 15, 0]],
    ]
    for index, (mapping, spans) in enumerate(zip(linked, expected_spans)):
        _map(mapping, "linked map")
        require(mapping["version"] == 1 and mapping["count"] == len(spans) and
                mapping["spans"][:len(spans)] == spans, "complete original linked declarations")
    require(tess[0]["outputMap"] == linked[2] and tess[1]["inputMap"] == linked[3],
            "actual TES owned patch map")
    every_map = linked + [program[key] for program in tess for key in ("inputMap", "outputMap")]
    for index, mapping in enumerate(every_map):
        require(mapping["version"] == (0 if index in (8, 11) else 1), "linked map version")
        if index in (8, 11):
            require(mapping["count"] == 0, "disabled provider map canonical zero")
    return CaseData(name, directory, metadata, geometry, tuple(all_views), tuple(sums), files)


def mutation_checks(case):
    metadata = case.metadata
    mutants = []
    changed = copy.deepcopy(metadata)
    changed["stages"][4]["bindings"][0], changed["stages"][4]["bindings"][1] = (
        changed["stages"][4]["bindings"][1], changed["stages"][4]["bindings"][0])
    mutants.append((changed, None))
    changed = copy.deepcopy(metadata)
    changed["fragmentComponents"][64] = [1, 0]
    mutants.append((changed, None))
    changed = copy.deepcopy(metadata)
    changed["tgsiProperties"][2][0][1] = 4
    mutants.append((changed, None))
    changed = copy.deepcopy(metadata)
    changed["stages"][2]["uniforms"][12]["bytes"] -= 4
    mutants.append((changed, None))
    changed = copy.deepcopy(metadata)
    changed["linkedMaps"][0]["spans"][63][0] = 1
    mutants.append((changed, None))
    filename = case.metadata["stages"][3]["uniforms"][2]["file"]
    data = bytearray(read_regular(case.directory / filename))
    data[-1] ^= 1
    mutants.append((metadata, {filename: bytes(data)}))
    packet = bytearray(read_regular(case.directory / f"{case.name}.ognsf"))
    struct.pack_into("<I", packet, 12, 10)
    mutants.append((metadata, {f"{case.name}.ognsf": bytes(packet)}))
    for changed, overrides in mutants:
        contents = dict(overrides or {})
        contents[f"{case.name}.five.json"] = json.dumps(changed, separators=(",", ":")).encode()
        try:
            verify_case(case.directory, case.name, contents)
        except ValueError:
            continue
        raise AssertionError("source semantic mutation escaped")
    return len(mutants)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--case", choices=tuple(dict(CASES)))
    parser.add_argument("--skip-mutations", action="store_true")
    args = parser.parse_args()
    selected = (args.case,) if args.case else tuple(name for name, _ in CASES)
    for name in selected:
        case = verify_case(case_directory(args.build, name), name)
        rejected = 0 if args.skip_mutations else mutation_checks(case)
        print(f"{name}: five-stage/65-view/maps/OGNSF-ABI5 PASS; "
              f"sums={' '.join(hex(value) for value in case.sums)}; rejects={rejected}")


if __name__ == "__main__":
    main()
