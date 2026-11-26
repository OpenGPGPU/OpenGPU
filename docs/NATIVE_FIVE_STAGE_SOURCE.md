# Original linked five-stage source

The independent `opengpu_native_five_*` family links original ESSL 3.20
VS/TCS/TES/GS/FS once, captures its five TGSI packets and eight original NIR
variable maps, and compiles five actual native programs. The existing
four-stage `opengpu_native_joint_*` objects and no-GS fragment artifact remain
separate. This introduces no public shader callbacks, screen capability,
getter, UAPI, protected submission or device permission.

## Ownership and stage boundaries

The opaque link owns the original Mesa tokens and reflection. Program creation
copies all packets/maps/reflection before the first GEM callback. VS uses the
original NEXT_TCS compiler. TC/TES use language program v3 and the full owned
patch layout. A distinct TES-to-GS entry requires NEXT_GEOMETRY; the old
linked entry still requires NEXT_FRAGMENT. GS uses the scalar geometry
program dialect described in [the GS compiler contract](NATIVE_GEOMETRY_MESA.md).
No old code is relabeled or borrowed from an independently linked shader.

The eight maps are VS output, TC input/output, TES input/output, GS
input/output and FS input. Each retains original variable spans, component
masks and semantic locations. The TC patch packing and TES export packing
are independent. TES-to-GS maps actual packed TES output to the GS input
inventory; GS-to-FS maps actual GS exports to the FS semantic slots. Sparse
location 15 remains a full native varying prefix, not a packed API location.
Position and applicable builtins count inside the existing component limits.
TES topology is checked against the original GS input primitive.

A source lease retains every required original backing/view and the program.
The complete test uses 60 UBO references plus five separately copied default
views, and five original code GEMs. API bindings 0..71 remain distinct from
each stage's logical blocks 0..11. VS/FS use one private table pair, TC/TES
another pair, and GS its own table. No global 60-entry hardware table is
invented. Same-GEM offsets/lengths survive rebind, caller reference release,
and destruction of the constants cache and caller's program reference.
The source pipe/context and screen must outlive the leased objects.

The lease freezes references and compiler metadata, not mutable GEM data or
producer fences. Protected capture, checked GUR1/GUR2/GUR3 contents, writer
leases, RX permissions and whole-job retirement still belong to the verified
kernel owner. This family does not claim those graphs have been built.

## GS-linked fragment compiler version 3

`opengpu_native_fragment_*_v3` is an explicit GS-linked compiler family.
Original HIR records the static builtin/sample use before optimized NIR loses
it. Checked uniform emission preserves all 16 varying slots, four MRTs,
flat/centroid masks, depth/control arrays and stage-local uniform requirements.
GS PrimitiveID and Layer read actual geometry input arrays. In particular,
Layer is not the no-GS version-2 constant zero. PointCoord has its own builtin
kind and does not become a generic varying by numerical offset.

The default transport uses existing ABI5 for pixel-rate GS input and ABI7
only when SampleID, SamplePosition or a sample qualifier requires sample
planes. MaskIn and HelperInvocation do not open those planes. ABI5 cannot
encode shader SampleMask writes: the actual original source is rejected
without creating code GEMs. The explicit diagnostic options described below
can target the separate geometry pixel-mask transport; metadata alone cannot
enable its verified owner or hardware provider. Sample input permission, geometry permission and uniform permission must
be independently verified before execution.

`OGNSF003` consists of eight magic bytes, 42 little-endian u32 header words,
and exactly the declared 1..512 instruction words, followed by EOF. The
header inventory matches OGNSF002's numerical order, with header word0=3:

- Words1..17: ABI, input arrays, stride, prefix bytes, used builtins, color
  mask, depth/mask writes, sample/geometry requirements, capacity, varying/MRT
  counts, instruction count, primitive/uniform requirements, static inputs.
- Words18..23: low/high words of varying, flat and centroid masks.
- Words24..36: default and twelve logical UBO byte requirements.
- Words37..40: four color types; word41 must be zero.

