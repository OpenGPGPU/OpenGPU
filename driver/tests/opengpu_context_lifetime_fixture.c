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
struct idr { struct opengpu_render_context *context; u32 id; };
struct opengpu_file { struct opengpu_device *gpu; struct idr contexts; int lock; };
struct drm_device { int unused; };
struct drm_file { void *driver_priv; };
struct drm_opengpu_context { u32 id, flags; };
static void mutex_lock(int *lock) { assert(!*lock); *lock=1; }
static void mutex_unlock(int *lock) { assert(*lock); *lock=0; }
static struct opengpu_render_context *idr_remove(struct idr *id, u32 key)
{ struct opengpu_render_context *c=key==id->id?id->context:NULL; if(c)id->context=NULL; return c; }
static int destroy_error;
static int vm_destroys, object_puts, binding_frees, context_frees, entity_destroys;
static struct opengpu_render_context *test_context;
static struct opengpu_resource_binding *test_binding;
static void kref_get(struct kref *r) { assert(r->refs>0); r->refs++; }
static void kref_put(struct kref *r, void (*release)(struct kref *))
{ assert(r->refs>0); if (--r->refs==0) release(r); }
static int opengpu_mmu_vm_destroy(struct opengpu_device *g, struct opengpu_vm *v)
{ assert(g==test_context->gpu && v==&test_context->vm); vm_destroys++; return destroy_error; }
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
#include "context_destroy_under_test.inc"

int main(void)
{
    struct opengpu_device gpu={0}; struct opengpu_file file={.gpu=&gpu};
    struct drm_gem_object object={.refs=1};
    struct opengpu_resource_binding binding={.object=&object};
    struct opengpu_render_context context={.refcount={1},.gpu=&gpu};
    test_context=&context; test_binding=&binding; context.bindings[0]=&binding;
    opengpu_context_get(&context); opengpu_context_get(&context); /* two queued jobs */
    file.contexts=(struct idr){&context,17};
    struct drm_device drm={0};struct drm_file client={.driver_priv=&file};
    struct drm_opengpu_context args={.id=17,.flags=1};
    assert(opengpu_compute_context_destroy_ioctl(&drm,&args,&client)==-EINVAL);
    assert(file.contexts.context==&context && !entity_destroys);
    args.flags=0;
    assert(!opengpu_compute_context_destroy_ioctl(&drm,&args,&client)); /* bounded wait times out */
    assert(!file.contexts.context && !file.lock); /* namespace revoked, not retired */
    assert(opengpu_compute_context_destroy_ioctl(&drm,&args,&client)==-ENOENT);
    assert(entity_destroys==1 && context.refcount.refs==2);
    assert(!vm_destroys && !object_puts && !binding_frees && !context_frees);
    opengpu_context_put(&context); /* first job retires */
    assert(context.refcount.refs==1 && !vm_destroys && !context_frees);
    opengpu_context_put(&context); /* last free-job cleanup has run */
    assert(vm_destroys==1 && object_puts==1 && object.refs==0);
    assert(binding_frees==1 && context_frees==1);
    context=(struct opengpu_render_context){.refcount={1},.gpu=&gpu};
    object.refs=1;context.bindings[0]=&binding;destroy_error=-EIO;
    opengpu_context_put(&context);
    assert(context.refcount.refs==0 && vm_destroys==2);
    assert(object.refs==1 && object_puts==1 && binding_frees==1 && context_frees==1);
    puts("context lifetime: actual namespace-delete success/timeout retains VM and bindings until last job PASS");
    return 0;
}
