/* Actual Mesa -> Gallium -> library -> Linux compute capture producer.
 * No CPU shader VM. Active resources live until external DUT receipts and
 * independent byte-oracle cleanup authorization have both been accepted. */
#define _DEFAULT_SOURCE
#include <assert.h>
#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <linux/sync_file.h>
#include <drm/drm.h>
#include <drm/drm_mode.h>
#include "pipe/p_context.h"
#include "pipe/p_screen.h"
#include "pipe/p_state.h"
#include "util/u_inlines.h"
#include "opengpu_public.h"
#include "opengpu_internal.h"
#include "cs_glsl_frontend.h"
#include "../../driver/tests/opengpu_compute_host_fixture.h"

static struct compute_host_session *session;
static struct {void *data;uint64_t bytes;unsigned maps,closed;} bos[512];
static unsigned next_bo=1,next_sync=1,submitted,retired;
static const char *directory;
static const char *loop_case;
static int storage_loop_case,dynamic_loop_case,loop_barrier_case,indexed_uniform_loop_case;
static int capture_only,expect_image_source_fault,uniform_case,dynamic_case,address_case,counter_case,counter_dynamic,counter_coexist,p12_counter_coexist,p12_shared_coexist,p12_texture_coexist,p12_combined_coexist;
static unsigned image_permission_fault;
static unsigned image_reset;
static unsigned storage_view_failure,expect_storage_fault,expect_image_write_fault;
static int expect_compute_failure(void){return expect_image_source_fault || image_permission_fault || storage_view_failure || expect_storage_fault || expect_image_write_fault;}
static unsigned dynamic_operation,dynamic_signed;
static unsigned address_kind,counter_operation,counter_index;
static unsigned counter_offset=4080,counter_count=8;
enum { P12_COMBINED_DEFAULT_COMPONENTS = 1016 };
static const char *counter_name;
static unsigned host_timeout_seconds;
static unsigned output_handles[4],uniform_handles[12],counter_handle,counter_storage_bindings[4],index_uniform_handle;
static unsigned image_handles[4],counter_image_bindings[4];
static unsigned texture_handles[16],texture_native_units[16];
static struct drm_opengpu_compute_counter_source_resources accepted_counter;
static struct drm_opengpu_compute_uniform_resources accepted_uniforms;
static void source_window(FILE *,unsigned,uint64_t,unsigned,unsigned);
static FILE *file(const char *,const char *);
#include "compute_host_storage_atomics.h"
#include "compute_host_storage_publication.h"
#include "compute_host_typed_images.h"
#include "compute_host_image_publication.h"
#include "compute_host_texture_publication.h"
#include "compute_host_p12_texture.h"
#include "compute_host_counter_publication.h"
#include "compute_host_shared_publication.h"
#include "compute_host_dma_publication.h"
#include "compute_host_mixed_atomics.h"
static void permission_views(struct drm_opengpu_compute_image_view *views)
{
 if(!image_permission_fault)return;
 /* Exercise the production DRM client's permission boundary after ordinary
  * Gallium admission. Keep its original shader/reflection, GEMs and layout;
  * only the actual submitted UAPI source-view access changes. */
 assert(image_case && image_atomic_case() && !image_target && image_format==2 &&
  !strcmp(image_name,"image-atomic-buffer-r32f"));
 unsigned unit=image_native_units[0];assert(unit<2);
 struct drm_opengpu_compute_image_view *v=&views[unit];
 assert(v->slot==39+unit && v->flags==(OPENGPU_COMPUTE_IMAGE_READ|OPENGPU_COMPUTE_IMAGE_WRITE));
 v->flags=image_permission_fault==1?OPENGPU_COMPUTE_IMAGE_WRITE:OPENGPU_COMPUTE_IMAGE_READ;
}
static void write_oracle(void);
static uint32_t read32(FILE *f)
{uint32_t v=0;for(unsigned n=0;n<4;n++){int b=fgetc(f);assert(b!=EOF);v|=(uint32_t)b<<(8*n);}return v;}
static uint64_t read64(FILE *f){uint64_t lo=read32(f);return lo|((uint64_t)read32(f)<<32);}
static FILE *file(const char *name,const char *mode)
{char path[4096];assert(snprintf(path,sizeof(path),"%s/%s",directory,name)>0);return fopen(path,mode);}
static FILE *wait_file(const char *name)
{struct timespec start,now,pause={.tv_nsec=10000000};assert(!clock_gettime(CLOCK_MONOTONIC,&start));
 for(;;){FILE *f=file(name,"rb");if(f)return f;assert(errno==ENOENT);
  FILE *error=file("error","rb");assert(!error && errno==ENOENT);
  assert(!clock_gettime(CLOCK_MONOTONIC,&now) && now.tv_sec-start.tv_sec<(time_t)host_timeout_seconds);
  assert(!nanosleep(&pause,NULL) || errno==EINTR);}}
static void magic(FILE *f,const char *expected)
{char v[8];assert(fread(v,1,8,f)==8 && !memcmp(v,expected,8));}
static void end(FILE *f){assert(fgetc(f)==EOF && !ferror(f) && !fclose(f));}
static void path(char out[4096],const char *name)
{assert(snprintf(out,4096,"%s/%s",directory,name)>0);}
static void marker(const char *name,const char *text)
{char target[4096],temporary[4096];path(target,name);assert(snprintf(temporary,sizeof(temporary),"%s.tmp",target)>0);
 FILE *f=fopen(temporary,"wx");assert(f && fputs(text,f)>=0 && !fclose(f));assert(!link(temporary,target) && !unlink(temporary));}
static struct compute_host_held_receipt held(FILE *f)
{struct compute_host_held_receipt h;h.command_id=read32(f);h.completion=read32(f);h.processed_bytes=read64(f);
 h.legacy_status=read32(f);h.status=read32(f);h.irq=read32(f);h.irq_asserted=read32(f);return h;}
static struct compute_host_reset_receipt reset_receipt(FILE *f)
{struct compute_host_reset_receipt r;
 r.command_id=read32(f);r.legacy_status=read32(f);r.status=read32(f);
 r.irq=read32(f);r.irq_asserted=read32(f);r.external_drained=read32(f);return r;}
