# Typed image live-owner transport and lowering (P08)

The complete-host CPU boundary now forwards the original `COMPUTE_IMAGES` and
`COMPUTE_IMAGE_RESOURCES` requests to their production Linux handlers. It retains
the accepted image views beside their original GEM bindings, snapshots the real
GIU1 records and keeps the original backing and descriptor owners through
retirement and observed cleanup. The actual Mesa producer's ioctl boundary uses
these entry points; texture/image combined submissions keep a separate contract.

The capture guard grants image writes only when the original view is writable.
For buffer images it includes complete exposed texels, excluding leftover bytes
in the declared byte window. For 2D, array, cube and 3D views it includes each
texel row in the selected layer or all exposed layers, excluding row/slice/layer
padding and unselected layers. Packed rows and planes coalesce arithmetically.
Overlapping resource views retain the existing interval-union rule.

The OGCMP wire supports 4096 writable intervals. A fragmented view exceeding
that representation is rejected before a hardware kernel is offered and remains
cancellable. This does not change production admission, RTL image dimensions,
resource capacities or the 2-CU/640×480/60-FPS product target.

## Gallium typed storage and load/store lowering

The next source phase adds private, image-only linear allocations for the
thirteen native image formats and 2D, 2D-array, cube and 3D targets. Checked
mip layouts retain original storage formats, 64-byte row alignment and explicit
plane pitches. The existing 128-MiB backing-window bound applies to the complete
allocation. Transfers map the selected mip/plane and retain the resource through
unmap; launch views keep the original BO, offsets, permissions and exposed layer
range. A private compiler target sidecar distinguishes a one-plane layered
array/volume from a 2D view of one selected plane. The public compute-program
layout and legacy emitter entry points remain unchanged.

Native image load/store lowering carries target, format and XYZ operands. The
Mesa frontend now recovers API image units through the actual program's
`ImageUnits` table after sampler lowering, preserving sparse bindings such as
read-only unit 3 and write-only unit 1. Buffer sampler admission also checks the
existing target-5 format policy rather than accepting every mapped format ID.

ASan/UBSan CPU checks pass 65 target/format TGSI load/store programs, including
literal coordinate/control checks and mismatched-target rejection; 52 actual
Gallium mip/plane transfer and reference-lifetime cases; and the existing buffer
view tests. The resource-shape fixture passes 52 typed layouts plus the legacy
allocation paths, partial array views and single-plane array/volume views. A
separate real Mesa ESSL→NIR→TGSI build compiles all 65 target/format programs and
passes reflection and the production native-word validator. Existing shared
own-stripe, atomic, address/SIMT, 16-KiB and symbolic-register checks also pass.
These checks execute no image shader on a GPU or DUT.

The final typed-storage source also passes the eleven original counter CPU
captures with a fresh 22-TU/22-MMD producer, strict READY checks, the independent
ASan/UBSan parser and untouched-memory result rejection. All 8102 external and
177 GPU/checker inputs remain unchanged across that run. This checks regression
of the producer path; it does not extend P07's DUT qualification.

This phase accepts only `PIPE_BIND_SHADER_IMAGE` for the new nonbuffer layouts.
Combined sampler/render bindings, imported/exported typed storage, nonbuffer
`imageSize`, and image atomics remain unsupported by this path. Existing render
layouts and driver/RTL capacities are unchanged. Real source-bound image
execution and its boundary/fault/cancel/reset oracles remain P08 work.

## CPU transport result and limits

The full112 CPU profile passes 208 source-view cases with ASan/UBSan and the
independent canonical C++ capture parser: thirteen image formats, eight
buffer/2D/array/cube/3D layouts, and read-only/read-write permissions. The matrix
uses all four image units and dedicated backing slots, nonzero levels/offsets,
split cache-line buffer views, nonzero pitches and selected/layered bindings.
An independent byte map checks the entire 8192-byte backing for each view.
SNORM buffer images use a valid by-size view over RGBA8 backing; unsupported
SNORM texture-buffer backing remains rejected.

