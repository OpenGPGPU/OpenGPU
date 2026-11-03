# Native tessellation command transport

The opt-in three-domain native host reports its internal GNQ1/GNQ2/GNQ3
command transport in GCR1 bit 11. Bit 12 additionally reports transform
feedback for that transport. The bits require the protected native host and
are clear on the default product profile. They do not advertise a userspace
submission interface or GLES conformance. The driver rejects feedback without
the command bit and rejects either advertised transport on an unprotected or
non-unified host.

`opengpu_hw_tessellation_async` submits a driver-owned GNQ snapshot after
checking its header and address arithmetic. The caller must separately
validate the complete frame, draw and resource records; prevent unsafe virtual
and physical aliases; and retain immutable snapshots, backing allocations and
VM mappings until the returned fence retires. No userspace ioctl calls this
internal transport yet.

Under the submission lock, the driver orders CPU writes, invalidates the GNQ
and other supplied snapshots, checks the execution owner, selects the VM and
publishes the unified command. The command uses these fields:

| Register | Tessellation meaning |
|---|---|
| `SOURCE` | 64-byte-aligned GNQ virtual address |
| `DESTINATION`, `PATTERN[0]` | Low 32 bits and bit 32 of its exclusive limit |
| `BYTES` | 512 |
| `WIDTH` | Context decoded from the GNQ snapshot |
| `HEIGHT`, `SOURCE_STRIDE` | Feedback program and cookie |
| `FLAGS` | Optional raster discard and feedback capture only |

Wait/signal events are rejected for this command. A limit at 2^32 is encoded
without truncation; the currently integrated host reports zero completed bytes
for tessellation. Feedback flags and scope values are rejected when that
transport is absent. Failure before publication returns no fence and does not
ring the tessellation doorbell; prior cache invalidation commands may have run.

The bounded AXI tests cover discovery with and without feedback, read-only
flags, protected ownership, the 2^32 limit, query context and feedback scope.
The C test exercises the production driver command writer for all three GNQ
formats, snapshot ordering, the same boundary, unavailable features and
invalidation/busy/idle failures. The Linux 7.2 guest driver also builds. Run:

```sh
python3 scripts/test_tessellation_transport_submit.py
bash scripts/test_scripts.sh
sbt -J-Xmx5G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=2 \
  -Dopengpu.simLowMem=1 -Dopengpu.productQualification=0 \
  'set Test / parallelExecution := false' \
  'testOnly opengpu.system.TessellationHostAxiCommandSpec opengpu.system.ComputeResourceCapabilitiesAxiSpec opengpu.graphics.ShaderImageCommandAbiSpec'
```

With the ARTI Linux build paths configured, reproduce the guest driver build
with:

```sh
ARTI_DIR=/path/to/arti ARTI_WORK=/path/to/arti-work \
  LINUX_BUILD=/path/to/arti-work/arti-linux-build \
  bash scripts/build_arti_guest_driver.sh /tmp/opengpu-tessellation-driver
```

Fresh default 720p workload and whole-host emissions match the `7617dcf4`
qualification baseline after normalizing source-location comments. The six
workload fixtures and their manifest match byte for byte. Reproduction commands
are in the [qualification notes](TESSELLATION_QUAD_EVALUATION.md#native-fragment-image-coexistence).

These checks do not establish scheduler or userspace submission, full resource
validation, cross-domain ordering, product timing, or H8 completion.
