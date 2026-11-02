/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_BACKING_IDENTITY_H
#define OPENGPU_BACKING_IDENTITY_H
#ifdef __KERNEL__
#include <linux/types.h>
#else
#include <stdbool.h>
#endif

/* An imported GEM can wrap a dma-buf exported by another OpenGPU device.
 * Follow those wrappers to the actual local GEM or foreign dma-buf identity.
 * Distinct foreign dma-bufs may still cover the same pages. */
enum opengpu_backing_kind {
    OPENGPU_BACKING_LOCAL,
    OPENGPU_BACKING_FOREIGN,
    OPENGPU_BACKING_WRAPPER,
    OPENGPU_BACKING_INVALID,
};

struct opengpu_backing_identity {
    const void *object;
    enum opengpu_backing_kind kind;
};

typedef enum opengpu_backing_kind (*opengpu_backing_resolve_fn)(
    const void *object, const void **next);

static inline bool opengpu_backing_identity_resolve(
    const void *object, opengpu_backing_resolve_fn resolve,
    struct opengpu_backing_identity *identity)
{
    unsigned int i;

    if (!object || !resolve || !identity)
        return false;
    for (i = 0; i < 32; i++) {
        const void *next = NULL;
        enum opengpu_backing_kind kind = resolve(object, &next);

        if (kind == OPENGPU_BACKING_LOCAL) {
            identity->object = object;
            identity->kind = kind;
            return true;
        }
        if (kind == OPENGPU_BACKING_FOREIGN && next) {
            identity->object = next;
            identity->kind = kind;
            return true;
        }
        if (kind != OPENGPU_BACKING_WRAPPER || !next)
            return false;
        object = next;
    }
    return false;
}

static inline bool opengpu_backing_identity_equal(
    const struct opengpu_backing_identity *a,
    const struct opengpu_backing_identity *b)
{
    return a->kind == b->kind && a->object == b->object;
}
#endif
