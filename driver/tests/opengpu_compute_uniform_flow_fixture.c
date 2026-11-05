/* Actual ioctl -> scheduler -> private VM -> MMIO -> POP -> job retirement.
 * Kernel primitives and hardware replies are simulated, not GPU execution. */
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "opengpu_drm.h"
#include "opengpu_compute_texture_bound_view.h"
#include "opengpu_compute_features.h"
#include "opengpu_compute_uniform_command.h"
#include "opengpu_compute_uniform_records.h"
#include "opengpu_compute_counter_source.h"
#include "opengpu_staging_validator.h"
#include "opengpu_backing_identity.h"
#include "opengpu_atomic_counter_command.h"
#include "opengpu_compute_texture_command.h"
#include "opengpu_storage_command.h"
#include "opengpu_compute_image_descriptor.h"
#include "opengpu_shader_image_command.h"
#include "opengpu_storage_descriptor.h"
#include "opengpu_shader_validator.h"
#include "opengpu_compute_uniform_counter_source_program.h"
#include "opengpu_kernarg_va.h"
#include "opengpu_tessellation_completion.h"
#include <stddef.h>
_Static_assert(sizeof(struct drm_opengpu_compute_uniform_view)==16,"uniform UAPI view size");
_Static_assert(offsetof(struct drm_opengpu_compute_uniform_view,handle)==8,"uniform UAPI handle offset");
_Static_assert(offsetof(struct drm_opengpu_compute_uniform_view,bytes)==12,"uniform UAPI size offset");
_Static_assert(sizeof(struct drm_opengpu_compute_counter_source)==16,"counter source UAPI size");
_Static_assert(offsetof(struct drm_opengpu_compute_counter_source,handle)==8,"counter source handle offset");
_Static_assert(offsetof(struct drm_opengpu_compute_counter_source,count)==12,"counter source count offset");
_Static_assert(sizeof(struct drm_opengpu_compute_counter_source_resources)==sizeof(struct drm_opengpu_compute_uniform_resources)+32,"counter source wrapper UAPI size");
typedef uint64_t u64;
typedef uint64_t dma_addr_t;
typedef unsigned char u8;
#define U32_MAX UINT32_MAX
#define U16_MAX UINT16_MAX
#define OPENGPU_MMU_PAGE_SIZE 4096u
#define OPENGPU_MMU_POLICY_UNCACHED 2u
#define OPENGPU_MAX_TEMP_MAPPINGS 8u
#define OPENGPU_DRAW_WAIT_MS 30000u
#define GFP_KERNEL 0
#define DMA_RESV_USAGE_READ 1
#define DMA_RESV_USAGE_WRITE 2
#define DRM_EXEC_INTERRUPTIBLE_WAIT 1
#define DRM_EXEC_IGNORE_DUPLICATES 2
#define upper_32_bits(v) ((u64)(v) >> 32)
#define lower_32_bits(v) ((u32)(v))
#define check_add_overflow(a,b,p) __builtin_add_overflow(a,b,p)
#define check_mul_overflow(a,b,p) __builtin_mul_overflow(a,b,p)
#define min_t(t,a,b) ((t)(a)<(t)(b)?(t)(a):(t)(b))
#define ARRAY_SIZE(a) (sizeof(a)/sizeof((a)[0]))
#define u64_to_user_ptr(v) ((void *)(uintptr_t)(v))
#define READ_ONCE(v) (v)
#define WRITE_ONCE(v,x) ((v)=(x))
#define ERR_PTR(e) ((struct dma_fence *)(intptr_t)(e))
#define dev_err(...) ((void)0)
#define system_dfl_wq NULL
#define msecs_to_jiffies(x) (x)
#define mutex_lock(p) do { assert(!*(p)); *(p)=1; } while (0)
#define mutex_unlock(p) do { assert(*(p)); *(p)=0; } while (0)
#define spin_lock_irqsave(p,f) do { (f)=0; mutex_lock(p); } while (0)
#define spin_unlock_irqrestore(p,f) do { (void)(f); mutex_unlock(p); } while (0)
#define dma_wmb() ((void)0)
#define drm_exec_until_all_locked(e) for (int once=1;once;once=0)
#define drm_exec_retry_on_contention(e) ((void)(e))
struct dma_fence { u64 seqno; int refs, error; bool signaled, heap; };
struct dma_resv { bool locked, producer; unsigned waits, reads, writes; int last_usage,wait_usage; struct dma_fence *last,*prior_read,*prior_write; };
struct drm_gem_object { struct dma_resv *resv; void *vaddr; u64 dma_addr; size_t size; void *import_attach; int refs; };
struct drm_gem_dma_object { void *vaddr; u64 dma_addr; };
struct opengpu_resource_binding { struct drm_gem_object *object; u64 dma,va,size,offset; u32 type; };
struct opengpu_vm { bool enabled; u32 root; };
struct drm_sched_entity { int unused; };
struct drm_sched_fence { struct dma_fence finished; };
struct drm_sched_job { struct drm_sched_fence *s_fence; struct dma_fence *waits[64]; unsigned wait_count; };
struct opengpu_render_context { struct opengpu_vm vm; u32 id; struct opengpu_resource_binding *bindings[OPENGPU_MAX_RESOURCE_BINDINGS]; struct drm_sched_entity entity; struct dma_fence *last_fence; int refs; };
struct opengpu_sched_dep { struct drm_gem_object *object; int wait_usage,signal_usage; };
struct opengpu_command_events { u32 flags,wait_event,signal_event; };
struct opengpu_buffer { void *cpu; u64 dma; size_t size; };
struct opengpu_kernel_launch {
    u64 kernel_pc,kernarg; u32 grid[3],local[3];
    bool texture_enabled,storage_enabled,image_enabled,counter_enabled,uniform_enabled;
    struct opengpu_compute_texture_command_context texture;
    struct opengpu_storage_command_context storage;
    struct opengpu_image_command_context image;
    struct opengpu_atomic_counter_command_context counter;
    struct opengpu_compute_uniform_command_context uniform;
};
struct opengpu_device { struct {
    bool compute_texture_transport,compute_storage_transport,compute_image_transport,compute_counter_transport,compute_uniform_transport,wedged,reset_pending;
    u32 capabilities,compute_resource_flags,unified_command_id,unified_opcode,aborting;
    u32 unified_completion_delay_ms,active_completion_delay_ms;
    u64 regs_size,unified_expected_bytes,fence_context,fence_seqno;
    int submit_lock,fence_lock,timeout_work,completion_work;
    struct dma_fence *unified_fence,*active_fence;
    struct { struct opengpu_tessellation_completion tessellation; bool tessellation_valid; int error; u32 flags,status; } last_fault;
    struct opengpu_compute_local_limits compute_local_limits;
    struct opengpu_compute_shared_limits compute_shared_limits;
} hw; u32 regs[512],captured[8],active_root; unsigned kicks,pops,uniform_writes,invalidations; };
enum { OPENGPU_SCHED_COMPUTE=1,OPENGPU_SCHED_TESSELLATION=7 };
struct opengpu_job_temporary_mapping { u64 va; size_t size; };
struct opengpu_sched_job {
    struct drm_sched_job base; struct opengpu_device *gpu; const struct opengpu_vm *vm;
    struct opengpu_render_context *context; int type;
    struct opengpu_buffer shader,texture_records,storage_records,image_records,counter_records,uniform_snapshot;
    struct opengpu_resource_binding counter_source;
    struct opengpu_buffer depth,vertex_shader,render_desc,commands;
    struct opengpu_kernel_launch kernel; struct opengpu_command_events events;
    struct drm_gem_object **objects; u32 object_count;
    struct opengpu_job_temporary_mapping temporary_mappings[OPENGPU_MAX_TEMP_MAPPINGS]; u32 temporary_mapping_count;
};
struct opengpu_file { int lock; struct opengpu_render_context *context; struct drm_gem_object *handles[64]; };
struct drm_device { struct opengpu_device *dev; };
struct drm_file { struct opengpu_file *driver_priv; u64 client_id; };
struct drm_syncobj { struct dma_fence *fence; };
struct drm_exec { struct dma_resv *locked[64]; unsigned count; };
struct opengpu_fence { struct dma_fence base; };
static unsigned buffers,allocation,fail_allocation,mapping,unmaps,queued_count,read_signals,peak_locks,heap_allocation,fail_heap;
static int map_error,unmap_error,invalidate_error,copy_error,input_error,wait_error,enqueue_error,dep_error;
static struct opengpu_sched_job *queued[64];
static struct drm_syncobj input_sync,output_sync;
static struct drm_gem_object *fail_wait_object;
static unsigned input_gets,input_waits,input_puts;
static const int opengpu_fence_ops;
static void *kzalloc(size_t n,int f) { (void)f;heap_allocation++;return heap_allocation==fail_heap?NULL:calloc(1,n); }
#define kvzalloc kzalloc
#define kfree free
#define kvfree free
static void *kcalloc(size_t n,size_t z,int f) { return kzalloc(n*z,f); }
static struct dma_fence *dma_fence_get(struct dma_fence *f) { if(f){assert(f->refs>0);f->refs++;} return f; }
static void dma_fence_put(struct dma_fence *f) { if(f){assert(f->refs>0); if(f==input_sync.fence)input_puts++; if(!--f->refs && f->heap)free(f);} }
static void dma_fence_init(struct dma_fence *f,const int *o,int *l,u64 c,u64 s)
{ (void)o;(void)l;(void)c;*f=(struct dma_fence){.seqno=s,.refs=1,.heap=true}; }
static void dma_fence_set_error(struct dma_fence *f,int e) { f->error=e; }
static struct opengpu_device *signal_device;
static void dma_fence_signal_locked(struct dma_fence *f)
{ assert(signal_device->pops && !(signal_device->regs[GPU_REG_UCMD_STATUS/4]&GPU_UCMD_STATUS_COMPLETION)); assert(!f->signaled); f->signaled=true; }
static struct opengpu_device *dev_get_drvdata(struct opengpu_device *d) { return d; }
static struct drm_gem_object *drm_gem_object_lookup(struct drm_file *f,u32 h)
{ struct drm_gem_object *o=h<ARRAY_SIZE(f->driver_priv->handles)?f->driver_priv->handles[h]:NULL; if(o)o->refs++; return o; }
static void drm_gem_object_put(struct drm_gem_object *o) { assert(o->refs>0);o->refs--; }
static void drm_gem_object_get(struct drm_gem_object *o) { assert(o->refs>0);o->refs++; }
static struct drm_gem_dma_object *to_drm_gem_dma_obj(struct drm_gem_object *o) { return (struct drm_gem_dma_object *)&o->vaddr; }
static enum opengpu_backing_kind opengpu_gem_backing_step(const void *p,const void **next)
{ const struct drm_gem_object *o=p;if(o->import_attach){*next=o->import_attach;return OPENGPU_BACKING_FOREIGN;}return OPENGPU_BACKING_LOCAL; }
#include "uniform_gem.inc"
static int opengpu_gem_backings_disjoint(struct drm_gem_object *const *o,unsigned n)
{ for(unsigned i=0;i<n;i++)for(unsigned j=0;j<i;j++)if(o[i]==o[j])return -EINVAL;return 0; }
static int copy_from_user(void *d,const void *s,size_t z) { if(copy_error)return 1;memcpy(d,s,z);return 0; }
static const void *memchr_inv(const void *p,int v,size_t n) { const u8 *b=p;while(n--){if(*b!=(u8)v)return b;b++;}return NULL; }
static struct opengpu_render_context *opengpu_context_lookup(struct opengpu_file *f,u32 id) { return id==f->context->id?f->context:NULL; }
static struct opengpu_resource_binding *opengpu_binding_lookup(struct opengpu_render_context *c,u32 slot,u32 type)
{ return slot && slot<=OPENGPU_MAX_RESOURCE_BINDINGS && c->bindings[slot-1] && c->bindings[slot-1]->type==type?c->bindings[slot-1]:NULL; }
static void opengpu_context_get(struct opengpu_render_context *c) { assert(c->refs>0);c->refs++; }
static void opengpu_context_put(struct opengpu_render_context *c) { assert(c->refs>0);c->refs--; }
static int opengpu_command_events_init(struct opengpu_device *g,struct opengpu_command_events *e,u32 f,u32 w,u32 s)
{ (void)g;if((f&~3u)||w||s)return -EINVAL;e->flags=f;return 0; }
static struct drm_syncobj *drm_syncobj_find(struct drm_file *f,u32 h) { (void)f;return h==2?&output_sync:NULL; }
static void drm_syncobj_put(struct drm_syncobj *s) { (void)s; }
static int drm_syncobj_find_fence(struct drm_file *f,u32 h,u64 p,u64 flags,struct dma_fence **out)
{ (void)f;assert(!p&&!flags);input_gets++;if(input_error)return input_error;if(h!=1)return -ENOENT;*out=dma_fence_get(input_sync.fence);return 0; }
static int opengpu_hw_fence_wait(struct opengpu_device *g,struct dma_fence *f,unsigned t,bool i)
{ (void)g;assert(f==input_sync.fence&&t==OPENGPU_DRAW_WAIT_MS+100&&i);input_waits++;if(wait_error)return wait_error;f->signaled=true;return f->error; }
static void drm_exec_init(struct drm_exec *e,int f,int n) { (void)f;(void)n;memset(e,0,sizeof(*e)); }
static int drm_exec_prepare_obj(struct drm_exec *e,struct drm_gem_object *o,int n)
{ (void)n;for(unsigned i=0;i<e->count;i++)if(e->locked[i]==o->resv)return 0;assert(!o->resv->locked&&e->count<ARRAY_SIZE(e->locked));o->resv->locked=true;e->locked[e->count++]=o->resv;if(e->count>peak_locks)peak_locks=e->count;return 0; }
static void drm_exec_fini(struct drm_exec *e) { for(unsigned i=0;i<e->count;i++){assert(e->locked[i]->locked);e->locked[i]->locked=false;} }
static int opengpu_wait_reservation(struct drm_gem_object *o,int usage)
{ assert(o->resv->locked&&usage==DMA_RESV_USAGE_WRITE);o->resv->waits++;if(o==fail_wait_object)return -EIO;if(wait_error)return wait_error;/* The producer completes while the wait is in progress. */if(o->resv->producer){memset(o->vaddr,0xa5,o->size);o->resv->producer=false;}return 0; }
static int opengpu_buffer_alloc(struct opengpu_device *g,struct opengpu_buffer *b,size_t z)
{ (void)g;allocation++;if(fail_allocation==allocation)return -ENOMEM;b->cpu=calloc(1,z);if(!b->cpu)return -ENOMEM;b->dma=0x10000000ULL+allocation*0x40000ULL;b->size=z;buffers++;return 0; }
static void opengpu_buffer_free(struct opengpu_device *g,struct opengpu_buffer *b)
{ (void)g;if(b->cpu){assert(buffers);buffers--;free(b->cpu);memset(b,0,sizeof(*b));} }
static int drm_sched_job_init(struct drm_sched_job *j,struct drm_sched_entity *e,int c,struct opengpu_file *f,u64 id)
{ (void)e;(void)c;(void)f;(void)id;j->s_fence=calloc(1,sizeof(*j->s_fence));assert(j->s_fence);j->s_fence->finished=(struct dma_fence){.seqno=queued_count+1,.refs=1,.heap=true};return 0; }
static void drm_sched_job_cleanup(struct drm_sched_job *j)
{ for(unsigned i=0;i<j->wait_count;i++)dma_fence_put(j->waits[i]);j->wait_count=0;/* Model scheduler cancellation/error completion before retirement. */j->s_fence->finished.signaled=true;dma_fence_put(&j->s_fence->finished);j->s_fence=NULL; }
static int drm_sched_job_add_syncobj_dependency(struct drm_sched_job *j,struct drm_file *f,u32 id,int p)
{ (void)j;(void)f;(void)id;(void)p;assert(!"uniform input fence was looked up twice");return -EINVAL; }
static int drm_sched_job_add_resv_dependencies(struct drm_sched_job *j,struct dma_resv *r,int usage)
{
    assert(r->locked);assert(usage==DMA_RESV_USAGE_READ||usage==DMA_RESV_USAGE_WRITE);
    r->wait_usage=usage;
    if(dep_error)return dep_error;
    struct dma_fence *fences[]={r->prior_write,usage==DMA_RESV_USAGE_READ?r->prior_read:NULL,
        usage==DMA_RESV_USAGE_READ||r->last_usage==DMA_RESV_USAGE_WRITE?r->last:NULL};
    for(unsigned i=0;i<ARRAY_SIZE(fences);i++) {
        if(!fences[i]||fences[i]->signaled)continue;
        unsigned n;for(n=0;n<j->wait_count;n++)if(j->waits[n]==fences[i])break;
        if(n<j->wait_count)continue;
        assert(j->wait_count<ARRAY_SIZE(j->waits));j->waits[j->wait_count++]=dma_fence_get(fences[i]);
    }
    return 0;
}
static void drm_sched_job_arm(struct drm_sched_job *j) { (void)j; }
static void drm_syncobj_replace_fence(struct drm_syncobj *s,struct dma_fence *f) { dma_fence_put(s->fence);s->fence=dma_fence_get(f); }
static void dma_resv_add_fence(struct dma_resv *r,struct dma_fence *f,int usage)
{ assert(r->locked);if(usage==DMA_RESV_USAGE_READ){assert(!r->producer);r->reads++;read_signals++;}else {assert(usage==DMA_RESV_USAGE_WRITE);r->writes++;}r->last_usage=usage;dma_fence_put(r->last);r->last=dma_fence_get(f); }
static void drm_sched_entity_push_job(struct drm_sched_job *b)
{ assert(!enqueue_error&&queued_count<ARRAY_SIZE(queued));queued[queued_count++]=(struct opengpu_sched_job *)b; }
static int opengpu_mmu_vm_unmap(struct opengpu_device *g,struct opengpu_vm *v,u64 a,size_t z)
;
static void opengpu_tessellation_job_release(struct opengpu_sched_job *j) { (void)j;assert(0); }
static struct opengpu_sched_job *opengpu_sched_job_from_base(struct drm_sched_job *b) { return (struct opengpu_sched_job *)b; }
#include "uniform_retire.inc"
static int opengpu_mmu_vm_map(struct opengpu_device *g,struct opengpu_vm *v,u64 a,u64 p,size_t z,u32 policy)
;
static struct { u32 root; u64 va,pa,size; bool live; } vm_maps[512];
static unsigned vm_map_count,fail_map_at;
static int opengpu_mmu_vm_map(struct opengpu_device *g,struct opengpu_vm *v,u64 a,u64 p,size_t z,u32 policy)
{
    (void)g;assert(v->enabled&&a&&p&&z&&policy==OPENGPU_MMU_POLICY_UNCACHED);mapping++;
    if(map_error)return map_error;
    if(fail_map_at==mapping)return -ENOMEM;
    for(unsigned i=0;i<vm_map_count;i++)if(vm_maps[i].live&&vm_maps[i].root==v->root)
        assert(a+z<=vm_maps[i].va||vm_maps[i].va+vm_maps[i].size<=a);
    assert(vm_map_count<ARRAY_SIZE(vm_maps));
    vm_maps[vm_map_count].root=v->root;vm_maps[vm_map_count].va=a;
    vm_maps[vm_map_count].pa=p;vm_maps[vm_map_count].size=z;
    vm_maps[vm_map_count++].live=true;return 0;
}
static int opengpu_mmu_vm_unmap(struct opengpu_device *g,struct opengpu_vm *v,u64 a,size_t z)
{
    (void)g;assert(v->enabled&&a&&z);unmaps++;if(unmap_error)return unmap_error;
    u64 lo=a&~4095ull,hi=(a+z+4095ull)&~4095ull;
    for(unsigned i=0;i<vm_map_count;i++)if(vm_maps[i].live&&vm_maps[i].root==v->root&&
        vm_maps[i].va>=lo&&vm_maps[i].va+vm_maps[i].size<=hi)vm_maps[i].live=false;
    return 0;
}
static int opengpu_binding_map_code(struct opengpu_device *g,struct opengpu_render_context *c,u64 p,size_t z,unsigned slot,u64 *a)
{ (void)g;assert(c->vm.enabled&&p&&z&&slot==1);*a=0xc0400000u;return 0; }
static struct dma_fence *opengpu_tessellation_job_run(struct opengpu_sched_job *j) { (void)j;assert(0);return NULL; }
static int opengpu_hw_unified_quiesce_timeout(struct opengpu_device *g) { return g->hw.unified_fence||g->hw.active_fence||g->hw.aborting?-EBUSY:0; }
static int opengpu_hw_wait_idle_locked(struct opengpu_device *g) { assert(g->hw.submit_lock);return 0; }
static int opengpu_hw_activate_vm_locked(struct opengpu_device *g,const struct opengpu_vm *v) { assert(g->hw.submit_lock&&v&&v->enabled);g->active_root=v->root;return 0; }
static u32 opengpu_reg_read(struct opengpu_device *g,u32 a) { assert(a/4<ARRAY_SIZE(g->regs));return g->regs[a/4]; }
static void opengpu_reg_write(struct opengpu_device *g,u32 a,u32 v)
{ assert(a/4<ARRAY_SIZE(g->regs));if(a==GPU_REG_UCMD_COMPLETION_POP){assert(g->hw.fence_lock&&v==1);g->pops++;g->regs[GPU_REG_UCMD_STATUS/4]=GPU_UCMD_STATUS_READY;}else g->regs[a/4]=v;if(a>=0x440&&a<0x460)g->uniform_writes++; }
static u32 opengpu_mmio_read_callback(void *g,u32 a) { return opengpu_reg_read(g,a); }
/* Keep the mock MMIO boundary opaque, like the kernel's iowrite32 call. */
static __attribute__((noinline)) void opengpu_mmio_write_callback(void *g,u32 a,u32 v) { opengpu_reg_write(g,a,v); }
static u32 opengpu_next_job_id(struct opengpu_device *g) { return g->kicks+1; }
static void opengpu_hw_unified_kick(struct opengpu_device *g,struct dma_fence *f)
{ assert(g->hw.submit_lock&&f==g->hw.unified_fence);for(unsigned i=0;i<8;i++)g->captured[i]=g->regs[0x440/4+i];g->kicks++; }
#include "uniform_submit.inc"
static bool opengpu_hw_execution_busy(struct opengpu_device *g) { return g->hw.unified_fence!=NULL; }
static int opengpu_hw_invalidate_buffer_locked(struct opengpu_device *g,const struct opengpu_buffer *b)
{ assert(g->hw.submit_lock&&b&&b->cpu);g->invalidations++;return invalidate_error; }
#include "uniform_hw.inc"
#include "uniform_job.inc"
static void opengpu_hw_record_fault_locked(struct opengpu_device *g,int error,u32 flags,u32 actual_id,u32 opcode,u32 expected_id,u32 expected_opcode,u32 status,u64 processed,u64 expected)
{ assert(actual_id==expected_id&&opcode==expected_opcode&&processed==expected);g->hw.last_fault.error=error;g->hw.last_fault.flags=flags;g->hw.last_fault.status=status; }
static void cancel_delayed_work(int *w) { *w=0; }
static void mod_delayed_work(void *q,int *w,u32 d) { (void)q;*w=(int)d; }
#include "uniform_drain.inc"
#include "uniform_ioctl.inc"

