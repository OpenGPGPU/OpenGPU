# Coherent compute buffer-image ordering

The bounded compute emitter admits `TGSI_MEMORY_COHERENT` on its checked
buffer-image load/store/query path. Image data accesses already bypass private
data caches, wait for acknowledged typed accesses and pass through shared L2.
The existing native fence remains the lowering for `memoryBarrierImage()`.
Other memory qualifiers remain rejected; none are silently removed. Product
capabilities, native resource counts and API enablement are unchanged.

The actual Mesa 22.3.6 ESSL→NIR→TGSI compiler fixture supplies two coherent
image producers, a repeated coherent-image consumer and a repeated buffer
texture consumer. Each producer writes data, executes `memoryBarrierImage()`
and then writes a flag on a different cache line. Consumers read the old texel,
publish a checkpoint, execute `memoryBarrier()` and read that texel again.
Their second coordinate comes from a real mutable checked SSBO value, avoiding
sampler common-subexpression elimination. Actual emitted instructions and
kernel-published texture/image/storage records are used without editing them.

The CPU qualification passes ASan/UBSan, checks exact backing/output guards
and rejects all six qualifier combinations containing RESTRICT or VOLATILE
without modifying the output program. The complete existing buffer-resource
corpus also passes, including typed frontends, full resource coexistence,
binding failures and ownership recovery. Its unsupported-qualifier negative
now uses VOLATILE; the fourteen negative cases are retained. Test-selection
integration and large-catalog checks pass.

## Execution fixture and limits

The actual two-CU run passes 21 kernels, 15 checked ordinary/vector values,
three precise image-write faults and eleven private-cache invalidations in
25,066 cycles. Lower read responses are delayed seven cycles and writes
73 cycles. Held faults/completions, exact backing/output guards, unchanged
consumer ownership and no-reset recovery pass in all three consumer modes.

The explicit qualification uses two real four-lane/two-warp CUs with private
L1 caches, `SharedComputeMemoryInterconnect` and the real four-bank shared L2.
Captured Gallium programs have different native context identities and fixed
metadata virtual addresses. Each CU therefore holds its own stable Sv32 root;
original descriptors remain unchanged, and views of the same original GEM
handle/offset map to the same physical backing. This is a component execution
contract, not command-system single-VM scheduling or API context concurrency.

The ordinary consumer first caches the old data and polls the old flag at
least twice before the producer proceeds. Its final cached reload checks
actual L2-to-private-L1 invalidation. Image and texture data use their real
uncached clients; each original consumer task first reads the old value and
then repeats its access after publication, retaining its VM and resource
owners throughout. These clients are not described as private texel caches.

Bounded response queues between the real interconnect and CUs delay delivery
of original replies. A producer's first instruction response waits until its
consumer has warmed the resource. For image/texture consumers, their original
checkpoint completion response waits until the actual producer flag write is
acknowledged. Lower writes still receive finite delayed acknowledgements and
free L2 banks before those CU-side delivery delays; holding a lower response
instead could prevent the producer from accessing that bank. Invalidations
continue through the actual cache ports. No resource result or acknowledgement
is synthesized by the fixture.

The fixture checks two publication epochs, a failed first image write with
held precise fault/completion, and healthy reuse without reset in each
consumer mode. The bounded R32UI buffer-image/texture paths do not establish
general image formats/layouts, all legal aliasing, graphics attachments, DMA,
API scheduling/visibility, default product capacity, throughput or physical
timing. The separate [Mesa atomic-counter path](GALLIUM_ATOMIC_COUNTERS.md) qualifies
bounded real lowering and counter-service execution. Shared SRAM remains
workgroup private; the separate [shared publication chain](GALLIUM_SHARED_PUBLICATION.md)
qualifies a full converged cross-warp producer followed by global publication
to its original warmed consumers. General shared semantics, H7 and the full
API/resource matrix remain open.

## Reproduction

With the matching Mesa objects and checked uniform-index lowering source:

```sh
export OPENGPU_MESA_DIR=/path/to/mesa-22.3.6
export OPENGPU_MESA_BUILD=/path/to/matching-mesa-build
export OPENGPU_DRM_INCLUDE=/path/to/drm-headers
python3 scripts/test_gallium_image_ordering.py --build .artifacts/image-active --sanitize

sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.productQualification=0 \
  -Dopengpu.galliumImageOrderingQualification=1 \
  -Dopengpu.galliumImageOrderingInputs=.artifacts/image-active/glsl \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "QualifyGalliumImageOrdering.scala").contains(f.getName))' \
  'Test/runMain opengpu.core.QualifyGalliumImageOrdering'

python3 scripts/test_gallium_buffer_flow.py --build .artifacts/image-buffer-regression --sanitize
python3 scripts/test_test_selection.py
```

The RTL runner requires explicit properties and actual compiler/publication
inputs, fails the command on assertion failures and is excluded from default
discovery. Delete inputs, models, private builds and logs when validation ends.
