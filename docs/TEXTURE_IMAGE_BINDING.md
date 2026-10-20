# GPU image format, mip binding and bound texel fetch

Status: integrated into local main with [672-file source verification](performance/mobile-depth-texture-main-2026-10-20.json).
The [reconciled qualification](performance/mobile-depth-texture-main-reconciled-2026-10-20.json)
runs all 29 component controls fresh alongside seven ABI checks, combined C11
headers, CI selection and six RTL emissions. It carries 206 native checks by
exact native source and RTL identity. The earlier 662-file binding qualification
remains a historical checkpoint. All four standalone synthesis screens carry
by identical RTL logic and unchanged constraints; source-location comments can
differ. Native image/sampler integration remains open; no new native texture
capability is advertised.

`TextureImageBindingUnit` loads a GXI1 image record that owns format identity,
allocation bounds, target, dimensional sequence and a GXL1 level table. A texel
caller supplies an image handle and integer level, without a caller-selected
format. `TextureImageBoundFetchUnit` connects successful binding to typed fetch
and decoding. It retains the image/level/data/decoder lifetime through held final
completion. All publication and address-space leases remain the parent's duty.

The image record is 32 little-endian words (128 bytes), loaded with two tagged
uncached 64-byte reads. Tables start on 64-byte boundaries, contain whole records
and can end exactly at 4 GiB. Wide handle/extent arithmetic precedes reads.
The [C header](../driver/opengpu_texture_image_binding_abi.h) and
[JSON layout](abi/texture-image-binding-v1.json) are generated from the RTL fields.
Narrow fields have canonical zero padding; words 23 through 31 are reserved zero.

| Word | Contents |
|---:|---|
| 0 / 1 / 2 | Magic `0x47584901`, size 128, version 1 |
| 3 / 4 / 5 / 6 | Context, pass, generation and image handle |
| 7 / 8 | Target and authoritative internal storage-format ID |
| 9 / 10 | Base and maximum level, each 4 bits |
| 11 / 12 / 13 / 14 | Logical level-zero width, height, depth and layer count |
| 15 / 16–17 | Allocation base and 33-bit exclusive limit |
| 18 / 19 / 20–21 / 22 | Level-table version, base, exclusive limit and first handle |

Logical level-zero dimensions determine the sequence; unused lower levels need
no descriptor or texel storage. `firstLevelHandle + level` selects a GXL1 record.
The base and maximum level delimit the exposed image view. The effective last
level also respects natural mip reduction. 2D arrays keep the same layer count;
3D depth reduces with the mip level. Cube views use six uniform square planes.
These internal dimension/level fields are resolved hardware state, not GL enums
or a complete public texture-object representation.

The admission request captures `requireMipChain` from its parent sampler policy.
When true, every required level from base to effective maximum is checked before
publishing the selected binding. When false, only the selected level is checked.
This distinction follows the sampler-dependent mip requirements in
[GLES 3.0.6 sections 3.8.10.4 and 3.8.13](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf).
Sampler filter legality, coordinate/LOD calculation and shader-facing texture
application still need their own integration and validation.

Every required level must have the declared target, dimensions, layers, common
allocation and packed byte size. One image format defines interpretation across
all its levels, including same-size formats with different numeric meanings.
The current image-format set is the existing 36 typed packed storage IDs;
compressed/depth image formats remain required work. GXL1 supplies layout only.
Within one checked mip set, bounding byte spans, including pitch padding, must
be disjoint. Levels can appear in any physical order. A later inconsistent mip
must fail before any selected texel data is fetched or any partial view published.

One immutable binding cache uses the complete image table, identity, handle,
selected full-width level and chain mode; caller tags are excluded. Explicit
invalidation revokes reuse and prevents invalidated fills from repopulating it.
Published records stay immutable while owned; invalidation is not atomic CPU
write protection. Texture data remains uncached. The selected GXL1 record may be
read again by the existing typed fetch child; its private cache then serves reuse.

Within binding, level requests use tags 0/1 and image header requests 2/3.
Bound fetch uses those tags for binding and tags 4/5 for selected-level reloads,
6/7 for data. Fault stages distinguish image, level, data, layout and coordinates.
A valid fault address may be zero. Offered reads remain stable through stalls and
cancellation, and all replies drain before completion. Errors zero binding/view
and texel payloads while retaining identity, full level, caller tag and diagnostics.

The initial compile failure from naming a Bundle field `binding` is retained in
the development artifacts; the public field is now `image`. It produced no
behavioral result. The independent native depth/facing/texture regression and
older native texture synthesis keep their frozen source and original processes.
The full hardware objective, native per-stage image/sampler binding, typed shader
transport, filtering/LOD, compressed decoding and throughput remain open. PPA
screening for this work will use synthesis only.

The first eight-control run passed seven checks and exposed an existing GXL1
reader issue: a prompt first-line fault could construct another descriptor offer
on the same edge. The original assertion, source and log are preserved in the
linked result. The reader now gates issue on the accepted fault response while
retaining already offered requests. The unchanged assertion passes in the full
27-control run. This is the sole changed file among the 655 existing dependencies;
the other 654 remain exact. The shared-reader change requires fresh synthesis
screens of both existing fetch profiles as well as the new binding profiles.

New coverage includes 144 authoritative format/target fetches, NPOT and reversed
mip layouts, base/max and array/volume rules, selected/full-chain cache identity,
22 encoding corruptions, later mip mismatches/overlap with zero data reads,
240 cancellation points, Sv32 permissions on image/level/data pages, and image
records, level tables and a full array texel ending exactly at 4 GiB. A ninth
control directly checks primary-fault preservation across nested cancellation,
valid zero fault addresses at image/level/data owners, and seven invalid caller
coordinate cases with zero texel reads and exact healthy reuse.

The [initial four synthesis screens](performance/mobile-texture-image-binding-synthesis-initial-2026-10-20.json)
all map fully. Raw/typed setup passes at +123.434/+10.117 ps; image binding and
bound fetch miss at -565.280/-675.490 ps. The binding critical path starts at
base-level state and ends at current-level capture. The revised owner adds three
admission stages separating natural mip selection, 33-bit handle formation,
41-bit table extents and their comparisons. Full-width level rejection precedes
narrowing; handle carries and exclusive 4 GiB ends stay checked. All 28 controls
and seven ABI checks pass after this change. Revised binding/bound synthesis
uses the same TC/SLVT, 1000 ps, 25 percent I/O and no-retiming constraints.

The [registered screens](performance/mobile-texture-image-binding-synthesis-registered-initial-2026-10-20.json)
still miss binding/bound setup at -83.596/-170.597 ps, with the remaining binding
path from base-level state to fault classification. The latest implementation
uses the equivalent root-based natural mip bound and a separate requested-level
check before handle narrowing. An independent original dimension-reduction
oracle checks 303,104 hardware cases, including every 14-bit root/base pair and
all target/max clamps at power boundaries. All 29 controls and seven ABI checks
pass. The [completed synthesis screens](performance/mobile-texture-image-binding-synthesis-2026-10-20.json)
pass full mapping and reported setup including I/O: binding +57.287 ps, bound
fetch +49.401 ps, raw +123.434 ps and typed +10.117 ps, at the unchanged 1 GHz
target. Raw/typed carry from the earlier completed screens by exact active-RTL
and current-source proof. All initial misses remain recorded. No place/route,
power, native execution rate or whole-GPU clock result is implied.
