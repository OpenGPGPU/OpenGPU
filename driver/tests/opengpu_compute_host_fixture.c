/* Actual compute resource admission and MMU composition. OS transport only;
 * this fixture neither interprets shaders nor manufactures GPU output. */
#include <assert.h>
#include <stdbool.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "gpu_abi.h"
#include "opengpu_compute_host_fixture.h"
#include "opengpu_drm.h"
#include "opengpu_cache_range.h"
#include "opengpu_storage_slot.h"
#include "opengpu_compute_texture_bound_view.h"
#include "opengpu_compute_features.h"
#include "opengpu_compute_uniform_command.h"
#include "opengpu_compute_uniform_records.h"
#include "opengpu_compute_counter_source.h"
#include "opengpu_compute_indirect_source.h"
#include "opengpu_staging_validator.h"
#include "opengpu_backing_identity.h"
#include "opengpu_atomic_counter_command.h"
#include "opengpu_compute_texture_command.h"
#include "opengpu_storage_command.h"
#include "opengpu_compute_image_descriptor.h"
#include "opengpu_shader_image_command.h"
#include "opengpu_storage_descriptor.h"
#include "opengpu_shader_validator.h"
#include "opengpu_shader_resources.h"
#include "opengpu_compute_uniform_counter_source_program.h"
#include "opengpu_kernarg_va.h"
#include "opengpu_tessellation_completion.h"
#include "opengpu_native_completion.h"
#include "opengpu_unified_completion.h"
#include <stddef.h>
_Static_assert(sizeof(struct drm_opengpu_compute_uniform_view)==16,"uniform UAPI view size");
_Static_assert(offsetof(struct drm_opengpu_compute_uniform_view,handle)==8,"uniform UAPI handle offset");
_Static_assert(offsetof(struct drm_opengpu_compute_uniform_view,bytes)==12,"uniform UAPI size offset");
_Static_assert(sizeof(struct drm_opengpu_compute_counter_source)==16,"counter source UAPI size");
_Static_assert(offsetof(struct drm_opengpu_compute_counter_source,handle)==8,"counter source handle offset");
_Static_assert(offsetof(struct drm_opengpu_compute_counter_source,count)==12,"counter source count offset");
_Static_assert(sizeof(struct drm_opengpu_compute_counter_source_resources)==sizeof(struct drm_opengpu_compute_uniform_resources)+32,"counter source wrapper UAPI size");
_Static_assert(sizeof(struct drm_opengpu_compute_indirect_source)==16,"indirect source UAPI size");
_Static_assert(offsetof(struct drm_opengpu_compute_indirect_resources,source)==sizeof(struct drm_opengpu_compute_counter_source_resources),"indirect source offset");
_Static_assert(sizeof(struct drm_opengpu_compute_indirect_resources)==sizeof(struct drm_opengpu_compute_counter_source_resources)+40,"indirect wrapper UAPI size");
typedef uint64_t u64;
typedef uint64_t dma_addr_t;
typedef unsigned char u8;
#define U32_MAX UINT32_MAX
#define U16_MAX UINT16_MAX
#define ERR_PTR(e) ((struct dma_fence *)(intptr_t)(e))

typedef unsigned spinlock_t;
struct dma_fence;
struct dma_fence_ops {const char *(*get_driver_name)(struct dma_fence *);const char *(*get_timeline_name)(struct dma_fence *);void (*release)(struct dma_fence *);};
struct dma_fence {u64 seqno;unsigned refs;int error;bool signaled;const struct dma_fence_ops *ops;};
struct fixture_dependency {
 struct fixture_dependency *next;struct dma_fence *fence;
 /* OS-boundary attribution only; never an execution or fence receipt. */
 struct dma_resv *reservation;int reservation_usage;
};
struct drm_sched_entity {int unused;};
struct drm_gpu_scheduler {int unused;};
struct kref {unsigned refs;};
#define OPENGPU_MMU_TEST_COMPUTE_EXTRA struct drm_gpu_scheduler scheduler;
struct drm_sched_fence {struct dma_fence finished;};
struct drm_sched_job {struct drm_sched_fence *s_fence;struct drm_sched_job *next;struct fixture_dependency *dependencies;};
#include "compute_host_fault_under_test.inc"
struct opengpu_device;
struct opengpu_vm;
static int compute_mmio_wait_idle(struct opengpu_device *);
static int compute_mmio_flush_tlbs(struct opengpu_device *);
static int compute_mmio_flush_asid(struct opengpu_device *,u32);
static int compute_mmio_activate_vm(struct opengpu_device *,const struct opengpu_vm *);
static int compute_mmio_enable_mmu(struct opengpu_device *,u64);
static void compute_mmio_dma_wmb(void);
static int compute_mmio_set_satp(struct opengpu_device *,u64,u32);
static int compute_mmio_disable_mmu(struct opengpu_device *);
#define OPENGPU_MMU_TEST_WAIT_IDLE(g) compute_mmio_wait_idle(g)
#define OPENGPU_MMU_TEST_FLUSH_TLBS(g) compute_mmio_flush_tlbs(g)
#define OPENGPU_MMU_TEST_FLUSH_ASID(g,a) compute_mmio_flush_asid(g,a)
#define OPENGPU_MMU_TEST_ACTIVATE_VM(g,v) compute_mmio_activate_vm(g,v)
#define OPENGPU_MMU_TEST_ENABLE_MMU(g,r) compute_mmio_enable_mmu(g,r)
#define OPENGPU_MMU_TEST_DMA_WMB() compute_mmio_dma_wmb()
#define OPENGPU_MMU_TEST_SET_SATP(g,r,a) compute_mmio_set_satp(g,r,a)
#define OPENGPU_MMU_TEST_DISABLE_MMU(g) compute_mmio_disable_mmu(g)
#define OPENGPU_MMU_TEST_HW_EXTRA \
    bool clip_depth,sampler_nearest,pixel_center,sampler_axis_wrap,sampler_biased_clamp,sampler_mirrored_repeat,viewport,depth_range,constant_blend; \
    bool compute_texture_transport,compute_storage_transport,compute_image_transport,compute_counter_transport,compute_uniform_transport,compute_indirect_transport; \
    u32 compute_resource_flags,shader_config; \
    struct opengpu_compute_local_limits compute_local_limits; \
    struct opengpu_compute_shared_limits compute_shared_limits; \
    void *compute_mmio;bool reset_pending;unsigned int aborting; \
    struct dma_fence *unified_fence,*active_fence; \
    u32 unified_command_id,unified_opcode,unified_completion_delay_ms,active_completion_delay_ms,job_seqno; \
    u64 unified_expected_bytes,fence_context,fence_seqno,regs_size; \
    int timeout_work,poll_work,completion_work,reset_work; \
    struct opengpu_fault_snapshot last_fault;
#define opengpu_buffer_alloc compute_mmu_buffer_alloc
#define opengpu_buffer_free compute_mmu_buffer_free
#define main compute_mmu_component_main
#include "opengpu_mmu_test.c"
#undef main
#undef opengpu_buffer_alloc
#undef opengpu_buffer_free
#define OPENGPU_MMU_PAGE_SIZE 4096u
#define OPENGPU_MAX_TEMP_MAPPINGS 8u
#define MAX_SCHEDULE_TIMEOUT 0x7fffffffl
#define DRM_EXEC_INTERRUPTIBLE_WAIT 1
#define DRM_EXEC_IGNORE_DUPLICATES 2
#define container_of(p,t,m) ((t *)((char *)(p)-offsetof(t,m)))
#define spin_lock_init(p) (*(p)=0)
#define atomic64_set(p,v) (*(p)=(v))
#define atomic64_read(p) (*(p))
#define atomic64_inc(p) (++*(p))
#define atomic64_dec(p) (--*(p))
#define time_after(a,b) ((long)((a)-(b))>0)
#define time_after_eq(a,b) ((long)((a)-(b))>=0)
#define msecs_to_jiffies(v) (v)
#define dev_err(...) ((void)0)
#define dma_rmb() ((void)0)
#define OPENGPU_DRAW_WAIT_MS 30000u
#define lower_32_bits(v) ((u32)(v))
#define upper_32_bits(v) ((u32)((u64)(v)>>32))
#define u64_to_user_ptr(v) ((void *)(uintptr_t)(v))
#define BUILD_BUG_ON(v) _Static_assert(!(v),"production layout invariant")
#define ARRAY_SIZE(v) (sizeof(v)/sizeof((v)[0]))
#define min_t(t,a,b) ((t)(a)<(t)(b)?(t)(a):(t)(b))
#define max(a,b) ((a)>(b)?(a):(b))
#define U32_MAX UINT32_MAX
#define U16_MAX UINT16_MAX
#define check_mul_overflow(a,b,p) __builtin_mul_overflow(a,b,p)
#define READ_ONCE(v) (v)
#define kzalloc(n,f) ((void)(f),calloc(1,n))
#define kvzalloc kzalloc
#define kfree(p) free(p)
enum dma_resv_usage {DMA_RESV_USAGE_READ=1,DMA_RESV_USAGE_WRITE=2};
struct dma_resv {bool locked;struct dma_fence *read,*write;};
struct dma_resv_iter {struct dma_resv *resv;unsigned index;int usage;};
struct drm_exec {struct dma_resv **locked;u32 count,capacity;};
struct drm_syncobj {struct dma_fence *fence;};
struct opengpu_sched_dep {struct drm_gem_object *object;int wait_usage,signal_usage;};
struct drm_gem_object { size_t size;struct dma_resv *resv;void *import_attach; };
struct drm_gem_dma_object { struct drm_gem_object base;void *vaddr;u64 dma_addr; };
struct compute_original {struct drm_gem_dma_object gem;struct opengpu_buffer backing;struct dma_resv resv;unsigned refs;bool closed;struct opengpu_device *gpu;};
struct opengpu_resource_binding {struct drm_gem_object *object;u64 dma,va,size,offset;u32 type,width,height,flags;};
struct opengpu_render_context {struct opengpu_device *gpu;struct opengpu_vm vm;u32 id;
    struct opengpu_resource_binding *bindings[OPENGPU_MAX_RESOURCE_BINDINGS];
    struct kref refcount;struct drm_sched_entity entity;struct dma_fence *last_fence;};
struct idr {void *entries[512];};
struct compute_host_session;
struct opengpu_file {struct compute_host_session *session;struct opengpu_device *gpu;struct mutex lock;struct opengpu_render_context *context;
    struct compute_original *originals[512];struct idr contexts;};
struct drm_file {struct opengpu_file *driver_priv;u64 client_id;};
struct drm_device {struct opengpu_device *dev;};
static int opengpu_buffer_alloc(struct opengpu_device *,struct opengpu_buffer *,size_t);
static void opengpu_buffer_free(struct opengpu_device *,struct opengpu_buffer *);
struct opengpu_device *dev_get_drvdata(struct opengpu_device *gpu){return gpu;}
static struct drm_gem_object *drm_gem_object_lookup(struct drm_file *file,u32 handle)
{struct compute_original *o=handle<512?file->driver_priv->originals[handle]:NULL;
 if(!o || !o->refs || o->closed)return NULL;
 ++o->refs;return &o->gem.base;}
static void drm_gem_object_get(struct drm_gem_object *object)
{struct compute_original *o=(void *)object;assert(o->refs);++o->refs;}
static void compute_original_drop(struct compute_original *);
static void drm_gem_object_put(struct drm_gem_object *object)
{compute_original_drop((void *)object);}
static struct drm_gem_dma_object *to_drm_gem_dma_obj(struct drm_gem_object *object)
{return (void *)object;}
struct opengpu_render_context *opengpu_context_lookup(struct opengpu_file *,u32);
static int opengpu_context_quiesce(struct opengpu_file *,struct opengpu_render_context *);
static struct dma_fence *dma_fence_get(struct dma_fence *);
static void dma_fence_put(struct dma_fence *);
static bool dma_fence_is_signaled(struct dma_fence *);
static int opengpu_hw_fence_wait(struct opengpu_device *,struct dma_fence *,unsigned,bool);
#include "compute_host_context_under_test.inc"
static int opengpu_hw_invalidate_sync(struct opengpu_device *,u32,u32,bool);
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wsign-compare"
#pragma GCC diagnostic ignored "-Wunused-parameter"
#include "compute_host_bind_under_test.inc"
#pragma GCC diagnostic pop


