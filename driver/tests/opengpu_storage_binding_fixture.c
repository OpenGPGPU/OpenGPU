/* Real resource ioctls and VA planner; modeled GEM ownership and MMU commits. */
#include <assert.h>
#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "opengpu_drm.h"
#include "gpu_abi.h"
#include "opengpu_kernarg_va.h"
#include "opengpu_storage_descriptor.h"
#include "opengpu_storage_slot.h"

typedef uint64_t u64;
typedef uint64_t dma_addr_t;
#define GFP_KERNEL 0
#define OPENGPU_MMU_PAGE_SIZE 4096u
#define OPENGPU_MMU_POLICY_UNCACHED 0u
#define OPENGPU_SHADER_MAX_INSTRUCTIONS 512u
#define upper_32_bits(v) ((u64)(v) >> 32)
#define check_add_overflow(a, b, out) __builtin_add_overflow(a, b, out)
#define check_mul_overflow(a, b, out) __builtin_mul_overflow(a, b, out)
#define BUILD_BUG_ON(v) _Static_assert(!(v), #v)
#define WRITE_ONCE(dst, value) ((dst) = (value))
#define max(a, b) ((a) > (b) ? (a) : (b))
#define opengpu_is_power_of_two(v) ((v) && !((v) & ((v) - 1)))

struct drm_gem_object { u64 size; unsigned refs; };
struct drm_gem_dma_object { struct drm_gem_object base; u64 dma_addr; };
struct opengpu_resource_binding {
    struct drm_gem_object *object;
    u64 dma, offset, size;
    u32 type, width, height, flags;
    u64 va;
};
struct opengpu_vm { bool enabled; };
struct opengpu_render_context {
    u32 id;
    struct opengpu_vm vm;
    struct opengpu_resource_binding *bindings[OPENGPU_MAX_RESOURCE_BINDINGS];
};
struct opengpu_device { struct { u32 capabilities; bool wedged; } hw; };
struct opengpu_file { int lock; struct opengpu_device *gpu; struct opengpu_render_context *ctx; };
struct drm_device { struct opengpu_device *dev; };
struct drm_file { struct opengpu_file *driver_priv; };
static struct drm_gem_dma_object objects[4];
static int allocation_count, fail_alloc, fail_quiesce, fail_map, fail_unmap;
static unsigned map_calls, passed;
static const struct drm_opengpu_resource *request;
struct mapping { u64 va, pa, span, logical_va, size; bool valid; };
static struct mapping mappings[39];

static void *kzalloc(size_t size, int flags)
{
    (void)flags;
    if (fail_alloc) return NULL;
    void *p = calloc(1, size);
    assert(p); allocation_count++; return p;
}
static void kfree(void *p) { if (p) { allocation_count--; free(p); } }
static struct opengpu_device *dev_get_drvdata(struct opengpu_device *p) { return p; }
static void mutex_lock(int *lock) { assert(!*lock); *lock = 1; }
static void mutex_unlock(int *lock) { assert(*lock == 1); *lock = 0; }
static struct drm_gem_object *drm_gem_object_lookup(struct drm_file *file, u32 handle)
{
    (void)file;
    if (!handle || handle > 4) return NULL;
    objects[handle - 1].base.refs++; return &objects[handle - 1].base;
}
static void drm_gem_object_put(struct drm_gem_object *p) { assert(p->refs > 1); p->refs--; }
static struct drm_gem_dma_object *to_drm_gem_dma_obj(struct drm_gem_object *p)
{ return (struct drm_gem_dma_object *)((char *)p - offsetof(struct drm_gem_dma_object, base)); }
static struct opengpu_render_context *opengpu_context_lookup(struct opengpu_file *f, u32 id)
{ return id == f->ctx->id ? f->ctx : NULL; }
static int opengpu_context_quiesce(struct opengpu_file *f, struct opengpu_render_context *c)
{ assert(f->lock && c == f->ctx); return fail_quiesce ? -EIO : 0; }

