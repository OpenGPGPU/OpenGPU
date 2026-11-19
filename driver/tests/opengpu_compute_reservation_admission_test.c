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
 return 0;
}
