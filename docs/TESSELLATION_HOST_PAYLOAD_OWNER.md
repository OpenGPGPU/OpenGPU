# Internal host payload-producer handoff

`TessellationHostPayloadOwner` selects either the legacy or tessellation
renderer for the shared pool's existing producer ports. It retains that choice
until accepted control and data replies are consumed and every successful
allocation has a successful release reply. It counts allocations and releases
rather than tracking just slot bits: the pool may reuse a freed slot while its
previous release acknowledgment is still held. Spill and cancel replies do not
release a resident lease. Data and control response ownership is checked against
the payload store's per-port reply capacities.

The owner must have exclusive use of these producer ports from reset. The
enclosing system may request handoff only after the outgoing renderer and its
shared-CU router have stopped producing work; the owner itself checks its
producer offers, replies and lease count before accepting handoff. The module
does not wire the renderers, pin the VM, qualify aliasing, or establish full
host reset behavior, product throughput or physical timing.

Two tests pass: one uses the real banked store and reuses its sole slot while
an older release reply is held; the other exercises reordered replies, spill,
cancel, held data faults and isolation between the two producers.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationHostPayloadOwnerSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```
