/* The actual compute job branch, using mock VM mapping and hardware submit. */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include "opengpu_kernarg_va.h"
typedef uint64_t dma_addr_t;
#define OPENGPU_SCHED_COMPUTE 1
#define OPENGPU_MMU_PAGE_SIZE 4096
#define OPENGPU_MMU_POLICY_UNCACHED 2
#define upper_32_bits(v) ((uint64_t)(v) >> 32)
#define ERR_PTR(e) ((struct dma_fence *)(intptr_t)(e))
struct dma_fence { int dummy; };
struct opengpu_vm { int dummy; };
struct opengpu_render_context { struct opengpu_vm vm; };
struct opengpu_buffer { void *cpu; dma_addr_t dma; size_t size; };
struct opengpu_kernel_launch { dma_addr_t kernel_pc; bool texture_enabled; struct { uint32_t root; } texture; };
struct opengpu_command_events { int dummy; };
struct opengpu_device { int dummy; };
struct opengpu_sched_job {
    int type; struct opengpu_device *gpu; struct opengpu_vm *vm;
    struct opengpu_render_context *context; struct opengpu_buffer shader, texture_records;
    struct opengpu_kernel_launch kernel; struct opengpu_command_events events;
};
static int mappings, tracks, submits, map_error, track_error;
static dma_addr_t mapped_va, mapped_pa; static size_t mapped_size;
static struct dma_fence fence;
static int opengpu_binding_map_code(struct opengpu_device *g, struct opengpu_render_context *c,
    dma_addr_t dma, size_t size, unsigned slot, dma_addr_t *va)
{ (void)g; (void)c; (void)size; (void)slot; *va = dma + 0x10000; return 0; }
static int opengpu_job_track_mapping(struct opengpu_sched_job *j, dma_addr_t va, size_t size)
{ (void)j; (void)va; (void)size; tracks++; return track_error; }
static int opengpu_mmu_vm_map(struct opengpu_device *g, struct opengpu_vm *v,
    dma_addr_t va, dma_addr_t pa, size_t size, unsigned policy)
{ (void)g; (void)v; assert(policy == OPENGPU_MMU_POLICY_UNCACHED); mappings++; mapped_va=va; mapped_pa=pa; mapped_size=size; return map_error; }
static int opengpu_hw_compute_async(struct opengpu_device *g, const struct opengpu_kernel_launch *l,
    const struct opengpu_buffer *s, const struct opengpu_buffer *t,
    const struct opengpu_command_events *e, const struct opengpu_vm *v, struct dma_fence **out)
{ (void)g; (void)l; (void)s; (void)t; (void)e; (void)v; submits++; *out=&fence; return 0; }
#include "job_under_test.inc"
int main(void)
{
    struct opengpu_device gpu = {0}; struct opengpu_render_context ctx = {0};
    struct opengpu_sched_job j = { .type=OPENGPU_SCHED_COMPUTE, .gpu=&gpu,
        .texture_records = { .cpu=&gpu, .dma=0x12000, .size=512 },
        .kernel = { .texture_enabled=true, .texture.root=0x12000 } };
    assert(opengpu_job_run(&j) == &fence && submits == 1 && !mappings);
    j.kernel.texture.root++; assert(opengpu_job_run(&j) == ERR_PTR(-EINVAL) && submits == 1);
    j.vm=&ctx.vm; j.context=&ctx; j.kernel.texture.root=OPENGPU_COMMAND_VA_BASE+2*OPENGPU_COMMAND_VA_STRIDE;
    assert(opengpu_job_run(&j) == &fence && submits == 2 && mappings == 1 && tracks == 1);
    assert(mapped_va == j.kernel.texture.root && mapped_pa == 0x12000 && mapped_size == 4096);
    j.kernel.texture.root++; assert(opengpu_job_run(&j) == ERR_PTR(-EINVAL) && mappings == 1);
    j.kernel.texture.root--; map_error=-ENOMEM;
    assert(opengpu_job_run(&j) == ERR_PTR(-ENOMEM) && submits == 2 && tracks == 1);
    map_error=0; track_error=-ENOSPC;
    assert(opengpu_job_run(&j) == ERR_PTR(-ENOSPC) && submits == 2);
    track_error=0; j.texture_records.cpu=NULL;
    assert(opengpu_job_run(&j) == ERR_PTR(-EINVAL) && submits == 2);
    j.texture_records.cpu=&gpu; j.texture_records.size=127;
    assert(opengpu_job_run(&j) == ERR_PTR(-EINVAL) && submits == 2);
    j.kernel.texture_enabled=false;
    assert(opengpu_job_run(&j) == &fence && submits == 3);
    return 0;
}
