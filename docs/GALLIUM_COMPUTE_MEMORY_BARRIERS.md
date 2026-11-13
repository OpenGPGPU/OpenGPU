# Gallium compute memory barriers

The bounded TGSI compute emitter accepts `MEMBAR` with an immediate bitmask
over shader buffers, atomic buffers, images, shared memory and thread-group
scope. Every legal combination lowers to canonical native `fence rw,rw`.
Unknown bits, dynamic operands and undeclared immediates fail without
publishing partial code. An empty mask conservatively emits the same fence.
No GL/GLES compute capability or API version is enabled by this change.

`MEMBAR` orders the calling invocation's memory operations; it does not wait
for other workgroup invocations to arrive. The thread-group bit neither emits
the native execution rendezvous nor advances the shared initialization proof.
`BARRIER` retains its separate full-warp, converged-program and initialized-
prefix admission checks.

Checked storage/image operations and translated atomics retain the warp until
their accepted requests acknowledge and the instruction commits. Storage and
ordinary image traffic use checked uncached lines. The shared L2 also probes
private-cache sharers before a conflicting full-line store, waits for their
invalidation acknowledgements and retains the write until lower memory
acknowledges it. The CU's one-unresolved-instruction-per-warp rule then orders
younger instructions. A fence does not replace retained resource/VM leases or
an execution barrier between invocations.

## Validation and limits

The real Mesa 22.3.6 ESSL310 → NIR → TGSI → production emitter → kernel word
validator CPU run passes under ASan/UBSan. It compiles all six GLSL memory-
barrier functions, a conditional buffer barrier and two publication epochs.
All 32 legal masks, 32 masks containing an unknown bit, invalid immediate and
dynamic sources, and absence of execution rendezvous are checked. The original
callback corpus also passes. The image/counter/shared mask cases in this
corpus operate on buffers: they establish compiler coverage, not visibility
for those other resource domains.

The separate [coherent buffer-image qualification](GALLIUM_IMAGE_ORDERING.md)
executes actual image producers and repeated image/texture consumers, plus
a warmed ordinary-cache consumer, through two real CUs and shared L2. Its
stable per-CU VMs and response-transport delays establish the documented
component contract; atomic-counter, shared and general API-domain gates
remain separate.

The seven actual barrier-program word streams execute on a four-lane/two-warp
CU. Each runs a failed first storage write followed by healthy reuse without
reset: 14 kernels, seven precise faults, 37-cycle delayed write responses,
request backpressure, exact output/guard words and held completion pass. No
younger storage read/result store follows a faulted write or passes its
acknowledgement.

The two actual Mesa publication producers also execute through the two-CU
`GpuSystem` command path with real private caches and four-bank shared L2.
The consumer first caches old data and polls the old flag at least twice.
Only then is the producer released to write data, execute the compiled
`memoryBarrierBuffer()` and write a flag on a different line. Lower writes
publish only at their responses, delayed by at least 73 cycles. Both epochs
pass: six kernels, two publications, correct consumer reloads, guards, held
completion, owner reuse and complete drain, in 1656 clocks. The reverse native
ordinary-producer/storage-consumer direction also passes: six kernels, two
publications and 1150 clocks. This directly checks compiler-to-CU-to-L2
storage/ordinary visibility with a consumer holding an old cached value.
The unmodified default four-CU native producer programs also pass both
directions: 24 kernels, four publications and twelve consumers, without
failures or skipped cases.

The full 16 KiB shared frontend regression passes under ASan/UBSan: 4096
initialized words, 128 work-items, two barrier generations, both warp orders
and two seed epochs, at 486 native words. Omitted/divergent execution barriers
and uninitialized shared reads remain rejected.

These bounded RTL checks use small caches and four lanes/two warps per CU.
They do not qualify product capacity, sustained rates, physical timing,
arbitrary sampler/image/storage aliasing, graphics attachments, host/DMA
visibility, graphics-stage admission, general layouts or full API/compiler
ownership. Existing image, texture and atomic evidence remains separately
scoped in [CU fence ordering](CU_FENCE_ORDERING.md). H7 remains open.

## Reproduction

Use already configured matching Mesa 22.3.6 objects and checked uniform-index
lowering source; the runner builds privately without modifying Mesa:

```sh
OPENGPU_MESA_DIR=/path/to/mesa-22.3.6 \
OPENGPU_MESA_BUILD=/path/to/matching-mesa-build \
OPENGPU_DRM_INCLUDE=/path/to/drm-headers \
python3 scripts/test_gallium_compute_barriers.py \
  --build .artifacts/compute-barriers-active --sanitize

sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.productQualification=0 \
  -Dopengpu.galliumComputeBarrierQualification=1 \
  -Dopengpu.galliumComputeBarrierInputs=.artifacts/compute-barriers-active/glsl \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "QualifyGalliumComputeBarrier.scala").contains(f.getName))' \
  'Test/runMain opengpu.core.QualifyGalliumComputeBarrier'

sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.productQualification=0 \
  -Dopengpu.galliumComputeBarrierQualification=1 \
  -Dopengpu.storagePublicationCus=2 \
  -Dopengpu.galliumComputeBarrierPublicationInputs=.artifacts/compute-barriers-active/glsl \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "GpuSystemStoragePublicationSpec.scala").contains(f.getName))' \
  'testOnly opengpu.system.GpuSystemStoragePublicationSpec'
```

The ordinary publication suite retains its default four-CU bidirectional
native programs without the input property. The two-CU option changes only
this local fixture's CU count. Actual Mesa input replaces only the storage
producers; reverse-direction coverage remains enabled. Missing input files or
the qualification property fail closed. `QualifyGalliumComputeBarrier` is not
discovered by default CI. No frame is rendered.

Delete task-specific CPU objects, generated inputs, models, caches and logs
when validation/diagnosis ends. Keep source and these concise results/commands;
no raw evidence archive is needed.
