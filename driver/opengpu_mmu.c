// SPDX-License-Identifier: GPL-2.0
/* GPU MMU with an ASID-0 identity root and isolated context roots.
 *
 * The CU MMUs are enabled with a root table of 4 MiB identity superpages, so
 * controlled physical-address bring-up keeps working.  Identity leaves are
 * read/write but not executable: code runs only from VM-private leaves, so a
 * shader cannot execute identity memory.  Context roots start empty and only
 * contain explicitly mapped private pages.  When an ASID-0 4 KiB page
 * needs a non-default cache policy (for example an uncached buffer that the CPU
 * writes), the containing superpage is split into a second-level table and only
 * that page's PTE changes.  Page tables live in coherent DMA memory and are
 * fetched uncached by the hardware walker, so CPU writes to them are visible.
 */
#ifdef __KERNEL__
#include <linux/module.h>
#include <linux/moduleparam.h>
#include <linux/string.h>
#include <linux/vmalloc.h>
#else
#include <string.h>
#endif
#include <linux/mutex.h>
#include <linux/overflow.h>

#include "opengpu_device.h"

#define MMU_PAGE_SIZE       4096u
#define MMU_SUPERPAGE_SIZE  (4u * 1024u * 1024u)
#define MMU_L1_ENTRIES      1024u
#define MMU_ROOT_ENTRIES    1024u

/* Sv32 PTE flag bits. No leaf is global: ASID-0 translations must never
 * bypass the ASID check after switching to a private context root. */
#define MMU_PTE_V 0x001u
#define MMU_PTE_R 0x002u
#define MMU_PTE_W 0x004u
#define MMU_PTE_X 0x008u
#define MMU_PTE_U 0x010u
#define MMU_PTE_G 0x020u
#define MMU_PTE_A 0x040u
#define MMU_PTE_D 0x080u
/* Identity leaves are read/write but never executable. */
#define MMU_PTE_LEAF_FLAGS (MMU_PTE_V | MMU_PTE_R | MMU_PTE_W | \
                            MMU_PTE_A | MMU_PTE_D)
#define MMU_PTE_POLICY_SHIFT 8u

/* Test hook: fail the next N valid VM-private map calls with -ENOMEM before
 * any page-table work. The guest DRM test uses this through sysfs to prove
 * bind-time private-map allocation failure cleans up and a follow-up bind
 * succeeds. */
static unsigned int mmu_fail_private_maps;
#ifdef __KERNEL__
module_param(mmu_fail_private_maps, uint, 0644);
MODULE_PARM_DESC(mmu_fail_private_maps,
    "Number of valid VM-private map calls to fail with -ENOMEM");
#endif

static u32 mmu_leaf_pte(dma_addr_t phys, u32 policy)
{
    u32 ppn = (u32)(phys >> 12);

    return ((ppn & 0xfffffu) << 10) | MMU_PTE_LEAF_FLAGS |
        ((policy & 0x3u) << MMU_PTE_POLICY_SHIFT);
}

/* Private leaves are executable so privately mapped snapshot code runs. */
static u32 mmu_leaf_pte_private(dma_addr_t phys, u32 policy)
{
    return mmu_leaf_pte(phys, policy) | MMU_PTE_X;
}

/* Snapshot pages contain data, never writable or executable GPU memory. */
static u32 mmu_leaf_pte_snapshot(dma_addr_t phys, u32 policy)
{
    u32 ppn = (u32)(phys >> 12);

    return ((ppn & 0xfffffu) << 10) | MMU_PTE_V | MMU_PTE_R | MMU_PTE_A |
        ((policy & 0x3u) << MMU_PTE_POLICY_SHIFT);
}

static u32 mmu_link_pte(dma_addr_t table)
{
    return ((((u32)(table >> 12)) & 0xfffffu) << 10) | MMU_PTE_V;
}

/* Fill a root table with 4 MiB identity superpages.  The whole physical
 * address space is reachable this way, so every existing physical-address
 * binding keeps working. */
