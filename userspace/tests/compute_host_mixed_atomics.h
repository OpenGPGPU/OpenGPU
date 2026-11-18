/* One original Gallium resource bound as both a typed buffer image and SSBO.
 * All bytes written here are caller inputs; external DUT bytes prove results. */
static unsigned mixed_case,mixed_format,mixed_pair,mixed_order,mixed_layout,mixed_storage_bindings[2];
static const char *mixed_name;
static const char *const mixed_formats[]={"r32ui","r32i","r32f"};
static const unsigned mixed_image_formats[]={6,10,2};
static const char *const mixed_pairs[]={"swap-add","xor-or","and-min","max-cas","cas-pair","swap-swap"};
static const char *const mixed_orders[]={"storage-first","image-first"};
static const struct {const char *name;unsigned storage_offset,storage_bytes,index,image_offset,image_bytes,coordinate;} mixed_views[]={
 {"same",0,64,0,0,64,0},{"shifted",3840,512,67,4096,52,3},{"endpoint",7936,256,63,8140,52,12}};

static void mixed_parse_case(const char *name)
{
 for(unsigned format=0;format<3;format++)for(unsigned pair=0;pair<6;pair++)
 for(unsigned order=0;order<2;order++)for(unsigned layout=0;layout<3;layout++){
  if((format==2)!=(pair==5))continue;
  char expected[160];assert(snprintf(expected,sizeof(expected),"mixed-atomic-%s-%s-%s-%s",
   mixed_formats[format],mixed_pairs[pair],mixed_orders[order],mixed_views[layout].name)>0);
  if(!strcmp(name,expected)){
   assert(!image_case && !mixed_case);mixed_case=image_case=1;image_mode=7;
   mixed_format=format;mixed_pair=pair;mixed_order=order;mixed_layout=layout;mixed_name=name;
  }
 }
}

static struct pipe_resource *mixed_target_buffer(struct pipe_screen *s)
{
 struct pipe_resource t={.target=PIPE_BUFFER,.format=image_formats[mixed_image_formats[mixed_format]].format,
  .width0=8192,.height0=1,.depth0=1,.array_size=1,.bind=PIPE_BIND_SHADER_IMAGE|PIPE_BIND_SHADER_BUFFER};
 return s->resource_create(s,&t);
}

static uint32_t mixed_seed(void)
{
 if(mixed_format==2)return 0x3f800000;
 if(mixed_pair==4)return 0;
 if(mixed_pair==2)return mixed_format==1?0x7fffffff:0xffffffff;
 if(mixed_pair==3)return mixed_format==1?0x80000000:0x7fffffff;
 return 0xfffffff0;
}

static void mixed_setup(struct pipe_screen *s,struct pipe_context *p,void *frontend,struct pipe_resource *outputs[4])
{
 uint8_t *target=pipe_opengpu_resource_map(opengpu_resource(outputs[0])->gpu);assert(target);
 memset(target,0x6b,8192);uint32_t seed=mixed_seed();
 unsigned offset=mixed_views[mixed_layout].storage_offset+4*mixed_views[mixed_layout].index;
 assert(offset==mixed_views[mixed_layout].image_offset+4*mixed_views[mixed_layout].coordinate && offset+4<=8192);
 for(unsigned byte=0;byte<4;byte++)target[offset+byte]=(uint8_t)(seed>>(8*byte));
 struct pipe_shader_buffer buffers[2]={0};unsigned seen=0,target_unit=2;
 for(unsigned unit=0;unit<2;unit++){
  unsigned api=cs_frontend_storage_binding(frontend,unit);assert(api==0 || api==2);
  assert(!(seen&(1u<<api)));seen|=1u<<api;mixed_storage_bindings[unit]=api;
  buffers[unit]=(struct pipe_shader_buffer){.buffer=outputs[api],
   .buffer_offset=api==0?mixed_views[mixed_layout].storage_offset:0,
   .buffer_size=api==0?mixed_views[mixed_layout].storage_bytes:8192};
  if(api==0)target_unit=unit;
 }
 assert(seen==5 && target_unit<2 && cs_frontend_image_binding(frontend,0)==3);
 p->set_shader_buffers(p,PIPE_SHADER_COMPUTE,0,2,buffers,3);
 struct pipe_image_view image={.resource=outputs[0],.format=image_formats[mixed_image_formats[mixed_format]].format,
  .access=PIPE_IMAGE_ACCESS_READ|PIPE_IMAGE_ACCESS_WRITE,.shader_access=PIPE_IMAGE_ACCESS_READ|PIPE_IMAGE_ACCESS_WRITE};
 image.u.buf.offset=mixed_views[mixed_layout].image_offset;image.u.buf.size=mixed_views[mixed_layout].image_bytes;
 p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,1,0,&image);
 struct opengpu_cs_program program;struct opengpu_cs_image_info info;
 assert(!opengpu_cs_emit_image_info(cs_frontend_tokens(frontend),NULL,&program,&info));
 assert(program.storage_atomics==(1u<<target_unit) && program.reads==program.storage_atomics && program.writes==3);
 assert(info.atomics==1 && program.image_reads==1 && program.image_writes==1 && !program.image_queries);
 assert(info.float_exchange==(mixed_format==2?1u:0u));
 if(mixed_format==2)assert(pipe_opengpu_screen_compute_image_float_exchange(opengpu_screen_gpu(s)));
 unsigned images=0,storage=0;
 for(unsigned n=0;n<program.words;n++)if((program.code[n]&127)==0x2b){
  unsigned function=program.code[n]>>26;
  if(function==23)images++;
  if(function==25 || function==26)storage++;
 }
 assert(images==1 && storage==1);
 printf("original %s: one image and one SSBO atomic, original shared GEM, native words=%u PASS (CPU compiler only)\n",mixed_name,program.words);
}

