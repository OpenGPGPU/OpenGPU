# Complete owner compute resource composition

`OwnerResourceProfile.computeStage` adds the checked shared atomic owner to
both CUs of `NativeOwnerHostProfile.target`. It keeps two CUs, sixteen lanes,
eight warps, 16 KiB shared SRAM with sixteen banks, the complete native and
compute services, 2048×2048 API storage capacity and the 640×480 workload.
The original owner-v1 factory and its discovery table remain unchanged.

The new identity is `two-cu-vga60-owner-v2-compute-stage`. Its discovery differs
from owner-v1 only at GCR flags address `0x368`: `0x3ffff` becomes `0x7ffff`.
Complete RTL emission and the original preparation/source/assertion checks
pass. The emitted `GpuSystem` retains two actual `GpuComputeUnit` instances;
their CU core contains `SharedAtomicVectorExecuteUnit`. All 796 generated
assertion files and the characterized SRAM model source remain present.
This establishes complete structural composition, without GPU execution or
a timing result.

The stage is intermediate: `fullResourceQualification=false` does not qualify
the final geometry/layered composition, any API, complete GPU execution,
whole-host clock or 60 FPS. A fresh complete model and matching executor are
required; an owner-v1 model cannot execute the new atomic cases as qualified
hardware.

The compute source controller accepts sixteen original Mesa ESSL dynamic shared
atomic cases: all ten word operations and six signed overloads. Each source has
four workgroups of 128 invocations and initializes all 4096 shared words.
The independent returned-byte oracle checks conditional old values, inactive
lanes, final shared values and per-address old-value multisets. It permits
arbitration order only inside each original output bucket and checks every
other physical allocation byte unchanged. All shared words use the same
initial seed; these programs do not distinguish address permutations that
preserve their old-value multisets and final outputs. Nonuniform per-address
and per-group initialization remains a broader addressing qualification.
CPU capture never supplies GPU results. The legacy shared and full UBO/SSBO sources remain available.

Prepare a new complete host with all assertion layers and the characterized
SRAM models:

```sh
python3 scripts/prepare_native_owner_flashsim.py --stage prepare --compute-stage \
  --java-heap 24G --output "$PWD/.artifacts/owner-compute-stage"
python3 scripts/prepare_native_owner_flashsim.py --stage check \
  --output "$PWD/.artifacts/owner-compute-stage"
```

The factory is deliberately separate from the frozen owner-v1 executor.
`qualify_compute_owner.py --case mandatory-compute` requires a matching
`two-cu-vga60-owner-v2-compute-stage` executor qualification before creating a
producer. `prepare_owner_executor.py` selects its distinct
`compute_stage_owner_driver.cpp` from the strictly checked preparation manifest.
The qualification binds that factory, source wrapper, complete include closure,
compiler, generated model and executable. A v1 record, driver or model cannot
be relabeled as the compute stage; native-v1 executors retain their exact table.
The stage is currently supported only by the compute executor. See
[the actual owner lifecycle](COMPUTE_HOST_EXECUTION.md) for the required
producer, original-memory oracle and synchronous cleanup protocol.

The stage executor's actual MMIO probe requires `0x7ffff`, every other unchanged
discovery word and the ten raw auxiliary observations. It publishes
`opengpu-compute-owner-profile-v2` with the intermediate stage identity, which
the controller encodes as `OGCP0002`. Both execution admission and capture
parsing require the same source-selected exact factory; neither chooses a
weaker profile from incoming data. The opcode-0 capture, held/retired receipt
and synchronous cleanup protocols retain their existing formats.

After fresh RTL/model generation, build and independently check the executor
with the original generation interpreter and fixed FlashSim/CIRCT environment:

```sh
python3 scripts/prepare_owner_executor.py --stage build --kind compute \
  --owner-output "$PWD/.artifacts/owner-compute-stage" \
  --flashsim-dir "$FLASHSIM" --shards 32 --jobs 1
python3 scripts/prepare_owner_executor.py --stage check --kind compute \
  --qualification "$PWD/.artifacts/owner-compute-stage/executors/compute/qualification.json" \
  --executor "$PWD/.artifacts/owner-compute-stage/executors/compute/executor"
```

CPU ASan/UBSan checks pass for both exact factories, all changed discovery
words, opposite-factory rejection before dependent probes, actual probe JSON
through the controller wire parser, held/retired observations and final
owner/context/Bare cleanup. Actual Linux ioctl/scheduler/MMU capture also passes
the matching canonical C++ parser for both factories, including the six failed
cleanup-ACK retention cases. Eight executor qualification tests reject mixed
driver/model factories, altered source/include closures and stale build/tool
identities. These checks execute no GPU shader and do not qualify returned
Mesa results. Run them with:

```sh
OPENGPU_TEST_SANITIZERS=1 python3 scripts/test_compute_owner.py
OPENGPU_TEST_SANITIZERS=1 python3 scripts/test_native_owner.py
OPENGPU_TEST_SANITIZERS=1 python3 scripts/test_compute_host.py --canonical-root "$PWD"
OPENGPU_TEST_SANITIZERS=1 python3 scripts/test_compute_host.py --compute-stage --canonical-root "$PWD"
python3 scripts/test_prepare_owner_executor.py
```

Actual Mesa 22.3.6 ESSL/NIR/TGSI, Gallium/library submission and the original
Linux ioctl/scheduler/MMU capture passed all sixteen dynamic atomic sources
with GCC ASan/UBSan. All sixteen untouched original-memory outputs failed the
independent oracle as required. These are CPU capture results, without a GPU
completion, returned image, frame-rate result or hardware cleanup receipt.

Reproduce a source capture with the independently checked compute-stage
profile wire, the configured Mesa dependencies described in the owner guide,
and a fresh private build/session directory per case:

```sh
python3 scripts/test_gallium_compute_host.py --sanitize --capture-only \
  --build NEW_BUILD --session NEW_SESSION --profile-wire COMPUTE_STAGE_WIRE \
  --mesa-shared-ntt CHECKED_PRIVATE_NTT --case shared-dynamic-0
```

Repeat with `shared-dynamic-0` through `shared-dynamic-9` and signed overloads
`shared-dynamic-i-0`, `-i-1`, `-i-2`, `-i-3`, `-i-4`, `-i-9`. The profile wire
used for this CPU check describes the factory and is not a live DUT probe.
The staged NTT postimage is checked before compilation. The compiler container
keeps the shared Mesa/source/build trees read-only.

Reproduce focused CPU transport, rejection and configuration checks:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_compute_host.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_compute_owner_protocol.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_compute_owner_oracle.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_qualify_compute_owner.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_prepare_native_owner_flashsim.py
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 \
  'testOnly opengpu.system.NativeOwnerHostProfileSpec'
```

The tests reject a relabeled old factory, any changed discovery word and
premature execution/performance labels. They do not establish the six complete
640×480 rendering results, cross-domain coherence, native geometry admission,
matching physical timing, or H6–H8 acceptance.
