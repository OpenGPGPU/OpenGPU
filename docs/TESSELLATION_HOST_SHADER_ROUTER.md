# Internal host shader-pool adapter

`TessellationHostShaderRouter` adapts the existing native renderer's ordinary
graphics port and the tessellation job owner's geometry/tessellation port to
one tagged shared-CU pool. The existing router retains task IDs, traps, texture
traffic, invalidations and optional payload leases. Legacy launches always
carry disabled geometry and tessellation sidebands; unexpected patch or
geometry traffic from a legacy-owned CU asserts. Tessellation traffic follows
the accepted task owner, including held responses and patch bases.

The focused test exercises two simultaneous clients with equal local IDs,
distinct payload leases, held launches, tessellation patch traffic and
reordered completions. This is an internal component, not a connected host:
the full system must still wire the job owner, memory bridge, payload pins,
VM/reset drain and command path and qualify their combined behavior and timing.
The focused adapter and existing tessellation/payload-router suites pass five
tests, including one-, two- and four-CU payload configurations.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationHostShaderRouterSpec opengpu.graphics.SharedShaderTessellationRouterSpec opengpu.graphics.SharedShaderPayloadRouterSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```
