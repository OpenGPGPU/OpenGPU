# Owner qualification memory accounting

Run a complete stage from its GPU topic checkout with explicit limits:

```sh
python3 -B scripts/run_owner_bounded.py \
  --memory-mib 24576 --timeout-seconds 14400 -- \
  python3 -B scripts/prepare_native_owner_flashsim.py --stage generate \
    --output "$P04_OWNER" --flashsim-dir "$FLASHSIM_DIR" --shards 32
```

The runner preserves argv, working directory, environment and the command's
output. A command failure keeps its exit status; a budget, observation or cleanup
failure returns failure. It starts a separate process session and tracks native
birth identities and observed ancestry across process-group changes. It retains
the unreaped leader until cleanup so a quick exit cannot reuse the initial
group's PID. It checks group membership before signaling, joins the leader after
the observed consumers exit, and reports resident/charged peaks and
`cleanup_proven`. It never removes command inputs or outputs. An uncertain exit
requires preserving inputs for diagnosis.

This is a runner for cooperating build/qualification tools, not a sandbox.
Commands must not launch background services that create a new session and
detach before any ancestry observation. The initial pinned group and previously
observed descendants remain covered after their parent exits. Unseen daemonized
sessions are outside the runner's exit proof. Keep the existing source, tool,
RTL, shard and binary qualification checks; a successful guard is not a GPU
qualification result.

For callers that already manage their own lifecycle,
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
python3 -B scripts/test_run_owner_bounded.py -v
```

The checks cover aggregate ceilings, compressed-footprint values exceeding an
RSS budget, swap accounting, unavailable observations, PID reuse and actual
32-MiB child allocation followed by process-group cleanup. macOS also compiles
an independent check of the public C structure layout. The compressed-memory
case uses controlled API fields; the tests do not create system memory pressure
or claim to force kernel compression.

The command-runner cases also exercise actual quick/nonzero exits, aggregate
allocation failure in a detached child, deadline cleanup of a child that ignores
SIGTERM, a leader that exits before its original-group child, and interruption
of the CLI. PID reuse, foreign groups and unavailable observations fail closed;
the caller's original input file remains present. These tests do not generate
RTL or a GPU model.
