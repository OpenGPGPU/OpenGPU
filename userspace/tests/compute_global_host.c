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
#include "../../driver/tests/opengpu_compute_reservation_fixture.h"

static struct compute_host_session *session;
static struct {void *data;uint64_t bytes;unsigned mapped,closed;} bos[16];
static unsigned next_bo=1,submitted,retired,capture_only,timeout_seconds,expect_global_fault,cached_kernarg;
static unsigned cache_alias_source;
static unsigned cache_alias_live,cache_alias_write_error;
static unsigned uc_cache_alias_source,uc_cache_alias_live,uc_cache_alias_write_error;
static unsigned l1_publication_source;
static unsigned reservation_wait;
static struct compute_reservation_producer *reservation_producer;
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
 assert(snap.code_bytes==shader.size && snap.storage_bytes==(uc_cache_alias_source?256u:0u) &&
  !snap.uniform_bytes && !snap.image_bytes && !snap.counter_bytes);
 FILE *f=file("code.words","wx");const uint32_t *words=snap.code;
 for(size_t i=0;i<snap.code_bytes/4;i++)assert(fprintf(f,"%08x\n",words[i])==9);
 assert(!fclose(f));
 f=file("oracle.json","wx");
 fprintf(f,"{\"schema\":\"%s\",\"sourceCase\":\"%s\",\"viewBytes\":8192,\"groups\":4,\"local\":16,\"guard\":3735928559,\"context\":%u,\"codePhysical\":%llu,\"codeBytes\":%llu,\"shader\":",
  l1_publication_source?"opengpu-compute-l1-publication-oracle-v1":
  uc_cache_alias_source?"opengpu-compute-uc-cache-alias-oracle-v1":
  cache_alias_source?"opengpu-compute-cache-alias-oracle-v1":
  cached_kernarg?"opengpu-compute-global-cached-atomic-oracle-v1":"opengpu-compute-global-atomic-oracle-v1",
  source_case,snap.context,(unsigned long long)snap.code_physical,(unsigned long long)snap.code_bytes);
 window(f,&shader);fputs(",\"kernarg\":",f);window(f,&kernarg);
 if(uc_cache_alias_source){
  fputs(",\"storageAlias\":",f);window(f,&kernarg);
  fprintf(f,",\"storageSlot\":13,\"storageBinding\":0,\"descriptorPhysical\":%llu,\"descriptorRoot\":%u,\"retainedObjects\":%u,\"temporaryMappings\":%u",
   (unsigned long long)snap.storage_physical,snap.storage_root,snap.retained_objects,snap.temporary_mappings);
 }
 assert(fputs("}\n",f)>=0 && !fclose(f));}

