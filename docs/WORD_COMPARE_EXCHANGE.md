# Word compare/exchange

`AMOCAS.W` compares the complete 32-bit memory word with the original value
of `rd`, writes `rs2` on equality and returns the old word in `rd`. The
implementation writes the captured old word on a failed comparison, as allowed
by the [RISC-V word-CAS contract](https://docs.riscv.org/reference/isa/v20260120/unpriv/zacas.html).
Both outcomes retain atomic ownership through publication. Global completion
waits for the actual lower write acknowledgement, including its access fault.
Sv32 requires readable and writable mappings even when comparison fails.

CAS captures its comparison operand through a second read cycle on the existing
two-read mirrored scalar SRAM register file. It adds no third read port or SRAM
replica. Destination RAW/WAW dependencies, `x0`, source/destination aliases,
writeback conflicts and held issue operands retain their architectural values.
Local shared memory and cached/uncached global L2 requests carry the same
captured comparison value. Helper-only invocations suppress memory effects.

Seventy distinct local hardware cases pass: 56 decoder/register/atomic/memory
regressions and 14 integration/translation/helper cases. Real four-CU,
16-lane/eight-warp execution covers 80 kernels: 36 legacy AMO, 40 global CAS
and four CU-local CAS kernels. Contended global comparisons permit one winning
swap per matched round; failed comparisons preserve the published word.
Actual L1 invalidation, stale aliases, delayed write acknowledgements, four
precise fault kinds, held traps/results and healthy owner reuse are checked.
Local kernels independently check both matching and failed comparisons and
read back the resulting shared word without external atomic traffic.

Reproduce with bounded cache geometry and serial simulation:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.core.frontend.decode.DecoderSpec opengpu.core.backend.register.ScalarRegisterMacroManagerSpec opengpu.core.backend.register.ScalarRegisterManagerSpec opengpu.core.backend.scoreboard.RegisterScoreboardSpec opengpu.core.backend.issue.ScalarIssueStageSpec opengpu.core.execute.memory.SharedAtomicExecuteStageSpec' \
  'testOnly opengpu.core.memory.BankedSharedMemorySpec opengpu.core.memory.SharedL2CacheSpec' \
  'testOnly opengpu.system.GpuSystemGlobalAtomicVmSpec opengpu.core.memory.GlobalAtomicTranslationSpec opengpu.core.memory.HelperInvocationPolicySpec'
```

The system fixture uses an eight-set/two-way L2 and four-set/one-way private
caches; all four CUs and four L2 banks remain present. These results do not
qualify full cache capacity, sustained rates, physical timing or product FPS.
Wider CAS encodings remain illegal; the complete Zacas extension is not
advertised. Cross-domain acquire/release ordering, compiler/API lowering,
general storage/image atomics and H7 acceptance remain open.

[Bounded compute submission](COMPUTE_WORD_ATOMIC_ADMISSION.md) now admits
relaxed word atomics through a hardware-gated driver path. Compiler lowering,
general storage/image descriptors and end-to-end API qualification remain open.
