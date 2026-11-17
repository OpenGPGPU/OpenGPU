# Protected112 framebuffer blit executor

`framebuffer-blit-sample` is a distinct fixed executor kind for
`two-cu-vga60-owner-v2-sample-stage`: 112 discovery words, GCR flags
`0x1fffff`, two CUs, sixteen lanes and eight warps. It uses the original
complete prepared factory without changing its capacities, RTL, SRAM models
or assertion layers. Its actual translation unit is
`native_framebuffer_blit_sample_driver.cpp`; an old96, single-point sample or
ordinary-graphics executor cannot supply this qualification.

## Actual complete-host execution (P06)

All 43 original caller packets passed on the complete assertion-enabled
protected112 factory using the compact Verilator `-O1` model. The source
deliveries in [#854](https://github.com/OpenGPGPU/OpenGPU/pull/854) and
[#856](https://github.com/OpenGPGPU/OpenGPU/pull/856) supplied rejected-MMIO
diagnostics and the VM-shootdown teardown barrier; the qualified compact
backend is delivered by [#857](https://github.com/OpenGPGPU/OpenGPU/pull/857).

The run preserved every original shader, kernarg, view, map and expected byte.
All 43 freshly captured packets were byte-identical to the original P06
packets; the 98-file caller/compiler-input closure passed admission and final
revalidation. Each case passed actual retirement, independent destination
bytes, read-only/VM guards, publication and context teardown. The source AXI
read fault and healthy reuse passed, followed by both delayed-response reset
channels, rejected submit during drain, partial-cancellation bytes, no writes
after reset acknowledgement, VM revocation and healthy command-ID reuse.

The complete qualifier exited zero in 3137.530 seconds, with peak charged
process-tree memory of 485,867,520 bytes under an 8 GiB/14600-second guard.
Source/tool/model/binary and original input identities were rechecked after
execution. Consumer exit and removal of the managed result directory were
confirmed; raw results and process logs are not archived.

This is the original bounded caller corpus: 28 distinct 2048-byte shader
programs, local size 1x1x1 and dispatch grids 2x1x1 or 2x2x1, with small
4x4 destinations and a 5x3 source in the raw-copy cases. The factory retains
its complete two-CU capacity. Original Linux MMIO replay, full-resource
coexistence, six full 640x480 product frames, physical timing and FPS remain
separate acceptance gates. This result closes P06 when its delivery merges
through the protected PR checks.

The source-selected wrapper shares the existing blit execution body. All 43
original caller packets remain mandatory: both filters across sixteen format
pairs, depth/stencil cases, six raw copy formats and same-GEM copy. Admission
retains the externally pinned input manifest, original caller source and
compiler hashes, actual code/kernarg/maps, independent destination bytes and
read-only/VM guards. The driver retains the source SLVERR exercise, delayed R
and B responses across unified reset, partial-cancellation byte checks, ACK
drain, revocation and healthy command-ID reuse. Every executor run starts from a real
112-word DUT probe; the wrapper does not fabricate discovery observations.

After clearing PTEs and requesting a full shootdown, teardown polls the actual
VM-busy status before clearing either SATP. The same MMIO loop services AXI
traffic and retains the original record cycle budget. A CPU protected-MMIO
peer covers delayed and immediate readiness; permanent busy exhausts the
budget without clearing the live SATPs or resetting the model. These checks
do not establish successful complete DUT execution.

The separate result schema is `opengpu-framebuffer-blit-sample-host-v1`.
It binds the exact kind/profile/stage and all 43 retirement observations plus
fault and both reset channels. Old96 and protected112 result contracts reject
one another. `originalLinuxTrace=false`, `fullResourceQualification=false`,
`finalProductQualification=false`, `clockQualified=false` and
`fpsQualified=false` remain explicit: these caller packets use a bounded host
qualification VM, not an original Linux MMIO replay.

Build only after the exact complete sample-stage model has finished generation
and native compilation. Use its original generation interpreter, checkout and
compiler environment. The [complete factory reproduction](OWNER_FULL_FACTORY_REPRODUCTION.md)
prepares the compact Verilator model and all four original executors. If this
kind has not already been built, its separate Verilator build command is:

```sh
"$GENERATION_PYTHON" -B scripts/prepare_owner_executor.py --stage build \
  --backend verilator --kind framebuffer-blit-sample --owner-output "$OWNER_OUTPUT"
```

For a completed FlashSim model, use its original CIRCT environment and matching
shard configuration instead:

```sh
"$GENERATION_PYTHON" -B scripts/prepare_owner_executor.py --stage build \
  --kind framebuffer-blit-sample --owner-output "$OWNER_OUTPUT" \
  --flashsim-dir "$FLASHSIM" --shards 32 --jobs 1
```

Capture all 43 original callers from the same checkout, using Mesa 22.3.6 and
the original DRM headers. Select a fresh internal artifacts directory; the
capture writes the independently pinned manifest and original code/kernarg/maps:

```sh
CALLER_DIRECTORY="$(mktemp -d "$PWD/.artifacts/blit-callers-XXXXXX")"
CALLER_INPUTS="$CALLER_DIRECTORY/inputs"
OPENGPU_MESA_DIR="$MESA" OPENGPU_DRM_INCLUDE="$DRM_INCLUDE" \
  "$GENERATION_PYTHON" -B scripts/run_owner_bounded.py \
  --memory-mib 1024 --timeout-seconds 300 -- \
  "$GENERATION_PYTHON" -B scripts/test_gallium_compute.py \
  --fixture framebuffer-blit --artifacts "$CALLER_INPUTS"
CALLER_MANIFEST_SHA256="$("$GENERATION_PYTHON" -B -c \
  'import hashlib, pathlib, sys; print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())' \
  "$CALLER_INPUTS/manifest.json")"
"$GENERATION_PYTHON" -B scripts/qualify_framebuffer_blit_sample_host.py --stage check \
  --executor "$OWNER_OUTPUT/executors/framebuffer-blit-sample/executor" \
  --executor-qualification "$OWNER_OUTPUT/executors/framebuffer-blit-sample/qualification.json" \
  --inputs "$CALLER_INPUTS" --input-manifest-sha256 "$CALLER_MANIFEST_SHA256"
"$GENERATION_PYTHON" -B scripts/run_owner_bounded.py \
  --memory-mib 8192 --timeout-seconds 14600 -- \
  "$GENERATION_PYTHON" -B scripts/qualify_framebuffer_blit_sample_host.py --stage run \
  --executor "$OWNER_OUTPUT/executors/framebuffer-blit-sample/executor" \
  --executor-qualification "$OWNER_OUTPUT/executors/framebuffer-blit-sample/qualification.json" \
  --inputs "$CALLER_INPUTS" --input-manifest-sha256 "$CALLER_MANIFEST_SHA256" \
  --timeout 14400
```

Remove `CALLER_DIRECTORY` after validation and proven consumer exit.
`GENERATION_PYTHON` must be the original model-generation interpreter. Preserve
the matching `PYTHONPATH`, `CIRCT_VERILOG`, `DYLD_LIBRARY_PATH` (where used),
`FLASHSIM_CIRCT_SERIAL` and `FLASHSIM_GC_POLICY`, plus the qualified compiler
environment and shard/PCH/alias options. The existing original checker validates
source, generator/tool identity and build environment again before and after
the run; another interpreter or toolchain cannot relabel the qualification.
`prepare_owner_executor.py` checks the complete original model, preparation,
assertions, toolchain, source and include closure, actual driver translation
unit, compiler flags, all shards and executable. The runner rechecks that
qualification and original packets before and after execution while holding
both owner and executor locks. Timeout/error cleanup uses bounded process-group
exit proof; unconfirmed consumers preserve temporary inputs. The old
`qualify_framebuffer_blit_host.py` remains the separate frozen96 diagnosis entry.

Local CPU checks pass: nineteen executor qualification tests, seven new112
admission/result/exit/source tests, eleven old96 gate/transport tests, the
original 43 caller captures and twenty-seven C++/ASan/UBSan input rejection
cases. The existing two-test sample transport suite also passes with
ASan/UBSan; its actual MMIO CPU peer rejects changes to all 112 discovery words.
Both real blit translation units pass declaration-only C++ typechecking.
The declaration-only source checker accepts its normalized original checkout,
including a normal CI checkout without Git. Cross-checkout owners must share
its actual Git common directory and remain within the GPU's internal worktree
area; protected112 still requires the original source root. A separate
repository or escaping symlink does not satisfy that boundary. These rules do
not grant executor qualification or relax the existing DUT owner gates.

GCC/G++13.3.0 passes all seven source/admission tests and nineteen executor
qualification tests, including both actual declaration-only blit translation
units with `-Werror`. The cached compiler ran with equal 512MiB memory and
total memory-plus-swap limits, one CPU, no network, read-only sources and a private temporary overlay.
The old96 eleven-test gate suite passes separately with the host's real Git
and Clang16; the cached image lacks Git and cannot run its two Git-dependent
negative checks. No compiler/tool was installed for these checks.

These CPU checks retain their limited scope. The complete112 execution above
supplies the bounded P06 DUT and fault/reset result; original Linux trace
qualification, six full 640×480 product frames and physical timing remain open.

```sh
python3 -B scripts/test_prepare_owner_executor.py
CC=gcc CXX=g++ OPENGPU_TEST_SANITIZERS=1 \
  python3 -B scripts/test_framebuffer_blit_sample_host.py
python3 -B scripts/test_framebuffer_blit_host.py
OPENGPU_MESA_DIR="$MESA" OPENGPU_DRM_INCLUDE="$DRM_INCLUDE" \
  python3 -B scripts/test_framebuffer_blit_inputs.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_sample_owner.py
```