static void mmu_fill_identity(u32 *root, u32 policy)
{
    u32 i;

    for (i = 0; i < MMU_ROOT_ENTRIES; i++)
        root[i] = mmu_leaf_pte((dma_addr_t)i * MMU_SUPERPAGE_SIZE, policy);
}

/* Find the second-level table for a 4 MiB region, splitting the identity
 * superpage on first use. */
static int mmu_l1_for_region(struct opengpu_device *gpu, u32 region,
                             struct opengpu_buffer **out)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    u32 i;
    int ret;

    for (i = 0; i < mmu->l1_count; i++) {
        if (mmu->l1_region[i] == region) {
            *out = &mmu->l1[i];
            return 0;
        }
    }
    if (mmu->l1_count >= OPENGPU_MMU_MAX_TABLES)
        return -ENOSPC;

    i = mmu->l1_count;
    ret = opengpu_buffer_alloc(gpu, &mmu->l1[i], MMU_PAGE_SIZE);
    if (ret)
        return ret;
    {
        u32 *l1 = mmu->l1[i].cpu;
        u32 j;

        for (j = 0; j < MMU_L1_ENTRIES; j++)
            l1[j] = mmu_leaf_pte((dma_addr_t)region * MMU_SUPERPAGE_SIZE +
                                 (dma_addr_t)j * MMU_PAGE_SIZE,
                                 OPENGPU_MMU_POLICY_CACHED);
    }
    /* The caller publishes the link only after every allocation succeeds. */
    mmu->l1_region[i] = region;
    mmu->l1_count++;
    *out = &mmu->l1[i];
    return 0;
}

static struct opengpu_buffer *vm_l1_find(struct opengpu_vm *vm, u32 region)
{
    u32 i;

    for (i = 0; i < vm->l1_count; i++)
        if (vm->l1_region[i] == region)
            return &vm->l1[i];
    return NULL;
}

/* Return only an owned table that the active root actually references. */
static const struct opengpu_buffer *vm_live_l1(const struct opengpu_vm *vm, u32 region)
{
    u32 i;
    for (i = 0; i < vm->l1_count; ++i) {
        const struct opengpu_buffer *l1 = &vm->l1[i];
        if (vm->l1_region[i] != region)
            continue;
        if (!l1->cpu || l1->size < MMU_PAGE_SIZE ||
            (l1->dma & (MMU_PAGE_SIZE - 1)) ||
            (u64)l1->dma > (1ULL << 32) - MMU_PAGE_SIZE ||
            ((const u32 *)vm->root.cpu)[region] != mmu_link_pte(l1->dma))
            return NULL;
        return l1;
    }
    return NULL;
}

/* Allocate (but do not publish) a VM-private L1 table for `region`. */
static int vm_l1_for_region(struct opengpu_device *gpu, struct opengpu_vm *vm,
                            u32 region,
                            struct opengpu_buffer **out)
{
    struct opengpu_buffer *l1 = vm_l1_find(vm, region);
    int ret;

    if (l1) {
        *out = l1;
        return 0;
    }
    if (vm->l1_count >= OPENGPU_VM_MAX_TABLES)
        return -ENOSPC;
    l1 = &vm->l1[vm->l1_count];
    ret = opengpu_buffer_alloc(gpu, l1, MMU_PAGE_SIZE);
    if (ret)
        return ret;
    /* Every unbound page must fault, regardless of allocator contents. */
    memset(l1->cpu, 0, MMU_PAGE_SIZE);
    vm->l1_region[vm->l1_count] = region;
    vm->l1_count++;
    *out = l1;
    return 0;
}

