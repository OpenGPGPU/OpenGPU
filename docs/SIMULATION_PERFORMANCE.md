# Full-system simulation performance investigation

The current baseline is the non-tile-binned 640x480 FlashSim model with vertex
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
benchmark; it cannot establish a QEMU/TCG speedup.

Compare replay binaries with alternating runs:

```sh
python3 scripts/bench_arti_trace.py work.trace baseline/replay \
  --candidate candidate=candidate/replay --rounds 3 --output comparison.json
```

The comparison records trace/binary hashes, preserves each completed result,
and rejects differing tick or DMA counts. Run timed comparisons after compiler
jobs finish. A loaded host can change even the short clear replay by several
times. Use the median and inspect the spread before accepting a small gain.

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

## Remaining candidates under investigation

The post-helper sample placed `_mem_prepare`, commit invalidation, `_commit`,
and tick dispatch at the top of active model costs. Sleeping QEMU threads and
BQL waits overlap model execution; summing blocked samples does not estimate
recoverable CPU time. Gallium present-copy time was negligible compared with
GPU fence time on this workload.

| Candidate | Evidence | Current status |
|---|---|---|
| Evaluate memory data/address dependencies per enabled port | The 384-port prepare helper checks 256 eager generations whenever any port writes | Correctness test passes; generated-model comparison pending |
| Avoid initializing disabled-port data/address scratch | 668 scalar stores are emitted although store code reads them only for enabled ports | Isolated candidate compiling |
| Profile-guided compilation of this exact model | Earlier matching-model PGO helped a different desktop workload; an old profile does not qualify this model | Separate instrumented build in progress |
| Remaining commit/invalidation and dispatch costs | Still prominent after moving the invalidation loop into an optimized translation unit | Re-profile after the memory candidates |
| QEMU/TCG and BQL policy | Guest threads block while model execution holds the BQL | Requires full-system measurements after model improvements |

Local raw evidence and candidate manifests are in
`.artifacts/full-system-speed/`. The previous accepted helper experiment is
in `.artifacts/flashsim-commit-bump/`; its three alternating 32 KiB clear
trials improved the median from 51.235 ms to 44.709 ms. The single final
native GLES pair improved draw time from 63.750 s to 60.000 s, so that guest
number has weaker statistical support than the clear result.
