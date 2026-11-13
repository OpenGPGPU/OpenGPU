// SPDX-License-Identifier: GPL-2.0
/* Low-level MMIO, interrupt and execution control.
 *
 * Unified commands (kernel, copy, fill, resolve, render) are submitted by
 * staging registers and pulsing SUBMIT; a unified completion slot names the
 * command and raises the shared IRQ, and the IRQ handler drains that slot.
 * Draws may also use the legacy single-job register START path.  The
 * host-memory job ring was retired. */
#include <linux/dma-fence.h>
#include <linux/delay.h>
#include <linux/dma-mapping.h>
#include <linux/interrupt.h>
#include <linux/io.h>
#include <linux/overflow.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/platform_device.h>
#include <linux/of.h>

#include "opengpu_device.h"
#include "opengpu_shader_resources.h"
#include "opengpu_drm.h"
#include "opengpu_resolve_validator.h"
#include "opengpu_tlb_flush.h"
#include "opengpu_compute_features.h"
#include "opengpu_native_advanced_blend_features.h"
#include "opengpu_native_preflight_features.h"
#include "opengpu_graphics_features.h"
#include "opengpu_compute_uniform_records.h"
#include "opengpu_compute_indirect_source.h"
#include "opengpu_tessellation_job_validate.h"
#include "opengpu_tessellation_transport.h"
#include "opengpu_unified_completion.h"

struct opengpu_fence {
    struct dma_fence base;
};

static const char *opengpu_fence_driver_name(struct dma_fence *fence)
{
    return "opengpu";
}

static const char *opengpu_fence_timeline_name(struct dma_fence *fence)
{
    return "render";
}

static void opengpu_fence_release(struct dma_fence *base)
{
    struct opengpu_fence *fence;

    fence = container_of(base, struct opengpu_fence, base);
    kfree(fence);
}

static const struct dma_fence_ops opengpu_fence_ops = {
    .get_driver_name = opengpu_fence_driver_name,
    .get_timeline_name = opengpu_fence_timeline_name,
    .release = opengpu_fence_release,
};

static u32 opengpu_reg_read(struct opengpu_device *gpu, u32 offset)
{
    return ioread32(gpu->hw.regs + offset);
}

static u32 opengpu_mmio_read_callback(void *context, u32 address)
{
    return opengpu_reg_read(context, address);
}


static void opengpu_reg_write(struct opengpu_device *gpu, u32 offset, u32 value)
{
    iowrite32(value, gpu->hw.regs + offset);
}

static void opengpu_mmio_write_callback(void *context, u32 address, u32 value)
{
    opengpu_reg_write(context, address, value);
}

static int opengpu_hw_unified_result_error(u32 opcode, u32 status);
static bool opengpu_hw_unified_drain(struct opengpu_device *gpu);

static void opengpu_hw_record_fault_locked(struct opengpu_device *gpu,
                                            int error, u32 flags,
                                            u32 command_id, u32 opcode,
                                            u32 expected_command_id,
                                            u32 expected_opcode,
                                            u32 status, u64 processed,
                                            u64 expected)
{
    struct opengpu_fault_snapshot *fault = &gpu->hw.last_fault;

    fault->sequence++;
    if (!fault->sequence)
        fault->sequence++;
    fault->bytes_processed = processed;
    fault->expected_bytes = expected;
    fault->error = error;
    fault->command_id = command_id;
    fault->opcode = opcode;
    fault->status = status;
    fault->flags = flags | OPENGPU_FAULT_VALID;
    fault->expected_command_id = expected_command_id;
    fault->expected_opcode = expected_opcode;
    fault->tessellation_valid = false;
    memset(&fault->tessellation, 0, sizeof(fault->tessellation));
}

void opengpu_hw_get_fault(struct opengpu_device *gpu,
                          struct opengpu_fault_snapshot *fault)
{
    unsigned long flags;

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    *fault = gpu->hw.last_fault;
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
}

/* Completion poll interval. The register read doubles as a watchdog: it
 * drains IH records that raced the interrupt, and emulated hardware models
 * only advance while the host touches device registers, so a polling read
 * keeps slow-clocked models moving toward completion. */
#define OPENGPU_HW_POLL_INTERVAL_MS 5

/* Recovery from a timeout or abort on the unified-command path.
 *
 * The abort paths only clean up driver-side fences; a command that timed out
 * may still be executing and may still own in-flight memory transactions.
 * When the device advertises GPU_CAP_UNIFIED_RESET the driver asks the
 * hardware for a safe reset: it stops dispatch, drains every in-flight
 * command and memory transaction, resets the command-path state, and then
 * clears STATUS.RESET_BUSY and raises the completion IRQ.  Submissions are
 * refused with -EBUSY until that acknowledgement arrives; if it never does
 * (a hung engine or lower memory), the drain is abandoned and the device is
 * marked wedged so unified submissions fail with -EIO until it is reloaded.
 */
static void opengpu_hw_unified_reset_request(struct opengpu_device *gpu)
{
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_RESET))
        return;

    WRITE_ONCE(gpu->hw.reset_pending, true);
    opengpu_reg_write(gpu, GPU_REG_UCMD_RESET, GPU_UCMD_RESET_REQUEST);
    /* Abort disables the IRQ; re-enable it so the drain acknowledgement is
     * delivered, and keep the progress poll running because emulated models
     * only advance while the host touches device registers. */
    opengpu_reg_write(gpu, GPU_REG_IRQ, GPU_IRQ_ENABLE);
    mod_delayed_work(system_dfl_wq, &gpu->hw.reset_work,
                     msecs_to_jiffies(OPENGPU_RESET_WAIT_MS));
    mod_delayed_work(system_dfl_wq, &gpu->hw.poll_work,
                     msecs_to_jiffies(OPENGPU_HW_POLL_INTERVAL_MS));
}

/* True while a requested reset is still draining; clears the request once the
 * hardware drops RESET_BUSY.  Safe from IRQ context. */
static bool opengpu_hw_unified_reset_pending(struct opengpu_device *gpu)
{
    if (!READ_ONCE(gpu->hw.reset_pending))
        return false;
    if (opengpu_reg_read(gpu, GPU_REG_UCMD_STATUS) &
        GPU_UCMD_STATUS_RESET_BUSY)
        return true;
    WRITE_ONCE(gpu->hw.reset_pending, false);
    cancel_delayed_work(&gpu->hw.reset_work);
    dev_info(gpu->dev, "unified command reset drained\n");
    return false;
}

static void opengpu_reset_work(struct work_struct *work)
{
    struct opengpu_hw *hw;
    struct opengpu_device *gpu;
    unsigned long flags;
    u32 status;

    hw = container_of(to_delayed_work(work), struct opengpu_hw, reset_work);
    gpu = container_of(hw, struct opengpu_device, hw);
    if (!READ_ONCE(gpu->hw.reset_pending))
        return;

    status = opengpu_reg_read(gpu, GPU_REG_UCMD_STATUS);
    if (status & GPU_UCMD_STATUS_RESET_BUSY) {
        spin_lock_irqsave(&gpu->hw.fence_lock, flags);
        opengpu_hw_record_fault_locked(
            gpu, -EIO,
            OPENGPU_FAULT_RESET_ISSUED | OPENGPU_FAULT_RESET_TIMEOUT,
            gpu->hw.unified_command_id, gpu->hw.unified_opcode,
            gpu->hw.unified_command_id, gpu->hw.unified_opcode,
            status, 0, 0);
        spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
        WRITE_ONCE(gpu->hw.wedged, true);
        dev_err(gpu->dev,
                "unified command reset did not drain; device wedged\n");
    }
    WRITE_ONCE(gpu->hw.reset_pending, false);
}

void opengpu_hw_progress_tick(struct opengpu_device *gpu)
{
    /* Heartbeat read: RO register, no side effects. Emulated hardware
     * models only advance while the host touches device registers, so
     * fence waiters poll this to keep their draw moving. */
    opengpu_reg_read(gpu, GPU_REG_STATUS);
}

/* How long a fence wait blocks before yielding. 0 keeps the original
 * single wait, which is what real silicon wants; the ARTI
 * runner builds with -DOPENGPU_FENCE_SLICE_MS=4 so the emulated case takes the
 * sliced path below. Kept as a module parameter so a stuck guest can be
 * re-tuned from sysfs without rebuilding. */
static unsigned int fence_slice_ms = OPENGPU_FENCE_SLICE_MS;
#ifdef __KERNEL__
module_param(fence_slice_ms, uint, 0644);
MODULE_PARM_DESC(fence_slice_ms,
    "Fence wait slice in ms. 0 = one blocking wait (real "
    "silicon); non-zero = sliced wait that ticks the device and calls "
    "cond_resched() between slices (emulator)");
#endif

/** opengpu_hw_fence_wait - wait for @fence while keeping the device moving
 * @gpu: device whose registers drive the emulated model
 * @fence: fence to wait on; not consumed, the caller still owns it
 * @timeout_ms: total budget in milliseconds
 * @interruptible: let a signal abort the wait
 *
 * Returns 0 once the fence has signalled, the fence's error status if it
 * completed with one, or a negative errno. A 31-second uninterruptible block
 * is fine on silicon but starves every other guest CPU under an emulator, where
 * a single register tick can cost far more than its nominal time, so the
 * emulated build waits in slices and yields in between.
 */
int opengpu_hw_fence_wait(struct opengpu_device *gpu, struct dma_fence *fence,
                          unsigned int timeout_ms, bool interruptible)
{
    unsigned int slice = fence_slice_ms;
    unsigned long deadline;
    long timeout;
    int status;

    if (dma_fence_is_signaled(fence))
        goto out_status;

    if (!slice) {
        /* Real silicon: one blocking wait, no scheduling noise. */
        timeout = dma_fence_wait_timeout(fence, interruptible,
                                         msecs_to_jiffies(timeout_ms));
        if (timeout <= 0)
            return timeout < 0 ? (int)timeout : -ETIMEDOUT;
        goto out_status;
    }

    /* Emulator: the model advances only while the guest touches registers, so
     * the wait has to keep the device moving -- and cond_resched() so this task
     * does not monopolise the CPU for the whole timeout. */
    deadline = jiffies + msecs_to_jiffies(timeout_ms);
    while (!dma_fence_is_signaled(fence)) {
        unsigned long now = jiffies, step;
        long waited;

        if (time_after_eq(now, deadline))
            return -ETIMEDOUT;
        timeout = deadline - now;
        step = min_t(unsigned long, timeout,
                     max_t(unsigned long, 1, msecs_to_jiffies(slice)));
        waited = dma_fence_wait_timeout(fence, interruptible, step);
        if (waited < 0)
            return (int)waited;
        if (waited > 0)
            goto out_status;
        if (dma_fence_is_signaled(fence))
            goto out_status;
        if (time_after_eq(jiffies, deadline))
            return -ETIMEDOUT;
        opengpu_hw_progress_tick(gpu);
        cond_resched();
        if (interruptible && signal_pending(current) &&
            !dma_fence_is_signaled(fence))
            return -ERESTARTSYS;
    }
out_status:
    status = dma_fence_get_status(fence);
    return status > 0 ? 0 : status;
}

static void opengpu_hw_complete(struct opengpu_device *gpu, int error)
{
    struct dma_fence *fence;
    unsigned long flags;

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    fence = gpu->hw.active_fence;
    if (fence) {
        gpu->hw.active_fence = NULL;
        gpu->hw.active_completion_delay_ms = 0;
        if (error)
            dma_fence_set_error(fence, error);
        dma_fence_signal_locked(fence);
    }
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);

    if (fence)
        dma_fence_put(fence);
}

static u32 opengpu_next_job_id(struct opengpu_device *gpu)
{
    u32 id = ++gpu->hw.job_seqno & 0xffff;

    if (!id)
        id = ++gpu->hw.job_seqno & 0xffff;
    return id;
}