int opengpu_mmu_set_range_policy(struct opengpu_device *gpu, dma_addr_t base,
                                 size_t size, u32 policy)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    u64 start, end, phys;
    size_t pages, n = 0;
    u32 *saved, old_roots[OPENGPU_MMU_MAX_TABLES];
    u32 old_count, i;
    int ret = 0;

    if (!mmu->enabled)
        return -EOPNOTSUPP;
    if (policy > OPENGPU_MMU_POLICY_UNCACHED)
        return -EINVAL;
    if (size == 0)
        return 0;

    if (check_add_overflow((u64)base, (u64)size, &end) ||
        end > (1ull << 32))
        return -ERANGE;
    start = (u64)base & ~(u64)(MMU_PAGE_SIZE - 1);
    end = (end + MMU_PAGE_SIZE - 1) & ~(u64)(MMU_PAGE_SIZE - 1);
    pages = (end - start) / MMU_PAGE_SIZE;
    saved = kvcalloc(pages, sizeof(*saved), GFP_KERNEL);
    if (!saved)
        return -ENOMEM;

    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_wait_idle_locked(gpu);
    if (ret)
        goto out_submit;
    mutex_lock(&mmu->lock);
    old_count = mmu->l1_count;
    /* Reserve all tables before touching any live mapping. */
    for (phys = start; phys < end;) {
        struct opengpu_buffer *l1;
        u32 region = phys / MMU_SUPERPAGE_SIZE;

        ret = mmu_l1_for_region(gpu, region, &l1);
        if (ret)
            goto rollback;
        phys = ((u64)region + 1) * MMU_SUPERPAGE_SIZE;
    }
    /* Preserve leaves, including those in newly reserved, unpublished tables. */
    for (phys = start; phys < end; phys += MMU_PAGE_SIZE) {
        struct opengpu_buffer *l1;
        u32 region = (u32)(phys / MMU_SUPERPAGE_SIZE);
        u32 page = (u32)((phys % MMU_SUPERPAGE_SIZE) / MMU_PAGE_SIZE);

        mmu_l1_for_region(gpu, region, &l1);
        saved[n++] = ((u32 *)l1->cpu)[page];
    }
    for (phys = start; phys < end; phys += MMU_PAGE_SIZE) {
        struct opengpu_buffer *l1;
        u32 region = (u32)(phys / MMU_SUPERPAGE_SIZE);
        u32 page = (u32)((phys % MMU_SUPERPAGE_SIZE) / MMU_PAGE_SIZE);
        u32 *table;

        /* All regions were reserved above; this lookup cannot allocate. */
        mmu_l1_for_region(gpu, region, &l1);
        table = l1->cpu;
        table[page] = mmu_leaf_pte(phys, policy);
    }
    dma_wmb();
    for (i = old_count; i < mmu->l1_count; i++) {
        u32 region = mmu->l1_region[i];
        u32 link = mmu_link_pte(mmu->l1[i].dma);
        old_roots[i] = ((u32 *)mmu->root.cpu)[region];
        ((u32 *)mmu->root.cpu)[region] = link;
        /* ASID-0 policy changes never grant access to context VMs. */
    }
    dma_wmb();
    ret = opengpu_hw_flush_tlbs(gpu);
    if (!ret)
        goto out_mmu;

    /* Restore both existing leaves and new root links. A second shootdown
     * failure leaves cached translations uncertain, so stop new submissions. */
    n = 0;
    for (phys = start; phys < end; phys += MMU_PAGE_SIZE) {
        struct opengpu_buffer *l1;
        u32 region = (u32)(phys / MMU_SUPERPAGE_SIZE);
        u32 page = (u32)((phys % MMU_SUPERPAGE_SIZE) / MMU_PAGE_SIZE);

        mmu_l1_for_region(gpu, region, &l1);
        ((u32 *)l1->cpu)[page] = saved[n++];
    }
    for (i = old_count; i < mmu->l1_count; i++)
        ((u32 *)mmu->root.cpu)[mmu->l1_region[i]] = old_roots[i];
    dma_wmb();
    if (opengpu_hw_flush_tlbs(gpu))
        WRITE_ONCE(gpu->hw.wedged, true);

rollback:
    while (mmu->l1_count > old_count)
        opengpu_buffer_free(gpu, &mmu->l1[--mmu->l1_count]);
out_mmu:
    mutex_unlock(&mmu->lock);
out_submit:
    mutex_unlock(&gpu->hw.submit_lock);
    kvfree(saved);
    return ret;
}

