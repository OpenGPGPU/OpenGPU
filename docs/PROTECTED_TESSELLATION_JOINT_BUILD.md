# Protected original-vertex tessellation BUILD

`opengpu_protected_tessellation_joint_build` creates an independent private
GNQ4/5/6 graph from original vertex, control, evaluation and fragment sources.
The old GNQ1/2/3 BUILD, GNT4/5/6, submit request, accessors and getters retain
their contracts. No ioctl, queue route, hardware discovery or factory is
enabled by this helper. The mandatory product target remains two CUs at
640×480/60FPS with the full resource capacities.

The formal source is
[`abi/protected-tessellation-joint-v2.json`](abi/protected-tessellation-joint-v2.json).
The generator emits the C constants and reserved/high-bit rules. The existing
GVS1, GND1 and GNT7/8/9 schemas retain their bytes.

## Joint job and directory

The job remains 512 bytes. GNQ4/5/6 magic is `0x474e5104/05/06`; header
version is 2. The respective sequence records are exactly GNT7/8/9. Header
capacity words remain attributes/bindings/varyings 16, MRT/samples 4. Physical
execution is separately fixed by the verified profile to 2 CUs, 16 lanes,
8 warps and a 128-item prefix; engine capacity remains 1–4.

| Words | Meaning |
| --- | --- |
| 0–7 | Magic, 512, 2, 16, 16, 16, 4, 4 |
| 8–15 | Zero |
| 16–20 | Sequence base, count, exclusive LIMIT33, stride |
| 21–23 | Frame base and exclusive LIMIT33 |
| 24–26 | Context, generation, pass |
| 27 | Directory version 2 for a nonempty sequence array |
| 28–30 | Directory base and exclusive LIMIT33 |
| 31–35 | Entry count, total bytes, entry bytes 64, draw count, invocation count |
| 36–127 | Zero |

All bases are VA32; only exclusive limits can equal `2^32`. Source-form
address words must be zero. Bound views have exact logical extents: frame
2048, sequence `(count−1)*stride+2048`, directory `64+64*count`. The stride
is at least 2048 and 64-byte aligned, with zero padding between records.
Arithmetic is widened before truncation; overflow and excess high bits fail.

GVM2 is an attached R/NX directory, never an executable entry. Its unchanged
64-byte header and 64-byte entries carry the original catalog index,
independent 64-bit invocation ID, range boundaries/ordinal, original instance
and input-patch ranges, task range and GVS view. Directory counts and identity
must match the joint job. A range's directory GVS view and GNT words 509–511
are relocated from the **same actual owner segment ID** returned by
`opengpu_tessellation_vertex_outer_append_roots`. No VA is guessed, parsed
from a staged record or taken from the producer.

No-invocation jobs have zero sequence count/stride/view, directory fields,
catalog, invocation/range/sequence plans and state arrays. Their frame uses
`opengpu_native_frame_snapshot_append_empty_complete`, which preserves the
ordinary original-resource/identity/physical-alias checks while leaving the
absent GNF state view zero. No dummy FS or input buffer is constructed.
Actual invocations with zero complete patches instead keep canonical empty
FIRST|LAST entries and their GNT/GVS records. These are different contracts.

## Original graph and permissions

The complete job, frame, GNT/state arrays, original GND catalog, draw plans,
invocations, ranges, stage/state plans, resources, callbacks and verified
profile are captured before the first callback. Catalog ordinals, sequence
indices and invocation IDs remain independent. Coverage follows original
instance-major patch order without gaps or overlap; repeated catalog entries
may belong to distinct invocations, including invocation ID zero.

The original draw plan's FS program and uniforms must exactly match its state
plan; the range's GNT state must match that original draw. The complete patch
layout, task/count, identity and domain must agree. VS→TCS component maps and
TES→FS component sources are distinct and checked against their respective
VS outputs and TES exports. No CPU-evaluated vertex or patch input replaces
the original program, attributes, indices, divisors or instance metadata.

