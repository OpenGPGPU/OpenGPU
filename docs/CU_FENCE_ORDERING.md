# CU fence ordering

`GpuComputeUnitFenceOrderingSpec` executes ordinary stores, `fence rw,rw`,
loads and a global `amoadd.w` through the real instruction frontend, scalar
backend, data cache and atomic request path. One four-lane/two-warp CU runs
three single-invocation kernels: a store fault, an atomic fault and successful
reuse without reset.

The external memory responder holds the first store request for thirteen
clocks, delays store acknowledgements by twenty-three clocks, and delays atomic
replies by thirty-one clocks. Writes become visible in its byte oracle only
when acknowledged. Assertions reject a younger atomic request or load before
its predecessor's acknowledgement, and reject register/output publication
after either fault. Precise fault PCs/addresses, held traps/completions, exact
result words (7, 41, 48), surrounding byte guards and drained responses pass.

The test passed locally with three executed kernels, two expected faults and
zero skipped cases. The actual test run took approximately fifty seconds,
including model preparation. Source/class dependency checks and raw results
remain local under `.artifacts/`. The changed-test PR gate selects this suite;
it does not require a frame render or product qualification.

```sh
sbt -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'testOnly opengpu.core.GpuComputeUnitFenceOrderingSpec'
```

`GpuSystemCachedPublicationSpec` adds ordinary cached-store publication through
four real CUs, their private caches and all four shared L2 banks. Only lower
memory is modeled. After warming all four private caches, three consumers load
the old data and poll the old flag at least twice before a producer is released.
The producer stores new data, executes `fence rw,rw`, then stores a flag on a
different cache line. Each consumer observes the new flag, executes the fence
and checks the new data. Both lower store acknowledgements are delayed by at
least 73 clocks; the flag store must follow the data acknowledgement. Memory
requests and public completions are backpressured, neighboring words stay
unchanged, and a second publication reuses command IDs and owners without reset.

This local case passed with twelve kernels, two publications and six consumers
in 996 simulated clocks, with no skipped cases. The complete test took about
three minutes and fourteen seconds including model preparation. It retains
16 lanes/eight warps per CU, with one active invocation per kernel, an eight-set,
two-way L2 and four-set, one-way private caches. It is explicitly excluded from
PR/nightly CI; reproduce it locally:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  'testOnly opengpu.system.GpuSystemCachedPublicationSpec'
```

The Linux compute validator also admits the canonical `fence rw,rw` encoding
for ordinary kernarg-only and checked storage compute profiles. Fragment,
vertex and texture profiles do not admit it; this does not establish ordering
for those separate resource clients. A 16×16 one-CU ARTI Linux smoke passed a one-lane
kernel that stores a word, fences, loads it and checks both output words after
the completion fence. Reproduce it with an existing configured model:

```sh
python3 scripts/test_driver.py
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=600 QEMU="$ISOLATED_QEMU" \
  scripts/run_arti_small_smoke.sh compute-fence
```

The single-CU fixture models atomic effects; actual L2 RMW has separate tests.
The four-CU fixture qualifies these ordinary cached-store/fence sequences with
real coherence. Neither establishes general GLSL memory barriers, typed
image/storage bindings, texture/attachment/DMA visibility, full resource
capacity, sustained rates or whole-host timing. Those E31-02–04/H7 requirements
remain open; simulation wall time is not GPU performance evidence.

A local four-CU storage-publication test uses real private caches and shared
L2 with four lanes and two warps per CU. Across two reuse epochs per direction,
one producer stores a word, executes `fence rw,rw`, and writes a flag through
ordinary memory. Three consumers first cache the old word and poll the flag,
then fence and read the new word. One run uses a storage write and ordinary
loads; the other uses an ordinary write and storage loads. The test delays
write acknowledgements, checks their order, preserves adjacent words, and holds
public completions. One test passed (24 kernels, four publications and twelve
consumers). It does not establish general barrier semantics, image/texture
visibility, or software API admission.

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=4 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'testOnly opengpu.system.GpuSystemStoragePublicationSpec'
```

The checked-storage Linux profile also admits this exact fence encoding when
texture operations are disabled. A small 16×16, one-CU Linux/ARTI smoke ran a
one-lane shader with storage store, fence, storage load, ordinary vector store,
fence and a word atomic; the storage, kernarg and atomic results passed. The
validator rejects other fence encodings and texture-enabled profiles. The same
shader also passed a denied storage-write test: the fault left storage, later
kernarg output and atomic input unchanged, and a writable retry succeeded
without resetting the device. This is not general GLES barrier admission or
image/texture ordering. Reproduce with a
configured storage model as described in [compute storage submission](COMPUTE_STORAGE_SUBMISSION.md):

```sh
python3 scripts/test_driver.py
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=600 QEMU="$ISOLATED_QEMU" \
  scripts/run_arti_small_smoke.sh storage
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=600 QEMU="$ISOLATED_QEMU" \
  scripts/run_arti_small_smoke.sh storage-fault
```
