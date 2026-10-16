# Full-system simulation performance investigation

## Tagged staging and fast native checks (2026-10-16)

Commit `3cf451a` overlaps fragment staging with eight local transaction IDs per
worker, scales the shared credit and translation ranges with CU count, pipelines
the shared request queue, and bounds reply buffering by local ownership. It also
removes the forwarding-only mix cycle from non-fractional bilinear samples.
The main source passes 38 targeted tests and four complete runtime-shader scenes,
with identical colour, depth and shader metadata:

| Scene | Previous 1 CU | Current 1 CU | Previous 4 CUs | Current 4 CUs |
| --- | ---: | ---: | ---: | ---: |
| 1×1 texture | 52,050 | 26,008 | 16,640 | 10,730 |
| 64×64 texture | 77,160 | 48,881 | 22,766 | 16,047 |

These are task cycles, not desktop startup times. Both CU counts improve; the
four-CU reductions are 35.5% and 29.5%. A worker request-refill experiment and a
larger sixteen-credit window passed correctness but slightly regressed both
four-CU scenes, so neither was selected. See the
[RTL qualification](performance/pipelined-fragment-staging-2026-10-16.json).
The new frozen native model passes the instrumented 18-case compute replay,
21 native GLES cases, private-depth and offscreen checks, and GTK idle/active
readback plus scanout at 64×64. Identical native render fixtures also pass twice,
with complete memory outputs unchanged: solid rendering falls from 11,952 to
8,827 native ticks, and spatial rendering from 18,523 to 14,135. These reductions
are 26.1% and 23.7%; native callback timing differs from the Scala AXI fixture.
See the [native training qualification](performance/pipelined-staging-native-training-2026-10-16.json).
The optimized build and full-resolution qualification remain in progress.
The latest completed full-resolution proof remains the
[texture-cache model](performance/vector-cache-native-full640-2026-10-16.json).

`benchmark_native_render.py` runs the same small scene through an already built
native model, using render-descriptor dimensions to select a 64×64 offscreen
viewport while keeping the desktop model at 640×480. It reuses the qualified
runtime vertex/fragment binaries, validates the advertised lane/warp/CU layout,
and checks every colour/depth pixel, staging canaries, and all memory outside
the permitted write regions. For example:

```sh
python3 scripts/benchmark_native_render.py \
  --model path/to/model \
  --archive path/to/optimized/libarti_rtl_model.a \
  --report docs/performance/pipelined-fragment-staging-2026-10-16.json \
  --repeat 3 \
  --output generated/qualification/native-render
```

The output directory must be new. The archive's build manifest must match its
hash and generated header; use `--profile-runtime` for a training archive. Each
case records input/output hashes, model ticks, DMA counts and host seconds.
MMIO submission can settle the whole job, so the measured interval includes
submission and all model work. Four baseline runs passed with stable outputs
and 11,952/18,523 ticks, taking about 1.1–1.3 seconds each on this host. These
fixtures accelerate native checks and comparisons; Debian, Mesa, GTK readback
and full scanout still require the separate guest qualification. Host-speed
comparisons require paired runs with controlled competing workloads. See the
[fixture qualification](performance/native-render-fixture-2026-10-16.json).

## Shared four-CU candidate: real Weston trace (2026-10-16)

The native-format candidate passes 18 compute replays, 21 GLES cases and
64x64 Weston/GTK readback plus scanout. An actual Weston/GTK trace replays
29,720 calls and 2,564,082 ticks, with every DMA write checked and no truncated
tail. All four shared shader workers run concurrently. The actual 640x480
Debian/Weston desktop also passes: the captured blue background, panel, launcher
and clock were visually verified on both instrumented and PGO builds. The PGO
capture was observed 336.7 seconds after QEMU start while another guest was
active; this is not a clean performance comparison. See the
[desktop qualification report](performance/shared-cu-desktop-qualification-2026-10-16.json)
and [verified scanout](images/shared-four-cu-weston-20261016.png).
A clean three-round comparison replays the same 29,720 calls and 2,564,082
ticks without other active guest or compiler jobs. Median runtime falls from
**43.73 s with O2 to 30.71 s with PGO and symbol ordering: 29.8% less host
time**. Commits, holds, DMA calls/bytes and all output checks match in every
run. This changes simulator speed, not architectural cycles. See
[the clean PGO measurements](performance/shared-cu-pgo-2026-10-16.json).
A clean sequential desktop pair also verifies both captures. First scanout
falls from **413.5 s with O2 to 320.7 s with PGO**, a **22.4% observed
reduction**. The two large background triangles take 171.423/172.764 s versus
133.698/133.964 s. This is one boot per build, sampled every 15 seconds;
it is not a repeated paired median. Both use the same native-format payload,
simulation watchdog and model timing settings. Compilation starts only after
the captures and guest teardown. See
[the clean startup observations](performance/shared-cu-desktop-startup-2026-10-16.json).
This candidate has not replaced the installed executable described below.

