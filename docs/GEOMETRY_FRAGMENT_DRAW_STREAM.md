# Geometry-to-fragment draw stream

`GeometryFragmentDrawStream` owns a draw descriptor across a stream of
assembled primitives. It serializes each primitive into the existing
one-primitive geometry/fragment interface, carries the returned geometry
sequence forward, and assigns draw-wide fragment-quad sequence numbers.
Completion retains the source report, primitive and quad counts, and the first
reported status until consumed.

An offered child draw remains stable until accepted even if cancellation
arrives; cancellation then reaches the child and the primitive source. The
source stream is drained, accepted child output is retained through
backpressure, and a source fault takes precedence over the resulting child
cancellation. A mismatched source identity or primitive count fails the draw.

Run the bounded controller tests:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 'testOnly opengpu.graphics.GeometryFragmentDrawStreamSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

The controller exposes the child interface and is not yet composed with the
real CU pipeline, native draw submission or attachment ownership. H8 remains
open; no product frame-rate or physical timing result is implied.
