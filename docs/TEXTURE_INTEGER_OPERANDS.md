# Captured integer texel fetch

Status: implemented on `codex/mobile-native-integer-execute`, under functional
qualification. Fresh composed regression and synthesis remain pending. This
extends the task-scoped operand bank described in
[Native texture execution](TEXTURE_NATIVE_EXECUTION.md); it adds no second bank
or GPU-memory coordinate packet.

`opengpu.tex.fetchi.c` uses custom0/function5 and
`opengpu.vtex.fetchi.c` uses custom1/function5 with normal RVV masking. Components
range0..3. The first source supplies the full32-bit texture unit, and the second
source must be zero. The destination is an ordinary scalar/vector register.
Function2 packet fetch, function3 parameter writes and function4 floating
sampling retain their encodings. Paired LLVM commit `1535f19fd` adds all eight
instruction records,12 MC encoding/disassembly/mask/extension cases and scalar
C inline-assembly emission. Its four targeted LLVM/Clang tests pass.

Integer fetch interprets the existing sixteen bank words as follows:

| Word | Integer interpretation |
| --- | --- |
| 0..2 | Signed32-bit X/Y/Z texel coordinates |
| 3 | Full32-bit array layer |
| 4 | Full32-bit cube face for the existing generic cube-face fetch extension |
| 5 | Full32-bit mip level |
| 6..12 | Reserved zero |
| 13 | Three signed four-bit offsets in bits11:0; higher bits zero |
| 14..15 | Reserved zero |

All eight pairs must be valid for a selected lane. Pair0 restarts the tuple;
trusted task launch clears validity, including when identities are reused.
Scalar parameter writes broadcast; vector writes obey active/VL/predicate
masks. Inactive sample lanes retain oldVd. An error in any active lane suppresses
the complete vector destination. Parameters and results stay owned through
backpressure, cancellation and accepted memory-response drain.

The checked resource owner receives captured coordinates directly, validates
GXR1/GXB1/GXI1/GXL1/GXS1 identities and bounds, and fetches decoded integer or
floating texel components without spatial/mip filtering. Signed offsets are
added with35-bit intermediates before coordinate bounds and narrowing. Array
layers, faces and mip levels retain32-bit checks. Only3D accepts a Z offset;
cube-face fetch rejects every nonzero offset. Face fetch is an existing generic
hardware extension, not a claim that GLES exposes `texelFetch` on cube samplers.
The existing image swizzle is applied once after decode.

Nonzero instruction controls, reserved tuple words and incomplete tuples reject
before descriptor reads. Target-dependent offset and coordinate failures reject
after descriptor admission and before texel reads. The enclosing owner retains
root publication and VM ownership through retirement and reuses its existing
two physical line credits.

The driver requires a complete static tuple on every path, defined scalar or
vector sources, texture permission and the current vector configuration. Branch
joins intersect tuple validity and VL changes invalidate that proof. Existing
masked-vector admission restrictions remain. No arbitrary packet pointer or
private payload allocation is admitted by this extension.

Tests cover64 formats, four components, scalar/masked vector results, four
resource targets, signed offset boundaries, negative coordinates corrected by
an offset, full-width rejected levels/faces, task/warp isolation, late-lane
faults, zero masks, held completion, cancellation and healthy reuse. These checks
are under execution; full shared-CU/AXI qualification, new whole-endpoint PPA,
queries, texture banking/coherence and the remaining H0-H8 work are still open.
