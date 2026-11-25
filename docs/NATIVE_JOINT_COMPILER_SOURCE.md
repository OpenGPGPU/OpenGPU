# Private linked four-stage program and source lease

Source delivery: [PR #827](https://github.com/OpenGPGPU/OpenGPU/pull/827), based
on merged #826. Full P17 target acceptance remains pending.

`opengpu_native_joint` compiles one original ESSL 320 VS/TCS/TES/FS link. Its
opaque compiler object owns the four TGSI streams, original NIR variable
spans, linked block order and HIR fragment static-use facts. Program creation
copies all those inputs before any code-GEM callback. VS uses `NEXT_TCS`;
TCS/TES use the checked language-v3 dialect; FS is actually re-emitted through
the checked uniform ABI 8/9 compiler. This does not stitch a separately linked
VS/FS program onto borrowed tessellation views or change a legacy program's
ABI tag.

The two boundaries are independent:

- VS SoA output words map to the actual packed TCS inputs through 64 typed
  `(kind, component)` entries. Position uses kind 0; original VS varying words
  use kind 1. Sparse location 15 stays semantic 15 rather than becoming ordinal 0.
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
16 KiB blocks; API bindings 0..71 are resolved through actual Mesa reflection.
VS/FS use one checked private table and TCS/TES another, each at most 24
entries. No global 72 binding number is treated as a private table index.
Rebinding, destroying the constants cache, or releasing the caller's program
does not change an existing lease. Exact windows of the same original GEM
remain separate, and old copied defaults remain retained across rebind.

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

No public shader callback, capability, UAPI, getter, factory, GSS1 or
discovery word is enabled. The existing private 112-word cache supplies
physical capacity and context identity only; it does not grant
primitive/sample, owned-patch or language-info permission. Those require an
independent verified joint profile.

## CPU validation

Actual Mesa 22.3.6/NIR/TGSI and real Gallium/winsys bodies passed the new
four-stage lifecycle test under ASan/UBSan. Only OS/GEM primitives were
modeled; no shader was interpreted or executed and no work was submitted.

| Original linked case | VS / TCS / TES / FS words | TCS input vertices/components → output vertices/components; per-patch | TES exports | FS ABI; varying slots/MRTs |
| --- | --- | --- | ---: | --- |
| 48 blocks, sparse VS15 → FS0 | 308 / 478 / 362 / 308 | 2/8 → 2/8; 0 | 8 | 8; 1/1 |
| 48 blocks + SampleID, VS1 → FS15 | 308 / 478 / 362 / 317 | 2/8 → 2/8; 0 | 8 | 9; 16/1 |
| Sparse matrix/vec3 patch-array reads | 61 / 277 / 203 / 58 | 2/8 → 2/10; 6 | 8 | 8; 16/1 |
| TessLevel inputs plus dynamic patch-array reads | 61 / 277 / 236 / 58 | 2/8 → 2/10; 6 | 8 | 8; 16/1 |
| Original indirect patch-array write | 61 / 275 / 203 / 58 | 2/8 → 2/10; 6 | 8 | 8; 16/1 |
| Indirect write in actual SIMT arms | 61 / 315 / 203 / 58 | 2/8 → 2/10; 6 | 8 | 8; 16/1 |

Both full-resource cases exercised all 48 original 16 KiB windows, API binding
71, four copied 1024-component defaults, and all 52 retained references.
Assertions checked positive rebind into a second lease, immutable old
metadata/data views, old default copies after rebind and cache destruction,
caller program release, foreign context/stage rejection, transferred-reference
consumption, short-view failure and each of four code-allocation failures plus
map failure. Returned metadata copies could not retag a program. Invalid
source maps/packet bounds failed before a code-GEM callback. The matrix case
checked independent literal component counts, the emitted shift/add address
sequence, and rejection of an actual TES token operand changed to padding.w.
Actual arithmetic is covered separately below; a constant or
instruction-pattern count is not execution. Numerical metadata tests retained
64-component/120-per-patch packing and rejected oversized output atomically.

Old linked VS/FS remained exactly 308/301 words before and after the new
cases, byte-for-byte equal to the separately compiled original emitter. Three
v1 and five v2 original ESSL cases also matched the independently supplied
original production tessellation emitter's full machine words, static
selections, constant extents, masks and barriers. The original TCS
indirect-store source now passes through its actual linked compiler path; the
static-store source remains a separate case.

Four independent production-source mutants failed precise real assertions:
omitting backing retention killed a still-leased copied-default GEM; forcing
the vec3 final shift from 3 to 4 removed the required emitted sequence;
skipping fn20 removed parameter capture before fn22; increasing the
two-element store span to 3 changed its literal bound. The latter checks prove
emitted structure, not shader results. Actual address arithmetic is covered
separately below. No control used a shader VM, timeout or a reduced hardware
capacity.

The final frozen-source run compiled 47 fresh translation units: 35 for the
joint build, eight for independent old compatibility and four for source-copy
negative controls. It reused no private objects. All 265 actual MMD
dependencies and 8101 external Mesa/DRM/SDK/library identities were bound,
with original GPU, ESSL, Python, schema, generated sources, binary and emitted
packets before use and afterwards. The complete run took 54.792 seconds. The
68 child groups totalled 39.710 seconds; the longest was 4.661 seconds and
every group had a 180-second bound. Maximum waited-child RSS was 263440 KiB
(not an aggregate-process memory measurement). Docker confirmed exit 0,
OOMKilled=false, memory=memory-swap 512 MiB, one CPU and no network. Original
Mesa libraries were reused. All ended build objects, caches, logs and private
baseline copies were deleted; only explicitly handed-off original program
packets remained while the independent protection consumer needed them.

An independent consumer on delivery source `3a134a96b` accepted the corrected
12-program family through actual production validators/private RX/MMU: the
three TCS/TES pairs took 3.41/3.41/3.39 seconds, FS 3.02 and VS 3.40 seconds.
All 3138 source/artifact inputs and HEAD stayed unchanged. This includes the
corrected matrix TES 203 and remains a CPU protection result. Independently,
source `4c7b60138` consumed both new TCS 275/TES 203 and TCS 315/TES 203 pairs
through the actual production validator/private RX/MMU under ASan/UBSan. Each
pair took 3.66 seconds (maximum sampled aggregate RSS 212400 and 210688 KiB),
with 3136 source/artifact inputs and HEAD unchanged. The final fresh run
emitted those same original bytes. Complete 52-resource GUR ownership, joint
BUILD and shader execution remain separate requirements.

The actual TES source reading `gl_TessLevelOuter.xy`, `gl_TessLevelInner.x`
and a dynamically indexed patch array initially reached Mesa
`nir_lower_system_values` with unsupported array-valued system values. The
private frontend now explicitly selects Mesa `GLSLTessLevelsAsInputs`: the
existing checked backend consumes compact patch inputs. This does not change
public screen capabilities. The test checks the original NIR compact spans and
actual TGSI operands, including the indirect PATCH access. No ambiguous span
lookup was observed for this real case, so the TES span lookup is unchanged.
Independent old v1/v2 byte comparisons still pass with this frontend
configuration. An independent consumer on delivery `079de6388` accepted the
original TCS 277/TES 236 pair through the actual validator/private RX/MMU
under ASan/UBSan in 3.63 seconds, with 204448 KiB maximum sampled aggregate
RSS. All 3140 inputs, membership and HEAD remained unchanged under the
180-second/512 MiB guard. The consumer explicitly selected program v3, owned
tessellation info and the actual owned patch layout; using the legacy v1
defaults correctly rejects this program. This remains a CPU protection result,
not shader execution.

### Mapped address arithmetic

Review found that the original mapped path used funct6 `0x25` with IVX, which
is `vsll.vx`, not multiplication. Checking a literal 24 did not prove a vec3
stride. The corrected production helper compares the original full 32-bit
index before arithmetic, uses shifts 3/4/5 for widths 1/2/4, and
`(index<<1)+index`, then `<<3`, for width 3. The original register and cached
vertex remain intact. Out-of-span values select the high-bit sentinel before
the owned patch service can truncate or reinterpret the address. The old
unmapped emission is unchanged.

The test-only exporter calls that same helper, used by actual `dynamic_input`,
and emits original instruction words for four widths. A fresh six-source
Chisel-plugin build passed in 6.86 seconds (358560 KiB maximum sampled
aggregate RSS). Actual `VectorDecoder` and full 16-lane/eight-warp
`VectorIntegerAlu` RTL passed all four widths in 13.20 seconds (408256 KiB),
including indices 1, 2 and the last legal index, nonzero base/flags, limit,
`0xffffffff` and high-bit indices. Original ADDR, cached vertex and pending
data registers were unchanged. An independent old shift implementation failed
the exact index-1 assertion: 299 instead of 51, in 13.44 seconds (419056 KiB).
Neither case timed out. Each JVM used a 128 MiB heap, 128 MiB metaspace, 32
MiB code cache, `ActiveProcessorCount=1` and SerialGC, with a 180-second
process-group bound and a 512 MiB sampled aggregate memory guard. Native
Verilator retained the upstream `-j0` setting; this was not a strict one-CPU
native-process limit. The fixture supplies register/scalar peers for this
arithmetic microsequence; it is not a shader/CU/patch-RAM test.

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

The original TCS `vectors[gl_InvocationID] = ...` produces TGSI `MOV
OUT[ADDR[0].x+4].xyz, ...`: an indirect destination register, distinct from
its per-vertex dimension. It is admitted only by explicit linked TCS v3 entry
points with the retained original output span and packing. Legacy v1/v2 and
nonlinked language-v3 entry points still reject it without changing output.

The compiler requires an exact GENERIC/per-vertex or PATCH/per-patch semantic
match. Compact tessellation-level placeholders do not create addressable spans
or permission to borrow a neighboring variable. Defined full 32-bit register
indices select a single original span; bounds are checked before multiplying
actual packed component widths. Per-vertex base addition also keeps the
original full index for its bounds check. OOB values become a high-bit
sentinel rather than wrapping into a valid descriptor. Dynamic per-vertex
output masks include all possible legal components. Params fn20 is emitted
after address/mask scratch, immediately before fn22; the actual owned provider
retains its existing execution/side-effect masks, descriptor bounds, fault and
drain behavior.

Two real original ESSL cases cover the original write and an xyz/zyx SIMT
split. Ten actual-token negatives reject bad
length/file/index/swizzle/ArrayID/padding, wrong dimension and an undefined
ADDR, with unchanged error output. Both sources retain their original writes;
static substitution is not used. Supplemental literal helper streams
additionally exercised actual Decoder/IntegerAlu PP and per-vertex addresses,
base 1 and full 32-bit wrap: fresh six-source compile 7.33 seconds, 359376 KiB
maximum sampled aggregate RSS; RTL 13.94 seconds, 418384 KiB, under the same
128 MiB JVM heap, `ActiveProcessorCount=1`/SerialGC, 180-second group bound
and 512 MiB sampled aggregate memory guard. Native Verilator again retained
upstream `-j0`; no strict native CPU limit is claimed. Register/scalar peers
do not execute fn20/22, patch storage, a shader or CU. All xyz components of
both legal columns are checked in the output-mask fixture. Broader supported
TCS lowering and actual shader/patch results must still be qualified
separately.

Actual protected joint submit, VS-to-owned-patch/TCS/TES/FS CU execution,
complete descriptor/image/GNY2 oracles, same-host five-stage execution and the
mandatory two-CU 640x480/60 FPS qualification remain pending. CPU resource and
compiler checks do not replace them. Scala regression remains disabled and
must not be enabled or dispatched.

## Reproduction

Use the immutable image and read-only Mesa 22.3.6/SDK/LD setup in
[NATIVE_FRAGMENT_PRIMITIVE_COMPILER.md](NATIVE_FRAGMENT_PRIMITIVE_COMPILER.md#reproduction),
with 512 MiB combined memory/swap, one CPU and no network. Original Mesa
libraries are reused. Create a private output and two read-only original
source baselines:

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

For independent protection of the levels case, use a checkout containing the
v3 snapshot consumer. Keep both programs unchanged and pass the actual layout
and explicit offline permissions:

```sh
mkdir "$OUT/levels-consumer"
cp "$OUT/build/matrix-levels.tcs.words" "$OUT/levels-consumer/native-tcs.words"
cp "$OUT/build/matrix-levels.tes.words" "$OUT/levels-consumer/native-tes.words"
python3 -B scripts/test_tessellation_program_snapshot.py --sanitize \
  --programs "$OUT/levels-consumer" --tcs-layout 2 8 2 10 6 --tes-layout 0 8 \
  --tcs-program-version 3 --tes-program-version 3 --owned-tessellation-info \
  --tes-patch-layout 2 8 2 10 6
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
capacity 128 and VS ABI1 are fixed. Short scratch, missing required uniform
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

The recorded run used direct Scala 2.13.18 compiler/Chisel 7.15 plugin
invocation rather than sbt: freshly compile `DecodeTypes.scala`,
`VectorDecoder.scala`, `VectorIntegerTypes.scala`, `VectorIntegerAlu.scala`,
the current `GpuSim.scala` and the explicit fixture, with new classes first in
an unchanged dependency classpath. Run `org.scalatest.tools.Runner -oD -s
opengpu.graphics.tessellation.TessellationMappedAddressSpec` with the property
above and `opengpu.simJobs=1`, `simLowMem=1`, `simLowMemOpt=0`,
`simSeparateBuild=1`, `simCache=off` (each prefixed `opengpu.`). Export to
another empty directory with `--wrong-shift-control` to require the precise
old-bug failure. Never enable or dispatch the disabled Scala regression
workflow.

Every compile/link/test process has a 180-second bound and process-group exit
proof. Source/ESSL/schema/generated inputs, original Mesa/DRM/SDK/link objects and
libraries, actual per-TU MMD inputs, binary and emitted packets are bound before
use and checked afterwards. `--reuse-objects` is only for an active private
build whose packet consumers have been released: it requires exact recorded
argv, object/MMD hashes and every actual dependency hash before reuse. Reused
objects must be reported separately from fresh compilation. Do not retain
ended build caches, raw packets, receipts or logs after their consumers finish.

## Original triangle and quad domain fixtures (CPU only)

The opt-in `test_native_joint.py --domains` adds two separately linked ESSL
recipes, `matrix-triangle` and `matrix-quad`. Their VS, FS, input/output patch
vertex counts (two), sparse location-15 boundaries, matrix/vec3 packing and
TES indices default remain the original matrix recipe. TCS invocation zero
writes every required level to one (triangle: three outer and one inner;
quad: four outer and two inner), then all invocations reach the original
barrier. TES uses `triangles` or `quads`, equal spacing and counter-clockwise
ordering, and adds the real `gl_TessCoord.xyz` to its matrix result. These are
original Mesa-linked programs, not patched machine words.

Only these explicit new fixture modes export an additional `.domain.json`:

```json
{"schema":"opengpu-native-joint-domain-v1","version":1,"stage":5,"domain":4,"spacing":2,"clockwise":0,"pointMode":0}
```

Each value comes from the actual queried TES program layout and is checked
against the original TGSI properties. `domain` and `spacing` use Mesa's
`pipe_prim_type` and `pipe_tess_spacing`: triangle=4, quad=7, equal=2. They are
**not RTL enum values**. The consumer must explicitly translate them. Existing
nine-number `.layout` and source-v1 JSON formats are unchanged; existing calls
produce no domain receipt. Four independent metadata disagreements must fail
emission with its output untouched. Missing, duplicate, extra, non-integer,
wrong-version and wrong-recipe receipt fields are rejected by the CPU reader.

Fresh GCC 13.3/ASan/UBSan validation passed in the immutable CPU image below,
with 512 MiB combined memory/swap and one CPU: 52 fresh translation units,
zero reused objects, 280 actual MMD dependencies and 8,101 guarded Mesa/external
inputs. The producer finished in 56.20 seconds; the independent protected
consumer then passed both recipes. Source/archive guards passed before and after.

| Actual linked recipe | VS / TCS / TES words | Patch input → output; per-patch components | TES exports |
| --- | --- | --- | ---: |
| Triangle, all required levels=1, real TessCoord | 61 / 236 / 221 | 2/8 → 2/10; 6 | 8 |
| Quad, all required levels=1, real TessCoord | 61 / 254 / 221 | 2/8 → 2/10; 6 | 8 |

All six packet files for each of seven old linked cases (42 files total)
matched a separately compiled frozen pre-domain fixture byte for byte,
including old layout/source JSON. Original 308/301 VS/FS and the three v1/five
v2 tessellation cases also retained their independent original-emitter byte
checks. Four source-copy controls individually removed the domain, spacing,
clockwise or point-mode comparison from the production emitter; each failed
the exact metadata-rejection assertion, rather than compilation or a timeout.

The independent protected consumer in this delivery accepted both original
TCS/TES pairs through the actual v3 validator, private RX snapshot, Sv32 MMU
and retirement paths. This is four actual program-byte consumptions; only
OS/GEM primitives are modeled. A final
host-only check also rejects `--domains` without its required frozen fixture
before creating any output or entering compilation. All three lightweight
recipe/receipt/CLI tests pass and run once in `scripts/test_scripts.sh`; this
CLI-only follow-up did not repeat or replace the measured C/Mesa qualification. It neither
submits protected graphics nor executes shader instructions. New triangle/quad
hardware parents, TES coordinates/results, primitive order/counts and TF
bytes remain unexecuted. The prior isoline GPU results do not cover these
new programs or domains. No capability, factory or public permission changes.

Reproduce from this compiler checkout with the existing Mesa 22.3.6 source,
compile database, original libraries and immutable image; do not rebuild Mesa:

```sh
GPU_ROOT=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")
COMPILER_ROOT=$PWD
OUT=$(mktemp -d "$COMPILER_ROOT/.artifacts/domain-cpu-XXXXXX")
CONSUMER=$(mktemp -d "$GPU_ROOT/.artifacts/worktrees/domain-consumer-XXXXXX")
git archive 057cfaf74385293f3ac425c9536b7af577ff2537 driver scripts | tar -x -C "$CONSUMER"
mkdir "$OUT/tmp" "$OUT/consumer-artifacts" "$CONSUMER/.artifacts"
git show bcdbdfd70:userspace/gallium/opengpu_native_uniform_emit.c > "$OUT/reference-uniform.c"
git show c6eddf7d5:userspace/gallium/opengpu_native_tess_uniform_emit.c > "$OUT/reference-tess.c"
git show fb0b2e6be:userspace/tests/native_joint_resources.c > "$OUT/reference-joint.c"
IMAGE=sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72
docker run --rm --init --memory=512m --memory-swap=512m --cpus=1 \
  --network=none --read-only \
  -v "$GPU_ROOT:$GPU_ROOT:ro" -v "$OUT:$OUT:rw" \
  -v "$OUT/consumer-artifacts:$CONSUMER/.artifacts:rw" -w "$COMPILER_ROOT" \
  -e PYTHONDONTWRITEBYTECODE=1 -e ASAN_OPTIONS=detect_leaks=0 -e TMPDIR="$OUT/tmp" \
  -e OPENGPU_MESA_DIR="$GPU_ROOT/depends/mesa" \
  -e OPENGPU_MESA_BUILD="$GPU_ROOT/depends/mesa-build" \
  -e OPENGPU_DRM_INCLUDE="$GPU_ROOT/depends/aarch64-root/include" \
  -e LD_LIBRARY_PATH="$GPU_ROOT/depends/aarch64-root/lib" \
  -e OPENGPU_MESA_LINK_LIBS="-l:libz.so.1 $GPU_ROOT/depends/aarch64-root/lib/libexpat.so.1.6.7" \
  "$IMAGE" sh -ec '
    timeout --signal=TERM --kill-after=5s 180s python3 -B scripts/test_native_joint.py \
      --sanitize --negative-controls --domains --build "$1/build" \
      --legacy-source "$1/reference-uniform.c" --tess-baseline-source "$1/reference-tess.c" \
      --joint-baseline-fixture "$1/reference-joint.c"
    timeout --signal=TERM --kill-after=5s 180s python3 -B scripts/test_native_joint_domains.py \
      --sanitize --programs "$1/build" --protected-root "$2"
  ' sh "$OUT" "$CONSUMER"
```

Keep the selected source, compiler, Mesa inputs, fixed consumer and output
bytes unchanged across both steps. The producer checks actual compile/link
inputs and every MMD dependency before/after; repeat the outer source/tool
identity checks for a new qualification. End all owned groups and check open
files, symlinks and other consumers before deleting these temporary outputs
and the consumer archive. Raw packets and process records are not committed.
