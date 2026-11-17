/* CPU transport tests only: no shader executes and no DUT is qualified. */
#include <sys/stat.h>

static int test_image_submit(struct compute_host_session *s,
 const struct drm_opengpu_compute *launch,const struct drm_opengpu_compute_image_view views[4],bool resources)
{
 if(resources){
  struct drm_opengpu_compute_image_resources r={.launch=*launch};memcpy(r.images,views,sizeof(r.images));
  return compute_host_submit_image_resources(s,&r);
 }
 struct drm_opengpu_compute_images r={.launch=*launch};memcpy(r.views,views,sizeof(r.views));
 return compute_host_submit_images(s,&r);
}

static void test_image_retire(struct compute_host_session *s,const char *directory)
{
 char path[4096];assert(snprintf(path,sizeof(path),"%s/record-0.retire",directory)>0);
 struct compute_host_held_receipt held={.command_id=s->gpu.hw.unified_command_id,
  .completion=0x8000u|s->gpu.hw.unified_command_id,.legacy_status=0,
  .status=GPU_UCMD_STATUS_COMPLETION|GPU_UCMD_STATUS_VM_BUSY,
  .irq=GPU_IRQ_ENABLE|GPU_IRQ_PENDING,.irq_asserted=1};
 assert(!compute_host_drain_held(s,&held,path));
 struct compute_host_retired_receipt retired={.held=held,.status=GPU_UCMD_STATUS_READY,
  .irq=GPU_IRQ_ENABLE,.external_drained=1};
 assert(!compute_host_observe_retired(s,&retired));
 assert(!compute_host_begin_cleanup(s,directory));
 int cleanup=compute_host_finish_observed(s);
 if(test_fail_phase){
  assert(test_fail_phase==1 && test_failure_injected && cleanup<0 && s->gpu.hw.wedged);
  struct opengpu_sched_job *job=opengpu_sched_job_from_base(s->head);assert(job && job->image_records.cpu);
  assert(s->render.originals[3]->refs && s->render.originals[3]->backing.cpu && s->context);
  assert(compute_host_end_cleanup(s)==-EBUSY && compute_host_destroy(s)<0);
  puts("typed image bad cleanup ACK retained reachable source/descriptor: PASS (CPU transport only)");
  fflush(stdout);_Exit(0);
 }
 assert(!cleanup && !compute_host_end_cleanup(s));
}

static void test_image_capture_budget(void)
{
 for(unsigned packed=0;packed<2;packed++){
  struct compute_host_session *s=compute_host_create(7,test_profile,ARRAY_SIZE(test_profile));assert(s);
  u32 *code=compute_host_original_allocate(s,1,4096);assert(code);memset(code,0,128);code[0]=OPENGPU_SHADER_CEASE;
  assert(compute_host_original_allocate(s,2,4096) && compute_host_original_allocate(s,3,0x48000));
  struct drm_opengpu_resource b={.context_id=1,.slot=1,.handle=1,.type=OPENGPU_RESOURCE_COMPUTE_SHADER,.size=128};
  assert(!compute_host_bind(s,&b));
  b=(struct drm_opengpu_resource){.context_id=1,.slot=2,.handle=2,.type=OPENGPU_RESOURCE_COMPUTE_KERNARG,.size=4096};
  assert(!compute_host_bind(s,&b));
  b=(struct drm_opengpu_resource){.context_id=1,.slot=39,.handle=3,.type=OPENGPU_RESOURCE_COMPUTE_STORAGE,.size=0x48000};
  assert(!compute_host_bind(s,&b));
  struct drm_opengpu_compute_images r={.launch={.context_id=1,.shader_slot=1,.kernarg_slot=2,
   .grid={1,1,1},.local={1,1,1},.out_syncobj=2}};
  r.views[0]=(struct drm_opengpu_compute_image_view){.slot=39,.offset=64,
   .flags=OPENGPU_COMPUTE_IMAGE_WRITE|OPENGPU_COMPUTE_IMAGE_LAYERED,.target=1,
   .storage_format=OPENGPU_TEXTURE_FORMAT_R32UI,.image_format=OPENGPU_TEXTURE_FORMAT_R32UI,
   .width=1,.height=1500,.depth=1,.layers=3,.row_pitch=packed?4:64,.layer_pitch=packed?6000:96000};
  assert(!compute_host_submit_images(s,&r));
  struct compute_writable_range ranges[COMPUTE_HOST_MAX_WRITABLE];unsigned count=0;
  int planned=compute_host_image_ranges(s,ranges,&count);
  if(packed){
   u64 first=s->context->bindings[38]->dma+64;
   assert(!planned && count==1 && ranges[0].first==first && ranges[0].end==first+18000);
  }else{
   assert(planned==-E2BIG && !count && compute_host_begin_execution(s)==-E2BIG);
   assert(!s->prepared_execution && !compute_mmio_state(&s->gpu)->kernel_submissions);
  }
  assert(!compute_host_cancel_unexecuted(s));
  assert(compute_host_destroy(s)==-ECANCELED);
  assert(!compute_host_destroy(s) && !live_allocations && !fixture_buffers);
 }
}

