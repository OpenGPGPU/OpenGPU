/* CPU transport for the exact production MMIO/IRQ functions. Captured native
 * completion data must come from the DUT; the invalidate control peer is
 * replayed and checked against that DUT before any qualification can pass. */
#ifdef OPENGPU_NATIVE_MMIO_DECLARE
#include "../opengpu_native_completion.h"
#include "../opengpu_tessellation_completion.h"
#include "../opengpu_atomic_counter_command.h"
#include "../opengpu_compute_texture_command.h"
#include "../opengpu_compute_uniform_command.h"
#include "../opengpu_storage_command.h"
#include "../opengpu_shader_image_command.h"
#include "native_mmio_fault_under_test.inc"
struct opengpu_vm;
struct opengpu_buffer;
static int native_mmio_wait_idle(struct opengpu_device *);
static int native_mmio_flush_tlbs(struct opengpu_device *);
static int native_mmio_flush_asid(struct opengpu_device *,u32);
static int native_mmio_activate_vm(struct opengpu_device *,const struct opengpu_vm *);
static int native_mmio_enable_mmu(struct opengpu_device *,u64);
static int native_mmio_invalidate(struct opengpu_device *,const struct opengpu_buffer *);
static int native_mmio_fence_wait(struct opengpu_device *,struct dma_fence *,unsigned,bool);
static void native_mmio_progress(struct opengpu_device *);
#define OPENGPU_MMU_TEST_HW_EXTRA \
    void *native_mmio; \
    bool compute_texture_transport,compute_storage_transport,compute_image_transport; \
    bool compute_counter_transport,compute_uniform_transport,compute_indirect_transport; \
    bool reset_pending,aborting; \
    struct dma_fence *unified_fence,*active_fence; \
    u32 unified_command_id,unified_opcode,unified_completion_delay_ms,active_completion_delay_ms; \
    u64 unified_expected_bytes,fence_context,fence_seqno,regs_size; \
    u32 job_seqno; \
    int timeout_work,poll_work,completion_work,reset_work; \
    struct opengpu_fault_snapshot last_fault;
