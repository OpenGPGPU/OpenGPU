# Original GND1 vertex draw reader

`TessellationVertexDrawReader` captures all 32 cache lines of the original
2048-byte GND1 and returns only its `VertexShaderDraw`. The enclosing controller
supplies a captured [GVS1 root](TESSELLATION_VERTEX_SOURCE_READER.md), the exact
512-byte source view and the accepted owner's private bounds. The reader uses
`NativeBinnedGraphicsLayout.drawFields` and
`NativeTypedGraphicsLayout.check/receive`; it has no second hand-written field
map, GND2 alternate, whole-frame reader or FS preflight.

The original count, first index/vertex, signed base vertex, base instance,
instance count, all 16 attributes/bindings, per-binding divisor/stride and
indexed view remain unchanged. The GPU range sequencer supplies original
draw-relative slices separately. Full-width slice/count products and instance
endpoints must fit those original counts. Identity, VS ABI and the exact code
PC must match GVS; its GND/RX references remain inside the accepted private
scope. This does not read instructions or establish RX permissions.

All reserved/high bits and narrow signed encodings use the original canonical
GND decoder. Header capacities remain 16 attributes, 16 bindings, 16 varyings,
four colors and four engine views. The reader requires 16 lanes/eight warps
and validates the complete physical 128-item VS1/2 scratch prefix, even for
zero patch work. Scratch cannot overlap the GVS view, GND, RX view or enabled
GUR1 root. Used input/index alias and descriptor checks use the existing VS
and input validators. The enclosing CPU BUILD still owns full physical
64-byte alias exclusion, unrelated frame/TCS/TES roles and source immutability.

All 64 linked maps are checked against the actual varying slots/mask; point
size requires VS ABI2 and inactive maps must be zero. VS uniforms remain
GUR1 with a private root. Legacy sampler fields, texture resources, output
reuse, indirect metadata and restart must be zero in this source mode.
Indirect/texture production paths remain complete targets, not completed
features. The preparer separately implements the selected query-false PATCHES
restart policy; this reader does not rewrite any input metadata.

One tag-zero read is outstanding at a time. Offered requests and accepted
responses survive cancellation; a real late memory fault takes precedence.
Error results expose a zero draw. Captured success/error completions remain
stable under backpressure, and the reader can be reused without reset. The
native owner must retain all records/resources through complete execution and
translation retirement.

## Staged source validation

This P39 delivery captures original GND1 records; later PRs connect memory,
source ownership and actual shader execution. All 768 production Scala source
files freshly compiled with Scala 2.13.18/Chisel 7.15.0 in 93.312 seconds,
without an existing GPU class provider. The exact delivery's three test/helper
sources then freshly compiled against that output in 3.911 seconds.

All three actual reader RTL cases passed in 62.658 seconds. Literal
little-endian fixtures check 32 reads, full original indexed/instanced/array
inputs, both VS ABIs, point size and 33-bit GND/RX ends at 2^32. Rejections
cover malformed encodings, scope/PC/ABI/count/mask failures, legacy sampler
fields, complete 128-item scratch capacity, immutable metadata aliases,
invalid request extents before reads, and missing scratch with zero work.
Held cancellation, last-line late faults, zero error payloads and healthy
no-reset reuse also pass. Assertions were enabled and model/object caches
disabled. Source/tool/HEAD/index guards passed before and after each command.

Compilation stayed within separate 600-second/6144-MiB sampled aggregate
bounds (observed 3388352/366656 KiB). The RTL run stayed within its
600-second/1536-MiB bound (observed 615552 KiB). Sampled RSS is not an absolute
peak. All 153 affected-suite selector checks and the integration/large-PR
catalog checks passed. No shader or complete host executed in these tests.

Earlier incremental source-copy controls separately rejected a 64-item scratch
prefix and a removed metadata-alias guard. Those historical checks do not
replace the exact delivery's fresh compilation and RTL results above.

## Reproduction and remaining connection

With the repository JDK/Scala/Chisel and Verilator toolchain:

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=1 compile
sbt -J-Xmx768m -J-XX:ActiveProcessorCount=1 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simCache=off -Dopengpu.simCcache=off \
  -Dopengpu.productQualification=0 \
  'testOnly opengpu.graphics.TessellationVertexDrawReaderSpec'
python3 -B scripts/test_changed_test_suites.py
python3 -B scripts/test_test_selection.py
git diff --check
```

The outer protected BUILD, production GNT7/8/9/GVS/GND reader-to-parent wiring,
original VS execution, range sequencing, acknowledged patch stores/seal,
existing-memory-client routing and complete TCS/TES retirement remain pending
as an integrated path. No discovery bit, available getter, ioctl or launch
route is enabled by this reader. All three domains, the full two-CU product
profile and VGA/timing/physical qualifications remain unqualified. Keep Scala
regression disabled; no broad workflow was enabled or dispatched.
