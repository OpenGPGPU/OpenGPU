/* Linux admission composition only: explicit CPU test profile, no public
 * feature, hardware kick, shader VM or precomputed shader result. */
#define OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN pt_native_submit_regression
#include "opengpu_native_submit_fixture.c"
#undef OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN
struct pts_record {
 struct native_test_record base;
 struct opengpu_protected_tessellation_plan plan;
 struct opengpu_protected_tessellation_state_plan state;
 struct opengpu_protected_tessellation_request request;
};
static const struct opengpu_protected_tessellation_profile pts_profile={
 .version=1,.domains=7,.compute_units=2,.lanes=16,.warps=8,.engines=4,
 .available=true,.scalar_fpu=true,.patch_zero_initialized=true};
static void pts_init(struct native_kernel_session *s,struct pts_record *r,u32 domain,u32 code,u32 defaults)
{
 memset(r,0,sizeof(*r));test_record_init(s,&r->base,code,defaults);
 u32 *d=r->base.draw;memset(d,0,sizeof(r->base.draw));const struct pt_layout *l=&pt_layouts[domain];
 r->base.job[0]=0x474e5101u+domain;r->base.state[OPENGPU_TYPED_FRAGMENT_STATE_FRAGMENT_VARYINGSLOTS_WORD]=1;
 d[0]=l->magic;d[1]=2048;d[2]=l->version;d[16]=1;d[17]=32;d[184]=s->context.id;d[185]=9;d[186]=8;
 d[l->input_vertices]=2;d[l->input_components]=4;d[l->output_vertices]=2;d[l->output_components]=8;
 for(u32 i=0;i<3;++i){d[l->control_grid+i]=1;d[l->evaluation_grid+i]=1;d[l->control_local+i]=i?1:2;d[l->evaluation_local+i]=1;}
 d[l->tes_outputs]=8;d[l->selections]=8;d[l->varyings]=1;d[l->vertex_stride]=16;
 for(u32 i=0;i<8;++i)d[l->selection_first+4*i]=i;
 d[l->viewport]=4;d[l->viewport+1]=4;d[l->viewport+2]=0x3f800000;d[l->viewport+4]=1;d[39]=1;d[37]=4;d[38]=4;
 u32 tes=test_gem(s,4096).handle;void *bytes=original_handle(s,tes)->gem.vaddr;
 word(bytes,64,0xffffffff);word(bytes,68,35u<<25|0xb);word(bytes,72,36u<<25|0xb);word(bytes,76,0x30500073);
 r->plan=(struct opengpu_protected_tessellation_plan){.control_program={.handle=code,.bytes=8,.entry_offset=4},
  .evaluation_program={.handle=tes,.offset=64,.bytes=16,.entry_offset=4},
  .control_uniforms=r->base.plan.vertex_uniforms,.evaluation_uniforms=r->base.plan.fragment_uniforms,
  .input={.handle=test_gem(s,4096).handle,.offset=64,.bytes=32}};
 r->state=(struct opengpu_protected_tessellation_state_plan){.fragment_program={.handle=code,.bytes=8,.entry_offset=4},
  .fragment_uniforms=r->base.plan.fragment_uniforms};
 r->request=(struct opengpu_protected_tessellation_request){.version=1,.context_id=s->context.id,.out_syncobj=2,
  .job=(uintptr_t)r->base.job,.frame=(uintptr_t)&r->base.encoded.frame,.sequences=(uintptr_t)r->base.draw,.sequences_bytes=2048,
  .states=(uintptr_t)r->base.state,.states_bytes=512,.plans=(uintptr_t)&r->plan,.plan_count=1,.plan_stride=sizeof(r->plan),
  .state_plans=(uintptr_t)&r->state,.state_plan_count=1,.state_plan_stride=sizeof(r->state),
  .resources=(uintptr_t)r->base.encoded.resources,.resource_count=r->base.encoded.resource_count,
  .resource_stride=sizeof(r->base.encoded.resources[0]),.fence_seqno=0xfeed};
}
static unsigned pts_observations;
static struct pts_record *pts_mutate;
static void pts_observer(void *record,u32 handle,u64 offset,u32 bytes,const void *data)
{
 assert(record==&pts_observations && handle && bytes && data && !running->gpu.hw.submit_lock.held);(void)offset;
 ++pts_observations;
 if(pts_mutate){memset(pts_mutate,0xa7,sizeof(*pts_mutate));pts_mutate=NULL;}
}
static int pts_submit(struct native_kernel_session *s,struct pts_record *r)
{
 struct native_kernel_record *record=calloc(1,sizeof(*record));assert(record && !s->pending_record);
 s->pending_record=record;running=s;copy_gpu=&s->gpu;s->observer=pts_observer;s->record=&pts_observations;
 int ret=protected_tessellation_submit_verified(&s->drm,&r->request,&s->file,&pts_profile);
 if(ret){assert(!record->job);free(record);}else assert(record->job && record->job->type==OPENGPU_SCHED_PROTECTED_TESSELLATION);
 s->pending_record=NULL;s->observer=NULL;s->record=NULL;return ret;
}
static u32 pts_root(struct native_kernel_session *s,unsigned index)
{struct opengpu_sched_job *j=native_record(s,index);u32 address;
 assert(j && j->type==OPENGPU_SCHED_PROTECTED_TESSELLATION && !j->ordinary_pending);
 assert(!opengpu_private_snapshot_protected_tessellation_job_address(j->native_owner,j->native_job_segment,&address));
 assert(opengpu_private_snapshot_native_job_address(j->native_owner,j->native_job_segment,&address)==-EINVAL);return address;}
