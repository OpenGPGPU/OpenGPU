# Shared tessellation image engines

The three-domain native tessellation owner shares one image-engine bank between
isoline, triangle and quad jobs. Its domain router already serializes jobs.
The four-engine product configuration therefore uses four tessellation image
engines instead of twelve. Ordinary native graphics retains its separate four
engines.

Each domain retains its job reader, sequence and input pipelines, bin frontend,
directory, memory translation and feedback state. The product configuration
retains two CUs, 16 lanes, eight warps, four engines per active domain, 65,536
directory entries per domain, the 2048-axis API extent and 512 KiB payload pool.
Sharing adds no arbitration or buffering between consecutive quads of a job.
The classified job acquires the bank one cycle before child admission.

One retained domain selects the complete engine interface: begin, primitive,
end, pixel and completion channels, all memory IDs, shader tasks, payload
control and data responses, pins, cancellation and performance events. The
owner survives cancellation and faults. It releases only when the public job
completion retires after image, memory, shader, payload and feedback traffic
drain. Backend state is not reset between domains; each new tile begin
invalidates its state-reader cache. Physical-bank performance accounting keeps
idle/collect cycles between jobs.

Feedback transfer drain is distinct from its sticky poisoned status. A held
switch reply or pending memory response prevents retirement; a consumed failed
transfer can drain while further work remains prohibited until feedback reset.
Cancellation before feedback admission consumes the existing classified offer
and returns status 4 without offering a render child or feedback switch.

Twenty-three local interface, ownership, structural, feedback and host-profile
checks pass. The structural case retains all three domain frontends and
feedback while counting exactly four physical image engines. The new
96-word protected-owner factory also converts with its enabled preflight and
advanced execution connections. Both existing
two-CU host integration oracles also pass: native-sample quad/triangle/isoline
images, faults and no-reset recovery; and serial-storage three-domain live
transform feedback with visibility/primitive queries and final raster output.
The native-sample run uses the unchanged product 32 KiB storage and 64 sample
owners. It additionally holds an accepted uniform-memory reply during RESET,
checks protected SATP writes until drain, and renders a healthy cross-domain
quad afterward. These host fixtures use two image engines and 4×4 frames;
they do not exercise the final protected-owner profile's enabled advanced
blend or shader-image service combination.

These changes alter RTL and require fresh model and physical qualification.
They do not establish a clock, area reduction or 640×480 at 60 FPS. Source and
mapped SRAM counts must be obtained from the new hierarchy; previous emitted
profiles retain their original source identity and qualification limits.

Reproduce the interface, owner, structural and feedback control checks with a
single JVM and serial suites:

```sh
JAVA_TOOL_OPTIONS='-Xmx3G -XX:ActiveProcessorCount=2' MAKEFLAGS=-j1 \
  sbt -batch -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationImageBankOwnerSpec opengpu.system.TessellationImageBankMuxSpec opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec opengpu.system.TessellationThreeDomainFeedbackAdmissionSpec opengpu.graphics.TransformFeedbackThreeDomainTransferSpec opengpu.graphics.tessellation.TessellationThreeDomainFeedbackBridgeSpec opengpu.system.NativeAlignmentHostProfileSpec'
```

Run the three protected-owner profile checks in a separate JVM:

```sh
JAVA_TOOL_OPTIONS='-Xmx6G -XX:ActiveProcessorCount=2' \
  sbt -batch 'testOnly opengpu.system.NativeOwnerHostProfileSpec'
```

The combined 3 GiB process exhausts its heap in that profile's Chisel
conversion. The fresh 6 GiB process passes without changing the host, storage
or owner capacities. This is an elaboration memory limit, not a product
configuration limit.

