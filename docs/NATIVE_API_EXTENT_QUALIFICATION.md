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
The API-extent compiled driver uses the same best-effort
[`progress.json` reporting](COMPILED_NATIVE_PRODUCT_QUALIFICATION.md) as the
six-scene runner; a reported scene is not an image-oracle result.

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
capacities. Its separate full-capacity mode renders 640×480 frames. Both modes check
GCR1/GNC3 discovery and drain external AXI traffic before the independent oracle
checks every stored color/depth sample, resolve value and pitched guard byte.
Preparation, build and execution records bind the exact source, RTL, fixtures,
driver and mode. Smoke results cannot satisfy the product-mode guards.
Execution alone cannot produce a successful qualification record.
The generated assertion bindings access internal state, so the compiled run
uses the flat hierarchy profile.

The default runs both frames in one model with a reset between them. Passing
`--scene-jobs 2` executes the 1× and 4× frames in separate fresh model
processes; it checks and records each execution separately, then verifies both
stored images and guards together. This mode does not exercise the reset
between the two frames. If process creation reports insufficient memory, the
other frame finishes first and the unstarted frame is retried once on its own.
This retry and its outcome are recorded. Other errors and repeated memory
errors retain completed output but fail the combined run without a passing
qualification record. No OOM is inferred merely from a terminated child.
Use the same `--scene-jobs` setting for a later `--stage verify` invocation.

If the qualification driver changes after a completed run, `--stage verify`
may specify `--original-driver-checkout <checkout>` to check the exact
recorded driver sources against that checkout. The current checkout must
still match the recorded RTL source, emitted RTL and fixture hashes; the
current independent image oracle checks the existing stored output. A
historical driver checkout cannot be used for preparation, building or a new
execution. The report records the verified driver hashes and checkout.
The guard passed against an existing four-CU API model with eight recorded
driver inputs and 1,517 RTL files; the changed current driver was rejected
without the original checkout. This provenance check alone does not qualify a
frame.

```sh
python3 -m unittest discover -s scripts -p 'test_qualify_native_*compiled.py'
python3 scripts/qualify_native_api_compiled.py --smoke \
  --hierarchy-profile flat --jobs 2 --output .artifacts/native-api-smoke
# Explicit full-capacity host run, outside routine test lanes:
python3 scripts/qualify_native_api_compiled.py --api-extent-product \
  --hierarchy-profile flat --jobs 2 --output .artifacts/native-api-compiled
# Optional separate-process frame execution; has a larger simultaneous RAM use:
python3 scripts/qualify_native_api_compiled.py --api-extent-product \
  --hierarchy-profile flat --jobs 2 --scene-jobs 2 --output .artifacts/native-api-parallel
# After a complete older execution, optionally reverify with its original driver:
python3 scripts/qualify_native_api_compiled.py --api-extent-product --stage verify \
  --hierarchy-profile flat --original-driver-checkout "$API_ORIGINAL_DRIVER" \
  --output "$API_COMPLETED_RUN"
```

Both commands run locally outside regular PR/nightly lanes and require the
full host resource budget. The Scala entry points require
`opengpu.fullHostQualification=1` and `opengpu.apiExtentQualification=1`;
`opengpu.productQualification` must match the selected mode. CPU parser and
guard tests render no pixels. An earlier 2048×2048 attempt failed before
rendering because the replay wrapper rejected the complete tile grid; that
admission limit was subsequently repaired. The 2048-axis tests inspect bounded
metadata and selected address/oracle coordinates without rendering or traversing
a 2048×2048 frame. No completed 2048×2048 GPU frame,
whole-host clock, FPS or H6 acceptance is claimed here. The existing compiled
product runner still requires all six original scenes together and retains its
separate 32×32 and explicit 720p profiles.

API product preparation and verification default to a 24 GiB maximum JVM heap;
`OPENGPU_QUALIFY_JAVA_HEAP` accepts a different positive size such as `32G`.
The small-frame smoke defaults to 6 GiB. The maximum is not a reservation and
the actual available memory and swap still depend on the host.

## Full-capacity small-frame check, 2026-11-03

The `--smoke` mode passed both 32×32 boundary frames on the four-CU/four-engine
API-capacity host, using hardware from `f6068109`. GCR1/GNC3 reads matched the
configured host, external traffic drained, and the independent oracle checked
all stored color/depth samples, resolves and guards. The 1× frame completed in
13,169 GPU cycles; the 4× frame completed in 50,814 GPU cycles.

These are small-frame results. They do not establish execution at the
2048×2048 target, a qualified whole-host clock, FPS or H6 acceptance.

## Independent-process API smoke, 2026-11-12

