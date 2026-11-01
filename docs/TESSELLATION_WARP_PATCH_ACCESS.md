# Tessellation warp patch access

`TessellationWarpPatchAccess` serializes one warp's active lanes into checked
`TessellationPatchContext` control accesses. Lane `i` owns invocation
`base+i`; a nonempty mask must fit in the 32-invocation patch. The owner must
route control replies to this adapter and issue a warp's barrier or finish
only after its access result is consumed. The adapter retains one instruction
at a time, accepts lanes in increasing order, and holds its result until
accepted. Input, per-vertex output, per-patch and level permissions remain
enforced by the patch context.

The result contains a retired-lane mask and returned data. A checked access or
reply error stops further lanes after the failing reply; earlier writes are
not rolled back. Cancellation holds an offered access until accepted and drains
its reply, then reports cancellation without issuing another lane. The owner
must continue routing accepted replies during cancellation. When the owner
accepts a terminal patch-context error, it must assert `terminal` to retire an
unaccepted offer that the closed context can no longer receive; accepted
requests still drain their replies. Invalid or empty
masks produce a failure without patch traffic. A held result is immutable even
if cancellation arrives after it was offered.

The bounded tests cover sparse and full lane masks, invocation 31, held offers
and results, invalid masks and replies, a later-lane fault, cancellation and
reuse. A composed patch-context test writes and reads 32 invocation outputs
across two warps and a barrier, and checks a forbidden cross-invocation write.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationWarpPatchAccessSpec opengpu.graphics.TessellationWarpPatchContextSpec opengpu.graphics.TessellationWarpBarrierSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

This is an internal ownership adapter. Actual CU instruction execution,
concurrent warp scheduling, dynamic TCS/TES ABI and compiler support, native
submission, physical timing and product throughput remain open. It does not
close H8.
