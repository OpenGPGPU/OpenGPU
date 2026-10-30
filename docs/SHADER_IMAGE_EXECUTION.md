# Vector shader image execution

`ShaderImageExecuteUnit` consumes captured vector instructions and uses the
[GPU image descriptor access path](SHADER_IMAGE_DESCRIPTORS.md) for typed loads
and stores. Operand tuples and load snapshots reside on chip, independently
for each warp and lane. No intermediate operand or result packet is written to
GPU memory.

This component is not yet routed by the CU decoder or a task launcher. The
following encodings describe its input contract; they are not an advertised
system capability. CU admission, compiler lowering, task descriptor publication,
VM/coherence ownership, barriers and full hardware qualification remain H7 work.

## Instruction contract

All operations use CUSTOM-1 (`0x2b`), with the function in bits 31:26. The caller
supplies the execution mask after active-lane, vector-length and predicate
filtering. Stores additionally intersect a trusted invocation side-effect mask,
excluding helper lanes. Loads and operand capture may execute for helpers.

| Function | Operation | Operands and result |
|---|---|---|
| 8 | Set parameter pair | Bits 14:12 select pair 0–3; `vd=0`. Capture `vs1` and `vs2` for participating lanes. A successful instruction acknowledges through scalar x0 without modifying v0. |
| 9 | Load image texel | Bits 14:12 are zero. `vs1=x`, `vs2=y`; return component R in `vd` and retain the complete RGBA result privately. |
| 10 | Store image texel | Bits 14:12 and `vd` are zero. `vs1=x`, `vs2=y`; write the captured RGBA tuple, then acknowledge through x0 after memory drain. |
| 11 | Read loaded component | Bits 14:12 select G/B/A as 1/2/3; both source-register fields are zero. Write the selected private component to `vd` without accessing memory. |

| Parameter pair | First word | Second word |
|---|---|---|
| 0 | Full 32-bit image-unit index | Control word |
| 1 | Full 32-bit z coordinate | Reserved, must be zero |
| 2 | Raw R word | Raw G word |
| 3 | Raw B word | Raw A word |

Control bits 2:0 contain the shader target, bits 4:3 the component type,
bit 5 indicates a format qualifier, and bits 13:6 contain the image format ID.
Bits 31:14 must be zero; the complete eight-bit format field is checked before
narrowing to the internal format width. Coordinates retain their signed
32-bit bit patterns through the checked address path.

Pair 0 starts a new tuple for its participating lanes and invalidates their
previous load snapshots. Loads require pairs 0 and 1; stores require all four
pairs. Every participating tuple is checked before the first access is offered.
Excluded store lanes require no tuple and issue no descriptor or texel request.
Inactive destination lanes retain their previous vector-register values.

One load instruction obtains all four components per participating lane.
Component reads observe that snapshot even if backing memory subsequently
changes. A failed or canceled load invalidates its participating snapshots and
suppresses the entire vector writeback. Results from other lanes and warps
remain independent. Subsequent component reads require a valid result for every
participating lane.

## Task ownership and retirement

The parent supplies a trusted `TextureImageLevelTable` and permission flag.
The table is captured at instruction admission. Every task launch must assert
`clearParameters` while the unit is idle, including launches that reuse an
identity; this clears tuples, snapshots and descriptor-cache validity. Descriptor
rebinding or VM identity reuse requires descriptor invalidation. These inputs
do not replace the parent's publication, VM or coherence lease.

An offered child request remains stable until accepted. Cancellation then drains
the child and any memory acknowledgement before producing one failed instruction
result. Wrong reply IDs cannot retire an owner; an actual descriptor or data
fault takes priority over cancellation. A completed, backpressured commit remains
immutable. No later lane begins after a fault or cancellation.

Stores are acknowledged operations, not rollback transactions: a fault on a
later lane does not undo earlier acknowledged stores. The parent must retain
memory ownership through failed retirement as well as successful retirement.

The focused validation command is:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 \
  'testOnly opengpu.graphics.ShaderImageExecuteUnitSpec'
```

The fixture uses four lanes and two warps, real descriptor and typed-memory
accesses, four image units, split-line texels and no rendered frame. Its five
cases cover masked snapshots and warp isolation, store byte guards and helper
suppression, invalid tuples and task reset, held offers/acknowledgements and
fault priority, and 120 cancellation cuts. All five cases pass with no failed,
skipped or pending tests. This validates the instruction adapter and its memory
path; CU instruction execution and system integration remain separate gates.
