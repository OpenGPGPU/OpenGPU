# Private native sample submission bridge

`native_sample_submit_producer.c` consumes the private compiler's `OGNSF001`
container and submits its original code bytes through the actual Linux native
version-2 ioctl, scheduler, private owner, shader validator and MMU. The current
entry is a **CPU protection test**: its VS is CEASE, and its final hardware kick
and retirement are modeled. It does not render, export a runnable DUT fixture,
write GNY2 success, enable API caps, or qualify sample builtins.

The container is eight ASCII bytes `OGNSF001`, then fifteen little-endian u32s:
version (1), fragment ABI, input arrays, array stride, prefix bytes, used system
values, color output mask, writes depth, writes sample mask, requires sample
owner, requires geometry owner, thread capacity, varying slots, color slots,
code words. Exactly that many little-endian instruction words follow; trailing
bytes, truncation and more than 512 words are rejected. The ordinary entry
requires ABI6, capacity 128, 16 varying slots, four MRT slots, 75 input arrays,
512-byte stride and a 47616-byte prefix. It rejects ABI7 because there is no
real GS draw/owner. Compiler metadata does not authorize execution: the public
sample discovery helper must validate the CPU profile fixture and the actual
kernel cache, GND and GFS must independently admit the program.

The build imports the unchanged 96-, 104- and 112-word source tables from
`owner_resource_contract.py`. Only the exact sample table can establish the
sample cache. These values are CPU test inputs, not observations of hardware.
Every accepted program is also submitted against a disabled sample cache, a
mismatched varying layout and an ordinary draw with ABI7; each must fail before
enqueue and preserve the caller fence. The successful private RX copy is checked
word for word with exact R/X and no W, then checked again after changing and
closing the original code GEM. The real retained reference survives until the
modeled retirement. Invalid instructions are rejected by the actual validator.

```sh
PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_sample_submit_bridge.py
# Feed actual ESSL frontend artifacts; never substitute the synthetic unit fixture:
PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_sample_submit_bridge.py --compiled-directory /path/to/private/compiler/output
# Frozen old capture schema and actual parser regression:
PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_mmio_capture.py --executor-root .
```

The unit suite's CEASE container tests parsing and ownership only. The compiled
artifact mode passes every actual ABI6 program through the protected RX path at
1x, 2x and 4x and requires at least one ordinary program; it rejects ABI7. It also
creates a live capture for each ordinary program and verifies that an unrelated
compute receipt is rejected without retirement or cleanup success. These CPU
checks do not execute either shader.

The compiler's real ESSL coverage and reproduction are documented separately in
`NATIVE_SAMPLE_ESSL_BRIDGE.md`. The current bridge's seven ASan/UBSan CPU tests
cover parsing, cache/layout rejection, RX lifetime, actual Linux-generated
`OGCAP002` read by the fixed C++ `SampleCapture::read`, and the independent
preflight oracle at 1x/2x/4x. The CPU packets in these tests are explicitly
synthetic; no Mesa rebuild or shader execution is performed. A missing
128-thread capability batch fails before READY. Both depth-policy values are
checked in the actual rewritten GFS; clearing `allowDepthOverride` in a copied
depth case must fail the independent preflight. Exact image view sizes cover
only their logical pixels/samples while their original 4096-byte GEM backing
and suffix guards remain intact. Temporary captures are removed after use.

## Live actual-DUT entry

`native_sample_live_producer.c` is a separate entry, built with
`build_bridge(output_directory, sanitize=True, live=True)` from the Python
module above. Run its binary with:

```text
native-sample-live-producer program.ognsf profile.ognp session-directory 1|2|4 case-id
```

It validates the actual probe's `OGNP0002` wire (112 ordered address/value pairs
and five ordered auxiliary pairs), preserves the original FS bytes and submits
a real 420-word native VS. The VS uses the existing x1/x8 kernarg/warp ABI, writes
clip position `(0,0,-0.5,1)` from an independent constant attribute and writes each
varying component `(4*slot+component+1)/16` with actual vector stores. All 64
varying-mask bits are set. The actual validator checks its bounded slice-address
arithmetic; it does not rely on unproved LUI address provenance. The native stage
generates vertex-valid/ordinal metadata from the input count. Its default viewport
depth is 0.25, distinct from a positive fragment depth override of 0.5. One point of size
four, viewport four by four, covers this behavioral frame. The full 128-thread,
16-varying, four-MRT factory capacities remain in place. Five original image
GEMs store four RGBA32F sample planes and D32F depth, initially cleared to 0.25
per color component and 1.0 depth. No resolve or averaging precedes the oracle.

`sample-inputs.json` contains inputs, raw FS words/metadata, case identity, private
record/code VAs, original attachment allocations/views/pitches/formats/clears,
GNY2 original view, private immutable segments, resource maps and all PTE ranges.
The controller must independently bind the original ESSL source/container hashes
and verify these inputs against its fixed source recipe. This file contains no
expected rendered colors or PASS result. The controller must retain source
identity for both C entries, the Python builder, source-fixed resource registry,
MMIO/body-extraction helpers and their actual driver-source dependencies.

