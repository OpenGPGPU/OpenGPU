# Protected original-vertex tessellation BUILD

`opengpu_protected_tessellation_joint_build` creates an independent private
GNQ4/5/6 graph from original vertex, control, evaluation and fragment sources.
The old GNQ1/2/3 BUILD, GNT4/5/6, submit request, accessors and getters retain
their contracts. The opt-in joint host and the kernel's private MMIO path
share the versioned provider and completion records below. The mandatory
product target remains two CUs at 640×480/60FPS with the full resource
capacities.

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

On the verified joint profile, private allocations skip the 16 KiB LDS
window at `0x10000000`. The prepare gate also checks the actual mapped
spans before publication. Original frame and scratch roles obtain distinct
MMU reservations. Source alias checks reject conflicting physical pages. Prepared fixture
checks independently verify the full fragment scratch prefixes, allocation
bounds, pairwise separation, arena separation and R/W/NX leaves.

The kernel-only `opengpu_private_snapshot_joint_request_locked` accessor
returns launch parameters for the exact published root that passed the joint
prepare gate. It rechecks live mappings and leases under `submit_lock` against
the original device and context. The root's logical extent and actual mapped
allocation are separate, and the owner envelope spans the real private
segments and reservations. Exclusive limits retain all 33 bits. The original
owner retains authority over the referenced mappings through retirement.

### Private host command and completion

GCR1 bit 22 guarantees the four read-only GJP1 words at `0x550–0x55c`.
They report magic `0x474a5001`, provider flags, the three-domain mask and
two-CU/16-lane/eight-warp shape, and the number of image engines. Flags
0–6 certify the owned vertex source, initialized patch state, owned patch
reads, tessellation information, original primitive inputs, directory route
and whole-owner retirement. Flags 7–10 report scalar FP, advanced blending,
sample inputs and transform feedback. The driver checks the record against
the verified native source profile and the mapped aperture before enabling
the private submission path. Legacy profiles leave bit 22 clear.

The locked kernel encoder packs the published request into unified opcode
15. The tagged pattern selects the joint request when the AXI host has the
joint port enabled. The physical joint renderer validates the decoded
request before taking its bank or issuing a fetch.

| Command register | Joint value |
| --- | --- |
| `SOURCE`, `BYTES` | Page-aligned GNQ base and exactly 512 logical bytes |
| `DESTINATION`, `WIDTH` | Owner base and low 32 bits of its exclusive limit |
| `PATTERN` | `0x474a0100` with domain in bits 2:1 and owner limit bit 32 in bit 0 |
| `HEIGHT`, `SOURCE_STRIDE`, `DEST_STRIDE` | Context, pass and generation |
| `GRID_X`, `GRID_Y` | Program and cookie of the separately authorized feedback scope |

The command reconstructs the exact one-page root allocation and checks the
root extent and scope before execution. DMA and event dependency flags are
rejected for this command. The joint and original tessellation queues can
share the opcode using their distinct command patterns.

GNZ2 detail words 0–11 use the existing completion area at
`0x180–0x1ac`. They carry the magic `0x474e5a02`, status bits 2:0, fault
stage bits 5:3, clear-only bit 6, fault address, context, pass, generation,
captured entries, retired ranges, retired patches and published primitives.
Words 12–15 at `0x540–0x54c` carry stored and resolved byte counts. Every
64-bit field is low word first. Metadata bytes read use the unified completion
byte registers. The joint report occupies one software completion slot and
all words stay stable until pop. The extended words read zero for legacy
results in the joint-enabled host.

The kernel checks the published owner again under `submit_lock`, invalidates
its private pages, stages its translated VM and attaches report storage to
the execution fence before ringing the doorbell. IRQ and polling share
`fence_lock` through the GNZ2 snapshot and pop. The snapshot validates the
command ID, opcode, status and the expected context/pass/generation before
publishing counters. A valid cancellation reports `-ECANCELED` and a valid
write fault preserves its address in the snapshot.

The FlashSim host AXI fixture passed profile discovery and write rejection,
the tagged command at a 4 GiB exclusive limit, queue backpressure and VM
exclusion, all sixteen completion words and legacy routing. It uses peers
at the joint execution ports. ASan/UBSan passed the published-owner enqueue
checks for all three domains and the IRQ/poll/abort races with modeled MMIO.
The full ARM64 Linux 7.2 module compiled and linked with GCC 15.2.

```sh
sbt -Dopengpu.simBackend=flashsim -Dopengpu.flashsimPython=python3.13 \
  'testOnly opengpu.system.TessellationHostAxiCommandSpec opengpu.graphics.TileBinHostCommandPortSpec'
python3 scripts/test_protected_tessellation_joint.py --sanitize
OPENGPU_TEST_SANITIZERS=1 python3 scripts/test_unified_completion_race.py
gmake -C "$OPENGPU_KERNEL_BUILD" -j1 ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- \
  M="$OPENGPU_DRIVER_SOURCE" MO="$OPENGPU_DRIVER_BUILD" modules
```

