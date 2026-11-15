# Tessellation language input-patch ID compiler

The private TCS/TES frontend compiles original linked ESSL320
`gl_PrimitiveID` through Mesa22.3.6 TGSI `PRIM_ID` to CUSTOM0 `fn37`,
selector 6. It neither substitutes a constant, command/task tag or generated
TES sequence nor executes a shader on the CPU. GPU stage tags remain 4/5,
independent of Mesa's stage enumeration.

The compiler emits explicit version 3 layout metadata with
`owned_tessellation_info=1`. TCS retains the checked version1 patch dialect;
TES retains version2 dynamic owned-patch reads, a captured five-field TCS
patch layout, zero static selections, and independent TES export count.
Original version1/version2 APIs reject version 3 and any nonzero language-info
flag. Compiler requirements never grant the kernel's independently verified
profile permission or enable a public Gallium capability/provider.

Each language read rematerializes selector6 into scratch x10, reads into x10,
and immediately splats the result. This also applies inside each SIMT arm;
no scalar register restoration, persistent selector authorization or general
GS permission is assumed. TES's existing selectors 0..5 retain their meanings.
The 512-word program limit, capacity 128, complete patch bounds, 12 UBOs per
stage, API 72 binding namespace, 1024 default components, 16KiB UBO views and
flat shader linkage remain intact.

`opengpu_native_tess_language_program_create` is a separate original-source
entry. It captures layouts/maps and compiles both programs before allocation
callbacks, then places their original native bytes in real code GEM objects.
The existing source helper returns these code views and exact per-stage
original UBO/default views. Caller ownership keeps the source pipe/constants
alive until snapshot acceptance. The kernel must still capture its verified
profile and source views, create private RX/R-NX mappings, and retain the whole
owner through physical retirement.

## Final bounded CPU results

Clang ASan/UBSan and immutable Linux GCC 13.3 ASan/UBSan passed the real Mesa
token builder/parser guards: TCS/TES with system-value and input-semantic
PrimitiveID forms, 40 malformed stage/version/permission/index/channel/layout
fixtures, scoped SIMT reads, and the existing packet/capacity/UBO/default
checks. Five source-copy semantic controls independently rejected missing
permission, old-dialect language flags, a wrong selector, constant substitution,
and omitted SIMT-arm selector rematerialization. The final Clang run took
12.10s (sampled 102816KiB aggregate child RSS); GCC took 10.11s.

The original linked ESSL cases produced these native word counts:

| Case | TCS | TES | TCS/TES selector6 reads |
| --- | ---: | ---: | --- |
| language |252|177|4/4|
| language-conditional |388|177|8/4|
| language-full |304|229|4/4|

All three retain actual defaults and UBO expressions; the full case uses all 12
blocks in each tessellation stage. The original winsys/Gallium source fixtures
passed exact GEM code/default copies, distinct same-GEM views, retained
references and cleanup. These tests model OS/GEM primitives and do not supply
shader results.

The same immutable build passed the old three version1 and five version2
ESSL cases, including 32×60+120 and 32×64 patch boundaries. Every old native
program was compared byte-for-byte with the original version2 production
emitter source from commit`c6eddf7d5dcc92874ad3526f2a439d5fa06aac33`, compiled
as an independently renamed baseline. Five-stage60-block/API 72 reflection
also passed. The 29 MMD translation units had 216 unique dependencies, all bound
to guarded inputs or the exact generated standalone scaffold.

All six new original programs passed the production P16 version3 validator
and actual private-owner/MMU RX snapshot fixture, including nonzero entry and
unchanged whole-view bytes. Reusing those same bytes under TCS version1 or TES
version2 was rejected at the corresponding stage. These five consumer runs
used explicit offline verified-test profiles, each below 180s/512MiB; the
maximum sampled aggregate child RSS was 218544KiB. The 731 driver/script inputs
and six program inputs stayed unchanged during consumption.

Linux compilation used immutable image
`sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72`,
GCC 13.3, one CPU, 512MiB memory with memory-swap also 512MiB, no network,
read-only sources/libraries and a private writable artifact overlay. The
combined original-ESSL build/regression group finished in 26.28s; the per-group
wall limit was 180s. No Mesa libraries were rebuilt. Reproduce the reported
budget; any later different-budget run must report its actual limits and
preserve the prior bounded result.

## Reproduction

Use configured Mesa22.3.6 libraries and the existing checked uniform-index
lowering source. Set `OPENGPU_MESA_DIR`, `OPENGPU_MESA_BUILD`,
`OPENGPU_DRM_INCLUDE`, `LD_LIBRARY_PATH` and `OPENGPU_MESA_LINK_LIBS` to those
actual immutable inputs. Run inside the bounded read-only Linux setup above:

```sh
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_tessellation_uniform_emit.py --language-negative-controls
mkdir -p .artifacts/tess-language-repro
git show c6eddf7d5dcc92874ad3526f2a439d5fa06aac33:userspace/gallium/opengpu_native_tess_uniform_emit.c > .artifacts/tess-language-repro/original-v2-emitter.c
python3 -B scripts/test_tessellation_uniform_frontend.py --sanitize --language --regressions --baseline-source .artifacts/tess-language-repro/original-v2-emitter.c --build .artifacts/tess-language-repro/build
```

The baseline source can be prepared on the host before entering an image
without git. For each new case, use the actual P16 consumer with the exact
reported layout; for example:

```sh
python3 -B "$P16/scripts/test_tessellation_program_snapshot.py" --sanitize --programs "$PWD/.artifacts/tess-language-repro/build/programs/language-conditional" --tcs-layout 2 4 2 8 0 --tes-layout 0 8 --tcs-program-version 3 --tes-program-version 3 --tes-patch-layout 2 4 2 8 0 --owned-tessellation-info
```

`--owned-tessellation-info` selects an offline test profile; it is not hardware
discovery. Remove ended private build/program/log outputs after all consumers
finish; retain source changes, not generated evidence.

## Remaining execution gates

The actual three-domain original-VS/range→TCS/TES language-binding producer,
physical shared-pool integration, verified owner/profile availability, real
Mesa submission and shader execution remain separate gates. No getter,
factory, UAPI/schema, discovery bit or public feature is enabled here. This
CPU result does not close P16/P39/P40 or qualify the mandatory 2CU 640×480/60FPS
product target. No Scala regression workflow, JVM, RTL or DUT ran for this
compiler block.
