# Atomic counter service

The counter service captures a task's publication and Sv32 context, translates
its descriptor reads, and forwards checked word atomics to an external atomic
translation and arbitration path. The vector adapter sequences active lanes,
preserves inactive destination values, and reports a later fault without
pretending to undo earlier atomic side effects.

Descriptor responses currently carry a fault and virtual fault address but no
page-fault classification. Precise distinction between page-walk faults and
other memory faults is still required for architectural trap retirement.
Actual CU/system wiring, reset ownership, native command admission, concurrent
resource qualification and full H7 remain open.

```sh
sbt 'testOnly opengpu.core.memory.AtomicCounterCuServiceSpec opengpu.core.memory.AtomicCounterVectorExecuteUnitSpec opengpu.core.memory.CheckedAtomicCounterSpec'
```
