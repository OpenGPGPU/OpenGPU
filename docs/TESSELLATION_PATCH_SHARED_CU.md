# Controlled isoline patch on the shared CU

`IsolinePatchSharedCu` connects a retained control patch and isoline evaluation
to the existing shared geometry CU service. It keeps the task's command ID and
kernel descriptor through completion and drains accepted evaluator requests
and patch inspections on cancellation. Outputs are provisional until the
completion succeeds; the enclosing owner must pin shader resources and reserve
the command ID throughout that interval.

The bounded admission test covers invalid layouts, cancellation before launch,
held completion and reuse. CI also runs the bounded patch evaluator and CU
executor tests. The local real-CU qualification executes the control-provided
isoline coordinates, checks both line endpoints, and reads retained patch data
while the evaluated output is held:

```sh
sbt -J-Xmx2G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolinePatchSharedCuAdmissionSpec'
sbt -J-Xmx3G -Dopengpu.simJobs=1 -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=1 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.IsolinePatchSharedCuQualificationSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

This uses the internal geometry scalar instruction ABI for coordinates. The
shader cannot yet read arbitrary patch data; control shader execution, full
TCS/TES ABI and compiler support, other tessellation domains, native submission
and product qualification remain open.
