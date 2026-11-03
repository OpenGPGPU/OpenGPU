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
    struct opengpu_buffer shader={0}, kernarg={0}, backing={0}, texture={0};
    uint32_t context=0, sync=0;
    uint64_t features=0;
    struct drm_opengpu_compute_resources submit={0};
    struct drm_opengpu_compute_texture_view view={0};
    uint32_t *result, *data, *pixels;
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
    CHECK(opengpu_buffer_create(fd,8192,&backing)); CHECK(opengpu_buffer_create(fd,64,&texture)); CHECK(opengpu_context_create(fd,&context));
    CHECK(opengpu_sync_create(fd,&sync));
    result=kernarg.map; data=backing.map; pixels=texture.map;
    shader_file=fopen(shader_path,"rb");
    if (!shader_file) { perror(shader_path); goto done; }
    program_size=fread(program,1,sizeof(program),shader_file);
    if (ferror(shader_file) || !program_size || program_size>128 || (program_size&3)) {
        fprintf(stderr,"invalid compute resources shader image\n"); goto done;
    }
    fclose(shader_file); shader_file=NULL;
    memset(shader.map,0,128); memcpy(shader.map,program,program_size); memset(result,0,64); memset(data,0,64); memset(pixels,0,64); result[2]=7; pixels[5]=0x12345678u;
    CHECK(bind_buffer(fd,context,1,&shader,OPENGPU_RESOURCE_COMPUTE_SHADER,128));
    CHECK(bind_buffer(fd,context,2,&kernarg,OPENGPU_RESOURCE_COMPUTE_KERNARG,64));
    CHECK(bind_buffer(fd,context,13,&backing,OPENGPU_RESOURCE_COMPUTE_STORAGE,8192));
    errno=0;
    if (bind_buffer(fd,context,35,&backing,OPENGPU_RESOURCE_COMPUTE_STORAGE,64)!=-1 || errno!=EBUSY) {
        fprintf(stderr,"storage slot alias was accepted\n"); goto done;
    }
    CHECK(opengpu_unbind(fd,context,13));
    CHECK(bind_buffer(fd,context,35,&backing,OPENGPU_RESOURCE_COMPUTE_STORAGE,8192));
    CHECK(bind_buffer(fd,context,34,&texture,OPENGPU_RESOURCE_COMPUTE_TEXTURE,64));
    submit.launch.context_id=context; submit.launch.shader_slot=1; submit.launch.kernarg_slot=2;
    submit.launch.grid[0]=submit.launch.grid[1]=submit.launch.grid[2]=1;
    submit.launch.local[0]=submit.launch.local[1]=submit.launch.local[2]=1;
    submit.launch.out_syncobj=sync;
    view.slot=34; view.format=14; view.width=4; view.height=4; view.depth=1; view.layers=1; view.level_count=1;
    view.levels[0].width=4; view.levels[0].height=4; view.levels[0].depth=1; view.levels[0].layers=1;
    view.levels[0].row_pitch=16; view.levels[0].block_bytes=4;
    submit.views=(uintptr_t)&view; submit.view_count=1;
    submit.bindings[0]=(struct drm_opengpu_compute_storage_binding){35,OPENGPU_COMPUTE_STORAGE_READ|OPENGPU_COMPUTE_STORAGE_WRITE};
    CHECK(bind_buffer(fd,context,35,&kernarg,OPENGPU_RESOURCE_COMPUTE_STORAGE,64));
    errno=0;
    if (opengpu_compute_resources(fd,&submit)!=-1 || errno!=EINVAL) {
        fprintf(stderr,"writable kernarg alias was accepted\n"); goto done;
    }
    CHECK(bind_buffer(fd,context,35,&texture,OPENGPU_RESOURCE_COMPUTE_STORAGE,64));
    errno=0;
    if (opengpu_compute_resources(fd,&submit)!=-1 || errno!=EINVAL) {
        fprintf(stderr,"texture/storage alias was accepted\n"); goto done;
    }
    CHECK(bind_buffer(fd,context,35,&backing,OPENGPU_RESOURCE_COMPUTE_STORAGE,64));
    puts("resources smoke: submitting"); fflush(stdout);
    CHECK(opengpu_compute_resources(fd,&submit));
    puts("resources smoke: waiting for completion"); fflush(stdout);
    CHECK(opengpu_sync_wait_success(fd,sync,OPENGPU_FENCE_TIMEOUT_MS));
    if (result[0]!=7 || result[1]!=4 || data[0]!=7 || result[2]!=8 || result[3]!=7 || result[4]!=0x12345678u) {
        fprintf(stderr,"storage=%08x size=%08x backing=%08x atomic=%08x old=%08x texel=%08x\n",
                result[0],result[1],data[0],result[2],result[3],result[4]); goto done;
    }
    puts("OPENGPU compute resources ioctl smoke: texture, storage and atomic PASS");
    status=0;
done:
    if (shader_file) fclose(shader_file);
    if (sync) opengpu_sync_destroy(fd,sync);
    if (context) opengpu_context_destroy(fd,context);
    if (texture.handle) opengpu_buffer_destroy(fd,&texture);
    if (backing.handle) opengpu_buffer_destroy(fd,&backing);
    if (kernarg.handle) opengpu_buffer_destroy(fd,&kernarg);
    if (shader.handle) opengpu_buffer_destroy(fd,&shader);
    if (fd>=0) close(fd);
    return status;
}
