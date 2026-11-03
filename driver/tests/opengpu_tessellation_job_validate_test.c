#include "../opengpu_tessellation_job_validate.h"
#include "../opengpu_native_binned_abi.h"
#include <assert.h>
#include <string.h>

_Static_assert(OPENGPU_TESS_FRAME_POOL_IDENTITY == OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_BINS_POOL_IDENTITY_CONTEXT_WORD, "pool identity offset");
_Static_assert(OPENGPU_TESS_FRAME_POOL_IDENTITY + 1 == OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_BINS_POOL_IDENTITY_GENERATION_WORD && OPENGPU_TESS_FRAME_POOL_IDENTITY + 2 == OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_BINS_POOL_IDENTITY_PASS_WORD, "pool identity layout");
_Static_assert(OPENGPU_TESS_FRAME_ARENA_IDENTITY == OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_PRODUCER_ARENA_IDENTITY_CONTEXT_WORD, "arena identity offset");
_Static_assert(OPENGPU_TESS_FRAME_ARENA_IDENTITY + 1 == OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_PRODUCER_ARENA_IDENTITY_GENERATION_WORD && OPENGPU_TESS_FRAME_ARENA_IDENTITY + 2 == OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_PRODUCER_ARENA_IDENTITY_PASS_WORD, "arena identity layout");
_Static_assert(OPENGPU_TESS_FRAME_COLOR_IDENTITY == OPENGPU_NATIVE_BINNED_FRAME_FRAME_IMAGE_PIXELS_COLORS_IDENTITY_CONTEXT_WORD, "color identity offset");
_Static_assert(OPENGPU_TESS_FRAME_COLOR_IDENTITY + 1 == OPENGPU_NATIVE_BINNED_FRAME_FRAME_IMAGE_PIXELS_COLORS_IDENTITY_GENERATION_WORD && OPENGPU_TESS_FRAME_COLOR_IDENTITY + 2 == OPENGPU_NATIVE_BINNED_FRAME_FRAME_IMAGE_PIXELS_COLORS_IDENTITY_PASS_WORD, "color identity layout");
_Static_assert(OPENGPU_TESS_FRAME_STATE_IDENTITY == OPENGPU_NATIVE_BINNED_FRAME_FRAME_STATES_IDENTITY_CONTEXT_WORD, "state identity offset");
_Static_assert(OPENGPU_TESS_FRAME_STATE_IDENTITY + 1 == OPENGPU_NATIVE_BINNED_FRAME_FRAME_STATES_IDENTITY_GENERATION_WORD && OPENGPU_TESS_FRAME_STATE_IDENTITY + 2 == OPENGPU_NATIVE_BINNED_FRAME_FRAME_STATES_IDENTITY_PASS_WORD, "state identity layout");
_Static_assert(OPENGPU_TESS_FRAME_TILE_COUNT == OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_BINS_TILECOUNT_WORD, "tile count offset");
_Static_assert(OPENGPU_TESS_FRAME_VERSION == OPENGPU_NATIVE_BINNED_FRAME_FRAME_VERSION_WORD, "frame version offset");
_Static_assert(OPENGPU_NATIVE_TESSELLATION_JOB_FRAME_MAGIC == OPENGPU_NATIVE_BINNED_FRAME_MAGIC && OPENGPU_NATIVE_TESSELLATION_JOB_FRAME_BYTES == OPENGPU_NATIVE_BINNED_FRAME_BYTES && OPENGPU_NATIVE_TESSELLATION_JOB_VERSION == OPENGPU_NATIVE_BINNED_VERSION, "frame ABI");
_Static_assert(OPENGPU_TESS_FRAME_DIRECTORY_BASE == OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_BINS_DIRECTORYBASE_WORD && OPENGPU_TESS_FRAME_DIRECTORY_LIMIT == OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_BINS_DIRECTORYLIMIT_WORD, "directory range");
_Static_assert(OPENGPU_TESS_FRAME_POOL_BASE == OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_BINS_POOL_BASE_WORD && OPENGPU_TESS_FRAME_POOL_LIMIT == OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_BINS_POOL_LIMIT_WORD, "pool range");
_Static_assert(OPENGPU_TESS_FRAME_ARENA_BASE == OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_PRODUCER_ARENA_BASE_WORD && OPENGPU_TESS_FRAME_ARENA_LIMIT == OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_PRODUCER_ARENA_LIMIT_WORD, "arena range");
_Static_assert(OPENGPU_TESS_FRAME_STATE_BASE == OPENGPU_NATIVE_BINNED_FRAME_FRAME_STATES_BASE_WORD && OPENGPU_TESS_FRAME_STATE_LIMIT == OPENGPU_NATIVE_BINNED_FRAME_FRAME_STATES_LIMIT_WORD, "state range");
_Static_assert(OPENGPU_TESS_FRAME_DEPTH_FORMAT == OPENGPU_NATIVE_BINNED_FRAME_FRAME_IMAGE_PIXELS_DEPTH_FORMAT_WORD && OPENGPU_TESS_FRAME_DEPTH_LOAD == OPENGPU_NATIVE_BINNED_FRAME_FRAME_IMAGE_PIXELS_DEPTH_LOADOP_WORD && OPENGPU_TESS_FRAME_STENCIL_FORMAT == OPENGPU_NATIVE_BINNED_FRAME_FRAME_IMAGE_PIXELS_STENCIL_FORMAT_WORD && OPENGPU_TESS_FRAME_STENCIL_LOAD == OPENGPU_NATIVE_BINNED_FRAME_FRAME_IMAGE_PIXELS_STENCIL_LOADOP_WORD, "depth and stencil access");
#define ASSERT_COLOR_LAYOUT(i) \
    _Static_assert(OPENGPU_TESS_FRAME_COLOR_ENABLED + (i) * OPENGPU_TESS_FRAME_COLOR_STRIDE == OPENGPU_NATIVE_BINNED_FRAME_FRAME_IMAGE_PIXELS_COLORS_COLORS##i##_ENABLED_WORD && \
                   OPENGPU_TESS_FRAME_COLOR_LOAD + (i) * OPENGPU_TESS_FRAME_COLOR_STRIDE == OPENGPU_NATIVE_BINNED_FRAME_FRAME_IMAGE_PIXELS_COLORS_COLORS##i##_LOADOP_WORD, "color access layout")
ASSERT_COLOR_LAYOUT(0); ASSERT_COLOR_LAYOUT(1); ASSERT_COLOR_LAYOUT(2); ASSERT_COLOR_LAYOUT(3);
#define ASSERT_VIEW_LAYOUT(i) \
    _Static_assert(OPENGPU_TESS_FRAME_VIEW_BASE + (i) * OPENGPU_TESS_FRAME_VIEW_STRIDE == OPENGPU_NATIVE_BINNED_FRAME_FRAME_IMAGE_VIEWS##i##_ALLOCATIONBASE_WORD && \
                   OPENGPU_TESS_FRAME_VIEW_LIMIT + (i) * OPENGPU_TESS_FRAME_VIEW_STRIDE == OPENGPU_NATIVE_BINNED_FRAME_FRAME_IMAGE_VIEWS##i##_ALLOCATIONLIMIT_WORD && \
                   OPENGPU_TESS_FRAME_VIEW_STORE + (i) * OPENGPU_TESS_FRAME_VIEW_STRIDE == OPENGPU_NATIVE_BINNED_FRAME_FRAME_IMAGE_VIEWS##i##_STORE_WORD, "image view layout")
ASSERT_VIEW_LAYOUT(0); ASSERT_VIEW_LAYOUT(1); ASSERT_VIEW_LAYOUT(2);
ASSERT_VIEW_LAYOUT(3); ASSERT_VIEW_LAYOUT(4); ASSERT_VIEW_LAYOUT(5);
#define ASSERT_RESOLVE_LAYOUT(i,b,l,e) \
    _Static_assert((b) == OPENGPU_NATIVE_BINNED_FRAME_FRAME_IMAGE_RESOLVES##i##_DESTINATION_ALLOCATIONBASE_WORD && \
                   (l) == OPENGPU_NATIVE_BINNED_FRAME_FRAME_IMAGE_RESOLVES##i##_DESTINATION_ALLOCATIONLIMIT_WORD && \
                   (e) == OPENGPU_NATIVE_BINNED_FRAME_FRAME_IMAGE_RESOLVES##i##_ENABLE_WORD, "image resolve layout");
OPENGPU_TESS_FRAME_RESOLVE_LAYOUT(ASSERT_RESOLVE_LAYOUT)
#undef ASSERT_RESOLVE_LAYOUT
_Static_assert(OPENGPU_TESS_FRAME_SCRATCH_BASE == OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCH0_BASE_WORD && OPENGPU_TESS_FRAME_SCRATCH_BASE + OPENGPU_TESS_FRAME_SCRATCH_STRIDE == OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCH1_BASE_WORD && OPENGPU_TESS_FRAME_SCRATCH_BASE + 2 * OPENGPU_TESS_FRAME_SCRATCH_STRIDE == OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCH2_BASE_WORD && OPENGPU_TESS_FRAME_SCRATCH_BASE + 3 * OPENGPU_TESS_FRAME_SCRATCH_STRIDE == OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCH3_BASE_WORD, "scratch ranges");
_Static_assert(OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCH0_LIMIT_WORD == OPENGPU_TESS_FRAME_SCRATCH_BASE + 1 && OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCH1_LIMIT_WORD == OPENGPU_TESS_FRAME_SCRATCH_BASE + 4 && OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCH2_LIMIT_WORD == OPENGPU_TESS_FRAME_SCRATCH_BASE + 7 && OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCH3_LIMIT_WORD == OPENGPU_TESS_FRAME_SCRATCH_BASE + 10, "scratch limits");
_Static_assert(OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_BINS_DIRECTORYLIMIT_BITS == 33 && OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_BINS_POOL_LIMIT_BITS == 33 && OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_PRODUCER_ARENA_LIMIT_BITS == 33 && OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCH0_LIMIT_BITS == 33 && OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCH1_LIMIT_BITS == 33 && OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCH2_LIMIT_BITS == 33 && OPENGPU_NATIVE_BINNED_FRAME_FRAME_SCRATCH3_LIMIT_BITS == 33, "range limit widths");
#define SAME_DRAW_FIELD(field) \
    _Static_assert(OPENGPU_NATIVE_TESSELLATION_SEQUENCE_##field == OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_##field && \
                   OPENGPU_NATIVE_TESSELLATION_SEQUENCE_##field == OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_##field, "draw ABI " #field)
SAME_DRAW_FIELD(BYTES);
SAME_DRAW_FIELD(COUNT_WORD);
SAME_DRAW_FIELD(PATCHSTRIDE_WORD);
SAME_DRAW_FIELD(TEMPLATE_INPUT_ALLOCATIONBASE_WORD);
SAME_DRAW_FIELD(TEMPLATE_INPUT_ALLOCATIONLIMIT_WORD);
SAME_DRAW_FIELD(TEMPLATE_INPUT_BASE_WORD);
SAME_DRAW_FIELD(TEMPLATE_INPUT_LIMIT_WORD);
SAME_DRAW_FIELD(TEMPLATE_SOURCE_VIEWPORT_IDENTITY_CONTEXT_WORD);
SAME_DRAW_FIELD(TEMPLATE_SOURCE_VIEWPORT_IDENTITY_GENERATION_WORD);
SAME_DRAW_FIELD(TEMPLATE_SOURCE_VIEWPORT_IDENTITY_PASS_WORD);
SAME_DRAW_FIELD(TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_COMMANDID_WORD);
#define SAME_TRI_QUAD_FIELD(field) \
    _Static_assert(OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_##field == OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_##field, "triangle/quad ABI " #field)
SAME_TRI_QUAD_FIELD(TEMPLATE_STRIDE_WORD);
SAME_TRI_QUAD_FIELD(TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTVERTICES_WORD);
SAME_TRI_QUAD_FIELD(TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTCOMPONENTS_WORD);
SAME_TRI_QUAD_FIELD(TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_TASKID_WORD);

static void word(unsigned char *b, unsigned int index, unsigned int value)
{
    unsigned int i;
    for (i = 0; i < 4; ++i) b[index * 4u + i] = (unsigned char)(value >> (i * 8u));
}
static void valid(unsigned char *b, unsigned int magic)
{
    memset(b, 0, OPENGPU_NATIVE_TESSELLATION_JOB_BYTES);
    word(b, 0, magic); word(b, 1, 512); word(b, 2, 1);
    word(b, 3, 16); word(b, 4, 16); word(b, 5, 16); word(b, 6, 4); word(b, 7, 4);
    word(b, 16, 0x4000); word(b, 17, 2); word(b, 18, 0x5000); word(b, 20, 2048);
    word(b, 21, 0x2000); word(b, 22, 0x2800); word(b, 24, 17); word(b, 25, 19); word(b, 26, 18);
}
static int decode(const unsigned char *b, struct opengpu_tessellation_job_view *v)
{
    return opengpu_tessellation_job_decode(b, 512, 0x1000, 0x1200, v);
}
int main(void)
{
    unsigned char b[512], unaligned[513], frame[2049], draw[2049];
    struct opengpu_tessellation_job_view view, before;
    const unsigned int magic[] = { OPENGPU_NATIVE_TESSELLATION_JOB_MAGIC,
        OPENGPU_NATIVE_TESSELLATION_TRIANGLE_JOB_MAGIC, OPENGPU_NATIVE_TESSELLATION_QUAD_JOB_MAGIC };
    unsigned int i, j;
    for (i = 0; i < 3; ++i) {
        valid(b, magic[i]); assert(decode(b, &view));
        assert(view.domain == i && view.draw_count == 2 && view.draw_limit == 0x5000 &&
               view.frame_limit == 0x2800 && view.context == 17 && view.pass == 18 && view.generation == 19);
        memcpy(unaligned + 1, b, sizeof(b)); assert(decode(unaligned + 1, &view));
    }
    memset(&view, 0xa5, sizeof(view)); before = view;
    assert(!opengpu_tessellation_job_decode(b, 511, 0x1000, 0x1200, &view));
    assert(!opengpu_tessellation_job_decode(b, 512, 0x1001, 0x1200, &view));
    assert(!opengpu_tessellation_job_decode(b, 512, 0xffffffc0ULL, 0x100000000ULL, &view));
    assert(!opengpu_tessellation_job_decode(b, 512, 0x1000, 0x100000001ULL, &view));
    assert(!opengpu_tessellation_job_decode(0, 512, 0x1000, 0x1200, &view));
    assert(!opengpu_tessellation_job_decode(b, 512, 0x1000, 0x1200, 0));
    assert(memcmp(&view, &before, sizeof(view)) == 0);
    word(b, 0, 0x474e5104u); assert(!decode(b, &view)); valid(b, magic[2]);
    word(b, 1, 64); assert(!decode(b, &view)); valid(b, magic[2]);
    word(b, 3, 15); assert(!decode(b, &view)); valid(b, magic[2]);
    word(b, 9, 1); assert(!decode(b, &view)); valid(b, magic[2]);
    word(b, 127, 1); assert(!decode(b, &view)); valid(b, magic[2]);
    word(b, 19, 2); assert(!decode(b, &view)); valid(b, magic[2]);
    word(b, 23, 2); assert(!decode(b, &view)); valid(b, magic[2]);
    word(b, 21, 0xfffff800u); word(b, 22, 0); word(b, 23, 1); assert(decode(b, &view));
    word(b, 21, 0xfffff840u); assert(!decode(b, &view)); valid(b, magic[2]);
    word(b, 16, 0xfffff800u); word(b, 17, 1); word(b, 18, 0); word(b, 19, 1); assert(decode(b, &view));
    word(b, 17, 2); assert(!decode(b, &view)); valid(b, magic[2]);
    word(b, 17, 0xffffffffu); word(b, 20, 0xffffffc0u); assert(!decode(b, &view)); valid(b, magic[2]);
    word(b, 20, 2047); assert(!decode(b, &view)); valid(b, magic[2]);
    word(b, 17, 0); word(b, 16, 1); word(b, 18, 1); word(b, 19, 1); word(b, 20, 1);
    assert(decode(b, &view)); /* Inactive draw fields are ignored by hardware. */

    memset(frame, 0, sizeof(frame));
    word(frame + 1, 0, OPENGPU_NATIVE_BINNED_FRAME_MAGIC);
    word(frame + 1, 1, OPENGPU_NATIVE_BINNED_FRAME_BYTES); word(frame + 1, 2, 1);
    word(frame + 1, 3, 16); word(frame + 1, 4, 16); word(frame + 1, 5, 16);
    word(frame + 1, 6, 4); word(frame + 1, 7, 4); word(frame + 1, OPENGPU_TESS_FRAME_VERSION, 1);
    { const unsigned int ids[] = { OPENGPU_TESS_FRAME_POOL_IDENTITY, OPENGPU_TESS_FRAME_ARENA_IDENTITY,
        OPENGPU_TESS_FRAME_COLOR_IDENTITY, OPENGPU_TESS_FRAME_STATE_IDENTITY };
      for (i = 0; i < sizeof(ids) / sizeof(ids[0]); ++i) {
        word(frame + 1, ids[i], view.context); word(frame + 1, ids[i] + 1, view.generation);
        word(frame + 1, ids[i] + 2, view.pass);
      }
      assert(opengpu_tessellation_frame_snapshot_precheck(frame + 1, 2048, &view));
      for (i = 0; i < sizeof(ids) / sizeof(ids[0]); ++i)
        for (j = 0; j < 3; ++j) {
          unsigned int value = j == 0 ? view.context : j == 1 ? view.generation : view.pass;
          word(frame + 1, ids[i] + j, value ^ 1u);
          assert(!opengpu_tessellation_frame_snapshot_precheck(frame + 1, 2048, &view));
          word(frame + 1, ids[i] + j, value);
        }
    }
    assert(!opengpu_tessellation_frame_snapshot_precheck(frame + 1, 2047, &view));
    assert(!opengpu_tessellation_frame_snapshot_precheck(0, 2048, &view));
    assert(!opengpu_tessellation_frame_snapshot_precheck(frame + 1, 2048, 0));
    word(frame + 1, OPENGPU_TESS_FRAME_TILE_COUNT, 0x10000);
    assert(!opengpu_tessellation_frame_snapshot_precheck(frame + 1, 2048, &view));
    word(frame + 1, OPENGPU_TESS_FRAME_TILE_COUNT, 0xffff);
    assert(opengpu_tessellation_frame_snapshot_precheck(frame + 1, 2048, &view));
    word(frame + 1, 8, 1); assert(!opengpu_tessellation_frame_snapshot_precheck(frame + 1, 2048, &view));
    word(frame + 1, 8, 0); word(frame + 1, OPENGPU_TESS_FRAME_VERSION, 2);
    assert(!opengpu_tessellation_frame_snapshot_precheck(frame + 1, 2048, &view));
    word(frame + 1, OPENGPU_TESS_FRAME_VERSION, 1); valid(b, magic[2]); assert(decode(b, &view));
    { const unsigned int starts[] = {OPENGPU_TESS_FRAME_ARENA_BASE, OPENGPU_TESS_FRAME_POOL_BASE,
          OPENGPU_TESS_FRAME_DIRECTORY_BASE, OPENGPU_TESS_FRAME_SCRATCH_BASE,
          OPENGPU_TESS_FRAME_SCRATCH_BASE + OPENGPU_TESS_FRAME_SCRATCH_STRIDE};
      const unsigned int ends[] = {OPENGPU_TESS_FRAME_ARENA_LIMIT, OPENGPU_TESS_FRAME_POOL_LIMIT,
          OPENGPU_TESS_FRAME_DIRECTORY_LIMIT, OPENGPU_TESS_FRAME_SCRATCH_BASE + 1,
          OPENGPU_TESS_FRAME_SCRATCH_BASE + OPENGPU_TESS_FRAME_SCRATCH_STRIDE + 1};
      const unsigned int regions[] = {0x1000, 0x2000, 0x4000};
      assert(!opengpu_tessellation_frame_descriptor_alias_precheck(frame + 1, 2048, &view, 0));
      assert(!opengpu_tessellation_frame_descriptor_alias_precheck(frame + 1, 2048, &view, 5));
      for (i = 0; i < sizeof(starts) / sizeof(starts[0]); ++i) {
        for (j = 0; j < sizeof(regions) / sizeof(regions[0]); ++j) {
          word(frame + 1, starts[i], regions[j]); word(frame + 1, ends[i], regions[j] + 64);
          assert(!opengpu_tessellation_frame_descriptor_alias_precheck(frame + 1, 2048, &view, 2));
        }
        word(frame + 1, starts[i], 0); word(frame + 1, ends[i], 0);
      }
      word(frame + 1, OPENGPU_TESS_FRAME_ARENA_BASE, 0x1200);
      word(frame + 1, OPENGPU_TESS_FRAME_ARENA_LIMIT, 0x1240);
      assert(opengpu_tessellation_frame_descriptor_alias_precheck(frame + 1, 2048, &view, 2));
      word(frame + 1, OPENGPU_TESS_FRAME_ARENA_LIMIT + 1, 2);
      assert(!opengpu_tessellation_frame_descriptor_alias_precheck(frame + 1, 2048, &view, 2));
      word(frame + 1, OPENGPU_TESS_FRAME_ARENA_BASE, 0xffffffc0u);
      word(frame + 1, OPENGPU_TESS_FRAME_ARENA_LIMIT, 0);
      word(frame + 1, OPENGPU_TESS_FRAME_ARENA_LIMIT + 1, 1);
      assert(opengpu_tessellation_frame_descriptor_alias_precheck(frame + 1, 2048, &view, 2));
      word(frame + 1, OPENGPU_TESS_FRAME_ARENA_LIMIT, 1);
      assert(!opengpu_tessellation_frame_descriptor_alias_precheck(frame + 1, 2048, &view, 2));
      word(frame + 1, OPENGPU_TESS_FRAME_ARENA_BASE, 0); word(frame + 1, OPENGPU_TESS_FRAME_ARENA_LIMIT, 0);
      word(frame + 1, OPENGPU_TESS_FRAME_ARENA_LIMIT + 1, 0);
      word(frame + 1, OPENGPU_TESS_FRAME_SCRATCH_BASE + 6, 0x1000);
      word(frame + 1, OPENGPU_TESS_FRAME_SCRATCH_BASE + 7, 0x1040);
      assert(opengpu_tessellation_frame_descriptor_alias_precheck(frame + 1, 2048, &view, 2));
      assert(!opengpu_tessellation_frame_descriptor_alias_precheck(frame + 1, 2048, &view, 3));
      word(frame + 1, OPENGPU_TESS_FRAME_SCRATCH_BASE + 6, 0);
      word(frame + 1, OPENGPU_TESS_FRAME_SCRATCH_BASE + 7, 0);
      word(frame + 1, OPENGPU_TESS_FRAME_POOL_BASE, 0x4000);
      word(frame + 1, OPENGPU_TESS_FRAME_POOL_LIMIT, 0x4040);
      view.draw_count = 0; assert(opengpu_tessellation_frame_descriptor_alias_precheck(frame + 1, 2048, &view, 2));
      view.draw_count = 2; assert(!opengpu_tessellation_frame_descriptor_alias_precheck(frame + 1, 2048, &view, 2));
      word(frame + 1, OPENGPU_TESS_FRAME_POOL_BASE, 0xffffffc0u);
      word(frame + 1, OPENGPU_TESS_FRAME_POOL_LIMIT, 0);
      word(frame + 1, OPENGPU_TESS_FRAME_POOL_LIMIT + 1, 1);
      view.frame_base = 0xfffff800u; view.frame_limit = 0x100000000ULL;
      assert(!opengpu_tessellation_frame_descriptor_alias_precheck(frame + 1, 2048, &view, 2));
    }

    for (i = 0; i < 3; ++i) {
        static const unsigned int draw_magic[] = { OPENGPU_NATIVE_TESSELLATION_SEQUENCE_MAGIC,
            OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_MAGIC, OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_MAGIC };
        valid(b, magic[i]); assert(decode(b, &view));
        memset(draw, 0, sizeof(draw)); word(draw + 1, 0, draw_magic[i]);
        word(draw + 1, 1, 2048); word(draw + 1, 2, i + 1);
        word(draw + 1, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_IDENTITY_CONTEXT_WORD, view.context);
        word(draw + 1, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_IDENTITY_GENERATION_WORD, view.generation);
        word(draw + 1, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_IDENTITY_PASS_WORD, view.pass);
        assert(opengpu_tessellation_draw_snapshot_precheck(draw + 1, 2048, &view, 1));
        { unsigned int vw = i ? OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTVERTICES_WORD : OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTVERTICES_WORD;
          unsigned int cw = i ? OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTCOMPONENTS_WORD : OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTCOMPONENTS_WORD;
          unsigned int tw = i ? OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_TASKID_WORD : OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_TASKID_WORD;
          unsigned int sw = i ? OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_TEMPLATE_STRIDE_WORD : OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_STRIDE_WORD;
          struct opengpu_tessellation_input_view input, saved;
          assert(opengpu_tessellation_draw_input_span_precheck(draw + 1, 2048, &view, 0)); /* empty */
          assert(opengpu_tessellation_draw_input_view_precheck(draw + 1, 2048, &view, 0, &input));
          assert(!input.active && !input.allocation_base && !input.allocation_limit);
          word(draw + 1, 16, 2); word(draw + 1, 17, 64); word(draw + 1, 18, 0x1000);
          word(draw + 1, 19, 0x1100); word(draw + 1, 21, 0x1000); word(draw + 1, 22, 0x1060);
          word(draw + 1, vw, 2); word(draw + 1, cw, 4); word(draw + 1, sw, 16);
          assert(opengpu_tessellation_draw_input_span_precheck(draw + 1, 2048, &view, 0));
          assert(opengpu_tessellation_draw_input_view_precheck(draw + 1, 2048, &view, 0, &input));
          assert(input.active && input.allocation_base == 0x1000 && input.allocation_limit == 0x1100);
          saved = input;
          word(draw + 1, 22, 0x105f); assert(!opengpu_tessellation_draw_input_span_precheck(draw + 1, 2048, &view, 0));
          assert(!opengpu_tessellation_draw_input_view_precheck(draw + 1, 2048, &view, 0, &input));
          assert(input.active == saved.active && input.allocation_base == saved.allocation_base &&
                 input.allocation_limit == saved.allocation_limit);
          assert(!opengpu_tessellation_draw_input_view_precheck(draw + 1, 2048, &view, 0, 0));
          word(draw + 1, 22, 0x1060); word(draw + 1, tw, 0xffffffffu);
          assert(!opengpu_tessellation_draw_input_span_precheck(draw + 1, 2048, &view, 0)); word(draw + 1, tw, 0);
          word(draw + 1, vw, 33); assert(!opengpu_tessellation_draw_input_span_precheck(draw + 1, 2048, &view, 0));
          word(draw + 1, vw, 2); word(draw + 1, cw, 0); word(draw + 1, 18, 1);
          assert(opengpu_tessellation_draw_input_span_precheck(draw + 1, 2048, &view, 0)); /* no input reads */
          assert(opengpu_tessellation_draw_input_view_precheck(draw + 1, 2048, &view, 0, &input));
          assert(!input.active && !input.allocation_base && !input.allocation_limit);
          word(draw + 1, cw, 4); word(draw + 1, 16, 1); word(draw + 1, 18, 0xffffffc0u);
          word(draw + 1, 19, 0); word(draw + 1, 20, 1); word(draw + 1, 21, 0xffffffe0u);
          word(draw + 1, 22, 0); word(draw + 1, 23, 1);
          assert(opengpu_tessellation_draw_input_span_precheck(draw + 1, 2048, &view, 0));
          assert(opengpu_tessellation_draw_input_view_precheck(draw + 1, 2048, &view, 0, &input));
          assert(input.active && input.allocation_base == 0xffffffc0u &&
                 input.allocation_limit == 0x100000000ULL);
          word(draw + 1, 21, 0xffffffe4u); assert(!opengpu_tessellation_draw_input_span_precheck(draw + 1, 2048, &view, 0));
        }
        assert(!opengpu_tessellation_draw_snapshot_precheck(draw + 1, 2048, &view, 2));
        assert(!opengpu_tessellation_draw_snapshot_precheck(draw + 1, 2047, &view, 0));
        view.draw_count = 0; assert(!opengpu_tessellation_draw_snapshot_precheck(draw + 1, 2048, &view, 0));
        view.draw_count = 2;
        word(draw + 1, 2, 99); assert(!opengpu_tessellation_draw_snapshot_precheck(draw + 1, 2048, &view, 0));
        word(draw + 1, 2, i + 1);
        for (j = 0; j < 3; ++j) {
            unsigned int value = j == 0 ? view.context : j == 1 ? view.generation : view.pass;
            word(draw + 1, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_IDENTITY_CONTEXT_WORD + j, value ^ 1u);
            assert(!opengpu_tessellation_draw_snapshot_precheck(draw + 1, 2048, &view, 0));
            word(draw + 1, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_IDENTITY_CONTEXT_WORD + j, value);
        }
        word(draw + 1, 0, draw_magic[(i + 1) % 3]);
        assert(!opengpu_tessellation_draw_snapshot_precheck(draw + 1, 2048, &view, 0));
        word(draw + 1, 0, draw_magic[i]); word(draw + 1, 3, 1);
        assert(!opengpu_tessellation_draw_snapshot_precheck(draw + 1, 2048, &view, 0));
        word(draw + 1, 3, 0);
        word(draw + 1, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_COMMANDID_WORD, 1);
        assert(!opengpu_tessellation_draw_snapshot_precheck(draw + 1, 2048, &view, 0));
    }
    assert(!opengpu_tessellation_draw_snapshot_precheck(0, 2048, &view, 0));
    assert(!opengpu_tessellation_draw_snapshot_precheck(draw + 1, 2048, 0, 0));
    view.domain = 3; assert(!opengpu_tessellation_draw_snapshot_precheck(draw + 1, 2048, &view, 0));
    return 0;
}
