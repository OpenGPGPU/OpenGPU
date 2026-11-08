# Bounded debug object labels and groups

Status: standalone integrated component with directed tests on local main. It
owns the object-label table and debug-group stack behind a debug output
contract; API entry points and message linkage remain open.

`DebugLabelStore` retains eight object labels and an eight-deep group stack.

## Contract

- A label is keyed by a 4-bit object type and 32-bit object id and carries a
  byte length (0..31) plus eight payload words. Writing an existing key
  replaces its text without changing the count; a new key takes the first free
  slot. A write when the table is full is dropped and sets the sticky overflow
  flag.
- Removing a key clears its slot. Queries return the stored label or a
  not-found result tagged with the caller's tag, and hold the result under
  backpressure.
- The group stack stores complete labels. Push appends when the stack has
  room and drops with the same overflow flag when full; pop removes the
  current group and is ignored at depth zero. `groupValid` and `group` expose
  the current label; `groupDepth` exposes the depth.
- An explicit clear empties the table, stack and overflow flag so a reused
  context starts clean. Reset initializes the table to empty.

## Directed evidence

`DebugLabelStoreSpec` passes four cases:

- eight labels with exact payload round-trips, in-place replacement, removal
  and re-addition, and count/full tracking;
- a ninth new key dropped with the overflow flag set, then a clear restoring
  an empty table and admitting a fresh label;
- eight pushed groups with current-label tracking at every depth, a ninth
  push dropped, pop-to-empty and push after clear;
- held query results under backpressure.

```sh
sbt 'testOnly opengpu.graphics.DebugLabelStoreSpec'
```

## Remaining work

Severity/type/text filtering, label storage for unlimited API counts
(eviction policy), linkage of labels and groups to debug-message records,
timestamps and the `glObjectLabel`/`glPushDebugGroup` API entry points remain
open.
