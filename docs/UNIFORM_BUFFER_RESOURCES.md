# Checked uniform resource records

`ShaderUniformLoadUnit` provides immutable descriptor admission and a raw
four-byte load for the resource contract in [Mobile limits](MOBILE_GPU_RESOURCE_LIMITS.md).
This is a standalone hardware service. Native instruction execution, draw
bindings, runtime discovery, compiler lowering, translated publication and
simultaneous vertex/fragment qualification remain required before exposing
uniform-buffer support through a native profile. GTC1/GNC2/GTO1 are unchanged.

The generated [C header](../driver/opengpu_uniform_abi.h) and
[word layout](abi/native-uniform-v1.json) define two 64-byte records. Headers
contain magic, byte size and version in words 0–2, followed by context, pass
and generation in words 3–5. Narrow-field padding and reserved words are zero.
All multiword limits are little-endian 33-bit exclusive byte addresses.

| GUR1 root word | Meaning |
| --- | --- |
| 6 | Vertex stage 0 or fragment stage 1 |
| 7 | Twelve active program-block bits |
| 8–9 | Six five-bit API binding indices per word, low program block first |
| 10–12 | Base and exclusive limit of the twenty-four-entry binding table |
| 13–14 | Default-view descriptor address and presence flag |
| 15 | Reserved zero |

An inactive mapping is zero; an active mapping selects binding 0–23. If no
blocks are active, table base/limit are canonically zero. Otherwise the table
contains all twenty-four 64-byte records within its checked, 64-byte-aligned
envelope. An absent default view has address zero; an enabled root or view
descriptor can reside at address zero. Descriptor spans may end at 4 GiB.

| GUB1 view word | Meaning |
| --- | --- |
| 6–7 | API binding index and two-bit stage permission mask |
| 8–10 | Data base and exclusive logical limit |
| 11–13 | Allocation base and exclusive allocation limit |
| 14–15 | Required read-only flag, then reserved zero |

Each stage maps twelve logical blocks into the shared twenty-four-binding
namespace. A block view starts at a 256-byte boundary and contains at most
16,384 bytes. Logical block 31 selects a separate default view whose binding
index is also 31. It has four-byte base alignment and up to 1,024 vertex or
896 fragment components. This permits default data immediately after an
unaligned private-prefix boundary without changing kernarg addressing.

The request carries independent full-width block and byte-offset operands,
identity, tag and the task's private-prefix interval. Invalid block numbers or
unaligned offsets cannot alias another binding or word. The loader checks
root identity/stage, every active mapping, complete table envelopes, selected
view identity/permissions, full address sums and logical/allocation bounds
before narrowing an address. Root/table/view metadata and visible data cannot
overlap the private prefix. Default data may share an allocation line with its
preceding prefix; only the selected word is returned. The parent must pin the
descriptors, read-only data publication and VM context through retirement.

The service has one physical read credit and no descriptor/data cache. Each
successful component reads one root line, one view line and one data line:
192 transferred bytes for a four-byte result. This is an admission/correctness
foundation, not a uniform throughput qualification. Descriptor reuse,
lane/broadcast coalescing and native performance budgets remain required.
Every offered request is irrevocable; cancellation drains its actual reply
before producing a zero-valued cancellation result. Already offered output
bits remain stable. The first observed memory fault survives later cancel.
Statuses are 0 success, 1 invalid resource, 2 access bounds/alignment, 3 memory
fault, 4 canceled and 7 malformed record/identity/permission. Fault sources
1/2/3 identify root/view/data. A data memory fault reports the requested word
address; metadata faults report the descriptor address.

Eleven directed checks pass across both stages. They read every word in a
16 KiB block, all twelve mappings and all default components; exercise the
last root/table/data words below 4 GiB; reject full-width aliases, malformed
records and otherwise valid private-prefix overlaps; and retain snapshots,
held requests/results, delayed responses, precise faults and healthy reuse.
Host script checks and generated ABI checks pass.

Both complete standalone profiles pass 1000 ps TC/SLVT synthesis with 25% I/O,
standard ABC, no retiming/false paths, zero skipped operators and no SRAM:

| Profile | Core / I/O slack (ps) | Cells | Synthesis library area |
| --- | ---: | ---: | ---: |
| Vertex | +279.473 / +479.737 | 9,172 | 1,202.12 |
| Fragment | +346.386 / +489.487 | 9,153 | 1,200.37 |

```sh
sbt 'runMain opengpu.elaboration.EmitNativeUniformAbi --check' \
  'testOnly opengpu.graphics.ShaderUniformLoadUnitSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-uniform-vertex .artifacts/uniform-vertex-rtl' \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-uniform-fragment .artifacts/uniform-fragment-rtl'
```

Qualify each emitted directory with top `ShaderUniformLoadUnit` using
`scripts/qualify_ppa.py --stage synthesis --period-ps 1000 --io-delay 0.25`.
Raw reports stay local. These component gates do not qualify an enclosing
shader/host clock or close H0/H6 or the full H0–H8 objective.
