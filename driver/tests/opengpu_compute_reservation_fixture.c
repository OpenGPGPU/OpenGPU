/* Exact production extraction and OS primitives; no shader execution. */
#include "opengpu_compute_host_fixture.c"
#include "opengpu_compute_reservation_fixture.h"

struct compute_reservation_session {
 struct compute_host_session *host;
 uint32_t contexts[2],handles[2];
 struct drm_sched_job *jobs[2];
};

struct compute_reservation_session *compute_reservation_create(
 const struct compute_host_profile_word *profile,size_t count,
 const uint32_t *program,size_t words,const void *initial,size_t bytes,
 bool shared,uint32_t flags)
{
 if(!program || !words || words>512 || !initial || bytes!=8192 ||
    (flags && flags!=OPENGPU_RESOURCE_CACHED))return NULL;
 struct compute_reservation_session *r=calloc(1,sizeof(*r));if(!r)return NULL;
 r->host=compute_host_open(profile,count);if(!r->host){free(r);return NULL;}
 struct compute_host_session *s=r->host;
 void *code=compute_host_original_allocate(s,1,4096);
 void *data=compute_host_original_allocate(s,2,8192);
 void *other=shared?NULL:compute_host_original_allocate(s,3,8192);
 if(!code || !data || (!shared && !other))goto fail;
 memcpy(code,program,words*4);memcpy(data,initial,bytes);
 if(other)memcpy(other,initial,bytes);
 r->handles[0]=2;r->handles[1]=shared?2:3;
 for(unsigned i=0;i<2;i++){
  if(compute_host_context_create(s,&r->contexts[i]))goto fail;
  struct drm_opengpu_resource b={.context_id=r->contexts[i],.slot=1,.handle=1,
   .type=OPENGPU_RESOURCE_COMPUTE_SHADER,.size=words*4};
  if(compute_host_bind(s,&b))goto fail;
  b=(struct drm_opengpu_resource){.context_id=r->contexts[i],.slot=2,.handle=r->handles[i],
   .type=OPENGPU_RESOURCE_COMPUTE_KERNARG,.size=8192,.flags=flags};
  if(compute_host_bind(s,&b))goto fail;
 }
 return r;
fail:
 assert(!compute_reservation_destroy(r));return NULL;
}

int compute_reservation_admit(struct compute_reservation_session *r)
{
 if(!r || !r->host || r->jobs[0])return -EINVAL;
 struct compute_host_session *s=r->host;
 if(s->head || s->exported || s->prepared_execution)return -EBUSY;
 for(unsigned i=0;i<2;i++){
  struct opengpu_render_context *context=opengpu_context_lookup(&s->render,r->contexts[i]);
  if(!context || context->last_fence)return -EINVAL;
  /* Different entities and no input sync object: reservation attribution
   * distinguishes this edge from the device-wide admission retirement chain. */
  struct drm_opengpu_compute command={.context_id=r->contexts[i],.shader_slot=1,.kernarg_slot=2,
   .grid={4,1,1},.local={16,1,1},.out_syncobj=i+1};
  compute_running=s;
  int error=opengpu_compute_launch_ioctl(&s->drm,&command,&s->file);if(error)return error;
  r->jobs[i]=s->tail;
 }
 return 0;
}

