/* Production texture preparation, with simulated GEM identities and bindings. */
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "opengpu_compute_texture_bound_view.h"
#include "opengpu_staging_validator.h"
#include "opengpu_backing_identity.h"
#include "opengpu_drm.h"
typedef uint64_t u64;
_Static_assert(sizeof(struct drm_opengpu_compute_texture_level) == 64, "level ABI size");
_Static_assert(sizeof(struct drm_opengpu_compute_texture_view) == 1128, "view ABI size");
_Static_assert(sizeof(struct drm_opengpu_compute_textures) == 88, "submit ABI size");
#define U32_MAX UINT32_MAX
#define upper_32_bits(v) ((uint64_t)(v) >> 32)
#define DMA_RESV_USAGE_READ 1
#define DMA_RESV_USAGE_WRITE 2
struct dma_resv { int unused; };
struct drm_gem_object { struct dma_resv *resv; bool foreign; struct drm_gem_object *wrapped; };
struct opengpu_resource_binding { struct drm_gem_object *object; u64 dma, va, size; u32 type; };
struct opengpu_render_context { struct { bool enabled; } vm; struct opengpu_resource_binding *bindings[OPENGPU_MAX_COMPUTE_TEXTURE_RESOURCE_SLOTS]; };
struct opengpu_sched_dep { struct drm_gem_object *object; int wait_usage, signal_usage; };
static struct opengpu_resource_binding *opengpu_binding_lookup(struct opengpu_render_context *c, u32 slot, u32 type)
{ return slot && slot<=OPENGPU_MAX_COMPUTE_TEXTURE_RESOURCE_SLOTS && c->bindings[slot-1] && c->bindings[slot-1]->type==type ? c->bindings[slot-1] : NULL; }
static enum opengpu_backing_kind opengpu_gem_backing_step(const void *o, const void **next)
{
    const struct drm_gem_object *g=o;
    if (g->foreign) { *next=g; return OPENGPU_BACKING_FOREIGN; }
    if (g->wrapped) { *next=g->wrapped; return OPENGPU_BACKING_WRAPPER; }
    return OPENGPU_BACKING_LOCAL;
}
#include "gem_under_test.inc"
#include "prepare_under_test.inc"
int main(void)
{
    struct dma_resv reservations[3] = {{0}};
    struct drm_gem_object gems[3] = {{.resv=&reservations[0]}, {.resv=&reservations[1]}, {.resv=&reservations[2]}};
    struct opengpu_resource_binding kernarg={.object=&gems[0], .dma=0x40000, .va=0x80000000, .size=4096};
    struct opengpu_resource_binding texture={.object=&gems[1], .dma=0x50000, .va=0xa0400000, .size=4096, .type=OPENGPU_RESOURCE_COMPUTE_TEXTURE};
    struct opengpu_render_context ctx={.vm.enabled=true};
    struct opengpu_compute_texture_prepare *p=calloc(1,sizeof(*p)); assert(p);
    ctx.bindings[0]=&texture;
    p->views[0]=(struct drm_opengpu_compute_texture_view) { .slot=1,.format=3,.width=4,.height=4,.depth=1,.layers=1,.level_count=1 };
    p->views[0].levels[0]=(struct drm_opengpu_compute_texture_level) { .width=4,.height=4,.depth=1,.layers=1,.row_pitch=16,.block_bytes=4 };
    assert(opengpu_compute_prepare_texture_views(p,1,&ctx,&kernarg)==0);
    assert(p->dep_count==2 && p->deps[1].object==&gems[1]);
    assert(p->entries[0].image.allocation_base==0xa0400000 && p->resolved[0][0].base==0xa0400000);
    p->views[1]=p->views[0]; assert(opengpu_compute_prepare_texture_views(p,2,&ctx,&kernarg)==0 && p->dep_count==2);
    p->views[0].levels[0].offset=4095;
    assert(opengpu_compute_prepare_texture_views(p,1,&ctx,&kernarg)==-EINVAL);
    p->views[0].levels[0].offset=0; gems[1].foreign=true;
    assert(opengpu_compute_prepare_texture_views(p,1,&ctx,&kernarg)==-EOPNOTSUPP);
    gems[1].foreign=false; gems[1].wrapped=&gems[0];
    assert(opengpu_compute_prepare_texture_views(p,1,&ctx,&kernarg)==-EINVAL);
    gems[1].wrapped=NULL; texture.dma=0x40000;
    assert(opengpu_compute_prepare_texture_views(p,1,&ctx,&kernarg)==-EINVAL);
    texture.dma=0x50000; texture.va=0;
    assert(opengpu_compute_prepare_texture_views(p,1,&ctx,&kernarg)==-EINVAL);
    texture.va=0xa0400000; p->views[0].pad=1;
    assert(opengpu_compute_prepare_texture_views(p,1,&ctx,&kernarg)==-EINVAL);
    p->views[0].pad=0; p->views[0].levels[0].offset=(1ULL<<32);
    assert(opengpu_compute_prepare_texture_views(p,1,&ctx,&kernarg)==-EINVAL);
    {
        struct dma_resv wide_resv[32] = {{0}};
        struct drm_gem_object wide_gems[32] = {{0}};
        struct opengpu_resource_binding wide_bindings[32] = {{0}};
        struct opengpu_render_context wide = {.vm.enabled=true};
        memset(p,0,sizeof(*p));
        for (u32 i=0; i<32; ++i) {
            wide_gems[i].resv=&wide_resv[i];
            wide_bindings[i]=(struct opengpu_resource_binding) {
                .object=&wide_gems[i], .dma=0x100000 + 4096ULL*i,
                .va=0xa0000000ULL + 0x400000ULL*(i+3), .size=4096,
                .type=OPENGPU_RESOURCE_COMPUTE_TEXTURE,
            };
            wide.bindings[i+2]=&wide_bindings[i];
            p->views[i]=(struct drm_opengpu_compute_texture_view) {
                .slot=i+3,.format=3,.width=4,.height=4,.depth=1,.layers=1,.level_count=1,
            };
            p->views[i].levels[0]=(struct drm_opengpu_compute_texture_level) {
                .width=4,.height=4,.depth=1,.layers=1,.row_pitch=16,.block_bytes=4,
            };
        }
        assert(opengpu_compute_prepare_texture_views(p,32,&wide,&kernarg)==0);
        assert(p->dep_count==33 && p->deps[32].object==&wide_gems[31]);
        assert(p->entries[31].image.allocation_base==0xa8800000u);
        p->views[31].slot=35;
        assert(opengpu_compute_prepare_texture_views(p,32,&wide,&kernarg)==-EINVAL);
    }
    free(p); return 0;
}
