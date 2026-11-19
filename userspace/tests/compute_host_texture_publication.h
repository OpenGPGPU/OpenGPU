#ifndef OPENGPU_COMPUTE_HOST_TEXTURE_PUBLICATION_H
#define OPENGPU_COMPUTE_HOST_TEXTURE_PUBLICATION_H
/* Original caller inputs and accepted-job metadata for buffer-texture publication. */
static unsigned texture_publication_case,texture_publication_index,texture_publication_storage_binding;
static unsigned texture_publication_handle,texture_publication_native_unit,texture_publication_api_binding;
static unsigned texture_publication_slot;
static struct drm_opengpu_compute_texture_view accepted_texture_view;
static const char *const texture_publication_names[]={
 "texture-publication-warm","texture-publication-1","texture-publication-reload"};
static void texture_publication_parse_case(const char *name)
{for(unsigned n=0;n<sizeof(texture_publication_names)/sizeof(texture_publication_names[0]);n++)
 if(!strcmp(name,texture_publication_names[n])){texture_publication_case=1;texture_publication_index=n;return;}}
static void texture_publication_setup(struct pipe_screen *s,struct pipe_context *p,void *frontend,
 struct pipe_resource *outputs[4])
{
 texture_publication_storage_binding=cs_frontend_storage_binding(frontend,0);
 assert(texture_publication_storage_binding==0);
 struct pipe_resource image={.target=PIPE_BUFFER,.format=PIPE_FORMAT_R32_UINT,
  .width0=8192,.height0=1,.depth0=1,.array_size=1,
  .bind=PIPE_BIND_SHADER_IMAGE|PIPE_BIND_SAMPLER_VIEW};
 struct pipe_resource *resource=s->resource_create(s,&image);assert(resource);
 texture_publication_handle=pipe_opengpu_resource_handle(opengpu_resource(resource)->gpu);
 void *mapped=pipe_opengpu_resource_map(opengpu_resource(resource)->gpu);assert(mapped);
 memset(mapped,0x6b,8192);uint32_t zero=0;
 memcpy((uint8_t *)mapped+4,&zero,4);memcpy((uint8_t *)mapped+68,&zero,4);
 if(texture_publication_index==1){
  texture_publication_api_binding=cs_frontend_image_binding(frontend,0);
  assert(texture_publication_api_binding==0);texture_publication_native_unit=0;
  texture_publication_slot=39;
  struct pipe_image_view view={.resource=resource,.format=PIPE_FORMAT_R32_UINT,
   .access=PIPE_IMAGE_ACCESS_READ|PIPE_IMAGE_ACCESS_WRITE,
   .shader_access=PIPE_IMAGE_ACCESS_READ|PIPE_IMAGE_ACCESS_WRITE};
  view.u.buf.offset=0;view.u.buf.size=8192;
  p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,1,0,&view);
 }else{
  texture_publication_api_binding=cs_frontend_sampler_binding(frontend,0);
  assert(texture_publication_api_binding==0);texture_publication_native_unit=0;
  texture_publication_slot=3;
  struct pipe_sampler_view description={.format=PIPE_FORMAT_R32_UINT,.target=PIPE_BUFFER,
   .swizzle_r=0,.swizzle_g=1,.swizzle_b=2,.swizzle_a=3};
  description.u.buf.offset=0;description.u.buf.size=8192;
  struct pipe_sampler_view *sampler=p->create_sampler_view(p,resource,&description);assert(sampler);
  p->set_sampler_views(p,PIPE_SHADER_COMPUTE,0,1,0,false,&sampler);
 }
 void *output=pipe_opengpu_resource_map(opengpu_resource(outputs[0])->gpu);
 assert(output);memset(output,0xa7,8192);
}
static void texture_publication_prepare_submission(struct drm_opengpu_compute *launch)
{
 assert(!submitted && launch->grid[0]==1 && launch->grid[1]==1 && launch->grid[2]==1 &&
  launch->local[0]==1 && launch->local[1]==1 && launch->local[2]==1);
}
static void texture_publication_write_oracle(FILE *f)
{
 struct compute_host_snapshot snapshot;assert(!compute_host_snapshot_get(session,&snapshot) &&
  snapshot.storage_bytes==256 && snapshot.storage_root &&
  snapshot.code && snapshot.code_bytes && !(snapshot.code_bytes&3) &&
  snapshot.kernarg_bytes);
 const char *role=texture_publication_index==0?"warm":texture_publication_index==1?"producer":"reload";
 const unsigned expected_data=texture_publication_index?7u:0u,expected_flag=texture_publication_index?1u:0u;
 uint64_t physical,bytes;
 assert(!compute_host_original_physical(session,texture_publication_handle,&physical,&bytes) && bytes==8192);
 fprintf(f,"{\"schema\":\"opengpu-compute-texture-publication-oracle-v1\",\"sourceCase\":\"%s\","
  "\"role\":\"%s\",\"epoch\":1,\"groups\":1,\"local\":1,\"viewBytes\":8192,\"guard\":3735928559,"
  "\"expectedData\":%u,\"expectedFlag\":%u,\"context\":%u,\"storageBinding\":%u,"
  "\"kernargPhysical\":%llu,\"kernargBytes\":%llu,\"storagePhysical\":%llu,\"storageRoot\":%u,"
  "\"codePhysical\":%llu,\"codeBytes\":%llu,",
  texture_publication_names[texture_publication_index],role,expected_data,expected_flag,snapshot.context,
  texture_publication_storage_binding,
  (unsigned long long)snapshot.kernarg_physical,(unsigned long long)snapshot.kernarg_bytes,
  (unsigned long long)snapshot.storage_physical,snapshot.storage_root,
  (unsigned long long)snapshot.code_physical,(unsigned long long)snapshot.code_bytes);
 if(texture_publication_index==1){
  assert(snapshot.images && snapshot.image_bytes==1024 && snapshot.image_root);
  const struct drm_opengpu_compute_image_view *v=&accepted_images[texture_publication_native_unit];
  assert(v->slot==39+texture_publication_native_unit);
  const struct drm_opengpu_resource *b=&image_bindings[v->slot];
  assert(b->handle==texture_publication_handle && !b->offset && b->size==8192);
  fprintf(f,"\"imageBinding\":%u,\"imagePhysical\":%llu,\"imageRoot\":%u,\"image\":"
   "{\"apiBinding\":%u,\"nativeUnit\":%u,\"handle\":%u,\"allocationPhysical\":%llu,\"allocationBytes\":%llu,"
   "\"bindingBytes\":%llu,\"view\":{\"offset\":%llu,\"rowPitch\":%llu,\"slicePitch\":%llu,\"layerPitch\":%llu,"
   "\"slot\":%u,\"flags\":%u,\"target\":%u,\"storageFormat\":%u,\"imageFormat\":%u,\"level\":%u,"
   "\"width\":%u,\"height\":%u,\"depth\":%u,\"layers\":%u,\"layer\":%u}},\"output\":",
   texture_publication_api_binding,(unsigned long long)snapshot.image_physical,snapshot.image_root,
   texture_publication_api_binding,texture_publication_native_unit,b->handle,
   (unsigned long long)physical,(unsigned long long)bytes,(unsigned long long)b->size,
   (unsigned long long)v->offset,(unsigned long long)v->row_pitch,(unsigned long long)v->slice_pitch,
   (unsigned long long)v->layer_pitch,v->slot,v->flags,v->target,v->storage_format,v->image_format,
   v->level,v->width,v->height,v->depth,v->layers,v->layer);
 }else{
  assert(snapshot.textures && snapshot.texture_bytes && snapshot.texture_root &&
   accepted_texture_view.slot==3 && accepted_texture_view.target==5 &&
   accepted_texture_view.format==14 && accepted_texture_view.buffer_texels==2048 &&
   accepted_texture_view.levels[0].buffer_bytes==8192);
  const struct drm_opengpu_resource *b=&image_bindings[accepted_texture_view.slot];
  assert(b->handle==texture_publication_handle && !b->offset && b->size==8192);
  fprintf(f,"\"textureBinding\":%u,\"texturePhysical\":%llu,\"textureRoot\":%u,\"texture\":"
   "{\"apiBinding\":%u,\"nativeUnit\":%u,\"handle\":%u,\"allocationPhysical\":%llu,\"allocationBytes\":%llu,"
   "\"bindingBytes\":%llu,\"view\":{\"slot\":%u,\"target\":%u,\"format\":%u,\"bufferTexels\":%u,"
   "\"height\":%u,\"depth\":%u,\"layers\":%u,\"levelCount\":%u,\"offset\":%llu,\"bufferBytes\":%llu}},\"output\":",
   texture_publication_api_binding,(unsigned long long)snapshot.texture_physical,snapshot.texture_root,
   texture_publication_api_binding,texture_publication_native_unit,b->handle,
   (unsigned long long)physical,(unsigned long long)bytes,(unsigned long long)b->size,
   accepted_texture_view.slot,accepted_texture_view.target,accepted_texture_view.format,
   accepted_texture_view.buffer_texels,accepted_texture_view.height,accepted_texture_view.depth,
   accepted_texture_view.layers,accepted_texture_view.level_count,
   (unsigned long long)accepted_texture_view.levels[0].offset,
   (unsigned long long)accepted_texture_view.levels[0].buffer_bytes);
 }
 source_window(f,output_handles[texture_publication_storage_binding],0,8192,texture_publication_storage_binding);
 fputs("}\n",f);
 FILE *code=file("code.words","wx");assert(code);
 for(size_t n=0;n<snapshot.code_bytes/4;n++)assert(fprintf(code,"%08x\n",((const uint32_t *)snapshot.code)[n])>0);
 assert(!fclose(code));
}
#endif
