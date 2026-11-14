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
actual Linux kernel fixture source. It rejects every live environment before
building; this builder has no live mode.
A real bridge must obtain the profile from the final host probe. Synthetic CPU
observations do not prove discovery or device execution.

A separate C entry is selected only by compiling both
`graphics_sample_flow.c` and `graphics_sample_kernel.c` with
`OPENGPU_NATIVE_SAMPLE_GRAPHICS_LIVE=1` and linking
`graphics_sample_owner_ipc.c`. It accepts one original-ESSL source directory
and requires `OPENGPU_SAMPLE_GRAPHICS_HOST_DIRECTORY` and
`OPENGPU_SAMPLE_GRAPHICS_HOST_PROFILE`. The strict112 profile comes from the
controller probe. Compiler words, reflection and both source plans go to that
IPC directory with the fixed READY filenames; original ESSL is read unchanged
from the source directory. The old live environment is rejected. Default CPU
admission rejects all four old/new live variables, including empty values,
before building or compiling a program.

The LIVE entry retains both protected original-program views after releasing
Gallium references, calls `graphics_sample_graphics_host_execute`, and emits
`ACTUAL_SAMPLE_GRAPHICS_LIVE_RETIRED` only after its observed execution and
complete external cleanup/final receipt exchange returns successfully. Failed
exchange returns nonzero without invoking the CPU capture peer. The Python
actual-Mesa builder requires exactly one of `--cpu-capture-only` and
`--prepare-live-producer`. CPU capture requires `--profile-wire`; prepare
rejects it because the controller supplies the actual profile at execution.
Both modes require `--native-kernel-root` and reject all live environment
variables before building. Prepare compiles the two LIVE translation units and
the new receipt codec, generates the original ESSL sources, then returns
`BUILT, execution=false` before starting a producer or writing a result. It
does not enable a public feature.

```sh
python3 -B scripts/test_gallium_sample_graphics_uniforms.py \
  --build "$OUT/build" --sanitize --native-kernel-root "$TOPIC" \
  --mesa-nir-lower-io "$CHECKED_PRIVATE_NIR" --prepare-live-producer
```

The independently checked controller can subsequently start
`$OUT/build/cs_uniform_flow $OUT/build/glsl` with its two new live environment
variables. The builder itself does not start or qualify that exchange.

`qualify_native_sample_graphics_owner.py` is the separate guarded two-owner
qualification entry. It requires an original `native-sample-graphics` executor
qualification before any archive, image inspection or compiler scan; the old96
and single-point sample qualifications are rejected. It requires a clean
committed source tree, checks the complete qualified source archive, and binds
original Mesa/DRM headers, eight link archives, both actual state-tracker
objects and every file on the specified runtime library path. The private NIR
source must match the explicit checked postimage. Generated compiler inputs
are bound before their actual compilation and checked against all 28 MMD
units, with the exact LIVE gates and fixed link recipe.

The qualifier builds once in the immutable 512MiB/single-CPU container without
starting the producer, then rechecks the original source/tool/model,
compiler/dependency identities and binary/ESSL products before and after the
ROOT controller starts the binary directly. The controller supplies the real
112-word profile, checks both images and full GNY2 records, observes cleanup
and requires the unique LIVE producer marker. A separate PID-bound consumer
cleanup confirmation gates deletion of outer build inputs; unknown exit
preserves those inputs. CPU tests cover rejection, source and actual MMD/link
closure, execution argument separation and cleanup/quarantine behavior:

```sh
python3 -B scripts/test_qualify_native_sample_graphics_owner.py
python3 -B scripts/qualify_native_sample_graphics_owner.py \
  --executor "$EXECUTOR" --executor-qualification "$QUALIFICATION" \
  --executor-checker "$TOPIC/scripts/prepare_owner_executor.py" \
  --executor-checker-python "$CHECKER_PYTHON" \
  --mesa "$MESA" --mesa-build "$MESA_BUILD" \
  --drm-include "$DRM_INCLUDE" --library-directory "$LD_LIBRARIES"
```

No final112 executor has qualified or executed this path. CPU construction
and rejection tests cannot produce a device PASS, hardware ACK or product
qualification.

Seven fast admission/entry tests and five kernel-lifetime ASan/UBSan tests pass,
including original handles closed before external execution, retained job
references, CPU/LIVE argument and environment separation, missing/empty/mixed
profiles, invalid compile-mode rejection and build-only command planning.
These tests use synthetic control
files and CEASE programs, without actual Mesa LIVE or DUT execution:

