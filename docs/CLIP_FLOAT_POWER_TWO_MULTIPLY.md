# Exact power-of-two clip multiplication

`ClipFloatArithmetic` directly scales a finite nonzero operand when either
normalized significand is exactly `0x800000`. The product keeps the other
24-bit significand, combines signs and adds the extended exponents. Operand
legality, cancellation priority, the original exponent range, packing and
immutable response states are retained. The FMA and divider remain available;
zeros, special values and all other products retain their existing paths.
`powerTwoMultiplyShortcut=false` selects the previous multiplication path for
controlled comparison.

Twelve local RTL cases pass, including all ten existing arithmetic cases, with
one 2 GiB JVM, two processors and one native compiler worker. Independent
arbitrary-precision rational RNE and packing oracles check 1,254 complete
responses in both modes. All fields match, including tags, arithmetic and
packing status, range errors and signed results. The 1,056 eligible requests
take 12 clocks through the previous path and 3 clocks through the shortcut;
the remaining 198 requests retain their response and latency.

The comparison covers both operand orders, both signs, exponent and gradual
packing boundaries, seeded random finite operands, adjacent nonqualifying
significands, zero, infinity, quiet/signalling NaNs and malformed inputs.
Both implementations pass cancellation and reset at every actual request-clock
boundary, held replies, range failures, legality before cancellation, no ghost
outputs and healthy general division without reset. Input snapshots and tag
wrap also pass. Test-selection regression passes all 138 cases and continues
to select the production clip, viewport, primitive-raster and fragment owners.

These are arithmetic-service measurements. They establish no complete-frame
benefit, area, whole-host clock or FPS result. Complete product execution and
physical qualification must use the changed source.

Reproduce with the full `ClipFloatArithmeticSpec` command and GPU-internal
temporary/cache settings in [the arithmetic validation instructions](CLIP_FLOAT_FINITE_ZERO.md#reproduction).
The default test suite runs both multiplication modes; no property is required.
Run `python3 -B scripts/test_changed_test_suites.py` for test-selection coverage,
with `TMPDIR` inside the GPU worktree. Remove models, caches and logs once their
processes and other consumers have ended.
