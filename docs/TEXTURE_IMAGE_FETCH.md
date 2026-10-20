# Texture image fetch development checkpoint

Status: implementation checkpoint on `codex/mobile-texture-image-fetch`.
Behavioral qualification, native integration and synthesis are pending. This
checkpoint does not establish completion of H4/H6 or GLES texture support.

Checkpoint validation: `sbt compile` passed, and `EmitTextureImageFetch` emitted
both raw and typed SystemVerilog profiles successfully. These checks establish
compilation and elaboration only; no behavioral or PPA result is claimed.
The generated format ABI passes its `--check` comparison and its header compiles
as C11 with warnings treated as errors.

`TextureImageDataFetchUnit` connects the GPU-memory GXL1 level loader and
checked span owner to uncached texture-data reads. Descriptor transactions use
IDs 0/1 and data transactions use IDs 2/3. A checked span of 1 to 16 bytes is
gathered from one or two aligned 64-byte lines; output bits beyond the span are
zero. Expected block layout and byte size must match the verified descriptor
before any data read is issued. The raw path accepts packed texels and 4x4
blocks; compressed block decoding is separate work.

Bus fills can extend beyond the logical allocation within the participating
pages. The checked span is the logical texel/block access. A parent must retain
the allocation and VM context, translate both participating pages and enforce
read permissions until the held result is consumed. Cancellation and faults
must drain offered and accepted requests before releasing that ownership.
Descriptor invalidation does not provide atomicity for concurrent CPU writes.

`TextureImageTypedFetchUnit` captures one of the existing 36 `TypedColorFormat`
storage IDs and uses the shared decoder after fetching the matching packed
byte layout. These IDs are internal storage encodings. The new format ABI
emitter describes their IDs and byte sizes; it does not advertise native
sampling support. Integer outputs retain their typed values.

The implementation still needs behavioral controls for every format and byte
offset, reordered replies, cache reuse, malformed records, layout mismatches,
memory faults, cancellation, held results, address-space boundaries and page
translation. Native image/sampler bindings, mip completeness, LOD selection,
coordinate processing, filtering and shader integration remain open. Any PPA
evaluation at this stage will use synthesis results only.
