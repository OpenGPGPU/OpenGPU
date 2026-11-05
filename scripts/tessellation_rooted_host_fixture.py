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


def fragment_program():
    def addi(rd, rs, immediate):
        return ((immediate & 4095) << 20) | (rs << 15) | (rd << 7) | 0x13

    def broadcast(rd, rs):
        return (23 << 26) | (1 << 25) | (rs << 15) | (4 << 12) | (rd << 7) | 0x57

    def store(base, vector):
        return (1 << 25) | (base << 15) | (6 << 12) | (vector << 7) | 0x27

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


def prepare(directory, texture_bits=0x3F000000, uniform_bits=0x3E800000):
    directory = Path(directory)
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
    for i, instruction in enumerate(fragment_program()):
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
    assert word(images["states"], fields["fragment.resources.address"] * 4) == RESOURCE_VA
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

    def decode(address, kind):
        layout = schema("native-uniform-v1.json", kind)
        data = read_va(address, layout["bytes"], metadata=True)
        values = {}
        for field in layout["fields"]:
            raw = sum(word(data, (field["word"] + i) * 4) << (32 * i)
                      for i in range((field["bits"] + 31) // 32))
            assert raw < 1 << field["bits"]
            values[field["name"]] = raw
        assert data == record(layout, values), "noncanonical uniform metadata"
        assert all(values[name] == value for name, value in identity.items())
        return values

    root = decode(uniform_va, "root")
    assert root["stage"] == 1 and root["activeMask"] == 1
    if owned:
        assert uniform_va == regions["job"]["va"] + 512 and regions["job"]["bytes"] == 512 + 1664
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
    assert read(translate(0x2000)[0], len(fragment_program()) * 4) == struct.pack("<" + "I" * len(fragment_program()), *fragment_program())
    return fixture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("prepare", "validate"))
    parser.add_argument("directory", type=Path)
    parser.add_argument("--texture-bits", type=lambda text: int(text, 0), default=0x3F000000)
    parser.add_argument("--uniform-bits", type=lambda text: int(text, 0), default=0x3E800000)
    args = parser.parse_args()
    if args.mode == "prepare":
        prepare(args.directory, args.texture_bits, args.uniform_bits)
    else:
        validate_prepared(args.directory)
        print("C-prepared rooted snapshots and nonidentity executable/data Sv32 roundtrip PASS (no RTL execution)")


if __name__ == "__main__":
    main()
