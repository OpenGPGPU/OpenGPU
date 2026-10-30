# Tessellation warp barrier adapter

`TessellationWarpBarrier` parks multiple SIMT warps for one control-shader
patch. Each lane mask maps lane `i` to invocation `base+i`; active masks must
be nonempty, disjoint and within the 32-invocation patch limit. The adapter
serializes arrivals to the patch context and resumes each warp only after all
of its invocations are released. Barrier ID and epoch accompany each resume.
Invalid masks, overlaps and inconsistent releases report a checked error.

The owner must stop offering new work on cancellation and assert `terminal`
when it accepts an error result from the patch context. An arrival already
offered is held until accepted, and completed resumes are held under
backpressure. The patch context's cancellation path drains an already offered
arrival. Once an error result has been accepted, `terminal` retires any arrival
that the closed context can no longer accept and cancels the parked warps.
This module alone does not launch control shaders or connect the actual
compute-unit instruction scheduler.

The tests connect this adapter to the patch context for two 16-lane warps,
32 invocations, repeated barriers and cancellation, and check invalid masks and
backpressure:

```sh
sbt 'testOnly opengpu.graphics.TessellationWarpBarrierSpec'
```

Native control-shader execution, workgroup scheduling, primitive generation,
API/compiler integration and full H8 qualification remain open.
