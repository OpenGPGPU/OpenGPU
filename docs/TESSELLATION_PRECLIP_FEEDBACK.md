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

This is a stage-level opt-in interface. It is not connected to the native host
or driver, does not establish physical alias provenance, and does not qualify
transform-feedback coexistence or product performance. Those and the broader
mobile GPU alignment requirements remain open.
