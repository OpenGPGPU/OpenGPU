# Vertex-to-geometry bin ownership

The ingress binds vertex work and geometry draw ownership, preserving held
offers and source completion through cancellation. The terminal bridge drains
vertex work when a geometry pass ends before accepting a held draw. The shared
CU router preserves independent tags for vertex work and concurrent geometry
subtree tasks.

The bounded tests cover identity, backpressure, cancellation, terminal drain,
reuse and concurrent task routing:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.VertexGeometryBinDrawIngressSpec opengpu.graphics.VertexGeometryBinTerminalBridgeSpec opengpu.graphics.GeometryVertexShaderRouterSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

Full draw/image integration, native submission and product qualification
remain separate work.
