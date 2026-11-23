#!/usr/bin/env python3
"""Check the original geometry ioctl and MMIO capture before DUT execution."""
import argparse
import os
from pathlib import Path
import shlex
import shutil
import struct
import subprocess
import tempfile

from compute_owner_oracle import SparseMemory
from compute_owner_protocol import strict_json
from native_geometry_feedback_oracle import (ACTIONS, COMMAND_BASE, DRAW_BYTES, RENDER_COMMAND,
    packet, raw_primitive)
from native_geometry_owner_inputs import ROOT, build_producer, program_paths, verify_inputs
from native_geometry_owner_oracle import (geometry_completion, layer_bytes, verify as verify_returned,
    verify_feedback_run)
from native_sample_owner_controller import atomic, wait_file
from native_sample_owner_protocol import AUXILIARY, profile_bytes
from owner_process_lifetime import CleanupIncomplete, stop_group
from owner_resource_contract import IMAGE_FLOAT_PROFILE, SAMPLE_FACTORY_CONTRACTS, SAMPLE_PROFILE


def profile(name):
    stage, words = SAMPLE_FACTORY_CONTRACTS[name]
    # Controlled CPU inputs used only to exercise the actual discovery decoder.
    values = (0x47550002, sum(1 << b for b in (6, 22, 23, 25, 29)) | 128 << 8,
              0x160, 1048576, 0x02020810)
    return dict(schema='opengpu-native-sample-owner-profile-v1', ownerProfile=name,
                ownerResourceStage=stage, ownerResourceVersion=2, fullResourceQualification=False,
                resetCount=1, sourceQualificationRequired=True, gpuExecution=False,
                clockQualified=False, fpsQualified=False, words=words,
                registers=[dict(address=a, value=v) for a, v in zip(AUXILIARY, values, strict=True)])


def reader(directory, *, sanitize):
    source = directory / 'inspect.cpp'
    source.write_text(r'''
#define OPENGPU_OWNER_IMAGE_FLOAT_STAGE 1
#include "native_sample_owner_capture.hpp"
int main(int argc, char **argv) {
  using namespace native_owner;
  require(argc == 3, "capture and memory paths required");
  auto capture = SampleCapture::read(std::string(argv[1]), true);
  auto memory = Memory::read(argv[2]);
  verify_readonly(memory, memory, capture.writable);
}
''')
    binary = directory / 'inspect'
    flags = ['-std=c++17', '-O1', '-Wall', '-Wextra', '-Werror', '-pedantic']
    if sanitize:
        flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
    subprocess.run([*shlex.split(os.environ.get('CXX', 'c++')), *flags,
                    '-I' + str(ROOT/'scripts'), '-I' + str(ROOT/'scripts/test_stubs/native_host'),
                    str(source), '-o', str(binary)], check=True)
    return binary