static u32 word(const void *p,unsigned i) { const u8 *b=(const u8 *)p+4*i;return b[0]|(u32)b[1]<<8|(u32)b[2]<<16|(u32)b[3]<<24; }
static void release_fence(struct dma_fence **f) { dma_fence_put(*f);*f=NULL; }
static bool scheduler_ready(struct opengpu_sched_job *j)
{ for(unsigned i=0;i<j->base.wait_count;i++)if(!j->base.waits[i]->signaled)return false;return true; }
static u32 *mapped_counter_words(struct opengpu_sched_job *j)
{
    const struct opengpu_resource_binding *s=&j->counter_source;
    assert(s->object&&j->kernel.counter_enabled);
    assert(word(j->counter_records.cpu,OPENGPU_STORAGE_DESCRIPTOR_BASE_WORD)==s->va);
    assert(word(j->counter_records.cpu,OPENGPU_STORAGE_DESCRIPTOR_LIMIT_WORD)==s->va+s->size);
    for(unsigned i=0;i<vm_map_count;i++)if(vm_maps[i].live&&vm_maps[i].root==j->vm->root&&
        s->va>=vm_maps[i].va&&s->va+s->size<=vm_maps[i].va+vm_maps[i].size) {
        u64 pa=vm_maps[i].pa+s->va-vm_maps[i].va;
        assert(pa==s->object->dma_addr+s->offset&&pa+s->size<=s->object->dma_addr+s->object->size);
        return (u32 *)((u8 *)s->object->vaddr+pa-s->object->dma_addr);
    }
    assert(!"counter source has no live original-GEM mapping");return NULL;
}
static void finish(struct opengpu_device *g,struct opengpu_sched_job *j,int error)
{
    assert(scheduler_ready(j));
    struct dma_fence *hw=opengpu_job_run(j);
    assert((intptr_t)hw>0&&!hw->signaled&&g->active_root==j->context->vm.root);
    unsigned pinned=buffers,refs=j->objects[j->object_count-1]->refs;
    if(j->counter_source.object) {
        /* Explicit device-reply model: resolve the actual mapped VA to the
         * original GEM, then update it before completion. This is not RTL. */
        u32 *values=mapped_counter_words(j);
        for(unsigned i=0;i<j->counter_source.size/4;i++)values[i]++;
        assert(j->counter_source.object->refs>=2&&!j->base.s_fence->finished.signaled);
    }
    for(unsigned n=0;n<7;n++){assert(!opengpu_hw_unified_drain(g));assert(buffers==pinned&&j->objects[j->object_count-1]->refs== (int)refs);}
    g->regs[GPU_REG_UCMD_STATUS/4]|=GPU_UCMD_STATUS_COMPLETION;
    g->regs[GPU_REG_UCMD_COMPLETION/4]=g->hw.unified_command_id|(GPU_UCMD_OP_KERNEL<<8)|
        (error?(GPU_UCMD_KERNEL_EXECUTION_FAILED<<11):(1u<<15));
    /* Retain a visible software completion without POP; another root cannot
     * stage a command over it, and no backing is released. */
    for(unsigned n=0;n<7;n++) {
        struct dma_fence *rejected=NULL;u32 root=g->active_root;
        mutex_lock(&g->hw.submit_lock);
        assert(opengpu_hw_unified_submit_locked(g,&j->kernel,NULL,GPU_UCMD_OP_KERNEL,
            0,0,0,0,0,0,0,0,0,0,j->vm,0,&rejected)==-EBUSY&&!rejected);
        mutex_unlock(&g->hw.submit_lock);
        assert(!hw->signaled&&g->active_root==root&&buffers==pinned&&j->objects[j->object_count-1]->refs==(int)refs);
    }
    signal_device=g;
    assert(opengpu_hw_unified_drain(g)&&hw->signaled&&hw->error==error);
    if(error)assert(g->hw.last_fault.status==GPU_UCMD_KERNEL_EXECUTION_FAILED&&g->hw.last_fault.flags==OPENGPU_FAULT_COMPLETION_ERROR);
    assert(buffers==pinned&&(!j->kernel.uniform_enabled||j->uniform_snapshot.cpu));
    dma_fence_put(hw);
    /* Model the DRM scheduler transferring the hardware result to finished. */
    j->base.s_fence->finished.error=error;j->base.s_fence->finished.signaled=true;
    opengpu_sched_free_job(&j->base);
}

