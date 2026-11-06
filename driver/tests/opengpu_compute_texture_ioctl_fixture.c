/* Actual compute ioctl core, with mock GEM, reservation and job submission. */
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "opengpu_compute_texture_bound_view.h"
#include "opengpu_compute_features.h"
#include "opengpu_staging_validator.h"
#include "opengpu_backing_identity.h"
#include "opengpu_atomic_counter_command.h"
#include "opengpu_compute_texture_command.h"
#include "opengpu_compute_uniform_command.h"
#include "opengpu_compute_uniform_records.h"
#include "opengpu_compute_counter_source.h"
#include "opengpu_compute_indirect_source.h"
#include "opengpu_storage_command.h"
#include "opengpu_compute_image_descriptor.h"
#include "opengpu_shader_image_command.h"
#include "opengpu_storage_descriptor.h"
#include "opengpu_shader_validator.h"
#include "opengpu_kernarg_va.h"
#include "opengpu_drm.h"
#include "../../userspace/opengpu_buffer_texture.h"
typedef uint64_t u64; typedef unsigned char u8;
#define U32_MAX UINT32_MAX
#define U16_MAX UINT16_MAX
#define upper_32_bits(v) ((uint64_t)(v)>>32)
#define check_mul_overflow(a,b,p) __builtin_mul_overflow(a,b,p)
#define check_add_overflow(a,b,p) __builtin_add_overflow(a,b,p)
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
#define OPENGPU_DRAW_WAIT_MS 30000
#define kzalloc(n,f) calloc(1,n)
#define kvzalloc(n,f) calloc(1,n)
#define kvfree(p) free(p)
#define u64_to_user_ptr(v) ((void *)(uintptr_t)(v))
#define kfree(p) free(p)
#define mutex_lock(p) ((void)(p))
#define mutex_unlock(p) ((void)(p))
#define dma_rmb() ((void)0)
struct dma_resv { int id; };
struct drm_gem_object { struct dma_resv *resv; bool foreign; void *vaddr; u64 dma_addr; size_t size; void *import_attach; };
struct opengpu_resource_binding { struct drm_gem_object *object; u64 dma, va, size, offset; u32 type; };
struct opengpu_render_context { struct { bool enabled; } vm; struct opengpu_resource_binding *bindings[OPENGPU_MAX_RESOURCE_BINDINGS]; };
struct opengpu_sched_dep { struct drm_gem_object *object; int wait_usage, signal_usage; };
struct opengpu_command_events { u32 flags; };
struct opengpu_buffer { void *cpu; u64 dma; size_t size; };
struct opengpu_kernel_launch { u32 indirect_base,indirect_bytes,indirect_offset; u64 kernel_pc,kernarg; u32 grid[3],local[3]; bool texture_enabled,storage_enabled,image_enabled,counter_enabled,uniform_enabled,indirect_enabled; struct opengpu_compute_texture_command_context texture; struct opengpu_storage_command_context storage; struct opengpu_image_command_context image; struct opengpu_atomic_counter_command_context counter; struct opengpu_compute_uniform_command_context uniform; };
struct opengpu_device { struct { bool compute_texture_transport,compute_storage_transport,compute_image_transport,compute_counter_transport,compute_uniform_transport,compute_indirect_transport; u32 capabilities,compute_resource_flags; struct opengpu_compute_local_limits compute_local_limits; struct opengpu_compute_shared_limits compute_shared_limits; } hw; };
struct opengpu_file { int lock; struct opengpu_render_context *context; };
struct drm_device { struct opengpu_device *dev; };
struct drm_file { struct opengpu_file *driver_priv; };
struct drm_syncobj { int unused; };
struct drm_exec { int unused; };
struct drm_gem_dma_object { void *vaddr; u64 dma_addr; };
struct dma_fence { int unused; };
/* This direct-only fixture has no reservation fences. The composed uniform
 * fixture supplies the real indirect producer/error/ownership cases. */
struct dma_resv_iter { int unused; };
#define dma_resv_for_each_fence(c,r,u,f) \
    for((void)(c),(void)(r),(void)(u),(f)=NULL;(f);(f)=NULL)
static int dma_fence_get_status(struct dma_fence *f) { (void)f;return 1; }
static struct drm_gem_object *drm_gem_object_lookup(struct drm_file *f,u32 h) { (void)f;(void)h;return NULL; }
static void drm_gem_object_put(struct drm_gem_object *o) { (void)o; }
static int drm_syncobj_find_fence(struct drm_file *f,u32 h,u64 p,u64 flags,struct dma_fence **out)
{ (void)f;(void)h;(void)p;(void)flags;(void)out;return -ENOENT; }
static int opengpu_hw_fence_wait(struct opengpu_device *g,struct dma_fence *f,unsigned t,bool i)
{ (void)g;(void)f;(void)t;(void)i;return 0; }
static void dma_fence_put(struct dma_fence *f) { (void)f; }
struct opengpu_sched_job { struct opengpu_device *gpu; void *vm; int type; struct opengpu_buffer shader,texture_records,storage_records,image_records,counter_records,uniform_snapshot,indirect_snapshot; struct opengpu_resource_binding counter_source; struct opengpu_kernel_launch kernel; struct opengpu_command_events events; };
static int fail_copy;
static int copy_from_user(void *dst, const void *src, size_t size)
{ if (fail_copy) return 1; memcpy(dst,src,size); return 0; }
static const void *memchr_inv(const void *ptr, int value, size_t size)
{ const u8 *p=ptr; while (size--) { if (*p != (u8)value) return p; ++p; } return NULL; }
static unsigned submitted, dep_count_seen; static u32 root_seen, binding_seen, binding31_seen;
static unsigned buffer_texture_expected, buffer_image_expected, buffer_mixed;
static u32 buffer_format_expected, buffer_count_expected;
static u64 buffer_bytes_expected, buffer_base_expected;
static int alloc_live, alloc_count, fail_alloc, fail_submit, storage_read_only, second_image, shared_image_storage;
static u32 expected_storage_limit=0xd0001000u;
static struct opengpu_device *dev_get_drvdata(struct opengpu_device *d) { return d; }
static int opengpu_command_events_init(struct opengpu_device *g,struct opengpu_command_events *e,u32 f,u32 w,u32 s)
{ (void)g; e->flags=f; return f||w||s ? -EINVAL : 0; }
static struct drm_syncobj *drm_syncobj_find(struct drm_file *f,u32 s) { (void)f;(void)s; return NULL; }
static void drm_syncobj_put(struct drm_syncobj *s) { (void)s; }
static struct opengpu_render_context *opengpu_context_lookup(struct opengpu_file *f,u32 id) { return id==1?f->context:NULL; }
#include "opengpu_storage_slot.h"
#include "binding_lookup_under_test.inc"
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
  b->cpu=calloc(1,size); b->size=size; b->dma=0x100000 + (u64)alloc_count*0x20000; if(b->cpu)++alloc_live; return b->cpu?0:-ENOMEM; }