Invalid source fields fail production admission. An unresolved backing writer
fence prevents execution without offering a kernel. Packed and over-budget
layouts check bounded planning and cancellation. Four bad cleanup-ACK cases
retain the reachable original image and private descriptor after public-handle
closure. The existing storage/uniform/counter transport and cleanup checks pass.

The transport-phase producer also passes all eleven original counter recipes, including
four-image coexistence, through a fresh 22-TU Mesa build with 22 checked MMD
files. All 8102 external and 174 GPU/checker inputs remain unchanged. Each CPU
capture passes strict READY checks and the independent ASan/UBSan parser;
untouched initial memory is rejected as a GPU result. The pinned Linux producer
uses 512 MiB memory and total memory-plus-swap, one CPU, read-only inputs and no
network. This regression does not rerun or extend P07's actual-DUT qualification.

These are CPU OS/MMIO transport fixtures, including CPU completion/cleanup peers.
They execute no shader and create no DUT qualification. P08 remains incomplete:
original Mesa typed image load/store/atomic programs and exact returned-byte
oracles still need source-bound full-host execution, including boundaries,
permission faults, cancellation/reset/drain and healthy reuse. Complete resource
combinations, cross-command coherence and product clock/FPS keep their own gates.
Existing executor qualifications cannot be relabeled for the changed source.

Reproduce the scoped CPU checks; their temporary builds and captures are removed
when each command ends:

```sh
env -u SDKROOT -u CPATH OPENGPU_TEST_SANITIZERS=1 PYTHONDONTWRITEBYTECODE=1 \
  python3 -B scripts/test_compute_host.py --sample-stage --canonical-root .
env -u SDKROOT -u CPATH OPENGPU_TEST_SANITIZERS=1 PYTHONDONTWRITEBYTECODE=1 \
  python3 -B scripts/test_compute_host.py --canonical-root .
python3 -B scripts/test_compute_counter_source.py
python3 -B scripts/test_prepare_owner_executor.py
python3 -B scripts/test_qualify_compute_owner.py
python3 -B scripts/test_compute_owner_oracle.py
python3 -B scripts/test_compute_owner_controller.py
python3 -B scripts/test_prepare_owner_verilator_executor.py
```

For the Mesa regression, use the labeled CPU profile, checked NTT and immutable
Linux container setup in [the compute capture reproduction](COMPLETE_OWNER_COMPUTE_EXECUTOR.md).
Inside that container, select the original eleven-case matrix explicitly:

```sh
python3 -B scripts/test_gallium_compute_host.py --sanitize \
  --build "$CPU_CAPTURE/build" --session "$CPU_CAPTURE/sessions" \
  --profile-wire "$CPU_CAPTURE/profile.bin" \
  --mesa-shared-ntt "$CPU_CAPTURE/checked-ntt/src/gallium/auxiliary/nir/nir_to_tgsi.c" \
  --case counter-increment --capture-only --capture-counter-matrix
```

The compiler input guard is `compute_owner_inputs.py --bound-inputs INPUTS.json`,
using the current `snapshot` and source hashes rather than an executor
qualification. Run the canonical parser and strict counter READY checker for
each `sessions/COUNTER_CASES` capture. CPU capture records cannot qualify a DUT.

For the typed compiler and callbacks, use the same pinned Mesa/DRM container
environment and new private output directories. Remove those directories after
their checks have ended:

```sh
python3 -B scripts/test_gallium_typed_images.py --sanitize \
  --build "$CPU_CAPTURE/typed-compiler"
python3 -B scripts/test_gallium_buffer_views.py --sanitize \
  --build "$CPU_CAPTURE/typed-callbacks"
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_gallium_shared_own_stripe.py
python3 -B scripts/test_gallium_resource_shapes.py
```
