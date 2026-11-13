# Linked graphics uniform frontend

This component connects actual linked Mesa ESSL vertex/fragment programs to
native SoA instructions and an original-GEM uniform-view planner. It does not
yet connect production GNJ submission, immutable native snapshots or complete
host execution. Device API capabilities remain unchanged.

## Compiler and source views

The explicit fixture links real ESSL 3.10 VS and FS programs, each with twelve
16 KiB UBOs and 1,024 default-uniform components. VS uses global bindings 0–11;
FS uses 24–35. Real Mesa NIR/NTT lowering supplies TGSI. Real
`st_bind_vs_ubos` and `st_bind_fs_ubos` map the linked program's block order to
stage constant slots 1–12. Linker block order is not global binding order.
Defaults occupy slot zero and are copied before Mesa reuses its parameter data.
The fixture's compiler limits are separate from the current device's reported
graphics limits.

The native helper supports straight-line raw MOV and unsigned word arithmetic
(add, multiply, bitwise operations and shifts), checked scalar GUR1/GUB1 loads,
position passthrough, flat varyings and raw color outputs. It rejects other
operations, indirect addressing, undefined components and malformed packets.
All source channels are read before an aliased destination is committed. The
512-word native ceiling remains enforced; failed emission leaves its output
untouched. Native SoA addresses retain bounded kernarg/local-base provenance,
including offsets beyond one signed ADDI immediate.
Interface reflection derives attribute, varying and color slot counts from
the actual TGSI declarations. It does not assume the fixture's one-attribute,
one-varying, one-color layout. Capacity remains an explicit input that a
production caller must obtain from verified native layout discovery.

The borrowed-view planner verifies live source-screen membership, original GEM
allocation bounds, alignment, active-view completeness and required byte
ranges. Exact equal GEM/offset/size views can share one of 24 aggregate slots
across stages; differing ranges remain distinct. Defaults are independent.
Unused logical views acquire no slot. Planning alone takes no job ownership,
waits on no producer and publishes no GPU-visible record. Production submission
must independently verify sources, wait on producers, snapshot data and retain
the accepted job's immutable resources through completion and reply drain.
Original GEM source offsets and sizes need four-byte alignment. GUC's
256-byte UBO alignment applies to the copied private destination; existing
64-byte source windows remain valid.

## Native shader admission

The separate native validator derives writable output slices from the actual
stage ABI and layout. Inputs, flags, built-ins and tail staging are read-only;
FS depth and output-control slices remain writable. The profile preserves
sixteen attributes, sixteen vec4 varyings and four colors, including existing
point-size/point-coordinate offsets. Geometry input version 5 needs its explicit
stage guard. Checked uniform instructions require a verified owned GUR/data
contract. Other resource domains and workgroup barriers remain closed in this
profile. Existing compute and legacy validator entry points retain their
previous contracts.

## CPU validation and open scope

ASan/UBSan passes the actual linked programs: VS 308 words, FS 301 words, each
with 26 checked uniform loads. An independent native-word interpreter consumes
the real source-GEM view data and checks every position/varying/color/control
word and untouched guard for 1, 3, 17 and 128 invocations. Additional native
lowering cases cover component aliases, partial writes, swizzles, all admitted
integer ALU operations, malformed inputs and instruction-capacity rejection.
CPU execution also checks highest attribute 15, varying 15 and color 3,
including the high four bits of the 64-component varying mask, and admits
zero-attribute/zero-varying runtime layouts. Reflection/emission failure changes
neither published layout nor program.
State-tracker checks cover binding 35, rebind, unused global binding 12,
unbound active views, allocation-tail clamping and reference recovery. Planner
checks include all 24 slots, shared cross-stage views, distinct subranges,
foreign-screen/image resources, exact allocation endpoints and failure without
partial output. These use modeled OS/GEM primitives and do not execute a GPU.

Native validator C coverage checks every input/output/tail slice across full
and empty runtime layouts, VS versions 1/2, FS versions 1–5, capability guards,
canonical fences and the unchanged 512-word ceiling. Existing compute, image,
atomic, SIMT and output-usage validator regressions pass.
The complete `scripts/test_driver.py` regression also passes.

Production GNJ/Gallium state admission, owned code/uniform publication, actual
typed attachments and the same complete two-CU host still need integration and
execution qualification. Complete API/compiler support and the 36-UBO/48-texture
cross-stage aggregate resource contract remain open. These bounded programs do
not establish arbitrary GL shader instruction capacity or complete H7.

## Reproduction

Use matching Mesa 22.3.6 source/build objects and the checked uniform-index
lowering source. Keep private outputs in this repository's `.artifacts/`.
Set `TMPDIR` to a fresh short GPU-internal directory; on macOS keep its complete
path at most 52 bytes for sbt socket compatibility, and use the same JVM temp
directory for any later hardware qualification.

```sh
export OPENGPU_MESA_DIR=/path/to/mesa-22.3.6
export OPENGPU_MESA_BUILD=/path/to/matching-mesa-build
export OPENGPU_DRM_INCLUDE=/path/to/drm-headers
python3 scripts/test_gallium_graphics_uniforms.py --build .artifacts/graphics-uniform-active --sanitize
python3 scripts/test_driver.py
python3 scripts/test_test_selection.py
```

Delete private builds, captures, caches, logs and the temporary directory after
their validation use ends. See [native uniform bindings](NATIVE_UNIFORM_BINDINGS.md)
for the hardware resource contract and [host ownership](NATIVE_UNIFORM_HOST_OWNERSHIP.md)
for the separate existing component proofs.