static u32 pts_default(struct native_kernel_session *s,unsigned index)
{u32 root=pts_root(s,index),sequence=test_read_va(s,root+16*4),uniform=test_read_va(s,sequence+500*4);
 return test_read_va(s,test_read_va(s,test_read_va(s,uniform+13*4)+8*4));}
static void pts_retire_ordinary(struct native_kernel_session *s)
{assert(s->gpu.compute.admission_lock.held && !s->gpu.hw.submit_lock.held && s->gpu.compute.ordinary_pending==1);
 s->progress=NULL;assert(!native_kernel_retire(s,0,0));}
static void pts_check_sources(struct native_kernel_session *s,unsigned refs)
{for(struct native_original *o=s->originals;o;o=o->next){assert(!o->resv.locked);if(o->visible)assert(o->refs>=refs);}}
int main(void)
{
 assert(!opengpu_protected_tessellation_profile_valid(NULL));
 assert(sizeof(struct opengpu_protected_tessellation_request)==144 &&
  sizeof(struct opengpu_protected_tessellation_plan)==512 && sizeof(struct opengpu_protected_tessellation_state_plan)==248);
 for(u32 domain=0;domain<3;++domain){
  struct native_kernel_session *s=native_kernel_session_create(7);assert(s);test_next_handle=0;
  u32 code=test_gem(s,4096).handle,defaults=test_gem(s,4096).handle;
  word(original_handle(s,code)->gem.vaddr,0,0xffffffff);word(original_handle(s,code)->gem.vaddr,4,0x30500073);
  word(original_handle(s,defaults)->gem.vaddr,4,0x11223344);
  struct pts_record first,second;pts_init(s,&first,domain,code,defaults);pts_init(s,&second,domain,code,defaults);
  /* Public unregistered entry must not dereference even a poison request,
   * look up handles, copy userspace, allocate, append or consume output. */
  struct opengpu_protected_tessellation_profile untouched=pts_profile;
  assert(opengpu_hw_protected_tessellation_profile(&s->gpu,&untouched)==-EOPNOTSUPP && !memcmp(&untouched,&pts_profile,sizeof(untouched)));
  private_alloc_fail_after=0;s->copy_error=1;
  assert(opengpu_protected_tessellation_ioctl(&s->drm,(void *)(uintptr_t)1,&s->file)==-EOPNOTSUPP);
  assert(opengpu_protected_tessellation_ioctl(&s->drm,&first.request,&s->file)==-EOPNOTSUPP && first.request.fence_seqno==0xfeed);
  assert(private_alloc_fail_after==0 && !s->head && s->context.references==1);private_alloc_fail_after=-1;s->copy_error=0;
  s->copy_error=1;assert(pts_submit(s,&first)==-EFAULT);s->copy_error=0;
  struct pts_record saved=first;
  for(u32 bad=0;bad<24;++bad){
   first=saved;int expected=-EINVAL;
   switch(bad){
   case 0:first.request.version=2;break;case 1:first.request.flags=1;break;case 2:first.request.pad=1;break;
   case 3:first.request.reserved[0]=1;break;case 4:first.request.reserved[1]=1;break;
   case 5:first.request.plan_stride--;break;case 6:first.request.state_plan_stride--;break;case 7:first.request.resource_stride--;break;
   case 8:first.request.job=0;break;case 9:first.request.frame=0;break;case 10:first.request.states=0;break;
   case 11:first.request.state_plans=0;break;case 12:first.request.resources=0;break;case 13:first.request.plan_count=0;break;
   case 14:first.request.state_plan_count=0;break;case 15:first.request.resource_count=28;break;
   case 16:first.request.states_bytes--;break;case 17:first.request.sequences_bytes=1ULL<<33;break;
   case 18:first.request.context_id=8;break;
   case 19:first.plan.control_program.handle=0xffff;expected=-ENOENT;break;
   case 20:first.plan.control_uniforms.defaults.offset=4092;expected=-ERANGE;break;
   case 21:first.plan.input.bytes=1ULL<<32;expected=-ERANGE;break;
   case 22:first.plan.input.offset=~(u64)0;break;
   case 23:first.request.plan_count=0xffffffffu;expected=-ERANGE;break;
   }
   assert(pts_submit(s,&first)==expected && first.request.fence_seqno==0xfeed && !s->head && s->context.references==1);
   pts_check_sources(s,1);
  }first=saved;
  /* A real original reservation producer is waited outside submit_lock. */
  struct native_original *original=original_handle(s,defaults);original->resv.write=calloc(1,sizeof(struct dma_fence));assert(original->resv.write);
  *original->resv.write=(struct dma_fence){.refs=1};unsigned observations=pts_observations;
  assert(pts_submit(s,&first)==-ETIMEDOUT && pts_observations==observations && original->refs==1);
  original->resv.write->signaled=true;original->resv.write->error=-EIO;assert(pts_submit(s,&first)==-EIO && pts_observations==observations);
  original->resv.write->error=0;
  struct native_original *frame_writer=original_handle(s,first.base.encoded.resources[0].gem_handle);
  frame_writer->resv.read=calloc(1,sizeof(struct dma_fence));assert(frame_writer->resv.read);
  *frame_writer->resv.read=(struct dma_fence){.refs=1};
  assert(pts_submit(s,&first)==-ETIMEDOUT && pts_observations==observations && frame_writer->refs==1);
  frame_writer->resv.read->signaled=true;
  /* An earlier reader of immutable code is not an original-source producer. */
  struct native_original *code_original=original_handle(s,code);
  code_original->resv.read=calloc(1,sizeof(struct dma_fence));assert(code_original->resv.read);
  *code_original->resv.read=(struct dma_fence){.refs=1};
  /* Malformed build, private-map/publish and scheduler enqueue failures release
   * complete owners and all retained originals, leaving output untouched. */
  first.base.draw[18]=64;assert(pts_submit(s,&first)==-EINVAL);first.base.draw[18]=0;
  mmu_fail_private_maps=1;int map_result=pts_submit(s,&first);if(map_result!=-ENOMEM)fprintf(stderr,"map fail expected ENOMEM got %d\n",map_result);assert(map_result==-ENOMEM);
  s->submit_error=-ENOMEM;assert(pts_submit(s,&first)==-ENOMEM);s->submit_error=0;
  assert(!s->head && !s->gpu.mmu.snapshot_leases && s->context.references==1 && first.request.fence_seqno==0xfeed);pts_check_sources(s,1);
  struct opengpu_render_context other={.gpu=&s->gpu,.references=1,.id=8};
  test_ordinary_queue(s,&other);assert(pts_submit(s,&first)==-ETIMEDOUT && !s->gpu.mmu.snapshot_leases);
  s->progress=pts_retire_ordinary;assert(!pts_submit(s,&first));assert(s->context.references==3 && !s->gpu.compute.ordinary_pending);
  assert(test_read_va(s,pts_root(s,0))==0x474e5101u+domain && pts_default(s,0)==0x11223344);
  for(u32 i=0;i<first.base.encoded.resource_count;++i){
   const struct opengpu_native_frame_resource *resource=&first.base.encoded.resources[i];
   struct native_original *o=original_handle(s,resource->gem_handle);
   struct dma_fence *published=resource->access&OPENGPU_NATIVE_FRAME_RESOURCE_WRITE?o->resv.write:o->resv.read;
   assert(published==&native_record(s,0)->base.s_fence->finished && o->refs==2);
  }
  assert(opengpu_protected_tessellation_job_run(native_record(s,0))==ERR_PTR(-EOPNOTSUPP));
  word(original->gem.vaddr,4,0x55667788);assert(!pts_submit(s,&second));
  assert(pts_default(s,0)==0x11223344 && pts_default(s,1)==0x55667788 && original->refs==3);
  struct pts_record third;pts_init(s,&third,domain,code,defaults);assert(!pts_submit(s,&third));
  struct opengpu_sched_job *cancelled_private=native_record(s,2);
  struct dma_fence *private_chain=dma_fence_get(cancelled_private->admission_chain);
  assert(!cancelled_private->ordinary_pending && !s->gpu.compute.ordinary_pending);
  assert(!native_kernel_retire(s,2,-ECANCELED) && !dma_fence_is_signaled(private_chain));
  struct opengpu_sched_job *cancelled=test_ordinary_queue(s,&other);struct dma_fence *chain=dma_fence_get(cancelled->admission_chain);
  assert(!native_kernel_retire(s,2,-ECANCELED) && !dma_fence_is_signaled(chain));
  struct opengpu_sched_job *later=test_ordinary_queue(s,&other);
  assert(later->base.dependencies->fence==chain && !dma_fence_is_signaled(chain));
  /* Native successor depends on execution, ordinary writer on retirement. */
  struct opengpu_sched_job *a=native_record(s,0),*b=native_record(s,1);
  assert(b->base.dependencies && !dma_fence_is_signaled(b->base.dependencies->fence));
  native_cpu_complete(s,&a->base,0);assert(dma_fence_is_signaled(b->base.dependencies->fence));
  assert(!dma_fence_is_signaled(chain) && native_kernel_run(s,2)==-EBUSY && original->refs==3);
  native_cpu_complete(s,&b->base,-ECANCELED);
  assert(!native_kernel_original_close(s,defaults) && original->refs==2);
  assert(!native_kernel_retire(s,0,0) && original->refs==1 && !dma_fence_is_signaled(chain));
  assert(!native_kernel_retire(s,0,-ECANCELED) && !original->refs && dma_fence_is_signaled(chain) && dma_fence_is_signaled(private_chain));dma_fence_put(chain);dma_fence_put(private_chain);
  assert(!native_kernel_run(s,0) && !native_kernel_retire(s,0,0) && other.references==1);dma_fence_put(other.last_fence);
  /* Healthy cancelled-owner reuse, same context/ASID, new retained original. */
  defaults=test_gem(s,4096).handle;word(original_handle(s,defaults)->gem.vaddr,4,0x10203040);
  struct pts_record healthy;pts_init(s,&healthy,domain,code,defaults);
  if(domain==2)pts_mutate=&healthy;
  assert(!pts_submit(s,&healthy) && pts_default(s,0)==0x10203040);
  assert(!native_kernel_retire(s,0,-ECANCELED));
  /* The actual scheduler free path must quarantine every original reference
   * and context on idle or translation-revocation failure. Explicit CPU fixture
   * recovery below releases this retained container only after real MMU revoke;
   * this does not add a production recovery ioctl or fabricate hardware drain. */
  struct pts_record quarantine;pts_init(s,&quarantine,domain,code,defaults);assert(!pts_submit(s,&quarantine));
  struct opengpu_sched_job *q=native_record(s,0);struct dma_fence *retirement=dma_fence_get(q->retirement_fence);
  for(u32 i=0;i<q->object_count;++i){
   struct native_original *o=original_object(q->objects[i]);assert(!native_kernel_original_close(s,o->handle) && o->refs==1);
  }
  unsigned refs=original_handle(s,defaults)->refs;assert(refs==1);int allocations=live_allocations;
  if(domain==1)asid_flush_failures=1;else idle_error=-ETIMEDOUT;
  assert(!native_kernel_retire(s,0,-ECANCELED));
  assert(s->gpu.hw.wedged && q->native_owner && original_handle(s,defaults)->refs==refs && live_allocations==allocations &&
   s->context.references==3 && !dma_fence_is_signaled(retirement));
  idle_error=0;s->gpu.hw.wedged=false;asid_flush_failures=1;
  assert(opengpu_private_snapshot_retire(q->native_owner)==-EIO && original_handle(s,defaults)->refs==refs &&
   s->context.references==3 && live_allocations==allocations && !dma_fence_is_signaled(retirement));
  s->gpu.hw.wedged=false;assert(!opengpu_private_snapshot_retire(q->native_owner));q->native_owner=NULL;
  for(u32 i=0;i<q->object_count;++i)drm_gem_object_put(q->objects[i]);
  kfree(q->objects);
  opengpu_context_put(q->context);opengpu_admission_job_retired(q);kfree(q);
  assert(dma_fence_is_signaled(retirement) && original_handle(s,defaults)->refs==0 && s->context.references==1);dma_fence_put(retirement);
  /* Enqueue itself may fail after publication. A failed cleanup must retain
   * the ORIGINAL lookup references, not only the scheduler's armed refs. */
  code=test_gem(s,4096).handle;defaults=test_gem(s,4096).handle;
  word(original_handle(s,code)->gem.vaddr,0,0xffffffff);word(original_handle(s,code)->gem.vaddr,4,0x30500073);
  struct pts_record rejected;pts_init(s,&rejected,domain,code,defaults);
  s->submit_error=-ENOMEM;s->submit_revoke_error=-ETIMEDOUT;
  assert(pts_submit(s,&rejected)==-ETIMEDOUT && rejected.request.fence_seqno==0xfeed && !s->head);
  q=s->submission_allocation;assert(q && q->native_owner && q->native_quarantine_refs && q->native_quarantine_refcount &&
   !q->objects && !q->retirement_fence && s->context.references==2 && s->gpu.hw.wedged);
  struct native_reference *retained=q->native_quarantine_refs;
  for(u32 i=0;i<q->native_quarantine_refcount;++i){
   struct native_original *o=original_object(retained[i].object);assert(o->refs==2 && !o->resv.locked);
   assert(!native_kernel_original_close(s,o->handle) && o->refs==1);
  }
  s->submit_error=s->submit_revoke_error=idle_error=0;s->gpu.hw.wedged=false;
  assert(!opengpu_private_snapshot_retire(q->native_owner));
  for(u32 i=0;i<q->native_quarantine_refcount;++i)drm_gem_object_put(retained[i].object);
  kvfree(retained);kfree(q);assert(s->context.references==1);
  assert(!native_kernel_session_destroy(s) && !live_allocations && !fixture_buffers);
 }
 puts("protected tessellation Linux submit: typed capture/original GEM reservations/RX+GUR3+GUR1/MMU/new-kind execution-vs-retirement/cancel+reuse PASS (CPU only; provider and kick unavailable)");return 0;
}
