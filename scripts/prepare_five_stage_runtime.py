#!/usr/bin/env python3
"""Prepare the two original no-TF 4x4 five-stage CPU runtime cases."""
import argparse
from fractions import Fraction
import json
from pathlib import Path

from five_stage_inputs import (CASES, FRAME_GUARDED_BYTES, FRAME_PIXEL_BYTES,
                               FRAME_PITCH, FRAME_STORED_BYTES, ROOT,
                               artifact_names, case_directory, json_object, read_regular,
                               require, sha256)
from five_stage_frame_oracle import bits, image, points
from five_stage_private_oracle import verify as verify_private
from five_stage_source_preflight import verify_case

SCHEMA = "opengpu-original-five-runtime-v1"
TASK_PREFIX = "io_task_bits_"
BEGIN_PREFIX = "io_begin_bits_"


def _leaf(target, key, value):
    require(key not in target, f"duplicate runtime leaf: {key}")
    require(type(value) is int and 0 <= value < 1 << 64, f"runtime leaf range: {key}")
    target[key] = str(value)


def _identity(target, prefix):
    for key, value in (("context", 7), ("pass", 8), ("generation", 9)):
        _leaf(target, prefix + key, value)


def _view(target, prefix, base, byte_count, allocation_bytes=None):
    allocation_bytes = byte_count if allocation_bytes is None else allocation_bytes
    for key, value in (("base", base), ("limit", base + byte_count),
                       ("allocationBase", base),
                       ("allocationLimit", base + allocation_bytes)):
        _leaf(target, prefix + key, value)


