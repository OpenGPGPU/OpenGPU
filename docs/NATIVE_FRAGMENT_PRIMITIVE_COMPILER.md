# Private ordinary-fragment compiler

The explicit no-GS ESSL320 entry points compile original Mesa22.3.6 FS tokens
into internal fragment ABI8 or9. They do not advertise a screen capability,
open a submit path, change GSS1/112 discovery, or grant resource permissions.
Original ESSL310 frontends, native VS/FS1 entry points, ABI6/7 raw emitter and
OGNSF001 remain separate. A program is re-emitted for its real layout; its ABI
is never changed after code generation.

## Compiler interfaces

`native_fs_frontend_compile_no_gs_v2` independently links a real fragment stage.
`graphics_uniform_compile_no_gs_v2` links real VS/FS, retaining Mesa's original
parameter reflection and state-tracker binding callbacks. Both require the
actual linked program to be ESSL320 and contain no GS. The standalone compiler
context option remains300, as required by Mesa's scaffolding; the source and
linked program language are checked as320. Existing frontend functions remain
strict310. None of these private limits are Gallium device capability reports.

The frontend copies `ir_variable.data.used` from Mesa's original attached
AST-to-HIR shader variables before lowering the separately linked NIR. This
retains static SampleID/Position use removed by optimization, without parsing
source text or retaining IR pointers. Real FS PrimitiveID arrives as a flat
INPUT semantic, not a generic varying. Layer is compiled to0 only in this
explicit no-GS language mode. See GLSL ES3.20 section7.1.5:
<https://registry.khronos.org/OpenGL/specs/es/3.2/GLSL_ES_Specification_3.20.html>.

`opengpu_native_fs_emit_v2` retains the raw backend's resource rejection.
`opengpu_native_fragment_uniform_compile_v2` extends the original checked
uniform backend; it emits the original scalar fn7 loads, tracks13 exact
required byte extents and retains GUR1/GUB1 resource semantics. It does not
replace them with a private payload uniform bank. Both accept bounded TGSI
with an explicit token word count and leave output unchanged on failure.
Each backend keeps its own instruction emitter, with common layout validation.
Original VS output/constant layouts and checked uniform entry points are
unchanged.

Both new entry points produce `opengpu_native_fragment_program_v2`. Its
metadata records requirements, not verified authority. Capacity remains128
threads, up to16 varying slots and4 raw MRTs, with512 code words. The consumer
must independently verify the applicable kernel-owned profile and original
resources. The kernel validator has its own shared instruction bound;512 is
this producer's bounded code capacity, not a newly lowered hardware limit.

Static SampleID, SamplePosition or a used sample-qualified varying select9
and require sample transport. HelperInvocation, SampleMaskIn and output Mask
alone select8. ABI8 needs primitive transport but no sample-plane permission.
ABI9 needs both. Neither grants GS layer/viewport authority. A dead static
SampleID read still selects9 even when the final TGSI reads only PrimitiveID.

The full16-varying/4-MRT128-thread input counts and prefixes are:

| ABI | inputs | PrimitiveID | SampleID/Position | prefix bytes |
| --- | ---: | ---: | --- | ---: |
|8|73|72|absent|46592|
|9|76|72|73/74/75|48128|

Flags and coverage remain input arrays4/5; PointCoord remains70/71. Helper
bool is decoded from the helper flag with TGSI boolean semantics. PrimitiveID
is not counted in the64-component generic varying mask. Flat and centroid
masks come from actual TGSI declarations; smooth/flat/centroid transport is
retained. Noperspective inputs, unsupported resources, flow or opcodes fail
explicitly. This block does not implement general sampler/image compilation.
Raw colors, depth and mask-written/sample-mask control retain the actual
ABI8/9 layout. SampleMask is truncated to four bits; the real stage intersects
it with invocation coverage. The compiler does not average sample colors.

## OGNSF002 container

