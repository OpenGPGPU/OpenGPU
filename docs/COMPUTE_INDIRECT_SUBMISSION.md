# Bounded Linux indirect resource submission

`DRM_IOCTL_OPENGPU_COMPUTE_INDIRECT_RESOURCES`, version1, reuses the existing
counter-source/uniform/resource submission contract. It adds a local argument
GEM handle/offset and three nonzero maximum group counts. The nested launch's
direct grid must be zero; its local dimensions, resource metadata, versions,
reserved fields and shader sandbox retain their existing checks. Existing
direct ioctls are unchanged. Query
`OPENGPU_DRIVER_FEATURE_COMPUTE_INDIRECT_RESOURCES` before using this interface.

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
authority is granted: ordinary kernarg accesses still require the existing
x1/x8 provenance, and group-derived resource offsets use checked services.
The512word/four-forward-state shader sandbox is unchanged. GLES/compiler
integration and complete H7 remain open.

The CPU checks exercise actual ioctl, reservation, scheduler, VM, MMIO, POP and
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
