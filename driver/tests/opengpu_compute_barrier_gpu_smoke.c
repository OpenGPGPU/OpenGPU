/* SPDX-License-Identifier: MIT */
/* Two-warp reconvergent workgroup barrier through the Linux submission path. */
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
    code[0]=0x00241293u; /* slli x5,x8,2: first local ID in this warp */
    code[1]=0x005082b3u; /* add x5,x1,x5 */
    code[2]=0xc100f057u; /* one vector lane per warp */
    code[3]=0x5e03b157u; /* vmv.v.i v2,7 */
    code[4]=0x0202e127u; /* each warp stores its own word */
    code[5]=0x00040463u; /* warp zero skips a nop */
    code[6]=0x00000013u;
    code[7]=0x0000000bu; /* reconverged workgroup barrier */
    code[8]=0x0000a183u; /* both warps read warp zero's word */
    code[9]=0x5e01c1d7u; /* vmv.v.x v3,x3 */
    code[10]=0x00428293u; /* addi x5,x5,4 */
    code[11]=0x0202e1a7u; /* each writes its observed value */
    code[12]=0x00040463u; /* reconverge again before second barrier */
    code[13]=0x00000013u;
    code[14]=0x0000000bu;
    code[15]=0x30500073u; /* cease */
    CHECK(bind_buffer(fd,context.id,1,&shader,OPENGPU_RESOURCE_COMPUTE_SHADER,128));
    CHECK(bind_buffer(fd,context.id,2,&kernarg,OPENGPU_RESOURCE_COMPUTE_KERNARG,64));
    submit.context_id=context.id; submit.shader_slot=1; submit.kernarg_slot=2;
    submit.grid[0]=submit.grid[1]=submit.grid[2]=1;
    submit.local[0]=8; submit.local[1]=submit.local[2]=1;
    submit.out_syncobj=sync.handle;
    puts("compute barrier smoke: submitting"); fflush(stdout);
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE,&submit));
    CHECK(wait_fence(fd,sync.handle));
    if (result[0]!=7 || result[1]!=7 || result[4]!=7 || result[5]!=7) {
        fprintf(stderr,"barrier results=%x,%x,%x,%x\n",result[0],result[1],result[4],result[5]); return 1;
    }
    puts("OPENGPU compute barrier ioctl smoke: two warps PASS");
    close(fd); return 0;
}
