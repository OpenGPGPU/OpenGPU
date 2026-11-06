# Fragment input bank packing

The input staging buffer accepts four consecutive fields in one clock while
retaining the original line order sent to shader payload memory. Its four
128-bit SRAM banks keep the same depth, single-port organization and capacity.
For a local quad `q` and field `f`, the layout is `bank=(q+f)%4, row=f`.
A fixed quad's four aligned fields and a fixed field's four quads therefore use
different banks. The captured read word rotates the physical banks back into
the original quad order.

`FragmentShaderStage` sends aligned four-field groups, with independent masks
for partial groups, and includes the original depth/control initialization.
Metadata writes proceed independently; the stage waits for their suffix before
counting or accepting the next quad. Staging offers, two transaction IDs,
cancel/fault drain, task leases and output/readback ordering retain their
existing ownership rules. No SRAM bank, row FIFO or additional port was added.

## Measured result

Independent control and candidate models used source base `1aa1c8cf`, with
only `FragmentInputCoalescer.scala` and `FragmentShaderStage.scala` different
in Main. Both used the same qualification Test source, two real native-SRAM
CUs, 16 lanes, eight warps, 128-item capacity, 16-varying/four-color hardware
interfaces, FPU, grouped texture services and a 262,144-byte payload pool
(maximum payload 69,632 bytes). The unchanged 42/67-word product programs use
two active varyings and one active color; each pair ran one quad and 35 quads
(140 items, including a complete 128-item batch and a partial batch).

| Program / quads | Total cycles, control → candidate | Input phase, control → candidate |
| --- | ---: | ---: |
| 42 words / 1 | 827 → 826 | 94 → 93 |
| 42 words / 35 | 3671 → 3420 (−6.84%) | 1257 → 1015 (−19.25%) |
| 67 words / 1 | 2230 → 2230 | 94 → 93 |
| 67 words / 35 | 6427 → 6168 (−4.03%) | 1257 → 1010 (−19.65%) |

The predeclared 35-quad gates were 5% total/15% input improvement for the
42-word program and 2% total/15% input improvement for the 67-word program.
Both one-quad cases required no total or input-phase regression. All passed.
Shader words, completion/metadata/exports, accepted staging operation order
and enabled bytes, initial/returned backing memory, draw/task publication and
per-ID ownership/drain matched exactly. External reads matched by operation
and count; both sides performed zero external writes.

The one-quad readback phases changed 55→57 and 55→59 cycles. Their six reads
and all post-first-pair latencies are unchanged. Earlier kernel completion
aligns the first pair with the same `cycle%13<4` response hold window; the
actual public reply handshakes account for the two/four-cycle difference.
The final reply-to-phase-end tail stays 16 cycles. Internal response-valid
signals were not recorded as public ports; this causal conclusion combines
the real public handshakes with the unchanged response path and test driver.
No incident-budget allowance or added RTL delay was used. Full phase vectors
remain in local qualification evidence.

Eight existing coalescer cases also passed, including partial masks, padding,
byte order, split/final-address cases and cancellation. The CPU mapping proof
covers the full physical mapping, all nonzero four-bit masks and independent
flush byte references.

These are component shader-export/backing-memory measurements. They do not
qualify a whole frame, whole-host timing, or FPS. Current planning uses the
user-selected 1 GHz assumption: 33.33M/16.67M service cycles for 30/60 FPS.
Historical measurements retain their original source and frequency scope.

## Native Stage timing

The complete `mobile-public-frame-fragment-shader` component passed native
SRAM mapping and setup constraint coverage at a 1000 ps period with 25% virtual
IO delays. Core setup slack was +83.506 ps and virtual-IO slack was +62.774 ps;
there were no skipped or unresolved operators or coverage gaps. This emitter
uses the full product fragment interface, four shared-CU interfaces, 16 lanes,
eight warps, grouped textures and 640×480 metadata. The functional A/B above
instantiated two actual CUs. Physical evidence used the frozen `1aa1c8cf`
production sources. Subsequent integration changed only compute-resource ABI
emission/capability helpers outside this component's changed execution path;
their unchanged `end` constant remains reachable through uniform ABI checks.
Placement/routing, hold, whole-host clock and FPS are outside this setup result.

## Reproduction

The ordinary small check is:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  'testOnly opengpu.graphics.FragmentInputCoalescerSpec'
```

For the A/B, compile separate control/candidate worktrees with identical
`FragmentShaderStageSpec.scala` qualification source. Use the pre-change
production files for control and the changed files for candidate. In control:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -J-XX:+ExitOnOutOfMemoryError \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simCcache=off \
  -Dopengpu.simReleaseHeap=1 -Dopengpu.simCache=/tmp/h6-bank-control-model \
  -Dopengpu.fragmentInputBankQualification=1 \
  -Dopengpu.fragmentInputBankMode=control \
  -Dopengpu.fragmentInputBankOutput=/tmp/h6-bank-control \
  -Dopengpu.fragmentInputBankBaseline=/tmp/h6-bank-control \
  -Dopengpu.fragmentPhaseAudit=1 \
  -Dopengpu.fragmentPhaseAuditDir=/tmp/h6-bank-control-audit \
  'testOnly opengpu.graphics.FragmentShaderStageQualificationSpec -- -t "should measure unchanged product shaders through native input bank packing"'
```

Then run the same command from the candidate tree with mode `candidate` and
fresh candidate output/audit/cache paths; keep the control baseline path. The
qualification is opt-in and is excluded from routine CI. The measured run
used one native factory per variant, four cold draws per factory, a single
C++ compiler and fresh normal caches. Complete actual native inputs, raw
arguments, normal version/key, binary hashes, per-cycle public ports and
runner XML were retained locally for independent review. No source/key or
version substitution was used.

The native timing command used the same compiled Main classpath and standard
emitter/lowering. From the compiled candidate checkout:

```sh
H6_MAIN_CP="$(sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 --error 'export Compile / fullClasspath')"
java -Xms256m -Xmx3G -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError \
  -Dopengpu.productQualification=0 -cp "$H6_MAIN_CP" \
  opengpu.elaboration.EmitPpaRtl mobile-public-frame-fragment-shader \
  /tmp/h6-bank-ppa/rtl
python scripts/qualify_ppa.py /tmp/h6-bank-ppa/rtl FragmentShaderStage \
  --stage synthesis --synthesis-engine yosys --yosys-preserve-hierarchy \
  --clock clock --period-ps 1000 --io-delay 0.25 --timeout 14400 \
  --output /tmp/h6-bank-ppa/synthesis
```

The measured flow used native ASAP7 SLVT/TC and pinned tool image
`sha256:a61e1f55785dcd34306e833181fc3c9c1699eef30bfd518352dc5478a31ea1d2`,
with one synthesis CPU, a hard 6 GiB memory/no-swap limit and networking off.
Run synthesis under those worker limits. Require successful qualification,
native mapping and complete setup/virtual-IO constraint coverage before using
the reported slack; the commands do not constitute routed or hold timing.
