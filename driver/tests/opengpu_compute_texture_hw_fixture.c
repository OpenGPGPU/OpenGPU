/* The production compute hardware entry point, with mock lock and invalidation. */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include "gpu_abi.h"
#include "opengpu_compute_features.h"
#include "opengpu_compute_indirect_source.h"
#include "opengpu_compute_uniform_records.h"
typedef uint64_t u64;
#define upper_32_bits(v) ((uint64_t)(v) >> 32)
#define check_mul_overflow(a,b,p) __builtin_mul_overflow(a,b,p)
#define mutex_lock(p) do { assert(*(p) == 0); *(p) = 1; } while (0)
#define mutex_unlock(p) do { assert(*(p) == 1); *(p) = 0; } while (0)
#define dma_wmb() (barriers++)
struct dma_fence { int dummy; };
struct opengpu_vm { bool enabled; };
struct opengpu_buffer { void *cpu; u64 dma; size_t size; };
struct opengpu_command_events { int dummy; };
struct opengpu_kernel_launch { u32 indirect_base,indirect_bytes,indirect_offset; u64 kernel_pc, kernarg; u32 grid[3], local[3]; bool texture_enabled, storage_enabled, image_enabled, counter_enabled, uniform_enabled, indirect_enabled; };
struct opengpu_device { struct { u32 capabilities; bool compute_indirect_transport; int submit_lock; struct opengpu_compute_local_limits compute_local_limits; } hw; };
static unsigned barriers, count, submits; static int fail_at;
static const struct opengpu_buffer *seen[6]; static struct dma_fence fence;
static int opengpu_hw_invalidate_buffer_locked(struct opengpu_device *g, const struct opengpu_buffer *b)
{ assert(g->hw.submit_lock && barriers); seen[count++] = b; return fail_at == (int)count ? -EIO : 0; }
/* Lease waiting is exercised through its actual body in the snapshot gate
 * fixture; this unrelated publication fixture has no live snapshot owner. */
static int opengpu_hw_snapshot_submission_lock(struct opengpu_device *g)
{ mutex_lock(&g->hw.submit_lock); return 0; }
static bool opengpu_hw_execution_busy(struct opengpu_device *g) { (void)g; return false; }
static int opengpu_mmu_verify_snapshot_locked(struct opengpu_device *g,const struct opengpu_vm *v,u64 a,u64 p,size_t z)
{ assert(g->hw.submit_lock&&v&&v->enabled&&a&&p&&z);return 0; }
static int opengpu_hw_unified_submit_locked(struct opengpu_device *g,
    const struct opengpu_kernel_launch *l, const struct opengpu_command_events *e,
    u32 opcode, u32 source, u32 destination, u32 bytes, u32 pattern, u32 sample,
    u32 width, u32 height, u32 source_stride, u32 destination_stride, u64 expected,
    const struct opengpu_vm *vm, u32 delay, struct dma_fence **out)
{ (void)l; (void)e; (void)source; (void)destination; (void)bytes; (void)pattern;
  (void)sample; (void)width; (void)height; (void)source_stride; (void)destination_stride;
  (void)expected; (void)vm; (void)delay; assert(g->hw.submit_lock && opcode == GPU_UCMD_OP_KERNEL);
  submits++; *out = &fence; return 0; }
