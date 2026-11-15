# Private no-GS FS8/9 program and source lifetime

`opengpu_native_graphics_v2` prepares actual linked ESSL320 VS1 + no-GS FS8/9
programs and retained original-GEM sources. `opengpu_native_draw_v2` directly
encodes canonical GND1/GFS1 records from the opaque source. These APIs do not
submit, enable a public callback/capability, extend GSS1/112, or grant primitive,
sample or uniform permission. The existing production protected admission
continues to reject this new fragment input contract until separately verified.

The compiler takes explicit bounded original TGSI word counts and linked
reflection. It validates packets with the actual emitter, derives GENERIC and
COLOR spans from their semantic kinds, then re-emits both original stages to a
common varying prefix. PRIMID INPUT is not a generic varying. The saved FS
metadata comes from that emission; a draw has no metadata/color-type override.
Old VS/FS1 compiler APIs, ESSL310, OGNSF001 and ABI6/7 are unchanged.

The two opaque objects have distinct lifetimes:

- The program owns two original code GEMs, native code, metadata, physical
  context identity and exact private112 observations. That cache supplies
  capacities, not permission for ABI8/9.
- A source lease retains the program and all required original Gallium resource
  references: up to two defaults and 24 UBO views. Rebinding or destroying the
  caller's program/resources does not change an existing lease. Exact windows
  of one GEM remain distinct. A second lease captures the new binding.

The existing checked uniform planner still enforces 12 blocks per stage,
24 private mappings, 1024 default components, 16KiB blocks, alignment and exact
original allocation bounds. A lease freezes view metadata, not data or producer
fences. The protected producer must still wait, copy and independently validate
code/default/UBO data, permissions, aliases and execution lifetime. Context and
screen must outlive their objects; Gallium context access is single-threaded.

Draw preparation preserves original attribute/view/instance bounds and full
16-attribute/16-varying/4-MRT/128-thread capacity. Fragment version, types, flat
and centroid masks, depth override and exact prefix come from the leased real
compiler result. Early depth remains conservatively disabled. Sample input ABI
selection remains based on actual static SampleID/Position/sample qualification,
not HelperInvocation, MaskIn or mask output alone. PC, scratch VA and uniform
root fields are canonical zero; the entire ordinary layered-plan GS extension
is zero. Original source handles/windows are plans for protected reconstruction.
No GND/GFS word layout or old wire version is widened or retagged.

## CPU validation

Actual Mesa22.3.6/NIR/TGSI and Gallium/winsys bodies passed
ASan/UBSan Linux CPU lifecycle/record checks in immutable image `cc860b…`, with
512MiB combined memory/swap, one CPU, no network and read-only original inputs.
Mesa libraries were reused. Each compiler/link/test process has a 180-second
bound and process-group exit proof. No timeout, OOM, GPU kick, shader VM,
JVM/model or Scala regression run was involved.

| Original linked case | FS words | ABI | Varyings / MRTs | Input arrays | Prefix bytes | Extra required owners |
| --- | ---: | ---: | --- | ---: | ---: | --- |
| 26-resource primitive | 308 | 8 | 1 / 1 | 13 | 9728 | uniform |
| 26-resource primitive + SampleID | 315 | 9 | 1 / 1 | 16 | 11264 | sample, uniform |
| flat slot15, four uint MRTs | 180 | 8 | 16 / 4 | 73 | 46592 | none |
| depth + sample-mask output | 60 | 9 | 0 / 1 | 12 | 9216 | sample |
| centroid slot15 | 58 | 8 | 16 / 1 | 73 | 40448 | none |

All five additionally require independently verified primitive ownership and
require no geometry ownership. Metadata requirements do not grant those owners.
The full-resource cases used both real 1024-component defaults and all 24
16KiB views, including the last word, from one original allocation. Checks
covered positive same-GEM rebind into a second lease, old-lease immutability,
caller/program release, code/data-reference retention, create/map rollback,
short-view rollback, cross-context/legacy entry rejection and unchanged output
on record/description failure. The old linked recipe still produced exactly
308/301 words, byte-for-byte equal to the privately compiled original emitter,
before and after the new cases. Independent published schemas checked actual
record reserved/high bits and every private device-address field.

Two independent production-source mutants were rejected at precise assertions:
omitting resource retention lost the original live GEM after rebind/release;
writing ABI1 instead of saved ABI8 failed the actual record-version assertion.
These are CPU lifecycle/encoding controls, not GPU result tests.

All 30 actual translation units and 238 MMD dependencies were bound, together
with original Mesa/DRM/generated headers, eight archives, two state-tracker
objects, LD libraries, GPU/Python/schema inputs, generated sources and binaries,
before compilation/use and after execution (8101 external input identities).
The 74 GPU inputs of production translation units were all present in the
prepared graphics checker/driver closure. Staging and the original sample
builder explicitly link both new TUs; the latter now requires exactly 31
compiles plus one link. Its 13 qualifier, 19 prepared-source and eight bridge
CPU guards passed; the bridge fixtures were sanitized.

Root's independent P16 `f5d76a73f` helper consumed all five **new program-owned**
OGNSF002 outputs through real validator/private RX/MMU capture, nonzero entry,
read-only inputs/tails, failure-atomic capture and retire/reuse. Missing
primitive/sample/uniform permissions were rejected. Clang16 ASan/UBSan passed
in 3.02 seconds (226736KiB maximum sampled aggregate RSS); 686 driver/script input files and the
five original packets were unchanged. This proves CPU protection only, not a
complete GUR graph, ISA execution, protected submit, GNY2/image or product result.
All temporary build/capture/reference data were removed after both consumers
terminated. No raw evidence is committed.

## Reproduction

With `GPU` the main workspace and `SOURCE` this topic, use the immutable image
and read-only Mesa/SDK/LD environment in
[NATIVE_FRAGMENT_PRIMITIVE_COMPILER.md](NATIVE_FRAGMENT_PRIMITIVE_COMPILER.md#reproduction).
Create a new private `OUT` and read-only original emitter baseline:

```sh
mkdir "$OUT/reference"
git -C "$SOURCE" show bcdbdfd70:userspace/gallium/opengpu_native_uniform_emit.c \
  > "$OUT/reference/opengpu_native_uniform_emit.c"
# Within the same constrained Docker invocation/environment; replace its command:
python3 -B scripts/test_native_graphics_v2.py --sanitize --negative-controls \
  --legacy-source "$OUT/reference/opengpu_native_uniform_emit.c" --build "$OUT/build"
```

Before deleting its active output packets, the independent P16 helper can run:

```sh
python3 -B scripts/test_native_program_snapshot.py --sanitize \
  --owned-primitive-inputs --primitive-artifacts "$OUT"/build/*.ognsf
```

Ordinary protected ABI8/9 submission, same-host no-GS/tess replay, actual full
2CU FS execution, complete original-GEM kernel snapshots and final product
images/GNY2 remain pending. The opaque source APIs are not a replacement for
those admissions or retirement proofs.