| Completed textured batches | Worker cycles/lane | Shader cycles/lane | Sampler fetch cycles/lane | Fetch fraction of shader |
|---|---:|---:|---:|---:|
| 64x32 texture | 87.49 | 72.60 | 52.55 | 72.38% |
| 64x64 texture | 73.41 | 60.51 | 42.77 | 70.67% |

These are per-worker architectural counts, which overlap across CUs. They
identify texture fetch as the main cost within these textured shader batches;
they are not fractions of whole-guest wall time. Host compilation and a
separate correctness guest were active, so this replay's seconds are excluded
from performance comparisons. A texture line buffer with explicit job/VM
invalidation is the next RTL experiment.

The trace also reveals eight-byte write callbacks on the 64-byte AXI bus:
the simulator adapter splits a wide beat into multiple callbacks. Consequently,
615,696 callback writes do not mean that many AXI transactions. The fill engine
already issues complete cache lines. Wider callbacks are a separate simulator
experiment; they would leave architectural cycle counts unchanged.

An optional texture line buffer is available through the emitter flag
`--buffer-texture-reads`; its default is off pending performance and full-guest
qualification. It merges words from a pending fill, allows independent fills
to overlap and pins each line until its word replies have been queued. The
desktop configuration has up to 16 line entries. Job boundaries, SATP changes,
TLB flushes and reset entry revoke reuse, while admitted shader reads can
still drain. The existing L2 policy and CPU upload/invalidate contract remain
in force. Validation passes 21 buffer/translation tests and 30 shared-CU
pipeline tests, including all 25 native-format pairs. See the
[correctness evidence](performance/texture-line-reuse-validation-2026-10-16.json).
Matched full-pool 1/4-CU tests are measuring cycle savings before enabling it.

The first cache measurements pass every colour/depth check but only reduce
the four-CU 64x64-texture scene from 33,727 to 32,300 cycles (4.2%); line
requests fall from 8,944 to 257. This version is not being promoted. Both the
fragment texture request queue and cache word reply queue inserted empty
cycles after transfers. They now refill on the same edge as consumption.
Two throughput regressions fail before that change and pass afterward;
29 unit/routing/translation tests, 30 cached pipeline tests and four targeted
uncached pipeline checks pass. See
[the throughput validation](performance/texture-port-throughput-validation-2026-10-16.json).
The revised full-pool tests pass all eight scenes. Including both queue and
cache changes, the four-CU 64x64-texture scene falls from **33,727 to 26,637
cycles (21.0%)**; the matched 1-to-4-CU speedup rises from **3.02x to 3.27x**.
The 1x1 texture scene falls from **25,884 to 23,993 cycles (7.3%)**, with
**3.29x** matched scaling. Each scene checks every colour/depth pixel, uniform
sentinels and actual peak CU use, with the complete 16-lane/eight-warp
vertex/fragment/FPU pool and the same runtime shader binary. These are
cap-112 matched comparisons. The full-capacity 128-lane one-CU controls also
pass: **78,034 cycles** for 1x1 and **86,360 cycles** for 64x64 textures,
giving conservative four-CU speedups of **3.25x** and **3.24x** respectively.
New full desktop measurements remain pending. See
[the cycle measurements](performance/texture-line-reuse-cycles-2026-10-16.json).

The sampler now skips coordinate and bilinear stages when the resolved mip
level is 1x1. Format decoding, packed mip offsets and trilinear mixing remain
active. In the solid-texture scene this reduces one-CU cap-112 cycles from
**78,828 to 67,648 (14.2%)** and four-CU cycles from **23,993 to 22,087 (7.9%)**.
The full-capacity one-CU control takes **66,854 cycles**, giving **3.03x**
conservative scaling. The 64x64-texture controls are unchanged. All six scenes
check every colour/depth pixel. The one-CU shader phase saves exactly five
cycles for each of 2,236 samples; the four-CU trace still has 6,068 cycles
with staging active and no shader running. Staging and fixed-LOD calculation
are further candidates; these cycle results do not establish desktop wall-time
gains. The new texture configuration passes the instrumented 18-case compute
replay, 21 native GLES cases, medium reproduction, GTK idle/active readback
and scanout at 64x64. Full 640x480 desktop/GTK qualification remains pending;
the optimized build uses only the profile from that passing guest. See
[the native qualification](performance/shared-cu-texture-native-qualification-2026-10-16.json).
See [the sampler measurements](performance/single-texel-sampler-2026-10-16.json).

