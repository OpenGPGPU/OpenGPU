/* CPU transport for the exact production MMIO/IRQ functions. Captured native
 * completion data must come from the DUT; the invalidate control peer is
 * replayed and checked against that DUT before any qualification can pass. */
#ifdef OPENGPU_NATIVE_MMIO_DECLARE
#include "../opengpu_native_completion.h"
#include "../opengpu_tessellation_completion.h"
#include "../opengpu_tessellation_joint_host.h"
#include "../opengpu_atomic_counter_command.h"
#include "../opengpu_compute_texture_command.h"
#include "../opengpu_compute_uniform_command.h"
#include "../opengpu_storage_command.h"
#include "../opengpu_shader_image_command.h"
#include "native_mmio_fault_under_test.inc"
#include "native_mmio_joint_scope_under_test.inc"
struct opengpu_vm;
struct opengpu_buffer;
static int native_mmio_wait_idle(struct opengpu_device *);
static int native_mmio_flush_tlbs(struct opengpu_device *);
static int native_mmio_set_satp(struct opengpu_device *,u64,u32);
static int native_mmio_disable_mmu(struct opengpu_device *);
static int native_mmio_flush_asid(struct opengpu_device *,u32);
static int native_mmio_activate_vm(struct opengpu_device *,const struct opengpu_vm *);
static int native_mmio_enable_mmu(struct opengpu_device *,u64);
static int native_mmio_invalidate(struct opengpu_device *,const struct opengpu_buffer *);
static int native_mmio_fence_wait(struct opengpu_device *,struct dma_fence *,unsigned,bool);
static void native_mmio_progress(struct opengpu_device *);
static void native_mmio_dma_wmb(void);
#define OPENGPU_MMU_TEST_HW_EXTRA \
    void *native_mmio; \
    bool compute_texture_transport,compute_storage_transport,compute_image_transport; \
    bool compute_counter_transport,compute_uniform_transport,compute_indirect_transport; \
    bool reset_pending;unsigned int aborting; \
    struct dma_fence *unified_fence,*active_fence; \
    struct opengpu_tessellation_joint_completion *joint_pending_completion; \
    struct opengpu_tessellation_joint_geometry_completion *joint_geometry_pending_completion; \
    u32 joint_geometry_context,joint_geometry_pass,joint_geometry_generation; \
    struct opengpu_protected_tessellation_joint_profile joint_tessellation; \
    struct opengpu_protected_tessellation_joint_geometry_profile joint_geometry; \
    u32 unified_command_id,unified_opcode,unified_completion_delay_ms,active_completion_delay_ms; \
    u64 unified_expected_bytes,fence_context,fence_seqno,regs_size; \
    u32 job_seqno; \
    int timeout_work,poll_work,completion_work,reset_work; \
    struct opengpu_fault_snapshot last_fault;
