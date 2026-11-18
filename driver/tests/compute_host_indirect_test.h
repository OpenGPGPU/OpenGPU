/* CPU control peer only: actual production C bodies, no shader execution. */
static void test_compute_host_indirect(const char *parent)
{
 assert(ARRAY_SIZE(test_profile)==122);
 struct compute_host_profile_word bad_profile[ARRAY_SIZE(test_profile)];
 for(unsigned n=0;n<8;n++){
  memcpy(bad_profile,test_profile,sizeof(bad_profile));bool changed=false;
  for(unsigned i=0;i<ARRAY_SIZE(bad_profile);i++)if(bad_profile[i].address==0x340+4*n){bad_profile[i].value^=1;changed=true;}
  assert(changed && !compute_host_open_indirect(bad_profile,ARRAY_SIZE(bad_profile)));
  struct compute_host_session *legacy=compute_host_open(bad_profile,ARRAY_SIZE(bad_profile));assert(legacy);
  const struct drm_opengpu_compute_indirect_resources unavailable={.version=2,.v2.flags=1};
  assert(compute_host_submit_indirect(legacy,&unavailable)==-EOPNOTSUPP && !legacy->head);
  assert(!legacy->gpu.hw.compute_indirect_transport && !compute_host_destroy(legacy));
  assert(!live_allocations && !fixture_buffers);
 }
 assert(!compute_host_open_indirect(test_profile,ARRAY_SIZE(test_profile)-1));
 for(unsigned test=0;test<15;test++){
  char directory[4096],path[4096];assert(snprintf(directory,sizeof(directory),"%s/indirect-%u",parent,test)>0);
  assert(!mkdir(directory,0700));
  struct compute_host_session *s=compute_host_open_indirect(test_profile,ARRAY_SIZE(test_profile));assert(s);
  u64 features=0;assert(!compute_host_query(s,OPENGPU_PARAM_DRIVER_FEATURES,&features) &&
   (features&OPENGPU_DRIVER_FEATURE_COMPUTE_INDIRECT_RESOURCES_V2));
  u32 context=0;assert(!compute_host_context_create(s,&context) && context==1);
  if(test==10){
   u32 second=0,third=0;
   assert(!compute_host_context_create(s,&second) && second==2);
   assert(!compute_host_context_create(s,&third) && third==3);
  }
  u32 *code=compute_host_original_allocate(s,1,4096),*original=compute_host_original_allocate(s,2,4096);
  assert(code && original);memset(original,0x73,4096);
  const u32 program[]={0x0000a283u,0x0040a303u,0x0080a383u,OPENGPU_SHADER_CEASE};
  memset(code,0,128);memcpy(code,program,sizeof(program));
  struct drm_opengpu_resource bind={.context_id=context,.slot=1,.handle=1,.type=OPENGPU_RESOURCE_COMPUTE_SHADER,.size=128};
  assert(!compute_host_bind(s,&bind));bind.slot=2;bind.handle=2;bind.type=OPENGPU_RESOURCE_COMPUTE_KERNARG;bind.size=4096;
  assert(!compute_host_bind(s,&bind));
  struct drm_opengpu_compute_indirect_resources q={.version=OPENGPU_COMPUTE_INDIRECT_VERSION2,
   .v2.flags=OPENGPU_COMPUTE_INDIRECT_KERNARG_COUNTS,.source={.handle=7,.offset=4092},
   .max_groups={UINT32_MAX,UINT32_MAX,UINT32_MAX},
   .resources.version=OPENGPU_COMPUTE_COUNTER_SOURCE_VERSION,
   .resources.resources.version=OPENGPU_COMPUTE_UNIFORM_VERSION,
   .resources.resources.resources.resource_flags=OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE,
   .resources.resources.resources.launch={.context_id=context,.shader_slot=1,.kernarg_slot=2,
    .local={8,4,4},.out_syncobj=2}};
  if(!test)q.resources.resources.resources.resource_flags=0;
  for(unsigned i=0;test && i<4;i++){
   assert(compute_host_original_allocate(s,3+i,4096));
   bind=(struct drm_opengpu_resource){.context_id=context,.slot=13+i,.handle=3+i,
    .type=OPENGPU_RESOURCE_COMPUTE_STORAGE,.size=4096};assert(!compute_host_bind(s,&bind));
   q.resources.resources.resources.bindings[i]=(struct drm_opengpu_compute_storage_binding){13+i,3};
  }
  const unsigned offsets[]={0,52,4084,4092,8180};if(test<5)q.source.offset=offsets[test];
  u32 grid[3]={2,2,2};
  if(test>=5 && test<=7){grid[0]=grid[1]=grid[2]=UINT32_MAX;grid[test-5]=0;}
  if(test==8){grid[0]=UINT32_MAX;grid[1]=grid[2]=1;}
  if(test==9)grid[0]=grid[1]=grid[2]=65535;
  u8 *data=compute_host_original_allocate(s,7,8192);assert(data);memset(data,0x6b,8192);
  memcpy(data+q.source.offset,grid,12);
  if(test==10){
   assert(compute_host_original_allocate(s,8,8192));
   q.resources.source=(struct drm_opengpu_compute_counter_source){.handle=8,.offset=4080,.count=8};
   q.resources.resources.active_mask=0xfff;
   for(unsigned i=0;i<13;i++){
    unsigned bytes=i<12?16384:4096;u32 *values=compute_host_original_allocate(s,9+i,bytes);assert(values);
    for(unsigned k=0;k<bytes/4;k++)values[k]=0x41000000u+i*0x01000000u+k;
    struct drm_opengpu_compute_uniform_view *v=i<12?&q.resources.resources.blocks[i]:&q.resources.resources.defaults;
    *v=(struct drm_opengpu_compute_uniform_view){.handle=9+i,.bytes=bytes};
   }
  }
  struct dma_fence writer={.refs=1};
  if(!test){
   s->render.originals[7]->resv.write=dma_fence_get(&writer);
   assert(compute_host_submit_indirect(s,&q)==-ETIMEDOUT && !s->head);
   writer.signaled=true;writer.error=-EIO;
   assert(compute_host_submit_indirect(s,&q)==-EIO && !s->head);writer.error=0;
   struct drm_opengpu_compute_indirect_resources bad=q;bad.v2.flags=0;
   assert(compute_host_submit_indirect(s,&bad)==-EINVAL && !s->head);
   bad=q;bad.version=1;assert(compute_host_submit_indirect(s,&bad)==-EINVAL && !s->head);
   bad=q;bad.source.offset=8184;assert(compute_host_submit_indirect(s,&bad)==-EINVAL && !s->head);
   bad=q;bad.max_groups[1]=0;assert(compute_host_submit_indirect(s,&bad)==-EINVAL && !s->head);
   bad=q;bad.source.handle=2;assert(compute_host_submit_indirect(s,&bad)==-EINVAL && !s->head);
  }
  assert(!compute_host_submit_indirect(s,&q));
  if(test==14){
   assert(!compute_host_cancel_unexecuted(s));assert(compute_host_destroy(s)==-ECANCELED);
   assert(!compute_host_destroy(s) && !fixture_buffers && !live_allocations);continue;
  }
  struct compute_host_indirect_snapshot snap;struct compute_host_snapshot old;
  assert(compute_host_snapshot_get(s,&old)==-EOPNOTSUPP && !compute_host_indirect_snapshot_get(s,&snap));
  assert(!memcmp(snap.grid,grid,12) && snap.source_offset==q.source.offset && snap.snapshot_offset==(q.source.offset&4095));
  assert(snap.resources.kernarg_physical==snap.snapshot_physical+snap.snapshot_offset && snap.resources.kernarg_bytes==12);
  assert(!memcmp(snap.resources.code,program,sizeof(program)) && snap.original_kernarg_bytes==4096);
  for(unsigned i=0;i<4096/4;i++)assert(original[i]==0x73737373u);
  for(unsigned i=0;i<snap.snapshot_bytes;i++)if(i<snap.snapshot_offset || i>=snap.snapshot_offset+12)
   assert(!((const u8 *)snap.arguments)[i]);
  const u32 later[3]={0x11111111u,0x22222222u,0x33333333u};memcpy(data+q.source.offset,later,12);
  if(test==3)assert(!compute_host_original_close(s,7) && s->render.originals[7]->backing.cpu);
  assert(!compute_host_begin_execution(s));
  struct opengpu_sched_job *job=opengpu_sched_job_from_base(s->head);u32 flags=0;
  for(unsigned off=0;off<snap.snapshot_bytes;off+=4096){
   assert(translate(&s->context->vm,snap.snapshot_va+off,&flags)==snap.snapshot_physical+off &&
    (flags&15)==3 && ((flags>>8)&3)==OPENGPU_MMU_POLICY_UNCACHED);
  }
  assert(job->kernel.kernarg==(u64)snap.snapshot_va+snap.snapshot_offset);
  assert(snprintf(path,sizeof(path),"%s/union.memory",directory)>0 && !compute_host_export_memory(s,path));
  assert(snprintf(path,sizeof(path),"%s/record-0.capture",directory)>0);
  assert(compute_host_capture(s,path,1000000,37)==-EOPNOTSUPP);
  assert(!compute_host_capture_indirect(s,path,1000000,37));
  assert(compute_host_capture_indirect(s,path,1000000,37)==-EINVAL);
  struct compute_host_reset_receipt reset={0};
  assert(compute_host_abort_captured(s,&reset,directory)==-EOPNOTSUPP &&
   compute_host_drain_reset(s,&reset,path)==-EOPNOTSUPP &&
   compute_host_observe_reset_retired(s,&reset)==-EOPNOTSUPP && !s->cancelled);
  unsigned status=test==11?GPU_UCMD_KERNEL_EXECUTION_FAILED:test==12?GPU_INDIRECT_RESULT_BOUNDS:
   test==13?GPU_INDIRECT_RESULT_READ_FAILED:0;
  struct compute_host_held_receipt held={.command_id=s->gpu.hw.unified_command_id,
   .completion=0x10600u|(status?status<<11:0x8000u)|s->gpu.hw.unified_command_id,
   .status=GPU_UCMD_STATUS_COMPLETION|GPU_UCMD_STATUS_VM_BUSY,
   .irq=GPU_IRQ_ENABLE|GPU_IRQ_PENDING,.irq_asserted=1};
  assert(GPU_UCMD_COMPLETION_OPCODE(held.completion)==14 && GPU_UCMD_COMPLETION_STATUS(held.completion)==status);
  assert(snprintf(path,sizeof(path),"%s/record-0.retire",directory)>0);
  for(unsigned bad=0;bad<7;bad++){
   struct compute_host_held_receipt wrong=held;
   if(bad==0)wrong.completion&=~(1u<<16);
   if(bad==1)wrong.completion=(status?status<<11:0x8000u)|(14u<<8)|held.command_id;
   if(bad==2)wrong.command_id^=1;
   if(bad==3)wrong.processed_bytes=4;
   if(bad==4)wrong.status|=GPU_UCMD_STATUS_RESET_BUSY;
   if(bad==5)wrong.irq_asserted=0;
   if(bad==6)wrong.status|=1u<<6;
   assert(compute_host_drain_indirect(s,&wrong,path,status)==-EINVAL && !dma_fence_is_signaled(s->hardware));
  }
  assert(compute_host_drain_held(s,&held,path)==-EINVAL && compute_host_drain_failed(s,&held,path)==-EINVAL);
  assert(!compute_host_drain_indirect(s,&held,path,status));
  struct compute_host_retired_receipt retired={.held=held,.status=GPU_UCMD_STATUS_READY,.irq=GPU_IRQ_ENABLE,.external_drained=1};
  assert(compute_host_observe_retired(s,&retired)==-EOPNOTSUPP &&
   compute_host_observe_failed_retired(s,&retired)==-EOPNOTSUPP);
  assert(compute_host_observe_indirect_retired(s,&retired,status==1?13:1)==-EINVAL);
  assert(!compute_host_observe_indirect_retired(s,&retired,status));
  int32_t fence_status=0;assert(!compute_host_completion_status(s,&fence_status));
  assert(fence_status==(status==12?-ERANGE:status?-EIO:1));
  assert(compute_host_finish_observed(s)==-EOPNOTSUPP && compute_host_finish_failed_observed(s)==-EOPNOTSUPP);
  assert(!compute_host_begin_cleanup(s,directory));
  assert(!compute_host_finish_indirect_observed(s,status) && !compute_host_end_cleanup(s));
  assert(!compute_host_destroy(s) && writer.refs==1 && !live_allocations && !fixture_buffers);
 }
 puts("indirect actual Linux v2/GCI1/private counts/MMU/opcode14/IRQ/cleanup: PASS (14 CPU captures, no GPU execution)");
}
