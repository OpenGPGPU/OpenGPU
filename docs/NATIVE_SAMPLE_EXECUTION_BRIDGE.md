# Native sample execution transport

`native_sample_owner_driver.cpp` is a fixed GNJ2 sample-host entry. Its profile
probe requires the ordered 112-word complete sample factory and five actual
auxiliary observations. It rejects old 96/104-word tables before submission.
The original native-v1 entry remains fixed to `OGCAP001` and its original table.

| Record | Magic | Contract |
| --- | --- | --- |
| Profile wire | `OGNP0002` | 112 ordered discovery pairs, five ordered auxiliary pairs; 952 bytes |
| Capture | `OGCAP002` | Original capture payload, exact 117 observations, completion plus twelve GNX checks |
| Held response | `OGNSH001` | Original raw native receipt fields, explicitly little endian; 92 bytes |
| Retired response | `OGNSR001` | Identical held fields plus idle/IRQ/drain observations; 108 bytes |
| Oracle authorization | `OGNSBA01` | u32 record count 1, only after independent returned-byte checks |
| Final authorization | `OGNSOK01` | u32 record count 1, only after observed context cleanup |

The fixed executor resets once, checks actual discovery, executes the captured
production enqueue trace, and records actual held GNX, raw reader fields and
64-bit append telemetry. It then waits for the live driver's `OGRTR001` drain
and POP trace. It preserves the original post-completion memory through
retirement and exports that memory for independent images/sample/GNY2 checks.
Only the subsequent oracle authorization permits the original owner's PTE
revocation and shootdowns. The original cleanup trace/ACK protocol still requires
all captured leaves revoked and both SATPs Bare before context release.

CPU ASan/UBSan parser and MMIO-peer checks pass for all discovery-word mutations,
cross-container/factory rejection, dependent-probe ordering, successful native
completion constraints, raw reader/64-bit telemetry retention and the C++ to
Python receipt wires. The unchanged native-v1 CPU suite also passes. The peer
injects completion values and executes no shader; it cannot qualify a DUT,
sample builtin, returned image, physical clock or FPS.

```sh
PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 \
  python3 -B scripts/test_native_sample_owner.py
PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 \
  python3 -B scripts/test_native_owner.py
```

Fresh complete caller compilation, generation and actual same-host execution
remain required. The transport does not transfer any previous model, timing or
six-scene qualification to the 112-word factory. Complete API sample state,
dynamic interpolation and the two-CU 640×480/60 FPS product gate remain separate.

The live controller checks the exact prepared source root and takes both the
shared model lock and the independent sample-executor lock before probing. It
checks the complete qualification again before submission and after observed
cleanup. The capture binds the original nonzero enqueue identity, actual Sv32
ASID/root, job descriptor and identical vector/instruction address spaces.
Original packet bytes must match the actual private RX mapping. This low-level
packet binding alone does not establish original ESSL compilation provenance.

The independent byte oracle checks the fixed fresh 4×4 full-coverage point at
1/2/4 samples, all four RGBA32F images and D32F depth, with no resolve. Sample
locations, one-hot input masks, unsigned/signed conversions, truncation,
varying slot 15 and both killing/preserving output masks follow the committed
source expressions without interpreting instructions. It checks actual rewritten
GFS sample/depth policy before DUT launch. Only exact image views, shader scratch
prefixes, one 23-line primitive record, the page header/first reference, one tile
directory and the original full GNY2 lease may change. All other physical bytes,
including page tables, descriptors, RX and page-rounded backing tails, stay
guarded. Oracle authorization precedes PTE revocation; final authorization follows
observed synchronous context cleanup. Process groups and any named task container
must be proved ended before temporary inputs are deleted.

CPU tests pass for all 21 case/sample preflights from actual Linux captures and
for synthetic returned-image/complete-GNY2 positives and source/guard negatives.
Those synthetic returns execute no shader and do not complete the live owner.
Absent model qualification or a copied checker is rejected before any probe.

