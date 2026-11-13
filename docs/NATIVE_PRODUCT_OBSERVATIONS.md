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
