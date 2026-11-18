/* Original typed-image resources and accepted ioctl views. CPU uploads are
 * inputs only; returned shader/image bytes are checked by the external oracle. */
#include "opengpu_cs_emit.h"
static unsigned image_case, image_target, image_mode, image_format, image_pair;
static int image_atomic_case(void){return image_mode==3 || image_mode==5 || image_mode==6;}
static int image_boundary_case(void){return image_mode==4 || image_mode==5;}
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
static const char *image_pairs[]={"swap-add","xor-or","and-min","max-cas","cas-pair","swap"};

static uint32_t image_contended_seed(void)
{
 if(image_format==2)return 0x3f800000;
 if(image_pair==4)return 0;
 if(image_pair==2)return image_format==10?0x7fffffff:0xffffffff;
 if(image_pair==3)return image_format==10?0x80000000:0x7fffffff;
 return 0xfffffff0;
}

static void image_parse_case(const char *name)
{
 for(unsigned target=0;target<5;target++)for(unsigned format=0;format<13;format++)for(unsigned pair=0;pair<6;pair++){
  if(format!=2 && format!=6 && format!=10)continue;
  if((format==2)!=(pair==5))continue;
  char expected[128];assert(snprintf(expected,sizeof(expected),"image-contended-%s-%s-%s",image_targets[target],image_formats[format].name,image_pairs[pair])>0);
  if(!strcmp(name,expected)){assert(!image_case);image_case=1;image_target=target;image_mode=6;image_format=format;image_pair=pair;image_name=name;}
 }
 for(unsigned target=0;target<5;target++)for(unsigned format=0;format<13;format++)for(unsigned mode=0;mode<6;mode++){
  if((mode==1 || mode==2) && (format!=6 || (mode==1 && target!=2 && target!=4) || (mode==2 && target<2)))continue;
  if(mode==3 && format!=2 && format!=6 && format!=10)continue;
  if(mode==4 && format!=6)continue;
  if(mode==5 && format!=2 && format!=6)continue;
  char expected[128];assert(snprintf(expected,sizeof(expected),mode==1?"image-single-%s-%s":mode==2?"image-plane-%s-%s":mode==3?"image-atomic-%s-%s":mode==4?"image-bounds-%s-%s":mode==5?"image-atomic-bounds-%s-%s":"image-%s-%s",image_targets[target],image_formats[format].name)>0);
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
 if(image_mode==6){
  /* The original recipe selects (1,1,z) in level one, or buffer word one.
   * Keep all padding and other texels spatially initialized as above. */
  uint64_t offset=48+4;
  if(image_target){
   unsigned height=image_target==3?26:10,planes=image_target==2?4:(image_target==3 || image_target==4)?6:1;
   unsigned row0=(26*4+63)&~63u,row=(13*4+63)&~63u,plane=row*(height/2);
   offset=(uint64_t)row0*height*planes+row+4;
   if(image_target==2 || image_target==3)offset+=2*plane;
   else if(image_target==4)offset+=plane;
  }
  uint32_t seed=image_contended_seed();assert(size==4 && offset+4<=bytes);
  for(unsigned b=0;b<4;b++)data[offset+b]=(uint8_t)(seed>>(8*b));
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
   .access=role?PIPE_IMAGE_ACCESS_WRITE:PIPE_IMAGE_ACCESS_READ|(image_atomic_case()?PIPE_IMAGE_ACCESS_WRITE:0),
   .shader_access=role?PIPE_IMAGE_ACCESS_WRITE:PIPE_IMAGE_ACCESS_READ|(image_atomic_case()?PIPE_IMAGE_ACCESS_WRITE:0)};
  if(!image_target){v->u.buf.offset=48;v->u.buf.size=13*image_formats[image_format].bytes+3;}
  else{
   v->u.tex.level=1;
   if(image_mode==2)v->u.tex.first_layer=v->u.tex.last_layer=role?(image_target==3?4:2):1;
   else if(image_target==2){v->u.tex.first_layer=image_mode==1?0:1;v->u.tex.last_layer=image_mode==1?0:3;}
   else if(image_target==3)v->u.tex.last_layer=5;
   else if(image_target==4){v->u.tex.first_layer=image_boundary_case()?1:image_mode==1?0:role?0:1;v->u.tex.last_layer=image_mode==1?0:2;}
  }
 }
 assert(seen==3);
 if(image_atomic_case()){
  struct opengpu_cs_program program;struct opengpu_cs_image_info info;
  assert(!opengpu_cs_emit_image_info(cs_frontend_tokens(frontend),NULL,&program,&info));
  assert(info.atomics==(1u<<image_native_units[0]) && program.image_reads==info.atomics && program.image_writes==3);
  const unsigned floating=image_format==2;
  assert(info.float_exchange==(floating?info.atomics:0));
  if(floating)assert(pipe_opengpu_screen_compute_image_float_exchange(opengpu_screen_gpu(s)));
  unsigned counts[8]={0};
  for(unsigned i=0;i<program.words;i++)if((program.code[i]&127)==0x2b && (program.code[i]>>26)==23)
   counts[(program.code[i]>>12)&7]++;
  for(unsigned op=0;op<8;op++){
   unsigned expected=image_boundary_case()?(op==0?2u:0u):floating?(op==0?5u:0u):(op==0 || op==7?2u:1u);
   if(image_mode==6)expected=image_pair==5?(op==0):image_pair==4?(op==7?2u:0u):(op==2*image_pair || op==2*image_pair+1);
   assert(counts[op]==expected);
  }
  printf("original %s: %u image atomics, native words=%u, metadata/opcodes PASS (CPU compiler only)\n",image_name,image_mode==6?(floating?1u:2u):image_boundary_case()?2u:floating?5u:10u,program.words);
 }
 p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,2,0,views);
}

static void image_write_oracle(FILE *f)
{
 struct compute_host_snapshot snap;assert(!compute_host_snapshot_get(session,&snap));
 assert(snap.images && snap.image_bytes==1024 && snap.image_root && snap.storage_bytes==256);
 fprintf(f,"{\"schema\":\"%s\",\"sourceCase\":\"%s\",\"groups\":2,\"local\":4,\"viewBytes\":8192,\"guard\":3735928559,\"context\":%u,\"kernargPhysical\":%llu,\"kernargBytes\":%llu,\"imagePhysical\":%llu,\"imageRoot\":%u,\"storagePhysical\":%llu,\"storageRoot\":%u",image_mode==6?"opengpu-compute-contended-image-oracle-v1":"opengpu-compute-image-oracle-v1",image_name,snap.context,
  (unsigned long long)snap.kernarg_physical,(unsigned long long)snap.kernarg_bytes,
  (unsigned long long)snap.image_physical,snap.image_root,(unsigned long long)snap.storage_physical,snap.storage_root);
 if(image_mode==6){
  assert(snap.code && snap.code_bytes && !(snap.code_bytes&3));
  fprintf(f,",\"codePhysical\":%llu,\"codeBytes\":%llu",(unsigned long long)snap.code_physical,(unsigned long long)snap.code_bytes);
  FILE *code=file("code.words","wx");assert(code);
  for(size_t n=0;n<snap.code_bytes/4;n++)assert(fprintf(code,"%08x\n",((const uint32_t *)snap.code)[n])>0);
  assert(!fclose(code));
 }
 fputs(",\"output\":",f);source_window(f,output_handles[image_storage_binding],0,8192,image_storage_binding);fputs(",\"images\":[",f);
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
