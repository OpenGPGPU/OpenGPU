# Linux native compute resource execution

The explicit `uniform-counter-source` guest passes real Linux DRM submission,
protected VM translation, GUM1 snapshots and original-GEM GAM1 execution through
`GpuHostSystemAxi`. Both healthy epochs and the intervening uniform bounds fault
pass, followed by normal guest power-down and QEMU exit zero. This is local
qualification; it adds no regular PR/nightly GPU case.

The model has two default 16-lane/eight-warp CUs, 16 KiB shared SRAM capacity,
real SRAM functional models and FPU, default caches/L2 and 256-bit memory AXI.
Actual GCR1 reads confirm that profile and all resource services. An observer
also checks a process-lifetime `activeComputeUnits` peak of two; it does not
attribute that peak to each full-resource kernel. The normal driver probe uses
a 16×16 frame. The guest workload submits compute commands only.

Each healthy parent has two 128-item groups and retains the following views
together, without expanding the 38 persistent binding slots:

| Resource | Retained set and executed checks |
|---|---|
| Uniform blocks | Twelve distinct 16 KiB source GEMs; independent first/last words of every block |
| Defaults | Independent 4096-byte GEM; all 1024 components, written by a real GPU producer in another context |
| Textures | 32 distinct source GEMs and checked R32UI fetch views |
| Storage | Four independent 128 MiB GEMs; all first/last words and checked per-item result stores |
| Images | Four R32UI views on those storage GEMs; store, fence, converged barrier and reload |
| Counters | Eight original-GEM counters at offset 4080 in an independent 8192-byte GEM, crossing a line and page; exact 32-element returned-old sets |

The 281-word program passes the existing 512-word, forward-CFG shader sandbox.
It checks 40 result planes per group: 10,240 individual words per healthy parent,
20,480 across epochs one and three. Source bytes, bounded storage guards and
counter backing are checked separately. A texture consumer in another context
reads the original updated counter GEM after submission. The observed
`pendingBeforeConsumer=true` is informational: these results do not by themselves
prove that a reservation wait was forced rather than satisfied by scheduling.

The explicit input syncobj belongs to the GPU default-value producer. No CPU
source writes race a busy job. After the first healthy fences retire, a default
offset of 4096 faults before any result or counter write. Its public fence
fails with `-EIO`; the fault has exactly completion-error flags, matching command
identity/opcode and a nonzero hardware status, with no timeout or abort. All
previous results remain intact. Contexts and fences retire before epoch three
uses changed source values, fresh contexts and a new counter GEM.

Use ARTI revision `8b313c8c2753c7f9141f61f607976f8d6eb891fb` or compatible
generic 32-byte memory-master support, a configured QEMU build and an already
prepared AArch64 Linux kernel with CMA. The builder verifies SRAM revision
`9f5af0939e8dd3cc1a9693a50b23441691dd7d25`. It emits one fresh native model,
builds with one compiler worker and links a private QEMU executable. Its
3 GiB/CPU2 JVM exits before native compilation. Replace `--run` with `--plan`
to print the argv without emission or building.

```sh
export OPENGPU_NATIVE_LINUX_RESOURCE_QUALIFICATION=1
MODEL="$PWD/.artifacts/native-linux-resources"
python3 scripts/build_arti_native_compute_model.py --run \
  --arti "$ARTI_DIR" --qemu-build "$QEMU_BUILD" --sram "$SRAM_ROOT" \
  --firmware "$QEMU_FW_DIR" --dtc "$LINUX_BUILD/scripts/dtc/dtc" \
  --output "$MODEL"
ARTI_SMOKE_DTB="$MODEL/guest.dtb" QEMU="$MODEL/qemu-system-aarch64" \
  ARTI_SMOKE_ARTIFACTS="$MODEL/guest-runs" ARTI_SMOKE_TIMEOUT=7200 \
  scripts/run_arti_small_smoke.sh uniform-counter-source
```

Use Python 3.10 or later and export the prepared `ARTI_DIR`, `ARTI_WORK`,
`LINUX_BUILD`, `LINUX_SOURCE`, `LINUX_HEADERS` and firmware paths as needed by
the existing smoke harness. This mode requires exactly 2 GiB guest RAM,
`cma=768M@0x60000000` and its private matching 16×16 DTB. The CMA range fits
entirely below 4 GiB. Conflicting settings fail before build/boot. The builder
retains source/RTL/wrapper/native binary hashes and actual command exit statuses.
Acceptance requires both healthy markers, the fault marker, actual-profile and
lifetime-peak observer success, the final guest marker and normal power-down;
a serial PASS cannot hide a later QEMU failure.

Relevant CPU checks are `bash scripts/test_scripts.sh` and strict compilation
of `driver/tests/opengpu_compute_storage_validator_test.c`. The latter uses the
actual validator for the full program, rejected resource/masking profiles,
fault program, default producer and counter texture consumer. The guest itself
is cross-compiled with `-static -O2 -Wall -Wextra -Werror`; a real Linux 7.2
AArch64 module build also passes. Raw manifests/logs remain local under
`.artifacts/` until guarded post-merge cleanup.

This closes the bounded Linux resource-view execution gap left by the earlier
[uniform](COMPUTE_UNIFORM_SUBMISSION.md) and
[counter-source](COMPUTE_COUNTER_SOURCE_SUBMISSION.md) C/module tests. It does
not qualify shared-memory traversal (the capacity stays 16 KiB), indirect
dispatch, image/storage atomic operations, broad alias/reservation ordering,
shader compiler/GLSL/API integration, four-CU product coexistence, physical
timing or precise per-warp trap reporting. Earlier
[native host](NATIVE_HOST_RESOURCE_PUBLICATION.md) and
[shared-capacity](COMPUTE_RESOURCE_COEXISTENCE.md) proofs retain their separate
scope. Full GLES 3.1 and H7 remain open.