static int result(int error){if(error){errno=-error;return -1;}return 0;}
static int wait_execution(void)
{
 if(retired)return 0;
 write_oracle();
 char output[4096];assert(submitted==1 && !compute_host_begin_execution(session));
 path(output,"union.memory");assert(!compute_host_export_memory(session,output));
 path(output,"record-0.capture");assert(!compute_host_capture(session,output,1000000000u,37));
 marker("READY",capture_only?"compute CPU capture only; no DUT execution\n":"compute active capture ready\n");
 if(capture_only){puts("actual Mesa/Linux compute CPU capture: PASS (no GPU execution)");fflush(stdout);_Exit(0);}
 if(image_reset){
  FILE *f=wait_file("record-0.reset.held.response");magic(f,"OGCRH001");
  struct compute_host_reset_receipt h=reset_receipt(f);end(f);
  assert(!compute_host_abort_captured(session,&h,directory));
  f=wait_file("record-0.reset.ack.response");magic(f,"OGCRA001");
  struct compute_host_reset_receipt ack=reset_receipt(f);end(f);
  path(output,"record-0.reset.retire");assert(!compute_host_drain_reset(session,&ack,output));
  f=wait_file("record-0.reset.retired.response");magic(f,"OGCRD001");
  struct compute_host_reset_receipt done=reset_receipt(f);end(f);
  assert(!compute_host_observe_reset_retired(session,&done));retired=1;return 0;
 }
 FILE *f=wait_file("record-0.held.response");magic(f,expect_compute_failure()?"OGCFH001":"OGCMH001");struct compute_host_held_receipt h=held(f);end(f);
 path(output,"record-0.retire");assert(!(expect_compute_failure()?compute_host_drain_failed(session,&h,output):compute_host_drain_held(session,&h,output)));
 f=wait_file("record-0.retired.response");magic(f,expect_compute_failure()?"OGCFR001":"OGCMR001");struct compute_host_retired_receipt r={.held=held(f)};
 r.status=read32(f);r.irq=read32(f);r.irq_asserted=read32(f);r.external_drained=read32(f);end(f);
 assert(!(expect_compute_failure()?compute_host_observe_failed_retired(session,&r):compute_host_observe_retired(session,&r)));retired=1;return 0;
}

int cs_fixture_ioctl(int fd,unsigned long request,...)
{
 va_list ap;va_start(ap,request);void *arg=va_arg(ap,void *);va_end(ap);assert(fd==101 || fd>=20000);
 if(request==DRM_IOCTL_OPENGPU_GET_PARAM){struct drm_opengpu_param *p=arg;
  if(p->pad){errno=EINVAL;return -1;}uint64_t value=0;int error=compute_host_query(session,p->param,&value);
  if(!error)p->value=value;
  return result(error);}
 if(request==DRM_IOCTL_MODE_GETRESOURCES){errno=ENODEV;return -1;}
 if(request==DRM_IOCTL_OPENGPU_CONTEXT_CREATE){struct drm_opengpu_context *p=arg;
  if(p->flags || p->id){errno=EINVAL;return -1;}return result(compute_host_context_create(session,&p->id));}
 if(request==DRM_IOCTL_OPENGPU_CONTEXT_DESTROY){struct drm_opengpu_context *p=arg;
  if(p->flags){errno=EINVAL;return -1;}return result(compute_host_context_destroy(session,p->id));}
 if(request==DRM_IOCTL_OPENGPU_CREATE_BUFFER){struct drm_opengpu_create_buffer *p=arg;
  assert(next_bo<512 && p->width && p->height && p->bpp==32 && p->width<=UINT32_MAX/4-63);
  p->pitch=(p->width*4+63)&~63u;p->size=(uint64_t)p->pitch*p->height;
  uint64_t pages=(p->size+4095)&~UINT64_C(4095);assert(pages>=p->size);
  p->handle=next_bo++;p->offset=(uint64_t)p->handle*4096;
  bos[p->handle].data=compute_host_original_allocate(session,p->handle,pages);assert(bos[p->handle].data);
  bos[p->handle].bytes=p->size;return 0;}
 if(request==DRM_IOCTL_GEM_CLOSE){struct drm_gem_close *p=arg;assert(p->handle<next_bo && !bos[p->handle].closed);
  int error=compute_host_original_close(session,p->handle);if(!error)bos[p->handle].closed=1;return result(error);}
 if(request==DRM_IOCTL_OPENGPU_RESOURCE_BIND){int error=compute_host_bind(session,arg);
  if(!error && (image_case || image_publication_case || texture_publication_case)){const struct drm_opengpu_resource *b=arg;assert(b->slot<=OPENGPU_MAX_RESOURCE_BINDINGS);image_bindings[b->slot]=*b;}
  return result(error);}
 if(request==DRM_IOCTL_OPENGPU_RESOURCE_UNBIND){struct drm_opengpu_resource *p=arg;
  assert(p->context_id==compute_host_context_id(session));return result(compute_host_unbind(session,p->slot));}
 if(request==DRM_IOCTL_OPENGPU_COMPUTE_STORAGE){
  struct drm_opengpu_compute_storage copy=*(struct drm_opengpu_compute_storage *)arg;
  if(storage_case)storage_prepare_submission(&copy);
  if(publication_case)publication_prepare_submission(&copy);
  if(shared_publication_case)shared_publication_prepare_submission(&copy);
  if(dma_publication_case)dma_publication_prepare_submission(&copy);
  int error=compute_host_submit_storage(session,&copy);if(!error)submitted++;return result(error);}
 if(request==DRM_IOCTL_OPENGPU_COMPUTE_RESOURCES){struct drm_opengpu_compute_resources copy=*(struct drm_opengpu_compute_resources *)arg;
  assert(texture_publication_case && copy.view_count==1 && copy.views);
  texture_publication_prepare_submission(&copy.launch);
  memcpy(&accepted_texture_view,(const void *)(uintptr_t)copy.views,sizeof(accepted_texture_view));
  int error=compute_host_submit_resources(session,&copy);if(!error)submitted++;return result(error);}
 if(request==DRM_IOCTL_OPENGPU_COMPUTE_IMAGES){struct drm_opengpu_compute_images copy=*(struct drm_opengpu_compute_images *)arg;
  permission_views(copy.views);
  if(image_publication_case)image_publication_prepare_submission(&copy.launch);
  if(texture_publication_case){
   texture_publication_prepare_submission(&copy.launch);
   assert(!compute_host_align_texture_publication_command(session));
  }
  int error=compute_host_submit_images(session,&copy);
  if(!error){if(image_case || image_publication_case || texture_publication_case)memcpy(accepted_images,copy.views,sizeof(accepted_images));submitted++;}return result(error);}
 if(request==DRM_IOCTL_OPENGPU_COMPUTE_IMAGE_RESOURCES){struct drm_opengpu_compute_image_resources copy=*(struct drm_opengpu_compute_image_resources *)arg;
  permission_views(copy.images);
  if(image_publication_case)image_publication_prepare_submission(&copy.launch);
  if(texture_publication_case){
   texture_publication_prepare_submission(&copy.launch);
   assert(!compute_host_align_texture_publication_command(session));
  }
  if(p12_texture_coexist){
   assert(copy.texture_count==16 && copy.textures);
   p12_texture_prepare_submission(&copy.launch);
   memcpy(accepted_p12_texture_views,(const void *)(uintptr_t)copy.textures,sizeof(accepted_p12_texture_views));
  }
  int error=compute_host_submit_image_resources(session,&copy);
  if(!error){if(image_case || image_publication_case || texture_publication_case)memcpy(accepted_images,copy.images,sizeof(accepted_images));submitted++;}return result(error);}
 if(request==DRM_IOCTL_OPENGPU_COMPUTE_UNIFORM_RESOURCES){int error=compute_host_submit_uniforms(session,arg);
  if(!error){accepted_uniforms=*(const struct drm_opengpu_compute_uniform_resources *)arg;submitted++;}return result(error);}
 if(request==DRM_IOCTL_OPENGPU_COMPUTE_COUNTER_SOURCE_RESOURCES){
  assert(counter_case || counter_publication_case);
  if(counter_publication_case)counter_publication_prepare_submission(arg);
  if(p12_combined_coexist){
   const struct drm_opengpu_compute_counter_source_resources *source=arg;
   const struct drm_opengpu_compute_counter_resources *resources=&source->resources.resources;
   assert(resources->texture_count==16 && resources->textures && source->resources.defaults.bytes==P12_COMBINED_DEFAULT_COMPONENTS*4);
   p12_texture_prepare_submission(&resources->launch);
   memcpy(accepted_p12_texture_views,(const void *)(uintptr_t)resources->textures,sizeof(accepted_p12_texture_views));

   /* The rejected requests share the original context and resource set.
    * The successful submission below must still be the only kernel job. */
   struct drm_opengpu_compute_counter_source_resources wrong=*source;
   wrong.resources.resources.textures=(uintptr_t)accepted_p12_texture_views;
   unsigned slot=accepted_p12_texture_views[0].slot;
   accepted_p12_texture_views[0].slot=resources->bindings[0].slot;
   assert(compute_host_submit_counter_source(session,&wrong)==-EINVAL);
   accepted_p12_texture_views[0].slot=slot;

   wrong=*source;
   wrong.resources.resources.bindings[0].slot=slot;
   assert(compute_host_submit_counter_source(session,&wrong)==-EINVAL);

   wrong=*source;
   wrong.resources.resources.images[0].slot=slot;
   assert(compute_host_submit_counter_source(session,&wrong)==-EINVAL);

   const unsigned aliases[]={texture_handles[0],output_handles[0],image_handles[0]};
   for(unsigned i=0;i<sizeof(aliases)/sizeof(aliases[0]);i++){
    wrong=*source;
    wrong.source.offset=0;
    wrong.source.handle=aliases[i];
    assert(compute_host_submit_counter_source(session,&wrong)==-EINVAL);
   }
  }
  struct drm_opengpu_compute_counter_source_resources submit=*(const struct drm_opengpu_compute_counter_source_resources *)arg;
  if(p12_combined_coexist && image_permission_fault){
   assert(image_permission_fault==2);
   for(unsigned unit=0;unit<4;unit++){
    struct drm_opengpu_compute_image_view *view=&submit.resources.resources.images[unit];
    assert(view->flags==OPENGPU_COMPUTE_IMAGE_WRITE);
    view->flags=OPENGPU_COMPUTE_IMAGE_READ;
   }
  }
  int error=compute_host_submit_counter_source(session,&submit);
  if(!error){accepted_counter=submit;submitted++;}return result(error);}
 if(request==DRM_IOCTL_SYNCOBJ_CREATE){struct drm_syncobj_create *p=arg;assert(!p->flags && next_sync<3);p->handle=next_sync++;return 0;}
 if(request==DRM_IOCTL_SYNCOBJ_DESTROY)return 0;
 if(request==DRM_IOCTL_SYNCOBJ_WAIT){struct drm_syncobj_wait *p=arg;assert(p->count_handles==1);return wait_execution();}
 if(request==DRM_IOCTL_SYNCOBJ_HANDLE_TO_FD){struct drm_syncobj_handle *p=arg;assert(retired);p->fd=20000+p->handle;return 0;}
 if(request==SYNC_IOC_FILE_INFO){struct sync_file_info *p=arg;assert(retired);
  int32_t status=0;assert(!compute_host_completion_status(session,&status));p->status=status;return 0;}
 fprintf(stderr,"unsupported actual compute capture ioctl %lx\n",request);errno=EOPNOTSUPP;return -1;
}
int cs_fixture_dup(int fd){assert(fd==7);return 101;}
int cs_fixture_close(int fd){assert(fd==101 || fd>=20000);return 0;}
void *cs_fixture_mmap(void *address,size_t bytes,int prot,int flags,int fd,off_t offset)
{unsigned h=(unsigned)(offset/4096);assert(!address && prot==(PROT_READ|PROT_WRITE) && flags==MAP_SHARED && fd==101);
 assert(!(offset&4095) && h && h<next_bo && !bos[h].closed && bos[h].bytes==bytes);bos[h].maps++;return bos[h].data;}
