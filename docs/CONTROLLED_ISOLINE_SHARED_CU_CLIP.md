# Controlled isoline clipping on shared CUs

`ControlledIsolineSharedCuClip` feeds assembled control-patch evaluation output
into the existing clip stage. The owner retains the task and drains accepted
CU and clip work through completion; clipped output is provisional until the
completion succeeds. Patch storage may be released after evaluation because
clipping consumes copied primitive data. The completion records accepted,
clipped and culled primitive counts and reports cancellation or clip failure.

The bounded admission check runs in CI. The local real-CU qualification drives
control-provided levels and a coordinate shader through mapping and far-plane
line clipping, including cancellation and reuse:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.ControlledIsolineSharedCuClipQualificationSpec opengpu.system.ControlledIsolineSharedCuClipAdmissionSpec'
```

Arbitrary patch reads by shader code, a native TCS/TES ABI, other domains,
viewport/raster/image integration and product qualification remain open.
