# Compute storage byte views

`OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BYTE_VIEWS` (driver feature bit 27)
allows `OPENGPU_RESOURCE_COMPUTE_STORAGE` bindings with 256-byte-aligned
offsets and any positive byte size through 128 MiB. Query the bit before using
these ranges; older drivers require page-aligned offsets and sizes divisible
by 64. The existing four bindings, paired slots, permissions, ownership,
synchronization and shader validation rules remain in force.

The shader descriptor keeps `limit = base + size` exactly. A 32-bit word access
must fit entirely inside that view. A one-byte view is a valid binding but
cannot authorize a word access. The allocation envelope alone rounds the limit
up to 64 bytes, because the existing storage transport reads complete lines
and uses byte masks for stores. Binding proves that this entire envelope is
inside the same GEM object and the DMA32 address range before publishing a
mapping. GEM padding never expands the shader's view. Image and counter views
still have their own admission requirements.

Each private storage window reserves 128 MiB plus one 4 KiB page, preserving
all four maximum views even at an in-page physical offset of `0xf00`. The
fourth window ends at the tessellation state arena without overlapping it.
These virtual addresses are private implementation details. The actual MMU
regression maps all four maximum views alongside 32 texture pages, 12 uniform
pages, nine command snapshots and the code/default argument pages; it uses
184 of the existing 192 leaf tables and verifies physical head/tail translations.
This checks that specific combination, rather than every possible resource mix.

Local validation covers 768 actual bind/rebind cases, 44 actual storage
descriptor preparations, rejected ranges and allocation/map/unmap failures,
ownership cleanup and recovery. The bind fixture extracts the production
ioctls and mapping helper and models primitive GEM/MMU services. The MMU test
uses the production page-table implementation. ASan/UBSan passes for both
fixtures and the MMU test. An isolated ARM64 module build passes against the
prepared Linux 7.2.0 tree with GCC 15.2.

One focused RTL case passes against the unchanged production
`CheckedStorageWordAccess`: exact byte endpoints, the last valid and first
invalid words, full maximum views, 33-bit address arithmetic, held masked stores
and healthy reuse after rejected accesses. It uses one small component model;
this change does not establish Linux guest execution, Gallium API coverage,
whole-GPU performance or H7 completion. The trusted tessellation export fixture
keeps its explicitly installed software/RTL addresses independent of the
driver's private window planner. Uniform and texture corrupted-tail-PTE tests
select the actual leaf region even when the tail crosses a 4 MiB boundary,
instead of indexing past the first leaf table.

Reproduce the CPU checks without allocating or rendering a large frame:

```sh
python3 scripts/test_driver.py
CC='cc -fsanitize=address,undefined -fno-omit-frame-pointer' \
  python3 scripts/test_storage_binding.py
CC='cc -fsanitize=address,undefined -fno-omit-frame-pointer' \
  python3 scripts/test_compute_texture_ioctl_flow.py
cc -std=c11 -O2 -Wall -Wextra -Werror \
  -fsanitize=address,undefined -fno-omit-frame-pointer \
  -Idriver/tests/mmu_stubs driver/tests/opengpu_mmu_test.c -o /tmp/opengpu-mmu
/tmp/opengpu-mmu
```

The focused RTL test is selected with:

```sh
sbt -J-Xmx3G -J-XX:ActiveProcessorCount=2 -Dopengpu.simJobs=1 \
  -Dopengpu.simLowMem=1 -Dopengpu.simLowMemOpt=0 \
  -Dopengpu.simSeparateBuild=1 -Dopengpu.simReleaseHeap=1 \
  -Dopengpu.simCcache=off -Dopengpu.productQualification=0 \
  'testOnly opengpu.core.memory.CheckedStorageWordAccessSpec -- -z "enforce exact byte bounds within complete transport lines"'
```

Use an already prepared ARM64 kernel tree for an isolated module build; do not
reconfigure a shared tree:

```sh
make -C "$PREPARED_KERNEL" M="$ISOLATED_DRIVER_STAGE" ARCH=arm64 \
  CROSS_COMPILE=aarch64-linux-gnu- -j1 gpu_drv.ko
```

`ISOLATED_DRIVER_STAGE` contains the driver's Makefile and C/header sources.
Raw logs, source pins and emission/build records stay local under `.artifacts/`.
