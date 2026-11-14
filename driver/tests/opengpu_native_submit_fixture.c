/* Actual Linux submit/scheduler/opaque owner/MMU composition. Only OS primitives
 * and the final hardware kick are modeled here; this is not GPU execution. */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "opengpu_native_submit_fixture.h"
#include "../gpu_abi.h"
typedef uint32_t u32;
typedef uint64_t u64;
typedef unsigned char u8;
typedef unsigned spinlock_t;
struct dma_fence;
struct dma_fence_ops {const char *(*get_driver_name)(struct dma_fence *);const char *(*get_timeline_name)(struct dma_fence *);void (*release)(struct dma_fence *);};
struct dma_fence {u64 seqno;unsigned refs;int error;bool signaled;const struct dma_fence_ops *ops;};
struct fixture_dependency {struct fixture_dependency *next;struct dma_fence *fence;};
struct drm_sched_entity {int unused;};
struct drm_sched_fence {struct dma_fence finished;};
struct drm_sched_job {struct drm_sched_fence *s_fence;struct drm_sched_job *next;struct fixture_dependency *dependencies;};
struct opengpu_device;
static void native_fixture_progress(struct opengpu_device *);
#define OPENGPU_PRIVATE_SNAPSHOT_PROGRESS(gpu) native_fixture_progress(gpu)
#define OPENGPU_PRIVATE_SNAPSHOT_CONTEXT_EXTRA struct drm_sched_entity entity;struct dma_fence *last_fence;
#define OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN native_owner_component_regression
#ifdef OPENGPU_NATIVE_MMIO
#define OPENGPU_NATIVE_MMIO_DECLARE
#include "opengpu_native_mmio_fixture.inc"
#undef OPENGPU_NATIVE_MMIO_DECLARE
#endif
#include "opengpu_private_snapshot_test.c"
#undef OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN
#include "../opengpu_native_frame.h"
#include "../opengpu_native_sources_features.h"
#include "../opengpu_native_advanced_blend_features.h"
#include "../opengpu_native_layout.h"
#include "../opengpu_native_frame_snapshot.c"
#include "../opengpu_native_program_snapshot.c"
#include "../opengpu_geometry_uniform_snapshot.c"
#include "../opengpu_geometry_program_snapshot.c"
#include "../opengpu_geometry_snapshot.c"
#include "../opengpu_native_layered_snapshot.c"
#include "../opengpu_native_input_snapshot.c"
#include "../opengpu_native_graph_alias.c"
#define identity native_graph_identity
#include "../opengpu_native_job_snapshot.c"
#undef identity
#define DMA_RESV_USAGE_READ 1
#define DMA_RESV_USAGE_WRITE 2
#define DRM_EXEC_INTERRUPTIBLE_WAIT 1
#define DRM_EXEC_IGNORE_DUPLICATES 2
#define lower_32_bits(v) ((u32)(v))
#define spin_lock_irqsave(p,f) do {(f)=0;assert(!*(p));*(p)=1;}while(0)
#define spin_unlock_irqrestore(p,f) do {(void)(f);assert(*(p));*(p)=0;}while(0)
#define dev_err(...) ((void)0)
#define spin_lock_init(p) (*(p)=0)
#define time_after_eq(a,b) ((long)((a)-(b))>=0)
#define atomic64_set(p,v) (*(p)=(v))
#define atomic64_read(p) (*(p))
#define atomic64_inc(p) (++*(p))
#define atomic64_dec(p) (--*(p))
#define u64_to_user_ptr(v) ((void *)(uintptr_t)(v))
#define container_of(p,t,m) ((t *)((char *)(p)-offsetof(t,m)))
struct drm_gem_object;
struct dma_resv {bool locked;struct dma_fence *read,*write;};
struct drm_gem_object {struct dma_resv *resv;size_t size;void *import_attach;};
struct drm_gem_dma_object {struct drm_gem_object base;void *vaddr;u64 dma_addr;};
struct native_original {struct native_original *next;struct drm_gem_dma_object gem;
    struct dma_resv resv;struct opengpu_buffer backing;u32 handle;unsigned refs;bool visible;};
struct opengpu_file {struct mutex lock;struct native_kernel_session *session;};
struct drm_file {struct opengpu_file *driver_priv;u64 client_id;};
struct drm_device {struct opengpu_device *dev;};
struct drm_syncobj {struct dma_fence *fence;};
struct drm_exec {struct dma_resv **locked;u32 count,capacity;};
struct dma_resv_iter {struct dma_resv *resv;unsigned index;int usage;};
struct opengpu_sched_dep {struct drm_gem_object *object;int wait_usage,signal_usage;};
enum {OPENGPU_SCHED_NATIVE_BINNED=8,OPENGPU_SCHED_TESSELLATION=7};
struct opengpu_sched_job {struct drm_sched_job base;struct opengpu_device *gpu;
    struct opengpu_render_context *context;const struct opengpu_vm *vm;unsigned type;
    struct opengpu_private_snapshot *native_owner;u32 native_job_segment;void *native_quarantine_refs;
    struct dma_fence *retirement_fence,*admission_chain;bool ordinary_pending;
    struct dma_fence_chain *execution_chain;bool admission_armed;
    struct drm_gem_object **objects;u32 object_count,temporary_mapping_count;
    struct {u64 va;size_t size;} temporary_mappings[8];
    struct opengpu_buffer depth,vertex_shader,shader,texture_records,storage_records,image_records,
        counter_records,uniform_snapshot,indirect_snapshot,render_desc,commands;};
static struct native_kernel_session *running;
struct native_kernel_profile {struct native_kernel_profile_word words[101];
    struct opengpu_native_source_limits limits;u32 capabilities;bool advanced,missing;};
struct native_kernel_record {
    struct native_kernel_record *next;unsigned index;
    struct opengpu_sched_job *job;struct dma_fence *hardware;
    struct native_kernel_held_receipt held;
    struct native_kernel_observed_receipt observed;
    bool captured,drained,verified,freed;void *mmio_state;
};
struct native_kernel_session {bool context_destroyed;struct native_kernel_profile *profile;
    struct native_kernel_record *records,*pending_record;unsigned record_count;struct opengpu_device gpu;struct opengpu_render_context context;
    struct opengpu_file render;struct drm_file file;struct drm_device drm;
    struct native_original *originals;struct drm_sched_job *head,*tail;
    struct drm_syncobj sync[3];u64 next_seq;unsigned count;
    native_kernel_source_observer observer;void *record;struct dma_fence *hardware;
    int copy_error,wait_error,submit_error;void (*progress)(struct native_kernel_session *);};
