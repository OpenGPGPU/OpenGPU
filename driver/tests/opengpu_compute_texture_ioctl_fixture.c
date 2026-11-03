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
#include "opengpu_storage_command.h"
#include "opengpu_compute_image_descriptor.h"
#include "opengpu_shader_image_command.h"
#include "opengpu_storage_descriptor.h"
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
struct opengpu_render_context { struct { bool enabled; } vm; struct opengpu_resource_binding *bindings[OPENGPU_MAX_RESOURCE_BINDINGS]; };
struct opengpu_sched_dep { struct drm_gem_object *object; int wait_usage, signal_usage; };
struct opengpu_command_events { u32 flags; };
struct opengpu_buffer { void *cpu; u64 dma; size_t size; };
struct opengpu_kernel_launch { u64 kernel_pc,kernarg; u32 grid[3],local[3]; bool texture_enabled,storage_enabled,image_enabled; struct opengpu_compute_texture_command_context texture; struct opengpu_storage_command_context storage; struct opengpu_image_command_context image; };
struct opengpu_device { struct { bool compute_texture_transport,compute_storage_transport,compute_image_transport; u32 capabilities,compute_resource_flags; } hw; };
struct opengpu_file { int lock; struct opengpu_render_context *context; };
struct drm_device { struct opengpu_device *dev; };
struct drm_file { struct opengpu_file *driver_priv; };
struct drm_syncobj { int unused; };
struct drm_exec { int unused; };
struct drm_gem_dma_object { void *vaddr; };
struct opengpu_sched_job { struct opengpu_device *gpu; void *vm; int type; struct opengpu_buffer shader,texture_records,storage_records,image_records; struct opengpu_kernel_launch kernel; struct opengpu_command_events events; };
static const void *memchr_inv(const void *ptr, int value, size_t size)
{ const u8 *p=ptr; while (size--) { if (*p != (u8)value) return p; ++p; } return NULL; }
static unsigned submitted, dep_count_seen; static u32 root_seen, binding_seen, binding31_seen;
static int alloc_count, fail_alloc, fail_submit, storage_read_only;
static u32 expected_storage_limit=0xd0001000u;
static struct opengpu_device *dev_get_drvdata(struct opengpu_device *d) { return d; }
static int opengpu_command_events_init(struct opengpu_device *g,struct opengpu_command_events *e,u32 f,u32 w,u32 s)
{ (void)g; e->flags=f; return f||w||s ? -EINVAL : 0; }
static struct drm_syncobj *drm_syncobj_find(struct drm_file *f,u32 s) { (void)f;(void)s; return NULL; }
static void drm_syncobj_put(struct drm_syncobj *s) { (void)s; }
static struct opengpu_render_context *opengpu_context_lookup(struct opengpu_file *f,u32 id) { return id==1?f->context:NULL; }
static struct opengpu_resource_binding *opengpu_binding_lookup(struct opengpu_render_context *c,u32 slot,u32 type)
{ return slot && slot<=OPENGPU_MAX_RESOURCE_BINDINGS && c->bindings[slot-1] && c->bindings[slot-1]->type==type ? c->bindings[slot-1] : NULL; }
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
    if (j->kernel.image_enabled) {
        assert(n == 2 && j->vm && j->image_records.cpu && j->image_records.size == 1024);
        assert(deps[1].wait_usage == (storage_read_only ? DMA_RESV_USAGE_WRITE : DMA_RESV_USAGE_READ));
        assert(deps[1].signal_usage == (storage_read_only ? DMA_RESV_USAGE_READ : DMA_RESV_USAGE_WRITE));
        assert(readword(j->image_records.cpu) == 0x47495501u);
        assert(readword((u8 *)j->image_records.cpu + 22*4) == 0xd0000000u);
        assert(readword((u8 *)j->image_records.cpu + 256) == 0x47495501u);
        assert(readword((u8 *)j->image_records.cpu + 256 + 7*4) == 0);
        assert(j->kernel.image.base == OPENGPU_COMMAND_VA_BASE + 4 * OPENGPU_COMMAND_VA_STRIDE);
        submitted++; opengpu_buffer_free(j->gpu,&j->shader); opengpu_buffer_free(j->gpu,&j->image_records); free(j); return 0;
    }
    if (j->kernel.storage_enabled) {
        assert((n == 2 || n == 3 || n == 5 || n == 37) && j->vm && j->storage_records.cpu && j->storage_records.size == 256);
        assert(deps[1].wait_usage == (storage_read_only ? DMA_RESV_USAGE_WRITE : DMA_RESV_USAGE_READ));
        assert(deps[1].signal_usage == (storage_read_only ? DMA_RESV_USAGE_READ : DMA_RESV_USAGE_WRITE));
        assert(readword(j->storage_records.cpu) == OPENGPU_STORAGE_DESCRIPTOR_MAGIC);
        assert(readword((const u8 *)j->storage_records.cpu + 32) == 1);
        assert(readword((const u8 *)j->storage_records.cpu + 36) == (storage_read_only ? 0u : 1u));
        assert(readword((const u8 *)j->storage_records.cpu + 40) == 0xd0000000u);
        assert(readword((const u8 *)j->storage_records.cpu + 44) == expected_storage_limit);
        assert(j->kernel.storage.base == OPENGPU_COMMAND_VA_BASE + 3 * OPENGPU_COMMAND_VA_STRIDE);
        if (n == 37) {
            assert(j->kernel.texture_enabled && j->texture_records.cpu);
            assert(readword((const u8 *)j->texture_records.cpu + 128 + 31*64) == 0x47584201u);
            for (u32 i=1; i<=4; ++i)
                assert(deps[i].wait_usage == DMA_RESV_USAGE_READ && deps[i].signal_usage == DMA_RESV_USAGE_WRITE);
            for (u32 i=5; i<n; ++i)
                assert(deps[i].wait_usage == DMA_RESV_USAGE_WRITE && deps[i].signal_usage == DMA_RESV_USAGE_READ);
        }
        if (n == 2 && j->kernel.texture_enabled) {
            assert(j->texture_records.cpu && deps[0].object != deps[1].object);
            assert(readword(j->texture_records.cpu) == 0x47585201u);
        }
        if (n == 3) {
            assert(j->kernel.texture_enabled && j->texture_records.cpu);
            assert(deps[2].wait_usage == DMA_RESV_USAGE_WRITE && deps[2].signal_usage == DMA_RESV_USAGE_READ);
            assert(readword(j->texture_records.cpu) == 0x47585201u);
        }
        if (n == 5) for (u32 i=1; i<4; ++i) {
            assert(readword((const u8 *)j->storage_records.cpu + i*64) == OPENGPU_STORAGE_DESCRIPTOR_MAGIC);
            assert(readword((const u8 *)j->storage_records.cpu + i*64 + 24) == i);
            assert(deps[i+1].wait_usage == DMA_RESV_USAGE_READ && deps[i+1].signal_usage == DMA_RESV_USAGE_WRITE);
        }
        submitted++; opengpu_buffer_free(j->gpu,&j->shader); opengpu_buffer_free(j->gpu,&j->storage_records); opengpu_buffer_free(j->gpu,&j->texture_records); free(j); return 0;
    }
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
    assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,NULL)==0);
    assert(submitted==1 && dep_count_seen==2 && root_seen==0x47585201u && binding_seen==0x47584201u);
    ctx.vm.enabled=true; kernarg.va=0x80000000; texture.va=0xa0000000;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,NULL)==0 && submitted==2);
    ctx.vm.enabled=false;
    texture.object=&gems[1]; texture.dma=kernarg.dma;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,NULL)==-EINVAL && submitted==2);
    texture.object=&gems[2]; texture.dma=0x60000; gems[2].foreign=true;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,NULL)==-EOPNOTSUPP && submitted==2);
    gems[2].foreign=false; alloc_count=0; fail_alloc=2;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,NULL)==-ENOMEM && submitted==2);
    fail_alloc=0; fail_submit=1;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,NULL)==-EIO && submitted==2);
    fail_submit=0; gpu.hw.compute_texture_transport=false;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,NULL)==-EOPNOTSUPP && submitted==2);
    code[0]=OPENGPU_SHADER_CEASE;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,NULL)==0 && submitted==3);
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
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,32,NULL,NULL)==0 && submitted==4);
        assert(dep_count_seen==33 && binding31_seen==0x47584201u);
    }
    {
        ctx.vm.enabled=false; ctx.bindings[2]=&texture; texture.object=&gems[2]; texture.dma=0x60000;
        gpu.hw.compute_resource_flags=OPENGPU_COMPUTE_RESOURCE_WORD_ATOMICS;
        code[0]=(6u<<25)|(5u<<7)|0x0bu; code[1]=0x00808493u; code[2]=0x00100513u;
        code[3]=(10u<<20)|(9u<<15)|(2u<<12)|(11u<<7)|0x2fu; code[4]=OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,NULL)==0 && submitted==5);
        gpu.hw.compute_resource_flags=0;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,NULL)==-EINVAL && submitted==5);
        gpu.hw.compute_resource_flags=OPENGPU_COMPUTE_RESOURCE_WORD_ATOMICS;
        texture.object=&gems[1]; texture.dma=kernarg.dma;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,NULL)==-EINVAL && submitted==5);
    }
    {
        struct opengpu_compute_storage_prepare storage={0};
        struct opengpu_resource_binding backing={.object=&gems[2],.dma=0x60000,
            .va=0xd0000000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_STORAGE};
        storage.bindings[0]=(struct drm_opengpu_compute_storage_binding){13,3};
        ctx.bindings[12]=&backing; ctx.vm.enabled=true; kernarg.va=0x80000000;
        gpu.hw.compute_storage_transport=true; launch.local[0]=1;
        code[0]=0xc100f057u;
        code[1]=(23u<<26)|(1u<<25)|(3u<<12)|(2u<<7)|0x57u;
        code[2]=(23u<<26)|(1u<<25)|(7u<<15)|(3u<<12)|(3u<<7)|0x57u;
        code[3]=(23u<<26)|(1u<<25)|(3u<<12)|(4u<<7)|0x57u;
        code[4]=(14u<<26)|(1u<<25)|(3u<<15)|0x2bu;
        code[5]=(16u<<26)|(1u<<25)|(4u<<20)|(2u<<15)|0x2bu;
        code[6]=(15u<<26)|(1u<<25)|(4u<<20)|(2u<<15)|(5u<<7)|0x2bu;
        code[7]=0x02006027u|(1u<<15)|(5u<<7);
        code[8]=0x00808493u; code[9]=0x00100513u;
        code[10]=(10u<<20)|(9u<<15)|(2u<<12)|(11u<<7)|0x2fu;
        code[11]=OPENGPU_SHADER_CEASE;
        gpu.hw.compute_resource_flags=OPENGPU_COMPUTE_RESOURCE_WORD_ATOMICS;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&storage,NULL)==0 && submitted==6);
        gpu.hw.compute_resource_flags=0;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&storage,NULL)==-EINVAL && submitted==6);
        gpu.hw.compute_resource_flags=OPENGPU_COMPUTE_RESOURCE_WORD_ATOMICS;
        storage.bindings[0].access=4;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&storage,NULL)==-EINVAL && submitted==6);
        storage.bindings[0].access=3; backing.object=&gems[1];
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&storage,NULL)==-EINVAL && submitted==6);
        backing.object=&gems[2]; gems[2].foreign=true;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&storage,NULL)==-EOPNOTSUPP && submitted==6);
        gems[2].foreign=false; ctx.vm.enabled=false;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&storage,NULL)==-EOPNOTSUPP && submitted==6);
        ctx.vm.enabled=true; backing.va++;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&storage,NULL)==-EINVAL && submitted==6);
        backing.va--; fail_alloc=alloc_count+2;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&storage,NULL)==-ENOMEM && submitted==6);
        fail_alloc=0; fail_submit=1;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&storage,NULL)==-EIO && submitted==6);
        fail_submit=0; storage_read_only=1; storage.bindings[0].access=OPENGPU_COMPUTE_STORAGE_READ;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&storage,NULL)==0 && submitted==7);
        storage.bindings[1]=storage.bindings[0];
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&storage,NULL)==-EINVAL && submitted==7);
        {
            struct dma_resv extra_resv[3]={{0}};
            struct drm_gem_object extra_gems[3]={{0}};
            struct opengpu_resource_binding extra[3]={{0}};
            for (u32 i=0;i<3;i++) {
                extra_gems[i].resv=&extra_resv[i];
                extra[i]=(struct opengpu_resource_binding){.object=&extra_gems[i],
                    .dma=0x70000+i*0x10000,.va=0xd8000000u+i*0x8000000u,
                    .size=4096,.type=OPENGPU_RESOURCE_COMPUTE_STORAGE};
                ctx.bindings[i+13]=&extra[i];
                storage.bindings[i+1]=(struct drm_opengpu_compute_storage_binding){i+14,3};
            }
            assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&storage,NULL)==0 && submitted==8);
        }
        memset(&storage.bindings[1],0,3*sizeof(storage.bindings[0]));
        backing.dma=0x08000000u; backing.size=128u*1024u*1024u;
        expected_storage_limit=0xd8000000u;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&storage,NULL)==0 && submitted==9);
        backing.size+=64;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&storage,NULL)==-EINVAL && submitted==9);
    }
    {
        struct dma_resv backing_resv={0};
        struct drm_gem_object backing_gem={.resv=&backing_resv};
        struct opengpu_resource_binding backing={.object=&backing_gem,.dma=0x70000,
            .va=0xd0000000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_STORAGE};
        struct opengpu_compute_storage_prepare storage={0};
        memset(p,0,sizeof(*p)); ctx.bindings[12]=&backing; ctx.bindings[2]=&texture;
        texture=(struct opengpu_resource_binding){.object=&gems[2],.dma=0x60000,
            .va=0xa0000000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_TEXTURE};
        p->views[0]=(struct drm_opengpu_compute_texture_view){.slot=3,.format=3,.width=4,.height=4,.depth=1,.layers=1,.level_count=1};
        p->views[0].levels[0]=(struct drm_opengpu_compute_texture_level){.width=4,.height=4,.depth=1,.layers=1,.row_pitch=16,.block_bytes=4};
        storage.bindings[0]=(struct drm_opengpu_compute_storage_binding){13,3};
        expected_storage_limit=0xd0001000u; storage_read_only=0; ctx.vm.enabled=true;
        code[0]=(6u<<25)|(5u<<7)|0x0bu;
        code[1]=0xc100f057u;
        code[2]=(14u<<26)|(1u<<25)|(1u<<15)|0x2bu;
        code[3]=(16u<<26)|(1u<<25)|(1u<<20)|(1u<<15)|0x2bu;
        code[4]=0x00808493u; code[5]=0x00100513u;
        code[6]=(10u<<20)|(9u<<15)|(2u<<12)|(11u<<7)|0x2fu;
        code[7]=OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==0 && submitted==10);
        p->views[1]=p->views[0];
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,2,&storage,NULL)==0 && submitted==11);
        gpu.hw.compute_texture_transport=false;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==-EOPNOTSUPP && submitted==11);
        gpu.hw.compute_texture_transport=true; gpu.hw.compute_storage_transport=false;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==-EOPNOTSUPP && submitted==11);
        gpu.hw.compute_storage_transport=true;
        backing.object=&gems[2];
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==-EINVAL && submitted==11);
        backing.object=&backing_gem; backing_gem.resv=gems[2].resv;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==-EINVAL && submitted==11);
        backing_gem.resv=&backing_resv; backing.dma=texture.dma;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==-EINVAL && submitted==11);
        backing.dma=0x70000; gems[2].foreign=true;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==-EOPNOTSUPP && submitted==11);
        gems[2].foreign=false; gpu.hw.compute_resource_flags=0;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==-EINVAL && submitted==11);
        gpu.hw.compute_resource_flags=OPENGPU_COMPUTE_RESOURCE_WORD_ATOMICS;
        fail_alloc=alloc_count+3;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==-ENOMEM && submitted==11);
        fail_alloc=0; fail_submit=1;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==-EIO && submitted==11);
    }
    {
        struct dma_resv tr[32]={{0}}, sr[4]={{0}};
        struct drm_gem_object tg[32]={{0}}, sg[4]={{0}};
        struct opengpu_resource_binding tb[32]={{0}}, sb[4]={{0}};
        struct opengpu_compute_storage_prepare storage={0};
        memset(p,0,sizeof(*p)); fail_submit=0; storage_read_only=0;
        for (u32 i=0;i<32;i++) {
            tg[i].resv=&tr[i];
            tb[i]=(struct opengpu_resource_binding){.object=&tg[i],.dma=0x10000000ULL+i*4096,
                .va=0xa0000000ULL+(i+3ULL)*0x400000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_TEXTURE};
            ctx.bindings[i+2]=&tb[i];
            p->views[i]=(struct drm_opengpu_compute_texture_view){.slot=i+3,.format=3,.width=4,.height=4,.depth=1,.layers=1,.level_count=1};
            p->views[i].levels[0]=(struct drm_opengpu_compute_texture_level){.width=4,.height=4,.depth=1,.layers=1,.row_pitch=16,.block_bytes=4};
        }
        for (u32 i=0;i<4;i++) {
            sg[i].resv=&sr[i];
            sb[i]=(struct opengpu_resource_binding){.object=&sg[i],.dma=0x20000000ULL+i*4096,
                .va=0xd0000000ULL+i*0x08000000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_STORAGE};
            ctx.bindings[i+34]=&sb[i];
            storage.bindings[i]=(struct drm_opengpu_compute_storage_binding){i+35,3};
        }
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,32,&storage,NULL)==0 && submitted==12);
    }
    {
        struct opengpu_compute_image_prepare images={0};
        struct opengpu_resource_binding backing={.object=&gems[2],.dma=0x60000,
            .va=0xd0000000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_STORAGE};
        ctx.bindings[12]=&backing; ctx.vm.enabled=true; kernarg.va=0x80000000;
        gpu.hw.compute_image_transport=true; launch.local[0]=1;
        fail_submit=0; storage_read_only=0;
        images.views[0]=(struct drm_opengpu_compute_image_view){.slot=13,
            .flags=OPENGPU_COMPUTE_IMAGE_READ|OPENGPU_COMPUTE_IMAGE_WRITE,
            .storage_format=OPENGPU_TEXTURE_FORMAT_R32UI,.image_format=OPENGPU_TEXTURE_FORMAT_R32UI,
            .width=4,.height=4,.depth=1,.layers=1,.row_pitch=16};
        code[0]=0xc100f057u;
        code[1]=(8u<<26)|(1u<<25)|(1u<<20)|(1u<<15)|0x2bu;
        code[2]=(8u<<26)|(1u<<25)|(1u<<20)|(1u<<15)|(1u<<12)|0x2bu;
        code[3]=(9u<<26)|(1u<<25)|(1u<<20)|(1u<<15)|(2u<<7)|0x2bu;
        code[4]=OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==0 && submitted==13);
        storage_read_only=1; images.views[0].flags=OPENGPU_COMPUTE_IMAGE_READ;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==0 && submitted==14);
        code[4]=0x00000463u; code[5]=0x0000000bu; code[6]=OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EINVAL && submitted==14);
        code[4]=OPENGPU_SHADER_CEASE;
        images.views[0].pad=1;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EINVAL && submitted==14);
        images.views[0].pad=0; images.views[0].row_pitch=15;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EINVAL && submitted==14);
        images.views[0].row_pitch=16; gems[2].foreign=true;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EOPNOTSUPP && submitted==14);
        gems[2].foreign=false; images.views[1]=images.views[0];
        images.views[1].offset=16; images.views[1].width=2; images.views[1].height=2;
        assert(opengpu_compute_prepare_images(&images,&ctx,&shader,&kernarg,1)==0);
        assert(images.dep_count==2 && images.records[1][6]==1 &&
               images.records[1][25]==0xd0000010u && images.records[1][26]==2 &&
               images.deps[1].wait_usage==DMA_RESV_USAGE_WRITE &&
               images.deps[1].signal_usage==DMA_RESV_USAGE_READ);
        images.views[1].offset=4095;
        assert(opengpu_compute_prepare_images(&images,&ctx,&shader,&kernarg,1)==-EINVAL);
        images.views[1].offset=16;
        images.views[1].flags |= OPENGPU_COMPUTE_IMAGE_WRITE;
        assert(opengpu_compute_prepare_images(&images,&ctx,&shader,&kernarg,1)==0);
        assert(images.dep_count==2 && images.deps[1].wait_usage==DMA_RESV_USAGE_READ &&
               images.deps[1].signal_usage==DMA_RESV_USAGE_WRITE);
        images.views[0].flags |= OPENGPU_COMPUTE_IMAGE_WRITE;
        images.views[1].flags = OPENGPU_COMPUTE_IMAGE_READ;
        assert(opengpu_compute_prepare_images(&images,&ctx,&shader,&kernarg,1)==0);
        assert(images.dep_count==2 && images.deps[1].signal_usage==DMA_RESV_USAGE_WRITE);
        images.views[0].flags = OPENGPU_COMPUTE_IMAGE_READ;
        images.views[2]=images.views[0]; images.views[3]=images.views[0];
        images.views[3].flags |= OPENGPU_COMPUTE_IMAGE_WRITE;
        assert(opengpu_compute_prepare_images(&images,&ctx,&shader,&kernarg,1)==0);
        assert(images.dep_count==2 && images.records[2][6]==2 && images.records[3][6]==3 &&
               images.deps[1].wait_usage==DMA_RESV_USAGE_READ &&
               images.deps[1].signal_usage==DMA_RESV_USAGE_WRITE);
        memset(&images.views[2],0,sizeof(images.views[2]));
        memset(&images.views[3],0,sizeof(images.views[3]));
        {
            struct opengpu_resource_binding alternate=backing;
            struct dma_resv independent_resv={0};
            struct drm_gem_object other={.resv=gems[2].resv};
            alternate.va+=0x08000000u; ctx.bindings[13]=&alternate;
            images.views[1].slot=14;
            assert(opengpu_compute_prepare_images(&images,&ctx,&shader,&kernarg,1)==-EINVAL);
            alternate.object=&other; alternate.dma+=4096;
            assert(opengpu_compute_prepare_images(&images,&ctx,&shader,&kernarg,1)==-EINVAL);
            other.resv=&independent_resv; alternate.dma=backing.dma;
            assert(opengpu_compute_prepare_images(&images,&ctx,&shader,&kernarg,1)==-EINVAL);
            ctx.bindings[13]=NULL;
        }
        memset(&images.views[1],0,sizeof(images.views[1])); images.views[1].width=1;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EINVAL && submitted==14);
        memset(&images.views[1],0,sizeof(images.views[1])); ctx.vm.enabled=false;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EOPNOTSUPP && submitted==14);
        ctx.vm.enabled=true; backing.object=&gems[1];
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EINVAL && submitted==14);
        backing.object=&gems[2]; fail_alloc=alloc_count+2;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-ENOMEM && submitted==14);
        fail_alloc=0; fail_submit=1;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EIO && submitted==14);
        fail_submit=0; gpu.hw.compute_image_transport=false;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EOPNOTSUPP && submitted==14);
    }
    {
        struct dma_resv shared_resv={0};
        struct drm_gem_object shared={.resv=&shared_resv};
        struct opengpu_resource_binding tex={.object=&shared,.dma=0x70000,
            .va=0xa0000000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_TEXTURE};
        struct opengpu_resource_binding store={.object=&shared,.dma=0x70000,
            .va=0xd0000000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_STORAGE};
        struct opengpu_compute_storage_prepare storage={0};
        memset(p,0,sizeof(*p)); fail_submit=0; storage_read_only=0;
        ctx.bindings[0]=&shader;ctx.bindings[1]=&kernarg;ctx.bindings[2]=&tex;ctx.bindings[12]=&store;
        ctx.vm.enabled=true;kernarg.va=0x80000000;expected_storage_limit=0xd0001000u;
        p->views[0]=(struct drm_opengpu_compute_texture_view){.slot=3,.format=3,.width=4,.height=4,.depth=1,.layers=1,.level_count=1};
        p->views[0].levels[0]=(struct drm_opengpu_compute_texture_level){.width=4,.height=4,.depth=1,.layers=1,.row_pitch=16,.block_bytes=4};
        storage.bindings[0]=(struct drm_opengpu_compute_storage_binding){13,3};
        code[0]=(6u<<25)|(5u<<7)|0x0bu;code[1]=OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==0 && submitted==15);
        p->views[1]=p->views[0];
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,2,&storage,NULL)==0 && submitted==16);
        storage_read_only=1;storage.bindings[0].access=OPENGPU_COMPUTE_STORAGE_READ;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,2,&storage,NULL)==0 && submitted==17);
        tex.size=2048;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==-EINVAL && submitted==17);
        tex.size=4096;tex.dma+=64;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==-EINVAL && submitted==17);
        tex.dma-=64;shared.foreign=true;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==-EOPNOTSUPP && submitted==17);
    }
    free(p); return 0;
}
