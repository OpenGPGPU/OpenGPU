# Vertex input formats

`VertexInputFetcher` accepts the original raw attribute contract with input
version 1 and the converted format set with input version 2. Input version is
independent of the vertex shader export ABI version. Version 1 accepts IDs 0–6;
version 2 accepts IDs 0–24. IDs 25–31 and other input versions are rejected.
Disabled arrays copy their four constant words without interpreting the format.

| IDs | Format | Delivered components |
|---|---|---|
| 0 | FP32 | Original bits, including signed zero and NaN payloads |
| 1–6 | Unsigned/signed 32, 16, 8-bit integers | Canonical raw 32-bit integer words |
| 7 | Binary16 | Exact FP32 expansion; signed zero and infinity; canonical positive quiet NaN |
| 8–13 | Unsigned/signed 8, 16, 32-bit integer to float | FP32, round to nearest, ties to even |
| 14–19 | Unsigned/signed normalized 8, 16, 32-bit | FP32, exact rational normalization rounded to nearest/even |
| 20–21 | Unsigned/signed normalized 2:10:10:10 REV | Four FP32 components |
| 22–23 | Unsigned/signed 2:10:10:10 REV to float | Four FP32 components |
| 24 | Signed 16.16 fixed point | FP32, round to nearest/even |

Unsigned normalization divides by `2^b - 1`. Signed normalization divides by
`2^(b-1) - 1` and clamps the most negative code to -1. REV packed words place
X/Y/Z/W in bits 0–9/10–19/20–29/30–31. Packed arrays require four components,
four-byte alignment and one complete logical four-byte word. Their component
index selects bits within that word, rather than stepping to another word.
These choices follow the vertex conversion and packed input definitions in the
[GLES 3.0 specification](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf),
sections 2.1.2 and 2.8. This hardware contract alone is not API conformance.

Byte, halfword and word formats retain their natural alignment checks. Every
component is checked against logical and allocation bounds before fetching;
allocation bounds cover complete cache lines. Missing array components use
zero, with W equal to FP32 1 for floating outputs or integer 1 for raw integer
outputs. Index restart, signed base vertex and instance divisors are unchanged.

Conversion occurs after a registered component-word capture. The normalized
converter repeats the numerator's bit pattern over 64 fractional bits, retaining
the leading bit, 24 significand bits and rounding guard for denominators up to
`2^32 - 1`; it uses no divider. Integer conversion retains guard and sticky bits
for ties-to-even. Cancellation can suppress a captured conversion and drains
offered reads, accepted responses and held records before reuse.

Native typed and binned record offsets, sizes and magic values are unchanged.
Each attribute format has a dedicated 32-bit wire word; five low bits now carry
its ID and the remaining bits are zero. Generated headers publish all 25 IDs
and the maximum input version. Older version-1 records retain their original
encodings. [GVI1](NATIVE_VERTEX_INPUT_CAPABILITIES.md) advertises the extension
on native typed and binned host profiles. Complete native host qualification
remains an integration requirement.

The initial component run passes nine checks, including 217,680 exact numeric
cases, all binary16 patterns, exhaustive normalized 8/16-bit domains, packed and
32-bit rounding boundaries, converted indexed/instanced fetches, cross-line
reads, typed defaults, logical bounds and legacy fault/cancellation controls.
The expanded conversion-cancellation and actual shared-CU shader checks are
pending. No new throughput or synthesis PPA result is claimed.
