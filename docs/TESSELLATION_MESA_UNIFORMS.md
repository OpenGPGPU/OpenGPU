# Private Mesa TCS/TES compilation and original resources

P17 adds an independent, private ESSL 3.20 frontend and native TCS/TES
emitter. Public Gallium tessellation callbacks and feature reporting remain
closed. This source does not make a caller-provided capacity a hardware
capability, submit a tessellation command, or replace execution with a shader
interpreter.

`opengpu_tess_frontend` links VS/TCS/TES/(optional GS)/FS with actual Mesa
22.3.6 GLSL, NIR and TGSI. GPU stage tags 4/5 map explicitly to Mesa TCS/TES;
Mesa enum values are never treated as hardware stage tags. The linked VS has
NEXT_SHADER=TCS. Its separate native compiler preserves the existing VS SoA
layout; the original VS/FS compiler still requires its original FRAGMENT
successor contract.

The compiler namespace has 72 API binding points and 60 combined graphics
block references, with at most 12 blocks per stage. Actual Mesa reflection
maps each stage's declared API binding to its logical block index, then
`st_bind_tcs_ubos`/`st_bind_tes_ubos` bind slots 1..12. Slot 0 holds up to 1024
raw default components; a block view holds up to 16KiB. Original GEM handles,
offsets, sizes and references survive this remap. Different views of one GEM
remain distinct. The resulting pair of `native_uniform_stage` plans is input
to P16's checked GUR3/GUB3 mapper with its 24 private entries; no 72-entry
hardware table, raw address, or producer-authorized root is introduced here.

`opengpu_native_tess_context` supplies private create/bind/delete TCS/TES,
patch-vertex and default-level callbacks. Bound deletion retains the shader;
compilation creates independent original code GEMs. Rebinding preserves the
other stage and earlier code pairs. Defaults are copied into stage-specific
original GEMs. The source pipe outlives the adapter and every code pair;
returned original views must remain referenced through protected capture and
any eventual execution/retirement. They are not a submission authorization.

The independent emitter metadata is version 1. TCS uses checked patch
functions 20/21/22 and reconverged barriers; tessellation-level stores include
both per-patch and tessellation-level flags. Conditional uniform operands use
real vector function 7 loads, with each SIMT arm rematerializing its shared
scalar scratch. TES uses checked static geometry-input selections and output
publication functions 32/33/35/36. It has one point publication per invocation
and grants neither GS layer nor primitive-ID operations. Programs exceeding
512 words or using unsupported operations fail without changing outputs.
The linked component map includes position when present within the 64 total
components. Coupled output storage is bounded by
`outputVertices * outputComponents + patchComponents <= 2048`.

## Local validation

Actual immutable Linux CPU validation passed with 512MiB memory plus swap
combined, one CPU, no network and read-only original Mesa/SDK inputs. Mesa's
configured libraries were linked without rebuilding them. Execution-scoped
input comparison covered 8101 external and 964 GPU source inputs. Actual MMD
from 24 translation units covered 209 canonical inputs, each in the bound
source/external inputs or private generated scaffolding.

| Original ESSL case | TCS words | TES words | CPU checks |
| --- | ---: | ---: | --- |
| `uniform` | 228 | 149 | binding71, 1024 defaults and last word of a 16KiB block |
| `full` | 292 | 201 | 12 blocks per stage, 24 distinct original views of one GEM |
| `conditional` | 340 | 149 | InvocationID branch with real per-arm uniform loads |

All three passed actual Mesa compilation, private Gallium callbacks, original
GEM/code/default-byte checks, rebind/lifetime checks and seven callback/resource
rejection cases under ASan/UBSan. A separate five-stage ESSL program passed
actual reflection of 12 blocks per stage, 60 total, including binding71. That
case does not emit or execute GS machine code.

The three generated TCS/TES byte pairs also passed the production P16 strict
validator, exact RX snapshot at nonzero entry and real CPU MMU publish/retire
fixture, with exact layouts TCS `2 4 2 8 0` and TES `8 8`, physical 16x8.
Supplemental actual-Mesa parser tests cover malformed/truncated tokens,
stage/version isolation, component63/64, defaults/block limits, patch capacity,
and unchanged outputs on rejection. Their hand-built TGSI is not original
ESSL evidence. The small supplemental test is registered in the existing
Mesa-available `test_driver.py` lane.

No Scala typecheck, RTL simulation, CU shader execution, GPU result, image
publication or product timing was measured. P17 remains incomplete at that
boundary. The protected P16 typed submit/admission path still needs to consume
these original program/uniform plans. P39 must supply real VS-produced patch
inputs; CPU-precomputed patch data cannot close that chain. P40 must provide
full dynamic TES patch reads: this emitter only accepts the existing 61 static
selection transport and never truncates a larger program. Complete stage
language coverage, no-TCS evaluation, three-domain protected execution,
same-host discovery/profile qualification and public API exposure remain
pending.

## Reproduction

Set `SOURCE` to this topic checkout and `GPU` to the primary GPU workspace
containing the original configured Mesa22.3.6 and Linux DRM SDK. Use the
immutable image below; only private GPU/scaffolding units are rebuilt.

```sh
IMAGE=sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72
OUT=$(mktemp -d "$SOURCE/.artifacts/p17-cpu-XXXXXX")
docker run --rm --init --memory=512m --memory-swap=512m --cpus=1 \
  --network=none --read-only -v "$GPU:$GPU:ro" -v "$OUT:$OUT:rw" \
  -w "$SOURCE" -e PYTHONDONTWRITEBYTECODE=1 -e TMPDIR="$OUT" \
  -e OPENGPU_MESA_DIR="$GPU/depends/mesa" \
  -e OPENGPU_MESA_BUILD="$GPU/depends/mesa-build" \
  -e OPENGPU_DRM_INCLUDE="$GPU/depends/aarch64-root/include" \
  -e LD_LIBRARY_PATH="$GPU/depends/aarch64-root/lib" \
  -e OPENGPU_MESA_LINK_LIBS="-l:libz.so.1 $GPU/depends/aarch64-root/lib/libexpat.so.1.6.7" \
  -e ASAN_OPTIONS=detect_leaks=0:abort_on_error=1 \
  -e UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
  "$IMAGE" python3 -B scripts/test_tessellation_uniform_frontend.py \
    --sanitize --build "$OUT/build"
for CASE in uniform full conditional; do
  python3 -B scripts/test_tessellation_program_snapshot.py --sanitize \
    --programs "$OUT/build/programs/$CASE" \
    --tcs-layout 2 4 2 8 0 --tes-layout 8 8
done
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_tessellation_uniform_emit.py
python3 -B scripts/test_tessellation_program_snapshot.py --sanitize --negative-controls
```

The original Mesa `nir_lower_io.c` must match the existing checked uniform
lowering postimage; the builder rejects another image. Delete `OUT` after the
container and all consumers exit. Do not retain emitted programs, build
receipts, logs or source manifests as repository evidence.
