# Original vertex source root

GVS1 is a separate 128-word/512-byte read-only source root for a future
protected original-VS → owned patch → TCS → dynamic TES submission. Its exact
schemas are `abi/tessellation-vertex-source-v1.json` and
`abi/tessellation-vertex-sequence-v1.json`; the generated production header is
`driver/opengpu_tessellation_vertex_source_abi.h`. The root requires the full
two-CU, 16-lane, eight-warp configuration. This is CPU-validated construction,
not an available submission path or a DUT qualification.

| Words | Meaning |
| --- | --- |
| 0–10 | GVS1 magic `0x47565301`, bytes/version, exact capacities |
| 11–15, 23, 31, 37–39, 46–63 | Zero |
| 16–22 | Context/pass/generation, sequence/draw index, first task ID, patch count |
| 24–26 | Private original-GND base and exclusive 33-bit limit |
| 27–30 | Private original-VS RX base, exclusive 33-bit limit, absolute entry VA |
| 32–36 | Slice version 1, first ordinal, patches per instance, first instance, instance count |
| 40–45 | Five captured patch-layout fields and original VS ABI 1/2 |
| 64–127 | 64 component maps: kind bits 1:0, component bits 8:2; inactive maps zero |

GNT7/8/9 (`0x474e5407/08/09`) inherit the isoline/triangle/quad GNT4/5/6
fields. Only words 509–511 add the GVS base/exclusive 33-bit limit. Their new
source mode requires zero old patch-DMA words 17–23, vertex stride 491/492,
static TES selection count/array 237–481 or 238–482, and legacy uniform flag
482/483. The checked GUR3 fields 499–508 remain intact. Header/version/domain,
reserved words, wide high bits, exact root extent and old/new transport
exclusion are checked without granting resource or execution permission.
Original GNQ/GND/GNT sizes remain 512/2048/2048 bytes; old encoders, readers
and admission interfaces are unchanged.

The producer-facing
`opengpu_tessellation_vertex_sequence_source_canonical` checks the same full
GNT7/8/9 header, domain, reserved bits and transport exclusions, but requires
all three source words 509–511 to be zero. The existing
`opengpu_tessellation_vertex_sequence_canonical` instead checks a bound root:
64-byte-aligned base, exact 512-byte extent and a 33-bit exclusive limit no
greater than 4 GiB. A producer cannot provide either relocation field. An
enclosing BUILD must validate the unbound source, apply owner-controlled
relocations, then validate the bound record; this distinction does not create
that enclosing BUILD or grant submission permission.

## Production helper boundary

`opengpu_tessellation_vertex_source_append` first copies the complete typed
kernel plan and original GND before invoking callbacks. Its internal verified
profile requires owned patch zero-initialization; no production getter supplies
that profile. Unavailable fails before any lookup callback or owner append.

The helper resolves retained original GEM backing once per handle, checks full
views and physical 64-byte source/scratch aliases, and uses the existing
production input, program and uniform helpers. All 16 attribute bindings,
indexed input, instancing metadata and the complete VS1/2 scratch prefix keep
their original typed provenance. Original code is validated at its entry and
copied byte-for-byte RX without write permission; VS defaults/12 UBO blocks
become a distinct GUR1 scope. GND/GVS/GUR1 are private R/NX, input mappings
R/NX, and checked scratch RW/NX. No GPU address comes from the typed plan.

Legacy sampler words 328–335 and policy words 480–483, texture-root metadata,
reuse output and indirect source metadata are rejected while their actual
source paths are unconnected. Indirect provenance slots remain in the plan;
the CPU does not parse indirect commands or predict patch inputs. The selected
ES 3.2 unsupported-for-patches/query-false restart policy canonicalizes only
the private source GND's restart flag to zero, even if the original global
enable flag is set. Other primitive paths keep their behavior. A query-true
implementation would need a different validated contract. See
[ES 3.2 §10.3.4](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf).

The enclosing submitter must retain original GEM/context references, hold
reservation ownership, wait producers outside `submit_lock`, and exclude CPU
writes during capture. Every error leaves the result unchanged and requires
whole-owner retirement, including after partial appends. This helper does not
perform Linux capture/waits, check aliases against unrelated frame/TCS/TES
roles, append GNT/GNQ, publish/enqueue, or enable a getter/ioctl.

## Validation and remaining integration

At delivery source commit `41b51b8a1217213de55198eaca7aa56142551f11`, Clang
and immutable Linux GCC 13.3 both passed ASan/UBSan owner/MMU fixtures and ten
semantic negative controls. Four ABI tests check exact fields,
append-only inheritance, stale generated headers and malformed catalogs. The
owner graph checks all 128 published root words, all reserved/high-bit words,
the original code/entry bytes, all 16 binding mappings, full VS uniforms, and
24 TCS/TES GUR3 blocks in the same actual private owner. It covers source and
plan mutation, unavailable/invalid profiles, coupled capacity/range overflow,
unused maps, physical aliases across handles, every allocation/copy/resolve
failure and successful owner reuse. CEASE programs test ownership, not shader
results. Existing native input/program and old canonical tests also passed.
The Linux container used one CPU, 512 MiB total memory/swap, no network and
read-only source; 758 source inputs matched before/after. Ended outputs were
removed.

Negative controls remove source-alias checks, legacy sampler rejection,
old-DMA exclusion, static-TES exclusion, restart canonicalization, 33-bit
source/sequence endpoint checks, unused-map rejection or immutable-plan
capture, or make metadata executable. Each fails a semantic assertion.