int opengpu_mmu_init(struct opengpu_device *gpu)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    int ret;

    mutex_init(&mmu->lock);
    opengpu_asid_pool_init(&mmu->asids);
    mmu->vms = NULL;
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return 0; /* No MMU control path; stay in Bare mode. */

    ret = opengpu_buffer_alloc(gpu, &mmu->root, MMU_PAGE_SIZE);
    if (ret)
        return ret;
    mmu_fill_identity(mmu->root.cpu, OPENGPU_MMU_POLICY_CACHED);
    dma_wmb();
    ret = opengpu_hw_enable_mmu(gpu, mmu->root.dma);
    if (ret) {
        opengpu_buffer_free(gpu, &mmu->root);
        return ret;
    }
    mmu->enabled = true;
    return 0;
}

/* Quiesce execution and drop every TLB entry tagged `asid`.  A recycled ASID
 * must be shot down before it names a different page table, and destroying a
 * VM must evict its entries so a later address space cannot hit them. */
static int mmu_shootdown_asid(struct opengpu_device *gpu, u32 asid)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    int ret;

    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_wait_idle_locked(gpu);
    if (!ret) {
        mutex_lock(&mmu->lock);
        ret = opengpu_hw_set_satp(gpu, mmu->root.dma, 0);
        if (!ret)
            ret = opengpu_hw_flush_tlb_asid(gpu, asid);
        mutex_unlock(&mmu->lock);
    }
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

int opengpu_mmu_vm_create(struct opengpu_device *gpu, struct opengpu_vm *vm)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    u32 asid;
    int ret;

    if (!vm)
        return -EINVAL;
    if (!mmu->enabled)
        return -EOPNOTSUPP;
    vm->asid = 0;
    vm->enabled = false;
    vm->l1_count = 0;
    vm->next = NULL;

    ret = opengpu_buffer_alloc(gpu, &vm->root, MMU_PAGE_SIZE);
    if (ret)
        return ret;

    mutex_lock(&mmu->lock);
    /* Only explicit VM mappings can publish a valid root link. */
    memset(vm->root.cpu, 0, MMU_PAGE_SIZE);
    ret = mmu->enabled ? opengpu_asid_alloc(&mmu->asids, &asid) :
        -EOPNOTSUPP;
    if (!ret) {
        vm->asid = asid;
        dma_wmb();
        vm->enabled = true;
        vm->next = mmu->vms;
        mmu->vms = vm;
    }
    mutex_unlock(&mmu->lock);
    if (ret)
        goto err_free_root;
    dma_wmb();

    /* A recycled ASID is clean: vm_destroy shoots the ASID down before it is
     * returned to the pool (and retains the VM on failure), so a new owner never
     * inherits another VM's private entries. */
    return 0;

err_free_root:
    opengpu_buffer_free(gpu, &vm->root);
    return ret;
}

int opengpu_mmu_vm_activate(struct opengpu_device *gpu,
                            const struct opengpu_vm *vm)
{
    if (!vm || !vm->enabled)
        return -EINVAL;
    if (!gpu->mmu.enabled)
        return -EOPNOTSUPP;
    /* The helper quiesces and programs satp. Distinct ASIDs keep their own
     * translations; destruction shoots down an ASID before reuse. */
    return opengpu_hw_activate_vm(gpu, vm);
}

enum mmu_private_leaf {
    MMU_PRIVATE_RW,
    MMU_SNAPSHOT_RO,
    MMU_CODE_RX,
};

/* Atomically map, unmap or replace private leaves, including overlapping
 * ranges and a shortened tail. A zero new size means unmap the old range.
 * All page tables and rollback storage are reserved before leaves change. */