static int wait_execution(void)
{if(retired)return 0;assert(submitted==1);if(!uc_cache_alias_source)write_source();
 if(reservation_wait){
  assert(reservation_producer && compute_reservation_producer_attempt(reservation_producer)==-EBUSY);
  struct compute_reservation_wait_info info;
  for(unsigned released=0;released<2;released++){
   assert(!compute_reservation_producer_snapshot(reservation_producer,&info));
   char text[4096];int n=snprintf(text,sizeof(text),
    "{\"schema\":\"opengpu-compute-reservation-%s-v1\",\"sourceCase\":\"%s\",\"cachedKernarg\":%s,\"producerKind\":\"external-cpu\",\"executionAttemptStatus\":-16,"
    "\"context\":%u,\"handle\":%u,\"objects\":%u,\"dependencies\":%u,\"pendingDependencies\":%u,"
    "\"reservationDependencies\":%u,\"producerDependencies\":%u,\"reservationUsage\":%u,\"queued\":%u,"
    "\"kernelSubmissions\":%u,\"blockedAttempts\":%u,\"originalIdentity\":%s,\"producerStatus\":%d,\"consumerStatus\":%d,"
    "\"producerContext\":%llu,\"producerSequence\":%llu,\"consumerSequence\":%llu,\"physical\":%llu,\"bytes\":%llu}\n",
    released?"released":"blocked",source_case,cached_kernarg?"true":"false",info.context,info.handle,info.objects,
    info.dependencies,info.pending_dependencies,info.reservation_dependencies,info.producer_dependencies,info.reservation_usage,
    info.queued,info.kernel_submissions,info.blocked_attempts,info.original_identity?"true":"false",info.producer_status,
    info.consumer_status,(unsigned long long)info.producer_context,(unsigned long long)info.producer_sequence,
    (unsigned long long)info.consumer_sequence,(unsigned long long)info.physical,(unsigned long long)info.bytes);
   assert(n>0 && n<(int)sizeof(text));marker(released?"reservation.released.json":"reservation.blocked.json",text);
   if(!released){FILE *release=wait_file("RELEASE_RESERVATION");magic(release,"OGCRRL01");assert(read32(release)==1);end(release);
    assert(!compute_reservation_producer_signal(reservation_producer));}
  }
  assert(!compute_reservation_producer_destroy(reservation_producer));reservation_producer=NULL;
 }
 char out[4096];assert(!compute_host_begin_execution(session));
 if(uc_cache_alias_source)write_source();
 path(out,"union.memory");assert(!compute_host_export_memory(session,out));
 path(out,"record-0.capture");assert(!compute_host_capture(session,out,1000000000,37));
 marker("READY",capture_only?"original RV32/Linux CPU capture; no GPU execution\n":"original RV32/Linux active capture\n");
 if(capture_only){puts("original RV32/Linux admission/capture: PASS (no GPU execution)");fflush(stdout);_Exit(0);}
 FILE *f=wait_file("record-0.held.response");magic(f,expect_global_fault?"OGCFH001":"OGCMH001");struct compute_host_held_receipt h=held(f);end(f);
 path(out,"record-0.retire");assert(!(expect_global_fault?compute_host_drain_failed(session,&h,out):compute_host_drain_held(session,&h,out)));
 f=wait_file("record-0.retired.response");magic(f,expect_global_fault?"OGCFR001":"OGCMR001");struct compute_host_retired_receipt r={.held=held(f)};
 r.status=read32(f);r.irq=read32(f);r.irq_asserted=read32(f);r.external_drained=read32(f);end(f);
 assert(!(expect_global_fault?compute_host_observe_failed_retired(session,&r):compute_host_observe_retired(session,&r)));retired=1;return 0;}

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
 if(request==DRM_IOCTL_OPENGPU_COMPUTE_STORAGE){assert(uc_cache_alias_source);
  int error=compute_host_submit_storage(session,arg);if(!error)submitted++;return result(error);}
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
{assert(argc>=6);uint64_t physical_base=0;
 directory=argv[1];source_case=argv[5];
 for(int i=6;i<argc;i++){
  if(!strcmp(argv[i],"--cached-kernarg")){assert(!cached_kernarg && !expect_global_fault);cached_kernarg=1;}
  else if(!strcmp(argv[i],"--reservation-wait")){assert(!reservation_wait);reservation_wait=1;}
  else if(!strcmp(argv[i],"--cache-alias-source")){assert(!cache_alias_source && !expect_global_fault);cache_alias_source=1;}
  else if(!strcmp(argv[i],"--uc-cache-alias-source")){assert(!uc_cache_alias_source);uc_cache_alias_source=1;}
  else if(!strcmp(argv[i],"--l1-publication-source")){assert(!l1_publication_source);l1_publication_source=1;}
  else if(!strcmp(argv[i],"--uc-cache-alias-live")){assert(!uc_cache_alias_live && !capture_only);uc_cache_alias_live=1;}
  else if(!strcmp(argv[i],"--expect-uc-cache-alias-write-error")){
   assert(!uc_cache_alias_write_error && !expect_global_fault && !capture_only);
   uc_cache_alias_write_error=1;expect_global_fault=2;
  }
  else if(!strcmp(argv[i],"--cache-alias-live")){assert(!cache_alias_live && !capture_only);cache_alias_live=1;}
  else if(!strcmp(argv[i],"--expect-cache-alias-write-error")){
   assert(!cache_alias_write_error && !expect_global_fault && !capture_only);
   cache_alias_write_error=1;expect_global_fault=2;
  }
  else if(!strcmp(argv[i],"--capture-only")){assert(!capture_only && !expect_global_fault);capture_only=1;}
  else if(!strcmp(argv[i],"--expect-global-fault")){
   assert(!capture_only && !expect_global_fault && !cached_kernarg && ++i<argc);
   expect_global_fault=!strcmp(argv[i],"read")?1:!strcmp(argv[i],"write")?2:0;assert(expect_global_fault);
  }else if(!strcmp(argv[i],"--physical-base")){
   assert(!physical_base && ++i<argc);const char *v=argv[i];assert(*v);
   for(;*v;v++){assert(*v>='0' && *v<='9');unsigned digit=(unsigned)(*v-'0');
    assert(physical_base<=(UINT32_MAX-digit)/10u);physical_base=physical_base*10+digit;}
   assert(physical_base && !(physical_base&4095));
  }else assert(!"unknown original global caller option");
 }
 assert(!cache_alias_source || (cached_kernarg && (capture_only != cache_alias_live)));
 assert(!cache_alias_live || (cache_alias_source && cached_kernarg && !capture_only));
 assert(!cache_alias_write_error || (cache_alias_live && expect_global_fault==2));
  assert(!cache_alias_source || !expect_global_fault || cache_alias_write_error);
  assert(!l1_publication_source || (uc_cache_alias_source && cached_kernarg && !capture_only));
  assert(!uc_cache_alias_source || (cached_kernarg && (capture_only != uc_cache_alias_live) &&
  !cache_alias_source && !cache_alias_live && (!expect_global_fault || uc_cache_alias_write_error)));
 assert(!uc_cache_alias_live || (uc_cache_alias_source && cached_kernarg && !capture_only));
 assert(!uc_cache_alias_write_error || (uc_cache_alias_live && expect_global_fault==2));
 assert(!reservation_wait || (!capture_only && !cache_alias_source && !cache_alias_live &&
  !uc_cache_alias_source && !uc_cache_alias_live && !expect_global_fault));
 assert(!strncmp(source_case,"global-atomic-",14) || !strncmp(source_case,"l1-publication-",15));
 for(const char *c=source_case;*c;c++)assert((*c>='a' && *c<='z') || *c=='-');
 timeout_seconds=compute_host_timeout_seconds();assert(timeout_seconds);
 FILE *f=fopen(argv[2],"rb");assert(f);magic(f,"OGCP0003");unsigned count=read32(f);assert(count==112);
 struct compute_host_profile_word *profile=calloc(count+10,sizeof(*profile));assert(profile);
 for(unsigned i=0;i<count;i++){profile[i].address=read32(f);profile[i].value=read32(f);}
 assert(read32(f)==10);for(unsigned i=count;i<count+10;i++){profile[i].address=read32(f);profile[i].value=read32(f);}end(f);
 if(physical_base)assert(!compute_host_allocation_base(physical_base));
 session=compute_host_open(profile,count+10);free(profile);assert(session);
 uint64_t features,required=uc_cache_alias_source?
  OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE|OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_ATOMICS:
  OPENGPU_DRIVER_FEATURE_COMPUTE_WORD_ATOMICS;
 assert(!opengpu_driver_features(7,&features) && (features&required)==required);
 uint8_t program[4097];f=fopen(argv[3],"rb");assert(f);program_bytes=fread(program,1,sizeof(program),f);
 assert(program_bytes && program_bytes<=4096 && !(program_bytes&3));end(f);
 assert(!opengpu_buffer_create(7,(uint32_t)program_bytes,&shader) && !opengpu_buffer_create(7,8192,&kernarg));
 memset(shader.map,0,shader.size);memcpy(shader.map,program,program_bytes);
 f=fopen(argv[4],"rb");assert(f && fread(kernarg.map,1,8192,f)==8192);end(f);
 uint32_t context,sync;assert(!opengpu_context_create(7,&context) && !opengpu_sync_create(7,&sync));
 struct drm_opengpu_resource binding={.context_id=context,.slot=1,.handle=shader.handle,
  .type=OPENGPU_RESOURCE_COMPUTE_SHADER,.size=shader.size};assert(!opengpu_bind(7,&binding));
 binding=(struct drm_opengpu_resource){.context_id=context,.slot=2,.handle=kernarg.handle,
  .type=OPENGPU_RESOURCE_COMPUTE_KERNARG,.size=8192,
  .flags=cached_kernarg?OPENGPU_RESOURCE_CACHED:0};assert(!opengpu_bind(7,&binding));
 if(uc_cache_alias_source){
  binding=(struct drm_opengpu_resource){.context_id=context,.slot=13,.handle=kernarg.handle,
   .type=OPENGPU_RESOURCE_COMPUTE_STORAGE,.size=8192,.flags=OPENGPU_RESOURCE_UNCACHED};
  assert(!opengpu_bind(7,&binding));
 }
 struct drm_opengpu_compute command={.context_id=context,.shader_slot=1,.kernarg_slot=2,
  .grid={4,1,1},.local={16,1,1},.out_syncobj=sync};
 if(reservation_wait){reservation_producer=compute_reservation_producer_create(session,kernarg.handle);assert(reservation_producer);}
 if(uc_cache_alias_source){
  struct drm_opengpu_compute_storage storage={.launch=command};
  storage.bindings[0]=(struct drm_opengpu_compute_storage_binding){13,
   OPENGPU_COMPUTE_STORAGE_READ|OPENGPU_COMPUTE_STORAGE_WRITE};
  assert(!opengpu_compute_storage(7,&storage) && submitted==1);
 }else assert(!opengpu_compute(7,&command) && submitted==1);
 int status=opengpu_sync_wait_success(7,sync,OPENGPU_FENCE_TIMEOUT_MS);
 assert(retired);
 if(expect_global_fault){int32_t fence_status;
  assert(status==-1 && errno==EIO && !compute_host_completion_status(session,&fence_status) && fence_status==-EIO);
 }else assert(!status);
 marker("cleanup_wait","original global kernel retired; independent byte authorization required\n");
 f=wait_file("BEGIN_CLEANUP");magic(f,"OGCMBA01");assert(read32(f)==1);end(f);
 assert(!compute_host_begin_cleanup(session,directory));
 assert(!(expect_global_fault?compute_host_finish_failed_observed(session):compute_host_finish_observed(session)));
 assert(!compute_host_end_cleanup(session));
 f=wait_file("FINAL_RESPONSE");magic(f,"OGCMOK01");assert(read32(f)==1);end(f);
 /* Dedicated source process: the original file/context release above owns
  * cleanup. Do not enqueue unobserved later ioctls after MMU shutdown. */
 assert(!compute_host_destroy(session));session=NULL;
 puts("original RV32/Linux owner lifecycle: PASS (DUT bytes verified externally)");return 0;}
