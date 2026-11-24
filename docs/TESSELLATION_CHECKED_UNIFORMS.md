# Checked TCS/TES uniform service (P15)

The optional `tessellationUniforms` constructor connects TCS stage4 and TES
stage 5 to independent checked scalar/vector uniform engines on the actual
shared-CU source/writeback ports. It defaults to false throughout the three
image domains and native sequence/job wrappers. The AXI host also accepts the
explicit option and uses it when allocating the three-domain transaction range.
The existing factories and public discovery contracts retain their defaults.

For the checked-uniform delivery before the retirement follow-up, protected
PR `Scala CI` passed a fresh compilation of all 759 main and 1099 test sources,
its host checks and unit lane. Bounded local validation also
passed a 128MiB, single-CPU parser check of 49 changed Scala sources and
Chisel-plugin compilation of the 27-unit core closure below. Thirteen actual
component RTL cases, one layout contract and five ABI generator checks passed.
The local incremental checks use unchanged frozen dependencies. See the
FlashSim checks below for physical CU execution after the task-retirement fix.
The complete three-domain execution and product checks remain separate
requirements.

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
Set up the FlashSim and CIRCT environment from the physical CU checks below
and set `PYTHON` to the Python executable used by FlashSim.

```sh
P15_CORE=(
  src/main/scala/opengpu/graphics/host/NativeTypedGraphicsDescriptors.scala
  src/main/scala/opengpu/graphics/shader/{NativeUniformCapabilities,ShaderUniformResources,ShaderUniformLoadUnit,ShaderUniformExecuteUnit}.scala
  src/main/scala/opengpu/system/tessellation/{TessellationUniformCuService,TessellationUniformSequenceLayout,TessellationCuIsolineSystemClient,TessellationCuTriangleSystemClient,TessellationCuQuadSystemClient,TessellationNativeSequenceReader,TessellationNativeTriangleSequenceReader,TessellationNativeQuadSequenceReader}.scala
  src/main/scala/opengpu/elaboration/{EmitTessellationUniformAbi,EmitTessellationUniformSequenceAbi,EmitTessellationNativeSequenceAbi,EmitTessellationNativeTriangleSequenceAbi,EmitTessellationNativeQuadSequenceAbi}.scala
  src/test/scala/opengpu/graphics/{WindowPrimitiveRecordsSpec,ShaderUniformLoadUnitSpec,ShaderUniformExecuteUnitSpec}.scala
  src/test/scala/opengpu/system/{TessellationUniformCuServiceSpec,TessellationUniformAbiSpec,TessellationCuIsolineSystemClientSpec,TessellationNativeSequenceReaderSpec,TessellationNativeTriangleSequenceReaderSpec,TessellationNativeQuadSequenceReaderSpec}.scala
  src/test/scala/opengpu/testutil/{GpuSim,FlashSimBackend}.scala
)
JAVA_LIMITS=(-Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m
  -XX:ActiveProcessorCount=1 -XX:+UseSerialGC)
java "${JAVA_LIMITS[@]}" -Dscala.usejavacp=true -cp "$SCALA_COMPILER_CP" \
  scala.tools.nsc.Main -classpath "$FROZEN_CP" -Xplugin:"$CHISEL_PLUGIN_JAR" \
  -language:reflectiveCalls -Ymacro-annotations -d "$GPU_TMP/classes" "${P15_CORE[@]}"
P15_CP="$GPU_TMP/classes:$FROZEN_CP"
SIM_FLAGS=(-Dopengpu.simBackend=flashsim -Dopengpu.flashsimPython="$PYTHON"
  -Dopengpu.simJobs=1
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
  src/main/scala/opengpu/graphics/host/NativeTypedGraphicsDescriptors.scala
  src/main/scala/opengpu/graphics/shader/{ShaderUniformResources,ShaderUniformLoadUnit,ShaderUniformExecuteUnit}.scala
  src/main/scala/opengpu/system/tessellation/{TessellationUniformCuService,TessellationCuIsolineSystemClient,TessellationCuTriangleSystemClient,TessellationCuQuadSystemClient}.scala
  src/test/scala/opengpu/graphics/TessellationSystemClientRetirementSpec.scala
  src/test/scala/opengpu/testutil/{GpuSim,FlashSimBackend}.scala
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
sbt -Dopengpu.simBackend=flashsim -Dopengpu.flashsimPython="$PYTHON" \
  -Dopengpu.simJobs=1 \
  'testOnly opengpu.graphics.ShaderUniformLoadUnitSpec opengpu.graphics.ShaderUniformExecuteUnitSpec opengpu.system.TessellationUniformAbiSpec opengpu.system.TessellationUniformCuServiceSpec opengpu.system.TessellationNativeSequenceReaderSpec opengpu.system.TessellationNativeTriangleSequenceReaderSpec opengpu.system.TessellationNativeQuadSequenceReaderSpec opengpu.system.TessellationCuIsolineSystemClientSpec'
```

