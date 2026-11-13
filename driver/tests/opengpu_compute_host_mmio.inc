/* CPU MMIO transport only. Kernel completions require actual DUT receipts. */
#include <unistd.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <time.h>
#include "../opengpu_tlb_flush.h"
struct compute_mmio_action {u32 kind,address,value,mask;};
struct compute_mmio_pte_shadow {struct compute_mmio_pte_shadow *next;u64 physical;size_t size;u32 *words;};
struct compute_mmio_state {
 u32 regs[1024];struct compute_mmio_action *actions;size_t count,capacity;
 bool kernel_waiting,receipt_loaded;unsigned kernel_submissions,invalidates,pops;
 struct compute_mmio_pte_shadow *pte;char *cleanup_directory;size_t cleanup_start;
 unsigned cleanup_sequence;int cleanup_error;bool cleanup_active,cleanup_done;

};
static int compute_mmio_cleanup_sync(struct opengpu_device *);
static struct compute_mmio_state *compute_mmio_state(struct opengpu_device *gpu)
{
 struct compute_mmio_state *m=gpu->hw.compute_mmio;
 if(!m){m=calloc(1,sizeof(*m));assert(m);gpu->hw.compute_mmio=m;
  m->regs[GPU_REG_UCMD_STATUS/4]=GPU_UCMD_STATUS_READY;
  gpu->hw.fence_context=dma_fence_context_alloc(1);gpu->hw.regs_size=0x1000;}
 return m;
}
static void compute_mmio_action(struct compute_mmio_state *m,u32 kind,u32 address,u32 value,u32 mask)
{
 assert(!(address&3) && (kind==4 || address<0x1000));
 if(m->count==m->capacity){size_t n=m->capacity?m->capacity*2:1024;
  void *p=realloc(m->actions,n*sizeof(*m->actions));assert(p);m->actions=p;m->capacity=n;}
 m->actions[m->count++]=(struct compute_mmio_action){kind,address,value,mask};
}
static u32 opengpu_reg_read(struct opengpu_device *gpu,u32 address)
{
 struct compute_mmio_state *m=compute_mmio_state(gpu);assert(!(address&3) && address<0x1000);
 u32 value=m->regs[address/4],mask=~0u,kind=3;
 if(address==GPU_REG_UCMD_STATUS){kind=2;mask=GPU_UCMD_STATUS_COMPLETION|GPU_UCMD_STATUS_VM_BUSY|GPU_UCMD_STATUS_RESET_BUSY;
  if(!(value&GPU_UCMD_STATUS_COMPLETION))mask|=GPU_UCMD_STATUS_READY|GPU_UCMD_STATUS_OVERFLOW|GPU_UCMD_STATUS_RESET_REJECTED;}
 compute_mmio_action(m,kind,address,value&mask,mask);return value;
}
static void opengpu_reg_write(struct opengpu_device *gpu,u32 address,u32 value)
{
 struct compute_mmio_state *m=compute_mmio_state(gpu);compute_mmio_action(m,1,address,value,0);
 assert(address!=GPU_REG_UCMD_RESET);
 if(address==GPU_REG_UCMD_SUBMIT){
  assert(value==1 && !(m->regs[GPU_REG_UCMD_STATUS/4]&GPU_UCMD_STATUS_COMPLETION));
  u32 opcode=m->regs[GPU_REG_UCMD_OPCODE/4];
  if(opcode==GPU_UCMD_OP_INVALIDATE){
   /* Control peer only: replay must verify these actual reads on the DUT. */
   ++m->invalidates;m->regs[GPU_REG_UCMD_STATUS/4]=GPU_UCMD_STATUS_COMPLETION|GPU_UCMD_STATUS_VM_BUSY;
   m->regs[GPU_REG_UCMD_COMPLETION/4]=0x8000u|(opcode<<8)|m->regs[GPU_REG_UCMD_ID/4];
   m->regs[GPU_REG_UCMD_COMPLETION_BYTES_LO/4]=m->regs[GPU_REG_UCMD_BYTES/4];m->regs[GPU_REG_UCMD_COMPLETION_BYTES_HI/4]=0;
  }else{
   assert(opcode==GPU_UCMD_OP_KERNEL && !m->kernel_waiting);
   m->kernel_waiting=true;++m->kernel_submissions;m->regs[GPU_REG_UCMD_STATUS/4]=GPU_UCMD_STATUS_VM_BUSY;
  }
 }else if(address==GPU_REG_UCMD_COMPLETION_POP){
  assert(value==1 && (m->regs[GPU_REG_UCMD_STATUS/4]&GPU_UCMD_STATUS_COMPLETION));
  assert(!m->kernel_waiting || m->receipt_loaded);++m->pops;
  m->regs[GPU_REG_UCMD_STATUS/4]=GPU_UCMD_STATUS_READY;m->regs[GPU_REG_UCMD_COMPLETION/4]=0;
  m->regs[GPU_REG_UCMD_COMPLETION_BYTES_LO/4]=m->regs[GPU_REG_UCMD_COMPLETION_BYTES_HI/4]=0;
  m->kernel_waiting=false;m->receipt_loaded=false;
 }else if(address==GPU_REG_IRQ){m->regs[address/4]=(m->regs[address/4]&~(value&6u)&~1u)|(value&1u);}
 else m->regs[address/4]=value;
 if(address==GPU_REG_UCMD_TLB_FLUSH && m->cleanup_active && !m->cleanup_error)
  m->cleanup_error=compute_mmio_cleanup_sync(gpu);
}
static u32 opengpu_mmio_read_callback(void *gpu,u32 address){return opengpu_reg_read(gpu,address);}
static void opengpu_mmio_write_callback(void *gpu,u32 address,u32 value){opengpu_reg_write(gpu,address,value);}
#define spin_lock_irqsave(p,f) do {(f)=0;assert(!*(p));*(p)=1;}while(0)
#define spin_unlock_irqrestore(p,f) do {(void)(f);assert(*(p));*(p)=0;}while(0)
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
#include "compute_host_hw_fence_under_test.inc"
#include "compute_host_hw_fault_record_under_test.inc"
#include "compute_host_hw_job_id_under_test.inc"
#include "compute_host_hw_reset_pending_under_test.inc"
#include "compute_host_hw_result_error_under_test.inc"
#include "compute_host_hw_drain_under_test.inc"
#include "compute_host_hw_irq_under_test.inc"
#include "compute_host_hw_enable_irqs_under_test.inc"
#include "compute_host_hw_quiesce_under_test.inc"
#include "compute_host_hw_kick_under_test.inc"
#include "compute_host_hw_set_satp_under_test.inc"
#include "compute_host_hw_activate_locked_under_test.inc"
#include "compute_host_hw_busy_under_test.inc"
#define opengpu_hw_wait_idle_locked compute_mmio_wait_idle
#include "compute_host_hw_wait_idle_under_test.inc"
#undef opengpu_hw_wait_idle_locked
#define opengpu_hw_activate_vm compute_mmio_activate_vm
#include "compute_host_hw_activate_under_test.inc"
#undef opengpu_hw_activate_vm
#define opengpu_hw_enable_mmu compute_mmio_enable_mmu
#include "compute_host_hw_enable_under_test.inc"
#undef opengpu_hw_enable_mmu
#define opengpu_hw_flush_tlbs compute_mmio_flush_tlbs_actual
#include "compute_host_hw_flush_under_test.inc"
#undef opengpu_hw_flush_tlbs
#define opengpu_hw_flush_tlb_asid compute_mmio_flush_asid_actual
#include "compute_host_hw_flush_asid_under_test.inc"
#undef opengpu_hw_flush_tlb_asid
static int compute_mmio_flush_tlbs(struct opengpu_device *gpu)
{struct compute_mmio_state *m=compute_mmio_state(gpu);if(m->cleanup_error)return m->cleanup_error;
 int error=compute_mmio_flush_tlbs_actual(gpu);return error?error:m->cleanup_error;}