static int mmu_vm_replace(struct opengpu_device *gpu, struct opengpu_vm *vm,
                          dma_addr_t va, dma_addr_t pa, size_t size, u32 policy,
                          dma_addr_t old_va, size_t old_size, enum mmu_private_leaf kind)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    u64 old_start = 0, old_end = 0, new_end = 0, pa_end, p, q;
    size_t old_pages, new_pages, n = 0;
    u32 *saved, old_count, i;
    int ret;

    if (!vm || !vm->enabled) return -EINVAL;
    if (!mmu->enabled) return -EOPNOTSUPP;
    if (policy > OPENGPU_MMU_POLICY_UNCACHED || (!size && !old_size) ||
        (va & (MMU_PAGE_SIZE - 1)) || (pa & (MMU_PAGE_SIZE - 1)) ||
        (size & (MMU_PAGE_SIZE - 1))) return -EINVAL;
    if (size && (check_add_overflow((u64)va, (u64)size, &new_end) ||
        check_add_overflow((u64)pa, (u64)size, &pa_end) ||
        new_end > (1ULL << 32) || pa_end > (1ULL << 32))) return -ERANGE;
    if (old_size) {
        if (check_add_overflow((u64)old_va, (u64)old_size, &old_end) ||
            old_end > (1ULL << 32)) return -ERANGE;
        old_start = (u64)old_va & ~(u64)(MMU_PAGE_SIZE - 1);
        old_end = (old_end + MMU_PAGE_SIZE - 1) & ~(u64)(MMU_PAGE_SIZE - 1);
    }
    old_pages = (old_end - old_start) / MMU_PAGE_SIZE;
    new_pages = size / MMU_PAGE_SIZE;
    saved = kvcalloc(old_pages + new_pages, sizeof(*saved), GFP_KERNEL);
    if (!saved) return -ENOMEM;
    if (size && mmu_fail_private_maps) { mmu_fail_private_maps--; ret = -ENOMEM; goto out_free; }
    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_wait_idle_locked(gpu);
    if (ret) goto out_submit;
    mutex_lock(&mmu->lock);
    if (!vm->enabled) { ret = -EINVAL; goto out_mmu; }
    old_count = vm->l1_count;
    for (p = old_start; p < old_end;) {
        u32 region = (u32)(p / MMU_SUPERPAGE_SIZE);
        struct opengpu_buffer *l1 = vm_l1_find(vm, region);
        if (!l1 || !l1->cpu) { ret = -ENOENT; goto rollback_tables; }
        p = ((u64)region + 1) * MMU_SUPERPAGE_SIZE;
    }
    for (p = va; p < new_end;) {
        u32 region = (u32)(p / MMU_SUPERPAGE_SIZE);
        struct opengpu_buffer *l1;
        ret = vm_l1_for_region(gpu, vm, region, &l1);
        if (ret) goto rollback_tables;
        if (!l1->cpu || l1->size < MMU_PAGE_SIZE) { ret = -EFAULT; goto rollback_tables; }
        p = ((u64)region + 1) * MMU_SUPERPAGE_SIZE;
    }
    /* Snapshot windows must not replace an existing live mapping. */
    if (kind != MMU_PRIVATE_RW) {
        for (p = va; p < new_end; p += MMU_PAGE_SIZE) {
            struct opengpu_buffer *l1 = vm_l1_find(vm, (u32)(p / MMU_SUPERPAGE_SIZE));
            if (((u32 *)l1->cpu)[(p % MMU_SUPERPAGE_SIZE) / MMU_PAGE_SIZE]) {
                ret = -EEXIST;
                goto rollback_tables;
            }
        }
    }
    for (p = old_start; p < old_end; p += MMU_PAGE_SIZE) {
        struct opengpu_buffer *l1 = vm_l1_find(vm, (u32)(p / MMU_SUPERPAGE_SIZE));
        saved[n++] = ((u32 *)l1->cpu)[(p % MMU_SUPERPAGE_SIZE) / MMU_PAGE_SIZE];
    }
    for (p = va; p < new_end; p += MMU_PAGE_SIZE) {
        struct opengpu_buffer *l1 = vm_l1_find(vm, (u32)(p / MMU_SUPERPAGE_SIZE));
        saved[n++] = ((u32 *)l1->cpu)[(p % MMU_SUPERPAGE_SIZE) / MMU_PAGE_SIZE];
    }
    for (p = old_start; p < old_end; p += MMU_PAGE_SIZE) {
        struct opengpu_buffer *l1 = vm_l1_find(vm, (u32)(p / MMU_SUPERPAGE_SIZE));
        ((u32 *)l1->cpu)[(p % MMU_SUPERPAGE_SIZE) / MMU_PAGE_SIZE] = 0;
    }
    for (p = va, q = pa; p < new_end; p += MMU_PAGE_SIZE, q += MMU_PAGE_SIZE) {
        struct opengpu_buffer *l1 = vm_l1_find(vm, (u32)(p / MMU_SUPERPAGE_SIZE));
        ((u32 *)l1->cpu)[(p % MMU_SUPERPAGE_SIZE) / MMU_PAGE_SIZE] =
            kind == MMU_PRIVATE_RW ? mmu_leaf_pte_private(q, policy) :
                mmu_leaf_pte_snapshot(q, policy) | (kind == MMU_CODE_RX ? MMU_PTE_X : 0);
    }
    dma_wmb();
    for (i = old_count; i < vm->l1_count; ++i)
        ((u32 *)vm->root.cpu)[vm->l1_region[i]] = mmu_link_pte(vm->l1[i].dma);
    dma_wmb();
    ret = opengpu_hw_flush_tlb_asid(gpu, vm->asid);
    if (!ret) goto out_mmu;

    /* Restore both original ranges, including overlaps, before releasing new
     * tables. A second failed shootdown wedges submission instead of allowing
     * a stale translation to outlive its backing. */
    n = 0;
    for (p = old_start; p < old_end; p += MMU_PAGE_SIZE) {
        struct opengpu_buffer *l1 = vm_l1_find(vm, (u32)(p / MMU_SUPERPAGE_SIZE));
        ((u32 *)l1->cpu)[(p % MMU_SUPERPAGE_SIZE) / MMU_PAGE_SIZE] = saved[n++];
    }
    for (p = va; p < new_end; p += MMU_PAGE_SIZE) {
        struct opengpu_buffer *l1 = vm_l1_find(vm, (u32)(p / MMU_SUPERPAGE_SIZE));
        ((u32 *)l1->cpu)[(p % MMU_SUPERPAGE_SIZE) / MMU_PAGE_SIZE] = saved[n++];
    }
    for (i = old_count; i < vm->l1_count; ++i)
        ((u32 *)vm->root.cpu)[vm->l1_region[i]] = 0;
    dma_wmb();
    if (opengpu_hw_flush_tlb_asid(gpu, vm->asid))
        WRITE_ONCE(gpu->hw.wedged, true);
