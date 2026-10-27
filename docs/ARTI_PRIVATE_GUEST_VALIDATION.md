# Private Linux guest validation

The current 64×64 compatibility smoke passes with four general 16-lane,
eight-warp CUs and private four-lane/two-warp graphics. The original driver,
userspace checks, assertions, 300-second fence/MSAA budgets and 30-second flip
budget are retained. This is H1 functional integration, not native four-engine
product qualification or a clock/FPS result.

An already built QEMU machine can advertise a viewport different from a newly
selected model. The resulting driver bounds can reject otherwise valid small
render buffers. Keep the machine's CPU, memory and interrupt tree, and change
only its GPU width, height and stride to match the actual emitted model.
`set_arti_guest_extent.py` checks the complete decoded tree through a `dtc`
round trip and refuses to overwrite an existing result.

The Verilator adapter also advances idle clocks after register transactions.
`build_arti_idle_adapter.py --idle-cycles 64` replaces only that software adapter
in a separate archive; it verifies byte identity of every DUT/runtime member
and preserves the original archive. The advance cap, IRQ stepping, generated
eight-region model and assertions remain unchanged. The qualified copy retains
all 1,660 DUT/runtime objects. This changes host simulation scheduling, not
silicon throughput. Explicit IRQ-poll counts exclude clocks advanced by MMIO;
use actual cycle counters for hardware-rate claims.

Start from a completed assertion-enabled Verilator embedded model and its
matching generated QEMU device object. Use the original compiler/runtime
headers when rebuilding the adapter, and keep those inputs frozen. Set
`arti_model`, `arti_device`, `arti_qemu_build`, `arti_firmware`, `arti_kernel`,
`arti_initrd` and `arti_dtc` to those current inputs. The initramfs must contain
the current driver and complete smoke binary built for 64×64. The Linux build's
`scripts/dtc/dtc` can supply the device-tree compiler.

```sh
arti_out="$PWD/.artifacts/h1-private-guest"
mkdir -p "$arti_out"
python3 scripts/build_arti_idle_adapter.py "$arti_model" "$arti_out/idle64" --idle-cycles 64
python3 scripts/link_arti_model.py "$arti_out/idle64/libarti_rtl_model.a" \
  "$arti_out/qemu-system-aarch64" --qemu-build "$arti_qemu_build" --device-object "$arti_device"
"$arti_out/qemu-system-aarch64" -machine "virt,dumpdtb=$arti_out/original.dtb" \
  -accel tcg -cpu cortex-a53 -m 512M -L "$arti_firmware" -display none -monitor none -serial none
python3 scripts/set_arti_guest_extent.py "$arti_out/original.dtb" "$arti_out/guest.dtb" \
  --width 64 --height 64 --stride 256 --dtc "$arti_dtc"
"$arti_out/qemu-system-aarch64" -machine virt -accel tcg -cpu cortex-a53 -m 512M \
  -L "$arti_firmware" -display none -monitor none -serial "file:$arti_out/serial.log" \
  -kernel "$arti_kernel" -initrd "$arti_initrd" -append console=ttyAMA0 -dtb "$arti_out/guest.dtb"
```

Require actual QEMU exit zero, `OPENGPU USERSPACE DRM PASS`, clean power-down
and no failed guest checks. A boot, model build or successful QEMU process exit
alone is insufficient. Preserve failed runs and raw artifacts locally. Never
reuse an old model against changed hardware sources or stop a live test because
an observation window expired.

The helpers' six CPU checks run through `bash scripts/test_scripts.sh`. The
adapter test links real small C++ archives and proves that the original and
DUT member remain unchanged. These host checks do not build RTL or replace the
complete guest. Full guest runs remain outside ordinary PR/nightly CI.
