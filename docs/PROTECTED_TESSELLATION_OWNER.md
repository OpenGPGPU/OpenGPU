# Protected tessellation ownership (P16)

P16 implements independent TCS/TES GUR3/GUB3 snapshots, a stage 4/5 program
validator and unchanged-byte private RX capture, a typed three-domain builder,
and Linux GEM/reservation/scheduler ownership admission. The production profile
getter and hardware run entry remain unavailable. There is **no registered
protected tessellation ioctl**, new discovery bit/address or hardware kick.
P16 remains incomplete; CPU tests do not qualify an actual provider or GPU result.
Old GUR1 and trusted GNQ/GNT/GNF interfaces retain their original contracts.

## Uniform and program contract

Uniform inputs reuse two `drm_opengpu_native_uniform_stage` records: TCS stage 4
and TES stage 5. Each retains 12 logical blocks of at most 16KiB and 1024 default
components. Their aggregate has 24 mappings. Only exact original
`(handle, offset, bytes)` views deduplicate; identical cross-stage views get
stage mask `0x30`. Different offsets/sizes remain separate. Defaults independently
use selector 31. Payloads are copied exactly and privately realigned to 256 bytes.
Unused entries, padding, reserved bits and complete read-line tails are zero.
Private roots/table/data are read-only and non-executable. Return values contain
private segment IDs/offsets/presence and aggregate byte size, never an exported
GEM or caller-selected address. All metadata is captured before callbacks.
Malformed/inactive views, offsets, sizes and overflow fail with output unchanged;
any partial append failure requires whole-owner retirement.

Programs are read once from retained originals into independent byte and
little-endian analysis buffers. The complete original view, including opaque
prefix/unreachable suffix, is copied unchanged into RX/no-W private pages.
Views are bounded to 512 words; aligned nonzero entry offsets are supported.
Every reachable path must terminate with CEASE. Untrusted stage metadata grants
no permission: a matched internal verified-owner profile is required.

Version 1 TCS validates GPU stage 4, lanes 1/2/4/8/16, warps 2/4/8 and actual launch
layout. Inputs have at most 32 vertices×64 components; outputs at most 32 vertices,
64 components and 120 patch components. Input and combined output/patch storage
must each fit 2048 words. Thus 32×64+0 and32×60+120 pass; 32×61+120 fails.
Version 1 TES validates stage 5 and only current vertex-zero static transport:
three TessCoord words plus at most 61 selected patch words, one emitted point.
This is not full dynamic `gl_in[]`; P40 needs matched service/ISA/validation.
P39 separately supplies original VS-to-patch execution. CPU-produced patch data
does not close either requirement.

Definedness follows real launch: x1 kernarg, x2..4 group coordinates, x5..7 local
sizes, x8 linear base, x9 physical lanes×warps, and only v1 initialized. Other
registers begin undefined. Full E32/M1 VL uses physical capacity/VLMAX rather
than output vertex count. Structured TCS SIMT merges lane-private vector state;
warp-shared scalar/FP registers are not restored by the hardware mask stack.
Scope writes are restricted to x10 LUI/ADDI scratch and checked uniform results
into x10 or discarded x0. Other scalar/FP writes and CSR/VL changes fail.
Every push, arm switch and JOIN clears x10/FP/v0/mask/parameter authority;
each arm must rematerialize scratch and recapture store parameters. Unsafe
cross-lane operations, backward/indirect control and lane-zero extraction inside
SIMT are rejected. Outside scopes, ordinary defined integer/FP32 ALU is allowed.

TCS CUSTOM1 functions 20/21/22 implement checked params/load/store; stores require
initialized params for the current mask and valid output permission. TessLevel
requires output|perPatch|tessLevel=7. The only barrier is real `0x0000000b` at a
converged PC, without outstanding alternatives or earlier peer CEASE. Runtime
memory-idle parking, PC matching and finish/cancel drain remain mandatory.
TES functions 32/33/35/36 provide static reads, component writes, exactly one
point and end before CEASE. Function 37 exposes existing invocation selectors;
GS layer 34/PrimitiveID 38, raw memory, atomics and unrelated resources fail.
Checked scalar/vector uniform function 7 requires the corresponding owned scope.

## Three-domain typed graph

`opengpu_protected_tessellation_build` captures the complete kernel metadata,
plans and resource graph before callbacks. Exact GNQ1/2/3 chooses GNT4/5/6;
there is no duplicate caller domain. Old/crossed/unknown versions, caller GPU
addresses, nonzero extension/reserved/high bits, missing/duplicate sequence or
state plans and mismatched identities fail. Physical patch layout, canonical
launch, static selections, raster/viewport fields, mapping bounds and 32-bit
patch-sequence arithmetic are checked before copying original programs.

TCS/TES programs use the independent RX validator; their uniforms use GUR3.
FS retains its original validator and GUR1. The existing complete frame helper
owns all exact resource roles. Typed relocations alone link private metadata,
program entries, uniform ranges and original patch/frame logical views. Immutable
source and writable publication ranges cannot share a physical 64-byte line,
even through different handles. Source RO/RO overlap remains allowed.
The published GNQ root accessor independently checks private frame/sequence
links and ownership; old GNJ/GNJ2 accessors reject it.

## Linux admission and retirement

