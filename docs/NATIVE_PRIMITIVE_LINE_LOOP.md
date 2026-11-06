# Bounded native LINE_LOOP fixture

This local fixture prepares a legacy GNB (`opcode 7`) draw with two
three-vertex LINE_LOOPs separated by a uint16 `0xffff` restart. Six exact dyadic
positions map to half-pixel endpoints on a 4×4, single-sample target. The image
uses one 8×8 native tile (`tileLog2=3`); the backend accepts 8/16-pixel tiles
independently of the image extent. The test
retains the full two-CU host interface: sixteen lanes/eight warps, sixteen
attribute/binding/varying slots, four color outputs and the existing native
SRAM/FPU/cache/payload profile. Only one attribute/varying slot and one color
attachment are active in this small draw; hardware capacities are unchanged.

The independent arbitrary-precision diamond-exit reference produces these
per-pixel contribution counts:

```text
1 1 1 1
1 1 0 1
1 1 1 1
1 1 1 1
```

ONE+ONE RGBA8 blending makes a false extra edge observable even at an already
covered pixel. Missing the first closing edge changes pixels 5/10/15; missing
the second changes 13/14. A false bridge across restart adds a second
contribution at 13/14/15. The shader outputs red 1/8, zero green/blue and alpha
one. The healthy reuse changes red to 1/4 and uses new addresses/generation.
The expected stored red quantum is respectively 32 and 64. The reference is
fixed before GPU execution; no DUT output supplies expected pixels.

The three prepared submissions are healthy, a logical index view missing the
last byte, and healthy reuse. The short view has complete physical backing but
only 13 logical bytes for seven uint16 indices. Vertex input's count preflight
must reject it with bounds status 2 before reading index/attribute data. The
native reader records stage 3 and the index base. Successful submissions retain
their complete image plus prefix, row-padding and tail guards in a 128-byte
pitched allocation. Read-only records, shader words, indices and positions are
also compared unchanged.

Five CPU checks pass, including strict C11 `-O2 -Wall -Wextra -Werror` and a
byte-identical ASan/UBSan `-O1` export. They check the native wire fields,
disjoint/aligned regions, both closing-edge and false-bridge witnesses, all
six positions, independently decoded straight-line shader addresses/values,
all sixteen literal pixels and guards, and rejection of corrupted returned
bytes. The small CPU ISA decoder is only a fixture check, not GPU execution.

```sh
python3 scripts/test_native_binned_line_loop_fixture.py
cc -std=c11 -O2 -Wall -Wextra -Werror -I driver -I driver/tests \
  driver/tests/opengpu_native_binned_line_loop_model_test.c -o /tmp/native-line-loop
mkdir .artifacts/native-line-loop-inputs
/tmp/native-line-loop --export .artifacts/native-line-loop-inputs
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 \
  -J-XX:+ExitOnOutOfMemoryError 'Test / compile'
```

`NativeBinnedLineLoopHostAxiSpec` is software-only local qualification glue. It
calls the pre-existing `TessellationOwnedTextureHostFixture.host()` factory,
then uses the existing MMIO/AXI service helpers. It adds no Module, IO or DUT
configuration. The intended service retains eleven-clock read delay,
five-clock acknowledgement delay and the 600,000-cycle guard. It checks real
shader/index traffic, GNX1 status/identity, per-ID read beats, held offers,
complete read/write drain, exact bytes and held-completion VM ownership.

## Local native result

The three-job sequence passes on the existing native host model. The original
process exits zero with exactly one ScalaTest case and no failures, errors or
skips. All three returned allocations match the independent C oracle; the
faulting submission leaves its entire 856,064-byte allocation unchanged.
Successful jobs also pass held-completion VM locks, completion pop and IRQ
release before reuse.

| Submission | Status | Service cycles | AR / RLAST | AW / WLAST / B |
|---|---:|---:|---:|---:|
| Healthy, red 1/8 | 0 | 21,755 | 233 / 233 | 180 / 180 / 180 |
| Short logical index view | 2 | 509 | 72 / 72 | 1 / 1 / 1 |
| Fresh healthy, red 1/4 | 0 | 21,755 | 233 / 233 | 180 / 180 / 180 |

The fixture uses legal native tile metadata and emits fragment control for
its declared color mask. CPU checks derive that control contract from the
actual descriptor and decoded shader. Before status assertions, the native
test saves the complete MMIO completion word, twelve detail words, accepted
read addresses and monitor counters. Returned memory is saved on failure too.

For explicit local execution after exporting the inputs, use the ordinary
`Test / fullClasspath` as `TEST_CLASSPATH`. An explicit Runner suite is needed
because this expensive fixture has `@DoNotDiscover`:

```sh
java -Xmx12G -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError \
  --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED \
  -Dopengpu.nativeLineLoopQualification=1 \
  -Dopengpu.nativeLineLoopInputs="$PWD/.artifacts/native-line-loop-inputs" \
  -Dopengpu.nativeLineLoopOutput="$PWD/.artifacts/native-line-loop-output" \
  -Dopengpu.simCache="$PWD/.artifacts/native-line-loop-model" \
  -Dopengpu.simHierarchy=GpuComputeUnit -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 -Dopengpu.simCcache=off \
  -cp "$TEST_CLASSPATH" org.scalatest.tools.Runner \
  -s opengpu.graphics.NativeBinnedLineLoopHostAxiSpec -oD
```

The qualified run used a separate cache-only gate: all 1,442 actual emitted
inputs, raw filelist/include closure, effective compiler arguments and the
natural key matched H8's reviewed model. It performed zero new native builds.
The command above alone does not enforce cache-only execution. No regular CI
lane is expanded. This bounded 4×4 result closes neither G01/G03 nor H6; full
topology, API, product performance and physical qualification remain required.