`scripts/build_arti_cycle_probe.py MODEL_DIR ARCHIVE OUTPUT_DIR` builds a
separate diagnostic replayer; `--qemu-archive` also produces an archive for a
separate QEMU binary. It rejects a model/archive header mismatch and preserves
existing output directories. Its vector texture counters distinguish gradient
work while the accepted instruction has equal mip clamps. Exact replay of the
older real GTK trace preserves all architectural and original probe counters:
34,264 such worker-cycles for 64x64 textures (3.3% of that shader group) and
6,136 for 64x32. These identify a candidate, not a measured bypass speedup or
whole-frame saving. See
[the probe validation](performance/fixed-lod-probe-validation-2026-10-16.json).

Full 640x480 GTK runs exposed an offscreen viewport bug in both prior
models: a 280x124 application target inherited the 640x480 scanout dimensions
and read back its clear colour. Commit `2f9f09f` adds per-job dimensions in
render descriptor words 14/15 and the DRM submit extension; zero dimensions
preserve older callers. The negative regression fails before the change;
host, shared-CU programmable offscreen and driver tests pass, and driver/Mesa
builds succeed. A fresh full model is being qualified. See
[the extent fix](performance/render-target-extent-fix-2026-10-16.json).

Earlier periodic GTK scanout checks accepted any sufficiently colourful
frame. Those recorded markers do not prove that the active GTK redraw was
presented. `verify_gtk_scanout.py` now requires an active readback with viewport
dimensions and a nearly complete rectangle of the expected colour in the
actual framebuffer. It rejects both saved full-resolution failure captures,
stale green frames and partial triangles/tiles. The gate freezes the exact
verified bytes as `gtk-smoke-verified.ppm`, with a hash and rectangle report,
so display timer updates cannot replace the reviewed capture. Compact tests
use a fullscreen GL area; the normal desktop keeps its 320x240 GTK window.

The wider-callback experiment passes its unit and guest correctness checks,
but six clean native GLES guests show **9,395.220 ms with eight-byte callbacks
versus 9,503.885 ms with 64-byte callbacks** (1.2% slower). Eight-byte callbacks
remain the default; 64-byte callbacks remain opt-in, not a claimed speedup.
See [the rejected callback-default experiment](performance/wide-callback-experiment-2026-10-16.json).

See [the trace measurements](performance/shared-cu-weston-trace-2026-10-16.json)
for raw phase counts, hashes, configuration and limitations. A bare guest module
also hit its 30-second kernel watchdog during the first large desktop draw.
The passing full-resolution retries use the documented simulation settings:
a 30-minute watchdog and four-millisecond fence slices.

## Installed RTL optimization (2026-10-15)

The default launcher now uses the qualified RTL changes in `34d8f7a` and
`ed7636b`, with a freshly trained profile. Resolution remains **640x480**;
general compute remains 16 lanes/eight warps per CU, and graphics remains
four lanes/two warps. Guest userspace and the fragment ABI are unchanged.

| Measurement | Previous model | Installed model | Time/cycle reduction |
|---|---:|---:|---:|
| First verified full desktop scanout, after QEMU start | 17 min 56 s | 5 min 51 s | 67.4% observed |
| Native GLES triangle, median of three fresh guests | 34.706 s | 11.958 s | 65.5% |
| Fixed shader scene, 1x samples | 68,026 cycles | 10,754 cycles | 84.2% |
| Fixed shader scene, 4x MSAA | 79,215 cycles | 22,150 cycles | 72.0% |

The native benchmark draws one triangle with a 160x120 bounding box in a
640x480 mode. Six fresh guests alternate executables, all pass pixel checks,
and capture is disabled. The desktop comparison is one fresh headless boot
per model with periodic scanout capture observed every 15 seconds; it is an
observed startup improvement, not a repeated paired median. The new boot also
enables throttled model statistics. Its last black capture is at 335.7 s and
the first verified background, panel, launcher and clock at 350.8 s. Including
launch preparation, the capture takes 354.9 s. Application readiness and
input latency require separate measurements.

The rasterizer now skips wholly uncovered quads after screen, scissor and
sample coverage, preserving all four helper lanes in every nonempty quad.
For the installed eight-fragment batches, staging uses five masked line
writes and two line reads instead of 64 writes and 24 reads. Other batch
widths retain the word path. Partial batches preserve inactive words and
uniforms; full-line cache invalidation and the existing output-control/depth
ABI remain intact. A separate regression fixes retirement when the final
fragment is blocked and raster flush arrives after a full batch completes.

The two full desktop background draws now take **152.597 s and 148.731 s**,
versus 491.562 s and 492.671 s. Surrounding throttled statistics show about
**50 million model ticks** for the pair; these are log brackets, not exact
per-draw hardware counters. The earlier 213–225 million cycle estimate below
applies to the previous RTL. All work through the new capture records about
58.1 million model ticks, including subsequent GTK activity.