All integers are little-endian u32. Magic is eight ASCII bytes `OGNSF002`,
followed by exactly42 words and then exactly `code_words` raw words. The header
is176 bytes including magic; no padding, trailer or optional fields exist.
C struct padding is never serialized. All derived lengths are validated from
bounded full-width fields before arithmetic or reading code. Read rejection
leaves the destination unchanged; reserved word41 must be0.

| word | value |
| --- | --- |
|0|container version2|
|1..4|FS ABI, input arrays, array stride, prefix bytes|
|5..8|used builtin mask, output color mask, writes depth, writes sample mask|
|9..13|requires sample owner, requires geometry owner(always0),128 threads, varying slots, color slots|
|14|code words,1..512|
|15..17|requires primitive owner(always1), requires uniform owner, static builtin mask|
|18..19|generic varying read mask, low/high32|
|20..21|flat mask, low/high32|
|22..23|centroid mask, low/high32|
|24..36|required bytes: defaults then logical UBO0..11|
|37..40|four MRT types: float0,uint1,int2|
|41|reserved0|

Builtin bits are SampleID1, SamplePosition2, MaskIn4, FragCoord8, generic
varying16, PrimitiveID64, no-GS Layer-zero128, Helper256, sample qualifier512.
Bit32, the old raw geometry-family flag, is not an ordinary permission. Unknown
bits and inconsistent static/used requirements are rejected. Defaults remain
bounded at4096 bytes/1024 components, each logical block at16384 bytes. Uniform
ownership is required exactly when emitted checked loads demand nonzero
extents. The serialized requirements cannot authorize any resource.

## Validation and remaining work

The opt-in CPU harness uses both actual Mesa frontends, both production
emitters, original libraries and checked lowering postimage. Its original
ESSL cases cover PrimitiveID/Layer, sample inputs, mask-only, helpers, dead
static sample use, varying slot15 with four MRTs, depth/mask and all12 UBOs'
last words plus1024 defaults. Raw and checked metadata must agree on identical
resource-free tokens. Token lengths, flat semantic/index, prerequisite and
capacity rejection leave outputs unchanged. Container checks reject old magic,
old ABI, high-width prefix, nonzero reserved, truncation and trailing bytes.

The source-copy negative controls remove the primitive prerequisite and shift
the real PrimitiveID load by one input array. These must respectively fail the
C rejection assertion and the independent source-formula word oracle. The word
oracle is a supplemental CPU model, not proof of CU shader or GPU execution.
Old6/7 code for seven original ESSL programs and old VS/FS1 code are compared
byte-for-byte against privately compiled original emitter sources. No historical
raw output or generated evidence is committed.

Final bounded CPU run: both frontends compiled all eight original ESSL
cases (15 frontend invocations), producing29 actual raw/checked programs.
ASan/UBSan passed, including87 supplemental word-oracle cases (128 lanes at
1/2/4 samples),14 old6/7 byte comparisons, old VS/FS1 byte comparisons,
container/language/semantic rejection and both independent source-copy
negative controls. All19 base compiler-generated dependency files covered164
actual input paths; original external inputs, source and generated/binary
identities matched before use and after execution. The immutable container
used512MiB combined memory/swap, one CPU and no network. It exited normally
and was removed. This result is CPU compiler/source validation, not execution
of these programs on a CU. No public feature or submit gate was enabled.

The common emitter object now has a real external dependency on the versioned
metadata implementation. Every actual legacy builder and staged Gallium
library explicitly links `opengpu_native_fragment_v2.c`; ordinary graphics
now has29 exact translation units. The raw compiler dependency count includes
its additional unit. Prepared checkers bind the new implementation/header,
real reflection helper and explicit Mesa IR header. Source mutation/missing
file tests reject stale closures. Dead stripping is not used to hide the new
link dependency. The final immutable Linux container actually built the
ordinary112 LIVE binary using the exact29-unit guarded recipe, then stopped
at `BUILT, execution=false` without starting the producer. It also rebuilt the
legacy raw12-unit compiler and passed all14 original ESSL programs and42
supplemental old-code cases. All239 GPU source-closure inputs, actual ordinary
MMD/link checks and external SDK/Mesa/LD identities remained unchanged; the
raw compiler's139 actual MMD inputs were checked against the same bound map.
No Mesa library or DUT was rebuilt. Local CPU tests also passed:13 graphics
qualifier guards,8 raw qualifier guards,19 prepared-source guards and8 bridge
checks (including ASan/UBSan C entry isolation and explicit metadata link).