/* DMA primitive rounds the reserved physical allocation just as coherent DMA
 * does; opengpu_memory.c retains the caller's logical buffer size. */
static void *dma_alloc_coherent(void *device,size_t size,dma_addr_t *physical,int flags)
{(void)device;(void)flags;if(!size || size>SIZE_MAX-4095)return NULL;
 struct opengpu_buffer backing={0};
 if(compute_mmu_buffer_alloc(NULL,&backing,(size+4095)&~(size_t)4095))return NULL;
 *physical=backing.dma;return backing.cpu;}
static void dma_free_coherent(void *device,size_t size,void *cpu,dma_addr_t physical)
{(void)device;struct opengpu_buffer backing={.cpu=cpu,.dma=physical,.size=size};compute_mmu_buffer_free(NULL,&backing);}
#include "compute_host_memory_under_test.inc"
struct opengpu_command_events {u32 flags,wait_event,signal_event;};
struct opengpu_kernel_launch { u32 indirect_base,indirect_bytes,indirect_offset;
    u64 kernel_pc,kernarg; u32 grid[3],local[3];
    bool texture_enabled,storage_enabled,image_enabled,counter_enabled,uniform_enabled,indirect_enabled;
    struct opengpu_compute_texture_command_context texture;
    struct opengpu_storage_command_context storage;
    struct opengpu_image_command_context image;
    struct opengpu_atomic_counter_command_context counter;
    struct opengpu_compute_uniform_command_context uniform;
};
struct dma_fence_chain;
struct opengpu_job_temporary_mapping {u64 va;size_t size;};
enum {OPENGPU_SCHED_COMPUTE=1,OPENGPU_SCHED_INVALIDATE=6,OPENGPU_SCHED_TESSELLATION=7,OPENGPU_SCHED_NATIVE_BINNED=8,OPENGPU_SCHED_PROTECTED_TESSELLATION=9};
struct opengpu_sched_job {
    void *native_owner;struct dma_fence *retirement_fence,*admission_chain;bool ordinary_pending;
    struct dma_fence_chain *execution_chain;bool admission_armed;
    struct drm_sched_job base; struct opengpu_device *gpu; const struct opengpu_vm *vm;
    struct opengpu_render_context *context; int type;
    u32 native_job_segment;
    dma_addr_t dma_source;u32 dma_bytes;
    struct opengpu_buffer shader,texture_records,storage_records,image_records,counter_records,uniform_snapshot,indirect_snapshot;
    struct opengpu_resource_binding counter_source;
    struct opengpu_buffer depth,vertex_shader,render_desc,commands;
    struct opengpu_kernel_launch kernel; struct opengpu_command_events events;
    struct drm_gem_object **objects; u32 object_count;
    struct opengpu_job_temporary_mapping temporary_mappings[OPENGPU_MAX_TEMP_MAPPINGS]; u32 temporary_mapping_count;
};
struct compute_host_session {
 struct opengpu_device gpu;struct opengpu_render_context *context;u32 context_id;
 struct opengpu_file render;struct drm_file file;struct drm_device drm;
 struct compute_host_profile_word *profile;size_t profile_count;
 bool missing,exported,captured,drained,observed,freed,vm_destroyed,cancelled,indirect_declared;
 struct compute_host_held_receipt held;
 int completion_error;
 struct drm_opengpu_compute_uniform_resources submitted;
 struct drm_opengpu_compute_indirect_resources submitted_indirect;
 struct drm_syncobj sync[512];struct drm_sched_job *head,*tail;u64 next_seq;
 struct dma_fence *hardware;bool prepared_execution;
 unsigned queued;int wait_error,copy_error,submit_error;
 struct compute_writable_range *captured_writable;unsigned captured_writable_count;
 bool phase_readback,phase_job_freed,retained_execution;
 bool phase_original_pinned[512];
 u32 phase_context,phase_epoch,phase_command,phase_opcode;
};
static struct compute_host_session *compute_running;
#include "opengpu_compute_host_os.inc"
#include "opengpu_compute_host_mmio.inc"
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-parameter"
#include "compute_host_query_under_test.inc"
#pragma GCC diagnostic pop
unsigned compute_host_timeout_seconds(void)
{
 const char *value=getenv("OPENGPU_COMPUTE_HOST_TIMEOUT");if(!value)return 300;
 if(!*value)return 0;
 unsigned seconds=0;
 for(;*value;value++){
  if(*value<'0' || *value>'9')return 0;
  unsigned digit=(unsigned)(*value-'0');if(seconds>(604800u-digit)/10u)return 0;
  seconds=seconds*10u+digit;
 }
 return seconds;
}
static u32 compute_profile_read(void *opaque,u32 address)
{struct compute_host_session *s=opaque;
 for(size_t i=0;i<s->profile_count;i++)if(s->profile[i].address==address)return s->profile[i].value;
 s->missing=true;return 0;}
static struct compute_host_session *compute_host_initialize(bool initial_context,bool indirect,
 const struct compute_host_profile_word *words,size_t count)
{
 if(!words || !count || count>1024 || !compute_host_timeout_seconds())return NULL;
 struct compute_host_session *s=calloc(1,sizeof(*s));if(!s)return NULL;
 s->profile=malloc(count*sizeof(*words));if(!s->profile){free(s);return NULL;}
 memcpy(s->profile,words,count*sizeof(*words));s->profile_count=count;
 for(size_t i=0;i<count;i++){
  if((words[i].address&3) || words[i].address>=0x1000)goto fail;
  for(size_t j=0;j<i;j++)if(words[i].address==words[j].address)goto fail;
 }
 u32 id=compute_profile_read(s,GPU_REG_ID);s->gpu.hw.capabilities=compute_profile_read(s,GPU_REG_CAPABILITIES);
 if((id&0xffff0000u)!=0x47550000u ||
    (s->gpu.hw.capabilities&(GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE|
      GPU_CAP_COMPUTE_SCALAR_FPU|GPU_CAP_SHADER_CONFIG_INFO|GPU_CAP_NATIVE_PIXEL_FORMATS))!=
      (GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE|GPU_CAP_COMPUTE_SCALAR_FPU|
       GPU_CAP_SHADER_CONFIG_INFO|GPU_CAP_NATIVE_PIXEL_FORMATS) ||
    !opengpu_read_compute_features(id,0x1000,s,compute_profile_read,&s->gpu.hw.compute_resource_flags) ||
    !opengpu_read_compute_local_limits(id,0x1000,s->gpu.hw.compute_resource_flags,s,compute_profile_read,&s->gpu.hw.compute_local_limits) ||
    !opengpu_read_compute_shared_limits(id,0x1000,s->gpu.hw.compute_resource_flags,s,compute_profile_read,&s->gpu.hw.compute_shared_limits) ||
    !opengpu_read_compute_texture_command_support(s->gpu.hw.compute_resource_flags,0x1000,s,compute_profile_read,&s->gpu.hw.compute_texture_transport) ||
    !opengpu_read_storage_command_support(s->gpu.hw.compute_resource_flags,0x1000,s,compute_profile_read,&s->gpu.hw.compute_storage_transport) ||
    !opengpu_read_image_command_support(s->gpu.hw.compute_resource_flags,0x1000,s,compute_profile_read,&s->gpu.hw.compute_image_transport) ||
    !opengpu_read_atomic_counter_command_support(s->gpu.hw.compute_resource_flags,0x1000,s,compute_profile_read,&s->gpu.hw.compute_counter_transport) ||
    !opengpu_read_compute_uniform_command_support(s->gpu.hw.compute_resource_flags,0x1000,s,compute_profile_read,&s->gpu.hw.compute_uniform_transport))goto fail;
 /* This qualification targets the stock two-CU complete profile. Admission
  * still retains all original resource capacities and per-domain services. */
 u32 shader=compute_profile_read(s,GPU_REG_SHADER_CONFIG);
 if(!(s->gpu.hw.capabilities&GPU_CAP_SHADER_CONFIG_INFO) || (shader&0xff)!=16 ||
    ((shader>>GPU_SHADER_CONFIG_WARPS_SHIFT)&GPU_SHADER_CONFIG_FIELD_MASK)!=8 ||
    ((shader>>GPU_SHADER_CONFIG_CUS_SHIFT)&GPU_SHADER_CONFIG_FIELD_MASK)!=2 ||
    compute_profile_read(s,OPENGPU_COMPUTE_RESOURCE_REG_COMPUTE_UNITS)!=2 ||
    compute_profile_read(s,OPENGPU_COMPUTE_RESOURCE_REG_LANES)!=16 ||
    compute_profile_read(s,OPENGPU_COMPUTE_RESOURCE_REG_WARPS)!=8 ||
    !opengpu_shader_resources_valid(shader, (s->gpu.hw.capabilities &
       GPU_CAP_FRAGMENT_BATCH_MASK) >> GPU_CAP_FRAGMENT_BATCH_SHIFT) ||
    s->gpu.hw.compute_local_limits.items!=128 || !s->gpu.hw.compute_shared_limits.discovered ||
    s->gpu.hw.compute_shared_limits.bytes!=16384 ||
    !s->gpu.hw.compute_texture_transport || !s->gpu.hw.compute_storage_transport ||
    !s->gpu.hw.compute_image_transport || !s->gpu.hw.compute_counter_transport ||
    !s->gpu.hw.compute_uniform_transport || s->missing)goto fail;
 s->gpu.hw.shader_config=shader;
 if(indirect && (count!=122 ||
    !opengpu_read_compute_indirect_support(true,id,0x1000,s->gpu.hw.capabilities,
      &s->gpu.hw.compute_local_limits,s,compute_profile_read,&s->gpu.hw.compute_indirect_transport) ||
    !s->gpu.hw.compute_indirect_transport || s->missing))goto fail;
 s->indirect_declared=indirect;
 s->render=(struct opengpu_file){.session=s,.gpu=&s->gpu};
 s->file.driver_priv=&s->render;s->drm.dev=&s->gpu;
 if(opengpu_mmu_init(&s->gpu))goto fail;
 if(initial_context){
  struct drm_opengpu_context request={0};
  if(opengpu_compute_context_create_ioctl(&s->drm,&request,&s->file)){opengpu_mmu_fini(&s->gpu);goto fail;}
  s->context=opengpu_context_lookup(&s->render,request.id);s->context_id=request.id;
  assert(s->context);
 }
 opengpu_admission_init(&s->gpu);compute_running=s;return s;
fail:free(s->profile);free(s);return NULL;
}
struct compute_host_session *compute_host_create(uint32_t context,
 const struct compute_host_profile_word *words,size_t count)
{return context?compute_host_initialize(true,false,words,count):NULL;}
struct compute_host_session *compute_host_open(const struct compute_host_profile_word *words,size_t count)
{return compute_host_initialize(false,false,words,count);}
struct compute_host_session *compute_host_open_indirect(const struct compute_host_profile_word *words,size_t count)
{return compute_host_initialize(false,true,words,count);}
int compute_host_allocation_base(uint64_t physical)
{
 if(!physical || (physical&4095) || physical>UINT32_MAX)return -EINVAL;
 if(live_allocations || fixture_buffers || compute_running)return -EBUSY;
 next_dma=(dma_addr_t)physical;return 0;
}
static void compute_original_drop(struct compute_original *o)
{assert(o->refs);if(!--o->refs){
 dma_fence_put(o->resv.read);dma_fence_put(o->resv.write);o->resv.read=o->resv.write=NULL;
 opengpu_buffer_free(o->gpu,&o->backing);
}}
int compute_host_original_close(struct compute_host_session *s,uint32_t handle)
{struct compute_original *o=s && handle<512?s->render.originals[handle]:NULL;
 if(!o || o->closed)return -ENOENT;
 o->closed=true;compute_original_drop(o);return 0;}