Version3 requires geometry ownership and requires primitive ownership to be
zero. Explicit ABI10 additionally requires independently verified geometry
pixel-mask service permission. ABI5 has `10+4*varyingSlots` input arrays; ABI7 adds three sample arrays.
Stride is 512 bytes at capacity128; the writable prefix follows inputs and
contains `4*colorSlots+2` arrays. Layer's static-input bit128 means real GS
Layer here, whereas version2's same numerical bit means no-GS Layer-zero.
The APIs, artifact magic/version and validators distinguish those meanings.
Both readers reject the other artifact; reserved fields, extra bytes and
failed reads leave the output unchanged. OGNSF001/002 are unchanged.

## Explicit geometry pixel-mask compilation

`opengpu_native_five_program_create_options` accepts a separate version1
compiler-options record. Its `geometry_pixel_mask_transport` must be exactly
0 or1, with both reserved words zero. Existing `program_create` supplies zero;
its ABI5/7 acceptance and bytes are unchanged. The option is captured before
GEM callbacks. It selects a compiler transport prerequisite, never a device
capability, kernel permission, discovery bit or public shader callback.

With the explicit option1, FS version3 selects ABI10: real geometry inputs,
no sample planes, mask-control enable bit6 and coverage bits10:7. Capacity128,
16 varyings and four MRTs require 74 input arrays and prefix47104 bytes,
identical in size to ABI5. SampleID, SamplePosition and sample-qualified input
are incompatible with this explicit pixel-rate mode and are rejected. Its
artifact still uses OGNSF003's exact inventory; ABI10 itself declares the
additional independent provider requirement. No old artifact is relabeled.
The old FS5/7 offline consumer deliberately continues rejecting ABI10 inputs.

A fresh 37-TU Linux CPU build passed all seven original-source cases and the
three precise source controls in 30.71 seconds, with 37 MMD files/257 distinct
dependencies. All 1311 GPU/reference and 8101 external inputs were unchanged.
The default six cases' 36 code/artifact files stayed byte-for-byte identical.
The same original pixel-mask ESSL is rejected by the default API and compiles
only with explicit options into a 436-word FS ABI10 program. It preserves
all 65 uniform references and five code objects, checks every allocation
rollback, and tests invalid option version/reserved/Boolean fields and
sample-plane incompatibility. Independent literals check the emitted shift7
and mask-enable64, prefix47104, geometry1/sample0/primitive0, and metadata
rejection after removing geometry or borrowing sample/primitive authority.

The original 436 FS words also passed an independent actual ABI10
validator/private-RX/MMU consumer with explicit offline-test permissions.
Clang ASan/UBSan took 3.58 seconds; 338 source/code inputs were unchanged.
It checked raw code, entry, precise scratch bounds and rejection without
geometry, geometry-mask or uniform permission. The independent nine semantic
controls passed. This consumer did not read OGNSF003 or import compiler
permission claims.

This is diagnostic CPU compilation and code protection. Its new
provider/admission/RTL source lives in a separate development topic and is
not enabled by this compiler.
It does not establish instruction execution, original five-stage values,
GUR2 contents or whole-owner submission/retirement.

## Validation and remaining work

Actual Mesa CPU validation is described by the opt-in runner below. It uses
original immutable Mesa libraries and modeled OS/GEM primitives; it does not
interpret or execute a shader. The real-source tests cover API binding71,
60 UBOs/5 defaults, first/last words, original view offsets, five code GEMs,
65-reference lease retention, rebind, short-view rollback, foreign context,
and each code-allocation failure. Independent source-copy controls remove GS
default retention, corrupt TES-to-GS mapping, or replace GS Layer with zero;
each must hit its own exact assertion, not a compilation error or timeout.

The final current-source build compiled 37 translation units from scratch
(including three source-copy controls), checked 37 MMD files and 257 distinct
dependencies, and passed in 30.71 seconds. The 1311 GPU/reference inputs and
8101 external Mesa/SDK/library inputs were unchanged before and after use.
All cases retain 65 uniform references, five code GEMs, 16 varying slots,
four MRTs and capacity128. VS/TC/TE/GS are 308/478/362/289 words:

