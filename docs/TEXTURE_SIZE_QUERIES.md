# Native texture-size queries

Status: [six initial decoder/native endpoint tests](performance/mobile-native-texture-size-initial-2026-10-21.json)
pass on804 frozen inputs, along with four paired LLVM tests and the C driver
validator. Full regression, shared-CU and synthesis qualification are in progress
on `codex/mobile-native-texture-size`. The current main qualification does
not cover this additional owner.

`opengpu.tex.size.c` uses custom0/function6; `opengpu.vtex.size.c` uses
custom1/function6 with normal RVV masks. Components0/1/2 return width, height,
and array-layer count or volume depth. Component2 returns zero for2D/cube;
components3..7 are illegal. Scalar sources are unit and signed shader LOD;
vector sources supply each active lane's unit and LOD. Results are32-bit
integers. No captured parameter pair is required or changed.

Shader LOD is relative to the image base level. The selected level is computed
with a33-bit sum before validation or narrowing. Negative and out-of-range LODs
fault without a wrapped level-table access. NPOT dimensions shrink to at least
one; array layers do not shrink. Cube dimensions describe a face. This follows
[GLES3.0 section2.12.9.3](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf)
and [GLSL ES3.00 section8.7](https://registry.khronos.org/OpenGL/specs/es/3.0/GLSL_ES_Specification_3.00.pdf).

The owner validates GXR1/GXB1 stage, identity and unit, then admits the selected
GXI1/GXL1 image level. It reads neither sampler descriptors nor texel data;
filter/wrap/comparison state and operand tuples cannot affect the dimensions.
The root must describe a valid image/binding table. Image format/type and target
must agree with the selected binding. A parent pins descriptor publication and
VM until the held scalar/vector completion is accepted.

Root/binding metadata uses logical ID0 and image admission uses IDs4..7. The
existing native two-credit adapter retains the external memory-port width.
Cancellation drains accepted reads before retirement; any active-lane failure
suppresses the entire vector destination, while zero masks issue no reads.
Inactive lanes retain oldVd. A trusted task reset may clear operand tuples
without invalidating the ordinary register-source semantics of a size query.

The driver admits defined ordinary sources and a valid vector length, without
a complete-pair proof. Its current profile continues to require unmasked vector
instructions. The paired LLVM change is `faeaa170d` on
`codex/opengpu-texture-size`, cherry-picked as `50f2c22e6` on sibling `opengpu`.
It adds all three scalar/vector component forms, masked MC cases, extension
checks and an ordinary scalar C inline-assembly helper. Existing encodings and
descriptor layouts are preserved.

Pending gates include full native owner regression, actual shared-CU/AXI query
programs, fresh whole-endpoint synthesis, compiler/API lowering and the remaining
H0-H8 requirements. PPA remains synthesis only.
