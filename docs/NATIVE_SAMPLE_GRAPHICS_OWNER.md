# Ordinary112 two-owner transport

The fixed `native_sample_graphics_driver.cpp` entry uses the exact protected
112-word sample-stage host, ordinary ABI1 programs and two distinct private job
owners. It shares the strict OGNP0002 profile probe and OGCAP002 parser, but has
its own two-record execution loop and receipt schemas. The old96 executor and
the record-zero-only sample executor retain their original contracts.

`--active-owners INPUT.memory NEW_OUTPUT SESSION` loads union memory once and
resets once. It checks both captures against the actual complete profile and
requires distinct command IDs, distinct private GNJ addresses, one shared Sv32
context and no raster-discard/feedback flags. It holds each actual completion,
exports its raw GNX/64-bit append observations, then waits for the producer's
original-driver retirement trace before POP/IRQ acknowledgement. It publishes
record1 results only after record0 retirement. Both resulting RAM snapshots
come from the same memory and bus instance. PTE ownership is retained for the
generic actual-cleanup replay with an exact record count of two.
Before the second enqueue, `record-1.admit` must contain `OGNGA001`, record1,
the bounded capture byte count and every exact original capture byte. The
controller must bind that capture to its retained checked source plan before
publishing admission. A missing, reordered or altered admission never runs the
second command.

The fixed Python controller now performs source preflight before launching the
executor, binds held/retired receipts to the original capture command and
context/pass/generation, and grants second-record admission only for the exact
checked capture bytes. Each independent result check precedes its execution
response. Before cleanup authorization, the controller revalidates both full
results in order, including the first GNY2/byte guards, and requires distinct
original commands. Original inputs, captures and returned snapshots remain
immutable through the final authorization. After the producer exits successfully,
the controller requires exactly one complete LIVE two-owner retirement marker
before reporting success and deleting the producer log; a CPU marker cannot
satisfy this gate.

The producer has a separate compile-time LIVE entry; the default CPU entry
rejects both old and new live environment variables, including empty values.
The controller requires the distinct `native-sample-graphics` prepared executor
kind and its original checker. This kind accepts only the exact sample-stage
factory, binds the GPU producer/compiler/include/checker closure, and retains
separate build/execution locks. The original three executor kinds keep their
existing contracts. Seventeen CPU qualification tests reject changed/missing
sources, undeclared includes, old driver/model relabeling and stale binaries.

The [Linux LIVE producer build](NATIVE_SAMPLE_GRAPHICS_SOURCE_PLANS.md) passes
ASan/UBSan compilation/linking of all28 units and331 actual dependencies. It
never starts the producer or executes a GPU command. The original Mesa inputs
and compiled products are checked before ended build data is removed. Actual
final-host execution and its matching compiler-input binding remain required;
no CPU receipt or successful build establishes GPU execution.

The JSON schemas are `opengpu-native-sample-graphics-held-v1` and
`opengpu-native-sample-graphics-retired-v1`. Each names record0 or record1,
recordCount2, resetCount1 and the exact sample-stage owner profile. A retired
receipt must preserve every raw held value. The final two-record set rejects
missing/reordered records and reused command IDs. Clock/FPS qualification stays
false.

`native_sample_graphics_protocol.py` encodes the new little-endian wires;
`userspace/tests/graphics_sample_owner_ipc.[ch]` independently decodes them:

| File | Magic | Count | Payload |
| --- | --- | ---: | --- |
| record-N.held.bin | OGNGH001 | 1 | 84-byte held observation |
| record-N.response.bin | OGNGR001 | 1 | 100-byte retired observation |
| cleanup.authorize.bin | OGNGB001 | 2 | none |
| response.bin | OGNGF001 | 2 | retired record0 then record1 |

The held payload is `(15 u32, u64, 4 u32)`; retirement adds four u32 values.
The C decoder checks command/completion identity, healthy GNX fault/reserved
fields, aligned 33-bit append bounds, status/IRQ/boolean fields, record order,
exact lengths and EOF. Failed calls leave the entire output unchanged,
including a final response whose second record fails. The cleanup wire is
separate from execution and final wires; receipt syntax alone cannot grant
permission to free an owner.

