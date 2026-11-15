# Captured invocation and private program source delivery

[PR #825](https://github.com/OpenGPGPU/OpenGPU/pull/825) rebases six original
logical commits on merged PR #824. It adds
unbound GNT source validation, complete original draw/invocation capture,
internal tessellation program profile 3, immutable native program authority,
and independently owned fragment ABI 8/9 program validation. It enables no
public capability, getter, UAPI or native factory.

The exact delivery code is `65a254a46`. All 329 driver C/header/fixture-include
files and the driver Makefile match the validated original topic byte-for-byte.
Current main's strict producer-macro checks and protected CI policy remain.

## Final component results

| Boundary | Actual validation |
| --- | --- |
| Unbound GNT7/8/9 | Clang/GCC ASan/UBSan positive cases and 11 semantic negative controls; source words 509..511 must be zero, bound records keep their separate exact-address rules |
| Original outer invocation | Actual owner/MMU, complete catalogs and ranges captured before callbacks; Clang/GCC positive cases and 10 semantic controls, including continuation draw identity |
| Tessellation program 3 | Clang/GCC actual validator/RX/MMU fixtures, 24 retained plus 10 new semantic controls; strict version/permission/SIMT scratch/patch-layout separation |
| Native program capture and FS8/9 | Clang/GCC validator/private-owner/MMU fixtures and 7 semantic controls; full 128/16-varying/4-MRT input/output bounds, exact source bytes, entry, permissions and retirement |
| Original Mesa programs | Six original TCS/TES language programs and 29 original FS8/9 raw/checked programs consumed by the independent driver validator/RX/MMU fixtures; missing or old profiles reject |
| Complete kernel module | Fresh ARM64 Linux 7.2/GCC 15.2 build: 28 core objects plus 2 helpers, MODPOST and `gpu_drv.ko` linkage; 17.50s, no warnings/errors; 154 driver and 16287 shared kernel/tool inputs unchanged |

The module build used a serial 180-second/512-MiB process-group guard, with
348480 KiB maximum sampled aggregate RSS. It was not loaded or executed.
Focused CPU groups used 180-second/512-MiB limits. Immutable GCC 13.3 runs used
one CPU, equal memory+swap limits, read-only inputs and no network. Ended
programs, build objects, modules, logs and private guards were removed after
process/open-file/consumer checks. Detailed results and commands are in
[outer capture](TESSELLATION_VERTEX_OUTER_PLAN.md),
[language profiles](TESSELLATION_LANGUAGE_PROGRAM.md),
[native capture](NATIVE_PROTECTED_SUBMISSION.md#combined-mainline-source-validation)
and [fragment profiles](NATIVE_FRAGMENT_PROGRAM_PROFILE.md).

## Complete host check

The exact delivery source passed the complete original `scripts/test_scripts.sh`
entry with ASan/UBSan enabled in 724.24s. All 3017 guarded non-Markdown source,
test, schema and configuration inputs and HEAD stayed unchanged. The run
included 152 pytest cases and 21 strict producer cases, preserving main's
literal and recursive producer-macro closure checks. It executed no JVM, RTL,
GPU model or guest.

This run used a serial 1500-second/768-MiB aggregate-RSS guard; maximum sampled
group RSS was 532208 KiB (519.73 MiB). The prior 512-MiB attempt was terminated
at 530480 KiB after 595.07s and is not a pass at that budget. An earlier 510.04s
attempt stopped because its Python environment lacked pytest. The passing
environment was Python 3.14.7/pytest 9.1.1 and unchanged Apple Clang 16.

Six existing environmental skips remain: four Mesa fixtures (transfer,
constants, index buffers and resource shapes) without `OPENGPU_MESA_DIR`, the
missing OpenGPU Clang target, and the optional real FlashSim bound-assertion
check without `OPENGPU_FLASHSIM_DIR`. None is an executed Mesa/DUT result.
All owned processes and raw outputs were cleaned after terminal/consumer checks.

The outer GVM2 map is an internal read-only source association. It is not a
new GNQ dialect or GPU reader. Original catalog indices, API invocation IDs,
range ordinals and sequence/task tags remain independent. Existing BUILD still
uses GNT4/5/6/static TES; joint GNT7/8/9 BUILD, frame-wide aliases and real
protected submission remain unqualified. Compiler requirements do not grant
verified owner permissions. FS8/9 does not derive authority from GS or GSS1.

These CPU and module checks do not establish actual VS/TCS/TES/FS execution,
two-CU/three-domain replay, complete resource coexistence, physical timing or
the 640x480/60-FPS target. P16/P39/P40 remain incomplete. The work-plan count
stays 3/40; a partial source delivery is not a completed target work package.

## Reproduction

```sh
python3 -B scripts/test_tessellation_vertex_source.py --sanitize --negative-controls
python3 -B scripts/test_tessellation_vertex_plan.py --sanitize --negative-controls
for group in language legacy-first legacy-second; do
  python3 -B scripts/test_tessellation_program_snapshot.py --sanitize \
    --negative-controls --negative-control-group "$group" || exit
done
python3 -B scripts/test_native_program_snapshot.py --sanitize --negative-controls
PATH="/opt/homebrew/bin:$PATH" PYTHONDONTWRITEBYTECODE=1 \
  OPENGPU_TEST_SANITIZERS=1 ASAN_OPTIONS=detect_leaks=0 bash scripts/test_scripts.sh
```

Use explicit offline permission flags and the original compiler outputs for
the optional byte-input checks documented above. Reproduce the complete module
using a fresh private source copy/MO directory and the configured ARM64 kernel,
retaining its shared-input guards. Delete temporary outputs after consumers
finish. Scala regression remains disabled and must not be enabled or dispatched.
