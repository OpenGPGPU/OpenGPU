# Checked TCS/TES uniform service (P15)

The optional `tessellationUniforms` constructor connects TCS stage4 and TES
stage 5 to independent checked scalar/vector uniform engines on the actual
shared-CU source/writeback ports. It defaults to false throughout the three
image domains and native sequence/job wrappers. Existing host factories,
discovery tables, GUC1 and public API capabilities do not enable it.

For the checked-uniform delivery before the retirement follow-up, protected
PR `Scala CI` passed a fresh compilation of all 759 main and 1099 test sources,
its host checks and unit lane. Bounded local validation also
passed a 128MiB, single-CPU parser check of 49 changed Scala sources and
Chisel-plugin compilation of the 27-unit core closure below. Thirteen actual
component RTL cases, one layout contract and five ABI generator checks passed.
The local incremental checks use unchanged frozen dependencies. Compilation
does not establish actual CU execution: the shared-system cancellation/reuse
checks still require a passing run after the task-retirement fix. P15 remains
unqualified until its full execution checks pass; component results do not
prove an image, protected submit, complete-host clock or FPS.

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

All three domain clients retain their task-local cancel and service-fault
latches while physical/service work drains and while the external task report
is held. They clear both latches when that report is actually consumed
(`completion.fire`). A latch left set until the next `task.fire` would hold the
child's cancel input high, preventing the very task acceptance needed to clear
it. Clearing on report retirement preserves the held result and admits the
next independent task without reset.

The shared source port can also carry sampler requests under the existing
native decoder permission. On stage 4/5 these fail with invalid status and a
failed scalar/vector writeback, without any uniform descriptor/data read.
This does not provide a sampler or image resource permission. Protected
TCS/TES code/resource provenance, writer leases and UAPI admission are separate;
they are not supplied by a trusted scope or this transport.

## Validation and reproduction

The bounded CPU checks passed: three public C/schema catalog checks (including
old/new type coexistence and all three appended layouts), 147 affected-suite
selection tests, integration/large-PR suite selection, emitter registry,
32 exact partition and four strict runner checks, and `git diff --check`.
No generated models, raw evidence or process logs are kept.

The current core sources also passed these bounded checks:

- Two execute cases retain 16 lanes/eight warps for stage 4/5, including the
  last block/default component and an invalid final lane.
- Two execute cases check scalar/vector operands, inactive lanes, unsupported
  sampler rejection without reads, held memory offers/replies, faults,
  cancellation and reuse. The sampler fixtures retain their expected rd/vd 7.
- Two load cases check all 12 blocks, all 1024 default components, upper private
  table slots, exact version/stage/mask/maps, bounds and cancel/fault drain.
- One service case checks distinct physical clients, TCS/TES memory IDs,
  held writebacks and actual response draining after modeled physical retirement.
- Six native reader cases check old/new contracts separately for all three
  domains, including reserved bits, cross-version rejection and cancellation.
- The layout contract and five `--check` generators verify the appended scopes
  and unchanged GNT1/2/3 headers/schemas against current Chisel definitions.

These 13 simulations do not instantiate a CU. The service has a modeled CU
channel peer; the native cases only read descriptors. Every compiler/Runner
process group had a 180-second ceiling, 128MiB heap/metaspace, 32MiB code cache,
one CPU and one native build job. All owned processes exited, and private
classes, RTL and model outputs were removed after checking consumers.

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

For the incremental core checks, `FROZEN_CP` is the read-only test classpath
from checkout `75899c1856ee0ee0204cbd2e5d1893d6dad11f63` and
`CHISEL_PLUGIN_JAR` is the matching 7.15.0 plugin. Compile all listed current
sources together; place private output before the frozen dependencies. The
current `NativeTypedGraphicsDescriptors` and old ABI callers are necessary:
their stale compiled method signatures cannot be reused. Use the same owned
process-group ceilings described above.

