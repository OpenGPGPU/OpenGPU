# Tessellation patch context

`TessellationPatchContext` owns a patch from input loading through control
invocation completion and output inspection. It connects the bounded patch
store to the invocation barrier and waits for accepted accesses and their replies
to drain before accepting a barrier arrival or invocation completion. The issuer
must submit each invocation's requests in program order.

During control execution, input is read-only, per-vertex output writes are
limited to the issuing invocation's vertex, and per-patch output writes are
shared. An invocation blocked at a barrier cannot access patch data until its
release. Invalid operations return a checked error. On cancellation, the
issuer must stop issuing new work; already offered loading and control accesses
are accepted as checked errors, while offered barrier and finish events are
discarded. Previously accepted accesses and replies drain before cancellation
is reported. If a completion result was already offered, it remains stable and
the consumer must withdraw unaccepted work when accepting that result. Output
inspection and patch release remain explicit after control completion.

The compact test covers held write replies before a barrier, cross-invocation
reads after release, rejected writes, divergent barriers, cancellation with an
outstanding reply and reuse:

```sh
sbt 'testOnly opengpu.graphics.TessellationPatchContextSpec opengpu.graphics.TessellationPatchContextCancelSpec'
```

Shared-CU instruction execution, tessellation level/primitive generation, native
submission and full H8 qualification remain open.
