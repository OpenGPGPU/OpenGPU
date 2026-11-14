# Physical timing results and whole-host qualification

## Historical block results

The following local ASAP7 TC/SLVT post-route runs used the
`chipagent/openroad:arm64` image, RTL from source `5a192dc7`, Yosys synthesis
and global route with I/O included on 2026-11-10. They qualify those blocks at
that source; they do not qualify the current complete host.

| Block | Verdict | Setup slack | Hold slack | Fmax |
|---|---|---:|---:|---:|
| `TriangleRasterizer` (`raster-quad`) | PASS | +91.98 ps | +28.82 ps | 1227.44 MHz |
| `ScalarBackend` (`scalar-backend`) | PASS | +0.56 ps | +22.12 ps | 1212.75 MHz |

Both passed their post-route gates: setup/hold violations, DRC, antenna,
placement, flow errors and synthesis mapping were clean. `SharedL2Slice` and
`VectorBackend` did not complete route within the two-hour budget and remain
unqualified. Ended run outputs have been removed.

## Whole-host result

The frozen two-CU 640×480 v1 graphics-capacity host failed its 14,400-second
hierarchy-preserving Yosys synthesis attempt while still in the 4.3 optimization
stage. The container had two CPU cores, 12 GiB of memory and no additional
swap. There was no completed QoR, constraint-coverage or clock result. The
requested 1 ns period and 25% I/O delay are constraints, not measured timing.
The configured 1,800-second/10 GiB coverage check was not reached. This failure
does not identify a particular RTL block as its cause.

