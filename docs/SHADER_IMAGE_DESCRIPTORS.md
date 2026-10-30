# GPU image-unit descriptors

`ShaderImageDescriptorReader` reads an immutable image binding from GPU memory
before publishing any fields to a consumer. This supplies GPU descriptor
provenance for the checked [image binding path](SHADER_IMAGE_BINDINGS.md).
H7 remains open: actual CU image instructions, task publication/VM ownership,
coherence, atomics and the complete resource matrix still require integration.

## GIU1 wire record

Each record contains 64 little-endian 32-bit words (256 bytes). A version-1
`TextureImageLevelTable` supplies the identity and half-open table bounds;
the record address is `base + unitIndex * 256`. Four image units are supported
by default. The full-width index and complete record span are checked before
any memory request. Bounds must be 64-byte aligned, the table size must be a
multiple of 256, and its exclusive limit may equal 2^32.

| Words | Contents |
|---|---|
| 0–2 | Magic `0x47495501`, record size 256, record version 1 |
| 3–6 | Context, pass, generation and image-unit index |
| 7–11 | Bound, complete, stage mask, readable and writable |
| 12–14 | Storage format, image format and compatibility-by-size flag |
| 15–19 | Selected level, base/max levels, layered flag and selected layer |
| 20–21 | View target and level |
| 22–25 | Allocation base, 33-bit exclusive allocation limit, texel base |
| 26–29 | Width, height, depth and array layers |
| 30–35 | 33-bit row, slice and layer pitches, each low word first |
| 36–37 | Block-4x4 flag and bytes per block |
| 38–63 | Reserved, zero |

Unused high bits of narrow fields must be zero. Identity and unit must match
the admitted request. All four lines are received into a private snapshot;
malformed or mismatched records never publish a partial binding. Binding
permissions, format semantics and texel coordinates are checked by the
downstream image-access unit.

## Software encoding

`driver/opengpu_shader_image_abi.h` and `docs/abi/shader-image-v1.json` are
generated from the hardware receiver's field layout. The byte-oriented helpers
in `driver/opengpu_shader_image.h` initialize a record, set/read complete fields,
validate its encoding and resolve a record address in a bounded table. They
encode little-endian bytes explicitly, including 33-bit fields; host byte order
and structure padding do not enter the wire format. A rejected field write
leaves the record unchanged. High halves, headers and reserved words cannot be
set independently through the field API.

Encoding validity does not establish binding permissions, compatible formats,
level spans or valid coordinates. The image-access hardware checks those
semantics. The caller still owns descriptor publication and VM/coherence leases;
these helpers neither submit a task nor advertise shader-image availability.

Regenerate or check the ABI and run the bounded host checks with:

```sh
sbt 'runMain opengpu.elaboration.EmitShaderImageAbi --check'
python3 scripts/test_driver.py
```

The C checks cover an exact 256-byte RGBA32UI record, all 31 field widths,
every individual wire-bit mutation, failed-write preservation and record bounds
at the exclusive 4 GiB limit. `ShaderImageAbiSpec` compares both published ABI
files with a fresh description of the actual Chisel receiver layout.
The host driver tests, the C encoder under AddressSanitizer/UndefinedBehaviorSanitizer,
and the actual Chisel layout check all pass. These results qualify serialization
and layout consistency; they do not qualify host task submission or visibility.

## Ownership and invalidation

The reader issues uncached 64-byte reads with one outstanding transaction.
An offered request remains stable until accepted, and cancellation drains an
accepted/offered read before returning. A memory fault takes precedence over
cancellation and reports the failing descriptor-line address. Other reply IDs
are backpressured. Public results remain immutable under backpressure.

A one-entry cache is keyed by the complete table envelope and image unit;
the request tag is not part of the key. The parent must invalidate it on
rebinding or VM identity reuse, including reuse of the same address and
generation. Invalidation during a fetch prevents cache fill. The parent must
pin the descriptor publication and VM for the entire eventual image operation.

## Validation

Four local simulation cases pass with zero failures or skips. They cover all
four units and every descriptor field, the exact 4 GiB end boundary, 50 malformed
word encodings, four owner mismatches, ten rejected table/index cases, cache
invalidation, held requests/results, wrong reply IDs, faults on all four lines,
cancellation drain and healthy reuse.

```sh
sbt 'testOnly opengpu.graphics.ShaderImageDescriptorReaderSpec'
```

These bounded memory tests do not render frames or establish GPU throughput,
whole-host timing, API conformance or H7 completion.

## Descriptor-to-texel access

`ShaderImageResourceAccess` composes this reader with `ShaderImageAccess`.
Its caller supplies a table, unit, shader access parameters and store values;
the binding snapshot comes exclusively from the descriptor reader. A descriptor
failure cannot issue a texel request. Permission, format, coordinate and backing
checks still complete before texel memory is offered.

Descriptor reads use reply ID 0 and texel transactions use ID 1 on the shared
64-byte port. Only one child owns memory at a time, and replies for the other
owner are backpressured. A store retires only after its acknowledgements drain.
Cancellation propagates through descriptor and data handoffs, preserving a
primary memory fault and held public results. The parent retains the same
publication, VM and coherence obligations through public retirement.

Four additional local simulation cases pass with zero failures or skips:
eight split-line load/store operations across four image units with guard bytes;
cache rebinding and generation/table changes; ten rejected/no-op accesses with
no texel requests; held descriptor/data offers, wrong reply IDs, delayed store
acknowledgements and primary faults; and 96 cancellation cut points followed by
healthy reuse. The two suites together execute eight cases.

```sh
sbt 'testOnly opengpu.graphics.ShaderImageDescriptorReaderSpec opengpu.graphics.ShaderImageResourceAccessSpec'
```

This composition supplies the resource path for future image instructions; it
does not yet connect imageLoad/imageStore to actual CU instruction execution.
