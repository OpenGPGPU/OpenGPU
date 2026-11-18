#ifndef OPENGPU_COMPUTE_HOST_STORAGE_ATOMICS_H
#define OPENGPU_COMPUTE_HOST_STORAGE_ATOMICS_H
/* Original caller inputs and actual accepted-job metadata, never GPU results. */
static unsigned storage_case,storage_index,storage_bindings[2];
static const char *const storage_names[]={
 "storage-atomic-exchange","storage-atomic-add","storage-atomic-xor","storage-atomic-or",
 "storage-atomic-and","storage-atomic-signed-min","storage-atomic-signed-max",
 "storage-atomic-unsigned-min","storage-atomic-unsigned-max","storage-atomic-compare-match",
 "storage-atomic-compare-miss","storage-atomic-exchange-lane","storage-atomic-add-lane",
 "storage-atomic-compare-lane","storage-atomic-readonly","storage-atomic-past-end","storage-atomic-endpoint"};
static const uint32_t storage_seeds[]={10u,0xfffffff0u,10u,0x80000000u,0xffffffffu,
 0x7fffffffu,0x80000000u,0xffffffffu,0x7fffffffu,10u,10u,0x13579bdfu,0xfffffff0u,0u,10u,10u,0xfffffff0u};
static void storage_parse_case(const char *name)
{for(unsigned n=0;n<sizeof(storage_names)/sizeof(storage_names[0]);n++)
 if(!strcmp(name,storage_names[n])){storage_case=1;storage_index=n;return;}}
static void storage_setup(void *frontend,struct pipe_resource *outputs[4])
{
 for(unsigned unit=0;unit<2;unit++)storage_bindings[unit]=cs_frontend_storage_binding(frontend,unit);
 assert(storage_bindings[0]<2 && storage_bindings[1]<2 && storage_bindings[0]!=storage_bindings[1]);
 void *target=pipe_opengpu_resource_map(opengpu_resource(outputs[0])->gpu);
 void *output=pipe_opengpu_resource_map(opengpu_resource(outputs[1])->gpu);
 assert(target && output);memset(target,0x6b,8192);memset(output,0xa7,8192);
 memcpy((uint8_t *)target+(storage_index==16?8188:0),&storage_seeds[storage_index],4);
}
static void storage_prepare_submission(struct drm_opengpu_compute_storage *request)
{
 assert(!submitted && request->launch.grid[0]==2 && request->launch.grid[1]==1 && request->launch.grid[2]==1 &&
  request->launch.local[0]==4 && request->launch.local[1]==1 && request->launch.local[2]==1);
 unsigned target=storage_bindings[0]==0?0:1;
 /* Preserve original Mesa words and caller BOs; this distinct negative view
  * must be admitted and fail through the actual checked hardware service. */
 if(storage_index==14)request->bindings[target].access=OPENGPU_COMPUTE_STORAGE_READ;
}
static void storage_write_oracle(FILE *f)
{
 struct compute_host_snapshot snapshot;assert(!compute_host_snapshot_get(session,&snapshot) &&
  snapshot.storage_bytes==256 && snapshot.storage_root);
 fprintf(f,"{\"schema\":\"opengpu-compute-storage-atomic-oracle-v1\",\"sourceCase\":\"%s\","
  "\"groups\":2,\"local\":4,\"viewBytes\":8192,\"guard\":3735928559,\"seed\":%u,\"targetOffset\":%u,"
  "\"targetAccess\":%u,\"resultStatus\":%u,\"atomicOffset\":%u,\"context\":%u,\"storageBindings\":[%u,%u],"
  "\"descriptorPhysical\":%llu,\"descriptorRoot\":%u,\"codePhysical\":%llu,\"codeBytes\":%llu,\"target\":",
  storage_names[storage_index],storage_seeds[storage_index],storage_index==16?8188u:0u,
  storage_index==14?1u:3u,(storage_index==14 || storage_index==15)?1u:0u,
  storage_index==15?8192u:storage_index==16?8188u:0u,snapshot.context,storage_bindings[0],storage_bindings[1],
  (unsigned long long)snapshot.storage_physical,snapshot.storage_root,
  (unsigned long long)snapshot.code_physical,(unsigned long long)snapshot.code_bytes);
 source_window(f,output_handles[0],0,8192,0);fputs(",\"output\":",f);
 source_window(f,output_handles[1],0,8192,1);fputs("}\n",f);
 FILE *code=file("code.words","wx");assert(code && !(snapshot.code_bytes&3));
 for(size_t n=0;n<snapshot.code_bytes/4;n++)assert(fprintf(code,"%08x\n",((const uint32_t *)snapshot.code)[n])>0);
 assert(!fclose(code));
}
#endif
