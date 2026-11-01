# Translated tessellation job memory

`TessellationNativeJobMemoryFabric` shares a physical memory port between the
already-translated image owner and the four-tag GNQ1/GNF1 job reader. Image IDs
are unchanged; four disjoint IDs carry translated job data and one carries its
Sv32 page walker. Both clients retain their IDs through held requests and
responses, including translation faults. The caller retains `satp`, mappings,
and physical allocations until the clients and fabric drain.

This component does not yet connect the complete job reader, image pipeline,
shared CUs and host command interface into a single native submission path.
Physical alias provenance remains the caller's responsibility.

`TessellationHostMemoryFabric` provides the next physical-memory boundary: it
merges the existing graphics-host L2 client and an already-translated
tessellation client into disjoint ID ranges. It preserves request fields and
returns each response to its owner, including under backpressure; an ID cannot
be reused before the client consumes its reply. Its drain indication covers
both clients and queued requests. This bridge is an internal component: host
command admission, actual host wiring, reset/VM ownership and physical alias
provenance still need integration and end-to-end qualification.

Focused simulations cover same-numbered IDs from both clients, out-of-order
responses, held requests and fault replies, ID reuse, single-ID clients and
an asymmetric range requiring an ID carry bit.
No timing or product-capacity qualification is implied.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationHostMemoryFabricSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

Three tests pass: concurrent image and four-tag job traffic with successful and
faulting translations, held request/response ownership, and an actual job
reader fetching a complete job and frame through nonidentity Sv32 mapping.
The CI test-selection checks also pass.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationNativeJobMemoryFabricSpec'
python3 scripts/test_test_selection.py
```
