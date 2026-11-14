# Checked TCS/TES uniform service (P15)

The optional `tessellationUniforms` constructor connects TCS stage4 and TES
stage 5 to independent checked scalar/vector uniform engines on the actual
shared-CU source/writeback ports. It defaults to false throughout the three
image domains and native sequence/job wrappers. Existing host factories,
discovery tables, GUC1 and public API capabilities do not enable it.

This delivery has passed a 128MiB, single-CPU Scala parser check of its 49
changed Scala sources. It has **not been typechecked, elaborated or simulated**
on this delivery tree. P15 remains unqualified until the pending checks
below execute. CPU catalog/selection checks do not prove a CU load, image,
protected submit, complete-host clock or FPS.

## Resource and draw contracts

GUR3 uses magic `0x47555203`, word2 version 3 and one 64-byte root; GUB3 uses
`0x47554203`, word2 version 3 and one 64-byte binding. Every reserved word and
narrow-field padding bit remains zero. The root stage at word6 is 3 bits and
must equal 4 or5 for the selected provider. GUB3 stageMask at word7 is 6 bits,
must include that provider, and may include only TCS/TES bits (`0x10`, `0x20`,
or shared `0x30`). GUR1/GUB1 and geometry GUR2/GUB2 are not accepted here.
Existing providers continue rejecting resource version 3.

Each stage retains 12 logical blocks, five-bit mappings into its private 24-entry
table, 16KiB maximum block views, 256-byte block alignment, and 1024 default
components at selector 31. Defaults remain independent of the binding table.
Application binding numbers are not encoded in these five-bit indices; the
72-binding API remap is a later integration step, outside this change.

The new exact sequence contracts are:

| Domain | Frozen draw | Uniform-capable draw magic | Header word2 |
| --- | --- | --- | --- |
| Isoline | GNT1 / version1 | `0x474e5404` (GNT4) | 4 |
| Triangle | GNT2 / version2 | `0x474e5405` (GNT5) | 5 |
| Quad | GNT3 / version 3 | `0x474e5406` (GNT6) | 6 |

All retain 512 words /2048 bytes. The complete original domain layout occupies
the original words; new scopes occupy only reserved words 496–508:

| Words | Field |
| --- | --- |
| 496–498 | context/pass/generation identity |
| 499,500 | TCS resource version/address |
| 501,502–503 | TCS privateBase/privateLimit (33bits) |
| 504,505 | TES resource version/address |
| 506,507–508 | TES privateBase/privateLimit (33bits) |

Words 509–511 remain zero. The original `uniformResources` boolean must be zero
in new records; each actual launch derives uniform permission from its own
version 3 scope. Disabled stage scopes are canonical zero. When either scope is
present, its identity must match the viewport/frame identity. The old readers
still require the original magic/version and zero in the entire new tail.
Enabled readers require only their new domain magic/version, not a union of
old and new formats. GNQ1/2/3 prefixes, 2048-byte minimum draw stride, array
bounds and descriptor extents do not change because the new fields fit the
original extent. The extension catalog references the complete frozen base
schemas in `docs/abi/tessellation-uniform-sequence.json`.

## Ownership and memory

The system client captures both roots, identity, forbidden writable prefixes
and command tag at task acceptance. It enables the stage-specific provider
for each accepted TCS or TES physical launch. TES remains stage 5 even though
its evaluation events use the geometry transport. A later launch cannot pass
an undrained uniform instruction.

One shared service selects the actual CU owner/tag and pins the instruction's
physical client for writeback. Its independent TCS/TES checked engines use
logical memory IDs 0/1. Each image domain adds exactly those two IDs after the
bin range and all 32-ID fragment-engine ranges, before power-of-two rounding.
The same enabled ID count propagates through input, translated, native-record,
job and three-domain fabrics. Queues retain offered memory payloads across
backpressure/cancellation; completion waits for accepted responses and held
writebacks to drain.

