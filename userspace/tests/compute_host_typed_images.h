/* Original typed-image resources and accepted ioctl views. CPU uploads are
 * inputs only; returned shader/image bytes are checked by the external oracle. */
#include "opengpu_cs_emit.h"
static unsigned image_case, image_target, image_mode, image_format;
static const char *image_name;
static struct drm_opengpu_compute_image_view accepted_images[4];
static struct drm_opengpu_resource image_bindings[OPENGPU_MAX_RESOURCE_BINDINGS+1];
static unsigned image_original_handles[2], image_native_units[2], image_storage_binding;
static const struct {const char *name;enum pipe_format format;unsigned native,bytes,bits,kind,components;} image_formats[]={
 {"rgba32f",PIPE_FORMAT_R32G32B32A32_FLOAT,35,16,32,0,4},
 {"rgba16f",PIPE_FORMAT_R16G16B16A16_FLOAT,34,8,16,0,4},
 {"r32f",PIPE_FORMAT_R32_FLOAT,29,4,32,0,1},
 {"rgba32ui",PIPE_FORMAT_R32G32B32A32_UINT,26,16,32,1,4},
 {"rgba16ui",PIPE_FORMAT_R16G16B16A16_UINT,24,8,16,1,4},
 {"rgba8ui",PIPE_FORMAT_R8G8B8A8_UINT,22,4,8,1,4},
 {"r32ui",PIPE_FORMAT_R32_UINT,14,4,32,1,1},
 {"rgba32i",PIPE_FORMAT_R32G32B32A32_SINT,25,16,32,2,4},
 {"rgba16i",PIPE_FORMAT_R16G16B16A16_SINT,23,8,16,2,4},
 {"rgba8i",PIPE_FORMAT_R8G8B8A8_SINT,21,4,8,2,4},
 {"r32i",PIPE_FORMAT_R32_SINT,13,4,32,2,1},
 {"rgba8",PIPE_FORMAT_R8G8B8A8_UNORM,3,4,8,3,4},
 {"rgba8_snorm",PIPE_FORMAT_R8G8B8A8_SNORM,39,4,8,4,4}};
static const char *image_targets[]={"buffer","2d","array","cube","3d"};
static const enum pipe_texture_target image_pipe_targets[]={PIPE_BUFFER,PIPE_TEXTURE_2D,PIPE_TEXTURE_2D_ARRAY,PIPE_TEXTURE_CUBE,PIPE_TEXTURE_3D};

static void image_parse_case(const char *name)
{
 for(unsigned target=0;target<5;target++)for(unsigned format=0;format<13;format++)for(unsigned mode=0;mode<4;mode++){
  if((mode==1 || mode==2) && (format!=6 || (mode==1 && target!=2 && target!=4) || (mode==2 && target<2)))continue;
  if(mode==3 && format!=6 && format!=10)continue;
  char expected[128];assert(snprintf(expected,sizeof(expected),mode==1?"image-single-%s-%s":mode==2?"image-plane-%s-%s":mode==3?"image-atomic-%s-%s":"image-%s-%s",image_targets[target],image_formats[format].name)>0);
  if(!strcmp(name,expected)){assert(!image_case);image_case=1;image_target=target;image_mode=mode;image_format=format;image_name=name;}
 }
}

static void image_upload(unsigned handle,int destination)
{
 uint64_t physical,bytes;assert(!compute_host_original_physical(session,handle,&physical,&bytes));
 uint8_t *data=bos[handle].data;assert(data && !(bytes%image_formats[image_format].bytes));
 if(destination){memset(data,0xa7,bytes);return;}
 unsigned size=image_formats[image_format].bytes,bits=image_formats[image_format].bits;
 for(uint64_t texel=0;texel<bytes/size;texel++)for(unsigned component=0;component<image_formats[image_format].components;component++){
  uint32_t ordinal=(uint32_t)(texel*image_formats[image_format].components+component),value;
  uint32_t spatial=(uint32_t)(texel^(texel>>4)^(texel>>8)^(texel>>12));
  switch(image_formats[image_format].kind){
  case 0:value=bits==32?0x3f000000u|((ordinal*0x9e3779u+0x13579u)&0x7fffffu):0x3800u|((ordinal*73u+19u)&1023u);break;
  case 3:value=((spatial>>component)&1)?255:0;break;
  case 4:{static const unsigned values[]={0,127,129,128};value=values[(spatial>>(2*component))&3];break;}
  default:value=ordinal*0x10203u+0x8391u;break;
  }
  for(unsigned b=0;b<bits/8;b++)data[texel*size+component*(bits/8)+b]=(uint8_t)(value>>(8*b));
 }
}