The added tests cover both stages' full block/default capacity and upper private
table slots; version/stage/mask/map/bounds rejection; scalar/vector and inactive
lane behavior; unsupported sampler rejection; held request/response cancellation,
faults and reuse; physical client/retirement ownership; unchanged old field
positions and old/new/cross-domain draw rejection. The shared-system case uses
two physical CUs at four lanes/two warps and makes TCS/TES load values affect
patch levels and evaluated output, and cancels an actual CU after accepting a
uniform read while its response is held, then reuses the same owner without
reset. Its cancellation/reuse check exposed the task-retirement bug.

### FlashSim physical CU checks

The isoline shared-system fixture passed with two physical 16-lane, eight-warp
CUs, an eight-set/two-way L2 fixture and a 32-control-vertex patch. The actual TCS and TES programs each used
scalar default selector 31 at component 1023, vector block 11 at byte 16380
of a 16 KiB binding and vector block 0 at private table entry 23. Both stages'
root, binding and data requests used their separate transaction IDs. The loaded
values affected the patch level and both evaluated vertices.

The same DUT covered seven healthy submissions, an accepted TES read whose
response was held during cancellation, four corrupt TES descriptors and a TES
data fault. Every error returned a stable completion under backpressure and
the following submission succeeded with the same command ID.

A system reset then cleared an active task after its TES root read was
accepted and before the response was delivered. Both memory peers reset their
transport state and kept their backing bytes. The idle interval after reset
produced no old completion, vertex or uniform request. The same command ID
then issued a fresh TES root read and produced the expected vertices. The run
passed eight healthy submissions, one cancellation and five faults in 290.7
seconds, with 2.51 GiB peak aggregate memory.

The separate AXI host elaboration check found the three domain services and
both physical CUs with the option enabled. Full product limits and protected
admission remain pending.

The reset run used FlashSim `39c920f` and the assertion-enabled CIRCT build
`80c4ed9b9`. The test and its local dependencies were rebuilt against the
existing classpath from OpenGPU `8a1919172`. On macOS, the library path setting
makes the compiler overlays visible through the system Make launcher.

```sh
export FLASHSIM_DIR="$FLASH_SIM_CHECKOUT"
export CIRCT_VERILOG="$CIRCT_TOOL_DIRECTORY/circt-verilog"
export FLASHSIM_CIRCT_LIBRARY_PATH="$CIRCT_TOOL_DIRECTORY"
export FLASHSIM_CIRCT_THREADS=2 FLASHSIM_GC_POLICY=defer
export FLASHSIM_CIRCT_LARGEST_FIRST=1
export FLASHSIM_PREFLATTEN_WIRES=1 FLASHSIM_COMMIT_PARTS=16
sbt -Dopengpu.simBackend=flashsim -Dopengpu.flashsimPython="$PYTHON" \
  -Dopengpu.simJobs=1 -Dopengpu.flashsimShards=32 -Dopengpu.tessellationUniformFullCu=1 \
  'testOnly opengpu.system.TessellationCuIsolineSystemClientSpec -- -z service=true'
```

Nine FlashSim reader tests passed across isoline, triangle and quad. They
checked the original and extended GNT contracts, cancellation drain, and
each domain's patch sequencer with a GPU-supplied record.

```sh
sbt -Dopengpu.simBackend=flashsim -Dopengpu.flashsimPython="$PYTHON" \
  'testOnly opengpu.system.TessellationNativeSequenceReaderSpec opengpu.system.TessellationNativeTriangleSequenceReaderSpec opengpu.system.TessellationNativeQuadSequenceReaderSpec'
```

### Complete checked-uniform owner qualification

