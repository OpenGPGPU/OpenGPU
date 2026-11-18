/* Original production opengpu_compute() caller for RV32 scalar word AMOs.
 * Only OS/GEM transport is modeled. Actual Linux admission/MMU and external
 * DUT held/retired/byte/cleanup receipts own every execution transition. */
#define _POSIX_C_SOURCE 200809L
#include <assert.h>
#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>
#include <drm/drm.h>
#include <linux/sync_file.h>
#include "opengpu.h"
#include "../../driver/tests/opengpu_compute_host_fixture.h"

static struct compute_host_session *session;
static struct {void *data;uint64_t bytes;unsigned mapped,closed;} bos[16];
static unsigned next_bo=1,submitted,retired,capture_only,timeout_seconds;
static const char *directory,*source_case;
static struct opengpu_buffer shader,kernarg;
static size_t program_bytes;

static void path(char out[4096],const char *name)
{int n=snprintf(out,4096,"%s/%s",directory,name);assert(n>0 && n<4096);}
static FILE *file(const char *name,const char *mode)
{char out[4096];path(out,name);FILE *f=fopen(out,mode);assert(f);return f;}
static uint32_t read32(FILE *f)
{uint32_t v=0;for(unsigned n=0;n<4;n++){int b=fgetc(f);assert(b!=EOF);v|=(uint32_t)b<<(8*n);}return v;}
static uint64_t read64(FILE *f)
{uint64_t lo=read32(f);return lo|((uint64_t)read32(f)<<32);}
static void magic(FILE *f,const char *value)
{char m[8];assert(fread(m,1,8,f)==8 && !memcmp(m,value,8));}
static void end(FILE *f)
{assert(fgetc(f)==EOF && !ferror(f) && !fclose(f));}
static void marker(const char *name,const char *value)
{char out[4096],tmp[4096];path(out,name);int n=snprintf(tmp,sizeof(tmp),"%s.tmp",out);assert(n>0 && n<(int)sizeof(tmp));
 FILE *f=fopen(tmp,"wx");assert(f && fputs(value,f)>=0 && !fclose(f));assert(!link(tmp,out) && !unlink(tmp));}
static FILE *wait_file(const char *name)
{struct timespec start,now,pause={.tv_nsec=10000000};assert(!clock_gettime(CLOCK_MONOTONIC,&start));
 for(;;){char out[4096];path(out,name);FILE *f=fopen(out,"rb");if(f)return f;assert(errno==ENOENT);
  path(out,"error");f=fopen(out,"rb");assert(!f && errno==ENOENT);
  assert(!clock_gettime(CLOCK_MONOTONIC,&now) && now.tv_sec-start.tv_sec<(time_t)timeout_seconds);
  assert(!nanosleep(&pause,NULL) || errno==EINTR);}}
static struct compute_host_held_receipt held(FILE *f)
{struct compute_host_held_receipt h;h.command_id=read32(f);h.completion=read32(f);h.processed_bytes=read64(f);
 h.legacy_status=read32(f);h.status=read32(f);h.irq=read32(f);h.irq_asserted=read32(f);return h;}
static int result(int error)
{if(error){errno=-error;return -1;}return 0;}

static void window(FILE *f,const struct opengpu_buffer *buffer)
{uint64_t physical,bytes;assert(!compute_host_original_physical(session,buffer->handle,&physical,&bytes));
 fprintf(f,"{\"handle\":%u,\"allocationPhysical\":%llu,\"allocationBytes\":%llu,\"offset\":0,\"bytes\":%llu}",
  buffer->handle,(unsigned long long)physical,(unsigned long long)bytes,(unsigned long long)buffer->size);}
static void write_source(void)
{struct compute_host_snapshot snap;assert(!compute_host_snapshot_get(session,&snap));
 assert(snap.code_bytes==shader.size && !snap.storage_bytes && !snap.uniform_bytes && !snap.image_bytes && !snap.counter_bytes);
 FILE *f=file("code.words","wx");const uint32_t *words=snap.code;
 for(size_t i=0;i<snap.code_bytes/4;i++)assert(fprintf(f,"%08x\n",words[i])==9);
 assert(!fclose(f));
 f=file("oracle.json","wx");
 fprintf(f,"{\"schema\":\"opengpu-compute-global-atomic-oracle-v1\",\"sourceCase\":\"%s\",\"viewBytes\":8192,\"groups\":4,\"local\":16,\"guard\":3735928559,\"context\":%u,\"codePhysical\":%llu,\"codeBytes\":%llu,\"shader\":",
  source_case,snap.context,(unsigned long long)snap.code_physical,(unsigned long long)snap.code_bytes);
 window(f,&shader);fputs(",\"kernarg\":",f);window(f,&kernarg);assert(fputs("}\n",f)>=0 && !fclose(f));}

