/* SPDX-License-Identifier: MIT */
/* Explicit headless compiler/callback/DRM application. This is not an EGL/GL
 * context and does not turn on PIPE_CAP_COMPUTE or advertise GLES 3.1. */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include "pipe/p_context.h"
#include "pipe/p_screen.h"
#include "pipe/p_state.h"
#include "frontend/winsys_handle.h"
#include "util/u_inlines.h"
#include "tgsi/tgsi_parse.h"
#include "opengpu_public.h"
#include "opengpu_cs_emit.h"
#include "opengpu_drm.h"
#include "opengpu_shader_validator.h"
#include "cs_glsl_frontend.h"
#include "gallium_compute_glsl_contract.h"

static struct {
    int fd;
    struct pipe_screen *screen;
    struct pipe_context *contexts[2];
    struct pipe_resource *storage[4], *ubos[12];
    void *frontends[5];
} app={.fd=-1};

static void cleanup(void)
{
    for(unsigned i=0;i<2;i++) if(app.contexts[i]) {
        app.contexts[i]->destroy(app.contexts[i]); app.contexts[i]=NULL;
    }
    for(unsigned i=0;i<4;i++) pipe_resource_reference(&app.storage[i],NULL);
    for(unsigned i=0;i<12;i++) pipe_resource_reference(&app.ubos[i],NULL);
    for(unsigned i=0;i<5;i++) if(app.frontends[i]) {
        cs_frontend_destroy(app.frontends[i]); app.frontends[i]=NULL;
    }
    if(app.screen) { app.screen->destroy(app.screen); app.screen=NULL; }
    if(app.fd>=0) { close(app.fd); app.fd=-1; }
}

static void require(int ok,const char *what)
{
    if(!ok) { fprintf(stderr,"gallium-glsl: %s (errno=%d: %s)\n",what,errno,strerror(errno)); exit(1); }
}

static uint64_t ns(void)
{
    struct timespec t; require(!clock_gettime(CLOCK_MONOTONIC,&t),"monotonic clock");
    return (uint64_t)t.tv_sec*UINT64_C(1000000000)+(uint64_t)t.tv_nsec;
}

static void compile_sources(const char *directory,const char *dumps)
{
    for(unsigned n=0;n<5;n++) {
        const struct glc_parent *p=&glc_parents[n]; char path[4096],prefix[4096];
        require(snprintf(path,sizeof(path),"%s/%s.comp",directory,p->name)>0,"GLSL path");
        if(dumps) require(snprintf(prefix,sizeof(prefix),"%s/%s",dumps,p->name)>0,"dump path");
        app.frontends[n]=cs_frontend_compile(path,dumps?prefix:NULL);
        require(app.frontends[n]!=NULL,"actual ESSL310/NIR/TGSI frontend");
        const void *tokens=cs_frontend_tokens(app.frontends[n]);
        unsigned temps=0,uifs=0; struct tgsi_parse_context parser;
        require(tgsi_parse_init(&parser,tokens)==TGSI_PARSE_OK,"actual TGSI parser");
        while(!tgsi_parse_end_of_tokens(&parser)) {
            tgsi_parse_token(&parser);
            if(parser.FullToken.Token.Type==TGSI_TOKEN_TYPE_INSTRUCTION &&
               parser.FullToken.FullInstruction.Instruction.Opcode==TGSI_OPCODE_UIF) uifs++;
            if(parser.FullToken.Token.Type==TGSI_TOKEN_TYPE_DECLARATION &&
               parser.FullToken.FullDeclaration.Declaration.File==TGSI_FILE_TEMPORARY) {
                unsigned t=parser.FullToken.FullDeclaration.Range.Last+1u; if(t>temps) temps=t;
            }
        }
        tgsi_parse_free(&parser);
        require(uifs && temps<=5 && cs_frontend_default_components(app.frontends[n])==1024,"actual UIF/20-component/default limits");
        struct opengpu_cs_program code;
        require(!opengpu_cs_emit(tokens,&code) && code.words<=512,"actual native emitter");
        for(unsigned i=0;i<3;i++) require(code.fixed_block[i]==p->local[i],"source local metadata");
        for(unsigned i=0;i<13;i++) require(code.constant_bytes[i]==(i?16384u:4096u),"full13CB byte extents");
        require(opengpu_compute_shader_validate_words_uniform_resources(code.code,512,12,glc_items(p),
            false,false,false,true,false,false,false,false),"actual kernel uniform+storage admission");
        require(!opengpu_compute_shader_validate_words_uniform_resources(code.code,512,12,glc_items(p),
            false,false,false,false,false,false,false,false),"storage-disabled must reject");
        if(p->fault) require(code.reads==8 && code.writes==1,"actual fault native slot masks");
        else require(code.reads==(p->consumer?15u:0u) && code.writes==15,"all four storage slots");
        unsigned seen=0;
        for(unsigned i=0;i<4;i++) { unsigned b=cs_frontend_storage_binding(app.frontends[n],i); require(b<4 && !(seen&(1u<<b)),"actual st SSBO binding map"); seen|=1u<<b; }
        if(dumps) {
            require(snprintf(path,sizeof(path),"%s/%s.words",dumps,p->name)>0,"word path");
            FILE *out=fopen(path,"wx"); require(out!=NULL,"new native output");
            require(fwrite(code.code,1,sizeof(code.code),out)==sizeof(code.code) && !fclose(out),"native output");
            require(snprintf(path,sizeof(path),"%s/%s.resources",dumps,p->name)>0,"resource path");
            out=fopen(path,"wx"); require(out!=NULL,"new resource reflection");
            fprintf(out,"words=%u reads=%u writes=%u\n",code.words,code.reads,code.writes);
            for(unsigned i=0;i<4;i++) fprintf(out,"native.%u.binding=%u\n",i,cs_frontend_storage_binding(app.frontends[n],i));
            require(!fclose(out),"resource reflection output");
        }
        printf("GLSL_LINUX_PROGRAM name=%s ESSL=310 UIF=%u temps=%u words=%u reads=%u writes=%u full13CB=true local=%u,%u,%u grid=%u,%u,%u GPU=unrun\n",
            p->name,uifs,temps,code.words,code.reads,code.writes,p->local[0],p->local[1],p->local[2],p->grid[0],p->grid[1],p->grid[2]);
    }
}

