# Complete owner factory qualification

The complete factory's fresh compilation, RTL emission, structural audit,
assertion-enabled Verilator model, four original executors and three actual
discovery probes passed P04's local binding acceptance. The protected merge of
[PR #847](https://github.com/OpenGPGPU/OpenGPU/pull/847) closes P04, following
the source deliveries in [#845](https://github.com/OpenGPGPU/OpenGPU/pull/845)
and [#846](https://github.com/OpenGPGPU/OpenGPU/pull/846).

P04 binds the complete `OwnerResourceProfile.sampleStage` factory to fresh RTL,
its assertion-enabled model, original executors and actual MMIO discovery.
The target retains two 16-lane/eight-warp CUs, 16 KiB shared memory per CU,
512-set/four-way L2, four image engines, 65536 paged entries, the 512 KiB payload
pool configured for five owners and twelve request ports, and characterized
SRAM models. Display size is
640x480; native render-target capacity remains 2048x2048.

The emitted hierarchy was checked directly: two actual CU instances, sixteen
lane interfaces/eight active-warp bits, sixteen 1024-byte logical shared banks
per CU, four 128-set/four-way L2 slices with 64-byte lines (128 KiB total), and
four actual layered image engines. Payload storage contains sixteen groups of
32 characterized 256x32-bit arrays, totaling 512 KiB. Its five control owners
and seven live request ports are retained: five producers plus two CUs. The
other five source-configured request ports are tied off by `GpuSystem` and
pruned by firtool. This does not qualify twelve simultaneously active clients.
These are structural capacity checks; shader execution and resource coexistence
retain their separate acceptance requirements.

The exact 112-word sample discovery contract keeps
`fullResourceQualification=false`. It is not GLES 3.2, original shader/API,
clock or FPS qualification. P05 and subsequent work packages retain those
requirements. Changes to hardware source, resources or ABI require a new P04
binding; an old96 model or a protocol peer cannot supply this qualification.

## Inline assertions for hierarchical compilation

The complete sample factory accepts `--inline-assertions`. Firtool enables
`Verification` and `Verification.Assert`, placing checks inside their owning
modules. The manifest labels this format with `assertionMode: "inline"`; the
preparation checker rejects missing checks, remaining assertion bind layers,
other factories and stale source/RTL bindings. The default bound-layer format
remains available.

Fresh compilation of all 788 production and 1131 test sources passed, with
4140/3601 class `SourceFile` identities checked. The complete inline emission
passed in 269.968 seconds with an observed charged process-tree peak of
15,520,350,208 bytes. Its original 112 discovery words and resource manifest
match the bound factory, apart from the assertion-format label.

The emitted RTL contains 3774 diagnostic statements. The bound version has one
additional statement whose failure condition is impossible: its 12-bit response
ID selects either `~tag[11]` or `tag[11]`, covering all 4096 values. All other
diagnostic messages and multiplicities match. The focused compiler check below
also verifies bound hierarchy rejection, successful inline hierarchy compilation,
healthy clocked execution and two independent actual assertion failures.
Set `FIRTOOL` to the Chisel toolchain's firtool 1.158.0 executable.

```sh
python3 -B scripts/prepare_native_owner_flashsim.py --stage prepare \
  --sample-resources --inline-assertions --java-heap 8G \
  --output "$PWD/.artifacts/owner-inline"
python3 -B scripts/prepare_native_owner_flashsim.py --stage check \
  --output "$PWD/.artifacts/owner-inline"
sbt -J-Xmx4G 'testOnly opengpu.system.OwnerInlineAssertionEmissionSpec'
python3 -B -m unittest discover -s scripts -p 'test_prepare*owner*.py'
python3 -B scripts/qualification/check_owner_inline_assertions.py \
  --firtool "$FIRTOOL"
```

Both Scala emission checks and 36 focused owner-input/executor checks passed.
The latter include backend/kind isolation, original include closure, changed
checkers/binaries/system headers and rehashed build-command mutations. These
checks do not replace the original executors' actual MMIO probes.

## Complete Verilator model

Verilator 5.050 generated the complete inline factory with assertions enabled
and six hierarchical module families. Generation completed in 937.939 seconds,
with a charged process-tree peak of 21,256,328,232 bytes under a 24 GiB limit.
It produced 3666 C++ files. Flat compilation exceeded the same memory limit;
the hierarchical result establishes complete code generation, not native
compilation, execution speed or GPU qualification.

The generator now selects `--expand-limit 0` to retain runtime helpers for
wide operations instead of expanding them into word assignments. With
Verilator 5.050, the exact emitted `L2MshrTable.sv` with its 32850-bit waiter
vectors generated 142,308,006 C++ bytes by default, including a 71,098,975-byte
file. With this option it generated 72,438,273 bytes, with a largest file of
609,172 bytes. Code generation took 48.054 versus 11.617 seconds; both exited
zero under a 4 GiB process-tree limit. This compares isolated code generation.
Existing model identities fail the changed generator binding and must be
regenerated before use with this version.

Fresh complete-factory preparation for the compact model compiled all 787
production and 1130 test sources; all 7737 class `SourceFile` attributes mapped
to this checkout. Its classpath contained no external GPU project classes.
All 1037 emitted RTL files were byte-identical to the original P04 inputs,
including capacities and inline checks. Preparation took 407.788 seconds,
with a charged process-tree peak of 16,676,929,536 bytes. This includes fresh
Scala compilation and emission; the original emission-only time above covers
a different workload.

The compact model's complete code generation passed in 683.130 seconds,
with a charged peak of 21,790,823,560 bytes. It produced 4487 C++ files,
2,752,007,801 bytes in total; the largest was 11,276,289 bytes. Complete native
compilation at `-O1` then passed with eight jobs in 1595.994 seconds, peaking
at 10,247,323,648 charged bytes. Both stages stayed within 24 GiB and proved
consumer cleanup. The original `-O0` build below uses a different optimization
level, so these native build times do not establish a compile-time speedup.
Generation/native source, tool, SDK, runtime and archive checks passed again
after the executor fixes in PRs #854 and #856; those fixes leave RTL unchanged.

Reproduce the isolated comparison against a prepared complete owner:

```sh
P04_OWNER="$PWD/.artifacts/owner-inline"
MSHR_COMPARISON="$(mktemp -d "$PWD/.artifacts/mshr-XXXXXX")"
for mode in default compact; do
  extra=()
  if [ "$mode" = compact ]; then extra=(--expand-limit 0); fi
  python3 -B scripts/run_owner_bounded.py --memory-mib 4096 \
    --timeout-seconds 180 -- verilator --cc --assert --output-groups 0 \
    --output-split 10000 --output-split-cfuncs 200 -Wno-fatal -Wno-UNOPTFLAT \
    --top-module L2MshrTable --Mdir "$MSHR_COMPARISON/$mode" \
    "${extra[@]}" "$P04_OWNER/rtl/L2MshrTable.sv" || exit
done
python3 -B - "$MSHR_COMPARISON" <<'PY'
from pathlib import Path
import shutil, sys
base = Path(sys.argv[1])
for mode in ('default', 'compact'):
    files = list((base / mode).glob('*.cpp'))
    print(mode, len(files), sum(p.stat().st_size for p in files),
          max(p.stat().st_size for p in files))
shutil.rmtree(base)
PY
```

The generator checks the exact prepared factory, RTL, compiler toolkit and
generated sources. The native builder binds its compiler, SDK, options,
runtime objects and complete model archives. It uses eight compiler jobs by
default, bounded by aggregate memory, and accepts `-O0` or `-O1` for active
code; initialization/slow files use `-O0`. On macOS it passes the selected SDK
explicitly when invoking the real compiler.

The original P04 build selected `-O0`. With Apple clang 16, one 83.6 MB
active translation unit compiled in 18.840 seconds at `-O0`, while its `-O1`
compile spent more than 24 minutes in ARM branch relaxation before interruption.
This establishes a compiler-cost limit, not simulation throughput. The original
four driver translation units retain `-O1`.

The complete model compiled successfully at `-O0` with eight jobs in 1043.919
seconds. Observed peak charged process-tree memory was 19,839,844,352 bytes,
under the 24 GiB limit. Complete model archives, native runtime objects,
generated-source identities and original source/tool inputs passed validation.

All four original executors compiled and linked successfully against the same
complete model: native-sample, native-sample-graphics, compute and
framebuffer-blit-sample. The actual compiler dependencies, original source and
checker closures, model/runtime archives, compiler/SDK/linker identities, exact
commands and executable hashes passed the original executor checker.

All three original probes ran the real DUT and passed their independent
protocol validators on both models. The compact `-O1` model also passed all
four original executor builds, with source/tool/model/binary identities
checked before and after execution:

| Original executor | Discovery words | Auxiliary registers | Original `-O0` probe | Compact `-O1` probe |
|---|---:|---:|---:|---:|
| native-sample | 112 | 5 | 15.186 s | 4.174 s |
| native-sample-graphics | 112 | 5 | 15.919 s | 4.149 s |
| compute | 112 | 10 | 16.577 s | 4.409 s |

Each observed one reset, the exact complete sample factory, idle status, clear
IRQ and no external memory work. Peak charged probe memory was 322,207,744
bytes. All probes exited zero with proven consumer cleanup and unchanged
source/tool/model/binary bindings. The compact probes peaked at 203,505,664
charged bytes. These discovery probes do not qualify shader/API execution,
resource coexistence, clock rate or FPS.

The original simulation advances its real VerilatedContext using `timeInc()`.
The backend supplies the legacy initial-zero callback required by the compiled
runtime; subsequent time reads use the context's actual 64-bit clock. The clock
check links the real runtime and verifies initial zero, advancement beyond
2^60, two independent contexts, C++ exception unwinding and rejection of a
wrong initial-time callback; it passed again against the compact model's real
runtime. On macOS the executor linker selects the installed
classic Apple linker and fingerprints both linker binaries. This avoids the
newer linker's 24-bit compact-unwind offset assertion on the large generated
functions; the original compute executable's caught-error path was verified.

Starting with the prepared inline owner above:

```sh
P04_OWNER="$PWD/.artifacts/owner-inline"
python3 -B scripts/generate_owner_verilator.py --owner-output "$P04_OWNER"
python3 -B scripts/generate_owner_verilator.py --owner-output "$P04_OWNER" --check
python3 -B scripts/build_owner_verilator.py --owner-output "$P04_OWNER" \
  --jobs 8 --memory-mib 24576 --optimization=-O1
python3 -B scripts/build_owner_verilator.py --owner-output "$P04_OWNER" --check
python3 -B scripts/qualification/check_owner_verilator_clock.py \
  --owner-output "$P04_OWNER"
for kind in native-sample native-sample-graphics compute framebuffer-blit-sample; do
  python3 -B scripts/prepare_owner_executor.py --stage build --backend verilator \
    --kind "$kind" --owner-output "$P04_OWNER"
done
for kind in native-sample native-sample-graphics compute; do
  python3 -B scripts/prepare_owner_executor.py --stage probe --kind "$kind" \
    --qualification "$P04_OWNER/executors/$kind/qualification.json" \
    --executor "$P04_OWNER/executors/$kind/executor"
done
```

These entry points retain the original four C++ translation units and original
source/checker closures. They use the actual Verilator runtime rather than the
FlashSim adapter. Each probe resets the real model, reads the complete 112-word
factory and original auxiliary registers, checks idle/IRQ/no-memory-work
invariants, and passes the result through the original independent protocol
validator. Probe outputs are removed after validation and proven consumer exit.
Shader/API execution, the 43 blit packets, clock and FPS retain their later
acceptance requirements.

## FlashSim compiler prerequisite

The compiler is CIRCT `d21a21e51`, with the firtool-1.158.0 shared SDK and LLVM
revision `6279700538792da0c5a08e17babfe9b6e824c69f`. Large recursive deadness
queries otherwise repeatedly traverse whole regions during canonicalization.
The bounded implementation and its isolated builder/checker are merged in
[LLVM PR #2](https://github.com/OpenGPGPU/llvm-project/pull/2), at
[`6090a2c305467740bdf0d2c63fe497d7337a4e23`](https://github.com/OpenGPGPU/llvm-project/commit/6090a2c305467740bdf0d2c63fe497d7337a4e23).
The query counts at most 64 work items, including visited intervals and enqueued
blocks, and conservatively retains an operation when the budget runs out.
GPU capacity, assertion coverage and the complete pass pipeline are retained.

This library overlay supports macOS with the matching shared LLVM 24 release.
Use Python 3.10 or newer. Set absolute paths for `LLVM_SOURCE` (a clean checkout
of the merged revision), `FLASHSIM_DIR` (the intended source checkout),
`CIRCT_RELEASE` (the matching SDK, including headers and shared libraries),
`CIRCT_BASE` (the real `circt-verilog` executable), and `GTEST_SOURCE` (the
googletest source directory). The SDK may be outside the FlashSim checkout.
Start from this GPU checkout; the tool output directory must be new:

```sh
P04_GPU="$PWD"
P04_TOOL="$P04_GPU/.artifacts/p04-tool"
P04_TMP="$P04_GPU/.artifacts/p04-tool-check"
mkdir -p "$P04_TMP"
python3 -B "$LLVM_SOURCE/mlir/utils/build_bounded_deadness_overlay.py" \
  --release "$CIRCT_RELEASE" --verilog "$CIRCT_BASE" --output "$P04_TOOL" \
  --test-googletest "$GTEST_SOURCE"
python3 -B "$LLVM_SOURCE/mlir/utils/check_bounded_deadness_overlay.py" \
  --flashsim "$FLASHSIM_DIR" --original "$CIRCT_BASE" \
  --bounded "$P04_TOOL/circt-verilog" --temporary-parent "$P04_TMP" \
  --original-opt "$CIRCT_RELEASE/bin/circt-opt" \
  --bounded-opt "$P04_TOOL/circt-opt"
```

The builder checks the exported MLIR ABI and runs the six source unit tests,
including small pure/effectful regions, long/wide budget boundaries and live
pure values. The semantic checker generates and executes actual assertion-enabled
C++ models against an independent initial/reset, synchronous-read, old-data,
hold and write fault oracle. ASan/UBSan errors terminate the executable through
`-fno-sanitize-recover=all`. It also compares canonical IR from eight nested
modules at each of four depths. These checks passed with Python 3.14.7; they
establish compiler behavior, not complete GPU model execution or throughput.

The builder's shell wrappers select the library for those local checks. Guarded
GPU generation requires the real executable so its bytes can be fingerprinted.
Select the isolated library explicitly and keep this loader environment through
generation, executor builds and qualification checks:

```sh
export CIRCT_VERILOG="$CIRCT_BASE"
export DYLD_LIBRARY_PATH="$P04_TOOL"
export FLASHSIM_CIRCT_SERIAL=1
export PYTHONDONTWRITEBYTECODE=1
DYLD_PRINT_LIBRARIES=1 "$CIRCT_VERILOG" --version
```

Verify that the loader output names
`$P04_TOOL/libMLIRSideEffectInterfaces.dylib`. `generator_inputs` rejects compiler
shell wrappers and fingerprints the real executable, loaded libraries and
explicit loader environment. With the FlashSim source tree at
[`ac55d1ad844a9efaf95f551184732a22dd99fa27`](https://github.com/OpenGPGPU/FlashSim/commit/ac55d1ad844a9efaf95f551184732a22dd99fa27),
the real-tool selection and all 21 clocked assertion/cache cases passed under
a 600-second/3072-MiB aggregate RSS ceiling.
Reproduce the assertion checks with temporary outputs under this checkout:

```sh
(cd "$FLASHSIM_DIR" && python3 -B -m pytest flashsim/test_clocked_assertions.py \
  -q -p no:cacheprovider --basetemp="$P04_TMP/pytest")
```

## Full model and original executors

Use a clean topic checkout and fresh compiler output. The local source audit
compiled all 788 production and 1130 test sources with Scala 2.13.18, Chisel
7.15.0 and Java 21, using only fresh GPU classes and third-party JARs without
GPU classes. Class `SourceFile` and actual loaded providers were checked;
classes from another GPU checkout are not valid substitutes.

For model generation, select the published FlashSim source at
[`1df7bc33cb0a12e192b074f6cd0575348422467c`](https://github.com/OpenGPGPU/FlashSim/commit/1df7bc33cb0a12e192b074f6cd0575348422467c).
It includes shared-expression traversal, recursive-visitor lifetime and output
buffer fixes. The compiler also transfers its frontend model to the optimizer
and releases parsed CIRCT text after lowering, allowing obsolete inputs to be
released as passes replace them. The public optimizer preserves caller-owned
input. Native/CIRCT success and failure lifetime checks and the
complete 419-test suite passed under both normal and deferred GC; those checks
do not establish complete GPU generation memory use.

The optional scoped GC policy below runs in the isolated generator process and
is part of the generator environment binding. Keep that environment through
executor builds and checks. First check that the clean FlashSim checkout has
the published source tree; a reviewed PR head with identical tree bytes is
also accepted.

```sh
set -e
P04_FLASHSIM_REV=1df7bc33cb0a12e192b074f6cd0575348422467c
test "$(git -C "$FLASHSIM_DIR" rev-parse 'HEAD^{tree}')" = \
  "$(git -C "$FLASHSIM_DIR" rev-parse "$P04_FLASHSIM_REV^{tree}")"
test -z "$(git -C "$FLASHSIM_DIR" status --porcelain)"
export FLASHSIM_GC_POLICY=defer
python3 -B scripts/run_owner_bounded.py --memory-mib 6144 --timeout-seconds 600 -- \
  sbt -J-Xmx4g -J-XX:ActiveProcessorCount=1 clean compile 'Test / compile'
P04_OWNER="$PWD/.artifacts/p04-owner"
python3 -B scripts/run_owner_bounded.py --memory-mib 24576 --timeout-seconds 1800 -- \
  python3 -B scripts/prepare_native_owner_flashsim.py --stage prepare \
    --sample-resources --java-heap 8G --output "$P04_OWNER"
python3 -B scripts/prepare_native_owner_flashsim.py --stage check \
  --output "$P04_OWNER"
python3 -B scripts/run_owner_bounded.py --memory-mib 24576 --timeout-seconds 14400 -- \
  python3 -B scripts/prepare_native_owner_flashsim.py --stage generate \
    --output "$P04_OWNER" --flashsim-dir "$FLASHSIM_DIR" --shards 32
for kind in native-sample native-sample-graphics compute framebuffer-blit-sample; do
  python3 -B scripts/run_owner_bounded.py --memory-mib 8192 --timeout-seconds 7200 -- \
    python3 -B scripts/prepare_owner_executor.py --stage build --kind "$kind" \
      --owner-output "$P04_OWNER" --flashsim-dir "$FLASHSIM_DIR" \
      --shards 32 --jobs 1 --shard-opt=-O1 --precompiled-header --conservative-alias
  python3 -B scripts/prepare_owner_executor.py --stage check --kind "$kind" \
    --qualification "$P04_OWNER/executors/$kind/qualification.json" \
    --executor "$P04_OWNER/executors/$kind/executor"
done
for kind in native-sample native-sample-graphics compute; do
  python3 -B scripts/run_owner_bounded.py --memory-mib 8192 --timeout-seconds 600 -- \
    "$P04_OWNER/executors/$kind/executor" \
      --probe-profile "$P04_OWNER/probe-$kind.json"
done
```

Each output and probe path must be new. The original preparation checker binds
source membership/bytes, exact factory manifest, RTL and assertion layers.
Generation binds the actual compiler, loaded libraries, FlashSim source, MLIR
and all 32 numbered shards. Each executor separately binds its original driver
and include closure, compiler/options/environment, objects and binary. Shared
object-cache use does not share mutable executor qualifications.

Validate the observed probe JSON with `profile_bytes` from
`scripts/native_sample_owner_protocol.py` for both native executors and
`scripts/compute_owner_protocol.py` for compute. They check all 112 actual
discovery words and the required auxiliary reads. The blit executable has its
own build qualification; its 43-packet execution remains P06.

```sh
python3 -B - "$P04_OWNER" <<'PY'
import sys
from pathlib import Path
sys.path.insert(0, str(Path.cwd() / "scripts"))
from compute_owner_protocol import profile_bytes as compute_profile, strict_json
from native_sample_owner_protocol import profile_bytes as native_profile
from owner_resource_contract import SAMPLE_PROFILE
owner = Path(sys.argv[1])
for kind, validate in (("native-sample", native_profile),
                       ("native-sample-graphics", native_profile),
                       ("compute", compute_profile)):
    profile = strict_json(owner / f"probe-{kind}.json")
    if profile.get("ownerProfile") != SAMPLE_PROFILE:
        raise ValueError("probe does not belong to the complete sample factory")
    wire = validate(profile)
    print(kind, len(profile["words"]), len(wire))
PY
```

Run the stages under explicit aggregate memory/deadline guards. The local
budgets are 6144 MiB/600 seconds per fresh compilation, an 8 GiB JVM heap with
24576 MiB/1800 seconds for complete RTL emission, 24576 MiB/14400 seconds for
model generation, and 8192 MiB/7200 seconds per native build. These budgets do
not permit resource reduction. RSS alone misses macOS compressed pages. Use
the [owner process memory guard](OWNER_PROCESS_MEMORY.md) with the existing
ownership, deadline and exit-proof checks: charge max(RSS, physical footprint)
on macOS or RSS plus swap on Linux, and report both resident and charged peaks.
Completed outputs, logs, caches and temporary source copies must be removed
after their consumers and diagnosis end, following
`AGENTS.md`; preserve the qualified model/tool inputs while an actual subsequent
consumer still needs them.
