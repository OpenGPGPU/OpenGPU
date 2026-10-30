# Atomic counter command publication

The optional internal host transport advertises GCR1 bit 8 and a GAM1 block
at `0x4a0`–`0x4bf`. Seven staging words hold enable/version, descriptor table
base, 33-bit limit, context, pass and generation; the final word is read-only
magic. The host captures a separate publication on each direct or indirect
kernel submission. Disabled profiles do not map this block. Only protected-VM
host configurations can enable it. A publisher retains the descriptor, backing
allocation and VM context until the public completion is popped.

The checked C encoder in `driver/opengpu_atomic_counter_command.h` detects the
advertised block and validates the version, 64-byte table alignment and the
4 GiB boundary before writing staging words. Counter execution and its
binding-zero descriptor contract are described in
[COMPUTE_ATOMIC_COUNTER_EXECUTION.md](COMPUTE_ATOMIC_COUNTER_EXECUTION.md).
This transport does not itself advertise GLES conformance or compiler support.

Validation:

```sh
python3 scripts/test_driver.py
python3 scripts/test_test_selection.py
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'runMain opengpu.elaboration.EmitAtomicCounterCommandAbi --check' \
  'runMain opengpu.elaboration.EmitComputeResourceAbi --check' \
  'testOnly opengpu.graphics.GpuCommandMmioAtomicCounterSpec opengpu.system.GpuHostAtomicCounterCommandSpec'
```

MMIO tests cover queued snapshots, invalid writes, reset of staging permission,
and independent image publication. The two-CU AXI test covers direct and
indirect commands with separate tables, held completion/VM protection, and
permitted and denied Sv32 descriptor accesses. Reset during an outstanding
descriptor read and during an accepted atomic cache miss drains the command,
clears staging permission and permits a new command. An accepted atomic may
have side effects despite cancellation. Full resource coexistence, compiler/API
integration, physical qualification and H7 closure remain open.
