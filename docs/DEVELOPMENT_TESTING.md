# Development test size and product qualification

## PR validation

Open PRs targeting `main`, including drafts, run the same bounded checks with a
15-minute job limit. Scala/build/CI changes compile all sources and execute the
selected suites from the fast PR catalog; documentation-only changes run host
script checks. The job summary reports the executed test and suite counts.
Zero-test, incomplete, failed, canceled or pending ScalaTest runs fail validation.

The required `ci-ok` gate also checks that the PR is ready for review and that
the tested head is still current. Drafts therefore receive test feedback while
remaining ineligible to merge. Marking a PR ready triggers fresh validation.
Broad integration regressions run in the separate regression workflow.

## Frame sizes

Routine rendering tests use **640×480 or smaller**. Prefer small tiles and images
for shader, format, fault, ownership and reset checks. Full-frame regressions
use `GraphicsTestResolution`: the default is 640×480, or 1200 tiles at 16×16,
instead of 3600 tiles at 1280×720. Complete image, byte-count, continuation and
two-engine assertions still apply to every tested pixel and tile.

The normal PR and nightly workflows set `-Dopengpu.productQualification=0`.
They retain their existing suite catalogs. Product RTL capacities, Mobile
resource budgets and the 720p product acceptance target remain unchanged.
Historical 720p measurements keep their original workload scope; VGA cycle
counts cannot be presented as new 720p results.

For example, run a default frame check with:

```sh
sbt 'testOnly opengpu.graphics.TileBinRenderPipelineSpec -- -z "clear and store a complete 640x480 frame"'
```

Run 720p deliberately outside the routine CI lanes:

```sh
sbt -Dopengpu.productQualification=1 'testOnly opengpu.graphics.TileBinRenderPipelineSpec -- -z "1280x720"'
```

The same switch selects the full-frame grid, directory, scissor, traversal and
persistent-tile viewport fixtures. It does not change production defaults.
Test names and measurement logs include the selected full-frame size.

Large scalar coordinates, texture dimensions, address limits and synthetic
metadata indices still belong in bounded boundary tests. For example, report
bits on either side of index 8192 must exercise both SRAM banks even though
a VGA 8×8 grid contains only 4800 tiles. Such checks do not render or traverse
an oversized framebuffer.
