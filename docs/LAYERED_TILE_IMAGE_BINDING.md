# Layered tile image binding

`LayeredTileImageBinding` converts a flattened layered-bin tile index into a
layer, screen-space tile rectangle and selected single-layer image views. It
uses a shared iterative divider and processes attachment views sequentially.
The request and result remain owned through cancellation and backpressure.

For each used source or resolve view, the binder checks the frame extent, row
pitch, per-layer stride, the complete declared layer span and 32-bit address
limits before publishing the selected base and limit. Disabled views are left
unchanged. The image owner must still validate the rest of the image descriptor
and retain allocation provenance, including cross-attachment aliasing.

The bounded unit test covers partial edge tiles, independent source and
resolve strides, invalid spans, the 65,535-tile limit, the 4 GiB endpoint,
snapshotting and cancellation:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.LayeredTileImageBindingSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

This is an internal binding client. Connecting it to a layer-aware tile owner,
native descriptors and real multi-layer image publication remains necessary
for H8.

`LayeredReplayTileImagePipeline` combines the binder with a geometry-aware
replay/image engine. Invalid bindings and cancellation before image admission
complete without starting an image. The caller retains the engine credit and
allocation provenance until the held completion is consumed; cross-attachment
alias validation and native submission remain external.

The bounded `LayeredReplayTileAdmissionSpec` covers held admission, faults,
cancellation and reuse. A local 4×4 real-CU qualification binds and shades
two layer views:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.LayeredReplayTileAdmissionSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.LayeredReplayImageQualificationSpec -- -z "bind and shade"'
```

`LayeredBinTileImagePipeline` joins one bin replay stream to the image owner.
Its renderer permit remains low until the tile, image publication and held
report drain. `LayeredBinTileDispatch` routes engine-tagged completions to
independent owners and retains an invalid-engine protocol fault through drain.
The enclosing pass must route shared-CU and memory traffic and retain all
allocations until completion.

The bounded owner and dispatcher tests cover credit retention, cancellation,
faults and invalid engine tags. A local 4×4 real-CU test publishes two layer
views before returning ownership:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.LayeredBinTileOwnerSpec opengpu.graphics.LayeredBinTileDispatchSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.LayeredReplayImageQualificationSpec -- -z "publish each layered bin"'
```

`LayeredGeometryBinImagePipeline` connects geometry tasks, layered bins,
per-engine replay/image owners, shared-CU routing and memory arbitration.
Admission checks grid and identity, complete-directory mode, state and scratch
spans, and overlaps with bin and attachment allocations. The enclosing owner
must validate allocation provenance and cross-attachment aliasing and pin all
resources through completion. Native submission and physical qualification
remain open.

A local 4×4 real-CU qualification checks geometry binning, fragment shading,
layer-9 image publication, a record-write fault and healthy reuse:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryBinLayerImageQualificationSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryWindowBinAdmissionSpec'
```

`LayeredGeometryBinDrawImagePipeline` connects assembled primitive draws to
the geometry/bin/image owner. Its local 4×4 real-CU qualification sends a
triangle through geometry shading, layered bins, fragment shading and image
publication. Vertex execution and native submission remain separate work.

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryBinDrawImageQualificationSpec'
```
