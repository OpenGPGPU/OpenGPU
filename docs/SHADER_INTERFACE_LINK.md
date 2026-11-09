# Linked shader interface blocks

`ShaderInterfaceLinkUnit` validates a versioned vertex-output and
fragment-input interface before either side is admitted:

- every active fragment input must match exactly one vertex output at the same
  location with identical kind, component count, array size and interpolation;
- extra vertex outputs are allowed;
- duplicate locations on either side, active slots marked invalid, or counts
  above sixteen reject the interface;
- the result reports matched, missing, fragment-duplicate, vertex-duplicate and
  mismatch masks for all sixteen slots.

One directed case covers a matched subset, extra vertex outputs, missing
locations, kind and interpolation mismatches, both duplicate sides and invalid
counts (`SHADER_INTERFACE_LINK`). Compiler block linkage and later-stage
interface admission remain outside this hardware validator.
