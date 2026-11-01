# Native tessellation record memory routing

`TessellationNativeRecordMemoryFabric` merges the translated image owner's
physical line port with a separate Sv32 client for native draw-record reads.
Image transaction IDs retain their original range; record data and the record
page walker use two distinct IDs above it. A buffered round-robin request
arbiter preserves held offers. Image IDs and the record request stay owned until
their responses are consumed, including held responses and page faults.

The enclosing owner keeps `satp`, page tables and allocations stable until the
frame, record reader and this fabric drain. Record requests are read-only and
use local ID zero. The image input is already translated; this fabric does not
translate it again. The reader and frame owner still require descriptor and
writable-storage alias validation, reset integration, and native host ownership.

The bounded tests exercise concurrent physical image and non-identity translated
record requests, independent IDs, held requests and both held response ports,
page faults and drain. A composition test connects `TessellationNativeSequenceReader` and
fetches all 32 lines of a draw record through Sv32 while an image request shares
the physical port. This is not a complete native job or driver submission.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationNativeRecordMemoryFabricSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```
