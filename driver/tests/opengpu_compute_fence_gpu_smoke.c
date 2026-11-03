/* SPDX-License-Identifier: MIT */
/* One-lane ordinary compute fence through the Linux submission path. */
#include "opengpu_compute_smoke_util.h"
#define CHECK(x) do { if ((x)<0) { perror(#x); return 1; } } while (0)
int main(void)
{
    struct buffer shader={0}, kernarg={0};
    struct drm_opengpu_context context={0}; struct drm_syncobj_create sync={0};
    struct drm_opengpu_compute submit={0};
    uint32_t *code, *result;
    int fd=open("/dev/dri/card0",O_RDWR|O_CLOEXEC);
    CHECK(fd); CHECK(create_buffer(fd,128,&shader)); CHECK(create_buffer(fd,64,&kernarg));
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_CONTEXT_CREATE,&context));
    CHECK(ioctl(fd,DRM_IOCTL_SYNCOBJ_CREATE,&sync));
    code=shader.map; result=kernarg.map; memset(code,0,128); memset(result,0,64);
    code[0]=0x00700113u; /* addi x2,x0,7 */
    code[1]=0x0020a023u; /* sw x2,0(x1) */
    code[2]=0x0330000fu; /* fence rw,rw */
    code[3]=0x0000a183u; /* lw x3,0(x1) */
    code[4]=0x0030a223u; /* sw x3,4(x1) */
    code[5]=0x30500073u; /* cease */
    CHECK(bind_buffer(fd,context.id,1,&shader,OPENGPU_RESOURCE_COMPUTE_SHADER,128));
    CHECK(bind_buffer(fd,context.id,2,&kernarg,OPENGPU_RESOURCE_COMPUTE_KERNARG,64));
    submit.context_id=context.id; submit.shader_slot=1; submit.kernarg_slot=2;
    submit.grid[0]=submit.grid[1]=submit.grid[2]=1;
    submit.local[0]=submit.local[1]=submit.local[2]=1;
    submit.out_syncobj=sync.handle;
    puts("compute fence smoke: submitting"); fflush(stdout);
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE,&submit));
    CHECK(wait_fence(fd,sync.handle));
    if (result[0]!=7 || result[1]!=7) {
        fprintf(stderr,"fence store=%x load=%x\n",result[0],result[1]); return 1;
    }
    puts("OPENGPU compute fence ioctl smoke: store and load PASS");
    close(fd); return 0;
}