static void opengpu_buffer_free(struct opengpu_device *g,struct opengpu_buffer *b) { (void)g; if(b->cpu){assert(alloc_live>0);--alloc_live;} free(b->cpu); b->cpu=NULL; }
static u32 readword(const unsigned char *p) { return (u32)p[0]|(u32)p[1]<<8|(u32)p[2]<<16|(u32)p[3]<<24; }
static int opengpu_sched_job_submit(struct opengpu_file *rf,struct drm_file *f,struct opengpu_render_context *c,
    struct opengpu_sched_job *j,u32 in,struct drm_syncobj *out,const struct opengpu_sched_dep *deps,u32 n,u64 *seq)
{
    (void)rf;(void)f;(void)c;(void)in;(void)out;(void)seq;
    if (fail_submit) return -EIO;
    if (buffer_texture_expected) {
        const u8 *r=j->texture_records.cpu;
        const u8 *image=r+128+32*64+31*128, *level=r+128+32*256+31*2048;
        assert(readword(r)==0x47585201u && readword(r+7*4)==32);
        assert(readword(image+7*4)==5 && readword(image+8*4)==buffer_format_expected);
        assert(readword(image+11*4)==1 && readword(image+28*4)==buffer_count_expected);
        assert(((u64)readword(image+30*4)<<32|readword(image+29*4))==buffer_bytes_expected);
        assert(readword(level+12*4)==buffer_base_expected && readword(level+13*4)==1);
        assert(readword(level+25*4)==buffer_count_expected);
        assert(((u64)readword(level+27*4)<<32|readword(level+26*4))==buffer_bytes_expected);
        assert(!!readword(r+14*4)==!!buffer_mixed);
        if (buffer_mixed) assert(readword(r+128+32*192)==0x47585301u);
        for(unsigned i=0;i<64;++i) assert(!r[128+32*192+31*64+i]);
        assert(n==2 && deps[1].wait_usage==DMA_RESV_USAGE_WRITE && deps[1].signal_usage==DMA_RESV_USAGE_READ);
    }
    if (buffer_image_expected) {
        const u8 *r=j->image_records.cpu;
        assert(readword(r+20*4)==5 && readword(r+26*4)==1);
        assert(readword(r+25*4)==buffer_base_expected && readword(r+38*4)==buffer_count_expected);
        assert(((u64)readword(r+40*4)<<32|readword(r+39*4))==buffer_bytes_expected);
        assert(readword(r+30*4)==0 && readword(r+10*4)==1);
        assert(readword(r+11*4)==!storage_read_only);
    }
    if (j->kernel.counter_enabled) {
        assert(j->vm && j->counter_records.cpu && j->counter_records.size==64 && n>=2);
        assert(readword(j->counter_records.cpu)==OPENGPU_STORAGE_DESCRIPTOR_MAGIC);
        assert(readword((const u8 *)j->counter_records.cpu+40)==0xd0000004u);
        assert(readword((const u8 *)j->counter_records.cpu+44)==0xd0000024u);
        assert(j->kernel.counter.base==OPENGPU_COMMAND_VA_BASE+5*OPENGPU_COMMAND_VA_STRIDE);
        assert(deps[n-1].wait_usage==DMA_RESV_USAGE_READ && deps[n-1].signal_usage==DMA_RESV_USAGE_WRITE);
        submitted++;
        opengpu_buffer_free(j->gpu,&j->shader); opengpu_buffer_free(j->gpu,&j->counter_records);
        opengpu_buffer_free(j->gpu,&j->storage_records); opengpu_buffer_free(j->gpu,&j->image_records);
        opengpu_buffer_free(j->gpu,&j->texture_records); free(j); return 0;
    }
    if (j->kernel.image_enabled) {
        assert((n == 2 || n == 3 || n == 4) && j->vm && j->image_records.cpu && j->image_records.size == 1024);
        assert(deps[1].wait_usage == (storage_read_only ? DMA_RESV_USAGE_WRITE : DMA_RESV_USAGE_READ));
        assert(deps[1].signal_usage == (storage_read_only ? DMA_RESV_USAGE_READ : DMA_RESV_USAGE_WRITE));
        assert(readword(j->image_records.cpu) == 0x47495501u);
        assert(readword((u8 *)j->image_records.cpu + 22*4) == 0xd0000000u);
        assert(readword((u8 *)j->image_records.cpu + 256) == 0x47495501u);
        if (!second_image) assert(readword((u8 *)j->image_records.cpu + 256 + 7*4) == 0);
        assert(j->kernel.image.base == OPENGPU_COMMAND_VA_BASE + 4 * OPENGPU_COMMAND_VA_STRIDE);
        if (j->kernel.storage_enabled) {
            assert((n == 2 || n == 3 || n == 4) && j->storage_records.cpu);
            assert(readword(j->storage_records.cpu) == OPENGPU_STORAGE_DESCRIPTOR_MAGIC);
            u32 index = shared_image_storage ? 1 : n-1;
            assert(deps[index].wait_usage==(storage_read_only ? DMA_RESV_USAGE_WRITE : DMA_RESV_USAGE_READ));
            assert(deps[index].signal_usage==(storage_read_only ? DMA_RESV_USAGE_READ : DMA_RESV_USAGE_WRITE));
        }
        if (j->kernel.texture_enabled) {
            assert(j->texture_records.cpu && n >= 2);
            if (n >= 3 && (!j->kernel.storage_enabled || n == 4 || shared_image_storage))
                assert(deps[2].wait_usage==DMA_RESV_USAGE_WRITE && deps[2].signal_usage==DMA_RESV_USAGE_READ);
            assert(readword(j->texture_records.cpu)==0x47585201u);
        }
        submitted++; opengpu_buffer_free(j->gpu,&j->shader); opengpu_buffer_free(j->gpu,&j->image_records); opengpu_buffer_free(j->gpu,&j->storage_records); opengpu_buffer_free(j->gpu,&j->texture_records); free(j); return 0;
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
static void buffer_ioctl_checks(void)
{
    u32 code[8]={(31u<<20)|(5u<<7)|0x13u,(6u<<25)|(5u<<15)|(6u<<7)|0x0bu,OPENGPU_SHADER_CEASE};
    struct dma_resv reservations[4]={{0}};
    struct drm_gem_object gems[4]={{.resv=&reservations[0],.vaddr=code},
        {.resv=&reservations[1]},{.resv=&reservations[2]},{.resv=&reservations[3]}};
    struct opengpu_resource_binding shader={.object=&gems[0],.dma=0x40000,.size=sizeof(code),.type=OPENGPU_RESOURCE_COMPUTE_SHADER};
    struct opengpu_resource_binding kernarg={.object=&gems[1],.dma=0x50000,.va=0x80000000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_KERNARG};
    struct opengpu_resource_binding texture={.object=&gems[2],.dma=0x1000000,.va=0xa0000000,.size=0x200000,.type=OPENGPU_RESOURCE_COMPUTE_TEXTURE};
    struct opengpu_resource_binding image={.object=&gems[3],.dma=0x2000000,.va=0xd0000000,.size=0x200000,.type=OPENGPU_RESOURCE_COMPUTE_STORAGE};
    struct opengpu_render_context ctx={.vm.enabled=true};
    struct opengpu_file rf={.context=&ctx};
    struct opengpu_device gpu={.hw={.compute_texture_transport=true,.compute_image_transport=true,
        .capabilities=GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE,
        .compute_resource_flags=OPENGPU_COMPUTE_RESOURCE_TEXTURE_COMMANDS |
            OPENGPU_COMPUTE_RESOURCE_IMAGE_COMMANDS | OPENGPU_COMPUTE_RESOURCE_BUFFER_TEXTURE_V1 |
            OPENGPU_COMPUTE_RESOURCE_BUFFER_IMAGE_SIZE_V1,
        .compute_local_limits={128,128,true}}};
    struct drm_device drm={.dev=&gpu}; struct drm_file file={.driver_priv=&rf};
    struct drm_opengpu_compute launch={.context_id=1,.shader_slot=1,.kernarg_slot=2,.grid={1,1,1},.local={1,1,1}};
    struct opengpu_compute_texture_prepare *p=calloc(1,sizeof(*p));
    struct opengpu_compute_image_prepare images={0};
    assert(p); ctx.bindings[0]=&shader;ctx.bindings[1]=&kernarg;ctx.bindings[2]=&texture;ctx.bindings[12]=&image;
    fail_copy=fail_alloc=fail_submit=second_image=shared_image_storage=storage_read_only=0;
    buffer_texture_expected=1; buffer_mixed=0;
    assert(opengpu_buffer_texture_view_init(&p->views[31],3,OPENGPU_TEXTURE_FORMAT_RGBA8,
        texture.size,63,65536ULL*4+3));
    unsigned initial_submitted=submitted;
    /* All gates are exercised through real copied-view admission before any
     * allocation/submission; old image support cannot stand in for textures. */
    for(unsigned missing=0;missing<5;missing++) {
        const u32 flags=gpu.hw.compute_resource_flags,caps=gpu.hw.capabilities;
        const bool transport=gpu.hw.compute_texture_transport;
        const int allocations=alloc_count;
        if(missing==0)gpu.hw.compute_resource_flags&=~OPENGPU_COMPUTE_RESOURCE_BUFFER_TEXTURE_V1;
        if(missing==1)gpu.hw.compute_resource_flags&=~OPENGPU_COMPUTE_RESOURCE_TEXTURE_COMMANDS;
        if(missing==2)gpu.hw.capabilities&=~GPU_CAP_UNIFIED_COMMANDS;
        if(missing==3)gpu.hw.capabilities&=~GPU_CAP_PROTECTED_VM_STATE;
        if(missing==4)gpu.hw.compute_texture_transport=false;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,32,NULL,NULL)==-EOPNOTSUPP);
        assert(submitted==initial_submitted && !alloc_live && alloc_count==allocations);
        gpu.hw.compute_resource_flags=flags;gpu.hw.capabilities=caps;
        gpu.hw.compute_texture_transport=transport;
    }
    for(unsigned f=0;f<64;++f) {
        unsigned stride=opengpu_texture_buffer_format_bytes(f);
        if (!stride) continue;
        buffer_format_expected=f; buffer_count_expected=65536;
        buffer_bytes_expected=65536ULL*stride+stride-1; buffer_base_expected=texture.va+63;
        assert(opengpu_buffer_texture_view_init(&p->views[31],3,f,texture.size,63,buffer_bytes_expected));
        p->views[31].min_filter=p->views[31].compare_enable=0xffffffffu;
        unsigned before=submitted;
        int result=opengpu_compute_launch_common(&drm,&launch,&file,p,32,NULL,NULL);
        if (result) fprintf(stderr,"buffer format=%u bytes=%llu count=%u prepare=%d launch=%d\n",f,
            (unsigned long long)buffer_bytes_expected,buffer_count_expected,opengpu_compute_prepare_texture_views(p,32,&ctx,&kernarg,&gpu),result);
        assert(!result);
        assert(submitted==before+1);
        ++p->views[31].buffer_texels;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,32,NULL,NULL)==-EINVAL && submitted==before+1);
        --p->views[31].buffer_texels;
    }
    p->views[0]=(struct drm_opengpu_compute_texture_view){.slot=3,.format=3,.width=4,.height=4,.depth=1,.layers=1,.level_count=1};
    p->views[0].levels[0]=(struct drm_opengpu_compute_texture_level){.width=4,.height=4,.depth=1,.layers=1,.row_pitch=16,.block_bytes=4};
    buffer_mixed=1;
    assert(!opengpu_compute_launch_common(&drm,&launch,&file,p,32,NULL,NULL));
    unsigned before=submitted;
    p->views[0].min_filter=6;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,p,32,NULL,NULL)==-EINVAL && submitted==before);
    p->views[0].min_filter=0;
    /* The complete declared range, including trailing bytes, precedes count
     * validation and cannot be truncated to the selected/clamped texels. */
    p->views[31].levels[0].buffer_bytes=(1ULL<<32)+1;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,p,32,NULL,NULL)==-EINVAL && submitted==before);
    p->views[31].levels[0].buffer_bytes=buffer_bytes_expected;
    texture.size=63+buffer_bytes_expected-1;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,p,32,NULL,NULL)==-EINVAL && submitted==before);
    texture.size=0x200000;
    assert(!opengpu_compute_launch_common(&drm,&launch,&file,p,32,NULL,NULL));
    buffer_texture_expected=buffer_mixed=0;
    code[0]=0xc100f057u;code[1]=(8u<<26)|(1u<<25)|(1u<<20)|(1u<<15)|0x2bu;
    code[2]=(8u<<26)|(1u<<25)|(1u<<20)|(1u<<15)|(1u<<12)|0x2bu;
    code[3]=(9u<<26)|(1u<<25)|(1u<<20)|(1u<<15)|(2u<<7)|0x2bu;code[4]=OPENGPU_SHADER_CEASE;
    buffer_image_expected=1;buffer_count_expected=65536;
    buffer_bytes_expected=65536ULL*16+7;buffer_base_expected=image.va+63;
    assert(opengpu_buffer_image_view_init(&images.views[0],13,OPENGPU_COMPUTE_IMAGE_READ|OPENGPU_COMPUTE_IMAGE_WRITE,
        OPENGPU_TEXTURE_FORMAT_RGBA32UI,OPENGPU_TEXTURE_FORMAT_RGBA32UI,image.size,63,buffer_bytes_expected));
    before=submitted;
    for(unsigned missing=0;missing<5;missing++) {
        const u32 flags=gpu.hw.compute_resource_flags,caps=gpu.hw.capabilities;
        const bool transport=gpu.hw.compute_image_transport;
        const int allocations=alloc_count;
        if(missing==0)gpu.hw.compute_resource_flags&=~OPENGPU_COMPUTE_RESOURCE_BUFFER_IMAGE_SIZE_V1;
        if(missing==1)gpu.hw.compute_resource_flags&=~OPENGPU_COMPUTE_RESOURCE_IMAGE_COMMANDS;
        if(missing==2)gpu.hw.capabilities&=~GPU_CAP_UNIFIED_COMMANDS;
        if(missing==3)gpu.hw.capabilities&=~GPU_CAP_PROTECTED_VM_STATE;
        if(missing==4)gpu.hw.compute_image_transport=false;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EOPNOTSUPP);
        assert(submitted==before && !alloc_live && alloc_count==allocations);
        gpu.hw.compute_resource_flags=flags;gpu.hw.capabilities=caps;
        gpu.hw.compute_image_transport=transport;
    }
    assert(!opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images));
    before=submitted;images.views[0].buffer_texels=0;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EINVAL && submitted==before);
    images.views[0].buffer_texels=65536;images.views[0].buffer_bytes=(1ULL<<32)+1;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EINVAL && submitted==before);
    images.views[0].buffer_bytes=buffer_bytes_expected;
    storage_read_only=1;images.views[0].flags=OPENGPU_COMPUTE_IMAGE_READ;
    assert(!opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images));
    /* A size program is checked using the copied, immutable code, not a raw
     * pre-scan that would overlook a branch past an early CEASE. */
    code[1]=(27u<<26)|(1u<<25)|(1u<<20)|(1u<<15)|(2u<<7)|0x2bu;
    code[2]=OPENGPU_SHADER_CEASE;
    before=submitted;
    gpu.hw.compute_resource_flags&=~OPENGPU_COMPUTE_RESOURCE_BUFFER_IMAGE_SIZE_V1;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EOPNOTSUPP);
    /* Ordinary target0 views remain admissible, but cannot authorize function27. */
    const struct drm_opengpu_compute_image_view saved_buffer=images.views[0];
    images.views[0]=(struct drm_opengpu_compute_image_view){.slot=13,
        .flags=OPENGPU_COMPUTE_IMAGE_READ,.storage_format=3,.image_format=3,
        .width=4,.height=4,.depth=1,.layers=1,.row_pitch=16};
    buffer_image_expected=0;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EINVAL);
    assert(submitted==before && !alloc_live);
    gpu.hw.compute_resource_flags|=OPENGPU_COMPUTE_RESOURCE_BUFFER_IMAGE_SIZE_V1;
    images.views[0]=saved_buffer;buffer_image_expected=1;
    assert(!opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images));
    assert(submitted==before+1 && !alloc_live);
    buffer_image_expected=0;
    free(p);
}
int main(void)
{
    u32 code[32] = { (6u<<25)|(5u<<7)|0x0bu, OPENGPU_SHADER_CEASE };
    struct dma_resv reservations[3]={{0},{1},{2}};
    struct drm_gem_object gems[3]={{.resv=&reservations[0],.vaddr=code},{.resv=&reservations[1]},{.resv=&reservations[2]}};
    struct opengpu_resource_binding shader={.object=&gems[0],.dma=0x40000,.size=sizeof(code),.type=OPENGPU_RESOURCE_COMPUTE_SHADER};
    struct opengpu_resource_binding kernarg={.object=&gems[1],.dma=0x50000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_KERNARG};
    struct opengpu_resource_binding texture={.object=&gems[2],.dma=0x60000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_TEXTURE};
    struct opengpu_render_context ctx={0}; struct opengpu_file rf={.context=&ctx};
    struct opengpu_device gpu={.hw={.compute_texture_transport=true,.capabilities=GPU_CAP_UNIFIED_COMMANDS,.compute_local_limits={128,128,true}}};
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
    texture.object=&gems[2]; texture.dma=0x60000; gems[2].resv=gems[1].resv;
    assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,NULL)==-EINVAL && submitted==2);
    gems[2].resv=&reservations[2];
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
        assert(opengpu_compute_prepare_storage(&storage,&ctx,&shader,&kernarg,1)==0);
        assert(storage.dep_count==2 && storage.range_count==3 &&
               storage.deps[1].signal_usage==DMA_RESV_USAGE_READ);
        {
            struct opengpu_resource_binding alternate=backing;
            alternate.va+=0x08000000u; alternate.dma+=4096; ctx.bindings[13]=&alternate;
            storage.bindings[1]=(struct drm_opengpu_compute_storage_binding){14,OPENGPU_COMPUTE_STORAGE_WRITE};
            assert(opengpu_compute_prepare_storage(&storage,&ctx,&shader,&kernarg,1)==0);
            assert(storage.dep_count==2 && storage.range_count==3 &&
                   storage.deps[1].signal_usage==DMA_RESV_USAGE_WRITE &&
                   storage.records[1][10]==0xd8000000u);
            ctx.bindings[13]=NULL;
        }
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
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==0 && submitted==12);
        backing.object=&backing_gem; backing_gem.resv=gems[2].resv;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==-EINVAL && submitted==12);
        backing_gem.resv=&backing_resv; backing.dma=texture.dma;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==-EINVAL && submitted==12);
        backing.dma=0x70000; gems[2].foreign=true;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==-EOPNOTSUPP && submitted==12);
        gems[2].foreign=false; gpu.hw.compute_resource_flags=0;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==-EINVAL && submitted==12);
        gpu.hw.compute_resource_flags=OPENGPU_COMPUTE_RESOURCE_WORD_ATOMICS;
        fail_alloc=alloc_count+3;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==-ENOMEM && submitted==12);
        fail_alloc=0; fail_submit=1;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==-EIO && submitted==12);
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
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,32,&storage,NULL)==0 && submitted==13);
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
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==0 && submitted==14);
        storage_read_only=1; images.views[0].flags=OPENGPU_COMPUTE_IMAGE_READ;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==0 && submitted==15);
        code[4]=0x00000463u; code[5]=0x0000000bu; code[6]=OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EINVAL && submitted==15);
        code[4]=OPENGPU_SHADER_CEASE;
        images.views[0].pad=1;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EINVAL && submitted==15);
        images.views[0].pad=0; images.views[0].row_pitch=15;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EINVAL && submitted==15);
        images.views[0].row_pitch=16; gems[2].foreign=true;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EOPNOTSUPP && submitted==15);
        gems[2].foreign=false; images.views[1]=images.views[0];
        images.views[1].offset=16; images.views[1].width=2; images.views[1].height=2;
        assert(opengpu_compute_prepare_images(&images,&ctx,&shader,&kernarg,1,&gpu)==0);
        assert(images.dep_count==2 && images.records[1][6]==1 &&
               images.records[1][25]==0xd0000010u && images.records[1][26]==2 &&
               images.deps[1].wait_usage==DMA_RESV_USAGE_WRITE &&
               images.deps[1].signal_usage==DMA_RESV_USAGE_READ);
        images.views[1].offset=4095;
        assert(opengpu_compute_prepare_images(&images,&ctx,&shader,&kernarg,1,&gpu)==-EINVAL);
        images.views[1].offset=16;
        images.views[1].flags |= OPENGPU_COMPUTE_IMAGE_WRITE;
        assert(opengpu_compute_prepare_images(&images,&ctx,&shader,&kernarg,1,&gpu)==0);
        assert(images.dep_count==2 && images.deps[1].wait_usage==DMA_RESV_USAGE_READ &&
               images.deps[1].signal_usage==DMA_RESV_USAGE_WRITE);
        images.views[0].flags |= OPENGPU_COMPUTE_IMAGE_WRITE;
        images.views[1].flags = OPENGPU_COMPUTE_IMAGE_READ;
        assert(opengpu_compute_prepare_images(&images,&ctx,&shader,&kernarg,1,&gpu)==0);
        assert(images.dep_count==2 && images.deps[1].signal_usage==DMA_RESV_USAGE_WRITE);
        images.views[0].flags = OPENGPU_COMPUTE_IMAGE_READ;
        images.views[2]=images.views[0]; images.views[3]=images.views[0];
        images.views[3].flags |= OPENGPU_COMPUTE_IMAGE_WRITE;
        assert(opengpu_compute_prepare_images(&images,&ctx,&shader,&kernarg,1,&gpu)==0);
        assert(images.dep_count==2 && images.records[2][6]==2 && images.records[3][6]==3 &&
               images.deps[1].wait_usage==DMA_RESV_USAGE_READ &&
               images.deps[1].signal_usage==DMA_RESV_USAGE_WRITE);
        memset(&images.views[2],0,sizeof(images.views[2]));
        memset(&images.views[3],0,sizeof(images.views[3]));
        {
            struct opengpu_resource_binding alternate=backing;
            struct dma_resv independent_resv={0};
            struct drm_gem_object other={.resv=gems[2].resv};
            alternate.va+=0x08000000u; alternate.dma+=4096; ctx.bindings[13]=&alternate;
            images.views[1].slot=14;
            assert(opengpu_compute_prepare_images(&images,&ctx,&shader,&kernarg,1,&gpu)==0);
            assert(images.dep_count==2 && images.range_count==3 &&
                   images.records[1][25]==0xd8000010u);
            images.views[1].flags |= OPENGPU_COMPUTE_IMAGE_WRITE;
            assert(opengpu_compute_prepare_images(&images,&ctx,&shader,&kernarg,1,&gpu)==0 &&
                   images.deps[1].signal_usage==DMA_RESV_USAGE_WRITE);
            images.views[1].flags=OPENGPU_COMPUTE_IMAGE_READ;
            alternate.object=&other; alternate.dma+=4096;
            assert(opengpu_compute_prepare_images(&images,&ctx,&shader,&kernarg,1,&gpu)==-EINVAL);
            other.resv=&independent_resv; alternate.dma=backing.dma;
            assert(opengpu_compute_prepare_images(&images,&ctx,&shader,&kernarg,1,&gpu)==-EINVAL);
            ctx.bindings[13]=NULL;
        }
        memset(&images.views[1],0,sizeof(images.views[1])); images.views[1].width=1;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EINVAL && submitted==15);
        memset(&images.views[1],0,sizeof(images.views[1])); ctx.vm.enabled=false;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EOPNOTSUPP && submitted==15);
        ctx.vm.enabled=true; backing.object=&gems[1];
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EINVAL && submitted==15);
        backing.object=&gems[2]; fail_alloc=alloc_count+2;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-ENOMEM && submitted==15);
        fail_alloc=0; fail_submit=1;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EIO && submitted==15);
        fail_submit=0; gpu.hw.compute_image_transport=false;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EOPNOTSUPP && submitted==15);
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
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==0 && submitted==16);
        p->views[1]=p->views[0];
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,2,&storage,NULL)==0 && submitted==17);
        storage_read_only=1;storage.bindings[0].access=OPENGPU_COMPUTE_STORAGE_READ;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,2,&storage,NULL)==0 && submitted==18);
        tex.size=2048;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==0 && submitted==19);
        tex.size=4096;tex.dma+=64;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==0 && submitted==20);
        tex.dma-=64;shared.foreign=true;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&storage,NULL)==-EOPNOTSUPP && submitted==20);
    }
    {
        struct dma_resv tex_resv={0};
        struct drm_gem_object tex_gem={.resv=&tex_resv};
        struct opengpu_resource_binding tex={.object=&tex_gem,.dma=0x70000,
            .va=0xa0000000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_TEXTURE};
        struct opengpu_resource_binding img={.object=&gems[2],.dma=0x60000,
            .va=0xd0000000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_STORAGE};
        struct opengpu_compute_image_prepare images={0};
        memset(p,0,sizeof(*p)); fail_submit=0; storage_read_only=0;
        ctx.bindings[0]=&shader;ctx.bindings[1]=&kernarg;ctx.bindings[2]=&tex;ctx.bindings[12]=&img;
        ctx.vm.enabled=true;kernarg.va=0x80000000;
        gpu.hw.compute_texture_transport=true;gpu.hw.compute_image_transport=true;
        p->views[0]=(struct drm_opengpu_compute_texture_view){.slot=3,.format=3,.width=4,.height=4,.depth=1,.layers=1,.level_count=1};
        p->views[0].levels[0]=(struct drm_opengpu_compute_texture_level){.width=4,.height=4,.depth=1,.layers=1,.row_pitch=16,.block_bytes=4};
        images.views[0]=(struct drm_opengpu_compute_image_view){.slot=13,
            .flags=OPENGPU_COMPUTE_IMAGE_READ|OPENGPU_COMPUTE_IMAGE_WRITE,
            .storage_format=OPENGPU_TEXTURE_FORMAT_R32UI,.image_format=OPENGPU_TEXTURE_FORMAT_R32UI,
            .width=4,.height=4,.depth=1,.layers=1,.row_pitch=16};
        code[0]=(6u<<25)|(5u<<7)|0x0bu; code[1]=0xc100f057u;
        code[2]=(8u<<26)|(1u<<25)|(1u<<20)|(1u<<15)|0x2bu;
        code[3]=(8u<<26)|(1u<<25)|(1u<<20)|(1u<<15)|(1u<<12)|0x2bu;
        code[4]=(9u<<26)|(1u<<25)|(1u<<20)|(1u<<15)|(2u<<7)|0x2bu;
        code[5]=OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,&images)==0 && submitted==21);
        p->views[1]=p->views[0];
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,2,NULL,&images)==0 && submitted==22);
        tex.object=&gems[2];
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,&images)==0 && submitted==23);
        tex.object=&tex_gem; tex_gem.resv=gems[2].resv;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,&images)==-EINVAL && submitted==23);
        tex_gem.resv=&tex_resv;tex.dma=img.dma;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,&images)==-EINVAL && submitted==23);
        tex.dma=0x70000;tex_gem.foreign=true;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,&images)==-EOPNOTSUPP && submitted==23);
        tex_gem.foreign=false;fail_alloc=alloc_count+2;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,&images)==-ENOMEM && submitted==23);
        fail_alloc=alloc_count+3;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,&images)==-ENOMEM && submitted==23);
        fail_alloc=0;fail_submit=1;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,&images)==-EIO && submitted==23);
        {
            struct dma_resv storage_resv={0};
            struct drm_gem_object storage_gem={.resv=&storage_resv};
            struct opengpu_resource_binding sb={.object=&storage_gem,.dma=0x80000,
                .va=0xd8000000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_STORAGE};
            struct opengpu_compute_storage_prepare st={0};
            ctx.bindings[13]=&sb; st.bindings[0]=(struct drm_opengpu_compute_storage_binding)
                {14,OPENGPU_COMPUTE_STORAGE_READ|OPENGPU_COMPUTE_STORAGE_WRITE};
            fail_submit=0; gpu.hw.compute_storage_transport=true;
            assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&st,&images)==-EINVAL);
            code[0]=0x00000013u;
            assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&st,&images)==0 && submitted==24);
            code[0]=(6u<<25)|(5u<<7)|0x0bu;
            /* Same image program with texture and storage profiles enabled. */
            assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&st,&images)==0 && submitted==25);
            tex.object=&storage_gem; tex.dma=sb.dma;
            assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&st,&images)==0 && submitted==26);
            /* Distinct storage and image slots alias one local object. */
            tex.object=&tex_gem; tex.dma=0x70000; sb.object=&gems[2]; shared_image_storage=1;
            assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&st,&images)==0 && submitted==27);
            /* All three clients use different ranges of the same object. */
            tex.object=&gems[2]; tex.dma=img.dma+128;
            assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&st,&images)==0 && submitted==28);
            tex.object=&tex_gem; tex.dma=0x70000;
            images.views[0].flags=OPENGPU_COMPUTE_IMAGE_READ; code[0]=0x00000013u;
            assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&st,&images)==0 && submitted==29);
            sb.dma=img.dma+64; st.bindings[0].access=OPENGPU_COMPUTE_STORAGE_READ;
            images.views[0].flags=OPENGPU_COMPUTE_IMAGE_READ|OPENGPU_COMPUTE_IMAGE_WRITE;
            assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&st,&images)==0 && submitted==30);
            images.views[0].flags=OPENGPU_COMPUTE_IMAGE_READ; storage_read_only=1;
            assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&st,&images)==0 && submitted==31);
            sb.dma=0x80000; storage_read_only=0;
            images.views[0].flags=OPENGPU_COMPUTE_IMAGE_READ|OPENGPU_COMPUTE_IMAGE_WRITE;
            code[0]=(6u<<25)|(5u<<7)|0x0bu; tex.object=&storage_gem; tex.dma=sb.dma;
            shared_image_storage=0;
            sb.object=&gems[2];
            assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&st,&images)==-EINVAL && submitted==31);
            sb.object=&storage_gem; storage_gem.resv=gems[2].resv;
            assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&st,&images)==-EINVAL && submitted==31);
            storage_gem.resv=&storage_resv; sb.dma=img.dma;
            assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&st,&images)==-EINVAL && submitted==31);
            sb.dma=0x80000; storage_gem.foreign=true;
            assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&st,&images)==-EOPNOTSUPP && submitted==31);
        }
        {
            struct opengpu_compute_storage_prepare st={0};
            st.bindings[0]=(struct drm_opengpu_compute_storage_binding){13,OPENGPU_COMPUTE_STORAGE_READ};
            code[0]=0x00000013u; storage_read_only=0; shared_image_storage=1;
            assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&st,&images)==0 && submitted==32);
            images.views[0].flags=OPENGPU_COMPUTE_IMAGE_READ;
            st.bindings[0].access=OPENGPU_COMPUTE_STORAGE_READ|OPENGPU_COMPUTE_STORAGE_WRITE;
            assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&st,&images)==0 && submitted==33);
            st.bindings[0].access=OPENGPU_COMPUTE_STORAGE_READ; storage_read_only=1;
            assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&st,&images)==0 && submitted==34);
            st.bindings[0].access=OPENGPU_COMPUTE_STORAGE_READ|OPENGPU_COMPUTE_STORAGE_WRITE;
            images.views[1]=images.views[0]; storage_read_only=0; second_image=1;
            assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&st,&images)==0 && submitted==35);
            images.views[1]=(struct drm_opengpu_compute_image_view){0};
            memset(images.records[1],0,sizeof(images.records[1])); second_image=0;
            images.views[0].flags=OPENGPU_COMPUTE_IMAGE_READ|OPENGPU_COMPUTE_IMAGE_WRITE;
            tex.object=&tex_gem; tex.dma=0x70000; tex_gem.resv=&tex_resv;
            code[0]=(6u<<25)|(5u<<7)|0x0bu;
            assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&st,&images)==0 && submitted==36);
            tex.object=&gems[2]; tex.dma=img.dma;
            assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,&st,&images)==0 && submitted==37);
            shared_image_storage=0;
        }
        code[0]=0xc100f057u;
        code[1]=(8u<<26)|(1u<<25)|(1u<<20)|(1u<<15)|0x2bu;
        code[2]=code[1]|(1u<<12); code[3]=code[1]|(2u<<12);
        code[4]=(23u<<26)|(1u<<25)|(1u<<20)|(1u<<15)|(1u<<12)|(2u<<7)|0x2bu;
        code[5]=OPENGPU_SHADER_CEASE;
        gpu.hw.compute_resource_flags=0;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EINVAL && submitted==37);
        gpu.hw.compute_resource_flags=OPENGPU_COMPUTE_RESOURCE_IMAGE_ATOMICS;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==0 && submitted==38);
        gpu.hw.compute_image_transport=false;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,&images)==-EOPNOTSUPP && submitted==38);
        gpu.hw.compute_image_transport=true;
        code[0]=(6u<<25)|(5u<<7)|0x0bu; code[1]=0xc100f057u;
        code[2]=(8u<<26)|(1u<<25)|(1u<<20)|(1u<<15)|0x2bu;
        code[3]=code[2]|(1u<<12); code[4]=(9u<<26)|(1u<<25)|(1u<<20)|(1u<<15)|(2u<<7)|0x2bu;
        code[5]=OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,&images)==0 && submitted==39);
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,2,NULL,&images)==0 && submitted==40);
        images.views[0].flags=OPENGPU_COMPUTE_IMAGE_READ; storage_read_only=1;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,&images)==0 && submitted==41);
        tex.size=2048;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,&images)==0 && submitted==42);
        tex.size=4096; tex.dma+=64;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,&images)==0 && submitted==43);
        tex.dma=img.dma; gems[2].foreign=true;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,p,1,NULL,&images)==-EOPNOTSUPP && submitted==43);
        gems[2].foreign=false;
    }
    {
        struct dma_resv resv[3]={{0}};
        struct drm_gem_object objects[3]={{.resv=&resv[0]},{.resv=&resv[1]},{.resv=&resv[2]}};
        struct opengpu_compute_image_prepare im={0};
        struct opengpu_compute_storage_prepare st={0};
        struct opengpu_resource_binding tex={.object=&objects[2],.dma=0x102000,
            .size=4096,.va=0xa0000000,.type=OPENGPU_RESOURCE_COMPUTE_TEXTURE};
        struct opengpu_staging_range a={0x100000,4096}, b={0x102000,4096};
        im.range_count=1; im.range_objects[0]=&objects[0]; im.ranges[0]=(struct opengpu_staging_range){0x80000,4096};
        assert(opengpu_compute_record_range(&objects[1],&a,im.range_objects,im.ranges,&im.range_count)==0);
        assert(opengpu_compute_record_range(&objects[1],&b,im.range_objects,im.ranges,&im.range_count)==0);
        assert(opengpu_compute_record_range(&objects[2],&b,im.range_objects,im.ranges,&im.range_count)==-EINVAL);
        st.range_count=2; st.range_objects[1]=&objects[2]; st.ranges[1]=b;
        assert(opengpu_compute_check_image_storage(&im,&st)==-EINVAL);
        ctx.bindings[2]=&tex; memset(p,0,sizeof(*p)); p->views[0].slot=3;
        assert(opengpu_compute_combine_textured_images(&im,p,1,&ctx)==-EINVAL);
        st.range_count=im.range_count; memcpy(st.range_objects,im.range_objects,sizeof(st.range_objects));
        memcpy(st.ranges,im.ranges,sizeof(st.ranges));
        assert(opengpu_compute_combine_resources(&st,p,1,&ctx)==-EINVAL);
    }
    {
        struct opengpu_compute_storage_prepare st={0};
        struct opengpu_resource_binding backing={.object=&gems[2],.dma=0x60000,
            .va=0xd0000000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_STORAGE};
        memset(&ctx,0,sizeof(ctx)); ctx.vm.enabled=true; ctx.bindings[0]=&shader;
        ctx.bindings[1]=&kernarg; ctx.bindings[12]=&backing;
        st.bindings[0]=(struct drm_opengpu_compute_storage_binding){13,3};
        gpu.hw.compute_storage_transport=true; gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
        storage_read_only=0; launch.local[0]=1;
        code[0]=0xc100f057u;
        code[1]=(23u<<26)|(1u<<25)|(3u<<12)|(2u<<7)|0x57u;
        code[2]=(23u<<26)|(1u<<25)|(7u<<15)|(3u<<12)|(3u<<7)|0x57u;
        code[3]=(24u<<26)|(1u<<25)|(2u<<20)|(3u<<15)|0x2bu;
        code[4]=(25u<<26)|(1u<<25)|(2u<<20)|(2u<<15)|(1u<<12)|(5u<<7)|0x2bu;
        code[5]=OPENGPU_SHADER_CEASE;
        gpu.hw.compute_resource_flags=0;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&st,NULL)==-EINVAL && submitted==43);
        gpu.hw.compute_resource_flags=OPENGPU_COMPUTE_RESOURCE_STORAGE_ATOMICS;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,&st,NULL)==0 && submitted==44);
    }
    {
        /* The real ioctl checks all 128 work-items, including the last word. */
        memset(&ctx,0,sizeof(ctx)); ctx.vm.enabled=true; ctx.bindings[0]=&shader; ctx.bindings[1]=&kernarg;
        kernarg.va=0x80000000; kernarg.size=512;
        gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
        gpu.hw.compute_local_limits=(struct opengpu_compute_local_limits){128,128,true};
        code[0]=0xc1087057u; /* vsetivli x0,16,e32,m1 */
        code[1]=0x00241293u; /* slli x5,x8,2 */
        code[2]=0x005082b3u; /* add x5,x1,x5 */
        code[3]=0x0202e0a7u; /* vse32.v v1,(x5) */
        code[4]=OPENGPU_SHADER_CEASE;
        launch.local[0]=128; launch.local[1]=launch.local[2]=1;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,NULL)==0 && submitted==45);
        kernarg.size=511;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,NULL)==-EINVAL && submitted==45);
        kernarg.size=512; launch.local[0]=8; launch.local[1]=4; launch.local[2]=4;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,NULL)==0 && submitted==46);
        launch.local[2]=5;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,NULL)==-EINVAL && submitted==46);
        launch.local[0]=129; launch.local[1]=launch.local[2]=1;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,NULL)==-EINVAL && submitted==46);
        gpu.hw.compute_local_limits=(struct opengpu_compute_local_limits){8,8,true};
        launch.local[0]=9;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,NULL)==-EINVAL && submitted==46);
        code[0]=OPENGPU_SHADER_CEASE; launch.local[0]=8;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,NULL)==0 && submitted==47);
    }
    {
        struct dma_resv resv[3]={{0}};
        struct drm_gem_object objects[3]={{.resv=&resv[0]},{.resv=&resv[1]},{.resv=&resv[2]}};
        struct opengpu_resource_binding backing={.object=&objects[0],.dma=0x60000,
            .va=0xd0000000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_STORAGE};
        struct opengpu_resource_binding other={.object=&objects[1],.dma=0x70000,
            .va=0xd0400000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_STORAGE};
        struct opengpu_compute_counter_prepare counter={.binding={.slot=13,.offset=4,.count=8}};
        struct opengpu_compute_storage_prepare st={0};
        memset(&ctx,0,sizeof(ctx)); ctx.vm.enabled=true; ctx.bindings[0]=&shader;
        ctx.bindings[1]=&kernarg; ctx.bindings[12]=&backing; ctx.bindings[13]=&other;
        kernarg.va=0x80000000; kernarg.size=4096;
        launch.local[0]=4; launch.local[1]=launch.local[2]=1;
        gpu.hw.compute_local_limits=(struct opengpu_compute_local_limits){128,128,true};
        gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE;
        gpu.hw.compute_counter_transport=true; gpu.hw.compute_storage_transport=true;
        code[0]=0xc1027057u; code[1]=(18u<<26)|(1u<<25)|(1u<<15)|(2u<<7)|0x2bu;
        code[2]=OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_launch_common(&drm,&launch,&file,NULL,0,NULL,NULL)==-EINVAL);
        assert(opengpu_compute_launch_extended(&drm,&launch,&file,NULL,0,NULL,NULL,&counter,NULL,NULL)==0 && submitted==48);
        counter.binding.offset=2;
        assert(opengpu_compute_launch_extended(&drm,&launch,&file,NULL,0,NULL,NULL,&counter,NULL,NULL)==-EINVAL);
        counter.binding.offset=4092; counter.binding.count=2;
        assert(opengpu_compute_launch_extended(&drm,&launch,&file,NULL,0,NULL,NULL,&counter,NULL,NULL)==-EINVAL);
        counter.binding.offset=4; counter.binding.count=9;
        assert(opengpu_compute_launch_extended(&drm,&launch,&file,NULL,0,NULL,NULL,&counter,NULL,NULL)==-EINVAL);
        counter.binding.count=8; backing.object=&gems[1];
        assert(opengpu_compute_launch_extended(&drm,&launch,&file,NULL,0,NULL,NULL,&counter,NULL,NULL)==-EINVAL);
        backing.object=&objects[0]; objects[0].foreign=true;
        assert(opengpu_compute_launch_extended(&drm,&launch,&file,NULL,0,NULL,NULL,&counter,NULL,NULL)==-EOPNOTSUPP);
        objects[0].foreign=false; backing.dma=kernarg.dma;
        assert(opengpu_compute_launch_extended(&drm,&launch,&file,NULL,0,NULL,NULL,&counter,NULL,NULL)==-EINVAL);
        backing.dma=0x60000; code[1]&=~(1u<<25);
        assert(opengpu_compute_launch_extended(&drm,&launch,&file,NULL,0,NULL,NULL,&counter,NULL,NULL)==-EINVAL);
        code[1]|=1u<<25; alloc_count=0; fail_alloc=2;
        assert(opengpu_compute_launch_extended(&drm,&launch,&file,NULL,0,NULL,NULL,&counter,NULL,NULL)==-ENOMEM);
        fail_alloc=0; fail_submit=1;
        assert(opengpu_compute_launch_extended(&drm,&launch,&file,NULL,0,NULL,NULL,&counter,NULL,NULL)==-EIO);
        fail_submit=0; st.bindings[0]=(struct drm_opengpu_compute_storage_binding){14,3};
        assert(opengpu_compute_launch_extended(&drm,&launch,&file,NULL,0,&st,NULL,&counter,NULL,NULL)==0 && submitted==49);
        st.bindings[0].slot=13;
        assert(opengpu_compute_launch_extended(&drm,&launch,&file,NULL,0,&st,NULL,&counter,NULL,NULL)==-EINVAL);
        {
            struct drm_opengpu_compute_counter_resources args={.launch=launch,
                .counter={.slot=13,.offset=4,.count=8}};
            assert(opengpu_compute_counter_resources_ioctl(&drm,&args,&file)==0 && submitted==50);
            args.resource_flags=4;
            assert(opengpu_compute_counter_resources_ioctl(&drm,&args,&file)==-EINVAL);
            args.resource_flags=0; args.bindings[0].slot=14;
            assert(opengpu_compute_counter_resources_ioctl(&drm,&args,&file)==-EINVAL);
            args.bindings[0].slot=0; args.images[0].slot=14;
            assert(opengpu_compute_counter_resources_ioctl(&drm,&args,&file)==-EINVAL);
            args.images[0].slot=0; args.texture_count=1;
            assert(opengpu_compute_counter_resources_ioctl(&drm,&args,&file)==-EINVAL);
            args.textures=(uintptr_t)p->views; fail_copy=1;
            assert(opengpu_compute_counter_resources_ioctl(&drm,&args,&file)==-EFAULT);
            fail_copy=0; args.textures=0; args.texture_count=0;
            args.resource_flags=OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE;
            args.bindings[0]=(struct drm_opengpu_compute_storage_binding){14,3};
            assert(opengpu_compute_counter_resources_ioctl(&drm,&args,&file)==0 && submitted==51);
        }
        {
            struct opengpu_resource_binding tex={.object=&objects[2],.dma=0x80000,
                .va=0xa0000000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_TEXTURE};
            struct drm_opengpu_compute_counter_resources args={.launch=launch,
                .counter={.slot=13,.offset=4,.count=8},
                .resource_flags=OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE |
                                OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES};
            memset(p,0,sizeof(*p)); ctx.bindings[2]=&tex;
            p->views[0]=(struct drm_opengpu_compute_texture_view){.slot=3,.format=3,
                .width=4,.height=4,.depth=1,.layers=1,.level_count=1};
            p->views[0].levels[0]=(struct drm_opengpu_compute_texture_level){.width=4,
                .height=4,.depth=1,.layers=1,.row_pitch=16,.block_bytes=4};
            args.textures=(uintptr_t)p->views; args.texture_count=1;
            args.bindings[0]=(struct drm_opengpu_compute_storage_binding){14,3};
            args.images[0]=(struct drm_opengpu_compute_image_view){.slot=14,
                .flags=OPENGPU_COMPUTE_IMAGE_READ|OPENGPU_COMPUTE_IMAGE_WRITE,
                .storage_format=OPENGPU_TEXTURE_FORMAT_R32UI,.image_format=OPENGPU_TEXTURE_FORMAT_R32UI,
                .width=4,.height=4,.depth=1,.layers=1,.row_pitch=16};
            gpu.hw.compute_image_transport=true; gpu.hw.compute_texture_transport=true;
            assert(opengpu_compute_counter_resources_ioctl(&drm,&args,&file)==0 && submitted==52);
            args.images[0].slot=13;
            assert(opengpu_compute_counter_resources_ioctl(&drm,&args,&file)==-EINVAL);
            args.images[0].slot=14; tex.object=backing.object;
            assert(opengpu_compute_counter_resources_ioctl(&drm,&args,&file)==-EINVAL);
        }
        ctx.vm.enabled=false;
        assert(opengpu_compute_launch_extended(&drm,&launch,&file,NULL,0,NULL,NULL,&counter,NULL,NULL)==-EOPNOTSUPP);
        ctx.vm.enabled=true; gpu.hw.compute_counter_transport=false;
        assert(opengpu_compute_launch_extended(&drm,&launch,&file,NULL,0,NULL,NULL,&counter,NULL,NULL)==-EOPNOTSUPP);
        assert(submitted==52);
    }
    {
        /* Actual storage preparation keeps each shader bound byte exact,
         * independently of the complete transport-line backing envelope. */
        struct dma_resv local_resv[6] = {{0}};
        struct drm_gem_object local_gems[6] = {{0}};
        struct opengpu_resource_binding views[4] = {{0}};
        struct opengpu_render_context local_ctx = {.vm.enabled=true};
        struct opengpu_compute_storage_prepare storage = {0};
        struct opengpu_resource_binding local_shader = {.object=&local_gems[0], .dma=0x10000, .size=64};
        struct opengpu_resource_binding local_arg = {.object=&local_gems[1], .dma=0x20000, .size=64};
        const u64 sizes[] = {1,3,4,63,64,65,127,128,4095,4097,128ull*1024*1024};
        for (unsigned i=0;i<6;i++) local_gems[i].resv=&local_resv[i];
        for (unsigned i=0;i<4;i++) {
            views[i]=(struct opengpu_resource_binding){.object=&local_gems[i+2],
                .dma=0x10000f00ull+i*0x20000000ull,
                .va=0xcfffCF00ull+i*(128ull*1024*1024+4096),
                .type=OPENGPU_RESOURCE_COMPUTE_STORAGE};
            local_ctx.bindings[34+i]=&views[i];
            storage.bindings[i]=(struct drm_opengpu_compute_storage_binding){35+i,3};
        }
        for (unsigned n=0;n<sizeof(sizes)/sizeof(*sizes);n++) {
            for (unsigned i=0;i<4;i++) views[i].size=sizes[n];
            assert(!opengpu_compute_prepare_storage(&storage,&local_ctx,&local_shader,&local_arg,7));
            assert(storage.dep_count==5 && storage.range_count==5);
            for (unsigned i=0;i<4;i++) {
                const u32 *words=storage.records[i];
                u64 limit=(u64)words[11]|((u64)words[12]<<32);
                u64 allocation_limit=(u64)words[14]|((u64)words[15]<<32);
                assert(words[10]==views[i].va && limit==views[i].va+sizes[n]);
                assert(words[13]==views[i].va && allocation_limit==((limit+63)&~63ull));
            }
        }
        views[3].size=128ull*1024*1024+1;
        assert(opengpu_compute_prepare_storage(&storage,&local_ctx,&local_shader,&local_arg,7)==-EINVAL);
    }
    free(p); buffer_ioctl_checks(); return 0;
}
