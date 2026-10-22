# Native uniform binding and execution

Vertex and fragment draw admission retain an optional `ShaderUniformResources`
reference to an immutable GUR1 root. Version zero has canonical address zero;
version one permits a 64-byte-aligned root at address zero or ending at 4 GiB.
The owner pins the root, binding table, read-only data and VM context through
retirement. The [uniform records](UNIFORM_BUFFER_RESOURCES.md) define twelve
logical blocks, twenty-four bindings, 16 KiB block bounds and default block 31.

| Native record | Uniform reference words (version, address) |
| --- | --- |
| GTD1 vertex draw | 612, 613 |
| GTD1 fragment draw | 626, 627 |
| GND1 vertex draw | 506, 507 |
| GFS1 fragment state | 118, 119 |

Every prior field offset, width, signedness and record size is retained across
the three generated layouts: 570 typed, 578 binned and 78 fragment-state fields.
Readers validate canonical padding and root references before publishing a
complete child descriptor. Held draw/state offers and cached GFS1 snapshots
retain the accepted root. Existing kernarg and private payload offsets remain.

`nativeUniformsAvailable` defaults false in generic decoder/core/CU constructors.
Task-specific sampler, native-resource and uniform-resource permission must all
allow an instruction. The router captures provider flags at client launch
acceptance; the CU captures them on its actual `kernel.fire` edge. Live pin
changes cannot alter an accepted task's permissions. Native vertex and fragment
adapters select their implemented uniform service. Legacy adapters retain
disabled uniform permission. Missing service produces a precise
illegal-instruction trap before a resource handoff.

Scalar custom-0 uses funct7=7 and funct3=0; vector custom-1 uses funct6=7,
funct3=0 and ordinary RVV `vm`. Full-width `rs1/vs1` selects the logical block;
`rs2/vs2` supplies its byte offset. Encoding constants are generated in
`opengpu_uniform_abi.h`. Execution uses the [checked retirement
adapter](UNIFORM_INSTRUCTION_EXECUTION.md), preserving inactive lanes,
coalescing equal operands and suppressing the entire destination on failure.

`NativeShaderTextureUnit` owns uniform execution exclusively through completion.
Its existing two physical line credits serve uniform, typed and floating
resource operations. The uniform loader retains one logical credit, widened
explicitly at the shared request mux and checked on reply. There is no extra
external memory slot or persistent uniform cache. Each distinct active-lane
`(block, offset)` pair transfers three uncached 64-byte lines; a sixteen-lane
broadcast transfers 192 bytes per warp.

The exact draw input/output prefix supplies the excluded private interval,
independent of local residence or spill. Uniform data and metadata cannot alias
that visible prefix. Default data may follow it immediately and share a padding
line. Existing resource-failure status 3 (fragment source 5) and cancellation
status 4 are retained. Unavailable instructions use the CU trap path. Offered
memory and completion packets retain ownership through backpressure and cancel.

The directed validation profile covers fourteen decode cases, seven queued
router cases, four compact/default actual-CU policy cases, three descriptor
reader cases, two native capacity cases, two fault/cancellation cases and one
fragment shared-line default case. Four
existing native texture compatibility cases cover both stages and payload modes.
The four task-policy and five uniform-stage cases explicitly use the production
native execution profile; all nine pass with zero ignored or pending tests.
The default stage cases execute 128 threads across all eight warps, read the
last word of every one of twelve 16 KiB blocks, and read the first/last default
words at vertex offset 4092 and fragment offset 3580. Exact exports and preserved
default tails are checked. A 544-byte fragment prefix also shares its final
cache line with default data while private control stores spill; first and last
default words remain exact. Vertex local payload uses a 15,872-byte prefix; a
69,120-byte full interface spills past its 64 KiB capacity. Fragment execution
retains a 45,056-byte prefix and repeats under controlled pool pressure to spill.
These native cases do not read every component; complete-word loader checks
retain their separate scope.

```sh
sbt -Dopengpu.simJobs=4 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.frontend.decode.UniformExecutionAvailabilityDecodeSpec opengpu.core.frontend.decode.TextureExecutionAvailabilityDecodeSpec opengpu.graphics.SharedShaderTaskRouterSpec' \
  'testOnly opengpu.core.GpuComputeUnitTexturePolicySpec' \
  'testOnly opengpu.graphics.NativeTypedGraphicsJobReaderSpec opengpu.graphics.NativeBinnedGraphicsJobReaderSpec opengpu.graphics.TypedFragmentStateReaderSpec -- -z "uniform"' \
  'testOnly opengpu.graphics.VertexShaderStageSpec -- -z "native uniform"' \
  'testOnly opengpu.graphics.FragmentShaderStageSpec -- -z "native uniform"'
```

Emit `mobile-uniform-resource-decode`, `mobile-native-resource-router`,
`mobile-native-uniform-vertex` and `mobile-native-uniform-fragment`. Qualify the
complete `DecodePipe`, `SharedShaderTaskRouter` or `NativeShaderTextureUnit`
with `scripts/qualify_ppa.py --stage synthesis --period-ps 1000 --io-delay 0.25`.
All four complete components pass 1000 ps TC/SLVT synthesis, including I/O,
with standard ABC, no retiming/false paths, zero skipped operators and no SRAM:

| Component | Core / I/O slack (ps) | Cells | Synthesis library area |
| --- | ---: | ---: | ---: |
| Uniform-capable decode | +464.989 / +467.983 | 1,554 | 157.799 |
| Shared task router | +410.592 / +274.034 | 15,172 | 1,321.23 |
| Vertex native provider | +21.146 / +84.044 | 1,067,700 | 148,283 |
| Fragment native provider | +55.205 / +85.180 | 1,087,053 | 149,569 |

The complete native providers include their existing texture services as well
as uniforms. These totals are not the incremental uniform cost. Component
results do not qualify an enclosing stage/CU/host clock.

Runtime discovery and driver/compiler admission/lowering remain required before
advertising uniform support. [Compact translated host checks](NATIVE_UNIFORM_HOST_OWNERSHIP.md)
cover both stages sharing one table, denied data pages and held-completion VM
ownership. GPU-producer publication/lifetime, concurrent full-size stage
use of one binding table, grouped texture-host composition, rate budgets and
enclosing clock/product qualification remain open. This binding/execution
component does not close H0/H6 or the full H0–H8 goal.
