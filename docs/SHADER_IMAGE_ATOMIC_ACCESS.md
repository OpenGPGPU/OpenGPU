# Checked image atomic access

`ShaderImageAtomicAccess` accepts a caller-supplied immutable image binding,
coordinates and a word operation. It resolves the binding with both read and
write permission, accepts R32I and R32UI image formats with a matching qualifier,
and checks word alignment and the complete backing line before offering a
virtual request to the shared atomic interface. It maps exchange, add, bitwise
operations, signed or unsigned min/max and compare/exchange to the existing
atomic operation codes. Unbound and out-of-range texels produce a zero no-op.

The parent must fetch and retain the checked GPU descriptor, translate and
arbitrate the atomic request with the retained VM context, and hold publication
and backing ownership through result consumption. An offered request remains
stable until accepted even after cancellation. Accepted operations drain their
response before retirement; a reported memory fault takes precedence over
cancellation. A successful operation may already have modified memory when
canceled, and cannot be rolled back. Held results remain stable.

One bounded local test passes with no skips:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.productQualification=0 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.ShaderImageAtomicAccessSpec'
```

The test checks all eight operation mappings, signed/unsigned min/max, the
permission matrix, qualifier and format rejection, unbound/out-of-range no-ops,
misalignment, held offers, wrong-owner replies, cancellation, fault precedence,
and healthy reuse. The shared atomic responder is mocked: this does not test
contention or prove atomicity in the actual L2. This component is not connected
to GPU descriptor fetch, CU instruction decode, translation, a native host, or
API/compiler submission. Cross-domain ordering, product capacity and physical
qualification remain open; it does not complete H7 or advertise a capability.
