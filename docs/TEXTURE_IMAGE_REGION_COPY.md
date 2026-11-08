# Bounded texture image region copy

Status: standalone integrated component with directed tests on local main. It
is not wired into a native command path, driver or API and does not claim
general `glCopyImageSubData` coverage.

`TextureImageRegionCopyUnit` copies one rectangular region between two
resolved image levels. The caller supplies both `TextureImageLevelView`
records; the owner performs no descriptor fetch, mip ownership or format
conversion.

## Contract

- Both views must use the same target and format. Targets are 2D, 2D array,
  cube, cube array, 3D and buffer; packed uncompressed texels of 1, 2, 3, 4,
  6, 8, 12 or 16 bytes are accepted. Multisample and 4x4 block views, and
  differing block byte sizes, are rejected before any memory access.
- A request carries a source and destination origin and a region extent.
  2D copies require layer and depth one. 2D-array copies advance along
  `layer` and require depth one. Cube copies advance along `z` as the face
  index (0..5); cube-array copies advance along `z` as the layer-face index
  (`layer*6+face`) over `layers*6` planes. Both require layer one. 3D copies
  advance along `z` and require layer one. Buffer copies advance along `x`
  over `bufferTexels` and require height, depth and layers one. At most 4096
  texels are copied per request.
- Origins plus extents are checked against each view's dimensions before any
  memory access, including zero extents and unused axis values. Per-texel
  addresses and pitches are owned by two `TextureImageAddressUnit` instances,
  so allocation spans, row/slice/layer pitches and the 4 GiB ceiling use the
  same rules as image fetch.
- Each texel is read from up to two 64-byte lines and written back to up to
  two lines with byte masks. Reads and writes are uncached line requests; one
  request is outstanding at a time. A texel never spans more than two lines,
  because packed texels are at most 16 bytes.
- The owner does not detect overlapping source and destination byte spans.
  GLES leaves copying a region onto itself undefined; callers must guarantee
  disjoint spans.

## Results

`status` is 0 success, 1 invalid view or format, 2 invalid extent or
coordinate, 3 level bounds or pitch failure, 4 cancelled and 5 memory fault.
`texels` and `bytes` count fully written texels. `readLines` and `writeLines`
count issued line transactions. `faultSource` is 1 for a source read and 2 for
a destination write; `faultAddress` is the faulting line. Results are held
until accepted and remain unchanged under backpressure. Cancellation drains
the outstanding line request before publishing status 4.

## Directed evidence

`TextureImageRegionCopyUnitSpec` passes eight cases:

- 2D R8 copy across padded rows and line crossings, with unchanged source
  bytes and exact line counts;
- RGBA8 and RGB32F copies with independent row pitches and both-direction line
  crossings;
- two 2D-array layers and three 3D slices along their own pitches;
- one-dimensional buffer copies of six R8, RGBA8 and RGB32F texels from
  unaligned bases;
- two cube faces and three cube-array layer-faces across their plane pitches,
  including a region crossing from one cube layer into the next;
- rejection of target, format, block-byte, 2D-array-on-2D, coordinate, extent
  and unused-axis errors before any memory access;
- source-line and destination-line faults with exact fault addresses and a
  healthy reuse copy;
- held immutable completion and cancellation into a drained status-4 result.

```sh
sbt 'testOnly opengpu.graphics.TextureImageRegionCopyUnitSpec'
```

## Remaining work

Multisample and compressed-block copies, format conversion, overlapping-span detection, command/driver/API exposure, composed
four-CU product execution and physical qualification remain open. The
per-texel line loop favors exactness over throughput.