int compute_host_query(struct compute_host_session *s,uint32_t param,uint64_t *value)
{if(!s || !value)return -EINVAL;struct drm_opengpu_param request={.param=param};
 int error=opengpu_compute_get_param_ioctl(&s->drm,&request,&s->file);if(!error)*value=request.value;return error;}
int compute_host_context_destroy(struct compute_host_session *s,uint32_t id)
{if(!s)return -EBUSY;
 struct compute_mmio_state *m=compute_mmio_state(&s->gpu);
 if(s->exported && (m->phase_cleanup?
    !(m->cleanup_active && s->phase_job_freed && id==s->phase_context):!m->cleanup_done))return -EBUSY;
 struct drm_opengpu_context request={.id=id};compute_running=s;
 int error=opengpu_compute_context_destroy_ioctl(&s->drm,&request,&s->file);
 if(!error && s->gpu.hw.wedged)return -EIO;
 if(!error && id==s->context_id)s->context=NULL;
 return error;}
int compute_host_context_create(struct compute_host_session *s,uint32_t *id)
{if(!s || !id || s->head || s->exported)return -EBUSY;
 struct drm_opengpu_context request={0};compute_running=s;
 int error=opengpu_compute_context_create_ioctl(&s->drm,&request,&s->file);
 if(!error){*id=request.id;s->context_id=request.id;s->context=opengpu_context_lookup(&s->render,request.id);}return error;}
static int compute_host_destroy_contexts(struct compute_host_session *s)
{for(u32 id=1;id<512;id++)if(idr_find(&s->render.contexts,id)){
 struct drm_opengpu_context request={.id=id};
 int error=opengpu_compute_context_destroy_ioctl(&s->drm,&request,&s->file);
 if(error || s->gpu.hw.wedged)return error?error:-EIO;
 if(id==s->context_id)s->context=NULL;
 }return 0;}
uint32_t compute_host_context_id(const struct compute_host_session *s)
{return s?s->context_id:0;}
void *compute_host_original_allocate(struct compute_host_session *s,uint32_t handle,uint64_t bytes)
{
 if(!s || s->exported || !handle || handle>=512 || s->render.originals[handle] ||
    !bytes || (bytes&4095) || bytes>SIZE_MAX || bytes>UINT32_MAX)return NULL;
 struct compute_original *o=calloc(1,sizeof(*o));if(!o)return NULL;
 if(opengpu_buffer_alloc(&s->gpu,&o->backing,(size_t)bytes)){free(o);return NULL;}
 o->gem=(struct drm_gem_dma_object){.base={.size=(size_t)bytes,.resv=&o->resv},
    .vaddr=o->backing.cpu,.dma_addr=o->backing.dma};o->refs=1;o->gpu=&s->gpu;
 s->render.originals[handle]=o;return o->gem.vaddr;
}
int compute_host_original_physical(struct compute_host_session *s,uint32_t handle,uint64_t *physical,uint64_t *bytes)
{struct compute_original *o=s && handle<512?s->render.originals[handle]:NULL;
 if(!o || !o->refs || !physical || !bytes)return -ENOENT;
 *physical=o->gem.dma_addr;*bytes=o->gem.base.size;return 0;}
int compute_host_bind(struct compute_host_session *s,const struct drm_opengpu_resource *binding)
{if(!s || !binding || s->exported)return -EBUSY;
 struct drm_opengpu_resource copy=*binding;return opengpu_compute_resource_bind_ioctl(&s->drm,&copy,&s->file);}
int compute_host_unbind(struct compute_host_session *s,uint32_t slot)
{if(!s || (s->exported && !compute_mmio_state(&s->gpu)->cleanup_active))return -EBUSY;
 struct drm_opengpu_resource request={.context_id=s->context_id,.slot=slot};
 return opengpu_compute_resource_unbind_ioctl(&s->drm,&request,&s->file);}

int compute_host_submit(struct compute_host_session *s,const struct drm_opengpu_compute *request)
{
 if(!s || !request || s->exported || s->head)return -EBUSY;
 compute_running=s;struct drm_opengpu_compute copy=*request;
 int error=opengpu_compute_launch_ioctl(&s->drm,&copy,&s->file);
 if(!error){memset(&s->submitted,0,sizeof(s->submitted));s->submitted.resources.launch=copy;
  s->context_id=copy.context_id;s->context=opengpu_context_lookup(&s->render,s->context_id);assert(s->context);}
 return error;
}

int compute_host_submit_uniforms(struct compute_host_session *s,const struct drm_opengpu_compute_uniform_resources *request)
{
 if(!s || !request || s->exported || s->head)return -EBUSY;
 compute_running=s;struct drm_opengpu_compute_uniform_resources copy=*request;
 int error=opengpu_compute_uniform_resources_ioctl(&s->drm,&copy,&s->file);
 if(!error){s->submitted=copy;s->context_id=copy.resources.launch.context_id;
  s->context=opengpu_context_lookup(&s->render,s->context_id);assert(s->context);}
 return error;
}

int compute_host_submit_counter_source(struct compute_host_session *s,const struct drm_opengpu_compute_counter_source_resources *request)
{
 if(!s || !request || s->exported || s->head)return -EBUSY;
 compute_running=s;struct drm_opengpu_compute_counter_source_resources copy=*request;
 int error=opengpu_compute_counter_source_resources_ioctl(&s->drm,&copy,&s->file);
 if(!error){s->submitted=copy.resources;s->context_id=copy.resources.resources.launch.context_id;
  s->context=opengpu_context_lookup(&s->render,s->context_id);assert(s->context);}
 return error;
}

int compute_host_submit_indirect(struct compute_host_session *s,const struct drm_opengpu_compute_indirect_resources *request)
{
 if(!s || !request || s->exported || s->head)return -EBUSY;
 if(!s->indirect_declared)return -EOPNOTSUPP;
 if(request->version!=OPENGPU_COMPUTE_INDIRECT_VERSION2 ||
    request->v2.flags!=OPENGPU_COMPUTE_INDIRECT_KERNARG_COUNTS || request->v2.reserved)return -EINVAL;
 compute_running=s;struct drm_opengpu_compute_indirect_resources copy=*request;
 int error=opengpu_compute_indirect_resources_ioctl(&s->drm,&copy,&s->file);
 if(!error){s->submitted_indirect=copy;s->submitted=copy.resources.resources;
  s->context_id=s->submitted.resources.launch.context_id;
  s->context=opengpu_context_lookup(&s->render,s->context_id);assert(s->context);}
 return error;
}

int compute_host_submit_storage(struct compute_host_session *s,const struct drm_opengpu_compute_storage *request)
{if(!s || !request || s->exported || s->head)return -EBUSY;
 compute_running=s;struct drm_opengpu_compute_storage copy=*request;
 int error=opengpu_compute_storage_ioctl(&s->drm,&copy,&s->file);
 if(!error){s->context_id=copy.launch.context_id;s->context=opengpu_context_lookup(&s->render,s->context_id);assert(s->context);
  memset(&s->submitted,0,sizeof(s->submitted));s->submitted.resources.launch=copy.launch;
  memcpy(s->submitted.resources.bindings,copy.bindings,sizeof(copy.bindings));
  s->submitted.resources.resource_flags=OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE;}
 return error;}
int compute_host_submit_image_resources(struct compute_host_session *s,const struct drm_opengpu_compute_image_resources *request)
{
 if(!s || !request || s->exported || s->head)return -EBUSY;
 /* Texture owners have a separate live capture contract. */
 if(request->textures || request->texture_count)return -EOPNOTSUPP;
 compute_running=s;struct drm_opengpu_compute_image_resources copy=*request;
 int error=opengpu_compute_image_resources_ioctl(&s->drm,&copy,&s->file);
 if(!error){
  memset(&s->submitted,0,sizeof(s->submitted));s->submitted.version=1;
  s->submitted.resources.launch=copy.launch;
  s->submitted.resources.resource_flags=OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE|OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES;
  memcpy(s->submitted.resources.bindings,copy.bindings,sizeof(copy.bindings));
  memcpy(s->submitted.resources.images,copy.images,sizeof(copy.images));
  s->context_id=copy.launch.context_id;s->context=opengpu_context_lookup(&s->render,s->context_id);assert(s->context);
 }
 return error;
}
int compute_host_submit_resources(struct compute_host_session *s,const struct drm_opengpu_compute_resources *request)
{
 if(!s || !request || s->exported || s->head)return -EBUSY;
 if(!request->views || !request->view_count)return -EINVAL;
 compute_running=s;struct drm_opengpu_compute_resources copy=*request;
 int error=opengpu_compute_resources_ioctl(&s->drm,&copy,&s->file);
 if(!error){
  memset(&s->submitted,0,sizeof(s->submitted));s->submitted.version=1;
  s->submitted.resources.launch=copy.launch;
  s->submitted.resources.resource_flags=OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE;
  memcpy(s->submitted.resources.bindings,copy.bindings,sizeof(copy.bindings));
  s->submitted.resources.textures=copy.views;s->submitted.resources.texture_count=copy.view_count;
  s->context_id=copy.launch.context_id;s->context=opengpu_context_lookup(&s->render,s->context_id);assert(s->context);
 }
 return error;
}
int compute_host_align_texture_publication_command(struct compute_host_session *s)
{
 if(!s || s->exported || s->head || s->captured)return -EBUSY;
 /* Match the one extra unified DMA the texture COMPUTE_RESOURCES path spends
  * before its compute kick (warm/reload command id 5 vs image producer 4). */
 s->gpu.hw.job_seqno+=1;return 0;
}
int compute_host_submit_images(struct compute_host_session *s,const struct drm_opengpu_compute_images *request)
{
 if(!s || !request || s->exported || s->head)return -EBUSY;
 compute_running=s;struct drm_opengpu_compute_images copy=*request;
 int error=opengpu_compute_images_ioctl(&s->drm,&copy,&s->file);
 if(!error){
  memset(&s->submitted,0,sizeof(s->submitted));s->submitted.version=1;
  s->submitted.resources.launch=copy.launch;
  s->submitted.resources.resource_flags=OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES;
  memcpy(s->submitted.resources.images,copy.views,sizeof(copy.views));
  s->context_id=copy.launch.context_id;s->context=opengpu_context_lookup(&s->render,s->context_id);assert(s->context);
 }
 return error;
}