static int opengpu_mmu_vm_replace(struct opengpu_device *gpu, struct opengpu_vm *vm,
    u64 va, u64 pa, size_t span, u32 policy, u64 old_va, size_t old_size)
{
    (void)gpu; assert(vm->enabled && policy == OPENGPU_MMU_POLICY_UNCACHED);
    bool storage=va>=OPENGPU_STORAGE_VA_BASE;
    u64 base=storage ? OPENGPU_STORAGE_VA_BASE : OPENGPU_TEXTURE_VA_BASE;
    u64 stride=storage ? OPENGPU_STORAGE_VA_STRIDE : OPENGPU_TEXTURE_VA_STRIDE;
    u64 slot=(va-base)/stride, index=slot+(storage ? 0 : 4);
    assert(slot<(storage ? 4u : 35u) && va==base+slot*stride);
    struct mapping *m = &mappings[index];
    assert((!m->valid && !old_va && !old_size) ||
           (m->valid && old_va == m->logical_va && old_size == m->size));
    map_calls++;
    if (fail_map) return -EIO;
    assert(!(va & 4095) && !(pa & 4095) && !(span & 4095));
    assert(va + span <= base+(slot+1)*stride);
    for (unsigned i = 0; i < 39; i++) if (i != index && mappings[i].valid)
        assert(va + span <= mappings[i].va || mappings[i].va + mappings[i].span <= va);
    u64 physical = objects[request->handle - 1].dma_addr + request->offset;
    *m = (struct mapping){va, pa, span, va + (physical & 4095), request->size, true};
    return 0;
}
static int opengpu_mmu_vm_unmap(struct opengpu_device *gpu, struct opengpu_vm *vm,
                               u64 va, size_t size)
{
    (void)gpu; assert(vm->enabled);
    for (unsigned i = 0; i < 39; i++) if (mappings[i].valid && mappings[i].logical_va == va) {
        assert(mappings[i].size == size);
        if (fail_unmap) return -EIO;
        mappings[i].valid = false; return 0;
    }
    assert(!"unmap without owned mapping"); return -EINVAL;
}
static int forbidden(void) { assert(!"unexpected nonstorage path"); return -EINVAL; }
#define opengpu_binding_map_kernarg(...) forbidden()
#define opengpu_binding_map_vertex(...) forbidden()
#define opengpu_binding_map_uncached(...) forbidden()
#define opengpu_binding_invalidate(...) forbidden()
/* These two warnings are existing kernel-source conventions. Keep all fixture
 * code and the changed arithmetic under -Werror. */
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wsign-compare"
#pragma GCC diagnostic ignored "-Wunused-parameter"
#include "storage_binding_under_test.inc"
#pragma GCC diagnostic pop

