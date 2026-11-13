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
read-only guard oracle. The two observation checks and all 34 existing compiled
product CPU checks pass locally. Reproduce with:

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