int compute_reservation_snapshot(struct compute_reservation_session *r,struct compute_reservation_info *out)
{
 if(!r || !out || !r->jobs[0] || !r->jobs[1])return -EINVAL;
 struct compute_host_session *s=r->host;
 struct drm_gem_object *originals[2];
 struct compute_reservation_info info={.queued=s->queued,
  .kernel_submissions=compute_mmio_state(&s->gpu)->kernel_submissions};
 for(unsigned i=0;i<2;i++){
  struct opengpu_sched_job *job=opengpu_sched_job_from_base(r->jobs[i]);
  originals[i]=&s->render.originals[r->handles[i]]->gem.base;
  info.contexts[i]=job->context->id;info.objects[i]=job->object_count;
  info.sequence[i]=job->base.s_fence->finished.seqno;
  info.fence_status[i]=dma_fence_get_status(&job->base.s_fence->finished);
  info.physical[i]=s->render.originals[r->handles[i]]->gem.dma_addr;
  for(struct fixture_dependency *d=job->base.dependencies;d;d=d->next){
   ++info.dependencies[i];info.pending_dependencies[i]+=!dma_fence_is_signaled(d->fence);
   if(i==1 && d->reservation==originals[i]->resv){
    ++info.reservation_dependencies;info.reservation_usage=(uint32_t)d->reservation_usage;
    info.producer_reservation_dependencies+=d->fence==&r->jobs[0]->s_fence->finished;
   }
  }
 }
 info.shared_original=originals[0]==originals[1];
 info.current_write_sequence=originals[1]->resv->write?originals[1]->resv->write->seqno:0;
 *out=info;return 0;
}

int compute_reservation_destroy(struct compute_reservation_session *r)
{
 if(!r || !r->host)return -EINVAL;
 struct compute_host_session *s=r->host;
 if(s->exported || s->prepared_execution)return -EBUSY;
 compute_running=s;
 while(s->head){
  struct drm_sched_job *base=s->head,*next=base->next;
  base->s_fence->finished.error=-ECANCELED;dma_fence_signal(&base->s_fence->finished);
  opengpu_sched_free_job(base);if(s->gpu.hw.wedged)return -EIO;
  s->head=next;if(!next)s->tail=NULL;--s->queued;
 }
 for(unsigned attempt=0;attempt<3;attempt++){
  int error=compute_host_destroy(s);
  if(!error){free(r);return 0;}
  if(error!=-ECANCELED)return error;
 }
 return -ECANCELED;
}

struct compute_external_fence {struct dma_fence base;spinlock_t lock;};
struct compute_reservation_producer {
 struct compute_host_session *host;
 struct drm_gem_object *original;
 struct dma_fence *fence;
 uint32_t handle,blocked_attempts;
 uint64_t context;
 uint8_t initial[8192];
};
static const char *compute_external_name(struct dma_fence *f)
{(void)f;return "external-cpu-original-gem";}
static const struct dma_fence_ops compute_external_ops={
 .get_driver_name=compute_external_name,.get_timeline_name=compute_external_name};

struct compute_reservation_producer *compute_reservation_producer_create(struct compute_host_session *s,uint32_t handle)
{
 if(!s || s->head || s->exported || s->prepared_execution || s->next_seq ||
    s->gpu.compute.admission_tail || s->gpu.compute.execution_tail ||
    s->gpu.compute.admission_seqno)return NULL;
 struct drm_gem_object *object=drm_gem_object_lookup(&s->file,handle);
 if(!object)return NULL;
 if(object->size!=8192){drm_gem_object_put(object);return NULL;}
 struct compute_reservation_producer *p=calloc(1,sizeof(*p));
 struct compute_external_fence *f=calloc(1,sizeof(*f));
 if(!p || !f){free(p);free(f);drm_gem_object_put(object);return NULL;}
 p->host=s;p->original=object;p->handle=handle;p->context=dma_fence_context_alloc(1);
 spin_lock_init(&f->lock);dma_fence_init(&f->base,&compute_external_ops,&f->lock,p->context,1);
 p->fence=&f->base;
 struct drm_exec exec;drm_exec_init(&exec,DRM_EXEC_INTERRUPTIBLE_WAIT|DRM_EXEC_IGNORE_DUPLICATES,0);
 int error=drm_exec_prepare_obj(&exec,object,1);
 if(!error && (object->resv->write || object->resv->read))error=-EBUSY;
 if(!error){
  memcpy(p->initial,to_drm_gem_dma_obj(object)->vaddr,sizeof(p->initial));
  dma_resv_add_fence(object->resv,p->fence,DMA_RESV_USAGE_WRITE);
 }
 drm_exec_fini(&exec);
 if(error){dma_fence_put(p->fence);drm_gem_object_put(object);free(p);return NULL;}
 return p;
}

