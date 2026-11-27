# Protected five-stage geometry BUILD

GNQ7/8/9 version 3 is a separate protected entry for isoline/triangle/quad
geometry attachments. GNQ1–6, GNT7–9 and GVM2 retain their existing formats and
validators. The [word contract](abi/protected-tessellation-joint-geometry-v1.json)
is explicit little-endian data; Scala Bundle packing is not a wire ABI.

The root retains the original sequence/frame/directory structure and adds an
exact GGA1 table. Its 64-byte header binds count and context/pass/generation.
Each 1,024-byte entry binds sequence index, state handle, domain, GS command ID,
GS task/input-primitive bases, independent input/output sequences, a separately
bounded GNG1 record and 64 TES-packed-to-GS-input mappings. Entry flags and words
80..179 are zero: this BUILD route does not admit transform-feedback descriptors,
resources or permissions. Linked fragment shaders are limited to GS-aware ABI5
and ABI7. Their full 16-varying/four-color prefixes are 47,104 and 48,640 bytes;
smaller ABI5 prefixes are rejected before any source copy.

BUILD captures every nested input before invoking callbacks, checks exact source
and identity relationships, rejects physical cache-line aliases across the five
stages, VS scratch and frame resources, and appends private RO/NX program,
uniform, GNG, GGA, directory, sequence, state, frame and root segments. Prepare
then validates the placed owner graph, actual GGA/GNG links, executable ranges,
uniform roots, fragment ABI and every segment/resource envelope. Full-record
scratch is heap allocated; allocation failure closes publication. Output remains
unchanged on failure and the whole partially built owner must be retired.

The CPU fixture exercises all three domains with ABI5/7, independent catalog
permutations, semantic maps, private MMU placement, source/identity rejection,
ABI10 rejection, feedback-reserved rejection, physical alias rejection,
post-prepare corruption and heap-allocation failure. It does not execute shaders
or prove GPU behavior.

Subsequent GPU PRs #961 and #962 added the no-TF ABI5/7 geometry RTL/readers and a
direct typed-isoline `full5`/`pointcoord` two-CU retained Verilator replay. Those
results are separate from this BUILD/prepare contract: they do not connect the
GNQ7/8/9 protected host, driver submission or public discovery route.

The three thin `TessellationSystem*JointGeometryVertexImage` parents now connect
the GNQ7/8/9 controller, typed range and native-geometry image internally, with
default-closed admission and real invalidation peer ports. Their default-closed
3/3, focused component/elaboration 29/29 and GNQ7 isoline clear-only two-frame
checks passed; see [joint parent results](TESSELLATION_JOINT_NATIVE_PARENT.md#thin-gnq789-geometry-parents).
They do not instantiate a shared image-bank/mux outer owner or enable the
protected host, driver, public submission, TF or ABI10.

## Validation

- The generated ABI/schema suite passes all 9 tests, including exact header
  regeneration and fail-closed schema mutations.
- The Clang ASan/UBSan owner/MMU runner passes the existing joint suite and all
  three geometry domains. It compiles 28 translation units, checks 126 distinct
  dependencies and 397 consumed inputs, and rejects all 16 existing plus 9 new
  geometry semantic controls. The same positive fixture passes Ubuntu GCC13 with
  `-O2 -Wall -Wextra -Werror` and ASan/UBSan.
- The sanitizer host lane passes every `scripts/test_scripts.sh` command except
  the optional LLVM ISA smoke. That smoke already passed on the unchanged pinned
  LLVM `2ba9912b981d8819ffecda059cc41872f986bcf3` in GPU PR #957; its ended build
  tree was not retained, so this delivery does not claim a fresh LLVM rebuild.
- A fresh ARM64 Linux 7.2/GCC 15.2 Kbuild compiles all 29 core objects, including
  `opengpu_protected_tessellation_joint_geometry.o`, plus both module helpers,
  MODPOST and `gpu_drv.ko`. `-Werror=frame-larger-than=2048` passes. The module was
  not loaded.

## Limits

This delivery does not enable discovery, a getter, ioctl, queue, host submission,
transform feedback or an execution route. It does not close P17, P39 or P40 and
does not replace their actual CU/frame, range/image ownership or product checks.
No Scala/RTL, actual CU, frame, image, fault/drain or product-FPS result is
claimed by these CPU and Kbuild checks. The later #961/#962 direct typed-isoline
evidence does not remove the protected-host, three-domain, fault/cancel or
product limits described above.

## Reproduction

```sh
python3 -B scripts/generate_protected_tessellation_joint_geometry_abi.py --check
python3 -B scripts/test_protected_tessellation_joint_abi.py
python3 -B scripts/test_protected_tessellation_joint.py --sanitize --negative-controls
OPENGPU_TEST_SANITIZERS=1 PYTHONDONTWRITEBYTECODE=1 bash scripts/test_scripts.sh
```

The full host command requires an explicit source-matching LLVM build for its
ISA-smoke step. Without that optional build, record the omission explicitly; do
not weaken the smoke's source/build pin checks. For the module build, stage the
current top-level driver inputs under `.artifacts`, then run:

```sh
stage=$(mktemp -d "$PWD/.artifacts/joint-geometry-kbuild-XXXXXX")
cp driver/*.c driver/*.h driver/*.inc driver/Makefile "$stage/"
gmake -C /path/to/configured/linux-build M="$stage" ARCH=arm64 \
  CROSS_COMPILE=aarch64-linux-gnu- \
  KCFLAGS='-fstack-usage -Werror=frame-larger-than=2048' -j1 gpu_drv.ko
rm -rf "$stage"
```

The owner/MMU runner bounds each process group and removes its temporary build
inputs after completion. Keep the broad `Scala regression` workflow disabled.
