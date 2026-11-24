# Joint frame and original-source capture

`TessellationJointFrameCaptureReader` captures the 32-cache-line GNF1 record
for an independently accepted joint job. It reuses the native field map and
reserved-bit checks. It validates frame identity, state extent, active-engine
scratch prefixes, numeric owner bounds and frame-writer/image allocation
conflicts. The LIMIT33 exclusive end may equal `2^32`. The enclosing owner
retains immutable BUILD references, physical alias validation and actual MMU
mappings through retirement.

A job with zero invocations uses state version one and an absent state view
with both base and limit zero. Frame writers, scratch and active image views
are still checked. The `clearOnly` report identifies the captured frame form
for subsequent clear, store and resolve execution.

`TessellationJointRangeCaptureController` reads GNT7/8/9, GVS1 and the original
GND1 in order. The directory supplies the independent draw and sequence
indices, source view, original invocation and range identity, and task extent.
A mismatched GNT509..511 root fails before the GVS read. The joint crosslink
checker validates the actual GVS/GND observations, including the original
ordinal, instances and row boundaries. The capture retains original index
offsets, base vertex and instance, bindings and divisors. The TCS patch layout
and TES export count retain their own fields.

Both components default to unavailable. The controller holds one tag-zero
owner until the child report transfers and its requests, replies and busy
state drain. Already offered submits and memory requests survive cancellation
through acceptance, and accepted memory requests consume their real replies.
Late memory faults take precedence over cancellation. Reports are stable
under backpressure, and a healthy job can reuse the controller without reset.

The enclosing GNQ/directory controller must keep the directory entry through
actual task retirement and drain. Parent and physical CU execution, protected
admission and the two-CU 640×480/60 FPS target remain separate qualification
gates.

## Focused validation

Eight component tests passed on fresh assertion-enabled FlashSim models with
16 lanes, eight warps, all 16 attribute/binding/varying slots, four engine
scratch views and four MRTs. The frame fixture uses four samples.

- Two GNF1 cases cover the default unavailable response, canonical no-state
  and populated state views, LIMIT33 and alias checks, held offers and reports,
  delayed memory faults and reset-free reuse.
- Three nonempty range cases compare every GNT field against independent
  little-endian words and verify serial GVS/GND capture, source-root rejection,
  independent draw identity, row/ordinal checks, cancel/drain and reuse.
- Three zero-work cases retain one original invocation with zero vertices and
  five instances. Each still reads all 72 lines (4,608 bytes) and preserves the
  original base vertex and instance. A falsely empty GVS over a nonempty GND
  and noncanonical zero-work slices fail, followed by healthy reuse.

The validated source tree was `4a153370f`. The rebase onto the protected P15
merge produced a byte-identical tree before the documentation update. A cold
Scala compile built 789 production and 1,138 test sources in 201 seconds.
All eight tests ran in one serial command in 309.49 seconds with a 1.08 GiB
time-reported maximum process RSS. The test JVM used a 1.5 GiB heap, CIRCT had
two workers and C++ compilation used one job. The affected-suite selector
passed 168 cases, as did the integration and large-PR catalog checks.

## Reproduction

Use JDK 21, Python 3.13, FlashSim `00cdc562fa8a3b075448555d821ff92c76d7eb63`,
firtool 1.158.0 and the compatible `circt-verilog` build reporting
`CIRCT d21a21e51` with bounded MLIR threading. Point `FLASHSIM_DIR` at the
FlashSim checkout, `CIRCT_VERILOG` at the importer and
`FLASHSIM_CIRCT_LIBRARY_PATH` at its macOS library directories.

```sh
git submodule update --init depends/asap7_sram_0p0
export FLASHSIM_DIR CIRCT_VERILOG FLASHSIM_CIRCT_LIBRARY_PATH
export FLASHSIM_CIRCT_THREADS=2
sbt -J-Xmx4g -J-XX:ActiveProcessorCount=3 'Test / compile'
sbt -J-Xms128m -J-Xmx1536m -J-XX:+UseSerialGC -J-XX:ActiveProcessorCount=2 \
  -Dopengpu.simBackend=flashsim -Dopengpu.flashsimPython=python3.13 \
  -Dopengpu.simJobs=1 -Dopengpu.simWorkspace=.artifacts/joint-range-sim \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationJointFrameCaptureReaderSpec opengpu.system.TessellationJointRangeCaptureControllerSpec opengpu.system.TessellationJointZeroWorkCaptureSpec'
python3 -B scripts/test_changed_test_suites.py
python3 -B scripts/test_test_selection.py
bash -n scripts/ci_shard_tests.sh
```
