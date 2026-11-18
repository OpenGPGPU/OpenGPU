# Bounded Linux indirect resource submission

`DRM_IOCTL_OPENGPU_COMPUTE_INDIRECT_RESOURCES`, version1, reuses the existing
counter-source/uniform/resource submission contract. It adds a local argument
GEM handle/offset and three nonzero maximum group counts. The nested launch's
direct grid must be zero; its local dimensions, resource metadata, versions,
reserved fields and shader sandbox retain their existing checks. Existing
direct ioctls are unchanged. Query
`OPENGPU_DRIVER_FEATURE_COMPUTE_INDIRECT_RESOURCES` before using this interface.

## Version 2: one snapshot for dispatch and shader counts

Query `OPENGPU_DRIVER_FEATURE_COMPUTE_INDIRECT_RESOURCES_V2` (bit44) before
selecting `OPENGPU_COMPUTE_INDIRECT_VERSION2`. The command's wire size and field
offsets remain unchanged; the original `pad[2]` spelling remains available to
version1 callers. Version2 uses `v2.flags` and requires `v2.reserved == 0`.
Unknown versions and flag bits remain rejected. Version1 retains its original
counter requirement and zero-padding checks.

An all-zero nested counter source omits the counter service in version2. A
present source retains its existing original-GEM, count, permission and
dependency checks. Each storage, image, texture and uniform domain remains
independently usable; the complete existing graph remains supported with or
without a counter. Bit44 requires discovered GCI1, protected VM and unified
commands; an omitted counter does not require counter transport. The existing
version1 feature bit26 retains its original discovery predicate.

With `v2.flags == 0`, the shader uses its original kernarg. With
`OPENGPU_COMPUTE_INDIRECT_KERNARG_COUNTS`, the shader's complete kernarg is the
same immutable twelve-byte copy consumed by GCI1. Its x1 points to the snapshot
base plus the preserved source-page offset, and shader admission is bounded to
twelve bytes. The original kernarg binding must still provide at least twelve
bytes. Any uniform private window uses that same twelve-byte bound. The
existing R-only, NX, uncached snapshot mapping serves both readers. The driver
leaves every original kernarg byte unchanged and retains the existing binding
ownership through retirement.

This supports the original compiler's `GRID_SIZE` loads at x1 offsets0/4/8:
`gl_NumWorkGroups` and the indirect dispatch consume one producer-fenced copy.
The original program bytes remain unchanged, and a later mutable-source update
cannot change either reader's grid. Store attempts remain subject to the
snapshot's R-only VM permission. Native GCI1, shader ISA, resource quantities
and the supported CU capacities are unchanged.

## Shared discovery and snapshot lifetime

The platform DT property **`opengpu,gci1` promises that the fixed GCI1 MMIO
window0x340..0x35f is mapped**. It is not an automatic-probing switch. When
absent, the driver performs no GCI1 register reads and retains direct compute.
A declaration requires ABI2, protected unified commands and the exact compatible
eight-word record, including flags, opcode14 and the discovered local capacity.
A malformed declared profile fails initialization. The global reserved capability
bit is unchanged. See the [hardware transport](INDIRECT_COMPUTE_DISPATCH.md).

The driver captures the input syncobj fence once and waits before admission
locks. It then locks all resource reservations, waits the argument source's
WRITE producers, checks their fence errors, and copies exactly twelve bytes
once into a zeroed job-owned allocation. Counts are decoded and validated from
that private copy; later source changes cannot change the job. CPU writes must
not race the copy. For nonempty work each full-u32 count must fit its declared
maximum and their product must fit u64. Any zero count preserves all three
values and submits a real opcode14 no-op, including unused UINT32_MAX axes.

The argument source must be local, CPU-mappable and allocation-disjoint from
every other job object, including READ-only peers. Its reservation supplies
the producer wait and receives the job's READ finished fence while still
locked. Source refs and all existing resources survive retirement; failure
rollback and failed-unmap quarantine also retain the private allocation.
There are still38 persistent binding slots. The complete active set plus an
independent argument object fits52 retained dependencies and53 reservation
locks. The snapshot uses the eighth temporary mapping, command window8 at
0xb2000000; its leaves are read-only, non-executable and uncached. Submission
verifies the actual PA/permissions and rejects writable aliases. Snapshot
cache lines are invalidated before the op14 doorbell to prevent stale reuse.

