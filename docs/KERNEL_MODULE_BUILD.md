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
