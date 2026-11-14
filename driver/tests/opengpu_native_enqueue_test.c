/* Real owner/MMU + real native admission function, hardware kick primitive.
 * This checks CPU ownership/admission; no MMIO or GPU execution is claimed. */
#define OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN private_snapshot_regression_main
#include "opengpu_private_snapshot_test.c"
#undef OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN
#define GPU_UCMD_OP_NATIVE_BINNED_RENDER 7u
#define lower_32_bits(value) ((u32)(value))
#define spin_lock_irqsave(lock,flags) do { assert(!*(lock));*(lock)=1;(flags)=0; } while(0)
#define spin_unlock_irqrestore(lock,flags) do { assert(*(lock));*(lock)=0;(void)(flags); } while(0)
struct dma_fence { unsigned marker; };
struct opengpu_kernel_launch;
struct opengpu_command_events;
static bool busy;
static unsigned kicks;
static int kick_error;
static u32 expected_address;
static const struct opengpu_vm *expected_vm;
static struct dma_fence fence={0xface};
static bool opengpu_hw_execution_busy(struct opengpu_device *gpu)
{assert(gpu->hw.submit_lock.held);return busy;}
static int opengpu_hw_unified_submit_locked(struct opengpu_device *gpu,
    const struct opengpu_kernel_launch *launch,const struct opengpu_command_events *events,
    u32 opcode,u32 source,u32 destination,u32 bytes,u32 pattern,u32 sample_mode,
    u32 width,u32 height,u32 source_stride,u32 destination_stride,u64 expected_bytes,
    const struct opengpu_vm *vm,u32 delay,struct dma_fence **output)
{
    assert(gpu->hw.submit_lock.held && !gpu->hw.fence_lock && !launch && !events);
    assert(opcode==7 && source==expected_address && destination==(u64)source+512);
    assert(bytes==512 && !pattern && !sample_mode && width==7 && !height && !source_stride && !destination_stride);
    assert(!expected_bytes && vm==expected_vm && !delay);
    assert(gpu->hw.native_context==7 && gpu->hw.native_pass==8 && gpu->hw.native_generation==9);
    ++kicks;if(kick_error)return kick_error;*output=&fence;return 0;
}
#include "native_enqueue_under_test.inc"
int main(void)
{
    struct opengpu_device gpu={0};
    struct opengpu_render_context context={.gpu=&gpu,.references=1,.id=7};
    struct opengpu_render_context other={.gpu=&gpu,.references=1,.id=7};
    struct opengpu_private_snapshot *owner;
    u32 segment,address;
    struct dma_fence *output;
    unsigned char packet[512]={0};
    const u32 header[]={0x474e4a01,512,1,16,16,16,4,4};
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
    assert(!opengpu_mmu_init(&gpu));assert(!opengpu_mmu_vm_create(&gpu,&context.vm));copy_gpu=&gpu;
    for(unsigned i=0;i<8;++i)word(packet,i*4,header[i]);
    word(packet,24*4,7);word(packet,25*4,9);word(packet,26*4,8);
    owner=opengpu_private_snapshot_create(&gpu,&context);assert(!IS_ERR(owner));
    assert(!opengpu_private_snapshot_append(owner,packet,sizeof(packet),&segment));
    output=&fence;
    assert(opengpu_hw_native_binned_async(&gpu,owner,&context,segment,&output)==-EOPNOTSUPP);
    assert(!output && !kicks && !invalidations);
    gpu.hw.native_sources.available=true;gpu.hw.native_descriptor_preflight=true;
    output=&fence;
    assert(opengpu_hw_native_binned_async(&gpu,owner,&context,segment,&output)==-EINVAL);
    assert(!output && !kicks && !invalidations);
    assert(!opengpu_private_snapshot_prepare(owner));assert(!opengpu_private_snapshot_publish(owner));
    assert(!opengpu_private_snapshot_native_job_address(owner,segment,&address));
    expected_address=address;expected_vm=&context.vm;
    assert(opengpu_hw_native_binned_async(&gpu,owner,&other,segment,&output)==-EINVAL);
    assert(!output && !kicks && !invalidations);
    busy=true;assert(opengpu_hw_native_binned_async(&gpu,owner,&context,segment,&output)==-EBUSY);
    busy=false;idle_error=-ETIMEDOUT;
    assert(opengpu_hw_native_binned_async(&gpu,owner,&context,segment,&output)==-ETIMEDOUT);
    idle_error=0;assert(!output && !kicks && !invalidations);
    invalidate_failure=1;
    assert(opengpu_hw_native_binned_async(&gpu,owner,&context,segment,&output)==-EIO);
    assert(!output && !kicks && invalidations==1 && context.references==2 && owner->segments->mapped);
    invalidations=0;invalidate_failure=0;kick_error=-ENOMEM;
    assert(opengpu_hw_native_binned_async(&gpu,owner,&context,segment,&output)==-ENOMEM);
    assert(!output && kicks==1 && invalidations==1 && context.references==2 && owner->segments->lease.active);
    kick_error=0;invalidations=0;
    assert(!opengpu_hw_native_binned_async(&gpu,owner,&context,segment,&output));
    assert(output==&fence && kicks==2 && invalidations==1 && !gpu.hw.submit_lock.held);
    assert(context.references==2 && owner->segments->mapped && owner->segments->lease.active);
    assert(!opengpu_private_snapshot_retire(owner));assert(context.references==1 && !gpu.mmu.snapshot_leases);
    opengpu_mmu_vm_destroy(&gpu,&context.vm);opengpu_mmu_fini(&gpu);
    assert(!live_allocations && !fixture_buffers);
    puts("native enqueue admission: actual private owner/MMU, exact opcode7 tuple, no-kick rejection, invalidate failure, retained lease and healthy retry PASS (C only; no MMIO/GPU)");
    return 0;
}
