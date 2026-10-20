# Captured integer texel fetch

Status: integrated into local main after [154 component/native/CU tests](performance/mobile-native-integer-execution-qualified-2026-10-21.json),
nine descriptor ABI checks and two positive whole-endpoint synthesis screens.
The [main audit](performance/mobile-native-integer-main-2026-10-21.json) verifies803
exact inputs and preserved artifact hashes. Corrected translated AXI qualification
remains running. This
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
| 5 | Full32-bit shader LOD relative to the captured image base level |
| 6..12 | Reserved zero |
| 13 | Three signed four-bit offsets in bits11:0; higher bits zero |
| 14..15 | Reserved zero |

All eight pairs must be valid for a selected lane. Pair0 restarts the tuple;
trusted task launch clears validity, including when identities are reused.
Scalar parameter writes broadcast; vector writes obey active/VL/predicate
masks. Inactive sample lanes retain oldVd. An error in any active lane suppresses
the complete vector destination. Parameters and results stay owned through
backpressure, cancellation and accepted memory-response drain.

The captured path adds shader LOD to the immutable image base with33-bit
arithmetic before level-range checks, following
[GLES3.0.6 section2.12.9.2](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).
The legacy function2 packet level remains absolute. Negative, out-of-range and
overflowing relative LOD values reject without wrapping. The corrected nonzero-base fixture fails against old RTL and passes against
this correction in both stages. [Extended controls](performance/mobile-native-integer-relative-lod-2026-10-21.json)
pass186 checks, including nonmip base validation and failure/reuse. Fresh
composed qualification remains pending.

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
pass at the component level; full shared-CU/AXI qualification, new whole-endpoint PPA,
queries, texture banking/coherence and the remaining H0-H8 work are still open.

The [corrected whole-endpoint synthesis](performance/mobile-native-integer-relative-synthesis-2026-10-21.json)
passes at1000ps with core/I/O setup+101.893/+183.062ps for vertex and
+97.808/+171.998ps for fragment, at TC/SLVT,25% I/O, standard ABC and no
retiming. This frozen source includes the base-relative LOD correction. Its
production inputs match the separate corrected scratch-isolation integration
source exactly; fresh CU/AXI qualification remains pending. PPA is synthesis only.

The [combined execution qualification](performance/mobile-native-integer-execution-qualified-2026-10-21.json)
records154 distinct component/native/CU tests across26 suites,nine ABI checks
and both positive full-endpoint synthesis screens. Both real shader stages
pass local/spilled payloads,all64 formats and image bases0/1/2. The selected
mip-page assertion is corrected after same-source negative/positive controls;
the separate full AXI matrix remains running and is not counted as passed.

The [directed translated AXI proof](performance/mobile-native-integer-mip-axi-directed-2026-10-21.json)
passes four RGBA32F2D frames across local/spilled payload modes with image
bases1/2 and shader LOD0. Both actual shader stages read the selected mip
descriptor page and preserve exact image guards and VM ownership. The same
frozen source reproduces the old origin-page assertion failure. This directed
run does not cover the full format/fault matrix;the unflagged74-frame cases
remain running.

The [disjoint-source captured regression](performance/mobile-native-texture-disjoint-captured-axi-2026-10-21.json)
passes both complete integer AXI matrices with corrected source leases:148
frames,144 successful and four expected errors. Nonzero image bases0/1/2 and
signed offsets retain exact selected-mip,output,fault and VM-drain checks. The
same run also passes144 floating frames. Production and selected AXI suite
inputs match main baseline `f6d8b1c`;the separate typed packet matrix remains
running.
