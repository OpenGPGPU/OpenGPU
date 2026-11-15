# Private linked four-stage program and source lease

Source delivery: [PR #827](https://github.com/OpenGPGPU/OpenGPU/pull/827), based
on merged #826. Full P17 target acceptance remains pending.

`opengpu_native_joint` compiles one original ESSL320 VS/TCS/TES/FS link. Its
opaque compiler object owns the four TGSI streams, original NIR variable spans,
linked block order and HIR fragment static-use facts. Program creation copies
all those inputs before any code-GEM callback. VS uses `NEXT_TCS`; TCS/TES use
the checked language-v3 dialect; FS is actually re-emitted through the checked
uniform ABI8/9 compiler. This does not stitch a separately linked VS/FS program
onto borrowed tessellation views or change a legacy program's ABI tag.

The two boundaries are independent:

- VS SoA output words map to the actual packed TCS inputs through 64 typed
  `(kind, component)` entries. Position uses kind0; original VS varying words
  use kind1. Sparse location15 stays semantic15 rather than becoming ordinal0.
- TCS output and TES input share one packing of the linked patch declarations.
  TES outputs then map separately to the 68 destination FS input components.
  TES export size is independent of TCS patch-output size. FS can retain its
  full 16-slot prefix even when a sparse source packs into fewer TES components.

Matrices and arrays keep their original NIR spans, semantic indices and vector
width. Dynamic TES register offsets use the actual component width; a vec3
column advances three components, not four. Full-width dynamic indices retain
checked sentinels and cannot borrow an adjacent variable's storage. Mesa may
reserve a vec4 TGSI declaration for a real vec2/vec3 input. Only the explicit
linked API accepts that declaration padding; actual missing-channel operands
remain rejected. It also emits existing vector conversion instructions for
real TGSI I2F/U2F/F2I/F2U. Decoder and `VectorFcvtAlu` already implement their
exact selectors; the old unmapped compiler entry points retain their acceptance
and byte contracts. CPU compilation does not prove conversion results on a CU.

The program owns all four actual code GEMs. A source lease retains the program
and up to 52 original backing references: four copied defaults and 48 original
UBO windows. Each stage keeps 12 logical blocks, 1024 default components and
16KiB blocks; API bindings0..71 are resolved through actual Mesa reflection.
VS/FS use one checked private table and TCS/TES another, each at most24 entries.
No global72 binding number is treated as a private table index. Rebinding,
destroying the constants cache, or releasing the caller's program does not
change an existing lease. Exact windows of the same original GEM remain
separate, and old copied defaults remain retained across rebind.

`source_describe` returns copied code/uniform views and component maps. It does
not accept caller-supplied replacement compiler metadata. It freezes references
and view metadata, not mutable data or fences. The context and screen must
outlive their objects; normal Gallium single-thread ownership applies. The
protected caller must still capture original code/data and fences, reconstruct
RX/GUR1/GUR3, validate aliases and retain the complete owner until retirement.

The P16 joint source uses VS code/uniforms in each original draw plan, TCS/TES
code/uniforms in its sequence plan, and identical FS code/uniforms in both the
draw and state plans. Original draw indices, invocation IDs, range bounds,
GVS/GVM directory identity, original attributes/index/scratch views and all raw
record construction belong to that caller and kernel BUILD. The compiler does
not infer these identities from shader tokens or manufacture patch inputs.

No public shader callback, capability, UAPI, getter, factory, GSS1 or discovery
word is enabled. The existing private112 cache supplies physical capacity and
context identity only; it does not grant primitive/sample, owned-patch or
language-info permission. Those require an independent verified joint profile.

## CPU validation

Actual Mesa22.3.6/NIR/TGSI and real Gallium/winsys bodies passed the new
four-stage lifecycle test under ASan/UBSan. Only OS/GEM primitives were modeled;
no shader was interpreted or executed and no work was submitted.

| Original linked case | VS / TCS / TES / FS words | TCS input vertices/components → output vertices/components; per-patch | TES exports | FS ABI; varying slots/MRTs |
| --- | --- | --- | ---: | --- |
| 48 blocks, sparse VS15 → FS0 | 308 / 478 / 362 / 308 | 2/8 → 2/8; 0 | 8 | 8; 1/1 |
| 48 blocks + SampleID, VS1 → FS15 | 308 / 478 / 362 / 317 | 2/8 → 2/8; 0 | 8 | 9; 16/1 |
| Sparse matrix/vec3 patch-array reads | 61 / 277 / 203 / 58 | 2/8 → 2/10; 6 | 8 | 8; 16/1 |

Both full-resource cases exercised all 48 original 16KiB windows, API binding71,
four copied 1024-component defaults, and all 52 retained references. Assertions
checked positive rebind into a second lease, immutable old metadata/data views,
old default copies after rebind and cache destruction, caller program release,
foreign context/stage rejection, transferred-reference consumption, short-view
failure and each of four code-allocation failures plus map failure. Returned
metadata copies could not retag a program. Invalid source maps/packet bounds
failed before a code-GEM callback. The matrix case checked independent literal
component counts, the emitted shift/add address sequence, and rejection of an
actual TES token operand changed to padding.w. Actual arithmetic is covered
separately below; a constant or instruction-pattern count is not execution. Numerical metadata tests retained
64-component/120-per-patch packing and rejected oversized output atomically.

Old linked VS/FS remained exactly 308/301 words before and after the new cases,
byte-for-byte equal to the separately compiled original emitter. Three v1 and
five v2 original ESSL cases also matched the independently supplied original
production tessellation emitter's full machine words, static selections,
constant extents, masks and barriers. The rejected original TCS indirect-store
case remained a separate rejection, not a renamed passing static-store test.

Two independent production-source mutants failed precise real assertions:
omitting backing retention killed a still-leased copied-default GEM; forcing
the vec3 final shift from3 to4 removed the required emitted sequence.
Neither control used a shader VM, timeout or a reduced hardware capacity.

The final frozen-source run compiled 45 fresh translation units: 35 for the
joint build, eight for independent old compatibility and two for source-copy
negative controls. It reused no private objects. All 263 actual MMD dependencies
and 8101 external Mesa/DRM/SDK/library identities were bound, with original GPU,
ESSL, Python, schema, generated sources, binary and emitted packets before use
and afterwards. Sixty child groups totalled 36.75 seconds; the longest was 4.678
seconds and every group had a 180-second bound. Maximum waited-child RSS was
263400 KiB (not an aggregate-process memory measurement). Docker confirmed exit 0,
OOMKilled=false, memory=memory-swap 512MiB, one CPU and no network. Original Mesa
libraries were reused. All ended build objects, caches, logs and private
baseline copies were deleted; only explicitly handed-off original program
packets remained while the independent protection consumer needed them.

The corrected 203-word matrix TES and all eleven companion programs passed
independent validator/private RX/MMU consumption on the mainline delivery source
below. Those checks do not establish complete 52-resource GUR ownership, joint
BUILD or shader execution.

### Mapped address arithmetic

Review found that the original mapped path used funct6`0x25` with IVX, which
is `vsll.vx`, not multiplication. Checking a literal24 did not prove a vec3
stride. The corrected production helper compares the original full32-bit index
before arithmetic, uses shifts3/4/5 for widths1/2/4, and `(index<<1)+index`, then
`<<3`, for width3. The original register and cached vertex remain intact.
Out-of-span values select the high-bit sentinel before the owned patch service
can truncate or reinterpret the address. The old unmapped emission is unchanged.

The test-only exporter calls that same helper, used by actual `dynamic_input`,
and emits original instruction words for four widths. A fresh six-source
Chisel-plugin build passed in6.86 seconds (358560 KiB maximum sampled aggregate
RSS). Actual `VectorDecoder` and full16-lane/eight-warp `VectorIntegerAlu` RTL
passed all four widths in13.20 seconds (408256 KiB), including index1,2,last,
nonzero base/flags, limit, `0xffffffff` and high-bit indices. Original ADDR,
cached vertex and pending data registers were unchanged. An independent old
shift implementation failed the exact index1 assertion:299 instead of51,
in13.44 seconds (419056 KiB). Neither case timed out. Each run used128MiB heap,
128MiB metaspace,32MiB code cache, one CPU, a180-second process-group bound and
512MiB sampled aggregate memory guard. The fixture supplies register/scalar
peers for this arithmetic microsequence; it is not a shader/CU/patch-RAM test.

The Scala fixture is outside default sbt test sources. It requires explicitly
exported words and is not claimed as automatic CI coverage. Existing callbacks,
compiler cases and old-byte comparisons still run through their existing lanes.


## Mainline delivery integration

The three logical source commits (`9ed94be7b`, `9fde939af`, `53103920c`) were
replayed onto merged #826. The delivery preserves main's callback-capture and
primitive-program regressions and its recursive producer-macro closure. On
exact corrected source `3a134a96b`, 3120 tracked non-document inputs, HEAD and
18 original packet/layout files stayed unchanged through these serial checks:

| Check | Result | Elapsed / maximum sampled aggregate RSS |
| --- | --- | --- |
| Strict prepared-source producer closure | 21 tests PASS | 22.18s / 92784KiB |
| Graphics qualifier guards | 13 tests PASS | 0.37s / 36064KiB |
| Sanitized graphics bridge | 8 tests PASS | 10.27s / 329072KiB |
| Original full8 TCS/TES v3 | 478/362 words PASS | 3.41s / 212896KiB |
| Original full9 TCS/TES v3 | 478/362 words PASS | 3.41s / 209200KiB |
| Original matrix TCS/TES v3 | 277/203 words PASS | 3.39s / 207168KiB |
| Three original FS8/9 artifacts | 308/317/58 words PASS | 3.02s / 198208KiB |
| Three original VS ABI1 artifacts | 308/308/61 words PASS | 3.40s / 26416KiB |

Every group had a 180-second/512MiB aggregate guard. Program checks used actual
production validators, opaque RX owners and MMU, with Clang16 ASan/UBSan. VS
profiles retained attributes1, varying slots16/2/16, capacity128 and exact
scratch38400/9728/38400; uniform permission was required for the two full VS
programs. Nonzero entry, short scratch, permission rejection, failed copy and
retirement checks remained active. The new vertex entry also ran the original
no-argument program fixture unchanged. This is twelve original program byte
consumers, not shader execution or a complete GUR/joint owner graph.

Reproduce producer/qualifier/bridge checks with
`test_prepare_owner_executor.py`, `test_qualify_native_sample_graphics_owner.py`
and `test_gallium_sample_graphics_bridge.py`. Original program consumers are
`test_tessellation_program_snapshot.py` (explicit v3, owned language-info and
captured patch layouts from the table), `test_native_program_snapshot.py
--owned-primitive-inputs --primitive-artifacts`, and the vertex command below.
Temporary consumer outputs were removed after process-group and consumer
checks. The original matrix inputs remain only while the separate actual-CU
qualification consumer needs them.

## Limits

This API covers four stages. A native GS emitter/cache, TES-to-GS boundary and
real five-stage 60-block/five-default (65-reference) lease remain pending. The
existing five-stage reflection fixture does not prove those resources.

The current checked tessellation transport has 64 per-vertex components
including applicable builtins, 120 per-patch components and 2048 coupled words.
The compiler preserves those limits and 512 native words per stage. Position
plus 64 user components cannot fit this transport; this is a current transport
limit, not a claim that such GLSL is illegal or that the full P25 target shrinks.

A real original TCS `vectors[gl_InvocationID] = ...` patch-array store produces
TGSI `MOV OUT[ADDR[0].x+4].xyz, ...`: an indirect destination register, not a
per-vertex dimension. The old checked packet contract rejects it. The test keeps
that original source as an explicit unsupported case; a separate static-store
case isolates and validates dynamic TES matrix/patch-array reads. Full TCS
indirect-store lowering is still required. No rejected case is reported as a
successful shader result.

Actual protected joint submit, VS-to-owned-patch/TCS/TES/FS CU execution,
complete descriptor/image/GNY2 oracles, same-host five-stage execution and the
mandatory two-CU640x480/60FPS qualification remain pending. CPU resource and
compiler checks do not replace them. Scala regression remains disabled and
must not be enabled or dispatched.

## Reproduction

Use the immutable image and read-only Mesa22.3.6/SDK/LD setup in
[NATIVE_FRAGMENT_PRIMITIVE_COMPILER.md](NATIVE_FRAGMENT_PRIMITIVE_COMPILER.md#reproduction),
with512MiB combined memory/swap, one CPU and no network. Original Mesa libraries
are reused. Create a private output and two read-only original source baselines:

```sh
mkdir "$OUT/reference"
git -C "$SOURCE" show bcdbdfd70:userspace/gallium/opengpu_native_uniform_emit.c \
  > "$OUT/reference/opengpu_native_uniform_emit.c"
git -C "$SOURCE" show c6eddf7d5:userspace/gallium/opengpu_native_tess_uniform_emit.c \
  > "$OUT/reference/opengpu_native_tess_uniform_emit.c"
# Replace the command inside that same constrained Docker invocation:
python3 -B scripts/test_native_joint.py --sanitize --negative-controls \
  --legacy-source "$OUT/reference/opengpu_native_uniform_emit.c" \
  --tess-baseline-source "$OUT/reference/opengpu_native_tess_uniform_emit.c" \
  --build "$OUT/build"
```

The tracked offline VS consumer runs the original no-argument native-program
fixture first, then checks each supplied program through the actual validator,
private RX owner and MMU. It accepts the full 512-word ABI1 capacity. The three
original linked Mesa VS programs passed with their independent layout and
uniform requirements in the same constrained Linux image (3.30 seconds,
ASan/UBSan):

```sh
python3 -B scripts/test_native_vertex_artifacts.py --sanitize \
  --vertex-artifact "$OUT/build/full8.vs.words" 1 16 1 \
  --vertex-artifact "$OUT/build/full9.vs.words" 1 2 1 \
  --vertex-artifact "$OUT/build/matrix.vs.words" 1 16 0
```

Arguments are attributes, varyings and explicit offline owned-uniform permission;
capacity128 and VS ABI1 are fixed. Short scratch, missing required uniform
permission, incorrect entry and failed copy are rejected; nonzero entry retains
the exact original suffix, private PTEs are RX without W, and owners retire.
A full 512-word literal additionally covers the entry-zero capacity boundary.
Ten malformed file/metadata arguments fail before compilation. The runner
honors `TMPDIR` for read-only source mounts; the earlier read-only temporary-directory
failure occurred before byte consumption and is excluded from successful results.
The original Python runner also passed unchanged on the host. The final
regeneration and consumed-source limits are recorded above. These are offline
CPU protection checks, not shader interpretation, joint BUILD, permission for
submission or GPU execution.

The address microsequence can be reproduced independently (no Mesa rebuild):

```sh
python3 -B scripts/test_tessellation_mapped_address.py --dump "$OUT/address-words"
# Explicit local-only test source; this command is not Scala regression dispatch.
sbt -Dopengpu.mappedAddressPrograms="$OUT/address-words" \
  'set Test / unmanagedSources += file("userspace/tests/scala/TessellationMappedAddressSpec.scala")' \
  'testOnly opengpu.graphics.tessellation.TessellationMappedAddressSpec'
```

The recorded run used direct Scala2.13.18 compiler/Chisel7.15 plugin invocation
rather than sbt: freshly compile `DecodeTypes.scala`, `VectorDecoder.scala`,
`VectorIntegerTypes.scala`, `VectorIntegerAlu.scala`, the current `GpuSim.scala`
and the explicit fixture, with new classes first in an unchanged dependency
classpath. Run `org.scalatest.tools.Runner -oD -s
opengpu.graphics.tessellation.TessellationMappedAddressSpec` with the property
above and `opengpu.simJobs=1`, `simLowMem=1`, `simLowMemOpt=0`,
`simSeparateBuild=1`, `simCache=off` (each prefixed `opengpu.`). Export to another
empty directory with `--wrong-shift-control` to require the precise old-bug
failure. Never enable or dispatch the disabled Scala regression workflow.

Every compile/link/test process has a 180-second bound and process-group exit
proof. Source/ESSL/schema/generated inputs, original Mesa/DRM/SDK/link objects and
libraries, actual per-TU MMD inputs, binary and emitted packets are bound before
use and checked afterwards. `--reuse-objects` is only for an active private
build whose packet consumers have been released: it requires exact recorded
argv, object/MMD hashes and every actual dependency hash before reuse. Reused
objects must be reported separately from fresh compilation. Do not retain
ended build caches, raw packets, receipts or logs after their consumers finish.
