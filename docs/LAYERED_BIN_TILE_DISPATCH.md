# Layered bin tile dispatch

`LayeredBinTileDispatch` routes tagged bin completions to independently owned
image engines. Invalid engine tags are consumed and retained as a protocol
fault; the enclosing pass must drain all engines before clearing the fault.
The dispatcher alone does not provide full pass integration or native
submission.

The bounded test covers separate owners, reordered reports and invalid tags:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.LayeredBinTileDispatchSpec'
```
