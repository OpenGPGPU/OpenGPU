# Private 112-word ordinary graphics source plans

`userspace/opengpu_native_sample_graphics.[ch]` provides a private, versioned
consumer of the exact ordered 112 discovery words and five auxiliary registers.
It checks the complete sample-stage contract, the actual 16-lane/8-warp/two-CU
shader configuration and 128-item batch, and derives limits through the existing
GNC5/GGS/GSS validator. Register reads reject missing prerequisites before
reading dependent records. This cache does not authorize a public feature.

The direct encoder creates GNJ2 and one-layer GNF3 records from zeroed storage,
using actual GNC5 limits and explicit 33-bit source/resolve pitches. It preserves
logical original-GEM views, four MRTs, four fragment scratch owners and the
65536-tile capacity. Completion is a separate exact 16384-byte original-GEM
view. Ordinary extended plans preserve the original code, GUR and input views
and zero every GS extension byte. Kernel admission still owns producer waits,
original-object bounds/aliases, private RX/RO snapshots and lifetime/drain.
Every rejected operation leaves its output unchanged.

The sample CPU/live producers use this encoder instead of passing synthetic
old GNC3 capabilities and rewriting version fields. The independent existing
recipe still checks every GNJ2/GNF3/GND1/GFS word and the live producer's original
RX code. Existing one-layer pitch values and descriptor bytes are preserved.
The qualifier binds the added compilation unit and its transitive headers.

The old GNC2/GNC3 runtime, frame planner, public GET_PARAM results, driver feature
bits and 96-word protocols remain separate. Original Mesa ABI1 programs and
uniform views can be carried by these plans; this slice does not connect the
Gallium program/context factory to the new private cache, add a two-owner 112
executor/image/GNY2 oracle, or qualify actual DUT execution. ABI6 uniform/flat
lowering and ABI7 with a real GS owner remain separate work.

CPU validation:

```sh
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_sample_graphics.py
OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_sample_submit_bridge.py
python3 -B scripts/test_prepare_owner_executor.py
python3 -B scripts/test_qualify_native_sample_owner.py
```

The first command checks complete GNF3 words at 1/2/4 samples, exact GNJ2/GNY2
source plans, full UBO/default preservation, all discovery-word mutations,
auxiliary/read failures, legal bank counts, limits and the frozen old planner.
These are CPU source/ownership tests; no Mesa rebuild, RTL/model or GPU runs.