| Original FS use | ABI | FS words | Prefix bytes |
|---|---:|---:|---:|
| GS PrimitiveID and Layer | 5 | 401 | 47104 |
| SampleID | 7 | 408 | 48640 |
| MaskIn and HelperInvocation | 5 | 434 | 47104 |
| PointCoord, original points GS | 5 | 427 | 47104 |
| Centroid varying | 5 | 401 | 47104 |
| Depth output | 5 | 408 | 47104 |

A separately compiled production validator/private-RX/MMU fixture consumed
these six original FS instruction files with explicit synthetic CPU geometry,
sample and uniform permissions. Clang16 ASan/UBSan passed in 3.417 seconds;
maximum sampled aggregate RSS was 202480 KiB, under the 180-second/512-MiB
limits. Its 654 source/code inputs were unchanged. It checks exact prefix
bounds, nonzero entry, raw RX bytes, publication/retirement, missing permissions,
and independent literal probes for every input, writable color/depth/control,
and read-only tail array. It does not import producer authority or execute
instructions. The new 289-word GS program also passed the actual independent
scalar-GS validator/private-RX/MMU fixture in 3.069 seconds, with maximum
sampled aggregate RSS 203392 KiB and 649 unchanged inputs.

The old four-stage family was rebuilt from scratch against the final common
source: 47 translation units, 47 MMD files/275 dependencies, 41.77 seconds.
Original 308/301 programs and all old v1/v2 byte comparisons against independent
original emitter sources passed, along with its four precise controls.
No cached object was counted as a fresh compilation.

The explicit NEXT_TCS VS compiler now admits vector floating ADD/MUL through
its own arithmetic gate; it never borrows the FS metadata flag. Legacy
NEXT_FRAGMENT acceptance remains integer-only. A real original matrix vertex
source adds `vec4(.125,.25,.5,0)` to payload while leaving position unchanged.
One original four-stage relink produces VS69/TCS277/TES203; TC/TE and FS
instruction/artifact bytes are identical to the unmodified source. The same
actual floating VS packet with its NEXT_SHADER property changed to FRAGMENT
is rejected atomically by the old compiler. Original 308/301 and v1/v2 bytes
still pass the independent baseline comparison.

The 69 original VS words passed the actual validator/private-RX/MMU consumer
at capacity128, attributes1/varyings16, scratch38400, with scalar-FP permission
both true and false. Pure OPFVV vector arithmetic does not depend on that
separate scalar permission. An independent scalar FLW literal still rejects
permission false and accepts true. The Clang16 ASan/UBSan group took 3.193
seconds, maximum sampled aggregate RSS199568 KiB, with 649 source/code inputs
unchanged. It checks original bytes, opaque nonzero entry, short scratch,
atomic failures, RX permissions and retirement; it does not execute shader
instructions. The original and variant source packages are separate.

The protected GS-linked ABI5/7 BUILD/prepare is now in main through GPU PRs
#959 and #960. PR #961 adds the no-TF geometry RTL source stage: GGA1/GNG1
readers, raw GS bridge, primitive/viewport/patch/bin pipeline, third router
client and all three image/vertex-input parents. Native geometry is disabled by
default, ordinary TF remains available when it is disabled, and ABI10 mask
control is not enabled. JDK21 focused tests pass 35/35, including 2/2 parent
elaboration checks; changed-suite selection passes 176 tests.

Actual original five-stage CU/frame execution remains pending. The next harness
keeps only `full5` and `pointcoord` at 4×4, with two CUs, all 65 uniform views,
four MRTs, independent image/clear/padding/guard checks and no-reset reuse.
Maximum-output GS lowering, remaining GLSL arithmetic/builtins and PointSize,
dynamic/ordered GS execution, TF, ABI10 provider/admission, protected host
submission and product qualification remain open. No component or CPU result
substitutes for those paths. The mandatory two-CU 640×480/60-FPS target remains
unchanged.

Reproduce the source/component stage with JDK 21 and Scala 2.13:

```sh
sbt 'testOnly opengpu.graphics.TessellationGeometryRawBridgeSpec opengpu.system.TessellationGeometryPipelineControlSpec opengpu.graphics.TessellationGeometryClientTransportSpec opengpu.graphics.TessellationGeometryVertexTupleSpec opengpu.system.TessellationJointGeometryJobCaptureReaderSpec opengpu.system.TessellationJointGeometryAttachmentReaderSpec opengpu.graphics.WindowPrimitiveRecordFaultPrioritySpec opengpu.graphics.TessellationVertexShaderRouterSpec opengpu.testutil.GpuSimInlineVerificationSpec opengpu.system.TessellationGeometryParentElaborationSpec'
python3 -B scripts/test_changed_test_suites.py
python3 -B scripts/test_test_selection.py
git diff --check
```