Original VS ABI1/2 is validated and copied byte-for-byte RX/no-W, including its
nonzero entry suffix. VS and FS retain GUR1. Control/evaluation have distinct
GUR3 roots with all 12 blocks and 1024 default components per stage, independent
24-mapping tables, exact-window sharing and stage permissions. TCS/TES program
v3 requires independently verified language info; TES additionally requires
task-owned dynamic reads from its captured patch layout, not the static
61-word transport. FS8/9 requires primitive-input permission independently of
GS; FS9 additionally needs sample permission. Metadata/GUR/GVS/GND is R/NX;
original inputs are R/NX; VS and fragment scratch are RW/NX.

The new joint GFS check follows the current primitive-only fragment reader's
metadata conditions: payload/late-test versions, shared/indexed/advanced blend
pairing and controls, exact engine count, active color types/mask, sample mode,
centroid mask and the full 128-item ABI8/9 scratch prefix. Header engine
capacity is always four; runtime engine count is a separate field. GNT raster,
GFS and frame sample modes must match. Each active FS color type must match the
original enabled attachment's generated format kind, independently of advanced
blend restrictions. Old GFS and protected BUILD APIs are unchanged.

| Stage | Checked or constructed values |
| --- | --- |
| Captured source preflight | Full canonical words, identity, zero source PC/scratch/GUR/image/resource addresses, legal GFS metadata, scratch stride, GNT/GFS/frame samples and attachment types |
| Actual RX/GUR/frame helpers | Original instruction validation/copy, bound GUR1/GUR3 publication, exact scratch allocation/logical views, complete frame roles and physical aliases |
| Joint prepare gate | Real MMU placement, root/owner/frame/directory identity and exact bound views, live leases/leaves, every segment/resource envelope outside fixed LDS |
| Published launch request | Exact prepared root, live leaves rechecked under the submit lock, checked domain and identity, logical job extent, job allocation and owner envelope |
| Future hardware route | Full GNQ/directory/GNT/GVS/GND admission and actual three-domain shader/frame execution; pending |

`TypedReplayTileImagePipeline.checkState` receives the exact engine count,
varying count and sample mode checked against the original GNT/state/frame;
the frame helper relocates each state into its exact per-engine stride window.
The fragment reader's GUR/PC/scratch address conditions become meaningful only
after the real helpers and prepare gate. `TypedTileRenderPipeline` also needs
matching identity, samples and enabled-attachment kinds; these static links are
checked here. Primitive ordering, per-tile/scissor coverage, actual shader
control/output masks, load/store/resolve execution and retirement remain real
hardware checks, not CPU-predicted shader results.


All original physical intervals are checked together, rounded to 64-byte
cache lines. The set includes every catalog's code/defaults/UBOs, all sixteen
attribute bindings, indices, explicit indirect provenance, VS scratch, all
control/evaluation/fragment sources and frame roles. VS scratch is a writer:
cross-draw/stage immutable aliases and any frame-role overlap fail even across
different handles. Only an identical physical VS-scratch role window may be
reused under the verified serial source-retirement contract. Partial writable
overlap is rejected. Read-only views may share a GEM without merging distinct
offsets or lengths. Original frame roles retain their own complete alias rules.

Unconnected indirect, sampler, texture-root and reuse paths fail closed while
retaining their source provenance fields and their place in the full target.
The CPU does not parse indirect commands. The existing query-false PATCHES
restart policy only canonicalizes the private GND restart flag.

The caller must retain all originals and context, hold producer reservations,
wait producers outside `submit_lock`, and exclude source CPU writes during
capture. Output stays unchanged on failure. Any partial BUILD/prepare/publish
failure requires retirement of the entire owner; failed idle/unmap/shootdown
retains the owner and original references for quarantine. This BUILD does not
perform Linux waits, acquire those references or enqueue hardware.

## Preparation and execution boundary

