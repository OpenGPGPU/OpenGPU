# Geometry primitive input mapping

`GeometryPrimitiveInput` turns an assembled point, line or triangle into one geometry invocation task. A linked component map selects each active input from four clip-position words and the source varying words; any 64 source words can be selected. The adapter snapshots the primitive and map, copies one word per cycle, and retains the completed task under backpressure. Invalid vertex counts, varying bounds, component counts or source indices produce a held fault instead. Input primitive order is preserved by accepting the next primitive only after the current task or fault is consumed.

The 64-component ceiling matches the minimum geometry input limit recorded in the [OpenGL ES 3.2 specification](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf). The link is responsible for supplying the active component map and retaining draw identity and other primitive metadata until downstream ownership ends. The maximum copy time is 192 cycles for a triangle with 64 components per vertex, excluding stalls.

`GeometryPrimitiveInputSpec` checks all 64 mapped words for points, lines and triangles, changed inputs after acceptance, held tasks, invalid sources and empty input. A system composition test sends a mapped triangle through the adapter and scheduler into a real shared CU, and checks two ordered invocations with held output. Run:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryPrimitiveInputSpec' \
  'testOnly opengpu.system.GeometrySharedCuSchedulerSpec -- -z mapped'
python3 scripts/test_test_selection.py
```

This is an internal adapter and is not connected to a production draw. Adjacency input assembly, draw cancellation, output mapping, transform feedback, binning and host/API integration remain open; this does not close H8.
