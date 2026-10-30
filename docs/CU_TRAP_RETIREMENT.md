# CU trap retirement timing

An acknowledged trap now enters a registered finish slot before retiring its
warp. Ordinary finishes keep priority, and a stalled public trap remains stable.
This removes the combinational path from public trap readiness to the CU finish
signal; accepted traps may retire one cycle later.

The complete storage-enabled CU synthesized from `3b2881d6` missed a 1,000 ps
pre-route screen with 25% I/O delay: worst core slack was −521.255 ps and I/O
slack was −172.676 ps. Both reported paths started at public trap readiness.
That screen included 344 SRAM instances; it was not a whole-GPU or frame-rate
result. The registered change has not yet been requalified against that screen.

Local validation passed eight tests for held traps, simultaneous normal and
faulting warp finishes, concurrent faults, destination reuse and storage faults:

```sh
sbt 'testOnly opengpu.core.ShaderTrapRetirementSpec opengpu.core.GpuComputeUnitConcurrentTrapSpec opengpu.core.GpuComputeUnitStorageFaultSpec'
```
