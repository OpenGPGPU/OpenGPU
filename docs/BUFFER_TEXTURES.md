# Native buffer texture views

Target 5 adds buffer-backed views to the native typed texture and GIU1 image
owners. It implements integer fetch, scalar texture size, image load/store
addressing and R32 image atomic addressing. GLES capability advertisement stays
disabled. Linux texture/image ioctls, Gallium binding, GLSL lowering, `imageSize`
execution and complete visibility/lifetime integration remain required for
E32-04o.

The format policy follows [GLES 3.2 §8.9/table 8.18](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf)
and [EXT_texture_buffer](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_texture_buffer.txt).
The sampler view admits exactly 30 existing storage-format IDs. GIU1 retains its
separate 13 image formats, access permissions and R32I/R32UI atomic restriction;
sampler eligibility does not grant image writes.

## Address and record contract

The existing 128-byte GXI1/GXL1 and 256-byte GIU1 layouts keep their sizes and all
old offsets. New fields occupy previously reserved words:

| Record | 32-bit texel count | 33-bit logical byte range |
|---|---|---|
| GXI1 | word 28 | words 29–30 |
| GXL1 | word 25 | words 26–27 |
| GIU1 view | word 38 | words 39–40 |

Targets 0–4 require these fields to be zero. Their dimensions, capacities and
format IDs are unchanged. Existing execution restrictions on cube arrays are
also unchanged.

Buffer views use level/baseLevel/maxLevel 0, dimensions/layers 1, zero pitches
and uncompressed storage. `base` identifies the range start; the independently
checked allocation bounds identify retained backing. The count is
`min(floor(bufferBytes / bytesPerTexel), maxBufferTexels)`. The default limit is
2^27 texels and configurations must support at least 65536; the 14-bit image
width is not a buffer limit. The entire declared byte range must fit backing,
including unused trailing bytes or bytes beyond a clamped count. Products and
sums retain full width before accepting the exclusive 2^32 endpoint. Native
byte alignment is 1; no GL binding-alignment capability is advertised.

Integer fetch uses full 32-bit x and requires y/z/layer/face and offsets 0.
Normalized sampling is rejected. Texture size returns the 32-bit count in
component 0 and zero in other components, without reading sampler or texel data.
An admitted buffer GXB bypasses GXS entirely; GXI must independently confirm
that target. Missing, malformed or faulting sampler records cannot affect a
buffer fetch. Nonbuffer bindings in a mixed table retain their sampler,
identity, mip-completeness and fault checks.

The C encoders, copied snapshot validators, table/bound-view builders and owned
tessellation metadata builder support these fields. Buffer-only roots can omit
the sampler table; mixed roots retain the ordinary sampler span. Owned admission
checks the whole buffer range against mapped retained backing, read dependencies
and existing alias rules. This does not add the new count/range fields to the
current Linux UAPI. C scheduler/VM validation and component execution are
separate evidence, not a composed buffer-texture host or GLES API qualification.

## Validation

All four directed component cases, the host script suite and the existing
canonical ABI check pass. The affected C fixtures also pass AddressSanitizer
and UndefinedBehaviorSanitizer. A prepared arm64 Linux 7.2 module build passes
with 85 source/Makefile inputs and no kernel rebuild.

The small component cases cover address arithmetic and 4GiB boundaries; actual
fetch/size results for all 30 read formats with scalar packet/captured vector
operations; split-line image load/store with guards; and R32UI checked atomic
requests. They exercise 65536-texel boundaries without traversing a large image,
retain mixed lane masks, reject malformed metadata before data I/O and recover
with healthy values. C tests cover the distinct 13-format image policy, NULL or
invalid buffer samplers, sparse/mixed tables and missing final-page recovery.

Reproduce C/script checks with `bash scripts/test_scripts.sh`. Run the following
existing suites with the quoted unique case filters, one at a time:

```text
TextureImageAddressUnitSpec: address buffer ranges beyond image dimensions with full count products and exclusive 4GiB ends
TextureSampleExecuteUnitSpec: fetch and query all buffer formats beyond image extents while rejecting filtered access and invalid ranges
ShaderImageResourceAccessSpec: load and store a buffer texel above the image width limit and retain faults through healthy reuse
ShaderImageAtomicResourceAccessSpec: drain descriptor and atomic owners with permission checks and fault precedence
```

Use the following command, substituting one suite and case row:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 -Dopengpu.simCcache=off \
  'Test/testOnly opengpu.graphics.SUITE -- -z "CASE"'
```

These are address/resource/texture-service components; no CU, whole-host,
rendering, timing or full-capacity qualification is claimed.

Canonical checks run with
`sbt 'Test/testOnly opengpu.graphics.ShaderImageAbiSpec'`. For an existing
prepared kernel and an independent copy of the driver sources, the module
command is `gmake -C "$KERNEL_BUILD" M="$MODULE_STAGE" ARCH=arm64
CROSS_COMPILE=aarch64-linux-gnu- -j1 gpu_drv.ko`.