The independent P16 candidate helper also consumed all29 original artifacts
under Clang ASan/UBSan: validator, nonzero-entry byte-exact RX snapshot, actual
CPU MMU RX/no-W publication and owner retirement passed. Removing primitive
permission rejected every packet before copying; removing sample permission
rejected ABI9; removing owned-uniform permission rejected the uniform program.
All731 bound driver/script inputs and all29 original artifact bytes remained unchanged. This checks
CPU protection and ownership only; it does not prove a complete GUR resource
graph or interpret/execute these programs on a CU.

Reproduce that separate check against the corresponding P16 helper sources:

```sh
python3 -B "$P16/scripts/test_native_program_snapshot.py" --sanitize \
  --owned-primitive-inputs --primitive-artifacts "${ACTUAL_PACKETS[@]}"
```

`ACTUAL_PACKETS` must list only the29 positive OGNSF002 files from the compiler
run, excluding deliberate mutant artifacts. No public profile/getter is opened.
Actual protected submit admission remains a separate dependency.
The existing driver rejects these versions until an independently verified
primitive-input profile is supplied. This block does not change that gate.
Program/source/draw integration must carry the emitted FS metadata and original
GEM resources; the current draw encoder still emits ABI1. Same-host no-GS/tess
replay, actual full2CU shader execution, product images and GNY2 remain pending.

## Reproduction

Use the original configured Mesa22.3.6 libraries and Linux SDK in `GPU`. Set
`SOURCE` to this topic. The private baseline contains source, not generated
shader evidence; delete it with the rest of the ended output directory.

```sh
OUT=$(mktemp -d "$SOURCE/.artifacts/fragment-v2-XXXXXX")
mkdir "$OUT/reference"
for NAME in opengpu_native_fs_emit.c opengpu_native_uniform_emit.c; do
  git -C "$SOURCE" show "bcdbdfd70:userspace/gallium/$NAME" > "$OUT/reference/$NAME"
done
docker run --rm --init --memory=512m --memory-swap=512m --cpus=1 \
  --network=none --read-only -v "$GPU:$GPU:ro" -v "$OUT:$OUT:rw" \
  -v "$OUT/reference:$OUT/reference:ro" -w "$SOURCE" \
  -e PYTHONDONTWRITEBYTECODE=1 -e TMPDIR="$OUT" \
  -e OPENGPU_MESA_DIR="$GPU/depends/mesa" \
  -e OPENGPU_MESA_BUILD="$GPU/depends/mesa-build" \
  -e OPENGPU_DRM_INCLUDE="$GPU/depends/aarch64-root/include" \
  -e LD_LIBRARY_PATH="$GPU/depends/aarch64-root/lib" \
  -e OPENGPU_MESA_LINK_LIBS="-l:libz.so.1 $GPU/depends/aarch64-root/lib/libexpat.so.1.6.7" \
  -e ASAN_OPTIONS=detect_leaks=0:abort_on_error=1 \
  -e UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
  sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  python3 -B scripts/test_native_fragment_v2.py --sanitize --negative-controls \
    --legacy-source "$OUT/reference" --build "$OUT/build"
```

The harness binds original Mesa source/generated headers, eight link archives,
one original state-tracker object, LD libraries, GPU source/header/scripts,
ESSL, actual per-TU MMD inputs and generated/binary inputs before use and after
execution. Mesa libraries are not rebuilt. Remove private objects, programs,
logs and manifests only after the container and all explicit consumers exit.
No JVM, full model or disabled Scala regression workflow is involved.
