# Owner qualification memory accounting

`scripts/owner_process_memory.py` supplies the memory check for a caller that
already owns and enumerates its process tree. On macOS it charges the larger
of resident memory and `proc_pid_rusage(RUSAGE_INFO_V0).ri_phys_footprint`;
the latter includes compressed memory. On Linux it charges `VmRSS + VmSwap`.
It does not use virtual address-space size or add RSS to a footprint that
already includes resident allocations.

Create one budget for each bounded stage and call it after every ownership
observation, inside the stage's existing cleanup `try/finally`:

```python
from owner_process_memory import ProcessMemoryBudget

budget = ProcessMemoryBudget(24576)
# owned is {pid: caller_birth_identity}, obtained by the caller's process-tree
# observation, including descendants that change process groups.
resident_bytes, charged_bytes = budget.check(owned)
```

`MemoryBudgetExceeded` and `MemoryObservationError` must both stop the stage
through its normal cleanup path. Permission errors, unsupported platforms and
missing counters do not fall back to RSS. Native start identities detect PID
reuse between samples. A departed PID contributes zero; that observation does
not establish that its descendants have exited. The budget does not discover
ownership, enforce a deadline, signal processes or authorize deleting inputs.
Do not make cleanup depend on another successful budget check.

This is sampled accounting, not a kernel hard limit or an atomic tree snapshot.
Short peaks between polls can escape observation, and shared resident pages
may be counted in more than one process. Report both
`peak_resident_bytes` and `peak_charged_bytes`, and preserve the caller's
ownership, deadline and exit-proof checks. The helper does not qualify a GPU
model or allow reducing hardware capacity.

Reproduce the focused checks with:

```sh
python3 -B scripts/test_owner_process_memory.py -v
```

The checks cover aggregate ceilings, compressed-footprint values exceeding an
RSS budget, swap accounting, unavailable observations, PID reuse and actual
32-MiB child allocation followed by process-group cleanup. macOS also compiles
an independent check of the public C structure layout. The compressed-memory
case uses controlled API fields; the tests do not create system memory pressure
or claim to force kernel compression.
