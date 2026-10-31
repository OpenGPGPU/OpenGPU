# Controlled isoline resident tile

`ControlledIsolineTypedTilePipeline` connects controlled-patch fragment output
to a resident typed pixel backend. The owner retains the task and services
control-patch traffic during the draw. The tile becomes available for external
maintenance only after shader output and pixel writes drain; the owner closes
the pass after maintenance. Output is provisional until completion succeeds.

Routine CI selects bounded tile and fragment admission checks. The local
real-CU qualification uses a 4×4 tile, reads stored color words and checks
maintenance/end ordering:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.ControlledIsolineTypedTileAdmissionSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.ControlledIsolineTypedTileQualificationSpec'
```

Backing-image publication, native shader access to arbitrary patch data, the
TCS/TES ABI, other tessellation domains and product qualification remain open.