def check_return_oracle(directory, memory, snapshot, description, outputs, programs, capture, observed):
    # The captured RAM is real driver state. These deliberately written
    # return bytes only exercise the checker and its failure paths.
    candidate = bytearray(memory.read_bytes())

    def overlay(address, payload):
        while payload:
            page = address & ~4095
            within = address - page
            position = snapshot.pages[page] + within
            length = min(len(payload), 4096 - within)
            candidate[position:position + length] = payload[:length]
            address += length
            payload = payload[length:]

    def address(mrt, kind, layer):
        gem = description['attachments'][mrt][kind]
        return gem['physical'] + gem['offset'] + layer * 4096

    # First pixel covers both left-column samples. Keep both SampleID alpha
    # values, quarter-average the result and preserve the provoking slot.
    assert struct.unpack_from('<16f', layer_bytes(2, 1)) == (
        819.375, 841.875, 864.375, 0, 0, 0, 0, 0,
        821.875, 844.375, 866.875, 2, 0, 0, 0, 0)
    assert struct.unpack_from('<4f', layer_bytes(2, 1, resolve=True)) == (410.3125, 421.5625, 432.8125, .5)
    assert struct.unpack_from('<4f', layer_bytes(3, 1)) == (1144.625, 1167.125, 1189.625, 1)
    assert layer_bytes(3, 1)[128:192] == bytes(64)

    for mrt in range(4):
        for kind in ('image', 'resolve'):
            for layer in range(2):
                overlay(address(mrt, kind, layer), layer_bytes(mrt, layer, resolve=(kind == 'resolve')))
    overlay(outputs.completion, struct.pack('<4096I', *geometry_completion(outputs.command_id)))
    returned = directory / 'oracle-return.memory'
    returned.write_bytes(candidate)
    with SparseMemory(returned) as result:
        assert verify_returned(snapshot, result, description, programs, capture, observed) == outputs.command_id

    for changed, reason in (
            (address(2, 'image', 1) + 32 + 12, 'geometry image MRT 2 layer 1'),
            (outputs.completion, 'geometry GNY2 word 0'),
            (address(0, 'image', 0) + 1024, 'guard changed')):
        index = snapshot.pages[changed & ~4095] + (changed & 4095)
        candidate[index] ^= 1
        returned.write_bytes(candidate)
        candidate[index] ^= 1
        with SparseMemory(returned) as result:
            try:
                verify_returned(snapshot, result, description, programs, capture, observed)
            except ValueError as error:
                assert reason in str(error), error
            else:
                raise AssertionError('geometry oracle accepted a changed ' + reason)

    feedback = outputs.feedback
    raw = raw_primitive()
    assert len(raw) == DRAW_BYTES and struct.unpack_from('<4f', raw) == (-2, -1, 0, 1)
    flat_slot = 4 * (4 + 3 * 15)
    assert [struct.unpack_from('<3f', raw, n * 208 + flat_slot)
            for n in range(3)] == [(121.25, 123.5, 125.75),
                                   (122.25, 124.5, 126.75),
                                   (123.25, 125.5, 127.75)]
    ordinary_render = bytes(candidate)
    for mrt in range(4):
        for kind in ('image', 'resolve'):
            for layer in range(2):
                overlay(address(mrt, kind, layer), bytes(256 if kind == 'resolve' else 1024))
    overlay(feedback.checkpoint(0), packet(feedback, 0, observed=True))
    overlay(feedback.stream, raw)
    discarded = directory / 'oracle-feedback-discard.memory'
    discarded.write_bytes(candidate)
    candidate[:] = ordinary_render
    for index, _ in enumerate(ACTIONS):
        overlay(feedback.checkpoint(index), packet(feedback, index, observed=True))
    overlay(feedback.stream, raw * 2)
    overlay(outputs.completion, struct.pack('<4096I', *geometry_completion(RENDER_COMMAND)))
    # Controlled observations exercise the same phase/byte checks as the
    # guarded executor. This fixture reports CPU preflight only.
    receipts = []
    for command, (opcode, data) in enumerate(((12, 7), (7, 0), (12, 7), (12, 7), (7, 0), (12, 6)), COMMAND_BASE):
        receipts.append(dict(command=command, opcode=opcode, data=data, heldStatus=0x22,
            completion=0x8000 | (opcode & 7) << 8 | (opcode >> 3) << 16 | command,
            heldIrq=3, retiredStatus=1, retiredIrq=1))
    telemetry = dict(schema='opengpu-native-geometry-feedback-v1', resetCount=1,
                     commitAckHeldCycles=23, receipts=receipts)
    returned.write_bytes(candidate)
    with SparseMemory(discarded) as discard:
        def check_feedback(acknowledgement=telemetry):
            with SparseMemory(returned) as result:
                verify_feedback_run(snapshot, discard, result, description, programs, capture,
                                    observed, acknowledgement)

        check_feedback()
        for address, canonical, wrong, diagnostic in (
            (feedback.stream, struct.pack('<f', -2), struct.pack('<f', -1), 'unclipped'),
            (feedback.stream + flat_slot, struct.pack('<f', 121.25), struct.pack('<f', 123.25), 'unflattened'),
            (feedback.checkpoint(3) + 36 * 4, struct.pack('<I', 2), struct.pack('<I', 4), 'GTF1'),
            (feedback.stream + 2 * DRAW_BYTES, b'\xa7', b'\x81', 'guard changed'),
        ):
            overlay(address, wrong)
            returned.write_bytes(candidate)
            overlay(address, canonical)
            try:
                check_feedback()
            except ValueError as error:
                assert diagnostic in str(error), error
            else:
                raise AssertionError('geometry feedback oracle accepted changed ' + diagnostic + ' bytes')
        returned.write_bytes(candidate)
        try:
            check_feedback(dict(telemetry, commitAckHeldCycles=0))
        except ValueError as error:
            assert 'ACK was not held' in str(error), error
        else:
            raise AssertionError('geometry feedback oracle accepted an unobserved commit ACK')


