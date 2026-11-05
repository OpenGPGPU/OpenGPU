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
#define GPU_CAP_UNIFIED_COMMANDS 1u
#define OPENGPU_MMU_MAX_TABLES 16u
#define OPENGPU_VM_MAX_TABLES 192u
#define OPENGPU_MMU_POLICY_CACHED 0u
#define OPENGPU_MMU_POLICY_UNCACHED 2u
struct opengpu_buffer { void *cpu; dma_addr_t dma; size_t size; };
struct opengpu_vm;
struct opengpu_mmu {
    struct mutex lock;
    struct opengpu_buffer root;
    struct opengpu_buffer l1[OPENGPU_MMU_MAX_TABLES];
    u32 l1_region[OPENGPU_MMU_MAX_TABLES];
    u32 l1_count;
    struct opengpu_asid_pool asids;
    bool enabled;
};
struct opengpu_vm {
    struct opengpu_buffer root;
    u32 asid;
    bool enabled;
    struct opengpu_buffer l1[OPENGPU_VM_MAX_TABLES];
    u32 l1_region[OPENGPU_VM_MAX_TABLES];
    u32 l1_count;
};
struct opengpu_device {
    struct { struct mutex submit_lock; u32 capabilities; bool wedged; } hw;
    struct opengpu_mmu mmu;
};
static int allocations_until_failure = -1, live_allocations, idle_error;
static unsigned flushes, barriers;
static int global_flush_failures;
static dma_addr_t next_dma = 0x100000;
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
    live_allocations++;
    return 0;
}
static void opengpu_buffer_free(struct opengpu_device *gpu,
                               struct opengpu_buffer *b)
{
    (void)gpu;
    if (b->cpu) {
        free(b->cpu);
        b->cpu = NULL;
        live_allocations--;
    }
}
static int opengpu_hw_wait_idle_locked(struct opengpu_device *gpu)
{
    assert(gpu->hw.submit_lock.held);
    return idle_error;
}
static int opengpu_hw_flush_tlbs(struct opengpu_device *gpu)
{
    assert(gpu->hw.submit_lock.held && gpu->mmu.lock.held);
    assert(barriers >= 2);
    flushes++;
    if (global_flush_failures) { --global_flush_failures; return -EIO; }
    return 0;
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
    return 0;
}
static int opengpu_hw_activate_vm(struct opengpu_device *gpu,
                                  const struct opengpu_vm *vm)
{
    /* The production helper acquires submit_lock itself. */
    assert(!gpu->hw.submit_lock.held);
    last_satp_root = vm ? vm->root.dma : 0;
    last_satp_asid = vm ? vm->asid : 0;
    vm_activations++;
    return 0;
}
static int opengpu_hw_enable_mmu(struct opengpu_device *gpu, dma_addr_t root)
{
    (void)gpu;
    assert(root && barriers);
    return 0;
}
#define WRITE_ONCE(x, v) ((x) = (v))
#define GFP_KERNEL 0
static bool snapshot_alloc_fail;
#define kvcalloc(n, s, flags) (snapshot_alloc_fail ? NULL : calloc((n), (s)))
#define kvfree(p) free(p)
#define dma_wmb() (++barriers)
#include "../opengpu_mmu.c"

static int verify_snapshot(struct opengpu_device *gpu, const struct opengpu_vm *vm,
                           dma_addr_t va, dma_addr_t pa, size_t size)
{
    int ret;
    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_mmu_verify_snapshot_locked(gpu, vm, va, pa, size);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

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
        assert(opengpu_mmu_vm_map(&gpu, &vm, 0x40000000ULL, 0x13000000ULL,
                                  MMU_PAGE_SIZE, 2) == -ENOSPC);
        assert(vm.l1_count == OPENGPU_VM_MAX_TABLES && ((u32 *)vm.root.cpu)[256] == 0);
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
    assert(!gpu.hw.submit_lock.held && !gpu.mmu.lock.held);
    opengpu_mmu_fini(&gpu);
    assert(!live_allocations);
    puts("MMU allocation, rollback, range and lock-scope tests passed");
    return 0;
}