The `two-cu-vga60-owner-v2-tessellation-uniform-stage` factory selects the full
640×480 dual-CU float owner and enables checked TCS/TES uniforms on all three
domains. The preparation guard requires the dedicated factory identity, the
enabled service and the exact 112-word image-float MMIO contract. The
source-bound executor builds the original float native-sample probe against
this factory's RTL and verifies its 112 discovery words and five auxiliary
registers.

The preparation command serializes FIRRTL directly from the Chisel circuit.
The JVM exits before the same Chisel-selected firtool lowers the RTL. The
factory retains the original lowering options and explicitly supplies its SRAM
model source. Both flows reported the SRAM annotation warning and produced
byte-identical RTL and file lists in a comparison fixture with both bound and
inline assertions.

The complete staged preparation passed in 412.6 seconds with 6.25 GiB peak
process-group memory, including a fresh main and test Scala compile and
firtool 1.158.0. The original combined attempt reached 10.42 GiB when its
10 GiB guard stopped it. The completed output sealed 2,331 files and matched
the 1,877 RTL and bind inputs enumerated by FlashSim. The emitted hierarchy
contains both physical CUs and all three domain uniform services. The temporary
FIRRTL was removed after lowering.

The assertion-enabled CIRCT import of this factory passed with FlashSim
`23333d0` and CIRCT `80c4ed9b9`. With four CIRCT workers, the guarded import
took 192.3 seconds with 6.70 GiB peak process-group memory. Reusing the
verified MLIR took 3.4 seconds. The six-worker trial reached the 7 GiB
guard, so the retained import uses four workers.

The native executor accepts an outlined model after verifying every derived
C++ file against the original assertion-enabled generation. The C++ fixture
linked the outlined tables, ran its bound assertions, and rejected a reordered
table even with a refreshed file hash. The 46 owner preparation, executor and
native builder checks passed.

With FlashSim `23333d0`, the full assertion-enabled model generated in
5,210 seconds and peaked at 24.41 GiB of charged memory. The initial native
build took 6,119 seconds and peaked at 15.86 GiB. At source `cd36805a8`, all
1,962 factory input hashes matched. The rebased native executor reused all
285 compiled model objects and linked and verified in 288 seconds with
7.36 GiB peak memory.

The rebased executor passed the actual MMIO probe. It verified 112 discovery
words, five auxiliary registers and idle state after one reset without
external memory traffic.

With the pinned SRAM submodule initialized and the FlashSim/CIRCT environment
above, put Espresso on `PATH` for the full decoder and set `OWNER` to a new
absolute path inside this checkout's `.artifacts`.

```sh
export FLASHSIM_CIRCT_THREADS=4
"$PYTHON" -B scripts/run_owner_bounded.py --memory-mib 7168 --timeout-seconds 1800 -- \
  "$PYTHON" -B scripts/prepare_native_owner_flashsim.py --stage prepare \
  --tessellation-uniforms --output "$OWNER"
"$PYTHON" -B scripts/prepare_native_owner_flashsim.py --stage generate \
  --flashsim-dir "$FLASHSIM_DIR" --output "$OWNER" --shards 64
(
  cd "$FLASHSIM_DIR"
  "$PYTHON" -B -m flashsim.reshard create --source "$OWNER/flashsim-dut" \
    --output "$OWNER/flashsim-outlined" --max-mib 48 --outline-invalidation-mib 24
)
"$PYTHON" -B scripts/prepare_owner_executor.py --stage build --backend flashsim \
  --kind native-sample --owner-output "$OWNER" --flashsim-dir "$FLASHSIM_DIR" \
  --reshard-dir "$OWNER/flashsim-outlined" --reshard-tool "$FLASHSIM_DIR" \
  --shards 64 --jobs 2 --precompiled-header --conservative-alias
"$PYTHON" -B scripts/prepare_owner_executor.py --stage probe --backend flashsim \
  --kind native-sample --qualification "$OWNER/executors/native-sample/qualification.json"
```

### Full-owner feedback replay

At source `70a16f300`, freshly preparing the dual-CU owner took 222 seconds
with 6.31 GiB peak memory. All 2,331 RTL and bind files and the factory
manifest matched the qualified model. All 285 compiled objects were reused.
The executor linked and verified in 287 seconds with 7.35 GiB peak memory.
The replay driver linked against the same objects in 106 seconds.

