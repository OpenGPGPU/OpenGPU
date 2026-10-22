# Uniform word execution and retirement

`ShaderUniformExecuteUnit` connects a checked uniform load to the existing
scalar/vector request and writeback packet types. It snapshots the accepted
resource root, identity, private-prefix interval, operands, destination, PC,
warp and masks. Scalar results advance PC by four. Vector results preserve
`oldVd` in inactive lanes. Raw bits, including floating-point NaN encodings,
are returned without conversion.

Active lanes with identical full-width `(block, byteOffset)` operands share
one `ShaderUniformLoadUnit` transaction. A full-width broadcast therefore
uses three 64-byte reads, rather than three reads per lane. Different words
remain separate checked transactions, even within one cache line; no
descriptor or data cache persists across instructions. An empty execution
mask performs no reads. The parent must pin the immutable descriptors,
read-only data and VM context through retirement.

Scalar/vector arbitration changes preference only on acceptance. An offered
child request is accepted before cancellation can revoke work; every offered
memory request and reply drains before retirement. A fault in any active lane
suppresses the entire destination write, including earlier successful lane
results. The first observed fault survives later cancellation. Held commits
retain their bits and status through downstream backpressure and cancellation.

The reserved scalar encoding is custom-0 (`0x0b`), funct7=7, funct3=0; the
reserved vector encoding is custom-1 (`0x2b`), funct6=7, funct3=0, with ordinary
RVV `vm`. `rs1/vs1` selects logical block 0–11 or default block 31;
`rs2/vs2` supplies the full 32-bit byte offset. `rd/vd` receives one raw word.
Generic decoders keep these encodings disabled. The [native binding
path](NATIVE_UNIFORM_BINDINGS.md) enables them only for an accepted task with
uniform-provider permission. The adapter alone does not advertise support.

Seven execution checks pass: both stages at compact four-lane/two-warp and
default sixteen-lane/eight-warp shape, both stages' fault/cancellation recovery,
and simultaneous scalar/vector arbitration. They cover all twelve block
mappings, the final word of a 16 KiB block, the vertex/fragment default limits,
operand/root/private-prefix snapshots, broadcast/divergent read counts,
inactive invalid operands, all-inactive masks, whole-write suppression,
metadata/data faults, all three held physical read phases and healthy reuse.
Six decoder profiles also verify that the reserved encodings fail closed.

Both complete default adapters, including their checked loaders, pass 1000 ps
TC/SLVT synthesis with 25% I/O, standard ABC, no retiming/false paths, zero
skipped operators and no SRAM:

| Stage | Core / I/O slack (ps) | Cells | Synthesis library area |
| --- | ---: | ---: | ---: |
| Vertex | +347.645 / +281.706 | 19,080 | 2,401.65 |
| Fragment | +345.810 / +281.706 | 19,340 | 2,406.80 |

```sh
sbt 'testOnly opengpu.graphics.ShaderUniformExecuteUnitSpec opengpu.core.frontend.decode.TextureExecutionAvailabilityDecodeSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-uniform-execute-vertex .artifacts/uniform-execute-vertex-rtl' \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-uniform-execute-fragment .artifacts/uniform-execute-fragment-rtl'
```

Qualify top `ShaderUniformExecuteUnit` with `scripts/qualify_ppa.py --stage
synthesis --period-ps 1000 --io-delay 0.25`. These component gates do not
qualify an enclosing native stage or CU clock. The native binding path records
its separate actual-CU scope. Discovery, driver/compiler lowering, translated
publication/lifetime and resource/rate qualification remain required. H0/H6 and
the full H0–H8 objective remain open.
