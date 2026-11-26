#!/usr/bin/env python3
"""CPU/runtime preflight and real private-uniform owner fixture for five-stage cases."""
import argparse
import copy
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess
import tempfile
import unittest

from five_stage_inputs import (CASES, CODE_WORDS, FRAME_CLEAR, FRAME_GUARDED_BYTES,
                               FRAME_PIXEL_BYTES, FRAME_PITCH, FRAME_STORED_BYTES,
                               NAMES, PRIVATE_ROOTS, PRIVATE_SEGMENTS, ROOT, STAGE_SUMS,
                               artifact_names, case_directory, json_object, read_regular,
                               require, sha256, source_input_bytes, write_source_input)
from five_stage_frame_oracle import image
from five_stage_private_oracle import mutation_checks as private_mutations
from five_stage_private_oracle import verify as verify_private
from five_stage_source_preflight import mutation_checks as source_mutations
from five_stage_source_preflight import verify_case
from prepare_five_stage_runtime import SCHEMA, prepare

FORBIDDEN_KEYS = frozenset({"feedback", "expectedtf", "transformfeedback"})
CASE_KEYS = {
    "name", "width", "taskCount", "expectedGsPrimitives", "expectedOriginalTesPrimitives",
    "begin", "tasks", "codePcs", "codeWords", "uniformRoots", "gfsBase", "gfsWords",
    "vertexBase", "vertexWords", "images", "arenaBase", "arenaBytes", "poolBase",
    "poolBytes", "directoryBase", "directoryBytes", "vsScratchBase", "vsScratchBytes",
    "fsScratchBases", "fsScratchBytes",
}
IMAGE_KEYS = {"base", "bytes", "rowPitch", "guardedBytes", "storedBytes",
              "expectedWithGuardsHex", "expectedStoredHex"}


def _no_tf(value):
    if type(value) is dict:
        for key, item in value.items():
            require(type(key) is str, "runtime key type")
            normalized = key.lower()
            require(not any(token in normalized for token in FORBIDDEN_KEYS),
                    "transform feedback is outside the five-chain runtime")
            _no_tf(item)
    elif type(value) is list:
        for item in value:
            _no_tf(item)


def _integer(value, label, maximum=0xFFFFFFFF):
    require(type(value) is int and 0 <= value <= maximum, label)
    return value


def _leaves(value, label):
    require(type(value) is dict, f"{label} must be an object")
    for key, item in value.items():
        require(type(key) is str and type(item) is str and
                re_full_decimal(item) and int(item) < 1 << 64, f"canonical runtime leaf: {key}")
    return value


def re_full_decimal(value):
    return value == "0" or (value.isascii() and value.isdigit() and not value.startswith("0"))


def _leaf_value(leaves, key, expected):
    require(key in leaves and int(leaves[key]) == expected, f"runtime leaf {key}")


