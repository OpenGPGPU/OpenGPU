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
required for the two-CU 640×480/60 FPS target. Final driver PTE revocation and
TLB shootdowns must also be verified on the live DUT.

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
