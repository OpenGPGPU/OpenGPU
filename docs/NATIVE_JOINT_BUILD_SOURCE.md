# Original linked Mesa source to protected joint BUILD

The private `opengpu_native_joint_build_source` API retains one original linked
VS/TCS/TES/FS source lease and captures an explicit draw catalog, invocations,
instance-major ranges and original frame views. VS uses the linked NEXT_TCS
boundary; TCS/TES retain program v3 and independent GUR3 resources; FS retains
its actual checked-uniform ABI8/9 code and independent TES→FS mapping. The
producer cannot supply a kernel profile, permission bit, private VA or patch
contents. The production discovery/getter/submit routes remain unavailable.

This is a resource-graph CPU proof. It does not execute or interpret ISA,
produce patch data, enqueue a command, render an image or qualify P16/P17.
The full five-stage/GS source and 65 real references remain pending.

## Source and record contract

`userspace/gallium/opengpu_native_joint_build_source.h` exposes an opaque
create/destroy/describe API with C and C++ linkage. It keeps the original source
and every supplied Gallium resource alive after caller rebind or destruction.
The context/screen must outlive this lease. Uniform data and producer fences
remain the protected capture provider's responsibility. A returned description
contains borrowed const arrays; changing its value copy cannot change the
original compiled metadata or retained objects.

The original 52 uniform references are 48 UBO windows plus four retained default
copies, not 52 distinct GEM handles. Four code objects, original vertex/index
views, VS scratch and frame resources are additional references. API bindings
0–71 are reflected into each stage's original twelve logical blocks. VS/FS and
TCS/TES keep separate private tables of at most 24 mappings; 60 graphics-stage
blocks are never collapsed into a single table. This four-stage proof covers
48 blocks and binding 71, with 1024 default components and 16-KiB UBO windows.

The encoder directly creates these canonical unbound records:

| Record | Header magic/version | Source |
| --- | --- | --- |
| GNQ | `0x474e5104/05/06`, version 2 | Actual TES domain, explicit caller catalog/range identities |
| GNT | `0x474e5407/08/09`, version 7/8/9 | Original linked TCS layout, separate TES exports/domain/spacing and both boundary maps |
| GNF1 | `0x474e4601`, header version 1, frame version 2 | Checked typed limits and original frame views |
| GND1 | `0x474e4401`, header version 1/input version 2 | Original VS/input/index/scratch views and original draw fields |
| GFS | `0x47465301`, header version 1, shader ABI8/9 | Actual opaque FS metadata/code, flat/centroid/depth/control contract |

There is no GNC3 fabrication, GNF3 retag, metadata-only shader relabel or new
wire field. The GNF1 checked planner accepts typed layout limits, requires a
null capability pointer and does not authorize BUILD. Frozen GNC2/3 and GNC5
entry points retain their own exact capability gates. All source PC, root,
scratch and input addresses are zero; protected BUILD constructs the actual
private RX/R-NX/RW graph and relocations.

GNT/range ordinals come from the caller's explicit invocation/range directory,
not draw tokens or shader instructions. Coverage must be complete, ordered and
instance-major, with exact FIRST/CONTINUATION/LAST flags. The original draw's
index type/window, signed baseVertex and baseInstance are preserved. Restart,
indirect execution and sampler provenance are not enabled by this API.

Empty forms are distinct. A GNQ with no invocations has no GNT/GVS/GND/state
arrays. A real zero-work invocation retains its original GND and one
FIRST|LAST GNT/GVS with patchCount zero and a canonical empty slice. Neither
form manufactures a successful child completion or dummy shader output.

## Independent CPU graph proof

`test_native_joint_build.py` is an explicit opt-in actual Mesa/Linux build. It
compiles the original linked ESSL once through Mesa 22.3.6 and the production
four-stage opaque program/source APIs. The same winsys GEM allocations used by
the real source objects are registered with the kernel fixture; no file of
invented handles or offline replacement registry is imported. The fixture
models OS memory/GEM primitives and completed-producer prerequisites. Its
verified full 2-CU/16-lane/eight-warp policy is selected independently inside
the kernel test translation unit, never by producer metadata.

