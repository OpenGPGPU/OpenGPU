# Native image line gathering

`TypedTileImageTransfer` gathers disjoint stored or resolved chunks into one
64-byte masked write. A line change, overlap, operation change or required
external read flushes the buffer. Offered requests remain immutable, only
successful physical acknowledgements commit byte/pixel counts, and cancellation
discards only unissued buffered data. The existing owner, transaction ID,
single-outstanding policy and supported formats remain unchanged.

Fourteen component checks pass, including all 36 color formats, packed and
unaligned views, 1x/2x/4x samples, allocation guards, exclusive 4 GiB ends,
permissions, aliases, faults and cancellation. A 16×16 RGBA8 stored-and-resolved
case issues 32 complete lines with exactly 2,048 enabled bytes and intact guards.

The complete development host also passes one 32×32 case with both sample
modes, four full 128-thread CUs, scalar FPU enabled, default production caches
and both pinned SRAM models. These measurements isolate line gathering against
the `e0357ff2` execution/resolve baseline; the workload and numeric checks are
unchanged.

| Measurement | 1x baseline → gathered | 4x baseline → gathered |
|---|---:|---:|
| Occupied AXI write bytes | 563,200 → 94,592 | 1,190,336 → 165,760 |
| Physical write bursts/replies | 8,800 → 1,478 | 18,599 → 2,590 |
| Enabled write bytes | 50,848 → 50,848 | 121,504 → 121,504 |
| Render-service clocks | 142,302 → 100,384 | 300,300 → 208,291 |
| Publication + submission + service/inspection clocks | 142,531 → 100,613 | 300,529 → 208,520 |

Occupied bytes count accepted full-width bus beats; enabled bytes count write
strobes. Neither measures SRAM capacity or runtime occupancy. Reads are unchanged,
and every accepted transaction drains. The one-million-cycle guard remains.

```sh
sbt -J-Xmx4G -Dopengpu.productQualification=0 -Dopengpu.simJobs=2 \
  'testOnly opengpu.graphics.TypedTileImageTransferSpec'
```

Use the [explicit full SRAM smoke commands](EXPLICIT_NATIVE_PRODUCT_FRAMES.md)
for the local-only enclosing replay. Routine images stay at or below 640×480.
These results establish neither a whole-host clock nor 720p frame rates; the
mapped timing/resource gates and complete product workload suite remain required.
