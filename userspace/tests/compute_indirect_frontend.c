/* Continuous original Mesa/Gallium -> Linux opcode0/14 source producer.
 * Shared OS primitives retain their single-owner entry point unchanged. */
#define main compute_single_frontend_main
#define cs_fixture_ioctl compute_single_fixture_ioctl
#define wait_execution compute_single_wait_execution
#include "compute_host_frontend.c"
#undef wait_execution
#undef cs_fixture_ioctl
#undef main

static unsigned phase_index,phase_context,phase_opcode,phase_status,source_handle,phase_output;
static const unsigned phase_programs[10]={0,4,4,4,1,4,2,4,3,4};
static const unsigned phase_grids[10]={0,0,0,0,1,1,2,2,3,3};
static const uint32_t producer_grids[4][3]={{2,2,2},{0,UINT32_MAX,UINT32_MAX},
 {UINT32_MAX,0,UINT32_MAX},{UINT32_MAX,UINT32_MAX,0}};
static int phase_wait(void);
int cs_fixture_ioctl(int fd,unsigned long request,...)
{
 va_list ap;va_start(ap,request);void *arg=va_arg(ap,void *);va_end(ap);assert(fd==101 || fd>=20000);
 if(request==DRM_IOCTL_SYNCOBJ_CREATE){struct drm_syncobj_create *p=arg;
  assert(!p->flags && next_sync<512);p->handle=next_sync++;return 0;}
 if(request==DRM_IOCTL_SYNCOBJ_WAIT){struct drm_syncobj_wait *p=arg;assert(p->count_handles==1);
  if(submitted && !retired)assert(!phase_wait());
  uint32_t h=*(const uint32_t *)(uintptr_t)p->handles;int32_t status=0;
  int error=compute_host_sync_status(session,h,&status);return result(error?error:status?0:-ETIMEDOUT);}
 if(request==DRM_IOCTL_SYNCOBJ_HANDLE_TO_FD){struct drm_syncobj_handle *p=arg;
  int32_t status=0;assert(!compute_host_sync_status(session,p->handle,&status) && status);p->fd=20000+p->handle;return 0;}
 if(request==SYNC_IOC_FILE_INFO){struct sync_file_info *p=arg;assert(fd>=20000);
  int32_t status=0;assert(!compute_host_sync_status(session,(unsigned)fd-20000,&status) && status);p->status=status;return 0;}
 if(request==DRM_IOCTL_OPENGPU_COMPUTE_STORAGE){
  const struct drm_opengpu_compute_storage *q=arg;
  assert(phase_opcode==0 && !submitted && q->launch.local[0]==4 && q->launch.local[1]==1 &&
   q->launch.local[2]==1 && q->launch.grid[0]==1 && q->launch.grid[1]==1 && q->launch.grid[2]==1);
  int error=compute_host_submit_storage(session,q);
  if(error)fprintf(stderr,"phase %u actual storage admission failed: %d\n",phase_index,error);
  if(!error){phase_context=q->launch.context_id;++submitted;}return result(error);
 }
 if(request==DRM_IOCTL_OPENGPU_COMPUTE_INDIRECT_RESOURCES){
  const struct drm_opengpu_compute_indirect_resources *q=arg;
  assert(phase_opcode==14 && !submitted && q->version==2 && q->v2.flags==1 &&
   q->source.handle==source_handle && q->source.offset==4092 &&
   q->resources.resources.resources.launch.local[0]==4 && q->resources.resources.resources.launch.local[1]==2 &&
   q->resources.resources.resources.launch.local[2]==1);
  if(phase_index==1){
   struct drm_opengpu_compute_indirect_resources bad=*q;bad.source.offset++;
   assert(compute_host_submit_indirect(session,&bad)==-EINVAL);
   bad=*q;bad.source.offset=8184;assert(compute_host_submit_indirect(session,&bad)==-EINVAL);
   bad=*q;bad.max_groups[0]=1;assert(compute_host_submit_indirect(session,&bad)==-ERANGE);
  }
  int error=compute_host_submit_indirect(session,q);
  if(!error){phase_context=q->resources.resources.resources.launch.context_id;++submitted;}
  return result(error);
 }
 if(request==DRM_IOCTL_OPENGPU_INVALIDATE)return result(compute_host_phase_invalidate(session,arg));
 /* GEM storage, mappings, discovery, contexts and resource binds are the same
  * original OS transport; no shader or GPU result is implemented here. */
 int error=compute_single_fixture_ioctl(fd,request,arg);
 if(error && (request==DRM_IOCTL_OPENGPU_RESOURCE_BIND || request==DRM_IOCTL_OPENGPU_CONTEXT_CREATE ||
    request==DRM_IOCTL_OPENGPU_COMPUTE_UNIFORM_RESOURCES))
  fprintf(stderr,"phase %u original ioctl %lx failed: %d\n",phase_index,request,errno);
 return error;
}
static void phase_word(FILE *f,uint32_t value)
{for(unsigned n=0;n<4;n++)assert(fputc((value>>(8*n))&255,f)!=EOF);}
static void phase_recipe(void)
{
 struct compute_host_snapshot snapshot;
 if(phase_opcode==14){struct compute_host_indirect_snapshot indirect;
  assert(!compute_host_indirect_snapshot_get(session,&indirect));snapshot=indirect.resources;
  assert(indirect.source_handle==source_handle && indirect.source_offset==4092 &&
   !memcmp(indirect.grid,producer_grids[phase_grids[phase_index]],12));
 }else assert(!compute_host_snapshot_get(session,&snapshot));
 FILE *f=file("code.words","wx");assert(f && !(snapshot.code_bytes&3));
 for(size_t n=0;n<snapshot.code_bytes/4;n++)assert(fprintf(f,"%08x\n",((const uint32_t *)snapshot.code)[n])>0);
 assert(!fclose(f));
 f=file("oracle.json","wx");assert(f);
 const uint32_t *grid=producer_grids[phase_grids[phase_index]];
 fprintf(f,"{\"schema\":\"opengpu-compute-indirect-recipe-v1\",\"phase\":%u,\"program\":%u,\"opcode\":%u,\"resultStatus\":%u,\"context\":%u,\"grid\":[%u,%u,%u],\"sourceHandle\":%u,\"outputHandle\":%u,\"codePhysical\":%llu,\"codeBytes\":%llu,\"source\":",
  phase_index,phase_programs[phase_index],phase_opcode,phase_status,phase_context,grid[0],grid[1],grid[2],
  source_handle,phase_output,(unsigned long long)snapshot.code_physical,(unsigned long long)snapshot.code_bytes);
 source_window(f,source_handle,0,8192,2);fprintf(f,",\"output\":");source_window(f,phase_output,0,8192,2);
 fprintf(f,",\"driverBoundaryNegatives\":%s}\n",phase_index==1?"true":"false");assert(!fclose(f));
 f=file("mode","wx");assert(f && fwrite("OGCIM001",1,8,f)==8);
 phase_word(f,phase_index);phase_word(f,phase_opcode);phase_word(f,phase_status);phase_word(f,phase_context);
 phase_word(f,phase_index==9);assert(!fclose(f));
}
static int phase_wait(void)
{
 if(retired)return 0;
 assert(submitted==1 && !compute_host_begin_execution(session));char output[4096];
 path(output,"union.memory");assert(!compute_host_export_memory(session,output));
 path(output,"record-0.capture");assert(!(phase_opcode==14?
  compute_host_capture_indirect(session,output,1000000000,37):compute_host_capture(session,output,1000000000,37)));
 path(output,"originals.bytes");assert(!compute_host_export_originals(session,output));phase_recipe();
 marker("READY",capture_only?"continuous source CPU capture only; no GPU execution\n":"continuous original Mesa/Linux capture ready\n");
 if(capture_only){puts("actual Mesa continuous producer compilation/capture: PASS (CPU only, first direct phase)");fflush(stdout);_Exit(0);}
 FILE *f=wait_file("record-0.held.response");struct compute_host_held_receipt h;
 if(phase_opcode==14){magic(f,"OGCIH001");h.command_id=read32(f);assert(read32(f)==14 && read32(f)==phase_status);
  h.completion=read32(f);h.processed_bytes=read64(f);h.legacy_status=read32(f);h.status=read32(f);h.irq=read32(f);h.irq_asserted=read32(f);
 }else{magic(f,"OGCMH001");h=held(f);}end(f);
 path(output,"record-0.retire");assert(!(phase_opcode==14?
  compute_host_drain_indirect(session,&h,output,phase_status):compute_host_drain_held(session,&h,output)));
 f=wait_file("record-0.retired.response");struct compute_host_retired_receipt r;
 if(phase_opcode==14){magic(f,"OGCIR001");r.held.command_id=read32(f);assert(read32(f)==14 && read32(f)==phase_status);
  r.held.completion=read32(f);r.held.processed_bytes=read64(f);r.held.legacy_status=read32(f);
  r.held.status=read32(f);r.held.irq=read32(f);r.held.irq_asserted=read32(f);
 }else{magic(f,"OGCMR001");r.held=held(f);}
 r.status=read32(f);r.irq=read32(f);r.irq_asserted=read32(f);r.external_drained=read32(f);end(f);
 assert(!(phase_opcode==14?compute_host_observe_indirect_retired(session,&r,phase_status):compute_host_observe_retired(session,&r)));
 retired=1;return 0;
}
int main(int argc,char **argv)
{
 assert(argc==5 || (argc==6 && !strcmp(argv[5],"--capture-only")));capture_only=argc==6;
 assert(!strcmp(argv[4],"indirect-pipeline"));host_timeout_seconds=compute_host_timeout_seconds();assert(host_timeout_seconds);
 const char *root=argv[2];directory=root;FILE *f=fopen(argv[1],"rb");assert(f);magic(f,"OGCP0003");
 unsigned count=read32(f);assert(count==112);struct compute_host_profile_word words[122];
 for(unsigned n=0;n<112;n++){words[n].address=read32(f);words[n].value=read32(f);}
 assert(read32(f)==10);for(unsigned n=112;n<122;n++){words[n].address=read32(f);words[n].value=read32(f);}end(f);
 session=compute_host_open_indirect(words,122);assert(session);
 uint64_t features=0;assert(!compute_host_query(session,OPENGPU_PARAM_DRIVER_FEATURES,&features) &&
  (features&OPENGPU_DRIVER_FEATURE_COMPUTE_INDIRECT_RESOURCES_V2));
 struct pipe_screen *screen=opengpu_drm_screen_create(7,NULL);assert(screen);
 struct pipe_context *context=screen->context_create(screen,NULL,0);assert(context);
 const char *names[5]={"producer-xyz.comp","producer-zero-x.comp","producer-zero-y.comp","producer-zero-z.comp","consumer.comp"};
 void *frontends[5],*shaders[5];
 for(unsigned n=0;n<5;n++){
  char source[4096],dump[4096];assert(snprintf(source,sizeof(source),"%s/%s",argv[3],names[n])>0);
  assert(snprintf(dump,sizeof(dump),"%s/program-%u",root,n)>0);
  frontends[n]=cs_frontend_compile_buffers(source,dump);assert(frontends[n] && !cs_frontend_shared_bytes(frontends[n]) &&
   !cs_frontend_default_components(frontends[n]) && cs_frontend_storage_binding(frontends[n],0)==2);
  struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=cs_frontend_tokens(frontends[n])};
  shaders[n]=context->create_compute_state(context,&state);assert(shaders[n]);
 }
 struct pipe_resource *source=buffer(screen,8192);assert(source);
 source_handle=pipe_opengpu_resource_handle(opengpu_resource(source)->gpu);
 memset(pipe_opengpu_resource_map(opengpu_resource(source)->gpu),0x6b,8192);
 struct pipe_resource *outputs[10]={0};
 for(phase_index=0;phase_index<10;phase_index++){
  char local[4096];assert(snprintf(local,sizeof(local),"%s/phase-%u",root,phase_index)>0);
  assert(!mkdir(local,0700));directory=local;submitted=retired=0;phase_context=0;
  const unsigned program=phase_programs[phase_index];phase_opcode=program==4?14:0;phase_status=phase_index==2?13:0;
  struct pipe_resource *resources[4]={0};
  if(phase_opcode==0){resources[2]=source;phase_output=source_handle;}
  else {outputs[phase_index]=buffer(screen,8192);assert(outputs[phase_index]);resources[2]=outputs[phase_index];
   phase_output=pipe_opengpu_resource_handle(opengpu_resource(outputs[phase_index])->gpu);
   memset(pipe_opengpu_resource_map(opengpu_resource(outputs[phase_index])->gpu),0xa7,8192);}
  context->bind_compute_state(context,shaders[program]);cs_frontend_storage(frontends[program],context,resources);
  struct pipe_grid_info grid={.work_dim=phase_opcode==14?3:1,.grid={1,1,1},.block={4,1,1}};
  if(phase_opcode==14){memset(grid.grid,0,sizeof(grid.grid));grid.block[1]=2;grid.indirect=source;grid.indirect_offset=4092;}
  context->launch_grid(context,&grid);assert(submitted==1 && retired);
  marker("cleanup_wait","actual compute retired; independent original byte/source/guard checks required\n");
  f=wait_file("BEGIN_CLEANUP");magic(f,"OGCIBA01");assert(read32(f)==phase_index);end(f);
  char returned[4096];path(returned,"returned.bytes");assert(!compute_host_import_originals(session,returned));
  if(phase_index==9){
   assert(!compute_host_begin_cleanup(session,directory) && !compute_host_finish_indirect_observed(session,0) &&
    !compute_host_end_cleanup(session));
   f=wait_file("FINAL_RESPONSE");magic(f,"OGCMOK01");assert(read32(f)==1);end(f);
  }else{
   assert(!compute_host_begin_phase_cleanup(session,directory) && !compute_host_release_phase_job(session));
   context->bind_compute_state(context,NULL);assert(!compute_host_end_phase_cleanup(session));
   f=wait_file("PHASE_RESPONSE");magic(f,"OGCPE001");struct compute_host_phase_receipt ack;
   ack.command_id=read32(f);ack.context_id=read32(f);ack.epoch=read32(f);ack.batches=read32(f);
   ack.status=read32(f);ack.irq=read32(f);ack.irq_asserted=read32(f);ack.external_drained=read32(f);
   ack.quiet_cycles=read32(f);ack.read_only_guards=read32(f);end(f);assert(!compute_host_resume_phase(session,&ack));
  }
 }
 for(unsigned n=0;n<5;n++)cs_frontend_destroy(frontends[n]);
 /* All original C ownership/MMU has been externally retired. As in the
  * single-owner producer, CPU Gallium objects end with this isolated process. */
 assert(!compute_host_destroy(session));session=NULL;
 puts("actual Mesa continuous producer/indirect original GEM lifecycle: PASS (DUT bytes checked externally)");return 0;
}
