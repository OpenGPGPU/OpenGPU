#ifndef OPENGPU_COMPUTE_HOST_COUNTER_PUBLICATION_H
#define OPENGPU_COMPUTE_HOST_COUNTER_PUBLICATION_H
/* Original caller inputs and accepted-job metadata for atomic-counter publication.
 * Counter binding zero owns a separate COUNTER_SOURCE GEM; it does not consume
 * an SSBO window. Warm/reload read counters[1]/counters[0]; the producer
 * unrolls seven increments on counters[1], barriers, then increments counters[0]. */
static unsigned counter_publication_case,counter_publication_index,counter_publication_storage_binding;
static unsigned counter_publication_handle;
static const char *const counter_publication_names[]={
 "counter-publication-warm","counter-publication-1","counter-publication-reload"};
static void counter_publication_parse_case(const char *name)
{for(unsigned n=0;n<sizeof(counter_publication_names)/sizeof(counter_publication_names[0]);n++)
 if(!strcmp(name,counter_publication_names[n])){counter_publication_case=1;counter_publication_index=n;return;}}
static void counter_publication_setup(struct pipe_screen *s,struct pipe_context *p,void *frontend,
 struct pipe_resource *outputs[4])
{
 counter_publication_storage_binding=cs_frontend_storage_binding(frontend,0);
 assert(counter_publication_storage_binding==0);
 struct pipe_resource templat={.target=PIPE_BUFFER,.format=PIPE_FORMAT_R8_UNORM,
  .width0=8192,.height0=1,.depth0=1,.array_size=1,
  .bind=PIPE_BIND_SHADER_BUFFER|PIPE_BIND_CONSTANT_BUFFER};
 struct pipe_resource *counter=s->resource_create(s,&templat);assert(counter);
 counter_publication_handle=pipe_opengpu_resource_handle(opengpu_resource(counter)->gpu);
 uint8_t *data=pipe_opengpu_resource_map(opengpu_resource(counter)->gpu);assert(data);
 memset(data,0x6b,8192);uint32_t zero=0;
 memcpy(data,&zero,4);memcpy(data+4,&zero,4);
 cs_frontend_counters(frontend,p,counter,0,32);
 void *output=pipe_opengpu_resource_map(opengpu_resource(outputs[0])->gpu);
 assert(output);memset(output,0xa7,8192);
}
static void counter_publication_prepare_submission(const struct drm_opengpu_compute_counter_source_resources *request)
{
 const struct drm_opengpu_compute *launch=&request->resources.resources.launch;
 assert(!submitted && request && launch->grid[0]==1 && launch->grid[1]==1 && launch->grid[2]==1 &&
  launch->local[0]==1 && launch->local[1]==1 && launch->local[2]==1);
 assert(request->source.handle==counter_publication_handle && !request->source.offset &&
  request->source.count==8);
}
static void counter_publication_write_oracle(FILE *f)
{
 const struct drm_opengpu_compute_counter_source *v=&accepted_counter.source;
 struct compute_host_snapshot snapshot;assert(!compute_host_snapshot_get(session,&snapshot) &&
  snapshot.storage_bytes==256 && snapshot.storage_root &&
  snapshot.code && snapshot.code_bytes && !(snapshot.code_bytes&3) &&
  snapshot.kernarg_bytes && snapshot.counter_root && snapshot.counter_descriptor_physical);
 const char *role=counter_publication_index==0?"warm":counter_publication_index==1?"producer":"reload";
 const unsigned expected_data=counter_publication_index?7u:0u,expected_flag=counter_publication_index?1u:0u;
 uint64_t physical,bytes;assert(!compute_host_original_physical(session,counter_publication_handle,&physical,&bytes));
 assert(accepted_counter.version==1 && v->handle==counter_publication_handle && !v->offset && v->count==8 &&
  bytes==8192 && snapshot.counter_physical==physical && snapshot.counter_bytes==32);
 fprintf(f,"{\"schema\":\"opengpu-compute-counter-publication-oracle-v1\",\"sourceCase\":\"%s\","
  "\"role\":\"%s\",\"epoch\":1,\"groups\":1,\"local\":1,\"viewBytes\":8192,\"guard\":3735928559,"
  "\"expectedData\":%u,\"expectedFlag\":%u,\"count\":8,\"offset\":0,\"context\":%u,\"counterHandle\":%u,"
  "\"storageBinding\":%u,\"descriptorPhysical\":%llu,\"descriptorRoot\":%u,"
  "\"kernargPhysical\":%llu,\"kernargBytes\":%llu,\"storagePhysical\":%llu,\"storageRoot\":%u,"
  "\"codePhysical\":%llu,\"codeBytes\":%llu,\"counter\":",
  counter_publication_names[counter_publication_index],role,expected_data,expected_flag,snapshot.context,
  v->handle,counter_publication_storage_binding,
  (unsigned long long)snapshot.counter_descriptor_physical,snapshot.counter_root,
  (unsigned long long)snapshot.kernarg_physical,(unsigned long long)snapshot.kernarg_bytes,
  (unsigned long long)snapshot.storage_physical,snapshot.storage_root,
  (unsigned long long)snapshot.code_physical,(unsigned long long)snapshot.code_bytes);
 source_window(f,counter_publication_handle,0,32,0);fputs(",\"output\":",f);
 source_window(f,output_handles[counter_publication_storage_binding],0,8192,counter_publication_storage_binding);
 fputs("}\n",f);
 FILE *code=file("code.words","wx");assert(code);
 for(size_t n=0;n<snapshot.code_bytes/4;n++)assert(fprintf(code,"%08x\n",((const uint32_t *)snapshot.code)[n])>0);
 assert(!fclose(code));
}
#endif
