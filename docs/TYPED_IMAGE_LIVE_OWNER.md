# Typed image live-owner transport (P08)

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

The updated producer also passes all eleven original counter recipes, including
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