static struct native_kernel_record *native_session_record(struct native_kernel_session *s,unsigned index)
{if(!s)return NULL;for(struct native_kernel_record *r=s->records;r;r=r->next)if(r->index==index)return r;return NULL;}
#ifdef OPENGPU_NATIVE_MMIO
static struct opengpu_sched_job *native_mmio_record_job(struct native_kernel_session *s,unsigned index)
{struct native_kernel_record *r=native_session_record(s,index);return r?r->job:NULL;}
#endif
static void native_fixture_progress(struct opengpu_device *gpu)
{assert(running && gpu==&running->gpu);
#ifdef OPENGPU_NATIVE_MMIO
 native_mmio_progress(gpu);
#endif
 if(running->progress)running->progress(running);}
static struct native_original *original_object(struct drm_gem_object *object)
{return container_of(container_of(object,struct drm_gem_dma_object,base),struct native_original,gem);}
static struct native_original *original_handle(struct native_kernel_session *s,u32 h)
{for(struct native_original *o=s->originals;o;o=o->next)if(o->handle==h)return o;return NULL;}
static struct dma_fence *dma_fence_get(struct dma_fence *f){if(f)++f->refs;return f;}
static void dma_fence_put(struct dma_fence *f){if(f){assert(f->refs);if(!--f->refs){if(f->ops && f->ops->release)f->ops->release(f);else free(f);}}}
static void drm_gem_object_get(struct drm_gem_object *o){assert(original_object(o)->refs);++original_object(o)->refs;}
static void drm_gem_object_put(struct drm_gem_object *o)
{struct native_original *r=original_object(o);assert(r->refs);if(!--r->refs){
 dma_fence_put(r->resv.read);dma_fence_put(r->resv.write);r->resv.read=r->resv.write=NULL;
 opengpu_buffer_free(&running->gpu,&r->backing);r->gem.vaddr=NULL;}}
static struct drm_gem_dma_object *to_drm_gem_dma_obj(struct drm_gem_object *o)
{return container_of(o,struct drm_gem_dma_object,base);}
static struct drm_gem_object *drm_gem_object_lookup(struct drm_file *f,u32 h)
{struct native_original *o=original_handle(f->driver_priv->session,h);
 if(!o || !o->visible)return NULL;
 drm_gem_object_get(&o->gem.base);return &o->gem.base;}
static struct opengpu_device *dev_get_drvdata(struct opengpu_device *d){return d;}
static struct opengpu_render_context *opengpu_context_lookup(struct opengpu_file *f,u32 id)
{assert(f->lock.held);return id==f->session->context.id?&f->session->context:NULL;}
static struct drm_syncobj *drm_syncobj_find(struct drm_file *f,u32 h)
{return h<3?&f->driver_priv->session->sync[h]:NULL;}
static void drm_syncobj_put(struct drm_syncobj *s){(void)s;}
static int drm_syncobj_find_fence(struct drm_file *f,u32 h,u64 point,u64 flags,struct dma_fence **out)
{struct drm_syncobj *s=drm_syncobj_find(f,h);assert(!point&&!flags);
 if(!s || !s->fence)return -ENOENT;
 *out=dma_fence_get(s->fence);return 0;}
static int opengpu_hw_fence_wait(struct opengpu_device *gpu,struct dma_fence *f,unsigned ms,bool interruptible)
{
#ifdef OPENGPU_NATIVE_MMIO
 return native_mmio_fence_wait(gpu,f,ms,interruptible);
#else
 assert(!gpu->hw.submit_lock.held && !gpu->hw.fence_lock && ms==OPENGPU_DRAW_WAIT_MS+100 && interruptible);
 if(running->wait_error)return running->wait_error;
 return !f->signaled?-ETIMEDOUT:f->error;
#endif
}
static struct dma_fence *reservation_next(struct dma_resv_iter *c)
{assert(c->resv->locked);while(c->index<2){struct dma_fence *f=c->index++?
 (c->usage==DMA_RESV_USAGE_READ?c->resv->read:NULL):c->resv->write;if(f)return f;}return NULL;}
#define dma_resv_for_each_fence(c,r,u,f) for((c)->resv=(r),(c)->usage=(u),(c)->index=0,(f)=reservation_next(c);(f);(f)=reservation_next(c))
#define drm_exec_until_all_locked(e) for(int once=1;once;once=0)
#define drm_exec_retry_on_contention(e) ((void)(e))
static void drm_exec_init(struct drm_exec *e,int flags,int unused)
{assert(flags==3&&!unused);memset(e,0,sizeof(*e));}
static int drm_exec_prepare_obj(struct drm_exec *e,struct drm_gem_object *o,int count)
{assert(count==1);for(u32 i=0;i<e->count;i++)if(e->locked[i]==o->resv)return 0;
 if(e->count==e->capacity){u32 n=e->capacity?e->capacity*2:16;void *p=realloc(e->locked,n*sizeof(*e->locked));if(!p)return -ENOMEM;e->locked=p;e->capacity=n;}
 assert(!o->resv->locked);o->resv->locked=true;e->locked[e->count++]=o->resv;return 0;}
static void drm_exec_fini(struct drm_exec *e)
{for(u32 i=0;i<e->count;i++){assert(e->locked[i]->locked);e->locked[i]->locked=false;}free(e->locked);}
static void *vmemdup_user(const void *p,size_t bytes)
{if(running->copy_error)return (void *)(intptr_t)-EFAULT;void *r=kvzalloc(bytes,0);if(!r)return (void *)(intptr_t)-ENOMEM;memcpy(r,p,bytes);return r;}
static void *kcalloc(size_t n,size_t bytes,int flags)
{if(n>SIZE_MAX/bytes)return NULL;return kzalloc(n*bytes,flags);}
static int drm_sched_job_init(struct drm_sched_job *j,struct drm_sched_entity *entity,int n,void *owner,u64 client)
{(void)entity;(void)owner;(void)client;assert(n==1);if(running->submit_error)return running->submit_error;
 j->s_fence=calloc(1,sizeof(*j->s_fence));if(!j->s_fence)return -ENOMEM;
 j->s_fence->finished=(struct dma_fence){.refs=1,.seqno=++running->next_seq};return 0;}
static void drm_sched_job_cleanup(struct drm_sched_job *j){
 while(j->dependencies){struct fixture_dependency *d=j->dependencies;j->dependencies=d->next;dma_fence_put(d->fence);free(d);}
 dma_fence_put(&j->s_fence->finished);j->s_fence=NULL;}