def check(directory, products, *, sanitize):
    binary = build_producer(directory, sanitize=sanitize)
    inspector = reader(directory, sanitize=sanitize)
    env = dict(os.environ, OPENGPU_NATIVE_HOST_TIMEOUT='15')
    older = directory / 'ordinary-sample-profile.bin'
    older.write_bytes(profile_bytes(profile(SAMPLE_PROFILE)))
    rejected = directory / 'rejected'
    rejected.mkdir()
    reject = subprocess.run([binary, older, rejected, *products], env=env, capture_output=True, timeout=20)
    assert reject.returncode == 2 and b'sample profile identity' in reject.stderr, reject.stderr
    assert not any(rejected.iterdir())

    observed = profile(IMAGE_FLOAT_PROFILE)
    float_profile = directory / 'image-float-profile.bin'
    float_profile.write_bytes(profile_bytes(observed, owner_profile=IMAGE_FLOAT_PROFILE))
    active = directory / 'active'
    active.mkdir()
    process = subprocess.Popen([binary, float_profile, active, *products], env=env,
                               start_new_session=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        wait_file(active / 'READY', process, None, 15)
        capture = active / 'record-0.capture'
        memory = active / 'union.memory'
        description = strict_json(active / 'geometry-inputs.json')
        original = tuple(path.read_bytes() for path in products)
        capture_bytes = capture.read_bytes()
        with SparseMemory(memory) as snapshot:
            outputs = verify_inputs(snapshot, description, original, capture_bytes, observed)
            check_return_oracle(directory, memory, snapshot, description, outputs, original, capture_bytes, observed)
        subprocess.run([inspector, capture, memory], env=env, check=True, timeout=20)
        atomic(active / 'record-0.held.response', b'OGCMH001')
        _, error = process.communicate(timeout=20)
        assert outputs.command_id > 0 and process.returncode == 2 and b'wrong private wire magic' in error, error
        assert not (active / 'record-0.retire').exists()
    finally:
        stop_group(process)
        _, error = process.communicate()
        if error and b'wrong private wire magic' not in error:
            print(error.decode(errors='replace'), end='')
    print('Original Mesa geometry RX, GNJ2, four two-layer MRT/resolve views and GTF1 matched captured driver bytes (CPU preflight)')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiled-prefix', type=Path, required=True)
    parser.add_argument('--sanitize', action='store_true')
    args = parser.parse_args()
    root = ROOT / '.artifacts'
    root.mkdir(exist_ok=True)
    products = program_paths(args.compiled_prefix)
    directory = Path(tempfile.mkdtemp(dir=root, prefix='geometry-live-'))
    preserve = False
    try:
        check(directory, products, sanitize=args.sanitize)
    except CleanupIncomplete:
        preserve = True
        print('Preserved geometry inputs for a possibly active consumer at ' + str(directory))
        raise
    finally:
        if not preserve:
            shutil.rmtree(directory)


if __name__ == '__main__':
    main()
