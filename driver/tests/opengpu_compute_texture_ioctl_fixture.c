/* Actual compute ioctl core, with mock GEM, reservation and job submission. */
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "opengpu_compute_texture_bound_view.h"
#include "opengpu_staging_validator.h"
#include "opengpu_backing_identity.h"
#include "opengpu_compute_texture_command.h"
#include "opengpu_shader_validator.h"
#include "opengpu_kernarg_va.h"
#include "opengpu_drm.h"
typedef uint64_t u64; typedef unsigned char u8;
#define U32_MAX UINT32_MAX
#define U16_MAX UINT16_MAX
#define upper_32_bits(v) ((uint64_t)(v)>>32)
#define check_mul_overflow(a,b,p) __builtin_mul_overflow(a,b,p)
#define min_t(t,a,b) ((t)(a)<(t)(b)?(t)(a):(t)(b))
#define ARRAY_SIZE(a) (sizeof(a)/sizeof((a)[0]))
#define OPENGPU_MMU_PAGE_SIZE 4096
#define OPENGPU_SCHED_COMPUTE 1
#define DMA_RESV_USAGE_READ 1
#define DMA_RESV_USAGE_WRITE 2
#define DRM_EXEC_INTERRUPTIBLE_WAIT 1
#define DRM_EXEC_IGNORE_DUPLICATES 2
#define drm_exec_until_all_locked(e) for (int once=1;once;once=0)
#define drm_exec_retry_on_contention(e) ((void)(e))
#define GFP_KERNEL 0
#define kzalloc(n,f) calloc(1,n)
#define kfree(p) free(p)
#define mutex_lock(p) ((void)(p))
#define mutex_unlock(p) ((void)(p))
struct dma_resv { int id; };
struct drm_gem_object { struct dma_resv *resv; bool foreign; void *vaddr; };
struct opengpu_resource_binding { struct drm_gem_object *object; u64 dma, va, size, offset; u32 type; };
struct opengpu_render_context { struct { bool enabled; } vm; struct opengpu_resource_binding *bindings[OPENGPU_MAX_COMPUTE_TEXTURE_RESOURCE_SLOTS]; };
struct opengpu_sched_dep { struct drm_gem_object *object; int wait_usage, signal_usage; };
struct opengpu_command_events { u32 flags; };
struct opengpu_buffer { void *cpu; u64 dma; size_t size; };
struct opengpu_kernel_launch { u64 kernel_pc,kernarg; u32 grid[3],local[3]; bool texture_enabled; struct opengpu_compute_texture_command_context texture; };
struct opengpu_device { struct { bool compute_texture_transport; u32 capabilities,compute_resource_flags; } hw; };
struct opengpu_file { int lock; struct opengpu_render_context *context; };
struct drm_device { struct opengpu_device *dev; };
struct drm_file { struct opengpu_file *driver_priv; };
struct drm_syncobj { int unused; };
struct drm_exec { int unused; };
struct drm_gem_dma_object { void *vaddr; };
struct opengpu_sched_job { struct opengpu_device *gpu; void *vm; int type; struct opengpu_buffer shader,texture_records; struct opengpu_kernel_launch kernel; struct opengpu_command_events events; };
static unsigned submitted, dep_count_seen; static u32 root_seen, binding_seen, binding31_seen;
static int alloc_count, fail_alloc, fail_submit;
static struct opengpu_device *dev_get_drvdata(struct opengpu_device *d) { return d; }
static int opengpu_command_events_init(struct opengpu_device *g,struct opengpu_command_events *e,u32 f,u32 w,u32 s)
{ (void)g; e->flags=f; return f||w||s ? -EINVAL : 0; }
static struct drm_syncobj *drm_syncobj_find(struct drm_file *f,u32 s) { (void)f;(void)s; return NULL; }
static void drm_syncobj_put(struct drm_syncobj *s) { (void)s; }
static struct opengpu_render_context *opengpu_context_lookup(struct opengpu_file *f,u32 id) { return id==1?f->context:NULL; }
static struct opengpu_resource_binding *opengpu_binding_lookup(struct opengpu_render_context *c,u32 slot,u32 type)
{ return slot && slot<=OPENGPU_MAX_COMPUTE_TEXTURE_RESOURCE_SLOTS && c->bindings[slot-1] && c->bindings[slot-1]->type==type ? c->bindings[slot-1] : NULL; }
static enum opengpu_backing_kind opengpu_gem_backing_step(const void *o,const void **next)
{ const struct drm_gem_object *g=o; if(g->foreign){*next=g; return OPENGPU_BACKING_FOREIGN;} return OPENGPU_BACKING_LOCAL; }
#include "gem_under_test.inc"
static int opengpu_gem_backings_disjoint(struct drm_gem_object *const *o,unsigned n)
{ for(unsigned i=0;i<n;i++) for(unsigned j=0;j<i;j++) if(o[i]==o[j])return -EINVAL; return 0; }
static struct drm_gem_dma_object *to_drm_gem_dma_obj(struct drm_gem_object *o) { return (struct drm_gem_dma_object *)&o->vaddr; }
static void drm_exec_init(struct drm_exec *e,int flags,int n) { (void)e;(void)flags;(void)n; }
static int drm_exec_prepare_obj(struct drm_exec *e,struct drm_gem_object *o,int n) { (void)e;(void)o;(void)n; return 0; }
static void drm_exec_fini(struct drm_exec *e) { (void)e; }
static int opengpu_wait_reservation(struct drm_gem_object *o,int usage) { (void)o;(void)usage; return 0; }
static int opengpu_buffer_alloc(struct opengpu_device *g,struct opengpu_buffer *b,size_t size)
{ (void)g; ++alloc_count; if (fail_alloc == alloc_count) return -ENOMEM;
  b->cpu=calloc(1,size); b->size=size; b->dma=0x100000 + (u64)alloc_count*0x20000; return b->cpu?0:-ENOMEM; }
