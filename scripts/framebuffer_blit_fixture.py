"""Strict reader for temporary actual-caller framebuffer inputs; no GPU claim."""
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import struct


NAMES = tuple([f"{kind}-{i}" for kind in ("nearest", "linear") for i in range(16)] +
              ["depth-0-1", "depth-0-2", "depth-0-3", "depth-1-1"] +
              [f"copy-region-{i}" for i in range(6)] + ["same-gem"])


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def checked_inputs(directory, source_root, manifest_sha256):
    """Require an external manifest pin and current original capture sources.

    This binds CPU inputs only. It deliberately has no DUT/backend/profile
    receipt and cannot authorize relabeling a generated hardware model.
    """
    directory, source_root = Path(directory), Path(source_root).resolve(strict=True)
    if directory.is_symlink() or not directory.is_dir() or not re.fullmatch("[0-9a-f]{64}", manifest_sha256):
        raise ValueError("invalid blit input directory/manifest pin")
    manifest = directory / "manifest.json"
    if manifest.is_symlink() or digest(manifest) != manifest_sha256:
        raise ValueError("blit capture manifest changed")
    data = json.loads(manifest.read_text())
    if data["schema"] != "opengpu-framebuffer-blit-inputs-v1" or data["cpuModelOnly"] is not True or \
            data["gpuExecution"] is not False or data["sourceRoot"] != str(source_root):
        raise ValueError("blit capture origin/scope differs")
    inputs = data["sourceInputs"]
    required = [source_root / name for name in ("scripts/test_gallium_compute.py",
        "scripts/framebuffer_blit_fixture.py", "userspace/tests/framebuffer_blit_callback.c",
        "userspace/gallium/opengpu_context.c", "userspace/pipe_opengpu.c",
        "userspace/opengpu_framebuffer_blit.h", "driver/opengpu_shader_validator.h")]
    if not all(str(path) in inputs for path in required):
        raise ValueError("blit capture source closure is incomplete")
    for name, value in inputs.items():
        path = Path(name)
        if not path.is_absolute() or path.resolve(strict=True) != path or digest(path) != value:
            raise ValueError("blit capture source changed: " + name)
    compiler = Path(data["compiler"]["path"])
    if not compiler.is_absolute() or digest(compiler) != data["compiler"]["sha256"]:
        raise ValueError("blit capture compiler changed")
    if set(data["cases"]) != set(NAMES) or {p.name for p in directory.iterdir()} != \
            {"manifest.json", *(name + ".blit" for name in NAMES)}:
        raise ValueError("incomplete or mixed blit capture directory")
    result = {}
    for name in NAMES:
        path = directory / (name + ".blit")
        if path.is_symlink() or digest(path) != data["cases"][name]:
            raise ValueError("blit capture input changed: " + name)
        result[name] = BlitFixture.read(path.read_bytes())
    return result


@dataclass(frozen=True)
class View:
    handle: int
    allocation: int
    limit: int
    access: int


@dataclass(frozen=True)
class BlitFixture:
    context: int
    grid: tuple
    local: tuple
    views: tuple
    shader: bytes
    kernarg: bytes
    source: bytes
    destination: bytes
    coordinates: bytes
    expected: bytes

    @classmethod
    def read(cls, data):
        def need(ok, message):
            if not ok:
                raise ValueError(message)
        need(type(data) is bytes and len(data) >= 96 and data[:8] == b"OGBLT001", "blit magic/header")
        values = struct.unpack_from("<22I", data, 8)
        version, context, shader_bytes, argument_bytes = values[:4]
        need(version == 1 and 0 < context < 512 and shader_bytes == 2048 and argument_bytes == 64,
             "blit capture version/context/program extents")
        grid, local = values[4:7], values[7:10]
        need(all(0 < n <= 8 for n in grid) and grid[2] == 1 and local == (1, 1, 1), "blit launch dimensions")
        views = tuple(View(*values[10 + i * 4:14 + i * 4]) for i in range(3))
        need(tuple(v.access for v in views) == (1, 3, 1), "blit view permissions")
        for view in views:
            need(0 < view.handle < 512 and 0 < view.limit <= view.allocation <= 65536 and
                 view.allocation % 64 == 0 and view.limit % 4 == 0, "blit view bounds")
        need(views[2].handle not in (views[0].handle, views[1].handle), "mutable coordinate alias")
        need(len(data) == 96 + shader_bytes + argument_bytes + sum(v.allocation for v in views) +
             views[1].allocation, "blit payload length")
        cursor = 96
        def take(count):
            nonlocal cursor
            result = data[cursor:cursor + count]
            cursor += count
            return result
        shader, kernarg = take(shader_bytes), take(argument_bytes)
        source, destination, coordinates = (take(v.allocation) for v in views)
        expected = take(views[1].allocation)
        need(kernarg == struct.pack("<3I", *grid) + bytes(52), "blit original kernarg")
        words = struct.unpack("<512I", shader)
        need(words.count(0x30500073) == 1, "blit cease count")
        used = words.index(0x30500073) + 1
        need(used > 30 and not any(words[used:]) and words[0] == 0x01007557, "blit program extent")
        descriptor = struct.unpack_from("<24I", coordinates)
        need(descriptor[0] in (1, 2, 3) and descriptor[1] == 96 and
             not descriptor[2] & ~(3 if descriptor[0] == 3 else 1), "blit descriptor version/flags")
        need(not any(coordinates[96:128]), "blit coordinate reserved words")
        step = 4 if descriptor[2] & 2 else 1
        need(views[2].limit == (32 + (grid[0] + grid[1]) * step) * 4, "blit exact coordinate view")
        if views[0].handle == views[1].handle:
            need(source == destination and views[0].allocation == views[1].allocation and descriptor[0] >= 2,
                 "same-GEM initial memory differs")
        return cls(context, grid, local, views, shader, kernarg, source, destination, coordinates, expected)
