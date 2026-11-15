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
must each fit 2048 words. Thus 32×64+0 and 32×60+120 pass; 32×61+120 fails.
Version 1 TES validates stage 5 and only current vertex-zero static transport:
three TessCoord words plus at most 61 selected patch words, one emitted point.
Version 1 requires all new dynamic-layout/permission fields to be zero and
rejects the new read instruction. The existing GNT4/5/6 builder still derives
only version 1; all three domains reject dynamic-read programs.

Internal program version 2 permits TES-only CUSTOM1 opcode `0x2b`, function 33
(bits 31:26), funct3=0. It requires an independent `owned_patch_reads` permission,
zero static `patch_words`, and captured `tes_patch_layout` with all five TCS
input/output/patch dimensions. That layout has the same 32-vertex, 64-component,
120-patch-component and coupled 2048-word bounds; its output component count is
independent of the top-level TES export count. CUSTOM0 function 32 then exposes
only TessCoord 0..2. CUSTOM0 function 33 retains its separate export permission.
The program remains limited to 512 words and exactly one emitted point.

Both vertex and descriptor operands must be defined full-width vectors. Known
constants reject input-bank access, high bits, out-of-layout vertices/components,
nonzero per-patch/level vertices and malformed level flags. Defined unknown
indices require the actual retained task's owned service to check every selected
lane at execution. Reads require full VL before emit/end; masked reads require
v0 plus defined old destination, and cannot write v0. Unmasked reads may define
v0 without inventing known mask bits. Source metadata cannot grant this permission.

Version 2 validation/RX capture does not enable a producer, serialized GNT,
protected submission or hardware provider. Its hardware-owned binding, dynamic
service and full CU/host execution remain separate P40 requirements. P39 still
requires original VS-to-patch execution. CPU-produced patch data closes neither
requirement.

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
TES CUSTOM0 functions 32/33/35/36 provide coordinate/static reads, component
writes, exactly one point and end before CEASE. Function 37 exposes existing invocation selectors;
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
capture and 24 semantic negative controls (12 original, 12 dynamic-read).
Version 2 fixtures cover version/stage/permission misuse, each forbidden version-1
extension field, full-width index aliases, independent patch/export dimensions,
zero-component levels, coupled capacity, definedness, mask/old-vd/v0 rules,
post-publication reads and capture-once original/profile mutation. Actual owner/
MMU tests preserve opaque prefix/suffix and nonzero entry bytes in RX/no-W pages;
errors leave output unchanged and retire the owner. Original Mesa/P17-emitted baseline
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

At delivery source commit `41b51b8a1217213de55198eaca7aa56142551f11`, all five
P16 suites passed Clang and actual Ubuntu GCC 13.3.0 with ASan/UBSan and all
53 semantic negative controls: uniform 5, program 24, BUILD 7, Linux submit 7
and GVS/source 10. This includes the version-2 validator and old GNT4/5/6
rejection of that instruction. The immutable ARM64 image below uses 512MiB
total memory+swap, one CPU, no network and read-only source. The complete GCC
run finished in 364.38 seconds; all 758 driver/script/ABI inputs were unchanged
before/after the run. `-Wall -Wextra
-Werror` remains enabled. Containers and private outputs were removed at
termination. No Mesa/JVM/model build or GPU execution was involved.

The delivery also passes the complete host lane at source commit
`8139bb727771b3756993069885bc606a22ddb6ae` with sanitizers enabled
(`OPENGPU_TEST_SANITIZERS=1 PYTHONDONTWRITEBYTECODE=1 bash scripts/test_scripts.sh`).
It completed in 709.82 seconds; 727 driver/script/ABI file inputs were unchanged.
The executor checks bind the fixture's exact default macro include to the real
protected C body and recursive headers. Changed/unknown macros, overrides,
missing sources, external aliases and omitted qualification dependencies fail;
49 targeted CPU checks and the complete host lane pass. Mesa-dependent and
unavailable OpenGPU-target checks retain their declared skips. This is CPU
validation, not a model or shader-execution result. Ended outputs were removed.

```sh
P16_GCC_OUT=$(mktemp -d "$PWD/.artifacts/p16-gcc-XXXXXX")
P16_GCC_NAME="p16-gcc-$$"
mkdir "$P16_GCC_OUT/tmp"
docker run --rm --init --name "$P16_GCC_NAME" \
  --memory=512m --memory-swap=512m --cpus=1 --network=none --read-only \
  -v "$PWD:$PWD:ro" -v "$P16_GCC_OUT:$PWD/.artifacts:rw" -w "$PWD" \
  -e TMPDIR="$PWD/.artifacts/tmp" -e PYTHONDONTWRITEBYTECODE=1 \
  -e CC=gcc -e CXX=g++ -e OPENGPU_TEST_SANITIZERS=1 \
  -e ASAN_OPTIONS=detect_leaks=0:quarantine_size_mb=16 --entrypoint python3 -i \
  sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 -B - <<'PY'
import subprocess, sys
for name in ("test_tessellation_uniform_snapshot.py", "test_tessellation_program_snapshot.py",
             "test_protected_tessellation.py", "test_protected_tessellation_submit.py",
             "test_tessellation_vertex_source.py"):
    subprocess.run([sys.executable, "-B", "scripts/" + name, "--negative-controls"], check=True)
PY
# Confirm the container has ended and no other consumer needs this private output.
test -z "$(docker ps -a --filter "name=^/$P16_GCC_NAME$" --format '{{.Names}}')" && \
  rm -r -- "$P16_GCC_OUT"
```

To validate original producer bytes without executing them, additionally run:

```sh
python3 -B scripts/test_tessellation_program_snapshot.py --sanitize \
  --programs /path/to/original-emitter-output \
  --tcs-layout 2 4 2 8 0 --tes-layout 8 8
```

For a version-2 TES producer, pass its separately captured patch layout; the
TCS program still uses version 1. This CLI selects an explicit CPU test profile,
not a production verified-owner profile:

```sh
python3 -B scripts/test_tessellation_program_snapshot.py --sanitize \
  --programs /path/to/original-dynamic-emitter-output \
  --tcs-layout 2 4 2 8 0 --tes-layout 0 8 --tes-program-version 2 \
  --tes-patch-layout 2 4 2 8 0
```