Use a fresh private copy of the current driver for `OPENGPU_DRIVER_SOURCE`
and a separate output directory for `OPENGPU_DRIVER_BUILD`. The private
hardware profile comes from the verified GCR1/GJP1 records.

### Protected DRM submission

`DRM_IOCTL_OPENGPU_SUBMIT_TESSELLATION_JOINT` uses a 152-byte version 1
request. It takes the unbound GNQ, GNF, GNT and original GND/GFS records,
typed draw, invocation, range and shader plans, and the frame's original
GEM handles. The kernel probes the verified joint profile before copying
variable-length arrays. It checks the canonical GNQ root against the
request's context and counts before reading those arrays.

The kernel waits the input sync object and the original GEM producers
before reading linked programs and uniforms. It places and publishes the
complete owner under the native admission lock. The scheduler dispatches the
private MMIO command, retains the GNZ2 storage through the execution fence,
and holds original GEM references until the owner retires. The ioctl returns
the execution fence sequence and can publish it to an output sync object.

The compiler's joint context reads the frozen GJP1 and GNC words through
dedicated `GET_PARAM` ranges. The existing native query stays reserved.
After `opengpu_native_joint_context_enable` succeeds, the linked Mesa program
and BUILD bundle supply the records, original GEMs and optional sync objects
directly to `opengpu_native_joint_build_source_submit`.

The ASan/UBSan composition exercised the registered ioctl handler with one
original draw, a 64-bit invocation ID and six patches for each of isoline,
triangle and quad. It checked producer waits, rollback after an enqueue
failure, the actual published MMU root and retirement after closing the
original code handle. The fixture models the final hardware kick.

The MMIO composition calls the production joint scheduler, opcode encoder
and IRQ drain. The CPU control peer checks the tagged triangle command, the
original Sv32 owner pages and all 16 held GNZ2 words before completion POP.
It also checks the high words of the byte counters and rejects a different
command or owner identity. The replay executor can probe a dedicated joint
host with `--probe-joint-profile` and consume its protected captures with
`--active-joint-owner-series`. That path rechecks discovery from the DUT,
waits for physical memory drain and then applies the original driver's
recorded POP and cleanup. Both joint modes support the 4×4 functional AXI
interface. Product qualification requires the characterized SRAM presence
signal and hardware performance counters.

The Mesa 22.3.6 Linux producer linked the baseline and a VS mutation for
each domain and submitted four additional baseline triangle draws. Each of
the ten DRM submissions retained the four linked programs after the original
handles closed and published a separate 4×4 writable image and protected Sv32
owner. The two VS inputs occupy distinct linked slots. The source checks
covered the original index data, base vertex of −1, two instances with base
instance 7 and divisor 2, and a fixed-index marker that stays a regular index
for ES PATCHES. The prepared GND clears the global restart enable for this
primitive mode.