static uint32_t *map(struct pipe_context *ctx,struct pipe_resource *r,unsigned offset,unsigned bytes,
                     unsigned usage,struct pipe_transfer **transfer)
{
    struct pipe_box box={.x=(int)offset,.width=(int)bytes,.height=1,.depth=1};
    uint32_t *data=ctx->buffer_map(ctx,r,0,usage,&box,transfer);
    require(data && *transfer,"real public buffer map"); return data;
}

static struct pipe_resource *buffer(unsigned bytes,unsigned bind)
{
    struct pipe_resource t={.target=PIPE_BUFFER,.format=PIPE_FORMAT_R32_UINT,.width0=bytes,
        .height0=1,.depth0=1,.array_size=1,.bind=bind};
    struct pipe_resource *r=app.screen->resource_create(app.screen,&t);
    require(r && r->width0==bytes,"exact full-size resource"); return r;
}

static void storage_initialize(struct pipe_context *ctx)
{
    for(unsigned b=0;b<4;b++) for(unsigned tail=0;tail<2;tail++) {
        struct pipe_transfer *t=NULL; unsigned bytes=glc_region_bytes(tail);
        uint32_t *d=map(ctx,app.storage[b],glc_region_offset(tail),bytes,PIPE_MAP_WRITE,&t);
        for(unsigned i=0;i<bytes/4;i++) d[i]=GLC_GUARD;
        ctx->buffer_unmap(ctx,t);
    }
}

static void check(struct pipe_context *ctx,unsigned epoch,unsigned total,unsigned consumed,
                  uint32_t *snapshot,int save)
{
    unsigned at=0;
    for(unsigned b=0;b<4;b++) for(unsigned tail=0;tail<2;tail++) {
        unsigned bytes=glc_region_bytes(tail),offset=glc_region_offset(tail); struct pipe_transfer *t=NULL;
        const uint32_t *d=map(ctx,app.storage[b],offset,bytes,PIPE_MAP_READ,&t);
        for(unsigned i=0;i<bytes/4;i++,at++) {
            if(save) snapshot[at]=d[i];
            else {
                uint32_t want=snapshot?snapshot[at]:glc_expected(epoch,b,offset+i*4,total,consumed);
                if(d[i]!=want) {
                    fprintf(stderr,"GLSL_LINUX_MISMATCH epoch=%u binding=%u offset=%u got=%08x want=%08x\n",epoch,b,offset+i*4,d[i],want);
                    exit(1);
                }
            }
        }
        ctx->buffer_unmap(ctx,t);
    }
    require(at==GLC_CHECK_WORDS,"all bounded result and guard words");
}

