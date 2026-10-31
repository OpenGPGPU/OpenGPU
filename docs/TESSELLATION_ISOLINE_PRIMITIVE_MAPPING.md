# Mapping evaluated isolines to renderer primitives

`TessellationIsolinePrimitiveMapper` maps an evaluated point or line's linked
TES output words to the existing `AssembledPrimitive` format. It copies clip
position and active varying components, applies flat values from the provoking
vertex, and optionally maps a point-size word. The caller supplies draw
identity, instance, primitive ID and a unique draw sequence. Invalid output
indices, point-size indices or varying masks produce a held fault instead of a
primitive. Outputs remain provisional until the enclosing patch reports
success; its owner must discard them on cancellation or failure.

The focused test checks line and point metadata, flat and non-flat varyings,
point size, held output and invalid linkage:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TessellationIsolinePrimitiveMapperSpec'
```

This mapper does not run a shader, allocate unique draw IDs, implement native
submission, or prove TES-to-raster integration. Shared-CU evaluation, patch
access, remaining tessellation domains and product qualification remain open.
