# Native fixed-point texture coordinate policy

Status: development candidate; reader, actual-CU and native image qualification
remain pending. This extension carries the qualified integer coordinate policy
through native sampler snapshots and generic vertex/fragment execution. It does
not change shader instruction encodings or claim general GLES texture resources.

Each sampler retains its eight legacy words and adds four explicit words:

| Record | Policy version | U wrap | V wrap | Signed coordinates |
|---|---:|---:|---:|---:|
| GTD1 vertex sampler | 56 | 57 | 58 | 59 |
| GTD1 fragment sampler | 552 | 553 | 554 | 555 |
| GND1 vertex sampler | 480 | 481 | 482 | 483 |
| GFS1 fragment sampler | 120 | 121 | 122 | 123 |

Version 0 preserves unsigned Q16.16 and the legacy Boolean clamp input on both
axes. U/V/signed extension words must be zero. Version 1 selects independent
repeat (0), clamp-to-edge (1) or mirrored-repeat (2) for each axis, and unsigned
Q16.16 or signed S16.16 coordinates. The legacy clamp word must be zero for
version 1. Unknown versions, reserved modes, unsupported explicit formats and noncanonical padding are rejected
before a native child draw/state offer. Existing offsets, field widths, headers,
record sizes and legacy zero-filled records retain their encodings. Older
hardware rejects nonzero extension words as reserved; software must discover
support before using version 1.

Native profiles expose the read-only eight-word GTX1 record at MMIO `0x200`.
Its words are magic `0x47545801`, length 8, supported policy-version mask 3,
feature mask `0x1ff`, wrap-mode mask 7, fraction bits 16, maximum dimension
16383, and legacy byte-order mask `0x1f`. Features describe repeat, clamp,
mirrored repeat, signed coordinates, independent axes, captured bindings,
trapped coordinate faults, scalar execution and vector execution. This record
is unavailable in legacy profiles. Writes, aliases, malformed transfers and
out-of-range burst beats follow the existing read-only discovery rules.

The owning draw snapshots the full sampler. `TexSampleUnit` snapshots it again
at each instruction. Invalid dimensions and mip intervals produce a held
coordinate fault without texture reads. The dedicated texture completion port
uses `writeRd=false` or `writesVd=false` only for a fault; healthy instructions,
including empty vectors, retain their normal write flag. The scalar/vector
backend routes a rejected texture completion into its existing memory-fault
stream, cancels its reserved destination and emits an access-fault trap rather
than a normal redirect or fabricated texel write. The core trap reports address
zero because no tap address was issued; the draw owner retains the sampler base
as its primary texture-fault address. Vertex status is memory fault (3);
fragment status is memory fault (3), source texture (5).

Graphics CUs already finish faulting warps on trap. The failed draw retains all
pending memory, task, payload and VM ownership until ordinary drain and held
completion consumption. No faulted export may become visible. Actual-CU scalar
and vector fault/reuse tests are required before integration.

General allocation bounds, image/sampler tables, floating coordinates, further
dimensions/formats, filtering modes, precision conformance and cluster throughput
remain open. PPA evaluation is synthesis only. The default native RTL changes in
this candidate; prior legacy RTL-equivalence evidence does not qualify it.
