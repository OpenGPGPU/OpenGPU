# Raster depth-resolution witness

[GLES 3.0.6 section 3.6.2](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf#page=120)
defines depth resolution through polygon rasterization and depth-buffer
conversion. The existing add/encode envelope is only part of that requirement.

A scalar binary32 replay of the current weighted-depth sequence finds a
constant-depth witness with eight subpixel bits:

- Vertices: `(-1239639,-17329276)`, `(2479278,-17329276)`,
  `(-1239639,34658552)`.
- Signed area: `193338417342276`; integer spans and area fit the existing
  triangle admission bounds.
- At pixel `(0,0)`, center edges are `64439008650732`, `64452793556076`,
  `64446615135468`.
- All three vertex depths are either `0x3f000001` or `0x3f000002`. Their
  difference is `0x33800000` (`2^-24`), one resolution step with the same
  binary32 exponent on both triangles.
- The software replay produces `0x3f000001` for both center depths.

The replay rounds edge conversion, each product, each sum and division
separately. The [actual RTL run](performance/mobile-depth-resolution-raster-failure-2026-10-20.json)
reproduces the collapse: 0/1 tests pass, and both observed center depths are
`0x3f000001`. The failing pair is retained on an unchanged source snapshot.
The new `Fp32DepthResolutionRasterSpec` uses actual primitive coverage,
single-pixel scissoring, immutable held quads and healthy reuse. It requires
distinct center depth and exact constant center/helper/covered-sample depth in
both windings and all three sample modes. No offset or clamping is involved.

The frozen 640-file reproduction preserves all combined production/ABI bytes.
RTL reproduction confirms the first pair; later windings/sample modes are not
reached by that failing run. No passing precision or full G04/H6 result is
claimed. Any arithmetic correction must rerun the relevant numeric, ownership,
image and combined-source checks before integration.

A second software witness uses integer-pixel vertices `(-262,-131)`,
`(524,-131)`, `(-262,262)`, fitting a viewport rectangle of 786x393 pixels.
Its scalar replay collapses the same source pair to `0x3f000002`. The expanded
RTL test tries this smaller geometry first, then the retained large-edge case;
its second frozen reproduction is pending.

The implemented candidate correction anchors depth at vertex zero:
`z = z0 + (e1*(z1-z0) + e2*(z2-z0))/area`. This avoids repeatedly multiplying
and summing the common depth term. Two differences are prepared once per quad
through the existing scalar service; center, covered samples and centroid
fallback share that depth path. Offset-before-clamp and cancellation ownership
remain in the same owner. The analogous anchored slope calculation still needs
checking. Focused precision, numeric/ownership and integrated qualification of
this correction are pending; the negative result does not qualify it.
