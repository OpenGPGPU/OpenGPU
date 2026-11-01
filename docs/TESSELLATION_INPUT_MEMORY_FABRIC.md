# Translated tessellation input memory

`TessellationInputMemoryFabric` combines the renderer's line requests with the
read-only patch-input DMA port through the existing `TileBinHostMemoryFabric`.
The ports use independent Sv32 translation clients, response ownership and page
walker IDs. Patch input uses local transaction ID zero; render IDs retain their
original values at the renderer port. The shared physical port uses the host
fabric's disjoint ID ranges and buffered request arbitration.

The enclosing owner retains the address space, mappings and allocations until
`drained` and all frame work complete. A held response keeps the fabric
undrained; translation faults return to the requesting client. Input allocation
bounds are checked by `TessellationPatchInputDma` before traffic. The fabric
does not establish native submission, physical alias provenance or reset
integration, and it does not replace the caller's ownership rules.

The focused tests cover concurrent Sv32 translation, independent page walks,
reordered replies, a stalled input response, input-page faults and drain. They
also connect the actual patch-input DMA and verify a GPU virtual input word is
loaded into the patch-access port through the translated physical request.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationInputMemoryFabricSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```