#include "prepare_under_test.inc"
int main(void)
{
    struct opengpu_device g = { .hw.capabilities = GPU_CAP_UNIFIED_COMMANDS };
    g.hw.compute_local_limits=(struct opengpu_compute_local_limits){128,128,true};
    struct opengpu_kernel_launch l = { .kernel_pc=0x1000, .kernarg=0x2000,
        .grid={1,1,1}, .local={1,1,1}, .texture_enabled=true };
    struct opengpu_buffer shader = { .cpu=&g, .dma=0x3000, .size=128 };
    struct opengpu_buffer tex = { .cpu=&g, .dma=0x4000, .size=128 };
    struct opengpu_buffer storage = { .cpu=&g, .dma=0x5000, .size=256 };
    struct dma_fence *f = NULL;
    l.storage_enabled=true;
    assert(opengpu_hw_compute_async(&g,&l,&shader,&tex,NULL,NULL,NULL,NULL,NULL,NULL,NULL,&f) == -EINVAL && !count && !f);
    l.storage_enabled=false;
    assert(opengpu_hw_compute_async(&g,&l,&shader,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,&f) == -EINVAL && !count);
    assert(opengpu_hw_compute_async(&g,&l,&shader,&tex,NULL,NULL,NULL,NULL,NULL,NULL,NULL,&f) == 0);
    assert(count == 2 && seen[0] == &shader && seen[1] == &tex && submits == 1 && f == &fence);
    count=0; fail_at=2; f=NULL;
    assert(opengpu_hw_compute_async(&g,&l,&shader,&tex,NULL,NULL,NULL,NULL,NULL,NULL,NULL,&f) == -EIO && !f && submits == 1);
    count=0; fail_at=0; l.texture_enabled=false;
    assert(opengpu_hw_compute_async(&g,&l,&shader,&tex,NULL,NULL,NULL,NULL,NULL,NULL,NULL,&f) == 0 && count == 1 && submits == 2);
    count=0; l.storage_enabled=true;
    assert(opengpu_hw_compute_async(&g,&l,&shader,&tex,&storage,NULL,NULL,NULL,NULL,NULL,NULL,&f) == 0);
    assert(count == 2 && seen[0] == &shader && seen[1] == &storage && submits == 3);
    count=0; fail_at=2; f=NULL;
    assert(opengpu_hw_compute_async(&g,&l,&shader,&tex,&storage,NULL,NULL,NULL,NULL,NULL,NULL,&f) == -EIO && !f && submits == 3);
    count=0; fail_at=0; l.storage_enabled=false; l.image_enabled=true;
    struct opengpu_buffer image = { .cpu=&g, .dma=0x6000, .size=1024 };
    assert(opengpu_hw_compute_async(&g,&l,&shader,&tex,NULL,NULL,NULL,NULL,NULL,NULL,NULL,&f) == -EINVAL && !count && !f);
    assert(opengpu_hw_compute_async(&g,&l,&shader,&tex,NULL,&image,NULL,NULL,NULL,NULL,NULL,&f) == 0);
    assert(count == 2 && seen[0] == &shader && seen[1] == &image && submits == 4);
    count=0; fail_at=2; f=NULL;
    assert(opengpu_hw_compute_async(&g,&l,&shader,&tex,NULL,&image,NULL,NULL,NULL,NULL,NULL,&f) == -EIO && !f && submits == 4);
    count=0; fail_at=0; l.image_enabled=false; l.local[0]=128; f=NULL;
    assert(opengpu_hw_compute_async(&g,&l,&shader,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,&f) == 0 && submits == 5);
    count=0; l.local[0]=129; f=NULL;
    assert(opengpu_hw_compute_async(&g,&l,&shader,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,&f) == -EINVAL && !count && !f && submits == 5);
    l.local[0]=8; l.local[1]=4; l.local[2]=4;
    assert(opengpu_hw_compute_async(&g,&l,&shader,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,&f) == 0 && submits == 6);
    count=0; l.local[2]=5; f=NULL;
    assert(opengpu_hw_compute_async(&g,&l,&shader,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,&f) == -EINVAL && !count && !f && submits == 6);
    g.hw.compute_local_limits=(struct opengpu_compute_local_limits){8,8,true};
    l.local[0]=9; l.local[1]=l.local[2]=1;
    assert(opengpu_hw_compute_async(&g,&l,&shader,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,&f) == -EINVAL && !count && !f && submits == 6);
    l.local[0]=8;
    assert(opengpu_hw_compute_async(&g,&l,&shader,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,&f) == 0 && submits == 7);
    struct opengpu_buffer counter = { .cpu=&g, .dma=0x7000, .size=64 };
    l.counter_enabled=true; count=0; f=NULL;
    assert(opengpu_hw_compute_async(&g,&l,&shader,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,&f) == -EINVAL && !count && !f);
    counter.size=128;
    assert(opengpu_hw_compute_async(&g,&l,&shader,NULL,NULL,NULL,&counter,NULL,NULL,NULL,NULL,&f) == -EINVAL && !count);
    counter.size=64;
    assert(opengpu_hw_compute_async(&g,&l,&shader,NULL,NULL,NULL,&counter,NULL,NULL,NULL,NULL,&f) == 0);
    assert(count == 2 && seen[0] == &shader && seen[1] == &counter && submits == 8);
    count=0; fail_at=2; f=NULL;
    assert(opengpu_hw_compute_async(&g,&l,&shader,NULL,NULL,NULL,&counter,NULL,NULL,NULL,NULL,&f) == -EIO && !f && submits == 8);
    count=0;fail_at=0;l.counter_enabled=false;l.uniform_enabled=true;
    struct opengpu_vm vm={.enabled=true};
    struct opengpu_buffer uniform={.cpu=&g,.dma=0x10000,.size=8192};
    assert(opengpu_hw_compute_async(&g,&l,&shader,NULL,NULL,NULL,NULL,&uniform,NULL,NULL,&vm,&f)==0);
    assert(count==2&&seen[0]==&shader&&seen[1]==&uniform&&submits==9);
    count=0;f=NULL;vm.enabled=false;
    assert(opengpu_hw_compute_async(&g,&l,&shader,NULL,NULL,NULL,NULL,&uniform,NULL,NULL,&vm,&f)==-EINVAL&&!count&&!f);
    vm.enabled=true;uniform.size=OPENGPU_COMPUTE_UNIFORM_SNAPSHOT_MAX_BYTES+4096;
    assert(opengpu_hw_compute_async(&g,&l,&shader,NULL,NULL,NULL,NULL,&uniform,NULL,NULL,&vm,&f)==-EINVAL&&!count);
    uniform.size=8192;fail_at=2;
    assert(opengpu_hw_compute_async(&g,&l,&shader,NULL,NULL,NULL,NULL,&uniform,NULL,NULL,&vm,&f)==-EIO&&!f&&submits==9);
    assert(!g.hw.submit_lock);
    return 0;
}