static int drm_sched_job_add_dependency(struct drm_sched_job *j,struct dma_fence *f)
{struct fixture_dependency *d=calloc(1,sizeof(*d));if(!d){dma_fence_put(f);return -ENOMEM;}d->fence=f;d->next=j->dependencies;j->dependencies=d;return 0;}
static u64 dma_fence_context_alloc(unsigned n){static u64 context=1;u64 result=context;context+=n;return result;}
static void dma_fence_init(struct dma_fence *f,const struct dma_fence_ops *ops,spinlock_t *lock,u64 context,u64 seq)
{(void)lock;assert(context);*f=(struct dma_fence){.refs=1,.seqno=seq,.ops=ops};}
static void dma_fence_signal(struct dma_fence *f){assert(f && !f->signaled);f->signaled=true;}
struct dma_fence_chain {struct dma_fence base;struct dma_fence *prev,*fence;};
static void fixture_chain_release(struct dma_fence *base)
{struct dma_fence_chain *c=container_of(base,struct dma_fence_chain,base);dma_fence_put(c->prev);dma_fence_put(c->fence);free(c);}
static const struct dma_fence_ops fixture_chain_ops={.release=fixture_chain_release};
static struct dma_fence_chain *dma_fence_chain_alloc(void){return kzalloc(sizeof(struct dma_fence_chain),0);}
static void dma_fence_chain_free(struct dma_fence_chain *c){kfree(c);}
static void dma_fence_chain_init(struct dma_fence_chain *c,struct dma_fence *prev,struct dma_fence *f,u64 seq)
{c->base=(struct dma_fence){.refs=1,.seqno=seq,.ops=&fixture_chain_ops};c->prev=prev;c->fence=f;}
static bool dma_fence_is_signaled(struct dma_fence *f)
{if(!f)return true;if(f->ops!=&fixture_chain_ops)return f->signaled;
 struct dma_fence_chain *c=container_of(f,struct dma_fence_chain,base);
 return dma_fence_is_signaled(c->prev)&&dma_fence_is_signaled(c->fence);}
#include "../opengpu_admission.h"
#include "native_admission_under_test.inc"
static int drm_sched_job_add_syncobj_dependency(struct drm_sched_job *j,struct drm_file *f,u32 h,u64 point)
{(void)j;(void)f;(void)h;(void)point;assert(0);return -EINVAL;}
static int drm_sched_job_add_resv_dependencies(struct drm_sched_job *j,struct dma_resv *r,int usage)
{(void)j;(void)usage;assert(r->locked);return 0;}
static void drm_sched_job_arm(struct drm_sched_job *j){assert(j->s_fence);}
static void dma_resv_add_fence(struct dma_resv *r,struct dma_fence *f,int usage)
{assert(r->locked);struct dma_fence **p=usage==DMA_RESV_USAGE_READ?&r->read:&r->write;
 dma_fence_put(*p);*p=dma_fence_get(f);}
static void drm_syncobj_replace_fence(struct drm_syncobj *s,struct dma_fence *f)
{dma_fence_put(s->fence);s->fence=dma_fence_get(f);}
static void drm_sched_entity_push_job(struct drm_sched_job *j)
{
 if(running->tail)running->tail->next=j;else running->head=j;running->tail=j;++running->count;
 struct opengpu_sched_job *job=container_of(j,struct opengpu_sched_job,base);
 if(job->type==OPENGPU_SCHED_NATIVE_BINNED){
  struct native_kernel_record *r=running->pending_record;assert(r && !r->job);
  r->job=job;r->index=running->record_count++;
  struct native_kernel_record **tail=&running->records;while(*tail)tail=&(*tail)->next;*tail=r;
 }
}
static void opengpu_tessellation_job_release(struct opengpu_sched_job *j){(void)j;assert(0);}
static struct opengpu_sched_job *opengpu_sched_job_from_base(struct drm_sched_job *j)
{return container_of(j,struct opengpu_sched_job,base);}
#include "native_scheduler_under_test.inc"
static void *native_memcpy(void *to,const void *from,size_t bytes)
{for(struct native_original *o=running->originals;o;o=o->next){
 uintptr_t first=(uintptr_t)o->gem.vaddr,last=first+o->gem.base.size,p=(uintptr_t)from;
 if(o->refs && p>=first && p<=last && bytes<=last-p && running->observer){
  assert(o->resv.locked);running->observer(running->record,o->handle,p-first,(u32)bytes,from);break;}}
 return memcpy(to,from,bytes);}
