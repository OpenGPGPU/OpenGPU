# Conservative resident depth bounds

`ConservativeD24DepthSummary` tracks an interval containing every current D24
sample in a resident depth plane. A clear initializes a uniform interval. An
unknown binding needs complete observed word coverage before becoming valid.
Writes immediately expand the current interval; tightening requires a complete
coverage epoch. Repeated writes retain all observed values in that epoch, so
overwrites and independently reordered ports remain conservative.

The native shared store observes full, irrevocable depth writes at the canonical
SRAM request ports. These taps do not control producer readiness. Generic depth
traffic, generic color ownership, maintenance writes, cancellation and faults
invalidate the interval. A later drained native bind starts unknown; it cannot
resurrect the original clear after a transfer or generic update. A new pass
binds a new summary even when it reuses the same physical plane.

Local validation covers the full 512-word plane, duplicated/reordered two-port
writes against actual resident values, native write observations, transfer
invalidation, partial recovery, cold loads and generation reuse. All six cases
in these two suites pass. Existing canonical-image, cancellation and MSAA
1x/2x/4x store regressions retain their assertions and four-sample steady rate.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  'testOnly opengpu.graphics.ConservativeD24DepthSummarySpec opengpu.graphics.SharedTypedSampleTileStoreSpec'
```

The interval currently supplies an observation interface. Pre-shader skip
eligibility, exact incoming-depth comparison, shader bypass, paired early/late
rendering equivalence and timing of that integrated path remain required.
These bounds alone do not close H3 or establish a whole-GPU clock or frame rate.