static void opengpu_job_fill(struct gpu_job_record *rec, u32 id,
                             const struct opengpu_job *job)
{
    memset(rec, 0, sizeof(*rec));
    rec->header = GPU_JOB_HDR(id, job->cmd_count);
    rec->cmd_base = lower_32_bits(job->cmd);
    rec->color_base = lower_32_bits(job->color);
    rec->depth_base = lower_32_bits(job->depth);
    rec->stride = job->stride;
    rec->state = GPU_JOB_STATE(job->depth_test, job->depth_func,
                               job->depth_write, job->cull_mode);
    rec->tex_base = lower_32_bits(job->texture);
    rec->tex_size = GPU_JOB_TEX_SIZE(job->texture_width,
                                     job->texture_height);
    rec->tex_config = job->texture_config;
    rec->msaa = job->sample_mode & 0x3u;
    rec->stencil_config = job->stencil_config;
    rec->stencil_ref_masks = job->stencil_ref_masks;
    rec->blend_config = job->blend_config;
    rec->pixel_formats = job->pixel_formats;
    rec->render_width = job->render_width;
    rec->render_height = job->render_height;
    if (job->stencil_test)
        rec->state |= GPU_JOB_STATE_STENCIL_TEST;
}


void opengpu_hw_abort(struct opengpu_device *gpu, int error)
{
    struct dma_fence *unified = NULL;
    unsigned long unified_flags;

    /* Exclude a completion from arming new delayed work after cancellation. */
    spin_lock_irqsave(&gpu->hw.fence_lock, unified_flags);
    gpu->hw.aborting++;
    spin_unlock_irqrestore(&gpu->hw.fence_lock, unified_flags);
    cancel_delayed_work_sync(&gpu->hw.timeout_work);
    cancel_delayed_work_sync(&gpu->hw.completion_work);
    cancel_delayed_work_sync(&gpu->hw.poll_work);
    opengpu_reg_write(gpu, GPU_REG_IRQ, 0);

    spin_lock_irqsave(&gpu->hw.fence_lock, unified_flags);
    unified = gpu->hw.unified_fence;
    gpu->hw.unified_fence = NULL;
    if (unified) {
        u32 fault_flags = OPENGPU_FAULT_ABORTED;

        if (error == -ETIMEDOUT)
            fault_flags |= OPENGPU_FAULT_TIMEOUT;
        if (gpu->hw.capabilities & GPU_CAP_UNIFIED_RESET)
            fault_flags |= OPENGPU_FAULT_RESET_ISSUED;
        opengpu_hw_record_fault_locked(
            gpu, error ?: -ECANCELED, fault_flags,
            gpu->hw.unified_command_id, gpu->hw.unified_opcode,
            gpu->hw.unified_command_id, gpu->hw.unified_opcode, 0, 0,
            gpu->hw.unified_expected_bytes);
        dma_fence_set_error(unified, error ?: -ECANCELED);
        dma_fence_signal_locked(unified);
    }
    /* Serialize completion-slot ownership with IRQ and poll drains. */
    if (unified && (opengpu_reg_read(gpu, GPU_REG_UCMD_STATUS) &
                    GPU_UCMD_STATUS_COMPLETION))
        opengpu_reg_write(gpu, GPU_REG_UCMD_COMPLETION_POP, 1);
    spin_unlock_irqrestore(&gpu->hw.fence_lock, unified_flags);
    if (unified)
        dma_fence_put(unified);

    opengpu_hw_unified_reset_request(gpu);


    opengpu_hw_complete(gpu, error ?: -ECANCELED);
    spin_lock_irqsave(&gpu->hw.fence_lock, unified_flags);
    gpu->hw.aborting--;
    spin_unlock_irqrestore(&gpu->hw.fence_lock, unified_flags);
}

static void opengpu_timeout_work(struct work_struct *work)
{
    struct opengpu_hw *hw;
    struct opengpu_device *gpu;
    unsigned long flags;
    struct dma_fence *fence = NULL;

    hw = container_of(to_delayed_work(work), struct opengpu_hw,
                      timeout_work);
    gpu = container_of(hw, struct opengpu_device, hw);

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    fence = gpu->hw.unified_fence;
    if (fence) {
        u32 fault_flags = OPENGPU_FAULT_TIMEOUT | OPENGPU_FAULT_ABORTED;

        gpu->hw.unified_fence = NULL;
        if (gpu->hw.capabilities & GPU_CAP_UNIFIED_RESET)
            fault_flags |= OPENGPU_FAULT_RESET_ISSUED;
        opengpu_hw_record_fault_locked(
            gpu, -ETIMEDOUT, fault_flags,
            gpu->hw.unified_command_id, gpu->hw.unified_opcode,
            gpu->hw.unified_command_id, gpu->hw.unified_opcode, 0, 0,
            gpu->hw.unified_expected_bytes);
        dma_fence_set_error(fence, -ETIMEDOUT);
        dma_fence_signal_locked(fence);
    }
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    if (fence) {
        /* The timed-out command may still be executing, so recover the
         * command path before the next submission. */
        opengpu_hw_unified_reset_request(gpu);
        dma_fence_put(fence);
        return;
    }
    /* Unified commands and their delayed fences are handled above or by
     * completion_work; do not enter the legacy completion path. */
    if (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS)
        return;

    {
        u32 status;

        status = opengpu_reg_read(gpu, GPU_REG_STATUS);
        opengpu_reg_write(gpu, GPU_REG_IRQ, 0);
        if (status & GPU_STATUS_ERROR)
            opengpu_hw_complete(gpu, -EIO);
        else if (status & GPU_STATUS_DONE)
            opengpu_hw_complete(gpu, 0);
        else
            opengpu_hw_complete(gpu, -ETIMEDOUT);
    }
}

static void opengpu_completion_work(struct work_struct *work)
{
    struct opengpu_hw *hw;
    struct opengpu_device *gpu;

    hw = container_of(to_delayed_work(work), struct opengpu_hw,
                      completion_work);
    gpu = container_of(hw, struct opengpu_device, hw);
    opengpu_hw_complete(gpu, 0);
}

static void opengpu_poll_work(struct work_struct *work)
{
    struct opengpu_hw *hw;
    struct opengpu_device *gpu;

    hw = container_of(to_delayed_work(work), struct opengpu_hw, poll_work);
    gpu = container_of(hw, struct opengpu_device, hw);

    if (opengpu_hw_unified_drain(gpu))
        return;
    if (READ_ONCE(gpu->hw.unified_fence)) {
        opengpu_hw_progress_tick(gpu);
        mod_delayed_work(system_dfl_wq, &gpu->hw.poll_work,
                         msecs_to_jiffies(OPENGPU_HW_POLL_INTERVAL_MS));
        return;
    }

    /* A requested safe reset is observed here and in the IRQ handler; keep
     * the poll ticking so the drain makes progress on emulated hardware. */
    if (opengpu_hw_unified_reset_pending(gpu)) {
        opengpu_hw_progress_tick(gpu);
        mod_delayed_work(system_dfl_wq, &gpu->hw.poll_work,
                         msecs_to_jiffies(OPENGPU_HW_POLL_INTERVAL_MS));
        return;
    }
}

static irqreturn_t opengpu_irq_handler(int irq, void *data)
{
    struct opengpu_device *gpu = data;
    u32 status;
    u32 irq_bits;
    u32 ack = GPU_IRQ_ENABLE;

    status = opengpu_reg_read(gpu, GPU_REG_STATUS);
    irq_bits = opengpu_reg_read(gpu, GPU_REG_IRQ);
    /* IRQ combines an enable bit with W1C pending causes.  Preserve enable
     * while acknowledging only the sticky bits that are set. */
    if (irq_bits & GPU_IRQ_PENDING)
        ack |= GPU_IRQ_PENDING;
    if (irq_bits & GPU_IRQ_VBLANK_PENDING)
        ack |= GPU_IRQ_VBLANK_PENDING;
    opengpu_reg_write(gpu, GPU_REG_IRQ, ack);
    opengpu_reg_write(gpu, GPU_REG_IRQ, GPU_IRQ_ENABLE);

    if (irq_bits & GPU_IRQ_VBLANK_PENDING)
        opengpu_display_handle_vblank(gpu);

    if (!(irq_bits & GPU_IRQ_PENDING))
        return IRQ_HANDLED;

    dev_info(gpu->dev, "OPENGPU irq: status=%08x\n", status);
    opengpu_hw_unified_reset_pending(gpu);
    opengpu_hw_unified_drain(gpu);

    /* Unified completion owns its fence and any delayed signaling. */
    if (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS)
        return IRQ_HANDLED;

    if (gpu->hw.active_completion_delay_ms) {
        cancel_delayed_work(&gpu->hw.timeout_work);
        mod_delayed_work(system_dfl_wq, &gpu->hw.completion_work,
                         msecs_to_jiffies(
                             gpu->hw.active_completion_delay_ms));
        return IRQ_HANDLED;
    }
    cancel_delayed_work(&gpu->hw.timeout_work);
    cancel_delayed_work(&gpu->hw.poll_work);
    opengpu_hw_complete(gpu, status & GPU_STATUS_ERROR ? -EIO : 0);
    return IRQ_HANDLED;
}

/* Enable device delivery only after Linux has installed its IRQ handler. */
static void opengpu_hw_enable_irqs(struct opengpu_device *gpu)
{
    opengpu_reg_write(gpu, GPU_REG_IRQ, GPU_IRQ_ENABLE);
}

