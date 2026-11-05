#!/usr/bin/env python3
"""Pure resource data and checks for the C-prepared 4x4 native host witness."""
import argparse
import json
from pathlib import Path
import struct


ROOT = Path(__file__).resolve().parents[1]
RESOURCE_VA = 0xD8000000
TEXTURE_DATA = 0xA00
UNIFORM_DATA = 0xB00
PACKET = 0x2C0


def schema(filename, layout=None):
    value = json.loads((ROOT / "docs/abi" / filename).read_text())
    if layout is not None:
        value = next(item for item in value["layouts"] if item["name"] == layout)
    return value


def word(data, offset):
    return struct.unpack_from("<I", data, offset)[0]


def put(data, offset, value):
    struct.pack_into("<I", data, offset, value & 0xFFFFFFFF)


def record(layout, values):
    data = bytearray(layout["bytes"])
    for i, value in enumerate((int(layout["magic"], 0), len(data), 1)):
        put(data, i * 4, value)
    fields = {field["name"]: field for field in layout["fields"]}
    assert set(values) <= fields.keys()
    for name, value in values.items():
        field = fields[name]
        assert 0 <= value < 1 << field["bits"], (name, value)
        for index in range((field["bits"] + 31) // 32):
            put(data, (field["word"] + index) * 4, value >> (32 * index))
    return data


def fragment_program(texture_host=False):
    def addi(rd, rs, immediate):
        return ((immediate & 4095) << 20) | (rs << 15) | (rd << 7) | 0x13

    def broadcast(rd, rs):
        return (23 << 26) | (1 << 25) | (rs << 15) | (4 << 12) | (rd << 7) | 0x57

    def store(base, vector):
        return (1 << 25) | (base << 15) | (6 << 12) | (vector << 7) | 0x27

    if texture_host:
        def address(rd, base, offset):
            # The default128-thread SoA has512-byte fields; color offsets
            # exceed ADDI's signed12-bit range. Never truncate them.
            upper = (offset + 2048) >> 12
            lower = offset - (upper << 12)
            assert -2048 <= lower < 2048
            return [(upper << 12) | (rd << 7) | 0x37, addi(rd, rd, lower),
                    (rd << 20) | (base << 15) | (rd << 7) | 0x33]
        code = [(2 << 20) | (8 << 15) | (1 << 12) | (7 << 7) | 0x13,
                (7 << 20) | (1 << 15) | (7 << 7) | 0x33,
                (3 << 30) | (0x10 << 20) | (4 << 15) | (7 << 12) | 0x57,
                RESOURCE_VA | (5 << 7) | 0x37, addi(5, 5, PACKET), addi(15, 0, 31),
                (2 << 25) | (15 << 20) | (5 << 15) | (10 << 7) | 0x0B,
                (7 << 25) | (11 << 7) | 0x0B,
                addi(5, 5, 64), (2 << 25) | (15 << 20) | (5 << 15) | (12 << 7) | 0x0B,
                0x3F800000 | (13 << 7) | 0x37]
        for color, registers in enumerate(((10, 11, 12, 13), (12, 10, 11, 13),
                                           (11, 12, 10, 13), (10, 10, 12, 13))):
            for component, register in enumerate(registers):
                code += [broadcast(1, register)] + address(6, 7, (10 + 4 * color + component) * 512) + [store(6, 1)]
        return code + [0x30500073]

    # Preserve the existing eight-invocation, 32-byte field-stride output ABI.
    return [
        (2 << 20) | (8 << 15) | (1 << 12) | (7 << 7) | 0x13,  # slli x7,x8,2
        (7 << 20) | (1 << 15) | (7 << 7) | 0x33,             # add x7,x1,x7
        (3 << 30) | (0x10 << 20) | (4 << 15) | (7 << 12) | 0x57,
        RESOURCE_VA | (5 << 7) | 0x37, addi(5, 5, PACKET),
        (2 << 25) | (5 << 15) | (10 << 7) | 0x0B,           # tex.fetch.r x10,x5,x0
        (7 << 25) | (11 << 7) | 0x0B,                       # uld x11,x0,x0
        broadcast(1, 10), addi(6, 7, 10 * 32), store(6, 1),
        broadcast(1, 11), addi(6, 7, 11 * 32), store(6, 1),
        broadcast(1, 0), addi(6, 7, 12 * 32), store(6, 1),
        0x3F800000 | (12 << 7) | 0x37,  # blue=0, alpha=1
        broadcast(1, 12), addi(6, 7, 13 * 32), store(6, 1),
        0x30500073,
    ]


def configure_texture_host(directory, generation):
    """Pure data changes for the single default-capacity opt-in host fixture."""
    for filename, layout in (("job.bin", schema("native-tessellation-quad-job-v3.json")),
                             ("draws.bin", schema("native-tessellation-quad-sequence-v3.json")),
                             ("frame.bin", schema("native-binned-graphics-v1.json")["frame"]),
                             ("states.bin", schema("typed-fragment-state-v1.json"))):
        data = bytearray((directory / filename).read_bytes())
        fields = {item["name"]: item for item in layout["fields"]}
        def set_field(name, value):
            entry = fields[name]
            assert 0 <= value < 1 << entry["bits"]
            for i in range((entry["bits"] + 31) // 32):
                put(data, (entry["word"] + i) * 4, value >> (32 * i))
        for name in fields:
            if name.endswith(".generation"):
                set_field(name, generation)
        if filename == "frame.bin":
            set_field("frame.scratchStride", 16384)
            set_field("frame.states.limit", 0x8200)  # one referenced GFS, no second slot
            set_field("frame.bins.bins.pool.pageSizeLog2", 12)
            for engine in range(2):
                set_field(f"frame.scratch[{engine}].limit", 0x100000 + engine * 0x10000 + 16384)
            for color in range(4):
                prefix = f"frame.image.pixels.colors.colors[{color}]."
                for name, value in {"enabled": 1, "format": 3, "offset": 4096 * (color + 1),
                                    "channelMask": 15, "loadOp": 1}.items():
                    set_field(prefix + name, value)
                for component, value in enumerate((0x3DCCCCCD, 0x3E4CCCCD, 0x3E99999A, 0x3ECCCCCD)):
                    set_field(prefix + f"clear[{component}]", value)
            for name, value in {"depth.format": 3, "depth.offset": 0, "depth.loadOp": 1,
                                "depth.clearDepth": 0x3F800000, "stencil.format": 6,
                                "stencil.offset": 256, "stencil.loadOp": 1,
                                "stencil.clearStencil": 17}.items():
                set_field("frame.image.pixels." + name, value)
            for view in range(6):
                prefix = f"frame.image.views[{view}]."
                start, size = 0x600000 + 4096 * view, 16 if view == 5 else 64
                for name, value in {"allocationBase": start, "allocationLimit": start + 64,
                                    "base": start, "limit": start + size, "imageWidth": 4, "imageHeight": 4,
                                    "rowPitch": 4 if view == 5 else 16, "store": 1, "readable": 1, "writable": 1}.items():
                    set_field(prefix + name, value)
        elif filename == "states.bin":
            put(data, 6 * 4, generation)
            set_field("fragment.colorSlots", 4); set_field("fragment.colorMask", 15)
            for prefix in ("fragment.scratch.", "scratch[0]."):
                start = word(data, fields[prefix + "base"]["word"] * 4)
                set_field(prefix + "limit", start + 16384)
                set_field(prefix + "allocationLimit", start + 16384)
            for name, value in {"depthEnable": 1, "depthWrite": 1, "depthFunction": 0,
                                "stencilEnable": 1}.items():
                set_field("tests." + name, value)
            for face in ("front", "back"):
                for name, value in {"function": 6, "reference": 42, "readMask": 255,
                                    "writeMask": 255, "depthPass": 2}.items():
                    set_field(f"tests.{face}." + name, value)
        (directory / filename).write_bytes(data)


def verify_texture_host_program():
    """Decode actual RV32 address instructions for all8 default16-lane warps."""
    program = fragment_program(True)
    for warp in range(8):
        registers = [0] * 32
        registers[1], registers[4], registers[8] = 0x18000000, 4, warp * 16
        stores, packets = [], []
        for instruction in program:
            opcode = instruction & 127
            rd, rs1, rs2 = ((instruction >> shift) & 31 for shift in (7, 15, 20))
            immediate = instruction >> 20
            if immediate & 2048: immediate -= 4096
            if opcode == 0x37:
                registers[rd] = instruction & 0xFFFFF000
            elif opcode == 0x13:
                function = (instruction >> 12) & 7
                assert function in (0, 1)
                registers[rd] = (registers[rs1] + immediate if function == 0 else registers[rs1] << (rs2 & 31)) & 0xFFFFFFFF
            elif opcode == 0x33:
                assert instruction >> 25 == 0 and (instruction >> 12) & 7 == 0
                registers[rd] = (registers[rs1] + registers[rs2]) & 0xFFFFFFFF
            elif opcode == 0x0B and instruction >> 25 == 2:
                packets.append((registers[rs1], registers[rs2]))
            elif opcode == 0x27:
                stores.append(registers[rs1])
            else:
                assert opcode in (0x0B, 0x57, 0x73)
            registers[0] = 0
        assert packets == [(RESOURCE_VA + PACKET, 31), (RESOURCE_VA + PACKET + 64, 31)]
        assert stores == [0x18000000 + warp * 64 + field * 512 for field in range(10, 26)]
        for field, base in enumerate(stores, 10):
            for lane in range(16):
                assert base + lane * 4 == 0x18000000 + field * 512 + 4 * (warp * 16 + lane)
                assert base + lane * 4 + 4 <= 0x18000000 + 14336
    return len(program)


def prepare(directory, texture_bits=0x3F000000, uniform_bits=0x3E800000,
            texture_host=False, base_texture_bits=0x3F400000, generation=9):
    directory = Path(directory)
    if texture_host:
        verify_texture_host_program()
        configure_texture_host(directory, generation)
    job = (directory / "job.bin").read_bytes()
    identity = {"identity.context": word(job, 24 * 4),
                "identity.pass": word(job, 26 * 4),
                "identity.generation": word(job, 25 * 4)}
    page = bytearray(4096)

    def install(offset, filename, values, layout=None):
        encoded = record(schema(filename, layout), {**identity, **values})
        assert offset % 64 == 0 and offset + len(encoded) <= len(page)
        page[offset:offset + len(encoded)] = encoded

    va = lambda offset: RESOURCE_VA + offset
    install(0, "native-texture-v1.json", {
        "stage": 1, "bindingCount": 1, "bindingsBase": va(0x100), "bindingsLimit": va(0x140),
        "imagesBase": va(0x180), "imagesLimit": va(0x200),
        "samplersBase": va(0x280), "samplersLimit": va(0x2C0)}, "root")
    install(0x80, "native-uniform-v1.json", {
        "stage": 1, "activeMask": 1, "bindingsBase": va(0x400), "bindingsLimit": va(0xA00)}, "root")
    install(0x100, "native-texture-v1.json", {"stage": 1}, "binding")
    format_id = next(item["id"] for item in schema("texture-image-format-v1.json")["formats"]
                     if item["name"] == "R32F")
    install(0x180, "texture-image-binding-v1.json", {
        "format": format_id, "rootWidth": 1, "rootHeight": 1, "rootDepth": 1, "layers": 1,
        "allocationBase": va(TEXTURE_DATA), "allocationLimit": va(TEXTURE_DATA + 64),
        "levelsVersion": 1, "levelsBase": va(0x200), "levelsLimit": va(0x280)})
    install(0x200, "texture-image-level-v1.json", {
        "view.allocationBase": va(TEXTURE_DATA), "view.allocationLimit": va(TEXTURE_DATA + 64),
        "view.base": va(TEXTURE_DATA), "view.texWidth": 1, "view.texHeight": 1,
        "view.texDepth": 1, "view.layers": 1, "view.rowPitch": 4, "view.blockBytes": 4})
    install(0x280, "texture-sampler-v1.json", {"state.maxLod": 0x41700000, "state.compareFunction": 3})
    install(PACKET, "native-texture-v1.json", {}, "packet")
    install(0x400, "native-uniform-v1.json", {
        "stageMask": 2, "base": va(UNIFORM_DATA), "limit": va(UNIFORM_DATA + 4),
        "allocationBase": va(UNIFORM_DATA), "allocationLimit": va(UNIFORM_DATA + 64), "readOnly": 1}, "binding")
    put(page, TEXTURE_DATA, texture_bits)
    put(page, UNIFORM_DATA, uniform_bits)
    if texture_host:
        # Source-page descriptor bytes are retained fixture scaffolding. The
        # executed metadata comes entirely from the typed C plans/job tail.
        install(PACKET, "native-texture-v1.json", {"level": 1}, "packet")
        install(PACKET + 64, "native-texture-v1.json", {}, "packet")
        for i in range(4): put(page, TEXTURE_DATA + 4 * i, base_texture_bits)
        put(page, TEXTURE_DATA + 64, texture_bits)
    (directory / "resources.bin").write_bytes(page)

    states = bytearray((directory / "states.bin").read_bytes())
    fields = {f["name"]: f for f in schema("typed-fragment-state-v1.json")["fields"]}
    for name, value in {"fragment.resources.version": 1, "fragment.resources.address": va(0),
                        "fragment.uniforms.version": 1, "fragment.uniforms.address": va(0x80),
                        "fragment.shaderPc": 0x2000}.items():
        put(states, fields[name]["word"] * 4, value)
    (directory / "states.bin").write_bytes(states)
    code = bytearray((directory / "code.bin").read_bytes())
    assert len(code) == 0x3000
    for i, instruction in enumerate(fragment_program(texture_host)):
        put(code, 0x1000 + i * 4, instruction)
    (directory / "code.bin").write_bytes(code)


def validate_prepared(directory):
    """Walk the serialized Sv32 leaves independently of the production C MMU."""
    directory = Path(directory)
    fixture = json.loads((directory / "prepared.json").read_text())
    assert fixture["version"] == 1
    regions = {item["name"]: item for item in fixture["regions"]}
    assert len(regions) == len(fixture["regions"])
    assert {"vm-root", "job", "frame", "draws", "states", "patch-data", "frame-data", "root-data", "code"} <= regions.keys()
    assert len({item["file"] for item in fixture["regions"]}) == len(regions)
    images = {}
    spans = []
    for name, region in regions.items():
        assert Path(region["file"]).name == region["file"]
        data = (directory / region["file"]).read_bytes()
        assert len(data) == region["bytes"] > 0
        assert 0 <= region["dma"] < region["dma"] + len(data) <= 1 << 32
        assert 0 <= region["va"] < 1 << 32 and region["va"] + len(data) <= 1 << 32
        assert all(region["dma"] + len(data) <= start or end <= region["dma"] for start, end in spans)
        spans.append((region["dma"], region["dma"] + len(data)))
        images[name] = data

    def read(address, size=4):
        for name, region in regions.items():
            if region["dma"] <= address and address + size <= region["dma"] + region["bytes"]:
                offset = address - region["dma"]
                return images[name][offset:offset + size]
        raise AssertionError(f"physical address absent from C export: {address:x}")

    def translate(address):
        root = word(read(fixture["rootDma"] + (address >> 22) * 4), 0)
        assert root & 0x3FF == 1
        leaf = word(read((root >> 10) * 4096 + ((address >> 12) & 1023) * 4), 0)
        assert leaf & 0x43 == 0x43 and not leaf & 0x30
        return (leaf >> 10) * 4096 + (address & 4095), leaf & 14, (leaf >> 8) & 3

    registers = dict(fixture["satpWrites"])
    assert set(registers) == {0x14C, 0x150}
    assert registers[0x14C] == registers[0x150]
    satp = registers[0x14C]
    assert satp >> 31 == 1 and (satp & 0xFFFFF) * 4096 == fixture["rootDma"]
    assert (satp >> 22) & 0x1FF == fixture["asid"]
    assert fixture["tlbWrites"] and all(address == 0x154 and value in (1, 2 | (fixture["asid"] << 3))
                                      for address, value in fixture["tlbWrites"])
    assert [0x154, 2 | (fixture["asid"] << 3)] in fixture["tlbWrites"]
    assert fixture["snapshotInvalidations"] == [[regions[name]["dma"], regions[name]["bytes"]]
                                               for name in ("job", "frame", "draws", "states")]
    for name, region in regions.items():
        if not region["va"]:
            continue
        for offset in range(0, region["bytes"], 4096):
            physical, permissions, policy = translate(region["va"] + offset)
            assert physical == region["dma"] + offset and physical != region["va"] + offset
            if name in ("job", "frame", "draws", "states"):
                assert permissions == 2, (name, permissions)
            assert policy == (0 if name in ("patch-data", "frame-data") else 2), (name, policy)
            if name == "code":
                assert permissions & 8
    command = fixture["commandArguments"]
    assert command["opcode"] == 15 and command["bytes"] == 512
    assert command["source"] == regions["job"]["va"] and command["destination"] == command["source"] + 512
    assert command["width"] == fixture["context"]
    assert word(images["job"], 21 * 4) == regions["frame"]["va"]
    assert word(images["job"], 16 * 4) == regions["draws"]["va"]
    fields = {f["name"]: f["word"] for f in schema("typed-fragment-state-v1.json")["fields"]}
    texture_owned = fixture.get("textureOwned", False)
    texture_host = fixture.get("textureHost", False)
    texture_va = word(images["states"], fields["fragment.resources.address"] * 4)
    if not texture_owned:
        assert texture_va == RESOURCE_VA
    assert translate(RESOURCE_VA)[0] == regions["root-data"]["dma"]
    assert word(images["root-data"], 0) == int(schema("native-texture-v1.json", "root")["magic"], 0)
    # Follow the actual serialized GFS -> GUR -> active/default GUB -> data,
    # including relocated metadata. This oracle does not assume root+128 or
    # table+0x400 and checks every data-line footprint via an independent walk.
    owned = fixture.get("uniformOwned", False)
    uniform_va = word(images["states"], fields["fragment.uniforms.address"] * 4)
    identity = {"identity.context": fixture["context"],
                "identity.pass": word(images["job"], 26 * 4),
                "identity.generation": word(images["job"], 25 * 4)}

    def read_va(address, size, metadata=False):
        assert size > 0 and 0 <= address < address + size <= 1 << 32
        physical = translate(address)[0]
        probes = [address, address + size - 1] + list(range((address + 4095) & ~4095, address + size, 4096))
        for probe in probes:
            pa, permissions, policy = translate(probe)
            assert pa == physical + probe - address and policy == 2
            if owned and metadata:
                assert permissions == 2
        if owned and metadata:
            assert regions["job"]["va"] + 512 <= address < address + size <= regions["job"]["va"] + regions["job"]["bytes"]
        return read(physical, size)

    def decode(address, kind=None, filename="native-uniform-v1.json"):
        layout = schema(filename, kind)
        data = read_va(address, layout["bytes"], metadata=True)
        values = {}
        for field in layout["fields"]:
            raw = sum(word(data, (field["word"] + i) * 4) << (32 * i)
                      for i in range((field["bits"] + 31) // 32))
            assert raw < 1 << field["bits"]
            values[field["name"]] = raw
        assert data == record(layout, values), "noncanonical resource metadata"
        assert all(values[name] == value for name, value in identity.items())
        return values

    root = decode(uniform_va, "root")
    assert root["stage"] == 1 and root["activeMask"] == 1
    if owned:
        assert uniform_va == regions["job"]["va"] + 512
        assert regions["job"]["bytes"] == 512 + 1664 + (73856 if texture_host else 2432 if texture_owned else 0)
    else:
        assert uniform_va == RESOURCE_VA + 128
    mapping = [(root["mapLow"] if i < 6 else root["mapHigh"]) >> (5 * (i % 6)) & 31 for i in range(12)]
    assert all(index < 24 if root["activeMask"] & (1 << i) else index == 0 for i, index in enumerate(mapping))
    assert root["bindingsBase"] % 64 == 0 and root["bindingsLimit"] >= root["bindingsBase"] + 1536
    table = read_va(root["bindingsBase"], 1536, metadata=True)
    selected = {mapping[i] for i in range(12) if root["activeMask"] & (1 << i)}

    def check_view(address, index):
        view = decode(address, "binding")
        assert view["bindingIndex"] == index and view["stageMask"] == 2 and view["readOnly"] == 1
        assert view["base"] % (4 if index == 31 else 256) == 0
        assert 0 < view["limit"] - view["base"] <= (4096 if index == 31 else 16384)
        assert view["allocationBase"] % 64 == view["allocationLimit"] % 64 == 0
        assert view["allocationBase"] <= view["base"] < view["limit"] <= view["allocationLimit"]
        footprint_base, footprint_end = view["base"] & ~63, (view["limit"] + 63) & ~63
        assert view["allocationBase"] <= footprint_base < footprint_end <= view["allocationLimit"]
        read_va(footprint_base, footprint_end - footprint_base)
        assert view["base"] == RESOURCE_VA + UNIFORM_DATA + (4 if index == 31 else 0)
        assert read_va(view["base"], 4) == images["root-data"][UNIFORM_DATA + (4 if index == 31 else 0):UNIFORM_DATA + (8 if index == 31 else 4)]

    for index in selected:
        check_view(root["bindingsBase"] + 64 * index, index)
    if owned:
        for index in set(range(24)) - selected:
            assert table[index * 64:(index + 1) * 64] == bytes(64)
        assert root["defaultPresent"] == 1
    if root["defaultPresent"]:
        check_view(root["defaultView"], 31)
    else:
        assert root["defaultView"] == 0
    if texture_host:
        validate_texture_host(fixture, regions, images, texture_va, decode, read_va, translate)
    elif texture_owned:
        assert owned and texture_va == regions["job"]["va"] + 512 + 1664
        texture_root = decode(texture_va, "root", "native-texture-v1.json")
        assert texture_root["stage"] == 1 and texture_root["bindingCount"] == 1
        binding_base = texture_root["bindingsBase"]
        image_base = texture_root["imagesBase"]
        sampler_base = texture_root["samplersBase"]
        assert binding_base == texture_va + 128
        assert texture_root["bindingsLimit"] == image_base == binding_base + 64
        assert texture_root["imagesLimit"] == sampler_base == image_base + 128
        assert texture_root["samplersLimit"] == sampler_base + 64
        binding = decode(binding_base, "binding", "native-texture-v1.json")
        assert binding["unit"] == binding["imageHandle"] == binding["samplerHandle"] == 0
        assert binding["stage"] == 1 and binding["target"] == binding["expectedType"] == 0
        image = decode(image_base, filename="texture-image-binding-v1.json")
        sampler = decode(sampler_base, filename="texture-sampler-v1.json")
        assert image["handle"] == sampler["handle"] == 0
        assert image["target"] == image["baseLevel"] == image["maxLevel"] == 0
        assert image["format"] == next(item["id"] for item in schema("texture-image-format-v1.json")["formats"]
                                       if item["name"] == "R32F")
        assert [image[name] for name in ("rootWidth", "rootHeight", "rootDepth", "layers")] == [1] * 4
        assert image["allocationBase"] == RESOURCE_VA + TEXTURE_DATA
        assert image["allocationLimit"] == image["allocationBase"] + 64
        assert image["levelsVersion"] == 1 and image["firstLevelHandle"] == 0
        assert all(value == 0 for name, value in image.items() if name.startswith("swizzle."))
        assert image["levelsBase"] == sampler_base + 64
        assert image["levelsLimit"] == image["levelsBase"] + 16 * 128
        assert sampler["state.maxLod"] == 0x41700000 and sampler["state.compareFunction"] == 3
        assert all(value == 0 for name, value in sampler.items()
                   if name.startswith("state.") and name not in ("state.maxLod", "state.compareFunction"))
        level = decode(image["levelsBase"], filename="texture-image-level-v1.json")
        assert level["handle"] == level["view.target"] == level["view.level"] == 0
        assert level["view.allocationBase"] == image["allocationBase"]
        assert level["view.allocationLimit"] == image["allocationLimit"]
        assert level["view.base"] == image["allocationBase"]
        assert [level["view." + name] for name in ("texWidth", "texHeight", "texDepth", "layers")] == [1] * 4
        assert level["view.rowPitch"] == level["view.blockBytes"] == 4
        assert level["view.slicePitch"] == level["view.layerPitch"] == level["view.block4x4"] == 0
        assert read_va(image["levelsBase"] + 128, 15 * 128, metadata=True) == bytes(15 * 128)
        assert read_va(level["view.base"] & ~63, 64) == images["root-data"][TEXTURE_DATA:TEXTURE_DATA + 64]
        assert read_va(level["view.base"], 4) == images["root-data"][TEXTURE_DATA:TEXTURE_DATA + 4]
    program = fragment_program(texture_host)
    assert read(translate(0x2000)[0], len(program) * 4) == struct.pack("<" + "I" * len(program), *program)
    return fixture


def validate_texture_host(fixture, regions, images, texture_va, decode, read_va, translate):
    """Independent CPU-only walk of the one opt-in default-capacity fixture."""
    assert fixture["uniformOwned"] and fixture["textureOwned"]
    assert texture_va == regions["job"]["va"] + 512 + 1664
    root = decode(texture_va, "root", "native-texture-v1.json")
    assert root["stage"] == 1 and root["bindingCount"] == 32
    b, i, s = (root[name + "Base"] for name in ("bindings", "images", "samplers"))
    assert b == texture_va + 128 and root["bindingsLimit"] == i == b + 32 * 64
    assert root["imagesLimit"] == s == i + 32 * 128 and root["samplersLimit"] == s + 32 * 64
    levels_base = s + 32 * 64
    # Unit31 must retain hardware index31. Every lower unit is an actual zero
    # slot, with no compacted table or hidden metadata pointers.
    assert read_va(b, 31 * 64, True) == bytes(31 * 64)
    assert read_va(i, 31 * 128, True) == bytes(31 * 128)
    assert read_va(s, 31 * 64, True) == bytes(31 * 64)
    assert read_va(levels_base, 31 * 2048, True) == bytes(31 * 2048)
    binding = decode(b + 31 * 64, "binding", "native-texture-v1.json")
    assert binding["unit"] == binding["imageHandle"] == binding["samplerHandle"] == 31
    assert binding["stage"] == 1 and binding["target"] == binding["expectedType"] == 0
    image = decode(i + 31 * 128, filename="texture-image-binding-v1.json")
    sampler = decode(s + 31 * 64, filename="texture-sampler-v1.json")
    assert image["handle"] == sampler["handle"] == 31
    assert image["target"] == image["baseLevel"] == 0 and image["maxLevel"] == 1
    assert image["format"] == next(item["id"] for item in schema("texture-image-format-v1.json")["formats"]
                                   if item["name"] == "R32F")
    assert [image[n] for n in ("rootWidth", "rootHeight", "rootDepth", "layers")] == [2, 2, 1, 1]
    assert image["allocationBase"] == RESOURCE_VA + TEXTURE_DATA
    assert image["allocationLimit"] == image["allocationBase"] + 128
    assert image["levelsVersion"] == 1 and image["firstLevelHandle"] == 0
    assert image["levelsBase"] == levels_base + 31 * 2048
    assert image["levelsLimit"] == image["levelsBase"] + 2048
    assert sampler["state.minFilter"] == 2 and sampler["state.maxLod"] == 0x41700000
    assert sampler["state.compareFunction"] == 3
    for level_index in (0, 1):
        level = decode(image["levelsBase"] + 128 * level_index, filename="texture-image-level-v1.json")
        assert level["handle"] == level["view.level"] == level_index and level["view.target"] == 0
        assert level["view.allocationBase"] == image["allocationBase"]
        assert level["view.allocationLimit"] == image["allocationLimit"]
        assert level["view.base"] == image["allocationBase"] + 64 * level_index
        size = 2 - level_index
        assert [level["view." + n] for n in ("texWidth", "texHeight", "texDepth", "layers")] == [size, size, 1, 1]
        assert level["view.rowPitch"] == size * 4 and level["view.blockBytes"] == 4
        assert level["view.slicePitch"] == level["view.layerPitch"] == level["view.block4x4"] == 0
        offset = TEXTURE_DATA + 64 * level_index
        assert read_va(level["view.base"], 64) == images["root-data"][offset:offset + 64]
    assert read_va(image["levelsBase"] + 256, 14 * 128, True) == bytes(14 * 128)

    def field(layout, data, name):
        item = next(f for f in layout["fields"] if f["name"] == name)
        value = sum(word(data, (item["word"] + j) * 4) << (32 * j) for j in range((item["bits"] + 31) // 32))
        assert value < 1 << item["bits"]
        return value
    frame_layout = schema("native-binned-graphics-v1.json")["frame"]
    frame = lambda name: field(frame_layout, images["frame"], name)
    state_layout = schema("typed-fragment-state-v1.json")
    state = lambda name: field(state_layout, images["states"], name)
    assert regions["states"]["bytes"] == 512 and frame("frame.scratchStride") == 16384
    assert state("fragment.colorSlots") == 4 and state("fragment.colorMask") == 15
    assert state("tests.depthEnable") == state("tests.depthWrite") == state("tests.stencilEnable") == 1
    assert state("tests.depthFunction") == 0
    for face in ("front", "back"):
        assert [state(f"tests.{face}." + n) for n in ("function", "reference", "writeMask", "depthPass")] == [6, 42, 255, 2]
    assert frame("frame.image.pixels.depth.format") == 3 and frame("frame.image.pixels.stencil.format") == 6
    for view in range(6):
        base = regions["frame-data"]["va"] + 0x30000 + 4096 * view
        size = 16 if view == 5 else 64
        prefix = f"frame.image.views[{view}]."
        assert frame(prefix + "base") == frame(prefix + "allocationBase") == base
        assert frame(prefix + "limit") == base + size and frame(prefix + "allocationLimit") == base + 64
        assert frame(prefix + "store") == frame(prefix + "readable") == frame(prefix + "writable") == 1
        assert [frame(prefix + n) for n in ("imageWidth", "imageHeight", "rowPitch")] == [4, 4, 4 if view == 5 else 16]
        physical, _, policy = translate(base)
        assert policy == 0 and physical == regions["frame-data"]["dma"] + 0x30000 + 4096 * view
        offset = 0x30000 + 4096 * view
        page = images["frame-data"][offset - 64:offset + 4096]
        assert page == bytes([0xA5]) * 64 + bytes([0xC3]) * size + bytes([0xA5]) * (4096 - size)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("prepare", "validate"))
    parser.add_argument("directory", type=Path)
    parser.add_argument("--texture-bits", type=lambda text: int(text, 0), default=0x3F000000)
    parser.add_argument("--uniform-bits", type=lambda text: int(text, 0), default=0x3E800000)
    parser.add_argument("--texture-host", action="store_true")
    parser.add_argument("--base-texture-bits", type=lambda text: int(text, 0), default=0x3F400000)
    parser.add_argument("--generation", type=int, default=9)
    args = parser.parse_args()
    if args.mode == "prepare":
        prepare(args.directory, args.texture_bits, args.uniform_bits, args.texture_host,
                args.base_texture_bits, args.generation)
    else:
        validate_prepared(args.directory)
        print("C-prepared rooted snapshots and nonidentity executable/data Sv32 roundtrip PASS (no RTL execution)")


if __name__ == "__main__":
    main()