/* OGCMP has at most 4096 exact byte intervals, independently of RTL capacity. */
#define COMPUTE_HOST_MAX_WRITABLE 4096u
struct compute_writable_range {u64 first,end;};
static int compute_writable_compare(const void *a,const void *b)
{const struct compute_writable_range *x=a,*y=b;return x->first<y->first?-1:x->first>y->first;}
static int compute_host_image_ranges(struct compute_host_session *s,
 struct compute_writable_range *ranges,unsigned *count)
{
 if(!(s->submitted.resources.resource_flags&OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES))return 0;
 for(unsigned i=0;i<4;i++){
  const struct drm_opengpu_compute_image_view *v=&s->submitted.resources.images[i];
  if(!v->slot)continue;
  if(v->slot>OPENGPU_MAX_RESOURCE_BINDINGS || v->pad || v->offset>UINT32_MAX ||
     (v->flags&~(OPENGPU_COMPUTE_IMAGE_READ|OPENGPU_COMPUTE_IMAGE_WRITE|
                 OPENGPU_COMPUTE_IMAGE_BY_SIZE|OPENGPU_COMPUTE_IMAGE_LAYERED)))return -EINVAL;
  const struct opengpu_resource_binding *b=s->context->bindings[v->slot-1];
  if(!b)return -EINVAL;
  bool buffer=v->target==5;
  u32 bytes=buffer?opengpu_texture_buffer_format_bytes(v->storage_format):
                   opengpu_compute_image_format_bytes(v->storage_format);
  struct opengpu_compute_image_descriptor d={.context=s->context_id,.unit=i,
   .storage_format=v->storage_format,.image_format=v->image_format,
   .level=v->level,.base_level=v->level,.max_level=v->level,.layer=v->layer,
   .readable=!!(v->flags&OPENGPU_COMPUTE_IMAGE_READ),.writable=!!(v->flags&OPENGPU_COMPUTE_IMAGE_WRITE),
   .compatible_by_size=!!(v->flags&OPENGPU_COMPUTE_IMAGE_BY_SIZE),.layered=!!(v->flags&OPENGPU_COMPUTE_IMAGE_LAYERED),
   .view={.target=v->target,.level=v->level,.base=(u32)v->offset,
    .width=buffer?1u:v->width,.height=v->height,.depth=v->depth,.layers=v->layers,
    .row_pitch=buffer?0:v->row_pitch,.slice_pitch=v->slice_pitch,.layer_pitch=v->layer_pitch,
    .block_bytes=bytes,.buffer_texels=buffer?v->buffer_texels:0,.buffer_bytes=buffer?v->buffer_bytes:0}};
  u32 checked[64];
  if(b->dma>UINT32_MAX || !opengpu_build_compute_image_descriptor(checked,&d,(u32)b->dma,b->size))return -EINVAL;
  if(!d.writable)continue;
  u64 first=b->dma+v->offset,row=buffer?(u64)v->buffer_texels*bytes:(u64)v->width*bytes;
  unsigned rows=buffer?1u:v->height,planes=1;
  u64 pitch=buffer?row:v->row_pitch,step=0;
  unsigned extent=v->target==1?v->layers:v->target==2?6u:v->target==3?v->depth:1u;
  if(v->target!=0 && !buffer){
   step=v->target==3?v->slice_pitch:v->layer_pitch;
   if(d.layered)planes=extent;else first+=(u64)v->layer*step;
  }
  /* Packed rows and planes need one interval, even for a large valid view.
   * Padding, unselected layers and incomplete buffer texels stay read-only. */
  if(pitch==row){row*=rows;rows=1;pitch=row;}
  if(rows==1 && planes>1 && step==row){row*=planes;planes=1;}
  if((u64)rows*planes>COMPUTE_HOST_MAX_WRITABLE-*count)return -E2BIG;
  for(unsigned plane=0;plane<planes;plane++)for(unsigned y=0;y<rows;y++){
   u64 start=first+(u64)plane*step+(u64)y*pitch;
   if(!row || start<b->dma || start>b->dma+b->size || row>b->dma+b->size-start)return -ERANGE;
   ranges[*count]=(struct compute_writable_range){start,start+row};++*count;
  }
 }
 return 0;
}
int compute_host_begin_execution(struct compute_host_session *s)
{
 if(!s || s->exported || s->prepared_execution || !s->head || s->head!=s->tail)return -EBUSY;
 if(s->submitted.resources.resource_flags&~(OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE|OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES))return -EOPNOTSUPP;
 /* Refuse an unrepresentable capture before offering a hardware command. */
 {struct compute_writable_range ranges[COMPUTE_HOST_MAX_WRITABLE];unsigned count=
    !opengpu_sched_job_from_base(s->head)->kernel.indirect_enabled;
  for(unsigned i=0;i<4;i++)count+=!!(s->submitted.resources.bindings[i].access&OPENGPU_COMPUTE_STORAGE_WRITE);
  count+=!!opengpu_sched_job_from_base(s->head)->kernel.counter_enabled;
  int error=compute_host_image_ranges(s,ranges,&count);if(error)return error;}
 compute_running=s;struct opengpu_sched_job *job=opengpu_sched_job_from_base(s->head);
 if(job->kernel.indirect_enabled && !s->indirect_declared)return -EOPNOTSUPP;
 compute_mmio_state(&s->gpu)->expected_kernel_opcode=job->kernel.indirect_enabled?
  GPU_UCMD_OP_KERNEL_INDIRECT:GPU_UCMD_OP_KERNEL;
 for(struct fixture_dependency *d=job->base.dependencies;d;d=d->next)if(!dma_fence_is_signaled(d->fence))return -EBUSY;
 opengpu_hw_enable_irqs(&s->gpu);struct dma_fence *fence=opengpu_job_run(job);
 if((intptr_t)fence<0 && (intptr_t)fence>=-4095)return (int)(intptr_t)fence;
 if(!fence)return -EINVAL;
 s->hardware=fence;s->prepared_execution=true;
 struct compute_mmio_state *m=compute_mmio_state(&s->gpu);
 assert(m->kernel_waiting && m->kernel_submissions==1 && !dma_fence_is_signaled(fence));
 return 0;
}
static int compute_host_snapshot_common(struct compute_host_session *s,struct compute_host_snapshot *out)
{
 if(!s || !out || !s->head || s->head!=s->tail)return -EINVAL;
 struct opengpu_sched_job *job=opengpu_sched_job_from_base(s->head);
 *out=(struct compute_host_snapshot){.code=job->shader.cpu,.code_bytes=job->shader.size,
  .code_physical=job->shader.dma,.storage=job->storage_records.cpu,.storage_bytes=job->storage_records.size,
  .storage_physical=job->storage_records.dma,.storage_root=job->kernel.storage.base,
  .uniforms=job->uniform_snapshot.cpu,.uniform_bytes=job->uniform_snapshot.size,
  .uniform_physical=job->uniform_snapshot.dma,.uniform_root=job->kernel.uniform.root,
  .counter_physical=job->counter_source.dma,.counter_bytes=job->counter_source.size,
  .counter_descriptor_physical=job->counter_records.dma,.counter_root=job->kernel.counter.base,
  .images=job->image_records.cpu,.image_bytes=job->image_records.size,
  .image_physical=job->image_records.dma,.image_root=job->kernel.image.base,
  .textures=job->texture_records.cpu,.texture_bytes=job->texture_records.size,
  .texture_physical=job->texture_records.dma,.texture_root=job->kernel.texture.root,
  .context=s->context_id,
  .kernarg_physical=s->context->bindings[s->submitted.resources.launch.kernarg_slot-1]->dma,
  .kernarg_bytes=s->context->bindings[s->submitted.resources.launch.kernarg_slot-1]->size,
  .retained_objects=job->object_count,.temporary_mappings=job->temporary_mapping_count};return 0;
}
int compute_host_snapshot_get(struct compute_host_session *s,struct compute_host_snapshot *out)
{
 if(s && s->head && opengpu_sched_job_from_base(s->head)->kernel.indirect_enabled)return -EOPNOTSUPP;
 return compute_host_snapshot_common(s,out);
}
int compute_host_indirect_snapshot_get(struct compute_host_session *s,struct compute_host_indirect_snapshot *out)
{
 if(!s || !out || !s->indirect_declared || !s->head || s->head!=s->tail)return -EINVAL;
 struct opengpu_sched_job *job=opengpu_sched_job_from_base(s->head);
 const struct drm_opengpu_compute_indirect_resources *q=&s->submitted_indirect;
 struct compute_original *source=q->source.handle<512?s->render.originals[q->source.handle]:NULL;
 if(!job->kernel.indirect_enabled || !source || !source->refs || !job->indirect_snapshot.cpu ||
    job->kernel.kernarg!=(u64)job->kernel.indirect_base+job->kernel.indirect_offset)return -EINVAL;
 struct compute_host_indirect_snapshot copy={0};
 int error=compute_host_snapshot_common(s,&copy.resources);if(error)return error;
 copy.original_kernarg_physical=copy.resources.kernarg_physical;
 copy.original_kernarg_bytes=copy.resources.kernarg_bytes;
 copy.arguments=job->indirect_snapshot.cpu;copy.snapshot_physical=job->indirect_snapshot.dma;
 copy.snapshot_bytes=job->indirect_snapshot.size;copy.snapshot_va=job->kernel.indirect_base;
 copy.snapshot_offset=job->kernel.indirect_offset;copy.source_handle=q->source.handle;
 copy.source_physical=source->gem.dma_addr;copy.source_bytes=source->gem.base.size;
 copy.source_offset=q->source.offset;copy.context=s->context_id;
 memcpy(copy.max_groups,q->max_groups,sizeof(copy.max_groups));memcpy(copy.grid,job->kernel.grid,sizeof(copy.grid));
 copy.resources.kernarg_physical=copy.snapshot_physical+copy.snapshot_offset;
 copy.resources.kernarg_bytes=12;*out=copy;return 0;
}
int compute_host_cancel_unexecuted(struct compute_host_session *s)
{
 if(!s || s->exported || s->prepared_execution || !s->head || s->head!=s->tail)return -EBUSY;
 if(s->submitted.resources.resource_flags&~(OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE|OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES))return -EOPNOTSUPP;
 /* Capture-budget failures must still permit cancellation before execution. */
 compute_running=s;struct drm_sched_job *base=s->head;
 base->s_fence->finished.error=-ECANCELED;dma_fence_signal(&base->s_fence->finished);
 opengpu_sched_free_job(base);if(s->gpu.hw.wedged)return -EIO;
 s->head=s->tail=NULL;s->queued=0;return 0;
}
static int compute_memory_le(FILE *f,u64 value,unsigned bytes)
{for(unsigned i=0;i<bytes;i++)if(fputc((value>>(8*i))&255,f)==EOF)return -EIO;return 0;}
static int compute_memory_compare(const void *a,const void *b)
{const struct fixture_buffer_record *x=*(const struct fixture_buffer_record *const *)a;
 const struct fixture_buffer_record *y=*(const struct fixture_buffer_record *const *)b;return x->dma<y->dma?-1:x->dma>y->dma;}
int compute_host_export_memory(struct compute_host_session *s,const char *path)
{
 if(!s || !path || s->exported || !s->prepared_execution)return -EINVAL;
 size_t count=0,pages=0;for(struct fixture_buffer_record *r=fixture_buffers;r;r=r->next){
  if(!r->cpu || !r->size || ((r->dma|r->size)&4095) || r->dma+r->size>(1ull<<32))return -ERANGE;
  ++count;pages+=r->size/4096;
 }
 if(!count || count>UINT32_MAX || pages>(1u<<20))return -ERANGE;
 struct fixture_buffer_record **ranges=malloc(count*sizeof(*ranges));if(!ranges)return -ENOMEM;
 size_t i=0;for(struct fixture_buffer_record *r=fixture_buffers;r;r=r->next)ranges[i++]=r;
 qsort(ranges,count,sizeof(*ranges),compute_memory_compare);
 for(i=1;i<count;i++)if(ranges[i-1]->dma+ranges[i-1]->size>ranges[i]->dma){free(ranges);return -EINVAL;}
 FILE *f=fopen(path,"wx");if(!f){free(ranges);return -errno;}
 int error=fwrite("OGMEM001",1,8,f)!=8?-EIO:0;
 error|=compute_memory_le(f,count,4);error|=compute_memory_le(f,pages,4);
 for(i=0;i<count;i++){
  error|=compute_memory_le(f,ranges[i]->dma,8);error|=compute_memory_le(f,ranges[i]->dma+ranges[i]->size,8);
  error|=compute_memory_le(f,0xa5,4);error|=compute_memory_le(f,0,4);
 }
 for(i=0;i<count;i++)for(size_t offset=0;offset<ranges[i]->size;offset+=4096){
  error|=compute_memory_le(f,ranges[i]->dma+offset,8);
  if(fwrite((u8 *)ranges[i]->cpu+offset,1,4096,f)!=4096)error=-EIO;
 }
 if(fclose(f))error=-EIO;
 free(ranges);if(error){remove(path);return -EIO;}
 error=fixture_mmu_visit_pte_pages(&s->gpu,compute_mmio_shadow_pte,compute_mmio_state(&s->gpu));
 if(error){remove(path);return error;}s->exported=true;return 0;
}

