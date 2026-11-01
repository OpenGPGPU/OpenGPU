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
