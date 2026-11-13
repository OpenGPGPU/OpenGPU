# Optional native advanced blend transport

`TypedTileColorConfig.nativeAdvancedDraws` enables explicit native draw and
fragment-state payload admission. It defaults to false and requires
`advancedBlend`, `nativeSamples`, `parallelLanes` and `parallelDepth`. The
existing 64 sample/location owners, four color locations, 4x storage and
32 KiB tile capacity remain intact. Advanced arithmetic uses the independent
route described in [ADVANCED_BLEND_EQUATIONS.md](ADVANCED_BLEND_EQUATIONS.md).

This is a native transport contract. GLES feature advertisement, fragment
shader `blend_support` metadata, color-output-zero/draw-buffer API restrictions,
noncoherent blend barriers and product/precision qualification require their
own integration. The current alignment-v2 product profile retains advanced
support disabled and its existing discovery contract.

## Safe discovery

Software first checks host ABI version 2 or later and reads the guaranteed
GCR1 record. Bit 15 of its flags word advertises GAB1. An absent bit means no
read of the optional block; ABI-v1 software must not read GCR1 either.

GAB1 occupies the read-only byte range `[0x4c0,0x4e0)`, immediately after the
atomic-counter command range. It is disjoint from image, frozen GDB1, storage,
uniform and texture command blocks. The full address is decoded before
register-address narrowing. Unsupported alignment, sizes, burst modes,
out-of-range addresses and every write, including a zero-strobe write, return
AXI SLVERR. Held responses retain their complete tuple. GDB1's eight words and
legacy register values remain unchanged.

| Word | GAB1 value |
|---|---|
| 0 | Magic `0x47414201` |
| 1 | Eight words |
| 2 | Payload-version mask `0x8`: explicitly defined draw and GFS payload version 3 |
| 3 | Equation mask `0x7fff`: equation IDs 0 through 14 |
| 4 | Low format mask `0xf00001ff` |
| 5 | High format mask `0xf` |
| 6 | Internal features `0x7`: parallel owners, per-sample destination RMW, ordered draw drain |
| 7 | Reserved, zero |

The format mask uses `TypedColorFormat` IDs: nine UNORM/sRGB formats and eight
floating-point formats. Integer formats are excluded. The generated
[header](../driver/opengpu_native_advanced_blend_abi.h) defines the exact
addresses, masks, independent version constants and control shifts.

`GpuHostSystemAxi` derives presence from its actual native profile's
`nativeAdvancedDraws` setting. The host requires native vertex-input routing,
protected unified commands, GCR1 and ABI version 2 or later. Constructing an
internal advanced arithmetic unit alone does not advertise this transport.

Linux initialization uses the guaranteed GCR presence bit before probing GAB1,
checks the MMIO extent and protected-command capabilities, then validates every
GAB1 word exactly. Unknown or missing version/equation/format/feature bits,
nonzero reserved fields and malformed lengths fail initialization with the
transport flag clear. The flag records native transport availability and does
not enable a Gallium GL extension.

The existing Linux tessellation submission entry also checks each referenced
immutable GFS entry before enqueue. It binds admission to the discovered
transport flag, validates version pairing and advanced/ordinary controls, and
rejects integer output types or enabled integer attachments. The generated
header obtains the GFS and frame word offsets from the actual Scala layouts.
This check preserves the legacy validation path and does not establish shader
or resource provenance. Discard-only jobs retain their existing contract.

## Explicit payload version 3

The GTD1 draw record and GFS1 fragment-state record keep their existing
envelope version, length, offsets and canonical padding rules. Each payload
defines its own version-3 constant, independently of the internal blend-state
version. Admission requires the matching version-3 blend state and the explicit
profile opt-in. Legacy payload versions 1 and 2 continue rejecting advanced
state even on an enabled profile. A version-3 payload with version-1/2 blend
state, unknown versions or malformed controls is rejected.

Each indexed buffer retains component mask bits 26:23 and ordinary control
bits 22:0. Advanced enable is bit 27 and its equation ID is bits 31:28. An
advanced control requires ordinary blend enable at bit 0, zero factors and
ordinary equations, and an equation ID below 15. A non-advanced control uses
ordinary indexed semantics and zero high equation bits. Shared enable,
factors and equations are zero; the four blend constants remain shared.

Native draw preflight validates output types before offering a shader draw.
GFS1 validates output types before publishing any fragment state. The render
owner also checks the actual attachment kinds before starting shader work.
Thus an integer advanced attachment fails before the resulting shader can
perform image writes or other side effects. Existing GFS canonical-padding
error precedence remains intact. Fragment-state fetch faults and cancellation
never publish a partial state or cache entry.

Accepted draws and GFS entries retain immutable private snapshots through held
offers/results, cancellation and owner drain. Native fast cohorts drain before
an advanced general cohort starts, and the general owner drains before the
next native cohort. Completion includes child arithmetic, encoding and masked
word writes. Committed prefix writes survive cancellation; new writes are
suppressed after cancellation except already offered irrevocable work.

## Reproduction

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 \
  'runMain opengpu.elaboration.EmitNativeAdvancedBlendAbi --check' \
  'runMain opengpu.elaboration.EmitComputeResourceAbi --check' \
  'runMain opengpu.elaboration.EmitNativeTypedGraphicsAbi --check' \
  'runMain opengpu.elaboration.EmitTypedFragmentStateAbi --check'
python3 scripts/test_driver.py
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

The independent C discovery fixture checks old-profile zero-probe behavior,
the exact eight-word record and all 256 single-bit corruptions. It removes its
temporary executable when complete. Hardware regression suites use small tile
fixtures and the existing production modules; no frame-rate claim follows from
native packet admission.

The GFS C fixture covers all four locations, fifteen equations and 36 attachment
formats with an independent numeric format-class oracle, plus integer output
types, shared controls, reserved bits and version pairing. The production
scheduler/MMU fixture covers all three patch domains and both image-addressing
modes. Seven malformed/unsupported cases per combination leave enqueue, GPU
kicks, object/context references and fences unchanged. Twelve valid advanced
jobs retain copied state and revoke its PTEs on retirement; the complete suite
passes 71 jobs with the original 128-fence capacity. Empty context page-table
pages may remain owned by the VM; job allocations and live mappings retire.