def _expected_gfs(repo, roots, pcs, scratch):
    schema = json_object(read_regular(Path(repo) / "docs/abi/typed-fragment-state-v1.json"))
    values = {
        "version": 1, "engineCount": 4, "fragment.version": 5,
        "fragment.identity.context": 7, "fragment.identity.pass": 8,
        "fragment.identity.generation": 9, "fragment.shaderPc": pcs[4],
        "fragment.varyingSlots": 16, "fragment.colorSlots": 4,
        "fragment.colorMask": 15, "fragment.sampler.texWidth": 1,
        "fragment.sampler.texHeight": 1, "fragment.uniforms.version": 1,
        "fragment.uniforms.address": roots[4], "tests.version": 1,
        "tests.front.function": 6, "tests.back.function": 6, "blend.version": 1,
    }
    for index in range(4):
        values[f"fragment.colorTypes[{index}]"] = 1
    for index, base in enumerate(scratch):
        prefix = "fragment.scratch." if index == 0 else f"scratch[{index - 1}]."
        values.update({prefix + "base": base, prefix + "limit": base + 47104,
                       prefix + "allocationBase": base,
                       prefix + "allocationLimit": base + 47104})
    result = [0] * 128
    result[:8] = [0x47465301, 512, 1, 4, 7, 8, 9, 0]
    for field in schema["fields"]:
        value = values.get(field["name"], 0)
        require(value < 1 << field["bits"], "GFS field range")
        for index in range((field["bits"] + 31) // 32):
            result[field["word"] + index] = (value >> (32 * index)) & 0xFFFFFFFF
    return result


def _disjoint(left, right, label):
    require(left[1] <= right[0] or right[1] <= left[0], label)


def verify_runtime(plan, build, repo=ROOT):
    build = Path(build).resolve(strict=True)
    repo = Path(repo).resolve(strict=True)
    require(type(plan) is dict and set(plan) == {
        "schema", "cpuOnly", "recordBytes", "properties", "supported", "abiSchema",
        "abiSha", "originalInputs", "cases",
    }, "runtime key set")
    _no_tf(plan)
    require(plan["schema"] == SCHEMA and plan["cpuOnly"] is True, "runtime schema")
    require(plan["recordBytes"] == 1472 and
            plan["properties"] == {"opengpu.productQualification": "0"} and
            plan["supported"] == [640, 480], "runtime capacity scope")
    require(plan["abiSchema"] == "docs/abi/typed-fragment-state-v1.json", "relative ABI schema")
    require(plan["abiSha"] == sha256(read_regular(repo / plan["abiSchema"])), "ABI schema binding")
    expected_inputs = {filename for name, _ in CASES for filename in artifact_names(name)}
    require(type(plan["originalInputs"]) is dict and set(plan["originalInputs"]) == expected_inputs,
            "original input set")
    for filename, digest in plan["originalInputs"].items():
        case_name = filename.split(".", 1)[0]
        require(Path(filename).name == filename and not Path(filename).is_absolute() and
                digest == sha256(read_regular(case_directory(build, case_name) / filename)),
                "original input binding")
    cases = plan["cases"]
    require(type(cases) is list and all(type(case) is dict for case in cases),
            "runtime case list")
    require([(case["name"], case["width"]) for case in cases] == list(CASES),
            "exact ordered full5/pointcoord 4x4 case set")

    immutable = []
    owned = []
    writable = []

    def add_immutable(extent, data):
        for other, other_data in immutable:
            if extent[0] < other[1] and other[0] < extent[1]:
                require(other[0] <= extent[0] and extent[1] <= other[1] and
                        data[other[0] - extent[0]:other[1] - extent[0]] ==
                        other_data[extent[0] - other[0]:extent[1] - other[0]],
                        "conflicting immutable source")
        immutable.append((extent, data))
    for case_index, case in enumerate(cases):
        name, width = case["name"], case["width"]
        require(type(case) is dict and set(case) == CASE_KEYS, "runtime case keys")
        require(type(case["name"]) is str and type(case["width"]) is int and
                type(case["taskCount"]) is int and type(case["expectedGsPrimitives"]) is int and
                type(case["expectedOriginalTesPrimitives"]) is int, "runtime case field types")
        directory = case_directory(build, name)
        source = verify_case(directory, name)
        private_data = read_regular(directory / f"{name}.uniform-private.bin")
        roots, _ = verify_private(directory, name, private_data)
        private_segments = []
        private_offset = 44
        for expected_base, expected_size in PRIVATE_SEGMENTS:
            base, size = struct.unpack_from("<2I", private_data, private_offset)
            private_offset += 8
            require((base, size) == (expected_base, expected_size), "private segment binding")
            private_segments.append((base, private_data[private_offset:private_offset + size]))
            private_offset += size
        require(private_offset == len(private_data), "private EOF")
        require(case["taskCount"] == 1 and case["expectedOriginalTesPrimitives"] == 1,
                "original upstream work")
        require(case["expectedGsPrimitives"] == (0 if name == "full5" else 1),
                "independent GS work")
        require(case["codeWords"] == list(CODE_WORDS[name]), "original code extents")
        require(type(case["codePcs"]) is list and len(case["codePcs"]) == 5, "code PC count")
        require(case["uniformRoots"] == list(PRIVATE_ROOTS) and roots == PRIVATE_ROOTS,
                "private roots")
        require(type(case["gfsWords"]) is list and len(case["gfsWords"]) == 128 and
                all(type(word) is int and 0 <= word <= 0xFFFFFFFF for word in case["gfsWords"]),
                "GFS words")
        require(type(case["vertexWords"]) is list and len(case["vertexWords"]) == 64 and
                all(type(word) is int and 0 <= word <= 0xFFFFFFFF for word in case["vertexWords"]),
                "original vertex words")
        _integer(case["gfsBase"], "GFS base")
        _integer(case["vertexBase"], "vertex base")
        _integer(case["arenaBase"], "arena base")
        _integer(case["poolBase"], "pool base")
        _integer(case["directoryBase"], "directory base")
        _integer(case["vsScratchBase"], "VS scratch base")
        require(case["arenaBytes"] == 16 * 1472 and case["poolBytes"] == 65536 and
                case["directoryBytes"] == 64 and case["vsScratchBytes"] == 38400 and
                case["fsScratchBytes"] == 47104, "logical capacities")
        require(type(case["fsScratchBases"]) is list and len(case["fsScratchBases"]) == 4,
                "FS scratch count")
        for base in case["fsScratchBases"]:
            _integer(base, "FS scratch base")
        begin = _leaves(case["begin"], "begin")
        _leaf_value(begin, "io_begin_bits_states_base", case["gfsBase"])
        _leaf_value(begin, "io_begin_bits_states_limit", case["gfsBase"] + 512)
        _leaf_value(begin, "io_begin_bits_scratchStride", 47104)
        for index, base in enumerate(case["fsScratchBases"]):
            _leaf_value(begin, f"io_begin_bits_scratch_{index}_base", base)
            _leaf_value(begin, f"io_begin_bits_scratch_{index}_limit", base + 47104)
        _leaf_value(begin, "io_begin_bits_bins_producer_arena_base", case["arenaBase"])
        _leaf_value(begin, "io_begin_bits_bins_producer_arena_limit", case["arenaBase"] + case["arenaBytes"])
        _leaf_value(begin, "io_begin_bits_bins_bins_directoryBase", case["directoryBase"])
        _leaf_value(begin, "io_begin_bits_bins_bins_directoryLimit", case["directoryBase"] + 64)
        _leaf_value(begin, "io_begin_bits_bins_bins_pool_base", case["poolBase"])
        _leaf_value(begin, "io_begin_bits_bins_bins_pool_limit", case["poolBase"] + case["poolBytes"])
        require(case["gfsWords"] == _expected_gfs(repo, roots, case["codePcs"], case["fsScratchBases"]),
                "complete ABI5 GFS root")

        case_owned = []
        for stage_index, pc in enumerate(case["codePcs"]):
            _integer(pc, "code PC")
            require(pc % 64 == 0, "code PC alignment")
            data = read_regular(directory / f"{name}.{NAMES[stage_index]}.words")
            require(len(data) == case["codeWords"][stage_index] * 4, "bound code bytes")
            padded = data + bytes((-len(data)) % 64)
            extent = (pc, pc + len(padded))
            for other in case_owned:
                _disjoint(extent, other, "code allocation overlap")
            case_owned.append(extent)
            add_immutable(extent, padded)
        for base, data, label in (
            (case["vertexBase"], struct.pack("<64I", *case["vertexWords"]), "vertex"),
            (case["gfsBase"], struct.pack("<128I", *case["gfsWords"]), "GFS"),
        ):
            require(base % 64 == 0, f"{label} alignment")
            extent = (base, base + len(data))
            for other in case_owned:
                _disjoint(extent, other, f"{label} allocation overlap")
            case_owned.append(extent)
            add_immutable(extent, data)
        for base, segment in private_segments:
            add_immutable((base, base + len(segment)), segment)
            for extent in case_owned:
                _disjoint(extent, (base, base + len(segment)), "case allocation versus private source alias")
        local_writable = [
            (case["vsScratchBase"], case["vsScratchBytes"], "VS scratch"),
            (case["arenaBase"], case["arenaBytes"], "arena"),
            (case["poolBase"], case["poolBytes"], "pool"),
            (case["directoryBase"], case["directoryBytes"], "directory"),
        ]
        local_writable += [(base, case["fsScratchBytes"], "FS scratch")
                           for base in case["fsScratchBases"]]
        require(type(case["images"]) is list and len(case["images"]) == 4, "four MRT images")
        for attachment, runtime_image in enumerate(case["images"]):
            require(type(runtime_image) is dict and set(runtime_image) == IMAGE_KEYS, "image keys")
            _integer(runtime_image["base"], "image base")
            require(runtime_image["bytes"] == FRAME_PIXEL_BYTES and
                    runtime_image["rowPitch"] == FRAME_PITCH and
                    runtime_image["guardedBytes"] == FRAME_GUARDED_BYTES and
                    runtime_image["storedBytes"] == FRAME_STORED_BYTES, "image byte extents")
            local_writable.append((runtime_image["base"], runtime_image["storedBytes"], "image"))
            expected, occupied, coordinates, _ = image(name, source.sums, attachment)
            require(runtime_image["expectedWithGuardsHex"] == expected[:FRAME_GUARDED_BYTES].hex() and
                    runtime_image["expectedStoredHex"] == expected.hex(),
                    "all-pixel/MRT clear/color/padding/guard bytes")
            prefix = f"io_begin_bits_image_views_{attachment}_"
            _leaf_value(begin, prefix + "base", runtime_image["base"])
            _leaf_value(begin, prefix + "limit", runtime_image["base"] + FRAME_PIXEL_BYTES)
            _leaf_value(begin, prefix + "allocationLimit", runtime_image["base"] + FRAME_STORED_BYTES)
            _leaf_value(begin, prefix + "rowPitch", FRAME_PITCH)
            clear_prefix = f"io_begin_bits_image_pixels_colors_colors_{attachment}_"
            for channel in range(4):
                _leaf_value(begin, clear_prefix + f"clear_{channel}", FRAME_CLEAR[attachment][channel])
            if name == "full5":
                require(not occupied, "full5 must remain all clear")
            else:
                require(len(occupied) == 4 and coordinates == {
                    (0, 0): (0x3E000000, 0x3F600000), (1, 0): (0x3F200000, 0x3F600000),
                    (0, 1): (0x3E000000, 0x3EC00000), (1, 1): (0x3F200000, 0x3EC00000),
                }, "one point and PointCoord values")
        for base, size, label in local_writable:
            require(base % 64 == 0 and size % 64 == 0, f"{label} line alignment")
            guarded = (base - 64, base + size + 64)
            for other in writable:
                _disjoint(guarded, other, "cross-case writable/guard alias")
            for other in case_owned:
                _disjoint(guarded, other, f"writable {label} versus immutable source alias")
            for extent, _ in immutable:
                _disjoint(guarded, extent, f"writable {label} versus private/source alias")
            writable.append(guarded)
            case_owned.append(guarded)
        for extent in case_owned:
            for other in owned:
                _disjoint(extent, other, "no-reset case allocation alias")
        owned.extend(case_owned)
        tasks = case["tasks"]
        require(type(tasks) is list and len(tasks) == 1, "one original upstream task")
        task = tasks[0]
        require(type(task) is dict and set(task) == {"taskId", "ordinal", "gsInputPrimitiveId", "assign"},
                "task keys")
        require(type(task["taskId"]) is int and type(task["ordinal"]) is int and
                type(task["gsInputPrimitiveId"]) is int, "task ID types")
        require(task["taskId"] == 71 and task["ordinal"] == 6 and task["gsInputPrimitiveId"] == 0,
                "task/ordinal/GS primitive IDs")
        assign = _leaves(task["assign"], "task assign")
        expected_leaves = {
            "io_task_bits_source_viewport_system_patch_control_patch_context_taskId": 71,
            "io_task_bits_source_inputPatchBinding_bits_taskId": 71,
            "io_task_bits_vertex_taskId": 71,
            "io_task_bits_source_geometryLink_expectedTesTaskId": 71,
            "io_task_bits_source_geometryLink_gsTaskId": 171,
            "io_task_bits_source_inputPatchBinding_bits_inputPatchPrimitiveId": 3,
            "io_task_bits_source_geometryLink_tesLanguagePatchId": 3,
            "io_task_bits_source_geometryLink_gsInputPrimitiveId": 0,
            "io_task_bits_source_viewport_firstPrimitiveId": 201,
            "io_task_bits_vertex_draw_shaderPc": case["codePcs"][0],
            "io_task_bits_source_geometryLink_program_pc": case["codePcs"][3],
            "io_task_bits_vertex_draw_uniforms_address": roots[0],
            "io_task_bits_source_geometryLink_program_uniforms_address": roots[3],
            "io_task_bits_vertex_draw_input_bindings_0_base": case["vertexBase"],
            "io_task_bits_source_geometryLink_program_programVersion": 1,
            "io_task_bits_source_geometryLink_program_inputVertices": 2,
            "io_task_bits_source_geometryLink_program_invocations": 1,
            "io_task_bits_source_geometryLink_program_outputTopology": 0 if name == "pointcoord" else 1,
            "io_task_bits_source_geometryLink_program_maxVertices": 2,
            "io_task_bits_source_geometryLink_program_inputComponents": 8,
            "io_task_bits_source_geometryLink_program_outputComponents": 8,
            "io_task_bits_source_geometryLink_program_builtinOutputMask": 3,
            "io_task_bits_source_geometryLink_program_vertexVaryingSlots": 14,
            "io_task_bits_source_geometryLink_program_fragmentVaryingSlots": 16,
        }
        for key, value in expected_leaves.items():
            _leaf_value(assign, key, value)
        for index in range(68):
            valid = 1 if index < 4 or index >= 64 else 0
            component = index if index < 4 else index - 60 if index >= 64 else 0
            _leaf_value(assign, f"io_task_bits_source_geometryLink_program_outputSources_{index}_valid", valid)
            _leaf_value(assign, f"io_task_bits_source_geometryLink_program_outputSources_{index}_bits", component)
    return True


def runtime_mutations(plan, build, repo=ROOT):
    mutants = []

    def add(label, mutate):
        changed = copy.deepcopy(plan)
        mutate(changed)
        mutants.append((label, changed))

    add("unversioned", lambda value: value.update(schema="old"))
    add("16x16", lambda value: value["cases"][1].update(width=16))
    add("case order", lambda value: value["cases"].reverse())
    add("zero-output line claimed FS", lambda value: value["cases"][0].update(expectedGsPrimitives=1))
    add("wrong VS extent", lambda value: value["cases"][1]["codeWords"].__setitem__(0, 61))
    add("vertex no-reset alias", lambda value: value["cases"][1].update(vertexBase=value["cases"][0]["vertexBase"]))
    add("image no-reset alias", lambda value: value["cases"][1]["images"][0].update(base=value["cases"][0]["images"][0]["base"]))
    add("scratch no-reset alias", lambda value: value["cases"][1].update(vsScratchBase=value["cases"][0]["vsScratchBase"]))
    add("lost GFS root", lambda value: value["cases"][1]["begin"].__setitem__("io_begin_bits_states_base", "0"))
    add("task ID mistaken language ID", lambda value: value["cases"][1]["tasks"][0]["assign"].__setitem__(
        "io_task_bits_source_inputPatchBinding_bits_inputPatchPrimitiveId", "71"))
    add("fake GS ID", lambda value: value["cases"][1]["tasks"][0]["assign"].__setitem__(
        "io_task_bits_source_geometryLink_gsInputPrimitiveId", "201"))
    add("raw location64 relocated", lambda value: value["cases"][1]["tasks"][0]["assign"].__setitem__(
        "io_task_bits_source_geometryLink_program_outputSources_64_bits", "0"))

    def bad_pixel(value):
        runtime_image = value["cases"][1]["images"][0]
        data = bytearray.fromhex(runtime_image["expectedStoredHex"])
        data[64] ^= 1
        runtime_image["expectedStoredHex"] = data.hex()

    add("one component pixel corruption", bad_pixel)

    def bad_gfs(value):
        schema = json_object(read_regular(Path(repo) / "docs/abi/typed-fragment-state-v1.json"))
        word = next(field["word"] for field in schema["fields"]
                    if field["name"] == "fragment.version")
        value["cases"][1]["gfsWords"][word] = 10

    add("GFS ABI10", bad_gfs)

    def bad_frame_byte(offset):
        def mutate(value):
            runtime_image = value["cases"][1]["images"][0]
            data = bytearray.fromhex(runtime_image["expectedStoredHex"])
            data[offset] ^= 1
            runtime_image["expectedStoredHex"] = data.hex()
        return mutate

    add("row padding corruption", bad_frame_byte(64 + 64))
    add("outer guard corruption", bad_frame_byte(FRAME_GUARDED_BYTES))
    add("wrong private roots", lambda value: value["cases"][1].update(uniformRoots=[0] * 5))
    for label, changed in mutants:
        try:
            verify_runtime(changed, build, repo)
        except ValueError:
            continue
        raise AssertionError(f"runtime mutation accepted: {label}")
    return len(mutants)


def compile_owner(repo, temporary, sanitize=False):
    repo = Path(repo)
    source = (repo / "driver/opengpu_hw.c").read_text()
    first = source.index("int opengpu_hw_snapshot_submission_lock(")
    last = source.index("\n}\n", first) + 2
    (temporary / "snapshot_submission_under_test.inc").write_text(source[first:last])
    flags = ["-std=c11", "-O1", "-UNDEBUG", "-Wall", "-Wextra", "-Werror",
             "-Wno-unused-function", "-Wno-unused-parameter", "-Wno-sign-compare",
             "-D_POSIX_C_SOURCE=200809L"]
    if sanitize or os.environ.get("OPENGPU_TEST_SANITIZERS") == "1":
        flags += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
    includes = [temporary, repo / "driver/tests", repo / "driver",
                repo / "driver/tests/private_snapshot_stubs",
                repo / "driver/tests/mmu_stubs", repo / "driver/tests/compute_submit_stubs"]
    binary = temporary / "five-uniform-owner"
    environment = dict(os.environ, TMPDIR=str(temporary))
    subprocess.run(shlex.split(os.environ.get("CC", "cc")) + flags +
                   ["-I" + str(path) for path in includes] +
                   [str(repo / "userspace/tests/native_five_uniform_owner.c"), "-o", str(binary)],
                   check=True, env=environment)
    return binary, environment


def run(build, repo=ROOT, output=None, sanitize=False, skip_owner=False, skip_mutations=False):
    build = Path(build).resolve(strict=True)
    repo = Path(repo).resolve(strict=True)
    for name, _ in CASES:
        directory = case_directory(build, name)
        case = verify_case(directory, name)
        if not skip_mutations:
            source_mutations(case)
    temporary_root = repo / ".artifacts"
    temporary_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="five-stage-", dir=temporary_root) as temporary_name:
        temporary = Path(temporary_name)
        owner = None
        if not skip_owner:
            owner, environment = compile_owner(repo, temporary, sanitize)
        for name, _ in CASES:
            directory = case_directory(build, name)
            case = verify_case(directory, name)
            private_path = directory / f"{name}.uniform-private.bin"
            if owner is not None:
                source_path = temporary / f"{name}.src"
                write_source_input(source_path, case.metadata, case.views)
                subprocess.run([str(owner), str(source_path), str(private_path)],
                               check=True, env=environment, timeout=30)
            private_data = read_regular(private_path)
            verify_private(directory, name, private_data)
            if not skip_mutations:
                private_mutations(directory, name, private_data)
        plan = prepare(build, repo)
        require(verify_runtime(plan, build, repo), "runtime preflight")
        rejected = 0 if skip_mutations else runtime_mutations(plan, build, repo)
        if output is not None:
            output = Path(output)
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(plan, indent=2) + "\n")
    print(f"FIVE_STAGE_RUNTIME_PASS cases={len(CASES)} views=65 owner={not skip_owner} "
          f"runtimeRejects={rejected} TF=false ABI5=true GPU=false")