A fresh trace replays all 34,707 calls and 8,595,267 ticks, with every DMA write
checked and no truncated tail. Completed 64x64 textured batches average
19.83 staging-write, 132.35 shader, 4.25 readback and 1.93 launch/emit cycles
per processed lane: **158.36 cycles/lane**, with shader execution now 83.57%
of the total. The earlier trace's corresponding group averages 346.60
cycles/lane, but the traces contain different batch populations; total trace
ticks are not a fixed-work speed comparison. Shader instruction count and
serialized texture sampling are the next measured targets. Graphics-width
changes still require a coordinated shader/ABI migration.

For roughly 307,200 processed background lanes after empty-quad rejection,
the new measured batch average predicts about **48.6 million batch cycles**,
consistent with the approximately 50 million observed model ticks. Edge
helpers, texture extent, cache behavior and downstream work prevent an exact
prediction. The staging bus-data lower bound is now roughly **2.15 million
cycles**: seven line transactions per eight-fragment batch, eight data beats
per transaction on the 64-bit bus. This excludes transaction overhead,
translation, shader and output work; it is not a complete frame-cycle target.

Validation includes 16 fragment-stage tests, 61 final integration tests,
a 12-scene pixel-checked workload sweep before the retirement fix, and both
shader cycle scenes rerun afterward. The final executable separately passes
compute rounding, native GLES pixels, GTK idle/active pixels and periodic
scanout. The full desktop image is visually verified in
[`images/weston-desktop-rtl-optimized-20261015.png`](images/weston-desktop-rtl-optimized-20261015.png).

Current artifact SHA-256 values:

| Artifact | SHA-256 |
|---|---|
| QEMU executable | `ba65addea4834f852ac67c82f3c2ca02eec213fe334e0d9b07e94f508b2b43a0` |
| Model archive | `4f8fa67fa308a3f06b8c954dde50d9eb3ff38e932901a1a2b193533dfd2ae27d` |
| Training profile | `226ca0f29d44812e08454bba94b100959acf9c898358336e7a496456b19d1f6e` |
| Complete 456-file RTL bundle | `ed34bb7b496587949e9155033813984018c2156798e4b58b165a2c8d42ddd479` |

The top-level SV text is unchanged; use the complete RTL bundle digest to
identify this change. The digest hashes sorted filelist names followed by NUL
and each file's raw SHA-256 digest. Generated C++, RTL, profile, ordering,
device compile command, qualification and rollback are saved under
`../arti-work/debian-640x480-flashsim-rtl-optimized/`. Its `rebuild.sh` creates
a separate executable. Both default FlashSim executables and the staged model
archive are installed and hash-verified; the previous qualified directory and
its original rollback remain available.

Portable samples, counters, hashes and limitations are in
[`performance/rtl-speed-2026-10-15.json`](performance/rtl-speed-2026-10-15.json).
Raw logs, trace and timing artifacts are in `.artifacts/rtl-speed/`.
The following sections retain the earlier simulator investigation and RTL
audit as the baseline for this optimization.

## Earlier simulator baseline

The earlier reference baseline is the non-tile-binned 640x480 FlashSim model with vertex
and fragment cores, plus the optimized commit invalidation helper from
FlashSim `5614962`. Its archive SHA-256 is
`9be9f2d5b281eaa828895db2264994c257b473ed4d806afaa7f0c8baafac5d71`.
Older timing numbers in the graphics roadmap describe different models and
must not be combined with this baseline.

## Qualification and comparison

Use real shader traffic: the fixed-function render mode of
`scripts/bench_arti_model.py` deliberately rejects a vertex/fragment-core
model. Its clear and idle measurements remain useful secondary workloads.

FlashSim's `scripts/build_arti_trace.py` can capture the current QEMU model's
MMIO, IRQ, DMA, and clock observations. The standalone replayer verifies
results and every DMA write, including scanout traffic. Recorded clocks keep
the simulated work fixed despite wall-clock settle limits. This is a model
benchmark; it cannot establish a QEMU guest speedup.

Compare replay binaries with alternating runs:

```sh
python3 scripts/bench_arti_trace.py work.trace baseline/replay \
  --candidate candidate=candidate/replay --rounds 3 --output comparison.json
```

The comparison records trace/binary hashes, preserves each completed result,
and rejects differing tick or DMA counts. Run timed comparisons after compiler
jobs finish. A loaded host can change even the short clear replay by several
times. Use the median and inspect the spread before accepting a small gain.

For later phases, add `--window-start N --window-calls 512
--window-repeats 7 --window-preheat 64`. The replayer verifies the prefix once
per binary, then forks children from the same warmed model state. Each child
verifies and measures an identical window after its preheat. This reduces the
cost of repeated samples, particularly for texture phases late in the trace.
The comparison rejects zero-tick windows and checks the requested start and
length, as well as matching tick/DMA counts. Full-trace replay remains required.

Accepted candidates also require a fresh native GLES/GTK proof:

