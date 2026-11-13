# Compute full-host execution boundary

The H7 full-host compute execution path is unfinished. The current independent
CPU fixture connects actual Linux resource binding, uniform/SSBO preparation,
source snapshots, scheduler/admission, `opengpu_job_run`, MMU PTE generation and
hardware MMIO/IRQ/drain and context CREATE/DESTROY bodies. It does not execute shader instructions, and
no returned GPU bytes or performance results are claimed.

The first producer now connects real Mesa ESSL/NIR/TGSI through the normal
Gallium/library submission path to the actual Linux capture. The full-shared
source compiles to 1002 TGSI tokens and keeps all 16 KiB, 128 local threads and
four workgroups. Its GCC ASan/UBSan CPU capture and the separate canonical C++
capture/PTE parser pass. That check exits at pending READY without a completion
receipt, and is explicitly not GPU execution. The controller and independent
returned-byte oracle are implemented; actual execution on the complete
96-word DUT, active receipt/cleanup integration and its final result remain open. Native GNJ captures are a separate
protocol and cannot authorize an opcode-0 compute submission.

The second actual Mesa producer retains twelve 16 KiB UBOs at global bindings
24..35, all 4096 default-uniform bytes and four SSBOs, with 128 local threads
and four workgroups. Its GCC ASan/UBSan CPU capture also passes the independent
C++ capture/PTE parser. Writable intervals retain distinct original allocation
boundaries even when physical allocations are adjacent. Both source-specific
oracles reject the untouched guard-filled output; neither producer precomputes
shader results. Actual returned uniform/shared bytes remain unqualified until
the complete DUT runs them.

The boundary retains stock two-CU, 16-lane/eight-warp tasks, 16 KiB shared SRAM,
twelve 16 KiB UBOs plus the full default block and four SSBOs. Source windows,
descriptors, private code/uniform copies, mappings and physical addresses come
from the Linux implementation and the OS allocation primitive. Legacy compute
private resource/code leaves retain the current production RWX permissions;
uniform snapshot leaves use the actual read-only, non-executable map. No native
private-owner permission guarantee is inferred for compute.

`compute_host_export_memory` serializes original allocations and actual PTE
pages as OGMEM001 after the real run path has mapped and submitted its command.
The CPU transport keeps that kernel pending. OGCMP001 contains its actual MMIO
trace and discovery; OGCMT001 records the real IRQ/drain/POP applied to an
external held receipt. All returned memory must be checked independently before
a separate cleanup authorization. Each actual shootdown blocks on the live
DUT acknowledgement before its backing may be freed. VM/ASID/global-page-table
teardown is part of that boundary: actual context destruction evicts its ASID,
then the global MMU shutdown clears both SATPs and waits for a full shootdown.
A failed acknowledgement retains the reachable VM/page tables and wedges the
device. The CPU composition passes six negative cases: wrong sequence and busy
acknowledgements at job-window revocation, context-ASID eviction and final
global shutdown. Each isolated negative-test process ends with quarantined
allocations retained; it does not manufacture a later recovery acknowledgement.

Only uniform + SSBO/shared kernels have an initial execution entry. Capture of
image/counter writable views and continuous multi-kernel publication remain
open; this qualification boundary does not change device capability limits.
In particular, compute's ordinary scheduler chain requires full retirement
before a later job reuses fixed code/descriptor windows. It cannot reuse the
native two-prepared-owner shortcut.

Run the current CPU composition checks without retaining build outputs:

```sh
PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 python3 scripts/test_compute_host.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_compute_owner_protocol.py
```

The explicit CPU test peer only checks transport/admission and never writes
shader results. It is guarded by `OPENGPU_COMPUTE_HOST_TEST_MAIN`, which must
never be defined when linking an actual Mesa/DUT producer. The source remains
on a dependent draft topic until the complete execution pipeline is ready.

The independent compute controller codec requires the real 96-word profile and
ten auxiliary register reads. Native profile/receipt schemas, truncated profiles,
changed completion identities and cleanup without `contextCleanupObserved`
are rejected. A completion receipt cannot authorize cleanup; a separate
`OGCMBA01` authorization follows all independent byte/source/guard oracles.
The non-test producer object is also compiled without the CPU peer macro.

The full-shared oracle calculates each returned word from the original ESSL
stripe stores and `(id ^ 16)` peer loads after converged barriers. It rejects
untouched or precomputed initial results, wrong peer words, partial output and
any other changed byte in the original physical allocations, including output
tails, arguments, code, descriptors, page tables and sources. Its CPU rejection
tests do not execute a shader.

The dedicated producer process keeps its original GEM mappings and Linux job
alive through held completion, real IRQ/drain/POP, returned-byte checks and a
separate cleanup authorization. The controller requires the final actual
context-shutdown JSON and successful executor exit before sending
`OGCMOK01`; only then may the Linux file's remaining GEM references be dropped.
Generic Gallium destruction/publication invalidates after that shutdown are
outside this single-kernel fixture: it ends its isolated CPU process rather
than fake a successful later GPU operation. This does not qualify general GL
context lifetime, multi-kernel publication or a cross-CU warmed consumer.

