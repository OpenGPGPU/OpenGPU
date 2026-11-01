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
