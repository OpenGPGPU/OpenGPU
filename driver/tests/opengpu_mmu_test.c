// SPDX-License-Identifier: GPL-2.0
/* Exercise the production MMU implementation with allocation/idle failures.
 * cc -std=c11 -Wall -Wextra -Werror -Itests/mmu_stubs \
 *    tests/opengpu_mmu_test.c -o /tmp/opengpu_mmu_test
 */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <linux/mutex.h>

/* Replace kernel/device dependencies, not the MMU algorithm under test. */
#define OPENGPU_DEVICE_H
typedef uint32_t u32;
typedef uint64_t u64;
typedef uint64_t dma_addr_t;
#include "../opengpu_asid.h"
#include "../opengpu_kernarg_va.h"
#include "../opengpu_native_binned_host_abi.h"
#ifndef GPU_CAP_UNIFIED_COMMANDS
#define GPU_CAP_UNIFIED_COMMANDS 1u
#endif
#define OPENGPU_MMU_MAX_TABLES 16u
#define OPENGPU_VM_MAX_TABLES 384u
#define OPENGPU_MMU_POLICY_CACHED 0u
#define OPENGPU_MMU_POLICY_UNCACHED 2u
struct opengpu_buffer { void *cpu; dma_addr_t dma; size_t size; };
struct opengpu_vm;
struct opengpu_vm_reservation {
    struct opengpu_vm_reservation *next;
    struct opengpu_vm *owner;
    dma_addr_t va;
    size_t size;
    bool active;
};
struct opengpu_snapshot_lease {
    struct opengpu_snapshot_lease *next;
    const struct opengpu_vm *owner;
    dma_addr_t base, va;
    size_t size;
    bool active, mapped, executable;
};
struct opengpu_mmu {
    struct mutex lock;
    struct opengpu_buffer root;
    struct opengpu_buffer l1[OPENGPU_MMU_MAX_TABLES];
    u32 l1_region[OPENGPU_MMU_MAX_TABLES];
    u32 l1_count;
    struct opengpu_asid_pool asids;
    struct opengpu_vm *vms;
    struct opengpu_snapshot_lease *snapshot_leases;
    bool enabled;
};
struct opengpu_vm {
    struct opengpu_vm *next;
    struct opengpu_vm_reservation *reservations;
    struct opengpu_buffer root;
    u32 asid;
    bool enabled;
    struct opengpu_buffer l1[OPENGPU_VM_MAX_TABLES];
    u32 l1_region[OPENGPU_VM_MAX_TABLES];
    u32 l1_count;
};
struct opengpu_device {
    struct {
        struct mutex submit_lock;
        u32 capabilities;
        bool wedged;
        /* Extra hardware primitives used by the native admission composition.
         * No scheduler/MMIO behavior is modeled by the MMU regression. */
        struct { bool available; u32 capacity,engines; struct opengpu_native_binned_host_caps frame; } native_sources;
        bool native_advanced_blend_transport;
        bool native_descriptor_preflight;
        bool native_layered_transport;
        u32 native_context, native_pass, native_generation;
        void *native_pending_owner;
        unsigned fence_lock;
#ifdef OPENGPU_MMU_TEST_HW_EXTRA
        OPENGPU_MMU_TEST_HW_EXTRA
#endif
    } hw;
    struct opengpu_mmu mmu;
    struct {struct mutex admission_lock;long long ordinary_pending;
        u64 admission_context,admission_seqno;struct dma_fence *admission_tail,*execution_tail;} compute;
    void *dev;
};
static int allocations_until_failure = -1, live_allocations, idle_error;
static unsigned flushes, barriers;
static int global_flush_failures;
static dma_addr_t next_dma = 0x100000;
static void (*mmu_allocation_complete)(struct opengpu_device *);
/* Actual primitive allocations, also usable by a captured-owner host fixture.
 * No device address is invented independently of buffer_alloc's result. */
struct fixture_buffer_record {
    struct fixture_buffer_record *next;
    void *cpu;
    dma_addr_t dma;
    size_t size;
};
static struct fixture_buffer_record *fixture_buffers;

static int opengpu_buffer_alloc(struct opengpu_device *gpu,
                               struct opengpu_buffer *b, size_t size)
{
    (void)gpu;
    if (!allocations_until_failure)
        return -ENOMEM;
    if (allocations_until_failure > 0)
        allocations_until_failure--;
    b->cpu = malloc(size);
    assert(b->cpu);
    memset(b->cpu, 0xa5, size);
    b->size = size;
    b->dma = next_dma;
    next_dma += size;
    {
        struct fixture_buffer_record *record=malloc(sizeof(*record));assert(record);
        record->cpu=b->cpu;record->dma=b->dma;record->size=b->size;
        record->next=fixture_buffers;fixture_buffers=record;
    }
    live_allocations++;
    if(mmu_allocation_complete){void (*done)(struct opengpu_device *)=mmu_allocation_complete;mmu_allocation_complete=NULL;done(gpu);}
    return 0;
}
static void opengpu_buffer_free(struct opengpu_device *gpu,
                               struct opengpu_buffer *b)
{
    (void)gpu;
    if (b->cpu) {
        struct fixture_buffer_record **link=&fixture_buffers,*record;
        while(*link && (*link)->cpu!=b->cpu)link=&(*link)->next;
        assert(*link);record=*link;*link=record->next;free(record);
        free(b->cpu);
        b->cpu = NULL;
        live_allocations--;
    }
}
static int opengpu_hw_wait_idle_locked(struct opengpu_device *gpu)
{
    assert(gpu->hw.submit_lock.held);
#ifdef OPENGPU_MMU_TEST_WAIT_IDLE
    return OPENGPU_MMU_TEST_WAIT_IDLE(gpu);
#else
    return idle_error;
#endif
}
static int opengpu_hw_flush_tlbs(struct opengpu_device *gpu)
{
    assert(gpu->hw.submit_lock.held && gpu->mmu.lock.held);
    assert(barriers >= 2);
    flushes++;
    if (global_flush_failures) { --global_flush_failures; return -EIO; }
#ifdef OPENGPU_MMU_TEST_FLUSH_TLBS
    return OPENGPU_MMU_TEST_FLUSH_TLBS(gpu);
#else
    return 0;
#endif
}
static dma_addr_t detached_root;static u32 detached_asid;static unsigned disables;
static int opengpu_hw_set_satp(struct opengpu_device *gpu, dma_addr_t root, u32 asid)
{
    assert(gpu->hw.submit_lock.held);
#ifdef OPENGPU_MMU_TEST_SET_SATP
    return OPENGPU_MMU_TEST_SET_SATP(gpu,root,asid);
#else
    detached_root=root;detached_asid=asid;return 0;
#endif
}
static int opengpu_hw_disable_mmu_locked(struct opengpu_device *gpu)
{
    assert(gpu->hw.submit_lock.held && gpu->mmu.lock.held);
#ifdef OPENGPU_MMU_TEST_DISABLE_MMU
    return OPENGPU_MMU_TEST_DISABLE_MMU(gpu);
#else
    ++disables;return opengpu_hw_flush_tlbs(gpu);
#endif
}
static unsigned asid_flushes, vm_activations;
static int asid_flush_failures;
static dma_addr_t last_satp_root;
static u32 last_satp_asid;
static int opengpu_hw_flush_tlb_asid(struct opengpu_device *gpu, u32 asid)
{
    assert(gpu->hw.submit_lock.held && gpu->mmu.lock.held);
    assert(barriers >= 2);
    (void)asid;
    asid_flushes++;
    if (asid_flush_failures) { --asid_flush_failures; return -EIO; }
#ifdef OPENGPU_MMU_TEST_FLUSH_ASID
    return OPENGPU_MMU_TEST_FLUSH_ASID(gpu,asid);
#else
    return 0;
#endif
}
static int opengpu_hw_activate_vm(struct opengpu_device *gpu,
                                  const struct opengpu_vm *vm)
{
    /* The production helper acquires submit_lock itself. */
    assert(!gpu->hw.submit_lock.held);
    last_satp_root = vm ? vm->root.dma : 0;
    last_satp_asid = vm ? vm->asid : 0;
    vm_activations++;
#ifdef OPENGPU_MMU_TEST_ACTIVATE_VM
    return OPENGPU_MMU_TEST_ACTIVATE_VM(gpu,vm);
#else
    return 0;
#endif
}
static int opengpu_hw_enable_mmu(struct opengpu_device *gpu, dma_addr_t root)
{
    (void)gpu;
    assert(root && barriers);
#ifdef OPENGPU_MMU_TEST_ENABLE_MMU
    return OPENGPU_MMU_TEST_ENABLE_MMU(gpu,root);
#else
    return 0;
#endif
}
#define WRITE_ONCE(x, v) ((x) = (v))
#define GFP_KERNEL 0
static bool snapshot_alloc_fail;
#define kvcalloc(n, s, flags) (snapshot_alloc_fail ? NULL : calloc((n), (s)))
#define kvfree(p) free(p)
static void fixture_dma_wmb(void)
{
    ++barriers;
#ifdef OPENGPU_MMU_TEST_DMA_WMB
    OPENGPU_MMU_TEST_DMA_WMB();
#endif
}
#define dma_wmb() fixture_dma_wmb()
#include "../opengpu_mmu.c"

/* Caller serializes MMU mutation. Report complete real allocations, including
 * all bytes of a larger table buffer; never synthesize or truncate PTE pages.
 * This also works from a dma_wmb hook while the production MMU lock is held. */
