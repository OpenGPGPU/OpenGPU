# Layered bin tile ownership

`LayeredBinTileOwner` retains one bin credit while its tile is bound, replayed
and published through an image engine. It snapshots the binding request, holds
completion under backpressure and propagates cancellation and image faults to
the producer. The enclosing pass must retain the template and drain all
accepted work; this block alone does not establish image publication or native
submission.

The bounded test covers held requests and completion, empty tiles, producer and
image faults, cancellation and credit release:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.LayeredBinTileOwnerSpec'
```
