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
static int capture_only,uniform_case,dynamic_case;
static unsigned dynamic_operation,dynamic_signed;
static unsigned host_timeout_seconds;
static unsigned output_handles[4],uniform_handles[12];
static struct drm_opengpu_compute_uniform_resources accepted_uniforms;
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
 FILE *f=wait_file("record-0.held.response");magic(f,"OGCMH001");struct compute_host_held_receipt h=held(f);end(f);
 path(output,"record-0.retire");assert(!compute_host_drain_held(session,&h,output));
 f=wait_file("record-0.retired.response");magic(f,"OGCMR001");struct compute_host_retired_receipt r={.held=held(f)};
 r.status=read32(f);r.irq=read32(f);r.irq_asserted=read32(f);r.external_drained=read32(f);end(f);
 assert(!compute_host_observe_retired(session,&r));retired=1;return 0;
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
 if(request==DRM_IOCTL_OPENGPU_RESOURCE_BIND)return result(compute_host_bind(session,arg));
 if(request==DRM_IOCTL_OPENGPU_RESOURCE_UNBIND){struct drm_opengpu_resource *p=arg;
  assert(p->context_id==compute_host_context_id(session));return result(compute_host_unbind(session,p->slot));}
 if(request==DRM_IOCTL_OPENGPU_COMPUTE_STORAGE){int error=compute_host_submit_storage(session,arg);if(!error)submitted++;return result(error);}
 if(request==DRM_IOCTL_OPENGPU_COMPUTE_UNIFORM_RESOURCES){int error=compute_host_submit_uniforms(session,arg);
  if(!error){accepted_uniforms=*(const struct drm_opengpu_compute_uniform_resources *)arg;submitted++;}return result(error);}
 if(request==DRM_IOCTL_SYNCOBJ_CREATE){struct drm_syncobj_create *p=arg;assert(!p->flags && next_sync<3);p->handle=next_sync++;return 0;}
 if(request==DRM_IOCTL_SYNCOBJ_DESTROY)return 0;
 if(request==DRM_IOCTL_SYNCOBJ_WAIT){struct drm_syncobj_wait *p=arg;assert(p->count_handles==1);return wait_execution();}
 if(request==DRM_IOCTL_SYNCOBJ_HANDLE_TO_FD){struct drm_syncobj_handle *p=arg;assert(retired);p->fd=20000+p->handle;return 0;}
 if(request==SYNC_IOC_FILE_INFO){struct sync_file_info *p=arg;assert(retired);p->status=1;return 0;}
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
static void write_oracle(void)
{FILE *f=file("oracle.json","wx");assert(f);
 if(dynamic_case){
  uint64_t physical,bytes;assert(!compute_host_original_physical(session,output_handles[0],&physical,&bytes));
  fprintf(f,"{\"schema\":\"opengpu-compute-shared-dynamic-oracle-v1\",\"outputPhysical\":%llu,\"allocationBytes\":%llu,\"viewBytes\":8192,\"groups\":4,\"local\":128,\"seed\":2147483648,\"operand\":4294967295,\"compare\":2147483648,\"operation\":%u,\"signed\":%s,\"guard\":3735928559}\n",(unsigned long long)physical,(unsigned long long)bytes,dynamic_operation,dynamic_signed?"true":"false");
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
 assert(argc==5 || argc==6);capture_only=argc==6 && !strcmp(argv[5],"--capture-only");assert(argc==5 || capture_only);
 host_timeout_seconds=compute_host_timeout_seconds();assert(host_timeout_seconds);
 uniform_case=!strcmp(argv[4],"uniforms");
 for(unsigned variant=0;variant<16;variant++){
  const unsigned signed_operations[]={0,1,2,3,4,9};unsigned op=variant<10?variant:signed_operations[variant-10];
  char name[64];assert(snprintf(name,sizeof(name),variant<10?"shared-dynamic-%u":"shared-dynamic-i-%u",op)>0);
  if(!strcmp(argv[4],name)){dynamic_case=1;dynamic_operation=op;dynamic_signed=variant>=10 || op==5 || op==6;}
 }
 assert(uniform_case || dynamic_case || !strcmp(argv[4],"shared"));
 directory=argv[2];FILE *f=fopen(argv[1],"rb");assert(f);
 char wire[8];assert(fread(wire,1,8,f)==8 && (!memcmp(wire,"OGCP0001",8) ||
  !memcmp(wire,"OGCP0002",8) || !memcmp(wire,"OGCP0003",8)));
 unsigned version=wire[7]-'0',count=read32(f);
 assert((version==3 && (count==104 || count==112)) || ((version==1 || version==2) && count==96));
 struct compute_host_profile_word *words=calloc(count+10,sizeof(*words));assert(words);
 for(unsigned i=0;i<count;i++){words[i].address=read32(f);words[i].value=read32(f);}
 assert(read32(f)==10);for(unsigned i=count;i<count+10;i++){words[i].address=read32(f);words[i].value=read32(f);}end(f);
 session=compute_host_create(1,words,count+10);free(words);assert(session);
 if(dynamic_case){uint64_t features=0;assert((version==2 || version==3) && !compute_host_query(session,OPENGPU_PARAM_DRIVER_FEATURES,&features) &&
  (features&OPENGPU_DRIVER_FEATURE_COMPUTE_SHARED_ATOMICS));}
 struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
 char dump[4096];path(dump,"actual-shared");void *frontend=cs_frontend_compile(argv[3],dump);assert(frontend);
 assert(cs_frontend_shared_bytes(frontend)==(uniform_case?0u:16384u));
 struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=cs_frontend_tokens(frontend),.req_local_mem=cs_frontend_shared_bytes(frontend)};
 void *cs=p->create_compute_state(p,&state);assert(cs);p->bind_compute_state(p,cs);
 struct pipe_resource *output[4];
 for(unsigned binding=0;binding<4;binding++){output[binding]=buffer(s,8192);assert(output[binding]);
  output_handles[binding]=pipe_opengpu_resource_handle(opengpu_resource(output[binding])->gpu);
  uint32_t *data=pipe_opengpu_resource_map(opengpu_resource(output[binding])->gpu);for(unsigned i=0;i<2048;i++)data[i]=0xdeadbeefu;}
 cs_frontend_storage(frontend,p,output);
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
 struct pipe_grid_info grid={.grid={4,1,1},.block={128,1,1}};
 if(uniform_case){grid.block[0]=8;grid.block[1]=4;grid.block[2]=4;}
 p->launch_grid(p,&grid);assert(submitted==1 && retired);
 marker("cleanup_wait","actual compute retired; independent byte/source/guard authorization required\n");
 f=wait_file("BEGIN_CLEANUP");magic(f,"OGCMBA01");assert(read32(f)==1);end(f);
 assert(!compute_host_begin_cleanup(session,directory));assert(!compute_host_finish_observed(session));assert(!compute_host_end_cleanup(session));
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
