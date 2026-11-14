# Finite-zero clip arithmetic

`ClipFloatArithmetic` returns exact signed zero directly for finite
multiplication with either operand zero, and for a zero numerator divided by
a finite nonzero denominator. Operand legality and cancellation retain their
original priority. The existing packing, response, ownership and held-output
states remain in use. Zero divided by zero, NaN, infinity, malformed operands
and every other operation retain their existing paths.

`finiteZeroShortcut=false` selects the previous implementation for controlled
comparison. Both implementations retain the FMA and divider, extended exponent
range, significand precision, request tags and accepted-operation accounting.

## Results and limits

Ten unique local RTL cases pass with a single 2 GiB JVM, two processors and one
native compiler worker. The new comparison checks all response fields for 655
requests, including both zero signs, both operand orders, the complete exponent
envelope, special values, invalid operands and tag wrap. Its 400 eligible
requests have these measured request-to-output-offer clock counts:

| Operation | Requests | Previous path | Finite-zero path |
| --- | ---: | ---: | ---: |
| Finite multiplication with a zero operand | 268 | 12 | 3 |
| Zero divided by finite nonzero | 132 | 7 | 3 |

The remaining 255 requests retain both their full response and their latency.
Cancellation and reset at every actual request-clock boundary pass for both
implementations, including held replies, no ghost output, legality before
cancellation and healthy general division without reset. Existing exact
rational RNE/directed arithmetic, gradual packing, codec, special-value and
drain tests also pass.

Required CI selects the clip, viewport, primitive-raster and fragment owners,
including spatial preparation, streaming, setup reuse and instance isolation.

These are bounded arithmetic-service results. No complete frame, physical CU,
area, whole-host clock or FPS result is established. Whole-host execution and
physical qualification require models and netlists generated from the changed
source.

## Streaming consumer comparison

This table records the historical comparison in [PR #799](https://github.com/OpenGPGPU/OpenGPU/pull/799),
before the power-of-two multiplication shortcut reached the streaming consumer.
Reproduce its clock counts at [that source revision](https://github.com/OpenGPGPU/OpenGPU/tree/6bb096440ef05eba92ab77e1ebe7d529c98aa711).
Current defaults also enable the multiplication shortcut; see
[the power-of-two consumer results](CLIP_FLOAT_POWER_TWO_MULTIPLY.md#streaming-consumer-comparison)
and their explicit `opengpu.fragmentMultiplyQualification=control|fast`
[reproduction modes](CLIP_FLOAT_POWER_TWO_MULTIPLY.md#reproduction).

Five four-context streaming RTL cases pass in each explicit `control` and
`fast` mode (ten executions). Both retain maximum 16 varyings, 64 components,
sixteen preparation services and the same component bank, with setup reuse
enabled in both. All twelve profile pairs have identical complete final quad
and completion data and pass the independent sample/centroid/FP32 oracle.
Held output, cancellation, reset and healthy reuse without reset pass in each
mode.

| Profile | Total clocks, control → fast | Steady four-quad period |
| --- | ---: | ---: |
| Full 64-component baseline | 1839 → 1839 | 357 → 357 |
| Existing nine active-slot/sample/centroid profiles | Unchanged in all nine | Unchanged in all nine |
| Two active slots, W=1 and equal depth, 1× | 1207 → 1119 | 241 → 219 |
| Same plane, 4× samples | 2443 → 2003 | 550 → 440 |

Each profile processes sixteen quads under independent output and completion
stalls. The two added plane profiles use reciprocal W=1 at every vertex and
depth 0.5; their benefit is specific to these inputs. The original profiles,
including the product-like active-slot and sample masks, show no cycle
improvement. These component results establish no six-scene frame or FPS gain.

The default CI active-profile case runs both shortcut modes and compares
per-profile full output/completion digests. Explicit zero-service or setup-reuse
qualification properties select one mode, keeping the two experiments
independent. Digest accumulators and comparison state are local to each test
and profile.

## Reproduction

Run in an isolated GPU topic worktree. Set `TMPDIR` and the JVM's
`java.io.tmpdir` to the same GPU-internal temporary directory with an absolute
path of at most 52 bytes. Direct sbt boot/global/ivy and Coursier caches into
the worktree's `.artifacts/` directory. Then run:

```sh
COURSIER_CACHE="$PWD/.artifacts/coursier" \
sbt -batch -J-Xmx2G -J-Xss4m -J-XX:ActiveProcessorCount=2 \
  -J-Djava.io.tmpdir="$TMPDIR" \
  -Dsbt.global.base="$PWD/.artifacts/sbt-global" \
  -Dsbt.boot.directory="$PWD/.artifacts/sbt-boot" \
  -Dsbt.ivy.home="$PWD/.artifacts/ivy" \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "ClipFloatArithmeticSpec.scala").contains(f.getName))' \
  'testOnly opengpu.graphics.ClipFloatArithmeticSpec'
```

Remove the temporary directory, caches, `target/`, `project/target/` and logs
after confirming that their processes and other consumers have ended.

For the historical streaming comparison, use the source revision from PR #799
and the same resource/cache options above. Substitute the following source
selection and test command, and run twice with
`-Dopengpu.fragmentZeroQualification=control` and then `=fast`:

```text
set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "GraphicsTestResolution.scala", "Fp32StreamingFragmentInterpolatorSpec.scala", "Fp32FragmentInterpolatorSpec.scala", "Fp32ViewportStageSpec.scala", "Fp32TriangleRasterStageSpec.scala", "Fp32SampleDepthSpec.scala", "CentroidRasterReference.scala", "PointLineRasterizerSpec.scala").contains(f.getName))
testOnly opengpu.graphics.Fp32StreamingFragmentInterpolatorSpec -- -z "own four raw" -z "retain full hardware" -z "one-cycle cancellation" -z "offered head" -z "reset all raw"
```