```sh
QEMU=/absolute/path/candidate-qemu \
GTK_DEBIAN_BASE="$PWD/../arti-work/debian-gtk-arm64.qcow2" \
GTK_PROOF_SMOKE=1 GTK_PROOF_KMS_MODE=64x64 \
GTK_PROOF_WESTON_REPRO=medium GTK_PROOF_SKIP_BUILD=1 \
GTK_PROOF_SKIP_COVERAGE=1 GTK_PROOF_SCANOUT_PERIODIC=1 \
GTK_PROOF_SMOKE_TIMEOUT_S=900 PROOF_WORK=/absolute/path/proof \
./scripts/run_gtk_coverage_guest.sh
```

The native GLES triangle has a 160x120 bounding box in a 640x480 mode; the following
Weston/GTK proof uses a 64x64 mode. Report these dimensions with the results.
Require native GLES pixel assertions, GTK idle/active readback, and the
periodic scanout gate. A framebuffer captured after the GLES program exits
can show the restored console and is not a valid GLES reference image.

## Earlier profile and function layout (2026-10-15)

The earlier simulator-only model uses FlashSim `b608384`, a fresh representative profile for
every object, and function ordering from that profile. The RTL remains the
same 640x480 design. DUT shards compile at `-O2`, and the API wrapper at `-O1`.
The profile includes successful compute, native GLES, GTK and scanout runs;
its SHA-256 is
`7bd7f3cdc88aa62bf649c5499551ede59b0f08baf98f5918c3eb628182f46722`.

Two alternating rounds of seven warmed samples per binary give:

| Replay workload | Original | Refreshed and ordered | Time reduction |
|---|---:|---:|---:|
| Native shader, 168,582 ticks | 1.232103 s | 0.732583 s | 40.54% |
| Texture, 255,934 ticks | 1.863885 s | 1.121337 s | 39.84% |

All samples execute equal tick and DMA work. Each has the same verified
prefix and 64-call preheat. The native window starts at call 30,308 of
`gtk.trace` and measures 512 calls; texture starts at 7,400 of
`gtk-hvf1024-settled.trace` and measures 256. The final model also passes the
complete 317,616-call, 42,307,012-tick trace with every DMA write checked,
zero mismatches and no truncated tail. Its actual QEMU executable separately
passes compute rounding, native GLES pixels, GTK idle/active pixels, and
periodic scanout.

Refreshing only the changed functions has similar performance, within about
0.5% of the complete refresh in the ordered comparisons. The complete build
is retained to use consistent profile data. Function ordering improves both
workloads by 2.0–2.8% over the refreshed build in the same comparison.

`scripts/arti_profile_order.py` ranks executed functions by recorded call
count and keeps only uniquely defined symbols in the target binary.
`scripts/link_arti_model.py --order-file FILE` applies the Darwin linker
order without changing the configured QEMU build. Replay and normal QEMU
have different wrapper symbols, so their order files are generated separately
and both hashes are retained. Keep these model timings separate from actual
guest draw timings.

The final executable's SHA-256 is
`8d2c4bc6d1f2d7de160a552b0370edae7318a228a66b7003daa022ea20006e5b`;
the model archive is
`33911b84ddc886f84bef7063e381192b983c0e8d8aaee8a4429d4ecc3889cc8c`.
Raw comparison and qualification evidence is under
`.artifacts/full-system-speed/quiet-refreshed-{shader,texture}.json`,
`qualified-final.json`, and `fq-proof/`.

Further hotspot trials used the same two workloads and 14 samples per
candidate. Invalidation prefetch distances of 4, 8 and 16 regress both
workloads by about 2–4%. Loading four IDs as one packed word gains less than
0.5%, and `-O3` on the hot tick/memory code trades a roughly 0.3% texture gain
for a 1.0% shader loss relative to the same refreshed build. None is retained.
`-mcpu=native` resolves to the compiler's existing `apple-m1` target here.
Evidence is in `quiet-hotspot-{shader,texture}.json`.

The final quiet guest comparison uses three fresh guests per executable in
the order baseline/candidate, candidate/baseline, baseline/candidate. HVF/host,
1024-tick IRQ chunks and both 8ms budgets are fixed. Framebuffer capture is
disabled; every run passes native GLES pixel assertions.

| Executable | Three native draw samples | Median |
|---|---|---:|
| Original baseline | 56.078 / 56.499 / 55.570 s | 56.078 s |
| Final candidate | 34.502 / 34.814 / 34.627 s | 34.627 s |

This is a **38.25% draw-time reduction**, or about 1.62 times the throughput,
for the native triangle with a 160x120 bounding box in a 640x480 mode. Boot and build time are
excluded. The result is separate from the replay measurements above.
`native-final/result.json` retains each guest result and the executable
hashes. A portable record of samples, hashes, fixed work, qualification,
and rejected candidates is checked in as
[`performance/2026-10-15.json`](performance/2026-10-15.json).