static void test_compute_host_images(const char *parent)
{
 /* Expected texel sizes and row offsets are independent test data, not
  * values returned by the descriptor encoder or range planner under test. */
 static const struct {u32 format,bytes;} formats[]={
  {OPENGPU_TEXTURE_FORMAT_RGBA32F,16},{OPENGPU_TEXTURE_FORMAT_RGBA16F,8},
  {OPENGPU_TEXTURE_FORMAT_R32F,4},{OPENGPU_TEXTURE_FORMAT_RGBA32UI,16},
  {OPENGPU_TEXTURE_FORMAT_RGBA16UI,8},{OPENGPU_TEXTURE_FORMAT_RGBA8UI,4},
  {OPENGPU_TEXTURE_FORMAT_R32UI,4},{OPENGPU_TEXTURE_FORMAT_RGBA32I,16},
  {OPENGPU_TEXTURE_FORMAT_RGBA16I,8},{OPENGPU_TEXTURE_FORMAT_RGBA8I,4},
  {OPENGPU_TEXTURE_FORMAT_R32I,4},{OPENGPU_TEXTURE_FORMAT_RGBA8,4},
  {OPENGPU_TEXTURE_FORMAT_RGBA8_SNORM,4}};
 static const struct {unsigned target,layered,layer,columns,count;u32 rows[12];} layouts[]={
  {5,0,0,3,1,{65}}, {0,0,0,3,2,{64,128}},
  {1,0,2,3,2,{576,640}}, {1,1,0,3,6,{64,128,320,384,576,640}},
  {2,0,4,2,2,{1088,1152}}, {2,1,0,2,12,{64,128,320,384,576,640,832,896,1088,1152,1344,1408}},
  {3,0,2,3,2,{576,640}}, {3,1,0,3,6,{64,128,320,384,576,640}}};
 unsigned cases=0;
 for(unsigned f=0;f<ARRAY_SIZE(formats);f++)for(unsigned l=0;l<ARRAY_SIZE(layouts);l++)for(unsigned write=0;write<2;write++){
  if(test_fail_phase && (f || l || write!=(getenv("OPENGPU_COMPUTE_HOST_TEST_IMAGE_WRITE")!=NULL)))continue;
  struct compute_host_session *s=compute_host_create(7,test_profile,ARRAY_SIZE(test_profile));assert(s);
  u32 *code=compute_host_original_allocate(s,1,4096);assert(code);memset(code,0,128);code[0]=OPENGPU_SHADER_CEASE;
  assert(compute_host_original_allocate(s,2,4096));
  u8 *image=compute_host_original_allocate(s,3,8192);assert(image);memset(image,0x69,8192);
  struct drm_opengpu_resource bind={.context_id=1,.slot=1,.handle=1,.type=OPENGPU_RESOURCE_COMPUTE_SHADER,.size=128};
  assert(!compute_host_bind(s,&bind));
  bind=(struct drm_opengpu_resource){.context_id=1,.slot=2,.handle=2,.type=OPENGPU_RESOURCE_COMPUTE_KERNARG,.size=4096};
  assert(!compute_host_bind(s,&bind));
  unsigned unit=(f+l)%4,slot=39+unit;
  bind=(struct drm_opengpu_resource){.context_id=1,.slot=slot,.handle=3,.type=OPENGPU_RESOURCE_COMPUTE_STORAGE,.size=8192};
  assert(!compute_host_bind(s,&bind));
  struct drm_opengpu_compute launch={.context_id=1,.shader_slot=1,.kernarg_slot=2,
   .grid={1,1,1},.local={1,1,1},.out_syncobj=2};
  struct drm_opengpu_compute_image_view views[4]={0};
  struct drm_opengpu_compute_image_view *v=&views[unit];
  *v=(struct drm_opengpu_compute_image_view){.slot=slot,
   .flags=OPENGPU_COMPUTE_IMAGE_READ|(write?OPENGPU_COMPUTE_IMAGE_WRITE:0)|
          (layouts[l].layered?OPENGPU_COMPUTE_IMAGE_LAYERED:0),
   .target=layouts[l].target,.storage_format=formats[f].format,.image_format=formats[f].format,
   .level=layouts[l].target==5?0:2,.offset=layouts[l].target==5?65:64,
   .width=layouts[l].columns,.height=2,.depth=layouts[l].target==3?3:1,
   .layers=layouts[l].target==1?3:1,.layer=layouts[l].layer};
  if(v->target==5){v->height=1;v->buffer_bytes=3*formats[f].bytes+1;}
  else {v->row_pitch=64;if(v->target==3)v->slice_pitch=256;
   if(v->target==1 || v->target==2)v->layer_pitch=256;}
  bool resources=!!((f+l+write)&1);
  /* SNORM is an image format, not a supported texture-buffer backing
   * format. Exercise its legal by-size view without broadening that API. */
  if(v->target==5 && f==12){
   assert(test_image_submit(s,&launch,views,resources)==-EINVAL && !s->head);
   v->storage_format=OPENGPU_TEXTURE_FORMAT_RGBA8;v->flags|=OPENGPU_COMPUTE_IMAGE_BY_SIZE;
  }
  if(!f && !write)for(unsigned bad=0;bad<5;bad++){
   struct drm_opengpu_compute_image_view wrong[4];memcpy(wrong,views,sizeof(wrong));
   if(bad==0)wrong[unit].flags=0;
   if(bad==1)wrong[unit].image_format=UINT32_MAX;
   if(bad==2)wrong[unit].offset=8191;
   if(bad==3)wrong[unit].pad=1;
   if(bad==4)wrong[unit].target=4;
   assert(test_image_submit(s,&launch,wrong,resources)<0 && !s->head);
  }
  struct dma_fence producer={.refs=1};s->render.originals[3]->resv.write=dma_fence_get(&producer);
  int admitted=test_image_submit(s,&launch,views,resources);
  if(admitted)fprintf(stderr,"typed image f=%u layout=%u write=%u resources=%u admission=%d\n",f,l,write,resources,admitted);
  assert(!admitted && s->head);
  assert(compute_host_begin_execution(s)==-EBUSY && !s->prepared_execution &&
   !compute_mmio_state(&s->gpu)->kernel_submissions);
  producer.signaled=true;
  struct compute_host_snapshot snap;assert(!compute_host_snapshot_get(s,&snap));
  assert(snap.images && snap.image_bytes==1024 && snap.image_root);
  const u32 *record=(const u32 *)snap.images+unit*64;
  assert(record[0]==0x47495501 && record[6]==unit && record[10]==1 && record[11]==write &&
   record[12]==v->storage_format && record[13]==formats[f].format && record[14]==!!(v->flags&OPENGPU_COMPUTE_IMAGE_BY_SIZE) && record[15]==v->level &&
   record[18]==layouts[l].layered && record[19]==v->layer && record[20]==v->target);
  struct opengpu_resource_binding *backing=s->context->bindings[slot-1];assert(backing);
  struct compute_writable_range ranges[COMPUTE_HOST_MAX_WRITABLE];unsigned count=0;
  assert(!compute_host_image_ranges(s,ranges,&count));
  assert(count==(write?layouts[l].count:0));
  for(unsigned byte=0;byte<8192;byte++){
   bool expected=false,actual=false;
   if(write)for(unsigned row=0;row<layouts[l].count;row++)
    expected|=byte>=layouts[l].rows[row] && byte<layouts[l].rows[row]+layouts[l].columns*formats[f].bytes;
   for(unsigned row=0;row<count;row++)actual|=backing->dma+byte>=ranges[row].first && backing->dma+byte<ranges[row].end;
   assert(actual==expected);
  }
  assert(!compute_host_original_close(s,3) && s->render.originals[3]->backing.cpu);
  char directory[4096],path[4096];assert(snprintf(directory,sizeof(directory),"%s/images/%03u",parent,cases)>0);
  assert(!mkdir(directory,0700));
  assert(!compute_host_begin_execution(s));
  u32 pte_flags=0;
  assert(translate(&s->context->vm,record[25],&pte_flags)==backing->dma+v->offset);
  assert(translate(&s->context->vm,snap.image_root+unit*256,&pte_flags)==snap.image_physical+unit*256);
  assert(snprintf(path,sizeof(path),"%s/union.memory",directory)>0);assert(!compute_host_export_memory(s,path));
  assert(snprintf(path,sizeof(path),"%s/record-0.capture",directory)>0);assert(!compute_host_capture(s,path,1000000,37));
  test_image_retire(s,directory);
  assert(!compute_host_destroy(s) && producer.refs==1 && !live_allocations && !fixture_buffers);
  ++cases;
 }
 assert(cases==208);
 test_image_capture_budget();
 puts("typed image CPU transport: 208 original-resource/permission/pitch/layer byte-guard cases PASS (no GPU execution)");
}
