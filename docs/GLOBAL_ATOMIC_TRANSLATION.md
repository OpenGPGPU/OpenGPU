# Global atomic translation

## Explicit cached compute kernargs

`OPENGPU_RESOURCE_CACHED` (resource flag bit 17) opts a compute-kernarg binding
into cached private Sv32 pages. It requires an enabled private VM; other
resource types and the combination with `OPENGPU_RESOURCE_UNCACHED` are
rejected. Existing bindings without this flag retain their uncached policy.
The driver invalidates the physical range before publishing the cached PTEs.
CPU writes after binding require rebinding or explicit cache invalidation;
compute submit does not automatically invalidate the original kernarg data.

The distinct `global-atomics-cached` qualifier keeps all 24 original RV32
programs, words, operands, four scalar callers in sixteen-lane workgroups,
original GEMs and private code snapshots. It requires exact cached/RWX/A/D
leaves (`0xcf`) on both argument pages instead of the original UC2 leaves
(`0x2cf`), without weakening code, permissions, launch, old-value or complete
physical-byte guards. Cached and uncached source schemas and producer options
remain separate. The page-tail, next-page and final-word cases are retained.

The 64 focused source/qualifier/input checks pass, including a real production
Linux bind/rebind test under ASan/UBSan. That test checks both pages, default
and explicit policies, failed flag/VM admission, original binding retention
and complete allocation cleanup. Independently guarded cached and uncached
Linux caller builds pass all 48 original CPU captures, 888 rejection controls
and 16 compiler/header/object/link mutation controls. Actual compilation uses
the three original native units, full MD closure and fixed compiler image;
all 238 declared software inputs remain bound. Ended CPU capture/build outputs
are removed. These are CPU admission and transport results, not GPU execution.
The 190 executor-input binding and affected-test selection checks also pass.

All 24 original cached programs now pass on the complete assertion-enabled
float host with two 16-lane/eight-warp CUs. The independent source oracle checks
all 192 scalar-caller ordered old values, final words and full physical guards,
including both argument pages and all endpoint cases. Every original caller,
held/retired receipt, PTE/context cleanup and native executor succeeds; each
case has its own reset. The controller, producer, outer qualifier and final
source/model checks exit zero.

