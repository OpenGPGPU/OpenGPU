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
static int capture_only;
static uint32_t read32(FILE *f)
{uint32_t v=0;for(unsigned n=0;n<4;n++){int b=fgetc(f);assert(b!=EOF);v|=(uint32_t)b<<(8*n);}return v;}
static uint64_t read64(FILE *f){uint64_t lo=read32(f);return lo|((uint64_t)read32(f)<<32);}
static FILE *file(const char *name,const char *mode)
{char path[4096];assert(snprintf(path,sizeof(path),"%s/%s",directory,name)>0);return fopen(path,mode);}
static FILE *wait_file(const char *name)
{struct timespec start,now,pause={.tv_nsec=10000000};assert(!clock_gettime(CLOCK_MONOTONIC,&start));
 for(;;){FILE *f=file(name,"rb");if(f)return f;assert(errno==ENOENT);
  FILE *error=file("error","rb");assert(!error && errno==ENOENT);
  assert(!clock_gettime(CLOCK_MONOTONIC,&now) && now.tv_sec-start.tv_sec<300);
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
 if(request==DRM_IOCTL_OPENGPU_COMPUTE_UNIFORM_RESOURCES){int error=compute_host_submit_uniforms(session,arg);if(!error)submitted++;return result(error);}
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
int main(int argc,char **argv)
{
 assert(argc==4 || argc==5);capture_only=argc==5 && !strcmp(argv[4],"--capture-only");assert(argc==4 || capture_only);
 directory=argv[2];FILE *f=fopen(argv[1],"rb");assert(f);magic(f,"OGCP0001");assert(read32(f)==96);
 struct compute_host_profile_word words[106];for(unsigned i=0;i<96;i++){words[i].address=read32(f);words[i].value=read32(f);}
 assert(read32(f)==10);for(unsigned i=96;i<106;i++){words[i].address=read32(f);words[i].value=read32(f);}end(f);
 session=compute_host_create(1,words,106);assert(session);
 struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
 char dump[4096];path(dump,"actual-shared");void *frontend=cs_frontend_compile(argv[3],dump);assert(frontend);
 assert(cs_frontend_shared_bytes(frontend)==16384);
 struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=cs_frontend_tokens(frontend),.req_local_mem=cs_frontend_shared_bytes(frontend)};
 void *cs=p->create_compute_state(p,&state);assert(cs);p->bind_compute_state(p,cs);
 struct pipe_resource *output[4];
 for(unsigned binding=0;binding<4;binding++){output[binding]=buffer(s,8192);assert(output[binding]);
  uint32_t *data=pipe_opengpu_resource_map(opengpu_resource(output[binding])->gpu);for(unsigned i=0;i<2048;i++)data[i]=0xdeadbeefu;}
 cs_frontend_storage(frontend,p,output);
 unsigned handle=pipe_opengpu_resource_handle(opengpu_resource(output[0])->gpu);uint64_t physical,bytes;
 assert(!compute_host_original_physical(session,handle,&physical,&bytes));
 f=file("oracle.json","wx");assert(f);fprintf(f,"{\"schema\":\"opengpu-compute-shared-oracle-v1\",\"outputPhysical\":%llu,\"allocationBytes\":%llu,\"viewBytes\":8192,\"groups\":4,\"local\":128,\"seed\":2701131776,\"guard\":3735928559}\n",(unsigned long long)physical,(unsigned long long)bytes);assert(!fclose(f));
 struct pipe_grid_info grid={.grid={4,1,1},.block={128,1,1}};p->launch_grid(p,&grid);assert(submitted==1 && retired);
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
