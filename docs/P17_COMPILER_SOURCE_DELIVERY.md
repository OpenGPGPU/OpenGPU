# P17 compiler and private source delivery

[PR #826](https://github.com/OpenGPGPU/OpenGPU/pull/826) replays six logical source commits onto protected `main` after
[#825](https://github.com/OpenGPGPU/OpenGPU/pull/825), base
`4a574e0410c240bbbe32f474f03f274732036c88`. It delivers original Mesa TCS/TES
compilation, dynamic TES patch reads, language input-patch IDs, ordinary no-GS
FS8/9 compilation, and private program/source/draw lifetimes. It does not close
P17 or change the alignment completion count of 3/40.

| Original logical commit | Delivery commit | Scope |
| --- | --- | --- |
| c34a84d4b | 84c42b6fc | Original linked TCS/TES compiler |
| bcdbdfd70 | 8084b2966 | Private code/default/UBO views and callbacks |
| b5c0eb8a1 | 4994653cd | Dynamic TES patch spans |
| 25fe87959 | 4aea27070 | Independent ordinary FS8/9 compiler |
| 3f537ebcf | 4fad742c8 | Language input-patch ID compiler |
| 332284f82 | 2af7f4576 | Opaque programs, source leases and derived draw records |

All 114 paths in this six-commit union were compared to original committed
P17 `332284f82`. Only three script differences remain: the mainline strict
producer macro/include closure and its two tests, and mainline driver checks
for the original vertex plan and protected tessellation submission. Those
checks were retained. The delivery changes no workflow, factory, public
capability, UAPI, GNQ/GNT layout or protected enablement.

## Complete host validation

`PATH="/opt/homebrew/bin:$PATH" PYTHONDONTWRITEBYTECODE=1
OPENGPU_TEST_SANITIZERS=1 ASAN_OPTIONS=detect_leaks=0 bash scripts/test_scripts.sh`
passed on the exact delivery source in 748.35s, with 504640KiB maximum sampled
aggregate child RSS. The serial guard allowed 1500s/768MiB and blocked real
JVM tools. Python3.14.7/pytest9.1.1 and Apple Clang16 were used. All 3106
tracked non-document inputs and source HEAD were unchanged before/after.
The run included 152 pytest cases (0.36s) and all 21 strict producer closure
checks (21.262s), preserving main's recursive macro/include verification.

Six environment skips remain explicit: four Mesa Gallium fixtures because
`OPENGPU_MESA_DIR` was unset for this host run, the missing OpenGPU Clang
target, and the optional real FlashSim bound-assertion probe without
`OPENGPU_FLASHSIM_DIR`. The independent Mesa results below do not turn those
skips into executions. No real JVM, RTL or shader executed in the host run.

## Exact delivery CPU results

The results below used delivery source `2af7f4576`, not a changing development
worktree. Original Mesa22.3.6 libraries and SDK were read-only and reused in
immutable image
`sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72`.
Each group had a 180-second deadline, 512MiB memory with memory-swap also
512MiB, one CPU and no network. Before/after checks kept 8101 external and
1104 GPU/source/baseline input identities unchanged. Actual compiler MMD
closures were checked against these inputs and private generated sources.

| Fresh original-Mesa group | Result | Elapsed within wrapper |
| --- | --- | ---: |
| Language v3, old v1/v2 regressions and independent old-emitter byte comparison | PASS; 29 translation units, 225 unique MMD inputs | 35.11s |
| Both ordinary FS8/9 frontends and semantic controls | PASS; 15 frontend calls, 29 original programs, 164 unique MMD inputs | 25.11s |
| Five program/source/draw lifecycle cases and two precise source controls | PASS; 30 translation units, 238 unique MMD inputs | 37.96s |

These times include the actual compilation, linking, cases and final input
checks; container startup is excluded. No sampled aggregate RSS was recorded
for these container groups. An initial private-wrapper attempt stopped after
3.40s because its subprocess hook also intercepted the process-group `ps`
probe. The hook was corrected before the fresh passing build; this was not a
compiler or production-source failure.

The three language cases produced TCS/TES word counts 252/177, 388/177 and
304/229. All six new programs passed this delivery's independent P16 v3
validator and actual private RX/MMU snapshot. Old v1/v2 programs remained
byte-for-byte equal to the independently renamed original v2 emitter. The
same build checked five-stage 60-block reflection; that is reflection only,
not a complete five-stage program/resource execution result.

All 29 new ordinary FS8/9 compiler outputs and all five new opaque-program
outputs independently passed the delivery P16 validator/private RX/MMU
consumer. The five program-owned cases preserved exact metadata, nonzero
entry, read-only inputs/tails, failure-atomic capture and retirement/reuse;
missing primitive/sample/uniform permission was rejected. The private test
profiles are explicit offline permissions, not hardware discovery.

The independent host consumers used Apple Clang16 ASan/UBSan, serial process
groups with 180-second/512MiB aggregate RSS limits, and 1041 source/program
input identities unchanged before/after:

| Group | Elapsed | Maximum sampled aggregate RSS |
| --- | ---: | ---: |
| Parser/capacity guards, 40 malformed language fixtures and five semantic source controls | 12.18s | 108192KiB |
| Language validator/RX, three groups | 3.45 / 3.48 / 3.43s | 218480KiB maximum |
| 29 ordinary compiler outputs, validator/RX/MMU | 3.04s | 223664KiB |
| Five opaque-program outputs, validator/RX/MMU | 2.92s | 211344KiB |

Program/source lifetime, exact cases and older scoped results are documented
in [NATIVE_GRAPHICS_PRIMITIVE_LIFECYCLE.md](NATIVE_GRAPHICS_PRIMITIVE_LIFECYCLE.md),
[NATIVE_FRAGMENT_PRIMITIVE_COMPILER.md](NATIVE_FRAGMENT_PRIMITIVE_COMPILER.md),
[TESSELLATION_LANGUAGE_FRONTEND.md](TESSELLATION_LANGUAGE_FRONTEND.md) and
[TESSELLATION_MESA_UNIFORMS.md](TESSELLATION_MESA_UNIFORMS.md).

## Reproduction and remaining gates

Use the immutable environment and source baselines in the linked documents.
Run these independently inside that bounded environment with a fresh writable
output overlay and read-only sources/libraries:

```sh
python3 -B scripts/test_tessellation_uniform_frontend.py --sanitize --language \
  --regressions --baseline-source "$OUT/reference/original-tess-v2.c" \
  --build "$OUT/tess"
python3 -B scripts/test_native_fragment_v2.py --sanitize --negative-controls \
  --legacy-source "$OUT/reference" --build "$OUT/fragment"
python3 -B scripts/test_native_graphics_v2.py --sanitize --negative-controls \
  --legacy-source "$OUT/reference/opengpu_native_uniform_emit.c" \
  --build "$OUT/graphics"
```

The tessellation frontend needs an external owned-process-group deadline and
input/MMD checks; the bare command alone does not impose those checks. Its
baseline is the original emitter from `c6eddf7d5`; ordinary baselines are from
`bcdbdfd70`. The fragment and graphics scripts contain input/MMD and per-process
deadline checks. Before deleting output programs, run the P16 consumers with
the exact layouts shown in the language document and `--primitive-artifacts`
for FS8/9. Ended build/program/reference/log data are temporary and are removed
after the last consumer and open-file/process checks.

The linked VS/TCS/TES/FS four-stage compiler and immutable 52-resource caller,
complete five-stage 65-resource lifecycle, indirect TCS patch-array stores,
original GUR graph, joint protected
BUILD/submit/retirement, original-Mesa execution on both full CUs, three-domain
images/GNY2, actual API reporting and 2-CU 640×480/60-FPS qualification remain
pending. Ordinary VS/FS lifecycle checks do not close those gates. Disabled
`Scala regression` is never enabled or dispatched.
