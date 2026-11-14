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
ARM64 kernel module also builds against the configured ARTI Linux 7.2 tree
with GCC 15.2.0: all eighteen production objects, modpost and the final
`gpu_drv.ko` link pass. The combined native/shared-atomic validator retains all
four software branch scopes, eight physical SIMT entries and 255-entry native
capacity. Packed state flags and a separate kernel range-transfer helper keep
the complete module build free of frame-size warnings with
`KCFLAGS=-Werror=frame-larger-than=2048`. The largest analyzer's GCC static stack
usage, including saved registers, is 2144 bytes. The kernel source and configured
build directories are read-only dependencies; module outputs belong in a
temporary GPU-local directory. These checks do not demonstrate GPU execution.

Reproduce the component checks with:

```sh
OPENGPU_TEST_SANITIZERS=1 python3 scripts/test_private_snapshot.py
OPENGPU_TEST_SANITIZERS=1 python3 scripts/test_native_submit.py
python3 scripts/test_native_job_snapshot.py --sanitize
OPENGPU_TEST_SANITIZERS=1 python3 scripts/test_native_mmio_capture.py
python3 scripts/test_driver.py
```

Set `TMPDIR` to a temporary directory inside the GPU repository. For a complete
kernel build, archive the committed driver sources into a temporary module
directory inside the repository. Use GNU make and the same GCC 15.2.0 cross
compiler as the configured kernel:

```sh
/opt/homebrew/bin/gmake \
  -C /Users/duckdonald/workspace/arti-work/arti-linux-build \
  M=<GPU-local-archived-driver-directory> ARCH=arm64 \
  CROSS_COMPILE=/opt/homebrew/bin/aarch64-linux-gnu- \
  KCFLAGS='-fstack-usage -Werror=frame-larger-than=2048' -j2 gpu_drv.ko
```

Remove the temporary module directory after the command finishes.

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

## Combined mainline source validation

The combined source retains the shared-atomic/SIMT proof bounds and kernel
range-transfer helper together with the explicit geometry/layered/sample
discovery cache and ABI6/7 owner gates. The driver entry keeps managed temporary
directories and its geometry, layered, sample and compute suites, and also runs
the mainline framebuffer input suite. The shared-proof bound test runs before
native admission, preserving the mainline allocation-test initialization order.

Eight core C suites pass ASan/UBSan, covering shared atomics, shader admission,
SIMT, native/geometry/sample discovery, native layout and MMU. The native
program/job, geometry graph, layered graph/submission, sample bridge and MMIO
component suites also pass with sanitizers; the sample bridge has seven cases.
Its depth-policy negative now matches the mandatory canonical recipe's exact
GFS word29 rejection. Framebuffer host guards (eleven tests), executor guards
(thirteen) and original-ESSL entry guards (eight) pass. This merge validation
runs no Mesa compiler, model, JVM or DUT and does not repeat the kernel build.
The full driver suite remains a separate integration check.

Reproduce the affected component checks in a managed GPU-local `TMPDIR`:

```sh
python3 -B scripts/test_native_program_snapshot.py --sanitize
python3 -B scripts/test_native_job_snapshot.py --sanitize
python3 -B scripts/test_geometry_snapshot.py --sanitize
python3 -B scripts/test_native_layered_snapshot.py --sanitize
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_layered_submit.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_sample_submit_bridge.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_mmio_capture.py
python3 -B scripts/test_framebuffer_blit_host.py
python3 -B scripts/test_prepare_owner_executor.py
python3 -B scripts/test_qualify_native_sample_owner.py
```

For each core name `shared_atomic_validator`, `shader_validator`,
`compute_simt_validator`, `native_sources_discovery`,
`native_geometry_transport_discovery`, `native_sample_transport`,
`native_layout` and `mmu`, compile `driver/tests/opengpu_<name>_test.c` with
`-std=c11 -O1 -Wall -Wextra -Werror -fsanitize=address,undefined
-fno-omit-frame-pointer`, then run it with `ASAN_OPTIONS=detect_leaks=0`.
Use `-Idriver/tests/mmu_stubs` for MMU,
`-Idriver/tests/compute_submit_stubs` for native layout and `-Idriver` for the
other suites. Put executables in a temporary `.artifacts/` directory and remove
them once the checks end.