static int wait_execution(void)
{if(retired)return 0;assert(submitted==1);write_source();
 char out[4096];assert(!compute_host_begin_execution(session));
 path(out,"union.memory");assert(!compute_host_export_memory(session,out));
 path(out,"record-0.capture");assert(!compute_host_capture(session,out,1000000000,37));
 marker("READY",capture_only?"original RV32/Linux CPU capture; no GPU execution\n":"original RV32/Linux active capture\n");
 if(capture_only){puts("original RV32/Linux admission/capture: PASS (no GPU execution)");fflush(stdout);_Exit(0);}
 FILE *f=wait_file("record-0.held.response");magic(f,"OGCMH001");struct compute_host_held_receipt h=held(f);end(f);
 path(out,"record-0.retire");assert(!compute_host_drain_held(session,&h,out));
 f=wait_file("record-0.retired.response");magic(f,"OGCMR001");struct compute_host_retired_receipt r={.held=held(f)};
 r.status=read32(f);r.irq=read32(f);r.irq_asserted=read32(f);r.external_drained=read32(f);end(f);
 assert(!compute_host_observe_retired(session,&r));retired=1;return 0;}

int global_fixture_ioctl(int fd,unsigned long request,...)
{va_list ap;va_start(ap,request);void *arg=va_arg(ap,void *);va_end(ap);assert(fd==7 || fd>=20000);
 if(request==DRM_IOCTL_OPENGPU_GET_PARAM){struct drm_opengpu_param *p=arg;assert(!p->pad);
  uint64_t value=0;int error=compute_host_query(session,p->param,&value);if(!error)p->value=value;return result(error);}
 if(request==DRM_IOCTL_OPENGPU_CONTEXT_CREATE){struct drm_opengpu_context *p=arg;assert(!p->flags);
  return result(compute_host_context_create(session,&p->id));}
 if(request==DRM_IOCTL_OPENGPU_CREATE_BUFFER){struct drm_opengpu_create_buffer *p=arg;
  assert(next_bo<16 && p->width && p->width<=UINT32_MAX/4-63 && p->height==1 && p->bpp==32);
  p->pitch=(p->width*4+63)&~63u;p->size=p->pitch;p->handle=next_bo++;p->offset=(uint64_t)p->handle*4096;
  uint64_t pages=(p->size+4095)&~UINT64_C(4095);bos[p->handle].data=compute_host_original_allocate(session,p->handle,pages);
  assert(bos[p->handle].data);bos[p->handle].bytes=p->size;return 0;}
 if(request==DRM_IOCTL_OPENGPU_RESOURCE_BIND)return result(compute_host_bind(session,arg));
 if(request==DRM_IOCTL_OPENGPU_COMPUTE){int error=compute_host_submit(session,arg);if(!error)submitted++;return result(error);}
 if(request==DRM_IOCTL_SYNCOBJ_CREATE){struct drm_syncobj_create *p=arg;assert(!p->flags && !p->handle);p->handle=1;return 0;}
 if(request==DRM_IOCTL_SYNCOBJ_WAIT){struct drm_syncobj_wait *p=arg;assert(p->count_handles==1);return wait_execution();}
 if(request==DRM_IOCTL_SYNCOBJ_HANDLE_TO_FD){struct drm_syncobj_handle *p=arg;assert(retired && p->handle==1);p->fd=20001;return 0;}
 if(request==SYNC_IOC_FILE_INFO){struct sync_file_info *p=arg;assert(retired);int32_t status;
  assert(!compute_host_completion_status(session,&status));p->status=status;return 0;}
 fprintf(stderr,"unsupported original global caller ioctl %lx\n",request);assert(0);return -1;}