Actual tagged physical completion is buffered before forwarding to the child.
If a canceled/faulted CU retires before its service response drains, that
retirement authorizes local discard; the result is never delivered to a later
CU owner. Cancellation is latched so its later failed physical completion does
not become a new primary fault. A noncancel service error suppresses writes,
cancels the child and remains a failed task through completion.

The shared source port can also carry sampler requests under the existing
native decoder permission. On stage 4/5 these fail with invalid status and a
failed scalar/vector writeback, without any uniform descriptor/data read.
This does not provide a sampler or image resource permission. Protected
TCS/TES code/resource provenance, writer leases and UAPI admission are separate;
they are not supplied by a trusted scope or this transport.

## Validation and reproduction

The bounded CPU checks passed: three public C/schema catalog checks (including
old/new type coexistence and all three appended layouts), 146 affected-suite
selection tests, integration/large-PR suite selection, emitter registry,
32 exact partition and four strict runner checks, and `git diff --check`.
No generated models, raw evidence or process logs are kept.

```sh
python3 -B scripts/test_tessellation_uniform_abi.py
python3 -B scripts/test_changed_test_suites.py
python3 -B scripts/test_test_selection.py
python3 -B scripts/test_ci_case_partitions.py
python3 -B scripts/test_run_ci_partition.py
python3 -B scripts/check_emit_registry.py
git diff --check
```

For the syntax-only check, set `SCALA_COMPILER_CP` to the Scala 2.13.18 compiler,
library and reflect jars and `SCALA_LIBRARY_JAR` to that same library jar. Use a
private managed temporary directory and remove it after the process exits:

```sh
mkdir -p "$GPU_TMP/classes"
java -Xmx128m -XX:ActiveProcessorCount=1 -Djava.io.tmpdir="$GPU_TMP" \
  -cp "$SCALA_COMPILER_CP" scala.tools.nsc.Main \
  -classpath "$SCALA_LIBRARY_JAR" -Ystop-after:parser -d "$GPU_TMP/classes" \
  $(git diff --name-only 4f78958bf29aa2faa8a6735e72f04c19c9fb8ebb HEAD -- '*.scala')
```

Once the resource window permits, a fresh full main+test compile must precede
these checks; changed constructor signatures must not be tested against stale
callers. Commands below are **pending**, not recorded passes:

```sh
sbt Test/compile
sbt 'runMain opengpu.elaboration.EmitTessellationUniformAbi --check'
sbt 'runMain opengpu.elaboration.EmitTessellationUniformSequenceAbi --check'
sbt 'runMain opengpu.elaboration.EmitTessellationNativeSequenceAbi --check'
sbt 'runMain opengpu.elaboration.EmitTessellationNativeTriangleSequenceAbi --check'
sbt 'runMain opengpu.elaboration.EmitTessellationNativeQuadSequenceAbi --check'
sbt 'testOnly opengpu.graphics.ShaderUniformLoadUnitSpec opengpu.graphics.ShaderUniformExecuteUnitSpec opengpu.system.TessellationUniformAbiSpec opengpu.system.TessellationUniformCuServiceSpec opengpu.system.TessellationNativeSequenceReaderSpec opengpu.system.TessellationNativeTriangleSequenceReaderSpec opengpu.system.TessellationNativeQuadSequenceReaderSpec opengpu.system.TessellationCuIsolineSystemClientSpec'
```

The added tests cover both stages' full block/default capacity and upper private
table slots; version/stage/mask/map/bounds rejection; scalar/vector and inactive
lane behavior; unsupported sampler rejection; held request/response cancellation,
faults and reuse; physical client/retirement ownership; unchanged old field
positions and old/new/cross-domain draw rejection. The actual shared-system case
uses two physical CUs and makes TCS/TES scalar and vector load values affect
patch levels and evaluated output, and cancels an actual CU after accepting a
uniform read while its response is held, then reuses the same owner without
reset. The isolated execute cases retain 16 lanes
and 8 warps. Whole three-domain image execution under contention, full 128-thread
host admission and final factory/source qualification remain pending; none is
inferred from these test sources or the CPU checks.