rollback_tables:
    while (vm->l1_count > old_count)
        opengpu_buffer_free(gpu, &vm->l1[--vm->l1_count]);
out_mmu:
    mutex_unlock(&mmu->lock);
out_submit:
    mutex_unlock(&gpu->hw.submit_lock);
out_free:
    kvfree(saved);
    return ret;
}

int opengpu_mmu_vm_replace(struct opengpu_device *gpu, struct opengpu_vm *vm,
                           dma_addr_t va, dma_addr_t pa, size_t size, u32 policy,
                           dma_addr_t old_va, size_t old_size)
{
    return mmu_vm_replace(gpu, vm, va, pa, size, policy, old_va, old_size, MMU_PRIVATE_RW);
}

/* Map and unmap share replacement's rollback and shootdown discipline. */
int opengpu_mmu_vm_map(struct opengpu_device *gpu, struct opengpu_vm *vm,
                       dma_addr_t va, dma_addr_t pa, size_t size, u32 policy)
{
    if (!size)
        return -EINVAL;
    return opengpu_mmu_vm_replace(gpu, vm, va, pa, size, policy, 0, 0);
}

int opengpu_mmu_vm_map_snapshot(struct opengpu_device *gpu, struct opengpu_vm *vm,
                                dma_addr_t va, dma_addr_t pa, size_t size, u32 policy)
{
    if (!size)
        return -EINVAL;
    return mmu_vm_replace(gpu, vm, va, pa, size, policy, 0, 0, MMU_SNAPSHOT_RO);
}