Seven ASan/UBSan tests pass for the C++/Python transport and cross-language
interoperation. A synthetic CPU MMIO peer executes two enqueue/retire cycles,
checks one reset and memory/context continuity, and rejects duplicate command
or job identities, changed contexts, feedback flags, old captures and a missing
second capture. Raw injected GNX reader values and append bytes above4GiB are
preserved through C++ JSON, Python encoding and the independent C reader.
Five additional ASan/UBSan C codec tests cover all truncations, old/phase-mixed
wires, malformed fields, exact profile isolation, timeouts and error markers.
These are CPU protocol tests, not original-Mesa or GPU execution results.

`native_sample_graphics_owner_oracle.py` binds the exact capture's private GNJ
address/512-byte bounds, shared SATP, ordinary flags and all successful GNX
identity/fault predicates to the complete canonical source plans. Its captured
page permissions must equal the page envelopes of the original writable
views. The independent result check remains narrower: two primitive records
use2944 arena bytes, the directory and pool use64 bytes each, the attachment
uses256 bytes, and scratch writes stay within the supported ABI1 logical views.
All other original allocation, RX/uniform/descriptor/PTE bytes and allocation
tails remain guarded. Mutable/private pages cannot alias either owner's original
read-only allocations or actual PTE pages; shared original read-only allocations
remain legal. The second result must preserve the first image, GNY2
lease and all non-current-owner bytes in the same original allocations.

The result checker uses the separately derived complete triangle GNY2 oracle
and independently calculated original-source RGBA32UI color. ASan/UBSan
CPU tests pass on real Linux canonical snapshots, placeholder CEASE programs,
synthetic returned images/GNY2, and command/page/guard/identity/order mutants.
Twelve tests include coherent writable/RO/PTE aliases, legal original RO sharing,
and identical bad first-result GNY2/guard bytes carried through both snapshots.
These synthetic results exercise rejection and do not establish GPU execution.
The [actual Mesa CPU preflight](NATIVE_SAMPLE_GRAPHICS_SOURCE_PLANS.md) also
passes for frozen39775 source, including canonical records and both actual C
capture identities; it does not contain returned GPU images or GNY2 results.

Thirteen controller CPU tests pass. The phase tests use explicit mocked
image/GNY2 checks. They
exercise successful phase order and admission, and reject source/result
mutation, foreign/swapped receipts, failed per-record or complete-pair checks,
invalid cleanup, executor failure and changed final qualification. They also
check that unknown process exit preserves inputs while cleanup still attempts
all owned consumers. A managed caller can request a separate consumer-cleanup
receipt, published only after both process groups, the owned container and IPC
cleanup finish. It binds the exact controller PID, source root, executor and
qualification. Missing or mismatched confirmation preserves the caller's inputs.
These tests establish controller gating only; the separate
source/image/GNY2 suites exercise the actual independent oracles.
Four additional tests use short real processes to reproduce atomic cleanup
publication followed immediately by executor exit. Only the final cleanup wait
rechecks the published file after a zero exit; earlier waits still reject that
exit window. Nonzero exit, missing receipt, early producer exit, error markers,
symbolic links and timeout remain failures. Content validation and the final
executor exit check still precede owner release. These CPU process tests do not
execute the C LIVE producer or establish GPU execution.

Reproduce serially:

```sh
OPENGPU_TEST_SANITIZERS=1 PYTHONDONTWRITEBYTECODE=1 \
  python3 -B scripts/test_native_sample_graphics_owner.py
OPENGPU_TEST_SANITIZERS=1 PYTHONDONTWRITEBYTECODE=1 \
  python3 -B scripts/test_graphics_sample_owner_ipc.py
OPENGPU_TEST_SANITIZERS=1 PYTHONDONTWRITEBYTECODE=1 \
  python3 -B scripts/test_native_sample_graphics_owner_oracle.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_native_sample_graphics_controller.py
```

These tests and the separate canonical descriptor tests are in the common CPU
lanes. Their managed outputs are removed when their consumers exit.
