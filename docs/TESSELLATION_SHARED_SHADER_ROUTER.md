# Tessellation control in the shared shader router

The optional tessellation sideband on `SharedShaderTaskRouter` snapshots warp
bases with the accepted local task, retains them across queued and stalled
launches, and selects them with the translated pool tag. Each CU's patch
accesses, parks, finishes, responses, resumes, cancellation and terminal signals
follow its current graphics owner. Clients retain their local tags, so equal
local tags from different clients do not alias. Capability-disabled routers
retain their existing interfaces.

`GeometryFragmentShaderRouter` can enable the sideband for its geometry client
while leaving the ordinary fragment interface unchanged. Its fragment branch
never offers tessellation bases or responses and asserts if control traffic is
attributed to a fragment task. The enclosing client must retain patch and
shader resources until the routed task completes and drain accepted traffic.
This routing capability does not by itself connect the system tessellation
producer to the fragment or image pipeline.

The tests cover queued launches with held bases, same local tags on two CUs,
access and response backpressure, barrier and finish traffic, cancellation,
terminal routing, reuse, and ordinary fragment isolation. Existing shared
router and geometry/fragment tests run with the capability disabled.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.SharedShaderTessellationRouterSpec opengpu.graphics.GeometryFragmentTessellationRouterSpec opengpu.graphics.SharedShaderTaskRouterSpec opengpu.graphics.GeometryFragmentShaderRouterSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```
