/* CPU admission only: these pending fences never stand in for DUT receipts. */
#define OPENGPU_COMPUTE_HOST_TEST_MAIN 1
#define OPENGPU_COMPUTE_HOST_TEST_NO_MAIN 1
#include "opengpu_compute_reservation_fixture.c"

static void clean(void)
{assert(!live_allocations && !fixture_buffers && !compute_running);}

static void pair(const uint32_t *program,size_t words,const uint8_t *initial,bool shared,uint32_t flags)
{
 struct compute_reservation_session *r=compute_reservation_create(test_profile,ARRAY_SIZE(test_profile),
  program,words,initial,8192,shared,flags);assert(r);
 struct compute_reservation_info info;memset(&info,0x55,sizeof(info));
 struct compute_reservation_info untouched=info;
 assert(compute_reservation_snapshot(r,&info)==-EINVAL && !memcmp(&info,&untouched,sizeof(info)));
 assert(!compute_reservation_admit(r));assert(compute_reservation_admit(r)==-EINVAL);
 assert(!compute_reservation_snapshot(r,&info));
 assert(info.contexts[0] && info.contexts[1] && info.contexts[0]!=info.contexts[1]);
 assert(info.objects[0]==1 && info.objects[1]==1 && info.queued==2);
 assert(!info.dependencies[0] && !info.pending_dependencies[0]);
 /* An ordinary job also waits on global retirement. The disjoint control
  * deliberately remains pending: that edge cannot prove a GEM wait. */
 assert(info.dependencies[1]==(shared?2u:1u));
 assert(info.pending_dependencies[1]==info.dependencies[1]);
 assert(info.shared_original==shared && info.reservation_dependencies==shared &&
  info.producer_reservation_dependencies==shared);
 if(shared)assert(info.reservation_usage==DMA_RESV_USAGE_READ);
 assert(!info.fence_status[0] && !info.fence_status[1] && !info.kernel_submissions);
 assert(info.sequence[1]==info.sequence[0]+1 && info.current_write_sequence==info.sequence[1]);
 assert((info.physical[0]==info.physical[1])==shared);
 struct opengpu_render_context *contexts[2];
 for(unsigned i=0;i<2;i++){
  struct opengpu_sched_job *job=opengpu_sched_job_from_base(r->jobs[i]);
  contexts[i]=opengpu_context_lookup(&r->host->render,r->contexts[i]);
  struct compute_original *original=r->host->render.originals[r->handles[i]];
  assert(job->objects[0]==&original->gem.base && job->context==contexts[i]);
  assert(job->shader.size==words*4 && !memcmp(job->shader.cpu,program,words*4));
  assert(!memcmp(original->gem.vaddr,initial,8192));
  assert(r->host->sync[i+1].fence==&job->base.s_fence->finished);
  for(unsigned n=0;n<4;n++){
   const uint32_t offsets[]={0,4092,4096,8188};uint32_t pte;
   struct opengpu_resource_binding *binding=contexts[i]->bindings[1];
   assert(translate(&contexts[i]->vm,binding->va+offsets[n],&pte)==binding->dma+offsets[n]);
   assert(pte==(flags?0xcfu:0x2cfu));
  }
 }
 assert(contexts[0]->vm.root.dma!=contexts[1]->vm.root.dma);
 assert(opengpu_sched_job_from_base(r->jobs[0])->shader.cpu!=opengpu_sched_job_from_base(r->jobs[1])->shader.cpu);
 /* The legacy one-owner execution entry is intentionally unavailable for
  * two queued jobs. This CPU stage does not claim an actual blocked DUT. */
 assert(compute_host_begin_execution(r->host)==-EBUSY);
 assert(!compute_reservation_destroy(r));clean();
}

