# Protected sample-input submission

The complete sample host's GSS1 discovery now controls Linux native shader
publication. Device probing independently validates the 112-word sample
transport and caches `native_sample_transport`; a sample presence bit without
geometry transport fails initialization. The actual native ioctl copies that
kernel-owned authorization into the GNJ2 builder. User fragment version fields
alone cannot enable sample inputs.

The program snapshot and shader validator admit ordinary ABI6 and GS ABI7 only
through the explicit sample-aware entry. The original validation entry remains
closed to both versions. ABI7 additionally requires a real GS draw; ordinary
draws cannot borrow GS builtins. The GNJ1 graph remains unchanged. For sixteen
varyings and four MRTs, the 128-thread input prefixes contain 75/77 arrays and
the complete color/depth/control prefixes contain 93/95 arrays. Input builtins,
varyings and staging tails remain read-only; only the actual outputs are writable.
The full ABI7 prefix is 48,640 bytes. Executable bytes are still copied unchanged
into a private RX segment, with entry-suffix bounds and original resource leases.

CPU validation passes with ASan/UBSan: input/output/tail slices across bounded
validator capacities 1/16/128/255 and varying/MRT layouts; default-closed version
and geometry gates; the actual opaque owner and MMU mappings; a full 128-thread,
sixteen-varying/four-MRT mixed ordinary/GS graph; and actual Linux ioctl-to-builder
authorization followed by scheduling and retirement. The ioctl tests explicitly
set a modeled discovery cache, and model the final hardware kick. They do not
execute a shader or invent a GPU completion result. The real discovered sample
factory remains two CUs with 128 threads per CU.

```sh
python3 -B scripts/test_native_program_snapshot.py --sanitize
python3 -B scripts/test_native_job_snapshot.py --sanitize
python3 -B scripts/test_native_layered_snapshot.py --sanitize
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_layered_submit.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_submit.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_sample_transport.py
python3 -B scripts/test_driver.py
```

A fresh complete ARM64 kernel module build also passes with GCC 15.2.0 and
Linux 7.2, `-fstack-usage -Werror=frame-larger-than=2048`. The largest reported
total stack usage is 2,144 bytes in the shared shader validator; the compiler's
frame-size check passes. Reproduce from a committed source checkout in a fresh
temporary directory inside `.artifacts/`, then remove the ended build output:

```sh
mkdir -p .artifacts
mkdir .artifacts/sample-driver-build
git archive HEAD driver | tar -x -C .artifacts/sample-driver-build
/opt/homebrew/bin/gmake -C /Users/duckdonald/workspace/arti-work/arti-linux-build \
  M="$PWD/.artifacts/sample-driver-build/driver" ARCH=arm64 \
  CROSS_COMPILE=/opt/homebrew/bin/aarch64-linux-gnu- \
  KCFLAGS='-fstack-usage -Werror=frame-larger-than=2048' -j2 gpu_drv.ko
```

Fresh complete Scala caller compilation remains unverified: both the all-main
and dependency-batched attempts exhausted a bounded 384 MiB heap. Their partial
class outputs were deleted. Actual sample-host RTL execution, original ESSL
builtin lowering, API minimum/static-use/coverage state, dynamic interpolation,
the complete six VGA frames, matching physical timing and 60 FPS remain required.
