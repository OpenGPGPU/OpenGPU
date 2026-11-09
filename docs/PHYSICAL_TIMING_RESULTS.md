# Current physical timing results, 2026-11-10

Local ASAP7 TC/SLVT 1 GHz post-route runs using the local
`chipagent/openroad:arm64` image, fresh RTL emitted from `origin/main`
(`5a192dc7`), Yosys synthesis and global route with I/O included.

| Block | Verdict | Setup slack | Hold slack | Fmax |
|---|---|---:|---:|---:|
| `TriangleRasterizer` (`raster-quad`) | PASS | +91.98 ps | +28.82 ps | 1227.44 MHz |
| `ScalarBackend` (`scalar-backend`) | PASS | +0.56 ps | +22.12 ps | 1212.75 MHz |

Both pass the post-route gate: setup/hold violations, DRC, antenna, placement,
flow errors and synthesis mapping all clean. Results:
`.artifacts/ppa-live-raster-route/result.json` and
`.artifacts/ppa-live-scalar-route/result.json`.

`SharedL2Slice` and `VectorBackend` route attempts were stopped after they did
not converge within the two-hour window (L2 remained in detailed route with
~475 violations; VectorBackend placement WNS was ~-410 ps). They are not pass
results.

## Whole-host status

`GpuHostSystemAxi` flat synthesis exceeded the 29 GiB Docker limit at the
FLATTEN/OPT stage (peak 28.8 GiB, exit 247). Hierarchy-preserving synthesis
continued for over four hours in Yosys optimization and had not produced a
linked timing report when this note was written; whole-host route has not run.
Whole-host physical timing therefore remains open and needs a partitioned or
blackboxed flow with a larger memory/time budget.
