# Internal ordinary-fragment program profiles

Native fragment ABI8/9 uses an independent verified-owner
`primitive_inputs` prerequisite. It is not inferred from compiler metadata,
geometry permission, GSS1 or a capability bit. ABI8 has ordinary PrimitiveID
without a layer or sample-plane array; ABI9 additionally requires the separate
sample-input prerequisite. A geometry-input profile cannot authorize either.
The new field must be false for all existing VS and FS1..7 profiles. Existing
production designated initializers clear it, so their admission does not grow.

`opengpu_native_shader_validate_words_primitive_inputs` is the explicit internal
entry. Versions1..7 delegate to the unchanged legacy validator with the new
prerequisite absent. Both old validator entry points still reject8/9.
The private program snapshot independently rejects missing/inconsistent owner
prerequisites before copying source bytes. It captures the full view/profile
before callbacks and preserves the original executable bytes and entry suffix.

At128 threads,16 varying slots and4 MRTs, ABI8 has73 input arrays and a46592-byte
prefix; ABI9 has76 and48128 bytes. Only the following18 color/depth/control
arrays are writable. PrimitiveID, sample inputs, other input arrays and staging
tails remain read-only. Full-width bounds,512 total source words, entry/branch
bounds, failure-output atomicity and whole-owner retirement remain enforced.

## Validation and limits

Clang16 and immutable Linux GCC13.3 ASan/UBSan passed the actual validator,
private-owner and MMU fixtures. Tests cover every input/output boundary at
full128/16/4 capacity, exact and one-byte-short prefixes,512/513-word limits,
legacy/new permission separation, failed outputs, nonzero entry, exact RX/no-W
maps and healthy reuse. Seven source-copy negative controls fail at the intended
assertions: view/profile capture, primitive/sample prerequisites, GS separation,
input/output boundary and legacy-profile separation.

The final Clang/GCC runs took23.02/25.26 seconds. Each used an external
180-second/512-MiB process-group guard; all731 driver/script inputs and HEAD
remained stable. Largest sampled Clang group RSS was221968KiB. GCC ran with
one CPU,512MiB memory equal to memory+swap, no network, read-only inputs and the
pinned AArch64 image
`sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72`.

A separate Clang ASan/UBSan run consumed29 original OGNSF002 programs produced
by both actual Mesa22.3.6 frontends and raw/checked-uniform backends. All passed
the independent validator and actual private RX/MMU owner in3.35 seconds,
with731 driver/script inputs and all29 source artifacts unchanged. Cases cover
PrimitiveID/Layer-zero, sample inputs, mask-only, helpers, dead static sample
use, slot15/four MRTs, depth/mask and12 UBOs plus1024 defaults. Program lengths
are41..180 words. Each retains an opaque prefix/suffix and a nonzero entry;
missing primitive/sample prerequisites reject before copy, and required uniform
loads reject without their independent permission. Resource-free programs pass
without incidental uniform permission.

The same production source passed a fresh complete ARM64 Linux7.2 module
build with GCC15.2:28 core objects plus2 helpers, MODPOST and `gpu_drv.ko`
linkage,17.50 seconds, no warnings or errors. The serial180-second/512-MiB
guard observed maximum sampled aggregate RSS348480KiB. All154 driver inputs
and16287 shared kernel/tool inputs remained unchanged. The resulting AArch64
module retained the native snapshot, tessellation validator/snapshot and
original vertex/outer-plan symbols. Private module outputs were deleted after
consumer checks. The module was not loaded or executed.

These are CPU protection-chain checks with explicit offline test permissions.
They do not prove complete GUR/resource-graph admission, a registered submit
path, actual shader execution, two-CU/three-domain replay or the640x480/60-FPS
target. Public capabilities, getters and existing draw encoders remain closed
to this new transport. Scala regression remains disabled and must not run.

## Reproduction

```sh
CC=clang ASAN_OPTIONS=detect_leaks=0 python3 -B scripts/test_native_program_snapshot.py \
  --sanitize --negative-controls
```

Repeat with GCC13.3 in the pinned read-only/no-network512MiB container. For
actual compiler inputs, regenerate the positive OGNSF002 artifacts using the
separate native fragment-v2 compiler harness, then supply only its29 positive
`raw-*`/`uniform-*` files, including the checked `.uniform` variants:

```sh
python3 -B scripts/test_native_program_snapshot.py --sanitize \
  --owned-primitive-inputs --primitive-artifacts "${NATIVE_POSITIVE_ARTIFACTS[@]}"
```

The explicit flag is offline fixture authority, never hardware discovery.
Neither it nor the artifact interface changes the production UAPI. Negative
control artifacts are not positive inputs. Delete generated programs and logs
after all explicit consumers finish; do not archive raw evidence.