int opengpu_hw_init(struct opengpu_device *gpu, struct platform_device *pdev)
{
    struct resource *res;
    u32 id;
    int ret;

    mutex_init(&gpu->hw.submit_lock);
    spin_lock_init(&gpu->hw.fence_lock);
    gpu->hw.fence_context = dma_fence_context_alloc(1);
    INIT_DELAYED_WORK(&gpu->hw.timeout_work, opengpu_timeout_work);
    INIT_DELAYED_WORK(&gpu->hw.completion_work, opengpu_completion_work);
    INIT_DELAYED_WORK(&gpu->hw.poll_work, opengpu_poll_work);
    INIT_DELAYED_WORK(&gpu->hw.reset_work, opengpu_reset_work);

    ret = dma_set_mask_and_coherent(gpu->dev, DMA_BIT_MASK(32));
    if (ret)
        return dev_err_probe(gpu->dev, ret, "32-bit DMA is unavailable\n");

    res = platform_get_resource_byname(pdev, IORESOURCE_MEM, "ctrl");
    if (!res)
        res = platform_get_resource(pdev, IORESOURCE_MEM, 0);
    if (!res)
        return dev_err_probe(gpu->dev, -ENODEV, "missing ctrl resource\n");

    gpu->hw.regs_phys = res->start;
    gpu->hw.regs_size = resource_size(res);
    gpu->hw.regs = devm_ioremap_resource(gpu->dev, res);
    if (IS_ERR(gpu->hw.regs))
        return PTR_ERR(gpu->hw.regs);

    id = opengpu_reg_read(gpu, GPU_REG_ID);
    if ((id >> 16) != GPU_DEVICE_ID)
        return dev_err_probe(gpu->dev, -ENODEV,
                             "bad device id 0x%08x\n", id);
    gpu->hw.capabilities = opengpu_reg_read(gpu, GPU_REG_CAPABILITIES);
    gpu->hw.clip_depth = opengpu_graphics_clip_depth_supported(id, gpu->hw.capabilities);
    gpu->hw.sampler_nearest = opengpu_graphics_nearest_supported(id, gpu->hw.capabilities);
    gpu->hw.pixel_center = opengpu_graphics_pixel_center_supported(id, gpu->hw.capabilities);
    gpu->hw.sampler_axis_wrap = opengpu_graphics_axis_wrap_supported(id, gpu->hw.capabilities);
    gpu->hw.sampler_biased_clamp = opengpu_graphics_biased_clamp_supported(id, gpu->hw.capabilities);
    gpu->hw.sampler_mirrored_repeat = opengpu_graphics_mirrored_repeat_supported(id, gpu->hw.capabilities);
    gpu->hw.viewport = opengpu_graphics_viewport_supported(id, gpu->hw.capabilities);
    gpu->hw.depth_range = opengpu_graphics_depth_range_supported(id, gpu->hw.capabilities);
    gpu->hw.constant_blend = opengpu_graphics_constant_blend_supported(id, gpu->hw.capabilities);
    if (!opengpu_read_compute_features(id, gpu->hw.regs_size, gpu,
                                      opengpu_mmio_read_callback,
                                      &gpu->hw.compute_resource_flags))
        return dev_err_probe(gpu->dev, -EINVAL,
                             "invalid ABI v2 compute resource record\n");
    if (!opengpu_read_native_advanced_blend_support(id,
            gpu->hw.compute_resource_flags, gpu->hw.capabilities,
            gpu->hw.regs_size, gpu, opengpu_mmio_read_callback,
            &gpu->hw.native_advanced_blend_transport))
        return dev_err_probe(gpu->dev, -EINVAL,
                             "invalid native advanced blend transport record\n");
    if (!opengpu_read_native_preflight_support(id,
            gpu->hw.compute_resource_flags, gpu->hw.capabilities,
            gpu->hw.regs_size, gpu, opengpu_mmio_read_callback,
            &gpu->hw.native_descriptor_preflight))
        return dev_err_probe(gpu->dev, -EINVAL,
                             "invalid native descriptor preflight record\n");
    if (!opengpu_read_native_source_limits(id,
            gpu->hw.compute_resource_flags, gpu->hw.capabilities,
            gpu->hw.regs_size, gpu, opengpu_mmio_read_callback,
            &gpu->hw.native_sources))
        return dev_err_probe(gpu->dev, -EINVAL,
                             "invalid protected native source profile record\n");
    if (!opengpu_read_compute_local_limits(id, gpu->hw.regs_size,
            gpu->hw.compute_resource_flags, gpu, opengpu_mmio_read_callback,
            &gpu->hw.compute_local_limits))
        return dev_err_probe(gpu->dev, -EINVAL,
                             "invalid ABI v2 compute local limits\n");
    if (!opengpu_read_compute_shared_limits(id, gpu->hw.regs_size,
            gpu->hw.compute_resource_flags, gpu, opengpu_mmio_read_callback,
            &gpu->hw.compute_shared_limits))
        return dev_err_probe(gpu->dev, -EINVAL,
                             "invalid ABI v2 compute shared limits\n");
    if (!opengpu_read_compute_indirect_support(
            of_property_read_bool(pdev->dev.of_node, "opengpu,gci1"), id,
            gpu->hw.regs_size, gpu->hw.capabilities, &gpu->hw.compute_local_limits,
            gpu, opengpu_mmio_read_callback, &gpu->hw.compute_indirect_transport))
        return dev_err_probe(gpu->dev, -EINVAL,
                             "invalid declared GCI1 compute profile\n");
    if (!opengpu_tessellation_transport_features(
            gpu->hw.compute_resource_flags, gpu->hw.capabilities,
            &gpu->hw.tessellation_transport,
            &gpu->hw.tessellation_feedback_transport))
        return dev_err_probe(gpu->dev, -EINVAL,
                             "invalid native tessellation transport flags\n");
    if (!opengpu_read_tessellation_host(
            gpu->hw.tessellation_transport, gpu->hw.regs_size, gpu,
            opengpu_mmio_read_callback, &gpu->hw.tessellation_engines,
            &gpu->hw.tessellation_virtual_images))
        return dev_err_probe(gpu->dev, -EINVAL,
                             "invalid native tessellation host record\n");
    if (!opengpu_read_compute_texture_command_support(
            gpu->hw.compute_resource_flags, gpu->hw.regs_size, gpu,
            opengpu_mmio_read_callback, &gpu->hw.compute_texture_transport))
        return dev_err_probe(gpu->dev, -EINVAL,
                             "invalid compute texture command record\n");
    if (!opengpu_read_storage_command_support(
            gpu->hw.compute_resource_flags, gpu->hw.regs_size, gpu,
            opengpu_mmio_read_callback, &gpu->hw.compute_storage_transport))
        return dev_err_probe(gpu->dev, -EINVAL,
                             "invalid compute storage command record\n");
    if (!opengpu_read_image_command_support(
            gpu->hw.compute_resource_flags, gpu->hw.regs_size, gpu,
            opengpu_mmio_read_callback, &gpu->hw.compute_image_transport))
        return dev_err_probe(gpu->dev, -EINVAL,
                             "invalid compute image command record\n");
    if (!opengpu_read_atomic_counter_command_support(
            gpu->hw.compute_resource_flags, gpu->hw.regs_size, gpu,
            opengpu_mmio_read_callback, &gpu->hw.compute_counter_transport))
        return dev_err_probe(gpu->dev, -EINVAL,
                             "invalid atomic counter command record\n");

    if (!opengpu_read_compute_uniform_command_support(
            gpu->hw.compute_resource_flags, gpu->hw.regs_size, gpu,
            opengpu_mmio_read_callback, &gpu->hw.compute_uniform_transport))
        return dev_err_probe(gpu->dev, -EINVAL,
                             "invalid compute uniform command record\n");
    if (gpu->hw.compute_uniform_transport &&
        (!(gpu->hw.capabilities & GPU_CAP_PROTECTED_VM_STATE) ||
         !(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS)))
        return dev_err_probe(gpu->dev, -EINVAL,
                             "compute uniforms require protected unified commands\n");
    if (gpu->hw.compute_counter_transport &&
        !(gpu->hw.capabilities & GPU_CAP_PROTECTED_VM_STATE))
        return dev_err_probe(gpu->dev, -EINVAL,
                             "atomic counters require protected VM state\n");
    gpu->hw.shader_config = 0;
    if (gpu->hw.capabilities & GPU_CAP_SHADER_CONFIG_INFO) {
        u32 cfg = opengpu_reg_read(gpu, GPU_REG_SHADER_CONFIG);
        u32 batch = (gpu->hw.capabilities & GPU_CAP_FRAGMENT_BATCH_MASK) >>
                    GPU_CAP_FRAGMENT_BATCH_SHIFT;

        if (!opengpu_shader_resources_valid(cfg, batch))
            return dev_err_probe(gpu->dev, -EINVAL,
                                 "invalid shader resource description 0x%08x\n", cfg);
        gpu->hw.shader_config = cfg;
        dev_info(gpu->dev,
                 "shader resources: lanes=%u warps=%u fragment_banks=%u executors=%u\n",
                 opengpu_shader_resource_lanes(cfg), opengpu_shader_resource_warps(cfg),
                 opengpu_shader_resource_banks(cfg), opengpu_shader_resource_cus(cfg));
    }
    dev_info(gpu->dev,
             "GPU ABI device=0x%08x version=0x%04x capabilities=0x%08x\n",
             id, id & 0xffff, gpu->hw.capabilities);

    if (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS) {
        u32 command_status = opengpu_reg_read(gpu, GPU_REG_UCMD_STATUS);

        /* Firmware must hand the device to Linux idle. Discard a consumed
         * boot-time result and clear the sticky submit-overflow diagnostic so
         * the first scheduler job starts from an owned completion slot. */
        if (command_status & GPU_UCMD_STATUS_COMPLETION)
            opengpu_reg_write(gpu, GPU_REG_UCMD_COMPLETION_POP, 1);
        if (command_status & GPU_UCMD_STATUS_OVERFLOW)
            opengpu_reg_write(gpu, GPU_REG_UCMD_STATUS,
                              GPU_UCMD_STATUS_OVERFLOW);
    }

    gpu->hw.irq = platform_get_irq_optional(pdev, 0);
    if (gpu->hw.irq == -EPROBE_DEFER)
        return -EPROBE_DEFER;
    if (gpu->hw.irq > 0) {
        ret = devm_request_irq(gpu->dev, gpu->hw.irq,
                               opengpu_irq_handler, 0, OPENGPU_NAME, gpu);
        if (ret)
            return dev_err_probe(gpu->dev, ret, "cannot request irq\n");
        opengpu_hw_enable_irqs(gpu);
    }

    /* The host-memory job ring is retired: user draws are submitted as unified
     * render commands and the self-test/fallback uses the legacy START
     * snapshot, so the driver no longer programs the admin ring.  The RTL
     * module is left idle until it is removed. */
    return 0;
}

void opengpu_hw_fini(struct opengpu_device *gpu)
{
    opengpu_hw_abort(gpu, -ECANCELED);
    /* abort may have requested a safe reset and re-armed this work; it must
     * not outlive the register aperture. */
    cancel_delayed_work_sync(&gpu->hw.reset_work);
    cancel_delayed_work_sync(&gpu->hw.poll_work);
}

/* Ordinary commands wait for private snapshot retirement, including the gap
 * between hardware completion and successful VM revocation. In particular an
 * ASID-0/Bare command must not gain physical write access during that gap.
 * Never wait with submit_lock held: the owner needs it to drain and unmap.
 * Private snapshot publication uses its separately verified owner path;
 * cache invalidation and retirement do not pass through this admission gate. */
int opengpu_hw_snapshot_submission_lock(struct opengpu_device *gpu)
{
    unsigned long timeout = jiffies + msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS + 100);

    for (;;) {
        mutex_lock(&gpu->hw.submit_lock);
        if (READ_ONCE(gpu->hw.wedged)) {
            mutex_unlock(&gpu->hw.submit_lock);
            return -EIO;
        }
        if (!gpu->mmu.snapshot_leases)
            return 0;
        mutex_unlock(&gpu->hw.submit_lock);
        if (time_after(jiffies, timeout))
            return -ETIMEDOUT;
        opengpu_hw_progress_tick(gpu);
        usleep_range(100, 200);
    }
}

/* Program the address space a submission will run in.  Called with submit_lock
 * held, immediately before the doorbell, so the switch cannot race another
 * submission. A NULL or disabled VM selects the ASID-0 identity map.
 * The CU TLBs and the graphics address translator are ASID-tagged, so a switch
 * needs no flush; each entry only resolves under its own ASID. Bare builds
 * (MMU never enabled) skip satp. */
static int opengpu_hw_activate_vm_locked(struct opengpu_device *gpu,
                                         const struct opengpu_vm *vm)
{
    if (!gpu->mmu.enabled)
        return 0;
    if (!vm || !vm->enabled)
        return opengpu_hw_set_satp(gpu, gpu->mmu.root.dma, 0);
    return opengpu_hw_set_satp(gpu, vm->root.dma, vm->asid);
}

static int opengpu_hw_validate_job(const struct opengpu_job *job,
                                   struct dma_fence **out_fence)
{
    if (!out_fence)
        return -EINVAL;
    if (upper_32_bits(job->cmd) || upper_32_bits(job->color) ||
        upper_32_bits(job->depth) || upper_32_bits(job->texture))
        return -ERANGE;
    return 0;
}

static int opengpu_hw_unified_result_error(u32 opcode, u32 status)
{
    if (status == GPU_UCMD_RESULT_EVENT_DEPENDENCY_FAILED)
        return -ECANCELED;
    if (opcode == GPU_UCMD_OP_KERNEL || opcode == GPU_UCMD_OP_KERNEL_INDIRECT) {
        switch (status) {
        case GPU_UCMD_RESULT_SUCCESS:
            return 0;
        case GPU_UCMD_KERNEL_INVALID_PC:
        case GPU_UCMD_KERNEL_INVALID_GRID:
        case GPU_UCMD_KERNEL_INVALID_LOCAL_SIZE:
        case GPU_UCMD_KERNEL_MISALIGNED_KERNARG:
        case GPU_UCMD_KERNEL_DMA_DEPENDENCY:
            return -EINVAL;
        case GPU_INDIRECT_RESULT_BOUNDS:
            return -ERANGE;
        case GPU_UCMD_KERNEL_EXECUTION_FAILED:
        default:
            return -EIO;
        }
    }
    if (opcode == GPU_UCMD_OP_RENDER || opcode == GPU_UCMD_OP_TESSELLATION)
        return status == GPU_UCMD_RESULT_SUCCESS ? 0 : -EIO;
    if (opcode >= GPU_UCMD_OP_QUERY_BEGIN && opcode <= GPU_UCMD_OP_QUERY_RESULT) {
        /* GQV1 has its own status namespace, distinct from DMA errors. */
        switch (status) {
        case 0: return 0;
        case 1: return -ERANGE;    /* bounds or encoding */
        case 2: return -EINVAL;    /* record identity */
        case 3: return -EIO;       /* memory or renderer fault */
        case 4: return -ECANCELED; /* reset */
        case 5: return -EINVAL;    /* active/target mismatch */
        case 6: return -ENOSPC;    /* active-context capacity */
        default: return -EIO;
        }
    }
    switch (status) {
    case GPU_UCMD_RESULT_SUCCESS:
        return 0;
    case GPU_UCMD_RESULT_INVALID_ALIGNMENT:
    case GPU_UCMD_RESULT_INVALID_LENGTH:
    case GPU_UCMD_RESULT_ADDRESS_OVERFLOW:
        return -ERANGE;
    case GPU_UCMD_RESULT_OVERLAP_UNSUPPORTED:
        return -EINVAL;
    case GPU_UCMD_RESULT_READ_FAULT:
    case GPU_UCMD_RESULT_WRITE_FAULT:
    default:
        return -EIO;
    }
}

