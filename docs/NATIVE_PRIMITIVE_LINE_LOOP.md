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
Whole-host timing, the six 720p workloads, broader native topology/format/sample
coverage and H6 acceptance remain required.

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
