/* Compose the actual private owner with the actual MMU/map/lease algorithms.
 * Reuse only kernel primitive mocks from the existing MMU fixture. */
#define main mmu_regression_main
#include "opengpu_mmu_test.c"
#undef main
#define OPENGPU_SCHEDULER_H
#define OPENGPU_MMU_PAGE_SIZE 4096u
struct opengpu_render_context {
    struct opengpu_device *gpu;
    struct opengpu_vm vm;
    unsigned references;
};
static void opengpu_context_get(struct opengpu_render_context *c)
{ assert(c->references); ++c->references; }
static void opengpu_context_put(struct opengpu_render_context *c)
{ assert(c->references > 1); --c->references; }
#define OPENGPU_DRAW_WAIT_MS 10u
#define READ_ONCE(x) (x)
#define msecs_to_jiffies(x) (x)
#define time_after(a,b) ((long)((a)-(b)) > 0)
static unsigned long jiffies;
static void opengpu_hw_progress_tick(struct opengpu_device *gpu)
{ assert(!gpu->hw.submit_lock.held); ++jiffies; }
static void cond_resched(void) { }
#include "snapshot_submission_under_test.inc"
#include "../opengpu_private_snapshot.c"

static struct opengpu_private_snapshot *new_owner(struct opengpu_device *gpu,
    struct opengpu_render_context *context, u32 *metadata, u32 *data)
{
    unsigned char words[64];
    struct opengpu_private_snapshot *owner;
    memset(words, 0x5a, sizeof(words));
    owner = opengpu_private_snapshot_create(gpu, context);
    assert(!IS_ERR(owner));
    assert(!opengpu_private_snapshot_append(owner, words, sizeof(words), metadata));
    words[16] = 0xff;
    assert(!opengpu_private_snapshot_append(owner, words, sizeof(words), data));
    assert(!opengpu_private_snapshot_relocate(owner, *metadata, 0, *data, 4,
                                             OPENGPU_PRIVATE_SNAPSHOT_VA32));
    assert(!opengpu_private_snapshot_relocate(owner, *metadata, 4, *data, sizeof(words),
                                             OPENGPU_PRIVATE_SNAPSHOT_LIMIT33));
    return owner;
}

