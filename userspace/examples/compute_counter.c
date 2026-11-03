/* SPDX-License-Identifier: MIT */
/* Bounded counter ioctl smoke: four lanes, one counter, no rendered frames. */
#include "../opengpu.h"
#include "compute_counter_program.h"
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define CHECK(expr) do { if ((expr) < 0) { perror(#expr); goto done; } } while (0)
#define EXPECT_EINVAL(expr) do { \
    errno = 0; \
    if ((expr) != -1 || errno != EINVAL) { \
        fprintf(stderr, "%s was not rejected with EINVAL\n", #expr); goto done; \
    } \
} while (0)

static int bind_buffer(int fd, uint32_t context, uint32_t slot,
                       const struct opengpu_buffer *buffer, uint32_t type)
{
    struct drm_opengpu_resource binding = { .context_id=context, .slot=slot,
        .handle=buffer->handle, .type=type, .size=64 };
    return opengpu_bind(fd, &binding);
}

static int range_permutation(const uint32_t *words, uint32_t first)
{
    unsigned mask = 0;
    for (unsigned i=0; i<4; ++i) {
        if (words[i] < first || words[i] >= first+4) return 0;
        mask |= 1u << (words[i]-first);
    }
    return mask == 15;
}

int main(int argc, char **argv)
{
    struct opengpu_buffer shader={0}, kernarg={0}, counter={0};
    struct drm_opengpu_compute_counter_resources submit={0};
    uint32_t context=0, fence=0, *result=NULL, *values=NULL;
    uint64_t features=0;
    int status=1, fd=opengpu_open(argc > 1 ? argv[1] : NULL);
    CHECK(fd);
    CHECK(opengpu_driver_features(fd, &features));
    if (!(features & OPENGPU_DRIVER_FEATURE_COMPUTE_ATOMIC_COUNTERS)) {
        fprintf(stderr, "atomic counter submission is not advertised\n");
        goto done;
    }
    CHECK(opengpu_context_create(fd,&context));
    CHECK(opengpu_buffer_create(fd,64,&shader));
    CHECK(opengpu_buffer_create(fd,64,&kernarg));
    CHECK(opengpu_buffer_create(fd,64,&counter));
    CHECK(opengpu_sync_create(fd,&fence));
    memset(shader.map,0,64); memcpy(shader.map,opengpu_counter_smoke_program,sizeof(opengpu_counter_smoke_program));
    result=kernarg.map; values=counter.map;
    for (unsigned i=0;i<16;++i) result[i]=values[i]=0xa5a5a5a5u;
    values[1]=7;
    CHECK(bind_buffer(fd,context,1,&shader,OPENGPU_RESOURCE_COMPUTE_SHADER));
    CHECK(bind_buffer(fd,context,2,&kernarg,OPENGPU_RESOURCE_COMPUTE_KERNARG));
    CHECK(bind_buffer(fd,context,13,&counter,OPENGPU_RESOURCE_COMPUTE_STORAGE));
    submit.launch.context_id=context; submit.launch.shader_slot=1; submit.launch.kernarg_slot=2;
    submit.launch.grid[0]=submit.launch.grid[1]=submit.launch.grid[2]=1;
    submit.launch.local[0]=4; submit.launch.local[1]=submit.launch.local[2]=1;
    submit.launch.out_syncobj=fence;
    submit.counter=(struct drm_opengpu_compute_counter_binding){.offset=4,.slot=13,.count=1};

    submit.counter.offset=2; EXPECT_EINVAL(opengpu_compute_counter_resources(fd,&submit));
    submit.counter.offset=4; submit.counter.count=9;
    EXPECT_EINVAL(opengpu_compute_counter_resources(fd,&submit));
    submit.counter.count=1; submit.resource_flags=4;
    EXPECT_EINVAL(opengpu_compute_counter_resources(fd,&submit));
    submit.resource_flags=0;
    CHECK(bind_buffer(fd,context,13,&kernarg,OPENGPU_RESOURCE_COMPUTE_STORAGE));
    EXPECT_EINVAL(opengpu_compute_counter_resources(fd,&submit));
    CHECK(bind_buffer(fd,context,13,&counter,OPENGPU_RESOURCE_COMPUTE_STORAGE));

    puts("counter smoke: submitting four lanes"); fflush(stdout);
    CHECK(opengpu_compute_counter_resources(fd,&submit));
    CHECK(opengpu_sync_wait_success(fd,fence,OPENGPU_FENCE_TIMEOUT_MS));
    if (!range_permutation(result,7) || !range_permutation(result+8,7) || values[1]!=7) {
        fprintf(stderr,"unexpected increment/decrement results or final counter %u\n", values[1]);
        goto done;
    }
    for (unsigned i=0;i<4;++i) if (result[4+i]!=11) {
        fprintf(stderr,"counter read lane %u returned %u\n",i,result[4+i]); goto done;
    }
    for (unsigned i=12;i<16;++i) if (result[i]!=0xa5a5a5a5u) {
        fprintf(stderr,"kernarg guard %u changed\n",i); goto done;
    }
    for (unsigned i=0;i<16;++i) if (i!=1 && values[i]!=0xa5a5a5a5u) {
        fprintf(stderr,"counter guard %u changed\n",i); goto done;
    }
    /* Index 1 is outside the one-counter view: no lane may update memory. */
    ((uint32_t *)shader.map)[1] |= 1u << 15;
    CHECK(opengpu_compute_counter_resources(fd,&submit));
    errno=0;
    if (opengpu_sync_wait_success(fd,fence,OPENGPU_FENCE_TIMEOUT_MS)!=-1 || errno!=EIO) {
        fprintf(stderr,"out-of-range counter did not fail its fence\n"); goto done;
    }
    {
        struct drm_opengpu_fault fault={0};
        CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_GET_FAULT,&fault));
        if ((fault.flags & (OPENGPU_FAULT_VALID|OPENGPU_FAULT_COMPLETION_ERROR)) !=
            (OPENGPU_FAULT_VALID|OPENGPU_FAULT_COMPLETION_ERROR) || fault.error>=0 || values[1]!=7) {
            fprintf(stderr,"counter fault: flags=%x error=%d value=%u\n",
                    fault.flags,fault.error,values[1]); goto done;
        }
    }
    ((uint32_t *)shader.map)[1] = opengpu_counter_smoke_program[1];
    CHECK(opengpu_compute_counter_resources(fd,&submit));
    CHECK(opengpu_sync_wait_success(fd,fence,OPENGPU_FENCE_TIMEOUT_MS));
    if (!range_permutation(result,7) || !range_permutation(result+8,7) || values[1]!=7) {
        fprintf(stderr,"counter did not recover after a fault\n"); goto done;
    }
    for (unsigned i=0;i<4;++i) if (result[4+i]!=11) {
        fprintf(stderr,"counter read failed after recovery\n"); goto done;
    }
    for (unsigned i=0;i<16;++i) if ((i!=1 && values[i]!=0xa5a5a5a5u) ||
                                      (i>=12 && result[i]!=0xa5a5a5a5u)) {
        fprintf(stderr,"guard changed during fault recovery\n"); goto done;
    }
    puts("OPENGPU compute counter ioctl smoke: operations, fault and recovery PASS");
    status=0;
done:
    if (fence) opengpu_sync_destroy(fd,fence);
    if (context) opengpu_context_destroy(fd,context);
    if (counter.handle) opengpu_buffer_destroy(fd,&counter);
    if (kernarg.handle) opengpu_buffer_destroy(fd,&kernarg);
    if (shader.handle) opengpu_buffer_destroy(fd,&shader);
    if (fd>=0) close(fd);
    return status;
}
