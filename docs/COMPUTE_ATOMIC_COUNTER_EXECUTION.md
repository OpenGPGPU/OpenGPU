# Checked compute atomic counters

The optional `atomicCounterAvailable` compute path accepts a task-owned,
versioned storage descriptor table at GPU command admission and retains its
lease until the public completion is consumed. Grid dispatch copies the task
to each workgroup. The descriptor uses binding zero and an independently
published readable and writable view with matching context, pass and generation.
The implementation supports counter indices 0–7, each a 32-bit word; it
checks view and backing-line bounds before issuing an atomic. The publisher
must keep the descriptor, allocation and VM context valid for the lease.

Native CUSTOM-1 vector operations implement read, increment and decrement.
Increment returns the old value and decrement returns the new value. Active
lanes execute in order within an instruction; helper lanes do not perform
increments or decrements. A fault suppresses the vector destination, but does
not undo earlier acknowledged lane operations. Requests already offered or
accepted drain during cancellation. The descriptor and atomic paths use the
captured task SATP; storage and counter descriptor traffic share the existing
16-ID CU namespace with tracked response ownership.

Local validation:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.CheckedAtomicCounterSpec opengpu.core.memory.AtomicCounterCuServiceSpec opengpu.core.memory.AtomicCounterVectorExecuteUnitSpec opengpu.core.memory.AtomicCounterVectorCuServiceSpec opengpu.core.memory.CounterAtomicArbiterSpec opengpu.core.memory.SharedStorageCounterPortSpec opengpu.core.frontend.decode.AtomicCounterAvailabilityDecodeSpec opengpu.core.GpuCoreCounterAtomicSpec opengpu.core.GpuComputeUnitAtomicCounterSpec opengpu.dispatch.AtomicCounterGridDispatchSpec opengpu.dispatch.AtomicCounterCommandOwnershipSpec opengpu.system.GpuSystemAtomicCounterSpec'
```

The two-CU system case executes three direct and three indirect workgroups,
with 24 increments contending on one counter through the shared cache; all 24
old values are distinct. CU tests cover read/increment/decrement and Bare,
permitted Sv32 and denied descriptor translation. The system test enables the
image, storage, uniform and texture interfaces but does not exercise concurrent
operations on them. This is an internal command interface, not a software or
GLES API. Host publication, full resource-coexistence and reset qualification,
compiler integration, full mobile resource limits, physical timing and H7
closure remain open.
