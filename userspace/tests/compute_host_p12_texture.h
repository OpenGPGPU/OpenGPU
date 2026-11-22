#ifndef OPENGPU_COMPUTE_HOST_P12_TEXTURE_H
#define OPENGPU_COMPUTE_HOST_P12_TEXTURE_H
/* P12 texture coexistence: 16 usamplerBuffer views in one compute launch. */
static struct drm_opengpu_compute_texture_view accepted_p12_texture_views[16];
static const char *const p12_texture_names[]={"p12-texture-coexist"};

static uint32_t p12_texture_value(unsigned unit, unsigned texel)
{
 uint32_t v=0x13579bdfu+0x01020304u+unit*0x00718293u+texel*0x9e3779b9u;
 return v^((unit+7u)<<(texel%19u));
}

static void p12_texture_setup(struct pipe_screen *s,struct pipe_context *p,void *frontend)
{
 struct pipe_resource *resources[16];
 struct pipe_sampler_view *samplers[16];
 struct pipe_sampler_view descriptions[16]={0};
 for(unsigned unit=0;unit<16;unit++){
  struct pipe_resource r={.target=PIPE_BUFFER,.format=PIPE_FORMAT_R32_UINT,
   .width0=8192,.height0=1,.depth0=1,.array_size=1,
   .bind=PIPE_BIND_SAMPLER_VIEW};
  resources[unit]=s->resource_create(s,&r);assert(resources[unit]);
  texture_handles[unit]=pipe_opengpu_resource_handle(opengpu_resource(resources[unit])->gpu);
  uint32_t *data=pipe_opengpu_resource_map(opengpu_resource(resources[unit])->gpu);assert(data);
  for(unsigned texel=0;texel<2048;texel++)data[texel]=p12_texture_value(unit,texel);
  texture_native_units[unit]=cs_frontend_sampler_binding(frontend,unit);
  assert(texture_native_units[unit]<16);
  descriptions[unit]=(struct pipe_sampler_view){.format=PIPE_FORMAT_R32_UINT,.target=PIPE_BUFFER,
   .swizzle_r=0,.swizzle_g=1,.swizzle_b=2,.swizzle_a=3};
  descriptions[unit].u.buf.offset=0;descriptions[unit].u.buf.size=8192;
  samplers[unit]=p->create_sampler_view(p,resources[unit],&descriptions[unit]);assert(samplers[unit]);
 }
 p->set_sampler_views(p,PIPE_SHADER_COMPUTE,0,16,0,false,samplers);
}

static void p12_texture_prepare_submission(struct drm_opengpu_compute *launch)
{
 assert(!submitted && launch->grid[0]==1 && launch->grid[1]==1 && launch->grid[2]==1 &&
  launch->local[0]==128 && launch->local[1]==1 && launch->local[2]==1);
}

static void p12_texture_write_oracle(FILE *f)
{
 struct compute_host_snapshot snap;assert(!compute_host_snapshot_get(session,&snap));
 fprintf(f,"{\"schema\":\"opengpu-compute-p12-texture-oracle-v1\",\"sourceCase\":\"p12-texture-coexist\","
  "\"groups\":1,\"local\":128,\"viewBytes\":8192,\"guard\":3735928559,\"context\":%u,"
  "\"kernargPhysical\":%llu,\"kernargBytes\":%llu",
  snap.context,(unsigned long long)snap.kernarg_physical,(unsigned long long)snap.kernarg_bytes);
 fputs(",\"outputs\":[",f);
 for(unsigned i=0;i<4;i++){if(i)fputc(',',f);source_window(f,output_handles[i],0,8192,i);}
 fprintf(f,"],\"storageBindings\":[%u,%u,%u,%u]",
  counter_storage_bindings[0],counter_storage_bindings[1],counter_storage_bindings[2],counter_storage_bindings[3]);
 assert(snap.images && snap.image_bytes==1024 && snap.image_root);
 fputs(",\"images\":[",f);
 for(unsigned i=0;i<4;i++){if(i)fputc(',',f);source_window(f,image_handles[i],0,512,i);}
 fprintf(f,"],\"imageBindings\":[%u,%u,%u,%u],\"imageRoot\":%u,\"imagePhysical\":%llu,\"imageBytes\":%llu",
  counter_image_bindings[0],counter_image_bindings[1],counter_image_bindings[2],counter_image_bindings[3],
  snap.image_root,(unsigned long long)snap.image_physical,(unsigned long long)snap.image_bytes);
 fputs(",\"textures\":[",f);
 for(unsigned unit=0;unit<16;unit++){
  if(unit)fputc(',',f);
  const struct drm_opengpu_compute_texture_view *v=&accepted_p12_texture_views[texture_native_units[unit]];
  uint64_t physical,bytes;assert(!compute_host_original_physical(session,texture_handles[unit],&physical,&bytes) && bytes==8192);
  assert(v->slot==3+unit && v->target==5 && v->format==14 && v->buffer_texels==2048 &&
   v->level_count==1 && v->levels[0].buffer_bytes==8192);
  fprintf(f,"{\"globalBinding\":%u,\"allocationPhysical\":%llu,\"allocationBytes\":8192,\"offset\":0,\"bytes\":8192,"
   "\"view\":{\"slot\":%u,\"target\":%u,\"format\":%u,\"bufferTexels\":%u,\"bufferBytes\":%llu}}",
   3+unit,(unsigned long long)physical,v->slot,v->target,v->format,v->buffer_texels,(unsigned long long)v->levels[0].buffer_bytes);
 }
 fputs("],\"textureBindings\":[",f);
 for(unsigned unit=0;unit<16;unit++){if(unit)fputc(',',f);fprintf(f,"%u",3+unit);}
 fprintf(f,"],\"textureRoot\":%u,\"texturePhysical\":%llu,\"textureBytes\":%llu}\n",
  snap.texture_root,(unsigned long long)snap.texture_physical,(unsigned long long)snap.texture_bytes);
}
#endif
