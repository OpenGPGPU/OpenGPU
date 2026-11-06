# Bounded H7 Linux / ESSL resource validation

The explicit two-CU ResourceGuest passed the five authored ESSL 3.10 programs
through the real Mesa frontend, Gallium callbacks, DRM driver, private Sv32
page tables and native RTL model on 2026-11-06. The supervised guest completed
in **116.9 seconds**, with peak owned RSS **1,046,249,472 bytes**, no automatic
retry, unchanged inputs and no remaining owned process. This is simulation
wall time, not hardware latency or a frame-rate measurement.

The retained RTL/model uses GPU source baseline `eef51bb2`; the guest module
includes the later read-only uniform-snapshot fix. Publishing the tools on a
newer main revision does not requalify a newly emitted model automatically.

This result uses a **private Linux 7.2.0 observation kernel**, not stock Linux.
It qualifies this bounded chain on **two** 16-lane, eight-warp CUs. Four-CU
product coexistence, general resource/barrier and cached cross-domain ordering,
broader compiler/API support, physical qualification and full H7 remain open.
`PIPE_CAP_COMPUTE` remains zero; no GLES conformance claim is made. No guest,
model, large rendering case or nightly lane is added to regular CI.

## Observed result and limits

The model retained FPU, native SRAM and all optional compute resource services,
16 KiB shared memory per CU, 16 transactions per CU, default 64-set/two-way
instruction/data caches, four instruction misses, and the default 256-set,
four-way, four-bank L2. The AXI data width was 32 bytes. The global observer
measured `peakActiveCUs=2`; it does not attribute that peak to an individual
shader or establish simultaneous use of every enabled service.

| Check | Actual result |
| --- | --- |
| Real frontend exports | 473 / 507 / 426 / 473 / 507 native words |
| Compute parents | Producer, consumer, selected fault, fresh producer, fresh consumer |
| Authored launch metadata | 9 groups, 72 physical warp invocations |
| Healthy work | 1,012 item executions, 506 result items; final warp has 13 active lanes |
| Application oracle | 8,256 bounded result/guard words per parent |
| Public allocations | Four independent 128 MiB SSBOs and twelve independent 16 KiB UBOs |
| Complete public layout | 131,120 pagemap PFN pages, distinct handles and no physical aliases |
| Installed storage mappings | 655,360 page/PFN comparisons across five actual SUBMIT snapshots |
| Private uniform mappings | 250 read-only/non-executable/uncached page checks; 50 private pages per parent |
| Fault and reuse | Expected execution fault / `EIO=5`, bounded outputs unchanged, fresh 125-item contexts pass |
| Accepted transport | 177 SUBMIT / 177 POP, 5,630 MMIO B; AR 14,624 / R 28,628 / AW 4,048 / W 8,096 / B 4,048 |
| Terminal | Selected application PASS, original QEMU exit 0, observer `COMPLETE_DRAINED` |

Groups and warps in this table come from the actual submitted dimensions and
compiler metadata, not a per-warp runtime trace. The private compute snapshots
identify exactly five resource parents among the 177 total commands. Storage
PTEs are checked for the required private read/write and cache policy; absence
of executable permission on storage is not claimed. Uniform PTEs are checked
for read-only/non-executable permission, separation from every public backing,
and absence of another writable/executable VA alias.

Healthy programs really access the first and last word of all four full SSBO
views. Accepted AR/R and strobed AW/W/B are joined to the correct parent before
its completion POP. The fault parent reads API buffer 0's prefix and accepts
no public storage write. Full allocation and full mapping checks do not imply
exhaustive payload traversal: CPU publication touches 33,024 bytes in 516 cache
line envelopes per epoch, and application readback checks the bounded words.

Public UBO PFNs identify source BOs, not the job-private uniform snapshot.
The latter is identified by accepted GUM1 control/root/identity registers and
actual installed PTEs. Each job has successful reads to 26 of its 50 mapped
uniform pages. The observer records only word zero of each 32-byte data beat;
these checks do **not** reconstruct complete AXI, GUR or GUB payloads.

## Why the private kernel is necessary