static void counter_source_cases(struct drm_device *drm,struct drm_file *file,
    struct opengpu_render_context *context,struct drm_opengpu_compute_uniform_resources full)
{
    struct opengpu_device *g=drm->dev;struct opengpu_file *rf=file->driver_priv;
    struct dma_resv source_resv={0};
    struct drm_gem_object source={.resv=&source_resv,.dma_addr=0x60000000,
        .size=8192,.refs=1,.vaddr=calloc(1,8192)};
    assert(source.vaddr);rf->handles[51]=&source;
    struct drm_opengpu_compute_counter_source_resources request={.resources=full,
        .source={.offset=60,.handle=51,.count=8},.version=1};
    struct drm_opengpu_compute_counter_source_resources valid=request;
    struct dma_fence writer={.refs=1},reader={.refs=1};
    unsigned get_count=input_gets,wait_count=input_waits,put_count=input_puts;
    source_resv.prior_write=&writer;source_resv.prior_read=&reader;
    u32 *values=(u32 *)((u8 *)source.vaddr+60);
    for(unsigned i=0;i<8;i++)values[i]=1000+i;
    assert(!opengpu_compute_counter_source_resources_ioctl(drm,&request,file));
    struct opengpu_sched_job *first=queued[queued_count-1];
    assert(first->object_count==51&&peak_locks==52&&source.refs==2);
    assert(first->uniform_snapshot.size==204800&&first->counter_source.object==&source);
    assert(first->counter_source.dma==0x6000003c&&first->counter_source.va==0xb1c0003c);
    assert(first->counter_source.size==32&&source_resv.waits==0);
    assert(source_resv.wait_usage==DMA_RESV_USAGE_READ);
    assert(source_resv.last==&first->base.s_fence->finished&&source_resv.writes==1&&!source_resv.reads);
    assert(writer.refs==2&&reader.refs==2&&!scheduler_ready(first));
    for(unsigned i=0;i<first->object_count;i++)for(unsigned j=0;j<i;j++)assert(first->objects[i]!=first->objects[j]);
    for(unsigned i=0;i<13;i++)assert(!memcmp((u8 *)first->uniform_snapshot.cpu+4096+i*16384,
        rf->handles[i+2]->vaddr,rf->handles[i+2]->size));
    assert(word(first->counter_records.cpu,13)==0xb1c00000&&word(first->counter_records.cpu,14)==0xb1c00080);

