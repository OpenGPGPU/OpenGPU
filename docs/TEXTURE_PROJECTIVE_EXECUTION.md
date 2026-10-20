# Projective texture execution on shared CUs

Status: [fourteen checks pass](performance/mobile-native-projective-qualified-2026-10-21.json)
on805 frozen inputs (`186e270`):eight scalar-backend tests,four real-CU projective
cases and two existing128-invocation payload cases. The projective cases submit
160 draws across both shader stages and local/spilled payload modes. The scalar
FPU is explicitly enabled for these programs;the legacy16-lane8-warp payload
cases retain their default scalar-FPU-disabled profile and original positional
constructor arguments. [Main integration](performance/mobile-native-size-projective-main-2026-10-21.json)
now combines these checks with qualified size queries and the existing integer
mip-page probes;23 fresh composed sampling checks pass on812 exact inputs.
This adds two shader-stage tests and a
shared program/oracle fixture; production RTL, driver, ABI and emission inputs
match the size-query source exactly. No new instruction or sampler hardware is
required to project coordinates before captured sampling.

The programs use existing scalar `fdiv.s` or vector `vfdiv.vv` operations, then
write the resulting FP32 bits to the ordinary eight operand pairs.2D sampling
captures S/Q and T/Q;3D also captures R/Q. Shadow sampling captures the divided
comparison reference. Unused2D Z is ignored. Explicit gradients are supplied in
the projected coordinate system and are not divided a second time. These rules
follow [GLSL ES3.00 section8.7](https://registry.khronos.org/OpenGL/specs/es/3.0/GLSL_ES_Specification_3.00.pdf).

Each stage/payload case submits40 draws: scalar/vector projection;2D,3D and
2D-shadow targets; automatic, explicit-lambda and explicit-gradient modes; two
dyadic coordinate steps; signed post-projection offset; a texel fault; cancel;
and healthy reuse. Fragment inputs use Q values2/4/8/16 with one covered BR lane
and three uncovered helpers. The two coordinate steps separate minification
from magnification after projection. Vertex automatic mode retains its zero-
lambda policy. Outputs check the divided coordinate separately from the sampled
value, using an independent dyadic texel-weight oracle.

Shader code allocations are disjoint from private spill scratch. Operands use
registers and task-scoped parameter pairs; no GPU-memory parameter packet is
read. Both local and spilled payload modes exercise actual shared CUs and
ordinary fault/cancel drain. Planned coverage is not a passing test result.
The parent whole-endpoint synthesis results may carry only after production,
emission and artifact hashes are verified; no duplicate synthesis is started
for these test-only changes.

Pending work includes combining the current integer/query/AXI qualification and completing texture
banking, throughput, coherence and the remaining H0-H8 requirements. This does
not claim compiler/API projective lowering or complete GLES conformance.

The scalar program profile explicitly enables the optional scalar FPU backend.
The default harness profile leaves it disabled and routes scalar FP instructions
to an external port; an unserviced port stalls such a shader. The isolated
backend probe passes three in-place divisions and dependent integer bit moves.
The optional test-harness flag is appended after existing positional parameters
so the prior oversized-payload argument keeps its meaning. Production RTL and
ISA remain unchanged. The first run also retained a previous2D texel in volume
slice0;clearing each reused allocation fixes the fixture without relaxing the
independent oracle. Both retained failures precede this explicit profile.