static void external_producer(const uint32_t *program,size_t words,const uint8_t *initial,
 uint32_t flags,bool disjoint,bool cancel)
{
 struct compute_host_session *s=compute_host_open(test_profile,ARRAY_SIZE(test_profile));assert(s);
 void *code=compute_host_original_allocate(s,1,4096),*data=compute_host_original_allocate(s,2,8192);
 assert(code && data);memcpy(code,program,words*4);memcpy(data,initial,8192);
 if(disjoint){void *other=compute_host_original_allocate(s,3,8192);assert(other);memcpy(other,initial,8192);}
 uint32_t context;assert(!compute_host_context_create(s,&context));
 struct drm_opengpu_resource b={.context_id=context,.slot=1,.handle=1,
  .type=OPENGPU_RESOURCE_COMPUTE_SHADER,.size=words*4};assert(!compute_host_bind(s,&b));
 b=(struct drm_opengpu_resource){.context_id=context,.slot=2,.handle=disjoint?3:2,
  .type=OPENGPU_RESOURCE_COMPUTE_KERNARG,.size=8192,.flags=flags};assert(!compute_host_bind(s,&b));
 assert(!compute_reservation_producer_create(NULL,2));
 assert(!compute_reservation_producer_create(s,1));assert(!compute_reservation_producer_create(s,511));
 struct compute_reservation_producer *p=compute_reservation_producer_create(s,2);assert(p);
 assert(!compute_reservation_producer_create(s,2));
 assert(!s->gpu.compute.admission_tail && !s->gpu.compute.execution_tail && !s->next_seq);
 assert(s->render.originals[2]->resv.write==p->fence && !dma_fence_get_status(p->fence));
 struct compute_reservation_wait_info info;memset(&info,0x55,sizeof(info));
 struct compute_reservation_wait_info unchanged=info;
 assert(compute_reservation_producer_snapshot(p,&info)==-EBUSY && !memcmp(&info,&unchanged,sizeof(info)));
 assert(compute_reservation_producer_destroy(p)==-EBUSY);
 struct drm_opengpu_compute command={.context_id=context,.shader_slot=1,.kernarg_slot=2,
  .grid={4,1,1},.local={16,1,1},.out_syncobj=1};assert(!compute_host_submit(s,&command));
 assert(!compute_reservation_producer_create(s,2));
 assert(!compute_reservation_producer_snapshot(p,&info));
 assert(info.context==context && info.handle==2 && info.objects==1 && info.queued==1 && !info.kernel_submissions);
 assert(info.producer_context && info.producer_sequence==1 && info.consumer_sequence==1);
 assert(!info.producer_status && !info.consumer_status && !info.blocked_attempts && info.bytes==8192);
 assert(info.physical==s->render.originals[2]->gem.dma_addr);
 assert(info.dependencies==!disjoint && info.pending_dependencies==!disjoint &&
  info.reservation_dependencies==!disjoint && info.producer_dependencies==!disjoint &&
  info.original_identity==!disjoint);
 assert(compute_reservation_producer_signal(p)==-EINVAL);
 if(disjoint){
  assert(compute_reservation_producer_attempt(p)==-EINVAL);
  assert(!compute_mmio_state(&s->gpu)->kernel_submissions);
 }else{
  assert(info.reservation_usage==DMA_RESV_USAGE_READ);
  struct fixture_dependency *dependency=s->head->dependencies;
  assert(dependency && !dependency->next && dependency->fence==p->fence &&
   dependency->reservation==p->original->resv);
  dependency->reservation=NULL;assert(compute_reservation_producer_attempt(p)==-EINVAL);
  assert(compute_reservation_producer_signal(p)==-EINVAL);dependency->reservation=p->original->resv;
  dependency->reservation_usage=DMA_RESV_USAGE_WRITE;
  assert(compute_reservation_producer_attempt(p)==-EINVAL);dependency->reservation_usage=DMA_RESV_USAGE_READ;
  ((uint8_t *)data)[192]^=1;assert(compute_reservation_producer_snapshot(p,&info)==-EINVAL);
  assert(compute_reservation_producer_attempt(p)==-EINVAL && compute_reservation_producer_signal(p)==-EINVAL);
  ((uint8_t *)data)[192]^=1;
  assert(compute_reservation_producer_attempt(p)==-EBUSY);
  assert(compute_reservation_producer_attempt(p)==-EBUSY);
  assert(!compute_reservation_producer_snapshot(p,&info));
  assert(info.blocked_attempts==2 && info.pending_dependencies==1 && !info.kernel_submissions);
  if(!cancel){
   assert(!compute_reservation_producer_signal(p));
   assert(compute_reservation_producer_signal(p)==-EINVAL && compute_reservation_producer_attempt(p)==-EINVAL);
  }else{
   /* An untagged extra edge (such as input-sync/global serialization) must
    * not be mistaken for the unique original-GEM wait. Normal cancellation
    * releases both dependency references below. */
   assert(!drm_sched_job_add_dependency(s->head,dma_fence_get(p->fence)));
   assert(!compute_reservation_producer_snapshot(p,&info) && info.dependencies==2 && info.pending_dependencies==2);
   assert(compute_reservation_producer_attempt(p)==-EINVAL && compute_reservation_producer_signal(p)==-EINVAL);
  }
  const unsigned boundaries[]={0,4092,4096,8188};
  for(unsigned n=0;n<ARRAY_SIZE(boundaries);n++){
   unsigned offset=boundaries[n];
   uint32_t pte;struct opengpu_resource_binding *binding=s->context->bindings[1];
   assert(translate(&s->context->vm,binding->va+offset,&pte)==binding->dma+offset);
   assert(pte==(flags?0xcfu:0x2cfu));
  }
 }
 if(cancel || disjoint){assert(!compute_reservation_producer_cancel(p));
  assert(compute_reservation_producer_cancel(p)==-EINVAL && compute_reservation_producer_signal(p)==-EINVAL);}
 assert(!compute_reservation_producer_snapshot(p,&info));
 assert(info.producer_status==((cancel || disjoint)?-ECANCELED:1) && !info.consumer_status &&
  !info.pending_dependencies && !info.kernel_submissions && !s->prepared_execution);
 assert(!memcmp(data,initial,8192));
 struct opengpu_sched_job *job=opengpu_sched_job_from_base(s->head);
 assert(job->shader.size==words*4 && !memcmp(job->shader.cpu,program,words*4));
 assert(!compute_reservation_producer_destroy(p));
 /* Source-only cancellation of the still-unexecuted GPU job. Producer
  * completion has never supplied a GPU fence, result word or DUT receipt. */
 assert(!compute_host_cancel_unexecuted(s));
 int error=compute_host_destroy(s);if(error==-ECANCELED)error=compute_host_destroy(s);
 assert(!error);clean();
}

