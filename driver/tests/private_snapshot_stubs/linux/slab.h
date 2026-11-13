#ifndef OPENGPU_TEST_PRIVATE_SNAPSHOT_SLAB_H
#define OPENGPU_TEST_PRIVATE_SNAPSHOT_SLAB_H
#include <stdlib.h>
static int private_alloc_fail_after = -1;
static void *kzalloc(size_t bytes, int flags)
{
    (void)flags;
    if (!private_alloc_fail_after)
        return NULL;
    if (private_alloc_fail_after > 0)
        --private_alloc_fail_after;
    return calloc(1, bytes);
}
static void kfree(void *p) { free(p); }
#endif
