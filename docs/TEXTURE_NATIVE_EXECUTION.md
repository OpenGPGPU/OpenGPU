# Native captured-operand texture execution

Status: functional implementation on `codex/mobile-native-texture-execute`.
Twelve focused controls and four real shared-CU integration tests pass. Fresh
aggregate and whole-endpoint synthesis qualification are running on803 frozen
inputs; the [checkpoint](performance/mobile-native-texture-execute-checkpoint-2026-10-21.json)
records source hashes and active jobs. No full
GLES, throughput, whole-GPU clock or H0-H8 completion is claimed.

The native texture endpoint now shares its existing two physical line credits
between legacy sampling, typed integer packet fetch and resource-bound floating
sampling. The floating path uses `TextureSampleExecuteUnit` and the separately
qualified [shader binding owner](TEXTURE_SHADER_SAMPLING.md). Sampling operands
are captured in a task-scoped register bank; no new per-fragment GPU-memory
parameter packet, scratch prefix or payload SRAM allocation is required.

## Instructions and register dependencies

| Operation | Encoding | Operands |
| --- | --- | --- |
| `opengpu.tex.params.p` | custom0, funct7=3, funct3=p, rd=0 | rs1/rs2 write word pair p |
| `opengpu.vtex.params.p` | custom1, funct6=3, funct3=p, vd=0 | vs1/vs2 write word pair p; vm retained |
| `opengpu.tex.samplef.c` | custom0, funct7=4, funct3=c | rd receives component c; rs1=unit, rs2=control |
| `opengpu.vtex.samplef.c` | custom1, funct6=4, funct3=c | vd receives component c; vs1=units, vs2=controls; vm retained |

Pairs range0..7 and components0..3. Other parameter destinations and component
encodings are illegal. Existing barrier, legacy texture, typed-fetch, derivative
and vector configuration encodings remain unchanged. The compiler partner is
`../llvm-project` branch `opengpu`, commit `67545a6ed` (instruction definitions
start at `3281b4393`). Its48 MC cases check encoding, disassembly, vector masks
and extension gating; the legacy MC, clang RVV and scalar C inline-assembly
smokes also pass. These tests preserve the existing `e32,m1,tu,mu` shader ABI.

Control bits1:0 select automatic0, explicit1 or gradient2; mode3 is reserved.
Bit2 selects shadow comparison and all higher bits must be zero. Coordinates,
LOD and gradients are raw FP32 bits in ordinary scalar/vector operands; integer
and floating sample returns retain their original32-bit representation. Scalar
FP register values therefore use the existing bit-move instructions as needed.

## Operand bank and task lifetime

| Word | Value |
| --- | --- |
| 0..2 | Original X/Y/Z coordinates |
| 3 | FP32 array layer |
| 4 | FP32 comparison reference |
| 5 | Explicit lambda before sampler clamps |
| 6 | Fragment automatic shader bias |
| 7..9 | Original-coordinate X-screen gradient |
| 10..12 | Original-coordinate Y-screen gradient |
| 13 | Three signed four-bit texel offsets in bits11:0; higher bits zero |
| 14..15 | Reserved zero |

Pair0 begins a new tuple for its written lanes. All eight pairs must be valid
before a sample uses that lane; later pair writes may update a complete tuple.
A tuple may serve multiple component selections. Scalar parameter instructions
broadcast to the executing warp's active lanes. Vector instructions use their
ordinary active/VL/predicate execution mask. Automatic fragment sampling gathers
original coordinates from each TL/TR/BL/BR group independently of coverage;
all four helper tuples must be complete. Vertex automatic mode needs no helpers
and starts lambda at zero before sampler clamps.

Trusted kernel launch clears every validity bit, including successive batches
with identical graphics identities. Reset is accepted only when all texture
instructions and line credits are drained. Graphics task ownership isolates the
per-endpoint warp banks; the single-task shared-CU adapter owns at most one CU.
Shader execution and resource/VM publication stay pinned through retirement.

Storage is `16 * lanes * warps * 4` data bytes plus16 validity bits per warp/lane,
for each instantiated native endpoint. The default16-lane/8-warp profile uses8192 data
bytes plus256 valid bytes. The4-lane/2-warp shared-CU fixtures use512 data
bytes plus16 valid bytes. This implementation uses registers and muxes; it makes
no SRAM macro area or bandwidth assumption. Include every endpoint instance and
its mapped cell cost in later H0/H8 sizing; raster quads do not introduce a
second shader VLEN. `MemoryNativeTypedGraphicsPipeline` contains one vertex and
one fragment endpoint (16384 data bytes plus512 valid bytes at the default
configuration). `TypedVertexBinImagePipeline` contains one geometry endpoint
and one fragment endpoint per render engine, so two engines require24576 data
bytes plus768 valid bytes. These register costs are additional to the proposed
tile, payload, texture-cache and L2 SRAM budgets; they do not increase their
advertised capacities.

## Retirement and validation

Parameter writes have no memory transaction or vector destination. Scalar
writes acknowledge through x0. Vector writes use the existing scalar-x0
completion path, preserving v0 and its outstanding scoreboard reservation.
A decoded backend test keeps a divide to v0 pending, acknowledges a parameter
operation and verifies that a dependent read waits for the correct divide.

Normal samples use ordinary scalar/vector destination reservations. Inactive
vector lanes retain oldVd. Any active-lane failure suppresses the complete vector
destination; accepted root, image, texel and arithmetic work drains before error
retirement. Parameter/source state and held results remain immutable under
backpressure, cancellation and descriptor invalidation. Empty vector masks
produce no reads. Explicit sampling remains available for one-lane configurations;
unavailable implicit helper groups reject before memory access.

The driver tracks complete parameter pairs from program entry and intersects
validity at branch joins. Pair0 and vector-length reconfiguration reset this
static proof. The current driver profile admits unmasked parameter writes,
requires defined sources and a complete tuple before sampling, and retains the
existing memory bounds. Scalar legacy integer packets must be proven aligned
64-byte spans in the immutable kernarg uniform tail; private local payloads and
loaded/unknown pointers are rejected. Vector packet-pointer provenance remains
unadmitted by this profile. Dynamic integer texel-fetch lowering still needs an
on-chip path; the legacy packet interface is not H5 closure for that operation.

## Evidence and remaining work

The direct tests execute1024 component samples across both stages and64 formats,
check every gradient word, explicit lambda, shader bias, array layers, cube face
selection, volume-Z gradients, comparison references and signed offsets.
Additional tests cover one/eight/sixteen shader lanes, independent quad groups,
high mask bits, task reset, incomplete tuples, warp isolation, zero masks,
late-lane faults and24 cancellation cuts. Decoder checks cover1232 new and332
existing cases. All parameter operations assert zero memory traffic.

Real shared-CU vertex and fragment tests pass with local and spilled payloads,
all64 formats, scalar/vector parameter/sample instructions, v0 preservation,
automatic/gradient modes, stale-tuple rejection after new task launch, data
faults, cancellation and healthy reuse. They count544 normal format/mode draws
plus four final healthy draws and16 rejected/faulted/cancelled draws. The initial
extra volume filter fixture retained a prior slice; clearing fixture allocations
fixed the expectation without a production RTL change. The failed log is retained.

Full native AXI/frame qualification, DRM resource-root publication and capability
adoption, complete compiler/API texture lowering, integer fetch/offsets,
queries/projective forms, banked texture throughput/coherence and remaining
H0-H8 gates remain open. Generated descriptor layouts remain unchanged; the
new instruction extension is paired with the sibling LLVM and driver updates.