The local final installation includes complete generated C++ sources, the
archive, profile, both order files, and device compile commands under
`../arti-work/debian-640x480-flashsim-qualified/`. Its `rebuild.sh` recreates
a separate `qemu-rebuilt` executable using the recorded profile and ordering.
The normal launcher uses the installed executable directly. A plain Ninja
link does not apply the optional function order; use the saved recipe to
reproduce the qualified settings. The original rollback files are retained.

The investigation has reached diminishing returns on these workloads:
subsequent measured hot-loop changes do not provide a useful consistent gain,
and remaining samples are spread across many RTL evaluation functions. This
is a measured stopping point, not a proof that every possible simulator or
RTL architecture has been optimized.

## Baseline graphics lane and cycle audit (2026-10-15)

Both desktop models have 16-lane, eight-warp general compute CUs,
but its graphics shader CU has **four lanes and two warps**, giving an
eight-fragment batch. Commit `41229a7` raised the default configuration;
`308e135` subsequently added the explicit `graphicsGpuConfig` override in
`EmitGpuHostSystemAxi.scala` to preserve the desktop shader ABI. The installed
generated model confirms four texture-coordinate lane registers. Desktop
shaders still use VL=4, fixed array offsets and 320-byte fragment banks;
removing the override alone is not a valid graphics-width migration.

A diagnostic wrapper samples the fragment FSM before every tick while replaying
the existing `gtk-hvf1024-settled.trace`. DMA verification remains enabled:
94,095 calls and 34,402,900 ticks complete without a mismatch or truncated tail.
These ticks cover the recorded guest workload, not one desktop frame.
Only batches observed from staging entry through emit exit contribute below.

| Completed textured batches | Processed lanes | Staging writes, cycles/lane | Shader, cycles/lane | Readback, cycles/lane | Total, cycles/lane |
|---|---:|---:|---:|---:|---:|
| 64x32 texture | 20,480 | 158.09 | 149.85 | 51.00 | 360.53 |
| 64x64 texture | 58,112 | 155.67 | 138.34 | 51.00 | 346.60 |

For the 64x64 group, staging writes/readback account for 59.6% of batch cycles,
shader execution for 39.9%, and launch/emit for the remainder. The stage writes
eight words and reads three words per processed lane. Transactions are issued
one at a time, as 64-byte line requests for individual four-byte values, over
the installed 64-bit AXI bus. Staging therefore has substantial transaction
and data-transfer overhead even before shader execution.

The previous quad rasterizer emitted **every quad in the bounding box**, including
quads with no covered samples. `io.quad.valid := active` does not reject empty
quads. The two background triangles consequently process roughly 614,400 lanes
for a 640x479 visible rectangle. At the measured 346.60 cycles per processed
lane this predicts about **213 million batch cycles**. Independently, the
984.23-second measured background time multiplied by the qualified texture
replay rate (228,240 ticks/s) gives about **225 million cycles**. Both are
estimates for the full desktop background; that startup run did not record
per-draw cycle counters. Texture extent, cache behavior, presentation, and
host integration limit the precision of this extrapolation.

For comparison, the previous uncoalesced staging alone has a bus-data lower
bound of `614400 * 11 * 8 = 54,067,200` cycles: eleven full-line transactions
per processed lane, eight data beats per line on the 64-bit AXI bus. This
excludes address/response, translation, shader and output costs; it is not
an achievable frame-cycle target. It explains why pixels divided by lane
count is not a useful render-cycle prediction for this implementation.

This baseline identified architectural optimization space beyond the simulator
changes. Rejecting wholly empty quads could remove roughly half the background
shader/staging lanes while retaining helpers in every nonempty quad. Coalescing
staging transactions and overlapping requests address the largest measured
phase. Graphics-width migration must update the shader VL and ABI together;
texture sampling also serializes active lanes, so lane count alone does not
predict speedup. These are candidates requiring cycle and pixel qualification,
not measured speedup claims. Exact phase counts and estimate assumptions are in
[`performance/fragment-cycles-2026-10-15.json`](performance/fragment-cycles-2026-10-15.json).

## Qualified tick and commit changes (2026-10-15)

FlashSim `b608384` uses the existing pass worklist to avoid re-demanding
already-current eager regions. On this top, 177 of 182 sequence demands
are already maintained by that worklist, and every late memory-enable demand
has already run earlier in the tick. The five remaining checks fit directly
in the tick path. Independently callable demand helpers keep their complete
bodies, preserving the header and unrelated object files.

The same change splits the 46,776-case commit switch into 94 helpers with
at most 512 cases each. These compile at `-O2`; small switches retain the
original path. Typed comparisons, ordered stores, wake masks, and invalidation
epochs remain intact. Tests cover chunk boundaries, 8/16/32/64/128-bit and
wide registers, duplicate invalidation IDs, wraparound, and invalid indices.
All 54 FlashSim tests pass.

Two alternating rounds, seven samples per round, gave these native shader
window medians. Each sample executes 168,582 ticks after the same verified
prefix and preheat:

