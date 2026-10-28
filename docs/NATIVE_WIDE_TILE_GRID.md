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

Validation passes 25 hardware metadata cases: seven complete lookup cases,
sixteen complete native-reader cases, one eight-bank SRAM bitmap case and one
explicit full-capacity directory case. The full-capacity case initializes,
opens, commits and reports all 65,536 entries, checks exact page chains and
final metadata, holds completion under backpressure and then reuses the arena
for two entries. It completes in 4,608,010 cycles, followed by 133 reuse cycles.
All cases use the unmodified pinned SRAM model. Five configuration cases and
both ABI generator/check commands also pass. No pixels are rendered.

Paired complete host emissions each retain four CUs, 16 lanes per CU, eight
warps and four native engines, with 710 reachable RTL definitions. The default
host retains 16,384 tiles, GNC2 and 640×480 native bounds. The explicit
`mobile-native-four-engine-api-extent-host` profile uses 65,536 tiles, GNC3 and
2048×2048 native bounds while its display remains 640×480. Its real SRAM totals
3,600,384 bytes versus 3,594,240 bytes by default: six additional 256×32 report
banks cost 6 KiB. These are emitted SRAM capacities, not measured software
allocations or a timing result.

These results do not qualify a native frame using the full grid,
viewport/scissor execution, a promoted product renderbuffer limit, whole-host
clock or FPS. Those remain H6 requirements. The exhaustive directory case is
opt-in and excluded from ordinary discovery; run it locally, outside PR/nightly
lanes, with `-Dopengpu.apiExtentQualification=1`.

```sh
git submodule update --init depends/asap7_sram_0p0
sbt -J-Xmx4G -Dopengpu.simJobs=1 -Dopengpu.productQualification=0 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.TileGridDescriptorLookupSpec opengpu.graphics.NativeBinnedGraphicsJobReaderSpec opengpu.graphics.TileReportBitmapSpec'
sbt 'testOnly opengpu.graphics.NativeWideTileGridEncodingSpec opengpu.graphics.NativeRenderTargetExtentSpec' \
  'runMain opengpu.elaboration.EmitNativeBinnedGraphicsAbi --check' \
  'runMain opengpu.elaboration.EmitNativeBinnedHostAbi --check'
```

```sh
# Metadata only: all 65,536 entries, with real report SRAMs; no rendered frame.
sbt -J-Xmx4G -Dopengpu.apiExtentQualification=1 \
  -Dopengpu.simJobs=1 -Dopengpu.productQualification=0 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.NativeWideTileDirectoryCapacityQualification'
# Complete default/API host emission; generated files stay local.
sbt -J-Xmx6G -Dopengpu.apiExtentQualification=1 \
  -Dopengpu.productQualification=0 \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-native-four-engine-host .artifacts/wide-grid-default-host' \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-native-four-engine-api-extent-host .artifacts/wide-grid-api-host'
```