int cs_fixture_munmap(void *address,size_t bytes)
{for(unsigned h=1;h<next_bo;h++)if(bos[h].data==address){assert(bytes==bos[h].bytes && bos[h].maps);bos[h].maps--;return 0;}assert(0);return -1;}

static struct pipe_resource *buffer(struct pipe_screen *s,unsigned bytes)
{struct pipe_resource t={0};t.target=PIPE_BUFFER;t.format=PIPE_FORMAT_R8_UNORM;t.width0=bytes;t.height0=t.depth0=t.array_size=1;
 t.bind=PIPE_BIND_SHADER_BUFFER|PIPE_BIND_CONSTANT_BUFFER;return s->resource_create(s,&t);}
static uint32_t uniform_value(unsigned slot,unsigned word)
{return (0x4ab319d7u+0x01020304u+slot*0x00718293u+word*0x9e3779b9u)^((slot+13u)<<(word%23));}
static void source_window(FILE *f,unsigned handle,uint64_t offset,unsigned bytes,unsigned binding)
{uint64_t physical,allocation;assert(!compute_host_original_physical(session,handle,&physical,&allocation));
 assert(offset<=allocation && bytes<=allocation-offset);
 fprintf(f,"{\"globalBinding\":%u,\"allocationPhysical\":%llu,\"allocationBytes\":%llu,\"offset\":%llu,\"bytes\":%u}",binding,
  (unsigned long long)physical,(unsigned long long)allocation,(unsigned long long)offset,bytes);}
