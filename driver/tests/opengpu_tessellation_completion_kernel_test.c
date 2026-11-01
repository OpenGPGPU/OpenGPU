#include "../opengpu_tessellation_completion.h"
int use_completion(const u32 *words, size_t count)
{
    struct opengpu_tessellation_completion decoded;
    return opengpu_tessellation_completion_decode(words, count, &decoded);
}