This is **immutable-snapshot indirect transport**, not live mutable GPU argument
execution. The existing GCI reader has no caller-specific maximum-count
comparator. Checking an original BO on the CPU and subsequently letting GCI
read changing bytes would not preserve the admitted bound. No new pointer
authority is granted beyond the selected kernarg contract: argument accesses
retain the existing x1/x8 provenance and its exact byte bound, and
group-derived resource offsets use checked services.
The512word/four-forward-state shader sandbox is unchanged. GLES/compiler
integration and complete H7 remain open.

The version1 CPU checks exercise actual ioctl, reservation, scheduler, VM, MMIO, POP and
retirement bodies with modeled kernel/device primitives. They cover the
complete512word resource/shared program,52refs/53locks, producer waits/errors,
unique12-byte copy, two roots, zero/full-u32 counts, partial-map failures,
cancellation and quarantine. GCC `-O2 -Werror`, ASan/UBSan and the prepared
Linux7.2/AArch64 `gpu_drv.ko` build pass. These checks alone are not GPU execution.

```sh
python3 scripts/test_compute_uniform_flow.py
python3 scripts/test_compute_feature_query.py
python3 scripts/test_arti_small_smoke_args.py
bash scripts/test_scripts.sh
```

## Version 2 source validation and P09 limits

