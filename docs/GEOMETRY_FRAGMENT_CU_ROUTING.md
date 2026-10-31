# Geometry and fragment CU routing

`GeometryFragmentShaderRouter` shares a geometry-capable CU pool between one
geometry and one fragment client. Task ownership, texture traffic, traps and
payload bindings follow the shared task router; geometry sidebands are exposed
only to the geometry client. The bounded test checks held launches, per-client
payload capture, CU ownership, geometry events and reordered completions:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryFragmentShaderRouterSpec'
```

The enclosing draw owner, native submission and full H8 qualification remain
open.
