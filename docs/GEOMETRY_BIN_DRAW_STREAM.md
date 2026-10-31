# Geometry bin draw stream

`GeometryWindowBinDrawStream` converts assembled primitive draws into owned
geometry bin tasks, preserving identity, source completion, backpressure and
cancellation through the draw report. `GeometryBinTaskTerminalBridge` drains a
held offer after pass termination and returns a local cancellation result.

The bounded tests exercise held tasks, source drain, cancellation and reuse:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryWindowBinDrawStreamSpec opengpu.graphics.GeometryBinTaskTerminalBridgeSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

Native submission and complete layered image pass qualification remain open.