static int compute_host_empty_contexts(struct compute_host_session *s,FILE *out)
{
 unsigned count=0;
 for(u32 id=1;id<512;id++){
  struct opengpu_render_context *c=idr_find(&s->render.contexts,id);if(!c || c==s->context)continue;
  if(!c->vm.enabled || !c->vm.asid || c->vm.asid>GPU_SATP_ASID_MASK ||
     !c->vm.root.cpu || c->vm.root.size!=4096 || (c->vm.root.dma&4095) ||
     c->vm.root.dma>(1ull<<32)-4096)return -EOPNOTSUPP;
  for(unsigned slot=0;slot<OPENGPU_MAX_RESOURCE_BINDINGS;slot++)if(c->bindings[slot])return -EOPNOTSUPP;
  for(unsigned word=0;word<1024;word++)if(((const u32 *)c->vm.root.cpu)[word])return -EOPNOTSUPP;
  ++count;
 }
 if(!out)return 0;
 if(compute_mmio_le(out,count,4))return -EIO;
 for(u32 id=1;id<512;id++){
  struct opengpu_render_context *c=idr_find(&s->render.contexts,id);if(!c || c==s->context)continue;
  u32 satp=GPU_SATP_ENABLE|(c->vm.asid<<GPU_SATP_ASID_SHIFT)|(u32)(c->vm.root.dma>>12);
  if(compute_mmio_le(out,id,4) || compute_mmio_le(out,satp,4))return -EIO;
 }
 return 0;
}
static int compute_host_capture_common(struct compute_host_session *s,const char *path,uint64_t budget,uint32_t hold,bool indirect)
{
 if(!s || !s->exported || !s->prepared_execution || s->captured || !budget || budget>1000000000 || hold<8 || hold>4096)return -EINVAL;
 struct compute_mmio_state *m=compute_mmio_state(&s->gpu);
 struct opengpu_sched_job *job=opengpu_sched_job_from_base(s->head);
 if(job->kernel.indirect_enabled!=indirect || (indirect && (!s->indirect_declared || s->profile_count!=122)))return -EOPNOTSUPP;
 if(!m->kernel_waiting || !s->hardware || dma_fence_is_signaled(s->hardware))return -EBUSY;
 if(s->profile_count==122){int error=compute_host_empty_contexts(s,NULL);if(error)return error;}
 struct compute_writable_range ranges[COMPUTE_HOST_MAX_WRITABLE];unsigned count=0;
 struct opengpu_resource_binding *arg=s->context->bindings[s->submitted.resources.launch.kernarg_slot-1];
 if(!arg)return -EINVAL;
 if(!indirect){ranges[count].first=arg->dma;ranges[count++].end=arg->dma+arg->size;}
 for(unsigned i=0;i<4;i++)if(s->submitted.resources.bindings[i].access&OPENGPU_COMPUTE_STORAGE_WRITE){
  u32 slot=s->submitted.resources.bindings[i].slot;
  if(!slot || slot>OPENGPU_MAX_RESOURCE_BINDINGS)return -EINVAL;
  struct opengpu_resource_binding *b=s->context->bindings[slot-1];if(!b)return -EINVAL;
  ranges[count].first=b->dma;ranges[count++].end=b->dma+b->size;
 }
 if(job->kernel.counter_enabled){
  /* Independent original GEM only: exact logical words, never page/line padding. */
  if(!job->counter_source.object || !job->counter_source.dma || !job->counter_source.size ||
     job->counter_source.size>32 || (job->counter_source.size&3))return -EOPNOTSUPP;
  ranges[count].first=job->counter_source.dma;ranges[count++].end=job->counter_source.dma+job->counter_source.size;
 }
 {int error=compute_host_image_ranges(s,ranges,&count);if(error)return error;}
 qsort(ranges,count,sizeof(*ranges),compute_writable_compare);
 unsigned merged=0;
 for(unsigned i=0;i<count;i++){
  if(ranges[i].first>=ranges[i].end || ranges[i].end>(1ull<<32))return -ERANGE;
  /* Distinct original allocations can be physically adjacent. Preserve their
   * exact boundary; only truly overlapping binding aliases need a union. */
  if(merged && ranges[i].first<ranges[merged-1].end){
   if(ranges[i].end>ranges[merged-1].end)ranges[merged-1].end=ranges[i].end;
  }else ranges[merged++]=ranges[i];
 }
 count=merged;
 char *temporary=NULL;FILE *f=compute_mmio_file(path,&temporary);if(!f)return -EIO;
 fwrite(indirect?"OGCI0001":s->profile_count==122?"OGCMP003":s->profile_count==114?"OGCMP002":"OGCMP001",1,8,f);
 compute_mmio_le(f,budget,8);compute_mmio_le(f,hold,4);
 compute_mmio_le(f,s->gpu.hw.unified_command_id,4);compute_mmio_le(f,s->profile_count,4);
 for(size_t i=0;i<s->profile_count;i++){compute_mmio_le(f,s->profile[i].address,4);compute_mmio_le(f,s->profile[i].value,4);}
 if(indirect){
  struct compute_host_indirect_snapshot a;assert(!compute_host_indirect_snapshot_get(s,&a));
  compute_mmio_le(f,OPENGPU_COMPUTE_INDIRECT_VERSION2,4);compute_mmio_le(f,OPENGPU_COMPUTE_INDIRECT_KERNARG_COUNTS,4);
  compute_mmio_le(f,a.source_handle,4);compute_mmio_le(f,a.context,4);
  compute_mmio_le(f,a.source_physical,8);compute_mmio_le(f,a.source_bytes,8);compute_mmio_le(f,a.source_offset,8);
  compute_mmio_le(f,a.snapshot_physical,8);compute_mmio_le(f,a.snapshot_bytes,8);
  compute_mmio_le(f,a.snapshot_va,4);compute_mmio_le(f,a.snapshot_offset,4);
  compute_mmio_le(f,a.original_kernarg_physical,8);compute_mmio_le(f,a.original_kernarg_bytes,8);
  for(unsigned i=0;i<3;i++)compute_mmio_le(f,a.max_groups[i],4);
  for(unsigned i=0;i<3;i++)compute_mmio_le(f,a.grid[i],4);
 }
 compute_mmio_le(f,count,4);
 for(unsigned i=0;i<count;i++){compute_mmio_le(f,ranges[i].first,8);compute_mmio_le(f,ranges[i].end,8);}
 if(s->profile_count==122)assert(!compute_host_empty_contexts(s,f));
 compute_mmio_le(f,m->count,4);compute_mmio_actions(f,m,0);
 struct compute_writable_range *saved=count?malloc(count*sizeof(*saved)):NULL;
 if(count && !saved){fclose(f);remove(temporary);free(temporary);return -ENOMEM;}
 if(count)memcpy(saved,ranges,count*sizeof(*saved));
 int error=compute_mmio_close(f,path,temporary);
 if(!error){s->captured=true;s->captured_writable=saved;s->captured_writable_count=count;}
 else free(saved);
 return error;
}
int compute_host_capture(struct compute_host_session *s,const char *path,uint64_t budget,uint32_t hold)
{return compute_host_capture_common(s,path,budget,hold,false);}
int compute_host_capture_indirect(struct compute_host_session *s,const char *path,uint64_t budget,uint32_t hold)
{return compute_host_capture_common(s,path,budget,hold,true);}

static u32 compute_host_completion(u32 opcode,u32 status,u32 command)
{return ((opcode&7u)<<8)|((opcode>>3)<<16)|(status<<11)|command|(status?0:0x8000u);}
static int compute_host_drain_receipt(struct compute_host_session *s,
 const struct compute_host_held_receipt *held,const char *path,u32 opcode,u32 result_status)
{
 int expected_error=opengpu_hw_unified_result_error(opcode,result_status);
 if(!s || !held || !s->captured || s->drained || held->command_id!=s->gpu.hw.unified_command_id ||
  s->gpu.hw.unified_opcode!=opcode ||
  held->completion!=compute_host_completion(opcode,result_status,held->command_id) || held->processed_bytes || held->legacy_status ||
  !(held->status&GPU_UCMD_STATUS_COMPLETION) || held->irq!=(GPU_IRQ_ENABLE|GPU_IRQ_PENDING) ||
  held->irq_asserted!=1 || !s->hardware || dma_fence_is_signaled(s->hardware))return -EINVAL;
 struct compute_mmio_state *m=compute_mmio_state(&s->gpu);
 if(!m->kernel_waiting || m->receipt_loaded)return -EBUSY;
 char *temporary=NULL;FILE *f=compute_mmio_file(path,&temporary);if(!f)return -EIO;
 size_t start=m->count;compute_running=s;
 m->regs[GPU_REG_STATUS/4]=held->legacy_status;m->regs[GPU_REG_UCMD_STATUS/4]=held->status;
 m->regs[GPU_REG_IRQ/4]=held->irq;m->regs[GPU_REG_UCMD_COMPLETION/4]=held->completion;
 m->regs[GPU_REG_UCMD_COMPLETION_BYTES_LO/4]=(u32)held->processed_bytes;
 m->regs[GPU_REG_UCMD_COMPLETION_BYTES_HI/4]=(u32)(held->processed_bytes>>32);m->receipt_loaded=true;
 opengpu_irq_handler(0,&s->gpu);
 const struct opengpu_fault_snapshot *fault=&s->gpu.hw.last_fault;
 bool wrong_fault=expected_error && (!fault->sequence || fault->error!=expected_error ||
  fault->flags!=(OPENGPU_FAULT_VALID|OPENGPU_FAULT_COMPLETION_ERROR) ||
  fault->command_id!=held->command_id || fault->expected_command_id!=held->command_id ||
  fault->opcode!=opcode || fault->expected_opcode!=opcode ||
  fault->status!=result_status || fault->bytes_processed);
 if(!dma_fence_is_signaled(s->hardware) || s->hardware->error!=expected_error || m->kernel_waiting || wrong_fault){fclose(f);remove(temporary);free(temporary);return -EIO;}
 fwrite(opcode==GPU_UCMD_OP_KERNEL_INDIRECT?"OGCIT001":"OGCMT001",1,8,f);
 if(opcode==GPU_UCMD_OP_KERNEL_INDIRECT){compute_mmio_le(f,held->command_id,4);compute_mmio_le(f,result_status,4);}
 compute_mmio_le(f,m->count-start,4);compute_mmio_actions(f,m,start);
 int error=compute_mmio_close(f,path,temporary);if(!error){s->held=*held;s->drained=true;s->completion_error=expected_error;}return error;
}
int compute_host_drain_held(struct compute_host_session *s,const struct compute_host_held_receipt *held,const char *path)
{return compute_host_drain_receipt(s,held,path,GPU_UCMD_OP_KERNEL,GPU_UCMD_RESULT_SUCCESS);}
int compute_host_drain_failed(struct compute_host_session *s,const struct compute_host_held_receipt *held,const char *path)
{
 if(!held || (held->status&(GPU_UCMD_STATUS_OVERFLOW|GPU_UCMD_STATUS_RESET_BUSY|GPU_UCMD_STATUS_RESET_REJECTED)))return -EINVAL;
 return compute_host_drain_receipt(s,held,path,GPU_UCMD_OP_KERNEL,GPU_UCMD_KERNEL_EXECUTION_FAILED);
}
static bool compute_host_indirect_status(u32 status)
{return status==GPU_UCMD_RESULT_SUCCESS || status==GPU_UCMD_KERNEL_EXECUTION_FAILED ||
 status==GPU_INDIRECT_RESULT_BOUNDS || status==GPU_INDIRECT_RESULT_READ_FAILED;}