The unmodified kernel stopped at the first pagemap report with `ENODATA`,
before any GPU SUBMIT. GEM DMA mmap uses `remap_pfn_range`, which eagerly
installs special 4 KiB CPU PTEs and marks the VMA `VM_PFNMAP`. Stock
`walk_page_test()` skips those VMAs and calls the pagemap hole callback. This
is an interface limitation, not missing page fault-in or an RTL failure.

The [minimal kernel patch](../../scripts/patches/linux-pagemap-pfnmap-qualification.patch)
adds a pagemap-only `test_walk` callback. For PFNMAP it permits the existing PTE
walker **only when the original `pm.show_pfn` authorization is true**. That
authorization remains `file_ns_capable(file, &init_user_ns, CAP_SYS_ADMIN)`:
the opener's file credentials in the initial user namespace. It is not replaced
by a current-UID or `capable()` check. Non-PFNMAP behavior and the unprivileged
PFNMAP hole/soft-dirty/end-of-buffer/file-offset behavior are preserved. PFNs
come from the real CPU PTE; `vm_pgoff` is not added.

This private image covers the actual 4 KiB GEM DMA/CMA mappings only. It is not
a general huge-PMD or arbitrary I/O PFNMAP qualification. It changes neither
GPU DMA mmap, mapped data, RTL, the application ELFs nor module ABI. The
separately merged uniform snapshot fix must also be present: slot 6 is mapped
with `opengpu_mmu_vm_map_snapshot`, not the writable generic VM mapper.

## Reproduce the private kernel without rebuilding Linux

Use Python 3.10 or newer for these tools (the actual run used Python 3.14).
Use the matching, already configured AArch64 Linux 7.2.0 source and object
cache, cross-toolchain and dependency modules. Keep the original source,
object cache and Image read-only. The tested cache uses 4 KiB pages, non-LTO,
no aggregate prelink, BTF or built-in DTB, and GNU thin `vmlinux.a` and EFI
libstub archives. Unsupported link dependencies are rejected before copying.
The [preparer](../../scripts/qualification/prepare_pagemap_kernel.py) copies their
**actual members**, generated headers/config and cached link tools into a fresh
independent CoW output. It requires macOS `clonefile`; hard links are forbidden.
It patches a copy of `task_mmu.c` and emits structured argv. Preparation compiles
nothing and starts no guest.

```sh
python3 scripts/qualification/prepare_pagemap_kernel.py \
  --linux-source "$linux_source" --linux-build "$linux_build" \
  --cross-prefix "$cross_prefix" --out "$new_private_kernel"
```

The plan binds original inputs, actual read-only CoW files, patched C source,
private link script and both generated guards. It excludes the one C object
that will be overwritten and newly generated link outputs. Review the plan and
its printed SHA, then run the committed one-shot executor. It enforces one
sequential worker, 2 GiB aggregate owned RSS, a 600-second outer limit, 1.5 GiB
logical private output and zero retries, preserving PID/birth/PGID and original
exit/stop/drain records:

```sh
python3 scripts/qualification/run_pagemap_kernel.py --check \
  --plan "$new_private_kernel/commands.json" --approved-sha "$reviewed_kernel_plan_sha"
python3 scripts/qualification/run_pagemap_kernel.py --run \
  --plan "$new_private_kernel/commands.json" --approved-sha "$reviewed_kernel_plan_sha"
```

Only `fs/proc/task_mmu.o` is recompiled with its cached compiler flags and old
include paths. Its actual MMD must contain only pinned dependencies, and its
defined-global symbol names/types must match the original object before the
thin-archive index can be reused. Final release/config and cached export
object/Module.symvers ABI checks must also pass.
The original `link-vmlinux.sh` retains all kallsyms passes,
sorttable and final System.map comparisons. Its sole implicit make call is
replaced by a strict argv/cwd/hash guard that reuses the frozen
`init/version-timestamp.o`; `MAKE=/usr/bin/false` rejects any other make.
Generated kallsyms assembly uses the cached ARM64 assembler flags. The two
original vmlinux objcopy operations and final Image objcopy follow unchanged.
There is no top-level `make Image` or `vmlinux.o` rebuild.