#define memcpy native_memcpy
#include "native_submit_under_test.inc"
#undef memcpy
struct opengpu_kernel_launch;
struct opengpu_command_events;
#ifdef OPENGPU_NATIVE_MMIO
#include "opengpu_native_mmio_fixture.inc"
#else
static bool opengpu_hw_execution_busy(struct opengpu_device *gpu)
{assert(gpu->hw.submit_lock.held);return running->hardware!=NULL;}
static int opengpu_hw_unified_submit_locked(struct opengpu_device *gpu,
    const struct opengpu_kernel_launch *launch,const struct opengpu_command_events *events,
    u32 opcode,u32 source,u32 destination,u32 bytes,u32 pattern,u32 sample_mode,
    u32 width,u32 height,u32 source_stride,u32 destination_stride,u64 expected_bytes,
    const struct opengpu_vm *vm,u32 delay,struct dma_fence **output)
{
    assert(gpu->hw.submit_lock.held && !gpu->hw.fence_lock && !launch && !events);
    assert(opcode==7 && !(source&63) && (u64)destination+((u64)pattern<<32)==(u64)source+512);
    assert(bytes==512 && !sample_mode && width==running->context.id && !height && !source_stride && !destination_stride);
    assert(!expected_bytes && vm==&running->context.vm && !delay && !running->hardware);
    *output=calloc(1,sizeof(**output));if(!*output)return -ENOMEM;
    (*output)->refs=1;(*output)->seqno=++running->next_seq;running->hardware=*output;
    return 0;
}
#endif
#include "native_enqueue_under_test.inc"
static u32 native_profile_read(void *opaque,u32 address)
{
    struct native_kernel_profile *p=opaque;
    for(unsigned i=0;i<101;i++)if(p->words[i].address==address)return p->words[i].value;
    p->missing=true;return 0;
}
struct native_kernel_profile *native_kernel_profile_create(
    const struct native_kernel_profile_word *words,size_t count,
    const struct native_kernel_profile_word *registers,size_t register_count)
{
    const u32 bases[]={0x360,0x160,0x340,0x240,0x260,0x280,0x3a0,0x4c0,0x4e0,0x300};
    const unsigned lengths[]={16,8,8,8,8,8,16,8,8,8};
    const u32 extra[]={GPU_REG_ID,GPU_REG_CAPABILITIES,GPU_REG_BIN_CAPABILITY_PTR,0x320,GPU_REG_SHADER_CONFIG};
    struct native_kernel_profile *p;
    if(!words || count!=96 || !registers || register_count!=5)return NULL;
    p=calloc(1,sizeof(*p));if(!p)return NULL;
    memcpy(p->words,words,96*sizeof(*words));memcpy(p->words+96,registers,5*sizeof(*registers));
    for(unsigned i=0;i<101;i++){
        bool allowed=false;
        for(unsigned j=0;j<i;j++)if(p->words[j].address==p->words[i].address)goto fail;
        if(i<96){for(unsigned b=0;b<10;b++)if(p->words[i].address>=bases[b] &&
            p->words[i].address<bases[b]+4*lengths[b] && !(p->words[i].address&3))allowed=true;
        }else for(unsigned j=0;j<5;j++)if(p->words[i].address==extra[j])allowed=true;
        if(!allowed)goto fail;
    }
    u32 id=native_profile_read(p,GPU_REG_ID),flags=native_profile_read(p,0x368);
    p->capabilities=native_profile_read(p,GPU_REG_CAPABILITIES);
    if((id&0xffff0000u)!=0x47550000u || native_profile_read(p,0x360)!=OPENGPU_COMPUTE_RESOURCE_MAGIC ||
        native_profile_read(p,0x364)!=16 || flags!=0x3ffff ||
        !opengpu_read_native_source_limits(id,flags,p->capabilities,0x1000,p,native_profile_read,&p->limits) ||
        !p->limits.available ||
        !opengpu_read_native_advanced_blend_support(id,flags,p->capabilities,0x1000,p,native_profile_read,&p->advanced) ||
        !p->advanced || p->missing)goto fail;
    if(!opengpu_native_source_shader_config_valid(&p->limits,p->capabilities,
        native_profile_read(p,GPU_REG_SHADER_CONFIG)))goto fail;
    return p;
fail:free(p);return NULL;
}
void native_kernel_profile_destroy(struct native_kernel_profile *p){free(p);}
int native_kernel_profile_query(const struct native_kernel_profile *p,uint32_t param,uint64_t *output)
{
    u64 value=0;
    if(!p || !output || !p->limits.available || p->missing)return -EINVAL;
    if(param==OPENGPU_PARAM_DRIVER_FEATURES)value=OPENGPU_DRIVER_FEATURE_NATIVE_BINNED;
    else if(param==OPENGPU_PARAM_CAPABILITIES)value=p->capabilities;
    else if(param==OPENGPU_PARAM_SHADER_CONFIG){
        for(unsigned i=0;i<101;i++)if(p->words[i].address==GPU_REG_SHADER_CONFIG)value=p->words[i].value;
    }
    else if(param==OPENGPU_PARAM_NATIVE_LAYOUT){
        const struct opengpu_native_binned_host_caps *c=&p->limits.frame;
        value=p->limits.capacity;
#define SLOT(field,shift) value|=(u64)((c->words[OPENGPU_NATIVE_BINNED_HOST_##field##_WORD]&OPENGPU_NATIVE_BINNED_HOST_##field##_MASK)>>OPENGPU_NATIVE_BINNED_HOST_##field##_SHIFT)<<shift
        SLOT(ATTRIBUTES,OPENGPU_NATIVE_LAYOUT_ATTRIBUTES_SHIFT);
        SLOT(BINDINGS,OPENGPU_NATIVE_LAYOUT_BINDINGS_SHIFT);
        SLOT(VARYINGS,OPENGPU_NATIVE_LAYOUT_VARYINGS_SHIFT);
        SLOT(COLORS,OPENGPU_NATIVE_LAYOUT_COLORS_SHIFT);
#undef SLOT
        value|=(u64)OPENGPU_NATIVE_LAYOUT_VERTEX_VERSIONS<<OPENGPU_NATIVE_LAYOUT_VERTEX_VERSIONS_SHIFT;
        value|=(u64)OPENGPU_NATIVE_LAYOUT_FRAGMENT_VERSIONS<<OPENGPU_NATIVE_LAYOUT_FRAGMENT_VERSIONS_SHIFT;
    }else if(param>=OPENGPU_PARAM_NATIVE_CAPS_BASE && param<OPENGPU_PARAM_NATIVE_CAPS_BASE+8)
        value=p->limits.frame.words[param-OPENGPU_PARAM_NATIVE_CAPS_BASE];
    else return -EINVAL;
    *output=value;return 0;
}
struct native_kernel_session *native_kernel_session_create(uint32_t id)
{
    struct native_kernel_session *s=calloc(1,sizeof(*s));if(!s)return NULL;
    running=s;s->gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_COMPUTE_SCALAR_FPU|GPU_CAP_PROTECTED_VM_STATE;
    s->gpu.hw.native_sources.available=true;s->gpu.hw.native_sources.capacity=128;
    s->gpu.hw.native_sources.engines=4;s->gpu.hw.native_descriptor_preflight=true;
    s->context=(struct opengpu_render_context){.gpu=&s->gpu,.id=id,.references=1};
    s->render.session=s;s->file.driver_priv=&s->render;s->drm.dev=&s->gpu;
    if(opengpu_mmu_init(&s->gpu) || opengpu_mmu_vm_create(&s->gpu,&s->context.vm)){free(s);return NULL;}
    opengpu_admission_init(&s->gpu);copy_gpu=&s->gpu;return s;
}
struct native_kernel_session *native_kernel_session_create_profile(uint32_t id,const struct native_kernel_profile *p)
{
    struct native_kernel_session *s;
    if(!p || !p->limits.available || p->missing)return NULL;
    s=native_kernel_session_create(id);if(!s)return NULL;
    s->profile=malloc(sizeof(*p));if(!s->profile){native_kernel_session_destroy(s);return NULL;}
    *s->profile=*p;s->gpu.hw.capabilities=p->capabilities;
    s->gpu.hw.native_sources.available=p->limits.available;
    s->gpu.hw.native_sources.capacity=p->limits.capacity;s->gpu.hw.native_sources.engines=p->limits.engines;
    s->gpu.hw.native_sources.frame=p->limits.frame;s->gpu.hw.native_advanced_blend_transport=p->advanced;
    return s;
}
void *native_kernel_original_allocate(struct native_kernel_session *s,uint32_t h,uint64_t bytes)
{
    struct native_original *o;
    if(!s || !h || !bytes || (bytes&4095) || bytes>SIZE_MAX || original_handle(s,h))return NULL;
    running=s;o=calloc(1,sizeof(*o));if(!o)return NULL;
    if(opengpu_buffer_alloc(&s->gpu,&o->backing,(size_t)bytes)){free(o);return NULL;}
    o->gem.base=(struct drm_gem_object){.resv=&o->resv,.size=(size_t)bytes};
    o->gem.vaddr=o->backing.cpu;o->gem.dma_addr=o->backing.dma;
    o->handle=h;o->refs=1;o->visible=true;o->next=s->originals;s->originals=o;
    return o->gem.vaddr;
}
int native_kernel_original_close(struct native_kernel_session *s,uint32_t h)
{
    struct native_original *o=s?original_handle(s,h):NULL;
    if(!o || !o->visible)return -ENOENT;
    running=s;o->visible=false;drm_gem_object_put(&o->gem.base);return 0;
}
int native_kernel_original_refcount(struct native_kernel_session *s,uint32_t h)
{struct native_original *o=s?original_handle(s,h):NULL;return o?(int)o->refs:-ENOENT;}
int native_kernel_original_physical(struct native_kernel_session *s,uint32_t h,uint64_t *physical,uint64_t *bytes)
{struct native_original *o=s?original_handle(s,h):NULL;if(!o || !o->refs || !physical || !bytes)return -ENOENT;
 *physical=o->gem.dma_addr;*bytes=o->gem.base.size;return 0;}