The new route must call `opengpu_protected_tessellation_joint_prepare` before
publication. It first runs the actual private owner prepare, then a status-only
kernel check of the prepared GNQ4/5/6 root and its owner, frame and GVM2 header
cross-links. All segment allocation envelopes and resource reservation envelopes
(including gaps) must avoid fixed LDS `[0x10000000,0x10004000)`. Exact adjacency
at either end is allowed. The check verifies actual live mappings and leases;
it does not accept a caller's claim that LDS was reserved. Any rejection closes
publication and requires whole-owner retirement/quarantine.

The ordinary allocator is unchanged and does not automatically skip LDS.
Correct placement selection in a future production route remains necessary;
the new gate rejects an unsafe placement instead of publishing it. Original
frame/scratch roles obtain distinct actual MMU reservations, while source
physical-alias checks reject conflicting underlying pages. Prepared fixture
checks independently verify the full fragment scratch prefixes, allocation
bounds, pairwise separation, arena separation and R/W/NX leaves.

The kernel-only `opengpu_private_snapshot_joint_request_locked` accessor
returns launch parameters for the exact published root that passed the joint
prepare gate. It rechecks live mappings and leases under `submit_lock` against
the original device and context. The root's logical extent and actual mapped
allocation are separate, and the owner envelope spans the real private
segments and reservations. Exclusive limits retain all 33 bits. The original
owner retains authority over the referenced mappings through retirement.
The hardware queue and provider discovery remain separate integration gates.


No production getter supplies the joint profile. An unavailable or incomplete
profile fails before source copy, resolution or owner append. Compiler metadata
and GSS1 cannot grant it. Existing native/protected job accessors reject the
new job magic.

The hardware start must carry the verified owner scope and expected frame
identity from the published request. Authorization still follows the immutable
references and live MMU mappings and leases inside the scope. Empty
no-state frames require a real clear/store/resolve path: the old replay's
nonempty-state admission cannot be bypassed with a dummy shader. Arbitrary
64-bit invocation IDs remain original BUILD-checked identities; a bounded
stream reader checks range boundaries, not unbounded global ID uniqueness.

A future real route must consume GNQ → directory → GNT → GVS/GND capture →
the matching three-domain typed range/image parent. The directory supplies
expected draw/sequence indices; GVS cannot authorize its own expected values.
Reader ownership changes only after actual completion consumption and pending
memory drain. Held offers survive cancellation until accepted or actual frame
stop. Patch advance requires actual source load/reply/seal, TCS/TES retirement,
typed task report and task drain. Final end, actual frame completion and full
fabric drain are all required. These reader/parent/factory/runtime and actual
CU/DUT qualifications remain pending; a constructed graph proves none of them.

## CPU validation and limits

Apple Clang 16 and immutable Linux GCC 13.3 passed the actual owner/MMU fixture
for all three domains and all 16 source-copy semantic controls. Each control
must fail at its named fixture case/diagnostic, including permission, physical
alias, LIMIT33, RX/NX, same-root directory, immutable capture, GFS semantics,
frame/GNT/GFS sample/type links and the prepared LDS-placement gate. Tests also
cover coupled 2048-word boundaries, exact `2^32` limits, repeated catalog
invocations, both kinds of empty work, every copy/resolve/allocation/segment/
relocation failure, quarantine and healthy retirement/reuse. These are bounded
CPU ownership/metadata tests; literal programs do not supply shader results.

The published request passed all three domains in the actual owner/MMU
fixture, including an owner limit at exactly 4 GiB. The fixture rejects
pre-publication calls, a root published without the joint gate, foreign
context/root inputs, changed write permissions and quarantined owners while
preserving the caller's request on every rejection. The fixture passed
under AddressSanitizer and UndefinedBehaviorSanitizer.

The old protected builder's seven controls, GVM2's ten controls, the actual
private-owner/frame/enqueue fixtures and original native graph regression
passed with both compilers. The new runner's final Clang run checked all 18
actual `-MMD` translation units and 103 distinct dependencies against pre-bound
source/generated inputs. Seven schema/runner tests cover the exact ABI,
unbound/changed dependencies, a live descendant after leader exit, and input
preservation when consumer exit cannot be proved.

