# Native frame traffic accounting

The native fragment phase counters are worker clocks. Phase zero includes idle
and collection, phase one includes staging/allocation, and phase five includes
emission/release. Their sum is configured workers times observed core clocks;
these counters are not independent serial phases or causal stall durations.
The frame log labels those scopes explicitly.

The existing `lowerReadBytes`/`lowerWriteBytes` hardware counters add
`1 << sizeLog2` for accepted lower requests. They count nominal request spans,
including disabled write lanes, rather than strobe-enabled AXI write bytes.
Keep that distinction when estimating bandwidth.

The opt-in `AxiTrafficObserver` samples AR/AW/W/R/B handshakes from the existing
pre-edge `onCycle` hook. It drives no DUT inputs and advances no clocks. It adds
read-only peeks only to opted-in qualification; the shared stimulus is unchanged.
`readBusBytes` and `writeBusBytes` count full-width data beats occupying the bus,
including unused lanes of narrow transfers. `enabledWriteBytes` counts accepted
WSTRB bits. Repeated writes count repeatedly; none of these is allocated storage,
unique-address footprint, DRAM traffic, energy or FPS.

A completed frame checks that every scheduled read beat and burst has retired,
and that write addresses, completed WLAST bursts and B replies agree. This
aggregate accounting supplements the existing protocol/data checks. Error
completion is tagged separately. Counts are emitted once per serviced frame;
raw transfer histories are not accumulated.

Ten focused checks pass: five counter checks and five small response-chained
AXI runs. They include data preceding its address, held responses, paused MMIO,
partial strobes and an accepted zero-strobe beat. The ordinary two controls
remain present. Together with the fifteen backing/oracle/entry checks, the
current targeted set has twenty-five passing cases with no skipped cases.

```sh
sbt -J-Xmx4G -J-XX:+ExitOnOutOfMemoryError -Dopengpu.simJobs=1 \
  'testOnly opengpu.testutil.AxiTrafficCountersSpec opengpu.system.AxiStimulusOrderingSpec'
```

Use the [explicit sparse frame commands](EXPLICIT_NATIVE_PRODUCT_FRAMES.md) for
full-host observations. Component checks do not establish full-host traffic,
product frame rates, general coherence or completion of the H0–H8 requirements.
