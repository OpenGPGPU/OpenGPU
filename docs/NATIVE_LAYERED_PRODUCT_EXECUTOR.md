# Layered product executor

`scripts/native_layered_product_driver.cpp` executes the original six product
recipes on the complete `geometry-compute` owner factory. The common builder
still names the executable `native_product_driver`. Its explicit additional
dependencies are `scripts/native_layered_product_fixture.hpp` and
`scripts/native_layered_product_execution.hpp` together with
`driver/opengpu_native_layered_abi.h`; the existing host simulation, AXI, fixture
and progress headers remain shared dependencies.

The distinct `OGLAY001` container has the `OGNAT001` field encoding, but requires
the exact ordered 104-word discovery table, GNJ2/GNF3 input and independent GNY2
publication. It accepts 32×32 smoke or 640×480 product recipes, with 4× samples
only for the sixth scene. Commands submit SOURCE=`jobBase`, exclusive
DEST=`jobBase+512`, BYTES=512 and PATTERN=0. The old `Fixture::read`, GNJ1 input
and 96-word owner parser remain unchanged and reject the new container.

Before submission the executor observes the prerequisite ID/capabilities, all
104 discovery registers, live quota and two-CU shader configuration, and verifies
idle IRQ/completion/request state. After real IRQ it requires drained external
traffic, no bus faults, all thirteen completion/GNX reads including the original
job identity, and canonical readback of all 4096 GNY2 words. It verifies the
single completion pop, IRQ acknowledgement and idle retirement before writing
the returned sparse backing and execution row. A partial GNY2 prefix cannot
override a coarse completion fault. Scala subsequently checks the full wide
completion semantics, immutable guards and every stored sample/resolve with the
independent original frame oracle.

Rows retain the original service/frame cycle scopes and traffic measurements,
with profile `two-cu-vga60-owner-v2`, stage `geometry-compute`, job/frame/completion
versions 2/3/2 and shader source `native-layered-product-hand-encoded`. The
`resourceDiscovery` array contains the actual ordered MMIO readback. Draw and
primitive completion counts remain unavailable in the coarse GNX row; no count
is inferred from the workload. Clock/FPS qualification remains false.

CPU validation:

```sh
PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_layered_product.py
PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_product_observation.py
```

The new regression checks every bit of all discovery addresses/values, missing
and duplicated words, order, launch/view/check contracts, every truncation,
GNJ2/GNF3 canonical words and original backing, sentinel/partial GNY2 and its
last word, actual preflight read ordering, fault observations and old/new format
separation. It also checks C++ discovery against the authoritative Python full
factory table and compiles the executor against the lightweight host stub.
Set `OPENGPU_LAYERED_FIXTURES` to the Scala producer's fixture directory to
add a six-container interoperability check. Temporary test files are removed
automatically.

These are CPU guards and source integration only. Actual complete-DUT execution
of the six 640×480 frames, independent returned-frame verification and physical
timing qualification remain required. This work does not inherit qualification
from the old GNJ1 model or from a standalone component test.

The separate fixed `native_sample_layered_product_driver.cpp` wrapper selects
the shared execution template's strict OGLAY002/112-word sample factory. It
rejects OGLAY001, while the original wrapper rejects OGLAY002. This path uses
the same real traffic/completion/publication/retirement checks and unchanged
original ABI1 recipes; it does not qualify SampleID/Position builtins. Its
exact manifest, row and runner contract is described in
`NATIVE_LAYERED_PRODUCT_QUALIFICATION.md`.
