# L2 store transaction ID ownership

Each L2 slice reserves lower IDs 0–3 for load MSHRs and IDs 4 through
`4 + storeEntries - 1` for outstanding stores. Store IDs use a carry-preserving
sum before widening to the requester interface. Without the carry, entry 4 in
an eight-entry table emitted ID 0 instead of 8; entry 12 in a sixteen-entry
table similarly emitted 0 instead of 16. A store acknowledgement could then
enter the load-refill path.

The repair preserves table capacity, allocation/issue latency, disjoint masked
write overlap, same-byte ordering and whole-line read hazards. An outgoing-ID
assertion checks the reserved store namespace.

Forty-one checks pass across the complete store-table, MSHR, merge-response and
shared-cache suites, with zero failures, skips or pending cases. The seven
store-table checks include both full eight/sixteen-entry windows, twelve-bit
caller IDs, lower backpressure, reverse and faulted acknowledgements, held
client replies, read hazards and entry reuse. Both overflow cases fail on the
preceding arithmetic.

The complete eight-entry and sixteen-entry tables use standard synthesis at
1,000 ps with 25% I/O delay: +483.040 ps core / +336.330 ps I/O and +434.623 ps core / +306.315 ps I/O respectively.
There is no retiming, false-path exemption, skipped operator or unresolved
mapping. These are table component results; they do not qualify an enclosing
L2 or GPU clock, storage budget, product rendering or frame rate.

```sh
sbt -J-Xmx6G -J-XX:+ExitOnOutOfMemoryError -Dopengpu.simJobs=1 \
  'testOnly opengpu.core.memory.L2StoreTransactionTableSpec opengpu.core.memory.L2MshrTableSpec opengpu.core.memory.L2MergeResponseGuardSpec opengpu.core.memory.SharedL2CacheSpec'
for entries in 8 16; do
  sbt "Test / runMain opengpu.core.memory.EmitL2StoreIdRtl $entries .artifacts/l2-store-$entries-rtl"
  CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
    ".artifacts/l2-store-$entries-rtl" L2StoreTransactionTable \
    --stage synthesis --period-ps 1000 --io-delay 0.25 --timeout 14400 \
    --output ".artifacts/l2-store-$entries-synthesis" || exit $?
done
```
