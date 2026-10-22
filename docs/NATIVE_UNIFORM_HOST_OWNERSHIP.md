# Native uniform ownership through the translated host

Two directed `NativeTypedGraphicsHostAxiSpec` cases pass on the production
native execution profile with four lanes, two warps and two shared CUs.
Each runs three 8×8 frames: healthy, denied fragment uniform-data page, healthy.
The cases use local and globally staged shader payloads respectively.

Each frame binds both shader stages to one immutable table of twenty-four
read-only 16 KiB views. Vertex maps logical blocks 0–11 to bindings 0–11;
fragment maps them to bindings 12–23. Scalar and vector instructions read the
last block words, plus each stage's first and last default words. Physical AXI
traffic confirms reads from all twenty-four block views. The final vertex and
fragment values contribute to the exact MRT/depth/store/resolve image oracle.
The native draw records carry separate roots and defaults for all three draws.

Actual Sv32 translation covers code, native descriptors, roots, table views,
uniform data and private scratch. Denying the final fragment block's data page
returns frame status 3 without a physical data read from that page. A following
healthy frame succeeds using fresh shader code and source allocations. While
each frame completion remains held, a VM-context change is rejected; popping
completion releases its lifetime. Payload reservations and pins return to zero.

Every default word remains unchanged after successful frames, including the
vertex default tail that shares its first line with the 608-byte private prefix.
This checks the local-prefix/global-uniform boundary and the partial final-line
clear during global staging. Fragment private prefixes are 896 bytes.

```sh
sbt -Dopengpu.simJobs=2 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.NativeTypedGraphicsHostAxiSpec -- -z "native uniform table ownership"'
```

These cases establish translated table access, fault isolation and completion
lifetime for compact native frames. They do not establish overlapping full-size
vertex/fragment capacity, GPU-producer publication, ASID remapping/reset coverage,
uniform throughput or an enclosing host clock. The separate
[binding qualification](NATIVE_UNIFORM_BINDINGS.md) records 128-thread per-stage
capacity and component timing. Runtime discovery and the remaining H0–H8 gates
remain open.