void *global_fixture_mmap(void *address,size_t bytes,int prot,int flags,int fd,off_t offset)
{unsigned handle=(unsigned)(offset/4096);assert(!address && prot==(PROT_READ|PROT_WRITE) && flags==MAP_SHARED && fd==7);
 assert(!(offset&4095) && handle && handle<next_bo && !bos[handle].closed && bos[handle].bytes==bytes);
 bos[handle].mapped++;return bos[handle].data;}
int global_fixture_munmap(void *address,size_t bytes)
{for(unsigned h=1;h<next_bo;h++)if(bos[h].data==address){assert(bytes==bos[h].bytes && bos[h].mapped);bos[h].mapped--;return 0;}
 assert(0);return -1;}
int global_fixture_close(int fd)
{assert(fd==20001);return 0;}

int main(int argc,char **argv)
{assert(argc==6 || (argc==7 && !strcmp(argv[6],"--capture-only")));
 directory=argv[1];source_case=argv[5];capture_only=argc==7;
 assert(!strncmp(source_case,"global-atomic-",14));
 for(const char *c=source_case;*c;c++)assert((*c>='a' && *c<='z') || *c=='-');
 timeout_seconds=compute_host_timeout_seconds();assert(timeout_seconds);
 FILE *f=fopen(argv[2],"rb");assert(f);magic(f,"OGCP0003");unsigned count=read32(f);assert(count==112);
 struct compute_host_profile_word *profile=calloc(count+10,sizeof(*profile));assert(profile);
 for(unsigned i=0;i<count;i++){profile[i].address=read32(f);profile[i].value=read32(f);}
 assert(read32(f)==10);for(unsigned i=count;i<count+10;i++){profile[i].address=read32(f);profile[i].value=read32(f);}end(f);
 session=compute_host_open(profile,count+10);free(profile);assert(session);
 uint64_t features;assert(!opengpu_driver_features(7,&features) && (features&OPENGPU_DRIVER_FEATURE_COMPUTE_WORD_ATOMICS));
 uint8_t program[4097];f=fopen(argv[3],"rb");assert(f);program_bytes=fread(program,1,sizeof(program),f);
 assert(program_bytes && program_bytes<=4096 && !(program_bytes&3));end(f);
 assert(!opengpu_buffer_create(7,(uint32_t)program_bytes,&shader) && !opengpu_buffer_create(7,8192,&kernarg));
 memset(shader.map,0,shader.size);memcpy(shader.map,program,program_bytes);
 f=fopen(argv[4],"rb");assert(f && fread(kernarg.map,1,8192,f)==8192);end(f);
 uint32_t context,sync;assert(!opengpu_context_create(7,&context) && !opengpu_sync_create(7,&sync));
 struct drm_opengpu_resource binding={.context_id=context,.slot=1,.handle=shader.handle,
  .type=OPENGPU_RESOURCE_COMPUTE_SHADER,.size=shader.size};assert(!opengpu_bind(7,&binding));
 binding=(struct drm_opengpu_resource){.context_id=context,.slot=2,.handle=kernarg.handle,
  .type=OPENGPU_RESOURCE_COMPUTE_KERNARG,.size=8192};assert(!opengpu_bind(7,&binding));
 struct drm_opengpu_compute command={.context_id=context,.shader_slot=1,.kernarg_slot=2,
  .grid={4,1,1},.local={16,1,1},.out_syncobj=sync};
 assert(!opengpu_compute(7,&command) && submitted==1);
 assert(!opengpu_sync_wait_success(7,sync,OPENGPU_FENCE_TIMEOUT_MS) && retired);
 marker("cleanup_wait","original global kernel retired; independent byte authorization required\n");
 f=wait_file("BEGIN_CLEANUP");magic(f,"OGCMBA01");assert(read32(f)==1);end(f);
 assert(!compute_host_begin_cleanup(session,directory));assert(!compute_host_finish_observed(session));
 assert(!compute_host_end_cleanup(session));
 f=wait_file("FINAL_RESPONSE");magic(f,"OGCMOK01");assert(read32(f)==1);end(f);
 /* Dedicated source process: the original file/context release above owns
  * cleanup. Do not enqueue unobserved later ioctls after MMU shutdown. */
 assert(!compute_host_destroy(session));session=NULL;
 puts("original RV32/Linux owner lifecycle: PASS (DUT bytes verified externally)");return 0;}