static bool opengpu_hw_unified_drain(struct opengpu_device *gpu)
{
    struct dma_fence *fence = NULL;
    unsigned long flags;
    u64 processed;
    u32 completion;
    u32 command_id;
    u32 opcode;
    u64 expected_bytes;
    struct opengpu_tessellation_completion tessellation;
    bool tessellation_valid = false;
    int ret;

    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return false;

    /* IRQ and the polling worker can arrive together. Protect the held slot
     * from the status check through snapshot capture and POP. */
    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    if (!(opengpu_reg_read(gpu, GPU_REG_UCMD_STATUS) &
          GPU_UCMD_STATUS_COMPLETION)) {
        spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
        return false;
    }

    completion = opengpu_reg_read(gpu, GPU_REG_UCMD_COMPLETION);
    processed = opengpu_reg_read(gpu,
        GPU_REG_UCMD_COMPLETION_BYTES_LO);
    processed |= (u64)opengpu_reg_read(gpu,
        GPU_REG_UCMD_COMPLETION_BYTES_HI) << 32;
    /* The completion window is invalidated by POP. Do not probe it for other
     * opcodes or when the device aperture does not contain the full window. */
    if (GPU_UCMD_COMPLETION_OPCODE(completion) == GPU_UCMD_OP_TESSELLATION)
        tessellation_valid = opengpu_tessellation_completion_capture(
            GPU_UCMD_COMPLETION_STATUS(completion), gpu->hw.regs_size, gpu,
            opengpu_mmio_read_callback, &tessellation);
    opengpu_reg_write(gpu, GPU_REG_UCMD_COMPLETION_POP, 1);

    fence = gpu->hw.unified_fence;
    command_id = gpu->hw.unified_command_id;
    opcode = gpu->hw.unified_opcode;
    expected_bytes = gpu->hw.unified_expected_bytes;
    gpu->hw.unified_fence = NULL;
    if (fence) {
        u32 fault_flags = 0;
        u32 actual_id = GPU_UCMD_COMPLETION_ID(completion);
        u32 actual_opcode = GPU_UCMD_COMPLETION_OPCODE(completion);
        u32 actual_status = GPU_UCMD_COMPLETION_STATUS(completion);
        bool actual_success = GPU_UCMD_COMPLETION_SUCCESS(completion);

        ret = opengpu_hw_unified_result_error(opcode,
            actual_status);
        if (ret)
            fault_flags |= OPENGPU_FAULT_COMPLETION_ERROR;
        if (actual_id != command_id)
            fault_flags |= OPENGPU_FAULT_COMMAND_ID_MISMATCH;
        if (actual_opcode != opcode)
            fault_flags |= OPENGPU_FAULT_OPCODE_MISMATCH;
        if (actual_success != !ret)
            fault_flags |= OPENGPU_FAULT_SUCCESS_MISMATCH;
        if (!ret && !opengpu_unified_completion_data_valid(opcode, processed, expected_bytes))
            fault_flags |= OPENGPU_FAULT_BYTES_MISMATCH;
        if (fault_flags & ~OPENGPU_FAULT_COMPLETION_ERROR)
            ret = -EIO;
        if (ret) {
            opengpu_hw_record_fault_locked(
                gpu, ret, fault_flags, actual_id, actual_opcode,
                command_id, opcode, actual_status, processed, expected_bytes);
            if (tessellation_valid && actual_id == command_id &&
                actual_opcode == opcode && opcode == GPU_UCMD_OP_TESSELLATION) {
                gpu->hw.last_fault.tessellation = tessellation;
                gpu->hw.last_fault.tessellation_valid = true;
            }
            dev_err(gpu->dev,
                    "unified command %u opcode %u failed: completion=%08x bytes=%llu error=%d\n",
                    command_id, opcode, completion, processed, ret);
            dma_fence_set_error(fence, ret);
        }
        cancel_delayed_work(&gpu->hw.timeout_work);
        if (gpu->hw.unified_completion_delay_ms && !gpu->hw.aborting) {
            gpu->hw.active_fence = fence;
            gpu->hw.active_completion_delay_ms =
                gpu->hw.unified_completion_delay_ms;
            mod_delayed_work(system_dfl_wq, &gpu->hw.completion_work,
                             msecs_to_jiffies(gpu->hw.active_completion_delay_ms));
            fence = NULL; /* delayed work owns the reference */
        } else {
            dma_fence_signal_locked(fence);
        }
    }
    gpu->hw.unified_completion_delay_ms = 0;
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    if (fence)
        dma_fence_put(fence);
    return true;
}

/* submit_lock excludes another submitter while an old timeout is drained. */
static int opengpu_hw_unified_quiesce_timeout(struct opengpu_device *gpu)
{
    unsigned long flags;
    bool busy;

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    busy = gpu->hw.unified_fence || gpu->hw.active_fence || gpu->hw.aborting;
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    if (busy)
        return -EBUSY;
    cancel_delayed_work_sync(&gpu->hw.timeout_work);
    return 0;
}

/* Arm recovery before ringing the doorbell, while the completion and abort
 * paths are excluded. Otherwise an immediate IRQ can consume the completion
 * before its timeout is armed, leaving a stale timeout for a later command. */
static void opengpu_hw_unified_kick(struct opengpu_device *gpu,
                                    struct dma_fence *fence)
{
    unsigned long flags;

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    if (gpu->hw.unified_fence == fence && !gpu->hw.aborting) {
        mod_delayed_work(system_dfl_wq, &gpu->hw.timeout_work,
                         msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS));
        mod_delayed_work(system_dfl_wq, &gpu->hw.poll_work,
                         msecs_to_jiffies(OPENGPU_HW_POLL_INTERVAL_MS));
        opengpu_reg_write(gpu, GPU_REG_UCMD_SUBMIT, 1);
    }
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
}

static int opengpu_hw_unified_submit_locked(
    struct opengpu_device *gpu,
    const struct opengpu_kernel_launch *launch,
    const struct opengpu_command_events *events,
    u32 opcode, u32 source, u32 destination,
    u32 bytes, u32 pattern, u32 sample_mode, u32 width, u32 height,
    u32 source_stride, u32 destination_stride, u64 expected_bytes,
    const struct opengpu_vm *vm, u32 completion_delay_ms,
    struct dma_fence **out_fence)
{
    struct opengpu_fence *fence;
    unsigned long flags;
    u32 command_id;
    u32 status;
    u32 texture_words[OPENGPU_COMPUTE_TEXTURE_COMMAND_WORDS];
    bool texture_enabled = launch && launch->texture_enabled;
    u32 storage_words[OPENGPU_STORAGE_COMMAND_WORDS];
    bool storage_enabled = launch && launch->storage_enabled;
    u32 image_words[OPENGPU_IMAGE_COMMAND_WORDS];
    bool image_enabled = launch && launch->image_enabled;
    u32 counter_words[OPENGPU_ATOMIC_COUNTER_COMMAND_WORDS];
    bool counter_enabled = launch && launch->counter_enabled;
    u32 uniform_words[OPENGPU_COMPUTE_UNIFORM_COMMAND_WORDS];
    bool uniform_enabled = launch && launch->uniform_enabled;
    int ret = 0;

    if (!out_fence)
        return -EINVAL;
    *out_fence = NULL;
    if ((opcode == GPU_UCMD_OP_KERNEL_INDIRECT) !=
            !!(launch && launch->indirect_enabled) ||
        (opcode == GPU_UCMD_OP_KERNEL_INDIRECT && !gpu->hw.compute_indirect_transport))
        return -EINVAL;
    if (texture_enabled &&
        ((opcode != GPU_UCMD_OP_KERNEL && opcode != GPU_UCMD_OP_KERNEL_INDIRECT) || !gpu->hw.compute_texture_transport ||
         !opengpu_encode_compute_texture_command_context(texture_words, &launch->texture)))
        return -EINVAL;
    if (storage_enabled &&
        ((opcode != GPU_UCMD_OP_KERNEL && opcode != GPU_UCMD_OP_KERNEL_INDIRECT) || !gpu->hw.compute_storage_transport ||
         !opengpu_encode_storage_command_context(storage_words, &launch->storage)))
        return -EINVAL;
    if (image_enabled &&
        ((opcode != GPU_UCMD_OP_KERNEL && opcode != GPU_UCMD_OP_KERNEL_INDIRECT) || !gpu->hw.compute_image_transport ||
         !opengpu_encode_image_command_context(image_words, &launch->image)))
        return -EINVAL;
    if (counter_enabled &&
        ((opcode != GPU_UCMD_OP_KERNEL && opcode != GPU_UCMD_OP_KERNEL_INDIRECT) || !gpu->hw.compute_counter_transport ||
         !opengpu_encode_atomic_counter_command_context(counter_words, &launch->counter)))
        return -EINVAL;
    if (uniform_enabled &&
        ((opcode != GPU_UCMD_OP_KERNEL && opcode != GPU_UCMD_OP_KERNEL_INDIRECT) || !gpu->hw.compute_uniform_transport ||
         !(gpu->hw.capabilities & GPU_CAP_PROTECTED_VM_STATE) ||
         !opengpu_encode_compute_uniform_command_context(uniform_words, &launch->uniform)))
        return -EINVAL;
    /* A previous timeout may already be running even after completion canceled
     * its timer. Wait for it before publishing another fence, but never cancel
     * recovery for an in-flight command. submit_lock excludes new submitters. */
    ret = opengpu_hw_unified_quiesce_timeout(gpu);
    if (ret)
        return ret;
    if (READ_ONCE(gpu->hw.wedged))
        return -EIO;
    if (events && opcode == GPU_UCMD_OP_TESSELLATION &&
        ((events->flags & ~(GPU_UCMD_FLAG_RASTER_DISCARD |
                            GPU_UCMD_FLAG_FEEDBACK_CAPTURE)) ||
         events->wait_event || events->signal_event))
        return -EINVAL;
    if (events && opcode != GPU_UCMD_OP_TESSELLATION &&
        ((events->flags & ~(GPU_UCMD_FLAG_WAIT_EVENT |
                            GPU_UCMD_FLAG_SIGNAL_EVENT)) ||
         (events->wait_event & ~0xffffu) ||
         (events->signal_event & ~0xffffu) ||
         (!(events->flags & GPU_UCMD_FLAG_WAIT_EVENT) &&
          events->wait_event) ||
         (!(events->flags & GPU_UCMD_FLAG_SIGNAL_EVENT) &&
          events->signal_event)))
        return -EINVAL;
    status = opengpu_reg_read(gpu, GPU_REG_UCMD_STATUS);
    /* A safe reset owns the command path until it drains: report busy rather
     * than letting a submission race the state reset. */
    if (READ_ONCE(gpu->hw.reset_pending) ||
        (status & GPU_UCMD_STATUS_RESET_BUSY))
        return -EBUSY;
    if (!(status & GPU_UCMD_STATUS_READY) ||
        (status & GPU_UCMD_STATUS_COMPLETION))
        return -EBUSY;
    if (status & GPU_UCMD_STATUS_OVERFLOW)
        opengpu_reg_write(gpu, GPU_REG_UCMD_STATUS,
                          GPU_UCMD_STATUS_OVERFLOW);
    if (status & GPU_UCMD_STATUS_RESET_REJECTED)
        opengpu_reg_write(gpu, GPU_REG_UCMD_STATUS,
                          GPU_UCMD_STATUS_RESET_REJECTED);

    /* Hardware can retain translation owners after software completion.
     * Drain them before writing the submitting address space. */
    ret = opengpu_hw_wait_idle_locked(gpu);
    if (ret)
        return ret;
    /* Select the submitting address space before any command register is
     * staged, still under submit_lock. */
    ret = opengpu_hw_activate_vm_locked(gpu, vm);
    if (ret)
        return ret;

