# Layered tile image binding

`LayeredTileImageBinding` converts a flattened layered-bin tile index into a
layer, screen-space tile rectangle and selected single-layer image views. It
uses a shared iterative divider and processes attachment views sequentially.
The request and result remain owned through cancellation and backpressure.

For each used source or resolve view, the binder checks the frame extent, row
pitch, per-layer stride, the complete declared layer span and 32-bit address
limits before publishing the selected base and limit. Disabled views are left
unchanged. The image owner must still validate the rest of the image descriptor
and retain allocation provenance, including cross-attachment aliasing.

The bounded unit test covers partial edge tiles, independent source and
resolve strides, invalid spans, the 65,535-tile limit, the 4 GiB endpoint,
snapshotting and cancellation:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.LayeredTileImageBindingSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

This is an internal binding client. Connecting it to a layer-aware tile owner,
native descriptors and real multi-layer image publication remains necessary
for H8.