typedef int (*fixture_pte_page_visitor)(void *, dma_addr_t, const void *, size_t);
static int fixture_visit_pte_buffer(const struct opengpu_buffer *buffer,
                                   fixture_pte_page_visitor visit, void *opaque)
{
    if (!buffer->cpu)
        return buffer->size ? -EINVAL : 0;
    if (buffer->size < MMU_PAGE_SIZE ||
        (buffer->size & (MMU_PAGE_SIZE - 1)) ||
        (buffer->dma & (MMU_PAGE_SIZE - 1)) ||
        (u64)buffer->size > (1ULL << 32) ||
        (u64)buffer->dma > (1ULL << 32) - (u64)buffer->size)
        return -EINVAL;
    return visit(opaque, buffer->dma, buffer->cpu, buffer->size);
}
static __attribute__((unused)) int fixture_mmu_visit_pte_pages(
    const struct opengpu_device *gpu, fixture_pte_page_visitor visit, void *opaque)
{
    const struct opengpu_vm *vm;
    u32 i;
    int ret;

    if (!gpu || !visit || gpu->mmu.l1_count > OPENGPU_MMU_MAX_TABLES)
        return -EINVAL;
    ret = fixture_visit_pte_buffer(&gpu->mmu.root, visit, opaque);
    if (ret)
        return ret;
    for (i = 0; i < gpu->mmu.l1_count; ++i) {
        ret = fixture_visit_pte_buffer(&gpu->mmu.l1[i], visit, opaque);
        if (ret)
            return ret;
    }
    for (vm = gpu->mmu.vms; vm; vm = vm->next) {
        if (vm->l1_count > OPENGPU_VM_MAX_TABLES)
            return -EINVAL;
        ret = fixture_visit_pte_buffer(&vm->root, visit, opaque);
        if (ret)
            return ret;
        for (i = 0; i < vm->l1_count; ++i) {
            ret = fixture_visit_pte_buffer(&vm->l1[i], visit, opaque);
            if (ret)
                return ret;
        }
    }
    return 0;
}

struct fixture_pte_visit_check {
    const struct opengpu_buffer **expected;
    size_t count, seen, bytes;
    int fail_at;
};
static int fixture_check_pte_page(void *opaque, dma_addr_t physical,
                                const void *bytes, size_t size)
{
    struct fixture_pte_visit_check *check = opaque;
    const struct opengpu_buffer *expected;
    assert(check->seen < check->count);
    expected = check->expected[check->seen];
    assert(physical == expected->dma && bytes == expected->cpu);
    assert(size == expected->size);
    /* Independent endpoint guards catch a cropped multi-page allocation. */
    assert(((const u32 *)bytes)[0] == 0x10203040u + check->seen);
    assert(((const u32 *)bytes)[size / sizeof(u32) - 1] ==
           0x50607080u + check->seen);
    check->bytes += size;
    return (int)check->seen++ == check->fail_at ? -EIO : 0;
}
static void fixture_test_pte_visitor(void)
{
    struct opengpu_device gpu = {0};
    struct opengpu_vm first = {0}, second = {0};
    const struct opengpu_buffer *buffers[] = {
        &gpu.mmu.root, &gpu.mmu.l1[0], &first.root, &first.l1[0],
        &second.root, &second.l1[0]
    };
    struct fixture_pte_visit_check check = {buffers, 6, 0, 0, -1};
    unsigned i;

    gpu.mmu.l1_count = first.l1_count = second.l1_count = 1;
    gpu.mmu.vms = &first;
    first.next = &second;
    for (i = 0; i < 6; ++i) {
        struct opengpu_buffer *buffer = (struct opengpu_buffer *)buffers[i];
        assert(!opengpu_buffer_alloc(&gpu, buffer,
                                    i ? MMU_PAGE_SIZE : 32768));
        ((u32 *)buffer->cpu)[0] = 0x10203040u + i;
        ((u32 *)buffer->cpu)[buffer->size / sizeof(u32) - 1] =
            0x50607080u + i;
    }
    assert(!fixture_mmu_visit_pte_pages(&gpu, fixture_check_pte_page, &check));
    assert(check.seen == 6 && check.bytes == 32768 + 5 * MMU_PAGE_SIZE);
    check.seen = check.bytes = 0;
    check.fail_at = 3;
    assert(fixture_mmu_visit_pte_pages(&gpu, fixture_check_pte_page, &check) == -EIO);
    assert(check.seen == 4);
    check.seen = check.bytes = 0;
    check.fail_at = -1;
    second.l1_count = OPENGPU_VM_MAX_TABLES + 1;
    assert(fixture_mmu_visit_pte_pages(&gpu, fixture_check_pte_page, &check) == -EINVAL);
    second.l1_count = 1;
    assert(fixture_mmu_visit_pte_pages(&gpu, NULL, &check) == -EINVAL);
    for (i = 0; i < 6; ++i)
        opengpu_buffer_free(&gpu, (struct opengpu_buffer *)buffers[i]);
}

