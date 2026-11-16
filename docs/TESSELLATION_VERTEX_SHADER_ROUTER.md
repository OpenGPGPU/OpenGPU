# Owned VS and tessellation shader routing

`TessellationVertexShaderRouter` connects the original VS and the TCS/TES
patch owner to the same external CU pool. Its inner `SharedShaderTaskRouter`
has two clients and one task slot per client. It retains local command tags,
launch-time native/uniform provider flags, geometry and TES bindings, texture
requests/writebacks, traps, invalidate ownership and payload bindings.

| Client | Inner pool tag | Example original local tag |
| --- | --- | --- |
| Patch/control/evaluation | 0 | `0x63` |
| Original VS | 1 | `0` from `KernelShaderStage` |

Placed inside the existing `GeometryFragmentShaderRouter` geometry branch,
both inner tags reuse its single outer geometry slot 0. Fragment tasks retain
outer slot 1. The inner router adds two reservations and its existing launch
queue; it does not create a second live outer geometry window or another CU.
Returns and traps reverse both mappings before reaching the original client.
GUR identities remain the captured draw/patch identities, not these pool tags.

The patch and pool ports have identical geometry capabilities and independently
optional TCS and TES-read interfaces. Both optional constructor flags default
to false. The VS port has no geometry, TCS, TES-read or image publication
sideband. Missing launch bindings are explicitly zero; unexpected VS geometry,
patch access, park, finish or TES-read requests stay unaccepted and assert.
This router has no image publication interface. An image-capable outer geometry
port must use the existing explicit `connectWithoutImages` adapter with matching
other optional interfaces; fragment image publication remains independent.

The optional payload interface forwards two captured active-slot pins and two
prospective-offer pins, as well as the selected pool binding. An enclosing
owner must include all four pins in its producer-pin accounting. GUR1/GUR3
roots, original views and service credits remain the responsibility of the
real VS and patch clients. They must not accept retirement while their memory,
texture, patch inspection or writeback work remains outstanding. This router's
`drained` covers its slots, launch and invalidate traffic; it is not a complete
frame-retirement signal or a native admission check.

## Staged results and boundary

All ten original router cases pass in the exact staged source: two-tier
remap/invalidate and provider/payload lifetimes, all independent optional
interface combinations, and five actual RTL assertion rejections for forbidden
VS geometry/access/park/finish/evaluation traffic. There is no physical CU in
these tests. Complete fresh-source compilation, per-case execution bounds,
cache/cleanup limits and reproduction commands are recorded in the
[typed-parent validation](TESSELLATION_VERTEX_INPUT_IMAGE.md#staged-validation-and-limits).

The enclosing typed parents retain original VS and image/source ownership.
Native GNT/GVS/GND admission, original program/resource authority and actual
shader execution remain separate requirements; this router enables no public
factory, getter or discovery bit. Keep Scala regression disabled.
