/* Actual render-ioctl admission and GEM cleanup with mocked objects. */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include "opengpu_drm.h"
#include "gpu_abi.h"
#include "opengpu_render_extent.h"
typedef uint64_t u64;
typedef uint64_t dma_addr_t;
struct drm_gem_object { size_t size; unsigned refs; };
struct drm_gem_dma_object;
struct opengpu_device { void *dev; u32 width,height,max_width,max_height; struct {u32 capabilities;} hw; };
struct drm_device { void *dev; };
struct drm_file { void *driver_priv; };
struct opengpu_depth_desc { int unused; };
struct drm_exec { int unused; };
struct opengpu_sched_job;
struct drm_syncobj;
struct opengpu_resource_binding;
struct opengpu_render_context;
struct opengpu_file;
static struct opengpu_device gpu={.width=16,.height=16,.max_width=64,.max_height=64};
static struct drm_gem_object command={.size=8192}, color={.size=1024};
static unsigned lookups;
static void *dev_get_drvdata(void *dev) { (void)dev; return &gpu; }
static struct drm_gem_object *drm_gem_object_lookup(struct drm_file *f, u32 handle)
{ struct drm_gem_object *o = handle==1 ? &command : handle==2 ? &color : NULL;
  (void)f; ++lookups; if(o) ++o->refs; return o; }
static void drm_gem_object_put(struct drm_gem_object *o) { assert(o && o->refs); --o->refs; }
#define check_mul_overflow(a,b,out) __builtin_mul_overflow((a),(b),(out))
#define check_add_overflow(a,b,out) __builtin_add_overflow((a),(b),(out))
#include "render_admission_under_test.inc"

struct mock_buffer { unsigned live; };
struct opengpu_sched_job { struct mock_buffer render_desc, depth, vertex_shader, shader, commands; };
static unsigned freed;
static void opengpu_buffer_free(struct opengpu_device *g, struct mock_buffer *b)
{ (void)g; if (b->live) { b->live=0; ++freed; } }
static void kfree(void *p) { (void)p; }
#include "render_failure_cleanup_under_test.inc"

int main(void)
{
    struct drm_device drm={0}; struct drm_file file={0};
    struct drm_opengpu_submit a={.context_id=1,.command_handle=1,.color_handle=2,
                                  .stride=64,.command_count=1};
    for (unsigned i=0;i<100;i++) {
        assert(opengpu_compute_drm_ioctl(&drm,&a,&file)==0);
        assert(command.refs==0 && color.refs==0);
    }
    assert(lookups==200);
    a.color_handle2=3; assert(opengpu_compute_drm_ioctl(&drm,&a,&file)==-EOPNOTSUPP);
    a.color_handle2=0; a.color_handle3=4; assert(opengpu_compute_drm_ioctl(&drm,&a,&file)==-EOPNOTSUPP);
    a.color_handle3=0; a.color_handle4=5; assert(opengpu_compute_drm_ioctl(&drm,&a,&file)==-EOPNOTSUPP);
    assert(lookups==200 && !command.refs && !color.refs);
    a.color_handle4=0; color.size=1023;
    assert(opengpu_compute_drm_ioctl(&drm,&a,&file)==-EINVAL);
    assert(!command.refs && !color.refs);
    {
        struct opengpu_sched_job job={{1},{1},{1},{1},{1}};
        test_failed_job_cleanup(&gpu,&job);
        assert(freed==5 && !job.render_desc.live && !job.depth.live &&
               !job.vertex_shader.live && !job.shader.live && !job.commands.live);
    }
    color.size=1024; a.color_handle=9;
    assert(opengpu_compute_drm_ioctl(&drm,&a,&file)==-ENOENT);
    assert(!command.refs && !color.refs);
    puts("Render admission and GEM cleanup fixture: PASS");
    return 0;
}
