# Atomic counter service

The counter service captures a task's publication and Sv32 context, translates
its descriptor reads, and forwards checked word atomics to an external atomic
translation and arbitration path. The vector adapter sequences active lanes,
preserves inactive destination values, and reports a later fault without
pretending to undo earlier atomic side effects.

The service retains the virtual descriptor address and distinguishes invalid
translation from page-table or descriptor memory access faults. The internal
CU and shared-system paths retain counter ownership through direct and indirect
commands. Local tests exercise counter operations across two CUs and check CU
trap causes, addresses and lane masks. CI retains bounded dispatch, command and
vector-service coverage; the two-CU system qualifier runs locally. Native
command admission, reset ownership, concurrent resource qualification and
full H7 remain open.

```sh
sbt 'testOnly opengpu.core.memory.AtomicCounterCuServiceSpec opengpu.core.memory.AtomicCounterVectorExecuteUnitSpec opengpu.core.memory.CheckedAtomicCounterSpec'
sbt -Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=2 \
  'testOnly opengpu.system.GpuSystemAtomicCounterSpec'
```
