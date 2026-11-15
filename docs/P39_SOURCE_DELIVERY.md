# Original VS source delivery

This source package preserves original draw-relative vertex/instance slices,
publishes linked VS output words through actual patch-write acknowledgements,
and seals only after successful source completion and drain. It adds owned
patch zero-initialization, whole-patch range retirement and scoped GVS1 reads.
The source delivery retains fifteen original logical commits, including the
held-completion/next-begin-drain correction, plus integration and documentation.
It enables no native factory, capability, getter or serialized submission.

## Final delivery checks

The exact delivery source at `29690672105cdf58bc33f5775afebdcfa4ea1784`
passed fresh Scala 2.13.18 / Chisel 7.15.0 plugin compilation of nine production
files, two test helpers and five Specs. Current output preceded only the
read-only dependency export from `75899c1856ee0ee0204cbd2e5d1893d6dad11f63`;
no P40 provider classes were borrowed. The current shared port and both vertex
stage/fetcher providers were compiled explicitly.

Ten actual component RTL cases passed, each selected as exactly one test in
its own JVM:

| Suite | Cases | Scope |
| --- | ---: | --- |
| `TessellationVertexPatchPublisherSpec` | 1 | Real owned context/SRAM, acknowledged writes, abort replies, drain and reuse |
| `TessellationVertexPatchSequenceSpec` | 4 | Original order, bounds, cancellation/failed retirement and held completion across later flush |
| `TessellationVertexSourceReaderSpec` | 3 | All 64 maps/eight reads, exact wide bounds, malformed/stale records and cancellation/late fault/reuse |
| `VertexInputFetcherSpec` | 2 | Original indexed/divisor slice addressing and malformed/cancelled setup |

Each process used 128 MiB heap/metaspace, 32 MiB code cache, one CPU/native
build job and a 180-second process-group limit. The longest case took
36.81 seconds; maximum sampled aggregate RSS was 383664 KiB, not an absolute
physical-memory peak. Source, dependency/compiler/ABI inputs and all fresh
classes matched before and after each run. All owned classes, simulations,
logs and guards were removed after process/open-file/consumer checks.

The publisher uses real patch storage but a VS data/control peer; range
retirement is also a peer. Fetcher fixtures use three or one attributes and
bindings, rather than a full shader workload. The single-binding malformed
case emitted seven existing W004 Vec1 dynamic-index width warnings; no source
or expectation was weakened to suppress them. The two-CU SourceSpec was
compiled but not executed. These checks establish neither shader ISA results
nor full resource/factory/native/three-domain/product qualification.

Normal CI selects the fetcher, publisher, range and GVS reader suites.
The two full-capacity two-CU ISA qualifiers remain local qualifications under
the existing CI policy, with the same bounded checks selected when their
sources change. All 152 selector CPU tests passed. Protected `ci-ok`, normal
Scala CI and the disabled status of Scala regression remain unchanged.

## Reproduction

Compile current providers in these groups with the matching compiler plugin:
shared port/slice/fetcher/vertex stage; patch store/context; patch source/range/
GVS reader; `GpuSim` and `WindowPrimitiveRecordsSpec`; publisher/range Specs;
reader/fetcher Specs; the two-CU SourceSpec for type checking only. Put current
output first on `CURRENT_CLASSES:FROZEN_CP`, checking the frozen source and
public interfaces rather than substituting a newer development provider.

```sh
java -Xmx128m -Xms32m -XX:MaxMetaspaceSize=128m \
  -XX:ReservedCodeCacheSize=32m -XX:ActiveProcessorCount=1 -XX:+UseSerialGC \
  -Xss2m -Dscala.usejavacp=true -cp "$SCALA_COMPILER_CP" scala.tools.nsc.Main \
  -classpath "$CURRENT_CLASSES:$FROZEN_CP" -Xplugin:"$CHISEL_PLUGIN" \
  -Ymacro-annotations -language:reflectiveCalls -d "$CURRENT_CLASSES" \
  "${CURRENT_GROUP[@]}"

MAKEFLAGS=-j1 TMPDIR="$OWN/tmp" java -Xmx128m -Xms32m \
  -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:ActiveProcessorCount=1 -XX:+UseSerialGC -Xss2m \
  -Djava.io.tmpdir="$OWN/tmp" -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  -Dopengpu.simLowMemOpt=0 -Dopengpu.simCache=off -Dopengpu.simSeparateBuild=1 \
  -Dopengpu.simWorkspace="$OWN/sim" -cp "$CURRENT_CLASSES:$FROZEN_CP" \
  org.scalatest.tools.Runner -oD -s "$SUITE" -z "$CASE"
python3 -B scripts/test_changed_test_suites.py
```

Create the private classes/tmp/simulation directories first, wrap each run in
the stated process-group deadline, and require one actual test per selected
case. Publisher selects `publish into the actual owned context`; run each of
the four range cases and three reader cases individually. Fetcher selects
`retain original indexed ordinals` and `reject malformed slices`. Clean owned
outputs only after consumers end. Do not enable or dispatch Scala regression.

P39 remains incomplete until original Mesa/ESSL VS execution, the complete
protected source/parent/three-domain chain, retirement and the final full
2-CU 640x480/60 FPS qualification pass. See [the work plan](ALIGNMENT_PR_PLAN.md)
and [execution requirements](TESSELLATION_EXECUTION_GAPS.md).
