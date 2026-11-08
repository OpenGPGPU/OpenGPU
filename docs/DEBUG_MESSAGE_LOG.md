# Bounded debug message log

Status: standalone integrated component with directed tests on local main. It
owns the ordered message store behind a debug output callback; API labels,
groups, severity filtering and the driver entry points remain open.

`DebugMessageLog` is a fixed-depth circular store of debug output records.

## Contract

- A record carries a 32-bit id, 4-bit source, 4-bit type, 3-bit severity, a
  5-bit byte length (0..31) and eight 32-bit payload words. The log does not
  interpret the enum values.
- Insertion is always accepted. While the log is full the new record is
  dropped and the sticky overflow flag is set. Reads return records in
  insertion order. The overflow flag clears only once the log has emptied or
  an explicit reset occurs.
- The default depth is eight records; the depth must be a power of two. A
  reset clears the store, the count, the overflow flag and both pointers so a
  reused context starts empty.
- `count`, `full` and `overflow` are published continuously. `out` is valid
  whenever at least one record is present and holds its payload under
  backpressure.
- A compound filter retains a record only when its severity, source and type
  each have an enabled bit in the three mask inputs. Filtered records are
  dropped silently: they neither occupy the log nor set the overflow flag,
  even when the log is full. With the filter disabled every record is
  retained as before.

## Directed evidence

`DebugMessageLogSpec` passes four cases:

- five records with distinct metadata and payloads read back in order;
- filling the log, dropping a ninth record, holding the oldest result under
  backpressure, preserving order for the retained records and clearing the
  overflow only after the log empties;
- reset clearing count, overflow and fullness and admitting fresh records;
- the compound severity/source/type filter, silent filtered drops when full,
  and overflow only when an accepted record cannot be stored.

```sh
sbt 'testOnly opengpu.graphics.DebugMessageLogSpec'
```

## Remaining work

Object names and labels, debug groups with push/pop and per-group filtering,
severity/type/text filtering, message timestamps, driver callbacks and the
`glDebugMessage*` API entry points remain open. Payloads longer than 31 bytes
are not representable in a single record.
