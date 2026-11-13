# Protected native submission

The native submission producer rebuilds GNJ/GNF/GND/GFS metadata in an opaque
kernel owner. The source UAPI supplies typed original-GEM views, rather than
arbitrary address patch locations. Program bytes become private RX mappings;
metadata and copied uniforms become private RO/NX mappings. Vertex inputs and
frame resources retain their actual original-GEM references and appropriate
RO/RW NX mappings. CPU access to ordinary input/image data retains the usual
producer synchronization contract; copying metadata does not make those
original resources immutable.

The producer captures user metadata and input sync objects once, waits for
producers before copying code/uniforms, validates the complete source graph,
and publishes only after private mappings and physical leases are established.
It preserves sixteen attributes/bindings/varyings, four color outputs, all
twenty-four UBO slots, two default blocks and the original wire draw/state
capacity. Exclusive allocation ends retain their 33rd bit.

Physical alias validation covers the complete graph before private append or
copy. It rejects vertex scratch overlapping any input/control view, all vertex
views overlapping frontend bin storage, and vertex scratch overlapping image
or resolve storage. It preserves read sharing, ordered vertex-scratch reuse,
and the supported transition from vertex input/scratch to later fragment
storage. Different GEM handles do not hide identical physical cache lines.

Two scheduler dependency chains distinguish execution from ownership:

- Native successors wait for all older execution/DRM-finished fences. An older
  completed native owner may remain mapped while a successor executes.
- Ordinary successors wait for full retirement, after all older owners and
  original references have been released. Native preparation first waits for
  every earlier ordinary job to retire, before creating GPU-visible leases.

Each chain includes its predecessor and its own fence. Early cancellation
cannot remove an older dependency. Retirement and fault progress do not acquire
the enqueue-only admission mutex or wait while holding the hardware submit
lock. Revocation failures retain the owner and original backing and wedge the
device; they do not signal successful retirement.

The host C suites exercise the actual producer, helpers, scheduler, opaque
owner and MMU with modeled OS primitives. They cover full graph links and
permissions, simultaneous old/new private copies, GEM close while jobs retain
references, cross-entity ordinary/native ordering, cancellation, bounded
timeouts, fault recovery and allocation/copy/lookup failures. The complete
ARM64 kernel module also builds against the configured ARTI Linux tree with
the cross compiler. These checks do not demonstrate GPU shader execution.

Reproduce the component checks with:

```sh
OPENGPU_TEST_SANITIZERS=1 python3 scripts/test_private_snapshot.py
OPENGPU_TEST_SANITIZERS=1 python3 scripts/test_native_submit.py
python3 scripts/test_native_job_snapshot.py --sanitize
OPENGPU_TEST_SANITIZERS=1 python3 scripts/test_native_mmio_capture.py
python3 scripts/test_driver.py
```

Set `TMPDIR` to a temporary directory inside the GPU repository. For a complete
kernel build, copy the driver sources into a temporary module directory inside
the repository and run the configured kernel's `make ... M=<directory>
ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- modules`; remove that module directory
afterwards.

The MMIO component also extracts the actual submit, activation, invalidation,
IRQ and GNX drain functions. Its control peer verifies two held results, actual
POP/ack traces and stable record identities while both owners remain mapped.
Its exported memory and traces can be checked with the compiled executor
parsers using `--executor-root <checkout>`; this parser check executes no GPU.

After all image/source oracles authorize cleanup, `native_kernel_begin_cleanup`
starts a distinct phase. The actual MMU's DMA barriers capture exact coherent
PTE clears, including their original physical addresses and old words. Every
real TLB flush blocks for a matching, ordered live-DUT acknowledgment before its
call can return and release backing. Transport errors enter the existing MMU
rollback/owner quarantine path. `native_kernel_end_cleanup` also destroys the
context and its ASID inside this acknowledgment window, then disables both
SATP registers and performs a final full flush before releasing the global
page tables. VM/ASID shootdown failures preserve the context, bindings and
page tables; a final-flush failure preserves the global tables. Only successful
context teardown publishes the final batch count; the producer must still await the executor's final cleanup result
before destroying the session. CPU ASan/UBSan checks pass for two owners, exact
exported-PTE replay, stale/busy acknowledgments, final context/global flush
failures retaining the relevant tables, and session destruction issuing no
further MMIO. This
CPU peer is excluded from the actual Mesa producer.

The driver feature bit and production native layout/capability queries remain
disabled while end-to-end qualification is unfinished. The separate test-only
profile cache consumes an actual DUT probe, including all 96 discovery words
and five auxiliary registers; it does not substitute a fixed CPU profile for
runtime discovery. Active execution receipts must preserve both owners through
the real held-completion, driver IRQ/drain/POP and subsequent DUT checks.
Owner cleanup remains unqualified until every shootdown is applied and checked
on the same live DUT. This work does not close GLES/KHR,
ordinary native geometry-shader admission, or the VGA/60-FPS product gate.