    fence = kzalloc(sizeof(*fence), GFP_KERNEL);
    if (!fence)
        return -ENOMEM;
    dma_fence_init(&fence->base, &opengpu_fence_ops,
                   &gpu->hw.fence_lock, gpu->hw.fence_context,
                   ++gpu->hw.fence_seqno);

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    if (gpu->hw.unified_fence || gpu->hw.active_fence || gpu->hw.aborting) {
        ret = -EBUSY;
    } else {
        command_id = opengpu_next_job_id(gpu) & 0xffu;
        gpu->hw.unified_fence = &fence->base;
        gpu->hw.unified_command_id = command_id;
        gpu->hw.unified_opcode = opcode;
        gpu->hw.unified_expected_bytes = expected_bytes;
        gpu->hw.unified_completion_delay_ms = completion_delay_ms;
        *out_fence = dma_fence_get(&fence->base);
    }
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    if (ret) {
        dma_fence_put(&fence->base);
        return ret;
    }

    if (gpu->hw.compute_texture_transport)
        opengpu_write_compute_texture_command(gpu, opengpu_mmio_write_callback,
                                              texture_enabled ? texture_words : NULL);
    if (gpu->hw.compute_storage_transport)
        opengpu_write_storage_command(gpu, opengpu_mmio_write_callback,
                                      storage_enabled ? storage_words : NULL);
    if (gpu->hw.compute_image_transport)
        opengpu_write_image_command(gpu, opengpu_mmio_write_callback,
                                    image_enabled ? image_words : NULL);
    if (gpu->hw.compute_counter_transport)
        opengpu_write_atomic_counter_command(gpu, opengpu_mmio_write_callback,
                                    counter_enabled ? counter_words : NULL);
    if (gpu->hw.compute_uniform_transport)
        opengpu_write_compute_uniform_command(gpu, opengpu_mmio_write_callback,
                                    uniform_enabled ? uniform_words : NULL);
    opengpu_reg_write(gpu, GPU_REG_UCMD_ID, command_id);
    opengpu_reg_write(gpu, GPU_REG_UCMD_OPCODE, opcode);
    opengpu_reg_write(gpu, GPU_REG_UCMD_KERNEL_PC,
                      launch ? lower_32_bits(launch->kernel_pc) : 0);
    opengpu_reg_write(gpu, GPU_REG_UCMD_KERNARG,
                      launch ? lower_32_bits(launch->kernarg) : 0);
    opengpu_reg_write(gpu, GPU_REG_UCMD_GRID_X,
                      launch ? launch->grid[0] : 1);
    opengpu_reg_write(gpu, GPU_REG_UCMD_GRID_Y,
                      launch ? launch->grid[1] : 1);
    opengpu_reg_write(gpu, GPU_REG_UCMD_GRID_Z,
                      launch ? launch->grid[2] : 1);
    opengpu_reg_write(gpu, GPU_REG_UCMD_LOCAL_X,
                      launch ? launch->local[0] : 1);
    opengpu_reg_write(gpu, GPU_REG_UCMD_LOCAL_Y,
                      launch ? launch->local[1] : 1);
    opengpu_reg_write(gpu, GPU_REG_UCMD_LOCAL_Z,
                      launch ? launch->local[2] : 1);
    opengpu_reg_write(gpu, GPU_REG_UCMD_FLAGS,
                      events ? events->flags : 0);
    opengpu_reg_write(gpu, GPU_REG_UCMD_WAIT_EVENT,
                      events ? events->wait_event : 0);
    opengpu_reg_write(gpu, GPU_REG_UCMD_SIGNAL_EVENT,
                      events ? events->signal_event : 0);
    opengpu_reg_write(gpu, GPU_REG_UCMD_SOURCE, source);
    opengpu_reg_write(gpu, GPU_REG_UCMD_DESTINATION, destination);
    opengpu_reg_write(gpu, GPU_REG_UCMD_BYTES, bytes);
    opengpu_reg_write(gpu, GPU_REG_UCMD_PATTERN, pattern);
    opengpu_reg_write(gpu, GPU_REG_UCMD_WIDTH, width);
    opengpu_reg_write(gpu, GPU_REG_UCMD_HEIGHT, height);
    opengpu_reg_write(gpu, GPU_REG_UCMD_SOURCE_STRIDE, source_stride);
    opengpu_reg_write(gpu, GPU_REG_UCMD_DEST_STRIDE,
                      destination_stride);
    opengpu_reg_write(gpu, GPU_REG_UCMD_SAMPLE_MODE, sample_mode & 0x3u);
    opengpu_hw_unified_kick(gpu, &fence->base);
    return 0;
}

static int opengpu_hw_unified_dma_submit_locked(
    struct opengpu_device *gpu, u32 opcode, u32 source, u32 destination,
    u32 bytes, u32 pattern, u32 height, u32 source_stride,
    u32 destination_stride, u64 expected_bytes,
    const struct opengpu_command_events *events,
    const struct opengpu_vm *vm,
    struct dma_fence **out_fence)
{
    return opengpu_hw_unified_submit_locked(
        gpu, NULL, events, opcode, source, destination, bytes, pattern, 0,
        bytes, height, source_stride, destination_stride, expected_bytes, vm, 0,
        out_fence);
}

/* Submit one DMA operation through the integrated common command payload.
 * DRM scheduler credit_limit=1 and submit_lock serialize this single MMIO
 * completion slot. Older tops retain the dedicated-engine fallback below. */
static int opengpu_hw_unified_dma_locked(struct opengpu_device *gpu,
                                         u32 opcode, u32 source,
                                         u32 destination, u32 bytes,
                                         u32 pattern, u32 height,
                                         u32 source_stride,
                                         u32 destination_stride,
                                         u64 expected_bytes)
{
    struct dma_fence *fence;
    int ret;

    ret = opengpu_hw_unified_dma_submit_locked(
        gpu, opcode, source, destination, bytes, pattern, height,
        source_stride, destination_stride, expected_bytes, NULL, NULL,
        &fence);
    if (ret)
        return ret;
    ret = opengpu_hw_fence_wait(gpu, fence, OPENGPU_DRAW_WAIT_MS + 100,
                                false);
    if (ret < 0)
        opengpu_hw_abort(gpu, ret);
    dma_fence_put(fence);
    return ret;
}

/** Hardware clear through the FillEngine: program base/count/pattern, kick
 * CLEAR_START and poll STATUS.CLEAR_BUSY.  The destination must be 64-byte
 * aligned with a byte count that is a multiple of 64 (the engine rejects
 * anything else; this helper returns -ERANGE for a misprogrammed range).
 * A clear is rejected while a draw is in flight. */
static int opengpu_hw_clear_locked(struct opengpu_device *gpu, u32 base,
                                   u32 bytes, u32 pattern)
{
    u64 end = (u64)base + bytes;
    unsigned long flags;
    unsigned long timeout;
    bool engine_busy;
    u32 status;

    if (!(gpu->hw.capabilities & GPU_CAP_CLEAR_ENGINE))
        return -EOPNOTSUPP;
    if ((base & 63u) || !bytes || (bytes & 63u))
        return -ERANGE;
    if (end > (1ull << 32))
        return -ERANGE;

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    engine_busy = gpu->hw.active_fence != NULL;
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    if (engine_busy)
        return -EBUSY;

    if (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS)
        return opengpu_hw_unified_dma_locked(
            gpu, GPU_UCMD_OP_FILL, 0, base, bytes, pattern, 0, 0, 0,
            bytes);

    opengpu_reg_write(gpu, GPU_REG_STATUS, GPU_STATUS_ERROR);
    opengpu_reg_write(gpu, GPU_REG_CLEAR_BASE, base);
    opengpu_reg_write(gpu, GPU_REG_CLEAR_BYTES, bytes);
    opengpu_reg_write(gpu, GPU_REG_CLEAR_PATTERN, pattern);
    opengpu_reg_write(gpu, GPU_REG_CLEAR_START, 1u);

    /* The emulated model only advances while the host touches device
     * registers, so poll through opengpu_hw_progress_tick() like the fence
     * waiters do. */
    timeout = jiffies + msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS);
    do {
        status = opengpu_reg_read(gpu, GPU_REG_STATUS);
        if (!(status & GPU_STATUS_CLEAR_BUSY))
            break;
        if (time_after(jiffies, timeout))
            return -ETIMEDOUT;
        opengpu_hw_progress_tick(gpu);
        cond_resched();
    } while (true);
    return status & GPU_STATUS_ERROR ? -EIO : 0;
}

static int opengpu_hw_blit_locked(struct opengpu_device *gpu, u32 source,
                                  u32 destination, u32 bytes)
{
    u64 source_end = (u64)source + bytes;
    u64 destination_end = (u64)destination + bytes;
    unsigned long flags;
    unsigned long timeout;
    bool engine_busy;
    u32 status;

    if (!(gpu->hw.capabilities & GPU_CAP_BLIT_ENGINE))
        return -EOPNOTSUPP;
    if ((source & 63u) || (destination & 63u) || !bytes || (bytes & 63u))
        return -ERANGE;
    if (source_end > (1ull << 32) || destination_end > (1ull << 32))
        return -ERANGE;
    if ((u64)source < destination_end &&
        (u64)destination < source_end)
        return -EINVAL;

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    engine_busy = gpu->hw.active_fence != NULL;
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    if (engine_busy)
        return -EBUSY;

    if (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS)
        return opengpu_hw_unified_dma_locked(
            gpu, GPU_UCMD_OP_COPY, source, destination, bytes, 0, 0, 0, 0,
            bytes);

    opengpu_reg_write(gpu, GPU_REG_STATUS, GPU_STATUS_ERROR);
    opengpu_reg_write(gpu, GPU_REG_BLIT_SRC_BASE, source);
    opengpu_reg_write(gpu, GPU_REG_BLIT_DST_BASE, destination);
    opengpu_reg_write(gpu, GPU_REG_BLIT_BYTES, bytes);
    opengpu_reg_write(gpu, GPU_REG_BLIT_START, 1u);

    timeout = jiffies + msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS);
    do {
        status = opengpu_reg_read(gpu, GPU_REG_STATUS);
        if (!(status & GPU_STATUS_BLIT_BUSY))
            break;
        if (time_after(jiffies, timeout))
            return -ETIMEDOUT;
        opengpu_hw_progress_tick(gpu);
        cond_resched();
    } while (true);
    return status & GPU_STATUS_ERROR ? -EIO : 0;
}

static int opengpu_hw_strided_blit_locked(struct opengpu_device *gpu,
                                          u32 source, u32 destination,
                                          u32 width, u32 height,
                                          u32 source_stride,
                                          u32 destination_stride)
{
    u64 source_span, destination_span, source_end, destination_end;
    unsigned long flags;
    unsigned long timeout;
    bool engine_busy;
    u32 status;

    if (!(gpu->hw.capabilities & GPU_CAP_STRIDED_ENGINE))
        return -EOPNOTSUPP;
    if ((source & 63u) || (destination & 63u) || !width || !height ||
        (width & 63u) || (source_stride & 63u) ||
        (destination_stride & 63u) || source_stride < width ||
        destination_stride < width)
        return -ERANGE;
    if (check_mul_overflow((u64)(height - 1), (u64)source_stride,
                           &source_span) ||
        check_mul_overflow((u64)(height - 1), (u64)destination_stride,
                           &destination_span) ||
        check_add_overflow((u64)source, source_span, &source_end) ||
        check_add_overflow(source_end, (u64)width, &source_end) ||
        check_add_overflow((u64)destination, destination_span,
                           &destination_end) ||
        check_add_overflow(destination_end, (u64)width, &destination_end) ||
        source_end > (1ull << 32) || destination_end > (1ull << 32))
        return -ERANGE;
    if ((u64)source < destination_end &&
        (u64)destination < source_end)
        return -EINVAL;

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    engine_busy = gpu->hw.active_fence != NULL;
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    if (engine_busy)
        return -EBUSY;

    if (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS)
        return opengpu_hw_unified_dma_locked(
            gpu, GPU_UCMD_OP_STRIDED_COPY, source, destination, width, 0,
            height, source_stride, destination_stride,
            (u64)width * height);

    opengpu_reg_write(gpu, GPU_REG_STATUS, GPU_STATUS_ERROR);
    opengpu_reg_write(gpu, GPU_REG_STRIDED_SRC_BASE, source);
    opengpu_reg_write(gpu, GPU_REG_STRIDED_DST_BASE, destination);
    opengpu_reg_write(gpu, GPU_REG_STRIDED_WIDTH, width);
    opengpu_reg_write(gpu, GPU_REG_STRIDED_HEIGHT, height);
    opengpu_reg_write(gpu, GPU_REG_STRIDED_SRC_STRIDE, source_stride);
    opengpu_reg_write(gpu, GPU_REG_STRIDED_DST_STRIDE,
                      destination_stride);
    opengpu_reg_write(gpu, GPU_REG_STRIDED_START, 1u);

    timeout = jiffies + msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS);
    do {
        status = opengpu_reg_read(gpu, GPU_REG_STATUS);
        if (!(status & GPU_STATUS_STRIDED_BUSY))
            break;
        if (time_after(jiffies, timeout))
            return -ETIMEDOUT;
        opengpu_hw_progress_tick(gpu);
        cond_resched();
    } while (true);
    return status & GPU_STATUS_ERROR ? -EIO : 0;
}