    /* Same mutable BO, independently retained root/snapshot: the second job
     * must wait for both previous CPU/GPU owners and the first finished fence. */
    struct opengpu_render_context alternate=*context;
    alternate.id=2;alternate.vm.root=0x80000999;alternate.refs=1;alternate.last_fence=NULL;
    request.resources.resources.launch.context_id=2;rf->context=&alternate;
    assert(!opengpu_compute_counter_source_resources_ioctl(drm,&request,file));
    struct opengpu_sched_job *second=queued[queued_count-1];
    assert(second->object_count==51&&second->vm==&alternate.vm&&source.refs==3&&!scheduler_ready(second));
    assert(input_gets==get_count+2&&input_waits==wait_count+2&&input_puts==put_count+2);
    assert(first->uniform_snapshot.cpu!=second->uniform_snapshot.cpu);
    /* A later read-only texture consumer uses the same original reservation,
     * waits the second WRITE fence and publishes READ while the jobs are held. */
    struct opengpu_resource_binding *old_texture=context->bindings[2],consumer_view=*old_texture;
    consumer_view.object=&source;consumer_view.dma=source.dma_addr;consumer_view.size=source.size;
    context->bindings[2]=&consumer_view;rf->context=context;
    assert(!opengpu_compute_uniform_resources_ioctl(drm,&full,file));
    struct opengpu_sched_job *consumer=queued[queued_count-1];context->bindings[2]=old_texture;
    assert(source.refs==4&&source_resv.reads==1&&source_resv.last_usage==DMA_RESV_USAGE_READ);
    assert(source_resv.wait_usage==DMA_RESV_USAGE_WRITE&&!scheduler_ready(consumer));
    request.source.handle=63;request.source.offset=4092; /* Captured metadata must not move either job. */
    writer.signaled=true;assert(!scheduler_ready(first));
    for(unsigned i=0;i<8;i++)values[i]=2000+i; /* Real producer completes after enqueue; data is not snapshotted. */
    reader.signaled=true;assert(scheduler_ready(first)&&!scheduler_ready(second));
    finish(g,first,0);assert(source.refs==3&&scheduler_ready(second)&&!scheduler_ready(consumer));
    for(unsigned i=0;i<8;i++)assert(values[i]==2001+i);
    finish(g,second,-EIO);assert(source.refs==2&&writer.refs==2&&reader.refs==1&&scheduler_ready(consumer));
    for(unsigned i=0;i<8;i++)assert(values[i]==2002+i); /* An acknowledged update survives an execution fault. */
    finish(g,consumer,0);assert(source.refs==1&&writer.refs==1&&reader.refs==1);
    assert(!g->regs[OPENGPU_ATOMIC_COUNTER_COMMAND_REG_CONTROL/4]);
    for(unsigned i=0;i<8;i++)assert(values[i]==2002+i);
    source_resv.prior_write=source_resv.prior_read=NULL;
    rf->context=context;request=valid;

    /* Exercise the actual common launch selection with all resource domains.
     * Only discovered shared admission accepts the complete 512-word shader;
     * an invalid initialization never enqueues or retains any of 51 objects.
     * This fixture checks admission/ownership, not shader execution. */
    struct opengpu_resource_binding *shader_binding=context->bindings[0];
    struct drm_gem_object *shader_object=shader_binding->object;
    void *original_code=shader_object->vaddr;
    u64 original_bytes=shader_binding->size,original_object_bytes=shader_object->size;
    u32 full_code[512];struct opg_shared_layout shared_layout;
    assert(opg_shared_program(full_code,false,0xa1100000u,&shared_layout)==512);
    shader_object->vaddr=full_code;shader_binding->size=shader_object->size=sizeof(full_code);
    u32 original_caps=g->hw.capabilities,original_flags=g->hw.compute_resource_flags;
    g->hw.capabilities|=GPU_CAP_COMPUTE_SCALAR_FPU;
    g->hw.compute_resource_flags|=OPENGPU_COMPUTE_RESOURCE_WORD_ATOMICS |
        OPENGPU_COMPUTE_RESOURCE_IMAGE_ATOMICS | OPENGPU_COMPUTE_RESOURCE_STORAGE_ATOMICS;
    unsigned shared_before=queued_count;
    assert(opengpu_compute_counter_source_resources_ioctl(drm,&request,file)==-EINVAL);
    assert(queued_count==shared_before&&source.refs==1&&!buffers);
    g->hw.compute_shared_limits=(struct opengpu_compute_shared_limits){OPG_SHARED_BASE,16384,16,true};
    assert(!opengpu_compute_counter_source_resources_ioctl(drm,&request,file));
    struct opengpu_sched_job *shared_job=queued[queued_count-1];
    assert(shared_job->object_count==51&&!memcmp(shared_job->shader.cpu,full_code,sizeof(full_code)));
    opengpu_sched_free_job(&shared_job->base);assert(source.refs==1&&!buffers);
    full_code[shared_layout.barrier]=0x00000013u;shared_before=queued_count;
    assert(opengpu_compute_counter_source_resources_ioctl(drm,&request,file)==-EINVAL);
    assert(queued_count==shared_before&&source.refs==1&&!buffers);
    g->hw.compute_shared_limits=(struct opengpu_compute_shared_limits){0};
    g->hw.capabilities=original_caps;g->hw.compute_resource_flags=original_flags;
    shader_object->vaddr=original_code;shader_binding->size=original_bytes;shader_object->size=original_object_bytes;

