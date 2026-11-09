# Full-system simulation performance, 2026-11-09

Measured on the local Apple Silicon host (14 CPUs, 36 GiB) with Verilator
5.050 and the current `GpuHostSystemAxi` RTL. Both QEMU binaries were linked
from the same current RTL; the FlashSim model merged 3,854 or-chains and cached
81,589 wires.

## ARTI probe A/B

`FlashSim/scripts/bench_arti_backends.sh`, three rounds, real QEMU/Linux
host-MMIO and job-queue self-test:

| Backend | Round 1 | Round 2 | Round 3 | Mean |
|---|---:|---:|---:|---:|
| FlashSim | 2 s | 2 s | 2 s | **2.0 s** |
| Verilator-linked QEMU | 20 s | 20 s | 20 s | **20.0 s** |

FlashSim is **10.0×** faster on the probe path. All rounds reported
`PROBE_TIMING: driver load returned to init`.

## Full userspace guest

Both backends booted the same bounded ARTI guest and printed
`OPENGPU USERSPACE EXAMPLES PASS`:

- FlashSim: DRM registration, `kadd 1+2+3=6`, `pipe_vertex_draw` 1009 pixels
  `0xff0000ff`, `vertex_alu` 2016 pixels, `pipe_resolve` 2x `0x80808080`;
  guest kernel time at pass **5.0 s**.
- Verilator: the same pass markers; guest kernel time at pass **1270 s**.

Guest kernel time is emulated Linux time, not wall clock; the probe A/B above
is the wall-clock comparison. The 720p product runs remain separate: the
retained Verilator six-scene run took 75,549 s (~21 h), and the retained
native FlashSim assertion model build took 19,578 s (~5.4 h). A current-main
native FlashSim 720p execution has not been completed.

## Reproduction

```sh
WORK=$PWD/../arti-work/flashsim-probe-timing \
FLASHSIM_QEMU=$PWD/../arti-work/qemu-flashsim-build/qemu-system-aarch64 \
VERILATOR_QEMU=$PWD/qemu-build/qemu-system-aarch64 \
ROUNDS=3 ./../FlashSim/scripts/bench_arti_backends.sh
```

No RTL or FlashSim source was modified for these measurements; the current
FlashSim skip/eager-wire caches are used as built.