#define OPENGPU_MMU_TEST_WAIT_IDLE(g) native_mmio_wait_idle(g)
#define OPENGPU_MMU_TEST_FLUSH_TLBS(g) native_mmio_flush_tlbs(g)
#define OPENGPU_MMU_TEST_FLUSH_ASID(g,a) native_mmio_flush_asid(g,a)
#define OPENGPU_MMU_TEST_ACTIVATE_VM(g,v) native_mmio_activate_vm(g,v)
#define OPENGPU_MMU_TEST_ENABLE_MMU(g,r) native_mmio_enable_mmu(g,r)
#define OPENGPU_PRIVATE_SNAPSHOT_INVALIDATE(g,b) native_mmio_invalidate(g,b)
#else
#include <unistd.h>
#include <sys/stat.h>
#include "../opengpu_tlb_flush.h"
#include "../opengpu_unified_completion.h"
struct opengpu_kernel_launch {
    u32 indirect_base,indirect_bytes,indirect_offset;
    u64 kernel_pc,kernarg;u32 grid[3],local[3];bool texture_enabled,indirect_enabled;
    struct opengpu_compute_texture_command_context texture;
    bool storage_enabled;struct opengpu_storage_command_context storage;
    bool image_enabled;struct opengpu_image_command_context image;
    bool counter_enabled;struct opengpu_atomic_counter_command_context counter;
    bool uniform_enabled;struct opengpu_compute_uniform_command_context uniform;
};
struct opengpu_command_events {u32 flags,wait_event,signal_event;};
struct native_mmio_action {u32 kind,address,value,mask;};
struct native_mmio_state {
    u32 regs[1024];struct native_mmio_action *actions;size_t count,capacity,start;
    bool native_waiting,receipt_loaded,exported;
    unsigned native_submissions,invalidates,pops;u64 allocation_signature;
};
static struct native_mmio_state *native_mmio_state(struct opengpu_device *gpu)
{
    struct native_mmio_state *m=gpu->hw.native_mmio;
    if(!m){m=calloc(1,sizeof(*m));assert(m);gpu->hw.native_mmio=m;
        m->regs[GPU_REG_UCMD_STATUS/4]=GPU_UCMD_STATUS_READY;
        m->regs[GPU_REG_IRQ/4]=0;
        gpu->hw.fence_context=dma_fence_context_alloc(1);gpu->hw.regs_size=0x1000;}
    return m;
}
static void native_mmio_action(struct native_mmio_state *m,u32 kind,u32 a,u32 v,u32 mask)
{
    assert(!(a&3) && a<0x1000);
    if(m->count==m->capacity){size_t n=m->capacity?m->capacity*2:1024;
        void *p=realloc(m->actions,n*sizeof(*m->actions));assert(p);m->actions=p;m->capacity=n;}
    m->actions[m->count++]=(struct native_mmio_action){kind,a,v,mask};
}
static u32 opengpu_reg_read(struct opengpu_device *gpu,u32 address)
{
    struct native_mmio_state *m=native_mmio_state(gpu);assert(!(address&3) && address<0x1000);
    u32 value=m->regs[address/4],mask=~0u,kind=3;
    if(address==GPU_REG_UCMD_STATUS){kind=2;
        mask=GPU_UCMD_STATUS_COMPLETION|GPU_UCMD_STATUS_VM_BUSY|GPU_UCMD_STATUS_RESET_BUSY;
        if(!(value&GPU_UCMD_STATUS_COMPLETION))mask|=GPU_UCMD_STATUS_READY|
            GPU_UCMD_STATUS_OVERFLOW|GPU_UCMD_STATUS_RESET_REJECTED;}
    native_mmio_action(m,kind,address,value&mask,mask);return value;
}
static void opengpu_reg_write(struct opengpu_device *gpu,u32 address,u32 value)
{
    struct native_mmio_state *m=native_mmio_state(gpu);native_mmio_action(m,1,address,value,0);
    assert(address!=GPU_REG_UCMD_RESET); /* Active series never resets. */
    if(address==GPU_REG_UCMD_SUBMIT){
        assert(value==1 && !(m->regs[GPU_REG_UCMD_STATUS/4]&GPU_UCMD_STATUS_COMPLETION));
        u32 opcode=m->regs[GPU_REG_UCMD_OPCODE/4];
        if(opcode==GPU_UCMD_OP_INVALIDATE){
            /* CPU control response only: actual DUT replay checks every read. */
            ++m->invalidates;
            m->regs[GPU_REG_UCMD_STATUS/4]=GPU_UCMD_STATUS_COMPLETION|GPU_UCMD_STATUS_VM_BUSY;
            m->regs[GPU_REG_UCMD_COMPLETION/4]=0x8000u|(opcode<<8)|m->regs[GPU_REG_UCMD_ID/4];
            m->regs[GPU_REG_UCMD_COMPLETION_BYTES_LO/4]=m->regs[GPU_REG_UCMD_BYTES/4];
            m->regs[GPU_REG_UCMD_COMPLETION_BYTES_HI/4]=0;
        }else{assert(opcode==GPU_UCMD_OP_NATIVE_BINNED_RENDER && !m->native_waiting);
            m->native_waiting=true;++m->native_submissions;m->regs[GPU_REG_UCMD_STATUS/4]=GPU_UCMD_STATUS_VM_BUSY;}
    }else if(address==GPU_REG_UCMD_COMPLETION_POP){
        assert(value==1 && (m->regs[GPU_REG_UCMD_STATUS/4]&GPU_UCMD_STATUS_COMPLETION));
        assert(!m->native_waiting || m->receipt_loaded);++m->pops;
        m->regs[GPU_REG_UCMD_STATUS/4]=GPU_UCMD_STATUS_READY;
        m->regs[GPU_REG_UCMD_COMPLETION/4]=0;
        m->regs[GPU_REG_UCMD_COMPLETION_BYTES_LO/4]=m->regs[GPU_REG_UCMD_COMPLETION_BYTES_HI/4]=0;
        for(unsigned n=0;n<12;n++)m->regs[0x180/4+n]=0;
        m->native_waiting=false;m->receipt_loaded=false;
    }else if(address==GPU_REG_IRQ){
        m->regs[address/4]=(m->regs[address/4]&~(value&6u)&~1u)|(value&1u);
    }else m->regs[address/4]=value;
}
static u32 opengpu_mmio_read_callback(void *gpu,u32 address){return opengpu_reg_read(gpu,address);}
static void opengpu_mmio_write_callback(void *gpu,u32 address,u32 value){opengpu_reg_write(gpu,address,value);}
#define upper_32_bits(v) ((u32)((u64)(v)>>32))
#define U32_MAX UINT32_MAX
#define dma_fence_set_error(f,e) ((f)->error=(e))
#define OPENGPU_HW_POLL_INTERVAL_MS 5
#define system_dfl_wq 0
#define mod_delayed_work(q,w,t) ((void)(q),(void)(w),(void)(t),0)
#define cancel_delayed_work(w) ((void)(w),0)
#define cancel_delayed_work_sync(w) ((void)(w),0)
#define dma_fence_signal_locked(f) dma_fence_signal(f)
#define dev_info(...) ((void)0)
#define cond_resched() (++jiffies)
static void opengpu_display_handle_vblank(struct opengpu_device *gpu){(void)gpu;assert(0);}
static void opengpu_hw_complete(struct opengpu_device *gpu,int error){(void)gpu;(void)error;assert(0);}
static void opengpu_hw_abort(struct opengpu_device *gpu,int error){(void)error;gpu->hw.wedged=true;}
typedef int irqreturn_t;
#define IRQ_HANDLED 1
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-parameter"
#pragma GCC diagnostic ignored "-Wunused-value"
#include "native_mmio_fence_under_test.inc"
#include "native_mmio_fault_record_under_test.inc"
#include "native_mmio_job_id_under_test.inc"
#include "native_mmio_reset_pending_under_test.inc"
#include "native_mmio_result_error_under_test.inc"
#include "native_mmio_drain_under_test.inc"
#include "native_mmio_irq_under_test.inc"
#include "native_mmio_enable_irqs_under_test.inc"
#include "native_mmio_quiesce_under_test.inc"
#include "native_mmio_kick_under_test.inc"
#include "native_mmio_set_satp_under_test.inc"
#include "native_mmio_activate_locked_under_test.inc"
#include "native_mmio_busy_under_test.inc"
#define opengpu_hw_wait_idle_locked native_mmio_wait_idle
#include "native_mmio_wait_idle_under_test.inc"
#undef opengpu_hw_wait_idle_locked
#define opengpu_hw_activate_vm native_mmio_activate_vm
#include "native_mmio_activate_under_test.inc"
#undef opengpu_hw_activate_vm
#define opengpu_hw_enable_mmu native_mmio_enable_mmu
#include "native_mmio_enable_under_test.inc"
#undef opengpu_hw_enable_mmu
#define opengpu_hw_flush_tlbs native_mmio_flush_tlbs
#include "native_mmio_flush_under_test.inc"
#undef opengpu_hw_flush_tlbs
#define opengpu_hw_flush_tlb_asid native_mmio_flush_asid
#include "native_mmio_flush_asid_under_test.inc"
#undef opengpu_hw_flush_tlb_asid
#include "native_mmio_submit_under_test.inc"
#define opengpu_hw_invalidate_buffer_locked native_mmio_invalidate
#include "native_mmio_invalidate_under_test.inc"
#undef opengpu_hw_invalidate_buffer_locked
#pragma GCC diagnostic pop
static void native_mmio_progress(struct opengpu_device *gpu)
{struct native_mmio_state *m=native_mmio_state(gpu);
 if(gpu->hw.unified_fence && !m->native_waiting)opengpu_hw_unified_drain(gpu);}
