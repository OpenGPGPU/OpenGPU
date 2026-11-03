/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <stdbool.h>
#include <stddef.h>
#include <errno.h>
#include <stdio.h>
#define OPENGPU_MAX_TEMP_MAPPINGS 2
#define WRITE_ONCE(x,v) ((x)=(v))
typedef unsigned long dma_addr_t;
struct opengpu_vm { int unused; };
struct opengpu_device { struct { bool wedged; } hw; };
struct opengpu_render_context { struct opengpu_vm vm; };
struct mapping { dma_addr_t va; size_t size; };
struct opengpu_sched_job { struct opengpu_device *gpu; struct opengpu_render_context *context;
    struct opengpu_vm *vm; struct mapping temporary_mappings[2]; unsigned temporary_mapping_count; };
static int unmap_error, unmaps;
static int opengpu_mmu_vm_unmap(struct opengpu_device *gpu, struct opengpu_vm *vm,
                                dma_addr_t va, size_t size)
{ (void)gpu; (void)vm; (void)va; (void)size; unmaps++; return unmap_error; }
#include "job_track_mapping_under_test.inc"
int main(void)
{
    struct opengpu_device gpu={0}; struct opengpu_render_context context={0};
    struct opengpu_sched_job job={.gpu=&gpu,.context=&context,.vm=&context.vm};
    assert(!opengpu_job_track_mapping(&job,4096,4096));
    assert(!opengpu_job_track_mapping(&job,8192,4096));
    assert(job.temporary_mapping_count==2 && job.temporary_mappings[1].va==8192);
    assert(opengpu_job_track_mapping(&job,12288,4096)==-ENOSPC);
    assert(unmaps==1 && !gpu.hw.wedged);
    unmap_error=-EIO;
    assert(opengpu_job_track_mapping(&job,12288,4096)==-ENOSPC);
    assert(unmaps==2 && gpu.hw.wedged && job.temporary_mapping_count==2);
    puts("temporary mapping: cleanup failure prevents reuse PASS");
    return 0;
}
