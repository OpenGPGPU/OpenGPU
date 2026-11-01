# Input-backed tessellation patch sequence

`TessellationPatchSequence` issues serial input-backed patch tasks from one
caller-supplied template and a patch count. It advances the input base by a
byte stride and the patch task ID by one, retaining the draw token. Before
issuing any task, it checks layout limits, the complete final input extent,
allocation and alignment, and task ID and address overflow. A zero count is
an empty sequence. A zero patch stride repeats the same input.

A task offer remains stable until accepted, including during cancellation.
The frame owner forwards `childCancel` to the image frame and keeps translation
and allocations alive until both sequence and frame complete. When the image
frame has reached terminal completion and cannot accept further tasks, the
owner asserts `frameStopped`; this explicitly flushes an unaccepted offer and
reports status 1 (or 4 if cancelled). An accepted child must instead report
completion before the sequence completes. Completion records the number of
successful patches, the failed index, and the most recent child result; status
1 denotes invalid sequence or a stopped frame, while other nonzero child
statuses and cancellation propagate. The enclosing frame completion retains
the detailed frame failure. Provisional output must be drained.

The bounded sequencer test covers multi-patch strides, the last word at 4 GiB,
overflow, empty draws, held offers, cancellation, child failure, terminal-frame
flush, completion backpressure and reuse. This is a task producer, not yet a
native command ABI or an integrated native draw submission path. Other domains,
compiler integration and product qualification remain open.

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationPatchSequenceSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```
