#!/usr/bin/env python3
"""Check the original geometry ioctl and MMIO capture before DUT execution."""
import argparse
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile

from compute_owner_oracle import SparseMemory
from compute_owner_protocol import strict_json
from native_geometry_owner_inputs import ROOT, build_producer, program_paths, verify_inputs
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
        with SparseMemory(memory) as snapshot:
            command = verify_inputs(snapshot, description, tuple(path.read_bytes() for path in products),
                                    capture.read_bytes(), observed)
        subprocess.run([inspector, capture, memory], env=env, check=True, timeout=20)
        atomic(active / 'record-0.held.response', b'OGCMH001')
        _, error = process.communicate(timeout=20)
        assert command > 0 and process.returncode == 2 and b'wrong private wire magic' in error, error
        assert not (active / 'record-0.retire').exists()
    finally:
        stop_group(process)
        _, error = process.communicate()
        if error and b'wrong private wire magic' not in error:
            print(error.decode(errors='replace'), end='')
    print('Original Mesa geometry RX, GNJ2 and four two-layer MRT/resolve views matched the captured driver bytes (CPU preflight)')


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