## No-TF direct typed-isoline harness

GPU PR #962 adds a direct typed-isoline harness for the original `full5` and
`pointcoord` programs at 4×4 on two physical CUs. It uses the native-geometry
vertex-input parent, all five original code objects, all 65 uniform views, four
MRTs, the full payload/cache/storage capacities and a real compute-store blocker.
The top and runtime contain no transform-feedback ports or fields, reject ABI10,
and do not instantiate the public/protected GNQ host route.

The real Ubuntu GCC13 Mesa compiler run passed with 65 retained references, five
code objects, 258 actual MMD dependencies and three semantic source controls.
Fresh-input private GUR1/GUR3/GUR2 owner generation passed with ASan/UBSan for
both cases, including 17 runtime rejection mutations. The Bundle-only shape
check passed 5,309 leaves and 1,404 assignments with no feedback leaves.

The retained full-capacity Verilator reset/idle build and two-case actual replay
also passed with `ACTUAL_ORIGINAL_FIVE_STAGE_ISOLINE_PASS cases=2 noReset=true
noTf=true physicalGraphicsCUs=2`. `full5` executed VS/TCS/TES/GS, produced zero
complete GS primitives and retained the independent clear image. `pointcoord`
executed all five stages, produced one point covering exactly four pixels with
the expected PointCoord/color values, and passed all four MRT image, padding and
guard checks. The real compute-store blocker held CU0 while graphics writebacks
committed on CU1; final acceptance observed committed graphics writeback on both
physical CUs, no traps and complete payload/source/task/memory drain.

This is a direct typed-isoline simulator qualification, not GNQ7/8/9 protected
host submission, public API execution, TF/ABI10 admission or product-FPS proof.
Reproduce the two-case path with:

```sh
WORK="$GPU/.artifacts/five-stage-qualification"
MODEL="$GPU/.artifacts/five-stage-model"
python3 -B scripts/test_native_five.py --five-chain-only --five-chain-handoff \
  --sanitize --negative-controls --build "$WORK/compiler"
python3 -B scripts/test_five_stage_runtime.py --build "$WORK/compiler" \
  --output "$WORK/runtime-plan.json" --sanitize
python3 -B scripts/qualify_five_stage.py --runtime-plan "$WORK/runtime-plan.json" \
  --input-root "$WORK/compiler" --build --work "$MODEL" \
  --memory-mib 24576 --timeout-seconds 13472
python3 -B scripts/qualify_five_stage.py --runtime-plan "$MODEL/runtime-plan.json" \
  --input-root "$WORK/compiler" --replay-model "$MODEL/model" \
  --memory-mib 4096 --timeout-seconds 7200
```

The qualifier removes the retained model/work directory after successful replay.
For hosts with shorter process limits, `--resume-workspace` can publish an exact
already-built slot after bounded direct Verilator/make continuation; it never
weakens source, port-ABI or option binding.

## Reproduction

Use the pinned immutable `cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72`
image with `--memory=512m --memory-swap=512m --cpus=1 --network=none --read-only`.
Mount the GPU/Mesa/SDK sources read-only and one private output under this
checkout's `.artifacts` writable. Reuse the existing Mesa22.3.6 libraries:

```sh
GPU=/Users/duckdonald/workspace/gpu
export OPENGPU_MESA_DIR="$GPU/depends/mesa"
export OPENGPU_MESA_BUILD="$GPU/depends/mesa-build"
export OPENGPU_DRM_INCLUDE="$GPU/depends/aarch64-root/include"
export LD_LIBRARY_PATH="$GPU/depends/aarch64-root/lib"
export OPENGPU_MESA_LINK_LIBS="-l:libz.so.1 $GPU/depends/aarch64-root/lib/libexpat.so.1.6.7"
python3 -B scripts/test_native_five.py --sanitize --negative-controls \
  --build "$OUT/build"
```

