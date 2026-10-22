# Draw-scoped vertex output reuse

Native typed and binned profiles implement a 16-entry post-transform cache.
Reuse is explicitly enabled per draw; arbitrary shaders retain ordinary
execution. `VertexShaderStageConfig.postTransformEntries=0` removes the cache.
Other supported capacities are powers of two from 2 through 32.

The caller may enable reuse only when shader outputs depend solely on vertex
and instance inputs plus immutable draw resources. The shader must have no
externally visible side effects and must not depend on batch composition,
local lane, warp placement or invocation count. Hardware does not prove this
promise. Shader compilation and runtime resource ownership must establish it.

GTD1 word 610 and GND1 word 498 carry the one-bit `reuseOutputs` flag. Padding
must be zero. GVI1 word 2 bits 23:16 report the implemented entry count; zero
means unavailable. Readers reject the flag on disabled profiles. Existing
record sizes, field offsets, input versions and format masks are unchanged.

Each cache key contains the complete 32-bit vertex ID, instance ID and base
instance. Draw admission clears all tags. Entries preserve raw position,
varying and point-size bits. Stream ordinal, identity and restart tokens come
from the current input record. Hits flush older partial shader batches before
export, so assembly, restart and downstream feedback retain their ordering.
Only successfully retired shader outputs populate the cache. A new tag becomes
valid after its complete payload is stored; updating a resident key does not
consume a second entry.

Input acquisition still performs index/attribute bounds checks and memory
accesses before lookup. This cache avoids shader execution and payload staging;
it does not promise reduced input-fetch traffic. Mixed hits and misses can
flush partial batches and increase launch count; workload qualification must
measure that tradeoff. Cancellation retains an
already offered output, drains cache operations and shader leases, and delays
completion until ownership is released. Every new draw starts with empty tags.

The full-interface default uses eight 256x32 SRAM macros, or 8 KiB, within the
existing 32 KiB primitive/state allocation. Tags and registered control/payload
buffers are additional logic. This is major-array accounting, not a complete
whole-renderer area or power budget.

## Verification

The actual two-CU stage test compares 32 vertex exports and two restarts over
two instances. Opting in reduces four shader batches to two with identical
ordered output in local and global payload modes. Staging read/write bytes also decrease. It checks new-draw
invalidation, mixed hit/miss ordering, held-output cancellation, acquisition fault before a cached key
can be used, and healthy recovery. A full-width fill cancellation test rejects every unoffered younger output
and checks healthy reuse. Component tests exercise full-width payload
bits, distinct instance keys, duplicate-key updates, replacement, backpressure
and clear, including the default 16-entry configuration with real SRAM models.

Twenty-four directed checks pass across cache, configuration, discovery, stage,
reader and host tests. Three generated ABI checks and a C11 probe also pass.
Four native AXI cases cover typed and binned local/global payloads, opt-out
and opt-in draws, nine indices forming three identical triangles,
capacity discovery, Sv32 input translation, exact four-MRT/depth/resolve and
guard bytes, held-completion VM ownership, and released payloads. Four reader
cases check capacity rejection, Boolean padding and held descriptor snapshots.

The standalone cache passes synthesis setup at 1000 ps, TC/SLVT, 25% I/O delay,
without retiming or false paths: core +418.432 ps and I/O +446.121 ps, with zero
skipped instances and all eight SRAM macros present. This component result
does not qualify the vertex stage, host clock, frame rate or H5 completion.

The enclosing vertex-stage synthesis screen fails the same 1000 ps setup gate:
core −836.824 ps and I/O +74.348 ps. Its mapping report also omits four `umod`
operators, so it cannot establish a full-stage maximum frequency. Repairing
complete arithmetic mapping and the failing path remains required separately
from the passing cache screen.

The [input arithmetic pipelines](VERTEX_INPUT_PIPELINING.md) now qualify complete
standalone conversion, fetcher and floating-axis mapping/setup. Their component
results do not replace the enclosing stage's qualification gate.

Reproduce the directed stage case with:

```sh
sbt 'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "reuse pure indexed"' \
    'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "cache fill"'
sbt 'testOnly opengpu.graphics.NativeTypedGraphicsHostAxiSpec opengpu.graphics.NativeBinnedGraphicsHostAxiSpec -- -z "render repeated native indexed triangles"'
```

Emit the default physical cache and enclosing stage with
`EmitPpaRtl mobile-vertex-reuse <directory>` and
`EmitPpaRtl mobile-vertex-shader-reuse <directory>`. Use the existing
`scripts/qualify_ppa.py` synthesis flow for either emitted top. Raw reports stay
local under `.artifacts/`.

General shader/compiler eligibility, workload hit rates, native full-interface
stress and complete resource/timing/product qualification remain required.
The original H0–H8 requirements remain open.
