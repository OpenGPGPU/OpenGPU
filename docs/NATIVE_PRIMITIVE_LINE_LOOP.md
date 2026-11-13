# Native LINE_LOOP, restart and fault recovery

Three 4×4 submissions pass on the retained assertion-enabled four-CU,
sixteen-lane/eight-warp, four-engine API-capacity host. The model retains its
512 KiB payload pool, 128 KiB L2, characterized SRAM and 256-bit AXI interface.
This is a partially relinked existing model, with its RTL, archive, original
driver, fixtures and executable identities audited before and after execution.

| Submission | Service cycles | Result |
| --- | ---: | --- |
| Two indexed LINE_LOOPs separated by restart | 20,313 | Exact image and guards |
| Index view missing its final byte | 365 | Bounds status 2, vertex stage 3, exact index address |
| Healthy reuse with new addresses/generation | 20,313 | Exact image and guards, doubled red quantum |

The seven uint16 indices include `0xffff` restart. The independent
arbitrary-precision diamond-exit reference checks both loop-closing edges and
detects an erroneous bridge across restart. ONE+ONE blending makes extra
contributions visible. The fixture's shader outputs red 1/8, then 1/4 on reuse;
the expected stored red values are 32 and 64. All sixteen pixels and every
prefix, row-padding and tail guard byte are checked against CPU results fixed
before execution.

The complete four-engine host requires four scratch views. The driver extends
the existing two-engine CPU fixture with disjoint views at offsets `0x60000`
and `0x70000`, retaining the same geometry, shaders and expected image. It checks
the actual compute/native discovery words and independently requires the full
hardware profile. Only one tile and color attachment are active; this does not
qualify four-engine concurrency or all interface-capacity combinations.

All submissions drain external AXI ownership with zero unmapped traffic.
Accepted writes stay within writable allocations, and read-only inputs remain
unchanged. The short logical index view retains complete physical backing but
is rejected before index/attribute data reads. GNX1 status, identity, fault stage
and address match; POP releases the details before healthy reuse without reset.
The AXI service uses the existing three-clock read and seven-clock write-reply
delays. Five CPU fixture checks pass, including strict C11 and ASan/UBSan builds.

The driver saves sparse input/returned memory and completion/read diagnostics
under the chosen output directory. Its three JSON lines describe this bounded
case; they do not create a six-scene product or paired API qualification record.
Whole-host timing, the six two-CU 640×480/60 FPS workloads, broader native
topology/format/sample coverage and H6 acceptance remain required. Returned
memories and diagnostic files are temporary; delete them when validation or
diagnosis no longer needs them rather than retaining an evidence archive.

## Reproduce

Use an unchanged, provenance-checked API model prepared and built by
`qualify_native_api_compiled.py --api-extent-product --hierarchy-profile flat`.
`API` below is that run directory; `VERILATOR_ROOT` names its matching Verilator
installation. Relinking this driver leaves the model archive unchanged. The
following link flags match the macOS toolchain used for the recorded run:

```sh
python3 scripts/test_native_binned_line_loop_fixture.py
c++ -std=c++17 -Os -g0 -fbracket-depth=4096 \
  -I "$API/build" -I "$VERILATOR_ROOT/include" -I "$VERILATOR_ROOT/include/vltstd" \
  -I scripts -I driver -I driver/tests \
  -c scripts/native_binned_line_loop_driver.cpp -o "$LINE_OBJECT"
c++ -Wl,-U,__Z15vl_time_stamp64v,-U,__Z13sc_time_stampv,-U,_vlog_startup_routines \
  "$LINE_OBJECT" "$API/build/verilated.o" "$API/build/verilated_threads.o" \
  "$API/build/VGpuHostSystemAxi__ALL.a" -pthread -o "$LINE_DRIVER"
"$LINE_DRIVER" "$API/fixtures" "$NEW_LINE_OUTPUT"
```

Use a new output directory. The driver requires the complete 640×480 API fixture
contract for discovery, while rendering only its separate 4×4 LINE_LOOP inputs.
The matching model/build guard and the independent CPU comparison remain
necessary when carrying results to another source revision.

## Seven-topology qualification mode

The same driver accepts `--topology-matrix` for nine consecutive submissions
without resetting the host: POINTS, LINES, LINE_LOOP, LINE_STRIP, TRIANGLES,
TRIANGLE_STRIP, TRIANGLE_FAN, a one-byte-short uint32 index view, and healthy
TRIANGLE_STRIP reuse with new addresses/generation and doubled red output.
Each renders a 4×4 single-sample image on the complete API-capacity model.

All nine submissions pass on the same partially relinked four-CU/four-engine
API host described above, without resetting between submissions:

