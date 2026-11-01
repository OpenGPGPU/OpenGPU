# Internal tessellation command routing

The opt-in `TileBinHostCommandPort` route reserves opcode 15 for a GNQ1 job.
It uses the existing 512-byte command envelope and 33-bit exclusive limit,
rejecting unsupported dependency and feedback flags through the envelope-valid
bit. The job is offered only when the shared render and memory dependencies are
idle. A submitted command retains its ID until its tessellation completion is
accepted, and prevents another render or memory command from entering during
that ownership; ordinary kernels retain the existing overlap policy.

The route is disabled by default. The enclosing host must include the actual
tessellation engine and all its memory in `renderIdle`, keep the VM and
allocations pinned, connect cancellation and completion details, and establish
physical alias provenance. No host capability is advertised here; wiring the
real job-to-image owner, MMIO/AXI submission, complete reset/drain semantics,
other tessellation domains and product qualification remain open.

The focused route and existing query, feedback, image and completion-port
regressions pass 21 tests; test-selection checks also pass.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationHostCommandPortSpec opengpu.graphics.TileBinHostCommandPortSpec opengpu.graphics.TileBinHostPrimitiveQuerySpec opengpu.graphics.TileBinHostQueryCommandPortSpec opengpu.graphics.TileBinHostTransformFeedbackSpec opengpu.graphics.TileBinHostImageCommandPortSpec opengpu.graphics.NativeBinnedCompletionInfoSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```
