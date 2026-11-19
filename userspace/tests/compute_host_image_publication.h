#ifndef OPENGPU_COMPUTE_HOST_IMAGE_PUBLICATION_H
#define OPENGPU_COMPUTE_HOST_IMAGE_PUBLICATION_H
/* Original caller inputs and accepted-job metadata for coherent buffer-image publication. */
static unsigned image_publication_case,image_publication_index,image_publication_storage_binding;
static unsigned image_publication_handle,image_publication_native_unit,image_publication_api_binding;
static const char *const image_publication_names[]={
 "image-publication-warm","image-publication-1","image-publication-reload"};
static void image_publication_parse_case(const char *name)
{for(unsigned n=0;n<sizeof(image_publication_names)/sizeof(image_publication_names[0]);n++)
 if(!strcmp(name,image_publication_names[n])){image_publication_case=1;image_publication_index=n;return;}}
static void image_publication_setup(struct pipe_screen *s,struct pipe_context *p,void *frontend,
 struct pipe_resource *outputs[4])
{
 image_publication_storage_binding=cs_frontend_storage_binding(frontend,0);
 assert(image_publication_storage_binding==0);
 image_publication_api_binding=cs_frontend_image_binding(frontend,0);
 assert(image_publication_api_binding==0);image_publication_native_unit=0;
 struct pipe_resource image={.target=PIPE_BUFFER,.format=PIPE_FORMAT_R32_UINT,
  .width0=8192,.height0=1,.depth0=1,.array_size=1,.bind=PIPE_BIND_SHADER_IMAGE};
 struct pipe_resource *resource=s->resource_create(s,&image);assert(resource);
 image_publication_handle=pipe_opengpu_resource_handle(opengpu_resource(resource)->gpu);
 void *mapped=pipe_opengpu_resource_map(opengpu_resource(resource)->gpu);assert(mapped);
 memset(mapped,0x6b,8192);uint32_t zero=0;
 memcpy((uint8_t *)mapped+4,&zero,4);memcpy((uint8_t *)mapped+68,&zero,4);
 struct pipe_image_view view={.resource=resource,.format=PIPE_FORMAT_R32_UINT,
  .access=PIPE_IMAGE_ACCESS_READ|PIPE_IMAGE_ACCESS_WRITE,
  .shader_access=PIPE_IMAGE_ACCESS_READ|PIPE_IMAGE_ACCESS_WRITE};
 view.u.buf.offset=0;view.u.buf.size=8192;
 p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,1,0,&view);
 void *output=pipe_opengpu_resource_map(opengpu_resource(outputs[0])->gpu);
 assert(output);memset(output,0xa7,8192);
}
static void image_publication_prepare_submission(struct drm_opengpu_compute *launch)
{
 assert(!submitted && launch->grid[0]==1 && launch->grid[1]==1 && launch->grid[2]==1 &&
  launch->local[0]==1 && launch->local[1]==1 && launch->local[2]==1);
}
static void image_publication_write_oracle(FILE *f)
{
 struct compute_host_snapshot snapshot;assert(!compute_host_snapshot_get(session,&snapshot) &&
  snapshot.images && snapshot.image_bytes==1024 && snapshot.image_root &&
  snapshot.storage_bytes==256 && snapshot.storage_root &&
  snapshot.code && snapshot.code_bytes && !(snapshot.code_bytes&3) &&
  snapshot.kernarg_bytes);
 const char *role=image_publication_index==0?"warm":image_publication_index==1?"producer":"reload";
 const unsigned expected_data=image_publication_index?7u:0u,expected_flag=image_publication_index?1u:0u;
 const struct drm_opengpu_compute_image_view *v=&accepted_images[image_publication_native_unit];
 assert(v->slot==39+image_publication_native_unit);
 const struct drm_opengpu_resource *b=&image_bindings[v->slot];
 uint64_t physical,bytes;assert(b->handle==image_publication_handle && !b->offset && b->size==8192 &&
  !compute_host_original_physical(session,b->handle,&physical,&bytes) && bytes==8192);
 fprintf(f,"{\"schema\":\"opengpu-compute-image-publication-oracle-v1\",\"sourceCase\":\"%s\","
  "\"role\":\"%s\",\"epoch\":1,\"groups\":1,\"local\":1,\"viewBytes\":8192,\"guard\":3735928559,"
  "\"expectedData\":%u,\"expectedFlag\":%u,\"context\":%u,\"storageBinding\":%u,\"imageBinding\":%u,"
  "\"kernargPhysical\":%llu,\"kernargBytes\":%llu,\"imagePhysical\":%llu,\"imageRoot\":%u,"
  "\"storagePhysical\":%llu,\"storageRoot\":%u,\"codePhysical\":%llu,\"codeBytes\":%llu,\"image\":"
  "{\"apiBinding\":%u,\"nativeUnit\":%u,\"handle\":%u,\"allocationPhysical\":%llu,\"allocationBytes\":%llu,"
  "\"bindingBytes\":%llu,\"view\":{\"offset\":%llu,\"rowPitch\":%llu,\"slicePitch\":%llu,\"layerPitch\":%llu,"
  "\"slot\":%u,\"flags\":%u,\"target\":%u,\"storageFormat\":%u,\"imageFormat\":%u,\"level\":%u,"
  "\"width\":%u,\"height\":%u,\"depth\":%u,\"layers\":%u,\"layer\":%u}},\"output\":",
  image_publication_names[image_publication_index],role,expected_data,expected_flag,snapshot.context,
  image_publication_storage_binding,image_publication_api_binding,
  (unsigned long long)snapshot.kernarg_physical,(unsigned long long)snapshot.kernarg_bytes,
  (unsigned long long)snapshot.image_physical,snapshot.image_root,
  (unsigned long long)snapshot.storage_physical,snapshot.storage_root,
  (unsigned long long)snapshot.code_physical,(unsigned long long)snapshot.code_bytes,
  image_publication_api_binding,image_publication_native_unit,b->handle,
  (unsigned long long)physical,(unsigned long long)bytes,(unsigned long long)b->size,
  (unsigned long long)v->offset,(unsigned long long)v->row_pitch,(unsigned long long)v->slice_pitch,
  (unsigned long long)v->layer_pitch,v->slot,v->flags,v->target,v->storage_format,v->image_format,
  v->level,v->width,v->height,v->depth,v->layers,v->layer);
 source_window(f,output_handles[image_publication_storage_binding],0,8192,image_publication_storage_binding);
 fputs("}\n",f);
 FILE *code=file("code.words","wx");assert(code);
 for(size_t n=0;n<snapshot.code_bytes/4;n++)assert(fprintf(code,"%08x\n",((const uint32_t *)snapshot.code)[n])>0);
 assert(!fclose(code));
}
#endif