int main(void)
{
    struct opengpu_device gpu = {0};
    struct opengpu_render_context context = {.gpu=&gpu, .references=1};
    struct opengpu_vm attacker;
    struct opengpu_private_snapshot *owner;
    u32 metadata, data, value, address;
    int allocations;
    assert(!mmu_regression_main());
    /* Independent literal byte oracle at the 33-bit exclusive-limit edge.
     * The source guards must survive both success and rejected pointer cases. */
    {
        unsigned char bytes[16], expected[8] = {0,0,0,0,1,0,0,0};
        struct snapshot_segment source = {.backing.cpu=bytes};
        struct snapshot_segment target = {.va=0xfffffff0ULL};
        struct snapshot_relocation relocation = {.source=&source, .target=&target,
            .offset=4, .addend=16, .kind=OPENGPU_PRIVATE_SNAPSHOT_LIMIT33};
        memset(bytes, 0xa5, sizeof(bytes));
        assert(!snapshot_apply_relocation(&relocation));
        assert(!memcmp(bytes+4, expected, 8));
        assert(bytes[0]==0xa5 && bytes[3]==0xa5 && bytes[12]==0xa5 && bytes[15]==0xa5);
        memset(bytes, 0xa5, sizeof(bytes));
        relocation.kind=OPENGPU_PRIVATE_SNAPSHOT_VA32;
        assert(snapshot_apply_relocation(&relocation)==-ERANGE);
        for(unsigned i=0;i<sizeof(bytes);++i)assert(bytes[i]==0xa5);
        relocation.kind=OPENGPU_PRIVATE_SNAPSHOT_LIMIT33;relocation.addend=17;
        assert(snapshot_apply_relocation(&relocation)==-ERANGE);
        for(unsigned i=0;i<sizeof(bytes);++i)assert(bytes[i]==0xa5);
    }
    gpu.hw.capabilities = GPU_CAP_UNIFIED_COMMANDS;
    assert(!opengpu_mmu_init(&gpu));
    assert(!opengpu_mmu_vm_create(&gpu, &context.vm));
    assert(!opengpu_mmu_vm_create(&gpu, &attacker));
    owner = new_owner(&gpu, &context, &metadata, &data);
    assert(context.references == 2);
    assert(!opengpu_private_snapshot_read32(owner, metadata, 16, &value));
    assert(value == 0x5a5a5a5a);
    assert(!opengpu_private_snapshot_read32(owner, data, 16, &value));
    assert(value == 0x5a5a5aff);
    assert(opengpu_private_snapshot_read32(owner, data, 61, &value) == -EINVAL);
    assert(opengpu_private_snapshot_read32(owner, data, 64, &value) == -ERANGE);
    assert(opengpu_private_snapshot_relocate(owner, metadata, 8, data, 0,
        OPENGPU_PRIVATE_SNAPSHOT_VA32) == -EINVAL); /* overlaps upper limit */
    assert(opengpu_private_snapshot_relocate(owner, metadata, 60, data, 0,
        OPENGPU_PRIVATE_SNAPSHOT_LIMIT33) == -ERANGE);
    assert(!opengpu_private_snapshot_prepare(owner));
    assert(!opengpu_private_snapshot_address(owner, data, &address));
    assert(!opengpu_private_snapshot_read32(owner, metadata, 0, &value));
    assert(value == address + 4);
    assert(!opengpu_private_snapshot_read32(owner, metadata, 4, &value));
    assert(value == address + 64);
    assert(!opengpu_private_snapshot_read32(owner, metadata, 8, &value) && !value);
    assert(opengpu_private_snapshot_append(owner, &value, 4, &data) == -EINVAL);
    assert(opengpu_private_snapshot_relocate(owner, metadata, 20, data, 0,
        OPENGPU_PRIVATE_SNAPSHOT_VA32) == -EINVAL);
    assert(!opengpu_private_snapshot_publish(owner));
    assert(opengpu_private_snapshot_publish(owner) == -EINVAL);
    mutex_lock(&gpu.hw.submit_lock);
    assert(!opengpu_private_snapshot_verify_locked(owner, &gpu, &context));
    assert(opengpu_private_snapshot_verify_locked(owner, &gpu, NULL) == -EINVAL);
    mutex_unlock(&gpu.hw.submit_lock);
    assert(opengpu_mmu_vm_replace(&gpu, &context.vm, owner->segments->va,
        0x71000000, MMU_PAGE_SIZE, 2, 0, 0) == -EACCES);
    assert(opengpu_mmu_vm_unmap(&gpu, &context.vm, owner->segments->va,
        owner->segments->backing.size) == -EACCES);
    assert(opengpu_mmu_vm_map(&gpu, &attacker, 0x50000000,
        owner->segments->backing.dma, MMU_PAGE_SIZE, 2) == -EACCES);
    allocations = live_allocations;
    idle_error = -ETIMEDOUT;
    assert(opengpu_private_snapshot_retire(owner) == -ETIMEDOUT);
    assert(gpu.hw.wedged && context.references == 2 && live_allocations == allocations);
    assert(gpu.mmu.snapshot_leases);
    /* Explicit recovery; no automatic freeing occurs behind the failed wait. */
    idle_error = 0; gpu.hw.wedged = false;
    asid_flush_failures = 1;
    assert(opengpu_private_snapshot_retire(owner) == -EIO);
    assert(gpu.hw.wedged && live_allocations == allocations && context.references == 2);
    gpu.hw.wedged = false;
    assert(!opengpu_private_snapshot_retire(owner));
    assert(!gpu.mmu.snapshot_leases && context.references == 1);
    /* Existing cross-context mappings cannot turn a failed prepare into a
     * use-after-free. Keep the complete owner until that alias is revoked. */
    owner = new_owner(&gpu, &context, &metadata, &data);
    assert(!opengpu_mmu_vm_map(&gpu, &attacker, 0x50000000,
        owner->segments->backing.dma, MMU_PAGE_SIZE, 2));
    assert(opengpu_private_snapshot_prepare(owner) == -EACCES);
    allocations = live_allocations;
    assert(opengpu_private_snapshot_retire(owner) == -EACCES);
    assert(gpu.hw.wedged && live_allocations == allocations && context.references == 2);
    gpu.hw.wedged = false;
    assert(!opengpu_mmu_vm_unmap(&gpu, &attacker, 0x50000000, MMU_PAGE_SIZE));
    assert(!opengpu_private_snapshot_retire(owner));
    /* Fresh owner reuses the VA holes after complete drain/revocation. */
    owner = new_owner(&gpu, &context, &metadata, &data);
    assert(!opengpu_private_snapshot_prepare(owner));
    assert(!opengpu_private_snapshot_publish(owner));
    assert(!opengpu_private_snapshot_retire(owner));
    assert(context.references == 1 && !gpu.mmu.snapshot_leases);
    opengpu_mmu_vm_destroy(&gpu, &attacker);
    opengpu_mmu_vm_destroy(&gpu, &context.vm);
    opengpu_mmu_fini(&gpu);
    assert(!live_allocations);
    puts("private snapshot: copied bytes, relocation, cross-VM lease, timeout/shootdown quarantine and healthy reuse PASS (C only)");
    return 0;
}
