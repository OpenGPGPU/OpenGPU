# Protected112 framebuffer blit executor

`framebuffer-blit-sample` is a distinct fixed executor kind for
`two-cu-vga60-owner-v2-sample-stage`: 112 discovery words, GCR flags
`0x1fffff`, two CUs, sixteen lanes and eight warps. It uses the original
complete prepared factory without changing its capacities, RTL, SRAM models
or assertion layers. Its actual translation unit is
`native_framebuffer_blit_sample_driver.cpp`; an old96, single-point sample or
ordinary-graphics executor cannot supply this qualification.

The source-selected wrapper shares the existing blit execution body. All 43
original caller packets remain mandatory: both filters across sixteen format
pairs, depth/stencil cases, six raw copy formats and same-GEM copy. Admission
retains the externally pinned input manifest, original caller source and
compiler hashes, actual code/kernarg/maps, independent destination bytes and
read-only/VM guards. The driver retains the source SLVERR exercise, delayed R
and B responses across unified reset, partial-cancellation byte checks, ACK
drain, revocation and healthy command-ID reuse. Every executor run starts from a real
112-word DUT probe; the wrapper does not fabricate discovery observations.

The separate result schema is `opengpu-framebuffer-blit-sample-host-v1`.
It binds the exact kind/profile/stage and all 43 retirement observations plus
fault and both reset channels. Old96 and protected112 result contracts reject
one another. `originalLinuxTrace=false`, `fullResourceQualification=false`,
`finalProductQualification=false`, `clockQualified=false` and
`fpsQualified=false` remain explicit: these caller packets use a bounded host
qualification VM, not an original Linux MMIO replay.

Build only after the exact complete sample-stage model has finished generation,
using its original FlashSim/CIRCT environment and matching shard configuration:

```sh
"$GENERATION_PYTHON" -B scripts/prepare_owner_executor.py --stage build \
  --kind framebuffer-blit-sample --owner-output "$OWNER_OUTPUT" \
  --flashsim-dir "$FLASHSIM" --shards 32 --jobs 1
"$GENERATION_PYTHON" -B scripts/qualify_framebuffer_blit_sample_host.py --stage check \
  --executor "$OWNER_OUTPUT/executors/framebuffer-blit-sample/executor" \
  --executor-qualification "$OWNER_OUTPUT/executors/framebuffer-blit-sample/qualification.json" \
  --inputs "$CALLER_INPUTS" --input-manifest-sha256 "$CALLER_MANIFEST_SHA256"
```

Use the same command with `--stage run --timeout 3600` for actual execution.
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

These checks execute no generated DUT and do not prove the blit fault/reset
lifecycle on hardware. Actual complete112 execution, original Linux trace
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
