/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <stdbool.h>
#include <stdio.h>
#include <errno.h>

#define ERESTARTSYS 512
#define OPENGPU_DRAW_WAIT_MS 100
struct opengpu_device { int unused; };
struct dma_fence { bool signaled; int refs; };
static int next_result, waits;
static bool signal_during_wait;
static int opengpu_hw_fence_wait(struct opengpu_device *gpu,
                                 struct dma_fence *fence,
                                 unsigned int timeout, bool interruptible)
{
    (void)gpu; (void)timeout; (void)interruptible;
    waits++;
    if (signal_during_wait) fence->signaled = true;
    return next_result;
}
static bool dma_fence_is_signaled(struct dma_fence *fence) { return fence->signaled; }
static void dma_fence_put(struct dma_fence *fence) { fence->refs--; }
#include "context_fence_wait_under_test.inc"

int main(void)
{
    struct opengpu_device gpu={0};
    struct dma_fence fence={.refs=1}, *last=&fence;

    next_result=-ERESTARTSYS;
    assert(opengpu_wait_fence(&gpu,&last,true)==-ERESTARTSYS);
    assert(last==&fence && fence.refs==1 && waits==1);
    next_result=-ETIMEDOUT;
    assert(opengpu_wait_fence(&gpu,&last,true)==-ETIMEDOUT);
    assert(last==&fence && fence.refs==1 && waits==2);
    next_result=0; signal_during_wait=true;
    assert(opengpu_wait_fence(&gpu,&last,true)==0);
    assert(!last && fence.refs==0 && waits==3);
    assert(opengpu_wait_fence(&gpu,&last,true)==0 && waits==3);

    fence=(struct dma_fence){.signaled=true,.refs=1}; last=&fence;
    signal_during_wait=false; next_result=-EIO;
    assert(opengpu_wait_fence(&gpu,&last,true)==-EIO);
    assert(!last && fence.refs==0);

    fence=(struct dma_fence){.refs=1}; last=&fence;
    signal_during_wait=true; next_result=-ERESTARTSYS;
    assert(opengpu_wait_fence(&gpu,&last,true)==-ERESTARTSYS);
    assert(!last && fence.refs==0); /* completion raced interruption */
    fence=(struct dma_fence){.refs=1}; last=&fence;
    signal_during_wait=false; next_result=-ETIMEDOUT;
    assert(opengpu_wait_fence(&gpu,&last,false)==-ETIMEDOUT);
    assert(!last && fence.refs==0); /* teardown releases its reference */
    puts("context fence wait: interrupted and timed-out jobs remain protected PASS");
    return 0;
}