The test calls the actual joint BUILD, prepare, publish, page-table walks and
whole-owner retirement. An independent literal ABI oracle checks GNQ directory
identities, GVS/GND links, full original code bytes, GUR1/GUR3/GUB identity and
mapping, all uniform words including the last word, and the independently fixed
uint data formulas. It checks actual input/index/frame physical windows,
logical limits, RX versus R-NX/RW PTE flags, precise FS/VS scratch prefixes and
unchanged whole original allocations. The original caller/defaults/cache/
program are released before graph capture; rebinds cannot substitute newer
windows. The shader programs are validated and copied, never executed.

The actual sources cover ABI8 and ABI9 full resources, sparse location 15,
matrix/array packing, dynamic TCS patch stores, SIMT stores and TES levels plus
PATCH arrays, as well as real triangle and quad TES declarations. Every source
exercises normal ranges, real zero-work invocations, and a no-invocation GNQ.
The actual second draw uses binding 15, U16 indices, baseVertex −1 and
baseInstance 7. All sixteen nonuniform binding-record offsets are checked
against published literal offsets. Bad counts, range coverage, frame/view
bounds and partial construction must release their references.

Missing primitive/sample permissions, incomplete producers, failed copies or
resolves, bad entries/identities and source/writer aliases are rejected with
unchanged output and actual owner retirement before reuse. Independent source
controls remove the opaque lease retain, substitute FS uniforms for VS, and
retag a real triangle source as isolines. They must respectively trigger the
real ASan lifetime error, independent GUR data assertion and domain assertion.
The original backing-retain/stride/store-params/store-bound controls and old
308/301, v1 and v2 independent-emitter byte comparisons remain in the same run.

## Final validation

The final immutable Linux/GCC 13.3/ASan+UBSan run compiled 53 translation units
fresh, with zero object reuse. Its 82 compiler/linker/fixture groups took
53.92 seconds summed (longest 7.748 seconds); each group retained the
180-second deadline. Child `ru_maxrss` was 353008 KiB, not an aggregate RSS
measurement. The whole container was capped at 512 MiB memory with no extra
swap, CPU 1 and no network; the cgroup peak reached the cap through all charged
memory, and `OOMKilled` was false. All 8101 external identities and 344 actual
MMD dependencies were bound before/after, along with source, generated
includes, objects, binaries and ESSL. A real C++ TU linked and called all three
new C API symbols. The seven original/new source controls failed at their
specified assertions or ASan lifetime diagnosis, not through timeouts.

The eight actual linked cases passed the three distinct normal/zero-work/
no-invocation BUILD graphs. Old 308/301 and independent v1/v2 emitter bytes were
unchanged. Separate sanitized frame and ordinary-graphics CPU checks retained
the old gates; the frame test also compares direct typed GNF1 bytes with the
old canonical encoder and rejects invalid limits atomically. No Scala/JVM,
DUT, ISA execution or public route was used for this block.

The final source also passed an ARM64 Linux 7.2.0 module Kbuild using GCC
15.2.0 and GNU Make 4.4.1, `-j1`. All 29 core objects, `gpu_drv.mod.o`,
`.module-common.o`, MODPOST and `gpu_drv.ko` completed without warnings or
errors in 17.507 seconds, with maximum sampled aggregate RSS of 357360 KiB
under the 180-second/512-MiB process-group guard. The ELF64 AArch64 relocatable
module has vermagic `7.2.0 SMP preempt mod_unload aarch64` and the actual
`opengpu_protected_tessellation_joint_build`,
`opengpu_protected_tessellation_joint_prepare` and
`opengpu_private_snapshot_check_prepared_joint` symbols. Before/after guards
covered 157 original driver inputs and their read-only copies, 12690 kernel
inputs including ARM64 headers, and 50 tool inputs. All 840 actual `.cmd`
dependencies belonged to those inputs or the private generated output.

