/* OS primitives only; imported driver functions remain in generated includes. */
static struct dma_fence *dma_fence_get(struct dma_fence *f){if(f)++f->refs;return f;}
static void dma_fence_put(struct dma_fence *f){if(f){assert(f->refs);if(!--f->refs){if(f->ops && f->ops->release)f->ops->release(f);else free(f);}}}
static struct drm_syncobj *drm_syncobj_find(struct drm_file *f,u32 h)
{return h<3?&f->driver_priv->session->sync[h]:NULL;}
static void drm_syncobj_put(struct drm_syncobj *s){(void)s;}
static int drm_syncobj_find_fence(struct drm_file *f,u32 h,u64 point,u64 flags,struct dma_fence **out)
{struct drm_syncobj *s=drm_syncobj_find(f,h);assert(!point&&!flags);
 if(!s || !s->fence)return -ENOENT;
 *out=dma_fence_get(s->fence);return 0;}
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
static void *kcalloc(size_t n,size_t bytes,int flags)
{if(n>SIZE_MAX/bytes)return NULL;return kzalloc(n*bytes,flags);}
static int drm_sched_job_init(struct drm_sched_job *j,struct drm_sched_entity *entity,int n,void *owner,u64 client)
{(void)entity;(void)owner;(void)client;assert(n==1);if(compute_running->submit_error)return compute_running->submit_error;
 j->s_fence=calloc(1,sizeof(*j->s_fence));if(!j->s_fence)return -ENOMEM;
 j->s_fence->finished=(struct dma_fence){.refs=1,.seqno=++compute_running->next_seq};return 0;}
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

static void compute_mmio_progress(struct opengpu_device *);
static int opengpu_hw_fence_wait(struct opengpu_device *gpu,struct dma_fence *f,unsigned ms,bool interruptible)
{if(gpu->hw.submit_lock.held){compute_mmio_progress(gpu);return dma_fence_is_signaled(f)?f->error:-ETIMEDOUT;}
 assert(!gpu->hw.submit_lock.held && !gpu->hw.fence_lock && ms==OPENGPU_DRAW_WAIT_MS+100 && interruptible);
 if(compute_running->wait_error)return compute_running->wait_error;
 return dma_fence_is_signaled(f)?f->error:-ETIMEDOUT;}
static int dma_fence_get_status(struct dma_fence *f)
{return dma_fence_is_signaled(f)?(f->error?f->error:1):0;}
static long dma_resv_wait_timeout(struct dma_resv *r,enum dma_resv_usage usage,bool interruptible,long timeout)
{assert(r->locked && interruptible && timeout==MAX_SCHEDULE_TIMEOUT);
 if(compute_running->wait_error)return compute_running->wait_error;
 if(r->write && !dma_fence_is_signaled(r->write))return 0;
 if(usage==DMA_RESV_USAGE_READ && r->read && !dma_fence_is_signaled(r->read))return 0;
 return 1;}
static unsigned long jiffies;
static void opengpu_hw_progress_tick(struct opengpu_device *gpu){(void)gpu;++jiffies;}
static void usleep_range(unsigned a,unsigned b){assert(a==100 && b==200);}
static void opengpu_context_get(struct opengpu_render_context *c){assert(c->refs);++c->refs;}
static void opengpu_context_put(struct opengpu_render_context *c){assert(c->refs>1);--c->refs;}
#include "../opengpu_admission.h"
#include "compute_host_admission_under_test.inc"
static int drm_sched_job_add_syncobj_dependency(struct drm_sched_job *j,struct drm_file *f,u32 h,u64 point)
{struct dma_fence *fence=NULL;int error=drm_syncobj_find_fence(f,h,point,0,&fence);
 return error?error:drm_sched_job_add_dependency(j,fence);}
static int drm_sched_job_add_resv_dependencies(struct drm_sched_job *j,struct dma_resv *r,int usage)
{assert(r->locked);struct dma_fence *fences[]={r->write,usage==DMA_RESV_USAGE_READ?r->read:NULL};
 for(unsigned i=0;i<2;i++)if(fences[i] && !dma_fence_is_signaled(fences[i])){
  int error=drm_sched_job_add_dependency(j,dma_fence_get(fences[i]));if(error)return error;}
 return 0;}
static void drm_sched_job_arm(struct drm_sched_job *j){assert(j->s_fence);}
static void dma_resv_add_fence(struct dma_resv *r,struct dma_fence *f,int usage)
{assert(r->locked);struct dma_fence **p=usage==DMA_RESV_USAGE_READ?&r->read:&r->write;
 dma_fence_put(*p);*p=dma_fence_get(f);}
static void drm_syncobj_replace_fence(struct drm_syncobj *s,struct dma_fence *f)
{dma_fence_put(s->fence);s->fence=dma_fence_get(f);}
static void drm_sched_entity_push_job(struct drm_sched_job *j)
{if(compute_running->tail)compute_running->tail->next=j;else compute_running->head=j;
 compute_running->tail=j;++compute_running->queued;}
static void opengpu_tessellation_job_release(struct opengpu_sched_job *job){(void)job;assert(0);}
static int opengpu_private_snapshot_retire(void *owner){(void)owner;assert(0);return -EOPNOTSUPP;}
static struct opengpu_sched_job *opengpu_sched_job_from_base(struct drm_sched_job *job)
{return container_of(job,struct opengpu_sched_job,base);}
#include "compute_host_scheduler_under_test.inc"
#include "compute_host_wait_reservation_under_test.inc"
#include "compute_host_events_under_test.inc"
#include "compute_host_lookup_under_test.inc"
static enum opengpu_backing_kind opengpu_gem_backing_step(const void *p,const void **next)
{const struct drm_gem_object *o=p;if(o->import_attach){*next=o->import_attach;return OPENGPU_BACKING_FOREIGN;}return OPENGPU_BACKING_LOCAL;}
static const void *memchr_inv(const void *p,int value,size_t size)
{const u8 *b=p;while(size--){if(*b!=(u8)value)return b;++b;}return NULL;}
static int copy_from_user(void *to,const void *from,size_t bytes)
{if(compute_running->copy_error)return 1;memcpy(to,from,bytes);return 0;}
#include "compute_host_gem_under_test.inc"
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-parameter"
#include "compute_host_ioctl_under_test.inc"
#pragma GCC diagnostic pop