static void mixed_write_oracle(FILE *f)
{
 struct compute_host_snapshot snap;assert(!compute_host_snapshot_get(session,&snap));
 assert(snap.images && snap.image_bytes==1024 && snap.image_root && snap.storage_bytes==256 && snap.storage_root);
 const struct drm_opengpu_compute_image_view *v=&accepted_images[0];assert(v->slot==39);
 const struct drm_opengpu_resource *b=&image_bindings[v->slot];
 uint64_t physical,bytes;assert(b->handle==output_handles[0] && !b->offset && b->size==8192 &&
  !compute_host_original_physical(session,b->handle,&physical,&bytes) && bytes==8192);
 for(unsigned unit=0;unit<2;unit++){
  unsigned api=mixed_storage_bindings[unit];const struct drm_opengpu_resource *storage=&image_bindings[35+unit];
  assert(storage->handle==output_handles[api] && storage->offset==(api==0?mixed_views[mixed_layout].storage_offset:0) &&
   storage->size==(api==0?mixed_views[mixed_layout].storage_bytes:8192));
 }
 fprintf(f,"{\"schema\":\"opengpu-compute-mixed-atomic-oracle-v1\",\"sourceCase\":\"%s\",\"groups\":2,\"local\":4,\"viewBytes\":8192,\"guard\":3735928559,\"context\":%u,\"targetHandle\":%u,\"storageBindings\":[%u,%u],\"kernargPhysical\":%llu,\"kernargBytes\":%llu,\"imagePhysical\":%llu,\"imageRoot\":%u,\"storagePhysical\":%llu,\"storageRoot\":%u,\"codePhysical\":%llu,\"codeBytes\":%llu,\"target\":",
  mixed_name,snap.context,output_handles[0],mixed_storage_bindings[0],mixed_storage_bindings[1],
  (unsigned long long)snap.kernarg_physical,(unsigned long long)snap.kernarg_bytes,
  (unsigned long long)snap.image_physical,snap.image_root,(unsigned long long)snap.storage_physical,snap.storage_root,
  (unsigned long long)snap.code_physical,(unsigned long long)snap.code_bytes);
 source_window(f,output_handles[0],mixed_views[mixed_layout].storage_offset,mixed_views[mixed_layout].storage_bytes,0);
 fputs(",\"output\":",f);source_window(f,output_handles[2],0,8192,2);
 fprintf(f,",\"image\":{\"apiBinding\":3,\"nativeUnit\":0,\"handle\":%u,\"allocationPhysical\":%llu,\"allocationBytes\":%llu,\"bindingBytes\":%llu,\"view\":{\"offset\":%llu,\"rowPitch\":%llu,\"slicePitch\":%llu,\"layerPitch\":%llu,\"slot\":%u,\"flags\":%u,\"target\":%u,\"storageFormat\":%u,\"imageFormat\":%u,\"level\":%u,\"width\":%u,\"height\":%u,\"depth\":%u,\"layers\":%u,\"layer\":%u}}}\n",
  b->handle,(unsigned long long)physical,(unsigned long long)bytes,(unsigned long long)b->size,
  (unsigned long long)v->offset,(unsigned long long)v->row_pitch,(unsigned long long)v->slice_pitch,(unsigned long long)v->layer_pitch,
  v->slot,v->flags,v->target,v->storage_format,v->image_format,v->level,v->width,v->height,v->depth,v->layers,v->layer);
 assert(snap.code && snap.code_bytes && !(snap.code_bytes&3));FILE *code=file("code.words","wx");assert(code);
 for(size_t n=0;n<snap.code_bytes/4;n++)assert(fprintf(code,"%08x\n",((const uint32_t *)snap.code)[n])>0);
 assert(!fclose(code));
}