def _gfs_words(repo, roots, pcs, scratch):
    schema_path = Path(repo) / "docs/abi/typed-fragment-state-v1.json"
    schema = json_object(read_regular(schema_path), "typed fragment schema")
    require(schema["magic"] == "0x47465301" and schema["version"] == 1 and
            schema["words"] == 128 and schema["bytes"] == 512 and schema["engines"] == 4,
            "typed fragment schema header")
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
    fields = schema["fields"]
    names = [field["name"] for field in fields]
    require(len(names) == len(set(names)) and set(values) <= set(names), "typed fragment fields")
    result = [0] * 128
    result[:8] = [0x47465301, 512, 1, 4, 7, 8, 9, 0]
    for field in fields:
        value = values.get(field["name"], 0)
        require(type(field["bits"]) is int and 1 <= field["bits"] <= 64 and
                type(field["word"]) is int and 0 <= field["word"] < 128,
                "typed fragment field shape")
        require(value < 1 << field["bits"], f"typed fragment field range: {field['name']}")
        for index in range((field["bits"] + 31) // 32):
            require(field["word"] + index < 128, "typed fragment field extent")
            result[field["word"] + index] = (value >> (32 * index)) & 0xFFFFFFFF
    return result


def _task(case, roots, pcs, vertex_base, vs_scratch):
    first, _, _, primitive_id = points(case.name)[0]
    task_id = 71
    assignment = {}
    viewport = TASK_PREFIX + "source_viewport_"
    system = viewport + "system_"
    patch = system + "patch_"
    control = patch + "control_"
    layout = control + "patch_context_layout_"
    _identity(assignment, viewport + "identity_")
    for key, value in (("instanceId", 2), ("baseInstance", 11), ("firstPrimitiveId", 201),
                       ("firstSequence", 1000), ("varyingSlots", 16), ("flatMask", 15 << 60)):
        _leaf(assignment, viewport + key, value)
    for key, value in (("version", 1), ("extentX", 4), ("extentY", 4), ("farDepth", bits(1))):
        _leaf(assignment, viewport + "viewport_" + key, value)
    _leaf(assignment, control + "patch_spacing", 0)
    for key, value in (("inputVertices", 2), ("inputComponents", 8), ("outputVertices", 2),
                       ("outputComponents", 8), ("patchComponents", 0)):
        _leaf(assignment, layout + key, value)
    _leaf(assignment, control + "patch_context_taskId", task_id)
    _leaf(assignment, control + "kernel_kernelPc", pcs[1])
    for axis in range(3):
        _leaf(assignment, control + f"kernel_gridSize_{axis}", 1)
        _leaf(assignment, control + f"kernel_localSize_{axis}", 2 if axis == 0 else 1)
    _leaf(assignment, patch + "commandId", 17)
    _leaf(assignment, patch + "evaluationKernel_kernelPc", pcs[2])
    _leaf(assignment, patch + "outputComponents", 8)
    _identity(assignment, system + "uniforms_identity_")
    for stage, root in zip(("control", "evaluation"), roots[1:3]):
        _leaf(assignment, system + f"uniforms_{stage}_resources_version", 3)
        _leaf(assignment, system + f"uniforms_{stage}_resources_address", root)
    raster = TASK_PREFIX + "source_raster_"
    for key, value in (("version", 1), ("screenW", 4), ("screenH", 4),
                       ("pointSize", bits(2)), ("lineWidth", bits(1))):
        _leaf(assignment, raster + key, value)
    _leaf(assignment, TASK_PREFIX + "source_token", 300)
    binding = TASK_PREFIX + "source_inputPatchBinding_"
    _leaf(assignment, binding + "valid", 1)
    for key, value in (("version", 1), ("taskId", task_id),
                       ("inputPatchPrimitiveId", first // 2)):
        _leaf(assignment, binding + "bits_" + key, value)
    vertex = TASK_PREFIX + "vertex_"
    _leaf(assignment, vertex + "version", 1)
    _leaf(assignment, vertex + "taskId", task_id)
    for key, value in (("inputVertices", 2), ("inputComponents", 8), ("outputVertices", 2),
                       ("outputComponents", 8), ("patchComponents", 0)):
        _leaf(assignment, vertex + "layout_" + key, value)
    for key, value in (("version", 1), ("firstOrdinal", first), ("count", 2),
                       ("firstInstance", 2), ("instances", 1)):
        _leaf(assignment, vertex + "slice_" + key, value)
    draw = vertex + "draw_"
    for key, value in (("version", 1), ("shaderPc", pcs[0]), ("attributeSlots", 1),
                       ("varyingSlots", 16), ("varyingMask", 15 << 60)):
        _leaf(assignment, draw + key, value)
    _leaf(assignment, draw + "sampler_texWidth", 1)
    _leaf(assignment, draw + "sampler_texHeight", 1)
    _view(assignment, draw + "scratch_", vs_scratch, 38400)
    _leaf(assignment, draw + "uniforms_version", 1)
    _leaf(assignment, draw + "uniforms_address", roots[0])
    _leaf(assignment, draw + "input_version", 1)
    _identity(assignment, draw + "input_identity_")
    for key, value in (("count", 16), ("instances", 4), ("baseInstance", 11)):
        _leaf(assignment, draw + "input_" + key, value)
    _view(assignment, draw + "input_bindings_0_", vertex_base, 256)
    _leaf(assignment, draw + "input_bindings_0_stride", 16)
    for key, value in (("enabled", 1), ("components", 4)):
        _leaf(assignment, draw + "input_attributes_0_" + key, value)
    for index, (kind, component) in enumerate(case.metadata["vertexComponents"]):
        _leaf(assignment, vertex + f"components_{index}_kind", kind)
        _leaf(assignment, vertex + f"components_{index}_component", component)
    link = TASK_PREFIX + "source_geometryLink_"
    for key, value in (("version", 1), ("domain", 0), ("expectedTesTaskId", task_id),
                       ("expectedTesSequence", 0), ("tesLanguagePatchId", first // 2),
                       ("gsTaskId", 171), ("gsInputPrimitiveId", primitive_id),
                       ("inputSequence", 2000), ("firstOutputSequence", 3000),
                       ("instanceId", 2), ("baseInstance", 11), ("tesOutputComponents", 8)):
        _leaf(assignment, link + key, value)
    _identity(assignment, link + "identity_")
    program = link + "program_"
    _identity(assignment, program + "identity_")
    geometry = case.geometry["geometry"]
    for key, index in (("programVersion", 0), ("inputVertices", 2), ("invocations", 3),
                       ("outputTopology", 4), ("maxVertices", 5), ("inputComponents", 6),
                       ("outputComponents", 7), ("builtinOutputMask", 8),
                       ("vertexVaryingSlots", 9), ("fragmentVaryingSlots", 10)):
        _leaf(assignment, program + key, geometry[index])
    _leaf(assignment, program + "pc", pcs[3])
    code_bytes = len(read_regular(case.directory / f"{case.name}.gs.words"))
    _view(assignment, program + "code_", pcs[3], code_bytes, (code_bytes + 63) & ~63)
    _leaf(assignment, program + "uniforms_version", 2)
    _leaf(assignment, program + "uniforms_address", roots[3])
    _leaf(assignment, program + "flatMask", 15 << 60)
    for index, source in enumerate(case.geometry["inputSources"]):
        _leaf(assignment, program + f"inputSources_{index}", source)
    for index, (valid, component) in enumerate(case.geometry["outputSources"]):
        _leaf(assignment, program + f"outputSources_{index}_valid", valid)
        _leaf(assignment, program + f"outputSources_{index}_bits", component)
    for index, (valid, component) in enumerate(case.geometry["evaluationComponents"]):
        _leaf(assignment, link + f"evaluationComponents_{index}_valid", valid)
        _leaf(assignment, link + f"evaluationComponents_{index}_component", component)
    _leaf(assignment, TASK_PREFIX + "source_geometryCommandId", 31)
    return {"taskId": task_id, "ordinal": first,
            "gsInputPrimitiveId": primitive_id, "assign": assignment}


def make_case(build, repo, name, width, case_index):
    require((name, width) in CASES, "exact four-by-four case scope")
    case = verify_case(case_directory(build, name), name)
    private_path = case_directory(build, name) / f"{name}.uniform-private.bin"
    roots, _ = verify_private(case_directory(build, name), name, read_regular(private_path))
    code_base = 0x110000 if name == "full5" else 0x210000
    pcs = [code_base + 4096 * index for index in range(5)]
    case_base = 0x1000000 + case_index * 0x1000000
    vertex_base = case_base
    vs_scratch = case_base + 0x10000
    gfs_base = case_base + 0x20000
    scratch = [case_base + 0x40000 + index * 0x10000 for index in range(4)]
    arena = case_base + 0x100000
    pool = case_base + 0x200000
    directory = case_base + 0x220000
    begin = {}
    _leaf(begin, BEGIN_PREFIX + "version", 1)
    producer = BEGIN_PREFIX + "bins_producer_"
    _leaf(begin, producer + "version", 1)
    _leaf(begin, producer + "arena_version", 1)
    _identity(begin, producer + "arena_identity_")
    _leaf(begin, producer + "arena_base", arena)
    _leaf(begin, producer + "arena_limit", arena + 16 * 1472)
    for key, value in (("screenWidth", 4), ("screenHeight", 4), ("tileLog2", 3)):
        _leaf(begin, producer + key, value)
    bins = BEGIN_PREFIX + "bins_bins_"
    for key, value in (("version", 3), ("tileCount", 1), ("directoryBase", directory),
                       ("directoryLimit", directory + 64)):
        _leaf(begin, bins + key, value)
    _leaf(begin, bins + "pool_version", 1)
    _identity(begin, bins + "pool_identity_")
    for key, value in (("base", pool), ("limit", pool + 65536), ("pageSizeLog2", 12)):
        _leaf(begin, bins + "pool_" + key, value)
    states = BEGIN_PREFIX + "states_"
    _leaf(begin, states + "version", 1)
    _identity(begin, states + "identity_")
    _leaf(begin, states + "base", gfs_base)
    _leaf(begin, states + "limit", gfs_base + 512)
    _leaf(begin, BEGIN_PREFIX + "scratchStride", 47104)
    for index, base in enumerate(scratch):
        _leaf(begin, BEGIN_PREFIX + f"scratch_{index}_base", base)
        _leaf(begin, BEGIN_PREFIX + f"scratch_{index}_limit", base + 47104)
    _leaf(begin, BEGIN_PREFIX + "image_version", 1)
    _leaf(begin, BEGIN_PREFIX + "image_pixels_version", 1)
    colors = BEGIN_PREFIX + "image_pixels_colors_"
    _leaf(begin, colors + "version", 1)
    _identity(begin, colors + "identity_")
    for key, value in (("tileLog2", 3), ("tileWidth", 4), ("tileHeight", 4),
                       ("reservedBytes", 4096)):
        _leaf(begin, colors + key, value)
    images = []
    for attachment in range(4):
        prefix = colors + f"colors_{attachment}_"
        for key, value in (("enabled", 1), ("format", 26), ("offset", 4096 + attachment * 1024),
                           ("channelMask", 15), ("loadOp", 1)):
            _leaf(begin, prefix + key, value)
        for channel in range(4):
            _leaf(begin, prefix + f"clear_{channel}", 0x10203040 + 16 * attachment + channel)
        base = case_base + 0x400000 + attachment * 0x10000
        view = BEGIN_PREFIX + f"image_views_{attachment}_"
        _view(begin, view, base, FRAME_PIXEL_BYTES, FRAME_STORED_BYTES)
        for key, value in (("imageWidth", 4), ("imageHeight", 4), ("rowPitch", FRAME_PITCH),
                           ("readable", 1), ("writable", 1), ("store", 1)):
            _leaf(begin, view + key, value)
        stored, _, _, pitch = image(name, case.sums, attachment)
        require(pitch == FRAME_PITCH and len(stored) == FRAME_STORED_BYTES, "frame oracle extent")
        images.append({
            "base": base, "bytes": FRAME_PIXEL_BYTES, "rowPitch": pitch,
            "guardedBytes": FRAME_GUARDED_BYTES, "storedBytes": FRAME_STORED_BYTES,
            "expectedWithGuardsHex": stored[:FRAME_GUARDED_BYTES].hex(),
            "expectedStoredHex": stored.hex(),
        })
    vertices = [(0, 0, 0, bits(1)) for _ in range(16)]
    first, xclip, yclip, _ = points(name)[0]
    vertices[first] = tuple(map(bits, (xclip, yclip, 0, 1)))
    vertices[first + 1] = tuple(map(bits, (xclip + Fraction(1, 16), yclip, 0, 1)))
    return {
        "name": name, "width": width, "taskCount": 1,
        "expectedGsPrimitives": 0 if name == "full5" else 1,
        "expectedOriginalTesPrimitives": 1, "begin": begin,
        "tasks": [_task(case, roots, pcs, vertex_base, vs_scratch)],
        "codePcs": pcs, "codeWords": list(case.metadata["stages"][index]["code"]["bytes"] // 4
                                           for index in range(5)),
        "uniformRoots": list(roots), "gfsBase": gfs_base,
        "gfsWords": _gfs_words(repo, roots, pcs, scratch),
        "vertexBase": vertex_base, "vertexWords": [word for vertex in vertices for word in vertex],
        "images": images, "arenaBase": arena, "arenaBytes": 16 * 1472,
        "poolBase": pool, "poolBytes": 65536, "directoryBase": directory,
        "directoryBytes": 64, "vsScratchBase": vs_scratch, "vsScratchBytes": 38400,
        "fsScratchBases": scratch, "fsScratchBytes": 47104,
    }


def prepare(build, repo=ROOT):
    build = Path(build).resolve(strict=True)
    repo = Path(repo).resolve(strict=True)
    cases = [make_case(build, repo, name, width, index)
             for index, (name, width) in enumerate(CASES)]
    original_inputs = {}
    for name, _ in CASES:
        directory = case_directory(build, name)
        for filename in artifact_names(name):
            original_inputs[filename] = sha256(read_regular(directory / filename))
    abi_schema = read_regular(repo / "docs/abi/typed-fragment-state-v1.json")
    return {
        "schema": SCHEMA, "cpuOnly": True, "recordBytes": 1472,
        "properties": {"opengpu.productQualification": "0"}, "supported": [640, 480],
        "abiSchema": "docs/abi/typed-fragment-state-v1.json", "abiSha": sha256(abi_schema),
        "originalInputs": original_inputs, "cases": cases,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--repo", type=Path, default=ROOT)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    plan = prepare(args.build, args.repo)
    output = args.output or (args.build / "five-stage-runtime.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(plan, indent=2) + "\n")
    print("FIVE_STAGE_RUNTIME_PREPARE_PASS cases=[('full5', 4), ('pointcoord', 4)] "
          "TF=false ABI5=true GPU=false")


if __name__ == "__main__":
    main()
