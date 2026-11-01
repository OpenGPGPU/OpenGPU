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
real job-to-image owner, system-level MMIO/AXI submission, complete reset/drain semantics,
other tessellation domains and product qualification remain open.

The optional `GpuHostAxi` transport exposes this submit/completion pair to its
enclosing system and includes the external tessellation drain in graphics and
VM ownership. A focused AXI test submits a job, verifies the pinned VM through
the live and held-completion phases, and checks reset cancellation. This is a
stubbed owner test: the production system still has to attach the actual job
engine and its memory and shared-CU paths before the command is available.
The focused host test and existing AXI ownership/native DMA regressions pass
six tests; test-selection checks pass as well.

The focused route and existing query, feedback, image and completion-port
regressions pass 21 tests; test-selection checks also pass.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationHostCommandPortSpec opengpu.graphics.TileBinHostCommandPortSpec opengpu.graphics.TileBinHostPrimitiveQuerySpec opengpu.graphics.TileBinHostQueryCommandPortSpec opengpu.graphics.TileBinHostTransformFeedbackSpec opengpu.graphics.TileBinHostImageCommandPortSpec opengpu.graphics.NativeBinnedCompletionInfoSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationHostAxiCommandSpec opengpu.system.GpuHostAxiOwnershipSpec opengpu.system.NativeControlHostDmaSpec'
```