All compiler/fixture groups stayed within 180 seconds and 512 MiB sampled
aggregate RSS. Observed maxima across related validation were 375968 KiB for
Clang and 370176 KiB for GCC; the final new-joint GCC matrix took 248.21 seconds
in total under the separate 900-second whole-run deadline. Actual Linux memory
and swap were both capped at 512 MiB with one CPU and no network. Source guards
passed; ended private outputs and containers were removed.

On BUILD delivery `4c7b60138` based on merged #827, the complete sanitized host script
passed in 767.27 seconds under a 1500-second/768-MiB sampled aggregate guard.
Peak sampled aggregate RSS was 531920 KiB. All 3128 tracked non-document source
inputs and HEAD were stable. Results include 152 pytest cases, 21 strict
producer checks and 757 unittest executions across 89 summaries; these are
executions, not a deduplicated test count. Six existing environment skips
remain: four unavailable Mesa environments, the Clang OpenGPU target and the
optional FlashSim bound check. Python 3.14.7, pytest 9.1.1 and Apple Clang 16
were used. The existing strict producer scanner and macro exemptions were
unchanged. Ended private test outputs were removed after consumer checks.

The accurate #828 source `1dba3adbd` also passed a complete ARM64 Linux
7.2.0 module Kbuild with GCC 15.2.0 and GNU Make 4.4.1, `-j1`: all 29 core
objects, `gpu_drv.mod.o`, `.module-common.o`, MODPOST and `gpu_drv.ko` completed
without warnings/errors. The ELF64 AArch64 relocatable module had vermagic
`7.2.0 SMP preempt mod_unload aarch64` and the actual joint BUILD, prepare and
`opengpu_private_snapshot_check_prepared_joint` symbols. Original/copy driver
inputs (157 files) and shared kernel/tools guards passed. No exact elapsed/peak value was retained; the 180-second/512-MiB aggregate
bounds applied. The module was never loaded. That result covers #828, not
subsequent changes to `opengpu_native_frame.c`; see the separate
[original linked source-to-BUILD proof](NATIVE_JOINT_BUILD_SOURCE.md).

This BUILD block did not run actual DUT/frame qualification. The accompanying default-off GNQ/GVM metadata readers passed
separate bounded ABI/component RTL and test-catalog checks after their
source replay; see
[TESSELLATION_JOINT_METADATA_CAPTURE.md](TESSELLATION_JOINT_METADATA_CAPTURE.md).
The Scala regression workflow remains disabled and was not triggered.
Actual GNT/GVS/GND capture and parent ownership, correct production VA placement,
no-state clear execution, a verified factory/getter/runtime and the mandatory
2-CU 640×480/60FPS product qualification remain pending.

## Reproduction

```sh
PATH=/opt/homebrew/bin:$PATH PYTHONDONTWRITEBYTECODE=1 \
  OPENGPU_TEST_SANITIZERS=1 ASAN_OPTIONS=detect_leaks=0 bash scripts/test_scripts.sh
python3 -B scripts/generate_protected_tessellation_joint_abi.py --check
python3 -B scripts/test_protected_tessellation_joint_abi.py
python3 -B scripts/test_protected_tessellation_joint.py --sanitize --negative-controls
python3 -B scripts/test_protected_tessellation.py --sanitize --negative-controls
python3 -B scripts/test_tessellation_vertex_plan.py --sanitize --negative-controls
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_private_snapshot.py
python3 -B scripts/test_native_job_snapshot.py --sanitize
```

The joint runner bounds each owned compiler/fixture process group to one
serial execution, 180 seconds and 512 MiB sampled aggregate RSS, with private
temporary outputs and before/after consumed-source guards. Every process group
is stopped and independently proved dead even after leader exit. An unknown
cleanup observation raises `CleanupIncomplete` and preserves the complete
workspace and named log. Each actual non-system MMD dependency must match a
pre-bound source identity or an exact pre-hashed private generated input. Linux GCC validation
uses the cached immutable image
`sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72`,
read-only source, private writable artifacts, no network, one CPU, and
`--memory=512m --memory-swap=512m`. A later run with different limits must
report its actual budget and preserve earlier bounded results.