On an existing assertion-enabled four-CU/four-engine API-capacity model,
the new frame selector and parallel runner completed the 32×32 1× and 4×
frames in separate processes, and the independent oracle verified every
stored sample, resolve and guard. The frames took 11,640 and 24,714 service
cycles respectively. A separate run on one model with a reset between frames
matched both complete stored memories byte for byte and had the same cycle
counts. In this single loaded-machine check, the reported per-frame simulation
times were 105.2/232.2 seconds in parallel and 112.1/240.2 seconds serially;
total end-to-end time was not measured and these numbers do not predict product
throughput. The driver was relinked to the existing model archive for
this bounded check; it is not a fresh end-to-end build, a 640×480 result or a
clock/FPS qualification. CPU tests also exercise concurrent child processes,
failure and spawn-memory-error handling, mode guards and altered output.
With a deliberately injected `ENOMEM` on the first spawn, the same real model
completed 4× first and then retried 1×; both stored memories matched the
independently verified small-frame results byte for byte. This was a controlled
spawn failure, not an observed system OOM.

```sh
python3 -m unittest discover -s scripts -p 'test_qualify_native_api_parallel.py'
python3 -m unittest discover -s scripts -p 'test_qualify_native_api_compiled.py'
python3 scripts/qualify_native_api_compiled.py --smoke \
  --hierarchy-profile flat --jobs 2 --scene-jobs 2 --output .artifacts/native-api-parallel-smoke
```

## Combined 720p display and API-capacity profile

The optional `--api-capacity` product-runner profile combines the existing
four-engine, 512 KiB-payload host with a 2048×2048 native maximum and a
65,536-entry directory. Product mode uses the 1280×720 display; smoke mode
uses the 640×480 display and 32×32 recipes. The runner checks all eight GNC3
capability words before each scene. Prepared manifests, execution and
reverification distinguish this profile from the original product profile.
The ordinary command and fixture bytes remain unchanged without the option.

The combined smoke preparation emitted 1,517 RTL files. Against the previously
prepared 640×480 API-capacity host, nine files differed only in firtool Scala
source-location comments and all remaining files were byte-identical. This is
an RTL identity check, not a combined-frame, clock or FPS qualification.
Configuration/discovery and command-encoding tests pass; the command parser
accepts the original 16-word contract or the complete 24-word combined
contract, and rejects malformed GNC3 sequences.

All six 32×32 recipes passed on the retained equivalent assertion-enabled
API-capacity Verilator model after relinking the product driver. Each ran in
a fresh process, drained its AXI traffic and passed the independent stored-image
and guard oracle. Rechecking the retained archive, executable, driver inputs,
1,517 emitted RTL files and all six output memories preserves the result;
rerunning the complete independent oracle also passes.

| Scene | Service cycles |
| --- | ---: |
| desktop-composition | 58,341 |
| textured-3d | 86,072 |
| opaque-overdraw | 101,355 |
| transparent-layers | 100,587 |
| small-triangles | 35,748 |
| msaa-4x | 114,444 |

This is a complete small-frame recipe check using a partially relinked model.
It does not establish a fresh complete build, a 720p result, whole-host timing
or H6 acceptance. The independent oracle can be rerun on the six retained
memories with:

```sh
sbt -J-Xmx4G -Dopengpu.fullHostQualification=1 -Dopengpu.productQualification=0 \
  -Dopengpu.productApiCapacity=1 -Dopengpu.apiExtentQualification=1 \
  "Test / runMain opengpu.system.VerifyCompiledNativeProduct --smoke --output $SIX_SCENE_MEMORIES"
```

```sh
sbt 'testOnly opengpu.graphics.NativeWideTileGridEncodingSpec opengpu.graphics.NativeRenderTargetExtentSpec opengpu.system.CompiledNativeProductCapacitySpec'
python3 -m unittest discover -s scripts -p 'test_qualify_native_*compiled.py'
python3 -m unittest discover -s scripts -p 'test_qualify_*flashsim_product.py'
# 32x32 functional smoke, full configured hardware capacity:
python3 scripts/qualify_native_product_flashsim.py --smoke --api-capacity \
  --flashsim-dir "$FLASHSIM_DIR" --output .artifacts/combined-api-smoke --scene-jobs 2
# Explicit 720p product qualification, outside ordinary CI/nightly lanes:
python3 scripts/qualify_native_product_flashsim.py --product --api-capacity \
  --flashsim-dir "$FLASHSIM_DIR" --output .artifacts/combined-api-product --scene-jobs 2
# Emit the corresponding 720p/API-capacity whole-host physical input:
sbt -J-Xmx24G -Dopengpu.productQualification=1 -Dopengpu.apiExtentQualification=1 \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-native-four-engine-api-extent-product-host .artifacts/combined-api-product-ppa'
```

The combined profile still requires complete 720p scene execution, independent
stored-image and guard verification, and matching whole-host physical timing.
Results from the separate API-capacity and 720p hosts do not establish a
combined profile or H6 acceptance.
