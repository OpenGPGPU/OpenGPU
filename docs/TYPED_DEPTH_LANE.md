# Generic depth/stencil lane

`TypedDepthLane` processes one pixel's covered samples in order, with stencil
testing before depth testing. It supports D16, D24, D32F, D24S8, D32FS8 and a
separate S8 view. Prepared sample depths preserve attachment precision. Masked
word writes preserve neighboring samples, stencil fields and padding bytes.

The caller holds the descriptor and quad stable from start through completion,
provides shared-word ordering and initialization, and starts new work only
after prior work completes. Cancellation must remain asserted through drain.
An offered request remains immutable until accepted; completion waits for its
reply. Successful write replies determine update counts, while initialization
is counted even if the initializing operation ultimately reports a fault.

Two tests pass with zero skipped or pending cases. The numeric test checks 42
combinations of seven layouts, three MSAA modes and two faces against independent
depth/stencil models, including rejected samples, exact packed words, held
requests and delayed replies. The cancellation test drains a held read and
checks initialization accounting on its fault response. These are component
checks; shared SRAM arbitration, four-lane integration and renderer throughput
require their own validation.

```sh
sbt -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 -Dopengpu.simJobs=1 \
  'testOnly opengpu.graphics.TypedDepthLaneSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-typed-depth-lane .artifacts/typed-depth-lane-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/typed-depth-lane-rtl TypedDepthLane --stage synthesis \
  --period-ps 1000 --io-delay 0.25 --timeout 14400 \
  --output .artifacts/typed-depth-lane-synthesis
```
