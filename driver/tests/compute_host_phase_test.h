/* Explicit CPU transport peer. Modified bytes below model a DUT response only
 * to test admission/ownership; this is not GPU producer or shader evidence. */
static void test_phase_put(u8 *bytes,u64 value,unsigned count)
{for(unsigned n=0;n<count;n++)bytes[n]=(u8)(value>>(8*n));}
static u64 test_phase_get(const u8 *bytes,unsigned count)
{u64 value=0;for(unsigned n=0;n<count;n++)value|=(u64)bytes[n]<<(8*n);return value;}
static void test_phase_readback(struct compute_host_session *s,const char *path,unsigned changed,
 const u32 values[3],unsigned bad)
{
 assert(!compute_host_export_originals(s,path));FILE *f=fopen(path,"rb");assert(f);
 assert(!fseek(f,0,SEEK_END));long length=ftell(f);assert(length>24 && !fseek(f,0,SEEK_SET));
 u8 *data=malloc((size_t)length+1);assert(data && fread(data,1,(size_t)length,f)==(size_t)length && !fclose(f));
 size_t cursor=24,change=0,readonly=0;
 while(cursor<(size_t)length){
  u32 handle=(u32)test_phase_get(data+cursor,4);u64 size=test_phase_get(data+cursor+16,8);
  assert(size && size<=(size_t)length-cursor-24);
  if(handle==changed)change=cursor+24;
  if(handle==1)readonly=cursor+24;
  cursor+=24+(size_t)size;
 }
 assert(cursor==(size_t)length && readonly && (!changed || change));
 if(changed)for(unsigned n=0;n<3;n++)test_phase_put(data+change+(changed==3?4092:0)+4*n,values[n],4);
 /* Wire/identity, read-only guards, cache-line padding and atomic rejection. */
 if(bad>=1 && bad<=9){const unsigned offset[]={0,0,8,12,16,20,24,28,32,40};data[offset[bad]]^=1;}
 if(bad==10)data[readonly]^=1;
 if(bad==11)data[change+(changed==3?4108:256)]^=1;
 if(bad==12)data[length++]=0;
 if(bad==13)--length;
 f=fopen(path,"wb");assert(f && fwrite(data,1,(size_t)length,f)==(size_t)length && !fclose(f));free(data);
}
static void test_compute_host_phases(const char *parent)
{
 struct compute_host_session *s=compute_host_open_indirect(test_profile,ARRAY_SIZE(test_profile));assert(s);
 u32 graphics=0;assert(!compute_host_context_create(s,&graphics) && graphics==1);
 u32 *code=compute_host_original_allocate(s,1,4096),*arg=compute_host_original_allocate(s,2,4096);
 u8 *counts=compute_host_original_allocate(s,3,8192),*out=compute_host_original_allocate(s,4,4096);
 assert(code && arg && counts && out);memset(arg,0x73,4096);memset(counts,0x6b,8192);memset(out,0xa7,4096);
 assert(compute_host_original_allocate(s,6,4096));
 const u32 program[]={0x0000a283u,0x0040a303u,0x0080a383u,OPENGPU_SHADER_CEASE};
 memset(code,0,128);memcpy(code,program,sizeof(program));
 u64 original_pa=0,original_bytes=0;assert(!compute_host_original_physical(s,3,&original_pa,&original_bytes));
 const u64 timeline=s->gpu.hw.fence_context;struct dma_fence *producer=NULL;
 const u32 ordinary[3]={2,2,2},zero[3]={0,UINT32_MAX,UINT32_MAX};
 for(unsigned round=0;round<6;round++){
  char directory[4096],path[4096];assert(snprintf(directory,sizeof(directory),"%s/phase-%u",parent,round)>0 && !mkdir(directory,0700));
  assert(s->phase_epoch==round && !s->vm_destroyed && !s->gpu.hw.wedged &&
   (!round || s->gpu.hw.fence_context==timeline) && !compute_mmio_state(&s->gpu)->reset_issued);
  if(round>=3){int32_t failed=0;assert(!compute_host_sync_status(s,42,&failed) && failed==-EIO);}
  u32 context=0;assert(!compute_host_context_create(s,&context) && context==2);
  if(round==3){out=compute_host_original_allocate(s,5,4096);assert(out);memset(out,0xa7,4096);}
  struct drm_opengpu_resource bind={.context_id=context,.slot=1,.handle=1,.type=OPENGPU_RESOURCE_COMPUTE_SHADER,.size=128};
  assert(!compute_host_bind(s,&bind));bind.slot=2;bind.handle=2;bind.type=OPENGPU_RESOURCE_COMPUTE_KERNARG;bind.size=4096;
  assert(!compute_host_bind(s,&bind));
  bool direct=round==0 || round==4;unsigned output=direct?3:round>=3?5:4;
  bind=(struct drm_opengpu_resource){.context_id=context,.slot=13,.handle=output,.type=OPENGPU_RESOURCE_COMPUTE_STORAGE,
   .offset=direct?3840:0,.size=direct?268:256};assert(!compute_host_bind(s,&bind));
  struct drm_opengpu_compute_indirect_resources q={.version=2,.v2.flags=1,
   .source={.handle=3,.offset=4092},.max_groups={UINT32_MAX,UINT32_MAX,UINT32_MAX},
   .resources.version=OPENGPU_COMPUTE_COUNTER_SOURCE_VERSION,
   .resources.resources.version=OPENGPU_COMPUTE_UNIFORM_VERSION,
   .resources.resources.resources.resource_flags=OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE,
   .resources.resources.resources.bindings={{13,3}},
   .resources.resources.resources.launch={.context_id=context,.shader_slot=1,.kernarg_slot=2,
    .grid={1,1,1},.local={4,1,1},.out_syncobj=40+round}};
  if(!direct){
   memset(q.resources.resources.resources.launch.grid,0,sizeof(q.resources.resources.resources.launch.grid));
   assert(producer && s->render.originals[3]->resv.write==producer && dma_fence_get_status(producer)==1);
  }
  struct drm_opengpu_compute_storage storage={.launch=q.resources.resources.resources.launch,.bindings={{13,3}}};
  int submit=direct?compute_host_submit_storage(s,&storage):compute_host_submit_indirect(s,&q);
  if(submit)fprintf(stderr,"phase %u source admission: %d\n",round,submit);
  assert(!submit);
  if(!direct){struct compute_host_indirect_snapshot snapshot;assert(!compute_host_indirect_snapshot_get(s,&snapshot));
   assert(snapshot.source_physical==original_pa && snapshot.source_bytes==original_bytes &&
    !memcmp(snapshot.grid,round==5?zero:ordinary,12));}
  assert(!compute_host_begin_execution(s));
  assert(snprintf(path,sizeof(path),"%s/union.memory",directory)>0 && !compute_host_export_memory(s,path));
  assert(snprintf(path,sizeof(path),"%s/record-0.capture",directory)>0 &&
   !(direct?compute_host_capture(s,path,1000000,37):compute_host_capture_indirect(s,path,1000000,37)));
  assert(snprintf(path,sizeof(path),"%s/originals.bytes",directory)>0 && !compute_host_export_originals(s,path));
  const unsigned status=round==2?GPU_INDIRECT_RESULT_READ_FAILED:0;
  struct compute_host_held_receipt h={.command_id=s->gpu.hw.unified_command_id,
   .completion=compute_host_completion(direct?GPU_UCMD_OP_KERNEL:GPU_UCMD_OP_KERNEL_INDIRECT,status,s->gpu.hw.unified_command_id),
   .status=GPU_UCMD_STATUS_COMPLETION|GPU_UCMD_STATUS_VM_BUSY,.irq=GPU_IRQ_ENABLE|GPU_IRQ_PENDING,.irq_asserted=1};
  assert(snprintf(path,sizeof(path),"%s/record-0.retire",directory)>0 &&
   !(direct?compute_host_drain_held(s,&h,path):compute_host_drain_indirect(s,&h,path,status)));
  struct compute_host_retired_receipt r={.held=h,.status=GPU_UCMD_STATUS_READY,.irq=GPU_IRQ_ENABLE,.external_drained=1};
  assert(!(direct?compute_host_observe_retired(s,&r):compute_host_observe_indirect_retired(s,&r,status)));
  int32_t finished=0;assert(!compute_host_sync_status(s,40+round,&finished) && finished==(status?-EIO:1));
  if(!round || round==4){dma_fence_put(producer);producer=dma_fence_get(s->render.originals[3]->resv.write);}
  const u32 *values=round==4?zero:ordinary;
  unsigned changed=round==2 || round==5?0:output;
  assert(compute_host_begin_phase_cleanup(s,directory)==-EBUSY && compute_host_release_phase_job(s)==-EBUSY);
  if(!round)for(unsigned bad=1;bad<=13;bad++){
   assert(snprintf(path,sizeof(path),"%s/bad-%u.bytes",directory,bad)>0);
   test_phase_readback(s,path,changed,values,bad);
   assert(compute_host_import_originals(s,path)==-EINVAL && !s->phase_readback);
   for(unsigned n=0;n<8192;n++)assert(counts[n]==0x6b);
  }
  if(round==2){
   const u32 failed_values[3]={9,8,7};
   assert(snprintf(path,sizeof(path),"%s/failed-write.bytes",directory)>0);
   test_phase_readback(s,path,output,failed_values,0);
   assert(compute_host_import_originals(s,path)==-EINVAL && !s->phase_readback);
  }
  assert(snprintf(path,sizeof(path),"%s/returned.bytes",directory)>0);test_phase_readback(s,path,changed,values,0);
  assert(!compute_host_import_originals(s,path) && compute_host_import_originals(s,path)==-EBUSY);
  assert(!memcmp(counts+4092,round>=4?zero:ordinary,12));
  if(round==5){
   assert(!compute_host_begin_cleanup(s,directory) && !compute_host_finish_indirect_observed(s,0));
   assert(!compute_host_end_cleanup(s));break;
  }
  s->phase_epoch=UINT32_MAX;assert(compute_host_begin_phase_cleanup(s,directory)==-EBUSY);s->phase_epoch=round;
  assert(!compute_host_begin_phase_cleanup(s,directory));
  assert(compute_host_context_destroy(s,context)==-EBUSY && compute_host_end_phase_cleanup(s)==-EBUSY);
  int released=compute_host_release_phase_job(s);
  if(test_phase_ack_failure){
   assert(released<0 && s->gpu.hw.wedged && s->head && s->hardware && s->context &&
    !s->phase_job_freed && s->render.originals[3]->refs>1 && s->render.originals[3]->backing.cpu);
   assert(compute_host_resume_phase(s,&(struct compute_host_phase_receipt){0})==-EBUSY && compute_host_destroy(s)<0);
   printf("phase ACK rejection %u: retained original GEM/job/context/fences (CPU only)\n",test_phase_ack_failure);
   fflush(stdout);_Exit(0); /* Deliberately pinned after failed native ACK. */
  }
  assert(!released && !s->head && !s->hardware);
  if(!round)assert(!compute_host_original_close(s,6) && s->render.originals[6]->refs==1 &&
   s->render.originals[6]->backing.cpu && s->phase_original_pinned[6]);
  assert(compute_host_end_cleanup(s)==-EBUSY && compute_host_finish_observed(s)<0 && compute_host_destroy(s)==-EBUSY);
  struct drm_opengpu_invalidate inv={.context_id=context,.handle=output,
   .offset=direct?3840:0,.bytes=direct?320:256,.out_syncobj=100+round};
  struct drm_opengpu_invalidate wrong=inv;wrong.handle=1;
  assert(compute_host_phase_invalidate(s,&wrong)==-EINVAL);
  wrong=inv;wrong.bytes+=64;assert(compute_host_phase_invalidate(s,&wrong)==-EINVAL);
  wrong=inv;wrong.offset+=4;assert(compute_host_phase_invalidate(s,&wrong)==-EINVAL);
  assert(!compute_host_phase_invalidate(s,&inv) && !compute_host_sync_status(s,100+round,&finished) && finished==1);
  assert(!compute_host_sync_status(s,40+round,&finished) && finished==(status?-EIO:1));
  assert(!compute_host_context_destroy(s,context) && !s->context);
  assert(idr_find(&s->render.contexts,graphics) && !compute_host_empty_contexts(s,NULL));
  assert(!compute_host_end_phase_cleanup(s));
  assert(compute_host_context_destroy(s,graphics)==-EBUSY);
  struct compute_host_phase_receipt ack={.command_id=h.command_id,.context_id=context,.epoch=round,
   .batches=compute_mmio_state(&s->gpu)->cleanup_sequence,.status=GPU_UCMD_STATUS_READY,
   .irq=GPU_IRQ_ENABLE,.external_drained=1,.quiet_cycles=32,.read_only_guards=1};
  for(unsigned bad=0;bad<10;bad++){
   _Static_assert(sizeof(struct compute_host_phase_receipt)==10*sizeof(u32),"phase receipt field layout");
   struct compute_host_phase_receipt wrong_ack;u32 fields[10];memcpy(fields,&ack,sizeof(fields));fields[bad]^=1;
   memcpy(&wrong_ack,fields,sizeof(wrong_ack));
   assert(compute_host_resume_phase(s,&wrong_ack)==-EINVAL && s->exported);
   if(!round)assert(s->render.originals[6]->refs==1 && s->render.originals[6]->backing.cpu && s->phase_original_pinned[6]);
  }
  assert(compute_host_destroy(s)==-EBUSY && !compute_host_resume_phase(s,&ack));
  if(!round)assert(!s->render.originals[6]->refs && !s->render.originals[6]->backing.cpu && !s->phase_original_pinned[6]);
  assert(compute_host_resume_phase(s,&ack)==-EBUSY && compute_host_destroy(s)==-EBUSY);
  u64 physical=0,bytes=0;assert(!compute_host_original_physical(s,3,&physical,&bytes) && physical==original_pa && bytes==original_bytes);
 }
 dma_fence_put(producer);assert(!compute_host_destroy(s) && !live_allocations && !fixture_buffers);
 puts("continuous original GEM/readback guards/fences/publication/phase ACK: PASS (six CPU phases, no GPU execution)");
}
