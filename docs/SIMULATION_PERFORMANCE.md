# Full-system simulation performance investigation

The reference baseline is the non-tile-binned 640x480 FlashSim model with vertex
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

The native GLES rectangle is 160x120 in a 640x480 mode; the following
Weston/GTK proof uses a 64x64 mode. Report these dimensions with the results.
Require native GLES pixel assertions, GTK idle/active readback, and the
periodic scanout gate. A framebuffer captured after the GLES program exits
can show the restored console and is not a valid GLES reference image.

## Final profile and function layout (2026-10-15)

The final model uses FlashSim `b608384`, a fresh representative profile for
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
for the 160x120 native rectangle in a 640x480 mode. Boot and build time are
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
scanout. Native GLES is a 160x120 rectangle in a 640x480 mode; GTK uses 64x64.

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

`scripts/run_arti_debian.sh` automatically selects HVF with `-cpu host` on
Apple Silicon when the FlashSim QEMU advertises HVF and has the hypervisor
entitlement. It uses an IRQ chunk of 1024 ticks capped at 8ms, with MMIO
settling also capped at 8ms. Other hosts and Verilator retain the TCG defaults.
`QEMU_ACCEL=tcg`, `QEMU_CPU`, and explicit `ARTI_MODEL_*` values take precedence.
The lower-level Arti launcher defaults to TCG unless configured explicitly.
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
