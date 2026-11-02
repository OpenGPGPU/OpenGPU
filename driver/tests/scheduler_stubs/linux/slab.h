#ifndef TEST_SLAB_H
#define TEST_SLAB_H
#include <stdlib.h>
#define GFP_KERNEL 0
static int test_alloc_fail;
static void *kcalloc(size_t n, size_t size, int flags)
{ (void)flags; return test_alloc_fail ? NULL : calloc(n, size); }
static void kfree(void *p) { free(p); }
#endif