The unregistered internal request has version/context, zero flags/padding/
reserved fields, input/output syncobjs, job/frame/sequence/state pointers and
sizes, exact typed plan/resource counts/strides and output fence sequence.
Its internal layout is 144 bytes; sequence/state plans are 512/248 bytes. These
sizes are not a registered UAPI or discovery record. The production wrapper gets
a verified profile **before** request dereference, usercopy, lookup or allocation.
The current getter always returns `-EOPNOTSUPP`, leaving output unchanged.

The guarded body captures the request/profile and copies all seven arrays once.
It resolves/retains every local original program/default/UBO/input/frame GEM;
imports, unmapped objects and incomplete logical views fail. Input syncobj is
captured once. `drm_exec` locks all reservations; producer fences complete
outside `submit_lock` and the hardware fence lock before source callbacks read.
Frame writes wait original readers and writers; immutable sources wait writers.
Programs/uniforms become private immutable copies; patch/frame resources retain
their typed logical permissions and original references.

A successful graph obtains native admission, prepares/publishes its private
owner and enqueues a distinct protected-tessellation job. Private successors
wait older execution; ordinary successors wait complete ownership retirement.
Both chains preserve earlier jobs when a successor is cancelled. The scheduler
retains original objects/context until retirement; output fence sequence changes
only on successful enqueue. Any build/map/publish/enqueue error retires the
whole owner. Failed idle/unmap/shootdown wedges and quarantines all originals,
context and backing without signaling retirement. There is no production
quarantine-recovery interface. The new run entry fails closed and never falls
back to a legacy opcode.

## Provider requirements still pending

The small internal profile is configuration, not hardware evidence. A future
explicit probe/record and private-owner execution route must verify together:

- Exact GNQ1/2/3↔GNT4/5/6 dialects, 512/2048-byte records, three-domain readers,
  GUR3/GUB3 stage 4/5 roots/table, 12/24 mappings, 16KiB blocks, 1024 defaults,
  256-byte alignment and existing FS/GUR1 contract.
- Actual two-CU, 16-lane/eight-warp, four-engine full-capacity provider, real launch
  ABI, checked scalar/vector uniform services, barrier/finish handling and
  cancellation/retirement ownership.
- Task-owned input/output patch RAM **and six levels initialized before access**
  or equally strict initialization proof. Existing undefined-content RAM and
  optional zero-init source/compilation alone do not qualify isolation.
- Exact private ASID/PTE permissions, immutable owner verification, GNQ route,
  context/pass/generation and command/completion identity, held-response drain
  and final translation retirement. Legacy capability bits cannot grant this.

Complete P39/P40, actual three-domain CU/full-host execution and cleanup remain
pending. No CPU result or private adapter substitutes for them. The mandatory
target stays two CUs at 640×480/60 FPS with full supported capacities. No Scala/
JVM/RTL, kernel-module build or actual TCS/TES execution was performed here.
The disabled Scala regression workflow must not be enabled or triggered.

## Final CPU results and reproduction

ASan/UBSan uniform tests compare every byte against a literal oracle at full
24×16KiB+2×1024-default capacity; they cover exact dedup, tails/reserved fields,
source/plan mutation, all copy/allocation failures and actual private MMU leases.
Five deliberately weakened encoders fail semantic assertions. Program tests
cover real launch/branch/SIMT/patch/barrier/uniform/publication rules, exact RX
capture and 12 semantic negative controls. Original Mesa/P17-emitted baseline
TCS/TES 228/149 words, full twelve-block 292/201 and conditional-uniform 340/149
pass the strict validator/RX/MMU path with exact 2/4/2/8/0 TCS layout, TES 8/8,
16 lanes/eight warps. They are original bytes, not executed shader results.

Builder tests construct all three domains with full provider capacity, 24 full
blocks/defaults and 27 frame roles; mutate all reserved/high/extension words;
inject every actual copy/resolve/allocation failure; and reject seven weakened
builders. Real timeout/shootdown quarantine, foreign aliases and healthy context
reuse compose with actual owner/MMU code.

Linux tests use an explicit CPU-only profile to invoke the real guarded body,
not the unavailable production getter. They cover 24 malformed boundaries,
producer waits/permissions, failed copy/build/map/enqueue, source/caller mutation,
original-handle close, private/ordinary cancellation chains and same-ASID reuse.
Both post-publication enqueue-failure cleanup and actual scheduler free
quarantine originals/context on failed revocation, including after all original
handles close. The pre-enqueue container retains the exact lookup reference count. Explicit fixture recovery first revokes the actual
MMU owner, then releases its quarantined container; it is not hardware drain.
Seven semantic mutants reject skipped waits, legacy job kind, wrong dependency/
credit classification, premature lookup/job reference release and missing frame write usage.
Old native/layered submit and compute dispatch/uniform/host CPU regressions pass.
All test outputs/mutant sources are temporary and removed at exit.

```sh
python3 -B scripts/test_tessellation_uniform_snapshot.py --sanitize --negative-controls
python3 -B scripts/test_tessellation_program_snapshot.py --sanitize --negative-controls
python3 -B scripts/test_protected_tessellation.py --sanitize --negative-controls
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_protected_tessellation_submit.py --negative-controls
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_submit.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_layered_submit.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_compute_texture_job_records.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_compute_uniform_flow.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_compute_host.py
python3 -B scripts/test_tessellation_uniform_abi.py
git diff --check
```

To validate original producer bytes without executing them, additionally run:

```sh
python3 -B scripts/test_tessellation_program_snapshot.py --sanitize \
  --programs /path/to/original-emitter-output \
  --tcs-layout 2 4 2 8 0 --tes-layout 8 8
```
