# Protected native owner host

`NativeOwnerHostProfile.target` selects `two-cu-vga60-owner-v1`: two shared
16-lane/eight-warp CUs, a 640×480 display/workload target, sixteen memory
transactions per CU and a 32-byte memory AXI port. It retains four native
engines, 2048×2048 API render-target capacity, 65,536 paged entries, sixteen
vertex attributes/bindings/varyings, four colors and the 512 KiB payload pool.
It enables actual native advanced execution, descriptor preflight and protected
source discovery. The frozen 72-word `two-cu-vga60-v2` profile remains separate.

The exact 96-word discovery contract contains GCR1, GNC3, GCI1, GQV1, GFC1,
GQP1, GUC1, GAB1, GNP1 and GSL1, with GCR flags `0x3ffff`. Runtime discovery
must also read actual ID/capabilities, the native capability pointer, the live
quota and `SHADER_CONFIG`; a preparation manifest supplies no runtime values
for those registers.

Local validation passes the six profile/configuration and bounded complete-host
elaboration tests, the input rejection tests and CI suite selection. These
checks execute no rendered GPU frame and establish no whole-host clock or FPS.
Fresh assertion-enabled DUT execution, exact stored-image/sample/resolve and
ownership/fault checks, plus matching physical timing and bandwidth, remain
required for the two-CU 640×480/60 FPS target. Final driver PTE revocation,
VM/ASID teardown and global MMU shutdown must also be verified on the live DUT,
including a full shootdown and readback of both bare SATPs before freeing the
last page tables.

Reproduce the configuration checks with:

```sh
sbt -J-Xmx6G 'testOnly opengpu.system.NativeOwnerHostProfileSpec opengpu.system.NativeAlignmentHostProfileSpec'
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_prepare_native_owner_flashsim.py
sbt 'Test / runMain opengpu.system.PrepareNativeOwnerHost --describe'
```

For complete RTL preparation, use a new directory inside this checkout:

```sh
python3 scripts/prepare_native_owner_flashsim.py --stage prepare \
  --output .artifacts/native-owner-host
python3 scripts/prepare_native_owner_flashsim.py --stage check \
  --output .artifacts/native-owner-host
python3 scripts/prepare_native_owner_flashsim.py --stage generate \
  --output .artifacts/native-owner-host --flashsim-dir "$FLASHSIM" --shards 32
```

Preparation emits the actual product factory with SRAM models and assertion
layers. Checks bind every discovery word, all Scala/build/SRAM inputs, the
preparation tools and emitted RTL before model generation. FlashSim's existing
generator checks retain assertion coverage and reject stale model/tool inputs.
The preparation/generation commands are not execution qualification and retain
`gpuExecution`, `clockQualified` and `fpsQualified` as false. Remove their
working outputs once the development/validation use ends.

The compute/native sequence also accepts this exact 96-word factory. Reuse the
prepared owner directory; the sequence preparation adds only the six original
32×32 input recipes and does not emit another DUT:

```sh
python3 scripts/qualify_native_alignment_sequence.py --owner-profile \
  --output .artifacts/native-owner-host --flashsim-dir "$FLASHSIM" --stage prepare
python3 scripts/qualify_native_alignment_sequence.py --owner-profile \
  --output .artifacts/native-owner-host --flashsim-dir "$FLASHSIM" --stage build
python3 scripts/qualify_native_alignment_sequence.py --owner-profile \
  --output .artifacts/native-owner-host --flashsim-dir "$FLASHSIM" --stage run
python3 scripts/qualify_native_alignment_sequence.py --owner-profile \
  --output .artifacts/native-owner-host --flashsim-dir "$FLASHSIM" --stage verify
```

Every scene reads all 96 discovery words from the executing DUT before running
compute/native/compute/native without an intervening reset. The verifier checks
stored images, samples/resolve, both compute epochs and read-only bytes against
independent Scala recipes. Source, assertion, model, fixture and profile checks
reject a changed preparation or a frozen 72-word v2 model.

Local validation passes the command/data tests, fixture binary roundtrips and
CPU ASan/UBSan corruption checks. These validate input generation
and rejection only; this owner sequence has not yet completed on a freshly
generated full DUT. Reproduce the command/data and CPU checks with:

