/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <stdio.h>
#include "../opengpu_backing_identity.h"

struct node {
    enum opengpu_backing_kind kind;
    const void *next;
};

static enum opengpu_backing_kind step(const void *object, const void **next)
{
    const struct node *node = object;
    *next = node->next;
    return node->kind;
}

int main(void)
{
    int foreign1, foreign2;
    struct node local1 = {OPENGPU_BACKING_LOCAL, NULL};
    struct node local2 = {OPENGPU_BACKING_LOCAL, NULL};
    struct node wrap1 = {OPENGPU_BACKING_WRAPPER, &local1};
    struct node wrap2 = {OPENGPU_BACKING_WRAPPER, &wrap1};
    struct node import1 = {OPENGPU_BACKING_FOREIGN, &foreign1};
    struct node import2 = {OPENGPU_BACKING_FOREIGN, &foreign1};
    struct node import3 = {OPENGPU_BACKING_FOREIGN, &foreign2};
    struct node wrapped_import = {OPENGPU_BACKING_WRAPPER, &import1};
    struct node cycle = {OPENGPU_BACKING_WRAPPER, NULL};
    struct node missing = {OPENGPU_BACKING_WRAPPER, NULL};
    struct node bad = {OPENGPU_BACKING_INVALID, NULL};
    struct opengpu_backing_identity a, b;
    struct node chain[33];
    unsigned i;

    assert(opengpu_backing_identity_resolve(&local1, step, &a));
    assert(opengpu_backing_identity_resolve(&wrap2, step, &b));
    assert(opengpu_backing_identity_equal(&a, &b));
    assert(opengpu_backing_identity_resolve(&local2, step, &b));
    assert(!opengpu_backing_identity_equal(&a, &b));
    assert(opengpu_backing_identity_resolve(&wrapped_import, step, &a));
    assert(opengpu_backing_identity_resolve(&import2, step, &b));
    assert(opengpu_backing_identity_equal(&a, &b));
    assert(opengpu_backing_identity_resolve(&import3, step, &b));
    assert(!opengpu_backing_identity_equal(&a, &b));
    /* Distinct pointer types must not be confused even if addresses match. */
    b.object = a.object;
    b.kind = OPENGPU_BACKING_LOCAL;
    assert(!opengpu_backing_identity_equal(&a, &b));
    for (i = 0; i < 32; i++) {
        chain[i].kind = OPENGPU_BACKING_WRAPPER;
        chain[i].next = &chain[i + 1];
    }
    chain[32].kind = OPENGPU_BACKING_LOCAL;
    chain[32].next = NULL;
    assert(opengpu_backing_identity_resolve(&chain[1], step, &b));
    assert(!opengpu_backing_identity_resolve(&chain[0], step, &b));
    cycle.next = &cycle;
    assert(!opengpu_backing_identity_resolve(&cycle, step, &a));
    assert(!opengpu_backing_identity_resolve(&missing, step, &a));
    assert(!opengpu_backing_identity_resolve(&bad, step, &a));
    assert(!opengpu_backing_identity_resolve(NULL, step, &a));
    assert(!opengpu_backing_identity_resolve(&local1, NULL, &a));
    assert(!opengpu_backing_identity_resolve(&local1, step, NULL));
    puts("GEM backing identity: local, imported, wrapped, cycle and invalid cases PASS");
    return 0;
}
