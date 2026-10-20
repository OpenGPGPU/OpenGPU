# Banked RGBA8 read-through service

Status: [nineteen distinct component checks](performance/mobile-banked-texture-readthrough-2026-10-21.json)
pass on812 frozen inputs. Eighteen checks pass together;an appended fault/cancel
race passes on otherwise identical inputs with all prior test bodies retained.
Resident-default synthesis passes1000ps including I/O (+172.334/+85.011ps);
the full read-through synthesis screen remains active. Native shader
resource/translation integration and the H4 exit gates remain open.

`BankedRgba8TextureHits(readThrough=true)` connects the four-bank8KiB cache,
sixteen-line fill owner and bounded bilinear association/filter pipeline.
Each input already contains four successfully translated,permission-admitted
physical RGBA8 addresses,row parity,weights,view key,work identity and a tag.
Coordinate/LOD/format planning and permission admission remain upstream.
The resident-only default retains its earlier interface and explicit misses.
The read-through profile owns refill internally and adds line-memory ports,
fill counters and a fault/fault-address completion.

`BankedTextureWordService` retains64 lookup owners across cache lookup,miss
joining and registered per-bank replies. Matching live physical/context/view/
parity lines share sixteen memory IDs and32 waiting consumers. The public
fill completion is an additional register. Returned cache lines supply their
exact requested word directly after refill acknowledgement. Four same-set
physical tap lines therefore complete even though they cannot all remain
resident together. A later miss does not join an already completed fill.

The bilinear owner coalesces duplicate taps,associates hit/forwarded words and
emits complete groups in order. In this profile `hit` means a successful
complete read-through result,including resolved misses. A physical read fault
suppresses the color and identifies an original physical word;it is never a
successful zero. Every accepted tap drains before the group completes.
Concurrent cancellation retains the primary fault. Fault addresses still need
native virtual/resource attribution at the future shader integration boundary.

Cancellation and invalidation stop unoffered word work,retain immutable public
memory/refill/word/sample offers and drain old owners. Late fills cannot publish
after an invalidation pulse. A held completed sample remains unchanged;earlier
unoffered groups become cancelled. The parent must scope cancellation for
shared native tasks and pin resource/VM publication through completion.

The cold-stream check admits eight groups and16 physical reads before the
first response,joins32 word consumers and reverses memory replies. After warm-up,
96 complete groups enter and retire each cycle with no physical reads. Another
192 groups preserve tags,colors and order under asymmetric backpressure.
Other cases cover four conflicting same-set taps,shared faults,eight cancellation
cuts,view/generation keys,changed backing data and late invalidation drain.

Filtering retains the existing integer group policy:four17-bit weights sum to
65,536,and each output byte is the weighted sum shifted right16 bits. Input
words are little-endian RGBA;packed output has R in bits31:24. Full normalized
FP32 results and native sampling precision need explicit integration. The
measured II1 starts with admitted physical taps and weights;it is not the
whole shader-to-texture issue rate or a whole-frame performance result.

The default store is byte-identical to its positive synthesis inputs. The
non-forwarding fill profile differs only in source-location comments;every
active SystemVerilog byte matches after removing comments while preserving
strings and whitespace. The changed resident-default filter and complete
read-through profile receive fresh synthesis screens. SRAM is behavioral in
these screens;no characterized macro area,power,route or GPU clock is claimed.
