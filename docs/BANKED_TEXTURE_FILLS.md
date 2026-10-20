# Banked texture line-fill ownership

Status: [thirteen component checks](performance/mobile-banked-texture-fills-2026-10-21.json)
pass,including four fill-owner checks connected to the actual8KiB banked SRAM.
Default16-fill RTL emission passes;its synthesis screen is running. The service
is on `codex/mobile-texture-fill-owner`;native shader sampling is not connected.

`BankedTextureFillOwner` accepts an aligned physical64-byte line,work identity,
texture/publication key,row parity and consumer tag. Translation and read
permission must already have succeeded for every consumer before admission or
joining. Cache identity never substitutes for permission checks. This service
has no TLB and returns fill progress,not an architectural zero texel.

Sixteen fill slots own independent memory IDs. A32-entry waiter table joins
matching physical/context/view/parity requests while a read or refill is live.
The held public completion is an additional register. Failed reads fan out a
fault without installing data. Reordered memory replies retain their fill ID;
registered refill offers wait for the store's acknowledged eight write beats.
A memory ID remains reserved through all associated consumer completions,
including the held public reply. A completed fill cannot accept new joiners:
a later miss may mean that another line already replaced its cache set.

The test admits32 consumers before the first memory reply,issues exactly16
independent reads,reverses their responses,and checks all installed words in
the real cache. Other checks cover context generation/view isolation,fault
fanout,immutable public replies,an offered memory read held across cancellation,
unoffered read cancellation,backpressure and healthy reuse.

Cancellation and invalidation each establish an epoch drain. Unoffered reads
can stop;offered reads/refills remain immutable and must drain. Invalidation
stays asserted at the cache until every old completion is consumed. A directed
regression showed why a pulse is insufficient:the stalled old refill could
install after the pulse despite its poisoned completion. The identical test
fails before the owner fix and passes afterward,checking the actual cache
again after invalidation and drain have ended. New work waits for that boundary.
Shared multi-task integration still needs cancellation scoped by its parent.

The existing [resident bilinear service](BANKED_TEXTURE_HITS.md) retains its
one-conflict-free-group-per-cycle hit gate. Miss read issue,refill rate and
whole-path sample latency are different quantities;this fill service does not
establish a whole-native-sampler throughput result. Next work connects group
misses,replay and result association,then translation/resource ownership and
native shader fault/coherence tests. Synthesis-only PPA remains the development
policy;H4 and the full H0-H8 goal remain open.
