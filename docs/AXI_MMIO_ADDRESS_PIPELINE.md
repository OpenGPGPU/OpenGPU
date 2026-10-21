# AXI MMIO beat-address pipeline

`GpuHostAxi` now captures burst protocol validity at AR/AW acceptance and
registers the complete current 33-bit address. INCR advances by four only when
a nonfinal beat is accepted; FIXED retains the address. The high carry remains
visible to register-map rejection. This removes base-plus-beat and burst-end
arithmetic ahead of register decoding, including the previously reported
beat-to-read-data I/O path. No new measured host slack is claimed yet.

The original response contract remains: the first beat bypasses the holding
register, unstalled reads return one beat each cycle, and a held response keeps
its exact data, response and last flag. W/B stalls do not advance or duplicate
writes. Unsupported sizes/burst types, misalignment, 4KiB crossings, high carry
and FIXED bursts longer than sixteen beats retain their rejection behavior.
Dynamic VM/quota mutation guards are still checked at the actual write beat;
only protocol validity is captured with the header. The original eight-bit
beat-counter wrap is retained even for malformed overlong write streams.

The [1,008-input checkpoint](performance/mobile-native-mmio-beat-address-2026-10-22.json)
passes seventeen checks. New tests transfer 341 read beats over thirteen
transactions, including a 256-beat rejection stream, poison live header pins
after acceptance, hold responses and exercise FIXED/INCR writes with exact
readback. Existing ownership/fairness, native capability, query/feedback and
quota/VM-control regressions remain selected.

The first runs exposed stale discovery fixture literals: GTX1 already includes
typed component-fetch bit512, and the following address0x220 already starts
GTF1. The mask mismatch reproduces on unchanged main. Corrected expectations
remain independent constants; the ninth word checks GTF1 magic, and a truly
unmapped disabled-query address retains the hole test. Original failed logs,
source snapshots and exact test-only repair are preserved. A separate main
baseline passes all four corrected profile cases with production unchanged;
the test file is restored exactly after the run. All1,008 baseline inputs stay
unchanged until that restoration, and existing user bytes are preserved.

The new address structure and functional checks do not qualify the full host.
Current-main UV/native/GTO1 composition, remaining legacy quad/color/depth/
gradient repair and exact default host synthesis at1000ps still remain.
