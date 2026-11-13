#ifndef OPENGPU_TEST_PRIVATE_SNAPSHOT_VMALLOC_H
#define OPENGPU_TEST_PRIVATE_SNAPSHOT_VMALLOC_H
#include <linux/slab.h>
static void *kvzalloc(size_t bytes,int flags) { return kzalloc(bytes,flags); }
#ifndef kvfree
static void kvfree(void *p) { kfree(p); }
#endif
#endif