| Model | Median | Time reduction from original |
|---|---:|---:|
| Original baseline | 1.235177 s | — |
| Memory/outline/unroll changes plus representative PGO | 0.833471 s | 32.52% |
| Above plus satisfied-demand elimination | 0.780629 s | 36.80% |
| Above plus bounded commit switches | 0.757853 s | 38.64% |
| Alternative table-driven commit | 0.752915 s | 39.04% |

The bounded-switch form keeps the original typed case operations and a small
94-entry dispatch table. The alternative has 46,776 metadata entries and
22,942 effect functions; its additional shader gain is about 0.65%, while the
two forms are within about 0.25% in the texture interaction comparison. The
bounded-switch form is the retained implementation.

Texture screening uses a different trace window, with 255,934 ticks per
sample. Separate matched comparisons measured 4.81% for the unprofiled
memory/outline/unroll changes, 31.90% with representative PGO, a further 5.97%
from demand elimination on top of PGO, and a further 3.90% from bounded
switches on top of that. These percentages come from separate batches and
must not be added.

The regenerated candidate has an identical DUT header. Only `dut_0.cpp`
and the replacement `dut_commit_chunks.cpp` change. Its complete recorded
workload passes: 317,616 calls, 42,307,012 ticks, 352,495,253 commits,
2,482,108,618 holds, 4,630,676 DMA reads, 7,983,437 DMA writes and 100,896,920
DMA bytes, with zero mismatches and no truncated tail. A fresh guest passes
compute rounding, native GLES pixels, GTK idle/active pixels, and periodic
scanout. Native GLES draws a triangle with a 160x120 bounding box in a 640x480 mode; GTK uses 64x64.

Evidence: `quiet-shader-combinations.json`, `quiet-texture-screening.json`,
`quiet-pgo-combinations.json`, `quiet-demand-commit-interaction.json`, and
`final-candidate/` under `.artifacts/full-system-speed/`.

The first profile was trained on the matching memory/outline/unroll source.
These intermediate comparisons reused it for unchanged code. The final
profile/layout comparison above uses a refreshed profile throughout. Guest
wall-clock comparisons remain a separate check from model replay timing.

The qualified candidate is installed locally as both
`qemu-build/qemu-system-aarch64` and `qemu-system-aarch64.flashsim`.
`../arti-work/debian-640x480-flashsim-qualified/install-manifest.json` records
the archive, executable and source commits. Its `rollback/` directory preserves
each original executable, staged archive/header and generated device source.
The Verilator executable is unchanged. The generated QEMU staging files now
match the qualified archive and idle-poll device, including the existing
display fixes; those earlier QEMU changes are not included in these commits.

For an actual guest comparison on Apple Silicon, use:

```sh
python3 scripts/bench_arti_guest.py /absolute/baseline-qemu /absolute/candidate-qemu \
  --base-image "$PWD/../arti-work/debian-gtk-arm64.qcow2" \
  --work "$PWD/.artifacts/native-ab" --rounds 3
```

This alternates fresh guests, fixes HVF/host and the 1024-tick/8ms IRQ policy,
disables framebuffer capture, and requires native pixel assertions in every
run. It records the single draw's elapsed time separately from guest boot and
build time. Full GTK/scanout qualification remains a separate requirement.

## Investigation inventory

The post-helper sample placed `_mem_prepare`, commit invalidation, `_commit`,
and tick dispatch at the top of active model costs. Sleeping QEMU threads and
BQL waits overlap model execution; summing blocked samples does not estimate
recoverable CPU time. Gallium present-copy time was negligible compared with
GPU fence time on this workload.

| Candidate | Evidence | Current status |
|---|---|---|
| Evaluate memory data/address dependencies per enabled port | The 384-port prepare helper checks 256 eager generations whenever any port writes | FlashSim `4f70542`; full recorded trace passes; combined guest training passes |
| Avoid initializing disabled-port data/address scratch | 668 scalar stores are emitted although store code reads them only for enabled ports | FlashSim `4f70542`; combined native shader window improves 2.85% |
| Profile-guided compilation of this exact model | Compute, native GLES, GTK, and periodic scanout training all pass | Full matched-source builds complete; 31.9% texture and 32.5% shader replay reduction before the latest changes |
| Outline enabled-port data helpers | Large prepare bodies can make the compiler prepare cold addresses in its prologue | 4.08% faster than original in shader windows; 1.20% incremental over four-way unrolling in a separate comparison |
| Four-way commit invalidation unrolling | Repeated small page-ID increments remain a hot helper | 4.25% faster than original with the memory changes; eight-way is slower and rejected |
| Reuse already-maintained demand regions | Training calls `_demand_mem` 28,822,501 times; all its generation checks hit | FlashSim `b608384` reuses the worklist and early demands; no new leaf-generation tracking is needed |
| QEMU guest CPU and IRQ policy | HVF guest polling leaves gaps with the small TCG IRQ budget | HVF 1024 ticks / 8ms passes compute, native, GTK and scanout; settled idle CPU about 4.15% |


