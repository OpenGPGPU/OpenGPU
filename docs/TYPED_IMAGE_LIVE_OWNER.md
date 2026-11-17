# Typed image source submission and live-owner contract (P08)

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

This phase accepts only `PIPE_BIND_SHADER_IMAGE` for the new nonbuffer layouts.
Combined sampler/render bindings, imported/exported typed storage, nonbuffer
`imageSize`, and image atomics remain unsupported by this path. Existing render
layouts and driver/RTL capacities are unchanged. Real source-bound image
execution and its boundary/fault/cancel/reset oracles remain P08 work.

## Original load/store source and independent result contract

The live producer now accepts 70 original ESSL recipes: thirteen formats across
buffer/2D/array/cube/3D, two single-plane layered array/volume cases and three
unlayered 2D views of selected array/cube/volume planes. Each launches two groups
of four invocations. Read-only API unit 3 and write-only unit 1 retain their
actual Mesa reflection order; sparse SSBO binding 2 observes all four loaded
components. The shader copies the loaded texel to a different image coordinate.
CPU uploads only initialize original input and guard allocations.

Nonbuffer resources use mip 1 of an odd-width allocation, explicit row/plane
pitches and selected/layered ranges. Buffer views start at byte 48 and include
three incomplete trailing bytes. Input patterns distinguish adjacent rows and
planes, including normalized formats. Accepted production ioctl views, original
GEM identities and the actual private GIU1/GSM1 snapshots supply READY metadata.
The common host producer always links its real Mesa atomic-buffer object, since
its runtime-selected counter branch remains reachable for noncounter cases.

Before execution, the independent reader requires the exact full112 discovery,
one final kernel after publication invalidates, original sparse bindings and
format/mip/pitch/layer/access fields, literal GIU1/GSM1 records, original Sv32
backings/permissions, a separate empty inactive context and exact writable
intervals. Read-only images, row/plane padding, unselected layers and incomplete
buffer texels receive no write grant. The controller binds the specific image
case and freezes all three READY inputs through result checks and cleanup.
The qualifier uses the original compiler-input/MMD/link guard, a read-only
512-MiB Linux container with one CPU and the exact final112 executor profile.

The returned-byte oracle derives the eight loaded RGBA values from original
source texels and format rules, checks each destination texel and all untouched
physical bytes, and rejects initial uploads as results. Half/float bits, signed
extension, scalar default components, normalized endpoints and SNORM -128→-1
loading/-127 storage are explicit. It neither executes native words nor
synthesizes returned GPU memory.

Final CPU validation passes all 70 fresh Mesa→production-ioctl captures with the
independent ASan/UBSan C++ parser, strict READY and 7745 rejection checks. These
include view/descriptor/permission/backing mutations, widened write intervals,
read-only grants, inactive-context authority and mismatched qualified cases.
All source patterns distinguish adjacent row/plane reads. The eleven original
counter CPU recipes also pass strict READY, canonical parsing and initial-memory
rejection with fresh 22-TU/22-MMD builds. Each matrix preserves all 8102 external
and 180 GPU/checker inputs. The 59 focused Python checks pass. These checks
execute no image shader on a DUT and create no execution qualification.

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

These are CPU OS/MMIO transport fixtures, including CPU completion/cleanup peers.
They execute no shader and create no DUT qualification. P08 remains incomplete:
the original typed load/store sources and exact returned-byte oracle still need
fresh source-bound full-host DUT execution. Image atomics, actual boundaries,
permission faults, cancellation/reset/drain and healthy reuse remain open. Complete resource
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

For the original typed-image CPU matrix, select a new build/session and replace
the case selection with the following flags on the same guarded command:

```sh
--case image-buffer-rgba32f --capture-only --capture-image-matrix
```

Then check every capture using the canonical C++ parser and run:

```sh
python3 -B scripts/test_compute_image_source.py --captures "$CPU_CAPTURE/sessions"
python3 -B scripts/test_compute_image_source.py
```

For later actual execution, freshly qualify the software executor against these
producer/checker sources, then use `qualify_compute_owner.py --case CASE` for
each name in `compute_image_cases.IMAGE_CASES`. The existing executor/Mesa/tool
arguments and complete-host cleanup protocol are unchanged. Matrix capture flags
require `--capture-only`; they cannot launch live qualification. This document
records no typed-image DUT result.

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