    unsigned before=queued_count,reads=read_signals;
    for(unsigned bad=0;bad<29;bad++) {
        request=valid;struct opengpu_resource_binding *texture=context->bindings[2];
        u64 dma=source.dma_addr;struct dma_resv *resv=source.resv;
        struct drm_gem_object *handle=rf->handles[51];size_t size=source.size;
        switch(bad) {
        case 0:request.version=0;break;case 1:request.version=2;break;
        case 2:request.pad[0]=1;break;case 3:request.pad[1]=1;break;case 4:request.pad[2]=1;break;
        case 5:request.source.handle=0;break;case 6:request.source.handle=63;break;
        case 7:request.source.count=0;break;case 8:request.source.count=9;break;
        case 9:request.source.offset=2;break;case 10:request.source.offset=8192-4;break;
        case 11:request.source.offset=UINT64_MAX-3;break;
        case 12:source.import_attach=&source;break;
        case 13:source.dma_addr=0xfffff000;break;case 14:source.dma_addr++;break;case 15:source.size--;break;
        case 16:request.resources.resources.counter.count=8;break;
        case 17:request.resources.resources.resource_flags|=OPENGPU_COMPUTE_UNIFORM_RESOURCE_COUNTERS;break;
        case 18:request.resources.resources.resource_flags|=8;break;
        case 19:request.resources.version=2;break;case 20:request.resources.pad[1]=1;break;
        case 21:request.resources.blocks[0].handle=51;break;
        case 22:rf->handles[51]=texture->object;break;
        case 23:source.resv=texture->object->resv;break;
        case 24:source.dma_addr=texture->object->dma_addr-4096;request.source.offset=0;break;
        case 25:g->hw.compute_counter_transport=false;break;
        case 26:g->hw.capabilities&=~GPU_CAP_PROTECTED_VM_STATE;break;
        case 27:context->vm.enabled=false;break;
        case 28:g->hw.compute_uniform_transport=false;break;
        }
        assert(opengpu_compute_counter_source_resources_ioctl(drm,&request,file)<0);
        source.import_attach=NULL;source.dma_addr=dma;source.resv=resv;source.size=size;rf->handles[51]=handle;
        g->hw.compute_counter_transport=g->hw.compute_uniform_transport=true;g->hw.capabilities=GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE;
        context->vm.enabled=true;
        assert(queued_count==before&&read_signals==reads&&source.refs==1&&!buffers&&!rf->lock&&context->refs==1);
        for(unsigned i=2;i<15;i++)assert(rf->handles[i]->refs==1);
    }
    request=valid;
    for(unsigned n=1;n<=6;n++) {
        fail_allocation=allocation+n;
        assert(opengpu_compute_counter_source_resources_ioctl(drm,&request,file)==-ENOMEM);
        fail_allocation=0;assert(queued_count==before&&source.refs==1&&!buffers);
    }
    for(unsigned n=1;n<=7;n++) {
        fail_heap=heap_allocation+n;
        assert(opengpu_compute_counter_source_resources_ioctl(drm,&request,file)==-ENOMEM);
        fail_heap=0;assert(queued_count==before&&source.refs==1&&!buffers);
    }
    dep_error=-EIO;
    assert(opengpu_compute_counter_source_resources_ioctl(drm,&request,file)==-EIO);
    dep_error=0;assert(queued_count==before&&source.refs==1&&!buffers);
    assert(!opengpu_compute_counter_source_resources_ioctl(drm,&request,file));
    struct opengpu_sched_job *cancelled=queued[queued_count-1];unsigned maps=mapping;
    opengpu_sched_free_job(&cancelled->base);
    assert(mapping==maps&&source.refs==1&&!buffers); /* Cancel before run never maps data. */
    for(unsigned n=1;n<=6;n++) {
        assert(!opengpu_compute_counter_source_resources_ioctl(drm,&request,file));
        struct opengpu_sched_job *failed=queued[queued_count-1];fail_map_at=mapping+n;
        assert(opengpu_job_run(failed)==ERR_PTR(-ENOMEM));fail_map_at=0;
        opengpu_sched_free_job(&failed->base);assert(source.refs==1&&!buffers);
    }
    assert(!opengpu_compute_counter_source_resources_ioctl(drm,&request,file));
    struct opengpu_sched_job *failed=queued[queued_count-1];invalidate_error=-EIO;
    assert(opengpu_job_run(failed)==ERR_PTR(-EIO));invalidate_error=0;
    opengpu_sched_free_job(&failed->base);assert(source.refs==1&&!buffers);

    /* GUM1 is unnecessary for the source-only profile. Retain exact nested
     * versions/zeros/flags and admit real counter opcodes through that profile. */
    u32 saved[32];struct drm_gem_object *shader=context->bindings[0]->object;
    assert(shader->size==sizeof(saved));memcpy(saved,shader->vaddr,sizeof(saved));
    memset(shader->vaddr,0,sizeof(saved));u32 *code=shader->vaddr;
    code[0]=0xc100f057u;code[1]=0x5e003157u;code[2]=0x4a01032bu;code[3]=OPENGPU_SHADER_CEASE;
    request=valid;request.resources.active_mask=0;
    memset(request.resources.blocks,0,sizeof(request.resources.blocks));memset(&request.resources.defaults,0,sizeof(request.resources.defaults));
    g->hw.compute_uniform_transport=false;
    for(unsigned bad=0;bad<7;bad++) {
        struct drm_opengpu_compute_counter_source_resources wrong=request;
        switch(bad) {
        case 0:wrong.resources.blocks[0].handle=2;break;case 1:wrong.resources.defaults.bytes=4;break;
        case 2:wrong.resources.version=0;break;case 3:wrong.resources.pad[0]=1;break;
        case 4:wrong.resources.resources.resource_flags|=8;break;
        case 5:wrong.resources.resources.counter.slot=38;break;
        case 6:wrong.resources.resources.resource_flags&=~OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES;break;
        }
        assert(opengpu_compute_counter_source_resources_ioctl(drm,&wrong,file)==-EINVAL&&source.refs==1&&!buffers);
    }
    assert(!opengpu_compute_counter_source_resources_ioctl(drm,&request,file));
    struct opengpu_sched_job *bare=queued[queued_count-1];assert(!bare->kernel.uniform_enabled&&bare->object_count==38);
    finish(g,bare,0);assert(source.refs==1&&!buffers);
    request.source.offset=8192-4;request.source.count=1;
    assert(!opengpu_compute_counter_source_resources_ioctl(drm,&request,file));
    finish(g,queued[queued_count-1],0);assert(((u32 *)source.vaddr)[2047]==1&&source.refs==1);
    g->hw.compute_uniform_transport=true;memcpy(shader->vaddr,saved,sizeof(saved));request=valid;

    /* Fresh original backing and root, two pages, after the old VA was revoked. */
    struct dma_resv fresh_resv={0};u32 fresh_data[2048]={0};
    struct drm_gem_object fresh={.resv=&fresh_resv,.dma_addr=0x61000000,.size=8192,.refs=1,.vaddr=fresh_data};
    rf->handles[51]=&fresh;request.source.offset=4092;
    alternate.vm.root=0x80000aaa;request.resources.resources.launch.context_id=2;rf->context=&alternate;
    assert(!opengpu_compute_counter_source_resources_ioctl(drm,&request,file));
    struct opengpu_sched_job *reused=queued[queued_count-1];
    assert(reused->counter_source.object==&fresh&&reused->counter_source.va==0xb1c00ffc);
    finish(g,reused,0);assert(fresh.refs==1&&alternate.refs==1&&source.refs==1);
    for(unsigned i=0;i<ARRAY_SIZE(fresh_data);i++)assert(fresh_data[i]==(u32)(i>=1023&&i<1031));
    rf->context=context;rf->handles[51]=&source;request=valid;