static int compute_mmio_flush_asid(struct opengpu_device *gpu,u32 asid)
{struct compute_mmio_state *m=compute_mmio_state(gpu);if(m->cleanup_error)return m->cleanup_error;
 int error=compute_mmio_flush_asid_actual(gpu,asid);return error?error:m->cleanup_error;}
#include "compute_host_hw_submit_under_test.inc"
#include "compute_host_hw_invalidate_under_test.inc"
#include "compute_host_hw_invalidate_validate_under_test.inc"
#include "compute_host_hw_invalidate_sync_under_test.inc"
#include "compute_host_hw_snapshot_submission_under_test.inc"
#include "compute_host_hw_compute_under_test.inc"
#pragma GCC diagnostic pop
static int compute_mmio_le(FILE *f,u64 value,unsigned bytes)
{for(unsigned n=0;n<bytes;n++)if(fputc((value>>(8*n))&255,f)==EOF)return -EIO;return 0;}
static int compute_mmio_actions(FILE *f,struct compute_mmio_state *m,size_t first)
{for(size_t i=first;i<m->count;i++){const struct compute_mmio_action *a=&m->actions[i];
 if(compute_mmio_le(f,a->kind,4)||compute_mmio_le(f,a->address,4)||compute_mmio_le(f,a->value,4)||compute_mmio_le(f,a->mask,4))return -EIO;}
 return 0;}
#ifdef OPENGPU_COMPUTE_HOST_TEST_MAIN
static void compute_host_test_cleanup_peer(struct opengpu_device *,const char *,unsigned);
#define OPENGPU_COMPUTE_MMIO_CLEANUP_TEST_PEER(g,d,s) compute_host_test_cleanup_peer(g,d,s)
#endif
#include "opengpu_compute_host_cleanup.inc"
static void compute_mmio_progress(struct opengpu_device *gpu)
{struct compute_mmio_state *m=compute_mmio_state(gpu);
 if(gpu->hw.unified_fence && !m->kernel_waiting)opengpu_hw_unified_drain(gpu);}

static struct dma_fence *opengpu_tessellation_job_run(struct opengpu_sched_job *job)
{(void)job;assert(0);return (void *)(intptr_t)-EOPNOTSUPP;}
static int opengpu_hw_native_binned_async(struct opengpu_device *gpu,void *owner,
 const struct opengpu_render_context *context,u32 segment,struct dma_fence **fence)
{(void)gpu;(void)owner;(void)context;(void)segment;(void)fence;assert(0);return -EOPNOTSUPP;}
#include "compute_host_run_under_test.inc"
