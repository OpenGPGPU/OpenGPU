# Geometry replay raster client

`GeometryReplayRasterClient` rasterizes a retained geometry bin record within
the supplied tile rectangle, intersecting it with the draw scissor and screen
bounds. It validates the replay identity, state handle, token and sequence,
requires geometry metadata, and carries input primitive ID, invocation and
layer with every held output quad. The primitive ID used by fragment inputs
remains the geometry shader's emitted primitive ID. The raster completion can
feed `GeometryFragmentIngress`; the upstream geometry source summary is not
present in a stored record and remains zero in this completion.

The focused test compares both halves of an 8×8 tile split and a clipped
negative-origin tile against the raster reference. It also checks descriptor
rejection, output backpressure, cancellation and healthy reuse:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryReplayRasterClientSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

The client is a replay handoff; native pass submission, the production image
owner and layered attachment selection remain open. H8 is not complete.
