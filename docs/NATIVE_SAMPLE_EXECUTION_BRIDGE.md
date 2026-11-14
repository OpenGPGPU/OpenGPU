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
