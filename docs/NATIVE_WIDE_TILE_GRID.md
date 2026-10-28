# Versioned native tile counts

Native frame payload version 2 uses all 32 bits of the existing tile-count word
55, with an exclusive count up to 65,536. Version 1 retains its 16-bit value and
requires zero high bits. Record sizes, other offsets, the published v1 JSON and
all existing C constants remain unchanged. New header constants describe the
v2 override; `native-binned-graphics-v2.json` extends the v1 schema.

A native configuration with `pagedCapacity=65536` accepts payload v2 and reports
GNC3 for virtual images or GNC4 for physical images. Other configurations retain
GNC2/GNC1 and reject v2 before offering a child frame. Version 1 remains usable
in a wide configuration. Zero or oversized v2 counts and noncanonical v1 high
bits are rejected before child work; held descriptors retain their captured
count even if backing memory changes.

The directory accepts the full count, uses 17-bit initialization/clear/opened
bookkeeping at that capacity and returns a 17-bit opened count. The report
bitmap uses eight real 256×32 SRAM banks for 65,536 bits. Lookup rejects grids
larger than 65,536, retains a 16-bit ordinal divider and admits the highest legal
ordinal, 65,535. Resident attachment capacity is unchanged.

Validation passes 24 hardware metadata cases: seven complete lookup cases,
sixteen complete native-reader cases and one eight-bank SRAM bitmap case. They
include selected 2048/2047-axis corner ordinals, malformed oversized grids,
legacy and v2 snapshots, padding rejection, ordering/cancellation/held outputs,
healthy reuse and independent boundary bits through the unmodified pinned SRAM
model. One configuration case and both ABI generator/check commands also pass.
No large image is rendered or traversed by these metadata checks.

These results do not qualify a full 65,536-entry initialization/completion,
a native frame using that grid, viewport/scissor execution, a promoted product
renderbuffer limit, whole-host clock or FPS. Those remain H6 requirements.

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx4G -Dopengpu.simJobs=1 -Dopengpu.productQualification=0 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TileGridDescriptorLookupSpec opengpu.graphics.NativeBinnedGraphicsJobReaderSpec opengpu.graphics.TileReportBitmapSpec'
sbt 'testOnly opengpu.graphics.NativeWideTileGridEncodingSpec' \
  'runMain opengpu.elaboration.EmitNativeBinnedGraphicsAbi --check' \
  'runMain opengpu.elaboration.EmitNativeBinnedHostAbi --check'
```