int opengpu_mmu_vm_map_code_snapshot(struct opengpu_device *gpu, struct opengpu_vm *vm,
                                     dma_addr_t va, dma_addr_t pa, size_t size, u32 policy)
{
    if (!size || !vm || !vm->asid)
        return -EINVAL;
    return mmu_vm_replace(gpu, vm, va, pa, size, policy, 0, 0, MMU_CODE_RX);
}

/* submit_lock excludes VM replacement and submission while these page tables
 * are checked. Bare/ASID-0 mode can prove identity only; its caller must
 * independently guarantee immutability and reject physical aliases. */
static int mmu_verify_snapshot_locked(struct opengpu_device *gpu,
    const struct opengpu_vm *vm, dma_addr_t va, dma_addr_t pa, size_t size, bool executable)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    u64 va_end, pa_end, va_page, pa_page, pa_limit, v, p;
    const u32 required = MMU_PTE_V | MMU_PTE_R | MMU_PTE_A | (executable ? MMU_PTE_X : 0);
    const u32 permissions = MMU_PTE_V | MMU_PTE_R | MMU_PTE_W | MMU_PTE_X |
                            MMU_PTE_U | MMU_PTE_G | MMU_PTE_A;
    u32 region, j;
    int ret = 0;

    lockdep_assert_held(&gpu->hw.submit_lock);
    if (!size || (executable && !vm) || ((va ^ pa) & (MMU_PAGE_SIZE - 1)))
        return -EINVAL;
    if (check_add_overflow((u64)va, (u64)size, &va_end) ||
        check_add_overflow((u64)pa, (u64)size, &pa_end) ||
        va_end > (1ULL << 32) || pa_end > (1ULL << 32))
        return -ERANGE;
    if (!vm)
        return va == pa ? 0 : -EFAULT;
    if (!mmu->enabled)
        return -EOPNOTSUPP;
    va_page = (u64)va & ~(u64)(MMU_PAGE_SIZE - 1);
    pa_page = (u64)pa & ~(u64)(MMU_PAGE_SIZE - 1);
    pa_limit = (pa_end + MMU_PAGE_SIZE - 1) & ~(u64)(MMU_PAGE_SIZE - 1);

    mutex_lock(&mmu->lock);
    if (!vm->enabled || !vm->asid || vm->asid > OPENGPU_ASID_MAX ||
        !vm->root.cpu || vm->root.size < MMU_PAGE_SIZE ||
        (vm->root.dma & (MMU_PAGE_SIZE - 1)) ||
        (u64)vm->root.dma > (1ULL << 32) - MMU_PAGE_SIZE ||
        vm->l1_count > OPENGPU_VM_MAX_TABLES) {
        ret = -EINVAL;
        goto out;
    }
    for (v = va_page, p = pa_page; v < va_end; v += MMU_PAGE_SIZE, p += MMU_PAGE_SIZE) {
        const struct opengpu_buffer *l1;
        u32 pte;
        region = (u32)(v / MMU_SUPERPAGE_SIZE);
        l1 = vm_live_l1(vm, region);
        if (!l1) {
            ret = -EFAULT;
            goto out;
        }
        pte = ((u32 *)l1->cpu)[(v % MMU_SUPERPAGE_SIZE) / MMU_PAGE_SIZE];
        if ((pte & permissions) != required || ((pte >> MMU_PTE_POLICY_SHIFT) & 3u) > OPENGPU_MMU_POLICY_UNCACHED ||
            ((u64)(pte >> 10) << 12) != p) {
            ret = -EACCES;
            goto out;
        }
    }
    /* A second writable VA for any snapshot page defeats a read-only leaf. */
    for (region = 0; region < MMU_ROOT_ENTRIES; ++region) {
        const struct opengpu_buffer *l1;
        u32 root = ((u32 *)vm->root.cpu)[region];
        if (!(root & MMU_PTE_V))
            continue;
        l1 = vm_live_l1(vm, region);
        if (!l1) {
            ret = -EFAULT;
            goto out;
        }
        for (j = 0; j < MMU_L1_ENTRIES; ++j) {
            u32 pte = ((u32 *)l1->cpu)[j];
            u64 mapped = (u64)(pte >> 10) << 12;
            if ((pte & (MMU_PTE_V | MMU_PTE_W)) == (MMU_PTE_V | MMU_PTE_W) &&
                mapped >= pa_page && mapped < pa_limit) {
                ret = -EACCES;
                goto out;
            }
        }
    }