Execution source `10b51e0f667b5c78c90a9c1a95859cc69685dc1f` binds 254 software
inputs, all byte-identical to merged
[#907](https://github.com/OpenGPGPU/OpenGPU/pull/907)
`fdcdbe1e09440fbea2ac21726723ba6efeb7f4f9`. The additional post-run executor
check and Git-blob comparison pass; all consumers and ended capture/simulation
data are removed. This result retains that exact software scope. Warm L1/L2
residency, accepted-write-error refetch and cached/UC same-GEM coherence keep
their distinct full-host matrices. Forced reservation waits remain open;
complete resources, physical clock and FPS retain their separate plan gates.
P10 remains open at 9/40.

```sh
env -u SDKROOT -u CPATH PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=scripts \
  /usr/bin/python3 -B -m unittest test_compute_cached_kernarg \
  test_compute_global_atomic_source test_qualify_compute_owner \
  test_compute_counter_source test_compute_global_host_inputs
env -u SDKROOT -u CPATH PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=scripts \
  /usr/bin/python3 -B -m unittest test_prepare_owner_executor test_changed_test_suites
```

For guarded CPU captures, add `--cached-kernarg` to the original sanitized
`compute_global_host_producer.py --capture-only` command, then check all
captures with `test_compute_global_atomic_source.py --cached-kernarg` and
the matching discovery JSON. For actual execution, freshly build the original
compute executor for these software sources against its matching complete
float model, then run:

```sh
python3 -B scripts/qualify_compute_owner.py \
  --executor "$COMPUTE_EXECUTOR" --executor-qualification "$COMPUTE_QUALIFICATION" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python "$QUALIFICATION_PYTHON" \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case global-atomics-cached --timeout 7200
```

## Source-generated cache warming and independent histories

The source helper `compute_cache_alias_cases.py` retains every original AMO,
operand and boundary, with ordinary one-element vector loads before and after
the operations. The first two resident callers synchronize through a separate
word using bounded forward polling; failure to meet the barrier cannot return
the required AMO results. All 24 derived programs pass actual production
shader admission. Independent decoding checks the eight original target
operations/comparisons and both 64-attempt forward barriers; upload and guard
checks also pass. Target words remain at the original base, page-tail,
next-page and final-word offsets. Barrier and observation words use separate
64-byte lines; prefix counter AMOs are not target AMO write attempts.

The distinct `opengpu-compute-cache-alias-oracle-v1` contract requires both
initial vector observations to equal the original seed and the barrier to
reach two. The independent history oracle retains all eight original AMOs
and per-caller vector read/AMO ordering. It allows ordinary reads to observe
other callers' later writes. Committed-first-write fault fixtures additionally
require the original admitted prefix, exact surviving AMO attempt count,
committed failed value and preserved failed/unadmitted result guards. All
current and previous physical allocation bytes remain checked. These CPU
histories do not execute the programs or observe RTL cache state.

The original production Linux caller accepts `--cache-alias-source` with
`--cached-kernarg` and either `--capture-only` or the distinct
`--cache-alias-live` mode described below. All 24 derived programs pass production
shader admission and guarded ASan/UBSan Linux captures with the exact cached
leaves on both original argument pages. Their 960 rejection controls and eight
native compiler/header/object/link controls pass. The unchanged UC and normal
cached modes also pass: the complete comparison comprises 72 captures, 1848
rejection controls and 24 native input controls, with all 242 declared software
inputs bound. Ended capture/build outputs are removed. The 37 focused
source/oracle/transport and legacy recovery tests and 190 executor-input and
test-selection checks pass. Another 28 common owner, atomic-order, counter and
original-source checks pass, including the existing Linux lifecycle controls.

The live controller now uses the native read-only cache observer through the
distinct recovery matrix below. CPU source checks do not prove L1/L2 residency, fault recovery/refetch
or cached/uncached same-GEM alias coherence. Those full-host gates remain
required, and P10 remains open at 9/40. Reproduce the CPU checks with:

```sh
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=scripts \
  python3 -B -m unittest test_compute_cache_alias_cases test_compute_cache_alias_source \
  test_compute_global_atomic_source test_compute_cached_kernarg \
  test_compute_global_host_inputs test_compute_global_accepted_recovery test_compute_global_recovery
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=scripts \
  python3 -B -m unittest test_prepare_owner_executor test_changed_test_suites
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=scripts \
  python3 -B -m unittest test_compute_owner test_compute_atomic_order \
  test_compute_counter_source test_compute_global_atomic_cases
```

Inside the same fixed compiler image and with a fresh bound-input request,
capture and check the derived source separately from normal cached programs:

```sh
python3 -B scripts/compute_global_host_producer.py \
  --build "$CAPTURE_BUILD" --profile-wire "$DISCOVERY_WIRE" \
  --bound-inputs "$INPUTS_REQUEST" --case all --capture-only --sanitize \
  --cached-kernarg --cache-alias-source
python3 -B scripts/test_compute_global_atomic_source.py \
  --captures "$CAPTURE_BUILD/captures" --profile-json "$PROFILE_JSON" \
  --cached-kernarg --cache-alias-source
python3 -B scripts/test_compute_global_host_inputs.py \
  --native-build "$CAPTURE_BUILD" --bound-inputs "$INPUTS_REQUEST"
```

## Read-only complete-model cache observation source

`compute_cache_alias_observer.hpp` reads both actual hierarchical CU instances
and the complete float owner's shared L2. It checks all 64 sets/two ways of
each L1 and all four 128-set/four-way L2 slices, using logical valid bits and
tags rather than directory hints. It reads no cache data arrays. Independent
instance roots, model names and contexts are checked before observing them.

The observer records settled pre-edge cache state and AXI/invalidation
handshakes. Its bounded history requires simultaneous two-CU/L2 warming,
both invalidate requests and ACKs before the first full-line lower write,
and absence of L1 copies before every target lower write. For a first-write
B error, it additionally requires the invalidated L2 line, a paired full
64-byte target AR/R transaction through its final beat, and subsequent L1/L2
refill. Other read IDs cannot supply that transaction's completion. Prefix
cold maintenance cannot satisfy the warmed AMO's invalidate acknowledgements.
The separate strict Python receipt checker rejects missing/retyped fields,
wrong target/command/phase, clocks, masks, write counts and error histories.

Complete-float compute builds explicitly include and bind three generated
headers: the parent root, the actual CU model and its root. The compiler's
full MD closure must contain all three. Missing, changed, aliased or omitted
headers reject qualification; other factory/kind combinations retain their
ordinary build path. The native driver instantiates the actual model view at
compile time. Source and model checks remain distinct and complete.

The 202 focused snapshot/receipt, executor-input and test-selection checks pass.
ASan/UBSan CPU fixtures cover every set/way, independent CUs, real port shapes,
pre-edge overloads, response reordering, two-beat reads and missing handshake
evidence. The exact complete model's types and both step overloads compile and
link; two actual CU instances also pass cold read-only access checks. The full
original compute driver compiles/links and passes its actual complete-float
MMIO discovery probe, with 1050 actual MD inputs. Each native stage runs under
an 8-GiB/600-second guard. All model/archive/runtime bytes remain unchanged.
The same fixed Linux compiler image passes all 72 UC/cached/derived-source
captures, 1848 rejection controls and 24 native build controls with 246 bound
software inputs. All ended native and CPU proof outputs are removed.

This delivers the read-only observer, timing hook and input binding. The live
matrix controller has not attached the hook yet. These checks do not establish
warm cache execution, accepted-write-error refetch or cached/uncached same-GEM
coherence. The required full-host matrices remain pending; P10 stays open at
9/40. Reproduce the component checks with:

```sh
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=scripts \
  python3 -B -m unittest test_compute_cache_alias_observer \
  test_prepare_owner_verilator_executor test_prepare_owner_executor test_changed_test_suites
```

For the same matching complete-float owner, rebuild and probe the original
compute executor with the bound generated headers and full dependency checks:

```sh
python3 -B scripts/prepare_owner_executor.py --stage build --backend verilator \
  --kind compute --owner-output "$OWNER_DIRECTORY"
python3 -B scripts/prepare_owner_executor.py --stage probe --backend verilator \
  --kind compute --qualification "$OWNER_DIRECTORY/executors/compute/qualification.json"
```

The MMIO probe is discovery validation; source warming and coherence still
require their subsequent original-program execution and independent byte gates.

## Cache-warm committed-write recovery implementation

The distinct `global-atomics-cache-alias` qualifier connects the 24 derived
ordinary-read/AMO programs to 48 committed-first-write-error and healthy phases.
The native runner retains one complete assertion-enabled float DUT, RAM,
initial reset and original command ID. Each original Linux source uses its
assigned disjoint physical namespace. Neither the ordinary cached matrix nor
the uncached matrix can select this source schema or its receipts.

Each round binds the physical target line to the independently checked
original argument allocation and source offset. The native runner observes
actual settled cache state and invalidation/AXI handshakes throughout execution
and retirement. It publishes its cache observation before the retired receipt;
the controller requires both the strict observation and independent
ordinary-read/AMO histories plus all current/prior RAM bytes before replying to
the original caller or authorizing cleanup. A failed cache proof or byte check
prevents cleanup and the next owner. Round start/end clocks bind the proof to
the same native command and monotonically advancing simulation.

Failed phases commit the first target write before returning its B error.
The observer requires both actual L1s and L2 to be warmed before invalidation,
both L1 invalidation acknowledgements before the first target write, then
actual L2 invalidation, a paired 64-byte lower refetch and subsequent cache
refill. Surviving callers' attempts must equal the native error receipt; the
healthy phase retains all eight original target operations. Real failed
fences/userspace EIO, PTE/context/MMU cleanup and external drain remain required.

All 279 focused source, observer, controller, qualifier and input-binding checks
pass. Guarded original Linux builds pass 72 UC/cached/derived CPU captures,
1848 source rejection controls and 24 native compiler-input mutation controls.
Two additional original C live-mode captures reach READY and remain blocked
without DUT receipts; 80 source negatives and eight mixed-option rejections
pass. These are CPU admission/transport controls, not shader execution.

The complete original compute driver compiles and links against the unchanged
full float model with all 249 software inputs and three generated cache headers
bound. Its full compiler dependency closure contains 1051 files; actual MMIO
discovery passes. These results validate native integration, not the 48-phase
shader execution. The complete cache-warm matrix, the original 24-program
normal cached matrix and actual UC/cached same-GEM alias coherence still await
their separate full-host runs. The current source performs AMOs through cached
kernargs; it does not implement the UC alias gate. P10 remains open at 9/40.

Reproduce the source/controller and binding checks with:

```sh
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=scripts python3 -B -m unittest \
  test_compute_cache_alias_recovery test_compute_cache_alias_observer \
  test_compute_cache_alias_source test_compute_global_accepted_recovery \
  test_compute_global_recovery test_qualify_compute_owner \
  test_prepare_owner_executor test_prepare_owner_verilator_executor \
  test_changed_test_suites test_compute_global_host_inputs
```

Inside the fixed compiler image, after the guarded alias CPU capture build
above, exercise the original C caller's distinct live flags and refusal to
advance without actual DUT receipts:

```sh
python3 -B scripts/test_compute_cache_alias_recovery.py \
  --native-source-build "$CAPTURE_BUILD" --profile-wire "$DISCOVERY_WIRE" \
  --profile-json "$PROFILE_JSON" --bound-inputs "$INPUTS_REQUEST"
```

For the pending complete execution, rebuild the source-qualified original
compute executor against its matching full float model, then run:

```sh
python3 -B scripts/qualify_compute_owner.py \
  --executor "$COMPUTE_EXECUTOR" --executor-qualification "$COMPUTE_QUALIFICATION" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python "$QUALIFICATION_PYTHON" \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case global-atomics-cache-alias --timeout 14400
```

## Explicit same-GEM UC/cached source admission

A compute job may explicitly bind the same GEM through a cached kernarg and
an `OPENGPU_RESOURCE_UNCACHED` storage view. Both policy flags are required.
Every view remains checked against other objects' backing ranges; coincident
DMA addresses, shared reservations, shader aliases and the default kernarg
alias policy remain rejected. Images retain their existing kernarg-disjoint
rule. The two data views retain one job data reference and the strongest
write reservation. Scalar word AMOs are disabled for this alias profile;
ordinary kernarg accesses remain bounded and UC operations use the checked
storage descriptors.

`compute_uc_cache_alias_cases.py` derives all 24 original operation/endpoint
cases. Four sixteen-lane workgroups each use one active vector lane for their
two checked storage AMOs. All original operands, comparisons and target offsets
0, 4092, 4096 and 8188 remain. Ordinary vector loads use the cached kernarg
mapping before and after the UC operations. The first two callers record their
own initial loads and increment a separate UC ready word. A shared forward
poll retains all 64 attempts, fits the existing 512-word binding limit, and
prevents target operations when readiness is not observed. Neither the shader
limit nor physical CU/lane/warp capacity is reduced.

The distinct `opengpu-compute-uc-cache-alias-oracle-v1` source contract requires
the same original handle/view, one enabled RW GSB1 descriptor and three disabled
rows. Both cached leaves (`0xcf`) and UC leaves (`0x2cf`) must resolve both pages
and every selected target to the same physical word. Source/private-code bytes,
the private descriptor allocation, one retained data GEM and two actual temporary
maps are checked. Independent ordinary-read/AMO histories and all physical bytes,
including descriptor and previously cleaned RAM guards, remain required.

All 283 focused source, Linux-admission, oracle, qualifier and binding checks
pass. Actual ASan/UBSan Linux admission covers every derived program and ten
policy/identity/reservation profiles. Independent binary decoding covers all
96 caller paths, original operands and both the last successful poll and
64-attempt failure path. The complete float-profile Linux/MMU regression passes
its 208 image-byte, 208 failed-receipt and 208 reset cases plus bad-ACK resource
retention controls. These are CPU transport checks, not shader execution.

Guarded original Linux builds in the fixed compiler image pass all four source
modes: 96 captures, 4080 source rejection controls and 32 compiler/header/object/
link mutation controls. The new UC mode contributes 24 captures and 2232
rejections, including each descriptor word and both aliases' PTEs. Eight actual
C option rejections pass; the existing cached live-source receipt-wait controls
also pass. All 252 software inputs remain bound and ended CPU outputs are removed.
The complete original float compute driver additionally compiles/links and passes
actual MMIO discovery with three cache headers and 1051 full compiler dependencies;
all 4497 model objects/archives and the bound headers remain byte-identical.

Reproduce the focused and original Linux lifecycle checks with:

```sh
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=scripts python3 -B -m unittest \
  test_compute_uc_cache_alias test_compute_cached_kernarg \
  test_compute_cache_alias_source test_compute_cache_alias_recovery \
  test_compute_global_atomic_source test_compute_global_host_inputs \
  test_qualify_compute_owner test_prepare_owner_executor \
  test_prepare_owner_verilator_executor test_changed_test_suites test_compute_atomic_order
OPENGPU_TEST_SANITIZERS=1 PYTHONDONTWRITEBYTECODE=1 \
  python3 -B scripts/test_compute_host.py --image-float-stage
```

Inside the fixed compiler image and with an original complete-float discovery
and fresh bound-input request, capture this mode separately:

```sh
python3 -B scripts/compute_global_host_producer.py \
  --build "$CAPTURE_BUILD" --profile-wire "$DISCOVERY_WIRE" \
  --bound-inputs "$INPUTS_REQUEST" --case all --capture-only --sanitize \
  --cached-kernarg --uc-cache-alias-source
python3 -B scripts/test_compute_global_atomic_source.py \
  --captures "$CAPTURE_BUILD/captures" --profile-json "$PROFILE_JSON" \
  --cached-kernarg --uc-cache-alias-source
python3 -B scripts/test_compute_global_host_inputs.py \
  --native-build "$CAPTURE_BUILD" --bound-inputs "$INPUTS_REQUEST"
python3 -B scripts/test_compute_uc_cache_alias.py \
  --native-build "$CAPTURE_BUILD" --profile-wire "$DISCOVERY_WIRE" \
  --bound-inputs "$INPUTS_REQUEST"
```

The captures above establish source admission and transport only. The distinct
live UC matrix below connects the continuous protocol, observer and failure
receipts. Actual two-CU/L2 residency, invalidation/refetch, accepted errors and
real cleanup for these UC aliases remain P10 gates. P10 stays open at 9/40;
complete resources, cross-command coherence, clocks and FPS retain their
separate plan requirements.

## Same-GEM UC/cache recovery implementation

`global-atomics-uc-cache-alias` connects all 24 derived programs to 48 alternating
committed-write-error/healthy phases. The original Linux caller uses its exact
cached kernarg and UC storage view, checked descriptor and ordinary cached
loads. Each owner waits for actual held/retired receipts and independent cleanup
authorization. The native runner keeps one complete assertion-enabled float DUT,
one RAM, one reset and the original command ID; each phase retains previous RAM.

UC matrix, target and fault plans have distinct `OGCUAM01`, `OGCUL001` and
`OGCUF001` signatures and a separate round schema. The native
`uc_cache_alias_accepted_write` failure mode derives three, five or seven target
attempts from complete pairs of surviving caller old values and the original
fail-fast dispatch prefix. The legacy SSBO five-attempt and integer-image
nine-attempt requirements remain intact. Independent source histories verify
the first committed AMO, surviving old values, final target and all physical
bytes, including descriptors, page tables and previous allocations.

The existing read-only complete-model observer supplies both CU L1s and all four
L2 slices. Before the controller returns the original retired response, it
requires both warm L1s/L2, real invalidate handshakes and ACKs, exact target AW/B
pairing, and ordinary-read refills. UC atomics invalidate the resident L2 line
at lookup, before sending L1 invalidations. Their distinct
`opengpu-compute-uc-cache-alias-observation-v1` proof requires that early drop
after observed two-CU/L2 warmth, followed by both L1 acknowledgements. Every
target write must have its own full-line lower AR/R after the preceding target
B response, with no intervening cache allocation. Both L1s and L2 must remain
absent at the write response and its retirement edge. A cached refill cannot
authorize a later UC write, and the cached observer retains its original
requirement that L2 is present at the first L1 invalidation.

An accepted error additionally requires an observed invalid L2 after the error,
a matched lower AR/R refetch and later cache refill. Both the
outer qualifier and controller require the bound Verilator cache headers; CPU
captures cannot substitute for this proof.

All 303 focused checks pass, including the 48-phase CPU protocol peers and bad
observation/returned-byte controls that prevent cleanup and the next owner.
Sanitized C++ controls cover early versus late L2 invalidation, every UC
authoritative read, missing/short/incomplete reads, intervening cached fills,
and allocation at either B observation edge. The Python checker rejects missing
or mistyped bypass witnesses and substitution between cached and UC schemas.
Four original Linux capture modes pass 96 captures, 4080 source rejections and
32 compiler/header/object/link controls with all 258 software inputs bound.
The new actual C live-source controls reach two READY captures and wait for DUT
receipts; 186 source, six controller-mode and twelve C option negatives pass.
Existing cached live-source and UC capture controls also pass. These controls
execute the original C admission path without executing a shader.

The complete-model executor compiles, links and passes actual MMIO discovery
with three generated cache headers and 1051 compiler dependencies. All 4497 model
objects/archives and the three headers remain byte-identical. Ended CPU/native
outputs are removed after their consumers exit. Reproduce the focused checks:

```sh
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=scripts python3 -B -m unittest \
  test_compute_cache_alias_observer test_compute_cache_alias_cases \
  test_compute_cache_alias_source test_compute_uc_cache_alias test_compute_uc_cache_alias_recovery \
  test_compute_cached_kernarg \
  test_compute_cache_alias_recovery test_compute_global_atomic_source \
  test_compute_global_host_inputs test_qualify_compute_owner test_prepare_owner_executor \
  test_prepare_owner_verilator_executor test_changed_test_suites test_compute_atomic_order \
  test_compute_reservation_admission
```

After a fresh guarded UC CPU capture in the fixed compiler image, exercise the
original live-source controls with:

```sh
python3 -B scripts/test_compute_uc_cache_alias_recovery.py \
  --native-source-build "$CAPTURE_BUILD" --profile-wire "$DISCOVERY_WIRE" \
  --profile-json "$PROFILE_JSON" --bound-inputs "$INPUTS_REQUEST"
```

Rebuild the source-qualified complete-float executor from these exact software
inputs before attempting the distinct full-host qualification:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/qualify_compute_owner.py \
  --executor "$EXECUTOR" --executor-qualification "$EXECUTOR_QUALIFICATION" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python "$OWNER_PYTHON" \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case global-atomics-uc-cache-alias --timeout 14400
```

The implementation and CPU/native-build checks above pass. The complete UC
48-phase results are below. The cached warm 48-phase gate remains separate;
normal cached 24-program execution passed in its stated earlier source scope.
P10 is not closed.

## Complete-owner UC/cache-alias execution

All 48 alternating committed-write-error/healthy phases pass on one complete
assertion-enabled float DUT, one retained RAM, one reset and the original command
ID. The 24 derived original programs preserve every required operation, operand
and page endpoint. The independent source oracle verifies 240 returned old
words, source-order constraints, ordinary cached reads, final target words and
all current and preceding physical allocations. All 24 target B errors are
physically injected and accepted after their writes commit; there are zero
read errors. Real EIO/healthy Linux fences and PTE/context/MMU cleanup pass.

Read-only actual cache witnesses pass in every phase: both CU L1s and L2 warm
before the UC access; L2 drops before both L1 invalidations and acknowledgements;
each target write has its own authoritative lower AR/R; target B responses and
retirement allocate no cache line. Error phases additionally observe invalid L2,
matched lower refetch and subsequent ordinary cached refill. The original
retired response and cleanup authorization remain gated on those witnesses and
independent byte checks.

The exact execution source is `1c5f65b6e54527c65d4e9ba389ef99011f36b570` with
258 qualified software inputs, byte-identical to merged #909 at
`95ec6b9f00b8f616750e181b8338906cb9df1869`. Native, controller, original producer,
outer qualifier and final input checks all return zero. An explicit final
executor check and all 258 source hashes/Git blobs also pass; the original
execution source is clean, and its container, process consumers and temporary
session are gone. No raw process data is retained. The later 262-input
reservation implementation has its own pending actual matrix and does not
change this result's source scope.

Reproduce from that exact software source and its source-qualified complete-float
executor with the `global-atomics-uc-cache-alias` command above. These results
close this UC alias execution gate. Warm cached recovery and forced reservation
waits remain separate P10 requirements; P11 GPU-producer/cross-domain ordering,
full resources and product qualification remain open.

## Cross-context reservation admission source

The CPU OS boundary attributes each reservation-origin dependency to its actual
`dma_resv` and usage. Device admission-chain and input-sync dependencies keep
separate provenance. The new reservation fixture calls the original Linux
compute ioctl twice using two fresh contexts, separate scheduler entities,
original GEM handles and immutable code snapshots, with no input sync object.
Both jobs remain pending and have never reached hardware.

All 24 original RV32 programs pass with default UC and explicit cached argument
pages, using both shared and disjoint original GEMs: 96 pairs and 192 original
Linux submissions. ASan/UBSan checks both pages, context/root and original-object
identities, pending fence sequences, exact private code and unchanged data.
Unexecuted scheduler cancellation consumes the real ECANCELED fences through
normal context destruction; malformed code and partial admission also release
all allocations and references.

All 286 focused checks and the complete float Linux/MMU CPU regression pass,
including 208 image-byte, 208 failed-receipt and 208 reset cases and bad-ACK
resource retention. The fixed compiler image also runs the 96-pair C test with
GCC/ASan/UBSan and 192 unchanged full-MD dependencies. Four original Linux
capture modes pass 96 captures, 4080 source rejections and 32 native-input
controls with all 258 software inputs bound. The complete-model executor
compiles, links and passes MMIO discovery with three cache headers and 1051
compiler dependencies; all 4497 model objects/archives and headers remain
byte-identical. Ended CPU and native-build outputs are removed.

The disjoint control remains pending on the device-wide retirement chain.
The shared-GEM case has an additional pending reservation WRITE-fence edge,
identified independently by its reservation pointer, usage and producer fence.
Thus cross-context pending state alone cannot establish a forced GEM wait.
This stage proves admission and attribution only: actual forced waiting still
requires an independent producer edge, blocked dispatch/wait, genuine producer
completion, actual consumer shader bytes and native cleanup/continuity proof.
It provides no alternate legacy capture or hardware-dispatch path for the two
queued owners, and does not close P10's reservation gate.

Reproduce the CPU controls with:

```sh
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=scripts python3 -B -m unittest \
  test_compute_reservation_admission test_compute_uc_cache_alias \
  test_compute_uc_cache_alias_recovery test_compute_cached_kernarg \
  test_compute_cache_alias_recovery test_compute_global_atomic_source \
  test_compute_global_host_inputs test_qualify_compute_owner test_prepare_owner_executor \
  test_prepare_owner_verilator_executor test_changed_test_suites test_compute_atomic_order
OPENGPU_TEST_SANITIZERS=1 PYTHONDONTWRITEBYTECODE=1 \
  python3 -B scripts/test_compute_host.py --image-float-stage
```

## External original-GEM producer admission

The reservation fixture also exposes a distinct external CPU producer. Its
WRITE fence is published on the original 8192-byte GEM before any GPU job is
admitted, with no global admission/execution predecessor. Input bytes are
initialized before resource binding and must remain unchanged while blocked.
The consumer uses the ordinary original Linux compute handler, a fresh context
and no input sync object.

The blocked-attempt and producer-release entries require exactly one pending
dependency: the original GEM reservation's actual producer fence with
`DMA_RESV_USAGE_READ`. They reject disjoint GEMs, unrelated or untagged extra
dependencies, changed input bytes and altered provenance. The attempted
`compute_host_begin_execution` must actually return `-EBUSY` without a hardware
submission. Only a genuine blocked attempt permits successful CPU producer
signaling; that signal leaves the GPU completion fence pending. A pending
producer cannot be discarded. Explicit producer cancellation signals its own
ECANCELED fence, and unexecuted GPU cancellation/context destruction retain
their ordinary cleanup paths.

All 24 original programs run through both default-UC and explicit-cached
admission, with successful release and cancellation: 96 blocked cases and 48
disjoint controls. Both pages and all four boundary words are checked. Together
with the cross-context pairs, the fixture covers 336 main Linux jobs without
executing a shader. The 35 focused input/executor/admission checks pass.
Clang and fixed-image GCC ASan/UBSan checks pass; the latter binds all 258
software inputs, 192 full compiler dependencies and the actual link/object
inputs before and after execution. Ended outputs are removed.

These are CPU OS-boundary admission/lifetime results. The native idle proof,
controller-authorized producer release, real consumer execution and its
all-byte/cleanup checks require the distinct live protocol below. The forced
reservation-wait gate remains open until its actual full-DUT matrix passes.
Reproduce the focused source checks with:

```sh
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=scripts python3 -B -m unittest \
  test_compute_reservation_admission test_compute_global_host_inputs \
  test_prepare_owner_executor test_prepare_owner_verilator_executor
```

## Complete-owner external reservation wait implementation

`global-atomics-reservation-wait` connects all 24 original RV32 programs to
both default-UC and explicit-cached consumers, for 48 independent cases. Each
original C caller installs the external CPU producer after initialization and
binding, submits through the ordinary Linux compute handler, and actually
attempts execution while its unique GEM dependency is pending. It publishes
the checked source identity, `-EBUSY`, pending producer/consumer fences and zero
kernel submissions. The three-unit original caller build compiles the
reservation fixture, which includes the host fixture; it never compiles both
as separate units. Full source/header/link and immutable compiler-image guards
remain mandatory.

The controller requires this distinct blocked source before starting a complete
assertion-enabled float DUT. Native execution starts with empty RAM and checks
at least 257 cycles of READY, no IRQ, no external AR/AW/W offers and drained
traffic. Its `OGCRWP01` plan and idle certificate bind the case, policy, original
physical GEM and actual producer/consumer fence identities. Only a valid idle
certificate authorizes the CPU producer's `OGCRRL01` release. That release
leaves the GPU fence pending. The caller then prepares its original capture.

Independent ordinary source/code/PTE/cache-policy validation must pass before
the controller emits the separate phase-bound `OGCRGO01` execution authorization.
The native runner then uploads those original inputs and runs the consumer on
the same DUT without another reset. Original held/retired receipts, all eight
old words, final target, every physical guard and real Linux/native cleanup
remain required. A final native witness checks READY, no IRQ, zero SATP roots,
drained external traffic and the uninterrupted clock; an independent cleaned-RAM
comparison verifies PTE teardown and unrelated bytes. Each case has one reset;
this is an external CPU producer wait, with GPU-producer cross-domain coherence
remaining in P11.

All 311 focused checks pass, including strict source/idle/release/cleanup fields,
all 48 original producer commands, early-publication rejection, and sanitized
native idle controls at the first, middle and last observed cycles. The fixed
compiler image passes all four existing capture modes: 96 original captures,
4080 source rejections and 32 compiler/header/object/link controls. The actual C
reservation path additionally passes four blocked/released READY controls, 74
source negatives and nine mixed-option rejections, while waiting for real DUT
completion receipts. GCC ASan/UBSan also passes the 336-main-job reservation
fixture with all 192 full dependencies unchanged. These remain CPU controls.

The complete native executor compiles, links and passes actual MMIO discovery
with all 262 software inputs bound, three cache headers and 1053 full compiler
dependencies. All 4497 model objects/archives and the three headers remain
byte-identical. No RTL, assertions or supported capacities change. Ended CPU,
native and discovery outputs are removed after their consumers exit.

Reproduce the focused source checks and actual original C receipt-wait controls
with the same fixed compiler image and complete-float discovery:

```sh
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=scripts python3 -B -m unittest \
  test_compute_reservation_wait test_compute_reservation_admission \
  test_compute_cache_alias_observer test_compute_cache_alias_cases test_compute_cache_alias_source \
  test_compute_uc_cache_alias test_compute_uc_cache_alias_recovery test_compute_cached_kernarg \
  test_compute_cache_alias_recovery test_compute_global_atomic_source test_compute_global_host_inputs \
  test_qualify_compute_owner test_prepare_owner_executor test_prepare_owner_verilator_executor \
  test_changed_test_suites test_compute_atomic_order
python3 -B scripts/test_compute_reservation_wait.py \
  --native-source-build "$CAPTURE_BUILD" --profile-wire "$DISCOVERY_WIRE" \
  --profile-json "$PROFILE_JSON" --bound-inputs "$INPUTS_REQUEST"
```

After rebuilding the source-qualified complete-float executor, run the distinct
actual matrix with:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/qualify_compute_owner.py \
  --executor "$EXECUTOR" --executor-qualification "$EXECUTOR_QUALIFICATION" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python "$OWNER_PYTHON" \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case global-atomics-reservation-wait --timeout 14400
```

The complete 48-case actual-DUT wait/consumer matrix and its final source/model
checks remain pending. CPU controls, source compilation and MMIO discovery do
not close this gate or P10.

## Global AMO translation and prior component checks

Global RV32 word AMOs capture the data SATP root and virtual operand before
sharing the ordinary data page walker. They require readable and writable
translations, retain the page offset and PTE cache policy, and hold physical
requests and attributed replies under backpressure. They do not cache
translations across tasks. Unsupported physical PPN/root bits are rejected
instead of aliasing a 32-bit physical address. Existing ISA encodings remain
unchanged.

Uncached AMOs invalidate cached aliases and wait for their acknowledgements
before reading authoritative lower memory. They retire after the lower write
acknowledgement, allocate no cache line and preserve unrelated resident ways.
Fault reports retain the original virtual instruction operand.

Twenty-three translation/walker/execute/data-TLB/CU hardware cases and five
focused L2 atomic cases pass. CU tests execute twelve single-warp kernels across
payload-guard modes with a 16-lane/eight-warp configuration. They check SATP
remapping without a cached translation, denied mappings, PTE-fetch and physical
AMO access faults, held precise traps and healthy reuse. The external atomic
responder checks CU physical requests; actual read/modify/write behavior is
covered separately by L2 tests.

All 47 L2, local-memory and payload-atomic guard cases also passed on the initial
cache-policy change. The two uncached L2 cases use four CU ports with a
16-lane/eight-warp configuration and an eight-set, two-way cache. These checks
do not qualify whole-GPU coherence or full resource capacity.

PTE-fetch access faults retain their cause through the shared walker and data
TLB; invalid or denied mappings report page faults. Both AMO fault kinds retain
the original virtual operand. This follows the distinction in the
[Sv32 translation algorithm](https://docs.riscv.org/reference/isa/v20260120/priv/supervisor.html#_virtual_address_translation_process).

The Sv32 walker also propagates a non-leaf PTE's global bit to its leaf
translation, as required by the [supervisor specification](https://docs.riscv.org/reference/isa/priv/supervisor.html).
The walker test checks all combinations of parent and leaf global bits, held
responses and successive walks; the separate TLB test checks reuse across ASID
changes and ASID-specific flushes. These two suites passed locally (12 tests):

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.Sv32PageTableWalkerSpec opengpu.core.memory.VectorTlbSpec'
```

A subsequent focused case connects the real walker to the TLB. A global
non-leaf and non-global leaf produce a translation reused after an ASID switch
and scoped flush; a full flush forces a new walk. It passed locally:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.VectorTlbSpec -- -z "reuse a non-leaf global walk"'
```

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'testOnly opengpu.core.memory.GlobalAtomicTranslationSpec opengpu.core.memory.SharedDataPageTableWalkerSpec opengpu.core.execute.memory.SharedAtomicExecuteStageSpec opengpu.core.memory.Sv32PageTableWalkerSpec opengpu.core.memory.VectorTlbSpec opengpu.core.GpuComputeUnitGlobalAtomicVmSpec opengpu.core.memory.SharedL2CacheSpec opengpu.core.memory.BankedSharedMemorySpec opengpu.core.memory.ShaderPayloadAtomicGuardSpec'
```

A bounded four-CU integration case connects real instruction/data caches,
Sv32 translation, alias invalidation and all four L2 banks. Its 36 kernels
exercise twelve successful cached/uncached RMWs and sixteen precise faults:
denied mappings, PTE-fetch failures and physical AMO read/write failures.
Stale private aliases must reload the updated value after uncached AMOs.
Concurrent CUs receive distinct old values; each successful AMO waits for its
actual delayed lower write acknowledgement before retiring. Held traps and
completions, unchanged neighboring words and healthy command-ID/owner reuse
are checked. Only lower memory is modeled.

```sh
sbt -J-Xmx4G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'testOnly opengpu.system.GpuSystemGlobalAtomicVmSpec'
```

This fixture retains four 16-lane/eight-warp CUs and four L2 banks, with an
eight-set/two-way L2 and four-set/one-way private caches to bound simulation
cost. It does not qualify full cache capacity, sustained atomic throughput or
whole-GPU coherence. [Word compare/exchange](WORD_COMPARE_EXCHANGE.md) adds
captured comparison operands and matching/failed CAS through the same local and
global owners. General storage/image access, wider atomics, broader
ordering/contention and complete composed timing remain open. These checks do
not close H7 or the full mobile alignment goal.

An L2 atomic write error now invalidates the affected cached line: lower memory
may have accepted the write even when it returns an error. A bounded L2 test
models that outcome, preserves an unrelated line in the same set, and verifies
that the next access refetches memory before a later atomic. It does not claim
that a failed atomic is rolled back.
Another focused case holds two L1 aliases and checks that their invalidations
complete before the failed atomic write, then refetches the memory value.
The same bounded suite checks signed and unsigned min/max against `0xffffffff`
and 10 through L2, including returned values, byte masks and preserved bytes.

Cached word AMOs also retain the carry when forming their exclusive byte end.
Previously, an operation at byte offset 60 wrote the correct lower-memory word
but left its cached copy unchanged because the six-bit end wrapped at 64.
A focused regression reproduced that stale final word (355 instead of 362)
after an acknowledged add. The widened bound now merges all four final bytes.
The existing L2 suite passes four cases, including 32 consecutive cached adds
across all sixteen word positions. It checks exact lower write masks/data,
delayed acknowledgements, returned old values and full cached-line contents
after every operation. These component results do not qualify aggregate
resource coexistence or whole-system coherence.

```sh
sbt -J-Xmx2G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=1 -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  'set Test / parallelExecution := false' \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "L2AtomicWriteFaultSpec.scala").contains(f.getName))' \
  'testOnly opengpu.core.memory.L2AtomicWriteFaultSpec'
```

```sh
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.L2AtomicWriteFaultSpec'
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.SharedL2CacheSpec -- -z AMO'
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.L2AtomicWriteFaultSpec -- -z "drain shared L1 aliases"'
```

## Original global AMO source corpus

`scripts/compute_global_atomic_cases.py` defines 24 driver-level RV32 programs
for the existing writable-kernarg word-atomic gate. Six two-operation families
cover all ten relaxed word operations, including independently observable CAS
winners and misses. The target is at byte 0, 4092, 4096 or 8188 of an original
8-KiB kernarg, covering both sides of a page boundary and the final whole word.
Four workgroups each contain one sixteen-lane warp. Scalar AMOs execute once
per warp: there are four competing scalar callers and eight source events,
with eight distinct old-word slots selected by the original launch group ID.
The source preserves the immutable x1 root and uses bounded forward branches,
constant scalar-store offsets and a fence between each caller's two AMOs.

All 24 programs pass the compiled production validator. Their missing-feature
controls and 1,152 individual malformed AMO mutations reject, including aq/rl,
wide operations, LR, undefined addresses and writes to the immutable root.
Literal CAS histories check both winning-caller identity and every returned
old word. The source and existing atomic-order suite also pass with the native
admission helper built under ASan/UBSan. Source uploads contain only the initial
target and output guards.

These checks establish source admission, not actual GPU execution. Original
Linux submission/capture, private Sv32 mappings, complete-host returned bytes,
delayed acknowledgments, fault recovery and cached-alias behavior retain their
separate P10 acceptance gates. No generated kernel or CPU result is retained.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_compute_global_atomic_cases.py
```

The standalone `compute_global_host_producer.py` builds the production userspace
library, the original Linux/MMU boundary and a raw `opengpu_compute()` caller.
It binds the original shader at slot 1 and an independent original kernarg at
slot 2. Shader bindings retain the production 64-byte alignment with zero
padding after the final CEASE. Original source bytes, private code snapshots,
cached executable mappings, both exact UC2 kernarg pages and the sole 8-KiB
writable grant are checked independently from captured memory and MMIO.

All 24 original Linux CPU captures pass with ASan/UBSan, followed by 432
metadata/code/PTE/write-grant rejection controls and untouched-upload rejection.
The source/order suite (18 tests) and existing counter/image/source oracle suite
(33 tests) pass; these overlapping suites are not additive counts. Fabricated
CPU byte fixtures use literal histories for all six families, checking each old
word, the final target and every other original physical byte. OR operands set
previously clear bits, and signed-min operands cross the sign boundary so those
operations have observable effects even in a group-serial execution.
The complete float-owner Linux/MMU/IRQ/cleanup CPU transport regression also
passes under ASan/UBSan; its modeled receipts are not DUT execution evidence.
All 23 software-executor source/input-closure checks pass.

Reproduce CPU capture checks inside the compiler environment, using discovery
wire/JSON from the same complete float-owner probe:

```sh
python3 -B scripts/compute_global_host_producer.py --build "$CPU/build" \
  --profile-wire "$OWNER_PROFILE_WIRE" --case all --capture-only --sanitize
python3 -B scripts/test_compute_global_atomic_source.py \
  --captures "$CPU/build/captures" --profile-json "$OWNER_PROFILE_JSON"
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover \
  -s scripts -p 'test_compute_*source.py'
OPENGPU_TEST_SANITIZERS=1 PYTHONDONTWRITEBYTECODE=1 \
  python3 -B scripts/test_compute_host.py --image-float-stage
(cd scripts && PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest \
  test_compute_global_atomic_cases test_compute_global_atomic_source \
  test_compute_atomic_order test_prepare_owner_executor)
```

The complete-owner `--case global-atomics` entry binds the exact full float
executor, committed source archive and original software qualification before
starting its compiler image. It builds the three original native C units with
ASan/UBSan, discovers every actual header before compilation, and requires the
same complete `-MD` closure afterward. Exact native objects, the linker trace,
compiler/image tools, generated Linux bodies, qualified source, dependency
receipts and executable remain checked through all 24 original source owners.
This route requires no Mesa or NTT preparation. The existing controller checks
each source's independent old values/whole RAM before authorizing its real
held/retired/IRQ/PTE/context/MMU cleanup; each case resets its DUT.

All 24 guarded original Linux CPU captures and their controller READY bindings
pass, including 432 source/PTE/grant controls and eight real native compiler,
header, dependency, object, executable and link rejection controls. The 44
focused source/controller/compiler tests also pass. The complete assertion-enabled
2-CU, sixteen-lane/eight-warp float owner subsequently passed all 24 original
programs and all 192 returned scalar old words. Every case passed its caller
program order, legal shared-word serialization, final target and complete
physical-byte guards, followed by the real held/retired IRQ, PTE/context/MMU
cleanup. Each case used a fresh complete DUT/reset.

The original guarded producer, native children, controller and outer qualifier
all exited zero. Final compiler/header/link/runtime, source, executor and model
checks passed. Frozen execution source `4be83bdbc204128955339561516eb153943b5be6`
and the publication source matched all 227 qualified software files byte for
byte. The original full hardware model and assertions were retained; only the
software execution wrapper was rebuilt. Ended qualification data was removed.

```sh
python3 -B scripts/qualify_compute_owner.py \
  --executor "$COMPUTE_EXECUTOR" --executor-qualification "$COMPUTE_QUALIFICATION" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python "$QUALIFICATION_PYTHON" \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case global-atomics --timeout 7200
```

These CPU captures do not prove delayed ACKs, global fault recovery,
accepted-write/error cached aliases or cross-command coherence. P10 remains
open; generated CPU data is removed after its checks end.

## Complete-owner global delayed acknowledgements

The explicit `global-atomics-delayed-ack` entry retains all 24 original RV32
programs and the complete assertion-enabled float factory. A source-owned
`OGCGACK1` plan identifies the exact physical target line and eight original
old-value slots in the same kernarg GEM. The native executor withholds the
first actual due target B response for at least 257 cycles, preserving its
original AXI identity and payload. It checks the real completion register and
all 32 old-output bytes throughout the hold. Early completion or any returned
old word before ACK release aborts without authorizing owner cleanup.

After release, the existing independent source-order/final-word/whole-physical
byte oracle and real IRQ/PTE/context/MMU cleanup still apply to every case.
The global transport has its own plan/report kind and native mode; the existing
storage v1 plan and 37-cycle contract are unchanged. Both plans use the same
actual AXI response gate, not manufactured kernel results.

Six ACK/AXI guard tests pass with native ASan/UBSan, including exact source
windows, missing observers, changed old words, malformed plans, premature
completion and unrelated writes. The 47 focused source/controller/compiler
checks and 23 software input/closure checks pass. The complete assertion-enabled
2-CU, sixteen-lane/eight-warp float DUT passed all 24 original programs and
192 returned scalar old words. Every case held its actual target B response
for at least 257 cycles with the completion bit clear and all eight original
old-value slots untouched. After release, caller-ordered old values, legal
serialization, the final target, complete physical guards and real
held/retired IRQ/PTE/context/MMU cleanup all passed. Each case used a fresh
complete DUT/reset.

The original guarded producer, native children, controller and outer qualifier
all exited zero. Final compiler/header/link/runtime, source, executor and model
checks passed. Frozen execution source `feda7c7163e8c3167cfa887d03f7ee70c17b14eb`
and the publication source matched all 227 qualified software files byte for
byte after execution. The complete hardware model, capacities and assertions
were unchanged; only the software wrapper was rebuilt. Ended qualification
data was removed.

```sh
python3 -B scripts/qualify_compute_owner.py \
  --executor "$COMPUTE_EXECUTOR" --executor-qualification "$COMPUTE_QUALIFICATION" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python "$QUALIFICATION_PYTHON" \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case global-atomics-delayed-ack --timeout 7200
```

This delay gate does not qualify continuous global fault/healthy reuse,
accepted-write/error cached aliases, cross-command coherence, clocks or FPS.

## Complete-owner global physical faults and continuous reuse

The distinct `global-atomics-recovery` entry runs each of the 24 original RV32
programs through read fault, healthy, first-write fault and healthy phases.
All 96 phases share one complete assertion-enabled float DUT, RAM and initial
reset, retaining the original command ID. Original caller allocations occupy
disjoint 16-MiB physical namespaces; every previously cleaned RAM byte remains
in the independent comparison. Each phase requires actual held/retired IRQ,
PTE/context/MMU cleanup and external drain before the next original owner.
The cleanup decoder requires the raw caller's zero inactive contexts; the
existing image/storage capture contract retains exactly one inactive context.

The original production `opengpu_compute()` caller consumes failed receipts
and requires its real negative fence and userspace `EIO` result. Read faults
permit no RAM changes or target writes. A one-shot lower B error suppresses
the first target write. The grid stops creating offers on failure and drains
previously admitted work, including a registered offer. Successful callers
must form an admitted group prefix with exactly one failed initial caller
(group zero or one); failed and unissued callers retain both guarded slots.
Each survivor returns both source-ordered old words. Those words must have a
legal shared-word serialization and final target, with every other physical
byte unchanged. The target write count must equal one plus the returned old
word count, including the failed first attempt and lower writes for CAS misses.
The existing storage first-write contract retains its separate five attempts.

Seventy focused source/controller/compiler checks and existing atomic/source
regressions pass. CPU controls cover all source layouts, both initial failed
callers and each admitted prefix, legal interleavings, source-order rejection,
prior RAM guards, exact arguments and counters, and cleanup refusal after bad
partial results. The 23 source/input-closure regressions also pass.

All 96 phases passed on the complete assertion-enabled float DUT with one RAM,
initial reset and original command ID. The 48 healthy phases returned 384
source-ordered old words. The 24 suppressed-write failures returned 48 more:
each drained one surviving caller, returned both old words and made exactly
three target attempts, including the suppressed first write. Failed and
unissued callers retained their guarded slots. All final targets, current and
previous physical-byte guards, actual negative fences/userspace `EIO`, held
and retired IRQs, PTE/context/MMU cleanup and external drains passed. The 24
read-fault phases changed no RAM bytes; cumulative physical/injected counters
agreed at 48 read errors and 24 write errors.

The original guarded producer, native children, controller and outer qualifier
all exited successfully. Final compiler/header/link inputs and software/model
checks passed. Frozen execution source
`f18705382184deb986e52c12691be84a8518b47c` and publication matched all 231
qualified software files byte for byte. This qualifies 432 actual returned
old words and complete continuous fault/healthy cleanup for this corpus.

```sh
python3 -B scripts/qualify_compute_owner.py \
  --executor "$COMPUTE_EXECUTOR" --executor-qualification "$COMPUTE_QUALIFICATION" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python "$QUALIFICATION_PYTHON" \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case global-atomics-recovery --timeout 7200
```

The injected write is suppressed, so this gate makes no rollback claim for
an accepted write followed by an error. Accepted-write/error cached aliases,
cross-command coherence, clocks and FPS remain separate qualifications. P10
remains open.

## Complete-owner accepted first-write errors

The distinct `global-atomics-accepted-write-error` entry uses all 24 original
RV32 programs. Each runs a committed first-write error followed by a healthy
owner: 48 phases share one complete float DUT, retained RAM, original command
ID and initial reset. Original allocations occupy disjoint physical namespaces;
each phase checks all previously cleaned physical bytes as well as its uploads.

The physical AXI agent applies the original enabled W bytes after both AW and
WLAST are accepted, then returns the delayed failing B response. It counts
committed errors independently of target attempts and response errors. Other
writes succeed; rearming the existing suppressed-write mode restores its
original policy while preserving cumulative counters. The original caller
checks its failed fence and userspace `EIO`, then performs real retirement and
PTE/context/MMU cleanup before the next original healthy owner.

The failed caller returns neither old-value slot, but its first AMO has changed
the target before surviving callers execute. The independent oracle starts
their source-ordered serialization from that committed first result; a
rollback history is rejected. Surviving callers must match the admitted group
prefix, and their old-word count must bind exactly to physical target attempts.
Every other physical byte remains guarded.

Seventy-four focused source/controller/qualifier checks and 26 input/closure
checks pass. CPU fixtures cover both initial failed callers, all admitted
prefixes and source layouts, interleavings, literal old/final values, rollback
and source-order rejection, whole-RAM guards and cleanup refusal. Native
ASan/UBSan covers 26 AXI cases, including W-before-AW, byte enables, independent
read snapshots before a delayed/stalled failing B, later healthy writes and
rearming without commit or counter leakage.

All 48 phases passed on the complete assertion-enabled float DUT with one
RAM, initial reset and original command ID. The 24 healthy phases returned
192 source-ordered old words, and the 24 committed-write failures returned
48 more, for 240 actual old words. Every failure made three target attempts:
one committed write returning an error, then both AMOs of the surviving
caller. Failed and unissued old slots stayed guarded. All committed-first
histories, final targets, current/prior physical-byte guards, actual negative
fences/userspace `EIO`, held/retired IRQs, PTE/context/MMU cleanup and external
drains passed. Cumulative physical/injected/accepted write errors agreed at
24; no physical or injected read errors occurred.

The original guarded producer, native children, controller and outer qualifier
all exited successfully, including final compiler/header/link, software source
and model checks. Frozen execution source
`066de7f1e6608e26ed7550bb072e4e166ca142ee` matched all 233 qualified software
files in publication byte for byte. No RTL, capacity or assertion changes were
needed.

```sh
python3 -B scripts/qualify_compute_owner.py \
  --executor "$COMPUTE_EXECUTOR" --executor-qualification "$COMPUTE_QUALIFICATION" \
  --executor-checker scripts/prepare_owner_executor.py \
  --executor-checker-python "$QUALIFICATION_PYTHON" \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --case global-atomics-accepted-write-error --timeout 7200
```

Cached-alias behavior and cross-command coherence retain their separate
complete-host qualification. P10 remains open.
