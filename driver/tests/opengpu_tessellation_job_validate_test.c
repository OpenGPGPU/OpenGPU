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
#define SAME_DRAW_FIELD(field) \
    _Static_assert(OPENGPU_NATIVE_TESSELLATION_SEQUENCE_##field == OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_##field && \
                   OPENGPU_NATIVE_TESSELLATION_SEQUENCE_##field == OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_##field, "draw ABI " #field)
SAME_DRAW_FIELD(BYTES);
SAME_DRAW_FIELD(TEMPLATE_SOURCE_VIEWPORT_IDENTITY_CONTEXT_WORD);
SAME_DRAW_FIELD(TEMPLATE_SOURCE_VIEWPORT_IDENTITY_GENERATION_WORD);
SAME_DRAW_FIELD(TEMPLATE_SOURCE_VIEWPORT_IDENTITY_PASS_WORD);
SAME_DRAW_FIELD(TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_COMMANDID_WORD);

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
