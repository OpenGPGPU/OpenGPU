# Captured original-draw outer plan

`opengpu_tessellation_vertex_outer_append` implements an internal version-2
catalog/invocation/range BUILD contract. It returns an owner-private GVM2 map
segment ID, never a GPU address. GVM2 is not a GNQ dialect: no reader, ioctl,
factory or public capability admits it. The existing closed profile and old
GNQ/GNT/GVS contracts retain their meanings.

## Original authority and exact coverage

The source supplies a tightly packed original GND1 catalog and complete
`drm_opengpu_native_draw_plan` array. Plans may be permuted; each `draw_index`
must independently index exactly one original catalog record. Sequences do
not index this array by their own ordinal. Unused catalog entries remain
canonical and unsupported indirect, sampler and texture-root metadata fails
closed even when unused.

The builder captures the entire catalog, complete native plans, ordered API
invocations, all range plans and callbacks before invoking any callback.
Invocation IDs are independent 64-bit values, including zero; reusing a
catalog index does not mean continuation. Each invocation declares its
original catalog index and input patch size. Ranges independently bind
sequence index, invocation index, original draw index and range ordinal.
`FIRST` starts the invocation, `CONTINUATION` preserves it, and `LAST` closes
it. Duplicate/missing indices, reused invocation IDs, inconsistent boundaries,
nonzero reserved fields and overflowing counts are rejected.

Coverage follows the original draw in instance-major, input-patch order.
First ordinals must align to the declared input patch size. No gap, overlap,
backtrack or skipped instance is admitted. A range spanning multiple instances
must cover complete instances. Trailing vertices insufficient to form an input
patch remain incomplete; they are not converted into a partial patch. Zero
work uses one canonical empty `FIRST|LAST` range. This is metadata validation,
not CPU evaluation of vertices, tessellation or indirect commands.

Each range calls the actual GVS source builder with its independently selected
original GND and native plan. Original code/entry, GUR1 defaults and all twelve
blocks, sixteen attribute bindings, index view, base vertex, base instance,
divisors and original scratch remain intact. The existing private PATCHES
restart canonicalization is retained. GVS/GND/GUR1 are R/NX, original VS code
is RX without write permission, original inputs are R/NX, and scratch is
RW/NX. Full native plans are captured, but their FS fields confer no permission
in this builder.

The caller must retain original GEM/context references, hold reservations,
complete producer dependencies and exclude CPU writes during capture. Errors
leave the output unchanged and require retirement of the entire owner,
including after partial appends. These requirements are not replaced by
holding a copied plan.

## Internal map

The map has a 64-byte header and one 64-byte entry per sequence, sorted by
sequence index. All words are little endian.

| Words | Header |
| --- | --- |
| 0–3 | Magic `0x47564d02`, total bytes, version 2, entry bytes 64 |
| 4–6 | Original catalog, invocation and range counts |
| 7–9 | Context, pass, generation |
| 10–14 | Attributes 16, bindings 16, varyings 16, warps 8, CUs 2 |
| 15 | Zero |

| Words | Entry |
| --- | --- |
| 0–1 | Sequence index, original draw catalog index |
| 2–3 | Explicit invocation ID, low/high words |
| 4–5 | Boundary flags, range ordinal |
| 6–9 | First instance, instance count, first input-patch ordinal, patches per instance |
| 10–11 | First owner task ID, patch count |
| 12–14 | Private GVS base and exclusive 33-bit limit |
| 15 | Zero |

The source-map checker requires all three GVS address words to be zero.
The bound-map checker instead requires a 64-byte-aligned exact 512-byte view,
including a legal exclusive endpoint of 4 GiB. Real typed owner relocations
produce those fields. Both reject a continuation that changes the invocation's
original catalog index. Syntax checks do not prove original draw/resource
authority or exact coverage; the captured outer BUILD performs those checks.

## Validation and reproduction

Apple Clang 16 and immutable ARM64 Linux GCC 13.3 passed the actual private
owner/MMU fixture with ASan/UBSan and ten independent source-copy semantic
negative controls (52.70 and 65.73 seconds). The Clang process group's largest
sampled RSS was 359248 KiB, not an absolute physical-memory peak. Linux used
one CPU, a 512 MiB total memory/swap cap, no network and read-only source.