The actual build copied 4,405 independent files, including all 4,317 unique
thin-archive members. All five stages exited zero in 22.8 seconds; peak owned
RSS was 741,982,208 bytes and peak logical output 980,184,267 bytes. Original
inputs and all 744 actual C dependency files were rechecked. The resulting
private Image was 41,617,920 bytes.
Unchanged release/config/exported symbol types allow the same module ABI;
verify these properties against your own prepared cache before booting.

## Reproduce the bounded guest and offline acceptance

Use the existing [real GLSL runtime instructions](../GALLIUM_COMPUTE.md#explicit-glsluniformcontrol-flow-linux-application)
and `scripts/build_gallium_compute_runtime.py --glsl-control-flow`. Reuse an
already qualified real Mesa archive/member closure; do not rebuild Mesa or
rerun preflight/oracle work that has already passed unchanged. Package the
actual app, its five authored `.comp` files, loader and transitive libraries.
Build the current module against the matching prepared kernel; retain the
seven matching DRM dependency modules and `arti_driver_deps` list described in
the existing Linux instructions.

The shared [init source](../../scripts/qualification/resource_guest_init.c)
selects exactly one `opengpu.qual=h7`/`h8` argument and sets pagemap/RAM variables
for that app. Compile it with the matching cross compiler:

```sh
"${cross_prefix}gcc" -D_GNU_SOURCE -static -O2 -Wall -Wextra -Werror \
  scripts/qualification/resource_guest_init.c -o "$rootfs/init"
```

For H7 place the real executable at `/opengpu/gallium_compute_glsl` and sources
under `/opengpu/glsl-compute`; copy its runtime library closure to the same
rootfs paths. `/init`, the app and `/lib/ld-linux-aarch64.so.1` must be mode 0755.
Create `/proc`, `/sys`, `/dev` and `/tmp`, add `/arti_driver.ko`, then package
the rootfs as gzip/newc. Inspect the actual archive bytes, modes, interpreter,
NEEDED closure and module ABI; the init powers off after failures too.

Use one shared native model built by
`scripts/build_arti_native_compute_model.py --run --ram-mib 3072` with the
arguments in the existing Linux instructions. It uses
`EmitNativeLinuxResourceGuest`, all default cache capacities and native SRAM.
Use the ARTI source with the real write-handshake fix (`53b47d56` or a reviewed
descendant); do not substitute an older wrapper. This explicit native build
used two CPUs/JVM Xmx3G, make `-j1`, 7 GiB aggregate RSS and 1,800 seconds,
zero retries; actual build time was 365.2 seconds with peak RSS 5,072,551,936
bytes. Do not build separate copies for H7/H8.

Check the actual dumped DTB with
`scripts/qualification/resource_guest_profile.py`: RAM must be exactly
`[0x40000000,0x100000000)`, 16×16 display metadata/stride 64, with the final
QEMU argv also specifying 3072M. Boot must actually reserve **1,536 MiB CMA at
0x80000000**, wholly inside that RAM. Static DTB and command-line checks alone
do not prove a reservation. The committed
[one-boot supervisor](../../scripts/qualification/resource_guest_once.py)
seals explicit files and directory membership into a fresh launch packet.
Supply the model, private kernel, archive, host tools, fresh output and the
same exclusive lock path for every H7/H8 user of that model:

```sh
python3 - "$model" "$new_private_kernel/private-o/arch/arm64/boot/Image" \
  "$initramfs" "$dtc" "$qemu_firmware" "$run" "$shared_model_lock" "$draft_config" <<'PY'
import json, pathlib, sys
model, kernel, initrd, dtc, firmware, output, lock, draft = sys.argv[1:]
p = pathlib.Path(model)
config = dict(app='h7', output=output, shared_model_lock=lock,
    outer_max_seconds=1800, owned_RSS_cap_bytes=7*1024**3,
    paths=dict(qemu=str(p/'qemu-system-aarch64'), kernel=kernel, initramfs=initrd,
        dtb=str(p/'guest.dtb'), dtc=dtc, firmware=firmware,
        guest_profile='scripts/qualification/resource_guest_profile.py'),
    input_files=['scripts/set_arti_guest_extent.py',
                 'scripts/qualification/check_guest_common.py',
                 'scripts/qualification/check_h7.py',
                 'scripts/qualification/golden-programs.json'])
pathlib.Path(draft).write_text(json.dumps(config, indent=2)+'\n')
PY
python3 scripts/qualification/resource_guest_once.py --prepare \
  --config "$draft_config" --packet "$launch_packet"
python3 scripts/qualification/resource_guest_once.py --check \
  --packet "$launch_packet" --approved-sha "$reviewed_launch_index_sha"
```

Preparation prints the exact derived QEMU argv/environment and INDEX SHA. It
does not claim or boot the model. Review those bytes and input pins before the
one-boot record authorizes action `ONE_RESOURCE_GUEST_BOOT_NO_BUILD`, `app=h7`,
that exact `index_sha256`, `outer_max_seconds=1800` and
`owned_RSS_cap_bytes=7516192768`. After that independent review, record the
caller's explicit decision with `--review`. This command does not perform the
review or start a process; it produces the review JSON and prints its SHA.

```sh
python3 scripts/qualification/resource_guest_once.py --review \
  --packet "$launch_packet" --approved-sha "$reviewed_launch_index_sha" \
  --approval-file "$launch_packet/review.json"
python3 scripts/qualification/resource_guest_once.py --run \
  --packet "$launch_packet" --approved-sha "$reviewed_launch_index_sha" \
  --approval-file "$launch_packet/review.json" --approval-sha "$printed_review_sha"
```

The entry enforces one CPU/native thread, 7 GiB RSS, 1,800 seconds, exclusive
model ownership and no retry. Its QEMU argv includes `-m 3072M`,
`-accel tcg,thread=single`, `-smp 1` and the exact
`cma=1536M@0x80000000 rdinit=/init opengpu.qual=h7` boot arguments. Progress
records SUBMIT/POP/event counts, file sizes/mtime and serial tail; quiet boot
intervals never imply a hang. It writes the original identity/terminal,
reviewed config/INDEX binding, immutable logs and complete proof manifest in
the frozen checker's format. Exit zero yields only `EXITED_ZERO_UNVERIFIED`.
Do not manufacture missing records or rewrite failures; all such data stays
local. The generic source retains the original supervision/record logic while
removing historical machine/model/module pins; its capture format has separate
CPU-only tests.

After consuming that original terminal, use the committed verifier source:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/qualification/check_h7.py \
  --serial "$run/serial.log" --stderr "$run/stderr.log" --proof "$run/proof" \
  --dtb "$model/guest.dtb" --dtc "$dtc" \
  --guest-profile scripts/qualification/resource_guest_profile.py \
  --common scripts/qualification/check_guest_common.py \
  --expected-common-sha 2a18b803254e6a73df614008e6cb2bbe0d2bab0f14ef66cda1198f9152fb8bbe \
  --original-terminal "$run/original-terminal.json" \
  --launch-result "$run/launch-result.json" --out "$run/h7-qualification.json"
```

Require `PASS_BOUNDED_H7_ACTUAL_LINUX_CHAIN`. Exit zero or powerdown alone never
passes. The original offline join passed in 2.34 seconds with 92,143,616-byte
maximum RSS. Its pure CPU format/negative checks can be reproduced separately:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/qualification/selfcheck_h7.py \
  --common scripts/qualification/check_guest_common.py --out "$new_cpu_result"
PYTHONDONTWRITEBYTECODE=1 python3 scripts/qualification/selfcheck_resource_guest_once.py \
  --out "$new_supervisor_cpu_results"
```

Those 27 synthetic checks reject missing app PASS, mixed metadata, incomplete
or aliased PFNs, wrong PTE permissions, writable uniform aliases, missing
successful responses, late responses and unstrobed endpoints. They allocate no
large BO, start no guest and do not replace the actual Linux result above.
The supervisor checks use only tiny Python child processes, including nonzero
exit, time/RSS/disk stops, a leader leaving a child, preclaim rejection, and a
synthetic original capture bound to the unchanged common checker. A synthetic
capture is separately rejected as evidence of a real Linux boot.