int native_kernel_submit(struct native_kernel_session *s,struct drm_opengpu_submit_native_binned *request,
    native_kernel_source_observer observer,void *record)
{
    if(!s || !request)return -EINVAL;
    struct native_kernel_record *prepared=calloc(1,sizeof(*prepared));if(!prepared)return -ENOMEM;
    assert(!s->pending_record);s->pending_record=prepared;
    running=s;copy_gpu=&s->gpu;s->observer=observer;s->record=record;
    int ret=opengpu_native_binned_ioctl(&s->drm,request,&s->file);
    if(ret){assert(!prepared->job);free(prepared);}else assert(prepared->job);
    s->pending_record=NULL;s->observer=NULL;s->record=NULL;return ret;
}
static struct opengpu_sched_job *native_record(struct native_kernel_session *s,unsigned index)
{struct drm_sched_job *j=s->head;while(index-- && j)j=j->next;return j?opengpu_sched_job_from_base(j):NULL;}
int native_kernel_run(struct native_kernel_session *s,unsigned index)
{
    struct opengpu_sched_job *j=s?native_record(s,index):NULL;struct dma_fence *fence=NULL;
    if(!j)return -ENOENT;
    (void)native_session_record(s,index);
    running=s;copy_gpu=&s->gpu;
    for(struct fixture_dependency *d=j->base.dependencies;d;d=d->next)if(!dma_fence_is_signaled(d->fence))return -EBUSY;
    if(j->type!=OPENGPU_SCHED_NATIVE_BINNED){
        int ret=opengpu_hw_snapshot_submission_lock(&s->gpu);
        if(!ret)mutex_unlock(&s->gpu.hw.submit_lock);
        return ret;
    }
    return opengpu_hw_native_binned_async(&s->gpu,j->native_owner,j->context,j->native_job_segment,&fence);
}
/* CPU control peer only. Final active execution uses actual DUT held/retired
 * receipts and the real hardware drain, never this synthetic completion. */