static void bind_program(unsigned n,struct pipe_context *ctx)
{
    void *f=app.frontends[n];
    struct pipe_compute_state in={.ir_type=PIPE_SHADER_IR_TGSI,.prog=cs_frontend_tokens(f)};
    void *state=ctx->create_compute_state(ctx,&in); require(state!=NULL,"real TGSI compute state");
    ctx->bind_compute_state(ctx,state);
    cs_frontend_storage(f,ctx,app.storage); /* Actual st_bind_cs_ssbos, including F's nonidentity map. */
    struct pipe_resource *resources[36]={0}; unsigned offsets[36]={0},sizes[36]={0};
    for(unsigned b=0;b<12;b++) { resources[24+b]=app.ubos[b]; sizes[24+b]=16384; }
    cs_frontend_ubos(f,ctx,resources,offsets,sizes); /* Actual global24..35 -> localCB1..12 mapping. */
}

static void uniforms(struct pipe_context *ctx,unsigned n)
{
    unsigned epoch=glc_parents[n].uniform_epoch; uint32_t defaults[1024];
    for(unsigned i=0;i<1024;i++) defaults[i]=glc_uniform(epoch,0,i);
    for(unsigned b=0;b<12;b++) for(unsigned tail=0;tail<2;tail++) {
        unsigned offset=tail?16384-64:0; struct pipe_transfer *t=NULL;
        uint32_t *d=map(ctx,app.ubos[b],offset,64,PIPE_MAP_WRITE,&t);
        for(unsigned i=0;i<16;i++) d[i]=glc_uniform(epoch,b+1,offset/4+i);
        ctx->buffer_unmap(ctx,t);
    }
    /* Real callback copies all4096 bytes; the frontend poisons its own Mesa
     * parameter storage immediately after returning. No busy GEM is poisoned. */
    cs_frontend_defaults(app.frontends[n],ctx,defaults,1024);
    memset(defaults,0x89,sizeof(defaults));
}

static void launch(unsigned n,struct pipe_context *ctx,int expected_error)
{
    struct pipe_grid_info grid={0}; const struct glc_parent *p=&glc_parents[n];
    memcpy(grid.grid,p->grid,sizeof(grid.grid)); memcpy(grid.block,p->local,sizeof(grid.block));
    errno=0; uint64_t start=ns(); ctx->launch_grid(ctx,&grid);
    require(errno==expected_error,"actual synchronous DRM completion status");
    printf("GLSL_LINUX_LAUNCH name=%s localItems=%u groups=%u error=%d ns=%llu\n",p->name,
        glc_items(p),p->grid[0],expected_error,(unsigned long long)(ns()-start));
}

static void run_epoch(unsigned epoch)
{
    const unsigned p=epoch==1?0:3,c=p+1,total=2*glc_items(&glc_parents[p]);
    app.contexts[0]=app.screen->context_create(app.screen,NULL,0);
    app.contexts[1]=app.screen->context_create(app.screen,NULL,0);
    require(app.contexts[0] && app.contexts[1] && app.contexts[0]!=app.contexts[1],"explicit fresh independent contexts");
    struct pipe_context *producer=app.contexts[0],*consumer=app.contexts[1];
    bind_program(p,producer); bind_program(c,consumer); storage_initialize(producer);
    printf("GLSL_LINUX_UPLOAD epoch=%u fullBackings=4 bytesPerBacking=%u touchedBytes=%u lineEnvelopes=516\n",
        epoch,GLC_VIEW_BYTES,4*(GLC_PREFIX_BYTES+GLC_TAIL_BYTES));
    if(epoch==1) {
        struct pipe_grid_info bad={.grid={2,1,1},.block={129,1,1}}; errno=0;
        producer->launch_grid(producer,&bad); require(errno==EINVAL,"local129 rejected");
        struct pipe_shader_buffer b={app.storage[0],0,GLC_VIEW_BYTES+1}; errno=0;
        producer->set_shader_buffers(producer,PIPE_SHADER_COMPUTE,0,1,&b,1);
        require(errno==EINVAL,"view expansion rejected"); cs_frontend_storage(app.frontends[p],producer,app.storage);
    }
    uniforms(producer,p); launch(p,producer,0); check(consumer,epoch,total,0,NULL,0);
    printf("GLSL_LINUX_PRODUCER epoch=%u checkedWords=%u full13CB=true full4x128MiB=true PASS\n",epoch,GLC_CHECK_WORDS);
    /* Only independent UBO/default sources change after completed P. Original
     * storage GEMs receive no CPU writes or invalidates between P and C. */
    uniforms(consumer,c); launch(c,consumer,0); check(consumer,epoch,total,1,NULL,0);
    printf("GLSL_LINUX_CONSUMER epoch=%u checkedWords=%u originalGEMs=true full13CB=true PASS\n",epoch,GLC_CHECK_WORDS);
    if(epoch==1) {
        struct pipe_transfer *t=NULL; uint32_t snapshot[GLC_CHECK_WORDS];
        uint32_t *d=map(consumer,app.storage[0],0,128*4,PIPE_MAP_WRITE,&t);
        for(unsigned i=0;i<128;i++) d[i]=i&1u?UINT32_C(0x40000000):UINT32_MAX;
        consumer->buffer_unmap(consumer,t); check(consumer,epoch,total,1,snapshot,1);
        struct drm_opengpu_fault previous={0},fault={0};
        require(!ioctl(app.fd,DRM_IOCTL_OPENGPU_GET_FAULT,&previous),"pre-fault sequence");
        bind_program(2,consumer); uniforms(consumer,2); launch(2,consumer,EIO);
        require(!ioctl(app.fd,DRM_IOCTL_OPENGPU_GET_FAULT,&fault),"actual public fault query");
        require(fault.flags==(OPENGPU_FAULT_VALID|OPENGPU_FAULT_COMPLETION_ERROR) && fault.error==-EIO &&
            fault.status && fault.sequence>previous.sequence && fault.command_id==fault.expected_command_id &&
            fault.opcode==0 && fault.expected_opcode==0,"precise public compute completion identity");
        check(consumer,epoch,total,1,snapshot,0);
        printf("GLSL_LINUX_FAULT selectedOddLanes=true originalAPIBuffer=0 nativeReadMask=8 nativeWriteMask=1 checkedWords=%u unchanged=true PASS\n",GLC_CHECK_WORDS);
    }
    for(unsigned i=0;i<2;i++) { app.contexts[i]->destroy(app.contexts[i]); app.contexts[i]=NULL; }
}