The producer/bound split adds all-three-domain cases for zero relocation
fields, cross-domain/header/size rejection, reserved and over-width fields,
forbidden legacy transports, ordinary bound roots and the exact 4 GiB
exclusive endpoint. An eleventh semantic negative control removes the
producer's zero-address requirement and must fail an assertion. The updated
owner/MMU fixture and all eleven semantic negative controls passed with
Clang and immutable Linux GCC 13.3 ASan/UBSan (53.05 and 62.72 seconds).
The ABI generator check and all four ABI/catalog tests also passed. The four
tested C/header/fixture/runner inputs were unchanged during each compiler run;
owned temporary outputs were removed. The earlier module-build result below
predates this split, which has no new runtime or module-build qualification.

Reproduce from the repository root:

```sh
python3 -B scripts/generate_tessellation_vertex_source_abi.py --check
python3 -B scripts/test_tessellation_vertex_source_abi.py
python3 -B scripts/test_tessellation_vertex_source.py --sanitize --negative-controls
python3 -B scripts/test_native_input_snapshot.py --sanitize
python3 -B scripts/test_native_program_snapshot.py --sanitize
python3 -B scripts/test_tessellation_uniform_abi.py
python3 -B scripts/test_tessellation_canonical.py
```

At delivery source commit `41b51b8a1217213de55198eaca7aa56142551f11`, a fresh
full module build passed against the configured ARM64 Linux 7.2.0 tree with
Homebrew AArch64 GCC 15.2.0 and GNU Make 4.4.1. Kbuild compiled all 27 driver
objects plus two module helpers, completed MODPOST and linked `gpu_drv.ko`
without warnings or errors. The module was verified as ELF64 AArch64
relocatable with vermagic `7.2.0 SMP preempt mod_unload aarch64`;
`opengpu_tessellation_vertex_source_append`,
`opengpu_tessellation_vertex_source_canonical` and
`opengpu_tessellation_vertex_sequence_canonical` were present in its linked
symbols. Kernel configuration, generated headers, symbol versions and host
tools matched before/after (5777 shared inputs), as did all 152 original and
copied driver inputs. No kernel or
driver source was changed and no warning was disabled. The serial build
completed in 17.20 seconds under an owned process group's 180-second wall
deadline and 512 MiB aggregate RSS guard. The largest sampled group RSS was
350304 KiB; this is not an absolute physical-memory peak. All private outputs
were removed after process, open-file and external-symlink consumer checks.

Reproduce the module build with an already configured kernel and its matching
cross compiler; do not configure or prepare the shared kernel as part of this
check. Run the build under the same bounded process-group monitor. Record
and compare SHA-256 and file metadata before/after for the kernel `.config`,
`Makefile`, `Module.symvers`, `include/config`, `include/generated`,
`arch/arm64/include/generated`, `scripts`, and the actual compiler/make binaries;
stop if shared configuration or tools would be rebuilt.

```sh
KERNEL_BUILD=/Users/duckdonald/workspace/arti-work/arti-linux-build
CROSS_COMPILE=/opt/homebrew/bin/aarch64-linux-gnu-
MAKE=/opt/homebrew/bin/gmake
mkdir -p .artifacts
TASK_DIR=$(mktemp -d "$PWD/.artifacts/p16-kbuild.XXXXXX")
mkdir "$TASK_DIR/src" "$TASK_DIR/out" "$TASK_DIR/tmp"
shasum -a 256 driver/Makefile driver/*.[ch] > "$TASK_DIR/source.sha256"
cp driver/Makefile driver/*.[ch] "$TASK_DIR/src/"
chmod a-w "$TASK_DIR/src/"* "$TASK_DIR/src"
BUILD_STATUS=0
TMPDIR="$TASK_DIR/tmp" "$MAKE" -C "$KERNEL_BUILD" \
    M="$TASK_DIR/src" MO="$TASK_DIR/out" ARCH=arm64 \
    CROSS_COMPILE="$CROSS_COMPILE" KCONFIG_NOSILENTUPDATE=1 \
    -j1 V=1 modules || BUILD_STATUS=$?
if test "$BUILD_STATUS" -eq 0; then
    "${CROSS_COMPILE}readelf" -h "$TASK_DIR/out/gpu_drv.ko"
    "${CROSS_COMPILE}readelf" -p .modinfo "$TASK_DIR/out/gpu_drv.ko"
    "${CROSS_COMPILE}nm" --defined-only "$TASK_DIR/out/gpu_drv.ko"
fi
shasum -a 256 -c "$TASK_DIR/source.sha256"
# Compare the shared-kernel/tool snapshot, then check no process/open file or
# external symlink consumes TASK_DIR before removing these private outputs.
chmod u+w "$TASK_DIR/src"
rm -rf "$TASK_DIR"
test "$BUILD_STATUS" -eq 0
```

Module loading, UAPI, runtime, RTL and product qualification remain pending;
compilation and linking do not establish them. The protected outer BUILD still
needs exact original-role capture and whole-graph alias
checks, VS/GUR1 plus TCS/TES GUR3 and program binding, GNT7/8/9 relocations,
the matching root/GND reader, source execution, ACK/seal and range-sequencer
integration, existing memory-client routing/drain, and production retirement.
Indirect and texture paths remain full targets. Hardware discovery, actual
three-domain execution, full-profile DUT, six VGA scenes and timing/physical
qualification remain pending; the older frozen 96-byte owner is not evidence
for the final profile. No Scala regression workflow was enabled or dispatched.
