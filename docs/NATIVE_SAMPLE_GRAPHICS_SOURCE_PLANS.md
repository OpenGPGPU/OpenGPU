# Private 112-word ordinary graphics source plans

`userspace/opengpu_native_sample_graphics.[ch]` provides a private, versioned
consumer of the exact ordered 112 discovery words and five auxiliary registers.
It checks the complete sample-stage contract, the actual 16-lane/8-warp/two-CU
shader configuration and 128-item batch, and derives limits through the existing
GNC5/GGS/GSS validator. Register reads reject missing prerequisites before
reading dependent records. This cache does not authorize a public feature.

The direct encoder creates GNJ2 and one-layer GNF3 records from zeroed storage,
using actual GNC5 limits and explicit 33-bit source/resolve pitches. It preserves
logical original-GEM views, four MRTs, four fragment scratch owners and the
65536-tile capacity. Completion is a separate exact 16384-byte original-GEM
view. Ordinary extended plans preserve the original code, GUR and input views
and zero every GS extension byte. Kernel admission still owns producer waits,
original-object bounds/aliases, private RX/RO snapshots and lifetime/drain.
Every rejected operation leaves its output unchanged.

The sample CPU/live producers use this encoder instead of passing synthetic
old GNC3 capabilities and rewriting version fields. The independent existing
recipe still checks every GNJ2/GNF3/GND1/GFS word and the live producer's original
RX code. Existing one-layer pitch values and descriptor bytes are preserved.
The qualifier binds the added compilation unit and its transitive headers.

The old GNC2/GNC3 runtime, frame planner, public GET_PARAM results, driver feature
bits and 96-word protocols remain separate. Original Mesa ABI1 programs and
uniform views are carried by these plans. The private Gallium bridge below
connects the program/context factory to the new cache. A two-owner 112
executor/image/GNY2 oracle and actual DUT qualification remain unfinished.

CPU validation:

```sh
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_sample_graphics.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_sample_submit_bridge.py
python3 -B scripts/test_prepare_owner_executor.py
python3 -B scripts/test_qualify_native_sample_owner.py
```

The first command checks complete GNF3 words at 1/2/4 samples, exact GNJ2/GNY2
source plans, full UBO/default preservation, all discovery-word mutations,
auxiliary/read failures, legal bank counts, limits and the frozen old planner.
These are CPU source/ownership tests; no Mesa rebuild, RTL/model or GPU runs.

The ordinary original-Mesa path now has an explicit private context bootstrap
and program/source/draw/submit entry in
`userspace/gallium/opengpu_native_sample_program.[ch]`. It validates the full
112-word cache, copies it into independent context storage and checks that
cache on each operation. The shared compiler emits the original ABI1 bytes;
reflection and actual stage constant callbacks retain both original GUR/default
and UBO views. GND1 and ABI1 are valid ordinary inputs to GNJ2/GNF3; this path
neither converts their shader ABI to ABI6 nor fabricates old GET_PARAM replies.
The legacy APIs still require their old runtime. Public features stay disabled.

The fixed `test_gallium_sample_graphics_uniforms.py` entry is CPU capture only.
It requires `--cpu-capture-only`, an explicit strict OGNP0002 profile and the
actual Linux kernel fixture source. It rejects the old live environment before
building: no two-owner live controller/receipt contract is implemented here.
A real bridge must obtain the profile from the final host probe. Synthetic CPU
observations do not prove discovery or device execution.

Bounded Linux ASan/UBSan validation used original Mesa22.3.6 static libraries
and the unchanged original ESSL sources: VS308/FS301 words, actual reflection,
24 UBOs and two 1024-component defaults, a changed original UBO/default for the
second owner, exact original code in both private RX snapshots, version2
GS-zero submit plans, two OGCAP002 records, union memory and separate 16384-byte
completion views. Complete source descriptors and reserved bits plus the
independent original-source color calculation passed. A final execution-only
replay bound all 8098 Mesa/DRM/library inputs, 28 compiler dependency units, GPU
source/generated inputs and the binary before and after execution. All matched.
The container used one CPU, 512MiB memory with equal swap limit, no network and
read-only original inputs. All private outputs and containers were removed.

The control/retirement/cleanup peer in this entry is explicitly modeled CPU
behavior. No shader ran, no GNY2 publication or image was produced by a DUT, and
neither sample builtins nor the six 640x480/60FPS product scenes are qualified.
A future strict two-owner 112 executor, independent full GNY2/image oracle and
live resource/cleanup protocol remain required. ABI6 uniform/flat lowering and
ABI7 with an actual GS owner also remain separate work.

Fast CPU admission tests are in both common CPU lanes:

```sh
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_gallium_sample_graphics_bridge.py
```

They check exact112/old96 isolation, OGNP0002/EOF rejection, legal two/four-bank
acceptance, odd-bank rejection, unchanged error outputs and CPU-only admission.
The complete host `test_driver.py` regression also passes with per-fixture
ASan/UBSan, actual Mesa 22.3.6 and DRM headers, including the old snapshot,
submission, validator, MMU and Gallium framebuffer paths. The Linux
`sync_file`-dependent fixture is skipped on macOS; the explicit Linux run above
covers the new producer. Reproduce the host regression with a managed temporary
directory and plain host compiler:

```sh
TMPDIR="$OUT" OPENGPU_TEST_SANITIZERS=1 CC=cc \
  OPENGPU_MESA_DIR="$MESA" OPENGPU_DRM_INCLUDE="$DRM_INCLUDE" \
  python3 -B scripts/test_driver.py
```

For the explicit actual-Mesa CPU path, use the immutable ARM64 tool image and
read-only source/build/library mounts described in `NATIVE_SAMPLE_ESSL_BRIDGE.md`,
with a new managed output directory under `.artifacts` and the same Mesa/DRM/link
environment. Invoke:

```sh
python3 -B scripts/test_gallium_sample_graphics_uniforms.py \
  --build "$OUT/build" --sanitize --native-kernel-root "$TOPIC" \
  --mesa-nir-lower-io "$CHECKED_PRIVATE_NIR" \
  --profile-wire "$OGNP0002_PROFILE" --cpu-capture-only
```

Keep the original input snapshot and actual compiler-generated `.d` closure
stable for the execution scope. Remove managed outputs after consumers exit.