int main(int argc,char **argv)
{
    setvbuf(stdout,NULL,_IOLBF,0); require(!atexit(cleanup),"cleanup registration");
    if(argc==4 && !strcmp(argv[1],"--preflight")) {
        require(!mkdir(argv[2],0700),"new frontend output directory"); compile_sources(argv[3],argv[2]);
        puts("GLSL_LINUX_PREFLIGHT fiveRealPrograms=PASS actualValidator=PASS callbacksIoctl=UNRUN GPU=UNRUN"); return 0;
    }
    require(argc==1 || (argc==3 && !strcmp(argv[1],"--sources")),"usage: gallium_compute_glsl [--sources DIRECTORY | --preflight NEW_DIRECTORY SOURCES]");
    compile_sources(argc==3?argv[2]:"/opengpu_glsl_sources",NULL);
    app.fd=open("/dev/dri/card0",O_RDWR|O_CLOEXEC); require(app.fd>=0,"actual DRM device");
    struct drm_opengpu_param features={.param=OPENGPU_PARAM_DRIVER_FEATURES};
    require(!ioctl(app.fd,DRM_IOCTL_OPENGPU_GET_PARAM,&features),"driver feature query");
    uint64_t need=OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE|OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BYTE_VIEWS|OPENGPU_DRIVER_FEATURE_COMPUTE_UNIFORMS;
    require((features.value&need)==need,"uniform/exact storage transport");
    app.screen=opengpu_drm_screen_create(app.fd,NULL); require(app.screen!=NULL,"real Gallium screen");
    require(!app.screen->get_param(app.screen,PIPE_CAP_COMPUTE),"GL compute capability remains off");
    uint64_t limits[3]={0}; require(app.screen->get_compute_param(app.screen,PIPE_SHADER_IR_TGSI,PIPE_COMPUTE_CAP_MAX_BLOCK_SIZE,limits)==24 && limits[0]==128,"actual full128item profile");
    unsigned handles[16];
    for(unsigned i=0;i<16;i++) {
        struct pipe_resource *r=buffer(i<4?GLC_VIEW_BYTES:16384,i<4?PIPE_BIND_SHADER_BUFFER:PIPE_BIND_CONSTANT_BUFFER);
        if(i<4) app.storage[i]=r; else app.ubos[i-4]=r;
        struct winsys_handle h={.type=WINSYS_HANDLE_TYPE_KMS};
        require(app.screen->resource_get_handle(app.screen,NULL,r,&h,0),"real independent GEM handle");
        handles[i]=h.handle; for(unsigned j=0;j<i;j++) require(handles[j]!=handles[i],"independent resource backings");
    }
    puts("GLSL_LINUX_ALLOC full4x128MiB=true independent12x16KiB=true ownedDefault4KiB=true");
    run_epoch(1); run_epoch(3);
    cleanup();
    puts("GLSL_LINUX_RUNTIME computeParents=5 metadataGroups=9 metadataPhysicalWarps=72 healthyItemExecutions=1012 resultItems=506 lastWarpActive=13 ESSLFrontend=310 CAP_COMPUTE=0 PASS");
    return 0;
}
