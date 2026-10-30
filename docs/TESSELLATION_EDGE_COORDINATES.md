# Tessellation edge coordinates

`TessellationEdgeCoordinate` converts a positive prepared FP32 level, spacing,
index and optional inner-level promotion into a deterministic Q0.24 edge
coordinate. Equal spacing uses equal segments. Fractional spacing places the
two shorter segments at opposite ends, with the other segments equal; the
clamped FP32 level determines their lengths. A bounded iterative divider rounds
to nearest, ties to even. Endpoints are exact, and a held response survives
backpressure. Cancellation is reported after accepted work drains.

The unit test compares more than 700 directed and randomized coordinates to an
independent exact-rational reference, including all indices of boundary levels,
inner-level promotion, invalid requests and cancellation:

```sh
sbt 'testOnly opengpu.graphics.TessellationEdgeCoordinateSpec'
```

The implementation uses 24 divider cycles for an interior coordinate, in
addition to level preparation. It is an internal building block: patch-domain
primitive construction, FP32 shader input conversion, throughput qualification
and H8 completion remain open. The [OpenGL ES 3.2 specification, §11.2.2](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf)
defines tessellation edge spacing and invariance requirements.
