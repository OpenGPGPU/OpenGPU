/* SPDX-License-Identifier: MIT */
/* Explicit headless ESSL/Mesa/Gallium/DRM witness, not an EGL/GL implementation.
 * --preflight only compiles/validates/dumps; it never opens a DRM device. */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>
#include "pipe/p_context.h"
#include "pipe/p_screen.h"
#include "pipe/p_state.h"
#include "frontend/winsys_handle.h"
#include "util/u_inlines.h"
#include "opengpu_public.h"
#include "opengpu_cs_emit.h"
#include "opengpu_drm.h"
#include "opengpu_shader_validator.h"
#include "cs_glsl_frontend.h"
#include "qualification_pagemap.h"
#include "gallium_buffer_glsl_contract.h"

static struct {
    int fd;
    struct pipe_screen *screen;
    struct pipe_context *ctx;
    struct pipe_resource *bo[GB_BOS],*ubo[12];
    struct pipe_sampler_view *views[32];
    void *frontend[GB_PROGRAMS],*state[GB_PROGRAMS];
    struct gb_model expected;
} app={.fd=-1};

static void require(int ok,const char *why)
{ if(!ok) { fprintf(stderr,"buffer-glsl: %s errno=%d (%s)\n",why,errno,strerror(errno)); exit(1); } }
static void destroy_context(void)
{
    if(!app.ctx) return;
    /* Last view release calls view->context->sampler_view_destroy. Drop our
     * external view refs before freeing that context; BO refs stay owned. */
    for(unsigned i=0;i<32;i++) pipe_sampler_view_reference(&app.views[i],NULL);
    app.ctx->bind_compute_state(app.ctx,NULL);
    for(unsigned i=0;i<GB_PROGRAMS;i++) if(app.state[i]) {
        app.ctx->delete_compute_state(app.ctx,app.state[i]); app.state[i]=NULL;
    }
    app.ctx->destroy(app.ctx); app.ctx=NULL;
}
static void cleanup(void)
{
    destroy_context();
    for(unsigned i=0;i<32;i++) pipe_sampler_view_reference(&app.views[i],NULL);
    for(unsigned i=0;i<GB_BOS;i++) pipe_resource_reference(&app.bo[i],NULL);
    for(unsigned i=0;i<12;i++) pipe_resource_reference(&app.ubo[i],NULL);
    for(unsigned i=0;i<GB_PROGRAMS;i++) if(app.frontend[i]) {
        cs_frontend_destroy(app.frontend[i]); app.frontend[i]=NULL;
    }
    if(app.screen) { app.screen->destroy(app.screen); app.screen=NULL; }
    if(app.fd>=0) { close(app.fd); app.fd=-1; }
}
static FILE *output(const char *dir,unsigned n,const char *suffix)
{
    char path[4096]; int length=snprintf(path,sizeof(path),"%s/%s.%s",dir,gb_names[n],suffix);
    require(length>0 && (size_t)length<sizeof(path),"output path");
    FILE *f=fopen(path,"wx"); require(f!=NULL,"new output file"); return f;
}
static void compile_sources(const char *sources,const char *dumps)
{
    for(unsigned n=0;n<GB_PROGRAMS;n++) {
        char path[4096],prefix[4096];
        int length=snprintf(path,sizeof(path),"%s/%s.comp",sources,gb_names[n]);
        require(length>0 && (size_t)length<sizeof(path),"shader path");
        length=snprintf(prefix,sizeof(prefix),"%s/%s",dumps?dumps:"",gb_names[n]);
        require(length>0 && (size_t)length<sizeof(prefix),"frontend output path");
        app.frontend[n]=cs_frontend_compile_buffers(path,dumps?prefix:NULL);
        require(app.frontend[n]!=NULL,"real ESSL310/NIR/TGSI frontend");
        struct opengpu_cs_program p;
        require(!opengpu_cs_emit(cs_frontend_tokens(app.frontend[n]),&p) && p.words<=512,"real bounded native emitter");
        for(unsigned c=0;c<3;c++) require(p.fixed_block[c]==gb_local[c],"actual local shape");
        unsigned used=0;
        for(unsigned b=0;b<4;b++) {
            unsigned binding=cs_frontend_storage_binding(app.frontend[n],b);
            require(binding<4 && !(used&(1u<<binding)),"actual four SSBO binding reflection"); used|=1u<<binding;
        }
        require(p.writes==15 && p.reads==((n>0 && n<5)?15u:0u),"actual SSBO masks");
        require(p.textures==((n>=1 && n<=3)?((UINT32_C(1)<<gb_texture_count[n-1])-1)<<gb_texture_first[n-1]:n==5?UINT32_C(0x80000000):0),"actual sampler masks");
        require(p.image_reads==(n==4?15u:0u) && p.image_writes==(n==4?15u:0u) &&
            p.image_queries==(n==4?15u:0u),"actual image LD/ST/size masks");
        for(unsigned b=0;b<13;b++) require(p.constant_bytes[b]==(n?0u:(b?16384u:4096u)),"full13CB static ranges");
        require(opengpu_compute_shader_validate_words_discovered_resources(p.code,512,12,GB_ITEMS,
            false,false,true,true,true,false,false,false,true,0,0,0,true),"actual discovered kernel instruction validator");
        if(n==4) require(!opengpu_compute_shader_validate_words_discovered_resources(p.code,512,12,GB_ITEMS,
            false,false,true,true,true,false,false,false,true,0,0,0,false),"fn27 absent-version failclosed");
        if(dumps) {
            FILE *f=output(dumps,n,"words"); require(fwrite(p.code,1,sizeof(p.code),f)==sizeof(p.code) && !fclose(f),"actual instruction output");
            f=output(dumps,n,"resources");
            fprintf(f,"words=%u reads=%u writes=%u textures=%u imageReads=%u imageWrites=%u imageQueries=%u\n",
                p.words,p.reads,p.writes,p.textures,p.image_reads,p.image_writes,p.image_queries);
            for(unsigned b=0;b<4;b++) fprintf(f,"native.%u.binding=%u\n",b,cs_frontend_storage_binding(app.frontend[n],b));
            require(!fclose(f),"actual reflection output");
        }
        printf("BUFFER_GLSL_PROGRAM name=%s words=%u storage=%x/%x textures=%08x images=%x/%x/%x local=8,4,4 GPU=UNRUN\n",
            gb_names[n],p.words,p.reads,p.writes,p.textures,p.image_reads,p.image_writes,p.image_queries);
    }
}
static uint32_t *map(struct pipe_resource *r,unsigned offset,unsigned bytes,unsigned usage,struct pipe_transfer **t)
{
    struct pipe_box b={.x=(int)offset,.width=(int)bytes,.height=1,.depth=1};
    uint32_t *p=app.ctx->buffer_map(app.ctx,r,0,usage,&b,t);
    require(p && *t,"real byte-range mapping"); return p;
}
static struct pipe_resource *buffer(unsigned bytes,unsigned bind)
{
    struct pipe_resource t={.target=PIPE_BUFFER,.format=PIPE_FORMAT_R32_UINT,.width0=bytes,
        .height0=1,.depth0=1,.array_size=1,.bind=bind};
    struct pipe_resource *r=app.screen->resource_create(app.screen,&t);
    require(r && r->width0==bytes,"full-size BO allocation"); return r;
}
static void upload_bo(unsigned bo)
{
    for(unsigned tail=0;tail<2;tail++) {
        struct pipe_transfer *t=NULL; unsigned bytes=gb_region_bytes(tail),offset=gb_region_offset(bo,tail);
        uint32_t *p=map(app.bo[bo],offset,bytes,PIPE_MAP_WRITE,&t);
        memcpy(p,&app.expected.words[bo][tail?GB_PREFIX_BYTES/4:0],bytes);
        errno=0; app.ctx->buffer_unmap(app.ctx,t); require(!errno,"bounded CPU-write publication");
    }
}
static void check_all(const char *label)
{
    unsigned checked=0;
    for(unsigned bo=0;bo<app.expected.count;bo++) for(unsigned tail=0;tail<2;tail++) {
        struct pipe_transfer *t=NULL; unsigned bytes=gb_region_bytes(tail),offset=gb_region_offset(bo,tail);
        const uint32_t *p=map(app.bo[bo],offset,bytes,PIPE_MAP_READ,&t);
        for(unsigned i=0;i<bytes/4;i++) {
            uint32_t want=gb_read(&app.expected,bo,offset+4*i);
            if(p[i]!=want) fprintf(stderr,"BUFFER_GLSL_MISMATCH stage=%s bo=%u byte=%u got=%08x want=%08x\n",label,bo,offset+4*i,p[i],want);
            require(p[i]==want,"all prefix/tail payload and guard words"); checked++;
        }
        app.ctx->buffer_unmap(app.ctx,t);
    }
    for(unsigned b=0;b<12;b++) {
        struct pipe_transfer *t=NULL; const uint32_t *p=map(app.ubo[b],0,16384,PIPE_MAP_READ,&t);
        for(unsigned w=0;w<4096;w++) require(p[w]==gb_uniform(app.expected.epoch,b+1,w),"all public UBO-source words unchanged");
        app.ctx->buffer_unmap(app.ctx,t);
    }
    printf("BUFFER_GLSL_CHECK stage=%s BOs=%u words=%u exact=true\n",label,app.expected.count,checked);
}
static void bind_views(void)
{
    for(unsigned b=0;b<32;b++) {
        struct pipe_sampler_view t={.target=PIPE_BUFFER,.format=PIPE_FORMAT_R32_UINT,
            .swizzle_r=0,.swizzle_g=1,.swizzle_b=2,.swizzle_a=3};
        t.u.buf.size=gb_bytes(app.expected.texture[b]);
        struct pipe_sampler_view *next=app.ctx->create_sampler_view(app.ctx,app.bo[app.expected.texture[b]],&t);
        require(next!=NULL,"checked full-range sampler view"); pipe_sampler_view_reference(&app.views[b],NULL); app.views[b]=next;
    }
    errno=0; app.ctx->set_sampler_views(app.ctx,PIPE_SHADER_COMPUTE,0,32,0,false,app.views); require(!errno,"all32 sampler callbacks");
    struct pipe_image_view images[4]={{0}};
    for(unsigned b=0;b<4;b++) {
        images[b].resource=app.bo[app.expected.image[b]]; images[b].format=PIPE_FORMAT_R32_UINT;
        images[b].access=images[b].shader_access=PIPE_IMAGE_ACCESS_READ|PIPE_IMAGE_ACCESS_WRITE;
        images[b].u.buf.offset=app.expected.image_offset[b];
        images[b].u.buf.size=gb_bytes(app.expected.image[b])-app.expected.image_offset[b];
    }
    errno=0; app.ctx->set_shader_images(app.ctx,PIPE_SHADER_COMPUTE,0,4,0,images); require(!errno,"four independent image callbacks");
}
static void upload_uniforms(unsigned epoch)
{
    for(unsigned b=0;b<12;b++) {
        struct pipe_transfer *t=NULL; uint32_t *p=map(app.ubo[b],0,16384,PIPE_MAP_WRITE,&t);
        for(unsigned w=0;w<4096;w++) p[w]=gb_uniform(epoch,b+1,w);
        errno=0; app.ctx->buffer_unmap(app.ctx,t); require(!errno,"UBO CPU-write publication");
    }
}
static void bind_uniforms(unsigned n)
{
    uint32_t values[1024];
    for(unsigned w=0;w<1024;w++) values[w]=gb_uniform(app.expected.epoch,0,w);
    struct pipe_resource *resources[36]={0}; unsigned offsets[36]={0},sizes[36]={0};
    for(unsigned b=0;b<12;b++) { resources[24+b]=app.ubo[b]; sizes[24+b]=16384; }
    errno=0; cs_frontend_ubos(app.frontend[n],app.ctx,resources,offsets,sizes);
    require(!errno,"active program-local st_bind_cs_ubos mapping");
    cs_frontend_defaults(app.frontend[n],app.ctx,values,1024); require(!errno,"active frontend owns full4KiB defaults");
    memset(values,0x89,sizeof(values));
}
static void launch(unsigned n,int error)
{
    if(!app.state[n]) {
        struct pipe_compute_state p={.ir_type=PIPE_SHADER_IR_TGSI,.prog=cs_frontend_tokens(app.frontend[n])};
        app.state[n]=app.ctx->create_compute_state(app.ctx,&p); require(app.state[n]!=NULL,"real TGSI compute state");
    }
    errno=0; app.ctx->bind_compute_state(app.ctx,app.state[n]); require(!errno,"native state bind");
    struct pipe_resource *ssbo[4]; for(unsigned b=0;b<4;b++) ssbo[b]=app.bo[app.expected.ssbo[b]];
    cs_frontend_storage(app.frontend[n],app.ctx,ssbo); require(!errno,"real st_bind_cs_ssbos mapping");
    if(n==0) bind_uniforms(n);
    struct pipe_grid_info grid={0}; memcpy(grid.grid,gb_grid,sizeof(gb_grid)); memcpy(grid.block,gb_local,sizeof(gb_local));
    app.ctx->launch_grid(app.ctx,&grid); require(errno==error,"actual synchronous DRM completion");
    if(!error) gb_reference(&app.expected,n);
    check_all(gb_names[n]); printf("BUFFER_GLSL_LAUNCH name=%s epoch=%u expectedError=%d PASS\n",gb_names[n],app.expected.epoch,error);
}
static void barrier(void)
{
    errno=0; app.ctx->memory_barrier(app.ctx,PIPE_BARRIER_SHADER_BUFFER|PIPE_BARRIER_IMAGE|PIPE_BARRIER_TEXTURE);
    require(!errno,"serialized producer publication; any pending fence failure aborts without replay");
}
static void new_context(void)
{
    require(app.ctx==NULL,"old owner destroyed before replacement context"); errno=0;
    app.ctx=app.screen->context_create(app.screen,NULL,0);
    require(app.ctx && !errno,"fresh context; screen reaps pending owner or fails closed");
}
static uint64_t env_number(const char *name)
{
    const char *s=getenv(name); char *end=NULL; errno=0;
    require(s && *s && *s!='-' && *s!='+' && *s!=' ' && *s!='\t',"explicit qualification RAM bounds");
    uint64_t n=strtoull(s,&end,0); require(!errno && end && !*end,"RAM bound syntax"); return n;
}
static void qualification_layout(void)
{
    const char *enabled=getenv("OPENGPU_QUALIFICATION_PAGEMAP"); if(!enabled) return;
    require(!strcmp(enabled,"1"),"explicit pagemap observer");
    uint64_t base=env_number("OPENGPU_QUALIFICATION_RAM_BASE"),end=env_number("OPENGPU_QUALIFICATION_RAM_END");
    for(unsigned n=0;n<app.expected.count+12;n++) {
        struct pipe_resource *r=n<app.expected.count?app.bo[n]:app.ubo[n-app.expected.count];
        struct winsys_handle h={.type=WINSYS_HANDLE_TYPE_KMS};
        require(app.screen->resource_get_handle(app.screen,NULL,r,&h,0),"GEM observer label");
        struct pipe_transfer *t=NULL; const void *p=map(r,0,r->width0,PIPE_MAP_READ,&t);
        int rc=opengpu_qual_pagemap_report(p,r->width0,base,end,stdout,n<app.expected.count?"buffer":"ubo-source",
            n<app.expected.count?n:24+n-app.expected.count,h.handle);
        app.ctx->buffer_unmap(app.ctx,t); errno=rc<0?-rc:0; require(!rc,"all pagemap pages visible; handle is not a PA");
    }
    puts("BUFFER_GLSL_PAGEMAP publicBOs=complete UBOsourcePFNs=notShaderSnapshotPFNs shaderPTEwalk=REQUIRED");
}
int main(int argc,char **argv)
{
    setvbuf(stdout,NULL,_IOLBF,0); require(!atexit(cleanup),"cleanup registration");
    if(argc==4 && !strcmp(argv[1],"--preflight")) {
        require(!mkdir(argv[2],0700),"new preflight directory"); compile_sources(argv[3],argv[2]);
        puts("BUFFER_GLSL_PREFLIGHT actualFrontend=PASS nativeValidator=PASS DRM=UNRUN GPU=UNRUN"); return 0;
    }
    require(argc==1 || (argc==3 && !strcmp(argv[1],"--sources")),"usage: gallium_buffer_glsl [--sources DIR | --preflight NEWDIR SOURCES]");
    compile_sources(argc==3?argv[2]:"/opengpu_glsl_buffer_sources",NULL);
    app.fd=open("/dev/dri/card0",O_RDWR|O_CLOEXEC); require(app.fd>=0,"real DRM device");
    struct drm_opengpu_param p={.param=OPENGPU_PARAM_DRIVER_FEATURES}; require(!ioctl(app.fd,DRM_IOCTL_OPENGPU_GET_PARAM,&p),"driver features");
    uint64_t need=OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE|OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BYTE_VIEWS|
        OPENGPU_DRIVER_FEATURE_COMPUTE_UNIFORMS|OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_TEXTURE|
        OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_IMAGES|OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_BACKINGS;
    require((p.value&need)==need,"exact target5/uniform/independent backing gates");
    app.screen=opengpu_drm_screen_create(app.fd,NULL); require(app.screen!=NULL,"actual Gallium screen");
    require(!app.screen->get_param(app.screen,PIPE_CAP_COMPUTE),"GL compute capability remains off"); new_context();
    unsigned handles[GB_BOS+12],count=0;
    for(unsigned b=0;b<40+12;b++) {
        struct pipe_resource *r=buffer(b<40?gb_bytes(b):16384,b<40?
            PIPE_BIND_SHADER_BUFFER|PIPE_BIND_SAMPLER_VIEW|PIPE_BIND_SHADER_IMAGE:PIPE_BIND_CONSTANT_BUFFER);
        if(b<40) app.bo[b]=r; else app.ubo[b-40]=r;
        struct winsys_handle h={.type=WINSYS_HANDLE_TYPE_KMS}; require(app.screen->resource_get_handle(app.screen,NULL,r,&h,0),"independent GEM handle");
        handles[count++]=h.handle; for(unsigned j=0;j+1<count;j++) require(handles[j]!=h.handle,"all baseline allocations independent");
    }
    for(unsigned epoch=1;epoch<=2;epoch++) {
        gb_initialize(&app.expected,epoch);
        for(unsigned b=0;b<40;b++) upload_bo(b);
        upload_uniforms(epoch); launch(0,0); barrier();
        if(epoch==2) {
            app.ctx->set_sampler_views(app.ctx,PIPE_SHADER_COMPUTE,0,0,32,false,NULL);
            app.ctx->set_shader_images(app.ctx,PIPE_SHADER_COMPUTE,0,0,4,NULL);
            gb_rebind(&app.expected);
            app.bo[40]=buffer(GB_VIEW_BYTES,PIPE_BIND_SHADER_IMAGE);
            struct winsys_handle h={.type=WINSYS_HANDLE_TYPE_KMS}; require(app.screen->resource_get_handle(app.screen,NULL,app.bo[40],&h,0),"replacement GEM handle");
            for(unsigned j=0;j<count;j++) require(handles[j]!=h.handle,"retained old owner cannot be recycled as replacement");
            upload_bo(40); /* old image3 remains alive, read back and checked after every launch. */
        }
        bind_views(); qualification_layout();
        for(unsigned n=1;n<5;n++) { launch(n,0); barrier(); }
        if(epoch==1) {
            struct drm_opengpu_fault before={0},fault={0};
            require(!ioctl(app.fd,DRM_IOCTL_OPENGPU_GET_FAULT,&before),"pre-fault sequence"); launch(5,EIO);
            require(!ioctl(app.fd,DRM_IOCTL_OPENGPU_GET_FAULT,&fault),"real completion fault record");
            require(fault.flags==(OPENGPU_FAULT_VALID|OPENGPU_FAULT_COMPLETION_ERROR) && fault.error==-EIO && fault.status &&
                fault.sequence>before.sequence && fault.command_id==fault.expected_command_id &&
                !fault.opcode && !fault.expected_opcode,"exact public compute completion identity");
            /* A namespace delete is not retirement. Explicit publication must
             * finish the saved fence first; failure aborts, never retries P. */
            barrier(); destroy_context(); new_context();
        }
    }
    cleanup(); puts("BUFFER_GLSL_RUNTIME parents=11 shaderPrograms=6 full32textures=true full4SSBO=true full4independentImages=true full13CB=true faultFreshReuse=PASS CAP_COMPUTE=0 PASS");
    return 0;
}
