# Vector response byte assembly

`VectorMemoryCoalescer` collects each lane's four returned bytes as cache-line
responses arrive. It retains the original unique-line capacity, request order,
masked-store priority, fault attribution and final-response publication cycle.
The response buffer also preserves the original narrow/inactive-lane bytes when
another lane requests their line. Empty masks and reset retain their behavior.

Admission registers unique candidates before compacting the line table. A
physical request is registered before its offer, including masked-store data.
After input acceptance, a nonempty transaction takes three preparation clocks
to offer its first line. Subsequent lines take one preparation clock after the
preceding response. The last response still publishes the final vector result
without another output clock. Held offers remain immutable until acceptance.

With sixteen lanes and 64-byte lines, a transaction still supports 32 distinct
line requests. The private response-data buffer changes from 32×512 bits to
16×4×8 bits (16,384 to 512 bits). Address tables, output registers, fault state,
cache storage and SRAM macros are additional; these counts are neither mapped
area nor runtime memory occupancy.

Ten checks pass across three suites. The sixteen-lane oracle completes 134
transactions, including 32 distinct lines, aliases, crossing and wrapped
addresses, narrow/masked operations, response delays, held outputs and reset
at each admission/request stage and while awaiting a response.
Three original coalescer cases remain. Six enclosing-core cases include an
aligned vector load, assembly across a cached line and delayed refill, a
dependent write-through store held for acknowledgement, and a cached reload.

The request stages change service latency. Component checks do not establish
the new complete-host cycle counts, clock or mapped resource budget; those
need qualification on the registered implementation.

```sh
sbt -J-Xmx4G -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=2 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.core.memory.VectorMemoryCoalescerSpec opengpu.core.memory.VectorMemoryResponseAssemblySpec opengpu.core.GpuCoreSpec'
sbt -J-Xmx4G \
  'runMain opengpu.elaboration.EmitPpaRtl vector-memory-coalescer .artifacts/vector-byte-rtl'
CHIPAGENT_DIR="$PWD/../chipagent" ../chipagent/.venv/bin/python scripts/qualify_ppa.py \
  .artifacts/vector-byte-rtl VectorMemoryCoalescer --clock clock \
  --period-ps 1000 --io-delay 0.25 --output .artifacts/vector-byte-synthesis
```

The [full SRAM frame commands](EXPLICIT_NATIVE_PRODUCT_FRAMES.md) remain local-only.
Whole-host timing, mapped resource budgets, the complete product workload suite
and 720p/30 FPS require separate qualification.