```sh
PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 \
  python3 -B scripts/test_native_sample_owner_oracle.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_native_sample_gny2_oracle.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_prepare_owner_executor.py
```

`native_sample_gny2_oracle.verify(data, command_id, samples)` independently
constructs all 4096 expected GNY2 words from the committed ABI catalog for the
fixed live 4×4 ordinary-point recipe: one full-frame primitive, identities
7/8/9, four RGBA32F and D32F clear/store views, and 1/2/4 samples. It checks all
628 fields, including the complete zero fault/restart trees, full 145-bit
counters, high field padding and 3330 reserved words. The original enqueue ID
must come from the captured submission, and the caller must separately verify
the recipe, images, immutable guards and successful held publication.

CPU regression passes mutations of every used/reserved word and every field's
highest allocated bit, including counter bits above 64. This checks rejection
and expected semantic traffic counts; it provides no observed DUT timing or
execution result. Reproduce without generation or a JVM:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_native_sample_gny2_oracle.py
```

## Guarded executor build

`prepare_owner_executor.py --kind native-sample` admits only
`two-cu-vga60-owner-v2-sample-stage` / `geometry-compute-sample`, validated by the
existing exact112 preparation and complete assertion-enabled model identity.
It selects `native_sample_owner_driver.cpp` from source, records its complete
shared include closure, and stores its executable, builder snapshot and
qualification exclusively under `executors/native-sample`. An independent
lock in that directory supplements the common lock protecting the shared shard
cache. Native-v1 remains96-only; the four compute factories keep their separate
driver and qualification contracts.

The new kind adds mandatory protocol, controller, independent oracle, producer,
fixture generators, native compiler/frontend and all seven original ESSL
sources to the existing checker contract. It also binds the actual C producer's
recursive local include closure, including Linux submit/scheduler/MMU/privateRX
sources and fixture stubs. Only fixed generated include names may be supplied
by the registered generators; absent consumers or unresolved local dependencies
fail qualification, including the independent GNY2 oracle. The C++ builder rechecks declared includes, compiler and
options, all generated shards, source/RTL/assertion/generation identities,
builder snapshot and executable hashes. Rehashing a record cannot silently
omit a new runtime header. These are build guards, not execution results.

CPU guard tests use tiny explicitly modeled binaries and copied source trees;
source mutation tests never edit an active original ESSL or driver file:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_prepare_owner_executor.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_prepare_native_owner_flashsim.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_prepare_native_sample_owner.py
```

Local CPU validation passes all thirteen executor guard tests, five owner
preparation tests and two sample preparation tests. New negatives cover stale
or cross-kind models, omitted dependencies, original C/ESSL/compiler changes,
missing controller/oracles/catalog, changed shared headers and checker aliases.
All mutation fixtures are private copies and their outputs are removed.

After a fresh complete112 preparation and assertion-enabled generation exist,
build and recheck the fixed executor with:

```sh
python3 -B scripts/prepare_owner_executor.py --stage build --kind native-sample \
  --owner-output "$OWNER_OUTPUT" --flashsim-dir "$FLASHSIM" \
  --shards 32 --jobs 1 --shard-opt=-O2
python3 -B scripts/prepare_owner_executor.py --stage check --kind native-sample \
  --qualification "$OWNER_OUTPUT/executors/native-sample/qualification.json" \
  --executor "$OWNER_OUTPUT/executors/native-sample/executor"
```

This integration has not built or executed a real DUT/model. A CPU fixture's
qualification is never reusable by the actual112 host. The independent
controller must request `verify_qualification(..., kind='native-sample')` before
probing or starting the live producer, and must check actual returned bytes
before permitting the original owner's cleanup.
Binding compiler sources in a build record does not establish provenance for
an arbitrary runtime `OGNSF001` artifact. The low-level controller must bind the
supplied program bytes to the actual descriptor/private RX and keep original
ESSL provenance qualification separate until its full compiler entry exists.