The validated two-CU integration oracles are
`TessellationNativeQuadHostAxiImageSpec`
and `TessellationThreeDomainFeedbackQueryHostAxiSpec`. Run them serially with the
same simulation options. Add `-Dopengpu.tessellationNativeStorage=1` when running
`TessellationNativeQuadHostAxiImageSpec` to select the unchanged product
32 KiB parallel/native-sample storage and its 64 sample owners. The default
serial-storage oracle and the dedicated owned-resource modes remain available.
`TessellationNativeThreeDomainFeedbackHostAxiSpec` remains an additional
integration oracle; it was not rerun for this change.
The small fixtures do not establish four-engine parallel throughput or a
complete product frame. Point `java.io.tmpdir` and `TMPDIR` at a unique short
directory directly under the main repository's `.artifacts/`. On macOS its
absolute path must be at most 52 bytes to leave room for sbt's socket suffix;
do not use a long worktree path. Leave
`opengpu.rootedHostEvidence` unset. Remove temporary models, fixtures, logs and
caches as soon as their validation use ends.

## Opt-in fragment sample/image route

The 112-word sample-stage factory explicitly enables
`tessellationFragmentImages` and `tessellationSampleShading`. Both default to
false, preserving the 96-word and 104-word factories. The host passes them
through all three domain owners into the shared physical replay bank. Image
publications are captured with the fragment task through the domain and host
routers; control/evaluation tasks explicitly publish an empty image binding.
The 112 discovery words and full product capacities are unchanged. The sample
manifest now requires both flags; source/manifest guards reject models prepared
before this routing change even when their discovery words match.

Tessellation bin records currently publish `geometry.valid=false`. The new
route accepts ordinary sample ABI6. ABI7 still requires a real GS primitive/layer
owner and is rejected on this route; zero metadata does not authorize it.
The bounded component selector starts at a trusted internal patch descriptor,
not the protected native job envelope. It is designed to execute isoline
control/evaluation and ABI6 fragment code on two 16-lane/eight-warp CUs with
16 varying slots, four raw MRTs, and 1x/2x/4x output in a 4x4 frame. Expected
coverage comes from an explicit TES line and the independent integer line
reference. Assertions cover every MRT/sample and allocation guard byte,
including clear pixels and an interior witness for each sample. The fixture
uses two image engines, eight directory entries and three payload owners; it
does not instantiate the complete three-domain/host wrapper. Its shader has no
image opcode: publication/root and task routing checks do not establish image
load/store execution.

Fresh JDK 21 `Test/compile` passed for all 755 main and 1095 test sources with
a 4 GiB heap in 204 seconds. A separate 2 GiB serial run passed 15 tests: five
pure owner-profile checks, three host-router checks, one bank-owner check,
four bank-mux variants and two GFS/image-reader cases. These exercise legacy
isolation, captured image identity, backpressure, cancellation, drain, reuse,
ABI6 admission and ABI7 rejection without GS metadata. The 25 Python
preparation/executor/product guard tests also pass.

The explicit Runner passed all three real-CU builtin-only sample cases: 1x in
9747, 2x in 11227 and 4x in 14123 fixture cycles. Each completed two patches,
two primitives and one tile with the independent full four-MRT/sample/guard
oracle. These are component simulation cycles, not clock or FPS measurements.
The separate image-op case failed its RW address oracle as described below;
image-op qualification and the complete 112-word protected host remain open.
Reproduce the CPU checks with:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p test_prepare_native_sample_owner.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p test_prepare_native_owner_flashsim.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p test_prepare_owner_executor.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p test_qualify_native_sample_layered_product.py
```

Use JDK 21 and one JVM at a time. Create a short managed temporary directory
under the main repository (not the long worktree path), and retain it until all
JVM, Verilator and native build processes exit:

```sh
GPU_MAIN=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")
GPU_TMP=$(mktemp -d "$GPU_MAIN/.artifacts/t.XXXXXX")
export TMPDIR="$GPU_TMP"
sbt -batch -J-Xms256m -J-Xmx4G -J-XX:ActiveProcessorCount=2 \
  -J-XX:MaxMetaspaceSize=768m -J-XX:ReservedCodeCacheSize=256m \
  -J-XX:MaxDirectMemorySize=256m -J-Xss4m -Djava.io.tmpdir="$GPU_TMP" \
  'Test/compile' 'export Test / fullClasspath'
