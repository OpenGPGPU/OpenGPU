# Joint frame and original-source capture

`TessellationJointFrameCaptureReader` captures the actual 32-cache-line GNF1
record belonging to an independently accepted joint job. It uses the existing
native field map, including reserved-bit checks. Frame identity, state extent,
active engine scratch prefixes, numeric owner bounds and frame-writer/image
allocation conflicts are checked before returning the captured descriptor.
An allocation ending exactly at LIMIT33 `2^32` is supported. An envelope is
not proof that its holes are mapped or that the caller owns their resources.
Immutable BUILD references, physical alias checks and actual MMU mappings
remain required through owner retirement.

For a zero-invocation job, state version remains one and state base/limit must
both be zero. Full frame writer, scratch and active image resources remain
present and checked. The report's `clearOnly` bit describes captured metadata;
this component does not execute clear, store or resolve and does not launch a
dummy fragment shader.

`TessellationJointRangeCaptureController` serially reads real GNT7/8/9, GVS1
and the original GND1. The accepted directory entry independently supplies draw
and sequence indices, source view, original invocation/range identity and task
extent. A different GNT509..511 root is rejected before any GVS read. Captured
GVS/GND observations then pass the joint crosslink checker, including original
draw-relative ordinal, instance and row boundaries. Original index offsets,
base vertex/instance, bindings and divisors are retained. TES export count is
separate from the TCS patch layout.

Both constructors default to unavailable. Neither adds a getter, factory,
discovery bit, public opcode, queue or ioctl. One tag-zero owner is retained
until the actual child report is accepted and its request/reply credit and
busy state drain. A held child submit is not cancelled before acceptance;
held memory offers survive cancellation and consume their actual late replies.
Memory faults take precedence over cancellation. Report bits are immutable
under backpressure, and the same component can accept a healthy job afterwards
without reset.

The future GNQ/directory master must hold its directory-entry lease through
actual range execution and task-specific retirement, not just source capture.
The three-domain execution parent, complete end/frame/fabric ownership,
primitive-ID interface propagation and real no-state clear/store/resolve route
are not implemented by these two components. Whole-CU, whole-host, six VGA
frames, physical timing and 2-CU 640x480/60-FPS qualification remain open.

## Focused validation

Eight actual component RTL tests passed with 16 lanes, eight warps, all sixteen
attribute/binding/varying slots, four engine scratch views and four MRT slots.
The GNF fixture uses four samples. These are metadata tests with real delayed
tag-zero memory responses, not CPU-precomputed shader or patch results.

The frame suite covers canonical/no-state capture, allocation endpoints and
high bits, identities, read-only and writable image aliases, held requests and
reports, cancellation, late faults and reset-free reuse. The three range cases
cover all domains, full captured GNT payload against independent LE words,
real GVS/GND capture, root rejection before reading, independent draw indices,
row/ordinal mismatches, cancellation, late faults and reuse. A short test-only
top-level observation port exposes the unchanged actual sequence Bundle;
it does not inject a child completion. The original deeply nested port hit a
generated svsim DPI C++ identifier failure before behavioral simulation.

Three additional zero-work cases retain an original invocation, GNQ count one,
independent literal directory metadata and actual GNT/GVS/GND reads. The original GND has zero vertices and
five instances; the canonical slice has zero task/instance/patch counts. Each
domain still performs all 72 cache-line reads (4,608 bytes), preserves original
base vertex/instance and compares the full GNT payload. A falsely empty GVS
over a nonempty original GND is rejected after reading the actual GND, as are
noncanonical zero slices. No-reset reuse remains healthy. This does not use
the zero-invocation clear-only shortcut.

Nineteen current sources were freshly plugin-compiled in groups of sixteen
and three. Every borrowed class was matched by package plus `SourceFile` to
its producer's source bytes; changed providers were excluded or freshly built.
10,427 source/class/tool/classpath inputs, directory memberships and the topic
HEAD remained stable through the final range run. No old compiled model was
reused. JVM limits were 128 MiB heap/metaspace, 32 MiB code cache,
`ActiveProcessorCount=1` and SerialGC; each group had a 180-second deadline and
512-MiB sampled process-group RSS limit. Frame tests passed in 33.42 seconds
(443,584 KiB). Final full-payload isoline/triangle/quad cases passed in
40.72/40.62/41.36 seconds (427,232/455,872/432,080 KiB). Fresh compilation took
9.05 and 4.83 seconds. Verilator retained upstream `-j 0` and reported fourteen
generation threads; separate native make used `--build-jobs 1`. These results
do not claim a strict single-CPU limit for Verilator generation.

The independent zero-work test source compiled in 2.96 seconds, using the
unchanged, source-matched component classes. Its three freshly generated RTL
models passed in 40.75/42.91/40.47 seconds with peaks of
441,536/448,192/438,864 KiB under the same per-group limits. All 9,001 consumed
inputs and provider memberships remained stable. Ended simulation/process data
is deleted; active shared providers are retained only while a named consumer
still needs them.

With a freshly compiled current-source test classpath in `JOINT_CP`, reproduce
the component tests with Java 21 and Chisel/plugin 7.15.0, Scala 2.13.18,
firtool 1.158.0 and Verilator 5.050:

```sh
java -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:ActiveProcessorCount=1 -XX:+UseSerialGC \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simCache=off -Dopengpu.simSeparateBuild=1 \
  -cp "$JOINT_CP" org.scalatest.tools.Runner -oD \
  -s opengpu.system.TessellationJointFrameCaptureReaderSpec
for domain in isoline triangle quad; do
  java -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
    -XX:ActiveProcessorCount=1 -XX:+UseSerialGC \
    -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
    -Dopengpu.simCache=off -Dopengpu.simSeparateBuild=1 \
    -cp "$JOINT_CP" org.scalatest.tools.Runner -oD \
    -s opengpu.system.TessellationJointRangeCaptureControllerSpec -z "$domain"
done
# Run the three zero-work cases with the same JVM flags and one JVM per case:
# -s opengpu.system.TessellationJointZeroWorkCaptureSpec -z "$domain"
```

Apply the reported external process-group deadline/RSS guard when reproducing
that budget. A different budget must report its actual limits and retain the
prior bounded result. Scala regression remains disabled; no workflow was
enabled or dispatched.
