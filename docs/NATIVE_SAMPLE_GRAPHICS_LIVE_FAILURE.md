# Ordinary112 LIVE wire rejection test

The opt-in CPU integration test passed on source `461d96d7b` with original
Mesa 22.3.6, actual Linux protected ioctl/scheduler snapshots, and the original
308-word VS and 301-word FS. One guarded build compiled the 28 LIVE producer
translation units; Mesa libraries were reused. The immutable ARM64 container
used 512 MiB memory and swap limits, one CPU, no network, and read-only source,
Mesa, DRM and library inputs.

Both cases ran the real C LIVE producer and the unchanged
`native_sample_graphics_controller.exchange`, including its original source,
descriptor, capture, image and GNY2 oracles. A separately named test-only peer
executed no shader ISA. It supplied synthetic held/retired control receipts,
then checked the real C `OGRTR001` IRQ-drain trace, including raw completion,
append-byte telemetry, all GNX words and exactly one POP.

- Unchanged original RAM was rejected by the independent ABI1 image check.
- A hand-supplied synthetic image with the original, unwritten GNY2 was rejected
  by the independent GNY2 check. No valid GNY2 was synthesized.

Neither rejection published `record-0.response.bin`, a second capture,
cleanup authorization, final response or producer success marker. After the
expected rejection, explicit cancellation made the real C producer exit with
status 1 and the test-only peer exit with status 0. Process groups and owned
containers were confirmed absent before temporary inputs and outputs were
removed.

The guarded build and execution checks bound the committed source archive,
actual compiler-generated MMD inputs, generated includes, two original Mesa
objects, eight link libraries, binary, ESSL, and external Mesa/DRM/LD inputs
before and after use.
Actual GPU compilation dependencies also had to belong to the production
executor's registered source closure. The profile observations in this test
were explicitly synthetic; no executor qualification was fabricated.

Run from a clean committed checkout with the configured workspace Mesa/SDK:

```sh
python3 -B scripts/test_native_sample_graphics_live_failure.py \
  --run-original-mesa-live-cpu-failure \
  --compiler-image sha256:cc860b33283c55bb906f73dbe48621b6dbb4dcce6d65098f7f29550e49459d72 \
  --timeout 900
```

The explicit opt-in entry is outside ordinary CI because it needs Docker and
the configured native Mesa libraries. It proves the real C wire/failure path
reaches the independent rejection checks. It does not execute a DUT, establish
correct GPU images or GNY2, exercise successful owner cleanup, or qualify
640×480 product frames, a physical clock or 60 FPS.