sbt -batch -J-Xms256m -J-Xmx2G -J-XX:ActiveProcessorCount=2 \
  -Djava.io.tmpdir="$GPU_TMP" -Dopengpu.simWorkspace="$GPU_TMP/sim" \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simCache=off \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.NativeOwnerHostProfileSpec -- -z compose' \
  'testOnly opengpu.system.NativeOwnerHostProfileSpec -- -z "retain the complete"' \
  'testOnly opengpu.system.NativeOwnerHostProfileSpec -- -z "reject protected"' \
  'testOnly opengpu.system.TessellationHostShaderRouterSpec opengpu.system.TessellationImageBankOwnerSpec opengpu.system.TessellationImageBankMuxSpec opengpu.graphics.TypedFragmentImageStateReaderSpec'
```

The next command runs the real-CU component selector, not a measured resource
bound. Run it only in a separately checked memory window. Its two complete CU
interfaces and real native-sample backend make it substantially larger than
the router/reader tests. Serial native compilation and heap release avoid
concurrent front-end/native build peaks; they do not guarantee peak memory.
The three sample modes currently create three sequential models. Both explicit
component suites use `@DoNotDiscover`; sbt `testOnly` does not select them from
`definedTests`. Two attempted `testOnly` commands reported zero tests and zero
suites despite exit success. **Zero selected tests are not a passing execution
result.** Export the compiled Test classpath, then name the suite explicitly in
ScalaTest Runner. Set `JAVA_HOME` to the same JDK 21 used to compile:

```sh
GPU_TEST_CP=$(cat target/streams/test/fullClasspath/_global/streams/export)
test -n "$GPU_TEST_CP"
MAKEFLAGS=-j1 "$JAVA_HOME/bin/java" -Xms256m -Xmx4G -XX:ActiveProcessorCount=2 \
  -XX:MaxMetaspaceSize=768m -XX:ReservedCodeCacheSize=256m \
  -XX:MaxDirectMemorySize=256m -Xss4m -Djava.io.tmpdir="$GPU_TMP" \
  -Dopengpu.simWorkspace="$GPU_TMP/cu-sim" -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simCache=off \
  -Dopengpu.tessellationSampleImageQualification=1 \
  -cp "$GPU_TEST_CP" org.scalatest.tools.Runner -oD \
  -s opengpu.graphics.tessellation.TessellationSampleImageSharedSystemSpec \
  -z sample-images-0
```

All eight `TessellationNativeThreeDomainJobImageElaborationSpec` cases pass:
payload, feedback and sample/image routing are independently disabled/enabled,
with one or four physical engines. The three native domain frontends retain
exactly one shared image bank in every case. These bounded four-lane/two-warp
structural checks do not instantiate the CUs or execute shaders. Reproduce with
the same JDK and short temporary-directory setup:

```sh
sbt -batch -J-Xms256m -J-Xmx4G -J-XX:ActiveProcessorCount=2 \
  -J-XX:MaxMetaspaceSize=768m -J-XX:ReservedCodeCacheSize=256m \
  -J-XX:MaxDirectMemorySize=256m -J-Xss4m -Djava.io.tmpdir="$GPU_TMP" \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationNativeThreeDomainJobImageElaborationSpec'