int compute_host_drain_indirect(struct compute_host_session *s,const struct compute_host_held_receipt *held,const char *path,uint32_t status)
{
 if(!s || !s->indirect_declared || !held || !compute_host_indirect_status(status) ||
    (held->status&~(GPU_UCMD_STATUS_READY|GPU_UCMD_STATUS_COMPLETION|GPU_UCMD_STATUS_VM_BUSY)))return -EINVAL;
 return compute_host_drain_receipt(s,held,path,GPU_UCMD_OP_KERNEL_INDIRECT,status);
}
static bool compute_same_held(const struct compute_host_held_receipt *a,const struct compute_host_held_receipt *b)
{return a->command_id==b->command_id && a->completion==b->completion && a->processed_bytes==b->processed_bytes &&
 a->legacy_status==b->legacy_status && a->status==b->status && a->irq==b->irq && a->irq_asserted==b->irq_asserted;}
static int compute_host_observe_receipt(struct compute_host_session *s,
 const struct compute_host_retired_receipt *receipt,int expected_error)
{
 u32 busy=GPU_UCMD_STATUS_COMPLETION|GPU_UCMD_STATUS_VM_BUSY|GPU_UCMD_STATUS_RESET_BUSY;
 if(!s || !receipt || !s->drained || s->observed || !compute_same_held(&s->held,&receipt->held) ||
  receipt->external_drained!=1 || receipt->irq_asserted || receipt->irq!=GPU_IRQ_ENABLE ||
  !(receipt->status&GPU_UCMD_STATUS_READY) || (receipt->status&busy) || !s->hardware ||
  !dma_fence_is_signaled(s->hardware) || s->hardware->error!=expected_error || s->completion_error!=expected_error)return -EINVAL;
 struct compute_mmio_state *m=compute_mmio_state(&s->gpu);
 m->regs[GPU_REG_UCMD_STATUS/4]=receipt->status;m->regs[GPU_REG_IRQ/4]=receipt->irq;
 if(expected_error)dma_fence_set_error(&s->head->s_fence->finished,expected_error);
 dma_fence_signal(&s->head->s_fence->finished);s->observed=true;return 0;
}
int compute_host_observe_retired(struct compute_host_session *s,const struct compute_host_retired_receipt *receipt)
{if(s && s->head && opengpu_sched_job_from_base(s->head)->kernel.indirect_enabled)return -EOPNOTSUPP;
 return compute_host_observe_receipt(s,receipt,0);}
int compute_host_observe_failed_retired(struct compute_host_session *s,const struct compute_host_retired_receipt *receipt)
{if(s && s->head && opengpu_sched_job_from_base(s->head)->kernel.indirect_enabled)return -EOPNOTSUPP;
 return compute_host_observe_receipt(s,receipt,-EIO);}
static bool compute_host_indirect_receipt(const struct compute_host_session *s,u32 status)
{return s && s->indirect_declared && s->head && opengpu_sched_job_from_base(s->head)->kernel.indirect_enabled &&
 compute_host_indirect_status(status) && s->held.completion==compute_host_completion(GPU_UCMD_OP_KERNEL_INDIRECT,status,s->held.command_id);}
int compute_host_observe_indirect_retired(struct compute_host_session *s,const struct compute_host_retired_receipt *receipt,uint32_t status)
{
 if(!compute_host_indirect_receipt(s,status) || !receipt || receipt->status!=GPU_UCMD_STATUS_READY)return -EINVAL;
 return compute_host_observe_receipt(s,receipt,opengpu_hw_unified_result_error(GPU_UCMD_OP_KERNEL_INDIRECT,status));
}
int compute_host_completion_status(struct compute_host_session *s,int32_t *status)
{
 if(!s || !status || !s->observed || s->freed || !s->hardware || !s->head || !s->head->s_fence)return -EINVAL;
 int hardware=dma_fence_get_status(s->hardware),finished=dma_fence_get_status(&s->head->s_fence->finished);
 if(hardware!=(s->completion_error?s->completion_error:1) || finished!=hardware)return -EIO;
 *status=finished;return 0;
}
int compute_host_begin_cleanup(struct compute_host_session *s,const char *directory)
{
 struct stat st;if(!s || !directory || lstat(directory,&st) || !S_ISDIR(st.st_mode))return -EINVAL;
 struct compute_mmio_state *m=compute_mmio_state(&s->gpu);
 if(!s->observed || s->freed || !s->exported || !m->pte || m->cleanup_active || m->cleanup_done ||
  m->cleanup_error || m->kernel_waiting || s->gpu.hw.unified_fence || s->gpu.hw.active_fence || s->gpu.hw.wedged)return -EBUSY;
 m->cleanup_directory=strdup(directory);if(!m->cleanup_directory)return -ENOMEM;
 char *path=compute_mmio_cleanup_path(m,"cleanup.begin",UINT32_MAX),*temporary=NULL;
 if(!path){free(m->cleanup_directory);m->cleanup_directory=NULL;return -ENOMEM;}
 FILE *f=compute_mmio_file(path,&temporary);if(!f){free(path);free(m->cleanup_directory);m->cleanup_directory=NULL;return -EIO;}
 fwrite("OGCLB001",1,8,f);compute_mmio_le(f,1,4);int error=compute_mmio_close(f,path,temporary);free(path);
 if(error){free(m->cleanup_directory);m->cleanup_directory=NULL;return error;}
 compute_running=s;m->cleanup_start=m->count;m->cleanup_active=true;return 0;
}
static int compute_host_finish_receipt(struct compute_host_session *s,int expected_error)
{
 if(!s || !s->observed || s->freed || !s->head || !s->hardware ||
  s->hardware->error!=expected_error || s->completion_error!=expected_error)return -EINVAL;
 struct compute_mmio_state *m=compute_mmio_state(&s->gpu);if(!m->cleanup_active || m->phase_cleanup || m->cleanup_error)return -EBUSY;
 compute_running=s;
 if(expected_error){
  /* The real context quiesce reports a completed job's error once and drops
   * only its context fence reference. Consume that expected report before
   * revocation; hardware/scheduler/reservation fences keep their error. */
  if(!s->context || !s->context->last_fence ||
   dma_fence_get_status(s->context->last_fence)!=expected_error)return -EINVAL;
  int error=opengpu_context_quiesce(&s->render,s->context);
  if(error!=expected_error || s->context->last_fence)return error?error:-EIO;
 }
 opengpu_sched_free_job(s->head);
 if(s->gpu.hw.wedged || m->cleanup_error)return -EIO;
 s->head=s->tail=NULL;s->queued=0;dma_fence_put(s->hardware);s->hardware=NULL;
 for(unsigned slot=1;slot<=OPENGPU_MAX_RESOURCE_BINDINGS;slot++)if(s->context->bindings[slot-1]){
  int error=compute_host_unbind(s,slot);if(error)return error;
 }
 int error=compute_host_destroy_contexts(s);
 if(error || m->cleanup_error)return error?error:-EIO;
 assert(!s->context);
 error=opengpu_mmu_fini(&s->gpu);if(error)return error;
 s->vm_destroyed=true;s->freed=true;return 0;
}
int compute_host_finish_observed(struct compute_host_session *s)
{if(s && s->head && opengpu_sched_job_from_base(s->head)->kernel.indirect_enabled)return -EOPNOTSUPP;
 return compute_host_finish_receipt(s,0);}
int compute_host_finish_failed_observed(struct compute_host_session *s)
{if(s && s->head && opengpu_sched_job_from_base(s->head)->kernel.indirect_enabled)return -EOPNOTSUPP;
 return compute_host_finish_receipt(s,-EIO);}
int compute_host_finish_cancelled_observed(struct compute_host_session *s)
{return s && s->cancelled?compute_host_finish_receipt(s,-ECANCELED):-EINVAL;}
int compute_host_finish_indirect_observed(struct compute_host_session *s,uint32_t status)
{
 if(!compute_host_indirect_receipt(s,status))return -EINVAL;
 return compute_host_finish_receipt(s,opengpu_hw_unified_result_error(GPU_UCMD_OP_KERNEL_INDIRECT,status));
}
int compute_host_end_cleanup(struct compute_host_session *s)
{
 if(!s || !s->freed || !s->vm_destroyed || s->head || s->gpu.hw.wedged)return -EBUSY;
 struct compute_mmio_state *m=compute_mmio_state(&s->gpu);
 if(!m->cleanup_active || m->phase_cleanup || m->cleanup_done || m->cleanup_error)return -EBUSY;
 if(m->count!=m->cleanup_start)m->cleanup_error=compute_mmio_cleanup_sync(&s->gpu);
 if(m->cleanup_error)return m->cleanup_error;
 if(!m->cleanup_sequence)return -EINVAL;
 char *path=compute_mmio_cleanup_path(m,"cleanup.done",UINT32_MAX),*temporary=NULL;if(!path)return -ENOMEM;
 FILE *f=compute_mmio_file(path,&temporary);if(!f){free(path);return -EIO;}
 fwrite("OGCLD001",1,8,f);compute_mmio_le(f,m->cleanup_sequence,4);
 int error=compute_mmio_close(f,path,temporary);free(path);if(!error){m->cleanup_active=false;m->cleanup_done=true;}return error;
}
#include "opengpu_compute_host_phase.inc"
int compute_host_destroy(struct compute_host_session *s)
{
 /* Exported physical state may be live in a DUT. Until the synchronous
  * completion/cleanup protocol is connected, fail closed rather than free. */
 if(!s || s->head || (s->retained_execution && !s->vm_destroyed) ||
    (s->gpu.hw.compute_mmio && compute_mmio_state(&s->gpu)->phase_cleanup) ||
    (s->exported && !compute_mmio_state(&s->gpu)->cleanup_done))return -EBUSY;
 compute_running=s;
 if(s->gpu.hw.wedged)return -EIO;
 if(s->context){
  for(unsigned slot=1;slot<=OPENGPU_MAX_RESOURCE_BINDINGS;slot++)if(s->context->bindings[slot-1]){
   int error=compute_host_unbind(s,slot);if(error)return error;
  }
  int error=compute_host_destroy_contexts(s);if(error)return error;
 }
 if(!s->vm_destroyed){int error=opengpu_mmu_fini(&s->gpu);if(error)return error;s->vm_destroyed=true;}
 for(unsigned h=1;h<512;h++)if(s->render.originals[h]){
  struct compute_original *o=s->render.originals[h];
  if(!o->closed){if(o->refs!=1)return -EBUSY;o->closed=true;compute_original_drop(o);}
  if(o->refs || o->backing.cpu)return -EBUSY;
  free(o);
 }
 opengpu_admission_fini(&s->gpu);
 struct compute_mmio_state *m=s->gpu.hw.compute_mmio;if(m){
  while(m->pte){struct compute_mmio_pte_shadow *p=m->pte;m->pte=p->next;free(p->words);free(p);}
  free(m->cleanup_directory);free(m->reset_directory);free(m->actions);free(m);
 }
 for(unsigned i=0;i<ARRAY_SIZE(s->sync);i++)dma_fence_put(s->sync[i].fence);
 compute_running=NULL;
 free(s->captured_writable);free(s->profile);free(s);return 0;
}
#ifdef OPENGPU_COMPUTE_HOST_TEST_MAIN
static u32 physical_word(u64 pa)
{for(struct fixture_buffer_record *r=fixture_buffers;r;r=r->next)
 if(pa>=r->dma && pa-r->dma<=r->size-4){u8 *b=(u8 *)r->cpu+(pa-r->dma);return b[0]|(u32)b[1]<<8|(u32)b[2]<<16|(u32)b[3]<<24;}
 assert(!"missing actual physical allocation");return 0;}
