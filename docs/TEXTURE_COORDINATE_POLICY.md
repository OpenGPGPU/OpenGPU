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
it. Zero dimensions also return a fault on the optional path. The existing
buffer/word-cache ownership rules apply.

Legacy constructors and ports retain their previous contract. No shader ISA or
native descriptor exposes this new policy yet. The development prepared path
now transports per-axis policy through the six-stage coordinate pipeline and
returns a separate coordinate-fault sideband. `TexturePreparedSample` retains
its existing addresses/weights shape. When prepared metadata is valid, it owns
addressing independently of the sampler's live policy/base/dimension pins; the
owner must forward its fault with the metadata. Nonzero mip, fractional LOD or
maximum mip inputs are unsupported for prepared entries and return faults.
Faulted entries issue no reads and retire in order through the optional
two-context window. A younger entry cannot change an older held result/fault.
Prepared-policy component checks pass; connection to `TexSampleUnit` and
general image/sampler descriptors remains required.
Those integrations, floating-coordinate conversion, full formats/dimensions,
throughput and H4/H6 remain open. This adds integer control/arithmetic, with no
new FPU, CU or SRAM. Prepared numeric, memory-ownership and legacy checks pass;
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

The [23-check prepared-policy regression](performance/mobile-texture-prepared-policy-2026-10-20.json)
passes on 637 unchanged source/ABI/selection files, with four ABI checks and
five RTL emissions. The six-stage pipeline matches independent unfurled
index-wrap coefficients for 2,674 inputs, including six invalid entries, and
streams 512 results at six-cycle latency with no bubbles. Maximum 14-bit
dimensions on both axes, 32-bit address wrapping, arbitrary stalls and reset
of occupied stages pass.

The prepared sampler returns 384 ordered results with 34 faults across serial
and overlapping profiles. Checks poison live bindings, reorder physical
responses, pulse invalidation during pending reads, and admit a younger fault
and valid successor while an older result is held. Eighteen additional direct
requests cover mixed axes, captured trilinear bindings and fault recovery in
the prepared-capable sampler. The existing nineteen checks pass again.
Legacy coordinate and cached/prepared sampler synthesis-active SV, ports and
hierarchy match independently emitted pre-extension baselines. New optional
emissions contain no FPU, CU or SRAM; no new rate or PPA result is claimed.

The next development step adds optional `TexSampleUnit` policy/fault transport.
It snapshots scalar/vector instruction bindings, forwards prepared faults,
aggregates faults through held completion and suppresses destination writes on
a fault. Signed implicit LOD uses the full coordinate difference and applies
bias before narrowing the full gradient exponent into the selected mip range.
Qualification is pending. Instruction encodings and default legacy hardware
remain unchanged; native descriptors and task-owner fault integration remain
required before exposure.
