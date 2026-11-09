# Primitive bounding box state and conservative culling

Status: standalone integrated component with directed tests on local main. It
implements the `EXT_primitive_bounding_box`/GLES 3.2 state and a conservative
clip-space cull hint. It is not wired into the geometry or raster command path
and does not claim full §13.2 semantics.

## Contract

- The state is eight clip-space floats: minimum and maximum X/Y/Z/W. The
  default `(-1,-1,-1,1)` to `(1,1,1,1)` covers the full clip volume.
- Values are not clamped to any fixed range. They must be finite, and each
  minimum must be less than or equal to its maximum; otherwise the load is
  rejected and the published box is marked invalid.
- A load may replace both planes with a tessellation control patch override.
  The caller supplies a complete pair; partial component writes are not
  represented here.
- `PrimitiveClipBounds` computes the component-wise minimum and maximum of a
  triangle's clip positions and marks the result unusable when any component
  is NaN.
- `PrimitiveBoundingBoxCull` reports culling only when the published box is
  valid, the primitive bounds are usable, and the two are disjoint on at least
  one of X, Y, Z or W. Straddling primitives and invalid or unusable inputs are
  never culled, so retaining fragments outside the hint stays legal.
- Results and published state are held; loads under backpressure remain
  stable.

## Window-space bounds

`PrimitiveBoundingBoxWindow` projects the sixteen min/max box vertices by
`x/w` and `y/w` and applies the GL viewport transform with a fused
multiply-add, in GL window coordinates with Y upward. When every `w` is
positive the reported X/Y minima and maxima are floored and ceiled to fragment
boundaries, with half the primitive size added or subtracted: half the point
size for points, half the rounded line width for lines and zero for triangles.
If any `w` is not positive, or a projection is non-finite, the owner reports
the full viewport and clears `exact`; the hint then culls nothing. Inputs are
validated (box state, viewport, and point/line size) before any arithmetic.

## Directed evidence

`PrimitiveBoundingBoxSpec` passes two cases and
`PrimitiveBoundingBoxWindowSpec` passes two:

- the default full-frustum box, a wide replacement box, a patch override,
  minimum-greater-than-maximum, non-finite values and an unsupported version,
  with held results;
- exact component bounds for a mixed-sign triangle, NaN unusability, culling
  for disjoint X and W ranges, retention of a straddling primitive, and
  refusal to cull for unusable bounds or an invalid box.

```sh
sbt 'testOnly opengpu.graphics.PrimitiveBoundingBoxSpec'
```

## Tessellation primitive union

`PrimitiveBoundingBoxUnionUnit` unions up to eight tessellation-primitive
clip-space boxes. Valid boxes contribute componentwise floating-point minima
and maxima; an empty valid set reports an empty result. NaN in a contributing
box, a count outside 1..8, or valid bits above the count reject the request.
200 random finite sets with infinities plus NaN/empty/malformed cases cover the
union (`BOUNDING_BOX_UNION`).

## Remaining work

True clipping of the sixteen-vertex volume against `w>0` (edge intersections
beyond the conservative full-viewport fallback), tessellation control
`gl_BoundingBox` write linkage, component-limit accounting, command/API
exposure and product qualification remain open.
