#include "../opengpu_tessellation_job_validate.h"
#include <assert.h>
#include <string.h>

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
    unsigned char b[512], unaligned[513];
    struct opengpu_tessellation_job_view view, before;
    const unsigned int magic[] = { OPENGPU_NATIVE_TESSELLATION_JOB_MAGIC,
        OPENGPU_NATIVE_TESSELLATION_TRIANGLE_JOB_MAGIC, OPENGPU_NATIVE_TESSELLATION_QUAD_JOB_MAGIC };
    unsigned int i;
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
    return 0;
}