The module was never loaded. Its Makefile does not link the pure
`opengpu_native_frame.c` planner, so this Kbuild result covers module
integration, while the separate 53-TU Linux run above compiled and exercised
the planner. Neither result establishes kernel runtime or shader execution.

The complete host lane also passed on the same source: Python 3.14.7 and
Apple Clang 16, sanitizers enabled, 152 pytest tests and 757 unittest executions
across 89 summaries (not unique test counts). It finished in 745.29 seconds
with maximum sampled group RSS of 532800 KiB under the 1500-second/768-MiB
guard. All 3149 tracked non-document and tool inputs, index and HEAD remained
stable. Six environment skips remain: four unavailable Mesa environments,
the unavailable Clang OpenGPU target, and the optional FlashSim check. The
separate actual Linux Mesa run above covers this block's explicit Mesa path.

## Reproduction

Use the pinned original Mesa/SDK inputs and immutable Linux tool image. Do not
rebuild Mesa libraries. From this source checkout, create a private output
under `.artifacts/`, then extract the two independent original emitter sources:

```sh
mkdir -p .artifacts
OUT=$(mktemp -d "$PWD/.artifacts/joint-build.XXXXXX")
git show a794c93c2b865a3f4a9069656ff39b5a329c1ca8:userspace/gallium/opengpu_native_uniform_emit.c > "$OUT/reference-uniform.c"
git show 71a798ccdca6e1bf2c81bf827a39dc316c5fa63a:userspace/gallium/opengpu_native_tess_uniform_emit.c > "$OUT/reference-tess.c"
```

In the existing read-only Mesa 22.3.6/SDK environment, run:

```sh
python3 -B scripts/test_native_joint_build.py --sanitize --negative-controls \
  --build "$OUT/build" --legacy-source "$OUT/reference-uniform.c" \
  --tess-baseline-source "$OUT/reference-tess.c"
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_frame.py
```

The Mesa command requires `OPENGPU_MESA_DIR`, `OPENGPU_MESA_BUILD`,
`OPENGPU_DRM_INCLUDE`, the original SDK `LD_LIBRARY_PATH`, and
`OPENGPU_MESA_LINK_LIBS` naming zlib and the original expat library. Use image
`sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72`,
`--memory=512m --memory-swap=512m --cpus=1 --network=none --read-only`, mount
source/Mesa/SDK read-only and only the private output read-write. Each compiler,
linker or fixture group has a 180-second process-group deadline; exit of the
leader does not replace descendant cleanup. The runner binds source/generated
inputs, actual MMD, objects/binaries/ESSL and all external Mesa/DRM/link inputs
before and after their use. Do not count diagnostic `--reuse-objects` as fresh
compilation. Release all consumers before removing completed private outputs.

For the module check, copy the current driver top-level `.c`, `.h` and
`Makefile` inputs into a private read-only source directory, with distinct
output and temporary directories. Against the prepared ARM64 Linux 7.2.0
kernel tree and cross-tools used above, run:

```sh
TMPDIR="$PRIVATE_TMP" /opt/homebrew/bin/gmake \
  -C /Users/duckdonald/workspace/arti-work/arti-linux-build \
  M="$PRIVATE_SRC" MO="$PRIVATE_OUT" ARCH=arm64 \
  CROSS_COMPILE=/opt/homebrew/bin/aarch64-linux-gnu- \
  KCONFIG_NOSILENTUPDATE=1 -j1 V=1 modules
```

Run this command in an owned process group with a 180-second deadline and
512-MiB sampled aggregate RSS bound. Bind original/copy driver inputs,
kernel source/build configuration, ARM64 headers, scripts and tools before
and after; verify actual `.cmd` dependencies against that closure. Inspect
the module with the cross-tool `readelf` and `nm`; do not load it. Confirm
the group has exited before deleting the private directories.