class OracleUnitTests(unittest.TestCase):
    def test_exact_scope_and_constants(self):
        self.assertEqual(CASES, (("full5", 4), ("pointcoord", 4)))
        self.assertEqual(STAGE_SUMS, (0xEAAF55C1, 0xF3753941, 0xD2F163C1,
                                      0x8B861A41, 0xC6341741))
        self.assertEqual(PRIVATE_ROOTS, (4096, 409600, 409664, 815104, 4160))
        self.assertEqual(PRIVATE_SEGMENTS, ((0x1000, 403200), (0x64000, 403200),
                                            (0xC7000, 202496)))
        self.assertEqual((FRAME_PITCH, FRAME_PIXEL_BYTES, FRAME_GUARDED_BYTES,
                          FRAME_STORED_BYTES), (192, 768, 896, 1024))

    def test_independent_frames(self):
        for attachment in range(4):
            full, occupied, _, _ = image("full5", STAGE_SUMS, attachment)
            self.assertFalse(occupied)
            expected_visible = b"".join(
                struct.pack("<4I", *FRAME_CLEAR[attachment]) * 4 +
                bytes([0xA5]) * (FRAME_PITCH - 64) for _ in range(4))
            self.assertEqual(full[64:64 + FRAME_PIXEL_BYTES], expected_visible)
            point, occupied, coordinates, _ = image("pointcoord", STAGE_SUMS, attachment)
            self.assertEqual(occupied, {(0, 0): 0x87F90241, (1, 0): 0x86D90241,
                                        (0, 1): 0x86590241, (1, 1): 0x87790241})
            self.assertEqual(coordinates, {(0, 0): (0x3E000000, 0x3F600000),
                                           (1, 0): (0x3F200000, 0x3F600000),
                                           (0, 1): (0x3E000000, 0x3EC00000),
                                           (1, 1): (0x3F200000, 0x3EC00000)})
            self.assertEqual(point[FRAME_GUARDED_BYTES:], bytes([0xA5]) * 128)

    def test_source_input_encoding(self):
        views = []
        for stage in range(5):
            for index in range(13):
                size = 4096 if index == 0 else 16384
                views.append((1 if index else stage + 10, index * 16448,
                              bytes([stage + index]) * size))
        data = source_input_bytes({"contextId": 1}, views)
        self.assertEqual(struct.unpack_from("<8sIIII", data), (b"OG5SRC01", 1, 5, 13, 0))
        offset = 24
        for handle, source_offset, payload in views:
            self.assertEqual(struct.unpack_from("<IIQ", data, offset), (handle, len(payload), source_offset))
            offset += 16
            self.assertEqual(data[offset:offset + len(payload)], payload)
            offset += len(payload)
        self.assertEqual(offset, len(data))

    def test_tf_keys_are_rejected(self):
        for key in FORBIDDEN_KEYS:
            with self.assertRaises(ValueError):
                _no_tf({key: 0})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", type=Path)
    parser.add_argument("--repo", type=Path, default=ROOT)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--skip-owner", action="store_true")
    parser.add_argument("--skip-mutations", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test or args.build is None:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(OracleUnitTests)
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        if not result.wasSuccessful():
            raise SystemExit(1)
    if args.build is not None:
        run(args.build, args.repo, args.output, args.sanitize, args.skip_owner, args.skip_mutations)


if __name__ == "__main__":
    main()