Bind original/generated inputs, actual MMD dependencies, binary and ESSL
files before/after use. Enforce the 180-second group deadline and prove owned
process groups/container have exited before deleting ended output. Reuse mode
is only for active private diagnosis with exact argv/object/MMD-input SHA
receipts; it must not be reported as a fresh build.

The independent offline FS consumer has an explicit test profile per file;
these arguments describe CPU policy only:

```sh
python3 -B scripts/test_native_geometry_fragment_artifacts.py --sanitize \
  --fragment-artifact "$OUT/build/full5.fs.words" 5 16 4 1 1 0 \
  --fragment-artifact "$OUT/build/full7.fs.words" 7 16 4 1 1 1 \
  --fragment-artifact "$OUT/build/pixel-inputs.fs.words" 5 16 4 1 1 0 \
  --fragment-artifact "$OUT/build/pointcoord.fs.words" 5 16 4 1 1 0 \
  --fragment-artifact "$OUT/build/centroid.fs.words" 5 16 4 1 1 0 \
  --fragment-artifact "$OUT/build/depth.fs.words" 5 16 4 1 1 0
python3 -B scripts/test_geometry_program_snapshot.py --sanitize \
  --program "$OUT/build/full5.gs.words" --owned-uniforms
```

The opt-in four-stage runner also emits the original-source floating payload
variant. It must run in the same bounded original-Mesa environment:

```sh
git show bcdbdfd70:userspace/gallium/opengpu_native_uniform_emit.c > "$OUT/reference-uniform.c"
git show c6eddf7d5:userspace/gallium/opengpu_native_tess_uniform_emit.c > "$OUT/reference-tess.c"
python3 -B scripts/test_native_joint.py --sanitize --negative-controls \
  --legacy-source "$OUT/reference-uniform.c" \
  --tess-baseline-source "$OUT/reference-tess.c" --build "$OUT/four"
python3 -B scripts/test_native_vertex_artifacts.py --sanitize \
  --vertex-artifact "$OUT/four/matrix-payload.vs.words" 1 16 0
```

The same fresh five-stage runner regenerates the diagnostic original ABI10
packet; no separate word patch or handwritten shader is used:

```sh
python3 -B scripts/test_native_five.py --sanitize --negative-controls \
  --build "$OUT/five"
# Original linked source: $OUT/five/sources/pixel-mask.{vert,tesc,tese,geom,frag}
# Raw FS words:          $OUT/five/pixel-mask-enabled.fs.words
# OGNSF003 artifact:     $OUT/five/pixel-mask-enabled.ognsf
```

## Original GS handoff for explicit local ISA qualification

The test-only `--geometry-handoff` switch exports the live five-stage source
lease's original GS code, GNG layout, TES-to-GS maps and thirteen retained
constant views, after caller resources/programs have been rebound or destroyed.
Each view retains its actual handle, decimal-string 64-bit offset and extent.
The runner immediately validates exact metadata/EOF/file names and binds every
new output before the next invocation. These files grant no shader permission.

Using the documented immutable Mesa 22.3.6 environment, run:

```sh
python3 -B scripts/test_native_five.py --build "$PRIVATE_BUILD" --geometry-handoff
```

The bounded Linux CPU run passed in 18.929 seconds: 34 fresh translation units,
34 MMD files and 253 distinct dependencies; 1,060 compiler/source inputs and
the external Mesa/SDK/library snapshot remained unchanged. Both original
`full5` and relinked `pointcoord` produced the same 289 GS instruction words,
with their distinct original line-strip/point metadata retained. Original
`full5` emits one line-strip vertex and therefore has zero complete primitives;
the point source supplies the positive downstream test case.

A separate default-off run passed in 9.388 seconds with one fresh resource TU
and 33 exactly matched reused TUs. All 35 original instruction files were
byte-identical, and it generated no handoff files. The actual GS289 bytes also
passed the independent geometry validator/private-RX/MMU/retirement fixture
with explicit offline uniform permission. None of these CPU checks executes a
shader or qualifies the complete five-stage owner chain. Ended containers,
objects and logs were removed; only explicitly active consumer inputs remain.
