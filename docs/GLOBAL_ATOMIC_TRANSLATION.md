# Global atomic translation

Global RV32 word AMOs capture the data SATP root and virtual operand before
sharing the ordinary data page walker. They require readable and writable
translations, retain the page offset and PTE cache policy, and hold physical
requests and attributed replies under backpressure. They do not cache
translations across tasks. Unsupported physical PPN/root bits are rejected
instead of aliasing a 32-bit physical address. Existing ISA encodings remain
unchanged.

Uncached AMOs invalidate cached aliases and wait for their acknowledgements
before reading authoritative lower memory. They retire after the lower write
acknowledgement, allocate no cache line and preserve unrelated resident ways.
Fault reports retain the original virtual instruction operand.

Twenty-three translation/walker/execute/data-TLB/CU hardware cases and five
focused L2 atomic cases pass. CU tests execute twelve single-warp kernels across
payload-guard modes with a 16-lane/eight-warp configuration. They check SATP
remapping without a cached translation, denied mappings, PTE-fetch and physical
AMO access faults, held precise traps and healthy reuse. The external atomic
responder checks CU physical requests; actual read/modify/write behavior is
covered separately by L2 tests.

All 47 L2, local-memory and payload-atomic guard cases also passed on the initial
cache-policy change. The two uncached L2 cases use four CU ports with a
16-lane/eight-warp configuration and an eight-set, two-way cache. These checks
do not qualify whole-GPU coherence or full resource capacity.

PTE-fetch access faults retain their cause through the shared walker and data
TLB; invalid or denied mappings report page faults. Both AMO fault kinds retain
the original virtual operand. This follows the distinction in the
[Sv32 translation algorithm](https://docs.riscv.org/reference/isa/v20260120/priv/supervisor.html#_virtual_address_translation_process).

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'testOnly opengpu.core.memory.GlobalAtomicTranslationSpec opengpu.core.memory.SharedDataPageTableWalkerSpec opengpu.core.execute.memory.SharedAtomicExecuteStageSpec opengpu.core.memory.Sv32PageTableWalkerSpec opengpu.core.memory.VectorTlbSpec opengpu.core.GpuComputeUnitGlobalAtomicVmSpec opengpu.core.memory.SharedL2CacheSpec opengpu.core.memory.BankedSharedMemorySpec opengpu.core.memory.ShaderPayloadAtomicGuardSpec'
```

A bounded four-CU integration case connects real instruction/data caches,
Sv32 translation, alias invalidation and all four L2 banks. Its 36 kernels
exercise twelve successful cached/uncached RMWs and sixteen precise faults:
denied mappings, PTE-fetch failures and physical AMO read/write failures.
Stale private aliases must reload the updated value after uncached AMOs.
Concurrent CUs receive distinct old values; each successful AMO waits for its
actual delayed lower write acknowledgement before retiring. Held traps and
completions, unchanged neighboring words and healthy command-ID/owner reuse
are checked. Only lower memory is modeled.

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'testOnly opengpu.system.GpuSystemGlobalAtomicVmSpec'
```

This fixture retains four 16-lane/eight-warp CUs and four L2 banks, with an
eight-set/two-way L2 and four-set/one-way private caches to bound simulation
cost. It does not qualify full cache capacity, sustained atomic throughput or
whole-GPU coherence. [Word compare/exchange](WORD_COMPARE_EXCHANGE.md) adds
captured comparison operands and matching/failed CAS through the same local and
global owners. General storage/image access, wider atomics, broader
ordering/contention and complete composed timing remain open. These checks do
not close H7 or the full mobile alignment goal.
