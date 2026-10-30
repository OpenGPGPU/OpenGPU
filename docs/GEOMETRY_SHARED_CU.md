# Geometry on shared compute units

The optional shared graphics port captures a geometry task with an accepted graphics launch and routes each compute unit's output events to the graphics owner. Cancellation is per compute unit, with command reset cancelling all units. Compute launches do not acquire geometry permission.

`GpuSystemSharedGeometrySpec` exercises two concurrently owned graphics compute units with different captured inputs, held output events, a competing compute launch, and a graphics launch without geometry permission. Run it with:

```sh
sbt 'testOnly opengpu.system.GpuSystemSharedGeometrySpec'
python3 scripts/test_test_selection.py
```

This interface does not yet connect the production graphics scheduler, transform feedback, or binning. Geometry qualification and H8 remain open.