static int native_mmio_fence_wait(struct opengpu_device *gpu,struct dma_fence *f,unsigned ms,bool interruptible)
{
    assert(f && ms==OPENGPU_DRAW_WAIT_MS+100 && !gpu->hw.fence_lock);
    if(interruptible){if(running->wait_error)return running->wait_error;return dma_fence_is_signaled(f)?f->error:-ETIMEDOUT;}
    assert(gpu->hw.submit_lock.held);
    native_mmio_progress(gpu);return dma_fence_is_signaled(f)?f->error:-ETIMEDOUT;
}
static int native_mmio_le(FILE *f,u64 value,unsigned bytes)
{for(unsigned n=0;n<bytes;n++)if(fputc((int)((value>>(8*n))&255),f)==EOF)return -EIO;return 0;}
static int native_mmio_actions(FILE *f,struct native_mmio_state *m,size_t first)
{
    for(size_t i=first;i<m->count;i++){
        const struct native_mmio_action *a=&m->actions[i];
        if(native_mmio_le(f,a->kind,4)||native_mmio_le(f,a->address,4)||
            native_mmio_le(f,a->value,4)||native_mmio_le(f,a->mask,4))return -EIO;
    }return 0;
}
struct native_mmio_range {u64 first,end;};
static int native_mmio_range_compare(const void *a,const void *b)
{const struct native_mmio_range *x=a,*y=b;return x->first<y->first?-1:x->first>y->first;}
static u64 native_mmio_allocation_signature(void)
{
    u64 hash=1469598103934665603ull;
    for(struct fixture_buffer_record *b=fixture_buffers;b;b=b->next){
        hash=(hash^b->dma)*1099511628211ull;hash=(hash^b->size)*1099511628211ull;
        hash=(hash^(uintptr_t)b->cpu)*1099511628211ull;
    }return hash;
}
static FILE *native_mmio_file(const char *path,char **temporary)
{
    struct stat st;if(!path || !*path || !lstat(path,&st) || errno!=ENOENT)return NULL;
    size_t size=strlen(path)+5;*temporary=malloc(size);if(!*temporary)return NULL;
    snprintf(*temporary,size,"%s.tmp",path);FILE *f=fopen(*temporary,"wbx");
    if(!f){free(*temporary);*temporary=NULL;}return f;
}
static int native_mmio_close(FILE *f,const char *path,char *temporary)
{
    int error=ferror(f)?-EIO:0;if(fflush(f))error=-EIO;if(fclose(f))error=-EIO;
    if(!error && link(temporary,path))error=-errno;
    if(remove(temporary) && !error)error=-errno;
    free(temporary);return error;
}
static int native_mmio_buffer_compare(const void *a,const void *b)
{
    const struct fixture_buffer_record *x=*(const struct fixture_buffer_record * const *)a;
    const struct fixture_buffer_record *y=*(const struct fixture_buffer_record * const *)b;
    return x->dma<y->dma?-1:x->dma>y->dma;
}
int native_kernel_export_memory(struct native_kernel_session *s,const char *path)
{
    if(!s || !s->profile || !s->records)return -EINVAL;
    struct native_mmio_state *m=native_mmio_state(&s->gpu);
    if(m->exported || m->native_waiting || s->gpu.hw.unified_fence)return -EBUSY;
    size_t count=0,pages=0;for(struct fixture_buffer_record *b=fixture_buffers;b;b=b->next){
        if(!b->cpu || !b->size || (b->dma&4095) || (b->size&4095) || b->dma+b->size>(1ull<<32))return -ERANGE;
        ++count;pages+=b->size/4096;
    }
    if(!count || count>UINT32_MAX || pages>(1u<<20))return -ERANGE;
    struct fixture_buffer_record **buffers=malloc(count*sizeof(*buffers));if(!buffers)return -ENOMEM;
    size_t n=0;for(struct fixture_buffer_record *b=fixture_buffers;b;b=b->next)buffers[n++]=b;
    qsort(buffers,count,sizeof(*buffers),native_mmio_buffer_compare);
    for(size_t i=1;i<count;i++)if(buffers[i-1]->dma+buffers[i-1]->size>buffers[i]->dma){free(buffers);return -EINVAL;}
    char *temporary=NULL;FILE *f=native_mmio_file(path,&temporary);if(!f){free(buffers);return -EIO;}
    fwrite("OGMEM001",1,8,f);native_mmio_le(f,count,4);native_mmio_le(f,pages,4);
    for(size_t i=0;i<count;i++){
        native_mmio_le(f,buffers[i]->dma,8);native_mmio_le(f,buffers[i]->dma+buffers[i]->size,8);
        native_mmio_le(f,0xa5,4);native_mmio_le(f,0,4);
    }
    for(size_t i=0;i<count;i++)for(size_t offset=0;offset<buffers[i]->size;offset+=4096){
        native_mmio_le(f,buffers[i]->dma+offset,8);fwrite((const u8 *)buffers[i]->cpu+offset,1,4096,f);
    }
    free(buffers);int error=native_mmio_close(f,path,temporary);
    if(!error){m->exported=true;m->allocation_signature=native_mmio_allocation_signature();}return error;
}
int opengpu_hw_native_binned_async(struct opengpu_device *,struct opengpu_private_snapshot *,
    const struct opengpu_render_context *,u32,struct dma_fence **);