static u64 translate(struct opengpu_vm *vm,u32 va,u32 *flags)
{u32 upper=physical_word(vm->root.dma+(va>>22)*4);assert((upper&15)==1);
 u32 leaf=physical_word(((u64)(upper>>10)<<12)+((va>>12)&1023)*4);
 assert(leaf&1);*flags=leaf&0x3ff;return ((u64)(leaf>>10)<<12)|(va&4095);}
#include "compute_host_test_profile.inc"
static int test_fail_phase,test_bad_kind;
static unsigned test_phase_ack_failure;
static bool test_failed_image_receipts;
static bool test_failure_injected;
#include "compute_host_reset_test.h"
static void compute_host_test_cleanup_peer(struct opengpu_device *gpu,const char *directory,unsigned sequence)
{
 struct compute_mmio_state *m=compute_mmio_state(gpu);assert(m->cleanup_active && m->pte);
 char path[4096];assert(snprintf(path,sizeof(path),"%s/cleanup-%u.observed",directory,sequence)>0);
 FILE *f=fopen(path,"wx");assert(f);assert(fwrite(m->phase_cleanup && test_phase_ack_failure!=1?"OGCPA001":"OGCLA001",1,8,f)==8);
 int phase=!gpu->mmu.vms?3:compute_running->context && !compute_running->context->refcount.refs?2:1;
 bool bad=test_fail_phase==phase && !test_failure_injected;
 if(bad)test_failure_injected=true;
 compute_mmio_le(f,sequence+((bad && test_bad_kind==1) || (m->phase_cleanup && test_phase_ack_failure==2)?1:0),4);
 compute_mmio_le(f,GPU_UCMD_STATUS_READY|((bad && test_bad_kind==2) || (m->phase_cleanup && test_phase_ack_failure==3)?GPU_UCMD_STATUS_VM_BUSY:0),4);
 compute_mmio_le(f,GPU_IRQ_ENABLE,4);compute_mmio_le(f,0,4);compute_mmio_le(f,1,4);assert(!fclose(f));
}
#include "compute_host_images_test.h"
#include "compute_host_indirect_test.h"
#include "compute_host_phase_test.h"
#ifndef OPENGPU_COMPUTE_HOST_TEST_NO_MAIN
int main(int argc,char **argv)
{
 assert(argc==2 || argc==4);
 if(getenv("OPENGPU_COMPUTE_HOST_TEST_INDIRECT")){
  const char *failure=getenv("OPENGPU_COMPUTE_HOST_TEST_PHASE_ACK");test_phase_ack_failure=failure?(unsigned)atoi(failure):0;
  assert(test_phase_ack_failure<=3);
  if(!test_phase_ack_failure)test_compute_host_indirect(argv[1]);
  test_compute_host_phases(argv[1]);return 0;
 }
 {
  dma_addr_t original=next_dma;assert(!live_allocations && !fixture_buffers && !compute_running);
  const uint64_t bad_bases[]={0,1,0x100001,UINT64_C(0x100000000),UINT64_MAX};
  for(unsigned i=0;i<ARRAY_SIZE(bad_bases);i++)assert(compute_host_allocation_base(bad_bases[i])==-EINVAL && next_dma==original);
  for(unsigned i=1;i<=2;i++){
   uint64_t base=UINT64_C(0x10000000)*i;assert(!compute_host_allocation_base(base));
   struct opengpu_buffer b={0};assert(!compute_mmu_buffer_alloc(NULL,&b,4096) && b.dma==base);
   assert(compute_host_allocation_base(base+0x10000)==-EBUSY && next_dma==base+4096);
   compute_mmu_buffer_free(NULL,&b);
  }
  assert(!compute_host_allocation_base(original) && !live_allocations && !fixture_buffers);
 }
 test_failed_image_receipts=getenv("OPENGPU_COMPUTE_HOST_TEST_FAILED_IMAGES")!=NULL;
 test_cancelled_image_receipts=getenv("OPENGPU_COMPUTE_HOST_TEST_RESET_IMAGES")!=NULL;
 test_reset_bad=getenv("OPENGPU_COMPUTE_HOST_TEST_BAD_RESET")?atoi(getenv("OPENGPU_COMPUTE_HOST_TEST_BAD_RESET")):0;
 assert(test_reset_bad>=0 && test_reset_bad<=19 && !(test_failed_image_receipts && test_cancelled_image_receipts));
 if(test_cancelled_image_receipts){
  /* Only explicit CPU-shaped reset tests add this discovery capability.
   * Actual producers continue to use their unmodified native probe words. */
  assert(ARRAY_SIZE(test_profile)==122);
  for(unsigned i=0;i<ARRAY_SIZE(test_profile);i++)
   if(test_profile[i].address==GPU_REG_CAPABILITIES)test_profile[i].value|=GPU_CAP_UNIFIED_RESET;
 }
 assert(!unsetenv("OPENGPU_COMPUTE_HOST_TIMEOUT") && compute_host_timeout_seconds()==300);
 assert(!setenv("OPENGPU_COMPUTE_HOST_TIMEOUT","3600",1) && compute_host_timeout_seconds()==3600);
 assert(!setenv("OPENGPU_COMPUTE_HOST_TIMEOUT","604800",1) && compute_host_timeout_seconds()==604800);
 const char *bad_timeouts[]={"","0","604801","99999999999999999999999999999","-1","+1"," 1","1x"};
 for(unsigned i=0;i<ARRAY_SIZE(bad_timeouts);i++){
  assert(!setenv("OPENGPU_COMPUTE_HOST_TIMEOUT",bad_timeouts[i],1) && !compute_host_timeout_seconds());
  assert(!compute_host_create(7,test_profile,ARRAY_SIZE(test_profile)));
 }
 assert(!unsetenv("OPENGPU_COMPUTE_HOST_TIMEOUT"));
 if(argc==4){test_fail_phase=atoi(argv[2]);test_bad_kind=atoi(argv[3]);
  assert(test_fail_phase>=1 && test_fail_phase<=3 && test_bad_kind>=1 && test_bad_kind<=2);}
 if(getenv("OPENGPU_COMPUTE_HOST_TEST_IMAGES")){
  assert(argc==4 && test_fail_phase==1 && ARRAY_SIZE(test_profile)==122);
  test_compute_host_images(argv[1]);assert(0);
 }


 assert(!compute_host_create(0,test_profile,ARRAY_SIZE(test_profile)));
 for(size_t i=0;i<ARRAY_SIZE(test_profile);i++) {
  struct compute_host_profile_word old=test_profile[i];
  test_profile[i].address=0x1000;
  assert(!compute_host_open(test_profile,ARRAY_SIZE(test_profile)));
  assert(!compute_host_create(7,test_profile,ARRAY_SIZE(test_profile)));test_profile[i]=old;
 }
 for(size_t i=0;i<ARRAY_SIZE(test_profile);i++) {
  struct compute_host_profile_word old=test_profile[i];
  if(old.address==GPU_REG_CAPABILITIES) {
   const u32 required[]={GPU_CAP_UNIFIED_COMMANDS,GPU_CAP_PROTECTED_VM_STATE,
       GPU_CAP_COMPUTE_SCALAR_FPU,GPU_CAP_SHADER_CONFIG_INFO,GPU_CAP_NATIVE_PIXEL_FORMATS};
   for(unsigned cap=0;cap<ARRAY_SIZE(required);cap++){
    test_profile[i].value=old.value & ~required[cap];
    assert(!compute_host_create(7,test_profile,ARRAY_SIZE(test_profile)));test_profile[i]=old;
   }
   test_profile[i].value &= ~GPU_CAP_FRAGMENT_BATCH_MASK;
   assert(!compute_host_create(7,test_profile,ARRAY_SIZE(test_profile)));test_profile[i]=old;
   test_profile[i].value ^= 1u << GPU_CAP_FRAGMENT_BATCH_SHIFT;
   assert(!compute_host_create(7,test_profile,ARRAY_SIZE(test_profile)));test_profile[i]=old;
  }
  if(old.address==GPU_REG_SHADER_CONFIG) {
   for(unsigned banks=1;banks<=3;banks+=2){
    test_profile[i].value=(old.value & ~(GPU_SHADER_CONFIG_FIELD_MASK << GPU_SHADER_CONFIG_BANKS_SHIFT)) |
       (banks << GPU_SHADER_CONFIG_BANKS_SHIFT);
    assert(!compute_host_create(7,test_profile,ARRAY_SIZE(test_profile)));test_profile[i]=old;
   }
  }
  if(old.address==0x494 || old.address==0x43c || old.address==0x3fc || old.address==0x4bc || old.address==0x460) {
   test_profile[i].value^=1;assert(!compute_host_create(7,test_profile,ARRAY_SIZE(test_profile)));test_profile[i]=old;
   test_profile[i].address=0x5f0;assert(!compute_host_create(7,test_profile,ARRAY_SIZE(test_profile)));test_profile[i]=old;
  }
  if(i){test_profile[i].address=test_profile[0].address;
   assert(!compute_host_create(7,test_profile,ARRAY_SIZE(test_profile)));test_profile[i]=old;}
 }
 /* The actual GET_PARAM body may expose the per-lane owner only from its
  * checked hardware presence bit. This CPU discovery test is not a DUT probe. */
 for(unsigned enabled=0;enabled<2;enabled++){
  size_t flags=0;while(flags<ARRAY_SIZE(test_profile) && test_profile[flags].address!=0x368)flags++;
  assert(flags<ARRAY_SIZE(test_profile));u32 saved=test_profile[flags].value;
  test_profile[flags].value=(saved & ~(1u<<18)) | (enabled?1u<<18:0);
  struct compute_host_session *discovery=compute_host_create(7,test_profile,ARRAY_SIZE(test_profile));assert(discovery);
  u64 features=0;assert(!compute_host_query(discovery,OPENGPU_PARAM_DRIVER_FEATURES,&features));
  assert(!!(features&OPENGPU_DRIVER_FEATURE_COMPUTE_SHARED_ATOMICS)==!!enabled);
  assert(!compute_host_destroy(discovery));assert(!live_allocations && !fixture_buffers);
  test_profile[flags].value=saved;
 }
 /* A Gallium caller owns context creation. Opening its render file must not
  * insert an unused context/ASID into the later observed cleanup stream. */
 struct compute_host_session *opened=compute_host_open(test_profile,ARRAY_SIZE(test_profile));
 assert(opened && !compute_host_context_id(opened) && !opened->context);
 for(unsigned id=1;id<512;id++)assert(!idr_find(&opened->render.contexts,id));
 assert(!compute_host_destroy(opened) && !live_allocations && !fixture_buffers);
 opened=compute_host_open(test_profile,ARRAY_SIZE(test_profile));assert(opened);
 u32 first_context=0;assert(!compute_host_context_create(opened,&first_context) && first_context==1);
 assert(compute_host_context_id(opened)==first_context && opened->context);
 assert(!compute_host_context_destroy(opened,first_context) && !opened->context);
 assert(!compute_host_destroy(opened) && !live_allocations && !fixture_buffers);
 struct compute_host_session *session=compute_host_create(7,test_profile,ARRAY_SIZE(test_profile));
 assert(session && compute_host_context_id(session)==1);
 u64 query=0x123456789abcdef0ull;
 assert(compute_host_query(session,0xffff,&query)==-EINVAL && query==0x123456789abcdef0ull);
 assert(!compute_host_query(session,OPENGPU_PARAM_COMPUTE_LOCAL_ITEMS,&query) && query==128);
 assert(!compute_host_query(session,OPENGPU_PARAM_COMPUTE_SHARED_BYTES,&query) && query==16384);
 assert(!compute_host_query(session,OPENGPU_PARAM_DRIVER_FEATURES,&query) && (query&OPENGPU_DRIVER_FEATURE_COMPUTE_UNIFORMS));
 assert(compute_host_original_allocate(session,1,4096));
 u64 pa=0,bytes=0;assert(!compute_host_original_physical(session,1,&pa,&bytes) && pa && bytes==4096);
 struct drm_opengpu_resource api_bind={.context_id=1,.slot=2,.handle=1,.type=OPENGPU_RESOURCE_COMPUTE_KERNARG,.size=4096};
 assert(!compute_host_bind(session,&api_bind));
 assert(!compute_host_original_close(session,1) && session->render.originals[1]->refs==1);
 assert(!drm_gem_object_lookup(&session->file,1));
 assert(!compute_host_unbind(session,2));
 assert(!session->render.originals[1]->refs && !session->render.originals[1]->backing.cpu);
 u32 extra_context=0;assert(!compute_host_context_create(session,&extra_context) && extra_context==2);
 assert(!compute_host_destroy(session));assert(!live_allocations && !fixture_buffers);


 for(unsigned active=0;active<(ARRAY_SIZE(test_profile)==122?3u:2u);active++){
 bool counter=active==2 || (active==1 && getenv("OPENGPU_COMPUTE_HOST_TEST_COUNTER"));char counter_directory[4096];
 assert(snprintf(counter_directory,sizeof(counter_directory),"%s/counter",argv[1])>0);
 const char *directory=counter?counter_directory:argv[1];
 session=compute_host_create(7,test_profile,ARRAY_SIZE(test_profile));assert(session);
 /* Gallium owns a graphics context and a separate compute context on the
  * same DRM file. Keep other accepted, empty VMs through final cleanup. */
 u32 second=0,third=0;
 assert(!compute_host_context_create(session,&second) && second==2);
 assert(!compute_host_context_create(session,&third) && third==3);
 u32 *code=compute_host_original_allocate(session,1,4096);assert(code);
 const u32 program[]={(7u<<25)|(5u<<7)|0x0bu,0x0050a023u,0xc1087057u,
  0x5e003157u,(7u<<26)|(1u<<25)|(2u<<20)|(2u<<15)|(3u<<7)|0x2bu,OPENGPU_SHADER_CEASE};
 memset(code,0,128);memcpy(code,program,sizeof(program));
 assert(compute_host_original_allocate(session,2,4096));
 api_bind=(struct drm_opengpu_resource){.context_id=1,.slot=1,.handle=1,
  .type=OPENGPU_RESOURCE_COMPUTE_SHADER,.size=128};assert(!compute_host_bind(session,&api_bind));
 api_bind=(struct drm_opengpu_resource){.context_id=1,.slot=2,.handle=2,
  .type=OPENGPU_RESOURCE_COMPUTE_KERNARG,.size=4096};assert(!compute_host_bind(session,&api_bind));
 struct drm_opengpu_compute_uniform_resources uniform={.version=1,.active_mask=0xfff,
  .resources.resource_flags=OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE,
  .resources.launch={.context_id=1,.shader_slot=1,.kernarg_slot=2,.grid={4,1,1},.local={128,1,1},.out_syncobj=2}};
 for(unsigned i=0;i<13;i++) {
  unsigned bytes=i<12?16384:4096;u32 *data=compute_host_original_allocate(session,3+i,bytes);assert(data);
  for(unsigned w=0;w<bytes/4;w++)data[w]=0x41000000u+i*0x01000000u+w*0x9e3779b1u;
  struct drm_opengpu_compute_uniform_view *view=i<12?&uniform.blocks[i]:&uniform.defaults;
  *view=(struct drm_opengpu_compute_uniform_view){.handle=3+i,.bytes=bytes};
 }
 for(unsigned i=0;i<4;i++) {
  assert(compute_host_original_allocate(session,16+i,4096));
  api_bind=(struct drm_opengpu_resource){.context_id=1,.slot=13+i,.handle=16+i,
   .type=OPENGPU_RESOURCE_COMPUTE_STORAGE,.size=4096};assert(!compute_host_bind(session,&api_bind));
  uniform.resources.bindings[i]=(struct drm_opengpu_compute_storage_binding){.slot=13+i,
   .access=OPENGPU_COMPUTE_STORAGE_READ|OPENGPU_COMPUTE_STORAGE_WRITE};
 }
 struct dma_fence producer={.refs=1};session->render.originals[3]->resv.write=dma_fence_get(&producer);
 assert(compute_host_submit_uniforms(session,&uniform)==-ETIMEDOUT && !session->head && session->context->refcount.refs==1);
 producer.signaled=true;
 if(counter){
  u8 *data=compute_host_original_allocate(session,20,8192);assert(data);memset(data,0x5a,8192);
  struct drm_opengpu_compute_counter_source_resources request={.version=1,.resources=uniform,
   .source={.handle=20,.offset=4080,.count=8}};
  for(unsigned bad=0;bad<5;bad++){
   struct drm_opengpu_compute_counter_source_resources wrong=request;
   if(bad==0)wrong.version=2;
   if(bad==1)wrong.source.handle=16; /* aliases an active original storage GEM */
   if(bad==2)wrong.source.count=9;
   if(bad==3)wrong.source.offset=8180;
   if(bad==4)wrong.source.offset=4081;
   assert(compute_host_submit_counter_source(session,&wrong)<0 && !session->head);
  }
  struct dma_fence *old=session->render.originals[20]->resv.write;
  session->render.originals[20]->resv.write=dma_fence_get(&producer);producer.signaled=false;
  assert(compute_host_submit_counter_source(session,&request)==-ETIMEDOUT && !session->head);
  producer.signaled=true;assert(!compute_host_submit_counter_source(session,&request));
  dma_fence_put(old);
  assert(session->render.originals[20]->refs>1);
  /* Closing the public handle cannot release the accepted original source. */
  assert(!compute_host_original_close(session,20) && session->render.originals[20]->backing.cpu);
 }else assert(!compute_host_submit_uniforms(session,&uniform));
 struct compute_host_snapshot snapshot;assert(!compute_host_snapshot_get(session,&snapshot));
 assert(snapshot.uniform_bytes==204800 && snapshot.code_bytes==128 && snapshot.retained_objects==(counter?19u:18u) && snapshot.storage_bytes==256);
 assert(!memcmp(snapshot.code,program,sizeof(program)) && !memcmp((u8 *)snapshot.uniforms+4096,
  session->render.originals[3]->gem.vaddr,16384));
 memset(session->render.originals[3]->gem.vaddr,0x73,16384);
 assert(((u32 *)((u8 *)snapshot.uniforms+4096))[0]==0x41000000u);
 assert(compute_host_unbind(session,2)==-ETIMEDOUT && session->context->bindings[1]);

 if(counter){
  assert(snapshot.counter_bytes==32 && snapshot.counter_physical==session->render.originals[20]->gem.dma_addr+4080);
  assert(snapshot.counter_root && snapshot.counter_descriptor_physical);
 }
 if(!active) {
  assert(!compute_host_cancel_unexecuted(session));assert(session->context->refcount.refs==1);
  assert(compute_host_destroy(session)==-ECANCELED);
 }else{
  assert(!compute_host_begin_execution(session));
  assert(compute_host_cancel_unexecuted(session)==-EBUSY && compute_host_destroy(session)==-EBUSY);
  struct opengpu_sched_job *job=opengpu_sched_job_from_base(session->head);u32 flags;
  assert(translate(&session->context->vm,(u32)job->kernel.kernel_pc,&flags)==job->shader.dma && (flags&15)==15);
  assert(translate(&session->context->vm,job->kernel.uniform.root,&flags)==job->uniform_snapshot.dma && (flags&15)==3);
  if(counter){
   assert(translate(&session->context->vm,job->counter_source.va,&flags)==job->counter_source.dma && (flags&15)==15);
   assert(translate(&session->context->vm,job->counter_source.va+31,&flags)==job->counter_source.dma+31);
  }
  assert(job->temporary_mapping_count==(counter?5u:3u) && session->context->refcount.refs==2 && !dma_fence_is_signaled(session->hardware));
  char path[4096];assert(snprintf(path,sizeof(path),"%s/union.memory",directory)>0);
  assert(!compute_host_export_memory(session,path));
  assert(snprintf(path,sizeof(path),"%s/record-0.capture",directory)>0);
  assert(!compute_host_capture(session,path,1000000,37));
  assert(compute_host_capture(session,path,1000000,37)==-EINVAL);
  assert(snprintf(path,sizeof(path),"%s/record-0.retire",directory)>0);
  struct compute_host_held_receipt held={.command_id=session->gpu.hw.unified_command_id,
   .completion=0x8000u|session->gpu.hw.unified_command_id,.legacy_status=0,
   .status=GPU_UCMD_STATUS_COMPLETION|GPU_UCMD_STATUS_VM_BUSY,.irq=GPU_IRQ_ENABLE|GPU_IRQ_PENDING,.irq_asserted=1};
  struct compute_host_held_receipt bad=held;bad.command_id^=1;
  assert(compute_host_drain_held(session,&bad,path)==-EINVAL && !dma_fence_is_signaled(session->hardware));
  assert(!compute_host_drain_held(session,&held,path));assert(dma_fence_is_signaled(session->hardware));
  struct compute_host_retired_receipt retired={.held=held,.status=GPU_UCMD_STATUS_READY,.irq=GPU_IRQ_ENABLE};
  assert(compute_host_observe_retired(session,&retired)==-EINVAL && !session->head->s_fence->finished.signaled);
  retired.external_drained=1;assert(!compute_host_observe_retired(session,&retired));
  assert(compute_host_destroy(session)==-EBUSY && job->uniform_snapshot.cpu && session->context->refcount.refs==2);
  assert(!compute_host_begin_cleanup(session,directory));
  int cleanup=compute_host_finish_observed(session);
  if(test_fail_phase){
   assert(test_failure_injected && cleanup<0 && session->gpu.hw.wedged);
   assert(!compute_mmio_state(&session->gpu)->cleanup_done && !session->freed);
   assert(compute_host_end_cleanup(session)==-EBUSY && compute_host_destroy(session)<0);
   for(unsigned h=1;h<=19;h++)assert(session->render.originals[h] && session->render.originals[h]->backing.cpu);
   if(test_fail_phase==1){
    if(counter)assert(session->render.originals[20]->refs && session->render.originals[20]->backing.cpu);
    assert(session->head && job->shader.cpu && job->uniform_snapshot.cpu && job->storage_records.cpu);
    assert(session->context && session->context->refcount.refs==2);
   }
   if(test_fail_phase<=2){
    assert(session->context && session->context->vm.enabled && session->context->vm.root.cpu);
    bool linked=false;for(struct opengpu_vm *vm=session->gpu.mmu.vms;vm;vm=vm->next)if(vm==&session->context->vm)linked=true;
    assert(linked && session->context->vm.asid);
    if(test_fail_phase==2)assert(!session->head && session->context->refcount.refs==0);
   }else assert(!session->context && !session->gpu.mmu.vms);
   assert(session->gpu.mmu.enabled && session->gpu.mmu.root.cpu);
   /* Quarantine deliberately has no public recovery/free operation. This
    * isolated negative-test process ends while every asserted allocation is
    * still retained; it never fabricates a later successful ACK. */
   puts("compute real-MMU bad ACK retained reachable allocations: PASS (CPU transport only)");
   fflush(stdout);_Exit(0);
  }
  assert(!cleanup);
  assert(session->vm_destroyed && !session->context && !session->head && !session->gpu.mmu.vms);
  assert(!compute_host_end_cleanup(session));
 }
 assert(!compute_host_destroy(session));assert(producer.refs==1 && !live_allocations && !fixture_buffers);
 }
 if(argc==2 && ARRAY_SIZE(test_profile)==122)test_compute_host_images(argv[1]);
 puts("compute actual Linux ioctl/snapshot/scheduler/MMU/MMIO/IRQ/cleanup CPU transport: PASS (no GPU execution)");return 0;
}

#endif
#endif
