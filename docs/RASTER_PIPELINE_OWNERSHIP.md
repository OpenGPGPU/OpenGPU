# Owned raster arithmetic and quad UVs

Raster attributes and programmable quad UVs travel through elastic pipelines
with their draw identity, coverage, sample mode and sample depths. Held outputs
retain all fields; completion waits for both arithmetic pipelines to drain
before another draw can replace their owner. Reset drops queued arithmetic and
permits healthy reuse.

Attribute interpolation preserves the existing modular color/depth arithmetic
and uses complete signed gradient sums before shifting. Quad UVs preserve
unsigned 32-bit inputs and helper-lane values. Both pipelines sustain one packet
per cycle after warm-up. Startup latency is 107 cycles for attributes and 146
for quad UVs; those costs still belong in workload budgets.

Sample thresholds are captured at setup with full signed precision. Coverage
uses balanced four-bit comparison groups, including sign, equality, winding
and top-left handling, without narrowing or changing the output protocol.

Validation on the composed source:

- 28 checks pass: 27 arithmetic/ownership/raster checks and one real-CU test
  containing six draws across 1x/2x/4x MSAA, pending owners and delayed replies.
- All 537 compiled suites are accounted for: 514 scheduled once and 23 existing
  desktop exclusions. The PR gate includes both small threshold suites.
- Four fresh emitted RTL maps match the frozen synthesis inputs exactly.
- Complete quad `RasterShader` synthesis passes 1000 ps TC/SLVT with 25% I/O,
  standard ABC, no retiming/false paths and zero skipped instances. Core/I/O
  slack is +49.400/+577.464 ps, versus the baseline's -15.739 ps core slack.
  Mapped library area is 315487.566, 0.86% below baseline, with 397252 flip-flops
  and no SRAM macro. This is a synthesis component result.
- Complete scalar `RasterShader` also passes the same 1000 ps setup constraints
  with +115.901/+583.025 ps core/I/O slack, complete mapping and zero skipped
  instances. Its qualified inputs match the fresh composed-source RTL map.

Whole-renderer/CU/host clocks, area/energy budgets, representative frame rates
and H0–H8 completion remain open. Detailed logs and manifests stay local.

Reproduce the directed checks with:

```sh
sbt 'testOnly opengpu.graphics.PreparedThresholdCoverageSpec opengpu.graphics.RasterThresholdSetupSpec'
sbt 'testOnly opengpu.graphics.RenderPipelineQuadUVOwnershipSpec'
```
