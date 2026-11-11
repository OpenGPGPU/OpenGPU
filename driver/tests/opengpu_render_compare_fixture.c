/* Real render-command validation with modeled shader/resource bindings. */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include "opengpu_drm.h"
#include "gpu_abi.h"

typedef uint64_t u64;
struct opengpu_buffer { void *cpu; u64 dma; };
struct opengpu_resource_binding { u64 size, va, dma; u32 flags; };
struct opengpu_device { struct { u32 capabilities; } hw; };
#define BUILD_BUG_ON(x) _Static_assert(!(x), "ABI layout")
#define ARRAY_SIZE(x) (sizeof(x) / sizeof((x)[0]))
#define check_mul_overflow(a,b,out) __builtin_mul_overflow((a),(b),(out))
#define check_add_overflow(a,b,out) __builtin_add_overflow((a),(b),(out))
#define lower_32_bits(x) ((u32)(x))
static bool opengpu_validate_kernarg_binding(
    struct opengpu_device *gpu, struct opengpu_buffer *shader,
    struct opengpu_resource_binding *kernarg, u32 entry, u32 offset,
    u32 stride, u32 slices, bool vertex, bool texture, u32 *inputs, u32 *outputs)
{
    (void)gpu; (void)shader; (void)kernarg; (void)entry; (void)offset;
    (void)stride; (void)slices; (void)vertex; (void)texture;
    (void)inputs; (void)outputs; return true;
}
#include "render_compare_under_test.inc"

_Static_assert(GPU_DEPTH_FUNC_LESS==0 && GPU_DEPTH_FUNC_LEQUAL==1 &&
               GPU_DEPTH_FUNC_GREATER==2 && GPU_DEPTH_FUNC_GEQUAL==3 &&
               GPU_DEPTH_FUNC_EQUAL==4 && GPU_DEPTH_FUNC_NOTEQUAL==5 &&
               GPU_DEPTH_FUNC_ALWAYS==6 && GPU_DEPTH_FUNC_NEVER==7,
               "compare encoding must match the RTL");

int main(void)
{
    struct opengpu_device gpu={0};
    struct drm_opengpu_submit args={.command_count=1};
    struct gpu_draw_record fixed={0};
    struct gpu_vert_draw_record vertex={0};
    struct opengpu_buffer commands={0}, shader={0};
    struct opengpu_resource_binding binding={.size=96};
    fixed.v0[3]=fixed.v1[3]=fixed.v2[3]=1;
    vertex.vert_count=3; vertex.vert_stride=GPU_VERTEX_STRIDE_BYTES;

    for (u32 f=0;f<8;f++) {
        u32 state=OPENGPU_DRAW_STATE_OVERRIDE | OPENGPU_DRAW_STATE_DEPTH_TEST |
                  (f << OPENGPU_DRAW_STATE_DEPTH_FUNC_SHIFT);
        fixed.state=state; commands.cpu=&fixed;
        assert(!opengpu_validate_commands(&gpu,&commands,&args,NULL,NULL,NULL));
        vertex.state=state; commands.cpu=&vertex;
        assert(!opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
                                                 &shader,&binding,&shader,&binding,NULL));
    }
    /* A zero stencil config can still be enabled; disabled state must have
     * neither a config nor a reference/mask word. */
    fixed.state=OPENGPU_DRAW_STATE_OVERRIDE; fixed.stencil_config=0; fixed.stencil_ref=1;
    commands.cpu=&fixed;
    assert(opengpu_validate_commands(&gpu,&commands,&args,NULL,NULL,NULL)==-EINVAL);
    fixed.state |= OPENGPU_DRAW_STATE_STENCIL_TEST;
    assert(!opengpu_validate_commands(&gpu,&commands,&args,NULL,NULL,NULL));
    vertex.state=OPENGPU_DRAW_STATE_OVERRIDE; vertex.stencil_config=0; vertex.stencil_ref=1;
    commands.cpu=&vertex;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
                                            &shader,&binding,&shader,&binding,NULL)==-EINVAL);
    vertex.state |= OPENGPU_DRAW_STATE_STENCIL_TEST;
    assert(!opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
                                             &shader,&binding,&shader,&binding,NULL));
    puts("render comparison validation fixture: PASS");
    return 0;
}