| Submission | Index width | Service cycles | Result |
| --- | ---: | ---: | --- |
| POINTS | 8 | 23,529 | Exact image and guards |
| LINES | 16 | 12,015 | Exact image and guards |
| LINE_LOOP | 32 | 26,726 | Exact image and guards |
| LINE_STRIP | 8 | 20,548 | Exact image and guards |
| TRIANGLES | 16 | 8,887 | Exact image and guards |
| TRIANGLE_STRIP | 32 | 10,706 | Exact image and guards |
| TRIANGLE_FAN | 8 | 14,453 | Exact image and guards |
| One-byte-short triangle index view | 32 | 365 | Bounds status 2 |
| Healthy TRIANGLE_STRIP reuse | 32 | 10,706 | Exact image and guards, doubled red quantum |

The executable, compiled driver dependencies, model archive, original model
inputs and all returned memories were checked before and after execution.
The independent CPU reconstruction passes for every input/image/guard, and
the driver checks GNX1 identity and fault details, immutable inputs and drained
AXI ownership for every submission. This qualifies the bounded topology
matrix, not the two-CU product profile or a frame-rate target.

The seven healthy draws use uint8/uint16/uint32 indices in rotation and a
width-specific fixed restart value. LINES and TRIANGLE_STRIP split after three
vertices; the other modes split after four. The strip enables back-face culling
so alternating winding and parity reset affect the stored image. Independent
CPU witnesses distinguish missing loop closures, restart bridges, fan anchors,
and strip parity errors. The CPU suite checks records, all pixel/guard bytes,
rejected corruptions and byte-identical ASan/UBSan exports; it also retains the
original five LINE_LOOP checks.

```sh
python3 scripts/test_native_binned_topology_fixture.py
"$LINE_DRIVER" "$API/fixtures" "$NEW_TOPOLOGY_OUTPUT" --topology-matrix
python3 scripts/test_native_binned_topology_fixture.py --verify-run "$NEW_TOPOLOGY_OUTPUT"
```

Rebuild the driver using the link recipe above. Runtime acceptance requires all
nine completion, identity, fault, traffic-drain, immutable-input and exact-image
checks to pass, with matching model/driver input identities. The offline checker
independently reconstructs CPU inputs, four disjoint scratch views and returned
images; it does not establish model provenance or completion by itself. The
four-engine extension offsets come from the C ABI headers, since the original
two-engine JSON layouts omit those reserved-tail fields.

This bounded matrix does not cover every topology/index/sample combination,
cross-batch assembly, four-engine concurrent rasterization, guard-band or
precision closure, whole-host timing, the six two-CU VGA scenes or H6 acceptance.

## Two-CU target-profile result

On 2026-11-13, all nine submissions also passed on the fresh assertion-enabled
`two-cu-vga60-v1` model: two sixteen-lane/eight-warp CUs, four native engines,
2048-axis native limits, 65,536 directory entries, 512 KiB payload storage,
128 KiB L2, characterized SRAM and 256-bit AXI. The nine service-cycle counts
are identical to the table above. Every returned image and guard matches the
independent CPU reconstruction, including the short-index bounds fault and
healthy reuse without reset. GNX1 identity/fault checks, immutable inputs and
external traffic drain all pass.

The driver was linked to the existing complete model archive, with its compiled
dependencies, archive, runtime objects, original model driver and executable
checked before and after execution. Product RTL matched the model's RTL byte
for byte. No model or build record was relabeled. This is bounded 4×4 topology
coverage on the full native graphics profile; it does not establish CU/engine
concurrency, full H7/H8 feature integration, whole-host timing or VGA frame rate.

Select `--two-cu-vga` explicitly. The driver reads the strict 640×480 two-CU
product fixture and independently checks every word of the actual 16-word
GCR1 and 8-word GNC3 blocks. The default remains the four-CU API fixture.
CPU tests reject a one-bit change to each discovery word on both profiles and
reject substituting a smoke fixture for the two-CU product contract.

```sh
python3 scripts/test_native_binned_topology_fixture.py
python3 scripts/qualify_native_product_compiled.py --smoke --two-cu-vga \
  --output "$MODEL" --stage prepare
python3 scripts/qualify_native_product_compiled.py --smoke --two-cu-vga \
  --output "$MODEL" --stage build --jobs 2
python3 scripts/qualify_native_product_compiled.py --product --two-cu-vga \
  --output "$PRODUCT" --stage prepare
# Use MODEL as API in the compile/link recipe above, retaining its matching
# Verilator installation. Check both preparation records and the model build
# against current source before and after the run; require identical RTL.
"$LINE_DRIVER" "$PRODUCT/fixtures" "$NEW_TOPOLOGY_OUTPUT" \
  --topology-matrix --two-cu-vga
python3 scripts/test_native_binned_topology_fixture.py --verify-run "$NEW_TOPOLOGY_OUTPUT"
```

All ten CPU tests, the real two-CU nine-submission run and the separate CPU
reconstruction pass. Remove generated model, relinked driver and returned-memory
outputs when their active use ends; retain only source, these results and the
reproduction commands.
