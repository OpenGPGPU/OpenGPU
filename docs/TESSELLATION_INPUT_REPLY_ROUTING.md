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

| Case | Result | Elapsed | Sampled process-group peak |
| --- | --- | --- | --- |
| Dynamic isoline | 1 passed | 11.07 s | 397280 KiB |
| Dynamic triangle | 1 passed | 11.73 s | 422240 KiB |
| Dynamic quad | 1 passed | 11.64 s | 427184 KiB |
| Legacy isoline | 1 passed | 10.35 s | 393104 KiB |

The same test and dependencies were run with freshly compiled original
production files from `9bc86886a20e158f3e01e417f65c6afee537fe2e` first in the
runtime classpath. All three dynamic domains failed at the 128-cycle semantic
assertion `load response stalled after its real input write`, after their
actual write was accepted. These were assertion failures, not wall timeouts.
Runtime class-load checks confirmed the intended fixed/original production
provider in each run. Source, class, jar, tool and membership guards passed.
All 163 selector checks and the integration/large-PR suite catalog checks
passed; the new component suite is registered in `system-core`.

The two existing `TessellationEvaluationPatchProviderSpec` RTL cases also
passed (21.00 s, 377408 KiB sampled group peak), covering held three-issuer
inspection credit, owner/scope rejection and canceled accepted inspection
drain. Those tests use modeled transport peers and do not execute a shader.

Each local run used a 180-second/512-MiB sampled aggregate process-group
guard, Java heap/metaspace/code-cache limits of 128/128/32 MiB,
`ActiveProcessorCount=1` and SerialGC. Native make used one job; Verilator's
upstream `-j 0` is not a strict one-CPU limit. Only selected changed sources
were freshly compiled, with individually source-matched dependency classes.
This does not qualify a complete source build or the full two-CU model.

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