static void image_setup(struct pipe_screen *s,struct pipe_context *p,void *frontend)
{
 struct pipe_resource *resources[2];struct pipe_image_view views[4]={0};
 image_storage_binding=cs_frontend_storage_binding(frontend,0);assert(image_storage_binding==2);
 for(unsigned role=0;role<2;role++){
  struct pipe_resource t={.target=image_pipe_targets[image_target],.format=image_formats[image_format].format,
   .width0=26,.height0=image_target==3?26:10,.depth0=image_target==4?(image_mode==1?2:6):1,
   .array_size=image_target==2?(image_mode==1?1:4):image_target==3?6:1,.last_level=1,.bind=PIPE_BIND_SHADER_IMAGE};
  if(!image_target){t.width0=8192;t.height0=t.depth0=t.array_size=1;t.last_level=0;
   if(image_format==12)t.format=PIPE_FORMAT_R8G8B8A8_UNORM;}
  resources[role]=s->resource_create(s,&t);assert(resources[role]);
  image_original_handles[role]=pipe_opengpu_resource_handle(opengpu_resource(resources[role])->gpu);
  image_upload(image_original_handles[role],role);
 }
 unsigned seen=0;
 for(unsigned unit=0;unit<2;unit++){
  unsigned api=cs_frontend_image_binding(frontend,unit);assert(api==3 || api==1);
  unsigned role=api==1;assert(!(seen&(1u<<role)));seen|=1u<<role;image_native_units[role]=unit;
  struct pipe_image_view *v=&views[unit];
  *v=(struct pipe_image_view){.resource=resources[role],.format=image_formats[image_format].format,
   .access=role?PIPE_IMAGE_ACCESS_WRITE:PIPE_IMAGE_ACCESS_READ|(image_mode==3?PIPE_IMAGE_ACCESS_WRITE:0),
   .shader_access=role?PIPE_IMAGE_ACCESS_WRITE:PIPE_IMAGE_ACCESS_READ|(image_mode==3?PIPE_IMAGE_ACCESS_WRITE:0)};
  if(!image_target){v->u.buf.offset=48;v->u.buf.size=13*image_formats[image_format].bytes+3;}
  else{
   v->u.tex.level=1;
   if(image_mode==2)v->u.tex.first_layer=v->u.tex.last_layer=role?(image_target==3?4:2):1;
   else if(image_target==2){v->u.tex.first_layer=image_mode==1?0:1;v->u.tex.last_layer=image_mode==1?0:3;}
   else if(image_target==3)v->u.tex.last_layer=5;
   else if(image_target==4){v->u.tex.first_layer=image_mode==1?0:role?0:1;v->u.tex.last_layer=image_mode==1?0:2;}
  }
 }
 assert(seen==3);
 if(image_mode==3){
  struct opengpu_cs_program program;struct opengpu_cs_image_info info;
  assert(!opengpu_cs_emit_image_info(cs_frontend_tokens(frontend),NULL,&program,&info));
  assert(info.atomics==(1u<<image_native_units[0]) && program.image_reads==info.atomics && program.image_writes==3);
  unsigned counts[8]={0};
  for(unsigned i=0;i<program.words;i++)if((program.code[i]&127)==0x2b && (program.code[i]>>26)==23)
   counts[(program.code[i]>>12)&7]++;
  for(unsigned op=0;op<8;op++)assert(counts[op]==(op==0 || op==7?2u:1u));
  printf("original %s: ten image atomics, native words=%u, metadata/opcodes PASS (CPU compiler only)\n",image_name,program.words);
 }
 p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,2,0,views);
}

static void image_write_oracle(FILE *f)
{
 struct compute_host_snapshot snap;assert(!compute_host_snapshot_get(session,&snap));
 assert(snap.images && snap.image_bytes==1024 && snap.image_root && snap.storage_bytes==256);
 fprintf(f,"{\"schema\":\"opengpu-compute-image-oracle-v1\",\"sourceCase\":\"%s\",\"groups\":2,\"local\":4,\"viewBytes\":8192,\"guard\":3735928559,\"context\":%u,\"kernargPhysical\":%llu,\"kernargBytes\":%llu,\"imagePhysical\":%llu,\"imageRoot\":%u,\"storagePhysical\":%llu,\"storageRoot\":%u,\"output\":",image_name,snap.context,
  (unsigned long long)snap.kernarg_physical,(unsigned long long)snap.kernarg_bytes,
  (unsigned long long)snap.image_physical,snap.image_root,(unsigned long long)snap.storage_physical,snap.storage_root);
 source_window(f,output_handles[image_storage_binding],0,8192,image_storage_binding);fputs(",\"images\":[",f);
 for(unsigned role=0;role<2;role++){
  if(role)fputc(',',f);
  unsigned unit=image_native_units[role];const struct drm_opengpu_compute_image_view *v=&accepted_images[unit];
  assert(v->slot==39+unit);const struct drm_opengpu_resource *b=&image_bindings[v->slot];
  uint64_t physical,bytes;assert(b->handle==image_original_handles[role] && !b->offset &&
   !compute_host_original_physical(session,b->handle,&physical,&bytes));
  fprintf(f,"{\"apiBinding\":%u,\"nativeUnit\":%u,\"handle\":%u,\"allocationPhysical\":%llu,\"allocationBytes\":%llu,\"bindingBytes\":%llu,\"view\":{\"offset\":%llu,\"rowPitch\":%llu,\"slicePitch\":%llu,\"layerPitch\":%llu,\"slot\":%u,\"flags\":%u,\"target\":%u,\"storageFormat\":%u,\"imageFormat\":%u,\"level\":%u,\"width\":%u,\"height\":%u,\"depth\":%u,\"layers\":%u,\"layer\":%u}}",role?1:3,unit,b->handle,
   (unsigned long long)physical,(unsigned long long)bytes,(unsigned long long)b->size,
   (unsigned long long)v->offset,(unsigned long long)v->row_pitch,(unsigned long long)v->slice_pitch,(unsigned long long)v->layer_pitch,
   v->slot,v->flags,v->target,v->storage_format,v->image_format,v->level,v->width,v->height,v->depth,v->layers,v->layer);
 }
 fputs("]}\n",f);
}
