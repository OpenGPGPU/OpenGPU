# Native completion snapshots in the driver

`driver/opengpu_native_completion.h` copies and validates the twelve host-order
GNX1 words belonging to one held native opcode-7 completion. It preserves the
generated GNC/GNX wire layouts (32/48 bytes) and works in kernel and userspace
code. Its internal result contains the copied GNX words and 64-bit append-read
telemetry; it is not a new ioctl or device capability.

The caller verifies the native GNC profile and pending native submission; opcode
7 also selects legacy ABIs and alone does not guarantee a GNX window. It holds the software completion-slot lock,
reads the unified status/byte count, and calls
`opengpu_native_completion_capture` before issuing POP. The helper performs no
writes. It rejects invalid arguments or a short aperture without probing MMIO,
and stops after one read if the completion magic is absent. All twelve words
are copied before return; a subsequent POP cannot erase the saved snapshot.

The return value reports record validity. A valid hardware error or cancellation
returns true so the driver can retain its diagnostics and fail the fence.
Success requires the exact submitted context/pass/generation, zero nested
statuses and no fault stages/valid bits. An early GNJ fetch fault can have no
decoded identity. Recorded address and reader draw remain raw owner fields,
including on success; use the stages and explicit validity bits before treating
them as fault data. Reserved bits, invalid conditional fields, mismatched status
and malformed records are rejected without modifying the caller's output.

The unified native byte count is accepted GND read traffic, not image bytes or
a DMA expected transfer size. `NativeBinnedGraphicsJobReader` adds 64 bytes per
accepted draw-line request, including faulting requests. GNP1 scans the bounded
RV32 draw array once and execution reads it once more. A stride of at least
2048 bytes limits each pass to 2^32 bytes; the conservative aggregate bound is
2^33 bytes. The helper accepts zero, requires a 64-byte multiple, and rejects
values above that bound. Other unified opcodes retain their existing exact-byte
or query-result checks; native completion integration must select this path
explicitly.

Kernel/user layout probes, independent held-window success/error cases, all
identity-bit perturbations, reserved fields, conditional validity and telemetry
boundaries pass. These CPU tests execute no GPU work and do not enable the
protected native submission capability.

```sh
OPENGPU_TEST_SANITIZERS=1 python3 scripts/test_native_completion.py
clang --target=i386-unknown-none -std=c11 -D__KERNEL__ -nostdinc \
  -fsyntax-only driver/tests/opengpu_native_binned_kernel_abi_test.c
clang --target=x86_64-unknown-none -std=c11 -D__KERNEL__ -nostdinc \
  -fsyntax-only driver/tests/opengpu_native_binned_kernel_abi_test.c
```
