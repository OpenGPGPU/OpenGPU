# Tessellation pre-clip feedback owner

`TessellationSystemIsolineViewport` can optionally place the existing feedback
export owner between TES primitive mapping and clipping. The mapper retains
raw per-vertex varyings for capture; the export owner applies the requested
flat shading only to the downstream primitive. The task's feedback scope stays
pinned through acknowledged stores and completion. Raster discard suppresses
raster output while retaining successful capture. Feedback faults use viewport
status 5 and retain their original status in `feedbackStatus`.

A real shared-CU control/evaluation test checks exact raw capture before flat
shading and clipping, held store acknowledgements, scope retention, raster
discard, memory faults, task cancellation and object reset. Simultaneous fault
and cancellation/reset retain the primary memory fault; each interrupted or
faulted case completes a healthy retry. The existing mapper and viewport tests
also pass with feedback disabled. Reproduce with:

```sh
sbt -J-Xmx6G -Dopengpu.simJobs=4 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simHierarchy=GpuComputeUnit \
  'testOnly opengpu.graphics.TessellationIsolinePrimitiveMapperSpec opengpu.system.TessellationSystemIsolineViewportSpec opengpu.system.TessellationSystemIsolineFeedbackSpec'
```

The opt-in bin pass forwards feedback scope and memory ownership through each
task and fences object commands until the pass drains. Real shared-CU bin tests
pass capture, raster discard, memory fault, task cancellation and global reset,
including simultaneous faults and a healthy retry after each failed pass. They
also check exact captured words, replay behavior, held acknowledgements and
feedback-control fencing. Reproduce with the same sbt options and
`testOnly opengpu.system.TessellationSystemIsolineBinPassSpec opengpu.system.TessellationSystemIsolineFeedbackBinSpec`.

The opt-in image pipeline also pins each task's feedback scope and fences
feedback object commands until the frame drains. Cancellation-induced bin
errors do not replace the original task fault, and feedback control remains
usable after a failed frame. The local shared-system image test exercises
capture, discard, memory fault, reset, and simultaneous reset/fault, while the
bounded admission test checks a held feedback-control reply. Reproduce with the
same sbt options and
`testOnly opengpu.graphics.TessellationSystemIsolineImageSharedSystemSpec opengpu.graphics.TessellationSystemIsolineImageAdmissionSpec`.
The shared-system image suite remains local-only because of its full GPU model.

The opt-in GPU-backed patch input owner pins the feedback scope as the task is
accepted, fences feedback commands for the frame, and propagates feedback reset
through its input DMA. A primary feedback memory fault retains status 5 over a
simultaneous reset. The local two-CU input-image test exercises GPU input reads,
feedback capture, fault and reset, held control commands and scope retention;
the existing input-fault and cancellation recovery cases remain in the same
suite. Run it with the options above and
`testOnly opengpu.graphics.TessellationSystemIsolineInputImageSharedSpec opengpu.graphics.TessellationSystemIsolineInputImageAdmissionSpec`.
The shared-system suite remains local-only because of its full GPU model.

The opt-in patch sequence owner retains the draw's feedback scope across all
patches and fences object commands until the frame ends. A feedback reset is
held until a pending patch offer has been accepted or the sequence has stopped;
the sequence reports a primary feedback memory fault ahead of cancellation.
The local two-CU sequence test checks exact capture across two GPU-backed
patches, feedback fault and reset races, and reset during a held patch offer.
Reproduce with the same sbt options and
`testOnly opengpu.system.TessellationPatchSequenceSpec opengpu.graphics.TessellationSequenceImageSharedSpec opengpu.graphics.TessellationSequenceImageAdmissionSpec`.
The shared-system sequence suite remains local-only.

The translated native sequence owner pins feedback scope at submit, before
reading the draw record, and defers reset until the reader's held draw can
retire. The reader preserves a primary feedback fault over cancellation. A
local two-CU test checks capture and fault/reset races while record, patch input
and image traffic use Sv32 translation. Feedback stores remain a separate
client carrying their pinned root to the enclosing translator; the test checks
that root and the captured words, not translation of that separate client.
Reproduce with the options above and
`testOnly opengpu.system.TessellationNativeSequenceReaderSpec opengpu.graphics.TessellationNativeSequenceImageSharedSpec`.
The shared-system suite remains local-only.

This remains an internal interface. It is not connected through the native job
owner, host or driver, does not establish physical alias provenance, and does
not qualify product performance. Those and the broader mobile GPU alignment
requirements remain open.