After publishing `READY`, the live entry keeps the exact private owner, PTEs,
original references and leases alive. It never calls the CPU completion or
retirement helper. Actual GPU execution, successful receipt/cleanup roundtrip
and image/GNY2 oracle qualification have **not** run. The required protocol is:

1. Probe the complete 112-word factory and five auxiliary registers. The fixed
   sample schema and `OGNP0002` wire reject the old 96/104 factories.
2. Export `OGMEM001` union memory and `OGCAP002` (same field sequence as 001,
   exactly 117 ordered discovery words, 13 completion/GNX checks). Keep 001
   fixed at its old 101-word schema.
3. Wait for `record-0.held.response` (`OGNSH001` and the exact LE native held
   receipt fields), then call the real driver's held drain to emit `OGRTR001`.
   Accept only `record-0.retired.response` (`OGNSR001`, held fields followed by
   retired status/IRQ/IRQ-asserted/external-drained) from actual observations
   before signaling its execution fence. Old compute receipt magic is rejected.
4. Check independent images and all 4096 canonical GNY2 words before writing
   `oracle-authorized.response` (`OGNSBA01`, u32 one). Apply and ACK every
   `OGCLB001`/cleanup trace PTE revocation and TLB operation, ending with
   `OGCLD001`. Write `final-authorized.response` (`OGNSOK01`, u32 one) after
   verifying final revocation; only then destroy the session. All wires require
   strict EOF, and `OPENGPU_NATIVE_HOST_TIMEOUT` bounds external waits.

The image oracle must calculate raw per-sample values from the original ESSL
expressions, coverage and fixed locations: 1x (1/2,1/2); 2x (1/4,1/4),
(3/4,3/4); 4x (1/4,1/4), (3/4,1/4), (1/4,3/4), (3/4,3/4). Use distinct
sample-ID, position, input-mask and output-mask expressions. Per-sample
`SampleMaskIn` is only the current sample's one-hot bit, including full geometric
coverage. The current mask-depth source XORs that bit and therefore suppresses
all color/depth updates; it is a negative mask case, not positive depth-override
proof. The separate original ESSL `mask-depth-keep` case retains the current sample
and writes depth 0.5, distinct from the VS default of 0.25; its actual DUT
depth result remains untested. Verify each stored
sample before any resolve. The first live geometry covers full coverage only. Add edge coverage, helpers, a mask
that suppresses a covered sample, all-zero output mask, and mask bits outside
the invocation's coverage. Derive expected masks by intersecting geometric
coverage, invocation coverage and shader mask. The GNY2 oracle must independently
check identity, counters, zero padding, healthy owner status and complete
publication. A GNX success or header-only GNY2 read is insufficient.

GS/ABI7 needs a real GS program and owner, raw GS output and the layered route;
it cannot borrow an ordinary draw's ABI6 proof. Dynamic interpolation services,
API enable/minimum policy, full RTL execution and frame/FPS qualification remain
open. The old ABI1 six-scene product run is a separate qualification.

## Canonical fixed-recipe precheck

`native_sample_owner_recipe.verify(space, description, meta, code)` independently
reconstructs all 128 GNJ2 words, 512 GNF3 words, 512 GND1 words and 128 GFS words
from zeroed records, fixed recipe arithmetic and the public ABI catalogs. Every
unused/reserved word and upper padding bit must match. Tile layout derives from
the 32KiB store and the full four-RGBA32F-plus-D32F footprint, rather than copied
frame values. The VS oracle separately encodes all 420 RV32/vector instructions,
including all 68 position/varying stores and exact distinct float constants.
Both private VS/FS RX bytes must match their independent source programs.

Dynamic resource VAs come from `frameResources` and `vertexScratch`, emitted
from the retained original plans. Each role/index/handle is unique and must bind
to the same retained original GEM, exact logical size, page-rounded allocation,
actual owner map and actual Sv32 PTE. Addresses in the records being checked do
not authorize their own expected values. Private job/frame/draw/state/code VAs
remain explicit distinct mapped views. These additional metadata keys describe
original inputs; they contain no expected descriptor bytes or rendered output.

```sh
PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_sample_owner_recipe.py
```

Four CPU tests pass. All three sample modes accept actual Linux captures and
reject 8,940 descriptor/VS word mutations, covering low and high bits of every
complete descriptor word and every VS instruction. Same-initial/returned GFS
magic corruption, color-format corruption and a VS replaced by immediate CEASE
also fail before both preflight and synthetic healthy-return checks. These are
synthetic CPU packet/return tests; no Mesa rebuild, shader DUT, actual completion
or successful GPU cleanup ran. The actual-ESSL consumer uses normalized case IDs
and the same independent preflight with its explicit CPU profile fixture; its
fresh compiler run remains a separate reproduction step.