    assert(!opengpu_compute_counter_source_resources_ioctl(drm,&request,file));
    struct opengpu_sched_job *q=queued[queued_count-1];unmap_error=-EIO;
    finish(g,q,0);assert(g->hw.wedged&&source.refs==2&&q->counter_source.object==&source&&q->temporary_mapping_count==7);
    /* An explicit external reset is the only simulated reclamation of a
     * quarantined job. The borrowed GEM must never pass through buffer_free. */
    unmap_error=0;
    for(unsigned i=0;i<q->temporary_mapping_count;i++)assert(!opengpu_mmu_vm_unmap(g,&context->vm,q->temporary_mappings[i].va,q->temporary_mappings[i].size));
    for(unsigned i=0;i<q->object_count;i++)drm_gem_object_put(q->objects[i]);
    free(q->objects);
    opengpu_buffer_free(g,&q->shader);opengpu_buffer_free(g,&q->texture_records);opengpu_buffer_free(g,&q->storage_records);
    opengpu_buffer_free(g,&q->image_records);opengpu_buffer_free(g,&q->counter_records);opengpu_buffer_free(g,&q->uniform_snapshot);
    opengpu_context_put(q->context);free(q);g->hw.wedged=false;
    assert(source.refs==1&&!buffers&&context->refs==1);
    for(unsigned i=0;i<vm_map_count;i++)assert(!vm_maps[i].live);
    release_fence(&source_resv.last);release_fence(&fresh_resv.last);release_fence(&alternate.last_fence);
    free(source.vaddr);rf->handles[51]=NULL;
    puts("independent counter source: all12UB/defaults/32textures/4images/4x128MiB SSBOs +8counters, 51refs/52locks, original-GEM WRITE ownership/two roots/2pages/fault/cancel/quarantine/reuse PASS (mock kernel/MMIO; no GPU execution)");
}

