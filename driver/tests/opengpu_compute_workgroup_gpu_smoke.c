/* SPDX-License-Identifier: MIT */
/* Bounded local IDs and discovered limits through the real Linux ioctl. */
#include "opengpu_compute_smoke_util.h"
#define CHECK(x) do { if ((x)<0) { perror(#x); return 1; } } while (0)

int main(void)
{
    struct buffer shader={0}, kernarg={0};
    struct drm_opengpu_context context={0};
    struct drm_opengpu_compute submit={0};
    struct drm_opengpu_param items={.param=OPENGPU_PARAM_COMPUTE_LOCAL_ITEMS};
    struct drm_opengpu_param axis={.param=OPENGPU_PARAM_COMPUTE_LOCAL_AXIS};
    uint32_t *code, *result, count, vector_length;
    int fd=open("/dev/dri/card0",O_RDWR|O_CLOEXEC);
    CHECK(fd);
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_GET_PARAM,&items));
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_GET_PARAM,&axis));
    if (!items.value || !axis.value || axis.value>items.value || items.value>255) {
        fprintf(stderr,"compute limits unavailable or invalid: %llu/%llu\n",
                (unsigned long long)items.value,(unsigned long long)axis.value); return 1;
    }
    count=items.value<128 ? (uint32_t)items.value : 128;
    if (count>axis.value) count=(uint32_t)axis.value;
    vector_length=count<16 ? count : 16;
    CHECK(create_buffer(fd,128,&shader)); CHECK(create_buffer(fd,1024,&kernarg));
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_CONTEXT_CREATE,&context));
    code=shader.map; result=kernarg.map; memset(code,0,128);
    code[0]=0xc1007057u | (vector_length<<15); /* vsetivli e32,m1 */
    code[1]=0x00241293u; /* slli x5,x8,2 */
    code[2]=0x005082b3u; /* add x5,x1,x5 */
    code[3]=0x04028293u; /* addi x5,x5,64 */
    code[4]=0x0202e0a7u; /* vse32.v v1,(x5) */
    code[5]=0x30500073u;
    CHECK(bind_buffer(fd,context.id,1,&shader,OPENGPU_RESOURCE_COMPUTE_SHADER,128));
    CHECK(bind_buffer(fd,context.id,2,&kernarg,OPENGPU_RESOURCE_COMPUTE_KERNARG,1024));
    submit.context_id=context.id; submit.shader_slot=1; submit.kernarg_slot=2;
    submit.grid[0]=submit.grid[1]=submit.grid[2]=1;
    {
        uint32_t shapes[3][3]={{count,1,1},{0,0,0},{0,0,0}};
        unsigned shape_count=1;

        if (count==128) {
            shapes[1][0]=8; shapes[1][1]=4; shapes[1][2]=4;
            shapes[2][0]=33; shapes[2][1]=shapes[2][2]=1;
            shape_count=3;
        } else if (count==8) {
            shapes[1][0]=shapes[1][1]=shapes[1][2]=2;
            shape_count=2;
        }
        printf("compute workgroup smoke: discovered=%llu axis=%llu shapes=%u\n",
               (unsigned long long)items.value,(unsigned long long)axis.value,shape_count); fflush(stdout);
        for (unsigned s=0;s<shape_count;s++) {
            struct drm_syncobj_create sync={0};
            struct drm_syncobj_destroy destroy={0};
            const unsigned n=shapes[s][0]*shapes[s][1]*shapes[s][2];

            memcpy(submit.local,shapes[s],sizeof(submit.local));
            CHECK(ioctl(fd,DRM_IOCTL_SYNCOBJ_CREATE,&sync)); submit.out_syncobj=sync.handle;
            for (unsigned i=0;i<256;i++) result[i]=0xdeadbeefu;
            CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE,&submit));
            CHECK(wait_fence(fd,sync.handle));
            for (unsigned i=0;i<256;i++) {
                uint32_t expected=i>=16 && i<16+n ? i-16 : 0xdeadbeefu;
                if (result[i]!=expected) {
                    fprintf(stderr,"workgroup shape %u result[%u]=%08x expected=%08x\n",s,i,result[i],expected); return 1;
                }
            }
            destroy.handle=sync.handle; CHECK(ioctl(fd,DRM_IOCTL_SYNCOBJ_DESTROY,&destroy));
        }
    }
    submit.local[0]=(uint32_t)axis.value+1u; submit.local[1]=submit.local[2]=1; submit.out_syncobj=0;
    errno=0;
    if (ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE,&submit)!=-1 || errno!=EINVAL) {
        fprintf(stderr,"over-limit local axis was not rejected\n"); return 1;
    }
    puts("OPENGPU compute workgroup ioctl smoke: limits and IDs PASS");
    close(fd); return 0;
}