static void native_cpu_complete(struct native_kernel_session *s,struct drm_sched_job *base,int error)
{
    if(s->hardware){s->hardware->error=error;s->hardware->signaled=true;dma_fence_put(s->hardware);s->hardware=NULL;}
    s->gpu.hw.native_pending_owner=NULL;
    assert(!base->s_fence->finished.signaled);
    base->s_fence->finished.error=error;base->s_fence->finished.signaled=true;
}
int native_kernel_retire(struct native_kernel_session *s,unsigned index,int error)
{
    struct drm_sched_job **link=s?&s->head:NULL;
    if(!link)return -EINVAL;
    while(index-- && *link)link=&(*link)->next;
    if(!*link)return -ENOENT;
    running=s;copy_gpu=&s->gpu;
    struct drm_sched_job *base=*link;struct opengpu_sched_job *job=opengpu_sched_job_from_base(base);
    /* CPU test response only. Full-host qualification must supply actual
     * completion/drain observations through a separate verified receipt path. */
    if(!base->s_fence->finished.signaled)native_cpu_complete(s,base,error);
    *link=base->next;if(s->tail==base){s->tail=NULL;for(struct drm_sched_job *p=s->head;p;p=p->next)s->tail=p;}
    for(struct native_kernel_record *r=s->records;r;r=r->next)if(r->job==job){r->job=NULL;r->freed=true;}
    --s->count;opengpu_sched_free_job(&job->base);return 0;
}
static bool native_same_held(const struct native_kernel_held_receipt *a,
    const struct native_kernel_held_receipt *b)
{
    return a->record_index==b->record_index && a->command_id==b->command_id &&
        a->completion==b->completion && !memcmp(a->gnx,b->gnx,sizeof(a->gnx)) &&
        a->append_read_bytes==b->append_read_bytes && a->held_legacy_status==b->held_legacy_status &&
        a->held_status==b->held_status && a->held_irq==b->held_irq &&
        a->held_irq_asserted==b->held_irq_asserted;
}
int native_kernel_observe_retired(struct native_kernel_session *s,unsigned index,
    const struct native_kernel_observed_receipt *observation)
{
    struct native_kernel_record *r=native_session_record(s,index);
    const u32 busy=GPU_UCMD_STATUS_COMPLETION|GPU_UCMD_STATUS_OVERFLOW|
        GPU_UCMD_STATUS_RESET_BUSY|GPU_UCMD_STATUS_RESET_REJECTED|GPU_UCMD_STATUS_VM_BUSY;
    if(!r || !r->job || !observation || !r->captured || !r->drained || r->verified ||
        observation->held.record_index!=index || !native_same_held(&r->held,&observation->held) ||
        observation->external_drained!=1 || observation->retired_irq_asserted ||
        observation->retired_irq!=GPU_IRQ_ENABLE ||
        !(observation->retired_status&GPU_UCMD_STATUS_READY) || (observation->retired_status&busy) ||
        !r->hardware || !dma_fence_is_signaled(r->hardware) || r->hardware->error ||
        s->gpu.hw.native_pending_owner || r->job->base.s_fence->finished.signaled)return -EINVAL;
    /* The actual hardware drain has signaled the returned fence. This models
     * DRM's finished propagation, without releasing the job/owner or PTEs. */
    r->observed=*observation;r->verified=true;
    dma_fence_signal(&r->job->base.s_fence->finished);
    return 0;
}
int native_kernel_finish_observed(struct native_kernel_session *s,unsigned index)
{
    struct native_kernel_record *r=native_session_record(s,index);
    struct drm_sched_job **link;
    if(!r || !r->job || !r->verified || r->freed || !r->hardware ||
        !dma_fence_is_signaled(r->hardware) || r->hardware->error || s->gpu.hw.wedged)return -EINVAL;
    for(struct native_kernel_record *other=s->records;other;other=other->next)
        if(!other->verified)return -EBUSY;
#ifdef OPENGPU_NATIVE_MMIO
    if(!native_mmio_state(&s->gpu)->cleanup_active)return -EBUSY;
#endif
    link=&s->head;while(*link && *link!=&r->job->base)link=&(*link)->next;
    if(!*link)return -ENOENT;
    running=s;copy_gpu=&s->gpu;
    struct drm_sched_job *base=*link,*next=base->next;
    /* The MMIO transport blocks at each actual shootdown before this real
     * free_job path can release the corresponding backing. */
    opengpu_sched_free_job(base);
    if(s->gpu.hw.wedged)return -EIO; /* actual free_job quarantined its owner */
    *link=next;if(s->tail==base){s->tail=NULL;for(struct drm_sched_job *p=s->head;p;p=p->next)s->tail=p;}
    --s->count;r->job=NULL;r->freed=true;dma_fence_put(r->hardware);r->hardware=NULL;
    return 0;
}
int native_kernel_session_destroy(struct native_kernel_session *s)
{
    if(!s)return -EINVAL;
    if(s->head || s->hardware)return -EBUSY;
#ifdef OPENGPU_NATIVE_MMIO
    if(s->gpu.hw.native_mmio && native_mmio_state(&s->gpu)->exported &&
        (!native_mmio_state(&s->gpu)->cleanup_done || !s->context_destroyed))return -EBUSY;
#endif
    running=s;copy_gpu=&s->gpu;
    for(struct native_original *o=s->originals;o;o=o->next)
        if(o->refs!=(o->visible?1:0))return -EBUSY;
    if(!s->context_destroyed){
        int error=opengpu_mmu_vm_destroy(&s->gpu,&s->context.vm);
        if(!error)error=opengpu_mmu_fini(&s->gpu);
        if(error)return error;
        s->context_destroyed=true;
    }
    for(struct native_original *o=s->originals;o;o=o->next)if(o->visible)native_kernel_original_close(s,o->handle);
    dma_fence_put(s->context.last_fence);
    for(unsigned i=0;i<3;i++)dma_fence_put(s->sync[i].fence);
    opengpu_admission_fini(&s->gpu);
#ifdef OPENGPU_NATIVE_MMIO
    native_mmio_release(s);
#endif
    assert(s->context.references==1);
    while(s->originals){struct native_original *o=s->originals;s->originals=o->next;free(o);}
    while(s->records){struct native_kernel_record *r=s->records;s->records=r->next;
        assert(!r->job && !r->hardware && !r->mmio_state);free(r);}
    free(s->profile);free(s);running=NULL;copy_gpu=NULL;return 0;
}
/* CPU unit fixture constants only; final active mode consumes actual probe. */
static void native_test_profile_words(struct native_kernel_profile_word words[96],
    struct native_kernel_profile_word regs[5])
{
    const struct native_kernel_profile_word auxiliary[]={{0,0x47550002},{0x60,
        GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE|GPU_CAP_COMPUTE_SCALAR_FPU|GPU_CAP_SHADER_CONFIG_INFO},
        {0x68,0x160},{0x320,1048576},{GPU_REG_SHADER_CONFIG,0x02020810}};
    memcpy(regs,auxiliary,sizeof(auxiliary));
    unsigned n=0;
#define BLOCK(base,...) do {const u32 v[]={__VA_ARGS__};for(unsigned i=0;i<sizeof(v)/sizeof(v[0]);i++)words[n++]=(struct native_kernel_profile_word){base+4*i,v[i]};}while(0)
    BLOCK(0x360,0x47435201,16,0x3ffff,2,16,8,128,128,0xffffffff,16384,16,8,0x10000000,32,131072,4);
    BLOCK(0x160,0x474e4303,8,0x474e4a01,0x08000200,0xc2100800,0x08000800,0xe4908000,0x8813d902);
    BLOCK(0x340,0x47434901,8,0x7f,14,12,4,0xffffffff,128);
    BLOCK(0x240,0x47515601,8,0x0b0a0908,0x47514f01,64,8,0x01001001,31);
    BLOCK(0x260,0x47464301,8,0x1003010c,0x47544601,512,0x04400408,69,255);
    BLOCK(0x280,0x47515001,8,0x0010040d,0x47504f01,64,0x00204008,0x8c88,63);
    BLOCK(0x3a0,0x47554301,16,1,0x7f,3,12,24,16384,1024,1024,256,64,0x0e00000b,0x1c00002b,0x1e00002b,31);
    BLOCK(0x4c0,0x47414201,8,8,0x7fff,0xf00001ff,15,7,0);
    BLOCK(0x4e0,0x474e5001,8,512,2048,2048,512,15,0);
    BLOCK(0x300,0x47534c01,8,31,32,1048576,0x04051918,0x320,0x138);
#undef BLOCK
    assert(n==96);
}
__attribute__((unused)) static void native_test_profile(void)
{
    struct native_kernel_profile_word words[96],regs[5];
    native_test_profile_words(words,regs);
    unsigned n=96;
    struct native_kernel_profile *p=native_kernel_profile_create(words,n,regs,5);
    assert(p);u64 value=0;struct opengpu_native_shader_layout layout;
    assert(!native_kernel_profile_query(p,OPENGPU_PARAM_DRIVER_FEATURES,&value) && value==OPENGPU_DRIVER_FEATURE_NATIVE_BINNED);
    assert(!native_kernel_profile_query(p,OPENGPU_PARAM_NATIVE_LAYOUT,&value) && opengpu_native_layout_decode(value,&layout));
    assert(layout.capacity==128 && layout.attributes==16 && layout.bindings==16 && layout.varyings==16 && layout.colors==4);
    for(unsigned i=0;i<8;i++){assert(!native_kernel_profile_query(p,OPENGPU_PARAM_NATIVE_CAPS_BASE+i,&value));assert(value==words[16+i].value);}
    value=0xfeed;assert(native_kernel_profile_query(p,0x108,&value)==-EINVAL && value==0xfeed);
    struct native_kernel_session *session=native_kernel_session_create_profile(17,p);
    assert(session && session->context.id==17 && session->gpu.hw.native_sources.capacity==128 && session->gpu.hw.native_sources.frame.words[7]==0x8813d902);
    assert(!native_kernel_profile_query(p,OPENGPU_PARAM_SHADER_CONFIG,&value) && value==0x02020810);
    native_kernel_profile_destroy(p);assert(!native_kernel_session_destroy(session));
    assert(!native_kernel_profile_create(words,95,regs,5));assert(!native_kernel_profile_create(words,96,regs,3));
    for(unsigned i=0;i<101;i++){
        struct native_kernel_profile_word *w=i<96?&words[i]:&regs[i-96];u32 saved=w->address;
        w->address=0x7fc;assert(!native_kernel_profile_create(words,96,regs,5));w->address=saved;
    }
    words[1].address=words[0].address;assert(!native_kernel_profile_create(words,96,regs,5));words[1].address=0x364;
    for(unsigned b=0;b<32;b++){
        words[2].value=0x3ffff^(1u<<b);assert(!native_kernel_profile_create(words,96,regs,5));
    }words[2].value=0x3ffff;
    for(unsigned b=0;b<32;b++){
        unsigned index=72; /* GAB first word; exact transport is mandatory. */
        u32 saved=words[index].value;words[index].value^=1u<<b;
        assert(!native_kernel_profile_create(words,96,regs,5));words[index].value=saved;
    }
}
#ifndef OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN
#define OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN main
#endif
struct native_test_record {
    u32 job[128],draw[512],state[128];
    struct drm_opengpu_native_draw_plan plan;
    struct opengpu_native_frame_encoding encoded;
    struct drm_opengpu_submit_native_binned submit;
};
static u32 test_next_handle;
static struct opengpu_native_frame_gem test_gem(struct native_kernel_session *s,u32 bytes)
{
    u32 h=++test_next_handle;assert(native_kernel_original_allocate(s,h,(bytes+4095u)&~4095u));
    return (struct opengpu_native_frame_gem){h,0,bytes};
}
static void test_record_init(struct native_kernel_session *s,struct native_test_record *r,u32 code,u32 defaults)
{
    const struct opengpu_native_binned_host_caps caps={{0x474e4303,8,0x474e4a01,0x08000200,0xc2100800,0x08000800,0xe4908000,0x902}};
    struct opengpu_native_frame_request request={.caps=&caps,.width=4,.height=4,.samples=1,
        .color_mask=1,.context=s->context.id,.pass=8,.generation=9,.state_count=1,.scratch_stride=8192};
    memset(r,0,sizeof(*r));
    request.attachments[0].format=26;request.attachments[0].channel_mask=15;
    request.attachments[0].image=(struct opengpu_native_frame_image){.gem=test_gem(s,1024),.row_pitch=64,.store=1};
    request.primitives=test_gem(s,4096);request.directory=test_gem(s,64);request.pool[0]=test_gem(s,4096);
    for(unsigned e=0;e<4;e++)request.scratch[e]=test_gem(s,8192);
    assert(!opengpu_native_frame_encode(&request,&r->encoded));
    const u32 j[]={0x474e4a01,512,1,16,16,16,4,4},d[]={0x474e4401,2048,1,16,16,16,4,4};
    memcpy(r->job,j,sizeof(j));memcpy(r->draw,d,sizeof(d));
    r->job[17]=1;r->job[20]=2048;r->job[24]=s->context.id;r->job[25]=9;r->job[26]=8;
    r->draw[31]=1;r->draw[311]=3;r->draw[314]=s->context.id;r->draw[315]=9;r->draw[316]=8;
    r->draw[325]=1;r->draw[327]=2;r->draw[346]=1;
    r->state[0]=0x47465301;r->state[1]=512;r->state[2]=1;r->state[3]=4;
    r->state[4]=s->context.id;r->state[5]=8;r->state[6]=9;r->state[27]=1;r->state[28]=4;
    r->state[30]=1;r->state[31]=1;r->state[32]=1;r->state[36]=s->context.id;r->state[37]=9;
    r->state[38]=8;r->state[56]=1;r->state[93]=1;r->state[94]=1;
    r->plan.vertex_program=(struct drm_opengpu_native_program_view){.handle=code,.bytes=4};
    r->plan.fragment_program=r->plan.vertex_program;
    r->plan.vertex_uniforms.defaults=(struct drm_opengpu_compute_uniform_view){.handle=defaults,.offset=4,.bytes=16};
    r->plan.fragment_uniforms=r->plan.vertex_uniforms;
    struct opengpu_native_frame_gem vs=test_gem(s,4096);
    r->plan.vertex_scratch=(struct drm_opengpu_compute_uniform_view){.handle=vs.handle,.bytes=4096};
    r->submit=(struct drm_opengpu_submit_native_binned){.version=1,.context_id=s->context.id,.out_syncobj=2,
        .job=(uintptr_t)r->job,.frame=(uintptr_t)&r->encoded.frame,.draws=(uintptr_t)r->draw,.draws_bytes=sizeof(r->draw),
        .states=(uintptr_t)r->state,.states_bytes=sizeof(r->state),.plans=(uintptr_t)&r->plan,.plan_count=1,
        .plan_stride=sizeof(r->plan),.resources=(uintptr_t)r->encoded.resources,
        .resource_count=r->encoded.resource_count,.resource_stride=sizeof(r->encoded.resources[0]),.fence_seqno=0xfeed};
}
static unsigned test_observations;
static void test_observe(void *record,u32 h,u64 offset,u32 bytes,const void *data)
{assert(record==&test_observations && h && bytes && data);(void)offset;++test_observations;}
static u32 test_read_va(struct native_kernel_session *s,u32 va)
{
    const struct opengpu_buffer *l1=vm_live_l1(&s->context.vm,va/MMU_SUPERPAGE_SIZE);assert(l1);
    u32 leaf=((u32 *)l1->cpu)[(va%MMU_SUPERPAGE_SIZE)/4096];assert(leaf&2u);
    u64 pa=((u64)(leaf>>10)<<12)+(va&4095);
    for(struct fixture_buffer_record *b=fixture_buffers;b;b=b->next)if(pa>=b->dma && pa+4<=b->dma+b->size){
        const u8 *p=(const u8 *)b->cpu+(pa-b->dma);return (u32)p[0]|((u32)p[1]<<8)|((u32)p[2]<<16)|((u32)p[3]<<24);
    }assert(0);return 0;
}
static u32 test_default_word(struct native_kernel_session *s,unsigned index)
{
    struct opengpu_sched_job *job=native_record(s,index);u32 va;assert(job);
    assert(!opengpu_private_snapshot_native_job_address(job->native_owner,job->native_job_segment,&va));
    u32 draws=test_read_va(s,va+16*4),root=test_read_va(s,draws+507*4);
    return test_read_va(s,test_read_va(s,test_read_va(s,root+13*4)+8*4));
}
static struct opengpu_sched_job *test_ordinary_queue(struct native_kernel_session *s,
    struct opengpu_render_context *context)
{
    struct opengpu_sched_job *job=calloc(1,sizeof(*job));assert(job);
    job->gpu=&s->gpu;job->type=0;
    mutex_lock(&s->render.lock);
    assert(!opengpu_sched_job_submit(&s->render,&s->file,context,job,0,NULL,NULL,0,NULL));
    mutex_unlock(&s->render.lock);return job;
}
static unsigned test_ordinary_retire_progress;
static void test_retire_prior_ordinary(struct native_kernel_session *s)
{
    assert(s->gpu.compute.admission_lock.held && !s->gpu.hw.submit_lock.held &&
        !s->gpu.hw.fence_lock && s->gpu.compute.ordinary_pending==1 && !s->gpu.mmu.snapshot_leases);
    assert(native_record(s,0)->type!=OPENGPU_SCHED_NATIVE_BINNED);
    s->progress=NULL;++test_ordinary_retire_progress;
    assert(!native_kernel_retire(s,0,0));
}
int OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN(void)
{
    native_test_profile();
    struct native_kernel_session *s=native_kernel_session_create(7);assert(s);
    /* Traverse every driver-side admission allocation, including the raw
     * execution-chain node freed before drm_sched_job_arm on failure. */
    for(int gate=0;gate<4;gate++){
        struct opengpu_sched_job j={.gpu=&s->gpu};int baseline=live_allocations;
        private_alloc_fail_after=gate;int ret=opengpu_admission_job_prepare(&j);
        private_alloc_fail_after=-1;
        assert(ret==(gate<3?-ENOMEM:0));opengpu_admission_job_abort(&j);
        assert(live_allocations==baseline && !s->gpu.compute.admission_tail && !s->gpu.compute.execution_tail);
    }
    u32 code=test_gem(s,4096).handle,defaults=test_gem(s,4096).handle;
    word(original_handle(s,code)->gem.vaddr,0,0x30500073u);
    word(original_handle(s,defaults)->gem.vaddr,4,0x11223344u);
    struct native_test_record first,second;
    test_record_init(s,&first,code,defaults);test_record_init(s,&second,code,defaults);
    first.submit.flags=1;assert(native_kernel_submit(s,&first.submit,NULL,NULL)==-EINVAL);
    assert(first.submit.fence_seqno==0xfeed && !s->head && s->context.references==1);first.submit.flags=0;
    s->copy_error=1;assert(native_kernel_submit(s,&first.submit,NULL,NULL)==-EFAULT);s->copy_error=0;
    assert(native_kernel_original_refcount(s,code)==1 && !s->head);
    /* A previously queued ordinary job has not run yet. No native GPU lease
     * may appear until it retires; completion must progress without submit_lock. */
    struct opengpu_render_context other={.gpu=&s->gpu,.references=1,.id=8};
    test_ordinary_queue(s,&other);
    assert(s->gpu.compute.ordinary_pending==1 && other.references==2);
    assert(native_kernel_submit(s,&first.submit,NULL,NULL)==-ETIMEDOUT);
    assert(first.submit.fence_seqno==0xfeed && !s->gpu.mmu.snapshot_leases && s->count==1);
    s->gpu.hw.wedged=true;
    assert(native_kernel_submit(s,&first.submit,NULL,NULL)==-EIO);
    assert(!s->gpu.mmu.snapshot_leases && s->count==1);s->gpu.hw.wedged=false;
    s->progress=test_retire_prior_ordinary;
    assert(!native_kernel_submit(s,&first.submit,test_observe,&test_observations));
    assert(test_ordinary_retire_progress==1 && !s->gpu.compute.ordinary_pending && other.references==1);
    assert(test_observations==4 && first.submit.fence_seqno!=0xfeed && s->context.references==3);
    assert(test_default_word(s,0)==0x11223344u && native_kernel_original_refcount(s,defaults)==2);
    word(original_handle(s,defaults)->gem.vaddr,4,0x55667788u);
    assert(!native_kernel_submit(s,&second.submit,test_observe,&test_observations));
    assert(test_observations==8 && s->context.references==5);
    assert(test_default_word(s,0)==0x11223344u && test_default_word(s,1)==0x55667788u);
    assert(native_kernel_original_refcount(s,defaults)==3 && native_kernel_session_destroy(s)==-EBUSY);
    /* Cancel a later entity job before either native predecessor has run.
     * Its self retirement is signaled, but the chain must retain both older
     * native owners. A still later ordinary entity cannot steal the credit. */
    struct opengpu_sched_job *cancelled=test_ordinary_queue(s,&other);
    struct dma_fence *cancelled_chain=dma_fence_get(cancelled->admission_chain);
    assert(!native_kernel_retire(s,2,-ECANCELED));
    assert(!dma_fence_is_signaled(cancelled_chain) && other.references==1);
    struct opengpu_sched_job *later=test_ordinary_queue(s,&other);
    assert(later->base.dependencies && later->base.dependencies->fence==cancelled_chain);
    assert(native_kernel_run(s,2)==-EBUSY && s->gpu.compute.ordinary_pending==1);
    assert(!native_kernel_original_close(s,defaults) && native_kernel_original_refcount(s,defaults)==2);
    assert(!native_kernel_original_close(s,code) && native_kernel_original_refcount(s,code)==2);
    assert(!native_kernel_run(s,0));
    assert(native_kernel_run(s,1)==-EBUSY);
    native_cpu_complete(s,&native_record(s,0)->base,0);
    /* Execution completion permits native2 while both owners, page tables,
     * private old/new data and original refs remain retained. Ordinary still
     * waits on retirement even after both execution fences are complete. */
    assert(!native_kernel_run(s,1));
    assert(native_kernel_original_refcount(s,defaults)==2 && s->context.references==5);
    assert(test_default_word(s,0)==0x11223344u && test_default_word(s,1)==0x55667788u);
    native_cpu_complete(s,&native_record(s,1)->base,-EIO);
    assert(native_kernel_run(s,2)==-EBUSY && !dma_fence_is_signaled(cancelled_chain));
    assert(!native_kernel_retire(s,0,0));
    assert(!dma_fence_is_signaled(cancelled_chain) && native_kernel_run(s,1)==-EBUSY);
    assert(native_kernel_original_refcount(s,defaults)==1 && s->context.references==3 && test_default_word(s,0)==0x55667788u);
    assert(!native_kernel_retire(s,0,-EIO));
    assert(!native_kernel_original_refcount(s,defaults) && !native_kernel_original_refcount(s,code));
    assert(dma_fence_is_signaled(cancelled_chain));dma_fence_put(cancelled_chain);
    assert(!native_kernel_run(s,0) && !native_kernel_retire(s,0,0));
    assert(!s->gpu.compute.ordinary_pending && other.references==1);
    dma_fence_put(other.last_fence);
    assert(!native_kernel_session_destroy(s));assert(!live_allocations && !fixture_buffers);
    puts("native submit: actual ioctl/scheduler/owner/MMU, two prepared snapshots, post-producer observer, real file-close/job refs and independent retirement, prior-ordinary timeout/fault/progress and cross-entity cancelled-predecessor chain PASS (CPU only; modeled hardware kick)");return 0;
}
