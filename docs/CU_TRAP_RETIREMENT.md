# CU trap retirement timing

An acknowledged trap now enters a registered finish slot before retiring its
warp. Ordinary finishes keep priority, and a stalled public trap remains stable.
This removes the combinational path from public trap readiness to the CU finish
signal; accepted traps may retire one cycle later.

The complete storage-enabled CU synthesized from `3b2881d6` missed a 1,000 ps
pre-route screen with 25% I/O delay: worst core slack was −521.255 ps and I/O
slack was −172.676 ps. Both reported paths started at public trap readiness.
The same complete CU screen at change `b0a3a0b5` reports −405.924 ps core
and −86.925 ps I/O slack: an improvement of 115.331 ps and 85.751 ps,
respectively, but both still miss the 1,000 ps target. The new worst core path
runs from scalar instruction fault state to the vector scoreboard. Each screen
contained 344 characterized SRAM instances and used synthesis-only timing;
this is neither post-route nor a whole-GPU or frame-rate result. Subsequent
changes must be measured independently.

Local validation passed eight tests for held traps, simultaneous normal and
faulting warp finishes, concurrent faults, destination reuse and storage faults:

```sh
sbt 'testOnly opengpu.core.ShaderTrapRetirementSpec opengpu.core.GpuComputeUnitConcurrentTrapSpec opengpu.core.GpuComputeUnitStorageFaultSpec'
```
