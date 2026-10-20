# Host AXI address and read-response ownership

`GpuHostAxi` shares one register address/length/beat state between reads and
writes. The previous bridge acknowledged both AR and AW when they arrived
together while idle. Both owners became active and the later address assignment
overwrote the first. It also drove a stalled RDATA directly from live registers:
an offered unified STATUS value changed from 9 to 1 when reset drain completed.

Two independent reproductions fail on the frozen original source. The corrected
bridge grants exactly one address channel under contention, alternating priority
according to the preceding accepted address. The waiting channel retains its
offer until the active transaction retires. This is transaction arbitration;
an upstream master that withholds required data/response acceptance can still
stall its own transaction.

An unstalled read retains the original combinational response and beat rate.
On the first stalled cycle, a holding register captures the offered word.
Subsequent stalls retain that word while live status changes independently.
Consuming the beat releases the holding register; the next beat/transaction
reads the current addressed register. RRESP and RLAST derive from immutable
transaction state while a beat is held. Bus reset clears both ownership and
holding state. Register reads are not reissued while the stored response waits.

The change adds one data register and two control bits, with no SRAM, shader
core, FPU, instruction encoding or wire interface change. Existing FIXED/INCR,
size/alignment, 4 KiB boundary and address-carry checks remain in force.

The two focused checks now pass: asynchronous-to-the-transaction reset completion
cannot change a held response, the next read sees the updated status, and eight
rounds of simultaneous address offers serialize both channels exactly once
under held R/B responses with exact write readback. Both first-grant directions
are exercised. All [nine controls across four suites](performance/mobile-host-axi-ownership-2026-10-20.json)
pass on the same 627 unchanged source/ABI files, including discovery/burst,
fill/reset/IRQ and buffered/unbuffered VM ownership. The complete native-host
composition below now passes on that same source.

Run the relevant controls with:

```sh
sbt -J-Xmx8G -J-XX:ActiveProcessorCount=4 -Dopengpu.simJobs=1 \
  'testOnly opengpu.system.GpuHostAxiOwnershipSpec opengpu.system.NativeVertexInputCapabilitiesAxiSpec opengpu.system.GpuHostAxiSpec opengpu.system.GpuHostVmOwnershipSpec'
```

## Complete native composition

The [37-check composition](performance/mobile-host-axi-native-composition-2026-10-20.json)
passes on the same 627 unchanged source/ABI files: twenty replay/shared-image/
native-owner checks and seventeen production native AXI cases. All 22 fault
cases run without filtering, four local/global profiles publish converted half
and packed-SNORM vertices, and GNC1/GNC2 cached images, primary fault attribution,
held completion, reset drain and healthy reuse pass with the coverage pipeline.
Three ABI checks and default four-CU emission complete. The emitted host has
769 files (768 SV), 371 active RTL files, one shared payload pool, two replay
engines and unchanged SRAM/AXI capacities.

This source precedes centroid/GFI1 and the concurrent mainline core-selector
changes. Their merged-source regression is distinct. The composed replay
synthesis remains active; standalone coverage timing does not establish host or
whole-GPU timing. Resource/provenance, broader feature/rate and H0–H8 gates remain
open.