static int bind_view(struct drm_device *drm, struct drm_file *file,
                     struct drm_opengpu_resource *r)
{
    request = r;
    int ret = opengpu_compute_resource_bind_ioctl(drm, r, file);
    assert(!file->driver_priv->lock); return ret;
}
int main(void)
{
    struct opengpu_device gpu = {0};
    struct opengpu_render_context ctx = {.id = 7, .vm.enabled = true};
    struct opengpu_file rf = {.gpu = &gpu, .ctx = &ctx};
    struct drm_device drm = {.dev = &gpu}; struct drm_file file = {.driver_priv = &rf};
    const u64 maximum = 128ull * 1024 * 1024;
    for (unsigned i = 0; i < 4; i++) objects[i] = (struct drm_gem_dma_object){
        .base = {.size = maximum + 4096, .refs = 1}, .dma_addr = 0x10000000ull + i * 0x20000000ull};
    const u64 lengths[] = {1, 3, 4, 63, 64, 65, 127, 128, 4095, 4096, 4097, maximum};
    struct drm_opengpu_resource r = {.context_id = 7, .type = OPENGPU_RESOURCE_COMPUTE_STORAGE};
    for (unsigned i = 0; i < 4; i++) {
        r.slot = 35 + i; r.handle = i + 1;
        for (u64 offset = 0; offset < 4096; offset += 256) for (unsigned n = 0; n < sizeof(lengths)/sizeof(*lengths); n++) {
            r.offset = offset; r.size = lengths[n];
            assert(bind_view(&drm, &file, &r) == 0);
            struct opengpu_resource_binding *b = ctx.bindings[r.slot - 1];
            assert(b && b->size == r.size && b->offset == offset && b->object == &objects[i].base);
            assert(b->dma == objects[i].dma_addr + offset);
            assert(b->va == OPENGPU_STORAGE_VA_BASE + i * OPENGPU_STORAGE_VA_STRIDE + offset);
            assert(mappings[i].pa == objects[i].dma_addr && mappings[i].span >= offset + r.size);
            assert(objects[i].base.refs == 2 && allocation_count == (int)i + 1);
            passed++;
        }
    }
    /* All four maximum views coexist at the largest legal in-page offset. */
    assert(mappings[3].va + mappings[3].span == OPENGPU_TESS_STATES_VA_BASE);
    struct opengpu_resource_binding *old = ctx.bindings[34];
    r = (struct drm_opengpu_resource){.context_id=7,.slot=35,.handle=1,
        .type=OPENGPU_RESOURCE_COMPUTE_STORAGE,.size=65,.offset=256};
    unsigned before = map_calls;
    for (unsigned n = 0; n < 7; n++) {
        struct drm_opengpu_resource bad = r;
        switch (n) {
        case 0: bad.offset = 4; break;
        case 1: bad.size = 0; break;
        case 2: bad.size = maximum + 1; break;
        case 3: bad.offset = UINT64_MAX - 255; break;
        case 4: bad.slot = 34; break;
        case 5: bad.flags = 2; break;
        default: bad.width = 1; break;
        }
        assert(bind_view(&drm, &file, &bad) == -EINVAL);
        assert(ctx.bindings[34] == old && objects[0].base.refs == 2 && allocation_count == 4);
    }
    assert(map_calls == before);
    /* A byte-valid GEM without its full transport line must still fail. */
    u64 saved_size = objects[0].base.size; objects[0].base.size = r.offset + r.size;
    assert(bind_view(&drm, &file, &r) == -EINVAL); objects[0].base.size = saved_size;
    u64 saved_dma = objects[0].dma_addr; objects[0].dma_addr = 0xffffff00ull;
    assert(bind_view(&drm, &file, &r) == -ERANGE); objects[0].dma_addr = saved_dma;
    fail_alloc = 1; assert(bind_view(&drm, &file, &r) == -ENOMEM); fail_alloc = 0;
    fail_quiesce = 1; assert(bind_view(&drm, &file, &r) == -EIO); fail_quiesce = 0;
    fail_map = 1; assert(bind_view(&drm, &file, &r) == -EIO); fail_map = 0;
    assert(ctx.bindings[34] == old && objects[0].base.refs == 2 && allocation_count == 4);
    ctx.vm.enabled = false; assert(bind_view(&drm, &file, &r) == -EOPNOTSUPP); ctx.vm.enabled = true;
    r.slot = 13; assert(bind_view(&drm, &file, &r) == -EBUSY); r.slot = 35;
    assert(bind_view(&drm, &file, &r) == 0); /* healthy replacement after errors */
    for (unsigned i = 0; i < 4; i++) {
        struct drm_opengpu_resource unbind = {.context_id=7,.slot=35+i};
        if (!i) { fail_unmap=1; assert(opengpu_compute_resource_unbind_ioctl(&drm,&unbind,&file)==-EIO); fail_unmap=0; }
        assert(opengpu_compute_resource_unbind_ioctl(&drm, &unbind, &file) == 0);
        assert(!mappings[i].valid && !ctx.bindings[34+i] && objects[i].base.refs == 1);
    }
    assert(!allocation_count && !rf.lock);
    /* The buffer range's final incomplete texel still belongs to the BO and
     * its65th page is included before any later view count can be derived. */
    r=(struct drm_opengpu_resource){.context_id=7,.slot=3,.handle=1,
        .type=OPENGPU_RESOURCE_COMPUTE_TEXTURE,.offset=63,.size=65536*4+1,
        .flags=OPENGPU_RESOURCE_UNCACHED};
    assert(!bind_view(&drm,&file,&r)); old=ctx.bindings[2];
    assert(old->size==262145 && mappings[7].span==65*4096 && objects[0].base.refs==2);
    before=map_calls; fail_quiesce=1;
    assert(bind_view(&drm,&file,&r)==-EIO && map_calls==before);fail_quiesce=0;
    fail_map=1;assert(bind_view(&drm,&file,&r)==-EIO);fail_map=0;
    assert(ctx.bindings[2]==old && objects[0].base.refs==2 && allocation_count==1);
    r.size=134217729;assert(bind_view(&drm,&file,&r)==-ERANGE);
    assert(ctx.bindings[2]==old && objects[0].base.refs==2);
    r.size=262145;assert(!bind_view(&drm,&file,&r));
    struct drm_opengpu_resource unbind_texture={.context_id=7,.slot=3};
    assert(!opengpu_compute_resource_unbind_ioctl(&drm,&unbind_texture,&file));
    assert(!mappings[7].valid && objects[0].base.refs==1 && !allocation_count && !rf.lock);
    printf("storage binding: PASS %u byte views, full four-window capacity, bounds and failure ownership\n", passed);
    return 0;
}