Each case used one reset and one live feedback capture through isoline,
triangle, quad and a second isoline draw. Completion arrived after external
drain, and the result and IRQ remained stable while held. The replay checked 22 feedback words, three
counters, the untouched input lines and the buffer guards.

| Case | Commands | Service cycles | Resource coverage |
| --- | ---: | ---: | --- |
| `distinct` | 6 | 18,539 | Separate executable addresses and both stages' default selector 31 |
| `invalidate` | 10 | 19,360 | Four code invalidations before reusing the control program address |
| `capacity` | 6 | 33,065 | TCS and TES read block 0, block 11 and the default view at the 16 KiB and 1024-component endpoints |

Generate the four-engine fixture from the Scala descriptor sources before
running the qualified owner. Set `FIXTURE` to a new absolute path under
`.artifacts` and use a Python executable path accepted by the FlashSim
Scala adapter. The descriptor fixture uses FlashSim with the largest-first
CIRCT option disabled. The executor verification uses the environment
recorded by the complete model.

```sh
FLASHSIM_CIRCT_LARGEST_FIRST=0 sbt -Dopengpu.simBackend=flashsim \
  -Dopengpu.flashsimPython="$PYTHON" -Dopengpu.simJobs=1 -Dopengpu.simCache=off \
  "Test / runMain opengpu.graphics.tessellation.TessellationUniformOwnerFixture \"$FIXTURE\""
"$PYTHON" -B scripts/qualify_tessellation_uniform_owner.py \
  --qualification "$OWNER/executors/native-sample/qualification.json" --fixture "$FIXTURE"
```

The fixture uses small draw descriptors on the complete 640×480 owner.
Full-owner fault and cancellation recovery across all three domains, product
frequency and FPS remain open.

### Three-domain AXI host execution

Both FlashSim cases passed with the adapter's compiler flags on two physical
16-lane, eight-warp CUs, an eight-set/two-way L2 fixture and a 17×9 frame. Each
case used one DUT, one reset and one live transform feedback capture across
isoline, triangle, quad and a second isoline draw. Every draw observed physical
AXI reads for both stages' uniform root, binding and data lines. The cases used
distinct program addresses or explicit invalidation before reusing the same
control program address. Both matched all 22 feedback words, byte and element
counts, and buffer guards.

Use FlashSim `db65773` and the assertion-enabled CIRCT build `80c4ed9b9`
with the environment above. Set `FLASHSIM_CIRCT_LARGEST_FIRST=1` for that
importer. The qualification reused one executable after comparing the ordered
RTL inputs, bind sources, testbench, driver, macros and compiler identities.

```sh
sbt -Dopengpu.simBackend=flashsim -Dopengpu.flashsimPython="$PYTHON" \
  -Dopengpu.simJobs=2 -Dopengpu.flashsimShards=32 -Dopengpu.tessellationUniformFullCu=1 \
  'testOnly opengpu.graphics.tessellation.TessellationNativeThreeDomainFeedbackHostAxiSpec'
```

The svsim adapter schedules the largest C++ sources first. Apple Clang 16
compiled the same `dut_10.cpp` at `-O1` in about 231 seconds with default
alias analysis and 89.3 seconds with `-fno-strict-aliasing`. Adding
`-fstrict-flex-arrays=1` to the conservative alias flags reduced the same
31 MiB `dut_commit_part014.cpp` from 211.9 to 35.1 seconds at `-O1`. The
two commit objects were byte-identical. Compare the flags using the same
generated header and shards from a single elaboration.

```sh
time c++ -std=c++17 -w -I "$DUT_GEN" -I "$PWD/scripts" -fbracket-depth=4096 \
  -O1 -fno-strict-aliasing -c "$DUT_GEN/dut_commit_part014.cpp" -o "$DUT_GEN/baseline.o"
time c++ -std=c++17 -w -I "$DUT_GEN" -I "$PWD/scripts" -fbracket-depth=4096 \
  -O1 -fno-strict-aliasing -fstrict-flex-arrays=1 \
  -c "$DUT_GEN/dut_commit_part014.cpp" -o "$DUT_GEN/fixed-arrays.o"
cmp "$DUT_GEN/baseline.o" "$DUT_GEN/fixed-arrays.o"
```
