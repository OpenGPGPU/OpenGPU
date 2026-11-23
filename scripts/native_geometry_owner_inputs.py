"""Build the live geometry producer and verify the original submitted inputs."""
import os
from pathlib import Path
import shlex
import struct
import subprocess

from compute_owner_protocol import required_flags, uint
from native_sample_owner_oracle import AddressSpace, capture, field, fields, require
from native_sample_owner_protocol import AUXILIARY
from owner_resource_contract import IMAGE_FLOAT_PROFILE, IMAGE_FLOAT_WORDS
from test_native_mmio_capture import write_bodies

ROOT = Path(__file__).resolve().parents[1]
SUFFIXES = ('.native-vs-code.bin', '.native-gs-code.bin', '.native-fs-code.bin', '.native-gs-gng.bin')


def program_paths(prefix):
    paths = [Path(str(prefix) + suffix).absolute() for suffix in SUFFIXES]
    require(all(path.is_file() and not path.is_symlink() for path in paths),
            'missing original Mesa geometry compiler products')
    return paths


def build_producer(directory, *, sanitize=False):
    write_bodies(directory)
    words = [(w['address'], w['value']) for w in IMAGE_FLOAT_WORDS]
    words.extend((address, 0) for address in AUXILIARY)
    entries = ',\n'.join('{%#xu,%#xu}' % pair for pair in words)
    (directory / 'native_geometry_expected_profile.inc').write_text(
        'static const struct native_kernel_profile_word geometry_expected_profile[117] = {\n'
        + entries + '\n};\n')
    includes = [directory, ROOT/'driver', ROOT/'driver/tests',
                ROOT/'driver/tests/private_snapshot_stubs', ROOT/'driver/tests/mmu_stubs',
                ROOT/'driver/tests/compute_submit_stubs']
    flags = ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror']
    if sanitize:
        flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
    binary = directory / 'native-geometry-live-producer'
    subprocess.run([*shlex.split(os.environ.get('CC', 'cc')), *flags,
                    *('-I' + str(p) for p in includes), str(ROOT/'scripts/native_geometry_live_producer.c'),
                    str(ROOT/'driver/opengpu_native_frame.c'), '-o', str(binary)], check=True)
    return binary


def descriptor(filename, prefix):
    return fields(ROOT / 'driver' / filename, 'OPENGPU_' + prefix + '_')


def original_window(memory, view, size):
    require(type(view) is dict and set(view) == {'physical', 'allocationBytes', 'offset', 'bytes'},
            'invalid geometry original GEM record')
    physical, size_alloc, offset, actual = (uint(view[k]) for k in
                                          ('physical', 'allocationBytes', 'offset', 'bytes'))
    require(actual == size and actual > 0 and offset + actual <= size_alloc and
            any(first == physical and last == physical + size_alloc for first, last, _ in memory.ranges),
            'geometry window escapes its original GEM allocation')
    return physical + offset