int compute_reservation_producer_snapshot(struct compute_reservation_producer *p,struct compute_reservation_wait_info *out)
{
 if(!p || !out || !p->host)return -EINVAL;
 struct compute_host_session *s=p->host;
 if(s->exported || s->prepared_execution || !s->head || s->head!=s->tail)return -EBUSY;
 if(s->submitted.resources.resource_flags)return -EINVAL;
 const struct drm_opengpu_compute *command=&s->submitted.resources.launch;
 struct opengpu_sched_job *job=opengpu_sched_job_from_base(s->head);
 if(command->in_syncobj || command->kernarg_slot!=2 || !job->context ||
    job->context!=s->context || job->context->last_fence!=&s->head->s_fence->finished ||
    s->gpu.compute.admission_seqno!=1 || s->next_seq!=1 ||
    memcmp(p->initial,to_drm_gem_dma_obj(p->original)->vaddr,sizeof(p->initial)))return -EINVAL;
 struct compute_reservation_wait_info info={.context=job->context->id,.handle=p->handle,
  .objects=job->object_count,.queued=s->queued,.blocked_attempts=p->blocked_attempts,
  .producer_context=p->context,.producer_sequence=p->fence->seqno,
  .consumer_sequence=s->head->s_fence->finished.seqno,
  .producer_status=dma_fence_get_status(p->fence),
  .consumer_status=dma_fence_get_status(&s->head->s_fence->finished),
  .physical=to_drm_gem_dma_obj(p->original)->dma_addr,.bytes=p->original->size,
  .kernel_submissions=compute_mmio_state(&s->gpu)->kernel_submissions};
 struct opengpu_resource_binding *binding=job->context->bindings[1];
 info.original_identity=job->object_count==1 && job->objects[0]==p->original &&
  binding && binding->object==p->original && binding->size==8192;
 for(struct fixture_dependency *d=job->base.dependencies;d;d=d->next){
  ++info.dependencies;info.pending_dependencies+=!dma_fence_is_signaled(d->fence);
  if(d->reservation==p->original->resv){
   ++info.reservation_dependencies;info.reservation_usage=(uint32_t)d->reservation_usage;
   info.producer_dependencies+=d->fence==p->fence;
  }
 }
 *out=info;return 0;
}
static int compute_external_pending(struct compute_reservation_producer *p)
{
 struct compute_reservation_wait_info info;
 int error=compute_reservation_producer_snapshot(p,&info);if(error)return error;
 return info.queued==1 && info.objects==1 && info.original_identity && info.dependencies==1 &&
  info.pending_dependencies==1 && info.reservation_dependencies==1 && info.producer_dependencies==1 &&
  info.reservation_usage==DMA_RESV_USAGE_READ && !info.kernel_submissions &&
  !info.producer_status && !info.consumer_status?0:-EINVAL;
}
int compute_reservation_producer_attempt(struct compute_reservation_producer *p)
{
 int error=compute_external_pending(p);if(error)return error;
 error=compute_host_begin_execution(p->host);
 if(error!=-EBUSY || compute_mmio_state(&p->host->gpu)->kernel_submissions)return -EIO;
 ++p->blocked_attempts;return error;
}
int compute_reservation_producer_signal(struct compute_reservation_producer *p)
{
 int error=compute_external_pending(p);if(error)return error;
 if(!p->blocked_attempts)return -EINVAL;
 dma_fence_signal(p->fence);return 0;
}
int compute_reservation_producer_cancel(struct compute_reservation_producer *p)
{
 if(!p || !p->host)return -EINVAL;
 if(p->host->exported || p->host->prepared_execution)return -EBUSY;
 if(dma_fence_is_signaled(p->fence))return -EINVAL;
 p->fence->error=-ECANCELED;dma_fence_signal(p->fence);return 0;
}
int compute_reservation_producer_destroy(struct compute_reservation_producer *p)
{
 if(!p || !p->host)return -EINVAL;
 if(!dma_fence_is_signaled(p->fence))return -EBUSY;
 dma_fence_put(p->fence);drm_gem_object_put(p->original);free(p);return 0;
}
