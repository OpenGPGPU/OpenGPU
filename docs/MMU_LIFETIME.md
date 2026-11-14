# MMU teardown

Destroying a VM returns an error if execution cannot become idle or its ASID
shootdown fails. The failed VM remains linked, with its page tables and ASID
reserved; the device is wedged. Context release retains the containing context
and its GEM bindings on that error, so the VM registry cannot point into freed
memory. A successful destroy first switches both page walkers to the retained
ASID-0 root, then evicts the retired ASID before releasing its tables.

Global MMU teardown refuses live VMs. After execution becomes idle, it writes
Bare mode to both SATP registers and performs a full TLB flush before releasing
the global tables. An idle or flush error retains those tables and backing.
VM creation rechecks MMU availability under the registry lock, including when
teardown completes while the creator allocates its unpublished root. A stopped
unified MMU returns `ENODEV`, so context creation cannot reinterpret shutdown
as a device that only supports Bare mode.

The host tests cover idle/ASID/global-flush failures, retained table contents and
ASIDs, live-VM rejection, last-context binding retention, subsequent successful
cleanup, and creation overlapping global shutdown. The tessellation transport
regression retains its original scheduler and image contracts. These CPU checks
do not establish GPU execution or product performance.

Reproduce with `TMPDIR` set to a temporary directory inside this repository:

```sh
python3 scripts/test_context_lifetime.py
python3 scripts/test_driver.py
```

The complete ARM64 module also builds against a configured Linux kernel. Copy
`driver/` into a temporary module directory inside `.artifacts/`, run
`make -C <configured-kernel> M=<module-directory> ARCH=arm64
CROSS_COMPILE=<cross-prefix> modules`, and remove the module directory afterwards.