```sh
P15_CORE=(
  src/main/scala/opengpu/graphics/{NativeTypedGraphicsDescriptors,NativeUniformCapabilities,ShaderUniformResources,ShaderUniformLoadUnit,ShaderUniformExecuteUnit}.scala
  src/main/scala/opengpu/system/{TessellationUniformCuService,TessellationUniformSequenceLayout,TessellationCuIsolineSystemClient,TessellationCuTriangleSystemClient,TessellationCuQuadSystemClient,TessellationNativeSequenceReader,TessellationNativeTriangleSequenceReader,TessellationNativeQuadSequenceReader}.scala
  src/main/scala/opengpu/elaboration/{EmitTessellationUniformAbi,EmitTessellationUniformSequenceAbi,EmitTessellationNativeSequenceAbi,EmitTessellationNativeTriangleSequenceAbi,EmitTessellationNativeQuadSequenceAbi}.scala
  src/test/scala/opengpu/graphics/{WindowPrimitiveRecordsSpec,ShaderUniformLoadUnitSpec,ShaderUniformExecuteUnitSpec}.scala
  src/test/scala/opengpu/system/{TessellationUniformCuServiceSpec,TessellationUniformAbiSpec,TessellationCuIsolineSystemClientSpec,TessellationNativeSequenceReaderSpec,TessellationNativeTriangleSequenceReaderSpec,TessellationNativeQuadSequenceReaderSpec}.scala
)
JAVA_LIMITS=(-Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m
  -XX:ActiveProcessorCount=1 -XX:+UseSerialGC)
java "${JAVA_LIMITS[@]}" -Dscala.usejavacp=true -cp "$SCALA_COMPILER_CP" \
  scala.tools.nsc.Main -classpath "$FROZEN_CP" -Xplugin:"$CHISEL_PLUGIN_JAR" \
  -language:reflectiveCalls -Ymacro-annotations -d "$GPU_TMP/classes" "${P15_CORE[@]}"
P15_CP="$GPU_TMP/classes:$FROZEN_CP"
SIM_FLAGS=(-Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0
  -Dopengpu.simCache=off -Dopengpu.simWorkspace="$GPU_TMP/sim")
for filter in 'full sixteen-lane' 'distinct TCS/TES'; do
  java "${JAVA_LIMITS[@]}" "${SIM_FLAGS[@]}" -cp "$P15_CP" \
    org.scalatest.tools.Runner -oD -s opengpu.graphics.ShaderUniformExecuteUnitSpec -z "$filter"
done
java "${JAVA_LIMITS[@]}" "${SIM_FLAGS[@]}" -cp "$P15_CP" org.scalatest.tools.Runner -oD \
  -s opengpu.graphics.ShaderUniformLoadUnitSpec -z 'full TCS/TES roots'
java "${JAVA_LIMITS[@]}" "${SIM_FLAGS[@]}" -cp "$P15_CP" org.scalatest.tools.Runner -oD \
  -s opengpu.system.TessellationUniformCuServiceSpec
for domain in '' Triangle Quad; do
  java "${JAVA_LIMITS[@]}" "${SIM_FLAGS[@]}" -cp "$P15_CP" org.scalatest.tools.Runner -oD \
    -s "opengpu.system.TessellationNative${domain}SequenceReaderSpec" -z 'uniforms='
done
java "${JAVA_LIMITS[@]}" -cp "$P15_CP" org.scalatest.tools.Runner -oD \
  -s opengpu.system.TessellationUniformAbiSpec
for emitter in EmitTessellationUniformAbi EmitTessellationUniformSequenceAbi \
  EmitTessellationNativeSequenceAbi EmitTessellationNativeTriangleSequenceAbi EmitTessellationNativeQuadSequenceAbi; do
  java "${JAVA_LIMITS[@]}" -cp "$P15_CP" "opengpu.elaboration.$emitter" --check
done
# Remove GPU_TMP only after all compiler, JVM and native consumers exit.
```

The nine-unit current retirement closure passed Chisel-plugin compilation;
all six retirement RTL cases passed. The fixture instantiates each actual
SystemClient, patch context and control scheduler with a CU protocol peer.
Its six cases cover all three domains with uniforms disabled/enabled at two
lanes/two warps. Disabled cases
isolate latched cancellation; enabled cases isolate a failed physical launch
with the checked service present. Both hold the external report for six cycles,
then require the next actual task acceptance within 16 cycles and further reuse
without reset. The enabled cases do not fetch uniforms or execute an ISA.
The original isoline code fails that exact next-task oracle in both modes.
Reproduce each case separately under the same 180-second process ceiling:

```sh
P15_RETIREMENT=(
  src/main/scala/opengpu/graphics/{NativeTypedGraphicsDescriptors,ShaderUniformResources,ShaderUniformLoadUnit,ShaderUniformExecuteUnit}.scala
  src/main/scala/opengpu/system/{TessellationUniformCuService,TessellationCuIsolineSystemClient,TessellationCuTriangleSystemClient,TessellationCuQuadSystemClient}.scala
  src/test/scala/opengpu/graphics/TessellationSystemClientRetirementSpec.scala
)
java "${JAVA_LIMITS[@]}" -Dscala.usejavacp=true -cp "$SCALA_COMPILER_CP" \
  scala.tools.nsc.Main -classpath "$FROZEN_CP" -Xplugin:"$CHISEL_PLUGIN_JAR" \
  -language:reflectiveCalls -Ymacro-annotations -d "$GPU_TMP/classes" "${P15_RETIREMENT[@]}"
for domain in 0 1 2; do
  for uniforms in false true; do
    java "${JAVA_LIMITS[@]}" "${SIM_FLAGS[@]}" -cp "$P15_CP" org.scalatest.tools.Runner -oD \
      -s opengpu.graphics.TessellationSystemClientRetirementSpec \
      -z "domain=$domain uniforms=$uniforms"
  done
done
```

A fresh complete main+test compile passed in protected PR CI. The complete
shared-system suites below must also pass on the retirement-fix head; the
incremental closure and parser check do not substitute for that validation.
Keep `Scala regression` disabled: these commands are focused checks and do not
enable or dispatch that workflow.

```sh
sbt Test/compile
sbt 'testOnly opengpu.graphics.ShaderUniformLoadUnitSpec opengpu.graphics.ShaderUniformExecuteUnitSpec opengpu.system.TessellationUniformAbiSpec opengpu.system.TessellationUniformCuServiceSpec opengpu.system.TessellationNativeSequenceReaderSpec opengpu.system.TessellationNativeTriangleSequenceReaderSpec opengpu.system.TessellationNativeQuadSequenceReaderSpec opengpu.system.TessellationCuIsolineSystemClientSpec'
```

The added tests cover both stages' full block/default capacity and upper private
table slots; version/stage/mask/map/bounds rejection; scalar/vector and inactive
lane behavior; unsupported sampler rejection; held request/response cancellation,
faults and reuse; physical client/retirement ownership; unchanged old field
positions and old/new/cross-domain draw rejection. The shared-system case uses
two physical CUs at four lanes/two warps and makes TCS/TES load values affect
patch levels and evaluated output, and cancels an actual CU after accepting a
uniform read while its response is held, then reuses the same owner without
reset. Its cancellation/reuse check failed before the retirement fix; a passing
post-fix run is required. That fixture is not the target
16-lane/eight-warp execution profile.
Actual CU execution at full capacity, whole three-domain image execution under
contention, full 128-thread host admission and final factory/source qualification
remain pending; none is inferred from component results or unexecuted tests.
