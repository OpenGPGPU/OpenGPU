# Triangle tessellation ring strip

`TessellationTriangleStrip` streams triangles between two ordered sides of a
triangle-domain ring. The caller supplies barycentric Q0.24 vertices, including
both endpoints of each side. The outer side has 1–64 segments; the inner side
has 0–64. A zero-segment inner side supplies one vertex and creates a fan.
The caller must provide consistently ordered, non-crossing sides whose strip
polygon is convex, with the inner side to the left of the counter-clockwise
outer side. The module does not validate coordinates or construct the rings.

The block advances the side with the smaller normalized segment progress and
emits one triangle per segment, preserving task ownership and sequence numbers.
It supports either winding and holds an offered triangle until accepted, even
when cancellation arrives. Cancellation then completes with the number of
triangles already accepted. Invalid segment counts complete without output.

The directed simulation checks nine segment-count pairs, three barycentric
rotations and both windings, including unequal counts and the 64-segment limit.
It checks boundary edges, signed area, coordinate sums, backpressure,
cancellation, invalid counts and reuse:

```sh
sbt -Dopengpu.productQualification=0 'testOnly opengpu.graphics.TessellationTriangleStripSpec'
```

This primitive alone does not implement a triangle-domain primitive generator,
shader execution or an end-to-end rendering path.
