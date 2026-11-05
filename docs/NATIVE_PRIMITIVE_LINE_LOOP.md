# Bounded native LINE_LOOP fixture

This local fixture prepares a legacy GNB (`opcode 7`) draw with two
three-vertex LINE_LOOPs separated by a uint16 `0xffff` restart. Six exact dyadic
positions map to half-pixel endpoints on a 4×4, single-sample target. The test
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

Native execution is **not yet qualified**. The software stack is based on the
H8 fixture's oracle correction at `6819236f`; that fixture has built its native
model, but its three-job runtime qualification is still pending. The LINE_LOOP
driver uses the same factory and observes its existing SRAM-provider output;
it adds no hardware probe. Ordinary Main/Test compilation passes; no LINE_LOOP
native run has occurred. A reviewed external gate must compare the complete actual emitted
inputs, factory/classes/recipe and natural cache key and reject every miss
before compilation. An opt-in test property alone does not provide that
cache-only guarantee. This work authorizes no second fresh host model,
physical run or frame-scale test, and closes neither G01/G03 nor H6 by itself.
