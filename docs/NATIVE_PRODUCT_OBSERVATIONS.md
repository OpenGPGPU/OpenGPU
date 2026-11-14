# Native product execution observations

The six-scene C++ product driver validates every GNX1 word against a successful
result and the context/pass/generation captured from the original GNJ1 input
before execution. It records all twelve observed words. Exactly one native
submit, successful command completion and observed retirement are required per
scene. Retirement checks both the cleared completion slot and IRQ, with no
external request left offered. These are command counts; GNX1 does not expose
completed draw or primitive counts. `drawCompletionCountAvailable=false` records
that limit rather than supplying an inferred count.

Cycle scope version 2 keeps the historical fields: `serviceCycles` counts AXI
service steps after the submit MMIO write returns until IRQ, while `gpuCycles`
is sampled after the thirteen completion/GNX reads. The new `frameCycles` counts
actual driver clock edges from the start of reset through discovery, submission,
execution, completion checks, pop and verified retirement. GPU-counter snapshots
at service start and IRQ identify the narrower execution interval. Simulation
wall time measures host simulator speed, not the GPU clock. None of these fields
establishes a qualified frequency or FPS; that requires matching full-host
physical timing and complete original-frame execution.

CPU validation checks the original identity layout, all 384 one-bit GNX changes,
wrong completion metadata, malformed launch/check ordering, failed retirement,
new external traffic and the driver's reset/control clock accounting. The driver
also passes syntax checking against a CPU interface stub. These checks execute
no GPU frame and do not replace the independent stored-image/sample/resolve and
read-only guard oracle. Reproduce with:

```sh
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_product_observation.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_qualify_native_product_compiled.py GuardTests.test_native_host_step_preserves_settled_axi_snapshot
```

All test binaries and temporary files are created inside this GPU checkout and
removed when each test ends.

## Complete owner profile

The 96-word product path first reads actual ID/capabilities and rejects missing
prerequisites before probing dependent resource windows. It then compares every
GCR/GNC/command/protected-source discovery word, the native capability pointer,
live quota and shader configuration. Actual values of the five additional live
registers are retained, and no prior completion, IRQ or external request may be
present before submission. The product and captured-owner executors share the
same shader-resource validator. The Python product gate rejects missing or
contradictory preflight observations, partial GNX words, inferred draw counts and
inconsistent service/frame intervals.

The six original 640×480 recipes use `NativeProductShaderPrograms` executable
native VS/FS inputs. Their stored images, samples, resolves and read-only guards
still require the independent Scala oracle. Actual linked Mesa shader/owner
execution remains a separate 4×4 gate; the product record identifies its shader
input source explicitly. CPU checks and a source-bound model build qualify
neither rendered frames nor physical clock/FPS. Changed driver inputs require
their own guarded build; an old driver qualification cannot be relabelled.

Additional CPU reproduction:

All 22 cases across the six related CPU scripts pass locally with ASan/UBSan
halt-on-error. This includes each of the 96 discovery words, prerequisite-first
rejection, all 256 bank encodings and existing native/compute owner, fixture and
executor-identity checks. The complete host script entry also passes for the
independent common observation change. No model or GPU job runs in these checks.

```sh
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_product_observation.py
python3 -B scripts/test_qualify_native_owner_product.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_owner.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_compute_owner.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_alignment_sequence.py
python3 -B scripts/test_prepare_owner_executor.py
```

## Reusing the exact prepared owner across worktrees

The owner product and ordered-sequence entries accept an already prepared
owner directory under the primary GPU repository's `.artifacts/`, including
another GPU worktree's active model. Before creating a lock or starting work,
they run the current original owner preparation check against every recorded
Scala/build/SRAM/helper input, exact discovery words, complete RTL and assertion
bytes. Different source, capacities or factory identity fail; an aliased lock
also fails. This permits driver-tool development around one original model
without duplicating or relabeling it. Every run retains the original output
lock; finish existing generation and executor work before starting these stages.

For the same source-qualified owner, after its original generation and previous
executor finish and release the original lock, run all six full frames serially:

```sh
for stage in prepare build run verify; do
  "$ORIGINAL_PYTHON" -B scripts/qualify_native_owner_product.py --product \
    --stage "$stage" --output "$OWNER" --flashsim-dir "$ORIGINAL_FLASHSIM" \
    --tile-log2 4 --jobs 2 --scene-jobs 1 --shards 32 \
    --shard-opt=-O0 --precompiled-header --conservative-alias || break
done
```

Use the generation-matched Python/CIRCT/FlashSim environment and build options
for every stage. The product CLI passes the selected shard optimization to the
original builder and exact build-identity guard; its default remains `-O2`.
The example selects `-O0` to reuse matching generated-model shards. A different
optimization requires its own guarded build and never relabels a previous
executor. All six independent 640×480 image/sample/resolve/guard checks
remain required. These entries neither qualify a different compute-stage or
geometry factory nor transfer a v1 clock/FPS result to that later profile.

Reproduce path, exact-source refusal, fixture and independent-verification
checks with `python3 -B scripts/test_qualify_native_owner_product.py` and
`python3 -B scripts/test_native_alignment_sequence.py` (twelve cases total).
