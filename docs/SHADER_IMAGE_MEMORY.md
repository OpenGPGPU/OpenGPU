# Shader image memory transfers

`ShaderImageMemoryAccess` converts and transfers one resolved shader-image
texel. It supports the thirteen GLES 3.1 image-unit formats through
`ShaderImageFormat`: RGBA32F, RGBA16F, R32F, RGBA32UI, RGBA16UI, RGBA8UI,
R32UI, RGBA32I, RGBA16I, RGBA8I, R32I, RGBA8 and RGBA8_SNORM.

This is an internal transfer component. Immutable GPU descriptor admission,
actual compute/graphics instruction integration, image atomics and cross-domain
barriers remain required. No API capability or H7 acceptance follows from these
component results.

## Ownership and bounds

The parent supplies a resolved 32-bit byte address, an exclusive 33-bit allocation
limit, format/type and task identity. It must retain descriptor, translation and
coherence ownership until the public result retires. The memory port uses the
uncached policy; this does not replace the parent's coherence protocol.

Both complete 64-byte backing lines are checked before either request is offered.
Allocation boundaries must be line aligned. A texel may span two lines; reads
consume the checked lines and stores enable exactly the texel's bytes. No address
wrap is permitted at the 4 GiB boundary. An invalid texel returns zero or performs
no store without accessing memory. Unsupported formats, shader-type mismatches
and malformed backing bounds return distinct failures before the first request.

One transaction is outstanding at a time. Offered requests remain stable until
accepted, and writes retain ownership until acknowledged. Cancellation drains
offered/accepted transactions and conversion work, then suppresses subsequent
lines. It cannot undo an offered or acknowledged store, so a split store can be
partially visible. A memory fault during cancellation remains the primary error.
Public results retain identity, tag, operation and data through backpressure.

## Validation

Four transfer tests pass with zero failures or skips. They cover 117 format and
address-boundary operations, all 256 signed-normalized byte codes, exact store
masks and guard bytes, twelve invalid/rejected operations, delayed acknowledgements,
wrong reply IDs, held outputs, cancellation before/during ownership, first/second
line faults and healthy reuse. The separately qualified store encoder covers
integer saturation, FP16/normalized conversion and format compatibility.

Reproduce the bounded component checks:

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=1 'testOnly opengpu.graphics.ShaderImageStoreEncoderSpec opengpu.graphics.ShaderImageMemoryAccessSpec'
```

These tests exercise the transfer port and typed results. They do not qualify
shader execution, the complete cache/VM path, product throughput or physical timing.

## Binding-to-memory composition

`ShaderImageAccess` joins the immutable binding/address owner to this transfer
component. It captures one complete operation, checks permissions and coordinates
before offering memory, and retains task identity and both child owners until
their results drain. Cancelled or invalid accesses return zero; a primary memory
fault retains its address and takes precedence over cancellation. Held public
results remain stable. The parent still owns descriptor provenance, translation
and coherence until public retirement.

Four composition tests pass with no failures or skips. They cover 208 typed
load/store operations over all thirteen formats and four image targets, both
layered and single-layer bindings, exact split-line stores and guard bytes,
twenty rejected/no-op accesses with no memory traffic, delayed store responses,
wrong response IDs, primary faults, 64 cancellation points and healthy reuse.

```sh
sbt -J-Xmx4G -Dopengpu.simJobs=1 'testOnly opengpu.graphics.ShaderImageAccessSpec'
```

This composition is an internal operation interface. GPU descriptor fetching,
CU instruction integration, atomics and cross-domain ordering remain open.
