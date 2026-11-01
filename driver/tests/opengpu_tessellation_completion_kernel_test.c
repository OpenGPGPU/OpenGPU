#include "../opengpu_tessellation_completion.h"
int use_completion(const u32 *words, size_t count)
{
    struct opengpu_tessellation_completion decoded;
    return opengpu_tessellation_completion_decode(words, count, &decoded);
}

static u32 fake_read(void *context, u32 address)
{
    const u32 *words = context;
    return words[(address - OPENGPU_NATIVE_TESSELLATION_COMPLETION_BASE) / 4];
}
int capture_completion(u32 *words)
{
    struct opengpu_tessellation_completion decoded;
    return opengpu_tessellation_completion_capture(3, 0x1b0, words, fake_read, &decoded);
}
