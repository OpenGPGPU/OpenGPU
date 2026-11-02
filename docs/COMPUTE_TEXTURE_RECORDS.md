# Compute texture resource and packet records

The checked compute encoders write GXR1 roots and GXB1 bindings with stage 2,
GXI1 image headers, GXL1 level layouts, GXQ1 coordinate packets for integer
fetches, and resolved GXS1 sampler records.
Compute decodes a two-bit stage; the existing generated graphics ABI describes
its one-bit vertex/fragment stage and is unchanged. The remaining word offsets,
magic and sizes are shared. Output consists of host words that the caller must
serialize little-endian.

The encoder checks the root's count, table alignment, exclusive 33-bit limits,
record strides and binding-table extent, plus the binding's unit, expected type
and target, the packet's face field, sampler filter/wrap and LOD encoding, and
the image header's target, representable dimensions, swizzle and first-level
table extent. Level records additionally check block size, pitch relationships
and the full allocation span using wide arithmetic. Image limits can vary by RTL configuration and are checked at
admission; representability here does not promise a particular device limit.
Sampler infinities and reversed LOD clamps are accepted; NaNs are rejected.
Coordinates remain full-width for
subsequent image/target checks. A size-only root may explicitly omit its sampler table; fetch and
sample callers must provide one. `size_only` is an encoder option, not an
encoded permission: the caller must restrict operations accordingly. Failed
encoding leaves the destination unchanged. It does not
validate image or sampler handles against those tables, check image/sampler
compatibility, prove image/level consistency or mip completeness, pin resources,
publish commands, or admit a native driver job.
The caller must retain records, data and VM through completion. Full H7 resource
and driver integration remain open.

```sh
python3 scripts/test_driver.py
python3 scripts/test_compute_texture_level.py
MAKE=gmake ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- \
  scripts/test_compute_texture_records_kernel.sh "$KDIR"
```

The small C checks cover stage 2, exact root/binding/image/level/packet/sampler words from the existing
binding-31 CU fixture, the graphics word-offset contract, exclusive 4 GiB
limits, malformed tables and untouched output on failure. The AArch64
kernel probe builds the encoder in a configured kernel tree; it does not execute
on hardware. `KDIR` names a prepared kernel build tree.
The deterministic level check compares 5,000 bounded numeric layouts against
an independent wide-integer model; it does not render a frame.