```

After verifying that no process or other consumer needs the temporary outputs,
remove `"$GPU_TMP"` and the ended simulation workspaces. No selector inherits an
earlier factory's model results.

## Isolated image-op component selector

`TessellationSampleImageOpsSharedSystemSpec` adds actual CUSTOM-1 image
instructions behind `sampleImageOpcodes=false` by default. The original
`TessellationSampleImageSharedSystemSpec` keeps its builtin-only shader and
remains independently selectable. Incremental `Test/compile` passed for the
additional sources. The sbt selector attempts ran zero tests; neither real-CU
suite executed in those attempts. The later explicit Runner passed the three
builtin-only baseline cases. Its image-op 1x case reached the final RW backing
oracle after the full MRT, immutable RO/publication/table and delayed-ACK
checks, then failed: the independent diamond coverage includes pixel `(1,1)`,
which the original coordinate bit mapping aliased with pixel `(3,1)`.
The corrected test uses real `vfcvt.rtz.xu.f.v` on `FragCoord.xy` and maps
`(x,y,sample)` to RW texel `(floor(x),4*sample+floor(y))`. The full independent
coverage set is retained. The corrected image-op test has not been compiled
or rerun; no image-op qualification is claimed.

The image-op program retains ABI6's 128-thread capacity, 512-byte array stride,
16 varying slots and four raw MRTs. It loads a nonzero per-sample RGBA32UI tuple
from GIU1 unit 0 into MRT3, stores the original sample builtins through unit 1,
and loads that store result back into MRT2. MRT0/1 retain the original builtin
outputs. Each program remains within 512 instructions. GIR1 at `0x8800` binds
the real GIU1 table at `0x9000`; fragment-only descriptors have format 26,
RO/RW permission respectively, and explicit embedded 2D level views. The RO
and RW allocations are separate 4 KiB regions at `0x700000` and `0x710000`.
Both texel bases have byte offset 63 to exercise split-line accesses.

Expected sample coverage still comes from the fixed TES line and independent
integer line reference. The oracle checks every MRT pixel/sample, every RO,
GIR1/table and RW allocation byte, helper-only rows, row padding and all MRT
guards. It delays an accepted RW store acknowledgement and requires the image
lease and completion owner to remain live until release. Final memory replies
must drain. The fixture uses bare physical memory, handcrafted internal ISA,
two configured CUs and one outstanding fragment task window. It does not prove
that both CUs execute image opcodes, parallel throughput, original ESSL, Sv32
or the complete protected 112-word host.

`TessellationSampleImageRecipeSpec` is a data-only regression for the unsigned
RTZ instruction encoding, the independent line coverage (including the 1x
left-edge pixel), and disjoint texel/byte addresses across every 4x4 pixel,
helper and 1x/2x/4x sample. Reproduce after compiling the updated test sources:

```sh
sbt -batch -J-Xms256m -J-Xmx4G -J-XX:ActiveProcessorCount=2 \
  'testOnly opengpu.graphics.tessellation.TessellationSampleImageRecipeSpec'
```

The new Scala recipe regression is unexecuted. A lightweight Python check of
the independent fixed-line coverage and all 112 pixel/sample coordinate and
address mappings passes; it does not execute the ISA or replace the RTL rerun.

After a fresh compile and a separately checked resource window, use the same
JDK, short `GPU_TMP` and serial build settings above:

```sh
MAKEFLAGS=-j1 "$JAVA_HOME/bin/java" -Xms256m -Xmx4G -XX:ActiveProcessorCount=2 \
  -XX:MaxMetaspaceSize=768m -XX:ReservedCodeCacheSize=256m \
  -XX:MaxDirectMemorySize=256m -Xss4m -Djava.io.tmpdir="$GPU_TMP" \
  -Dopengpu.simWorkspace="$GPU_TMP/image-ops" -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simCache=off \
  -Dopengpu.tessellationSampleImageOpsQualification=1 \
  -cp "$GPU_TEST_CP" org.scalatest.tools.Runner -oD \
  -s opengpu.graphics.tessellation.TessellationSampleImageOpsSharedSystemSpec \
  -z sample-images-0
```

Each command above selects exactly one 1x test. Remove `-z sample-images-0` to
select all three 1x/2x/4x cases in that suite. To run both 1x tests in one fresh
JVM, set both qualification properties and provide each suite's filter directly
after its name:

```sh
-s opengpu.graphics.tessellation.TessellationSampleImageSharedSystemSpec -z sample-images-0 \
-s opengpu.graphics.tessellation.TessellationSampleImageOpsSharedSystemSpec -z sample-images-0
```

Require two tests and two completed suites in the reported result. A single
`-z` after both suite names filters only the last suite; the measured run used
that form and executed three baseline cases plus one image-op case. Do not use
ScalaTest `-P` parallel execution for this run.