out:
    mutex_unlock(&mmu->lock);
    return ret;
}

int opengpu_mmu_verify_snapshot_locked(struct opengpu_device *gpu,
                                       const struct opengpu_vm *vm,
                                       dma_addr_t va, dma_addr_t pa, size_t size)
{
    return mmu_verify_snapshot_locked(gpu, vm, va, pa, size, false);
}

int opengpu_mmu_verify_code_snapshot_locked(struct opengpu_device *gpu,
                                            const struct opengpu_vm *vm,
                                            dma_addr_t va, dma_addr_t pa, size_t size)
{
    return mmu_verify_snapshot_locked(gpu, vm, va, pa, size, true);
}

int opengpu_mmu_vm_unmap(struct opengpu_device *gpu, struct opengpu_vm *vm,
                         dma_addr_t va, size_t size)
{
    if (!size)
        return -EINVAL;
    return opengpu_mmu_vm_replace(gpu, vm, 0, 0, 0,
                                  OPENGPU_MMU_POLICY_CACHED, va, size);
}

int opengpu_mmu_vm_destroy(struct opengpu_device *gpu, struct opengpu_vm *vm)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    u32 i;
    int ret;

    if (!vm || !vm->enabled)
        return 0;
    /* Evict this address space before releasing the ASID for reuse.  Private
     * (non-global) entries must not survive into the next owner, so if the
     * shootdown fails the whole VM and ASID remain quarantined. */
    ret = mmu_shootdown_asid(gpu, vm->asid);
    if (ret) {
        WRITE_ONCE(gpu->hw.wedged, true);
        return ret; /* Retain VM identity, ASID and every reachable table. */
    }
    /* Publish the VM as dead before dropping page tables, under the same
     * locks unmap holds, so a retiring job cannot write a freed table. */
    mutex_lock(&gpu->hw.submit_lock);
    mutex_lock(&mmu->lock);
    vm->enabled = false;
    {
        struct opengpu_vm **link;
        for (link = &mmu->vms; *link && *link != vm; link = &(*link)->next)
            ;
        if (*link)
            *link = vm->next;
        vm->next = NULL;
    }
    opengpu_asid_free(&mmu->asids, vm->asid);
    for (i = 0; i < vm->l1_count; i++)
        opengpu_buffer_free(gpu, &vm->l1[i]);
    vm->l1_count = 0;
    opengpu_buffer_free(gpu, &vm->root);
    mutex_unlock(&mmu->lock);
    mutex_unlock(&gpu->hw.submit_lock);
    return 0;
}

int opengpu_mmu_fini(struct opengpu_device *gpu)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    u32 i;
    int ret = 0;

    mutex_lock(&gpu->hw.submit_lock);
    mutex_lock(&mmu->lock);
    if (mmu->vms) {
        ret = -EBUSY;
        goto out;
    }
    if (mmu->enabled) {
        ret = opengpu_hw_wait_idle_locked(gpu);
        if (!ret)
            ret = opengpu_hw_disable_mmu_locked(gpu);
        if (ret)
            goto out;
    }
    for (i = 0; i < mmu->l1_count; i++)
        opengpu_buffer_free(gpu, &mmu->l1[i]);
    mmu->l1_count = 0;
    opengpu_buffer_free(gpu, &mmu->root);
    mmu->enabled = false;
out:
    if (ret)
        WRITE_ONCE(gpu->hw.wedged, true);
    mutex_unlock(&mmu->lock);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}
