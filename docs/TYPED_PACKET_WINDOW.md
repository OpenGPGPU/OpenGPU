# Typed packet reservations

`TypedSamplePacketWindow` tracks up to 128 four-sample packets and reserves
their accessed color and depth/stencil words before offering work. Aliasing
packets wait until the preceding worker's terminal acknowledgement releases
its reservations. Workers must return that acknowledgement only after all
accepted memory and arithmetic obligations have drained.

Completions may arrive out of order; retirement follows allocation order.
Generation tags prevent a stale completion from matching a reused slot.
Unaccessed planes and empty packets take no word reservations. Cancellation
preserves already offered work and retirement records while accepting the
remaining owned upstream packets for drain. New public quad admission is the
enclosing frontend's responsibility. Rebind requires complete drain.

Four tests pass, including 512 packets with 128 contexts and a 64-clock worker
latency, consecutive allocation/retirement, masked-plane reservations, aliases,
stale tags, held output, sticky cancellation and healthy reuse. All twelve
emitted RTL files match the qualified snapshot. The default 128-entry,
14-bit-address component passes synthesis at 1,000 ps with 25% I/O delay:
core slack +228.182 ps, I/O slack +250.956 ps, zero skipped or unresolved
mappings. Entries may be powers of two from 4 through 128; the tests also
exercise four-entry wrap/reuse. Whole-backend timing, physical memory effects
and product frame rates require their separate integration gates.

```sh
sbt 'testOnly opengpu.graphics.TypedSamplePacketWindowSpec'
sbt 'runMain opengpu.elaboration.EmitPpaRtl mobile-typed-packet-window .artifacts/typed-packet-window-rtl'
CHIPAGENT_DIR=../chipagent ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/typed-packet-window-rtl TypedSamplePacketWindow \
  --stage synthesis --period-ps 1000 --io-delay 0.25 --timeout 1800 \
  --output .artifacts/typed-packet-window-synthesis
```
