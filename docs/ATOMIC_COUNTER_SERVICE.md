# Atomic counter service

The counter service captures a task's publication and Sv32 context, translates
its descriptor reads, and forwards checked word atomics to an external atomic
translation and arbitration path. The vector adapter sequences active lanes,
preserves inactive destination values, and reports a later fault without
pretending to undo earlier atomic side effects.

The service retains the virtual descriptor address and distinguishes invalid
translation from page-table or descriptor memory access faults. Architectural
trap retirement still requires actual CU/system wiring. Reset ownership,
native command admission, concurrent resource qualification and full H7 remain
open.

```sh
sbt 'testOnly opengpu.core.memory.AtomicCounterCuServiceSpec opengpu.core.memory.AtomicCounterVectorExecuteUnitSpec opengpu.core.memory.CheckedAtomicCounterSpec'
```
