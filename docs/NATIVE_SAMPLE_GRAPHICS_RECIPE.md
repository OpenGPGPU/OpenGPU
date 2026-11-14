# Canonical ordinary112 triangle descriptors

`scripts/native_sample_graphics_recipe.py` checks the fixed
`original-abi1-uniform-triangle-v1` source plan. It is separate from the old
96-word executor and the single-record ABI6 sample-point recipe. It preserves
the 16-attribute/16-binding/16-varying/four-MRT/four-engine descriptor envelopes
and the ABI1 programs' 128-thread capacity. This bounded input uses one varying,
one RGBA32UI attachment, a 4x4 frame and one sample; it does not qualify all
varyings/MRTs or sample builtins.

`verify(initial, description, programs, reflection, returned=None)` first calls
the existing source checker with separately supplied compiler bytes and linked
reflection. That checker binds both original/private RX programs, all 24 UBOs
and two defaults, canonical GUR/GUB packing, original resource views and actual
Sv32 PTEs. Its original vertices are `(-1,-1,0,1)`, `(3,-1,0,1)` and
`(-1,3,0,1)`: an ordinary clipping triangle, not the sample-point recipe.

The new checker independently builds all 128 GNJ2, 512 GNF3, 512 GND1 and 128
GFS words from zero. It uses the published ABI schemas, the fixed draw/raster/
viewport/ABI1 policy, explicit original resource roles, each owner's private
descriptor/code addresses and checked uniform roots. It compares every word,
including reserved words and high padding, and each private descriptor's
page-allocation tail. It does not copy any initial or returned descriptor field
into an expected record. Both owners keep their own code/resource/descriptor
addresses; the second owner's changed original UBO/default bytes remain bound
by the independent source checker.

`records(description)` is a memory-free expected-record constructor.
`verify_records(space, description)` is a lower-level descriptor/tail check for
mutation tests. Neither lower-level function establishes compiler provenance;
callers need the complete `verify` entry. Original GEM handles and relocated
addresses are opaque observations, not independently predicted handle numbers.
Their physical windows, permissions, roles, aliases and original bytes must
remain bound to the retained source plan and actual producer in a future live
controller.

Six ASan/UBSan CPU tests pass. They use real Linux protected snapshots,
relocation and MMU code with explicit placeholder CEASE programs and synthetic
reflection. The existing source-only fixture is accepted by the narrow source
checker but rejected here because its original draw defaults are not the fixed
ordinary encoder recipe. A separate literal C draw/state input, following the
ordinary encoder request, passes through the real snapshot code for the
canonical positive cases. Python expected bytes are never fed to that C input.
The tests reject 5120 low/high-bit descriptor-word mutants across both owners,
33-bit limit mutations, descriptor allocation tails, original-view boundary/
alias/version changes and cross-owner or unreachable code/root/record links.

Reproduce serially from the topic checkout; outputs are temporary and removed
after the CPU consumers exit:

```sh
OPENGPU_TEST_SANITIZERS=1 PYTHONDONTWRITEBYTECODE=1 \
  python3 -B scripts/test_native_sample_graphics_recipe.py
```

This slice does not run Mesa, a shader, a DUT, or a modeled completion peer. It
does not check GNY2 results, issue hardware ACKs, retire owners or authorize
cleanup. The current source checker remains unchanged. No test lane or
executor qualification closure is changed by this slice.

A future ordinary112 two-owner executor can reuse exact OGNP0002 discovery,
OGCAP002 parsing and the generic held/retired/PTE-cleanup mechanisms. It needs
a distinct executor kind and ready/receipt/authorization contract with strict
record indexes 0 and 1, one reset and one retained union memory. The existing
native96 active-series probe and OGNS single-record/count-one messages must
remain strict. The new contract must bind external compiler/reflection inputs,
both source plans/captures and this checker before admitting MMIO work.

Its independent GNY2 oracle must account for the triangle's real clipping fan,
post-clip admitted/received parts, producer records/references and image replay
counts. It cannot reuse the point oracle's one-reference/23-line constants or
its four-color-plus-depth stored-byte total. No unknown GNY2 counter value or
GPU qualification is asserted here.