def verify_inputs(memory, description, compiler_products, capture_data, observed_profile):
    required_flags(description, dict(schema='opengpu-native-geometry-inputs-v1',
        ownerProfile=IMAGE_FLOAT_PROFILE, gpuExecuted=False, width=4, height=4, samples=4, layers=2,
        layerPitch=4096, context=7, **{'pass': 11}, generation=13))
    vs, gs, fs, original_gng = compiler_products
    require(all(type(code) is bytes and 0 < len(code) <= 2048 and len(code) % 4 == 0
                for code in (vs, gs, fs)) and type(original_gng) is bytes and len(original_gng) == 1024,
            'invalid original geometry compiler output shapes')
    command_id, registers, writable = capture(capture_data, observed_profile, owner_profile=IMAGE_FLOAT_PROFILE)
    require(registers.get(0xf4) == uint(description['jobVA']) and
            registers.get(0x14c) == registers.get(0x150), 'capture changed the job or Sv32 context')
    space = AddressSpace(memory, description, registers.get(0x14c, 0))
    jobs = descriptor('opengpu_native_layered_abi.h', 'NATIVE_LAYERED_JOB')
    frames = descriptor('opengpu_native_layered_abi.h', 'NATIVE_LAYERED_FRAME')
    binned = descriptor('opengpu_native_binned_abi.h', 'NATIVE_BINNED_FRAME')
    draws = descriptor('opengpu_native_binned_abi.h', 'NATIVE_BINNED_DRAW')
    geometry = descriptor('opengpu_native_geometry_abi.h', 'NATIVE_GEOMETRY_PROGRAM')
    fragment = descriptor('opengpu_typed_fragment_state_abi.h', 'TYPED_FRAGMENT_STATE_FRAGMENT')

    job = space.read(description['jobVA'], 512)
    require(struct.unpack_from('<3I', job) == (0x474e4a02, 512, 2) and
            field(job, jobs, 'JOB_DRAWCOUNT') == 1 and
            field(job, jobs, 'JOB_DRAWBASE') == uint(description['drawVA']) and
            field(job, jobs, 'JOB_FRAMEBASE') == uint(description['frameVA']) and
            field(job, jobs, 'COMPLETION_BASE') == uint(description['completionVA']),
            'geometry job does not name its original private graph')
    frame = space.read(description['frameVA'], 2048)
    draw = space.read(description['drawVA'], 2048)
    require(struct.unpack_from('<3I', frame) == (0x474e4603, 2048, 3) and
            field(frame, frames, 'LAYERED_LAYERS') == 2 and
            field(frame, frames, 'FRAME_STATES_BASE') == uint(description['stateVA']) and
            field(frame, frames, 'FRAME_STATES_LIMIT') - description['stateVA'] == 512 and
            struct.unpack_from('<I', draw, 509 * 4)[0] == uint(description['geometryVA']),
            'geometry draw lost its two-layer frame, GNG1 or state')
    state = space.read(description['stateVA'], 512)
    gng = space.read(description['geometryVA'], 1024)
    patched_first = 4 * geometry['PC'][0]
    patched_end = 4 * (geometry['UNIFORMS_ADDRESS'][0] + 1)
    require(gng[:patched_first] == original_gng[:patched_first] and
            gng[patched_end:] == original_gng[patched_end:] and
            field(gng, geometry, 'FLATMASK') == 0x7000000000000000 and
            field(gng, geometry, 'UNIFORMS_VERSION') == 0 and
            field(gng, geometry, 'UNIFORMS_ADDRESS') == 0,
            'published GNG1 changed source, flat-mask or uniform bindings')
    pcs = [field(draw, draws, 'DRAW_VERTEX_GEOMETRY_VERTEX_SHADERPC'),
           field(gng, geometry, 'PC'), field(state, fragment, 'SHADERPC')]
    require(pcs == description['shaderVAs'] and field(gng, geometry, 'CODE_BASE') == pcs[1] and
            field(gng, geometry, 'CODE_LIMIT') == pcs[1] + len(gs) and
            [field(state, fragment, name) for name in
             ('VERSION', 'VARYINGSLOTS', 'COLORSLOTS', 'COLORMASK', 'SAMPLEMODE')] == [7, 16, 4, 15, 2],
            'geometry source lost ABI7, the four MRTs or the full sample mode')
    for pc, view, code in zip(pcs, description['programs'], (vs, gs, fs), strict=True):
        original = original_window(memory, view, len(code))
        private = space.physical(pc, len(code), executable=True)
        require(original != private and memory.read(original, len(code)) == code and
                memory.read(private, len(code)) == code, 'geometry original/RX code bytes changed')

    vertex = field(draw, draws, 'DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_BINDINGS0_BASE')
    data = struct.pack('<24f', -1.5, -.75, 0, 1, .25, .5, .75, 0, .75, -.75, 0, 1,
                       1.25, 1.5, 1.75, 0, 0, .75, 0, 1, 2.25, 2.5, 2.75, 0)
    original = original_window(memory, description['vertices'], len(data))
    require(space.physical(vertex, len(data)) == original and memory.read(original, len(data)) == data,
            'geometry vertex binding lost the original positions/seeds')

    def mapped_output(va, original, count):
        require(space.physical(va, count, writable=True) == original and
                any(first <= original and original + count <= end for first, end in writable),
                'geometry output is outside its original mapped writable range')

    completion = original_window(memory, description['completion'], 16384)
    mapped_output(description['completionVA'], completion, 16384)
    attachments = description.get('attachments')
    require(type(attachments) is list and len(attachments) == 4, 'geometry draw requires four original MRTs')
    for mrt, attachment in enumerate(attachments):
        required_flags(attachment, dict(imageRowPitch=256, resolveRowPitch=64))
        for kind, pitch, stem, span, sentinel in (
            ('image', 'SOURCEPITCH', f'FRAME_IMAGE_VIEWS{mrt}_', 1024, 0x51 + mrt),
            ('resolve', 'RESOLVEPITCH', f'FRAME_IMAGE_RESOLVES{mrt}_DESTINATION_', 256, 0x71 + mrt),
        ):
            require(field(frame, frames, f'LAYERED_{pitch}{mrt}') == 4096,
                    'geometry layer stride changed')
            base = field(frame, binned, stem + 'BASE')
            length = 4096 + span
            view = attachment[kind]
            pa = original_window(memory, view, length)
            mapped_output(base, pa, length)
            require(field(frame, binned, stem + 'LIMIT') == base + length and
                    memory.read(view['physical'], view['allocationBytes']) == bytes([sentinel]) * view['allocationBytes'],
                    'geometry initial layer bounds or guard pattern changed')
    return command_id
