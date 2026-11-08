# Context robust-access and reset status

Status: standalone integrated component with directed tests on local main. It
publishes the context-flag and reset-notification state contract; per-resource
robust memory behavior, API object lifetime and physical reset timing remain
open.

`GraphicsContextRobustness` owns the context-level state behind
`glGetGraphicsResetStatus` and `GL_CONTEXT_FLAG_ROBUST_ACCESS_BIT`.

## Contract

- The context-flag word publishes bit 2 (`0x4`) when robust access is
  configured; otherwise it is zero.
- With `NO_RESET_NOTIFICATION` (strategy 0) a fault never latches a reset
  cause or marks the context lost.
- With `LOSE_CONTEXT_ON_RESET` (strategy 1) the first fault latches a cause
  and marks the context lost. A fault whose `known` bit is clear reports
  `UNKNOWN_CONTEXT_RESET`; otherwise a fault that is marked guilty or carries
  the local context id reports `GUILTY_CONTEXT_RESET`, and any other context
  reports `INNOCENT_CONTEXT_RESET`. Later faults do not replace the first
  cause.
- A status query is always valid. Accepting it returns the latched cause and
  clears the cause to `NO_ERROR`; the lost flag stays asserted until an
  explicit restore, which clears the cause, the latch and the lost flag.
- Reserved strategy values latch nothing. Query results are held under
  backpressure.

Status values are 0 `NO_ERROR`, 1 `GUILTY_CONTEXT_RESET`, 2
`INNOCENT_CONTEXT_RESET` and 3 `UNKNOWN_CONTEXT_RESET`. A fault that arrives
in the same cycle as an accepted query wins over the clear.

## Directed evidence

`GraphicsContextRobustnessSpec` passes two cases:

- both notification strategies, local/remote/unknown guilt, first-cause
  stickiness, query clearing, restore, reserved strategy values and the
  robust-access flag;
- held status and lost state under query backpressure, with the cause cleared
  only after acceptance.

```sh
sbt 'testOnly opengpu.graphics.GraphicsContextRobustnessSpec'
```

## Remaining work

Robust memory access behavior at each resource owner (out-of-range reads
returning zero, discarded writes, shader memory fault handling), API object
lifetime and context-destroy semantics, `glReadnPixels`/`glGetnUniform`
clamping, delivery of reset events from all fault owners, and product/API
qualification remain open.
