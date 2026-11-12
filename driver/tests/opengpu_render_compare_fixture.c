/* Real render-command validation with modeled shader/resource bindings. */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include "opengpu_drm.h"
#include "gpu_abi.h"
#include "opengpu_graphics_features.h"

typedef uint64_t u64;
struct opengpu_buffer { void *cpu; u64 dma; };
struct opengpu_resource_binding { u64 size, va, dma; u32 flags; };
struct opengpu_device { struct { u32 capabilities; bool clip_depth, sampler_nearest, pixel_center, sampler_axis_wrap, sampler_biased_clamp, sampler_mirrored_repeat, viewport, depth_range, constant_blend; } hw; };
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
_Static_assert(!(OPENGPU_DRAW_STATE_VALID_MASK &
                 (GPU_DRAW_FRAGMENT_INPUT_SKIP_MASK | GPU_DRAW_FRAGMENT_OUTPUT_SKIP_MASK)),
               "userspace state must not overlap trusted fragment metadata");

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

    for (u32 version=1; version<=3; ++version)
        for (u32 mask=0; mask<4; ++mask) {
            u32 caps=(mask & 1 ? GPU_CAP_VERTEX_CORE : 0) |
                     (mask & 2 ? GPU_CAP_UNIFIED_RENDER : 0);
            assert(opengpu_graphics_clip_depth_supported(0x47550000u | version, caps) ==
                   (version == 3 && mask == 3));
        }
    for (u32 version=1; version<=4; ++version)
        for (u32 mask=0; mask<8; ++mask) {
            u32 caps=(mask & 1 ? GPU_CAP_VERTEX_CORE : 0) |
                     (mask & 2 ? GPU_CAP_FRAGMENT_CORE : 0) |
                     (mask & 4 ? GPU_CAP_UNIFIED_RENDER : 0);
            assert(opengpu_graphics_nearest_supported(0x47550000u | version, caps) ==
                   (version == 4 && mask == 7));
        }
    for (u32 version=1; version<=6; ++version)
        for (u32 mask=0; mask<4; ++mask) {
            u32 caps=(mask & 1 ? GPU_CAP_VERTEX_CORE : 0) |
                     (mask & 2 ? GPU_CAP_UNIFIED_RENDER : 0);
            assert(opengpu_graphics_pixel_center_supported(0x47550000u | version, caps) ==
                   (version >= 5 && mask == 3));
        }
    for (u32 version=1; version<=7; ++version)
        for (u32 mask=0; mask<8; ++mask) {
            u32 caps=(mask & 1 ? GPU_CAP_VERTEX_CORE : 0) |
                     (mask & 2 ? GPU_CAP_FRAGMENT_CORE : 0) |
                     (mask & 4 ? GPU_CAP_UNIFIED_RENDER : 0);
            assert(opengpu_graphics_axis_wrap_supported(0x47550000u | version, caps) ==
                   (version >= 6 && mask == 7));
        }
    for (u32 version=1; version<=8; ++version)
        for (u32 mask=0; mask<4; ++mask) {
            u32 caps=(mask & 1 ? GPU_CAP_VERTEX_CORE : 0) |
                     (mask & 2 ? GPU_CAP_UNIFIED_RENDER : 0);
            assert(opengpu_graphics_viewport_supported(0x47550000u | version, caps) ==
                   (version >= 7 && mask == 3));
        }
    for (u32 version=1; version<=9; ++version)
        for (u32 mask=0; mask<4; ++mask) {
            u32 caps=(mask & 1 ? GPU_CAP_VERTEX_CORE : 0) |
                     (mask & 2 ? GPU_CAP_UNIFIED_RENDER : 0);
            assert(opengpu_graphics_depth_range_supported(0x47550000u | version, caps) ==
                   (version >= 8 && mask == 3));
        }
    for (u32 version=1; version<=10; ++version)
        for (u32 mask=0; mask<4; ++mask) {
            u32 caps=(mask & 1 ? GPU_CAP_VERTEX_CORE : 0) |
                     (mask & 2 ? GPU_CAP_UNIFIED_RENDER : 0);
            assert(opengpu_graphics_constant_blend_supported(0x47550000u | version, caps) ==
                   (version >= 9 && mask == 3));
        }
    for (u32 version=1; version<=11; ++version)
        for (u32 mask=0; mask<8; ++mask) {
            u32 caps=(mask & 1 ? GPU_CAP_VERTEX_CORE : 0) |
                     (mask & 2 ? GPU_CAP_FRAGMENT_CORE : 0) |
                     (mask & 4 ? GPU_CAP_UNIFIED_RENDER : 0);
            assert(opengpu_graphics_biased_clamp_supported(0x47550000u | version, caps) ==
                   (version >= 10 && mask == 7));
        }
    for (u32 version=1; version<=12; ++version)
        for (u32 mask=0; mask<8; ++mask) {
            u32 caps=(mask & 1 ? GPU_CAP_VERTEX_CORE : 0) |
                     (mask & 2 ? GPU_CAP_FRAGMENT_CORE : 0) |
                     (mask & 4 ? GPU_CAP_UNIFIED_RENDER : 0);
            assert(opengpu_graphics_mirrored_repeat_supported(0x47550000u | version, caps) ==
                   (version >= 11 && mask == 7));
        }
    commands.cpu=&vertex;
    vertex.vert_attr_format=OPENGPU_VERTEX_FORMAT_CLIP_DEPTH;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL)==-EOPNOTSUPP);
    gpu.hw.clip_depth=true;
    assert(!opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL));
    vertex.vert_attr_format=2;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL)==-EINVAL);
    vertex.vert_attr_format=0;

    /* Viewport words are accepted only by v7 vertex hosts and only while
     * enabled; a flipped axis without a viewport is malformed. */
    fixed.state=OPENGPU_DRAW_STATE_OVERRIDE | OPENGPU_DRAW_STATE_VIEWPORT;
    commands.cpu=&fixed;
    assert(opengpu_validate_commands(&gpu,&commands,&args,NULL,NULL,NULL)==-EINVAL);
    commands.cpu=&vertex;
    vertex.state=OPENGPU_DRAW_STATE_OVERRIDE | OPENGPU_DRAW_STATE_VIEWPORT;
    vertex.reserved0[0]=0xfffe0003u; vertex.reserved0[1]=0x00090011u;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL)==-EOPNOTSUPP);
    gpu.hw.viewport=true;
    assert(!opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL));
    vertex.state|=OPENGPU_DRAW_STATE_VIEWPORT_FLIP_Y;
    assert(!opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL));
    vertex.reserved0[1]=0x00090000u;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL)==-EINVAL);
    vertex.reserved0[1]=0x00090011u; vertex.reserved0[2]=1;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL)==-EINVAL);
    vertex.reserved0[2]=0; vertex.state=OPENGPU_DRAW_STATE_OVERRIDE;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL)==-EINVAL);
    vertex.reserved0[0]=vertex.reserved0[1]=0;
    vertex.state|=OPENGPU_DRAW_STATE_VIEWPORT_FLIP_Y;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL)==-EINVAL);
    /* Userspace cannot forge the fragment usage metadata in bits 19..28. */
    vertex.state=OPENGPU_DRAW_STATE_OVERRIDE | (1u << GPU_DRAW_FRAGMENT_INPUT_SKIP_SHIFT);
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL)==-EINVAL);
    fixed.state=vertex.state=0;

    /* Range endpoints are opt-in D24 values on clip-depth vertex draws. */
    fixed.state=OPENGPU_DRAW_STATE_OVERRIDE | OPENGPU_DRAW_STATE_DEPTH_RANGE;
    commands.cpu=&fixed;
    assert(opengpu_validate_commands(&gpu,&commands,&args,NULL,NULL,NULL)==-EINVAL);
    commands.cpu=&vertex;
    vertex.state=OPENGPU_DRAW_STATE_OVERRIDE | OPENGPU_DRAW_STATE_DEPTH_RANGE;
    vertex.vert_attr_format=OPENGPU_VERTEX_FORMAT_CLIP_DEPTH;
    vertex.reserved0[2]=0x00e12345u;vertex.reserved0[3]=0x00123456u;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL)==-EOPNOTSUPP);
    gpu.hw.depth_range=true;
    assert(!opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL));
    vertex.reserved0[2]|=0x01000000u;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL)==-EINVAL);
    vertex.reserved0[2]&=0x00ffffffu;vertex.vert_attr_format=0;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL)==-EINVAL);
    vertex.vert_attr_format=OPENGPU_VERTEX_FORMAT_CLIP_DEPTH;
    vertex.state=OPENGPU_DRAW_STATE_OVERRIDE;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL)==-EINVAL);
    vertex.reserved0[2]=vertex.reserved0[3]=0;vertex.vert_attr_format=0;
    fixed.state=vertex.state=0;

    /* Constant factors and their RGBA word are opt-in on v9 vertex draws;
     * fixed triangles, old hosts and unrelated reserved data stay rejected. */
    vertex.state=OPENGPU_DRAW_STATE_OVERRIDE;
    vertex.blend_config=OPENGPU_DRAW_BLEND_PRESENT |
        (GPU_BLEND_FACTOR_CONSTANT_COLOR << OPENGPU_DRAW_BLEND_SRC_SHIFT) |
        (GPU_BLEND_FACTOR_ONE << OPENGPU_DRAW_BLEND_DST_SHIFT);
    vertex.reserved0[4]=0x80402080u;
    commands.cpu=&vertex;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL)==-EOPNOTSUPP);
    gpu.hw.constant_blend=true;
    assert(!opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL));
    vertex.reserved0[4]=0; /* Transparent black is a valid constant. */
    assert(!opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL));
    vertex.blend_config=OPENGPU_DRAW_BLEND_PRESENT |
        (GPU_BLEND_FACTOR_ONE << OPENGPU_DRAW_BLEND_SRC_SHIFT) |
        (GPU_BLEND_FACTOR_INV_CONSTANT_ALPHA << OPENGPU_DRAW_BLEND_DST_SHIFT);
    vertex.reserved0[4]=0x80402080u;
    assert(!opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL));
    vertex.blend_config=OPENGPU_DRAW_BLEND_PRESENT |
        (GPU_BLEND_FACTOR_ONE << OPENGPU_DRAW_BLEND_SRC_SHIFT);
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL)==-EINVAL);
    vertex.reserved0[4]=0;
    vertex.blend_config=OPENGPU_DRAW_BLEND_PRESENT | (15u << OPENGPU_DRAW_BLEND_SRC_SHIFT);
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL)==-EINVAL);
    fixed.state=OPENGPU_DRAW_STATE_OVERRIDE;
    fixed.blend_config=OPENGPU_DRAW_BLEND_PRESENT |
        (GPU_BLEND_FACTOR_CONSTANT_COLOR << OPENGPU_DRAW_BLEND_SRC_SHIFT);
    commands.cpu=&fixed;
    assert(opengpu_validate_commands(&gpu,&commands,&args,NULL,NULL,NULL)==-EINVAL);
    fixed.state=vertex.state=0;
    fixed.blend_config=vertex.blend_config=0;

    /* Both draw forms reject the new bit on older hosts. */
    fixed.state=vertex.state=OPENGPU_DRAW_STATE_OVERRIDE | OPENGPU_DRAW_STATE_TEX_ENABLE;
    fixed.sampler=vertex.sampler=OPENGPU_DRAW_SAMPLER_NEAREST;
    commands.cpu=&fixed;
    assert(opengpu_validate_commands(&gpu,&commands,&args,&shader,&binding,&binding)==-EOPNOTSUPP);
    commands.cpu=&vertex;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,&binding)==-EOPNOTSUPP);
    gpu.hw.sampler_nearest=true;
    commands.cpu=&fixed;
    assert(!opengpu_validate_commands(&gpu,&commands,&args,&shader,&binding,&binding));
    commands.cpu=&vertex;
    assert(!opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,&binding));
    fixed.state=vertex.state=OPENGPU_DRAW_STATE_OVERRIDE;
    commands.cpu=&fixed;
    assert(opengpu_validate_commands(&gpu,&commands,&args,&shader,&binding,NULL)==-EINVAL);
    commands.cpu=&vertex;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL)==-EINVAL);
    fixed.sampler=vertex.sampler=0;

    fixed.state=vertex.state=OPENGPU_DRAW_STATE_OVERRIDE | OPENGPU_DRAW_STATE_TEX_ENABLE;
    fixed.sampler=vertex.sampler=OPENGPU_DRAW_SAMPLER_WRAP_V_FLIP;
    commands.cpu=&fixed;
    assert(opengpu_validate_commands(&gpu,&commands,&args,&shader,&binding,&binding)==-EOPNOTSUPP);
    commands.cpu=&vertex;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,&binding)==-EOPNOTSUPP);
    gpu.hw.sampler_axis_wrap=true;
    commands.cpu=&fixed;
    assert(!opengpu_validate_commands(&gpu,&commands,&args,&shader,&binding,&binding));
    commands.cpu=&vertex;
    assert(!opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,&binding));
    fixed.state=vertex.state=OPENGPU_DRAW_STATE_OVERRIDE;
    commands.cpu=&fixed;
    assert(opengpu_validate_commands(&gpu,&commands,&args,&shader,&binding,NULL)==-EINVAL);
    commands.cpu=&vertex;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL)==-EINVAL);
    fixed.sampler=vertex.sampler=0;

    /* Biased axes require v10, clamping on the selected axis, and a vertex draw. */
    fixed.state=vertex.state=OPENGPU_DRAW_STATE_OVERRIDE | OPENGPU_DRAW_STATE_TEX_ENABLE |
                             OPENGPU_DRAW_STATE_TEX_CLAMP;
    fixed.sampler=vertex.sampler=OPENGPU_DRAW_SAMPLER_BIASED_CLAMP_MASK;
    commands.cpu=&fixed;
    assert(opengpu_validate_commands(&gpu,&commands,&args,&shader,&binding,&binding)==-EOPNOTSUPP);
    commands.cpu=&vertex;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,&binding)==-EOPNOTSUPP);
    gpu.hw.sampler_biased_clamp=true;
    commands.cpu=&fixed;
    assert(opengpu_validate_commands(&gpu,&commands,&args,&shader,&binding,&binding)==-EOPNOTSUPP);
    commands.cpu=&vertex;
    assert(!opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,&binding));
    vertex.sampler=OPENGPU_DRAW_SAMPLER_BIASED_CLAMP_V | OPENGPU_DRAW_SAMPLER_WRAP_V_FLIP;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,&binding)==-EINVAL);
    vertex.sampler=OPENGPU_DRAW_SAMPLER_BIASED_CLAMP_U | OPENGPU_DRAW_SAMPLER_WRAP_V_FLIP;
    assert(!opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,&binding));
    vertex.state &= ~OPENGPU_DRAW_STATE_TEX_CLAMP;
    vertex.sampler=OPENGPU_DRAW_SAMPLER_BIASED_CLAMP_V | OPENGPU_DRAW_SAMPLER_WRAP_V_FLIP;
    assert(!opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,&binding));
    vertex.sampler=OPENGPU_DRAW_SAMPLER_BIASED_CLAMP_U | OPENGPU_DRAW_SAMPLER_WRAP_V_FLIP;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,&binding)==-EINVAL);
    vertex.state &= ~OPENGPU_DRAW_STATE_TEX_ENABLE;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,&binding)==-EINVAL);
    fixed.sampler=vertex.sampler=0;
    fixed.state=vertex.state=0;

    /* Mirrors select clamped hardware taps and are not valid on fixed draws. */
    fixed.state=vertex.state=OPENGPU_DRAW_STATE_OVERRIDE | OPENGPU_DRAW_STATE_TEX_ENABLE |
                             OPENGPU_DRAW_STATE_TEX_CLAMP;
    fixed.sampler=vertex.sampler=OPENGPU_DRAW_SAMPLER_MIRROR_MASK;
    commands.cpu=&fixed;
    assert(opengpu_validate_commands(&gpu,&commands,&args,&shader,&binding,&binding)==-EOPNOTSUPP);
    commands.cpu=&vertex;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,&binding)==-EOPNOTSUPP);
    gpu.hw.sampler_mirrored_repeat=true;
    assert(!opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,&binding));
    vertex.sampler=OPENGPU_DRAW_SAMPLER_MIRROR_U | OPENGPU_DRAW_SAMPLER_BIASED_CLAMP_U;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,&binding)==-EINVAL);
    vertex.sampler=OPENGPU_DRAW_SAMPLER_MIRROR_V | OPENGPU_DRAW_SAMPLER_WRAP_V_FLIP;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,&binding)==-EINVAL);
    vertex.sampler=OPENGPU_DRAW_SAMPLER_MIRROR_U | OPENGPU_DRAW_SAMPLER_WRAP_V_FLIP;
    assert(!opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,&binding));
    vertex.state &= ~OPENGPU_DRAW_STATE_TEX_ENABLE;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,&binding)==-EINVAL);
    fixed.sampler=vertex.sampler=0;
    fixed.state=vertex.state=0;

    /* Raster center does not require a texture or fragment shader. */
    fixed.state=vertex.state=OPENGPU_DRAW_STATE_OVERRIDE;
    fixed.sampler=vertex.sampler=OPENGPU_DRAW_RASTER_PIXEL_CENTER;
    commands.cpu=&fixed;
    assert(opengpu_validate_commands(&gpu,&commands,&args,NULL,NULL,NULL)==-EOPNOTSUPP);
    commands.cpu=&vertex;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL)==-EOPNOTSUPP);
    gpu.hw.pixel_center=true;
    commands.cpu=&fixed;
    assert(!opengpu_validate_commands(&gpu,&commands,&args,NULL,NULL,NULL));
    commands.cpu=&vertex;
    assert(!opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL));
    fixed.state=vertex.state=0;
    commands.cpu=&fixed;
    assert(opengpu_validate_commands(&gpu,&commands,&args,NULL,NULL,NULL)==-EINVAL);
    commands.cpu=&vertex;
    assert(opengpu_validate_vertex_commands(&gpu,&commands,&args,&binding,
           &shader,&binding,&shader,&binding,NULL)==-EINVAL);
    fixed.sampler=vertex.sampler=0;

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