int main(int argc,char **argv)
{
 assert(argc==2);uint32_t program[513];uint8_t initial[8192];size_t first_words=0;
 for(unsigned number=0;number<24;number++){
  char path[4096];assert(snprintf(path,sizeof(path),"%s/program-%u.bin",argv[1],number)>0);
  FILE *f=fopen(path,"rb");assert(f);size_t words=fread(program,4,513,f);assert(words && words<=512 && fgetc(f)==EOF);assert(!fclose(f));
  assert(snprintf(path,sizeof(path),"%s/initial-%u.bin",argv[1],number)>0);
  f=fopen(path,"rb");assert(f && fread(initial,1,sizeof(initial),f)==sizeof(initial) && fgetc(f)==EOF);assert(!fclose(f));
  for(unsigned flags=0;flags<2;flags++)for(unsigned shared=0;shared<2;shared++)
   pair(program,words,initial,shared,flags?OPENGPU_RESOURCE_CACHED:0);
  for(unsigned flags=0;flags<2;flags++){
   for(unsigned cancel=0;cancel<2;cancel++)external_producer(program,words,initial,
    flags?OPENGPU_RESOURCE_CACHED:0,false,cancel);
   external_producer(program,words,initial,flags?OPENGPU_RESOURCE_CACHED:0,true,true);
  }
  first_words=words;
 }
 assert(!compute_reservation_create(test_profile,ARRAY_SIZE(test_profile),program,513,initial,8192,true,0));
 assert(!compute_reservation_create(test_profile,ARRAY_SIZE(test_profile),program,first_words,initial,8191,true,0));
 assert(!compute_reservation_create(test_profile,ARRAY_SIZE(test_profile),program,first_words,initial,8192,true,
  OPENGPU_RESOURCE_CACHED|OPENGPU_RESOURCE_UNCACHED));clean();
 {
  struct compute_reservation_session *r=compute_reservation_create(test_profile,ARRAY_SIZE(test_profile),
   program,first_words,initial,8192,true,0);assert(r);
  r->contexts[1]=r->contexts[0];
  assert(compute_reservation_admit(r)==-EINVAL && r->host->queued==1);
  assert(!compute_reservation_destroy(r));clean();
 }
 {
  program[0]=0xffffffff;
  struct compute_reservation_session *r=compute_reservation_create(test_profile,ARRAY_SIZE(test_profile),
   program,first_words,initial,8192,true,0);assert(r);
  assert(compute_reservation_admit(r)==-EINVAL && !r->host->queued);
  assert(!compute_reservation_destroy(r));clean();
 }
 puts("original Linux reservation admission: 96 cross-context pairs / 192 jobs, shared-vs-disjoint attribution, two-page policies and cancellation cleanup PASS; CPU only, no shader or forced DUT wait");
 puts("external original-GEM producer: 96 blocked/released-or-cancelled cases + 48 disjoint controls PASS; unique reservation dependency, actual EBUSY attempts, zero hardware submissions; CPU only");
 return 0;
}
