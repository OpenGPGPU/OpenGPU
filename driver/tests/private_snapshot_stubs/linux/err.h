#ifndef OPENGPU_TEST_SNAPSHOT_ERR_H
#define OPENGPU_TEST_SNAPSHOT_ERR_H
#include <stdint.h>
#define ERR_PTR(e) ((void *)(intptr_t)(e))
#define IS_ERR(p) ((uintptr_t)(p) >= (uintptr_t)-4095)
#define PTR_ERR(p) ((int)(intptr_t)(p))
#endif