The independent invocation recipe selects catalog indices `[1,0,1]`, including
split continuations and a distinct later invocation reusing the same GND.
Checks cover every published root/descriptor word, exact private map words,
actual permissions, indexed input and full resources, empty/incomplete tails,
unused catalogs, callback mutation, and allocation/copy/resolve failure with
unchanged output and actual owner retirement. The first callback destroys all
caller containers and callbacks; later ranges still use captured originals.
Negative controls must fail their specific append, rejection, canonical-map or
MMU-permission assertion after compiling successfully.

```sh
python3 -B scripts/test_tessellation_vertex_plan.py --sanitize --negative-controls
IMAGE=sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72
TASK_DIR=$(mktemp -d "$PWD/.artifacts/outer-plan.XXXXXX")
mkdir "$TASK_DIR/tmp"
docker run --rm --init --memory=512m --memory-swap=512m --cpus=1 \
    --network=none --read-only --ulimit core=0 --user "$(id -u):$(id -g)" \
    --mount "type=bind,src=$PWD,dst=/src,readonly" \
    --mount "type=bind,src=$TASK_DIR,dst=/src/.artifacts" --workdir /src \
    -e CC=gcc -e PYTHONDONTWRITEBYTECODE=1 -e TMPDIR=/src/.artifacts/tmp \
    --entrypoint python3 "$IMAGE" \
    scripts/test_tessellation_vertex_plan.py --sanitize --negative-controls
# Check unchanged source and no remaining consumers before removing own output.
rmdir "$TASK_DIR/tmp" "$TASK_DIR"
```

The normal host lane invokes this suite once through
`scripts/test_scripts.sh` → `scripts/test_driver.py`. No Scala regression
workflow is enabled or dispatched.

A fresh full module build of the source including the new Makefile object
passed against configured ARM64 Linux 7.2.0 with AArch64 GCC 15.2.0. Kbuild
compiled all 28 driver objects plus two module helpers, completed MODPOST and
linked an ELF64 AArch64 relocatable `gpu_drv.ko`, with vermagic
`7.2.0 SMP preempt mod_unload aarch64`. All three new outer-plan symbols and
the producer-facing unbound GNT checker were present. Standard warnings were
unchanged; no warning or error was emitted.

The build used read-only private driver copies, separate M/MO outputs, `-j1`,
a 180-second deadline and a 512 MiB aggregate process-group RSS guard. It
completed in 16.81 seconds; the largest sampled group RSS was 332832 KiB, not
an absolute physical-memory peak. Driver originals/copies and shared kernel
configuration, generated headers, symbols, scripts and actual tools matched
before/after by content and file metadata. Process, open-file and external
symlink consumer checks preceded removal of all task outputs.

Use the guarded source-copy procedure in
[the source-root contract](TESSELLATION_VERTEX_SOURCE.md), with the existing
configured kernel and compiler, without preparing or modifying shared inputs:

```sh
TMPDIR="$TASK_DIR/tmp" /opt/homebrew/bin/gmake \
    -C /Users/duckdonald/workspace/arti-work/arti-linux-build \
    M="$TASK_DIR/src" MO="$TASK_DIR/out" ARCH=arm64 \
    CROSS_COMPILE=/opt/homebrew/bin/aarch64-linux-gnu- \
    KCONFIG_NOSILENTUPDATE=1 -j1 V=1 modules
```

Module loading and kernel runtime remain unverified. Linking does not prove
Linux admission, hardware execution or product qualification.

Whole-graph frame/TCS/TES physical alias checks, original Linux admission and
reservation/retirement integration, GNT source relocations, a matching versioned
GNQ route, full parent execution and protected full-profile GPU qualification
remain pending. Input-patch language PrimitiveID, no-GS raster PrimitiveID,
viewport order sequence and dense bin index are distinct; this map creates no
shader builtin or draw/instance cursor implementation. No code here enables
discovery, submission, a factory or public tessellation support.
