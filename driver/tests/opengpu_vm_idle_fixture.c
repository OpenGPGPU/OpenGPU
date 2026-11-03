/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <errno.h>
#include <stdio.h>
#define GPU_CAP_UNIFIED_COMMANDS 1u
#define GPU_REG_UCMD_STATUS 1u
#define GPU_UCMD_STATUS_VM_BUSY (1u << 5)
#define OPENGPU_DRAW_WAIT_MS 10u
#define READ_ONCE(x) (x)
#define lockdep_assert_held(x) ((void)(x))
#define msecs_to_jiffies(x) (x)
#define time_after(a,b) ((long)((a)-(b)) > 0)
static unsigned long jiffies;
struct opengpu_device { struct { int submit_lock; unsigned capabilities; bool wedged; } hw;
    bool software_busy, hardware_busy; int ticks, clear_after, reads; };
static bool opengpu_hw_execution_busy(struct opengpu_device *g) { return g->software_busy; }
static unsigned opengpu_reg_read(struct opengpu_device *g, unsigned reg)
{ assert(reg==GPU_REG_UCMD_STATUS); g->reads++; return g->hardware_busy ? GPU_UCMD_STATUS_VM_BUSY : 0; }
static void opengpu_hw_progress_tick(struct opengpu_device *g)
{ g->ticks++; jiffies++; if (g->ticks==g->clear_after) { g->software_busy=false; g->hardware_busy=false; } }
static void cond_resched(void) { }
#include "vm_idle_under_test.inc"
int main(void)
{
    struct opengpu_device gpu={0};
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS; gpu.hardware_busy=true; gpu.clear_after=3;
    assert(!opengpu_hw_wait_idle_locked(&gpu));
    assert(gpu.ticks==3 && gpu.reads==4);
    gpu=(struct opengpu_device){0}; jiffies=0; gpu.hardware_busy=true;
    assert(!opengpu_hw_wait_idle_locked(&gpu)); /* legacy/Bare never reads status */
    assert(!gpu.reads && !gpu.ticks);
    gpu=(struct opengpu_device){0}; jiffies=0; gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
    gpu.software_busy=true; gpu.clear_after=2;
    assert(!opengpu_hw_wait_idle_locked(&gpu) && gpu.ticks==2);
    gpu=(struct opengpu_device){0}; jiffies=0; gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
    gpu.hardware_busy=true;
    assert(opengpu_hw_wait_idle_locked(&gpu)==-ETIMEDOUT && gpu.ticks==11);
    gpu=(struct opengpu_device){0}; jiffies=0; gpu.hw.wedged=true;
    assert(opengpu_hw_wait_idle_locked(&gpu)==-EIO);
    puts("VM idle wait: software and hardware owners, timeout and legacy PASS");
    return 0;
}
