# Private native sample submission bridge

`native_sample_submit_producer.c` consumes the private compiler's `OGNSF001`
container and submits its original code bytes through the actual Linux native
version-2 ioctl, scheduler, private owner, shader validator and MMU. The current
entry is a **CPU protection test**: its VS is CEASE, and its final hardware kick
and retirement are modeled. It does not render, export a runnable DUT fixture,
write GNY2 success, enable API caps, or qualify sample builtins.

The container is eight ASCII bytes `OGNSF001`, then fifteen little-endian u32s:
version (1), fragment ABI, input arrays, array stride, prefix bytes, used system
values, color output mask, writes depth, writes sample mask, requires sample
owner, requires geometry owner, thread capacity, varying slots, color slots,
code words. Exactly that many little-endian instruction words follow; trailing
bytes, truncation and more than 512 words are rejected. The ordinary entry
requires ABI6, capacity 128, 16 varying slots, four MRT slots, 75 input arrays,
512-byte stride and a 47616-byte prefix. It rejects ABI7 because there is no
real GS draw/owner. Compiler metadata does not authorize execution: the public
sample discovery helper must validate the CPU profile fixture and the actual
kernel cache, GND and GFS must independently admit the program.

The build imports the unchanged 96-, 104- and 112-word source tables from
`owner_resource_contract.py`. Only the exact sample table can establish the
sample cache. These values are CPU test inputs, not observations of hardware.
Every accepted program is also submitted against a disabled sample cache, a
mismatched varying layout and an ordinary draw with ABI7; each must fail before
enqueue and preserve the caller fence. The successful private RX copy is checked
word for word with exact R/X and no W, then checked again after changing and
closing the original code GEM. The real retained reference survives until the
modeled retirement. Invalid instructions are rejected by the actual validator.

```sh
PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_sample_submit_bridge.py
# Feed actual ESSL frontend artifacts; never substitute the synthetic unit fixture:
PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_sample_submit_bridge.py --compiled-directory /path/to/private/compiler/output
# Frozen old capture schema and actual parser regression:
PYTHONDONTWRITEBYTECODE=1 OPENGPU_TEST_SANITIZERS=1 python3 -B scripts/test_native_mmio_capture.py --executor-root .
```

The unit suite's CEASE container tests parsing and ownership only. The compiled
artifact mode passes every actual ABI6 program through the protected RX path at
1x, 2x and 4x and requires at least one ordinary program; it rejects ABI7. It does
not execute those programs. Actual frontend-to-RX results must be reported
separately from the synthetic unit suite.

## Actual DUT follow-on contract

A distinct live entry must replace CEASE VS with a real native vertex program
that writes position, valid outputs and the complete configured varying record.
It must preserve original ESSL FS bytes. It must use original backing GEMs and
keep the exact private owner, PTE pages, original references and leases alive
through the following protocol, without the CPU retirement helper:

1. Probe the complete 112-word factory and five auxiliary registers. The fixed
   sample schema and `OGNP0002` wire reject the old 96/104 factories.
2. Export `OGMEM001` union memory and `OGCAP002` (same field sequence as 001,
   exactly 117 ordered discovery words, 13 completion/GNX checks). Keep 001
   fixed at its old 101-word schema.
3. Wait for actual held completion/IRQ/GNX and full GNY2 readback, then call the
   real driver's held drain to emit `OGRTR001`. Accept only actual retired and
   external-drained observations before signaling its execution fence.
4. Check independent images and all 4096 canonical GNY2 words before authorizing
   `finish_observed`. Apply and ACK every `OGCLB001`/cleanup trace PTE revocation
   and TLB operation, ending with `OGCLD001`. Only then destroy the session.

The image oracle must calculate raw per-sample values from the original ESSL
expressions, coverage and fixed locations: 1x (1/2,1/2); 2x (1/4,1/4),
(3/4,3/4); 4x (1/4,1/4), (3/4,1/4), (1/4,3/4), (3/4,3/4). Use distinct
sample-ID, position, input-mask and output-mask expressions; verify each stored
sample before any resolve. Include full and edge coverage, helpers, a mask
that suppresses a covered sample, all-zero output mask, and mask bits outside
the invocation's coverage. Derive expected masks by intersecting geometric
coverage, invocation coverage and shader mask. The GNY2 oracle must independently
check identity, counters, zero padding, healthy owner status and complete
publication. A GNX success or header-only GNY2 read is insufficient.

GS/ABI7 needs a real GS program and owner, raw GS output and the layered route;
it cannot borrow an ordinary draw's ABI6 proof. Dynamic interpolation services,
API enable/minimum policy, full RTL execution and frame/FPS qualification remain
open. The old ABI1 six-scene product run is a separate qualification.
