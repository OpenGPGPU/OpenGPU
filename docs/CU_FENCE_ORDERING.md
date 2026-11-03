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
for ordinary, checked-storage and texture-enabled compute profiles. Fragment
and vertex profiles do not admit it; this does not establish ordering for
those graphics resource clients. A 16×16 one-CU ARTI Linux smoke passed a one-lane
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

The checked-storage Linux profile also admits this exact fence encoding. A small 16×16, one-CU Linux/ARTI smoke ran a
one-lane shader with storage store, fence, storage load, ordinary vector store,
fence and a word atomic; the storage, kernarg and atomic results passed. The
validator rejects other fence encodings and graphics profiles. The same
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

A local one-CU texture/storage ordering test delays storage writes and texture
responses by 31 clocks. It checks that a texture size request follows the prior
storage acknowledgement and that a later storage load follows texture
completion. Injected storage and texture faults suppress younger operations; a
healthy third kernel succeeds without reset. A separate 16×16, one-CU Linux/ARTI
smoke passes two warps through texture fetch, fence, distinct storage write,
fence and a per-warp storage read, checking both outputs. These tests use
disjoint resources and do not establish texture/storage alias coherence,
graphics/image/DMA visibility or general GLES memory-barrier semantics.

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=4 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'testOnly opengpu.graphics.ComputeTextureFenceOrderingSpec'
scripts/build_arti_compute_resources_model.sh "$QEMU_BUILD" "$QEMU_SRC" "$ARTI_EMBEDDED_WRAPPER"
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_TIMEOUT=900 QEMU="$ISOLATED_QEMU" \
  scripts/run_arti_small_smoke.sh resources-fence
```

A local actual-CU test repeatedly fetches one `R32UI` texel, writes the same
word through storage, fences and refetches it. Two delayed writes produce exact
old/new values and preserve neighboring words. A separate two-CU system test
uses real shared L2, delayed write acknowledgements and a polling consumer to
verify two publications in each of three modes: Bare, cached Sv32 aliases, and
uncached Sv32 aliases matching the Linux binding policy. The translated modes
use distinct virtual addresses mapped to the same physical page. These tests
do not establish arbitrary formats, imported-backing aliasing, or general GLES
coherence.

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=4 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'testOnly opengpu.graphics.ComputeTextureStorageAliasSpec opengpu.graphics.GpuTextureStorageAliasPublicationSpec'
```

## Image and ordinary memory

A local one-CU, one-lane check executes an image store, `fence rw,rw`, an
ordinary load and store, a second fence, and an image load. The fixture delays
write acknowledgements by 31 cycles and verifies that neither younger read
passes the preceding acknowledgement. A failed image write produces one trap,
suppresses the later accesses and leaves the checked words intact; a subsequent
kernel succeeds without reset and returns the exact values. This is Bare-mode CU
ordering evidence with modeled lower memory. The separate four-CU cached
image-store publication evidence is described in [image execution](SHADER_IMAGE_EXECUTION.md).
The small Linux/ARTI image guest also passed a denied write followed by a
fenced store/load and healthy reuse. These tests do not establish general image,
texture, attachment or DMA barriers.

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.GpuComputeUnitImageFenceSpec'
```
