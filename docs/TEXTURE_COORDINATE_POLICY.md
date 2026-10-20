# Captured texture coordinate policy

This development component extends `TextureUnit` with optional
`coordinatePolicyInput=true`. Each axis independently selects repeat (0),
clamp-to-edge (1), or mirrored repeat (2). Mode 3 is reserved. The signed flag
interprets coordinates as S16.16; otherwise they retain unsigned Q16.16.

The integer-filter reference applies wrapping to unfurled texel indices using
[GLES 3.0.6 table 3.22](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf#page=168).
The implementation reduces normalized periods before its bounded multiply.
Mirrored intervals round the reflected product upward to preserve the original
floor direction and eight-bit filter fractions. NPOT dimensions need no divider.

The optional path captures policy, base, dimensions, format and mip selection
when a request is accepted. These bindings survive both trilinear level walks.
Reserved modes return a held zero result with `coordinateFault=true` and issue
no reads. The fault is meaningful with `result.valid`; its consumer must check
it. Nonzero dimensions and the existing buffer/word-cache ownership rules apply.

Legacy constructors and ports retain their previous contract. No shader ISA or
native descriptor exposes this new policy yet. Prepared metadata is rejected
with this constructor option: its pipeline still needs policy/fault transport
before connection to `TexSampleUnit` and general image/sampler descriptors.
Those integrations, floating-coordinate conversion, full formats/dimensions,
throughput and H4/H6 remain open. This adds integer control/arithmetic, with no
new FPU, CU or SRAM. Numeric, memory-ownership and legacy checks are pending;
PPA remains synthesis only.

The [19-check regression](performance/mobile-texture-coordinate-policy-2026-10-20.json)
passes on 644 unchanged source/ABI files: 9,038 valid samples and six reserved-
mode faults across cached/uncached profiles, plus seventeen legacy sampler/
pipeline checks. NPOT dimensions, signed/unsigned full-width inputs, mixed axis
modes, mip/trilinear walks, captured bindings, reordered replies, held results
and invalid-mode reuse pass. Synthesis-active legacy RTL, ports and hierarchy
match the independently emitted baseline after comments/whitespace are removed.
The enabled component introduces no FPU, CU or SRAM. These are component checks;
the integrations and performance gates above remain open.
