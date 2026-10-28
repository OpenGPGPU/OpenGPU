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

Full-capacity host emission, a compiled boundary-frame driver and independent
stored-image verification are required before this becomes a native extent
qualification. Actual 2048-axis viewport/scissor execution, format/sample
combinations, whole-host clock, 720p frame rates and H6 acceptance remain open.
Keep generated inputs, snapshots, logs and intermediate reports in `.artifacts/`.
