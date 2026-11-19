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