#define OPENGPU_MMU_TEST_WAIT_IDLE(g) native_mmio_wait_idle(g)
#define OPENGPU_MMU_TEST_SET_SATP(g,r,a) native_mmio_set_satp(g,r,a)
#define OPENGPU_MMU_TEST_DISABLE_MMU(g) native_mmio_disable_mmu(g)
#define OPENGPU_MMU_TEST_FLUSH_TLBS(g) native_mmio_flush_tlbs(g)
#define OPENGPU_MMU_TEST_FLUSH_ASID(g,a) native_mmio_flush_asid(g,a)
#define OPENGPU_MMU_TEST_ACTIVATE_VM(g,v) native_mmio_activate_vm(g,v)
#define OPENGPU_MMU_TEST_ENABLE_MMU(g,r) native_mmio_enable_mmu(g,r)
#define OPENGPU_MMU_TEST_DMA_WMB() native_mmio_dma_wmb()
#define OPENGPU_PRIVATE_SNAPSHOT_INVALIDATE(g,b) native_mmio_invalidate(g,b)
#else
#include <unistd.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <time.h>
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
struct native_mmio_pte_shadow {
    struct native_mmio_pte_shadow *next;u64 physical;size_t size;u32 *words;
};
struct native_mmio_state {
    u32 regs[1024];struct native_mmio_action *actions;size_t count,capacity,start;
    bool native_waiting,receipt_loaded,exported;
    bool recording_reset,issued_reset;
    unsigned native_submissions,invalidates,pops;u64 allocation_signature;
    struct native_mmio_pte_shadow *pte;
    char *cleanup_directory;size_t cleanup_start;unsigned cleanup_sequence;
    int cleanup_error;bool cleanup_active,cleanup_done;
};
static int native_mmio_cleanup_sync(struct opengpu_device *);
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
    assert(!m->cleanup_done); /* Session destruction is host-only after ACK. */
    assert(!(a&3) && (kind==4 || a<0x1000));
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
static u32 native_mmio_joint_detail_address(unsigned word)
{
    const unsigned head=OPENGPU_TESS_JOINT_HOST_COMPLETION_HEAD_WORDS;
    return word<head?OPENGPU_TESS_JOINT_HOST_COMPLETION_HEAD_BASE+4u*word:
        OPENGPU_TESS_JOINT_HOST_COMPLETION_TAIL_BASE+4u*(word-head);
}
static u32 native_mmio_joint_geometry_detail_address(unsigned word)
{
    return word<12u?OPENGPU_JOINT_GEOMETRY_COMPLETION_PREFIX_BASE+4u*word:
        word<OPENGPU_JOINT_GEOMETRY_COMPLETION_PREFIX_WORDS?OPENGPU_JOINT_GEOMETRY_COMPLETION_LEGACY_TAIL_BASE+4u*(word-12u):
        OPENGPU_JOINT_GEOMETRY_COMPLETION_GEOMETRY_BASE+4u*(word-OPENGPU_JOINT_GEOMETRY_COMPLETION_PREFIX_WORDS);
}
static void opengpu_reg_write(struct opengpu_device *gpu,u32 address,u32 value)
{
    struct native_mmio_state *m=native_mmio_state(gpu);native_mmio_action(m,1,address,value,0);
    if(address==GPU_REG_UCMD_RESET){
        assert(m->recording_reset && m->native_waiting && !m->issued_reset && value==GPU_UCMD_RESET_REQUEST);
        m->issued_reset=true;
    }else if(address==GPU_REG_UCMD_SUBMIT){
        assert(value==1 && !(m->regs[GPU_REG_UCMD_STATUS/4]&GPU_UCMD_STATUS_COMPLETION));
        u32 opcode=m->regs[GPU_REG_UCMD_OPCODE/4];
        if(opcode==GPU_UCMD_OP_INVALIDATE){
            /* CPU control response only: actual DUT replay checks every read. */
            ++m->invalidates;
            m->regs[GPU_REG_UCMD_STATUS/4]=GPU_UCMD_STATUS_COMPLETION|GPU_UCMD_STATUS_VM_BUSY;
            m->regs[GPU_REG_UCMD_COMPLETION/4]=0x8000u|(opcode<<8)|m->regs[GPU_REG_UCMD_ID/4];
            m->regs[GPU_REG_UCMD_COMPLETION_BYTES_LO/4]=m->regs[GPU_REG_UCMD_BYTES/4];
            m->regs[GPU_REG_UCMD_COMPLETION_BYTES_HI/4]=0;
        }else{
            assert(!m->native_waiting);
            if(opcode==OPENGPU_TESS_JOINT_HOST_OPCODE){
                assert((gpu->hw.joint_pending_completion &&
                    (m->regs[GPU_REG_UCMD_PATTERN/4]&~7u)==OPENGPU_TESS_JOINT_HOST_COMMAND_TAG) ||
                    (gpu->hw.joint_geometry_pending_completion &&
                    (m->regs[GPU_REG_UCMD_PATTERN/4]&~7u)==OPENGPU_JOINT_GEOMETRY_HOST_COMMAND_TAG));
            }else assert(opcode==GPU_UCMD_OP_NATIVE_BINNED_RENDER && !gpu->hw.joint_pending_completion &&
                !gpu->hw.joint_geometry_pending_completion);
            m->native_waiting=true;++m->native_submissions;m->regs[GPU_REG_UCMD_STATUS/4]=GPU_UCMD_STATUS_VM_BUSY;}
    }else if(address==GPU_REG_UCMD_COMPLETION_POP){
        assert(value==1 && (m->regs[GPU_REG_UCMD_STATUS/4]&GPU_UCMD_STATUS_COMPLETION));
        assert(!m->native_waiting || m->receipt_loaded);++m->pops;
        m->regs[GPU_REG_UCMD_STATUS/4]=GPU_UCMD_STATUS_READY;
        m->regs[GPU_REG_UCMD_COMPLETION/4]=0;
        m->regs[GPU_REG_UCMD_COMPLETION_BYTES_LO/4]=m->regs[GPU_REG_UCMD_COMPLETION_BYTES_HI/4]=0;
        for(unsigned n=0;n<OPENGPU_TESS_JOINT_HOST_COMPLETION_WORDS;n++)
            m->regs[native_mmio_joint_detail_address(n)/4]=0;
        for(unsigned n=0;n<OPENGPU_JOINT_GEOMETRY_COMPLETION_WORDS;n++)
            m->regs[native_mmio_joint_geometry_detail_address(n)/4]=0;
        for(unsigned n=0;n<OPENGPU_JOINT_GEOMETRY_COMPLETION_RESERVED_WORDS;n++)
            m->regs[(OPENGPU_JOINT_GEOMETRY_COMPLETION_RESERVED_BASE+4*n)/4]=0;
        m->native_waiting=false;m->receipt_loaded=false;
    }else if(address==GPU_REG_IRQ){
        m->regs[address/4]=(m->regs[address/4]&~(value&6u)&~1u)|(value&1u);
    }else m->regs[address/4]=value;
    /* A successful shootdown must reach this same live DUT before the actual
     * MMU function can return and release the original/private backing. */
    if(address==GPU_REG_UCMD_TLB_FLUSH && m->cleanup_active && !m->cleanup_error)
        m->cleanup_error=native_mmio_cleanup_sync(gpu);
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
#define opengpu_hw_complete native_mmio_complete_actual
#include "native_mmio_complete_under_test.inc"
#include "native_mmio_reset_request_under_test.inc"
#define opengpu_hw_abort native_mmio_abort_actual
#include "native_mmio_abort_under_test.inc"
#undef opengpu_hw_abort
#undef opengpu_hw_complete
#include "native_mmio_reset_pending_under_test.inc"
#include "native_mmio_result_error_under_test.inc"
#include "native_mmio_drain_under_test.inc"
#include "native_mmio_irq_under_test.inc"
#include "native_mmio_enable_irqs_under_test.inc"
#include "native_mmio_quiesce_under_test.inc"
#include "native_mmio_kick_under_test.inc"
#define opengpu_hw_set_satp native_mmio_set_satp
#include "native_mmio_set_satp_under_test.inc"
#undef opengpu_hw_set_satp
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
#define opengpu_hw_flush_tlbs native_mmio_flush_tlbs_actual
#include "native_mmio_flush_under_test.inc"
#undef opengpu_hw_flush_tlbs
#define opengpu_hw_flush_tlb_asid native_mmio_flush_asid_actual
#include "native_mmio_flush_asid_under_test.inc"
#undef opengpu_hw_flush_tlb_asid
/* Preserve the production function bodies. A failed remote MMIO transport is
 * surfaced through their existing int error boundary, so actual MMU rollback
 * and owner quarantine run before backing can be released. */
static int native_mmio_flush_tlbs(struct opengpu_device *gpu)
{
    struct native_mmio_state *m=native_mmio_state(gpu);
    if(m->cleanup_error)return m->cleanup_error;
    int error=native_mmio_flush_tlbs_actual(gpu);
    return error?error:m->cleanup_error;
}
static int native_mmio_flush_asid(struct opengpu_device *gpu,u32 asid)
{
    struct native_mmio_state *m=native_mmio_state(gpu);
    if(m->cleanup_error)return m->cleanup_error;
    int error=native_mmio_flush_asid_actual(gpu,asid);
    return error?error:m->cleanup_error;
}
#define opengpu_hw_disable_mmu_locked native_mmio_disable_mmu
#define opengpu_hw_flush_tlbs native_mmio_flush_tlbs
#include "native_mmio_disable_under_test.inc"
#undef opengpu_hw_flush_tlbs
#undef opengpu_hw_disable_mmu_locked
#include "native_mmio_submit_under_test.inc"
#define opengpu_hw_invalidate_buffer_locked native_mmio_invalidate
#include "native_mmio_invalidate_under_test.inc"
#undef opengpu_hw_invalidate_buffer_locked
#include "native_mmio_joint_profile_under_test.inc"
#include "native_mmio_joint_geometry_profile_under_test.inc"
#define opengpu_hw_wait_idle_locked native_mmio_wait_idle
#include "native_mmio_joint_submit_under_test.inc"
#include "native_mmio_joint_geometry_submit_under_test.inc"
#undef opengpu_hw_wait_idle_locked
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
static int native_mmio_shadow_pte(void *opaque,dma_addr_t physical,const void *bytes,size_t size)
{
    struct native_mmio_state *m=opaque;
    struct native_mmio_pte_shadow *p=calloc(1,sizeof(*p));if(!p)return -ENOMEM;
    p->words=malloc(size);if(!p->words){free(p);return -ENOMEM;}
    memcpy(p->words,bytes,size);p->physical=physical;p->size=size;p->next=m->pte;m->pte=p;
    return 0;
}
static int native_mmio_diff_pte(void *opaque,dma_addr_t physical,const void *bytes,size_t size)
{
    struct native_mmio_state *m=opaque;struct native_mmio_pte_shadow *p=m->pte;
    while(p && p->physical!=physical)p=p->next;
    if(!p || p->size!=size)return -EIO;
    const u32 *current=bytes;
    for(size_t n=0;n<size/4;n++)if(current[n]!=p->words[n]){
        /* Cleanup only revokes existing mappings. Rollback after transport
         * failure stays local and can never produce a successful receipt. */
        if(current[n] || !(p->words[n]&1) || physical+4*n>UINT32_MAX)return -EIO;
        native_mmio_action(m,4,(u32)(physical+4*n),0,p->words[n]);p->words[n]=0;
    }
    return 0;
}
static void native_mmio_dma_wmb(void)
{
    if(!running || !running->gpu.hw.native_mmio)return;
    struct native_mmio_state *m=running->gpu.hw.native_mmio;
    if(m->cleanup_active && !m->cleanup_error)
        m->cleanup_error=fixture_mmu_visit_pte_pages(&running->gpu,native_mmio_diff_pte,m);
}
static char *native_mmio_cleanup_path(const struct native_mmio_state *m,const char *suffix,unsigned sequence)
{
    size_t size=strlen(m->cleanup_directory)+80;char *path=malloc(size);if(!path)return NULL;
    if(sequence==UINT32_MAX)snprintf(path,size,"%s/%s",m->cleanup_directory,suffix);
    else snprintf(path,size,"%s/cleanup-%u.%s",m->cleanup_directory,sequence,suffix);
    return path;
}
static int native_mmio_read_le(FILE *f,u32 *value)
{
    *value=0;for(unsigned n=0;n<4;n++){int c=fgetc(f);if(c==EOF)return -EIO;*value|=(u32)c<<(8*n);}return 0;
}
static int native_mmio_cleanup_ack(const char *path,unsigned sequence)
{
    struct stat before,st;if(lstat(path,&before) || !S_ISREG(before.st_mode))return -EINVAL;
    int flags=O_RDONLY;
#ifdef O_NOFOLLOW
    flags|=O_NOFOLLOW;
#endif
    int fd=open(path,flags);if(fd<0)return -errno;
    if(fstat(fd,&st) || !S_ISREG(st.st_mode) || st.st_size!=28 ||
        before.st_dev!=st.st_dev || before.st_ino!=st.st_ino){close(fd);return -EINVAL;}
    FILE *f=fdopen(fd,"rb");if(!f){close(fd);return -EIO;}
    char magic[8];u32 v[5];int error=fread(magic,1,8,f)!=8 || memcmp(magic,"OGCLA001",8)?-EINVAL:0;
    for(unsigned n=0;n<5 && !error;n++)error=native_mmio_read_le(f,&v[n]);
    if(!error && (v[0]!=sequence || v[1]!=GPU_UCMD_STATUS_READY || v[2]!=GPU_IRQ_ENABLE || v[3] || v[4]!=1))error=-EINVAL;
    if(fclose(f) && !error)error=-EIO;
    return error;
}
static int native_mmio_timeout_seconds(unsigned *seconds)
{
    const char *configured=getenv("OPENGPU_NATIVE_HOST_TIMEOUT");*seconds=300;
    if(!configured)return 0;
    if(!*configured)return -EINVAL;
    for(const char *p=configured;*p;p++)if(*p<'0' || *p>'9')return -EINVAL;
    char *end;errno=0;unsigned long value=strtoul(configured,&end,10);
    if(errno || *end || !value || value>86400)return -EINVAL;
    *seconds=(unsigned)value;return 0;
}
static int native_mmio_cleanup_sync(struct opengpu_device *gpu)
{
    struct native_mmio_state *m=native_mmio_state(gpu);struct stat st;
    if(!m->cleanup_active || m->cleanup_done || m->cleanup_error || m->cleanup_sequence==UINT32_MAX)return -EINVAL;
    unsigned sequence=m->cleanup_sequence;
    char *trace=native_mmio_cleanup_path(m,"trace",sequence),*ack=native_mmio_cleanup_path(m,"observed",sequence);
    char *failed=native_mmio_cleanup_path(m,"error",UINT32_MAX),*temporary=NULL;
    if(!trace || !ack || !failed){free(trace);free(ack);free(failed);return -ENOMEM;}
    unsigned timeout;int error=native_mmio_timeout_seconds(&timeout);if(error)goto out;
    if(!lstat(ack,&st) || errno!=ENOENT){error=-EEXIST;goto out;}
    if(m->count-m->cleanup_start>UINT32_MAX){error=-ERANGE;goto out;}
    FILE *f=native_mmio_file(trace,&temporary);if(!f){error=-EIO;goto out;}
    fwrite("OGCLN001",1,8,f);native_mmio_le(f,m->count-m->cleanup_start,4);
    native_mmio_actions(f,m,m->cleanup_start);error=native_mmio_close(f,trace,temporary);if(error)goto out;
#ifdef OPENGPU_NATIVE_MMIO_CLEANUP_TEST_PEER
    /* Explicit CPU regression peer only; never enabled by the Mesa producer. */
    OPENGPU_NATIVE_MMIO_CLEANUP_TEST_PEER(gpu,m->cleanup_directory,sequence);
#endif
    struct timespec start,now,pause={.tv_nsec=10000000};
    if(clock_gettime(CLOCK_MONOTONIC,&start)){error=-EIO;goto out;}
    for(;;){
        if(!lstat(ack,&st)){error=native_mmio_cleanup_ack(ack,sequence);break;}
        if(errno!=ENOENT){error=-errno;break;}
        if(!lstat(failed,&st)){error=-EIO;break;}
        if(clock_gettime(CLOCK_MONOTONIC,&now)){error=-EIO;break;}
        if(now.tv_sec-start.tv_sec>=timeout){error=-ETIMEDOUT;break;}
        if(nanosleep(&pause,NULL) && errno!=EINTR){error=-errno;break;}
    }
    if(!error){++m->cleanup_sequence;m->cleanup_start=m->count;}
out:
    free(trace);free(ack);free(failed);return error;
}
int native_kernel_begin_cleanup(struct native_kernel_session *s,const char *directory)
{
    struct stat st;if(!s || !directory || lstat(directory,&st) || !S_ISDIR(st.st_mode))return -EINVAL;
    struct native_mmio_state *m=native_mmio_state(&s->gpu);
    if(!m->exported || !m->pte || m->cleanup_active || m->cleanup_done || m->cleanup_error ||
        m->native_waiting || s->gpu.hw.unified_fence || s->gpu.hw.active_fence || s->gpu.hw.wedged)return -EBUSY;
    for(struct native_kernel_record *r=s->records;r;r=r->next)if(!r->verified || r->freed)return -EBUSY;
    m->cleanup_directory=strdup(directory);if(!m->cleanup_directory)return -ENOMEM;
    char *path=native_mmio_cleanup_path(m,"cleanup.begin",UINT32_MAX),*temporary=NULL;
    if(!path){free(m->cleanup_directory);m->cleanup_directory=NULL;return -ENOMEM;}
    FILE *f=native_mmio_file(path,&temporary);if(!f){free(path);free(m->cleanup_directory);m->cleanup_directory=NULL;return -EIO;}
    fwrite("OGCLB001",1,8,f);native_mmio_le(f,s->record_count,4);
    int error=native_mmio_close(f,path,temporary);free(path);
    if(error){free(m->cleanup_directory);m->cleanup_directory=NULL;return error;}
    m->cleanup_start=m->count;m->cleanup_active=true;return 0;
}
int native_kernel_end_cleanup(struct native_kernel_session *s)
{
    if(!s)return -EINVAL;
    struct native_mmio_state *m=native_mmio_state(&s->gpu);
    if(!m->cleanup_active || m->cleanup_done || m->cleanup_error || s->count || s->gpu.hw.wedged)return -EBUSY;
    running=s;copy_gpu=&s->gpu;
    /* Context teardown and the final Bare/full-flush must be acknowledged by
     * the still-live DUT before its page tables or ASID can be reclaimed. */
    if(!s->context_destroyed){
        int error=opengpu_mmu_vm_destroy(&s->gpu,&s->context.vm);
        if(!error)error=opengpu_mmu_fini(&s->gpu);
        if(error)return error;
        s->context_destroyed=true;
    }
    if(m->count!=m->cleanup_start)m->cleanup_error=native_mmio_cleanup_sync(&s->gpu);
    if(m->cleanup_error)return m->cleanup_error;
    if(!m->cleanup_sequence)return -EINVAL;
    char *path=native_mmio_cleanup_path(m,"cleanup.done",UINT32_MAX),*temporary=NULL;
    if(!path)return -ENOMEM;
    FILE *f=native_mmio_file(path,&temporary);if(!f){free(path);return -EIO;}
    fwrite("OGCLD001",1,8,f);native_mmio_le(f,m->cleanup_sequence,4);
    int error=native_mmio_close(f,path,temporary);free(path);
    if(!error){m->cleanup_active=false;m->cleanup_done=true;}return error;
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
    if(!error)error=fixture_mmu_visit_pte_pages(&s->gpu,native_mmio_shadow_pte,m);
    if(!error){m->exported=true;m->allocation_signature=native_mmio_allocation_signature();}return error;
}
int opengpu_hw_native_binned_async(struct opengpu_device *,struct opengpu_private_snapshot *,
    const struct opengpu_render_context *,u32,struct dma_fence **);
/* New private transports must opt in explicitly; old OGCAP001/101 never
 * widens by inference from a profile, manifest or runtime count. */
#ifndef OPENGPU_NATIVE_CAPTURE_MAGIC
#define OPENGPU_NATIVE_CAPTURE_MAGIC "OGCAP001"
#endif
#ifndef OPENGPU_NATIVE_CAPTURE_DISCOVERY_WORDS
#define OPENGPU_NATIVE_CAPTURE_DISCOVERY_WORDS 101
#endif
#ifndef OPENGPU_NATIVE_CAPTURE_WORD
#define OPENGPU_NATIVE_CAPTURE_WORD(session,index) ((session)->profile->words[index])
#endif
_Static_assert(OPENGPU_NATIVE_CAPTURE_DISCOVERY_WORDS<=OPENGPU_NATIVE_PROFILE_STORAGE_WORDS,
    "capture exceeds private profile storage");
enum {
    NATIVE_JOINT_READ_FAULT_STATUS=1, NATIVE_JOINT_READ_FAULT_STAGE=5,
    NATIVE_JOINT_WRITE_FAULT_STATUS=3, NATIVE_JOINT_WRITE_FAULT_STAGE=6,
};
static u32 native_mmio_joint_status(const struct native_kernel_record *record)
{
    if(record->joint_write_fault_address)
        return (NATIVE_JOINT_WRITE_FAULT_STAGE<<3)|NATIVE_JOINT_WRITE_FAULT_STATUS;
    if(record->joint_uniform_read_fault)
        return (NATIVE_JOINT_READ_FAULT_STAGE<<3)|NATIVE_JOINT_READ_FAULT_STATUS;
    return record->joint_sequence_count?0u:0x40u;
}
static u32 native_mmio_completion(u32 command_id,bool joint,const struct native_kernel_record *record)
{
    u32 opcode=joint?OPENGPU_TESS_JOINT_HOST_OPCODE:GPU_UCMD_OP_NATIVE_BINNED_RENDER;
    u32 status=joint?(native_mmio_joint_status(record)&7u):0;
    u32 result=status?status<<11:1u<<15;
    return command_id|((opcode&7u)<<8)|((opcode&8u)<<13)|result;
}
static void native_mmio_capture_expect(FILE *file,u32 address,u32 mask,u32 value)
{
    native_mmio_le(file,address,4);
    native_mmio_le(file,mask,4);
    native_mmio_le(file,value,4);
}
static void native_mmio_capture_joint_detail(FILE *file,const struct native_kernel_session *s,
    const struct native_kernel_record *record)
{
    const u32 expected[] = {
        OPENGPU_TESS_JOINT_HOST_COMPLETION_MAGIC,
        native_mmio_joint_status(record),record->joint_write_fault_address,
        s->gpu.hw.native_context,s->gpu.hw.native_pass,s->gpu.hw.native_generation,
        record->joint_directory_count,record->joint_uniform_read_fault?0:record->joint_directory_count,
    };
    const unsigned known=sizeof(expected)/sizeof(expected[0]);
    /* The device supplies the patch, primitive and physical byte counters.
     * Record every word for stability, retirement and the separate oracle. */
    for(unsigned i=0;i<OPENGPU_TESS_JOINT_HOST_COMPLETION_WORDS;++i)
        native_mmio_capture_expect(file,native_mmio_joint_detail_address(i),
            i<known?UINT32_MAX:0,i<known?expected[i]:0);
}
static void native_mmio_capture_joint_geometry_detail(FILE *file,const struct native_kernel_session *s,
    const struct native_kernel_record *record)
{
    const u32 expected[]={
        OPENGPU_JOINT_GEOMETRY_COMPLETION_MAGIC,
        0x80u|(native_mmio_joint_status(record)&0x7fu),0,
        s->gpu.hw.joint_geometry_context,s->gpu.hw.joint_geometry_pass,s->gpu.hw.joint_geometry_generation,
        record->joint_sequence_count,record->joint_directory_count,
    };
    for(unsigned i=0;i<OPENGPU_JOINT_GEOMETRY_COMPLETION_WORDS;++i)
        native_mmio_capture_expect(file,native_mmio_joint_geometry_detail_address(i),
            i<8?UINT32_MAX:0,i<8?expected[i]:0);
}
static int native_mmio_joint_image_line(const struct opengpu_sched_job *job,u32 *address,u64 *physical)
{
    const struct opengpu_private_snapshot *owner=job->native_owner;
    u32 frame_va,base,store,writable;
    if(opengpu_private_snapshot_read32(owner,job->native_job_segment,
            4u*OPENGPU_TESS_JOINT_FRAME_BASE_WORD,&frame_va))return -EINVAL;
    const struct snapshot_segment *frame=snapshot_joint_target(owner,frame_va);
    if(!frame || !frame->mapped ||
        opengpu_private_snapshot_read32(owner,frame->id,
            4u*OPENGPU_NATIVE_BINNED_FRAME_FRAME_IMAGE_VIEWS0_BASE_WORD,&base) ||
        opengpu_private_snapshot_read32(owner,frame->id,
            4u*OPENGPU_NATIVE_BINNED_FRAME_FRAME_IMAGE_VIEWS0_STORE_WORD,&store) ||
        opengpu_private_snapshot_read32(owner,frame->id,
            4u*OPENGPU_NATIVE_BINNED_FRAME_FRAME_IMAGE_VIEWS0_WRITABLE_WORD,&writable) ||
        !base || (base&63u) || store!=1 || writable!=1)return -EINVAL;
    for(const struct snapshot_resource *r=owner->resources;r;r=r->next)
        for(const struct snapshot_resource_map *m=r->maps;m;m=m->next) {
            if(!r->reservation.active || !m->mapped || !m->writable)continue;
            u64 first=r->reservation.va+m->offset;
            if(first>base || (u64)base+64>first+m->bytes)continue;
            u64 target=m->physical+(base-first);
            if(!target || (target&63u) || target>(1ull<<32)-64)return -ERANGE;
            *address=base;*physical=target;
            return 0;
        }
    return -EINVAL;
}
enum native_joint_replay {NATIVE_JOINT_RENDER,NATIVE_JOINT_WRITE_FAULT,NATIVE_JOINT_CANCEL,NATIVE_JOINT_UNIFORM_READ_FAULT};
static int native_mmio_capture(struct native_kernel_session *s,unsigned index,const char *path,
    u64 budget,u32 hold,enum native_joint_replay replay)
{
    struct native_kernel_record *r=native_session_record(s,index);
    struct opengpu_sched_job *job=native_mmio_record_job(s,index);
    if(!r || !job || !s->profile || r->freed || !budget || budget>1000000000 || hold<8 || hold>4096)return -EINVAL;
    bool geometry=job->type==OPENGPU_SCHED_PROTECTED_TESSELLATION &&
        job->joint_report_kind==OPENGPU_TESSELLATION_JOINT_REPORT_GNZ3;
    if(geometry && replay!=NATIVE_JOINT_RENDER)return -EOPNOTSUPP;
    if(r->captured)return -EINVAL;
    bool legacy_joint=job->type==OPENGPU_SCHED_PROTECTED_TESSELLATION && job->joint_report;
    bool joint=geometry||legacy_joint;
    if((geometry && (job->joint_report || !job->joint_geometry_report || !s->profile->joint_geometry.available)) ||
        (legacy_joint && (job->joint_geometry_report ||
            job->joint_report_kind!=OPENGPU_TESSELLATION_JOINT_REPORT_GNZ2 || !s->profile->joint.available)) ||
        (!joint && (replay!=NATIVE_JOINT_RENDER || job->type!=OPENGPU_SCHED_NATIVE_BINNED)))return -EINVAL;
    struct native_mmio_state *m=native_mmio_state(&s->gpu);
    if(!m->exported || m->native_waiting || m->allocation_signature!=native_mmio_allocation_signature())return -EBUSY;
    for(struct native_kernel_record *old=s->records;old && old!=r;old=old->next)if(!old->verified)return -EBUSY;
    for(struct fixture_dependency *d=job->base.dependencies;d;d=d->next)if(!dma_fence_is_signaled(d->fence))return -EBUSY;
    if(geometry && (opengpu_private_snapshot_read32(job->native_owner,job->native_job_segment,
         4u*OPENGPU_JOINT_GEOMETRY_ROOT_GEOMETRY_COUNT_WORD,&r->joint_sequence_count) ||
        opengpu_private_snapshot_read32(job->native_owner,job->native_job_segment,
         4u*OPENGPU_TESS_JOINT_SEQUENCE_COUNT_WORD,&r->joint_directory_count)))return -EINVAL;
    if(legacy_joint && (opengpu_private_snapshot_read32(job->native_owner,job->native_job_segment,
         4u*OPENGPU_TESS_JOINT_SEQUENCE_COUNT_WORD,&r->joint_sequence_count) ||
        opengpu_private_snapshot_read32(job->native_owner,job->native_job_segment,
         4u*OPENGPU_TESS_JOINT_DIRECTORY_COUNT_WORD,&r->joint_directory_count)))return -EINVAL;
    if(replay==NATIVE_JOINT_CANCEL &&
        (!r->joint_sequence_count || !(s->gpu.hw.capabilities&GPU_CAP_UNIFIED_RESET)))return -EINVAL;
    if((replay==NATIVE_JOINT_WRITE_FAULT || replay==NATIVE_JOINT_UNIFORM_READ_FAULT) &&
        (!r->joint_sequence_count || !r->joint_directory_count))return -EINVAL;
    u32 fault_address=0;
    u64 fault_physical=0;
    if(replay==NATIVE_JOINT_WRITE_FAULT) {
        int error=native_mmio_joint_image_line(job,&fault_address,&fault_physical);
        if(error)return error;
    }
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
    int error=0;
    if(joint){
        struct dma_fence *fence=opengpu_protected_tessellation_job_run(job);
        if(IS_ERR(fence))error=PTR_ERR(fence);
        else r->hardware=fence;
    }else error=opengpu_hw_native_binned_async(&s->gpu,job->native_owner,job->context,job->native_job_segment,&r->hardware);
    if(error){fclose(f);remove(temporary);free(temporary);free(ranges);return error;}
    assert(m->native_waiting && r->hardware && !dma_fence_is_signaled(r->hardware));
    if(m->allocation_signature!=native_mmio_allocation_signature()){fclose(f);remove(temporary);free(temporary);free(ranges);return -EIO;}
    r->joint_write_fault_address=fault_address;
    r->joint_source_cancel=replay==NATIVE_JOINT_CANCEL;
    r->joint_uniform_read_fault=replay==NATIVE_JOINT_UNIFORM_READ_FAULT;
    const char *magic=OPENGPU_NATIVE_CAPTURE_MAGIC;
    if(joint) {
        magic=geometry?"OGCAP007":"OGCAP003";
        if(!geometry) {
            if(fault_address)magic="OGCAP004";
            if(r->joint_source_cancel)magic="OGCAP005";
            if(r->joint_uniform_read_fault)magic="OGCAP006";
        }
    }
    fwrite(magic,1,8,f);
    if(fault_address){native_mmio_le(f,fault_physical,8);native_mmio_le(f,fault_address,4);}
    native_mmio_le(f,budget,8);native_mmio_le(f,hold,4);
    unsigned discovery_words=joint?s->profile->word_count:OPENGPU_NATIVE_CAPTURE_DISCOVERY_WORDS;
    native_mmio_le(f,s->gpu.hw.unified_command_id,4);native_mmio_le(f,discovery_words,4);
    for(unsigned i=0;i<discovery_words;i++){
        struct native_kernel_profile_word word=joint?s->profile->words[i]:OPENGPU_NATIVE_CAPTURE_WORD(s,i);
        native_mmio_le(f,word.address,4);native_mmio_le(f,word.value,4);
    }
    native_mmio_le(f,merged,4);for(size_t i=0;i<merged;i++){
        native_mmio_le(f,ranges[i].first,8);native_mmio_le(f,ranges[i].end,8);
    }free(ranges);
    native_mmio_le(f,m->count-m->start,4);native_mmio_actions(f,m,m->start);
    native_mmio_le(f,r->joint_source_cancel?0:geometry?1u+OPENGPU_JOINT_GEOMETRY_COMPLETION_WORDS:
        joint?1u+OPENGPU_TESS_JOINT_HOST_COMPLETION_WORDS:13u,4);
    if(!r->joint_source_cancel) {
        native_mmio_capture_expect(f,GPU_REG_UCMD_COMPLETION,UINT32_MAX,
            native_mmio_completion(s->gpu.hw.unified_command_id,joint,r));
        if(geometry)native_mmio_capture_joint_geometry_detail(f,s,r);
        else if(joint)native_mmio_capture_joint_detail(f,s,r);
        else for(unsigned i=0;i<12;i++){
            u32 expected=i==0?OPENGPU_NATIVE_BINNED_COMPLETION_MAGIC:
                i==6?s->gpu.hw.native_context:i==7?s->gpu.hw.native_pass:i==8?s->gpu.hw.native_generation:0;
            native_mmio_capture_expect(f,0x180+4*i,(i==2||i==3)?0:UINT32_MAX,expected);
        }
    }
    native_mmio_le(f,0,4);error=native_mmio_close(f,path,temporary);
    if(!error){r->captured=true;m->start=m->count;}return error;
}
int native_kernel_capture(struct native_kernel_session *s,unsigned index,const char *path,u64 budget,u32 hold)
{
    return native_mmio_capture(s,index,path,budget,hold,NATIVE_JOINT_RENDER);
}
int native_kernel_capture_joint_write_fault(struct native_kernel_session *s,unsigned index,
    const char *path,u64 budget,u32 hold)
{
    return native_mmio_capture(s,index,path,budget,hold,NATIVE_JOINT_WRITE_FAULT);
}
int native_kernel_capture_joint_uniform_read_fault(struct native_kernel_session *s,unsigned index,
    const char *path,u64 budget,u32 hold)
{
    return native_mmio_capture(s,index,path,budget,hold,NATIVE_JOINT_UNIFORM_READ_FAULT);
}
int native_kernel_capture_joint_cancel(struct native_kernel_session *s,unsigned index,
    const char *path,u64 budget,u32 hold)
{
    return native_mmio_capture(s,index,path,budget,hold,NATIVE_JOINT_CANCEL);
}
int native_kernel_drain_held(struct native_kernel_session *s,unsigned index,
    const struct native_kernel_held_receipt *held,const char *path)
{
    struct native_kernel_record *r=native_session_record(s,index);struct opengpu_native_completion decoded;
    struct opengpu_tessellation_joint_geometry_completion geometry_decoded;
    bool geometry=r && r->job && r->job->joint_report_kind==OPENGPU_TESSELLATION_JOINT_REPORT_GNZ3;
    bool joint=r && r->job && (r->job->joint_report||r->job->joint_geometry_report);
    if(!r || !r->job || !held || !r->captured || r->drained || r->freed || r->joint_source_cancel ||
        held->record_index!=index ||
        held->command_id!=s->gpu.hw.unified_command_id ||
        held->completion!=native_mmio_completion(held->command_id,joint,r) ||
        !(held->held_status&GPU_UCMD_STATUS_COMPLETION) || held->held_irq!=(GPU_IRQ_ENABLE|GPU_IRQ_PENDING) ||
        held->held_irq_asserted!=1 || !r->hardware || dma_fence_is_signaled(r->hardware))return -EINVAL;
    if(geometry) {
        if(s->gpu.hw.joint_geometry_pending_completion!=r->job->joint_geometry_report ||
            (held->append_read_bytes&63u) ||
            !opengpu_decode_tessellation_joint_geometry_completion(held->gnz3,
                OPENGPU_JOINT_GEOMETRY_COMPLETION_WORDS,native_mmio_joint_status(r)&7u,
                s->gpu.hw.joint_geometry_context,s->gpu.hw.joint_geometry_pass,s->gpu.hw.joint_geometry_generation,
                &geometry_decoded))return -EINVAL;
    }else if(joint){
        const u32 *words=held->gnz2;
        if(s->gpu.hw.joint_pending_completion!=r->job->joint_report ||
            (held->append_read_bytes&63u) || words[0]!=OPENGPU_TESS_JOINT_HOST_COMPLETION_MAGIC ||
            words[1]!=native_mmio_joint_status(r) || words[2]!=r->joint_write_fault_address ||
            words[3]!=s->gpu.hw.native_context || words[4]!=s->gpu.hw.native_pass ||
            words[5]!=s->gpu.hw.native_generation || words[6]!=r->joint_directory_count ||
            words[7]!=(r->joint_uniform_read_fault?0:r->joint_directory_count))return -EINVAL;
    }else if(s->gpu.hw.native_pending_owner!=r->job->native_owner ||
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
    if(geometry)for(unsigned i=0;i<OPENGPU_JOINT_GEOMETRY_COMPLETION_WORDS;++i)
        m->regs[native_mmio_joint_geometry_detail_address(i)/4]=held->gnz3[i];
    else if(joint)for(unsigned i=0;i<OPENGPU_TESS_JOINT_HOST_COMPLETION_WORDS;++i)
        m->regs[native_mmio_joint_detail_address(i)/4]=held->gnz2[i];
    else for(unsigned i=0;i<12;i++)m->regs[0x180/4+i]=held->gnx[i];
    m->receipt_loaded=true;
    opengpu_irq_handler(0,&s->gpu);
    if(!dma_fence_is_signaled(r->hardware) || r->hardware->error!=native_kernel_expected_error(r) ||
        s->gpu.hw.native_pending_owner ||
        s->gpu.hw.joint_pending_completion || s->gpu.hw.joint_geometry_pending_completion ||
        m->native_waiting || (geometry && !r->job->joint_geometry_report->valid) ||
        (joint && !geometry && !r->job->joint_report->valid)){
        fclose(f);remove(temporary);free(temporary);return -EIO;
    }
    fwrite(joint?"OGRTR002":"OGRTR001",1,8,f);native_mmio_le(f,m->count-start,4);native_mmio_actions(f,m,start);
    int error=native_mmio_close(f,path,temporary);if(!error){r->held=*held;r->drained=true;m->start=m->count;}
    return error;
}
static struct native_kernel_record *native_mmio_reset_record(struct native_kernel_session *s,
    const struct native_kernel_reset_receipt *receipt)
{
    if(!s || !receipt || receipt->legacy_status || s->gpu.hw.wedged ||
        receipt->command_id!=s->gpu.hw.unified_command_id)return NULL;
    struct native_kernel_record *r=native_session_record(s,receipt->record_index);
    if(!r || !r->captured || !r->joint_source_cancel || !r->job || !r->job->joint_report ||
        r->job->joint_report->valid || !r->hardware || r->verified || r->job->base.s_fence->finished.signaled)
        return NULL;
    return r;
}
static void native_mmio_load_reset_receipt(struct native_mmio_state *m,
    const struct native_kernel_reset_receipt *receipt)
{
    m->regs[GPU_REG_STATUS/4]=receipt->legacy_status;
    m->regs[GPU_REG_UCMD_STATUS/4]=receipt->status;
    m->regs[GPU_REG_IRQ/4]=receipt->irq;
}
static int native_mmio_reset_trace(struct native_mmio_state *m,const char *path,
    const char *magic,u32 command,size_t start)
{
    if(m->count<start || m->count-start>1024)return -ERANGE;
    char *temporary=NULL;
    FILE *f=native_mmio_file(path,&temporary);
    if(!f)return -EIO;
    fwrite(magic,1,8,f);
    native_mmio_le(f,command,4);
    native_mmio_le(f,m->count-start,4);
    native_mmio_actions(f,m,start);
    int error=native_mmio_close(f,path,temporary);
    if(!error)m->start=m->count;
    return error;
}
int native_kernel_cancel_joint(struct native_kernel_session *s,
    const struct native_kernel_reset_receipt *held,const char *request_trace)
{
    struct native_kernel_record *r=native_mmio_reset_record(s,held);
    struct opengpu_device *gpu=r?&s->gpu:NULL;
    if(!r || !request_trace || r->drained || gpu->hw.reset_pending || gpu->hw.aborting ||
        gpu->hw.active_fence || gpu->hw.unified_fence!=r->hardware ||
        gpu->hw.joint_pending_completion!=r->job->joint_report ||
        !(gpu->hw.capabilities&GPU_CAP_UNIFIED_RESET) || dma_fence_is_signaled(r->hardware) ||
        held->external_drained || !(held->status&GPU_UCMD_STATUS_VM_BUSY) ||
        (held->status&~(GPU_UCMD_STATUS_READY|GPU_UCMD_STATUS_VM_BUSY)) ||
        !(held->irq&GPU_IRQ_ENABLE) || (held->irq&~(GPU_IRQ_ENABLE|GPU_IRQ_PENDING)) ||
        held->irq_asserted!=!!(held->irq&GPU_IRQ_PENDING))return -EINVAL;
    struct native_mmio_state *m=native_mmio_state(gpu);
    if(!m->native_waiting || m->receipt_loaded || m->issued_reset || m->cleanup_active)return -EBUSY;

    native_mmio_load_reset_receipt(m,held);
    running=s;copy_gpu=gpu;
    size_t start=m->count;
    unsigned pops=m->pops;
    m->recording_reset=true;
    native_mmio_abort_actual(gpu,-ECANCELED);
    m->recording_reset=false;
    const struct opengpu_fault_snapshot *fault=&gpu->hw.last_fault;
    if(!m->issued_reset || !gpu->hw.reset_pending || m->pops!=pops || gpu->hw.aborting ||
        gpu->hw.unified_fence || gpu->hw.joint_pending_completion || gpu->hw.native_pending_owner ||
        !dma_fence_is_signaled(r->hardware) || r->hardware->error!=-ECANCELED ||
        !fault->sequence || fault->error!=-ECANCELED ||
        fault->flags!=(OPENGPU_FAULT_VALID|OPENGPU_FAULT_ABORTED|OPENGPU_FAULT_RESET_ISSUED) ||
        fault->command_id!=held->command_id || fault->expected_command_id!=held->command_id ||
        fault->opcode!=OPENGPU_TESS_JOINT_HOST_OPCODE || fault->expected_opcode!=OPENGPU_TESS_JOINT_HOST_OPCODE ||
        fault->status || fault->bytes_processed)return -EIO;
    return native_mmio_reset_trace(m,request_trace,"OGJCQ001",held->command_id,start);
}
static bool native_mmio_reset_ack(const struct native_kernel_reset_receipt *receipt,bool retired)
{
    return receipt->status==GPU_UCMD_STATUS_READY && receipt->external_drained==1 &&
        receipt->irq==(retired?GPU_IRQ_ENABLE:GPU_IRQ_ENABLE|GPU_IRQ_PENDING) &&
        receipt->irq_asserted==(retired?0u:1u);
}
int native_kernel_drain_joint_reset(struct native_kernel_session *s,
    const struct native_kernel_reset_receipt *ack,const char *irq_trace)
{
    struct native_kernel_record *r=native_mmio_reset_record(s,ack);
    if(!r || !irq_trace || r->drained || !s->gpu.hw.reset_pending || s->gpu.hw.unified_fence ||
        !dma_fence_is_signaled(r->hardware) || r->hardware->error!=-ECANCELED ||
        !native_mmio_reset_ack(ack,false))return -EINVAL;
    struct native_mmio_state *m=native_mmio_state(&s->gpu);
    if(!m->native_waiting || !m->issued_reset)return -EBUSY;

    native_mmio_load_reset_receipt(m,ack);
    running=s;copy_gpu=&s->gpu;
    size_t start=m->count;
    unsigned pops=m->pops;
    opengpu_irq_handler(0,&s->gpu);
    if(s->gpu.hw.reset_pending || m->pops!=pops || m->regs[GPU_REG_IRQ/4]!=GPU_IRQ_ENABLE)
        return -EIO;
    int error=native_mmio_reset_trace(m,irq_trace,"OGCRV001",ack->command_id,start);
    if(!error) {
        r->drained=true;
        m->native_waiting=false;
        m->issued_reset=false;
    }
    return error;
}
int native_kernel_observe_joint_reset_retired(struct native_kernel_session *s,
    const struct native_kernel_reset_receipt *retired)
{
    struct native_kernel_record *r=native_mmio_reset_record(s,retired);
    if(!r || !r->drained || s->gpu.hw.reset_pending ||
        !dma_fence_is_signaled(r->hardware) || r->hardware->error!=-ECANCELED ||
        !native_mmio_reset_ack(retired,true))return -EINVAL;
    struct native_mmio_state *m=native_mmio_state(&s->gpu);
    if(m->native_waiting || m->issued_reset)return -EBUSY;
    native_mmio_load_reset_receipt(m,retired);
    dma_fence_set_error(&r->job->base.s_fence->finished,-ECANCELED);
    dma_fence_signal(&r->job->base.s_fence->finished);
    r->verified=true;
    return 0;
}
static void native_mmio_release(struct native_kernel_session *s)
{
    struct native_mmio_state *m=s->gpu.hw.native_mmio;
    if(m){assert(!s->gpu.hw.unified_fence && !s->gpu.hw.active_fence);
        while(m->pte){struct native_mmio_pte_shadow *p=m->pte;m->pte=p->next;free(p->words);free(p);}
        free(m->cleanup_directory);free(m->actions);free(m);s->gpu.hw.native_mmio=NULL;}
    for(struct native_kernel_record *r=s->records;r;r=r->next){free(r->mmio_state);r->mmio_state=NULL;}
}
#endif