Source PR: [#881](https://github.com/OpenGPGPU/OpenGPU/pull/881).

Source `82f2447acda2cd6597723c536417a307aab2abae` passes Clang and Linux
GCC13.3 ASan/UBSan checks using the actual production ioctl, scheduler,
submission and retirement bodies with modeled kernel/device primitives.
The tests cover nine resource combinations, distinct queued snapshots, one
copy after the actual source-reservation wait, immutable original kernarg
guards, first/last/crossing-page views, zero/full-u32 and large valid counts,
u64 product overflow, strict twelve-byte shader bounds, invalid version/flags,
failed producer fences, allocation/map rollback and healthy reuse. Original
v1 counter/uniform/image/texture paths also pass. A standard C++17
`-pedantic-errors` check preserves both public member spellings and the wire
layout. The complete float-host C/canonical transport regression also passes
208 healthy,208 failed and208 cancelled image-view cases, plus invalid BUSY
and cleanup-ACK retention checks. These are CPU transport checks; no shader
executes in these fixtures.

A fresh configured Linux7.2/AArch64 module build with GCC15.2 passes MODPOST
and `gpu_drv.ko` linkage in17.137s, with peak aggregate RSS347521024 bytes under
the512MiB/180s guard. All157 original/copied driver inputs and16290 shared
header/configuration/script/tool inputs remain unchanged;840 actual command
dependencies are checked. The32 object outputs and AArch64 relocatable module
are inspected, and the module is not loaded. Private sources, outputs, logs
and the Linux GCC test container are removed after verification.

```sh
PYTHONDONTWRITEBYTECODE=1 \
CC='clang -fsanitize=address,undefined -fno-omit-frame-pointer' \
  python3 -B scripts/test_compute_uniform_flow.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_compute_feature_query.py
# On Linux, repeat with CC='gcc -fsanitize=address,undefined -fno-omit-frame-pointer'.
env -u SDKROOT -u CPATH PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 \
  python3 -B scripts/test_compute_host.py --image-float-stage --canonical-root .
```

For Kbuild, copy the current top-level driver `.c`, `.h` and `Makefile` inputs
into a private read-only source directory. Use separate output/temp directories
and the configured ARM64 kernel and cross-tools; bind original/copy/shared
inputs before and after and check the actual `.cmd` dependency closure:

```sh
TMPDIR="$PRIVATE_TMP" python3 -B scripts/run_owner_bounded.py \
  --memory-mib 512 --timeout-seconds 180 -- \
  /opt/homebrew/bin/gmake -C "$KERNEL_BUILD" \
  M="$PRIVATE_SRC" MO="$PRIVATE_OUT" ARCH=arm64 \
  CROSS_COMPILE=/opt/homebrew/bin/aarch64-linux-gnu- \
  KCONFIG_NOSILENTUPDATE=1 -j1 V=1 modules
```

This driver source stage does not close P09. A distinct original opcode14
capture and current complete-DUT execution of original
shader/GPU-produced three-dimensional grids remain required, including source
fences/private snapshots, zero/extreme/out-of-bounds inputs, failures and
healthy reuse. The alignment count stays8/40. The earlier version1 guest
results below retain their original source/model scope and do not qualify v2.

## Version 2 Gallium routing

On screens advertising bit44, Gallium retains the original indirect resource,
its logical buffer size and the exact byte offset. It does not map or decode
the count triplet. The pipe submits version2 with
`OPENGPU_COMPUTE_INDIRECT_KERNARG_COUNTS`, a zero direct grid and an optional
independent counter source. The producer-fenced driver copy supplies both GCI1
and the unmodified shader's `gl_NumWorkGroups` loads. The original pipe kernarg
is not overwritten. Existing publication, fences, failed-wait ownership and
context recovery remain in use. Screens without bit44 retain the existing CPU
snapshot/direct fallback; that path does not qualify native indirect execution.

The source's logical tail is checked independently of the page-rounded GEM
allocation. Active axes retain the advertised `UINT32_MAX` grid capacity;
inactive axes have a caller bound of one. The driver checks nonempty count
products in u64 and retains its real opcode14 zero-work contract. Source
allocation aliases with any submitted resource are rejected. Storage-only v2
does not require legacy combined-resource or counter features; each supplied
resource domain still requires its own capability and checked view.

Source `8098dff3ad4b3992bce5f8ae83e41a0c064fef95` passes fresh Linux
GCC13.3 ASan/UBSan builds against actual Mesa22.3.6. Four original ESSL programs
exercise a three-dimensional consumer, default/UBO inputs, an independent
counter and a count producer. Eighteen accepted indirect requests pass the
actual production ioctl, private records, opcode14 MMIO staging and retirement
bodies with modeled OS/GEM/VM/device replies and an independent CPU word oracle.
Protecting the source mapping outside the ioctl bridge detects upper-layer
count reads; original native shader bytes and kernarg guards remain unchanged.
Checks cover first/last/crossing-page offsets, logical64B bounds inside a4096B
GEM, zero and large valid grids, u64 overflow and inactive-axis rejection,
foreign sources, allocation aliases, malformed bounds/empty-counter metadata,
injected submission failure and healthy reuse. After an accepted wait failure,
source mutation and public-handle closure preserve the private counts and
source backing until retirement. Whole output/guard words remain checked.

The original buffer and eight-program atomic-counter regressions also pass
fresh GCC13.3 ASan/UBSan builds, including32textures, four SSBOs, four images,
twelve UBOs/defaults,128 returned counter words, partial-bind failures and
publication/teardown ownership. The unchanged legacy callback suite passes
GCC11.4 ASan/UBSan with leak checking, including CPU indirect fallback,
full128-item local shapes and four128MiB sparse metadata views.

With pinned Mesa22.3.6 source/build, the checked uniform-index patch and DRM
headers selected through `OPENGPU_MESA_DIR`, `OPENGPU_MESA_BUILD` and
`OPENGPU_DRM_INCLUDE`, reproduce on Linux with new private build directories:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_gallium_compute_indirect.py \
  --build "$PRIVATE_ROOT/indirect" --sanitize
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_gallium_buffer_flow.py \
  --build "$PRIVATE_ROOT/buffers" --sanitize
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_gallium_atomic_counter.py \
  --build "$PRIVATE_ROOT/counters" --sanitize
PYTHONDONTWRITEBYTECODE=1 \
CC='gcc -fsanitize=address,undefined -fno-omit-frame-pointer' \
ASAN_OPTIONS='detect_leaks=1:abort_on_error=1' \
UBSAN_OPTIONS='halt_on_error=1:print_stacktrace=1' \
  python3 -B scripts/test_gallium_compute.py
```

These are CPU source-composition checks, including a CPU-executed producer.
They do not establish GPU-produced counts, real producer reservation fences,
hardware page walks or cross-command DUT coherence. That routing stage exports
no opcode14 capture or complete-DUT v2 result. Those P09 gates remain open;
the alignment count stays8/40 and the public compute API remains disabled.
Private validation builds, logs and generated inputs are removed after use.

## Version 2 opcode14 capture contract

The Linux qualification bridge now has an explicit
`compute_host_open_indirect` declaration and a dedicated v2 submission,
snapshot, capture and receipt interface. It validates GCI1 through the actual
production discovery helper before advertising transport. GCI1's eight words
already belong to the complete112-word factory record; the original ten
auxiliary observations and all resource capacities remain intact. Legacy
opens leave indirect transport disabled, including with poisoned GCI1 data.

| Wire | Contract |
|---|---|
| `OGCI0001` | Original source GEM identity/physical extent/offset, caller maxima, private snapshot PA/VA/size/offset, original kernarg guard, admitted grid, complete observations, writable views, empty VMs and actual opcode14 enqueue trace |
| `OGCIT001` | Actual Linux IRQ/POP trace with exact command identity and result status |
| `OGCIH001` | Typed held observation with explicit opcode14 and result status |
| `OGCIR001` | The unchanged held observation plus exact idle/drained retirement |

The independent C++ reader requires the same twelve bytes for GCI1 and x1,
zero private padding, the exact preceding snapshot invalidate, and both pages'
actual Sv32 PA/RO/NX/uncached mappings when the view crosses a page. Writable
aliases in the active kernel VM are rejected. Source, snapshot and original
kernarg guards cannot be declared writable. Zero-work grids preserve full-u32
axes; nonempty counts retain the caller maxima and u64 product checks. Legacy
opcode0 capture, snapshot, receipt and reset entry points reject indirect jobs.

Opcode14's highest opcode bit is encoded in completion bit16, separately from
status bits14:11. For command17, success is `0x18611`, execution failure1 is
`0x10e11`, GCI bounds12 is `0x16611`, and GCI read failure13 is `0x16e11`.
The naive `14 << 8` encoding is rejected. The driver reports bounds as
`ERANGE`, and execution/read failures as `EIO`; typed observations still bind
the exact result status when Linux errno is shared.

Source `e51d034823bf730cedb5f9004ffa335e28eb8e77` passes Clang ASan/UBSan and
Linux GCC13.3 ASan/UBSan with leak checking. Fourteen actual C-generated
captures reach the independent C++ reader; an additional case cancels before
execution. The checks cover source-only submission,
first/last/crossing-page offsets, zero and large valid grids,128 local items,
twelve UBOs/defaults with eight independent counters and four SSBOs, two
additional empty VMs, source mutation/public-handle closure, failed source
fences and exact result0/1/12/13 IRQ/fence/fault/cleanup paths. Mutations reject
wrong source/private bounds, writable or
executable/cached/global/wrong-PA leaves, a writable alias, changed private
bytes/padding, legacy opcodes/wires and partial or mixed completion fields.
The three Python protocol tests also pass. Both toolchains pass the existing
208 healthy,208 failed and208 cancelled float-host transport/canonical cases,
including invalid BUSY and cleanup-ACK retention. The22 executor qualification
tests pass; the focused capture test is registered in the host script lane.

```sh
env -u SDKROOT -u CPATH PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 \
  python3 -B scripts/test_compute_indirect_capture.py
env -u SDKROOT -u CPATH PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 \
  python3 -B scripts/test_compute_host.py --image-float-stage --canonical-root .
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_prepare_owner_executor.py
# On Linux, CC=gcc CXX=g++ repeats the sanitizer and leak checks.
```

These checks use a small three-load native input and modeled device replies;
no shader executes. Actual Mesa opcode14 source/capture integration, the native
execution adapter/controller, GPU-produced three-dimensional counts and real
producer fences, continuous original-GEM/DUT ownership and fault/reuse checks
remain required. The legacy reset protocol cannot qualify opcode14 reset/drain.
No complete-DUT v2 or clock/FPS result is claimed. P09 remains open at8/40.
Generated captures, memory images, test builds and logs are removed after use.

## Version2 continuous-owner source lifecycle

The compute host boundary supports distinct phase cleanup for a continuous
opcode0 producer/opcode14 consumer. It retains the original render file,
original GEM physical storage and reservation/sync fences. It releases the
completed job's temporary mappings through the production scheduler; ordinary
caller-side publication and destruction then revoke the old compute context.
Other original contexts must remain empty. The global MMU, hardware fence
timeline and profile remain live. Final whole-session cleanup retains its
original MMU/bare-SATP shutdown requirements.

`OGCOB001` transfers every live original GEM in handle order. Its four u32
header fields are command ID, opcode, phase epoch and original count. Each
record has u32 handle/zero padding, u64 PA/size, then exactly size bytes.
Import requires the observed retirement and matches the complete original
identity set. It validates the whole transfer before changing any allocation;
only captured writable bytes may differ, and failed commands may change none.
Originals are pinned through the final phase ACK, including public-handle
closure during caller-side cleanup. Shader/private/PTE data cannot be imported
as original output.

The source emits `OGCPB001` authorization, `OGCPC001` cleanup batches and
`OGCPD001` phase completion; batch acknowledgements use `OGCPA001`. The existing
whole-session cleanup protocol is unchanged. Publication uses the actual
production invalidate ioctl, scheduler run branch and asynchronous opcode5
submission. Its range is limited to cache lines intersecting captured writes
within the original GEM. Its modeled control completion still requires native
replay/verification before phase acknowledgement. Re-arming requires the old
compute context gone, other contexts empty, exact command/context/epoch/batch
identity and an idle, drained, IRQ-free receipt with 32 quiet cycles and intact
read-only guards. It preserves the actual original fences, including completed
errors; it cannot silently destroy a retained session.

Source `f05e76e316ddca38f8fafc74f4e7641549f50c38` passes Clang ASan/UBSan
and Linux GCC13.3 ASan/UBSan/leak-checking across six CPU-peer
phases: producer bytes, healthy indirect consumer, GCI read failure, healthy
reuse with a fresh output, a zero-axis producer and zero-work consumer. Counts
cross offset4092 in the same original GEM; the completed producer WRITE fence
survives publication and consumer READ errors. Thirteen malformed identity,
read-only/padding and truncated/trailing transfers reject atomically; ten
mutated final receipt fields reject re-arming. Wrong batch magic, sequence and
BUSY acknowledgements retain reachable original GEM/job/context/fences.
Both toolchains also pass the existing 208 healthy,208 failed and208 cancelled
float-host transport/canonical cases and their failed-ACK/BUSY guards. The
executor qualification suite has23 tests, including changed/omitted phase
source rejection. Run the three commands in the preceding section.

These are CPU transport tests with explicitly modeled returned bytes and
control replies. The actual Mesa continuous frontend, typed native phase
consumer, retained live-RAM merge, full-DUT execution and independent GPU byte
oracle remain required. No GPU-produced counts, continuous hardware coherence,
clock/FPS or P09 completion is claimed; the accepted count remains8/40.

## Earlier version1 guest qualification

An explicit local guest qualification uses the same original full resource
and shared-memory programs: twelve16KiB blocks,1024defaults,32textures,
four images, four independent128MiB SSBOs, eight independent counters and
all4096 shared words per group. A separate128-item producer writes three
counts at offset4092 using warp0/VL1 stores. Compute kernargs are actually mapped
UNCACHED by `opengpu_binding_map_kernarg`; its canonical fence and finished
producer fence precede the CPU copy on coherent local GEM pages, as in uniform
publication. `dma_rmb` supplies CPU ordering; it does not flush GPU L2 or replace
those completed writes. The source is not poisoned while a job owns it.

The opt-in keeps two default16lane/eight-warp CUs, real SRAM/FPU/default caches
and32-byte AXI. It needs the matching private model and a separately hashed DTB
with only the GCI1 mapping declaration added. It checks two healthy epochs'
18432 individual result words each, zero work, CPU rejection of3>max2 before
enqueue, the existing indirect uniform fault and fresh context/source reuse.
The argument snapshot itself is correctly mapped; this is not a new physical
GCI fetch-fault test. This guest mode is outside routine CI:

```sh
OPENGPU_NATIVE_LINUX_RESOURCE_QUALIFICATION=1 \
OPENGPU_NATIVE_LINUX_SHARED_QUALIFICATION=1 \
OPENGPU_NATIVE_LINUX_INDIRECT_QUALIFICATION=1 \
ARTI_SMOKE_WIDTH=16 ARTI_SMOKE_HEIGHT=16 ARTI_SMOKE_DRAW_WAIT_MS=900000 \
ARTI_SMOKE_TIMEOUT=7200 QEMU="$PRIVATE_QEMU" ARTI_SMOKE_DTB="$GCI1_DTB" \
  scripts/run_arti_small_smoke.sh uniform-counter-source
```

One Linux7.2/AArch64 guest passes that complete sequence, normal power-down and
QEMU exit zero, without a retry or new emission/model. Each healthy epoch
checks18432 individual results, including all4096 shared words in each of two
128-item groups. Both zero-axis submissions complete with prior results and
counters intact; both3>max2 submissions return `ERANGE` before queueing. The
epoch1 uniform-bounds fault reports public opcode14 and preserves prior data;
epoch3 succeeds with fresh contexts/VM and argument/counter GEM objects.
The test submits17 compute workloads:12 direct helpers and5 indirect workloads.
That count excludes probe work, cache invalidations and other unified commands.
The original281word, fault8word and two512word shared programs remain
byte-identical; only the separate argument producer is new.

The reused native hardware was originally emitted at `00197df1`; guest software
is `98793f0b` plus this patch. This does not requalify the later `7d4b3636`
scheduler RTL. The original QEMU binary is unchanged; the new DTB differs only
by the declared `opengpu,gci1` property. Runtime module inputs and the actual
guest ELF match the frozen CPU-validated sources/ELF. The observer confirms the
actual two-CU profile; its lifetime peak2 includes probe/helper work and does
not attribute concurrent full-resource work to both CUs. Likewise,
`pendingBeforeConsumer=true` is informational, not proof of a forced dependency
wait. Live argument execution, compiler/GLES API integration, four-CU product
and physical qualification remain separate H7 gates.