The Mesa build entry is `scripts/test_gallium_compute_host.py`; it uses the
existing pinned Mesa22.3.6 configured objects and checked private shared NTT
postimage. Its `--profile-wire` input is the controller's strictly encoded real
96+10 probe, and `--session` is the live IPC directory. An explicit
`--capture-only` mode is reserved for CPU build/capture regression and exits
without any GPU completion or cleanup authorization. Do not use it in the
active controller command.
Select `--case shared` (the default) or `--case uniforms` to choose the original
full-capacity ESSL source; both use the same Linux ownership and controller
protocol. The independent uniform oracle computes its expectations from the
original GEM/default source windows in ESSL binding order, independently of
native UBO remapping, instructions and private snapshots.

After independently qualifying the complete factory/source/tool build, run:

```sh
python3 scripts/compute_owner_controller.py --executor QUALIFIED_COMPUTE_EXECUTOR --executor-qualification EXECUTOR_RECORD --executor-checker ORIGINAL_PREPARE_OWNER_EXECUTOR --executor-checker-python GENERATION_PYTHON --bridge-command BRIDGE_ARGV_JSON
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_compute_owner_oracle.py
```

`BRIDGE_ARGV_JSON` is a JSON argv array; `{ipc}` expands to the short, temporary
GPU-internal session directory. The command must keep the real C producer alive;
no shell is evaluated. The controller probes its executing DUT, serves actual
held/retired receipts, checks independent returned bytes, and deletes temporary
builds, captures and logs at terminal completion. `sourceQualificationRequired`
is preserved: a protocol peer or stale binary cannot establish the required
source/tool/factory checks. The actual full-DUT result remains unqualified until
that path runs on the final model.

The controller invokes the original `prepare_owner_executor.py --stage check`
before probing and again before its final response, using the generation's
exact Python interpreter and fixed CIRCT environment. It does not copy that
checker into the bridge checkout or reinterpret its source/model/binary guards.
A native executor record or any stale/changed source, tool, model or binary must
fail that independent check. The bridge's qualification does not become valid
merely because its byte protocol was accepted.

After the initial read-only executor check, the controller takes the prepared
owner's existing `.lock` with an exclusive lock and rechecks qualification. It
holds the same product/build lock through probing, live C ownership, execution,
oracles, synchronous cleanup and final response. A concurrent model/executor
rebuild cannot replace the active inputs; the lock is released only after both
processes have completed or stopped.

The full reproducible entry runs both real source cases by default:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/qualify_compute_owner.py --executor "$OWNER/executors/compute/executor" --executor-qualification "$OWNER/executors/compute/qualification.json" --executor-checker "$INTEGRATION/scripts/prepare_owner_executor.py" --executor-checker-python /opt/homebrew/opt/python@3.14/bin/python3.14
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_qualify_compute_owner.py
```

Keep the generation's fixed CIRCT environment. `$INTEGRATION` is the original
guarded-checker checkout and `$OWNER` is its actual prepared complete96 output;
neither can be a relabeled old model. A missing qualification fails before any
container or producer build. `--case shared|uniforms` selects one case. The
default compiler image is the existing `gpu-gtk-ci-check:latest`, resolved to an
immutable local image ID before use, with one 2 GiB/two-CPU serial build. Mesa
source/configured objects and DRM/library inputs default to GPU `depends/`;
explicit input paths must also stay inside the GPU workspace.
The GPU workspace, fixed producer archive and Mesa inputs are mounted read-only;
only the new managed session (including CPU build output) is writable.
`--timeout` defaults to 3600 seconds and accepts 1..604800. It is passed as
`OPENGPU_COMPUTE_HOST_TIMEOUT` to the actual producer's held/retired/final waits
and each synchronous cleanup acknowledgement; none retains the standalone
300-second default during formal execution. Malformed, zero or out-of-range
values reject admission. Every wait remains bounded.

This entry requires a clean committed producer checkout, archives that exact
commit, and runs the original checked private Mesa NTT from that archive. It
does not pass `--capture-only` or enable the CPU test peer. Each controller uses
the guarded executor and existing owner lock; qualification and executable
identity must also remain unchanged between the two cases. The outer process
owns unique containers and managed IPC directories, stops its process group,
verifies every remaining group member is absent or a zombie within a bound,
verifies container exit, then removes CPU builds, archives, captures and logs in
`finally`. If container exit cannot be established, it fails and retains the
possibly active inputs for diagnosis instead of deleting a live consumer's
files. This command has not yet run successfully on a complete DUT; its CPU
command/refusal/cleanup tests are separate from actual execution qualification.
