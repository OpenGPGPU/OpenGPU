/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <errno.h>
#define container_of(p,t,m) ((t *)((char *)(p)-offsetof(t,m)))
#define ARRAY_SIZE(a) (sizeof(a)/sizeof((a)[0]))
typedef uint32_t u32;
struct kref { int refs; };
struct opengpu_device { int unused; };
struct dma_fence { int unused; };
struct drm_sched_entity { int unused; };
struct drm_gem_object { int refs; };
struct opengpu_resource_binding { struct drm_gem_object *object; };
struct opengpu_vm { int unused; };
struct opengpu_render_context {
    struct kref refcount;
    struct opengpu_device *gpu;
    struct drm_sched_entity entity;
    struct dma_fence *last_fence;
    struct opengpu_resource_binding *bindings[2];
    struct opengpu_vm vm;
};
struct opengpu_file { struct opengpu_device *gpu; };
static int vm_destroys, object_puts, binding_frees, context_frees, entity_destroys;
static struct opengpu_render_context *test_context;
static struct opengpu_resource_binding *test_binding;
static void kref_get(struct kref *r) { assert(r->refs>0); r->refs++; }
static void kref_put(struct kref *r, void (*release)(struct kref *))
{ assert(r->refs>0); if (--r->refs==0) release(r); }
static void opengpu_mmu_vm_destroy(struct opengpu_device *g, struct opengpu_vm *v)
{ assert(g==test_context->gpu && v==&test_context->vm); vm_destroys++; }
static void drm_gem_object_put(struct drm_gem_object *o) { o->refs--; object_puts++; }
static void kfree(void *p)
{
    if (p==test_context) context_frees++;
    else { assert(p==test_binding); binding_frees++; }
}
static void drm_sched_entity_destroy(struct drm_sched_entity *e)
{ assert(e==&test_context->entity); entity_destroys++; }
static int opengpu_wait_fence(struct opengpu_device *g, struct dma_fence **f, bool i)
{ (void)g; assert(!i); *f=NULL; return -ETIMEDOUT; }
#include "context_lifetime_under_test.inc"

int main(void)
{
    struct opengpu_device gpu={0}; struct opengpu_file file={.gpu=&gpu};
    struct drm_gem_object object={.refs=1};
    struct opengpu_resource_binding binding={.object=&object};
    struct opengpu_render_context context={.refcount={1},.gpu=&gpu};
    test_context=&context; test_binding=&binding; context.bindings[0]=&binding;
    opengpu_context_get(&context); opengpu_context_get(&context); /* two queued jobs */
    opengpu_context_free(&file,&context); /* timeout while jobs remain */
    assert(entity_destroys==1 && context.refcount.refs==2);
    assert(!vm_destroys && !object_puts && !binding_frees && !context_frees);
    opengpu_context_put(&context); /* first job retires */
    assert(context.refcount.refs==1 && !vm_destroys && !context_frees);
    opengpu_context_put(&context); /* last free-job cleanup has run */
    assert(vm_destroys==1 && object_puts==1 && object.refs==0);
    assert(binding_frees==1 && context_frees==1);
    puts("context lifetime: timeout retains VM and bindings until last job PASS");
    return 0;
}
