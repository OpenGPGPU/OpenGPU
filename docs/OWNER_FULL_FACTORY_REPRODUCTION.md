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

The local compiler is CIRCT `d21a21e51`, with the firtool-1.158.0 SDK and LLVM
revision `6279700538792da0c5a08e17babfe9b6e824c69f`. Large recursive deadness
queries otherwise repeatedly traverse whole regions during canonicalization.
The existing LLVM change
[`13d1cd1825594d49000d04fbdab24c9946b573a4`](https://github.com/OpenGPGPU/llvm-project/commit/13d1cd1825594d49000d04fbdab24c9946b573a4)
limits this optimizer query to 64 work items, conservatively retaining an
operation when the bound is reached. This limit changes neither GPU capacity
nor assertion coverage. The patch parent's entire source file matches the SDK
revision's original file; the library change is exactly the ten budget lines.

Set `LLVM_SOURCE` to a checkout containing that commit, `FLASHSIM_DIR` to the
FlashSim source checkout, and `CIRCT_BASE` to the matching `circt-verilog`
executable. Use Python 3.10 or newer for the FlashSim commands below (the local
checks used Python 3.14.7). Build an isolated library; leave installed tools and
libraries intact:

```sh
CIRCT_RELEASE="$FLASHSIM_DIR/third_party/circt-release/firtool-1.158.0"
P04_TOOL="$PWD/.artifacts/p04-tool"
mkdir -p "$P04_TOOL/bin" "$P04_TOOL/lib"
git -C "$LLVM_SOURCE" show \
  13d1cd1825594d49000d04fbdab24c9946b573a4:mlir/lib/Interfaces/SideEffectInterfaces.cpp \
  > "$P04_TOOL/SideEffectInterfaces.cpp"
clang++ -std=c++17 -O2 -fPIC -fvisibility-inlines-hidden \
  -fno-exceptions -fno-rtti -dynamiclib \
  -I"$CIRCT_RELEASE/include" "$P04_TOOL/SideEffectInterfaces.cpp" \
  -L"$CIRCT_RELEASE/lib" -lMLIRIR -lMLIRSupport -lLLVMSupport \
  -Wl,-install_name,@rpath/libMLIRSideEffectInterfaces.dylib \
  -Wl,-compatibility_version,24.0.0 -Wl,-current_version,24.0.0 \
  -Wl,-rpath,"$CIRCT_RELEASE/lib" \
  -o "$P04_TOOL/lib/libMLIRSideEffectInterfaces.dylib"
cp "$CIRCT_BASE" "$P04_TOOL/bin/circt-verilog"
export CIRCT_VERILOG="$P04_TOOL/bin/circt-verilog"
export FLASHSIM_CIRCT_SERIAL=1
export PYTHONDONTWRITEBYTECODE=1
DYLD_PRINT_LIBRARIES=1 "$CIRCT_VERILOG" --version
```

The loader must select the isolated `libMLIRSideEffectInterfaces.dylib`.
Its 55 exported symbols matched the SDK library exactly. Nine SDK-linked MLIR
checks covered small pure/effectful regions and both long/wide boundaries;
selecting the original library failed the precise 61-operation boundary control.
The fixed tool also passed all 20 FlashSim clocked assertion/cache cases
(34.72 seconds; 600-second/3072-MiB process-group ceiling). These are compiler
checks, not complete-model execution. The corresponding LLVM boundary cases
are `DeadOpFixture.*` in `mlir/unittests/Interfaces/SideEffectInterfacesTest.cpp`.

Reproduce the FlashSim checks with temporary outputs under this checkout:

```sh
P04_GPU="$PWD"
(cd "$FLASHSIM_DIR" && python3 -m pytest flashsim/test_clocked_assertions.py -q \
  --basetemp="$P04_GPU/.artifacts/p04-assertions" \
  -o cache_dir="$P04_GPU/.artifacts/p04-pytest-cache")
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

Run the stages under explicit aggregate memory/deadline guards. The local
budgets are 6144 MiB/600 seconds per fresh compilation, an 8 GiB JVM heap with
24576 MiB/1800 seconds for complete RTL emission, 24576 MiB/7200 seconds for
model generation, and 8192 MiB/7200 seconds per native build. These budgets do
not permit resource reduction. Completed outputs, logs, caches and temporary
source copies must be removed after their consumers and diagnosis end, following
`AGENTS.md`; preserve the qualified model/tool inputs while an actual subsequent
consumer still needs them.
