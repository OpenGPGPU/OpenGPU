# Full-system simulation performance, 2026-11-09

Measured on the local Apple Silicon host (14 CPUs, 36 GiB) with Verilator
5.050 and the `GpuHostSystemAxi` RTL available on 2026-11-09. Both QEMU
binaries were linked from that same RTL; the FlashSim model merged 3,854 or-chains and cached
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
is the wall-clock comparison. These bounded guest checks do not qualify the
two-CU 640×480/60 FPS product workload or a whole-host physical clock.

## FlashSim-only reproduction

Use a FlashSim-linked QEMU binary and the same Linux Image, firmware directory
and probe initramfs for each timed round. The benchmark requires the guest pass
marker, normal shutdown and QEMU exit zero.

```sh
python3 -B scripts/bench_flashsim_arti.py --qemu "$FLASHSIM_QEMU" \
  --kernel "$LINUX_IMAGE" --firmware "$QEMU_FW_DIR" \
  --initrd "$PROBE_INITRD" --workload probe --rounds 3
```

Add `--candidate "$CANDIDATE_QEMU"` to alternate two FlashSim builds. Keep
the same RTL, driver, kernel, initramfs and host configuration when comparing
backend changes. For the bounded guest examples above, use `--workload examples`
with the examples initramfs. Select the same `--accel` and `--cpu` for both builds.

## ARTI AXI input drive

A test of the archived 64×64 model relinked the same FlashSim-generated DUT
objects and QEMU objects with two ARTI bridges. The candidate drives memory
inputs only when AW ownership or the response queues change. Both bridges pass
the guest examples. Replaying the same trace reproduces 1,596 calls, 296,193
ticks, 19,520 DMA reads, 35,013 DMA writes and 902,000 DMA bytes. The bounded guest
wall-time medians are 5.057 s and 5.062 s.

| Fixed-tick workload | Baseline | Candidate |
| --- | ---: | ---: |
| Idle | 48.39 ns/tick | 43.38 ns/tick |
| Scanout enabled | 253.98 ns/tick | 250.26 ns/tick |

The fixed-tick medians use nine alternating rounds of 8,000,000 ticks per bridge.
Tick, commit and hold counts match in each workload. These results apply to the
archived model. Current RTL requires a fresh model and separate measurements.

```sh
for model in "$BASELINE_MODEL" "$CANDIDATE_MODEL"; do
  c++ -O2 -std=c++17 -Idriver -I"$model" scripts/bench_flashsim_ticks.cpp \
    "$model/libarti_rtl_model.a" -o "$model/bench-ticks"
done
for phase in idle scanout; do
  for round in $(seq 1 9); do
    if [ "$((round % 2))" = 1 ]; then first="$BASELINE_MODEL"; second="$CANDIDATE_MODEL"
    else first="$CANDIDATE_MODEL"; second="$BASELINE_MODEL"; fi
    "$first/bench-ticks" "$phase"
    "$second/bench-ticks" "$phase"
  done
done
```

## Profile-guided build on the current two-CU RTL

Both builds used the 64×64 host RTL emitted from OpenGPU `8a1919172`,
FlashSim `670b3a9` and the same ARTI AXI bridge from `f1b2c29`. The model
shards used Clang `-O2` with precompiled headers and conservative aliasing,
and the wrapper used `-O1`. Both QEMU builds used the same Linux Image and
examples initramfs with HVF on the same Apple Silicon host.

Two instrumented boots of the examples workload supplied the profile. In
seven alternating rounds, the baseline wall-time median was **5.182 s** and
the PGO median was **4.444 s**, a **14.2% reduction**. All rounds checked DRM
registration, compute, vertex draw and ALU output, resolve output and normal
shutdown. The result applies to this bounded workload, which also supplied
the training profile. The complete 640×480 workload has a separate qualification.

Build with `FLASHSIM_PRECOMPILED_HEADER=1`, `FLASHSIM_CONSERVATIVE_ALIAS=1`
and `FLASHSIM_SHARD_OPT=-O2` for all variants. Use the same generated model
and bridge, and link each archive into the same ARTI QEMU source build.
Generate the training archive with `FLASHSIM_PGO_GENERATE=1`; its QEMU link
also needs `-fprofile-instr-generate`. Collect and merge the profiles, then
rebuild with `FLASHSIM_PGO_PROFILE="$PROFILE_DIR/merged.profdata"`.

```sh
LLVM_PROFILE_FILE="$PROFILE_DIR/%p.profraw" \
  python3 -B scripts/bench_flashsim_arti.py --qemu "$TRAINING_QEMU" \
    --kernel "$LINUX_IMAGE" --firmware "$QEMU_FW_DIR" --initrd "$EXAMPLES_INITRD" \
    --workload examples --accel hvf --cpu host --rounds 2
llvm-profdata merge -output="$PROFILE_DIR/merged.profdata" "$PROFILE_DIR/"*.profraw
python3 -B scripts/bench_flashsim_arti.py --qemu "$BASELINE_QEMU" --candidate "$PGO_QEMU" \
  --kernel "$LINUX_IMAGE" --firmware "$QEMU_FW_DIR" --initrd "$EXAMPLES_INITRD" \
  --workload examples --accel hvf --cpu host --rounds 7
```