static void check_loop_caps(struct pipe_screen *screen)
{
 uint64_t items=0,axis=0,block[3]={0},threads=0,shared=0,driver_shared=0;
 assert(!compute_host_query(session,OPENGPU_PARAM_COMPUTE_LOCAL_ITEMS,&items) && items==128);
 assert(!compute_host_query(session,OPENGPU_PARAM_COMPUTE_LOCAL_AXIS,&axis) && axis>=8);
 assert(screen->get_compute_param(screen,PIPE_SHADER_IR_TGSI,PIPE_COMPUTE_CAP_MAX_THREADS_PER_BLOCK,&threads)==sizeof(threads) &&
        threads==items);
 assert(screen->get_compute_param(screen,PIPE_SHADER_IR_TGSI,PIPE_COMPUTE_CAP_MAX_BLOCK_SIZE,block)==sizeof(block));
 for(unsigned d=0;d<3;d++)assert(block[d]==axis);
 assert(!screen->get_compute_param(screen,PIPE_SHADER_IR_NIR,PIPE_COMPUTE_CAP_MAX_BLOCK_SIZE,NULL));
 assert(!compute_host_query(session,OPENGPU_PARAM_COMPUTE_SHARED_BYTES,&driver_shared));
 assert(screen->get_compute_param(screen,PIPE_SHADER_IR_TGSI,PIPE_COMPUTE_CAP_MAX_LOCAL_SIZE,&shared)==sizeof(shared) &&
        shared==driver_shared);
 assert(screen->get_shader_param(screen,PIPE_SHADER_COMPUTE,PIPE_SHADER_CAP_MAX_SHADER_BUFFERS)==4);
 assert(screen->get_shader_param(screen,PIPE_SHADER_COMPUTE,PIPE_SHADER_CAP_MAX_INSTRUCTIONS)==512);
 if(dynamic_loop_case || indexed_uniform_loop_case){
  assert(screen->get_shader_param(screen,PIPE_SHADER_COMPUTE,PIPE_SHADER_CAP_MAX_CONST_BUFFERS)==13);
  assert(screen->get_shader_param(screen,PIPE_SHADER_COMPUTE,PIPE_SHADER_CAP_MAX_CONST_BUFFER0_SIZE)==4096);
 }
 assert(!screen->get_param(screen,PIPE_CAP_COMPUTE));
}
static void write_oracle(void)
{FILE *f=file("oracle.json","wx");assert(f);
 if(mixed_case)mixed_write_oracle(f);
 else if(storage_case)storage_write_oracle(f);
 else if(publication_case)publication_write_oracle(f);
 else if(image_publication_case)image_publication_write_oracle(f);
 else if(texture_publication_case)texture_publication_write_oracle(f);
 else if(counter_publication_case)counter_publication_write_oracle(f);
 else if(shared_publication_case)shared_publication_write_oracle(f);
 else if(dma_publication_case)dma_publication_write_oracle(f);
 else if(image_case)image_write_oracle(f);
 else if(p12_texture_coexist){p12_texture_write_oracle(f);}
 else if(p12_shared_coexist){
  struct compute_host_snapshot snap;assert(!compute_host_snapshot_get(session,&snap));
  fprintf(f,"{\"schema\":\"opengpu-compute-p12-shared-oracle-v1\",\"sourceCase\":\"p12-shared-coexist\",\"groups\":1,\"local\":128,\"viewBytes\":8192,\"guard\":3735928559,\"sharedBytes\":16384,\"context\":%u,\"kernargPhysical\":%llu,\"kernargBytes\":%llu,\"outputs\":[",
   snap.context,(unsigned long long)snap.kernarg_physical,(unsigned long long)snap.kernarg_bytes);
  for(unsigned i=0;i<4;i++){if(i)fputc(',',f);source_window(f,output_handles[i],0,8192,i);}
  fprintf(f,"],\"storageBindings\":[%u,%u,%u,%u]",counter_storage_bindings[0],counter_storage_bindings[1],counter_storage_bindings[2],counter_storage_bindings[3]);
  assert(snap.images && snap.image_bytes==1024 && snap.image_root);
  fputs(",\"images\":[",f);
  for(unsigned i=0;i<4;i++){if(i)fputc(',',f);source_window(f,image_handles[i],0,512,i);}
  fprintf(f,"],\"imageBindings\":[%u,%u,%u,%u],\"imageRoot\":%u,\"imagePhysical\":%llu,\"imageBytes\":%llu",
   counter_image_bindings[0],counter_image_bindings[1],counter_image_bindings[2],counter_image_bindings[3],snap.image_root,
   (unsigned long long)snap.image_physical,(unsigned long long)snap.image_bytes);
  fputs(",\"ubos\":[",f);
  for(unsigned i=0;i<12;i++){if(i)fputc(',',f);source_window(f,uniform_handles[i],0,16384,24+i);}
  fputs("],\"uboBindings\":[",f);
  for(unsigned i=0;i<12;i++){if(i)fputc(',',f);fprintf(f,"%u",24+i);}
  fprintf(f,"],\"uniformPhysical\":%llu}\n",(unsigned long long)snap.uniform_physical);
 }
 else if(p12_counter_coexist){
  const struct drm_opengpu_compute_counter_source *v=&accepted_counter.source;
  struct compute_host_snapshot snap;assert(!compute_host_snapshot_get(session,&snap));
  uint64_t physical,bytes;assert(!compute_host_original_physical(session,counter_handle,&physical,&bytes));
  assert(accepted_counter.version==1 && v->handle==counter_handle && v->offset==counter_offset && v->count==counter_count &&
   bytes==8192 && snap.counter_physical==physical+counter_offset && snap.counter_bytes==counter_count*4 && snap.counter_root);
  fprintf(f,"{\"schema\":\"opengpu-compute-p12-counter-oracle-v1\",\"sourceCase\":\"%s\",\"groups\":1,\"local\":128,\"viewBytes\":8192,\"guard\":3735928559,\"seed\":%u,\"count\":%u,\"counterHandle\":%u,\"context\":%u,\"descriptorPhysical\":%llu,\"descriptorRoot\":%u,\"kernargPhysical\":%llu,\"kernargBytes\":%llu,\"counter\":",
   counter_name,0xfffffffeu,counter_count,v->handle,snap.context,
   (unsigned long long)snap.counter_descriptor_physical,snap.counter_root,
   (unsigned long long)snap.kernarg_physical,(unsigned long long)snap.kernarg_bytes);
  source_window(f,counter_handle,counter_offset,counter_count*4,0);fputs(",\"outputs\":[",f);
  for(unsigned i=0;i<4;i++){if(i)fputc(',',f);source_window(f,output_handles[i],0,8192,i);}
  fprintf(f,"],\"storageBindings\":[%u,%u,%u,%u]",counter_storage_bindings[0],counter_storage_bindings[1],counter_storage_bindings[2],counter_storage_bindings[3]);
  assert(snap.images && snap.image_bytes==1024 && snap.image_root &&
   accepted_counter.resources.resources.resource_flags==(OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE|OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES));
  fputs(",\"images\":[",f);
  for(unsigned i=0;i<4;i++){if(i)fputc(',',f);source_window(f,image_handles[i],0,512,i);}
  fprintf(f,"],\"imageBindings\":[%u,%u,%u,%u],\"imageRoot\":%u,\"imagePhysical\":%llu,\"imageBytes\":%llu",
   counter_image_bindings[0],counter_image_bindings[1],counter_image_bindings[2],counter_image_bindings[3],snap.image_root,
   (unsigned long long)snap.image_physical,(unsigned long long)snap.image_bytes);
  fputs(",\"ubos\":[",f);
  for(unsigned i=0;i<12;i++){if(i)fputc(',',f);source_window(f,uniform_handles[i],0,16384,24+i);}
  fputs("],\"uboBindings\":[",f);
  for(unsigned i=0;i<12;i++){if(i)fputc(',',f);fprintf(f,"%u",24+i);}
  fprintf(f,"],\"uniformPhysical\":%llu",(unsigned long long)snap.uniform_physical);
  if(p12_combined_coexist)p12_texture_write_bindings(f,&snap);
  fputs("}\n",f);
 }else if(counter_case){
  const struct drm_opengpu_compute_counter_source *v=&accepted_counter.source;
  struct compute_host_snapshot snap;assert(!compute_host_snapshot_get(session,&snap));
  uint64_t physical,bytes;assert(!compute_host_original_physical(session,counter_handle,&physical,&bytes));
  assert(accepted_counter.version==1 && v->handle==counter_handle && v->offset==counter_offset && v->count==counter_count &&
   bytes==8192 && snap.counter_physical==physical+counter_offset && snap.counter_bytes==counter_count*4 && snap.counter_root);
  fprintf(f,"{\"schema\":\"opengpu-compute-counter-oracle-v1\",\"sourceCase\":\"%s\",\"groups\":%u,\"local\":4,\"viewBytes\":8192,\"guard\":3735928559,\"seed\":%u,\"index\":%u,\"count\":%u,\"counterHandle\":%u,\"context\":%u,\"descriptorPhysical\":%llu,\"descriptorRoot\":%u,\"kernargPhysical\":%llu,\"kernargBytes\":%llu,\"counter\":",counter_name,counter_dynamic?(counter_index?1u:2u):4u,counter_operation==2?(counter_dynamic?0u:1u):0xfffffffeu,counter_index,counter_count,v->handle,snap.context,
   (unsigned long long)snap.counter_descriptor_physical,snap.counter_root,
   (unsigned long long)snap.kernarg_physical,(unsigned long long)snap.kernarg_bytes);
  source_window(f,counter_handle,counter_offset,counter_count*4,0);fputs(",\"outputs\":[",f);
  for(unsigned i=0;i<4;i++){if(i)fputc(',',f);source_window(f,output_handles[i],0,8192,i);}
  fprintf(f,"],\"storageBindings\":[%u,%u,%u,%u]",counter_storage_bindings[0],counter_storage_bindings[1],counter_storage_bindings[2],counter_storage_bindings[3]);
  if(counter_coexist){
   assert(snap.images && snap.image_bytes==1024 && snap.image_root &&
    accepted_counter.resources.resources.resource_flags==(OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE|OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES));
   fputs(",\"images\":[",f);
   for(unsigned i=0;i<4;i++){if(i)fputc(',',f);source_window(f,image_handles[i],0,512,i);}
   fprintf(f,"],\"imageBindings\":[%u,%u,%u,%u],\"imageRoot\":%u,\"imagePhysical\":%llu,\"imageBytes\":%llu",
    counter_image_bindings[0],counter_image_bindings[1],counter_image_bindings[2],counter_image_bindings[3],snap.image_root,
    (unsigned long long)snap.image_physical,(unsigned long long)snap.image_bytes);
  }
  if(counter_dynamic){
   assert(accepted_counter.resources.active_mask==1 && !accepted_counter.resources.defaults.handle &&
    accepted_counter.resources.blocks[0].handle==index_uniform_handle && accepted_counter.resources.blocks[0].bytes==16);
   fputs(",\"indexUniform\":",f);source_window(f,index_uniform_handle,0,16,35);
   fprintf(f,",\"uniformRoot\":%u,\"uniformPhysical\":%llu,\"uniformBytes\":%llu",snap.uniform_root,
    (unsigned long long)snap.uniform_physical,(unsigned long long)snap.uniform_bytes);
  }
  fputs("}\n",f);
 }else if(address_case){
  const char *names[]={"shared-address-add","shared-address-cas-match","shared-address-cas-miss"};
  uint64_t physical,bytes;assert(!compute_host_original_physical(session,output_handles[0],&physical,&bytes));
  fprintf(f,"{\"schema\":\"opengpu-compute-shared-address-oracle-v1\",\"sourceCase\":\"%s\",\"outputPhysical\":%llu,\"allocationBytes\":%llu,\"viewBytes\":8192,\"groups\":4,\"local\":128,\"seedBase\":2701131776,\"groupShift\":16,\"addend\":257,\"replaceValue\":4275878552,\"compareXor\":%u,\"guard\":3735928559}\n",names[address_kind],(unsigned long long)physical,(unsigned long long)bytes,address_kind==2?1u:0u);
 }else if(dynamic_case){
  uint64_t physical,bytes;assert(!compute_host_original_physical(session,output_handles[0],&physical,&bytes));
  fprintf(f,"{\"schema\":\"opengpu-compute-shared-dynamic-oracle-v1\",\"outputPhysical\":%llu,\"allocationBytes\":%llu,\"viewBytes\":8192,\"groups\":4,\"local\":128,\"seed\":2147483648,\"operand\":4294967295,\"compare\":2147483648,\"operation\":%u,\"signed\":%s,\"guard\":3735928559}\n",(unsigned long long)physical,(unsigned long long)bytes,dynamic_operation,dynamic_signed?"true":"false");
 }else if(loop_case){
  fprintf(f,"{\"schema\":\"opengpu-compute-%s-oracle-v1\",\"sourceCase\":\"%s\",\"viewBytes\":8192,\"groups\":1,\"local\":%u,\"guard\":3735928559",
   loop_case,loop_case,loop_barrier_case?4u:128u);
  if(storage_loop_case){
   fputs(",\"input\":",f);
   source_window(f,output_handles[1],0,8192,1);
  }
  if(dynamic_loop_case || indexed_uniform_loop_case){
   unsigned bytes=indexed_uniform_loop_case?4096u:16u;
   assert(!accepted_uniforms.active_mask && accepted_uniforms.defaults.handle && accepted_uniforms.defaults.bytes==bytes);
   fputs(",\"defaults\":",f);
   source_window(f,accepted_uniforms.defaults.handle,accepted_uniforms.defaults.offset,bytes,0);
  }
  fputs(",\"output\":",f);
  source_window(f,output_handles[0],0,8192,0);
  fputs("}\n",f);
 }else if(uniform_case){
  assert(accepted_uniforms.active_mask==4095 && accepted_uniforms.defaults.handle && accepted_uniforms.defaults.bytes==4096);
  fputs("{\"schema\":\"opengpu-compute-uniform-oracle-v1\",\"viewBytes\":8192,\"groups\":4,\"local\":128,\"guard\":3735928559,\"defaults\":",f);
  source_window(f,accepted_uniforms.defaults.handle,accepted_uniforms.defaults.offset,4096,0);
  fputs(",\"blocks\":[",f);
  for(unsigned i=0;i<12;i++){if(i)fputc(',',f);source_window(f,uniform_handles[i],0,16384,24+i);}
  fputs("],\"outputs\":[",f);
  for(unsigned i=0;i<4;i++){if(i)fputc(',',f);source_window(f,output_handles[i],0,8192,i);}
  fputs("]}\n",f);
 }else{
  uint64_t physical,bytes;assert(!compute_host_original_physical(session,output_handles[0],&physical,&bytes));
  fprintf(f,"{\"schema\":\"opengpu-compute-shared-oracle-v1\",\"outputPhysical\":%llu,\"allocationBytes\":%llu,\"viewBytes\":8192,\"groups\":4,\"local\":128,\"seed\":2701131776,\"guard\":3735928559}\n",(unsigned long long)physical,(unsigned long long)bytes);
 }
 assert(!fclose(f));}
