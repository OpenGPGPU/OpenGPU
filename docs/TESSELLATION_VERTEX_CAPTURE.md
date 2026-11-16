# Original vertex capture controller

`TessellationVertexCaptureController` captures an accepted owner's
`TessellationVertexSourceRequest`, then serially connects the real GVS1 source
reader and original GND1 draw reader to one tag-zero record-memory route. A
successful capture reads eight plus 32 real 64-byte responses and returns the
accepted scope, decoded root and original `VertexShaderDraw`, without rebasing
its original indexed/instanced inputs. The 16-lane, eight-warp reader retains
16 attributes, 16 bindings, 16 varyings and all 64 component maps. The formal
GVS1 layout is recorded in [the source ABI](abi/tessellation-vertex-source-v1.json),
byte-identical to the protected encoder's catalog.

The completion reports status, failing stage/address and actual source, draw
and total bytes, including an accepted faulty response. Root and draw are zero
on every failure. An offered child submission or memory read survives cancel;
the controller consumes the real child completion before waiting for that
child to become idle. Route handoff and final reporting require both readers
idle and the actual request/response debt zero. Cancellation never erases that
debt. An observed child error, including a late memory fault, takes precedence
over external cancellation; a held final report is stable until accepted.

The three focused RTL cases supply literal canonical records through actual
accepted memory responses to both real readers. They cover success and reuse,
malformed or stale scope, foreign tags, held offers, cancellation at actual
child handshakes, both route-handoff drains, late faults and held reports.
Child completions are never injected.

[Fresh-source results, negative controls and reproduction](P39_CAPTURE_RANGE_SOURCE_DELIVERY.md)
record the current delivery checks without requiring historical class outputs.

This is not range generation, VS execution, native-host admission or a new
production route. The expected sequence/draw ordinal still comes from the
outer checked plan, not from the decoded GVS1 record. Protected code/UBO and
original-resource provenance, physical aliases, owner pinning, later
VS/TCS/TES/patch retirement, full-parent elaboration and product qualification
remain outside this capture component. No GPU execution or completed P39
integration is claimed.
