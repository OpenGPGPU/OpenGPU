# Kernel module build checks

The platform removal callback uses the kernel's version-appropriate callback
signature. The atomic commit tail accepts the object type provided by the
kernel headers; the display sequencing remains shared.

The full out-of-tree driver module builds against Ubuntu 26.04 arm64 Linux
7.0.0-42 headers and the repository's configured arm64 Linux 7.2.0 tree.
Reproduce with a configured kernel build tree and matching cross-toolchain:

```sh
make -C /path/to/linux-source O=/path/to/linux-build \
  M="$PWD/driver" ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- modules
```

For a distribution header package, omit `O`, `ARCH`, and `CROSS_COMPILE` when
building natively, and use the header directory as the `-C` path. The platform
registration object also compiles against Ubuntu 24.04 Linux 6.8.0-147 headers.
The complete 6.8 module is not qualified: it still requires DRM scheduler and
vblank API adaptation. Linux 5.15 also fails on workqueue API differences and is unqualified.
These are compilation checks; they do not establish runtime behavior or product
qualification.

The hardware fence waiter honors its interruptibility setting, returns the
fence status when a wait completes, and counts emulator progress time against
the same overall deadline as its sliced waits. Reproduce the deterministic
host-mock coverage with `python3 scripts/test_fence_wait.py`. The mock checks
signal, completion, error and deadline cases; it is not a Linux scheduler or
hardware timing test. A 64×64 dma-buf ordering and GPU-read smoke test also
passes with the sliced waiter in the cached ARTI/QEMU guest described below.

Linear and strided blits reject overlapping DMA bounding ranges and known
shared GEM backings before private GPU virtual mappings can hide an alias.
`python3 scripts/test_blit_aliases.py` exercises the actual ioctl validation
prefixes with small GEM/DMA mocks; the earlier linear path accepts the
overlapping-range control. Adjacent independent ranges remain valid. Distinct
foreign dma-bufs with separate DMA addresses may still share physical pages;
their provenance is not established by these checks.

The verification-only render fence delay is applied after unified hardware
completion, including error completions. The completion and abort paths retain
one fence reference until delay expiry or cancellation; normal submissions do
not request a delay. The small pthread fixture extracts the actual completion,
abort and drain code and checks success, errors, cancellation, concurrent
drains, immediate completion and draining a previous timeout before a new
submission: `python3 scripts/test_unified_completion_race.py`. It mocks workqueues
and MMIO, so it does not establish Linux IRQ timing or a live KMS wait. The
full driver also builds against the configured arm64 Linux 7.2.0 tree with the
command above.

For a small live KMS implicit-sync check, build the guest test for the same
frame size as the device model (64×64 in this example), then run it with
`OPENGPU_TEST_SYNC_ONLY=1` on a guest with the OpenGPU DRM device:

```sh
aarch64-linux-gnu-gcc -static -O2 -Wall -Wextra -Werror \
  -I/path/to/linux-headers/include -Idriver -DTEST_WIDTH=64 -DTEST_HEIGHT=64 \
  -o opengpu_drm_test driver/tests/opengpu_drm_test.c
OPENGPU_TEST_SYNC_ONLY=1 ./opengpu_drm_test
```

The mode queues two delayed renders, checks that the scanout fence is pending
before the atomic modeset, checks the modeset wait, render fence statuses and
both images, and exits before the wider DRM suite. It requests the test extent
and selects a matching custom mode when the preferred mode differs. The mode
rejects sizes over 640×480.

The 64×64 test passes on an ARM64 Linux 7.2 guest using the cached ARTI/QEMU
model and the driver built with a 4 ms emulator fence slice. Both images and
fence statuses pass, and the driver's framebuffer preparation reports the
scanout render fence. That cached model has one four-lane executor and boots
at 640×480; its full RTL provenance and current product configuration are not
qualified by this run. The driver's display path also waits for GPU idle, so
the timing check alone does not isolate the GEM implicit-fence wait.

On the same small guest build, `OPENGPU_TEST_INVALIDATE_ONLY=1
./opengpu_drm_test` checks dma-buf CPU-write completion against an independent
pending delayed render. It checks that CPU access ends successfully, that the
render has completed when the ioctl returns, and that the rendered image and
fence status are correct. A first GPU copy primes a 64-byte source line, and a
second copy checks that all 16 CPU-written words are visible after the write.
A cached ARTI/QEMU run passes at 64×64. An isolated negative-control module
using the earlier nonblocking dma-buf invalidation hook fails the ordering test
with `EBUSY`. A second control disables that hook and removes the ordering
assertion so the visibility check can run; the GPU copy then returns stale data.
Both controls retain the rest of the driver. This is one timing-dependent
contention and one cache-line visibility case, not proof of all interleavings
or product configurations.

`OPENGPU_TEST_BIND_ONLY=1 ./opengpu_drm_test` tests a second context binding a
64-byte texture view at offset 60 while a delayed render is pending. The guest
primes two GPU cache lines, writes the view on the CPU, binds it, then checks
the render and a 128-byte GPU copy including both unchanged neighboring words.
This passes at 64×64 on the same cached ARTI/QEMU model. An isolated control
using the previous nonblocking binding invalidate returns `EBUSY`; another
control deliberately invalidating only the first line returns stale data at
the first word of the second line. This covers one timing-dependent cross-
context case and one unaligned range, not the full resource or product matrix.

`OPENGPU_TEST_SIGNAL_ONLY=1 ./opengpu_drm_test` repeats the dma-buf case while
a child sends a handled signal during the CPU-write completion ioctl. The
64×64 cached ARTI/QEMU guest passes, including render and GPU readback. An
isolated control with the earlier fence waiter fails that ioctl with `EINTR`.
This is one timing-dependent signal case; it does not cover every waiter or
signal interleaving.

An existing ARTI/QEMU model and configured ARM64 kernel can run these cases
without regenerating RTL or running the full guest suite:

```sh
scripts/run_arti_small_smoke.sh kms
scripts/run_arti_small_smoke.sh dmabuf
scripts/run_arti_small_smoke.sh bind
scripts/run_arti_small_smoke.sh signal
```

Set `ARTI_DIR`, `ARTI_WORK`, `QEMU`, `LINUX_SOURCE`, `LINUX_BUILD` or
`LINUX_HEADERS` when their locations differ from the sibling-checkout defaults.
The runner uses 64×64 by default, permits dimensions from 16 to 64, builds an
isolated module and initramfs, and keeps its logs under `.artifacts/`. It does
not establish the provenance or capabilities of the supplied emulator model.
All four selectors pass at 64×64 against the cached model described above.