int native_kernel_capture(struct native_kernel_session *s,unsigned index,const char *path,u64 budget,u32 hold)
{
    struct native_kernel_record *r=native_session_record(s,index);
    struct opengpu_sched_job *job=native_mmio_record_job(s,index);
    if(!r || !job || !s->profile || r->captured || r->freed || !budget || budget>1000000000 || hold<8 || hold>4096)return -EINVAL;
    struct native_mmio_state *m=native_mmio_state(&s->gpu);
    if(!m->exported || m->native_waiting || m->allocation_signature!=native_mmio_allocation_signature())return -EBUSY;
    for(struct native_kernel_record *old=s->records;old && old!=r;old=old->next)if(!old->verified)return -EBUSY;
    for(struct fixture_dependency *d=job->base.dependencies;d;d=d->next)if(!dma_fence_is_signaled(d->fence))return -EBUSY;
    struct native_mmio_range *ranges=NULL;size_t count=0;
    for(struct snapshot_resource *resource=job->native_owner->resources;resource;resource=resource->next)
        for(struct snapshot_resource_map *map=resource->maps;map;map=map->next)if(map->writable){
            void *p=realloc(ranges,(count+1)*sizeof(*ranges));if(!p){free(ranges);return -ENOMEM;}ranges=p;
            ranges[count++]=(struct native_mmio_range){map->physical,map->physical+map->bytes};
        }
    if(!count || count>4096){free(ranges);return -ERANGE;}
    qsort(ranges,count,sizeof(*ranges),native_mmio_range_compare);size_t merged=0;
    for(size_t i=0;i<count;i++){
        if(merged && ranges[i].first<ranges[merged-1].end){
            if(ranges[i].end>ranges[merged-1].end)ranges[merged-1].end=ranges[i].end;
        }else ranges[merged++]=ranges[i];
    }
    char *temporary=NULL;FILE *f=native_mmio_file(path,&temporary);if(!f){free(ranges);return -EIO;}
    running=s;copy_gpu=&s->gpu;
    if(!m->native_submissions)opengpu_hw_enable_irqs(&s->gpu);
    int error=opengpu_hw_native_binned_async(&s->gpu,job->native_owner,job->context,job->native_job_segment,&r->hardware);
    if(error){fclose(f);remove(temporary);free(temporary);free(ranges);return error;}
    assert(m->native_waiting && r->hardware && !dma_fence_is_signaled(r->hardware));
    if(m->allocation_signature!=native_mmio_allocation_signature()){fclose(f);remove(temporary);free(temporary);free(ranges);return -EIO;}
    fwrite("OGCAP001",1,8,f);native_mmio_le(f,budget,8);native_mmio_le(f,hold,4);
    native_mmio_le(f,s->gpu.hw.unified_command_id,4);native_mmio_le(f,101,4);
    for(unsigned i=0;i<101;i++){
        native_mmio_le(f,s->profile->words[i].address,4);native_mmio_le(f,s->profile->words[i].value,4);
    }
    native_mmio_le(f,merged,4);for(size_t i=0;i<merged;i++){
        native_mmio_le(f,ranges[i].first,8);native_mmio_le(f,ranges[i].end,8);
    }free(ranges);
    native_mmio_le(f,m->count-m->start,4);native_mmio_actions(f,m,m->start);
    native_mmio_le(f,13,4);
    native_mmio_le(f,GPU_REG_UCMD_COMPLETION,4);native_mmio_le(f,UINT32_MAX,4);
    native_mmio_le(f,0x8700u|s->gpu.hw.unified_command_id,4);
    for(unsigned i=0;i<12;i++){
        u32 expected=i==0?OPENGPU_NATIVE_BINNED_COMPLETION_MAGIC:
            i==6?s->gpu.hw.native_context:i==7?s->gpu.hw.native_pass:i==8?s->gpu.hw.native_generation:0;
        native_mmio_le(f,0x180+4*i,4);native_mmio_le(f,(i==2||i==3)?0:UINT32_MAX,4);native_mmio_le(f,expected,4);
    }
    native_mmio_le(f,0,4);error=native_mmio_close(f,path,temporary);
    if(!error){r->captured=true;m->start=m->count;}return error;
}
int native_kernel_drain_held(struct native_kernel_session *s,unsigned index,
    const struct native_kernel_held_receipt *held,const char *path)
{
    struct native_kernel_record *r=native_session_record(s,index);struct opengpu_native_completion decoded;
    if(!r || !r->job || !held || !r->captured || r->drained || r->freed || held->record_index!=index ||
        held->command_id!=s->gpu.hw.unified_command_id || held->completion!=(0x8700u|held->command_id) ||
        !(held->held_status&GPU_UCMD_STATUS_COMPLETION) || held->held_irq!=(GPU_IRQ_ENABLE|GPU_IRQ_PENDING) ||
        held->held_irq_asserted!=1 || !r->hardware || dma_fence_is_signaled(r->hardware) ||
        s->gpu.hw.native_pending_owner!=r->job->native_owner ||
        !opengpu_native_completion_decode(held->gnx,12,0,held->append_read_bytes,
            s->gpu.hw.native_context,s->gpu.hw.native_pass,s->gpu.hw.native_generation,&decoded))return -EINVAL;
    struct native_mmio_state *m=native_mmio_state(&s->gpu);
    if(!m->native_waiting || m->receipt_loaded)return -EBUSY;
    char *temporary=NULL;FILE *f=native_mmio_file(path,&temporary);if(!f)return -EIO;
    running=s;copy_gpu=&s->gpu;size_t start=m->count;
    m->regs[GPU_REG_STATUS/4]=held->held_legacy_status;m->regs[GPU_REG_UCMD_STATUS/4]=held->held_status;
    m->regs[GPU_REG_IRQ/4]=held->held_irq;m->regs[GPU_REG_UCMD_COMPLETION/4]=held->completion;
    m->regs[GPU_REG_UCMD_COMPLETION_BYTES_LO/4]=(u32)held->append_read_bytes;
    m->regs[GPU_REG_UCMD_COMPLETION_BYTES_HI/4]=(u32)(held->append_read_bytes>>32);
    for(unsigned i=0;i<12;i++)m->regs[0x180/4+i]=held->gnx[i];
    m->receipt_loaded=true;
    opengpu_irq_handler(0,&s->gpu);
    if(!dma_fence_is_signaled(r->hardware) || r->hardware->error || s->gpu.hw.native_pending_owner || m->native_waiting){
        fclose(f);remove(temporary);free(temporary);return -EIO;
    }
    fwrite("OGRTR001",1,8,f);native_mmio_le(f,m->count-start,4);native_mmio_actions(f,m,start);
    int error=native_mmio_close(f,path,temporary);if(!error){r->held=*held;r->drained=true;m->start=m->count;}
    return error;
}
static void native_mmio_release(struct native_kernel_session *s)
{
    struct native_mmio_state *m=s->gpu.hw.native_mmio;
    if(m){assert(!s->gpu.hw.unified_fence && !s->gpu.hw.active_fence);free(m->actions);free(m);s->gpu.hw.native_mmio=NULL;}
    for(struct native_kernel_record *r=s->records;r;r=r->next){free(r->mmio_state);r->mmio_state=NULL;}
}
#endif
