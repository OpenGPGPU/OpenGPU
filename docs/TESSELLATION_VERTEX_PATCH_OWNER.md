# Original VS and patch task ownership

`TessellationVertexPatchOwner` retains one accepted original VS descriptor and
one typed image task. The enclosing domain adapter must validate matching
task identity and layout, connect the real source publisher to the image
context's load/reply/seal ports, and supply actual child completions. This
controller does not execute shaders, manufacture a seal, or replace either
child's completion.

The image task transfers first. A successful `opened` event permits the VS
offer; an admission rejection can complete without opening a context or
launching VS. All original draw bindings, component maps and slice fields
remain captured until retirement. The reported task ID always comes from the
accepted original VS descriptor, including when a locally rejected image has
no meaningful nested task ID.

Cancellation does not withdraw a held image or VS offer. Only the image's
actual terminal frame completion permits abandoning an unaccepted image
offer. A held VS offer must transfer before the controller cancels its
accepted producer. `sourceOwned` also lets the enclosing adapter gate payload
cancellation until that transfer. A previously offered parent range task can
be accepted and canceled locally without starting either child.

The controller accepts the actual VS completion while `sourceBusy` remains
asserted, then waits for that busy signal and all local transport credits to
drain. Image success requires a successful matching VS completion, allowing
both actual completions to transfer in the same cycle. A launch acceptance
alone cannot satisfy this requirement. The first noncancel failure remains
primary, and a real fault takes priority over cancellation. A foreign VS task
ID or premature image success reports a protocol failure.

| Report status | Meaning |
| --- | --- |
| 0 | Successful image and original VS retirement |
| 1 | Image failure or terminal frame before image acceptance |
| 2 | Original VS failure |
| 3 | Protocol failure |
| 4 | Cancellation without a noncancel failure |

`transportDrained` covers local router and original-VS memory/payload credits.
It must not depend on unrelated fragment work or whole-frame completion.
The controller's `drained` excludes its held parent report: including that
report in the range's child-busy input would make report acceptance circular.
Whole-frame retirement still requires the enclosing image's full drain.
After a report is offered, a new valid-only flush may lower
`transportDrained`. The report remains immutable and may transfer; live
`drained` and the next `begin.ready` still wait for transport to drain again.
The held-report assertion checks completed children and absence of new offers,
without treating that later flush as unfinished work from the reported task.
The enclosing owner retains program, uniform, GEM and MMU resources through
whole-frame and whole-fabric retirement, including after this task report.

## Staged results and limits

The two actual Owner/Publisher tests pass in the complete fresh staged source.
The controller test covers admission rejection, held offers during cancel,
real completions while source busy, late faults, foreign task IDs, premature
image success, simultaneous completions, backpressure, later flush and reuse.
The second connects the actual Publisher/Owner feedback, held writes, real
replies and seals, fault priority and healthy no-reset reuse. VS execution and
image/store responses remain protocol peers; no shader is executed.

Two independently compiled source-copy controls reproduce the exact cancel
combinational cycle and held-report drain assertion. Full compilation,
source/consumer checks, per-case bounds, original batch limit and reproduction
are recorded in the [typed-parent validation](TESSELLATION_VERTEX_INPUT_IMAGE.md#staged-validation-and-limits).
The three real typed parents also pass CIRCT lowering with the fixed Owner.

Native/protected source admission, original three-domain shader execution,
complete resource and product qualification remain pending. This controller
never enables a factory, public API, getter or discovery bit. Keep Scala
regression disabled.
