# Instruction translation faults

The instruction TLB distinguishes a physical page-table read failure from an
invalid or non-executable Sv32 mapping. The fetch and decode paths preserve
that distinction: the scalar trap reports instruction access fault (cause 1)
for the former and instruction page fault (cause 12) for the latter, with the
original virtual PC in `tval`. The fetch-fault path suppresses instruction issue.
Instruction-cache memory faults remain access faults.

The instruction TLB, instruction cache, frontend and trap suites passed locally
(12 tests), including held responses and both fault kinds. A separate small
four-lane CU case passed: it injects an invalid root PTE and a PTE-read error
in successive kernels, holds the trap, checks cause and virtual PC, and
verifies failed completion without resetting. No frame is rendered.

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.InstructionTlbSpec opengpu.core.memory.InstructionCacheSpec opengpu.core.frontend.GpuFrontendSpec opengpu.core.trap.CoreTrapArbiterSpec'
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simHierarchy=GpuComputeUnit \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.core.GpuComputeUnitInstructionPageFaultSpec'
```

This does not qualify every page-table level, remapping race, system fault path
or the broader mobile GPU memory and physical requirements.
