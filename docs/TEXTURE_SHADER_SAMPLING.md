# Resource-bound shader sampling development

Status: the [800-input checkpoint](performance/mobile-shader-resource-sampling-checkpoint-2026-10-21.json)
passes 128 distinct tests across 21 suites and nine unchanged ABI checks.
It includes 299 successful resource-bound queries per shader stage, root address
zero and exact 4 GiB end, fourteen malformed root/binding cases and eighteen
cancellation cuts per stage. Eight child/standalone RTL profiles are byte-exact
to their positive parent screens. Both new stage-owner synthesis runs remain
pending; no timing pass is claimed for this owner. This is an internal resource/operand endpoint. CU instruction decoding,
scalar/vector operand transport and retirement, compiler/validator adoption and
native frame integration remain open. No software record, native opcode or
capability is changed or advertised here.

`ShaderTextureSampleUnit` captures a shader's resource root, graphics identity,
full-width binding unit, component, tag and floating sampling operands. It reads
the existing GXR1 root and selected GXB1 binding, validates both against the
constructor-selected shader stage and captured identity, and builds the
GXI1/GXS1 tables and handles for `TextureImageSampleUnit`. The shader cannot
supply independent image/sampler table bounds or override the binding's target
and expected return type. All existing GPU descriptor layouts remain unchanged.

The operand port carries original FP32 coordinates, separate array layer and
comparison reference, shadow selection, explicit lambda, raw gradients,
complete helper coordinates, bias and integer texel offsets. The enclosing CU
execution owner must supply and pin these values on chip. This endpoint adds no
GPU-memory parameter packet; the actual CU path must still be implemented and
verified before claiming the H5 common-path requirement.

## Stage policy

| Mode | Vertex | Fragment |
| --- | --- | --- |
| Automatic | Pre-clamp lambda +0; no helper requirement | Complete fine helper differences, then clamped shader bias |
| Explicit | Supplied lambda | Supplied lambda |
| Gradient | Original-coordinate explicit gradients | Original-coordinate explicit gradients |

Vertex automatic selection follows [GLES 3.0.6 section 2.12.9.4](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf):
the pre-bias lambda starts at zero. Sampler min/max LOD clamps still apply after
this stage policy.

Bias must have canonical zero bits outside fragment automatic mode. Invalid
mode, offset range or missing fragment helper mask fails before root reads.
Target-specific coordinate, offset and helper matching checks use the captured
binding and precede image/texel work as defined by the child sampler. Unused
lambda/gradient/helper operand storage is ignored by this port; only the
selected mode is forwarded to the child's canonical request fields.

Root reads cover both 64-byte lines. The full 32-bit unit is checked against a
binding count of 1 through 32 before computing the selected binding address.
Table alignment, bounds, record headers, padding, stage and identity checks are
the same immutable resource contract used by native integer fetch. Root and
binding data remain captured throughout sampling and held completion.

## Ownership and results

Logical memory ID15 belongs to root/binding reads. The composed sampler retains
IDs0 through4 and8 through11; an assertion rejects collisions with ID15.
Offered reads stay valid until accepted, accepted reads drain before completion,
and every child remains owned through cancellation. Metadata faults retain the
failing virtual line address. A prior fault survives later cancellation.

The result includes the selected swizzled component, return type, identity,
unit/tag and the complete sampling result for internal diagnostics. Native fault
source 1 denotes root, 2 binding, 3 parameters and 4 the image sampling pipeline;
the nested result retains the pipeline's internal fault source. Errors publish
no color. Held results remain immutable under input mutation, cancellation and
resource invalidation. Publication and translation leases remain the parent
execution owner's responsibility until result retirement.

The endpoint serializes one sample lifetime. It does not qualify banked texture
hit delivery, one bilinear result per cycle, CU/raster helper execution, general
shader instruction support, or full H0-H8 closure. Fresh aggregate and synthesis
qualification are required for this new owner.
