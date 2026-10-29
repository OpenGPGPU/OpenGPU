# Checked uniform resource records

`ShaderUniformLoadUnit` provides immutable descriptor admission and a raw
four-byte load for the resource contract in [Mobile limits](MOBILE_GPU_RESOURCE_LIMITS.md).
The [native binding path](NATIVE_UNIFORM_BINDINGS.md) connects it to task-owned
scalar/vector execution and native draw/state records. [GUC1 discovery](NATIVE_UNIFORM_DISCOVERY.md)
reports this hardware word-load contract; [concurrent stage checks](NATIVE_UNIFORM_CONCURRENT_CAPACITY.md)
cover translated GPU publication. Driver/compiler API integration, full-capacity
renderer composition, general coherence and rate qualification remain required
before advertising full uniform-buffer support. GTC1/GNC2/GTO1 are unchanged.

The [uniform execution adapter](UNIFORM_INSTRUCTION_EXECUTION.md) adds
scalar/vector retirement and coalesces identical active-lane requests. It
is selected by an explicitly enabled native uniform provider.

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
index is also 31. It has four-byte base alignment and up to 1,024 components
in either stage. This permits default data immediately after an
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
foundation, not a uniform throughput qualification. The execution adapter
coalesces identical active-lane requests; descriptor reuse and native performance
budgets remain open.
Every offered request is irrevocable; cancellation drains its actual reply
before producing a zero-valued cancellation result. Already offered output
bits remain stable. The first observed memory fault survives later cancel.
Statuses are 0 success, 1 invalid resource, 2 access bounds/alignment, 3 memory
fault, 4 canceled and 7 malformed record/identity/permission. Fault sources
1/2/3 identify root/view/data. A data memory fault reports the requested word
address; metadata faults report the descriptor address.

Twelve directed checks pass across both stages. They read every word in a
16 KiB block, all twelve mappings and all default components; exercise the
last root/table/data words below 4 GiB; reject full-width aliases, malformed
records and otherwise valid private-prefix overlaps; and retain snapshots,
held requests/results, delayed responses, precise faults and healthy reuse.
Host script checks and generated ABI checks pass.

The earlier standalone profiles, with a 896-component fragment limit,
pass 1000 ps TC/SLVT synthesis with 25% I/O,
standard ABC, no retiming/false paths, zero skipped operators and no SRAM:

| Profile | Core / I/O slack (ps) | Cells | Synthesis library area |
| --- | ---: | ---: | ---: |
| Vertex | +279.473 / +479.737 | 9,172 | 1,202.12 |
| Fragment | +346.386 / +489.487 | 9,153 | 1,200.37 |

These retained results do not qualify the expanded fragment implementation.

```sh
sbt 'runMain opengpu.elaboration.EmitNativeUniformAbi --check' \
  'testOnly opengpu.graphics.ShaderUniformLoadUnitSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-uniform-vertex .artifacts/uniform-vertex-rtl' \
  'runMain opengpu.elaboration.EmitPpaRtl mobile-uniform-fragment .artifacts/uniform-fragment-rtl'
```

Qualify each emitted directory with top `ShaderUniformLoadUnit` using
`scripts/qualify_ppa.py --stage synthesis --period-ps 1000 --io-delay 0.25`.
Raw reports stay local. These component gates do not qualify an enclosing
shader/host clock or close H6 or the full H0–H8 objective.

## Expanded fragment default capacity

Both stages admit up to 1,024 raw default components (4,096 bytes). This raises
the fragment word-load capacity from 896 to the GLES 3.2 default-component
minimum without changing descriptor layouts, instruction encodings or the
private payload allocation. GUC1 word 9 and the generated C/JSON limits report
1,024. It remains a checked GPU-memory view, not an additional SRAM allocation.

The current qualification passes 24 tests with zero failures, canceled,
ignored or pending cases: twelve loader, seven scalar/vector execution and
five AXI discovery cases. Both loaders read every default component and every
word of a 16 KiB block. Fragment boundary checks accept the new final word,
reject offset 4096 and oversized views before data reads, retain the previous
3,584-byte view's bounds and recover after rejection. Four- and sixteen-lane
execution reads the final word through scalar/vector retirement and suppresses
the entire vector write when one active lane exceeds its view. Discovery
checks use literal expected words across enabled and absent providers.
The actual Scala ABI generator and its `--check` both pass, as does strict
C11 compilation with the native typed, binned and fragment-state headers.

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'set Test / parallelExecution := false' \
  'runMain opengpu.elaboration.EmitNativeUniformAbi --check' \
  'testOnly opengpu.graphics.ShaderUniformLoadUnitSpec opengpu.graphics.ShaderUniformExecuteUnitSpec opengpu.system.NativeUniformCapabilitiesAxiSpec'
```

This verifies the changed loader/execution bound and its host discovery.
Expanded-capacity 128-thread native frames, API/compiler integration and new
fragment synthesis/whole-host timing remain required; this change does not
close H6 or H8.
