# Tessellation language program profile

Internal program profile version 3 adds the independent
`owned_tessellation_info` permission. It is a verified-owner prerequisite,
not shader metadata authority, a hardware probe or a public capability.
Versions 1/2 require this new field to be false. Version 3 requires it to be
true even when the program does not read the language input.

| Profile | TCS | TES |
| --- | --- | --- |
| 1 | Original checked-patch contract | Original static-selection contract |
| 2 | Rejected | Original dynamic owned-patch contract |
| 3 | Version-1 patch limits; TES-only fields zero | Version-2 dynamic contract; owned patch reads, captured patch layout and zero static words required |

There is no generic `version >= 2` admission. TES export components remain
independent of its captured TCS patch layout. Existing 2048-word coupled patch
bounds, 512-instruction limit and physical lane/warp bounds remain unchanged.

CUSTOM0 function 37/funct3 0/rs2 field zero reads selector 6 only with version 3.
The selector must be a proved scalar constant. TCS accepts only selector 6;
inside SIMT it additionally requires source x10 and destination x0/x10. Each
arm and continuation must rematerialize x10 after the existing shared-scalar
boundary cleanup. A returned value is defined but unknown: it establishes no
selector constant, mask or patch-parameter authorization. TES selectors 0..5
retain their old meanings; selector 6 requires version 3. No GS layer,
primitive-ID output, emit or arbitrary memory permission is granted to TCS.

The production snapshot helper remains unchanged. Its actual CPU fixture
checks full-profile capture before the source callback, one source copy,
nonzero entry, opaque prefix/suffix preservation, exact private RX/no-W PTEs
and source mutation after capture. A rejected owner is retired before a fresh
healthy owner is constructed. A source-copy negative control removing profile
capture fails when the callback changes the original version/permission.

## Validation

Apple Clang 16.0.0 (clang-1600.0.26.6) and immutable Linux GCC 13.3
both passed ASan/UBSan positive fixtures,
all 24 retained semantic negative controls and 10 new controls. The new controls
cover legacy/new permission gates, selector authorization, SIMT source/target
and arm cleanup, returned-value non-authority, TCS/TES layout separation and
callback profile capture. Explicit version-3 CLI input passed; missing explicit
permission and a legacy profile with the new permission were rejected before
compilation. CLI inputs in this check were literal CPU test programs, not Mesa
captures or GPU results. All 625 consumed driver/scripts inputs matched before
and after each compiler run.

| Negative-control batch | Controls | Clang seconds | GCC seconds |
| --- | ---: | ---: | ---: |
| language | 10 | 35.08 | 68.93 |
| legacy-first | 12 | 41.41 | 81.18 |
| legacy-second | 12 | 41.29 | 81.40 |

Each owned group was limited to 180 seconds and 512 MiB. Host Clang's observed
peak group RSS was 223552 KiB. GCC ran with one CPU, 512 MiB memory equal to the
memory+swap limit, no network, read-only source/root filesystem and a private
writable artifact overlay. The pinned AArch64 image was
`sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72`
(`gcc (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0`).

The same driver source also passed a fresh complete ARM64 Linux 7.2 module
build with GCC 15.2.0: 28 core objects plus two module helpers, MODPOST and
`gpu_drv.ko` linkage, in 17.53 seconds with no warnings or errors. The serial
build used an external 180-second/512-MiB process-group guard; maximum sampled
aggregate RSS was 329424 KiB. The AArch64 module retained the outer-plan,
program-profile, validator and snapshot symbols. All 154 driver inputs and
16286 shared kernel/tool inputs remained unchanged; private build outputs were
removed after consumer checks. The module was neither loaded nor executed.

Factory/getter/UAPI/capability admission, the real Mesa/language producer and
actual version-3 CU/three-domain execution remain pending. These CPU checks do
not close the full 2-CU 640x480/60 FPS target or replace actual GPU execution.
Scala regression remains disabled and must not be enabled or dispatched.

## Reproduction

Run the batches serially with an external 180-second owned-process-group limit
and 512 MiB resource guard. Reproduce the reported budget; any later
different-budget run must report its actual limits and preserve the prior
bounded result:

```sh
for tess_group in language legacy-first legacy-second
do
  CC=clang ASAN_OPTIONS=detect_leaks=0 PYTHONDONTWRITEBYTECODE=1 \
    python3 -B scripts/test_tessellation_program_snapshot.py --sanitize \
    --negative-controls --negative-control-group "$tess_group" || exit
done
```

Repeat with `CC=gcc` inside the pinned image using
`--memory=512m --memory-swap=512m --cpus=1 --network=none --read-only`, a read-only
checkout mounted at its original absolute path, and an exclusive writable
`.artifacts` overlay/TMPDIR. No Git or network access is needed by this test.
The default `all` group retains every control; `legacy` retains the original
24-control group. Delete ended temporary outputs after their last consumer.

The optional offline byte-input interface defaults to version 1. Version 3
requires explicit `--tcs-program-version 3` and/or `--tes-program-version 3`
plus `--owned-tessellation-info`; it never infers permission from an opcode.
For a pair using version 3, supply original LE files and exact layouts:

```sh
python3 -B scripts/test_tessellation_program_snapshot.py --sanitize \
  --programs "$TESS_ORIGINAL_WORDS" --tcs-program-version 3 \
  --tes-program-version 3 --owned-tessellation-info \
  --tcs-layout 32 64 32 60 120 --tes-layout 0 8 \
  --tes-patch-layout 32 64 32 60 120
```

Those layouts describe the bounded literal fixture. Actual producer inputs must
supply their own exact captured layouts and source identities; this command is
not hardware qualification.
