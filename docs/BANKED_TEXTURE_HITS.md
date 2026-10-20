# Banked RGBA8 texture hit pipeline

Status: [eight component checks](performance/mobile-banked-texture-hits-2026-10-21.json)
pass on808 frozen inputs. Store and complete filter-cluster synthesis are
running. Translation/native shader integration and the sixteen-fill scheduler
remain required before H4 can close.

`BankedRgba8TextureHits` accepts one admitted bilinear group:four physical word
addresses,row-parity bits,normalized17-bit weights,work identity,opaque64-bit
view/publication key and destination tag. The measured interval begins with
translated addresses and weights;resource admission,address generation and
translation must also sustain the rate in the final sampler. This component
has no TLB and is not connected to shader instructions yet.

## Storage and bank mapping

For RGBA8 rows with64-byte-aligned starts and pitches,bank=`(yParity,address[2])`.
Adjacent X/Y taps normally occupy four banks. Duplicate clamp taps share one
word request;distinct words in the same bank serialize. Odd-size wrap and other
conflicting patterns must retain correct results with reduced rate.

The default8KiB data store has four logical single-port word banks. Each bank
uses two256x32 arrays,for eight arrays total. A64-byte line occupies eight words
in each bank of its row-parity pair. Each pair has64 independent direct-mapped
line slots;the aggregate holds128 distinct lines without four full data copies.
Skewing every line into one parity pair limits useful capacity to4KiB. Set bits
come from physical address[11:6]. Tags include the remaining20 physical line
bits,96-bit work identity and64-bit view key:2,880 tag bytes plus16 valid bytes.
These tags and pipeline/sample state are additional to the8KiB data budget.

The16-entry sample association table holds1,092 logical bytes of request,data,
alias,completion and cancellation fields,plus pointers/control. The refill
buffer,word-reply queues and filter registers are additional. Lane count remains
`GpuConfig.lanes`;four taps/four word banks do not define a shader VLEN.
Behavioral and characterized-macro array modes share the single-port contract;
the current synthesis screen uses behavioral arrays,not a macro area claim.

## Ownership and results

Every lookup requires successful physical translation/read-permission admission
upstream. Rebinding,translation changes and render-to-texture publication require
the enclosing owner to issue invalidation and retain VM/resource ownership
through `busy` drain. Cache identity cannot substitute for permission admission.

The store reserves a reply entry before each synchronous SRAM read. Tagged
responses remain immutable under backpressure. A refill writes two banks over
eight cycles;the other bank pair can keep reading. The refill lease ends when
`refillDone` is consumed. Failed fills never install valid data. Invalidation
during a fill drains its accepted writes and reports that it was not installed.
Already offered word/sample/refill completions remain stable.

The filter coalesces duplicate taps and associates returned words with a bounded
sample slot. Registered products and channel sums sustain one complete sample
per cycle after warm-up on conflict-free resident data. Input bytes have R in
bits7:0;packed output has R in bits31:24,matching the existing integer texture
filter convention. Channel output is `sum(byte*weight)>>16`,with an exact weight
sum of65,536,including the17th endpoint bit. This is an RGBA8 group service;
full FP32/native-format,LOD/mip,shadow and API precision policy remain with the
general sampler until an explicitly qualified fast-path integration exists.

A miss produces `hit=false` and a missing-tap mask. Its zero color is not a
successful texture value or an architectural fault result. The future miss/
fallback owner must resolve or fault it before shader retirement. Global cancel
stops new admission and drains accepted bank work;accepted output already
offered to a consumer retains its value. Shared multi-task integration needs
owner-scoped cancellation around this global service drain.

## Evidence and next integration

The tests measure96 resident samples/384 taps with both input and complete
result intervals of one cycle. Distinct same-bank taps give an interval of four;
duplicate clamp taps use one read and retain one-sample-per-cycle throughput.
Other checks fill/read all128 unique lines,exercise13 cancellation cuts,hold
results,stream through opposite-pair refills,wrap16 sample slots under192
backpressured samples,and fairly serve96 words from four contending producers.
Physical-tag,context,generation and view changes cause explicit misses.
Both new suites are selected by the existing CI graphics shard.

Next work is a translated tagged miss owner with16 outstanding fills,miss joining,
fault fanout and refill arbitration,followed by native shader/resource integration
and whole-path rate/coherence checks. PPA remains synthesis only;no whole-GPU
clock,power,route or H0-H8 completion is established by this component result.
