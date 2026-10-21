# Translated RGBA8 with native FP32 filtering

`TranslatedRgba8Fp32TextureService` connects virtual tap admission, raw banked
fetch, RGBA8 decode and native FP32 bilinear filtering. The [828-input
qualification](performance/mobile-translated-rgba8-fp32-2026-10-21.json) passes
82 fresh checks across ten suites and ten RTL emissions. Thirteen checks
exercise the new service, 46 regress the existing banked services, and 23
cover the existing native execution/query controls.

The input contains prechecked, aligned RGBA8_UNORM virtual word spans, row
parity, work identity, view key, caller tag and full FP32 X/Y weights. Coordinates,
resource bounds and task binding admission are upstream. Other formats/layouts
and shared-CU request wiring are still required for the native sampler.

Every nonzero contribution requires translation and permission before any data
lookup, even for a resident physical alias. Exact endpoints suppress unused taps;
negative zero selects the zero endpoint. The smallest positive subnormal remains
active. Internal unit-sum Q16 values represent only this exact admission mask;
they never replace or quantize the FP32 interpolation weights.

Raw mode returns four original little-endian words, with R in the low byte.
It bypasses the Q16 weighted-sum normalizer; its shared result's `values` field
is zero and `rawTaps` is the numeric payload. Four decoders round each byte/255
to FP32 before the [native filter](TEXTURE_BILINEAR_FP32_PIPELINE.md) evaluates
its separately rounded inverse/product and fused sum. Tests retain subnormal
contributions, including the gradual-underflow boundary between bytes 127/128.

The parent retains 128 result owners through decode, filtering and public
retirement. Child replies are associated through an ordered ID queue, so caller
tags may repeat. Scoped cancellation matches every live identity/tag row,
including rows whose child work already retired. One public result stays
immutable. Global cancellation/invalidation closes admission through complete
epoch drain; accepted PTE/data offers and arithmetic are never abandoned. The
VM epoch stays pinned until this entire lifetime ends.

Translation and data faults retain their original virtual tap address and remain
primary under cancellation. Invalid FP32 weights fault before PTE/data work.
Fault-source values are 1 for translation, 2 for data and 3 for invalid weights;
source 3 has address zero. Uncached aliases bypass resident data and do not install
private lines. Drained root changes or explicit translation flushes remap backing.

A warmed translated-to-filtered stream sustains 192 complete groups at input
and output II1 across owner-ring reuse. Another 384 groups exercise backpressure,
with no extra PTE or data reads. Tests cancel all 128 warm owners and invalidate
18 cold accepted groups while a physical offer is held, then verify exact
retirement and reuse. Ten further cuts cover translation through final retirement.

The outer declared owner arrays account for 11,024 logical bytes before constant
or duplicate-register elimination. Queues and arithmetic are additional, as are
the child's 32 translation/result rows, 16 physical sample slots, 16 fills and
8 KiB four-bank data cache. This is a logical storage account, not an SRAM macro
or physical area result.

The five default banked profiles still match their qualified RTL after removing
comments only. The standalone FP32 filter retains 16 byte-identical active files
and its passing component synthesis. The complete new translated service has a
passing synthesis screen at 1000 ps, TC/SLVT, 25% I/O, standard ABC, without
retiming or place/route. Core/I/O setup slack is +40.947/+343.996 ps, with
complete mapping and zero skipped instances. Whole-native performance remains
unproven; subsequent read-accounting/late-join and client-router revisions
require separate synthesis.
The legacy controls pass on the same frozen source. Both legacy native endpoints
have fresh 77-file emissions; only the FMA core and wrapper logic differ from
the old qualified endpoints. Fresh vertex endpoint synthesis now passes; fragment
endpoint synthesis is running. Prior native endpoint timing is not carried.

## Fresh native vertex synthesis

The corrected FMA's [complete native vertex endpoint](performance/mobile-native-fp32-vertex-synthesis-2026-10-21.json)
passes mapped synthesis at 1000 ps, TC/SLVT, 25% I/O delay and standard ABC,
without retiming or place/route. Core/I/O setup slack is +88.968/+163.440 ps;
all 1,166,030 cells are mapped with zero skipped instance types. Library area
is 154116.373679. The 828-input source and all 77 active endpoint SV files
remain unchanged through the run. This endpoint still uses the existing native
sampler; it does not demonstrate new banked service routing. Fresh fragment
synthesis is running separately. The translated native FP32 service also
passes its own synthesis screen at +40.947/+343.996 ps core/I/O slack. The
subsequent counted/late-join revision and coordinate/client composition need
their own synthesis evidence. All remaining H0-H8 gates stay open.