int opengpu_hw_clear(struct opengpu_device *gpu, u32 base, u32 bytes,
                     u32 pattern)
{
    int ret;

    ret = opengpu_hw_snapshot_submission_lock(gpu);
    if (ret)
        return ret;
    ret = opengpu_hw_clear_locked(gpu, base, bytes, pattern);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

int opengpu_hw_blit(struct opengpu_device *gpu, u32 source, u32 destination,
                    u32 bytes)
{
    int ret;

    ret = opengpu_hw_snapshot_submission_lock(gpu);
    if (ret)
        return ret;
    ret = opengpu_hw_blit_locked(gpu, source, destination, bytes);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

int opengpu_hw_strided_blit(struct opengpu_device *gpu, u32 source,
                            u32 destination, u32 width, u32 height,
                            u32 source_stride, u32 destination_stride)
{
    int ret;

    ret = opengpu_hw_snapshot_submission_lock(gpu);
    if (ret)
        return ret;
    ret = opengpu_hw_strided_blit_locked(gpu, source, destination, width,
                                         height, source_stride,
                                         destination_stride);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

static bool opengpu_hw_execution_busy(struct opengpu_device *gpu)
{
    unsigned long flags;
    bool busy;

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    /* A pending safe reset owns the command path, so it reports busy just
     * like an in-flight command. */
    busy = READ_ONCE(gpu->hw.reset_pending) || gpu->hw.aborting ||
        gpu->hw.unified_fence ||
        gpu->hw.active_fence != NULL;
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    return busy;
}

/* Keep submissions excluded until the caller has published its page tables
 * and flushed translations. Progress polling also services emulator fences. */
int opengpu_hw_wait_idle_locked(struct opengpu_device *gpu)
{
    unsigned long timeout = jiffies + msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS);

    lockdep_assert_held(&gpu->hw.submit_lock);
    while (opengpu_hw_execution_busy(gpu) ||
           ((gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS) &&
            (opengpu_reg_read(gpu, GPU_REG_UCMD_STATUS) &
             GPU_UCMD_STATUS_VM_BUSY))) {
        if (READ_ONCE(gpu->hw.wedged))
            return -EIO;
        if (time_after(jiffies, timeout))
            return -ETIMEDOUT;
        opengpu_hw_progress_tick(gpu);
        cond_resched();
    }
    return READ_ONCE(gpu->hw.wedged) ? -EIO : 0;
}

int opengpu_hw_wait_idle(struct opengpu_device *gpu)
{
    int ret;

    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_wait_idle_locked(gpu);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

int opengpu_hw_clear_async(struct opengpu_device *gpu, u32 base, u32 bytes,
                           u32 pattern,
                           const struct opengpu_command_events *events,
                           const struct opengpu_vm *vm,
                           struct dma_fence **out_fence)
{
    u64 end = (u64)base + bytes;
    int ret;

    if (!out_fence)
        return -EINVAL;
    *out_fence = NULL;
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS)) {
        if (events && events->flags)
            return -EOPNOTSUPP;
        return opengpu_hw_clear(gpu, base, bytes, pattern);
    }
    if (!(gpu->hw.capabilities & GPU_CAP_CLEAR_ENGINE))
        return -EOPNOTSUPP;
    if ((base & 63u) || !bytes || (bytes & 63u) || end > (1ull << 32))
        return -ERANGE;

    ret = opengpu_hw_snapshot_submission_lock(gpu);
    if (ret)
        return ret;
    ret = opengpu_hw_execution_busy(gpu) ? -EBUSY :
        opengpu_hw_unified_dma_submit_locked(
            gpu, GPU_UCMD_OP_FILL, 0, base, bytes, pattern, 0, 0, 0,
            bytes, events, vm, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

int opengpu_hw_blit_async(struct opengpu_device *gpu, u32 source,
                          u32 destination, u32 bytes,
                          const struct opengpu_command_events *events,
                          const struct opengpu_vm *vm,
                          struct dma_fence **out_fence)
{
    u64 source_end = (u64)source + bytes;
    u64 destination_end = (u64)destination + bytes;
    int ret;

    if (!out_fence)
        return -EINVAL;
    *out_fence = NULL;
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS)) {
        if (events && events->flags)
            return -EOPNOTSUPP;
        return opengpu_hw_blit(gpu, source, destination, bytes);
    }
    if (!(gpu->hw.capabilities & GPU_CAP_BLIT_ENGINE))
        return -EOPNOTSUPP;
    if ((source & 63u) || (destination & 63u) || !bytes ||
        (bytes & 63u) || source_end > (1ull << 32) ||
        destination_end > (1ull << 32))
        return -ERANGE;
    if ((u64)source < destination_end &&
        (u64)destination < source_end)
        return -EINVAL;

    ret = opengpu_hw_snapshot_submission_lock(gpu);
    if (ret)
        return ret;
    ret = opengpu_hw_execution_busy(gpu) ? -EBUSY :
        opengpu_hw_unified_dma_submit_locked(
            gpu, GPU_UCMD_OP_COPY, source, destination, bytes, 0, 0, 0, 0,
            bytes, events, vm, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

int opengpu_hw_strided_blit_async(struct opengpu_device *gpu, u32 source,
                                  u32 destination, u32 width, u32 height,
                                  u32 source_stride,
                                  u32 destination_stride,
                                  const struct opengpu_command_events *events,
                                  const struct opengpu_vm *vm,
                                  struct dma_fence **out_fence)
{
    u64 source_span, destination_span, source_end, destination_end;
    int ret;

    if (!out_fence)
        return -EINVAL;
    *out_fence = NULL;
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS)) {
        if (events && events->flags)
            return -EOPNOTSUPP;
        return opengpu_hw_strided_blit(gpu, source, destination, width,
                                       height, source_stride,
                                       destination_stride);
    }
    if (!(gpu->hw.capabilities & GPU_CAP_STRIDED_ENGINE))
        return -EOPNOTSUPP;
    if ((source & 63u) || (destination & 63u) || !width || !height ||
        (width & 63u) || (source_stride & 63u) ||
        (destination_stride & 63u) || source_stride < width ||
        destination_stride < width)
        return -ERANGE;
    if (check_mul_overflow((u64)(height - 1), (u64)source_stride,
                           &source_span) ||
        check_mul_overflow((u64)(height - 1), (u64)destination_stride,
                           &destination_span) ||
        check_add_overflow((u64)source, source_span, &source_end) ||
        check_add_overflow(source_end, (u64)width, &source_end) ||
        check_add_overflow((u64)destination, destination_span,
                           &destination_end) ||
        check_add_overflow(destination_end, (u64)width,
                           &destination_end) ||
        source_end > (1ull << 32) || destination_end > (1ull << 32))
        return -ERANGE;
    if ((u64)source < destination_end &&
        (u64)destination < source_end)
        return -EINVAL;

    ret = opengpu_hw_snapshot_submission_lock(gpu);
    if (ret)
        return ret;
    ret = opengpu_hw_execution_busy(gpu) ? -EBUSY :
        opengpu_hw_unified_dma_submit_locked(
            gpu, GPU_UCMD_OP_STRIDED_COPY, source, destination, width, 0,
            height, source_stride, destination_stride,
            (u64)width * height, events, vm, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

/* Host invalidation uses physical 64-byte lines and a 32-bit extent. */
static int opengpu_hw_invalidate_validate(struct opengpu_device *gpu,
                                          u32 address, u32 bytes)
{
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return -EOPNOTSUPP;
    if ((address & 63u) || !bytes || (bytes & 63u) ||
        (u64)address + bytes > (1ull << 32))
        return -ERANGE;
    return 0;
}

/* Scheduler-facing submissions remain nonblocking. Host invalidation addresses
 * are physical: the invalidate port does not consult VECTOR_SATP. */
int opengpu_hw_invalidate_async(struct opengpu_device *gpu, u32 address,
                                u32 bytes,
                                const struct opengpu_command_events *events,
                                const struct opengpu_vm *vm,
                                struct dma_fence **out_fence)
{
    int ret;

    if (!out_fence)
        return -EINVAL;
    *out_fence = NULL;
    ret = opengpu_hw_invalidate_validate(gpu, address, bytes);
    if (ret)
        return ret;
    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_execution_busy(gpu) ? -EBUSY :
        opengpu_hw_unified_submit_locked(
            gpu, NULL, events, GPU_UCMD_OP_INVALIDATE, address, 0, bytes, 0,
            0, bytes, 0, 0, 0, bytes, vm, 0, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

/* CPU-access and binding uploads must not fail just because an unrelated job
 * is active. Exclude new submissions through invalidation completion, or a
 * scheduler job could observe the invalidation itself as busy and fail. */
int opengpu_hw_invalidate_sync(struct opengpu_device *gpu, u32 address,
                               u32 bytes, bool interruptible)
{
    struct dma_fence *fence = NULL;
    int ret;

    ret = opengpu_hw_invalidate_validate(gpu, address, bytes);
    if (ret)
        return ret;
    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_wait_idle_locked(gpu);
    if (!ret)
        ret = opengpu_hw_unified_submit_locked(
            gpu, NULL, NULL, GPU_UCMD_OP_INVALIDATE, address, 0, bytes, 0,
            0, bytes, 0, 0, 0, bytes, NULL, 0, &fence);
    if (!ret) {
        ret = opengpu_hw_fence_wait(gpu, fence, OPENGPU_DRAW_WAIT_MS + 100,
                                    interruptible);
        /* On interruption or timeout, attempt to drain before allowing a
         * scheduler submission to observe the owned hardware command. */
        if (ret)
            opengpu_hw_wait_idle_locked(gpu);
        dma_fence_put(fence);
    }
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

int opengpu_hw_resolve_async(struct opengpu_device *gpu, u32 source,
                             u32 destination, u32 width, u32 height,
                             u32 source_stride, u32 destination_stride,
                             u32 sample_mode,
                             const struct opengpu_command_events *events,
                             const struct opengpu_vm *vm,
                             u32 invalidate_phys,
                             struct dma_fence **out_fence)
{
    struct opengpu_resolve_desc desc;
    struct opengpu_resolve_layout layout;
    u32 max_sample_mode;
    u32 pattern;
    int ret;

    if (!out_fence)
        return -EINVAL;
    *out_fence = NULL;
    /* Resolve averages the samples of an MSAA target through the unified
     * command path; both capabilities must be present.  The target may be
     * fixed-function or programmable (GPU_CAP_FRAGMENT_CORE), since the
     * unified path reads the resolved sample layout either way. */
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS) ||
        !(gpu->hw.capabilities & GPU_CAP_MSAA))
        return -EOPNOTSUPP;

    max_sample_mode = (gpu->hw.capabilities & GPU_CAP_MSAA_MAX_MODE_MASK) >>
                      GPU_CAP_MSAA_MAX_MODE_SHIFT;

    desc.source_offset = source;
    desc.destination_offset = destination;
    desc.width = width;
    desc.height = height;
    desc.source_stride = source_stride;
    desc.destination_stride = destination_stride;
    desc.sample_mode = sample_mode;

    /* The ioctl already validated the ranges against the GEM objects; here the
     * absolute DMA addresses are re-checked for arithmetic, the 32-bit address
     * window and overlap before programming the registers. */
    ret = opengpu_resolve_validate(&desc, 1ull << 32, 1ull << 32,
                                   max_sample_mode, &layout);
    if (ret)
        return ret;

    /* PATTERN carries the physical invalidate base when the engine source is a
     * private VA; zero keeps Bare/identity behaviour (invalidate == source). */
    pattern = (vm && invalidate_phys && invalidate_phys != source) ?
        invalidate_phys : 0;

    ret = opengpu_hw_snapshot_submission_lock(gpu);
    if (ret)
        return ret;
    ret = opengpu_hw_execution_busy(gpu) ? -EBUSY :
        opengpu_hw_unified_submit_locked(
            gpu, NULL, events, GPU_UCMD_OP_RESOLVE, source, destination,
            width, pattern, sample_mode, width, height, source_stride,
            destination_stride, (u64)width * height * 4ull, vm, 0, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

/** Point both CU `satp` registers at `root_table` under `asid`, without a TLB
  * flush.  Used for a coarse VM switch: the caller quiesces execution first
  * and shoots down the ASID if it was recycled; other address spaces' TLB
  * entries stay resident. */
int opengpu_hw_set_satp(struct opengpu_device *gpu, dma_addr_t root_table,
                        u32 asid)
{
    u32 satp;

    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return -EOPNOTSUPP;
    if (upper_32_bits(root_table) || (root_table & 0xfff))
        return -EINVAL;
    if (asid > GPU_SATP_ASID_MASK)
        return -EINVAL;
    /* satp: bit 31 enables translation, ASID in [30:22], root PPN in [19:0]. */
    satp = GPU_SATP_ENABLE | (asid << GPU_SATP_ASID_SHIFT) |
        (lower_32_bits(root_table) >> 12);
    opengpu_reg_write(gpu, GPU_REG_UCMD_VECTOR_SATP, satp);
    opengpu_reg_write(gpu, GPU_REG_UCMD_INSTRUCTION_SATP, satp);
    return 0;
}

/** Switch the active address space outside a submission.  Quiesces execution,
 * then programs `satp`; `vm == NULL` restores the global ASID-0 identity map.
 * A per-submission switch happens inside the submit lock instead (see
 * opengpu_hw_activate_vm_locked). */
int opengpu_hw_activate_vm(struct opengpu_device *gpu,
                           const struct opengpu_vm *vm)
{
    int ret;

    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_wait_idle_locked(gpu);
    if (!ret)
        ret = opengpu_hw_activate_vm_locked(gpu, vm);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

/** Enable Sv32 translation with a driver-built identity map.  `root_table` is
 * the physical address of a 4 KiB root page table whose 1024 entries are
 * 4 MiB identity superpages; a full TLB flush is issued to commit the change.
 * The map is deliberately identity so existing physical-address bindings keep
 * working; a later mapping layer can override individual pages (for example to
 * mark a CPU-written buffer uncached). */
int opengpu_hw_enable_mmu(struct opengpu_device *gpu, dma_addr_t root_table)
{
    int ret;

    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_wait_idle_locked(gpu);
    if (!ret)
        ret = opengpu_hw_set_satp(gpu, root_table, 0);
    if (!ret)
        opengpu_reg_write(gpu, GPU_REG_UCMD_TLB_FLUSH, opengpu_tlb_flush_full());
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

/** Invalidate every entry of both translation caches. */
int opengpu_hw_flush_tlbs(struct opengpu_device *gpu)
{
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return -EOPNOTSUPP;
    opengpu_reg_write(gpu, GPU_REG_UCMD_TLB_FLUSH, opengpu_tlb_flush_full());
    return 0;
}

/* Caller holds submit_lock and the MMU mutation lock and has proved idle.
 * Stop both page walkers before a full shootdown permits freeing ASID-0 PTs. */
int opengpu_hw_disable_mmu_locked(struct opengpu_device *gpu)
{
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return -EOPNOTSUPP;
    opengpu_reg_write(gpu, GPU_REG_UCMD_VECTOR_SATP, 0);
    opengpu_reg_write(gpu, GPU_REG_UCMD_INSTRUCTION_SATP, 0);
    return opengpu_hw_flush_tlbs(gpu);
}

/** Invalidate only the TLB entries tagged with `asid`.  Global mappings and
  * entries of other address spaces stay resident, so switching between
  * address spaces does not pay a full-flush refill.  The three graphics word
  * clients are ASID-tagged too and honour the same scope. */
int opengpu_hw_flush_tlb_asid(struct opengpu_device *gpu, u32 asid)
{
    opengpu_tlb_u32 word;
    int ret;

    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return -EOPNOTSUPP;
    ret = opengpu_tlb_flush_asid(asid, &word);
    if (ret)
        return ret;
    opengpu_reg_write(gpu, GPU_REG_UCMD_TLB_FLUSH, word);
    return 0;
}

/** Invalidate only the TLB entries for virtual page number `vpn`.  Useful for
  * a single mapping change; other entries, including global ones, survive. */
int opengpu_hw_flush_tlb_vpn(struct opengpu_device *gpu, u32 vpn)
{
    opengpu_tlb_u32 word;
    int ret;

    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return -EOPNOTSUPP;
    ret = opengpu_tlb_flush_vpn(vpn, &word);
    if (ret)
        return ret;
    opengpu_reg_write(gpu, GPU_REG_UCMD_TLB_FLUSH, word);
    return 0;
}

/* `shader` is the job's CPU-written code snapshot. Instruction fetch is always
 * L2-cached, so its lines are dropped under the same submit_lock hold as the
 * kernel doorbell, like draw snapshots in opengpu_hw_render_async. */
int opengpu_hw_compute_async(struct opengpu_device *gpu,
                             const struct opengpu_kernel_launch *launch,
                             const struct opengpu_buffer *shader,
                             const struct opengpu_buffer *texture_records,
                             const struct opengpu_buffer *storage_records,
                             const struct opengpu_buffer *image_records,
                             const struct opengpu_buffer *counter_records,
                             const struct opengpu_buffer *uniform_snapshot,
                             const struct opengpu_buffer *indirect_snapshot,
                             const struct opengpu_command_events *events,
                             const struct opengpu_vm *vm,
                             struct dma_fence **out_fence)
{
    int ret;

    if (!launch || !out_fence)
        return -EINVAL;
    *out_fence = NULL;
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return -EOPNOTSUPP;
    if (!launch->kernel_pc || upper_32_bits(launch->kernel_pc) ||
        (launch->kernel_pc & 3) || !launch->kernarg ||
        upper_32_bits(launch->kernarg) || (launch->kernarg & 3) ||
        (!launch->indirect_enabled &&
         (!launch->grid[0] || !launch->grid[1] || !launch->grid[2])) ||
        !opengpu_compute_local_size_valid(&gpu->hw.compute_local_limits,
                                          launch->local, NULL))
        return -EINVAL;

    if (launch->indirect_enabled) {
        if (!gpu->hw.compute_indirect_transport || !vm || !vm->enabled ||
            !indirect_snapshot || !indirect_snapshot->cpu || !indirect_snapshot->dma ||
            (indirect_snapshot->dma & 4095) ||
            (indirect_snapshot->size != 4096 && indirect_snapshot->size != 8192) ||
            !launch->indirect_base || (launch->indirect_base & 4095) ||
            launch->indirect_bytes != indirect_snapshot->size ||
            (launch->indirect_offset & 3) || launch->indirect_offset >= 4096 ||
            launch->indirect_offset + OPENGPU_COMPUTE_INDIRECT_COUNT_BYTES > launch->indirect_bytes)
            return -EINVAL;
    } else if (launch->indirect_base || launch->indirect_bytes || launch->indirect_offset)
        return -EINVAL;

    if (launch->storage_enabled && (!storage_records || !storage_records->cpu ||
        !storage_records->dma || storage_records->size < 64 ||
        storage_records->size > 256 || (storage_records->size & 63)))
        return -EINVAL;
    if (launch->texture_enabled && (!texture_records || !texture_records->cpu ||
        !texture_records->dma || texture_records->size < 128))
        return -EINVAL;
    if (launch->image_enabled && (!image_records || !image_records->cpu ||
        !image_records->dma || image_records->size < 256 ||
        image_records->size > 1024 || (image_records->size & 255)))
        return -EINVAL;
    if (launch->counter_enabled && (!counter_records || !counter_records->cpu ||
        !counter_records->dma || counter_records->size != 64))
        return -EINVAL;
    if (launch->uniform_enabled && (!vm || !vm->enabled || !uniform_snapshot ||
        !uniform_snapshot->cpu || !uniform_snapshot->dma ||
        (uniform_snapshot->dma & 255) || uniform_snapshot->size < 4096 ||
        uniform_snapshot->size > OPENGPU_COMPUTE_UNIFORM_SNAPSHOT_MAX_BYTES ||
        (uniform_snapshot->size & 4095)))
        return -EINVAL;
    ret = opengpu_hw_snapshot_submission_lock(gpu);
    if (ret)
        return ret;
    dma_wmb();
    ret = launch->indirect_enabled ? opengpu_mmu_verify_snapshot_locked(gpu, vm,
        launch->indirect_base, indirect_snapshot->dma, indirect_snapshot->size) : 0;
    if (!ret)
        ret = opengpu_hw_invalidate_buffer_locked(gpu, shader);
    if (!ret && launch->texture_enabled)
        ret = opengpu_hw_invalidate_buffer_locked(gpu, texture_records);
    if (!ret && launch->storage_enabled)
        ret = opengpu_hw_invalidate_buffer_locked(gpu, storage_records);
    if (!ret && launch->image_enabled)
        ret = opengpu_hw_invalidate_buffer_locked(gpu, image_records);
    if (!ret && launch->counter_enabled)
        ret = opengpu_hw_invalidate_buffer_locked(gpu, counter_records);
    if (!ret && launch->uniform_enabled)
        ret = opengpu_hw_invalidate_buffer_locked(gpu, uniform_snapshot);
    if (!ret && launch->indirect_enabled)
        ret = opengpu_hw_invalidate_buffer_locked(gpu, indirect_snapshot);
    if (!ret)
        ret = opengpu_hw_execution_busy(gpu) ? -EBUSY :
            opengpu_hw_unified_submit_locked(
                gpu, launch, events,
                launch->indirect_enabled ? GPU_UCMD_OP_KERNEL_INDIRECT : GPU_UCMD_OP_KERNEL,
                launch->indirect_base, 0, launch->indirect_bytes, 0, 0, launch->indirect_offset, 0,
                0, 0, 0, vm, 0, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

/* Drop shared-L2 lines covering a CPU-written coherent DMA buffer.  dma_alloc
 * reuses physical pages whose prior GPU-L2 tags can otherwise shadow the
 * memcpy the ioctl just performed.  Called with submit_lock held; waits for
 * the invalidate to retire before the caller doorbells the draw. */
int opengpu_hw_invalidate_buffer_locked(
    struct opengpu_device *gpu, const struct opengpu_buffer *buffer)
{
    struct dma_fence *fence;
    u64 start, end;
    u32 bytes;
    int ret;

    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS) || !buffer ||
        !buffer->cpu || !buffer->size)
        return 0;
    start = (u64)buffer->dma & ~(u64)63;
    if (check_add_overflow((u64)buffer->dma, (u64)buffer->size, &end))
        return -ERANGE;
    end = (end + 63ull) & ~(u64)63;
    if (start > U32_MAX || end > (1ull << 32) || end - start > U32_MAX)
        return -ERANGE;
    bytes = (u32)(end - start);
    if (!bytes)
        return 0;
    if (opengpu_hw_execution_busy(gpu))
        return -EBUSY;

    ret = opengpu_hw_unified_submit_locked(
        gpu, NULL, NULL, GPU_UCMD_OP_INVALIDATE, (u32)start, 0, bytes, 0, 0,
        bytes, 0, 0, 0, bytes, NULL, 0, &fence);
    if (ret)
        return ret;
    ret = opengpu_hw_fence_wait(gpu, fence, OPENGPU_DRAW_WAIT_MS + 100,
                                false);
    if (ret < 0)
        opengpu_hw_abort(gpu, ret);
    dma_fence_put(fence);
    return ret;
}

static int opengpu_hw_invalidate_snapshots_locked(
    struct opengpu_device *gpu,
    const struct opengpu_buffer *const *snapshots, unsigned int count)
{
    unsigned int i;
    int ret;

    for (i = 0; i < count; i++) {
        ret = opengpu_hw_invalidate_buffer_locked(gpu, snapshots[i]);
        if (ret)
            return ret;
    }
    return 0;
}

/* Internal GNQ transport for a caller that already owns and validated the
 * complete job and all referenced resources. The caller retains immutable
 * snapshots, backing allocations and VM mappings until the fence retires. */
int opengpu_hw_tessellation_async(struct opengpu_device *gpu,
                                  const struct opengpu_tessellation_descriptors *descriptors,
                                  u32 descriptor_va, u64 descriptor_limit,
                                  const struct opengpu_buffer *const *snapshots,
                                  unsigned int snapshot_count,
                                  const struct opengpu_tessellation_scope *scope,
                                  const struct opengpu_vm *vm,
                                  struct dma_fence **out_fence)
{
    struct opengpu_tessellation_job_view job;
    struct opengpu_command_events events = { 0 };
    u32 program = 0, cookie = 0;
    unsigned int i;
    int ret;

    if (!out_fence)
        return -EINVAL;
    *out_fence = NULL;
    if (!gpu->hw.tessellation_transport)
        return -EOPNOTSUPP;
    if (!descriptors || !descriptors->job || !descriptors->frame ||
        (snapshot_count && !snapshots) ||
        (!descriptors->fragment_code && descriptors->fragment_code_va))
        return -EINVAL;
    for (i = 0; i < snapshot_count; ++i)
        if (!snapshots[i] || !snapshots[i]->cpu || !snapshots[i]->size)
            return -EINVAL;
    if (scope) {
        if (scope->flags & ~(GPU_UCMD_FLAG_RASTER_DISCARD |
                             GPU_UCMD_FLAG_FEEDBACK_CAPTURE))
            return -EINVAL;
        if (!gpu->hw.tessellation_feedback_transport &&
            (scope->flags || scope->program || scope->cookie))
            return -EOPNOTSUPP;
        events.flags = scope->flags;
        program = scope->program;
        cookie = scope->cookie;
    }

    ret = opengpu_hw_snapshot_submission_lock(gpu);
    if (ret)
        return ret;
    ret = opengpu_hw_execution_busy(gpu) ? -EBUSY : opengpu_hw_wait_idle_locked(gpu);
    if (ret)
        goto out_unlock;
    if (!opengpu_tessellation_snapshots_precheck(
            descriptors->job->cpu, descriptors->job->size,
            descriptor_va, descriptor_limit, descriptors->frame->cpu,
            descriptors->frame->size,
            descriptors->draws ? descriptors->draws->cpu : NULL,
            descriptors->draws ? descriptors->draws->size : 0,
            gpu->hw.tessellation_engines, &job)) {
        ret = -EINVAL;
        goto out_unlock;
    }
    ret = opengpu_mmu_verify_snapshot_locked(gpu, vm, descriptor_va,
        descriptors->job->dma, OPENGPU_NATIVE_TESSELLATION_JOB_BYTES);
    if (!ret)
        ret = opengpu_mmu_verify_snapshot_locked(gpu, vm, job.frame_base,
            descriptors->frame->dma, OPENGPU_NATIVE_TESSELLATION_JOB_FRAME_BYTES);
    if (!ret && job.draw_count) {
        u64 span = (u64)(job.draw_count - 1u) * job.draw_stride +
                   OPENGPU_NATIVE_TESSELLATION_SEQUENCE_BYTES;
        ret = opengpu_mmu_verify_snapshot_locked(gpu, vm, job.draw_base,
            descriptors->draws->dma, (size_t)span);
    }
    if (!ret && descriptors->states) {
        const unsigned char *frame = descriptors->frame->cpu;
        u64 base = opengpu_tessellation_job_le32(frame + 4u * OPENGPU_TESS_FRAME_STATE_BASE);
        u64 limit = opengpu_tessellation_job_le32(frame + 4u * OPENGPU_TESS_FRAME_STATE_LIMIT) |
            ((u64)opengpu_tessellation_job_le32(frame + 4u * (OPENGPU_TESS_FRAME_STATE_LIMIT + 1u)) << 32);

        if (!descriptors->states->cpu || !descriptors->states->size ||
            limit > OPENGPU_TESSELLATION_ADDRESS_END || limit < base ||
            descriptors->states->size != limit - base)
            ret = -EINVAL;
        else
            ret = opengpu_mmu_verify_snapshot_locked(gpu, vm, base,
                descriptors->states->dma, descriptors->states->size);
    }
    if (!ret && descriptors->fragment_code) {
        const struct opengpu_buffer *code = descriptors->fragment_code;
        if (!code->cpu || !code->size)
            ret = -EINVAL;
        else
            ret = opengpu_mmu_verify_code_snapshot_locked(gpu, vm,
                descriptors->fragment_code_va, code->dma, code->size);
    }
    if (ret)
        goto out_unlock;
    dma_wmb();
    ret = opengpu_hw_invalidate_buffer_locked(gpu, descriptors->job);
    if (!ret)
        ret = opengpu_hw_invalidate_buffer_locked(gpu, descriptors->frame);
    if (!ret && job.draw_count)
        ret = opengpu_hw_invalidate_buffer_locked(gpu, descriptors->draws);
    if (!ret && descriptors->states)
        ret = opengpu_hw_invalidate_buffer_locked(gpu, descriptors->states);
    if (!ret && descriptors->fragment_code)
        ret = opengpu_hw_invalidate_buffer_locked(gpu, descriptors->fragment_code);
    if (!ret)
        ret = opengpu_hw_invalidate_snapshots_locked(gpu, snapshots, snapshot_count);
    if (!ret)
        ret = opengpu_hw_execution_busy(gpu) ? -EBUSY :
            opengpu_hw_unified_submit_locked(
                gpu, NULL, &events, GPU_UCMD_OP_TESSELLATION,
                descriptor_va, lower_32_bits(descriptor_limit),
                OPENGPU_NATIVE_TESSELLATION_JOB_BYTES,
                (u32)(descriptor_limit >> 32), 0, job.context, program,
                cookie, 0, 0, vm, 0, out_fence);
out_unlock:
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

/* Publish one draw as a unified render command.  The 16-word render descriptor
 * is filled into driver-owned memory and submitted by virtual address, so the
 * device fetches the descriptor and the command buffer through the context VM.
 * Snapshots are invalidated and private depth is cleared under the same
 * submit_lock hold, exactly as the legacy/job-ring draw path does. */
int opengpu_hw_render_async(struct opengpu_device *gpu,
                            const struct opengpu_job *job,
                            void *descriptor_cpu,
                            u32 descriptor_va, u32 descriptor_bytes,
                            const struct opengpu_buffer *const *snapshots,
                            unsigned int snapshot_count,
                            bool clear_depth, u32 clear_base, u32 clear_bytes,
                            u32 clear_pattern,
                            const struct opengpu_command_events *events,
                            const struct opengpu_vm *vm,
                            struct dma_fence **out_fence)
{
    int ret;

    ret = opengpu_hw_validate_job(job, out_fence);
    if (ret)
        return ret;
    if (!descriptor_cpu || descriptor_bytes < sizeof(struct gpu_job_record))
        return -EINVAL;
    memset(descriptor_cpu, 0, descriptor_bytes);
    opengpu_job_fill((struct gpu_job_record *)descriptor_cpu, 0, job);

    ret = opengpu_hw_snapshot_submission_lock(gpu);
    if (ret)
        return ret;
    ret = opengpu_hw_invalidate_snapshots_locked(gpu, snapshots,
                                                 snapshot_count);
    if (!ret && clear_depth)
        ret = opengpu_hw_clear_locked(gpu, clear_base, clear_bytes,
                                      clear_pattern);
    if (!ret)
        ret = opengpu_hw_unified_submit_locked(
            gpu, NULL, events, GPU_UCMD_OP_RENDER, descriptor_va, 0,
            descriptor_bytes, 0, 0, descriptor_bytes, 0, 0, 0, descriptor_bytes, vm,
            job->completion_delay_ms, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

int opengpu_hw_display_commit(struct opengpu_device *gpu,
                              const struct opengpu_scanout *scanout)
{
    u32 control = 0;
    int ret;

    if (scanout->enable &&
        (!scanout->base || upper_32_bits(scanout->base) ||
         !scanout->stride || !scanout->width || !scanout->height))
        return -EINVAL;

    /* Weston can submit a render and immediately page-flip its target BO.
     * Quiesce the GPU before publishing that BO as scanout, otherwise ARTI
     * may copy it while the fragment work is still in flight and display a
     * stale/black frame. */
    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_wait_idle_locked(gpu);
    if (ret) {
        mutex_unlock(&gpu->hw.submit_lock);
        return ret;
    }

    /* A page flip changes only BASE.  Avoid reprogramming the mode and
     * toggling CONTROL while an unchanged scanout is already active. */
    if (scanout->enable && gpu->display.enabled &&
        gpu->display.stride == scanout->stride &&
        gpu->display.width == scanout->width &&
        gpu->display.height == scanout->height &&
        gpu->display.format == scanout->format) {
        /* Rewriting BASE also invalidates ARTI's one-shot scanout view when
         * a client updates the same framebuffer in place. */
        opengpu_reg_write(gpu, GPU_REG_SCANOUT_BASE,
                          lower_32_bits(scanout->base));
        gpu->display.scanout = scanout->base;
        mutex_unlock(&gpu->hw.submit_lock);
        return 0;
    }

    /* Disable first and publish BASE last. ARTI guest-memory display watches
     * CONTROL (enable), WIDTH/HEIGHT, STRIDE and BASE; the BASE write is the
     * visibility latch, and CONTROL bit0 gates presentation. */
    opengpu_reg_write(gpu, GPU_REG_SCANOUT_CONTROL, 0);
    if (gpu->hw.capabilities & GPU_CAP_HW_VBLANK) {
        if (!gpu->display.period)
            gpu->display.period = DIV_ROUND_UP(100000000u, 30u);
        opengpu_reg_write(gpu, GPU_REG_SCANOUT_PERIOD, gpu->display.period);
    }
    opengpu_reg_write(gpu, GPU_REG_SCANOUT_STRIDE, scanout->stride);
    opengpu_reg_write(gpu, GPU_REG_SCANOUT_WIDTH, scanout->width);
    opengpu_reg_write(gpu, GPU_REG_SCANOUT_HEIGHT, scanout->height);
    opengpu_reg_write(gpu, GPU_REG_SCANOUT_FORMAT, scanout->format);
    opengpu_reg_write(gpu, GPU_REG_SCANOUT_BASE,
                      scanout->enable ? lower_32_bits(scanout->base) : 0);
    if (scanout->enable) {
        control = GPU_SCANOUT_ENABLE;
        if ((gpu->hw.capabilities & GPU_CAP_HW_VBLANK) &&
            gpu->display.vblank_irq)
            control |= GPU_SCANOUT_VBLANK_EN;
        opengpu_reg_write(gpu, GPU_REG_SCANOUT_CONTROL, control);
    }
    if (!!(opengpu_reg_read(gpu, GPU_REG_SCANOUT_STATUS) &
           GPU_SCANOUT_ACTIVE) != scanout->enable) {
        gpu->display.enabled = false;
        mutex_unlock(&gpu->hw.submit_lock);
        return -EIO;
    }
    gpu->display.scanout = scanout->base;
    gpu->display.stride = scanout->stride;
    gpu->display.width = scanout->width;
    gpu->display.height = scanout->height;
    gpu->display.format = scanout->format;
    gpu->display.enabled = scanout->enable;
    /* Submit validation follows the active mode. Disable leaves the last
     * mode in place, matching the hardware viewport latch. */
    if (scanout->enable && scanout->width && scanout->height) {
        gpu->width = min_t(u32, scanout->width, gpu->max_width);
        gpu->height = min_t(u32, scanout->height, gpu->max_height);
    }
    mutex_unlock(&gpu->hw.submit_lock);
    return 0;
}

int opengpu_hw_vblank_enable(struct opengpu_device *gpu, bool enable)
{
    u32 control;

    if (!(gpu->hw.capabilities & GPU_CAP_HW_VBLANK))
        return -EOPNOTSUPP;
    gpu->display.vblank_irq = enable;
    if (!gpu->display.period)
        gpu->display.period = DIV_ROUND_UP(100000000u, 30u);
    opengpu_reg_write(gpu, GPU_REG_SCANOUT_PERIOD,
                      enable ? gpu->display.period : 0);
    control = opengpu_reg_read(gpu, GPU_REG_SCANOUT_CONTROL);
    if (enable)
        control |= GPU_SCANOUT_VBLANK_EN;
    else
        control &= ~GPU_SCANOUT_VBLANK_EN;
    opengpu_reg_write(gpu, GPU_REG_SCANOUT_CONTROL, control);
    return 0;
}