The integrated v2 host at source `487aacd4b` (PR #772) passed the official
complete product preparation with a fixed 10 GiB JVM heap: all six 640×480
fixtures and the complete connected RTL were emitted and rechecked. This is a
new v2 emission, not a relabeled v1 model. It retains two 16-lane/eight-warp CUs,
sixteen transactions per CU, four engines, the full native API capacities and
all 72 discovery words. Ordinary native geometry draws, native advanced blend
and patch shader images remain explicitly unavailable. Preparation establishes
neither simulation execution nor synthesis, physical timing or FPS.

Whole-host physical timing remains open. Chisel definition reuse reduces
construction work while retaining physical instances; its IR savings do not
establish area or timing improvement.

## Reproduction and acceptance

Use a clean matching source checkout with the pinned SRAM collateral
initialized, ChipAgent's Python environment and an explicit `CHIPAGENT_DIR`
pointing to its independent primary checkout. Keep all GPU run directories
inside this repository and remove them when their active use ends.

```sh
OPENGPU_QUALIFY_JAVA_HEAP=10G JDK_JAVA_OPTIONS=-XX:ActiveProcessorCount=2 \
  python3 scripts/qualify_native_product_compiled.py --product --alignment-v2 \
  --stage prepare --scene-jobs 3 --output "$RUN"
python3 scripts/qualify_ppa.py "$RUN/rtl" GpuHostSystemAxi \
  --expected-sram-macro srambank_64x4x32_6t122=752 \
  --expected-sram-macro srambank_64x4x64_6t122=1232 \
  --stage synthesis --synthesis-engine yosys --yosys-preserve-hierarchy \
  --clock io_s_axi_aclk --period-ps 1000 --io-delay 0.25 --timeout 14400 \
  --coverage-timeout 1800 --coverage-memory-gib 10 --output "$PPA_RUN"
```

The second command describes the synthesis check to run on the matching v2
RTL; no successful v2 synthesis result is claimed. Apply explicit container
CPU/memory limits before that run. Preserve every DUT operator and real SRAM
macro, and check the resulting linked whole-design netlist. Mapping success
must include resolved operators and drivers, the expected physical SRAM
instances and all clock/I/O endpoints; block timing and abstracted DUT modules
cannot establish the host clock. Post-route acceptance additionally requires
global setup/hold, placement, routing, DRC and antenna gates to pass.

For 60 FPS, each complete independently verified product frame must fit
`floor(qualified_host_clock_hz / 60)` service cycles at that matching physical
clock. A requested 1 GHz clock gives a planning budget of 16,666,666 cycles,
but a requested clock, partial frame or simulator wall-time measurement cannot
establish this requirement.

The per-master expectations above come from the complete source-instance
traversal of the fixed v2 emission. Recheck them after a source change with
`python3 scripts/report_ppa_sram_inventory.py "$RUN/rtl" GpuHostSystemAxi`.
The source report does not prove mapped retention: synthesis checks the actual
saved ODB in the same OpenROAD invocation as `check_setup`, and route checks
the actual final ODB. Missing, retyped, nonphysical or unexpected macro masters
fail, including equal-total type substitutions. The tiny two-type/multiple-
instance OpenDB oracle passes both count gates; it establishes no product
physical timing. Reproduce it explicitly with:

```sh
python3 scripts/verify_ppa_sram_macros.py \
  --image chipagent/openroad:arm64 --sram-root /path/to/pinned/generated
```

## Protected-owner whole-host flow

The 96-word `two-cu-vga60-owner-v1` host with the shared tessellation bank has
an exact source-hierarchy contract of 752 `srambank_64x4x32_6t122` and 992
`srambank_64x4x64_6t122` instances. The source inventory is not mapped retention
or timing. Use the new owner-specific entry point against its actual prepared
source checkout; do not apply the earlier 72-word counts or borrow its clock:

The complete frozen 96-word shared-bank host reached the 14,400-second limit
in pre-mapping Yosys OPT with two CPUs and 12 GiB: no mapped netlist, clock or
STA result was produced. Source and tool checks completed, and no physical
qualification passed; the command below reproduces this diagnostic budget.

```sh
CHIPAGENT_DIR=/path/to/chipagent PYTHONDONTWRITEBYTECODE=1 \
  /path/to/chipagent/.venv/bin/python -B scripts/qualify_native_owner_ppa.py \
  --owner-root "$OWNER_ROOT" --prepared "$OWNER_PREPARED" \
  --stage synthesis --period-ps 1000 --timeout 14400 \
  --docker-cpus 2 --docker-memory-gib 12 \
  --coverage-timeout 1800 --coverage-memory-gib 10 \
  --output .artifacts/native-owner-synthesis
```

The wrapper invokes the frozen owner's original preparation checker before and
after the flow, binding all 96 discovery words, complete Scala/build/SRAM
inputs, assertions and RTL. It separately binds the current physical scripts,
ChipAgent Python sources, timing configuration and original SRAM LIB/LEF files.
Mutation or flow failure removes any success gate. The owner checkout supplies
the physical SRAM collateral explicitly; a different checkout's source is not
used to relabel the DUT. ChipAgent must include [PR #2](https://github.com/OpenGPGPU/chipagent/pull/2)
(main `638d8e9f`).

Both main flow and synthesis STA containers receive their CPU and memory limits
at creation, with memory-swap equal to memory. Coverage checks use one CPU and
their separate declared memory limit. A real one-CPU/one-GiB cgroup check confirms
zero additional swap. These limits are diagnostic budgets, not measured host
requirements or a guarantee of completion.

After successful synthesis diagnosis, a separately budgeted `--stage route`
run uses full timing closure without the synthesis-only hierarchy option.
Acceptance still requires clean global setup/hold, placement, DRC, antenna and
flow metrics, resolved mapping, and all expected physical macros. The final
check loads the actual `6_final.odb` with its actual `6_final.sdc` and pinned
TC/SLVT timing libraries, then runs `check_setup`; missing constraints,
unclocked registers, unconstrained endpoints or changed inputs fail closed.
The tiny OpenDB oracle passes both macro gates and final constraint coverage;
actual missing-clock and missing-I/O-delay variants are rejected. It does not
synthesize or route the whole host. Whole-host clock and 60 FPS remain open.

Reproduce the CPU guards and the one-CPU/one-GiB OpenDB check with:

```sh
python -m pytest -q -p no:cacheprovider \
  scripts/test_qualify_ppa.py scripts/test_qualify_native_owner_ppa.py
python -B scripts/verify_ppa_sram_macros.py \
  --image chipagent/openroad:arm64 --sram-root "$OWNER_ROOT/depends/asap7_sram_0p0/generated"
```
