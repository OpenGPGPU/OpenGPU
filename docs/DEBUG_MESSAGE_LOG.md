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
  and overflow only when an accepted record cannot be stored;
- the sixteen-entry message-ID filter list, pass-all mode, reserved ID zero and
  silent drops without downstream backpressure.

```sh
sbt 'testOnly opengpu.graphics.DebugMessageLogSpec'
```

## Transport envelope

`DebugMessageEnvelopeUnit` attaches the current 64-bit timestamp and the
current debug-group label key to each record. A record without a valid group,
or with object type zero, carries a cleared label so consumers cannot attribute
it to a stale object. Backpressure is forwarded unchanged. One directed case
covers timestamp/label attachment, cleared labels, zero object type and held
records (`DEBUG_ENVELOPE`).

## Remaining work

Object labels and debug groups with push/pop are implemented separately. Text
filtering, message timestamps, message-to-label linkage, driver callbacks and
the `glDebugMessage*`/`glObjectLabel`/`glPushDebugGroup` API entry points remain
open. Payloads longer than 31 bytes are not representable in a single record.

## Message-ID filter list

`DebugMessageIdFilterUnit` holds up to sixteen 32-bit message IDs. When its
enable is low every record passes; when high, only an enabled list entry equal
to the record ID passes. Dropped records are consumed without waiting for the
downstream ready, so a filtered stream never stalls on a log that is not
accepting. Entry zero is reserved and never matches, even if marked valid.
One directed case covers pass-all, list matching, silent drops, downstream
backpressure and the reserved-zero/empty-list cases (`DEBUG_ID_FILTER`).

