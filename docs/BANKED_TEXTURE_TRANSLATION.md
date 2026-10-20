# Translated banked RGBA8 group service

The [822-input qualification](performance/mobile-banked-texture-translation-2026-10-21.json)
passes46 fresh component tests and six emissions. This is the virtual-tap
admission boundary for the banked service;native CU/resource/coordinate wiring
and full timing closure remain open.

`TranslatedBankedRgba8TextureService` accepts checked virtual RGBA8 spans,row
parity,Q16 weights,work identity and a caller tag. Four TLB ports check every
active tap before dispatching any physical group,including cache hits. A
fault suppresses the entire group;zero-weight taps need no translation or read.
All accepted translations drain. Physical data faults map back to an original
virtual word,with separate translation/data attribution.

The parent pins one SATP epoch until `busy` clears,including held final results.
A SATP change while idle automatically flushes translations and the private
cache before new admission. Explicit scoped flushes also require idle. PTE
traffic uses a separately routed four-ID port;physical data uses sixteen IDs.
Thirty-two admission/result rows are extra metadata beyond the physical service's
sixteen association slots,fill slots and8KiB data cache.

An uncached PTE forces fresh physical reads and prevents private installation,
even when a cached alias already holds the line. Cached/uncached misses do not
join one fill. Invalidation establishes a data publication boundary;page-table
changes within one SATP require the explicit drained translation flush.

`cancelGroup` matches the complete work identity and caller tag. It cancels only
those rows,drains their accepted PTE/data work and preserves unrelated groups
and later tag reuse. Global cancel/invalidate drains the whole epoch. A single
public completion stays immutable;completed rows behind it remain cancellable.
Primary translation/data faults survive cancellation.

Tests cover nonidentity Sv32,denied resident aliases,partially denied groups,
unmapped unused taps,uncached aliases,root changes,explicit remapping,precise
virtual faults,held PTE cancellation,scoped cancellation and publication cuts.
A warmed stream sustains96 complete groups at input/output II1;192 more groups
exercise backpressure without new PTE/data reads. Numeric checks retain the
exact admitted Q16 contract;they do not establish native coordinate precision.

The resident default matches its existing synthesis logic after removing only
comments. Complete read-through timing remains negative after the first queue
cut (-32.971/-144.873ps);shared fill-admission registration is next. The new
translated service needs its own full synthesis screen. No native sampler rate,
GLES completion,power,route or whole-GPU clock is claimed.

The [shared fill-admission correction](performance/mobile-banked-texture-shared-admission-2026-10-21.json)
now passes46 fresh checks on822 inputs with six emissions. Two complete word
records after bank arbitration separate that selection from fill-key matching.
Stopped shared admissions retire through a fourth word-completion source,with
all original owners retained. The `joined` performance pulse is registered by
one cycle;event counts and warm input/output II1 remain unchanged. Packed read-through
synthesis now passes1000ps including I/O (+41.862/+11.396ps),with750,012
fully mapped cells. FP32 improves but fails (-6.794/-48.922ps),with754,779
mapped cells. Its remaining path crosses shared-owner cancellation selection,
return credits and tap telemetry. Translated synthesis now passes 1000 ps
including I/O (+46.194/+193.375 ps), with 943,830 fully mapped cells and
122,463.368640 mapped library area. All 822 source inputs and 29 active RTL
files remained unchanged. Scalar epoch-control and registered telemetry are
qualified separately before main integration. These are synthesis-only component
results;native wiring and whole-GPU timing remain open.

The [epoch-control correction](performance/mobile-banked-texture-epoch-control-2026-10-21.json)
passes46 checks again on822 frozen inputs. Since word-service cancellation is
global and new admission waits for complete drain,one sticky epoch bit replaces
per-owner cancel/invalidation lookup arrays. Selective identity/tag cancellation
remains in the translated parent and preserves unrelated groups. Tap/conflict
telemetry is registered by one cycle;tests verify384 hot and768 backpressured
tap events with unchanged input/output II1. Standalone normalizer and resident
cluster active RTL remain raw-byte identical. Fresh packed,FP32 and translated
synthesis screens are running at1000ps;their timing closure is still unproven.