```sh
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_gallium_sample_graphics_bridge.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_graphics_sample_live.py
```

The guarded prepare-only helper passed actual Linux ASan/UBSan compilation
and linking at fixed source `12ca453cd`, using immutable image `cc860b33283c`,
one CPU, 512MiB memory with equal swap limit, no network and read-only original
sources/libraries. All 28 actual compilation units and 331 dependencies passed
its per-unit MMD and exact link checks. The 8101 original compiler inputs,
including DRM alias resolutions, headers, eight archives, two state-tracker
objects and runtime libraries, remained unchanged. All 29 generated compiler
inputs were bound before their compilation and rechecked afterwards. Frozen
source/request identities and the finished AArch64 ELF/ESSL products also
matched after the build. All 146 actual GPU compilation dependencies belonged
to the 236-file registered production checker/driver/default closure; none
were omitted. Thirteen lightweight qualification guards pass, including SDK
alias target changes, escapes and cycles.

Existing Mesa libraries were not rebuilt. The builder returned
`BUILT, execution=false`: the producer was never started, and no execution
result, READY, RAM capture or GPU result was produced. This CPU validation's
source map was not an executor qualification. The container and all child
processes ended; private build, source and raw outputs were removed. Actual
LIVE execution remains untested.

Bounded Linux ASan/UBSan validation used original Mesa22.3.6 static libraries
and the unchanged original ESSL sources: VS308/FS301 words, actual reflection,
24 UBOs and two 1024-component defaults, a changed original UBO/default for the
second owner, exact original code in both private RX snapshots, version2
GS-zero submit plans, two OGCAP002 records, union memory and separate 16384-byte
completion views. Source descriptor schemas/reserved bits and the independent
original-source color calculation passed. A final execution-only
replay bound all 8098 Mesa/DRM/library inputs, 28 compiler dependency units, GPU
source/generated inputs and the binary before and after execution. All matched.
The container used one CPU, 512MiB memory with equal swap limit, no network and
read-only original inputs. All private outputs and containers were removed.

The control/retirement/cleanup peer in this entry is explicitly modeled CPU
behavior. No shader ran, no GNY2 publication or image was produced by a DUT, and
neither sample builtins nor the six 640x480/60FPS product scenes are qualified.
Actual qualification of the strict two-owner 112 executor, independent full
GNY2/image oracle and live resource/cleanup protocol remains required. ABI6 uniform/flat lowering and
ABI7 with an actual GS owner also remain separate work.

Fast CPU admission tests are in both common CPU lanes:

```sh
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_gallium_sample_graphics_bridge.py
```

They check exact112/old96 isolation, OGNP0002/EOF rejection, legal two/four-bank
acceptance, odd-bank rejection, unchanged error outputs and CPU-only admission.
The complete host `test_driver.py` regression passes at source `c079b5f5c`
with per-fixture ASan/UBSan and actual Mesa22.3.6/DRM headers. It includes the
new source, canonical recipe, codec, two-owner result, controller and original
qualification guards alongside the snapshot, submission, validator, MMU and
Gallium framebuffer paths. The Linux `sync_file`-dependent Gallium compute
fixture is unavailable on macOS; the separate Linux CPU capture and guarded
build above retain their stated scopes. No DUT ran. Ended test processes,
managed builds and logs were removed. Reproduce with a managed temporary
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

The new112 CPU entry also writes two independently versioned
`record-{0,1}.source-plan.json` sidecars with schema
`opengpu-native-sample-graphics-source-v1`. The old96 `.source.json` schema is
unchanged. Each sidecar records the original GEM handle, allocation PA/extent
and logical offset/bytes for both original programs, all 24 UBOs and two
defaults, frame resources, vertex input/scratch and the 16KiB completion view.
It includes both private RX addresses, complete original 308/301-word programs,
stage layouts, linked binding-to-slot observations, GUR/GUB addresses and 5-bit
indices, private segment/resource mappings and the actual Sv32 root/leaf words
and permissions. A separate compiler-reflection file comes from the unchanged
actual Mesa linked-program accessors before plan construction.

