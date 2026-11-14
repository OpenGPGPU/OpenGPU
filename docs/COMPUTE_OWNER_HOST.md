# Compute execution on the complete owner host

`scripts/compute_owner_driver.cpp` executes an original Linux compute capture
on the same complete, assertion-enabled 96-word `NativeOwnerHostProfile` DUT
used for protected native rendering. It shares the AXI service, original
physical memory image and acknowledged MMU cleanup runtime with the native
executor. Its compute capture, completion and retirement protocols are separate.

The first supported execution is one direct opcode-0 job in a fresh isolated
context. Preparatory opcode-5 invalidations must complete and be popped before
the final kernel enqueue. The capture must end at that enqueue, use the actual
shared nonzero Sv32 ASID, and retain the original launch and physical writable
intervals. All 96 source-contract words and ten auxiliary/transport observations
must match fresh DUT reads before submission. Unsupported opcodes, resets,
events, malformed local products and native capture/retirement codecs fail.

`OGCMP001` contains a u64 cycle budget, u32 hold cycles and u32 command ID;
then u32 counts followed by discovery address/value pairs, writable u64
first/end pairs and four-u32 kind/address/value/mask actions. Counts and byte
bounds are checked. The executor runs only those original actions and services
external AXI while polling MMIO; it generates no shader, descriptor or output.

After the real IRQ, it requires exact successful completion `0x8000 | id`,
zero processed bytes, a stable held completion/telemetry window, drained
external traffic and unchanged bytes outside the original writable intervals.
It publishes `record-0.held.json` with schema
`opengpu-compute-owner-held-v1`. It never probes native GNX registers. The live
original Linux IRQ/drain code must then publish `record-0.retire` using
`OGCMT001` and a count of four-u32 actions. All three completion/telemetry
words must be read before the single POP. The executor performs that trace,
checks idle/IRQ/drain and unchanged post-completion RAM, writes
`record-0.memory`, then publishes `record-0.retired.json` with schema
`opengpu-compute-owner-retired-v1`.

`OPENGPU_COMPUTE_HOST_TIMEOUT` controls every executor IPC wait, including the
initial capture, retirement and all acknowledged cleanup phases. It uses the
same decimal 1..604800-second contract as the original producer/controller,
with a 300-second standalone default. The formal controller passes its chosen
`--timeout` to both processes. Malformed values fail before a DUT reset or
probe; the separate captured DUT cycle budget and all byte/completion oracles
remain unchanged.

An independent oracle must compare all returned result bytes and source/guard
bytes before authorizing the original producer to release resources. Only
then may the producer emit `cleanup.begin` (`OGCLB001`, count one). The shared
acknowledged cleanup protocol verifies original PTE revocations, the retiring
ASID flush, and final dual bare SATPs plus full TLB shootdown. Final acceptance
requires `contextCleanupObserved:true`; it preserves outputs, one reset and
the original RAM. Producer VM/PT/ASID backing must survive until its actual
DUT acknowledgment. Host bookkeeping after this protocol performs no MMIO.

Build from an already prepared and completely generated owner model, keeping
all paths inside the GPU checkout. The guarded entry never emits or regenerates
RTL, and rejects missing or stale `generation.json`, MLIR, assertions or shards:

```sh
python3 scripts/prepare_owner_executor.py --stage build --kind compute \
  --owner-output "$OWNER" --flashsim-dir "$FLASHSIM" --shards 32 --jobs 1
EXECUTOR="$OWNER/executors/compute/executor"
QUALIFICATION="$OWNER/executors/compute/qualification.json"
python3 scripts/prepare_owner_executor.py --stage check --kind compute \
  --qualification "$QUALIFICATION" --executor "$EXECUTOR"
"$EXECUTOR" --probe-profile "$NEW_PROFILE_JSON"
# Start the original producer with that observed profile; wait for its export.
"$EXECUTOR" --active-owner "$INPUT_MEMORY" \
  "$NEW_OUTPUT_DIR" "$SESSION_DIR"
```

The checker binds the original 96-word preparation, generated model/toolchain,
all driver/adapter/ABI/checker sources, compiler identity/options/SDK environment,
linked shard set, builder snapshot and executable bytes. Native and compute
have fixed separate driver/dependency lists. Both reuse the same guarded
`$OWNER/flashsim-build` object cache and retain their own small executables and
build snapshots, so building the other driver does not invalidate an existing
executor. Changing its source, actual model, tool or binary does invalidate it.
A failed new build removes its previous success record before input validation.

The controller must check qualification before probing and again before final
release authorization. It holds an exclusive flock on `$OWNER/.lock` throughout
probe, live producer/executor, oracle and final cleanup; builds and product
qualification use that same lock. Checks are read-only and take no lock.
Call the checker from its original source checkout, using the generation's
Python interpreter and CIRCT/loader environment. Copying the checker into a
different checkout or relaxing tool identity is not supported. The public
Python entry is `verify_qualification(path, executor=..., kind='compute')`.

CPU protocol tests pass with ASan/UBSan, including delayed real-producer-shaped
retirement and cleanup, bad transport/identity/telemetry, readonly corruption,
late RAM mutation and native-codec/GNX rejection. Run:

```sh
OPENGPU_TEST_SANITIZERS=1 PYTHONDONTWRITEBYTECODE=1 \
  python3 -B scripts/test_compute_owner.py
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/test_prepare_owner_executor.py
```

The CPU peer executes no instruction. Actual full-DUT execution, Mesa producer
integration, independent returned-byte qualification and fault/reuse cases are
still required. This single-job entry does not establish continuous multi-job
publication, all compute resource domains, GLES closure, physical clock or FPS.
