# Tessellation control barrier

`TessellationBarrier` tracks one patch with up to 32 control invocations. It
releases invocations only after all have arrived at the same barrier ID, retains
stalled releases, and reports duplicate, out-of-range or divergent arrivals.
Repeated barriers use increasing epochs; completion and cancellation retain
ownership until the result is consumed. A cancellation while a release is held
preserves that offer until it is accepted.

The enclosing shader scheduler must drain each invocation's preceding patch
writes before presenting its barrier arrival and propagate cancellation to the
invocations. This component does not yet connect the patch store or implement
shader execution. The [OpenGL ES 3.2 specification, §11.2.1.2](https://registry.khronos.org/OpenGL/specs/es/3.2/es_spec_3.2.pdf)
requires the control-shader barrier and output visibility behavior.

The compact test covers 32 invocations, two barriers, held releases, divergent
IDs, early completion, invalid IDs, cancellation and task reuse:

```sh
sbt 'testOnly opengpu.graphics.TessellationBarrierSpec'
```

Patch-store integration, shared-CU control shader execution and H8 remain open.
