# Native API extent qualification inputs

`NativeProductFrameInputs.encodeApiExtent` prepares a version-2 native frame
with eight-pixel tiles, a maximum 2048×2048 extent and a 65,536-entry directory.
The directory has 4 MiB of backing. Its bin arena reserves 512 MiB of external
GPU address space starting at 0x40000000,
providing space for a 4 KiB page per tile without crossing the fixed 128 KiB
shared-memory aperture. Image, resolve, shader and scratch allocations retain
independent bounds and pitched guards. Allocation fills use sparse memory;
the address reservation is not on-chip SRAM or an upfront host allocation.
Encoding does not render pixels or traverse the image.

Seven CPU cases pass, including the full tile-count word, final image address,
source/resolve allocation separation and rejected oversized dimensions. All six
existing 32×32 recipe input snapshots remain byte-identical. The ordinary
`encode` entry point retains its version-1, sixteen-pixel-tile layout.

```sh
sbt 'testOnly opengpu.graphics.NativeApiExtentEncodingSpec opengpu.graphics.NativeProductFrameInputsSpec'
```

The replay wrapper accepts a 65,536-tile descriptor only when its paged
directory has that capacity. Other profiles retain the 65,535 limit. Four
bounded hardware cases check child admission and malformed counts; five
existing 20×17 replay cases pass, including exact records, credits, faults and
cancellation. These checks render no pixels and do not qualify a complete API
frame.

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'testOnly opengpu.graphics.WindowPrimitiveBinReplayGridAdmissionSpec opengpu.graphics.WindowPrimitiveBinReplayPassSpec'
```

Full-capacity host execution and independent stored-image verification are
required before this becomes a native extent qualification. Actual 2048-axis
viewport/scissor execution, format/sample
combinations, whole-host clock, 720p frame rates and H6 acceptance remain open.
Keep generated inputs, snapshots, logs and intermediate reports in `.artifacts/`.

The boundary recipe now places two triangles at the upper-right corner and
scissors them to pixel (2047, 2047), with an exclusive maximum of 2048 on both
axes. An explicit API oracle checks RGBA8 and quantized D24S8 at 1×/4× samples.
It retains the ordinary reference's extent limit and uses the existing clipping,
independent edge equations, ordered depth/blend references and pitched guards.
Empty reference bins use the recipe's specified clear values.

Eighteen CPU checks pass across the API encoding, boundary/oracle, existing
input and existing image-reference suites. They include a bounded 16×16 complete
stored-image comparison with corrupted covered/uncovered samples and guards;
the six ordinary input snapshots remain byte-identical. These are CPU checks,
not execution of a 2048×2048 GPU frame.

```sh
sbt 'testOnly opengpu.graphics.NativeApiBoundaryFrameSpec opengpu.graphics.NativeApiExtentEncodingSpec opengpu.graphics.NativeProductFrameInputsSpec opengpu.graphics.NativeProductFrameReferenceSpec'
```

The explicit compiled API runner emits four 16-lane/eight-warp CUs, four tile
engines, the 512 KiB payload pool and characterized SRAM models. Native target
bounds are 2048×2048 with 65,536 directory entries; display storage stays at
640×480. Its smoke mode uses 32×32 1×/4× frames while retaining those hardware
capacities. Its separate product mode uses 2048×2048 frames. Both modes check
GCR1/GNC3 discovery and drain external AXI traffic before the independent oracle
checks every stored color/depth sample, resolve value and pitched guard byte.
Preparation, build and execution records bind the exact source, RTL, fixtures,
driver and mode. Smoke results cannot satisfy the product-mode guards.
Execution alone cannot produce a successful qualification record.

```sh
python3 -m unittest discover -s scripts -p 'test_qualify_native_*compiled.py'
python3 scripts/qualify_native_api_compiled.py --smoke \
  --hierarchy-profile fp32 --jobs 2 --output .artifacts/native-api-smoke
# Explicit product qualification, outside routine test lanes:
python3 scripts/qualify_native_api_compiled.py --api-extent-product \
  --hierarchy-profile fp32 --jobs 2 --output .artifacts/native-api-compiled
```

Both commands run locally outside regular PR/nightly lanes and require the
full host resource budget. The Scala entry points require
`opengpu.fullHostQualification=1` and `opengpu.apiExtentQualification=1`;
`opengpu.productQualification` must match the selected mode. CPU parser and
guard tests render no pixels. An earlier 2048×2048 attempt failed before
rendering because the replay wrapper rejected the complete tile grid; that
admission limit was subsequently repaired. No completed 2048×2048 GPU frame,
whole-host clock, FPS or H6 acceptance is claimed here. The existing compiled
product runner still requires all six original scenes together and retains its
separate 32×32 and explicit 720p profiles.