int main(void)
{
    u32 code[32]={(7u<<25)|(5u<<7)|0x0bu,0x0050a023u,0xc1087057u,
        0x5e003157u,(7u<<26)|(1u<<25)|(2u<<20)|(2u<<15)|(3u<<7)|0x2bu,OPENGPU_SHADER_CEASE};
    struct dma_resv resv[51]={0};struct drm_gem_object objects[51]={0};
    struct opengpu_resource_binding shader={.object=&objects[0],.dma=0x40000,.size=sizeof(code),.type=OPENGPU_RESOURCE_COMPUTE_SHADER};
    struct opengpu_resource_binding kernarg={.object=&objects[1],.dma=0x50000,.va=0x80000000,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_KERNARG};
    struct opengpu_render_context context={.vm={true,0x80000111},.id=1,.refs=1};
    struct opengpu_render_context second_context={.vm={true,0x80000222},.id=2,.refs=1};
    struct opengpu_file rf={.context=&context};struct drm_file file={.driver_priv=&rf};
    struct opengpu_device gpu={.hw={.capabilities=GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE,
        .compute_uniform_transport=true,.compute_local_limits={128,128,true}}};
    struct drm_device drm={.dev=&gpu};
    struct drm_opengpu_compute_uniform_resources request={.version=1,.active_mask=0xfff,
        .resources.launch={.context_id=1,.shader_slot=1,.kernarg_slot=2,.grid={2,1,1},.local={128,1,1},.in_syncobj=1,.out_syncobj=2}};
    struct dma_fence input={.refs=1};input_sync.fence=&input;
    for(unsigned i=0;i<ARRAY_SIZE(objects);i++)objects[i]=(struct drm_gem_object){.resv=&resv[i],.refs=1,.dma_addr=0x40000+i*0x40000};
    objects[0].vaddr=code;objects[0].size=sizeof(code);objects[0].dma_addr=shader.dma;
    objects[1].size=4096;objects[1].dma_addr=kernarg.dma;
    context.bindings[0]=&shader;context.bindings[1]=&kernarg;
    second_context.bindings[0]=&shader;second_context.bindings[1]=&kernarg;
    for(unsigned i=0;i<13;i++){
        struct drm_opengpu_compute_uniform_view *v=i<12?&request.blocks[i]:&request.defaults;
        unsigned n=i+2;objects[n].size=i<12?16384:4096;objects[n].vaddr=malloc(objects[n].size);assert(objects[n].vaddr);
        for(unsigned w=0;w<objects[n].size/4;w++)((u32 *)objects[n].vaddr)[w]=0x41000000u+i*0x01000000u+w*0x9e3779b1u;
        rf.handles[n]=&objects[n];
        *v=(struct drm_opengpu_compute_uniform_view){.handle=n,.bytes=(u32)objects[n].size};
    }
    resv[2].producer=true;
    gpu.regs[GPU_REG_UCMD_STATUS/4]=GPU_UCMD_STATUS_READY;
    assert(opengpu_compute_uniform_resources_ioctl(&drm,&request,&file)==0&&queued_count==1);
    struct opengpu_sched_job *first=queued[0];
    assert(first->object_count==14&&context.refs==2&&input_gets==1&&input_waits==1&&input_puts==1);
    assert(first->uniform_snapshot.size==204800&&word(first->uniform_snapshot.cpu,0)==OPENGPU_UNIFORM_ROOT_MAGIC);
    assert(word(first->uniform_snapshot.cpu,3)==1&&first->kernel.uniform.context==1);
    assert(word(first->uniform_snapshot.cpu,6)==2&&word(first->uniform_snapshot.cpu,7)==0xfff);
    assert(first->kernel.uniform.root==0xb1800000&&first->kernel.uniform.private_base==kernarg.va);
    for(unsigned i=0;i<13;i++){
        const void *record=(u8 *)first->uniform_snapshot.cpu+(i<12?64+i*64:1600);
        u32 base=word(record,8),offset=base-first->kernel.uniform.root;
        assert(offset==4096+i*16384&&word(record,0)==OPENGPU_UNIFORM_BINDING_MAGIC&&word(record,1)==64&&word(record,2)==1);
        assert(word(record,3)==1&&word(record,6)==(i<12?i:31)&&word(record,7)==4&&word(record,14)==1);
        assert(word(record,9)==base+objects[i+2].size&&!word(record,10));
        assert(word(record,11)==base&&word(record,12)==base+objects[i+2].size&&!word(record,13));
        assert(!memcmp((u8 *)first->uniform_snapshot.cpu+offset,objects[i+2].vaddr,objects[i+2].size));
        assert(resv[i+2].waits==1&&resv[i+2].reads==1&&objects[i+2].refs==2&&!resv[i+2].locked);
        memset(objects[i+2].vaddr,0x73,objects[i+2].size);
    }
    assert(((u8 *)first->uniform_snapshot.cpu)[4096]==0xa5);
    request.blocks[0].offset=UINT64_MAX; /* Poison the caller after successful capture. */
    assert(first->uniform_snapshot.size==204800&&((u8 *)first->uniform_snapshot.cpu)[4096]==0xa5);
    request.blocks[0].offset=0;
    request.resources.launch.context_id=2;rf.context=&second_context;
    assert(opengpu_compute_uniform_resources_ioctl(&drm,&request,&file)==0&&queued_count==2&&context.refs==2&&second_context.refs==2);
    assert(queued[1]->uniform_snapshot.cpu!=first->uniform_snapshot.cpu&&((u8 *)queued[1]->uniform_snapshot.cpu)[4096]==0x73);
    assert(word(queued[1]->uniform_snapshot.cpu,3)==2&&queued[1]->kernel.uniform.context==2);
    finish(&gpu,first,0);
    assert(gpu.kicks==1&&gpu.pops==1&&gpu.captured[0]==0x101&&gpu.captured[1]==0xb1800000&&context.refs==1);
    finish(&gpu,queued[1],-EIO);
    assert(gpu.kicks==2&&gpu.pops==2&&context.refs==1&&second_context.refs==1&&!buffers&&unmaps==4);
    request.resources.launch.context_id=1;rf.context=&context;
    for(unsigned i=2;i<15;i++)assert(objects[i].refs==1&&!resv[i].locked);

    /* Reachable maximum: 13 distinct uniform GEMs + 32 distinct textures +
     * four 128MiB SSBOs, with all four images sharing those storage backings.
     * No permanent binding slot is invented for a fifth counter backing. */
    struct drm_opengpu_compute_texture_view texture_views[32]={0};
    struct opengpu_resource_binding resource_bindings[36]={0};
    struct drm_opengpu_compute_uniform_resources uniform_only=request;
    for(unsigned i=2;i<15;i++)for(unsigned w=0;w<objects[i].size/4;w++)
        ((u32 *)objects[i].vaddr)[w]=0x53000000u+i*0x01000000u+w*0x9e3779b1u;
    gpu.hw.compute_texture_transport=gpu.hw.compute_storage_transport=gpu.hw.compute_image_transport=gpu.hw.compute_counter_transport=true;
    for(unsigned i=0;i<32;i++) {
        unsigned object=i+15,slot=i+3;
        objects[object].size=4096;objects[object].dma_addr=0x02000000+i*0x400000ULL;
        resource_bindings[i]=(struct opengpu_resource_binding){.object=&objects[object],
            .dma=objects[object].dma_addr,.va=0xa0000000ULL+slot*0x400000ULL,.size=4096,.type=OPENGPU_RESOURCE_COMPUTE_TEXTURE};
        struct opengpu_kernarg_va_plan plan;
        assert(!opengpu_texture_va_plan(resource_bindings[i].dma,resource_bindings[i].size,slot,4096,&plan)&&plan.va==resource_bindings[i].va);
        context.bindings[slot-1]=&resource_bindings[i];
        texture_views[i]=(struct drm_opengpu_compute_texture_view){.slot=slot,.format=3,
            .width=4,.height=4,.depth=1,.layers=1,.level_count=1};
        texture_views[i].levels[0]=(struct drm_opengpu_compute_texture_level){
            .width=4,.height=4,.depth=1,.layers=1,.row_pitch=16,.block_bytes=4};
    }
    for(unsigned i=0;i<4;i++) {
        unsigned object=i+47,slot=i+35;
        objects[object].size=0x08000000;objects[object].dma_addr=0x30000000+i*0x08000000ULL;
        resource_bindings[i+32]=(struct opengpu_resource_binding){.object=&objects[object],
            .dma=objects[object].dma_addr,.va=0xd0000000ULL+i*0x08000000ULL,.size=0x08000000,.type=OPENGPU_RESOURCE_COMPUTE_STORAGE};
        struct opengpu_kernarg_va_plan plan;
        assert(!opengpu_storage_va_plan(resource_bindings[i+32].dma,resource_bindings[i+32].size,i,4096,&plan)&&plan.va==resource_bindings[i+32].va);
        context.bindings[slot-1]=&resource_bindings[i+32];
        request.resources.bindings[i]=(struct drm_opengpu_compute_storage_binding){slot,3};
        request.resources.images[i]=(struct drm_opengpu_compute_image_view){.slot=slot,
            .flags=OPENGPU_COMPUTE_IMAGE_READ|OPENGPU_COMPUTE_IMAGE_WRITE,
            .storage_format=OPENGPU_TEXTURE_FORMAT_R32UI,.image_format=OPENGPU_TEXTURE_FORMAT_R32UI,
            .width=4,.height=4,.depth=1,.layers=1,.row_pitch=16};
    }
    request.resources.textures=(uintptr_t)texture_views;request.resources.texture_count=32;
    request.resources.resource_flags=OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE|OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES;
    assert(!opengpu_compute_uniform_resources_ioctl(&drm,&request,&file));
    struct opengpu_sched_job *maximum=queued[queued_count-1];
    assert(maximum->object_count==50&&peak_locks==51&&maximum->uniform_snapshot.size==204800);
    for(unsigned i=0;i<13;i++)assert(!memcmp((u8 *)maximum->uniform_snapshot.cpu+4096+i*16384,objects[i+2].vaddr,objects[i+2].size));
    for(unsigned i=12*64+64;i<24*64+64;i++)assert(!((u8 *)maximum->uniform_snapshot.cpu)[i]);
    for(unsigned i=0;i<maximum->object_count;i++)for(unsigned j=0;j<i;j++)assert(maximum->objects[i]!=maximum->objects[j]);
    for(unsigned i=2;i<15;i++)assert(objects[i].refs==2&&resv[i].last==&maximum->base.s_fence->finished);
    for(unsigned i=15;i<47;i++)assert(objects[i].refs==2&&resv[i].reads==1&&!resv[i].writes);
    for(unsigned i=47;i<51;i++)assert(objects[i].refs==2&&resv[i].writes==1&&!resv[i].reads);
    for(unsigned i=0;i<4;i++)assert(word((u8 *)maximum->storage_records.cpu+i*64,11)==0xd8000000u+i*0x08000000u);
    finish(&gpu,maximum,0);assert(!buffers);

    /* Distinct reservations cannot synchronize overlapping allocations, even
     * when this job only reads the texture and snapshots its uniform source. */
    u64 texture_dma=objects[15].dma_addr;unsigned overlap_queued=queued_count;
    objects[15].dma_addr=resource_bindings[0].dma=objects[2].dma_addr;
    int overlap_result=opengpu_compute_uniform_resources_ioctl(&drm,&request,&file);
    if(overlap_result!=-EINVAL)fprintf(stderr,"different-BO READ texture/uniform DMA overlap admitted: ret=%d queued=%u\n",overlap_result,queued_count-overlap_queued);
    assert(overlap_result==-EINVAL&&queued_count==overlap_queued&&!buffers);
    objects[15].dma_addr=resource_bindings[0].dma=texture_dma;

    /* A uniform and a read-only texture of the same BO retain exactly one
     * strongest READ dependency, despite thirteen separate uniform views. */
    struct opengpu_resource_binding original_texture=resource_bindings[0];
    resource_bindings[0].object=&objects[2];resource_bindings[0].dma=objects[2].dma_addr;
    resource_bindings[0].size=objects[2].size;
    unsigned alias_waits=resv[2].waits,alias_reads=resv[2].reads;
    assert(!opengpu_compute_uniform_resources_ioctl(&drm,&request,&file));
    struct opengpu_sched_job *read_alias=queued[queued_count-1];
    assert(read_alias->object_count==49&&objects[2].refs==2&&resv[2].waits==alias_waits+1&&resv[2].reads==alias_reads+1);
    finish(&gpu,read_alias,0);resource_bindings[0]=original_texture;

    counter_source_cases(&drm,&file,&context,request);
    request.resources.counter=(struct drm_opengpu_compute_counter_binding){.slot=38,.count=8};
    request.resources.resource_flags|=OPENGPU_COMPUTE_UNIFORM_RESOURCE_COUNTERS;
    unsigned before=queued_count;
    assert(opengpu_compute_uniform_resources_ioctl(&drm,&request,&file)==-EINVAL&&queued_count==before&&!buffers);
    /* Existing Linux counter transport has four total storage windows: only
     * three distinct SSBOs coexist with its required disjoint counter view. */
    request.resources.bindings[3]=(struct drm_opengpu_compute_storage_binding){0};
    request.resources.images[3]=request.resources.images[2];
    assert(!opengpu_compute_uniform_resources_ioctl(&drm,&request,&file));
    struct opengpu_sched_job *counter_job=queued[queued_count-1];
    assert(counter_job->object_count==50&&counter_job->kernel.counter_enabled&&counter_job->counter_records.size==64);
    assert(word(counter_job->counter_records.cpu,11)-word(counter_job->counter_records.cpu,10)==32);
    finish(&gpu,counter_job,0);assert(!buffers);
    request=uniform_only;
    request.active_mask=0;memset(request.blocks,0,sizeof(request.blocks));
    assert(!opengpu_compute_uniform_resources_ioctl(&drm,&request,&file));
    struct opengpu_sched_job *defaults_only=queued[queued_count-1];
    assert(defaults_only->uniform_snapshot.size==8192&&defaults_only->object_count==2);
    assert(!word(defaults_only->uniform_snapshot.cpu,7)&&!word(defaults_only->uniform_snapshot.cpu,10)&&word(defaults_only->uniform_snapshot.cpu,14)==1);
    assert(!memcmp((u8 *)defaults_only->uniform_snapshot.cpu+4096,objects[14].vaddr,4096));
    finish(&gpu,defaults_only,0);request=uniform_only;
    unsigned healthy_kicks=gpu.kicks;

    /* Same-BO multiple ranges are independent copies with one read fence/ref. */
    request.active_mask=3;memset(request.blocks,0,sizeof(request.blocks));request.defaults=(struct drm_opengpu_compute_uniform_view){0};
    request.blocks[0]=(struct drm_opengpu_compute_uniform_view){.handle=2,.bytes=64,.offset=0};
    request.blocks[1]=(struct drm_opengpu_compute_uniform_view){.handle=2,.bytes=64,.offset=32};
    unsigned reads=resv[2].reads,waits=resv[2].waits;before=queued_count;
    assert(!opengpu_compute_uniform_resources_ioctl(&drm,&request,&file)&&queued_count==before+1);
    struct opengpu_sched_job *same=queued[before];assert(same->object_count==2&&objects[2].refs==2);
    assert(!memcmp((u8 *)same->uniform_snapshot.cpu+4096,objects[2].vaddr,64));
    assert(!memcmp((u8 *)same->uniform_snapshot.cpu+4352,(u8 *)objects[2].vaddr+32,64));
    assert(resv[2].reads==reads+1&&resv[2].waits==waits+1);
    same->base.s_fence->finished.signaled=true; /* Scheduler cancellation before run. */
    same->base.s_fence->finished.error=-ECANCELED;opengpu_sched_free_job(&same->base);
    assert(objects[2].refs==1&&!buffers&&context.refs==1&&gpu.kicks==healthy_kicks);
    /* Different objects must have disjoint complete allocations, even if the
     * selected read-only subranges themselves happen not to overlap. */
    u64 second_source_dma=objects[3].dma_addr;
    objects[3].dma_addr=objects[2].dma_addr;request.blocks[1].handle=3;request.blocks[1].offset=8192;
    before=queued_count;
    assert(opengpu_compute_uniform_resources_ioctl(&drm,&request,&file)==-EINVAL&&queued_count==before&&!buffers&&objects[2].refs==1&&objects[3].refs==1);
    objects[3].dma_addr=second_source_dma;

    /* The metadata/fence/source errors never enqueue or publish a read fence. */
    request.active_mask=1;request.blocks[1]=(struct drm_opengpu_compute_uniform_view){0};
    const struct drm_opengpu_compute_uniform_resources valid=request;
    void *source0=objects[2].vaddr;u64 source0_dma=objects[2].dma_addr;
    for(unsigned which=0;which<38;which++){
        request=valid;before=queued_count;reads=read_signals;
        switch(which){
        case 0:request.version=2;break;case 1:request.pad[1]=1;break;
        case 2:request.active_mask=0x1000;break;case 3:request.blocks[1]=request.blocks[0];break;
        case 4:request.blocks[0].bytes=16388;break;case 5:request.blocks[0].bytes=0;break;
        case 6:request.blocks[0].offset=UINT64_MAX-3;break;case 7:request.blocks[0].offset=16384;break;
        case 8:request.blocks[0].offset=1;break;case 9:request.blocks[0].handle=63;break;
        case 10:request.defaults=(struct drm_opengpu_compute_uniform_view){.handle=2,.bytes=4100};break;
        case 11:request.resources.resource_flags=8;break;case 12:request.resources.launch.flags=4;break;
        case 13:request.resources.launch.in_syncobj=63;break;case 14:input.error=-EIO;break;
        case 15:wait_error=-ETIMEDOUT;break;case 16:gpu.hw.compute_uniform_transport=false;break;
        case 17:objects[2].import_attach=&objects[2];break;case 18:objects[2].resv=objects[1].resv;break;
        case 19:gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;break;
        case 20:gpu.hw.capabilities=GPU_CAP_PROTECTED_VM_STATE;break;
        case 21:context.vm.enabled=false;break;case 22:objects[2].vaddr=NULL;break;
        case 23:objects[2].dma_addr=UINT64_MAX-3;break;case 24:objects[2].dma_addr=shader.dma;break;
        case 25:objects[2].dma_addr=kernarg.dma;break;case 26:request.blocks[0].bytes=3;break;
        case 27:request.blocks[0].handle=0;break;case 28:request.resources.launch.out_syncobj=63;break;
        case 29:request.resources.counter=(struct drm_opengpu_compute_counter_binding){.slot=38,.count=8};break;
        case 30:request.resources.bindings[0]=(struct drm_opengpu_compute_storage_binding){35,1};break;
        case 31:request.resources.images[0].slot=35;break;
        case 32:request.resources.textures=(uintptr_t)texture_views;break;
        case 33:request.resources.texture_count=33;request.resources.textures=(uintptr_t)texture_views;break;
        case 34:request.defaults.bytes=4;break;case 35:request.active_mask=0;break;
        case 36:request.resources.launch.context_id=63;break;
        case 37:request.resources.texture_count=1;request.resources.textures=(uintptr_t)texture_views;copy_error=1;break;
        }
        assert(opengpu_compute_uniform_resources_ioctl(&drm,&request,&file)<0&&queued_count==before&&read_signals==reads);
        input.error=0;wait_error=0;gpu.hw.compute_uniform_transport=true;objects[2].import_attach=NULL;objects[2].resv=&resv[2];
        gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE;context.vm.enabled=true;
        objects[2].vaddr=source0;objects[2].dma_addr=source0_dma;copy_error=0;
        assert(objects[2].refs==1&&!buffers&&context.refs==1&&!rf.lock);
    }
    request=valid;
    fail_wait_object=&objects[2];before=queued_count;reads=read_signals;
    assert(opengpu_compute_uniform_resources_ioctl(&drm,&request,&file)==-EIO&&queued_count==before&&read_signals==reads&&!buffers&&objects[2].refs==1);
    fail_wait_object=NULL;
    request=uniform_only;request.blocks[8].handle=63;
    assert(opengpu_compute_uniform_resources_ioctl(&drm,&request,&file)==-ENOENT&&queued_count==before&&read_signals==reads&&!buffers);
    for(unsigned i=2;i<15;i++)assert(objects[i].refs==1);
    request=valid;
    for(unsigned fail=1;fail<=2;fail++){
        fail_allocation=allocation+fail;before=queued_count;
        assert(opengpu_compute_uniform_resources_ioctl(&drm,&request,&file)==-ENOMEM&&queued_count==before&&!buffers&&objects[2].refs==1);
        fail_allocation=0;
    }
    dep_error=-EIO;before=queued_count;
    assert(opengpu_compute_uniform_resources_ioctl(&drm,&request,&file)==-EIO&&queued_count==before&&!buffers&&objects[2].refs==1);
    dep_error=0;
    for(unsigned fail=1;fail<=3;fail++){
        fail_heap=heap_allocation+fail;before=queued_count;
        assert(opengpu_compute_uniform_resources_ioctl(&drm,&request,&file)==-ENOMEM&&queued_count==before&&!buffers&&objects[2].refs==1);
        fail_heap=0;
    }
    assert(!opengpu_compute_uniform_resources_ioctl(&drm,&request,&file));
    struct opengpu_sched_job *badmap=queued[queued_count-1];map_error=-ENOMEM;
    assert(opengpu_job_run(badmap)==ERR_PTR(-ENOMEM)&&gpu.kicks==healthy_kicks);map_error=0;
    opengpu_sched_free_job(&badmap->base);assert(!buffers&&objects[2].refs==1);
    assert(!opengpu_compute_uniform_resources_ioctl(&drm,&request,&file));
    struct opengpu_sched_job *badpublish=queued[queued_count-1];invalidate_error=-EIO;
    assert(opengpu_job_run(badpublish)==ERR_PTR(-EIO)&&gpu.kicks==healthy_kicks);invalidate_error=0;
    opengpu_sched_free_job(&badpublish->base);assert(!buffers&&objects[2].refs==1);
    assert(!opengpu_compute_uniform_resources_ioctl(&drm,&request,&file));
    finish(&gpu,queued[queued_count-1],0);assert(gpu.kicks==healthy_kicks+1&&gpu.pops==gpu.kicks&&!buffers&&objects[2].refs==1);
    /* Failed revocation quarantines the snapshot, every source and context.
     * Only an explicitly simulated external reset permits fixture reclamation. */
    assert(!opengpu_compute_uniform_resources_ioctl(&drm,&request,&file));
    struct opengpu_sched_job *quarantine=queued[queued_count-1];unmap_error=-EIO;
    finish(&gpu,quarantine,0);
    assert(gpu.hw.wedged&&buffers==2&&objects[2].refs==2&&context.refs==2&&quarantine->uniform_snapshot.cpu);
    unmap_error=0;
    for(unsigned i=0;i<quarantine->temporary_mapping_count;i++)
        assert(!opengpu_mmu_vm_unmap(&gpu,&context.vm,quarantine->temporary_mappings[i].va,quarantine->temporary_mappings[i].size));
    for(unsigned i=0;i<quarantine->object_count;i++)drm_gem_object_put(quarantine->objects[i]);
    free(quarantine->objects);opengpu_buffer_free(&gpu,&quarantine->shader);opengpu_buffer_free(&gpu,&quarantine->uniform_snapshot);
    opengpu_context_put(&context);free(quarantine);gpu.hw.wedged=false;
    assert(!buffers&&objects[2].refs==1&&context.refs==1);
    for(unsigned i=0;i<ARRAY_SIZE(resv);i++){assert(!resv[i].locked);release_fence(&resv[i].last);}
    release_fence(&context.last_fence);release_fence(&second_context.last_fence);release_fence(&output_sync.fence);
    assert(input.refs==1&&context.refs==1);
    for(unsigned i=2;i<15;i++){assert(objects[i].refs==1);free(objects[i].vaddr);}
    puts("compute uniform flow: 12x16KiB+1024 defaults, 32textures/4images/4x128MiB SSBOs, 50refs/51locks, producer/read-fence dedup, two roots/queued snapshots, held POP/fault/cancel/quarantine/reuse PASS (mock kernel/MMIO; no GPU execution)");
    return 0;
}
