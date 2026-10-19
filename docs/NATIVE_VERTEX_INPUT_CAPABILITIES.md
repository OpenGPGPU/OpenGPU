# Native vertex input discovery

Native typed and native binned `GpuHostSystemAxi` profiles expose GVI1 at
`0x1b0..0x1bc`. This read-only block is independent of GNC image addressing,
native descriptor magic and the vertex shader export ABI. Probe the exact
magic and a successful read response before using input version 2; older hosts
and legacy profiles leave the block unmapped.

| Offset | Value | Meaning |
|---|---|---|
| 0x1b0 | 0x47564901 | GVI1 magic |
| 0x1b4 | 4 | Number of capability words |
| 0x1b8 | 0x00000502 | Maximum input version in bits 7:0; format ID width in bits 15:8 |
| 0x1bc | 0x01ffffff | Supported format IDs 0–24 |

Input version 1 retains formats 0–6. Version 2 additionally supports the
[converted formats](VERTEX_INPUT_FORMATS.md). Generated native typed and binned
C headers define the block address, fields and format IDs. Existing descriptor
record offsets, sizes, GNC discovery and GNX1 completion words are unchanged.
The register constants remain readable while VM owners are active and after
reset. Writes, including zero-strobe writes, return SLVERR without side effects.

The host control interface also corrects the shared burst-address decoder:
FIXED (`AxBURST=0`) retains the address and INCR (`AxBURST=1`) advances by four
bytes. These encodings follow the
[Arm AXI specification, Table A3-3](https://developer.arm.com/-/media/Arm%20Developer%20Community/PDF/IHI0022H_amba_axi_protocol_spec.pdf).
The control port supports aligned 32-bit words, FIXED bursts up to 16 beats and
INCR bursts up to 256 beats. Unsupported sizes, WRAP/reserved burst encodings,
4 KiB crossings and address carries return SLVERR before register mutation;
invalid read beats return zero. Single-beat FIXED accesses remain supported.

Initial checks pass for enabled GVI1 and the legacy unmapped profile, with
held read/write responses, INCR/FIXED reads and writes, all write strobes,
unaligned/out-of-map addresses, unsupported bursts and sizes, 4 GiB wrap
attempts, VM ownership and reset. Three existing AXI register/reset/fill checks
and both buffered/unbuffered VM-owner tests also pass: seven checks across three
suites. Full native-host integration is pending.
These bounded checks do not establish complete AXI or API conformance, Mobile
rate/budget closure or a new synthesis PPA result.
