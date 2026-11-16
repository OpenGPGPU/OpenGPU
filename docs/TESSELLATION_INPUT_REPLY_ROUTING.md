# Shared tessellation input reply routing

With `evaluationPatchAvailable=true`, the isoline, triangle and quad shared
evaluators previously sent every patch-context reply into the inspection
arbiter. That arbiter obtains reply credit only after an inspection request
is accepted. An original VS input write produces a source-0 reply before any
inspection, so its acknowledgement stalled, including during cancellation.

All three evaluators now return source-0 replies directly to the input
publisher. Source-2 replies retain the existing three-issuer inspection
arbitration. A valid reply with another source is an assertion failure.
The legacy path, public interfaces and capacities are unchanged.

## Final component results

`TessellationSharedLoadReplySpec` instantiates each actual shared evaluator,
control scheduler, patch context and store with 16 lanes, eight warps and
two CU interfaces. It uses 32×64 input components and 32×60+120 output/patch
components. It accepts actual first/last input writes, holds their real
replies, cancels with a reply outstanding, drains completion, and reuses the
store without reset, including a checked out-of-bounds write. No inspection
request, shader execution, CU completion or reply peer is injected.

All four actual domain/legacy cases pass in the exact delivery's fourteen-case
component run, using all-fresh production/test classes; the complete run takes
113.212 seconds. Its compiler and actual-CU results are in the
[P40 staged validation](TESSELLATION_DYNAMIC_PATCH_READ.md#staged-source-validation-and-limits).
This fixture does not seal or launch a shader and needs no later language-ID
binding. Its original full-capacity write/ACK, cancellation, fault and reuse
oracles remain intact.

Independently compiled copies of all three production evaluators with the old
reply routing each fail the fixed 128-cycle assertion `load response stalled
after its real input write`, after the actual write is accepted. Isoline,
triangle and quad fail with one selected test each in 9.724/10.799/10.889
seconds, within separate 180-second/1536-MiB limits; sampled aggregate maxima
are 478656/532784/544128 KiB. These are semantic assertion failures, not wall
timeouts. The evaluator and fixture sources are unchanged by the later CU
launch-contract fix. All owned processes exit before mutants/classes/logs are
removed. These controls do not execute a shader.

All 157 affected-suite selector checks and integration/large-PR catalog checks
pass. `TessellationSharedLoadReplySpec` is registered in `system-core`, alongside
the preserved earlier joint metadata suites.

## Reproduction and remaining scope

Run the suites locally without dispatching a workflow:

```sh
sbt -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 -Dopengpu.simCache=off -Dopengpu.simSeparateBuild=1 'testOnly opengpu.system.TessellationSharedLoadReplySpec opengpu.system.TessellationEvaluationPatchProviderSpec'
python3 -B scripts/test_changed_test_suites.py
python3 -B scripts/test_test_selection.py
```

These component results do not establish original VS→TCS→TES execution,
actual two-CU ownership/transform feedback, original Mesa programs, three-domain
protected submission or 640×480/60-FPS product qualification. The complete
model must be rebuilt from the corrected sources before those gates can pass.
P39/P40 remain open; the alignment closed count stays 3/40.