`native_sample_graphics_source_oracle.py` requires the separately supplied
original compiler bytes and reflection. It checks the full original and private
26 windows against the fixed input arithmetic and the two second-record XOR
patches, independently rebuilds every GUR/GUB word and padding byte, checks all
mapped PTE pages and compares both private RX images. Its ABI1 RGBA32UI image
oracle follows the ESSL declaration order with uint32 arithmetic, rather than
the linked slot order. It checks the entire original image allocation against
the fixed clear/guard recipe. Neither initial memory nor producer endpoint
values authorize expected source bytes or colors. Passing the same corrupted
source as initial and returned memory is rejected.

The source checker uses the published ABI schemas to validate actual private
record headers and identities, GNJ2 draw/frame bounds, GNF3 state bounds and
both stages' shader PC, ABI1 version and GUR root. This ties the checked code
and uniform copies to the reachable job, rather than accepting an unused
sidecar inventory. This narrow cross-link check is not a complete canonical
GNJ/GNF/GND/GFS or GNY2 oracle.

Six lightweight ASan/UBSan CPU tests pass using two actual Linux protected
snapshots with explicitly synthetic compiler words/reflection. They cover each
original/private uniform window, every GUR/GUB word, both RX images, PTE and
metadata faults, source pointer/identity/bounds mutations, record/reflection/ABI
isolation and synthetic image/guard mutations. The three existing private112
admission/old96 isolation tests also pass. The source test is in both common
CPU lanes and checks the new include
in actual compiler-generated make dependencies:

```sh
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_sample_graphics_source.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_gallium_sample_graphics_bridge.py
```

The explicit actual-Mesa entry requires the full source preflight after
capture. Fixed source `39775cfa1` passed with original Mesa22.3.6 and actual
Linux protected snapshots: original VS308/FS301 words, two owners with 26
windows each, real non-declaration-order linked reflection, all GUR/GUB records
through binding index23, both private RX images, eight frame resource roles
and separate 16384-byte completion views. Both complete canonical descriptor
recipes passed. The independent owner preflight accepted the actual first C
capture, and its capture-identity check accepted the actual second capture,
including job bounds, SATP, context, canonical GNX predicates and exact writable
page envelopes. The OGNP0002 observations were explicitly synthetic CPU inputs,
with an explicitly supplied two-bank shader configuration. Neither capture is
a DUT receipt.

Reproduce with the actual-Mesa CPU entry and environment above; no Mesa library
is rebuilt. After that entry completes, use the separate compiler words,
reflection, source plans and explicitly synthetic profile JSON for these checks:

```sh
PYTHONPATH="$TOPIC/scripts" python3 -B - <<'PY'
import json, os
from pathlib import Path
from compute_owner_oracle import SparseMemory
from native_sample_graphics_recipe import verify
from native_sample_graphics_owner_oracle import preflight, capture_identity
p = Path(os.environ["OUT"]) / "build/glsl"
profile = json.loads(Path(os.environ["CPU_PROFILE_JSON"]).read_text())
programs = [(p / ("native-real-" + stage + ".words")).read_bytes()
            for stage in ("vs", "fs")]
reflection = json.loads((p / "compiler-reflection.json").read_text())
records = [json.loads((p / ("record-%d.source-plan.json" % i)).read_text())
           for i in range(2)]
captures = [(p / ("record-%d.capture" % i)).read_bytes() for i in range(2)]
with SparseMemory(p / "union.memory") as memory:
    for record in records:
        verify(memory, record, programs, reflection)
    first = preflight(memory, records, programs, reflection, profile, captures[0])
    second = capture_identity(captures[1], profile, records[1])
    assert first != second
PY
```

The validation used immutable image `cc860b33283c`, one CPU, 512MiB memory with
equal swap limit, no network and read-only original sources/libraries. The
execution-scoped input binding covered all 10045 external Mesa/DRM/library
files, including both actual state-tracker objects, 3198 frozen GPU inputs
including Python and the three ABI schemas, all 28 actual compiler-generated
dependency units with 328 dependencies and the source-plan/internal-frame
includes, generated inputs and the binary. All 13336 bound inputs matched after
C execution and after the complete independent preflight. The container and
all child processes ended; private builds, source copies and raw outputs were
removed.

These checks establish CPU source, canonical descriptor and capture/oracle
interoperability only. The control/retirement/cleanup peer remains modeled CPU
behavior. No shader or JVM/model ran, no returned image/GNY2 was supplied, and
`verify_result` was not called. Actual two-owner live execution, full returned
GNY2/image validation, observed cleanup and product qualification remain
unfinished. The optional synthetic image/GNY2 tests are CPU oracle tests and
are not GPU evidence.
