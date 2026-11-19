#ifndef OPENGPU_COMPUTE_HOST_STORAGE_PUBLICATION_H
#define OPENGPU_COMPUTE_HOST_STORAGE_PUBLICATION_H
/* Original caller inputs and accepted-job metadata for SSBO publication. */
static unsigned publication_case,publication_index,publication_bindings[2];
static const char *const publication_names[]={
 "storage-publication-warm","storage-publication-1","storage-publication-reload"};
static void publication_parse_case(const char *name)
{for(unsigned n=0;n<sizeof(publication_names)/sizeof(publication_names[0]);n++)
 if(!strcmp(name,publication_names[n])){publication_case=1;publication_index=n;return;}}
static void publication_setup(void *frontend,struct pipe_resource *outputs[4])
{
 for(unsigned unit=0;unit<2;unit++)publication_bindings[unit]=cs_frontend_storage_binding(frontend,unit);
 assert(publication_bindings[0]<2 && publication_bindings[1]<2 &&
  publication_bindings[0]!=publication_bindings[1]);
 void *target=pipe_opengpu_resource_map(opengpu_resource(outputs[0])->gpu);
 void *output=pipe_opengpu_resource_map(opengpu_resource(outputs[1])->gpu);
 assert(target && output);memset(target,0x6b,8192);memset(output,0xa7,8192);
 uint32_t zero=0;memcpy((uint8_t *)target+4,&zero,4);memcpy((uint8_t *)target+68,&zero,4);
}
static void publication_prepare_submission(struct drm_opengpu_compute_storage *request)
{
 assert(!submitted && request->launch.grid[0]==1 && request->launch.grid[1]==1 && request->launch.grid[2]==1 &&
  request->launch.local[0]==1 && request->launch.local[1]==1 && request->launch.local[2]==1);
}
static void publication_write_oracle(FILE *f)
{
 struct compute_host_snapshot snapshot;assert(!compute_host_snapshot_get(session,&snapshot) &&
  snapshot.storage_bytes==256 && snapshot.storage_root);
 const char *role=publication_index==0?"warm":publication_index==1?"producer":"reload";
 const unsigned expected_data=publication_index?7u:0u,expected_flag=publication_index?1u:0u;
 fprintf(f,"{\"schema\":\"opengpu-compute-storage-publication-oracle-v1\",\"sourceCase\":\"%s\","
  "\"role\":\"%s\",\"epoch\":1,\"groups\":1,\"local\":1,\"viewBytes\":8192,\"guard\":3735928559,"
  "\"expectedData\":%u,\"expectedFlag\":%u,\"context\":%u,\"storageBindings\":[%u,%u],"
  "\"descriptorPhysical\":%llu,\"descriptorRoot\":%u,\"codePhysical\":%llu,\"codeBytes\":%llu,\"target\":",
  publication_names[publication_index],role,expected_data,expected_flag,snapshot.context,
  publication_bindings[0],publication_bindings[1],
  (unsigned long long)snapshot.storage_physical,snapshot.storage_root,
  (unsigned long long)snapshot.code_physical,(unsigned long long)snapshot.code_bytes);
 source_window(f,output_handles[0],0,8192,0);fputs(",\"output\":",f);
 source_window(f,output_handles[1],0,8192,1);fputs("}\n",f);
 FILE *code=file("code.words","wx");assert(code && !(snapshot.code_bytes&3));
 for(size_t n=0;n<snapshot.code_bytes/4;n++)assert(fprintf(code,"%08x\n",((const uint32_t *)snapshot.code)[n])>0);
 assert(!fclose(code));
}
#endif
