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

These are bounded arithmetic-service results. No complete frame, physical CU,
area, whole-host clock or FPS result is established. Whole-host execution and
physical qualification require models and netlists generated from the changed
source.

## Reproduction

Run in an isolated GPU topic worktree. Set `TMPDIR` and the JVM's
`java.io.tmpdir` to the same GPU-internal temporary directory with an absolute
path of at most 52 bytes. Direct sbt boot/global/ivy and Coursier caches into
the worktree's `.artifacts/` directory. Then run:

```sh
sbt -batch -J-Xmx2G -J-Xss4m -J-XX:ActiveProcessorCount=2 \
  -J-Djava.io.tmpdir="$TMPDIR" \
  -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'set Test / sources := (Test / sources).value.filter(f => Set("GpuSim.scala", "ClipFloatArithmeticSpec.scala").contains(f.getName))' \
  'testOnly opengpu.graphics.ClipFloatArithmeticSpec'
```

Remove the temporary directory, caches, `target/`, `project/target/` and logs
after confirming that their processes and other consumers have ended.
