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

Fourteen translation/walker/execute hardware cases and all 47 existing and
extended L2, local-memory and payload-atomic guard cases pass. The two uncached
L2 cases use four CU ports with 16-lane/eight-warp configuration and an eight-set,
two-way cache. These are component checks, not whole-GPU coherence or resource
qualification.

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'testOnly opengpu.core.memory.GlobalAtomicTranslationSpec opengpu.core.memory.SharedDataPageTableWalkerSpec opengpu.core.execute.memory.SharedAtomicExecuteStageSpec opengpu.core.memory.Sv32PageTableWalkerSpec opengpu.core.memory.SharedL2CacheSpec opengpu.core.memory.BankedSharedMemorySpec opengpu.core.memory.ShaderPayloadAtomicGuardSpec'
```

Instruction-driven CU integration remains to be validated. The shared walker's
generic fault bit still merges invalid PTEs and PTE-fetch access faults; precise
cause propagation remains required. General storage classes, compare/exchange,
ordering, contention/fault coverage and complete composed timing remain open.
This change does not close H7 or the full mobile alignment goal.
