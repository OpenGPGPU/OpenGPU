# Isoline typed-image integration

`IsolineTypedImagePipeline` connects one isoline patch and one resident typed
pixel tile to `TypedTileImageTransfer`. The owner validates the tile draw,
requires identical tile descriptors at both boundaries, and rejects overlap
between fragment scratch and the allocations of active attachment or resolve
views before starting either child. The transfer validates image views before
binding the tile. Refill traffic, shader traffic, tile maintenance and backing
store responses retain ownership until completion; the owner closes the tile
only after a successful transfer and drains outstanding memory responses before
reporting completion.

The external owner must retain the shader and other resources for the draw and
ensure that they do not alias the backing views. Cancellation or a later store
fault can leave earlier physical writes visible: backing image publication is
not transactional. This path does not implement native/API submission, general
patch input access, other tessellation domains, or product throughput; H8 and
product qualification remain open.

Run the bounded allocation and CI-selection checks with:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolineImageScratchHazardSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

The local-only 4×4 real-CU qualification checks successful backing writes,
overlap rejection, cancellation under held output and a failed store response:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolineTypedImageQualificationSpec'
```
