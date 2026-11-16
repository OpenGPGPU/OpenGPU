# Complete owner factory qualification

The complete factory's fresh compilation, RTL emission and structural audit
have passed. Assertion-enabled model generation, original executor builds and
actual discovery probes remain pending; P04 is not closed by these results.

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

## Compiler prerequisite

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

```sh
sbt -J-Xmx4g -J-XX:ActiveProcessorCount=1 clean compile 'Test / compile'
P04_OWNER="$PWD/.artifacts/p04-owner"
python3 -B scripts/prepare_native_owner_flashsim.py --stage prepare \
  --sample-resources --java-heap 8G --output "$P04_OWNER"
python3 -B scripts/prepare_native_owner_flashsim.py --stage check \
  --output "$P04_OWNER"
python3 -B scripts/prepare_native_owner_flashsim.py --stage generate \
  --output "$P04_OWNER" --flashsim-dir "$FLASHSIM_DIR" --shards 32
for kind in native-sample native-sample-graphics compute framebuffer-blit-sample; do
  python3 -B scripts/prepare_owner_executor.py --stage build --kind "$kind" \
    --owner-output "$P04_OWNER" --flashsim-dir "$FLASHSIM_DIR" \
    --shards 32 --jobs 1 --shard-opt=-O1 --precompiled-header --conservative-alias
  python3 -B scripts/prepare_owner_executor.py --stage check --kind "$kind" \
    --qualification "$P04_OWNER/executors/$kind/qualification.json" \
    --executor "$P04_OWNER/executors/$kind/executor"
done
for kind in native-sample native-sample-graphics compute; do
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
24576 MiB/1800 seconds for complete RTL emission, 24576 MiB/10800 seconds for
model generation, and 8192 MiB/7200 seconds per native build. These budgets do
not permit resource reduction. RSS alone misses macOS compressed pages. Use
the [owner process memory guard](OWNER_PROCESS_MEMORY.md) with the existing
ownership, deadline and exit-proof checks: charge max(RSS, physical footprint)
on macOS or RSS plus swap on Linux, and report both resident and charged peaks.
Completed outputs, logs, caches and temporary source copies must be removed
after their consumers and diagnosis end, following
`AGENTS.md`; preserve the qualified model/tool inputs while an actual subsequent
consumer still needs them.
