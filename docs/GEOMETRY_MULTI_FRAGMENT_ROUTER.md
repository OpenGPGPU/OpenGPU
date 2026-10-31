# Geometry and multi-fragment CU routing

`GeometryMultiFragmentShaderRouter` shares a geometry-capable CU pool between
one geometry client and up to four fragment clients. It retains per-client
task ownership, captured payload bindings and completion routing while
completions may return out of order. The enclosing pass owns the clients and
must drain them before releasing resources.

The bounded test covers one geometry and two fragment clients, payload
bindings, geometry events, ownership and reordered completions:

```sh
sbt -J-Xmx3G -Dopengpu.simJobs=1 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GeometryMultiFragmentShaderRouterSpec'
python3 scripts/test_changed_test_suites.py
python3 scripts/test_test_selection.py
```

Full pass integration, native submission and physical qualification remain
separate work.