```sh
sbt 'testOnly opengpu.system.CompiledNativeProductCapacitySpec opengpu.system.NativeAlignmentSequenceDataSpec'
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_native_alignment_sequence.py
```

The 32×32 sequence cannot establish the 640×480 performance target or physical
timing, and all clock/FPS qualification flags remain false.

For complete original product frames, `qualify_native_owner_product.py` reuses
the same separately prepared factory and generated model. Select `--product`
for 640×480 or `--smoke` for 32×32, with explicit `--tile-log2 3` (8×8) or `4`
(16×16). Both retain the same hardware/API capacities. For example:

```sh
python3 scripts/qualify_native_owner_product.py --product --tile-log2 4 \
  --output .artifacts/native-owner-host --flashsim-dir "$FLASHSIM" --stage prepare
python3 scripts/qualify_native_owner_product.py --product --tile-log2 4 \
  --output .artifacts/native-owner-host --flashsim-dir "$FLASHSIM" --stage build
python3 scripts/qualify_native_owner_product.py --product --tile-log2 4 \
  --output .artifacts/native-owner-host --flashsim-dir "$FLASHSIM" --stage run
python3 scripts/qualify_native_owner_product.py --product --tile-log2 4 \
  --output .artifacts/native-owner-host --flashsim-dir "$FLASHSIM" --stage verify
```

Preparation requires fresh fixtures. When switching from the completed
sequence, first remove its ended fixture/output/qualification records after
checking consumers. Keep the common RTL, generated model and build objects
while they are still in use; building the product driver reuses matching
shards. Run these driver qualifications sequentially in a shared output
directory: each build changes its driver/build identity.

The product gate requires all six actual execution rows with the exact owner
profile, resource words, capacities and frame dimensions. Its independent
Scala verifier reconstructs the selected original recipes and checks every
image/sample/resolve plus read-only input and allocation guard. Failed new
execution attempts invalidate prior success records before any source/model
check. CPU rejection tests cover old profiles, partial suites, stale results,
wrong dimensions/tiling and premature clock/FPS claims. Actual owner product
execution and a matching qualified physical clock remain pending; choosing
16×16 tiles alone establishes no performance improvement or 60 FPS result.

`native_owner_driver --probe-compute-profile NEW.json` is a separate read-only
compute admission probe. It preserves the 96 actual discovery words and the
five base runtime registers, then reads the advertised GTM1/GSM1/GIM1/GAM1/GUM1
transport magics at `0x494`, `0x43c`, `0x3fc`, `0x4bc` and `0x460`, respectively.
GCR command-support bits must be present before those dependent reads. The
`opengpu-compute-owner-profile-v1` schema has ten auxiliary registers; the
existing native schema remains at five. Wrong magics, missing prerequisites,
active completions or external memory work fail the probe. No command is
submitted and `gpuExecution`, clock and FPS qualification stay false.

Both probes require the original unified-command, protected-VM, scalar-FPU,
shader-config and native-format capability bits. Raw fragment batch must equal
the 16-lane × eight-warp product (128), and the raw bank count must be even and
at least two, using the driver's shared resource validator. The compute wire
parser enforces the same consistency before accepting a capture. No raw field
is repaired. Executor source guards also bind that shared validator header.
Eighteen CPU tests pass, including ASan/UBSan protocol checks, missing capability
and batch rejection, odd-bank rejection, preserved legal raw bank counts and
executor include-closure checks. Reproduce with:

```sh
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_owner.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_compute_owner.py
python3 -B scripts/test_prepare_owner_executor.py
python3 -B scripts/test_build_native_flashsim.py
```

The active native executor uses `OPENGPU_NATIVE_HOST_TIMEOUT` for its initial
capture, every later capture/retirement and every cleanup response. It accepts
decimal seconds within 1..86400, defaults to 300 when unset, and rejects malformed
or overflowing values before accessing inputs or resetting the model. This is
the CPU session response deadline; captured DUT cycle budgets remain unchanged.
The ASan/UBSan CPU protocol test checks one-second missing capture, retirement,
cleanup authorization and cleanup batch deadlines, with no successful retirement
or cleanup receipt emitted for the timed-out phase.