int main(int argc,char **argv)
{
 assert(argc>=5);uint64_t physical_base=0;
 p12_combined_coexist=!strcmp(argv[4],"p12-combined-coexist");
 storage_parse_case(argv[4]);publication_parse_case(argv[4]);
 image_publication_parse_case(argv[4]);texture_publication_parse_case(argv[4]);
 counter_publication_parse_case(argv[4]);shared_publication_parse_case(argv[4]);dma_publication_parse_case(argv[4]);image_parse_case(argv[4]);
 assert(!(storage_case && (publication_case || shared_publication_case || dma_publication_case)));
 assert(!(publication_case && (shared_publication_case || dma_publication_case)));
 assert(!(image_publication_case && (publication_case || image_case || storage_case || texture_publication_case || counter_publication_case || shared_publication_case || dma_publication_case)));
 assert(!(texture_publication_case && (publication_case || image_case || storage_case || image_publication_case || counter_publication_case || shared_publication_case || dma_publication_case)));
 assert(!(counter_publication_case && (publication_case || image_case || storage_case || image_publication_case || texture_publication_case || shared_publication_case || dma_publication_case || counter_case)));
 assert(!(shared_publication_case && (publication_case || image_case || storage_case || image_publication_case || texture_publication_case || counter_publication_case || counter_case || dma_publication_case)));
 assert(!(dma_publication_case && (publication_case || image_case || storage_case || image_publication_case || texture_publication_case || counter_publication_case || shared_publication_case || counter_case)));
 for(int i=5;i<argc;i++){
  if(!strcmp(argv[i],"--capture-only")){assert(!capture_only && !expect_compute_failure() && !image_reset);capture_only=1;}
  else if(!strcmp(argv[i],"--expect-image-source-fault")){assert(!capture_only && !expect_compute_failure() && !image_reset);expect_image_source_fault=1;}
  else if(!strcmp(argv[i],"--expect-image-write-fault")){
   assert(!capture_only && !expect_compute_failure() && !image_reset && image_case && image_mode==6);
   expect_image_write_fault=1;
  }
  else if(!strcmp(argv[i],"--expect-storage-fault")){
   assert(!capture_only && !expect_compute_failure() && !image_reset && storage_case &&
    storage_index!=14 && storage_index!=15 && ++i<argc);
   expect_storage_fault=!strcmp(argv[i],"read")?1:!strcmp(argv[i],"write")?2:0;
   assert(expect_storage_fault);
  }
  else if(!strcmp(argv[i],"--expect-image-permission-fault")){
   assert(!capture_only && !expect_compute_failure() && !image_reset && ++i<argc);
   image_permission_fault=!strcmp(argv[i],"read")?1:!strcmp(argv[i],"write")?2:0;
   assert(image_permission_fault && (!p12_combined_coexist || image_permission_fault==2));
  }
  else if(!strcmp(argv[i],"--expect-image-reset")){
   assert(!capture_only && !expect_compute_failure() && !image_reset && (p12_combined_coexist || !strcmp(argv[4],"image-atomic-buffer-r32f")) && ++i<argc);
   image_reset=!strcmp(argv[i],"read")?1:!strcmp(argv[i],"write")?2:0;assert(image_reset);
  }
  else if(!strcmp(argv[i],"--physical-base")){
   assert(!physical_base && ++i<argc);const char *v=argv[i];assert(*v);
   for(;*v;v++){assert(*v>='0' && *v<='9');unsigned digit=(unsigned)(*v-'0');
    assert(physical_base<=(UINT32_MAX-digit)/10u);physical_base=physical_base*10+digit;}
   assert(physical_base && !(physical_base&4095));
  }else assert(!"unknown compute host option");
 }
 assert(!physical_base || (!capture_only && ((image_case && image_mode==6) || !strcmp(argv[4],"image-atomic-buffer-r32f") ||
  p12_combined_coexist || publication_case || image_publication_case || texture_publication_case || counter_publication_case || shared_publication_case || dma_publication_case ||
  (storage_case && storage_index!=14 && storage_index!=15))));
 assert(!expect_compute_failure() || expect_storage_fault || expect_image_write_fault || (p12_combined_coexist && image_permission_fault==2) ||
  !strcmp(argv[4],"image-atomic-buffer-r32f"));
 host_timeout_seconds=compute_host_timeout_seconds();assert(host_timeout_seconds);
 storage_loop_case=!strcmp(argv[4],"storage-loop") || !strcmp(argv[4],"masked-storage-loop");
 dynamic_loop_case=!strcmp(argv[4],"dynamic-loop");
 loop_barrier_case=!strcmp(argv[4],"loop-branch");
 indexed_uniform_loop_case=!strcmp(argv[4],"indexed-uniform-loop");
 if(storage_loop_case || dynamic_loop_case || loop_barrier_case || indexed_uniform_loop_case || !strcmp(argv[4],"nested-loop") ||
    !strcmp(argv[4],"constant-loop") || !strcmp(argv[4],"loop-break") ||
    !strcmp(argv[4],"continue-loop"))loop_case=argv[4];
 uniform_case=!strcmp(argv[4],"uniforms");
 storage_view_failure=storage_case && (storage_index==14 || storage_index==15);
 mixed_parse_case(argv[4]);
 const char *counter_names[]={"counter-increment","counter-read","counter-decrement",
  "counter-dynamic-increment","counter-dynamic-read","counter-dynamic-decrement",
  "counter-dynamic-all-increment","counter-dynamic-all-read","counter-dynamic-all-decrement"};
 for(unsigned i=0;i<9;i++)if(!strcmp(argv[4],counter_names[i])){counter_case=1;counter_operation=i%3;counter_dynamic=i>=3;counter_index=i>=6?0:i>=3?4:7;counter_name=counter_names[i];}
 if(!strcmp(argv[4],"counter-scalar")){counter_case=1;counter_index=0;counter_offset=8188;counter_count=1;counter_name="counter-scalar";}
 if(!strcmp(argv[4],"counter-coexist")){counter_case=counter_coexist=1;counter_index=7;counter_name="counter-coexist";}
 if(p12_combined_coexist || !strcmp(argv[4],"p12-counter-coexist")){
  counter_case=p12_counter_coexist=1;counter_offset=8192-8*4;counter_name=argv[4];
 }
 if(!strcmp(argv[4],"p12-shared-coexist")){p12_shared_coexist=1;}
 if(!strcmp(argv[4],"p12-texture-coexist")){p12_texture_coexist=1;}
 for(unsigned variant=0;variant<16;variant++){
  const unsigned signed_operations[]={0,1,2,3,4,9};unsigned op=variant<10?variant:signed_operations[variant-10];
  char name[64];assert(snprintf(name,sizeof(name),variant<10?"shared-dynamic-%u":"shared-dynamic-i-%u",op)>0);
  if(!strcmp(argv[4],name)){dynamic_case=1;dynamic_operation=op;dynamic_signed=variant>=10 || op==5 || op==6;}
 }
 const char *address_names[]={"shared-address-add","shared-address-cas-match","shared-address-cas-miss"};
 for(unsigned i=0;i<3;i++)if(!strcmp(argv[4],address_names[i])){address_case=1;address_kind=i;}
 assert(storage_case || publication_case || image_publication_case || texture_publication_case || counter_publication_case || shared_publication_case || dma_publication_case || image_case || counter_case || loop_case || uniform_case || dynamic_case || address_case || p12_shared_coexist || p12_texture_coexist || !strcmp(argv[4],"shared"));
 directory=argv[2];FILE *f=fopen(argv[1],"rb");assert(f);
 char wire[8];assert(fread(wire,1,8,f)==8 && (!memcmp(wire,"OGCP0001",8) ||
  !memcmp(wire,"OGCP0002",8) || !memcmp(wire,"OGCP0003",8)));
 unsigned version=wire[7]-'0',count=read32(f);
 if(counter_case || counter_publication_case || shared_publication_case || dma_publication_case || image_case || storage_case || publication_case || image_publication_case || p12_texture_coexist || texture_publication_case || p12_shared_coexist)assert(version==3 && count==112);
 assert((version==3 && (count==104 || count==112)) || ((version==1 || version==2) && count==96));
 struct compute_host_profile_word *words=calloc(count+10,sizeof(*words));assert(words);
 for(unsigned i=0;i<count;i++){words[i].address=read32(f);words[i].value=read32(f);}
 assert(read32(f)==10);for(unsigned i=count;i<count+10;i++){words[i].address=read32(f);words[i].value=read32(f);}end(f);
 if(physical_base)assert(!compute_host_allocation_base(physical_base));
 session=compute_host_open(words,count+10);free(words);assert(session);
 if(storage_case){uint64_t features=0;assert(!compute_host_query(session,OPENGPU_PARAM_DRIVER_FEATURES,&features) &&
  (features&OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_ATOMICS));}
 if(counter_case || counter_publication_case){uint64_t features=0;assert(!compute_host_query(session,OPENGPU_PARAM_DRIVER_FEATURES,&features) &&
  (features&(OPENGPU_DRIVER_FEATURE_COMPUTE_ATOMIC_COUNTERS|OPENGPU_DRIVER_FEATURE_COMPUTE_COUNTER_SOURCE))==
   (OPENGPU_DRIVER_FEATURE_COMPUTE_ATOMIC_COUNTERS|OPENGPU_DRIVER_FEATURE_COMPUTE_COUNTER_SOURCE));}
 if(dynamic_case || address_case){uint64_t features=0;assert((version==2 || version==3) && !compute_host_query(session,OPENGPU_PARAM_DRIVER_FEATURES,&features) &&
  (features&OPENGPU_DRIVER_FEATURE_COMPUTE_SHARED_ATOMICS));}
 struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
 if(loop_case)check_loop_caps(s);
 char dump[4096];path(dump,"actual-shared");void *frontend=(counter_case || counter_publication_case)?cs_frontend_compile_atomic_counters(argv[3],dump):
  (loop_case || image_case || storage_case || publication_case || image_publication_case || texture_publication_case || shared_publication_case || dma_publication_case || p12_shared_coexist || p12_texture_coexist)?cs_frontend_compile_buffers(argv[3],dump):cs_frontend_compile(argv[3],dump);assert(frontend);
 {
  unsigned expected_shared=0;
  if(shared_publication_case && shared_publication_index==1)expected_shared=128; /* dual own-stripe */
  else if(p12_combined_coexist || !(loop_case || uniform_case || counter_case || counter_publication_case || shared_publication_case || dma_publication_case || image_case || storage_case || publication_case || image_publication_case || texture_publication_case || p12_texture_coexist))expected_shared=16384;
  assert(cs_frontend_shared_bytes(frontend)==expected_shared);
 }
 struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=cs_frontend_tokens(frontend),.req_local_mem=cs_frontend_shared_bytes(frontend)};
 void *cs=p->create_compute_state(p,&state);
 if(!cs){fprintf(stderr,"create_compute_state failed errno=%d req_local=%u case=%s\n",errno,(unsigned)state.req_local_mem,argv[4]);abort();}
 p->bind_compute_state(p,cs);
 struct pipe_resource *output[4];
 for(unsigned binding=0;binding<4;binding++){output[binding]=mixed_case && !binding?mixed_target_buffer(s):buffer(s,8192);assert(output[binding]);
  output_handles[binding]=pipe_opengpu_resource_handle(opengpu_resource(output[binding])->gpu);
  uint32_t *data=pipe_opengpu_resource_map(opengpu_resource(output[binding])->gpu);for(unsigned i=0;i<2048;i++)data[i]=0xdeadbeefu;}
 if(storage_loop_case){
  uint32_t *input=pipe_opengpu_resource_map(opengpu_resource(output[1])->gpu);
  for(unsigned lane=0;lane<128;lane++)input[lane]=uniform_value(1,lane);
 }
 cs_frontend_storage(frontend,p,output);
 if(dynamic_loop_case){
  const uint32_t defaults[4]={6};
  cs_frontend_defaults(frontend,p,defaults,4);
 }else if(indexed_uniform_loop_case){
  uint32_t defaults[1024];
  for(unsigned word=0;word<1024;word++)defaults[word]=uniform_value(0,word);
  cs_frontend_defaults(frontend,p,defaults,1024);
 }
 if(storage_case)storage_setup(frontend,output);
 if(publication_case)publication_setup(frontend,output);
 if(image_publication_case)image_publication_setup(s,p,frontend,output);
 if(texture_publication_case)texture_publication_setup(s,p,frontend,output);
 if(p12_texture_coexist || p12_combined_coexist)p12_texture_setup(s,p,frontend);
 if(counter_publication_case)counter_publication_setup(s,p,frontend,output);
 if(shared_publication_case)shared_publication_setup(frontend,output);
 if(dma_publication_case)dma_publication_setup(frontend,output);
 if(mixed_case)mixed_setup(s,p,frontend,output);
 else if(image_case)image_setup(s,p,frontend);
 if(counter_case || p12_shared_coexist || p12_texture_coexist)for(unsigned i=0;i<4;i++)counter_storage_bindings[i]=cs_frontend_storage_binding(frontend,i);
 if(counter_coexist || p12_counter_coexist || p12_shared_coexist || p12_texture_coexist){
  struct pipe_resource *images[4];struct pipe_image_view views[4]={0};
  for(unsigned i=0;i<4;i++){
   struct pipe_resource image={.target=PIPE_BUFFER,.format=PIPE_FORMAT_R32_UINT,
    .width0=4096,.height0=1,.depth0=1,.array_size=1,.bind=PIPE_BIND_SHADER_IMAGE|PIPE_BIND_SHADER_BUFFER};
   images[i]=s->resource_create(s,&image);assert(images[i]);
   image_handles[i]=pipe_opengpu_resource_handle(opengpu_resource(images[i])->gpu);
   memset(pipe_opengpu_resource_map(opengpu_resource(images[i])->gpu),0x69,4096);
  }
  for(unsigned native=0;native<4;native++){
   unsigned api=cs_frontend_image_binding(frontend,native);assert(api<4);counter_image_bindings[native]=api;
   views[native]=(struct pipe_image_view){.resource=images[api],.format=PIPE_FORMAT_R32_UINT,
    .access=PIPE_IMAGE_ACCESS_WRITE,.shader_access=PIPE_IMAGE_ACCESS_WRITE};
   views[native].u.buf.size=512;
  }
  p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,4,0,views);
 }
 struct pipe_resource *ub[36]={0};unsigned offsets[36]={0},sizes[36]={0};
 if(uniform_case){
  assert(cs_frontend_default_components(frontend)==1024);uint32_t defaults[1024];
  for(unsigned w=0;w<1024;w++)defaults[w]=uniform_value(0,w);
  cs_frontend_defaults(frontend,p,defaults,1024);
  for(unsigned i=0;i<12;i++){
   ub[24+i]=buffer(s,16384);assert(ub[24+i]);sizes[24+i]=16384;
   uniform_handles[i]=pipe_opengpu_resource_handle(opengpu_resource(ub[24+i])->gpu);
   uint32_t *data=pipe_opengpu_resource_map(opengpu_resource(ub[24+i])->gpu);
   for(unsigned w=0;w<4096;w++)data[w]=uniform_value(i+1,w);
  }
  cs_frontend_ubos(frontend,p,ub,offsets,sizes);
 }
 if(p12_combined_coexist){
  uint32_t defaults[P12_COMBINED_DEFAULT_COMPONENTS];
  assert(cs_frontend_default_components(frontend)==P12_COMBINED_DEFAULT_COMPONENTS);
  for(unsigned w=0;w<P12_COMBINED_DEFAULT_COMPONENTS;w++)defaults[w]=uniform_value(0,w);
  cs_frontend_defaults(frontend,p,defaults,P12_COMBINED_DEFAULT_COMPONENTS);
 }
 if(counter_dynamic){
  ub[35]=buffer(s,4096);assert(ub[35]);sizes[35]=16;
  index_uniform_handle=pipe_opengpu_resource_handle(opengpu_resource(ub[35])->gpu);
  uint32_t *data=pipe_opengpu_resource_map(opengpu_resource(ub[35])->gpu);assert(data);memset(data,0x6d,4096);
  data[0]=counter_index;data[1]=0x13579bdfu;data[2]=0x2468ace0u;data[3]=0x11223344u;
  cs_frontend_ubos(frontend,p,ub,offsets,sizes);
 }
 if(p12_counter_coexist || p12_shared_coexist){
  for(unsigned i=0;i<12;i++){
   ub[24+i]=buffer(s,16384);assert(ub[24+i]);sizes[24+i]=16384;
   uniform_handles[i]=pipe_opengpu_resource_handle(opengpu_resource(ub[24+i])->gpu);
   uint32_t *data=pipe_opengpu_resource_map(opengpu_resource(ub[24+i])->gpu);
   for(unsigned w=0;w<4096;w++)data[w]=uniform_value(i+1,w);
  }
  cs_frontend_ubos(frontend,p,ub,offsets,sizes);
 }
 if(counter_case){
  struct pipe_resource *counter=buffer(s,8192);assert(counter);
  counter_handle=pipe_opengpu_resource_handle(opengpu_resource(counter)->gpu);
  uint8_t *data=pipe_opengpu_resource_map(opengpu_resource(counter)->gpu);assert(data);memset(data,0x5a,8192);
  uint32_t *words=(uint32_t *)(data+counter_offset);
  uint32_t seed=counter_operation==2?(counter_dynamic?0u:1u):0xfffffffeu;
  for(unsigned i=0;i<counter_count;i++)words[i]=seed+(i+(counter_dynamic?1u:0u))*0x10203u;
  if(!p12_counter_coexist)words[counter_dynamic?7:counter_index]=seed;
  cs_frontend_counters(frontend,p,counter,counter_offset,counter_count*4);
  /* Gallium and the accepted production job retain this original GEM until
   * the same externally observed retirement/context cleanup as all outputs. */
 }
 struct pipe_grid_info grid={.grid={4,1,1},.block={128,1,1}};
 if(counter_case){grid.block[0]=4;if(counter_dynamic)grid.grid[0]=counter_index?1:2;}
 if(p12_counter_coexist || p12_shared_coexist || p12_texture_coexist){grid.grid[0]=1;grid.block[0]=128;}
 if(image_case){grid.block[0]=4;grid.grid[0]=2;}
 if(storage_case){grid.block[0]=4;grid.grid[0]=2;}
 if(publication_case || image_publication_case || texture_publication_case || counter_publication_case || shared_publication_case || dma_publication_case){
  grid.block[0]=shared_publication_case && shared_publication_index==1?16:1;grid.grid[0]=1;}
 if(loop_case)grid.grid[0]=1;
 if(uniform_case || loop_case){grid.block[0]=8;grid.block[1]=4;grid.block[2]=4;}
 if(loop_barrier_case){grid.block[0]=4;grid.block[1]=1;grid.block[2]=1;}
 p->launch_grid(p,&grid);assert(submitted==1 && retired);
 marker("cleanup_wait","actual compute retired; independent byte/source/guard authorization required\n");
 f=wait_file("BEGIN_CLEANUP");magic(f,"OGCMBA01");assert(read32(f)==1);end(f);
 assert(!compute_host_begin_cleanup(session,directory));
 assert(!(image_reset?compute_host_finish_cancelled_observed(session):
  expect_compute_failure()?compute_host_finish_failed_observed(session):compute_host_finish_observed(session)));
 assert(!compute_host_end_cleanup(session));
 f=wait_file("FINAL_RESPONSE");magic(f,"OGCMOK01");assert(read32(f)==1);end(f);
 /* End this dedicated source process after the actual Linux file/context
  * ownership is retired. A generic Gallium destructor would enqueue later
  * publication invalidates; those are outside this single-kernel capture.
  * It must not run against an already shut-down MMU or be faked successful.
  * Compiler/Gallium CPU allocations end with this isolated process. */
 cs_frontend_destroy(frontend);
 assert(!compute_host_destroy(session));session=NULL;
 puts("actual Mesa/Linux compute owner lifecycle: PASS (DUT bytes verified externally)");return 0;
}