Inside the pinned Linux environment from the
[linked BUILD reproduction](NATIVE_JOINT_BUILD_SOURCE.md#reproduction), use
an empty private `SESSION` and the independent vertex/fragment emitter source.

```sh
python3 -B scripts/build_native_joint_live_producer.py --sanitize \
  --build "$OUT/build" --legacy-source "$OUT/reference-uniform.c"
"$OUT/build/native-joint" userspace/tests/tess_joint_live_essl --cpu-preflight "$SESSION"
```

The live controller verified the original published graph against the
captured Sv32 page tables, including the indexed source and retained RX
bytes. All ten records passed on one model with two physical CUs, each with
16 lanes and 8 warps, and one 4×4 image bank. The controller checked exact RGBA32UI output for the
six domain and VS combinations and both healthy reuse draws, plus untouched
images for the two failure cases. It verified the allocation guards, owner
and context revocations, and the driver's cleanup acknowledgment.

FlashSim injected a physical AXI write fault at the first line of the original
triangle image. The driver IRQ path retired with `-EIO` and GNZ2 reported
status 3, stage 6, the exact fault address and zero acknowledged store bytes.
The next draw produced the expected image on the same DUT and RAM.

For cancellation, FlashSim held a physical AXI response for the original
VS index read. The Linux driver issued the command reset and the DUT kept
reset and VM busy asserted for at least 32 service cycles while the response
was held. After the response drained, the DUT preserved both SATP values
and the previous raw completion registers, cleared completion validity,
returned zero for GNZ2 detail and kept AXI idle. The Linux IRQ path retired with
`-ECANCELED`, and the next original draw produced the expected image and
completed cleanup on the same DUT and RAM.

The ten-record run took 67.69 seconds with 334 MiB peak charged memory.
On the same 4×4 model, cold FlashSim generation took 1325.39 seconds at
5.07 GiB peak charged memory. CIRCT import took 56.52 seconds, Python
flattening took 296.40 seconds and C++ emission took 176.85 seconds.
The initial two-worker native build took 1205.76 seconds at 9.65 GiB peak.
The cancellation rerun reused all 89 verified FlashSim objects and rebuilt
the executor in 28.22 seconds with 2.48 GiB peak charged memory.

For the routine 4×4 configuration, set `FIRTOOL` to FIRTool 1.158 and
`FLASHSIM` to checkout `e8039c6768` with its configured CIRCT installation.
Use Python 3.13 to prepare the assertion-enabled model. The functional host
uses the production 4 KiB bin pages required by the Linux GNF encoder.

```sh
mkdir -p .artifacts
OUT=$(mktemp -d "$PWD/.artifacts/joint-live.XXXXXX")
mkdir "$OUT/rtl"
sbt -batch -J-Xmx3g -J-XX:ActiveProcessorCount=2 \
  'set Test / sources ~= (_.filter(p => Set("GraphicsTestResolution.scala", "GpuSim.scala", "FlashSimBackend.scala", "GpuHostTestSupport.scala", "QualifyNativeFourEngineSramHost.scala", "PrepareOwnerResourceSampleHost.scala", "PrepareOwnerResourceImageFloatHost.scala", "TessellationNativeHostElaborationSpec.scala").contains(p.getName)))' \
  "set Test / classDirectory := file(\"$OUT/classes\")" \
  "set Test / compileAnalysisFile := file(\"$OUT/analysis.zip\")" \
  "Test / runMain opengpu.system.PrepareProtectedJointOwnerHost $OUT/host.fir"
"$FIRTOOL" "$OUT/host.fir" -format=fir --split-verilog "-o=$OUT/rtl" \
  --lowering-options=disallowLocalVariables,disallowPackedArrays
(
  cd "$FLASHSIM"
  FLASHSIM_PREFLATTEN_WIRES=1 FLASHSIM_MODULE_JOBS=2 \
    python3.13 -B -c 'from pathlib import Path; import sys; from flashsim.compile import compile_file; compile_file(Path(sys.argv[1]),Path(sys.argv[2]),frontend="circt",assertions=True,split=64)' \
    "$OUT/rtl/GpuHostSystemAxi.sv" "$OUT/dut/dut.h"
  python3.13 -B -m flashsim.reshard create --source "$OUT/dut" \
    --output "$OUT/resharded" --max-mib 32 --outline-invalidation-mib 24
  python3.13 -B -m flashsim.reshard verify --source "$OUT/dut" --output "$OUT/resharded"
)
python3.13 -B scripts/run_owner_bounded.py --memory-mib 11264 --timeout-seconds 2400 -- \
  python3.13 -B scripts/qualify_native_joint_live.py --build-only --jobs 2 \
  --dut-dir "$OUT/resharded" --executor-build "$OUT/executor"
python3.13 -B scripts/run_owner_bounded.py --memory-mib 4096 --timeout-seconds 2700 -- \
  python3.13 -B scripts/qualify_native_joint_live.py --timeout 600 \
  --dut-dir "$OUT/resharded" --executor-build "$OUT/executor"
```

`NativeAlignmentHostProfile.protectedTessellationTarget` selects the joint
host, checked tessellation uniforms and protected sources at 640×480. Its
discovery table includes GJP1 and the same factory elaborates the 4×4 test
configuration. Full-capacity and product qualification still require the
640×480 host with characterized memory, hardware performance counters and
timing closure for 60 FPS.

## Four-engine target replay

The complete protected VGA factory reported 92 discovery words, two 16-lane
eight-warp CUs and four engines. All ten original Mesa submissions passed on
that factory with 4×4 frames, including the three domains, VS mutation,
physical store fault, held original index read across reset, both healthy
reuses and the actual driver's retirement and cleanup acknowledgment. The
assertion-enabled FlashSim replay took 206.86 seconds with 1.58 GiB peak
charged memory. Full-frame 640×480 execution, characterized memory and
60 FPS timing remain pending.

The RTL inputs matched GPU main at `82d3574f25`. The full model used FlashSim
`38e050ebe2` and contained 14,670 assertions. With two CIRCT threads and the
bounded-deadness overlay, import took 133.62 seconds. The stock import was
still running when stopped at 343.17 seconds. Whole C++ generation through
emission took 3,972.86 seconds at 14.39 GiB peak charged memory. Verified
resharding brought the largest C++ unit from 872.35 to 74.52 MiB.

On ARM64 macOS the 351 objects linked into a separate model library beside
the small executable. This layout keeps the large model clear of the
executable's shared-cache address conflict. The build receipt binds both
files and the replay checks both hashes. With both build workers set to
`-O0` for the model shards, the final cached build reused all 351 objects,
took 116.74 seconds and peaked at 7.34 GiB. The build used the installed
classic Apple linker for the large model.

Use a new `OUT` and add `--full-factory` to the Scala `runMain` command
above. Initialize the pinned `depends/asap7_sram_0p0` submodule and set
`CIRCT_OVERLAY` to the directory with the verified compiler and libraries
from [the CIRCT prerequisite](OWNER_FULL_FACTORY_REPRODUCTION.md#flashsim-compiler-prerequisite).
Set `FLASHSIM` to checkout `38e050ebe2`.

```sh
GPU="$PWD"
python3.13 -B scripts/run_owner_bounded.py --memory-mib 5120 --timeout-seconds 900 -- \
  "$FIRTOOL" "$OUT/host.fir" -format=fir --mlir-disable-threading --split-verilog \
  "-o=$OUT/rtl" --lowering-options=disallowLocalVariables,disallowPackedArrays
for width in 32 64; do
  cat "depends/asap7_sram_0p0/generated/verilog/srambank_64x4x${width}_6t122.v"
  printf '\n'
done > "$OUT/rtl/NativeFourEngineSramModels.sv"
(
  cd "$FLASHSIM"
  export CIRCT_VERILOG="$CIRCT_OVERLAY/circt-verilog" DYLD_LIBRARY_PATH="$CIRCT_OVERLAY"
  export FLASHSIM_CIRCT_THREADS=2 FLASHSIM_CIRCT_SERIAL=0 FLASHSIM_CIRCT_LARGEST_FIRST=1
  export FLASHSIM_MODULE_JOBS=2 FLASHSIM_PREFLATTEN_WIRES=1 FLASHSIM_GC_POLICY=defer
  python3.13 -B "$GPU/scripts/run_owner_bounded.py" --memory-mib 16384 --timeout-seconds 7200 -- \
    python3.13 -B -c 'from pathlib import Path; import sys; from flashsim.compile import compile_file; compile_file(Path(sys.argv[1]),Path(sys.argv[2]),frontend="circt",assertions=True,split=128)' \
    "$OUT/rtl/GpuHostSystemAxi.sv" "$OUT/dut/dut.h"
  python3.13 -B -m flashsim.reshard create --source "$OUT/dut" --output "$OUT/resharded" \
    --max-mib 32 --commit-parts 32 --outline-invalidation-mib 24
  python3.13 -B -m flashsim.reshard verify --source "$OUT/dut" --output "$OUT/resharded"
)
python3.13 -B scripts/run_owner_bounded.py --memory-mib 12288 --timeout-seconds 7200 -- \
  python3.13 -B scripts/qualify_native_joint_live.py --build-only --jobs 2 --shard-opt=-O0 \
  --shared-model --link-flag=-Wl,-ld_classic --link-flag=-isysroot \
  --link-flag="$(xcrun --show-sdk-path)" --dut-dir "$OUT/resharded" --executor-build "$OUT/executor"
python3.13 -B scripts/run_owner_bounded.py --memory-mib 4096 --timeout-seconds 3600 -- \
  python3.13 -B scripts/qualify_native_joint_live.py --timeout 900 \
  --dut-dir "$OUT/resharded" --executor-build "$OUT/executor"
```

```sh
OPENGPU_TEST_SANITIZERS=1 python3 scripts/test_protected_tessellation_submit.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_mmio_capture.py --executor-root .
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_owner.py
python3 -B scripts/test_flashsim_native_adapter.py
python3 -B scripts/test_native_joint_live_oracle.py
```

The hardware start must carry the verified owner scope and expected frame
identity from the published request. Authorization still follows the immutable
references and live MMU mappings and leases inside the scope. Empty
no-state frames require a real clear/store/resolve path: the old replay's
nonempty-state admission cannot be bypassed with a dummy shader. Arbitrary
64-bit invocation IDs remain original BUILD-checked identities; a bounded
stream reader checks range boundaries, not unbounded global ID uniqueness.

The joint hardware route consumes GNQ → directory → GNT → GVS/GND capture →
the matching three-domain typed range/image parent. The directory supplies
expected draw/sequence indices; GVS cannot authorize its own expected values.
Reader ownership changes only after actual completion consumption and pending
memory drain. Held offers survive cancellation until accepted or actual frame
stop. Patch advance requires actual source load/reply/seal, TCS/TES retirement,
typed task report and task drain. Final end, actual frame completion and full
fabric drain are all required.

## Earlier CPU validation and limits

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

The accompanying default-off GNQ/GVM metadata readers passed separate bounded
ABI/component RTL and test-catalog checks after their source replay. See
[TESSELLATION_JOINT_METADATA_CAPTURE.md](TESSELLATION_JOINT_METADATA_CAPTURE.md).
The Scala regression workflow remains disabled and was not triggered.

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
