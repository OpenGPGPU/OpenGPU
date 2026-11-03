/* SPDX-License-Identifier: MIT */
/* Small combined compute example; optional DRM node and shader image. */
#include "../opengpu.h"
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static int bind_buffer(int fd, uint32_t ctx, uint32_t slot,
                       const struct opengpu_buffer *b, uint32_t type, uint64_t size)
{
    struct drm_opengpu_resource r={.context_id=ctx,.slot=slot,.handle=b->handle,
        .type=type,.size=size};
    return opengpu_bind(fd,&r);
}
#define CHECK(x) do { if ((x)<0) { perror(#x); goto done; } } while (0)
int main(int argc, char **argv)
{
    struct opengpu_buffer shader={0}, kernarg={0}, backing[4]={{0}}, texture[2]={{0}};
    uint32_t context=0, sync=0;
    uint64_t features=0;
    struct drm_opengpu_compute_resources submit={0};
    struct drm_opengpu_compute_texture_view view[2]={{0}};
    uint32_t *result;
    const char *shader_path=argc>2 ? argv[2] : "/opengpu_compute_resources.bin";
    uint8_t program[129]; size_t program_size; FILE *shader_file=NULL;
    int status=1, fd=opengpu_open(argc > 1 ? argv[1] : NULL);
    CHECK(fd); CHECK(opengpu_driver_features(fd,&features));
    if ((features & (OPENGPU_DRIVER_FEATURE_COMPUTE_RESOURCES |
                           OPENGPU_DRIVER_FEATURE_COMPUTE_WORD_ATOMICS)) !=
        (OPENGPU_DRIVER_FEATURE_COMPUTE_RESOURCES | OPENGPU_DRIVER_FEATURE_COMPUTE_WORD_ATOMICS)) {
        fprintf(stderr,"compute resources or word atomic driver feature missing\n"); goto done;
    }
    CHECK(opengpu_buffer_create(fd,128,&shader)); CHECK(opengpu_buffer_create(fd,64,&kernarg));
    CHECK(opengpu_buffer_create(fd,8192,&backing[0]));
    for (unsigned i=1;i<4;i++) CHECK(opengpu_buffer_create(fd,64,&backing[i]));
    for (unsigned i=0;i<2;i++) CHECK(opengpu_buffer_create(fd,64,&texture[i]));
    CHECK(opengpu_context_create(fd,&context));
    CHECK(opengpu_sync_create(fd,&sync));
    result=kernarg.map;
    shader_file=fopen(shader_path,"rb");
    if (!shader_file) { perror(shader_path); goto done; }
    program_size=fread(program,1,sizeof(program),shader_file);
    if (ferror(shader_file) || !program_size || program_size>128 || (program_size&3)) {
        fprintf(stderr,"invalid compute resources shader image\n"); goto done;
    }
    fclose(shader_file); shader_file=NULL;
    memset(shader.map,0,128); memcpy(shader.map,program,program_size); memset(result,0,64);
    for (unsigned i=0;i<4;i++) memset(backing[i].map,0,64);
    for (unsigned i=0;i<2;i++) memset(texture[i].map,0,64);
    result[7]=7; ((uint32_t *)texture[1].map)[5]=0x12345678u;
    CHECK(bind_buffer(fd,context,1,&shader,OPENGPU_RESOURCE_COMPUTE_SHADER,128));
    CHECK(bind_buffer(fd,context,2,&kernarg,OPENGPU_RESOURCE_COMPUTE_KERNARG,64));
    CHECK(bind_buffer(fd,context,13,&backing[0],OPENGPU_RESOURCE_COMPUTE_STORAGE,8192));
    errno=0;
    if (bind_buffer(fd,context,35,&backing[0],OPENGPU_RESOURCE_COMPUTE_STORAGE,64)!=-1 || errno!=EBUSY) {
        fprintf(stderr,"storage slot alias was accepted\n"); goto done;
    }
    CHECK(opengpu_unbind(fd,context,13));
    CHECK(bind_buffer(fd,context,35,&backing[0],OPENGPU_RESOURCE_COMPUTE_STORAGE,8192));
    for (unsigned i=0;i<2;i++)
        CHECK(bind_buffer(fd,context,33+i,&texture[i],OPENGPU_RESOURCE_COMPUTE_TEXTURE,64));
    submit.launch.context_id=context; submit.launch.shader_slot=1; submit.launch.kernarg_slot=2;
    submit.launch.grid[0]=submit.launch.grid[1]=submit.launch.grid[2]=1;
    submit.launch.local[0]=4; submit.launch.local[1]=submit.launch.local[2]=1;
    submit.launch.out_syncobj=sync;
    for (unsigned i=0;i<2;i++) {
        uint32_t side=i ? 4 : 2;
        view[i].slot=33+i; view[i].format=14; view[i].width=side; view[i].height=side;
        view[i].depth=1; view[i].layers=1; view[i].level_count=1;
        view[i].levels[0].width=side; view[i].levels[0].height=side; view[i].levels[0].depth=1;
        view[i].levels[0].layers=1; view[i].levels[0].row_pitch=side*4; view[i].levels[0].block_bytes=4;
    }
    submit.views=(uintptr_t)view; submit.view_count=2;
    for (unsigned i=0;i<4;i++)
        submit.bindings[i]=(struct drm_opengpu_compute_storage_binding){35+i,OPENGPU_COMPUTE_STORAGE_READ|OPENGPU_COMPUTE_STORAGE_WRITE};
    for (unsigned i=1;i<4;i++)
        CHECK(bind_buffer(fd,context,35+i,&backing[i],OPENGPU_RESOURCE_COMPUTE_STORAGE,64));
    CHECK(bind_buffer(fd,context,35,&kernarg,OPENGPU_RESOURCE_COMPUTE_STORAGE,64));
    errno=0;
    if (opengpu_compute_resources(fd,&submit)!=-1 || errno!=EINVAL) {
        fprintf(stderr,"writable kernarg alias was accepted\n"); goto done;
    }
    CHECK(bind_buffer(fd,context,35,&backing[0],OPENGPU_RESOURCE_COMPUTE_STORAGE,64));
    puts("resources smoke: submitting"); fflush(stdout);
    CHECK(opengpu_compute_resources(fd,&submit));
    puts("resources smoke: waiting for completion"); fflush(stdout);
    CHECK(opengpu_sync_wait_success(fd,sync,OPENGPU_FENCE_TIMEOUT_MS));
    for (unsigned i=0;i<4;i++) {
        uint32_t data=((uint32_t *)backing[i].map)[0];
        if (result[i]!=7+i || data!=7+i) {
            fprintf(stderr,"lane %u load=%08x backing=%08x\n",i,result[i],data); goto done;
        }
    }
    if (result[4]!=2 || result[5]!=4 || result[6]!=0x12345678u || result[7]!=8 || result[8]!=7) {
        fprintf(stderr,"sizes=%u,%u texel=%08x atomic=%u old=%u\n",
                result[4],result[5],result[6],result[7],result[8]); goto done;
    }
    puts("OPENGPU compute resources ioctl smoke: texture, storage and atomic PASS");
    status=0;
done:
    if (shader_file) fclose(shader_file);
    if (sync) opengpu_sync_destroy(fd,sync);
    if (context) opengpu_context_destroy(fd,context);
    for (unsigned i=0;i<2;i++) if (texture[i].handle) opengpu_buffer_destroy(fd,&texture[i]);
    for (unsigned i=0;i<4;i++) if (backing[i].handle) opengpu_buffer_destroy(fd,&backing[i]);
    if (kernarg.handle) opengpu_buffer_destroy(fd,&kernarg);
    if (shader.handle) opengpu_buffer_destroy(fd,&shader);
    if (fd>=0) close(fd);
    return status;
}