static int verify_snapshot(struct opengpu_device *gpu, const struct opengpu_vm *vm,
                           dma_addr_t va, dma_addr_t pa, size_t size)
{
    int ret;
    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_mmu_verify_snapshot_locked(gpu, vm, va, pa, size);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

static int verify_code(struct opengpu_device *gpu, const struct opengpu_vm *vm,
                       dma_addr_t va, dma_addr_t pa, size_t size)
{
    int ret;
    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_mmu_verify_code_snapshot_locked(gpu, vm, va, pa, size);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

static void finish_while_vm_allocates(struct opengpu_device *gpu)
{assert(!opengpu_mmu_fini(gpu));}
int main(void)
{
    struct opengpu_device gpu = { .hw.capabilities = GPU_CAP_UNIFIED_COMMANDS };
    u32 root_before[1024], leaf_before[1024];
    u32 i;

    assert(!opengpu_mmu_init(&gpu));
    assert((((u32 *)gpu.mmu.root.cpu)[0] & MMU_PTE_V) != 0);
    assert((((u32 *)gpu.mmu.root.cpu)[0] & 0x20) == 0); /* ASID-tagged identity */
    /* Identity leaves are read/write but never executable. */
    assert((((u32 *)gpu.mmu.root.cpu)[0] & MMU_PTE_X) == 0);
    memcpy(root_before, gpu.mmu.root.cpu, sizeof(root_before));
    idle_error = -ETIMEDOUT;
    assert(opengpu_mmu_set_range_policy(&gpu, 0, 4096, 2) == -ETIMEDOUT);
    assert(!gpu.hw.submit_lock.held && !gpu.mmu.l1_count && !flushes);
    idle_error = 0;

    /* Fail after allocating the first of two regions: no live link changed. */
    allocations_until_failure = 1;
    assert(opengpu_mmu_set_range_policy(&gpu, 0x3ff000, 8192, 2) == -ENOMEM);
    assert(!memcmp(root_before, gpu.mmu.root.cpu, sizeof(root_before)));
    assert(!gpu.mmu.l1_count && live_allocations == 1 && !flushes);
    allocations_until_failure = -1;
    assert(!opengpu_mmu_set_range_policy(&gpu, 0x3ff000, 8192, 2));
    assert(gpu.mmu.l1_count == 2 && flushes == 1);
    assert((((u32 *)gpu.mmu.l1[0].cpu)[1023] & 0x320) == 0x200);
    assert((((u32 *)gpu.mmu.l1[1].cpu)[0] & 0x320) == 0x200);
    assert((((u32 *)gpu.mmu.l1[0].cpu)[1022] & 0x300) == 0);

    /* Existing PTEs must also survive allocation failure in a later region. */
    memcpy(leaf_before, gpu.mmu.l1[1].cpu, sizeof(leaf_before));
    allocations_until_failure = 0;
    assert(opengpu_mmu_set_range_policy(&gpu, 0x400000, 0x401000, 2) == -ENOMEM);
    assert(!memcmp(leaf_before, gpu.mmu.l1[1].cpu, sizeof(leaf_before)));
    assert(gpu.mmu.l1_count == 2 && flushes == 1);
    allocations_until_failure = -1;

    /* Capacity exhaustion rolls back all newly reserved tables. */
    memcpy(root_before, gpu.mmu.root.cpu, sizeof(root_before));
    assert(opengpu_mmu_set_range_policy(&gpu, 0x800000, 15 * 0x400000, 2) == -ENOSPC);
    assert(gpu.mmu.l1_count == 2 && live_allocations == 3);
    assert(!memcmp(root_before, gpu.mmu.root.cpu, sizeof(root_before)));
    assert(flushes == 1);

    assert(opengpu_mmu_set_range_policy(&gpu, UINT64_MAX - 1, 8, 2) == -ERANGE);
    assert(opengpu_mmu_set_range_policy(&gpu, 0xffffffff, 2, 2) == -ERANGE);
    assert(!opengpu_mmu_set_range_policy(&gpu, 0xffffffff, 1, 2));
    i = gpu.mmu.l1_count - 1;
    assert(gpu.mmu.l1_region[i] == 1023);
    assert((((u32 *)gpu.mmu.l1[i].cpu)[1023] >> 10) == 0xfffff);

    /* A failed global shootdown restores preexisting leaves and removes
     * newly published root links; a failed rollback wedges the device. */
    memcpy(root_before, gpu.mmu.root.cpu, sizeof(root_before));
    memcpy(leaf_before, gpu.mmu.l1[1].cpu, sizeof(leaf_before));
    {
        int live_before = live_allocations;
        u32 count_before = gpu.mmu.l1_count;
        unsigned flush_before = flushes;

        snapshot_alloc_fail=true;
        assert(opengpu_mmu_set_range_policy(&gpu,0x7ff000,8192,2)==-ENOMEM);
        snapshot_alloc_fail=false;
        assert(flushes==flush_before && gpu.mmu.l1_count==count_before);
        global_flush_failures=1;
        assert(opengpu_mmu_set_range_policy(&gpu,0x7ff000,8192,2)==-EIO);
        assert(flushes==flush_before+2 && gpu.mmu.l1_count==count_before);
        assert(live_allocations==live_before && !gpu.hw.wedged);
        assert(!memcmp(root_before,gpu.mmu.root.cpu,sizeof(root_before)));
        assert(!memcmp(leaf_before,gpu.mmu.l1[1].cpu,sizeof(leaf_before)));
        global_flush_failures=2;
        assert(opengpu_mmu_set_range_policy(&gpu,0x7ff000,8192,2)==-EIO);
        assert(flushes==flush_before+4 && gpu.hw.wedged);
        assert(live_allocations==live_before && gpu.mmu.l1_count==count_before);
        assert(!memcmp(root_before,gpu.mmu.root.cpu,sizeof(root_before)));
        assert(!memcmp(leaf_before,gpu.mmu.l1[1].cpu,sizeof(leaf_before)));
        gpu.hw.wedged=false;
    }

    /* Per-VM roots start empty; activation reprograms satp. */
    {
        struct opengpu_vm vm, vm2;
        int base_live = live_allocations;
        unsigned base_asid = asid_flushes;

        assert(!opengpu_mmu_vm_create(&gpu, &vm));
        assert(vm.enabled && vm.asid == 1);
        assert(live_allocations == base_live + 1);
        /* Creation does not shoot down a freshly allocated ASID. */
        assert(asid_flushes == base_asid);
        for (i = 0; i < MMU_ROOT_ENTRIES; i++)
            assert(((u32 *)vm.root.cpu)[i] == 0);

        assert(!opengpu_mmu_vm_activate(&gpu, &vm));
        assert(vm_activations == 1);
        assert(last_satp_root == vm.root.dma && last_satp_asid == vm.asid);

        /* A second VM takes the next ASID; destroy evicts each ASID once. */
        assert(!opengpu_mmu_vm_create(&gpu, &vm2));
        assert(vm2.enabled && vm2.asid == 2);
        assert(asid_flushes == base_asid);
        opengpu_mmu_vm_destroy(&gpu, &vm2);
        assert(asid_flushes == base_asid + 1);

        opengpu_mmu_vm_destroy(&gpu, &vm);
        assert(!vm.enabled);
        assert(asid_flushes == base_asid + 2);
        assert(live_allocations == base_live);
    }

    /* ASID-0 policy updates never grant access to a live context. */
    {
        struct opengpu_vm vm;
        u32 region = 500;
        int live_before = live_allocations;
        unsigned full_before = flushes;

        assert(!opengpu_mmu_vm_create(&gpu, &vm));
        assert(((u32 *)vm.root.cpu)[1023] == 0);
        assert(!opengpu_mmu_set_range_policy(
            &gpu, (dma_addr_t)region * MMU_SUPERPAGE_SIZE, MMU_PAGE_SIZE, 2));
        assert(((u32 *)vm.root.cpu)[region] == 0);
        assert(((u32 *)gpu.mmu.root.cpu)[region] & MMU_PTE_V);
        assert(flushes == full_before + 1);
        opengpu_mmu_vm_destroy(&gpu, &vm);
        /* Only the new L1 table stays allocated after the VM is gone. */
        assert(live_allocations == live_before + 1);
    }

    /* Thirty-two independent texture windows plus compute resources fit in
     * one VM; the separate private limit fails without publishing entry 65. */
    {
        struct opengpu_vm vm;
        int live_before = live_allocations;
        assert(!opengpu_mmu_vm_create(&gpu, &vm));
        for (u32 i = 0; i < 32; ++i)
            assert(!opengpu_mmu_vm_map(&gpu, &vm, 0xa0000000ULL + (i + 3ULL) * MMU_SUPERPAGE_SIZE,
                                       0x10000000ULL + i * MMU_PAGE_SIZE, MMU_PAGE_SIZE, 2));
        assert(!opengpu_mmu_vm_map(&gpu, &vm, 0x80400000ULL, 0x11000000ULL, MMU_PAGE_SIZE, 2));
        assert(!opengpu_mmu_vm_map(&gpu, &vm, 0xc0400000ULL, 0x11001000ULL, MMU_PAGE_SIZE, 0));
        assert(!opengpu_mmu_vm_map(&gpu, &vm, 0xb0800000ULL, 0x11002000ULL, MMU_PAGE_SIZE, 2));
        assert(vm.l1_count == 35);
        for (u32 i = 0; i < OPENGPU_VM_MAX_TABLES - 35; ++i)
            assert(!opengpu_mmu_vm_map(&gpu, &vm, 0x00400000ULL + i * MMU_SUPERPAGE_SIZE,
                                       0x12000000ULL + i * MMU_PAGE_SIZE, MMU_PAGE_SIZE, 2));
        assert(vm.l1_count == OPENGPU_VM_MAX_TABLES);
        assert(opengpu_mmu_vm_map(&gpu, &vm, 0x70000000ULL, 0x13000000ULL,
                                  MMU_PAGE_SIZE, 2) == -ENOSPC);
        assert(vm.l1_count == OPENGPU_VM_MAX_TABLES && ((u32 *)vm.root.cpu)[448] == 0);
        opengpu_mmu_vm_destroy(&gpu, &vm);
        assert(live_allocations == live_before);
    }

    /* Four maximum, non-page-aligned storage views retain their entire
     * capacity alongside texture, uniform and command peer mappings. This
     * exercises real PTE allocation rather than only VA arithmetic. */
    {
        struct opengpu_vm vm;
        struct opengpu_kernarg_va_plan plans[4];
        int live_before = live_allocations;
        const u64 maximum = 128ull * 1024 * 1024;
        assert(!opengpu_mmu_vm_create(&gpu, &vm));
        for (u32 i = 0; i < 4; ++i) {
            u64 dma = 0x10000f00ull + i * 0x20000000ull;
            assert(!opengpu_storage_va_plan(dma, maximum, i, MMU_PAGE_SIZE, &plans[i]));
            assert(!opengpu_mmu_vm_map(&gpu, &vm, plans[i].va_page,
                plans[i].pa_page, plans[i].span, OPENGPU_MMU_POLICY_UNCACHED));
        }
        for (u32 i = 0; i < 32; ++i)
            assert(!opengpu_mmu_vm_map(&gpu, &vm, OPENGPU_TEXTURE_VA_BASE + (i+3ull)*MMU_SUPERPAGE_SIZE,
                0x90000000ull+i*MMU_PAGE_SIZE, MMU_PAGE_SIZE, 2));
        for (u32 i = 0; i < 12; ++i)
            assert(!opengpu_mmu_vm_map(&gpu, &vm, OPENGPU_KERNARG_VA_BASE + (i+15ull)*MMU_SUPERPAGE_SIZE,
                0x91000000ull+i*MMU_PAGE_SIZE, MMU_PAGE_SIZE, 2));
        for (u32 i = 0; i < 9; ++i)
            assert(!opengpu_mmu_vm_map(&gpu, &vm, OPENGPU_COMMAND_VA_BASE+i*MMU_SUPERPAGE_SIZE,
                0x92000000ull+i*MMU_PAGE_SIZE, MMU_PAGE_SIZE, 2));
        assert(!opengpu_mmu_vm_map(&gpu,&vm,0xc0400000ull,0x93000000ull,MMU_PAGE_SIZE,0));
        assert(!opengpu_mmu_vm_map(&gpu,&vm,0x80800000ull,0x93001000ull,MMU_PAGE_SIZE,2));
        assert(vm.l1_count == 184 && vm.l1_count < OPENGPU_VM_MAX_TABLES);
        for (u32 i = 0; i < 4; ++i) for (u32 tail = 0; tail < 2; ++tail) {
            u64 offset = tail ? maximum-4 : 0;
            u64 va = plans[i].va+offset;
            const struct opengpu_buffer *l1 = vm_live_l1(&vm, va/MMU_SUPERPAGE_SIZE);
            assert(l1);
            u32 pte = ((u32 *)l1->cpu)[(va/MMU_PAGE_SIZE)%MMU_L1_ENTRIES];
            assert(pte & MMU_PTE_V);
            assert(((u64)(pte>>10)<<12)+(va & (MMU_PAGE_SIZE-1)) ==
                   0x10000f00ull+i*0x20000000ull+offset);
        }
        assert(plans[3].va_page+plans[3].span == OPENGPU_TESS_STATES_VA_BASE);
        struct opengpu_kernarg_va_plan images[4];
        for (u32 i = 0; i < 4; ++i) {
            assert(!opengpu_image_backing_va_plan(0x50000f00ull+i*maximum,
                maximum, i, MMU_PAGE_SIZE, &images[i]));
            int before = live_allocations;
            u32 count = vm.l1_count;
            u32 roots[MMU_L1_ENTRIES];
            void *leaves = malloc((size_t)count*MMU_PAGE_SIZE);
            assert(leaves);
            memcpy(roots,vm.root.cpu,sizeof(roots));
            for (u32 n=0;n<count;n++)
                memcpy((unsigned char *)leaves+n*MMU_PAGE_SIZE,vm.l1[n].cpu,MMU_PAGE_SIZE);
            /* A mid-allocation failure leaves old roots/leaves unchanged. */
            allocations_until_failure = 2;
            assert(opengpu_mmu_vm_map(&gpu, &vm, images[i].va_page,
                images[i].pa_page, images[i].span, 2) == -ENOMEM);
            allocations_until_failure = -1;
            assert(vm.l1_count == count && live_allocations == before);
            assert(!memcmp(roots,vm.root.cpu,sizeof(roots)));
            for (u32 n=0;n<count;n++)
                assert(!memcmp((unsigned char *)leaves+n*MMU_PAGE_SIZE,vm.l1[n].cpu,MMU_PAGE_SIZE));
            free(leaves);
            for (u64 va = images[i].va_page; va < images[i].va_page+images[i].span; va += MMU_PAGE_SIZE) {
                const struct opengpu_buffer *l1 = vm_live_l1(&vm, va/MMU_SUPERPAGE_SIZE);
                assert(!l1 || !((u32 *)l1->cpu)[(va/MMU_PAGE_SIZE)%MMU_L1_ENTRIES]);
            }
            assert(!opengpu_mmu_vm_map(&gpu, &vm, images[i].va_page,
                images[i].pa_page, images[i].span, 2));
        }
        assert(vm.l1_count == 313 && vm.l1_count < OPENGPU_VM_MAX_TABLES);
        /* Every leaf, including full first/last physical pages, comes from
         * its actual complete backing rather than a clamped texel count. */
        for (u32 i = 0; i < 8; ++i) {
            struct opengpu_kernarg_va_plan *p = i < 4 ? &plans[i] : &images[i-4];
            for (u64 off = 0; off < p->span; off += MMU_PAGE_SIZE) {
                u64 va = p->va_page+off;
                const struct opengpu_buffer *l1 = vm_live_l1(&vm, va/MMU_SUPERPAGE_SIZE);
                assert(l1);
                u32 pte = ((u32 *)l1->cpu)[(va/MMU_PAGE_SIZE)%MMU_L1_ENTRIES];
                assert((pte & MMU_PTE_V) && ((u64)(pte>>10)<<12) == p->pa_page+off);
            }
        }
        for (u32 i = 0; i < 4; ++i) {
            assert(!opengpu_mmu_vm_unmap(&gpu, &vm, images[i].va, maximum));
            assert(!opengpu_mmu_vm_map(&gpu, &vm, images[i].va_page,
                images[i].pa_page+0x1000, images[i].span, 2));
            const struct opengpu_buffer *l1 = vm_live_l1(&vm, images[i].va_page/MMU_SUPERPAGE_SIZE);
            assert(((u64)(((u32 *)l1->cpu)[(images[i].va_page/MMU_PAGE_SIZE)%MMU_L1_ENTRIES]>>10)<<12) ==
                images[i].pa_page+0x1000);
        }
        opengpu_mmu_vm_destroy(&gpu,&vm);
        assert(live_allocations == live_before);
    }

    /* Private mappings expose only their explicitly bound pages. */
    {
        struct opengpu_vm vm;
        dma_addr_t va = 0x20000000;            /* region 128, page 0 */
        dma_addr_t pa = 0x12345000;
        u32 region = (u32)(va / MMU_SUPERPAGE_SIZE);
        u32 *table;
        int live_before = live_allocations;
        unsigned full_before, asid_before;

        assert(!opengpu_mmu_vm_create(&gpu, &vm));
        full_before = flushes;
        asid_before = asid_flushes;
        assert(!opengpu_mmu_vm_map(&gpu, &vm, va, pa, MMU_PAGE_SIZE, 2));
        assert(flushes == full_before);
        assert(asid_flushes == asid_before + 1);
        assert(vm.l1_count == 1);
        assert((((u32 *)vm.root.cpu)[region] & 1) != 0);
        assert((((u32 *)vm.root.cpu)[region] >> 10) ==
               (u32)(vm.l1[0].dma >> 12));
        table = vm.l1[0].cpu;
        /* The mapped page points at the requested PA, without the G bit, with
         * the requested policy, and executable (private code/data). */
        assert((table[0] >> 10) == 0x12345);
        assert((table[0] & 0x20) == 0);
        assert((table[0] & 0x300) == 0x200);
        assert((table[0] & MMU_PTE_X) != 0);
        /* An untouched sibling has no valid translation. */
        assert(table[1] == 0);
        /* Validation: alignment, size, policy and the 32-bit window. */
        assert(opengpu_mmu_vm_map(&gpu, &vm, va + 1, pa, MMU_PAGE_SIZE, 0) ==
               -EINVAL);
        assert(opengpu_mmu_vm_map(&gpu, &vm, va, pa, MMU_PAGE_SIZE + 1, 0) ==
               -EINVAL);
        assert(opengpu_mmu_vm_map(&gpu, &vm, va, pa, 0, 0) == -EINVAL);
        assert(opengpu_mmu_vm_map(&gpu, &vm, va, pa, MMU_PAGE_SIZE, 3) ==
               -EINVAL);
        assert(opengpu_mmu_vm_map(&gpu, &vm, 0xfffff000, pa, 0x2000, 0) ==
               -ERANGE);
        /* An ASID-0 split of the same region cannot clobber the private table. */
        assert(!opengpu_mmu_set_range_policy(
            &gpu, (dma_addr_t)region * MMU_SUPERPAGE_SIZE, MMU_PAGE_SIZE, 0));
        assert((((u32 *)vm.root.cpu)[region] >> 10) ==
               (u32)(vm.l1[0].dma >> 12));
        /* Unbind revokes the containing page, leaving siblings invalid. */
        asid_before = asid_flushes;
        assert(!opengpu_mmu_vm_unmap(&gpu, &vm, va + 123, 100));
        assert(table[0] == 0);
        assert(table[1] == 0);
        assert(asid_flushes == asid_before + 1);
        assert(opengpu_mmu_vm_unmap(
                   &gpu, &vm, 0x30000000, MMU_PAGE_SIZE) == -ENOENT);
        /* A retired job can revoke a window after the table's CPU mapping
         * has already been dropped. That must fail closed, not write NULL. */
        {
            void *cpu = vm.l1[0].cpu;

            vm.l1[0].cpu = NULL;
            assert(opengpu_mmu_vm_unmap(&gpu, &vm, va, MMU_PAGE_SIZE) ==
                   -ENOENT);
            vm.l1[0].cpu = cpu;
        }
        assert(asid_flushes == asid_before + 1);
        assert(opengpu_mmu_vm_unmap(&gpu, &vm, va, 0) == -EINVAL);
        assert(opengpu_mmu_vm_unmap(
                   &gpu, &vm, 0xfffff000, 0x2000) == -ERANGE);
        opengpu_mmu_vm_destroy(&gpu, &vm);
        /* Only the global split table stays allocated. */
        assert(live_allocations == live_before + 1);
    }

    /* Two contexts may use the same private VA without sharing translations.
     * Revoking one context's leaf must not change the other context's mapping. */
    {
        struct opengpu_vm first, second, recycled;
        dma_addr_t va = 0x24000000;
        int live_before = live_allocations;
        unsigned asid_before = asid_flushes;
        u32 recycled_asid;
        u32 *first_table, *second_table;

        assert(!opengpu_mmu_vm_create(&gpu, &first));
        assert(!opengpu_mmu_vm_create(&gpu, &second));
        assert(first.asid != second.asid);
        assert(!opengpu_mmu_vm_map(
            &gpu, &first, va, 0x11111000, MMU_PAGE_SIZE, 0));
        assert(!opengpu_mmu_vm_map(
            &gpu, &second, va, 0x22222000, MMU_PAGE_SIZE, 0));
        first_table = first.l1[0].cpu;
        second_table = second.l1[0].cpu;
        assert((first_table[0] >> 10) == 0x11111);
        assert((second_table[0] >> 10) == 0x22222);
        assert(first_table[1] == 0 && second_table[1] == 0);
        assert(((u32 *)first.root.cpu)[0] == 0);
        assert(((u32 *)second.root.cpu)[0] == 0);
        assert(!opengpu_mmu_vm_unmap(
            &gpu, &first, va, MMU_PAGE_SIZE));
        assert(first_table[0] == 0);
        assert((second_table[0] >> 10) == 0x22222);
        assert(asid_flushes == asid_before + 3);
        recycled_asid = first.asid;
        opengpu_mmu_vm_destroy(&gpu, &first);
        opengpu_mmu_vm_destroy(&gpu, &second);
        assert(asid_flushes == asid_before + 5);

        /* Force the allocator cursor to the released ID. Destroy already
         * flushed it, so the new owner starts from a fresh root and cannot
         * observe either prior translation. */
        gpu.mmu.asids.next = recycled_asid;
        assert(!opengpu_mmu_vm_create(&gpu, &recycled));
        assert(recycled.asid == recycled_asid);
        assert(((u32 *)recycled.root.cpu)[va / MMU_SUPERPAGE_SIZE] == 0);
        assert(!opengpu_mmu_vm_map(
            &gpu, &recycled, va, 0x33333000, MMU_PAGE_SIZE, 0));
        assert((((u32 *)recycled.l1[0].cpu)[0] >> 10) == 0x33333);
        opengpu_mmu_vm_destroy(&gpu, &recycled);
        assert(asid_flushes == asid_before + 7);
        assert(live_allocations == live_before);
    }
    {
        struct opengpu_vm buffer_vm;
        struct opengpu_kernarg_va_plan plan;
        const u64 bytes=65536*4+1, dma=0x1900003full;
        int live_before=live_allocations;
        assert(!opengpu_mmu_vm_create(&gpu,&buffer_vm));
        assert(!opengpu_texture_va_plan(dma,bytes,3,4096,&plan));
        assert(plan.span==65*4096);
        allocations_until_failure=0;
        assert(opengpu_mmu_vm_map(&gpu,&buffer_vm,plan.va_page,plan.pa_page,plan.span,2)==-ENOMEM);
        assert(!buffer_vm.l1_count && !((u32 *)buffer_vm.root.cpu)[plan.va_page/MMU_SUPERPAGE_SIZE]);
        allocations_until_failure=-1;
        assert(!opengpu_mmu_vm_map(&gpu,&buffer_vm,plan.va_page,plan.pa_page,plan.span,2));
        u32 *pte=vm_l1_find(&buffer_vm,plan.va_page/MMU_SUPERPAGE_SIZE)->cpu;
        for(u32 page=0;page<65;++page) {
            u32 leaf=pte[(plan.va_page%MMU_SUPERPAGE_SIZE)/MMU_PAGE_SIZE+page];
            assert((leaf>>10)==((plan.pa_page+page*MMU_PAGE_SIZE)>>12));
            assert((leaf&(MMU_PTE_V|MMU_PTE_R|MMU_PTE_W|MMU_PTE_A|MMU_PTE_D))==
                (MMU_PTE_V|MMU_PTE_R|MMU_PTE_W|MMU_PTE_A|MMU_PTE_D));
        }
        assert(!opengpu_mmu_vm_unmap(&gpu,&buffer_vm,plan.va,bytes));
        assert(!pte[64]);opengpu_mmu_vm_destroy(&gpu,&buffer_vm);assert(live_allocations==live_before);
    }
    {
        struct opengpu_vm vm;
        const u64 va=0xd0000000ULL, pa=0x10000000ULL;
        u32 *pte, before[3];
        int live_before=live_allocations;
        assert(!opengpu_mmu_vm_create(&gpu, &vm));
        assert(!opengpu_mmu_vm_map(&gpu,&vm,va,pa,3*MMU_PAGE_SIZE,2));
        pte=vm_l1_find(&vm,va/MMU_SUPERPAGE_SIZE)->cpu;
        assert(!opengpu_mmu_vm_replace(&gpu,&vm,va,pa+0x10000,MMU_PAGE_SIZE,2,
                                        va,3*MMU_PAGE_SIZE));
        assert((pte[0]>>10)==((pa+0x10000)>>12) && !pte[1] && !pte[2]);
        assert(!opengpu_mmu_vm_replace(&gpu,&vm,va,pa,3*MMU_PAGE_SIZE,2,
                                        va,MMU_PAGE_SIZE));
        assert(!opengpu_mmu_vm_replace(&gpu,&vm,va+MMU_PAGE_SIZE,pa+0x20000,
                                        2*MMU_PAGE_SIZE,2,va+12,3*MMU_PAGE_SIZE-12));
        assert(!pte[0] && (pte[1]>>10)==((pa+0x20000)>>12) &&
               (pte[2]>>10)==((pa+0x21000)>>12));
        assert(!opengpu_mmu_vm_replace(&gpu,&vm,va+MMU_PAGE_SIZE,pa+0x30000,
                                        MMU_PAGE_SIZE,2,va+MMU_PAGE_SIZE+32,64));
        assert((pte[1]>>10)==((pa+0x30000)>>12));
        memcpy(before,pte,sizeof(before));
        allocations_until_failure=0;
        assert(opengpu_mmu_vm_replace(&gpu,&vm,va+MMU_SUPERPAGE_SIZE,pa,
                   MMU_PAGE_SIZE,2,va+MMU_PAGE_SIZE,2*MMU_PAGE_SIZE)==-ENOMEM);
        allocations_until_failure=1;
        assert(opengpu_mmu_vm_replace(&gpu,&vm,va+MMU_SUPERPAGE_SIZE,pa,
                   MMU_SUPERPAGE_SIZE+MMU_PAGE_SIZE,2,
                   va+MMU_PAGE_SIZE,2*MMU_PAGE_SIZE)==-ENOMEM);
        allocations_until_failure=-1;
        assert(!memcmp(before,pte,sizeof(before)) && vm.l1_count==1);
        asid_flush_failures=1;
        assert(opengpu_mmu_vm_replace(&gpu,&vm,va+MMU_SUPERPAGE_SIZE,pa,
                   MMU_PAGE_SIZE,2,va+MMU_PAGE_SIZE,2*MMU_PAGE_SIZE)==-EIO);
        assert(!memcmp(before,pte,sizeof(before)) && vm.l1_count==1 && !gpu.hw.wedged);
        asid_flush_failures=1;
        assert(opengpu_mmu_vm_replace(&gpu,&vm,va,pa,2*MMU_PAGE_SIZE,2,
                   va+MMU_PAGE_SIZE,2*MMU_PAGE_SIZE)==-EIO);
        assert(!memcmp(before,pte,sizeof(before)) && !gpu.hw.wedged);
        snapshot_alloc_fail=true;
        assert(opengpu_mmu_vm_unmap(&gpu,&vm,va,MMU_PAGE_SIZE)==-ENOMEM);
        assert(!memcmp(before,pte,sizeof(before)) && !gpu.hw.wedged);
        assert(opengpu_mmu_vm_map(&gpu,&vm,va,pa,MMU_PAGE_SIZE,2)==-ENOMEM);
        assert(!memcmp(before,pte,sizeof(before)) && !gpu.hw.wedged);
        snapshot_alloc_fail=false;
        /* A failed new map rolls back both an existing leaf and any new
         * page tables; failure to flush the rollback wedges submissions. */
        asid_flush_failures=1;
        assert(opengpu_mmu_vm_map(&gpu,&vm,va+MMU_PAGE_SIZE,pa,
                                  MMU_PAGE_SIZE,2)==-EIO);
        assert(!memcmp(before,pte,sizeof(before)) && !gpu.hw.wedged);
        asid_flush_failures=1;
        assert(opengpu_mmu_vm_map(&gpu,&vm,va+MMU_SUPERPAGE_SIZE,pa,
                                  MMU_PAGE_SIZE,2)==-EIO);
        assert(vm.l1_count==1 && !((u32 *)vm.root.cpu)[(va/MMU_SUPERPAGE_SIZE)+1]);
        assert(!memcmp(before,pte,sizeof(before)) && !gpu.hw.wedged);
        asid_flush_failures=2;
        assert(opengpu_mmu_vm_map(&gpu,&vm,va,pa,MMU_PAGE_SIZE,2)==-EIO);
        assert(!memcmp(before,pte,sizeof(before)) && gpu.hw.wedged);
        gpu.hw.wedged=false;
        /* A failed unmap leaves the original binding intact; a failed
         * rollback shootdown wedges rather than expose a stale translation. */
        asid_flush_failures=1;
        assert(opengpu_mmu_vm_unmap(&gpu,&vm,va+MMU_PAGE_SIZE+12,64)==-EIO);
        assert(!memcmp(before,pte,sizeof(before)) && !gpu.hw.wedged);
        asid_flush_failures=2;
        assert(opengpu_mmu_vm_unmap(&gpu,&vm,va,3*MMU_PAGE_SIZE)==-EIO);
        assert(!memcmp(before,pte,sizeof(before)) && gpu.hw.wedged);
        gpu.hw.wedged=false;
        asid_flush_failures=2;
        assert(opengpu_mmu_vm_replace(&gpu,&vm,va,pa,MMU_PAGE_SIZE,2,
                   va+MMU_PAGE_SIZE,2*MMU_PAGE_SIZE)==-EIO);
        assert(!memcmp(before,pte,sizeof(before)) && gpu.hw.wedged);
        gpu.hw.wedged=false;
        opengpu_mmu_vm_destroy(&gpu,&vm);
        assert(live_allocations==live_before);
    }
    /* Snapshot mapping and verification cover page boundaries, exact backing,
     * permissions, writable aliases, rollback and the exclusive 4 GiB end. */
    {
        struct opengpu_vm vm;
        const u64 va = 0x203ff000ULL, pa = 0x10000000ULL, alias = 0x30000000ULL;
        u32 *first, *second, *root, before;
        int live_before = live_allocations;
        assert(!opengpu_mmu_vm_create(&gpu, &vm));
        assert(!opengpu_mmu_vm_map_snapshot(&gpu, &vm, va, pa, 2 * MMU_PAGE_SIZE, 2));
        first = vm_l1_find(&vm, va / MMU_SUPERPAGE_SIZE)->cpu;
        second = vm_l1_find(&vm, (va + MMU_PAGE_SIZE) / MMU_SUPERPAGE_SIZE)->cpu;
        root = vm.root.cpu;
        assert((first[1023] & 0xffu) == (MMU_PTE_V | MMU_PTE_R | MMU_PTE_A));
        assert((second[0] & 0xffu) == (MMU_PTE_V | MMU_PTE_R | MMU_PTE_A));
        assert(!verify_snapshot(&gpu, &vm, va + 64, pa + 64, MMU_PAGE_SIZE));
        assert(verify_snapshot(&gpu, &vm, va + 64, pa + 65, 100) == -EINVAL);
        assert(verify_snapshot(&gpu, &vm, va + 64, pa + MMU_PAGE_SIZE + 64, 100) == -EACCES);
        assert(verify_snapshot(&gpu, &vm, va, pa, 0) == -EINVAL);
        before = first[1023]; first[1023] |= MMU_PTE_W;
        assert(verify_snapshot(&gpu, &vm, va, pa, 64) == -EACCES);
        first[1023] = before | MMU_PTE_X;
        assert(verify_snapshot(&gpu, &vm, va, pa, 64) == -EACCES);
        first[1023] = before | MMU_PTE_G;
        assert(verify_snapshot(&gpu, &vm, va, pa, 64) == -EACCES);
        first[1023] = before & ~MMU_PTE_A;
        assert(verify_snapshot(&gpu, &vm, va, pa, 64) == -EACCES);
        first[1023] = before & ~MMU_PTE_R;
        assert(verify_snapshot(&gpu, &vm, va, pa, 64) == -EACCES);
        first[1023] = (before & ~(3u << MMU_PTE_POLICY_SHIFT)) | (3u << MMU_PTE_POLICY_SHIFT);
        assert(verify_snapshot(&gpu, &vm, va, pa, 64) == -EACCES);
        first[1023] = before;

        assert(!opengpu_mmu_vm_map(&gpu, &vm, alias, pa + MMU_PAGE_SIZE, MMU_PAGE_SIZE, 0));
        assert(verify_snapshot(&gpu, &vm, va + 64, pa + 64, MMU_PAGE_SIZE) == -EACCES);
        assert(!opengpu_mmu_vm_unmap(&gpu, &vm, alias, MMU_PAGE_SIZE));
        assert(!opengpu_mmu_vm_map(&gpu, &vm, alias, 0x50000000, MMU_PAGE_SIZE, 0));
        assert(!verify_snapshot(&gpu, &vm, va + 64, pa + 64, MMU_PAGE_SIZE));
        assert(opengpu_mmu_vm_map_snapshot(&gpu, &vm, alias, pa, MMU_PAGE_SIZE, 0) == -EEXIST);
        assert(!verify_snapshot(&gpu, &vm, va, pa, 64));
        assert(opengpu_mmu_vm_map_snapshot(&gpu, &vm, va, pa + 0x10000, MMU_PAGE_SIZE, 2) == -EEXIST);
        assert(first[1023] == before && !verify_snapshot(&gpu, &vm, va, pa, 64));
        {
            const u64 crossing = 0x3c3ff000ULL;
            u32 old_count, old_pte, *existing;
            unsigned old_flushes;
            assert(!opengpu_mmu_vm_map(&gpu, &vm, crossing + MMU_PAGE_SIZE,
                                        0x60000000, MMU_PAGE_SIZE, 0));
            old_count = vm.l1_count; old_flushes = asid_flushes;
            existing = vm_l1_find(&vm, (crossing + MMU_PAGE_SIZE) / MMU_SUPERPAGE_SIZE)->cpu;
            old_pte = existing[0];
            assert(opengpu_mmu_vm_map_snapshot(&gpu, &vm, crossing,
                0x61000000, 2 * MMU_PAGE_SIZE, 2) == -EEXIST);
            assert(vm.l1_count == old_count && !root[crossing / MMU_SUPERPAGE_SIZE]);
            assert(existing[0] == old_pte && asid_flushes == old_flushes);
        }
        root[0] = mmu_leaf_pte(0, 0);
        assert(verify_snapshot(&gpu, &vm, va, pa, 64) == -EFAULT);
        root[0] = 0;
        asid_flush_failures = 1;
        assert(opengpu_mmu_vm_map_snapshot(&gpu, &vm, va + 2 * MMU_PAGE_SIZE,
                                             pa + 0x10000, MMU_PAGE_SIZE, 2) == -EIO);
        assert(first[1023] == before && !gpu.hw.wedged);
        assert(!verify_snapshot(&gpu, &vm, va, pa, 64));

        assert(!opengpu_mmu_vm_map_snapshot(&gpu, &vm, 0xfffff000ULL,
                                             0xfffff000ULL, MMU_PAGE_SIZE, 0));
        assert(!verify_snapshot(&gpu, &vm, 0xffffffc0ULL, 0xffffffc0ULL, 64));
        assert(verify_snapshot(&gpu, &vm, 0xffffffc0ULL, 0xffffffc0ULL, 65) == -ERANGE);
        assert(!verify_snapshot(&gpu, NULL, 0x1234, 0x1234, 64));
        assert(verify_snapshot(&gpu, NULL, 0x1234, 0x2234, 64) == -EFAULT);
        assert(verify_snapshot(&gpu, &vm, 0x35000000, 0x60000000, 64) == -EFAULT);
        opengpu_mmu_vm_destroy(&gpu, &vm);
        assert(live_allocations == live_before);
    }
    /* Executable snapshots share rollback/alias discipline, but require RX
     * rather than either the old RWX private leaf or the NX data snapshot. */
    {
        struct opengpu_vm vm;
        const u64 va = 0x283ff000ULL, pa = 0x17000000ULL, alias = 0x38000000ULL;
        u32 *first, *second, old, old_count;
        int live_before = live_allocations;
        assert(!opengpu_mmu_vm_create(&gpu, &vm));
        assert(!opengpu_mmu_vm_map_code_snapshot(&gpu, &vm, va, pa, 2 * MMU_PAGE_SIZE, 2));
        first = vm_l1_find(&vm, va / MMU_SUPERPAGE_SIZE)->cpu;
        second = vm_l1_find(&vm, (va + MMU_PAGE_SIZE) / MMU_SUPERPAGE_SIZE)->cpu;
        old = first[1023];
        assert((old & 0xffu) == (MMU_PTE_V | MMU_PTE_R | MMU_PTE_X | MMU_PTE_A));
        assert((second[0] & 0xffu) == (old & 0xffu));
        assert(!verify_code(&gpu, &vm, va + 64, pa + 64, MMU_PAGE_SIZE));
        assert(verify_snapshot(&gpu, &vm, va, pa, 64) == -EACCES);
        for (u32 bad = 0; bad < 5; ++bad) {
            const u32 leaves[] = { old | MMU_PTE_W, old | MMU_PTE_U, old | MMU_PTE_G,
                                   old & ~MMU_PTE_X, old & ~MMU_PTE_R };
            first[1023] = leaves[bad];
            assert(verify_code(&gpu, &vm, va + 64, pa + 64, MMU_PAGE_SIZE) == -EACCES);
        }
        first[1023] = old;
        second[0] ^= 1u << 10;
        assert(verify_code(&gpu, &vm, va + 64, pa + 64, MMU_PAGE_SIZE) == -EACCES);
        second[0] ^= 1u << 10;
        assert(!opengpu_mmu_vm_map(&gpu, &vm, alias, pa + MMU_PAGE_SIZE, MMU_PAGE_SIZE, 2));
        assert(verify_code(&gpu, &vm, va, pa, 2 * MMU_PAGE_SIZE) == -EACCES);
        assert(!opengpu_mmu_vm_unmap(&gpu, &vm, alias, MMU_PAGE_SIZE));
        old_count = vm.l1_count;
        assert(opengpu_mmu_vm_map_code_snapshot(&gpu, &vm, va, pa + 0x10000,
                                                2 * MMU_PAGE_SIZE, 2) == -EEXIST);
        assert(vm.l1_count == old_count && first[1023] == old);
        asid_flush_failures = 1;
        assert(opengpu_mmu_vm_map_code_snapshot(&gpu, &vm, va + 2 * MMU_PAGE_SIZE,
                                                pa + 2 * MMU_PAGE_SIZE, MMU_PAGE_SIZE, 2) == -EIO);
        assert(!gpu.hw.wedged && !second[1]);
        assert(!verify_code(&gpu, &vm, va, pa, 2 * MMU_PAGE_SIZE));
        assert(!opengpu_mmu_vm_unmap(&gpu, &vm, va, 2 * MMU_PAGE_SIZE));
        assert(!opengpu_mmu_vm_map_code_snapshot(&gpu, &vm, va, pa + 0x10000,
                                                 2 * MMU_PAGE_SIZE, 2));
        assert(!verify_code(&gpu, &vm, va, pa + 0x10000, 2 * MMU_PAGE_SIZE));
        assert(verify_code(&gpu, &vm, va, pa, 64) == -EACCES);
        assert(!opengpu_mmu_vm_map_code_snapshot(&gpu, &vm, 0xfffff000ULL,
                                                 0xfffff000ULL, MMU_PAGE_SIZE, 2));
        assert(!verify_code(&gpu, &vm, 0xfffffffcULL, 0xfffffffcULL, 4));
        assert(verify_code(&gpu, &vm, 0xfffffffcULL, 0xfffffffcULL, 8) == -ERANGE);
        assert(!verify_code(&gpu, &vm, 0xffffffffULL, 0xffffffffULL, 1));
        assert(verify_code(&gpu, &vm, 0xffffffffULL, 0xffffffffULL, 2) == -ERANGE);
        assert(verify_code(&gpu, NULL, va, va, 64) == -EINVAL);
        assert(opengpu_mmu_vm_map_code_snapshot(&gpu, NULL, va, pa, MMU_PAGE_SIZE, 2) == -EINVAL);
        opengpu_mmu_vm_destroy(&gpu, &vm);
        assert(live_allocations == live_before);
    }
    /* Full uniform publication: slot6 is read-only across all fifty pages,
     * and can be rebound to new backing only after its old mapping retires. */
    {
        struct opengpu_vm vm;
        const u64 va=0xb1800000ULL,pa=0x70000000ULL,bytes=204800;
        int live_before=live_allocations;
        assert(!opengpu_mmu_vm_create(&gpu,&vm));
        assert(!opengpu_mmu_vm_map_snapshot(&gpu,&vm,va,pa,bytes,2));
        assert(!verify_snapshot(&gpu,&vm,va,pa,bytes));
        for(u64 offset=0;offset<bytes;offset+=MMU_PAGE_SIZE) {
            u32 *table=vm_l1_find(&vm,(va+offset)/MMU_SUPERPAGE_SIZE)->cpu;
            u32 leaf=table[((va+offset)%MMU_SUPERPAGE_SIZE)/MMU_PAGE_SIZE];
            assert((leaf&0x3ffu)==(MMU_PTE_V|MMU_PTE_R|MMU_PTE_A|(2u<<MMU_PTE_POLICY_SHIFT)));
            assert((u64)(leaf>>10)*MMU_PAGE_SIZE==pa+offset);
        }
        assert(opengpu_mmu_vm_map_snapshot(&gpu,&vm,va,pa+bytes,bytes,2)==-EEXIST);
        assert(!opengpu_mmu_vm_unmap(&gpu,&vm,va,bytes));
        assert(!opengpu_mmu_vm_map_snapshot(&gpu,&vm,va,pa+bytes,bytes,2));
        assert(!verify_snapshot(&gpu,&vm,va,pa+bytes,bytes));
        assert(verify_snapshot(&gpu,&vm,va,pa,bytes)==-EACCES);
        opengpu_mmu_vm_destroy(&gpu,&vm);assert(live_allocations==live_before);
    }
    /* Snapshot VA allocation is transactional over the full 32-bit space,
     * not a small fixed command slot; preserve occupied holes and the output
     * address under allocation/shootdown failure. */
    {
        struct opengpu_vm vm;
        const u64 pa = 0x74000000ULL, span = MMU_SUPERPAGE_SIZE + MMU_PAGE_SIZE;
        dma_addr_t chosen = 0xdeadbeef;
        int live_before = live_allocations;
        assert(!opengpu_mmu_vm_create(&gpu, &vm));
        assert(!opengpu_mmu_vm_map(&gpu, &vm, 0x1000, 0x60000000, MMU_PAGE_SIZE, 2));
        assert(!opengpu_mmu_vm_map(&gpu, &vm, 0x4000, 0x60001000, MMU_PAGE_SIZE, 2));
        allocations_until_failure = 0;
        assert(opengpu_mmu_vm_map_snapshot_anywhere(&gpu, &vm, pa, span, 2,
                                                   &chosen) == -ENOMEM);
        assert(chosen == 0xdeadbeef);
        allocations_until_failure = -1;
        asid_flush_failures = 1;
        assert(opengpu_mmu_vm_map_snapshot_anywhere(&gpu, &vm, pa, span, 2,
                                                   &chosen) == -EIO);
        assert(chosen == 0xdeadbeef && !gpu.hw.wedged);
        assert(!opengpu_mmu_vm_map_snapshot_anywhere(&gpu, &vm, pa, span, 2, &chosen));
        assert(chosen == 0x5000);
        assert(!verify_snapshot(&gpu, &vm, chosen, pa, span));
        assert(!opengpu_mmu_vm_unmap(&gpu, &vm, chosen, span));
        assert(!opengpu_mmu_vm_map_snapshot_anywhere(&gpu, &vm, pa, 2 * MMU_PAGE_SIZE,
                                                    2, &chosen));
        assert(chosen == 0x2000);
        assert(!verify_snapshot(&gpu, &vm, chosen, pa, 2 * MMU_PAGE_SIZE));
        assert(opengpu_mmu_vm_map_snapshot_anywhere(&gpu, &vm, pa, 0, 2, &chosen) == -EINVAL);
        assert(chosen == 0x2000);
        assert(opengpu_mmu_vm_map_snapshot_anywhere(&gpu, &vm, pa, MMU_PAGE_SIZE,
                                                   2, NULL) == -EINVAL);
        opengpu_mmu_vm_destroy(&gpu, &vm);
        assert(!gpu.mmu.vms && live_allocations == live_before);
    }
    /* New private descriptor leases exclude pre-existing aliases in any VM,
     * future writers and executable aliases. Only the owner can publish RO/NX
     * leaves; a held map, late idle failure or failed shootdown retains backing.
     * This test exercises the real map/acquire/release implementation. */
    {
        struct opengpu_vm first, second;
        struct opengpu_snapshot_lease a = {0}, b = {0};
        const u64 pa = 0x72000000ULL, va = 0x50000000ULL;
        int live_before = live_allocations;
        assert(!opengpu_mmu_vm_create(&gpu, &first));
        assert(!opengpu_mmu_vm_create(&gpu, &second));
        assert(gpu.mmu.vms == &second && second.next == &first);
        mutex_lock(&gpu.hw.submit_lock);
        assert(opengpu_mmu_snapshot_acquire_locked(&gpu, &a, NULL, pa,
                                                   MMU_PAGE_SIZE) == -EINVAL);
        assert(opengpu_mmu_snapshot_acquire_locked(&gpu, &a, &first, pa + 1,
                                                   MMU_PAGE_SIZE) == -EINVAL);
        assert(opengpu_mmu_snapshot_acquire_locked(&gpu, &a, &first,
                  0xfffff000ULL, 2 * MMU_PAGE_SIZE) == -ERANGE);
        assert(!a.active && !gpu.mmu.snapshot_leases);
        mutex_unlock(&gpu.hw.submit_lock);
        assert(!opengpu_mmu_vm_map(&gpu, &second, va, pa + MMU_PAGE_SIZE,
                                   MMU_PAGE_SIZE, 2));
        mutex_lock(&gpu.hw.submit_lock);
        assert(opengpu_mmu_snapshot_acquire_locked(&gpu, &a, &first, pa,
                 2 * MMU_PAGE_SIZE) == -EACCES);
        assert(!a.active && !gpu.mmu.snapshot_leases);
        mutex_unlock(&gpu.hw.submit_lock);
        assert(!opengpu_mmu_vm_unmap(&gpu, &second, va, MMU_PAGE_SIZE));
        mutex_lock(&gpu.hw.submit_lock);
        idle_error = -ETIMEDOUT;
        assert(opengpu_mmu_snapshot_acquire_locked(&gpu, &a, &first, pa,
                 2 * MMU_PAGE_SIZE) == -ETIMEDOUT);
        assert(!a.active);
        idle_error = 0;
        assert(!opengpu_mmu_snapshot_acquire_locked(&gpu, &a, &first, pa,
                                                   2 * MMU_PAGE_SIZE));
        assert(opengpu_mmu_snapshot_acquire_locked(&gpu, &b, &second,
                 pa + MMU_PAGE_SIZE, MMU_PAGE_SIZE) == -EBUSY);
        assert(a.active && !b.active && gpu.mmu.snapshot_leases == &a);
        mutex_unlock(&gpu.hw.submit_lock);
        assert(opengpu_mmu_vm_map(&gpu, &second, va, pa, MMU_PAGE_SIZE, 2) == -EACCES);
        assert(opengpu_mmu_vm_map(&gpu, &first, va, pa, MMU_PAGE_SIZE, 2) == -EACCES);
        assert(opengpu_mmu_vm_map_snapshot(&gpu, &second, va, pa,
                                           MMU_PAGE_SIZE, 2) == -EACCES);
        assert(opengpu_mmu_vm_map_code_snapshot(&gpu, &first, va, pa,
                                                MMU_PAGE_SIZE, 2) == -EACCES);
        assert(!opengpu_mmu_vm_map_snapshot(&gpu, &first, va, pa,
                                            2 * MMU_PAGE_SIZE, 2));
        assert(!verify_snapshot(&gpu, &first, va, pa, 2 * MMU_PAGE_SIZE));
        assert(a.mapped && a.va == va);
        assert(opengpu_mmu_vm_map_snapshot(&gpu, &first, va + 0x10000, pa,
                   2 * MMU_PAGE_SIZE, 2) == -EACCES);
        assert(opengpu_mmu_vm_replace(&gpu, &first, va, pa + 0x100000,
                   MMU_PAGE_SIZE, 2, 0, 0) == -EACCES);
        assert(opengpu_mmu_vm_unmap(&gpu, &first, va, MMU_PAGE_SIZE) == -EACCES);
        assert(opengpu_mmu_vm_unmap(&gpu, &first, va, 2 * MMU_PAGE_SIZE) == -EACCES);
        mutex_lock(&gpu.hw.submit_lock);
        assert(opengpu_mmu_snapshot_release_locked(&gpu, &a) == -EBUSY);
        assert(a.active);
        mutex_unlock(&gpu.hw.submit_lock);
        asid_flush_failures = 1;
        assert(opengpu_mmu_snapshot_unmap(&gpu, &a) == -EIO);
        assert(!verify_snapshot(&gpu, &first, va, pa, 2 * MMU_PAGE_SIZE));
        assert(a.active);
        assert(!opengpu_mmu_snapshot_unmap(&gpu, &a));
        mutex_lock(&gpu.hw.submit_lock);
        idle_error = -ETIMEDOUT;
        assert(opengpu_mmu_snapshot_release_locked(&gpu, &a) == -ETIMEDOUT);
        assert(a.active);
        idle_error = 0;
        assert(!opengpu_mmu_snapshot_release_locked(&gpu, &a));
        assert(!a.active && !gpu.mmu.snapshot_leases && !a.owner && !a.next);
        assert(!opengpu_mmu_snapshot_acquire_locked(&gpu, &b, &second, pa,
                                                   2 * MMU_PAGE_SIZE));
        assert(!opengpu_mmu_snapshot_release_locked(&gpu, &b));
        mutex_unlock(&gpu.hw.submit_lock);
        assert(!opengpu_mmu_vm_map(&gpu, &first, va, pa, 2 * MMU_PAGE_SIZE, 2));
        opengpu_mmu_vm_destroy(&gpu, &second);
        assert(gpu.mmu.vms == &first && !first.next);
        opengpu_mmu_vm_destroy(&gpu, &first);
        assert(!gpu.mmu.vms && live_allocations == live_before);
    }
    /* Mutable resources need immutable VA ownership, including unmapped pool
     * gaps. This reserves the complete envelope without claiming CPU/GEM data
     * immutability or requiring physical contiguity across spill allocations. */
    {
        struct opengpu_vm vm;
        struct opengpu_vm_reservation envelope={0},other={0},forged;
        const u64 span=2*MMU_SUPERPAGE_SIZE+3*MMU_PAGE_SIZE,pa=0x75000000;
        dma_addr_t snapshot=0;
        int live_before=live_allocations;
        assert(!opengpu_mmu_vm_create(&gpu,&vm));
        idle_error=-ETIMEDOUT;
        assert(opengpu_mmu_vm_reserve(&gpu,&vm,span,&envelope)==-ETIMEDOUT && !envelope.active);
        idle_error=0;
        assert(!opengpu_mmu_vm_reserve(&gpu,&vm,span,&envelope));
        assert(envelope.va==MMU_PAGE_SIZE && envelope.size==span && envelope.active);
        assert(!opengpu_mmu_vm_reserve(&gpu,&vm,MMU_PAGE_SIZE,&other));
        assert(other.va==envelope.va+span);
        assert(opengpu_mmu_vm_map(&gpu,&vm,envelope.va+MMU_PAGE_SIZE,pa,MMU_PAGE_SIZE,2)==-EACCES);
        assert(opengpu_mmu_vm_map_snapshot(&gpu,&vm,envelope.va+MMU_PAGE_SIZE,pa,MMU_PAGE_SIZE,2)==-EACCES);
        assert(opengpu_mmu_vm_unmap(&gpu,&vm,envelope.va,MMU_PAGE_SIZE)==-EACCES);
        forged=envelope;
        assert(opengpu_mmu_vm_map_reserved(&gpu,&forged,0,pa,MMU_PAGE_SIZE,2,true)==-EACCES);
        assert(opengpu_mmu_vm_map_reserved(&gpu,&envelope,span,pa,MMU_PAGE_SIZE,2,true)==-EINVAL);
        assert(!opengpu_mmu_vm_map_snapshot_anywhere(&gpu,&vm,pa,MMU_PAGE_SIZE,2,&snapshot));
        assert(snapshot==other.va+other.size);
        assert(!opengpu_mmu_vm_unmap(&gpu,&vm,snapshot,MMU_PAGE_SIZE));
        allocations_until_failure=0;
        assert(opengpu_mmu_vm_map_reserved(&gpu,&envelope,0,pa,MMU_PAGE_SIZE,2,true)==-ENOMEM);
        allocations_until_failure=-1;asid_flush_failures=1;
        assert(opengpu_mmu_vm_map_reserved(&gpu,&envelope,0,pa,MMU_PAGE_SIZE,2,true)==-EIO);
        assert(envelope.active && !gpu.hw.wedged);
        assert(!opengpu_mmu_vm_map_reserved(&gpu,&envelope,0,pa,MMU_PAGE_SIZE,2,true));
        assert(!opengpu_mmu_vm_map_reserved(&gpu,&envelope,span-MMU_PAGE_SIZE,pa+0x100000,MMU_PAGE_SIZE,2,false));
        {
            struct opengpu_buffer *first=vm_l1_find(&vm,(u32)(envelope.va/MMU_SUPERPAGE_SIZE));
            u64 last=envelope.va+span-MMU_PAGE_SIZE;
            struct opengpu_buffer *tail=vm_l1_find(&vm,(u32)(last/MMU_SUPERPAGE_SIZE));
            u32 rw=((u32 *)first->cpu)[(envelope.va%MMU_SUPERPAGE_SIZE)/MMU_PAGE_SIZE];
            u32 ro=((u32 *)tail->cpu)[(last%MMU_SUPERPAGE_SIZE)/MMU_PAGE_SIZE];
            assert((rw&(MMU_PTE_R|MMU_PTE_W|MMU_PTE_X))==(MMU_PTE_R|MMU_PTE_W));
            assert((ro&(MMU_PTE_R|MMU_PTE_W|MMU_PTE_X))==MMU_PTE_R);
        }
        assert(opengpu_mmu_vm_release_reservation(&gpu,&envelope)==-EBUSY);
        assert(opengpu_mmu_vm_map_reserved(&gpu,&envelope,0,pa+0x200000,MMU_PAGE_SIZE,2,true)==-EEXIST);
        asid_flush_failures=1;
        assert(opengpu_mmu_vm_unmap_reserved(&gpu,&envelope,0,MMU_PAGE_SIZE)==-EIO);
        assert(envelope.active && !gpu.hw.wedged);
        assert(!opengpu_mmu_vm_unmap_reserved(&gpu,&envelope,0,MMU_PAGE_SIZE));
        assert(!opengpu_mmu_vm_unmap_reserved(&gpu,&envelope,span-MMU_PAGE_SIZE,MMU_PAGE_SIZE));
        idle_error=-ETIMEDOUT;
        assert(opengpu_mmu_vm_release_reservation(&gpu,&envelope)==-ETIMEDOUT && envelope.active);
        idle_error=0;
        opengpu_mmu_vm_destroy(&gpu,&vm);
        assert(gpu.hw.wedged && vm.enabled && envelope.active && vm.root.cpu);
        gpu.hw.wedged=false;
        assert(!opengpu_mmu_vm_release_reservation(&gpu,&envelope));
        assert(!envelope.active && !envelope.owner);
        assert(!opengpu_mmu_vm_map(&gpu,&vm,MMU_PAGE_SIZE,pa,MMU_PAGE_SIZE,2));
        assert(!opengpu_mmu_vm_unmap(&gpu,&vm,MMU_PAGE_SIZE,MMU_PAGE_SIZE));
        assert(!opengpu_mmu_vm_release_reservation(&gpu,&other));
        opengpu_mmu_vm_destroy(&gpu,&vm);
        assert(!vm.enabled && live_allocations==live_before);
    }
    /* Destruction cannot free PT memory or recycle an ASID on idle/flush
     * failure. Other VMs remain linked and the old ASID stays allocated. */
    {
        struct opengpu_vm victim, other;
        assert(!opengpu_mmu_vm_create(&gpu,&victim));
        assert(!opengpu_mmu_vm_map(&gpu,&victim,0x28000000,0x11223000,4096,0));
        void *root=victim.root.cpu,*leaf=victim.l1[0].cpu;
        int allocations=live_allocations;u32 asid=victim.asid;
        idle_error=-ETIMEDOUT;
        assert(opengpu_mmu_vm_destroy(&gpu,&victim)==-ETIMEDOUT);
        idle_error=0;
        assert(gpu.hw.wedged && victim.enabled && gpu.mmu.vms==&victim);
        assert(victim.root.cpu==root && victim.l1[0].cpu==leaf && live_allocations==allocations);
        asid_flush_failures=1;
        assert(opengpu_mmu_vm_destroy(&gpu,&victim)==-EIO);
        assert(victim.enabled && victim.asid==asid && victim.root.cpu==root && live_allocations==allocations);
        assert(opengpu_asid_in_use(&gpu.mmu.asids,asid));
        assert(!opengpu_mmu_vm_create(&gpu,&other) && other.asid!=asid);
        assert(opengpu_mmu_fini(&gpu)==-EBUSY && gpu.mmu.enabled && gpu.mmu.root.cpu);
        assert(!opengpu_mmu_vm_destroy(&gpu,&other));
        assert(gpu.mmu.vms==&victim && !opengpu_mmu_vm_destroy(&gpu,&victim));
        assert(!gpu.mmu.vms && !opengpu_asid_in_use(&gpu.mmu.asids,asid));gpu.hw.wedged=false;
    }
    {
        int allocations=live_allocations;void *root=gpu.mmu.root.cpu;
        idle_error=-ETIMEDOUT;
        assert(opengpu_mmu_fini(&gpu)==-ETIMEDOUT);
        idle_error=0;
        assert(gpu.hw.wedged && gpu.mmu.enabled && gpu.mmu.root.cpu==root && live_allocations==allocations);
        global_flush_failures=1;
        assert(opengpu_mmu_fini(&gpu)==-EIO);
        assert(gpu.mmu.enabled && gpu.mmu.root.cpu==root && live_allocations==allocations);
        gpu.hw.wedged=false;
    }
    assert(!gpu.hw.submit_lock.held && !gpu.mmu.lock.held);
    assert(detached_root==gpu.mmu.root.dma && !detached_asid);
    assert(!opengpu_mmu_fini(&gpu) && disables>=2);
    fixture_test_pte_visitor();
    assert(!live_allocations && !fixture_buffers);
    /* A global fini winning during unpublished-root allocation must prevent
     * later ASID allocation/publication into the disabled MMU. */
    assert(!opengpu_mmu_init(&gpu));
    struct opengpu_vm after_shutdown;mmu_allocation_complete=finish_while_vm_allocates;
    assert(opengpu_mmu_vm_create(&gpu,&after_shutdown)==-ENODEV);
    assert(!gpu.mmu.enabled && !gpu.mmu.root.cpu && !gpu.mmu.vms);
    assert(!after_shutdown.enabled && !after_shutdown.root.cpu && !live_allocations && !fixture_buffers);
    assert(opengpu_mmu_vm_create(&gpu,&after_shutdown)==-ENODEV);
    gpu.hw.capabilities=0;
    assert(opengpu_mmu_vm_create(&gpu,&after_shutdown)==-EOPNOTSUPP && !live_allocations);
    puts("MMU allocation, rollback, range and lock-scope tests passed");
    return 0;
}