Local raw evidence and candidate manifests are in
`.artifacts/full-system-speed/`. The previous accepted helper experiment is
in `.artifacts/flashsim-commit-bump/`; its three alternating 32 KiB clear
trials improved the median from 51.235 ms to 44.709 ms. The single final
native GLES pair improved draw time from 63.750 s to 60.000 s, so that guest
number has weaker statistical support than the clear result.

The screening trace contains 3,309,682 ticks, 365,676 DMA reads, and 858,136
DMA writes. Baseline times were 24.754/24.533/24.549 s; initialization elision
23.774/23.778/23.834 s; per-port demand 24.093/24.127/24.073 s. The two
changes together passed the complete trace: 317,616 calls, 42,307,012 ticks,
4,630,676 DMA reads, and 7,983,437 DMA writes, without mismatches or a truncated
tail. The emitter changes are committed as FlashSim `4f70542`; the matching-source instrumented model also passes compute, native GLES, GTK,
and scanout in a fresh guest. The regenerated normal build is also qualified as described above.

For native GLES timing alone, the guest script accepts
`GTK_PROOF_REPRO_ONLY=1` with `GTK_PROOF_WESTON_REPRO=medium`; it exits after
the native pixel assertions and records this narrower scope in run metadata.
The complete GTK/scanout proof above is still required for final qualification.

Regeneration also exposed hash-dependent SOP term ordering in five otherwise
unchanged shards. FlashSim `9a96442` canonicalizes the simplifier traversal
and literal-set ordering. Eight hash seeds now produce the same result, with
exhaustive truth-table checks for small and 65-term cones. This improves
compiler-cache reuse; it has no claimed simulation runtime gain.

## Guest CPU and progress budgets

`scripts/run_arti_debian.sh` and `scripts/run_arti_gpu.sh` automatically
select HVF with `-cpu host` on Apple Silicon when the FlashSim QEMU advertises
HVF and has the hypervisor entitlement. They use an IRQ chunk of 1024 ticks
capped at 8ms, with MMIO settling also capped at 8ms. Other hosts and Verilator
retain the TCG defaults. `QEMU_ACCEL=tcg`, `QEMU_CPU`, and explicit
`ARTI_MODEL_*` values take precedence. The lower-level Arti launchers
(`run_linux_test.sh`, `run_interactive.sh`) default to TCG unless `QEMU_ACCEL`
is set.
The proof script records the resolved CPU and budgets in run metadata.

With the same original model, a native 160x120 draw under HVF took 90.879s
with the 16-tick / 200us IRQ budget and 65.462s with 256 ticks / 2ms. These
are single-run diagnostic measurements, not repeated final benchmarks. The
1024-tick / 8ms policy passes compute rounding checks, native pixels, GTK
idle/active pixels, and periodic 64x64 scanout. Its small 32x24 native draw
passed in 4.158s in a separate run.

An initial 94.5% "idle" sample overlapped a final queued compositor render.
Trace command submission/completion records exposed this mistake. A second
probe waited for the command queue to drain and continuously verified that
no new GPU commands arrived during the ten-second sample: CPU usage was
4.15%, and the complete GTK/scanout proof passed afterward. Measure settled
idle this way before changing idle progress policy.

PGO training must exit normally to write its raw profile. Forked replay
windows use `_exit` and are unsuitable for training; use a complete guest or
ordinary full replay. Keep the training executable and generated source
hashes with the merged profile. `scripts/link_arti_model.py` links and signs
a separate QEMU using the configured Ninja link command, without changing
the default executable or staged model library.

Arti `8c3614e` backs off an IRQ timer to at most 8ms only when the model's tick
counter does not advance. MMIO rearms the 100us interval. A weak counter
fallback keeps the original policy for backends without this API. Runtime
checks with tiny model libraries observe 802 polls without counters versus
121 idle polls with counters over the same 1.2s interval. A guest using the
real GPU passes compute/GLES/GTK/scanout checks. Earlier diagnostic console-idle
samples were 3.90% and 2.13%, but differed in setup/capture conditions and do
not establish a paired CPU reduction.

An experimental RAM dirty-page renderer passes GPU scanout and a CPU test
that changes every pixel in place without a page flip. However, the matched
CPU framebuffer workload measures about 0.385% idle CPU with either renderer.
This does not establish a useful performance gain, so the renderer remains an
experiment rather than part of the installed candidate. Console samples taken
while compilers were active are excluded from performance conclusions.

Guest proofs now use private seed/modules ISOs. Arti `97902c6` generates
cloud-init data in temporary staging rather than rewriting tracked templates.
Native-only proofs disable the later GTK framebuffer matcher, which otherwise
kept scanning a restored console. These changes isolate concurrent tests and
remove capture overhead from native timing.
