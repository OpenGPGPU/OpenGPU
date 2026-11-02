# Compute texture root and binding records

The checked compute encoder writes GXR1 roots and GXB1 bindings with stage 2.
Compute decodes a two-bit stage; the existing generated graphics ABI describes
its one-bit vertex/fragment stage and is unchanged. The remaining word offsets,
magic and sizes are shared. Output consists of host words that the caller must
serialize little-endian.

The encoder checks the root's count, table alignment, exclusive 33-bit limits,
record strides and binding-table extent, plus the binding's unit, expected type
and target. A size-only root may explicitly omit its sampler table; fetch and
sample callers must provide one. `size_only` is an encoder option, not an
encoded permission: the caller must restrict operations accordingly. Failed
encoding leaves the destination unchanged. It does not
validate image or sampler handles against those tables, construct image/level/
sampler records, pin resources, publish commands, or admit a native driver job.
The caller must retain records, data and VM through completion. Full H7 resource
and driver integration remain open.

```sh
python3 scripts/test_driver.py
MAKE=gmake ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- \
  scripts/test_compute_texture_records_kernel.sh "$KDIR"
```

The small C checks cover stage 2, exact root/binding words from the existing
binding-31 CU fixture, the graphics word-offset contract, exclusive 4 GiB
limits, malformed tables and untouched output on failure. The AArch64
kernel probe builds the encoder in a configured kernel tree; it does not execute
on hardware. `KDIR` names a prepared kernel build tree.
