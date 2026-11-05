# Native system resource publication

`QualifyNativeSystemResourcePublication` exercises the actual `GpuSystem`
unified command router, command processor, grid distributor, two native CUs,
memory interconnect and shared SRAM L2. Each CU retains the default sixteen
lanes, eight warps, enabled FPU and SRAM register/shared storage. Instruction
and vector caches retain 64 sets/two ways; L2 retains 256 sets/four ways/four
banks. Only sparse lower byte memory is modeled. Texture descriptors,
translation/sampling/fetch and global atomic translation/L2 execution are real.

A healthy parent expands into two concurrent 128-item workgroups. Both retain
the complete compute active set: twelve 16 KiB uniform blocks plus all 1024
default components, sixteen texture/sampler views, four R32UI image units,
four 128 MiB sparse storage views, eight counters in a 32-byte view and 16 KiB
shared storage per resident group. Independent uniform endpoints, every default
component and view ID execute. Every shared word carries a group-specific,
resource/FPU-dependent value and is read by a different warp after a barrier.
Workgroup/local markers identify actual physical CU participation.

Both groups increment all eight counters before publishing separate capacity
flags. Exact returned-value sets contain 32 increments per counter across the
two CUs. The leader/peer handshake follows complete resource scoreboards and
all 256 counter write acknowledgements; a workgroup barrier does not synchronize
the other group.

After that phase, all warps warm an ordinary cached alias twice. A consumer
leader publishes readiness only after its group finishes these reads. The
producer completes its own warm reads, observes the actual ready token and
passes a local barrier before writing any alias. Storage stores feed actual
image loads/stores; storage and image atomic adds update the same physical
words. The producer fences and publishes an ordinary flag on a separate line.
The consumer observes that token, fences and checks the new values through
image, storage, texture and ordinary cached aliases. The first alias write
requires all sixteen warmed warp owners, and consumer release requires all
512 preceding data-write acknowledgements plus the actual publication token.

Ordinary flags may be forwarded by real L2 before their lower write responses.
The fixture counts requests, token observations and acknowledgements separately;
the producer fence and parent completion still require acknowledged writes.
Lower writes are delayed 73 cycles and alter the byte oracle only when their
responses are accepted. Held requests/results must stay stable.

The cached target uses a different private-cache set from flags. Code, counters
and flags avoid its shared-L2 set; every accepted lower request is checked so an
unrelated cacheable fill/store cannot evict the warmed directory line. The
initial warm stage requires exactly one actual cached lower read of that line.
This protects the cached-alias check from incidental capacity evictions. Checked
texture/image/storage line clients use uncached traffic; this does not establish
general sampler-cache coherence.

One direct healthy parent, one early bounds-fault parent and one indirect
healthy parent execute without reset. The latter has fresh page tables,
physical backing, publication generation/data and reversed group roles.
Live command bits are poisoned after admission. The system's shared SATP stays
fixed until public completion is consumed and the context drains, then receives
a full TLB flush before reuse. Each faulting warp must trap exactly
once with the precise PC/address/mask and no destination or write side effect.
Held public completions retain all five resource leases; duplicate public IDs
cannot enter until retirement. Read-only bytes and backing guards are checked.

The native qualification passes all three parents in one simulation without
reset. Direct and indirect parents each check 984 vector results (15,744 lane
values), 192 scalar uniform endpoints, 512 atomic results and exactly 772 lower
write acknowledgements. Both CUs have all eight warps active concurrently. Each
healthy parent warms the cached target through one lower read and observes the
fresh published value from all sixteen warp owners. The bounds parent accepts
exactly eight distinct warp traps and performs no atomic operation or lower
write. Held completion, all five leases, command-ID reuse and fresh VM roots
pass in the same run. No production RTL or capacity change is needed.

Run the pure assembler/control-flow check without emitting hardware:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-XX:+ExitOnOutOfMemoryError \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "QualifyNativeSystemResourcePublication.scala").contains(f.getName))' \
  'Test/runMain opengpu.system.QualifyNativeSystemResourcePublication --check-program'
```

Run native qualification explicitly, outside routine PR/nightly lanes:

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.nativeSystemResourcePublicationQualification=1 -Dopengpu.productQualification=0 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simHierarchy=GpuComputeUnit 'set Test / parallelExecution := false' \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "QualifyNativeSystemResourcePublication.scala").contains(f.getName))' \
  'Test/runMain opengpu.system.QualifyNativeSystemResourcePublication --reports .artifacts/native-system-resource-publication'
```

The runner is `@DoNotDiscover` and property gated; it adds no routine CI case,
lane or frame render. This is a two-CU unified-command system witness. Host
MMIO/software-completion publication, Linux reservation fences, graphics
attachments/DMA, four-CU product coexistence, API/linkage/format coverage,
physical timing and full H7 remain separate gates. Simulation wall time is not
product performance evidence.
