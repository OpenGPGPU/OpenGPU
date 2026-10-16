/* SPDX-License-Identifier: MIT */
/* Native DRM regression: default CPU-visible staging, explicit transient
 * input omission, identical pixels, and capability fallback. */
#include "../opengpu.h"
#include "../gallium/opengpu_shader_abi.h"
#include "../gallium/opengpu_shaders.h"
#include "../../driver/opengpu_shader_resources.h"
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define CHECK(x) do { if ((x) < 0) { perror(#x); return 1; } } while (0)
#define WORDS(x) (sizeof(x) / sizeof((x)[0]))
#define SIDE 64u
#define RED 0xff0000ffu

static int bind_buffer(int fd, uint32_t context, uint32_t slot,
                       uint32_t type, const struct opengpu_buffer *bo)
{
    struct drm_opengpu_resource r = {
        .context_id = context, .slot = slot, .type = type,
        .handle = bo->handle, .size = bo->size,
    };
    return opengpu_bind(fd, &r);
}

static int fill(int fd, uint32_t context, struct opengpu_buffer *bo,
                uint32_t pattern, uint32_t sync)
{
    struct drm_opengpu_fill f = {
        .context_id = context, .destination_handle = bo->handle,
        .bytes = SIDE * SIDE * 4u, .pattern = pattern, .out_syncobj = sync,
    };
    if (opengpu_fill(fd, &f)) return -1;
    return opengpu_sync_wait_success(fd, sync, 1800000);
}

static int rejected(int fd, struct drm_opengpu_submit *s)
{
    errno = 0;
    if (opengpu_render(fd, s) == -1 && errno == EINVAL) return 0;
    fprintf(stderr, "transient staging accepted invalid flags/state (errno=%d)\n", errno);
    return -1;
}

#define SCRATCH 0x5a6b7c8du
static int scratch_check(const struct opengpu_buffer *bo,uint32_t config,
                          uint32_t bank_stride,int omitted,int omitted_outputs) {
    uint32_t cap=opengpu_shader_resource_batch(config), stride=4*cap;
    uint32_t banks=opengpu_shader_resource_banks(config);
    unsigned changed[6]={0}, output_changed[2]={0};
    for(unsigned bank=0;bank<banks;bank++) {
        const uint32_t *p=(const uint32_t *)((const char *)bo->map+bank*bank_stride);
        for(unsigned slice=0;slice<6;slice++)for(unsigned lane=0;lane<cap;lane++) {
            if(p[slice*cap+lane]!=SCRATCH)changed[slice]++;
            if(omitted && slice!=3 && p[slice*cap+lane]!=SCRATCH) {
                fprintf(stderr,"omittedinputchanged bank=%u slice=%u lane=%u\n",bank,slice,lane);return -1;
            }
        }
        for (unsigned slice=7;slice<9;slice++) for (unsigned lane=0;lane<cap;lane++) {
            if (p[slice*cap+lane]!=SCRATCH) output_changed[slice-7]++;
            if (omitted_outputs && p[slice*cap+lane]!=SCRATCH) {
                fprintf(stderr,"omitted output changed bank=%u slice=%u lane=%u\n",bank,slice,lane);return -1;
            }
        }
        for(unsigned byte=9*stride;byte<bank_stride;byte+=4)
            if(p[byte/4]!=SCRATCH) {fprintf(stderr,"uniformchanged bank=%u\n",bank);return -1;}
    }
    for(unsigned slice=0;slice<6;slice++)
        if((!omitted || slice==3) && !changed[slice]) {
            fprintf(stderr,"requiredinputnotstaged slice=%u\n",slice);return -1;
        }
    if (!omitted_outputs && (!output_changed[0] || !output_changed[1])) {
        fputs("default depth/control were not initialized\n",stderr);return -1;
    }
    return 0;
}
int main(void) {
    struct opengpu_buffer command={0},color={0},vb={0},vs={0},fs={0},vk={0},fk={0};
    struct vertex {int32_t x,y,z,w;uint32_t color,depth,u,v;} vertices[]={
        {-65536,-65536,0,65536,RED,16,100,200},
        {65536,-65536,0,65536,RED,16,300,400},
        {-65536,65536,0,65536,RED,16,500,600}};
    uint64_t caps,features;uint32_t config,context,sync,fstride,vstride;size_t words;
    uint32_t baseline[SIDE*SIDE];unsigned covered=0;int fd=opengpu_open(NULL);CHECK(fd);
    CHECK(opengpu_driver_features(fd,&features));CHECK(opengpu_capabilities(fd,&caps));
    if(!(features&OPENGPU_DRIVER_FEATURE_TRANSIENT_FRAGMENT_STAGING)) {
        puts("OPENGPU TRANSIENT STAGING: unsupported driver");return 77;
    }
    CHECK(opengpu_shader_config(fd,&config));CHECK(opengpu_context_create(fd,&context));CHECK(opengpu_sync_create(fd,&sync));
    fstride=opengpu_shader_fragment_bank_stride(config);vstride=opengpu_shader_vertex_kernarg_bytes(config);
    /* Small non-line-aligned configurations retain the legacy word path. */
    unsigned capacity = opengpu_shader_resource_batch(config);
    int coalesced = capacity == 8 || capacity % 16 == 0;
    CHECK(opengpu_buffer_create(fd,sizeof(struct drm_opengpu_vertex_draw),&command));
    CHECK(opengpu_buffer_create(fd,SIDE*SIDE*4u,&color));CHECK(opengpu_buffer_create(fd,sizeof(vertices),&vb));
    CHECK(opengpu_buffer_create(fd,2048,&vs));CHECK(opengpu_buffer_create(fd,2048,&fs));
    CHECK(opengpu_buffer_create(fd,2*vstride,&vk));
    CHECK(opengpu_buffer_create(fd,opengpu_shader_resource_banks(config)*fstride,&fk));
    CHECK(opengpu_shader_lower_runtime_abi(opengpu_vertex_passthrough,WORDS(opengpu_vertex_passthrough),true,vs.map,512,&words));
    CHECK(opengpu_shader_lower_runtime_abi(opengpu_fragment_color,WORDS(opengpu_fragment_color),false,fs.map,512,&words));
    memcpy(vb.map,vertices,sizeof(vertices));
    CHECK(bind_buffer(fd,context,1,OPENGPU_RESOURCE_VERTEX_BUFFER,&vb));
    CHECK(bind_buffer(fd,context,2,OPENGPU_RESOURCE_VERTEX_SHADER,&vs));
    CHECK(bind_buffer(fd,context,3,OPENGPU_RESOURCE_VERTEX_KERNARG,&vk));
    CHECK(bind_buffer(fd,context,4,OPENGPU_RESOURCE_SHADER,&fs));
    CHECK(bind_buffer(fd,context,5,OPENGPU_RESOURCE_KERNARG,&fk));
    struct drm_opengpu_vertex_draw *draw=command.map;
    *draw=(struct drm_opengpu_vertex_draw){.vertex_count=3,.vertex_stride=sizeof(vertices[0]),
        .vertex_kernarg_bank_stride=vstride,.fragment_kernarg_bank_stride=fstride,.state=OPENGPU_DRAW_STATE_OVERRIDE};
    struct drm_opengpu_submit submit={.context_id=context,.command_handle=command.handle,.color_handle=color.handle,
        .stride=SIDE*4u,.command_count=1,.flags=OPENGPU_SUBMIT_VERTEX_CORE,.vertex_buffer_slot=1,
        .vertex_shader_slot=2,.vertex_kernarg_slot=3,.shader_slot=4,.kernarg_slot=5,.out_syncobj=sync,
        .render_width=SIDE,.render_height=SIDE};
    /* Raw record hints are reserved for trusted kernel snapshots. */
    draw->state|=55u<<19;CHECK(rejected(fd,&submit));draw->state=OPENGPU_DRAW_STATE_OVERRIDE;
    draw->state|=15u<<25;CHECK(rejected(fd,&submit));draw->state=OPENGPU_DRAW_STATE_OVERRIDE;
    for(unsigned pass=0;pass<2;pass++) {
        for(size_t i=0;i<fk.size/4;i++)((uint32_t *)fk.map)[i]=SCRATCH;
        CHECK(fill(fd,context,&color,0,sync));
        if(pass)submit.flags|=OPENGPU_SUBMIT_TRANSIENT_FRAGMENT_STAGING;
        if(pass && !(caps&OPENGPU_CAP_FRAGMENT_INPUT_USAGE)) {
            CHECK(rejected(fd,&submit));
            for(size_t i=0;i<fk.size/4;i++)if(((uint32_t *)fk.map)[i]!=SCRATCH)return 1;
            if(memcmp(baseline,color.map,sizeof(baseline))==0) { /* fill intentionally cleared it */ return 1; }
            break;
        }
        CHECK(opengpu_render(fd,&submit));CHECK(opengpu_sync_wait_success(fd,sync,1800000));
        CHECK(scratch_check(&fk,config,fstride,pass && coalesced,
            pass && coalesced && (caps&OPENGPU_CAP_FRAGMENT_OUTPUT_USAGE)));
        for(unsigned y=0;y<SIDE;y++)for(unsigned x=0;x<SIDE;x++) {
            uint32_t expected=x>0&&y>0&&x+y<=SIDE?RED:0;
            if(((uint32_t *)color.map)[y*SIDE+x]!=expected) {
                fprintf(stderr,"pixel mismatch pass=%u x=%u y=%u got=%08x expected=%08x\n",pass,x,y,
                    ((uint32_t *)color.map)[y*SIDE+x],expected);return 1;
            }
            if(!pass && expected)covered++;
        }
        if(!pass)memcpy(baseline,color.map,sizeof(baseline));
        else if(memcmp(baseline,color.map,sizeof(baseline)))return 1;
    }
    printf("OPENGPU TRANSIENT STAGING PASS: hardware=%u covered=%u output_usage=%u defaultCPUinputs+uniforms preserved, "
           "reservedhint rejected, %s\n",!!(caps&OPENGPU_CAP_FRAGMENT_INPUT_USAGE),covered,!!(caps&OPENGPU_CAP_FRAGMENT_OUTPUT_USAGE),
           caps&OPENGPU_CAP_FRAGMENT_INPUT_USAGE?
               (coalesced?"optin omittedbytesandpixels exact":"optin legacy staging and pixels exact"):
               "unsupportedoptin rejected withoutscratchwrites");
    CHECK(opengpu_sync_destroy(fd,sync));CHECK(opengpu_context_destroy(fd,context));
    CHECK(opengpu_buffer_destroy(fd,&command));CHECK(opengpu_buffer_destroy(fd,&color));CHECK(opengpu_buffer_destroy(fd,&vb));
    CHECK(opengpu_buffer_destroy(fd,&vs));CHECK(opengpu_buffer_destroy(fd,&fs));CHECK(opengpu_buffer_destroy(fd,&vk));CHECK(opengpu_buffer_destroy(fd,&fk));
    close(fd);return 0;
}