static void opengpu_buffer_free(struct opengpu_device *g,struct opengpu_buffer *b) { (void)g; free(b->cpu); b->cpu=NULL; }
static u32 readword(const unsigned char *p) { return (u32)p[0]|(u32)p[1]<<8|(u32)p[2]<<16|(u32)p[3]<<24; }
static int opengpu_sched_job_submit(struct opengpu_file *rf,struct drm_file *f,struct opengpu_render_context *c,
    struct opengpu_sched_job *j,u32 in,struct drm_syncobj *out,const struct opengpu_sched_dep *deps,u32 n,u64 *seq)
{
    (void)rf;(void)f;(void)c;(void)in;(void)out;(void)seq;
    if (fail_submit) return -EIO;
    if (!j->kernel.texture_enabled) {
        assert(n==1 && !j->texture_records.cpu);
        submitted++; opengpu_buffer_free(j->gpu,&j->shader); free(j); return 0;
    }
    assert(j->texture_records.cpu && (n==2 || n==33));
    assert(deps[0].wait_usage==DMA_RESV_USAGE_READ && deps[0].signal_usage==DMA_RESV_USAGE_WRITE);
    for (u32 i=1; i<n; ++i)
        assert(deps[i].wait_usage==DMA_RESV_USAGE_WRITE && deps[i].signal_usage==DMA_RESV_USAGE_READ);
    root_seen=readword(j->texture_records.cpu); binding_seen=readword((const u8 *)j->texture_records.cpu+128);
    if (n==33) binding31_seen=readword((const u8 *)j->texture_records.cpu+128+31*64);
    assert(j->kernel.texture.root==(j->vm ? OPENGPU_COMMAND_VA_BASE + 2 * OPENGPU_COMMAND_VA_STRIDE : j->texture_records.dma));
    assert(j->kernel.texture.context==1);
    dep_count_seen=n; submitted++;
    opengpu_buffer_free(j->gpu,&j->shader); opengpu_buffer_free(j->gpu,&j->texture_records); free(j); return 0;
}
#include "flow_under_test.inc"
int main(void)
{
    u32 code[32] = { (6u<<25)|(5u<<7)|0x0bu, OPENGPU_SHADER_CEASE };
    struct dma_resv reservations[3]={{0},{1},{2}};
    struct drm_gem_object gems[3]={{.resv=&reservations[0],.vaddr=code},{.resv=&reservations[1]},{.resv=&reservations[2]}};
    struct opengpu_resource_binding shader={.object=&gems[0],.dma=0x40000,.size=sizeof(code),.type=OPENGPU_RESOURCE_COMPUTE_SHADER};
    struct opengpu_resource_binding kernarg={.object=&gems[1],.dma=0x50000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_KERNARG};
    struct opengpu_resource_binding texture={.object=&gems[2],.dma=0x60000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_TEXTURE};
    struct opengpu_render_context ctx={0}; struct opengpu_file rf={.context=&ctx};
    struct opengpu_device gpu={.hw={.compute_texture_transport=true,.capabilities=GPU_CAP_UNIFIED_COMMANDS}};
    struct drm_device drm={.dev=&gpu}; struct drm_file file={.driver_priv=&rf};
    struct drm_opengpu_compute launch={.context_id=1,.shader_slot=1,.kernarg_slot=2,.grid={1,1,1},.local={1,1,1}};
    struct opengpu_compute_texture_prepare *p=calloc(1,sizeof(*p)); assert(p);
    ctx.bindings[0]=&shader;ctx.bindings[1]=&kernarg;ctx.bindings[2]=&texture;
    p->views[0]=(struct drm_opengpu_compute_texture_view){.slot=3,.format=3,.width=4,.height=4,.depth=1,.layers=1,.level_count=1};
    p->views[0].levels[0]=(struct drm_opengpu_compute_texture_level){.width=4,.height=4,.depth=1,.layers=1,.row_pitch=16,.block_bytes=4};
    assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1)==0);
    assert(submitted==1 && dep_count_seen==2 && root_seen==0x47585201u && binding_seen==0x47584201u);
    ctx.vm.enabled=true; kernarg.va=0x80000000; texture.va=0xa0000000;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1)==0 && submitted==2);
    ctx.vm.enabled=false;
    texture.object=&gems[1]; texture.dma=kernarg.dma;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1)==-EINVAL && submitted==2);
    texture.object=&gems[2]; texture.dma=0x60000; gems[2].foreign=true;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1)==-EOPNOTSUPP && submitted==2);
    gems[2].foreign=false; alloc_count=0; fail_alloc=2;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1)==-ENOMEM && submitted==2);
    fail_alloc=0; fail_submit=1;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1)==-EIO && submitted==2);
    fail_submit=0; gpu.hw.compute_texture_transport=false;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1)==-EOPNOTSUPP && submitted==2);
    code[0]=OPENGPU_SHADER_CEASE;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0)==0 && submitted==3);
    {
        struct dma_resv wide_resv[32]={{0}};
        struct drm_gem_object wide_gems[32]={{0}};
        struct opengpu_resource_binding wide_bindings[32]={{0}};
        memset(p,0,sizeof(*p)); ctx.vm.enabled=true; kernarg.va=0x80000000;
        gpu.hw.compute_texture_transport=true; code[0]=(6u<<25)|(5u<<7)|0x0bu;
        for (u32 i=0; i<32; ++i) {
            wide_gems[i].resv=&wide_resv[i];
            wide_bindings[i]=(struct opengpu_resource_binding) {
                .object=&wide_gems[i], .dma=0x5000000ULL+i*4096,
                .va=0xa0000000ULL+(i+3ULL)*0x400000, .size=4096,
                .type=OPENGPU_RESOURCE_COMPUTE_TEXTURE,
            };
            ctx.bindings[i+2]=&wide_bindings[i];
            p->views[i]=(struct drm_opengpu_compute_texture_view) {
                .slot=i+3,.format=3,.width=4,.height=4,.depth=1,.layers=1,.level_count=1,
            };
            p->views[i].levels[0]=(struct drm_opengpu_compute_texture_level) {
                .width=4,.height=4,.depth=1,.layers=1,.row_pitch=16,.block_bytes=4,
            };
        }
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,32)==0 && submitted==4);
        assert(dep_count_seen==33 && binding31_seen==0x47584201u);
    }
    {
        ctx.vm.enabled=false; ctx.bindings[2]=&texture; texture.object=&gems[2]; texture.dma=0x60000;
        gpu.hw.compute_resource_flags=OPENGPU_COMPUTE_RESOURCE_WORD_ATOMICS;
        code[0]=(6u<<25)|(5u<<7)|0x0bu; code[1]=0x00808493u; code[2]=0x00100513u;
        code[3]=(10u<<20)|(9u<<15)|(2u<<12)|(11u<<7)|0x2fu; code[4]=OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1)==0 && submitted==5);
        gpu.hw.compute_resource_flags=0;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1)==-EINVAL && submitted==5);
        gpu.hw.compute_resource_flags=OPENGPU_COMPUTE_RESOURCE_WORD_ATOMICS;
        texture.object=&gems[1]; texture.dma=kernarg.dma;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1)==-EINVAL && submitted==5);
    }
    free(p); return 0;
}
