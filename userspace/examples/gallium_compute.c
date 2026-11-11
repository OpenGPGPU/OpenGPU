/* SPDX-License-Identifier: MIT */
/* Explicit headless callback -> Linux execution smoke. --preflight never
 * opens a device and establishes no hardware execution result. */
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
#include "opengpu_public.h"
#include "opengpu_cs_emit.h"
#include "opengpu_drm.h"
#include "opengpu_shader_validator.h"
#include "gallium_compute_program.h"

static void require(int condition,const char *what)
{
    if(!condition){fprintf(stderr,"gallium-compute: %s (errno=%d: %s)\n",what,errno,strerror(errno));exit(1);}
}

static uint64_t ns(void)
{
    struct timespec t;require(!clock_gettime(CLOCK_MONOTONIC,&t),"monotonic clock");
    return (uint64_t)t.tv_sec*UINT64_C(1000000000)+(uint64_t)t.tv_nsec;
}

static void shape(unsigned epoch,struct pipe_grid_info *grid)
{
    memset(grid,0,sizeof(*grid));grid->grid[0]=2;grid->grid[1]=grid->grid[2]=1;
    grid->block[0]=epoch==1?8:5;grid->block[1]=grid->block[2]=epoch==1?4:5;
}

static int preflight(const char *directory)
{
    require(!mkdir(directory,0700),"new preflight output directory");
    {
        struct gc_program tgsi;struct opengpu_cs_program code;
        gc_indirect_build(&tgsi);require(!opengpu_cs_emit(tgsi.tokens,&code),"indirect producer TGSI compiler");
        require(code.words<=512 && !code.reads && code.writes==3 &&
            opengpu_compute_shader_validate_words_storage(code.code,512,12,4,false),
            "indirect producer validator");
        printf("GALLIUM_RUNTIME_PREFLIGHT indirect words=%u validator=PASS GPU=unrun\n",code.words);
    }
    for(unsigned epoch=1;epoch<=3;epoch+=2)for(unsigned consumer=0;consumer<2;consumer++){
        struct gc_program tgsi;struct opengpu_cs_program code;
        uint32_t seed=epoch==1?UINT32_C(0x12345):UINT32_MAX;
        gc_build(&tgsi,seed,(int)consumer);require(!opengpu_cs_emit(tgsi.tokens,&code),"actual TGSI compiler");
        struct pipe_grid_info grid;shape(epoch,&grid);
        unsigned items=grid.block[0]*grid.block[1]*grid.block[2],total=2*items;
        require(code.words<=512 && code.reads==(consumer?1u:0u) && code.writes==(consumer?14u:1u),"native words/resource use");
        require(opengpu_compute_shader_validate_words_storage(code.code,512,12,items,false),"unchanged kernel validator");
        require(!opengpu_compute_shader_validate_words_storage(code.code,512,8,items,false),"short kernarg rejection");
        uint32_t invalid[512];memcpy(invalid,code.code,sizeof(invalid));invalid[0]=0xffffffffu;
        require(!opengpu_compute_shader_validate_words_storage(invalid,512,12,items,false),"illegal instruction rejection");
        require(gc_offset(total-1,total)==GC_VIEW_BYTES-4,"exact last word");
        for(unsigned i=0;i<total;i++){
            uint32_t offset=gc_offset(i,total);
            require(offset<=GC_VIEW_BYTES && 4<=GC_VIEW_BYTES-offset,"full view address subtraction bound");
            require((i==total-1 && offset==GC_VIEW_BYTES-4) || offset<GC_PREFIX_BYTES,"bounded prefix/tail accesses");
            for(unsigned binding=0;binding<4;binding++)(void)gc_expected(binding,i,seed);
        }
        char path[4096];
        require(snprintf(path,sizeof(path),"%s/epoch%u-%c.words",directory,epoch,consumer?'c':'p')>0,"program path");
        FILE *f=fopen(path,"wx");require(f!=NULL,"new native word file");
        require(fwrite(code.code,1,sizeof(code.code),f)==sizeof(code.code) && !fclose(f),"native word output");
        require(snprintf(path,sizeof(path),"%s/epoch%u-%c.tgsi",directory,epoch,consumer?'c':'p')>0,"TGSI path");
        f=fopen(path,"wx");require(f!=NULL,"new TGSI file");
        unsigned tokens=tgsi.header->HeaderSize+tgsi.header->BodySize;
        require(fwrite(tgsi.tokens,4,tokens,f)==tokens && !fclose(f),"TGSI output");
        printf("GALLIUM_RUNTIME_PREFLIGHT epoch=%u role=%c words=%u local=%u,%u,%u grid=2,1,1 items=%u views=4 viewBytes=%u finalOffset=%u reads=%u writes=%u validator=PASS GPU=unrun\n",
            epoch,consumer?'c':'p',code.words,grid.block[0],grid.block[1],grid.block[2],total,GC_VIEW_BYTES,GC_VIEW_BYTES-4,code.reads,code.writes);
    }
    return 0;
}

static void *state(struct pipe_context *context,uint32_t seed,int consumer)
{
    struct gc_program program;gc_build(&program,seed,consumer);
    struct pipe_compute_state input={.ir_type=PIPE_SHADER_IR_TGSI,.prog=program.tokens};
    void *cs=context->create_compute_state(context,&input);require(cs!=NULL,"create actual TGSI compute state");
    context->bind_compute_state(context,cs);return cs;
}

static void views(struct pipe_context *context,struct pipe_resource *buffers[4],int consumer,int fault)
{
    struct pipe_shader_buffer bindings[4];
    for(unsigned i=0;i<4;i++)bindings[i]=(struct pipe_shader_buffer){buffers[i],0,fault && !i?3:GC_VIEW_BYTES};
    context->set_shader_buffers(context,PIPE_SHADER_COMPUTE,0,4,bindings,consumer?14:1);
}

static uint32_t *map(struct pipe_context *context,struct pipe_resource *buffer,
                      unsigned offset,unsigned bytes,unsigned usage,struct pipe_transfer **transfer)
{
    struct pipe_box box={.x=(int)offset,.width=(int)bytes,.height=1,.depth=1};
    uint32_t *data=context->buffer_map(context,buffer,0,usage,&box,transfer);
    require(data!=NULL && *transfer!=NULL,"public buffer mapping");return data;
}

static void indirect_runtime(struct pipe_screen *screen)
{
    struct pipe_context *p=screen->context_create(screen,NULL,0);require(p!=NULL,"indirect context");
    struct gc_program program;gc_indirect_build(&program);
    struct pipe_compute_state input={.ir_type=PIPE_SHADER_IR_TGSI,.prog=program.tokens};
    void *cs=p->create_compute_state(p,&input);require(cs!=NULL,"indirect compute state");
    p->bind_compute_state(p,cs);
    struct pipe_resource *buffers[2]={0};
    for(unsigned i=0;i<2;i++){
        struct pipe_resource t={.target=PIPE_BUFFER,.format=PIPE_FORMAT_R32_UINT,.width0=4096,
            .height0=1,.depth0=1,.array_size=1,.bind=PIPE_BIND_SHADER_BUFFER};
        buffers[i]=screen->resource_create(screen,&t);require(buffers[i]!=NULL,"indirect buffer");
        struct pipe_transfer *transfer=NULL;
        uint32_t *data=map(p,buffers[i],0,64,PIPE_MAP_WRITE,&transfer);
        for(unsigned k=0;k<16;k++)data[k]=i?GC_GUARD:0;
        p->buffer_unmap(p,transfer);
    }
    struct pipe_shader_buffer bindings[2]={{buffers[0],0,4096},{buffers[1],0,4096}};
    p->set_shader_buffers(p,PIPE_SHADER_COMPUTE,0,2,bindings,3);
    struct pipe_grid_info producer={.block={4,1,1},.grid={1,1,1}};
    p->launch_grid(p,&producer);
    /* No CPU map or state change separates these launches. */
    struct pipe_grid_info consumer={.work_dim=1,.block={4,1,1},.grid={0,0,0},
                                    .indirect=buffers[0]};
    p->launch_grid(p,&consumer);p->memory_barrier(p,PIPE_BARRIER_ALL);
    struct pipe_transfer *transfer=NULL;
    const uint32_t *data=map(p,buffers[1],0,64,PIPE_MAP_READ,&transfer);
    for(unsigned i=0;i<16;i++)require(data[i]==(i<8?100+i:GC_GUARD),"indirect result and guards");
    p->buffer_unmap(p,transfer);

    uint32_t *args=map(p,buffers[0],0,64,PIPE_MAP_WRITE,&transfer);
    args[0]=0;args[1]=args[2]=1;p->buffer_unmap(p,transfer);
    uint32_t *result=map(p,buffers[1],0,64,PIPE_MAP_WRITE,&transfer);
    for(unsigned i=0;i<16;i++)result[i]=GC_GUARD;
    p->buffer_unmap(p,transfer);
    consumer.grid[0]=consumer.grid[1]=consumer.grid[2]=1;
    p->launch_grid(p,&consumer);p->memory_barrier(p,PIPE_BARRIER_ALL);
    data=map(p,buffers[1],0,64,PIPE_MAP_READ,&transfer);
    for(unsigned i=0;i<16;i++)require(data[i]==GC_GUARD,"zero indirect grid is a no-op");
    p->buffer_unmap(p,transfer);
    consumer.indirect_offset=UINT32_MAX-7u;errno=0;p->launch_grid(p,&consumer);
    require(errno==EINVAL,"overflowing indirect offset rejected");
    p->set_shader_buffers(p,PIPE_SHADER_COMPUTE,0,2,NULL,0);p->delete_compute_state(p,cs);
    for(unsigned i=0;i<2;i++)pipe_resource_reference(&buffers[i],NULL);
    p->destroy(p);
    puts("GALLIUM_LINUX_INDIRECT gpuProducer=true sameContext=true groups=2 zeroNoop=true guards=true PASS");
}

static void initialize(struct pipe_context *context,struct pipe_resource *buffers[4])
{
    for(unsigned binding=0;binding<4;binding++)for(unsigned tail=0;tail<2;tail++){
        unsigned bytes=tail?GC_TAIL_BYTES:GC_PREFIX_BYTES,offset=tail?GC_VIEW_BYTES-GC_TAIL_BYTES:0;
        struct pipe_transfer *transfer=NULL;
        uint32_t *data=map(context,buffers[binding],offset,bytes,PIPE_MAP_WRITE,&transfer);
        for(unsigned word=0;word<bytes/4;word++)data[word]=GC_GUARD;
        context->buffer_unmap(context,transfer);
    }
}

static void check(struct pipe_context *context,struct pipe_resource *buffers[4],
                   unsigned epoch,unsigned consumed)
{
    struct pipe_grid_info grid;shape(epoch,&grid);
    unsigned total=2*grid.block[0]*grid.block[1]*grid.block[2];
    uint32_t seed=epoch==1?UINT32_C(0x12345):UINT32_MAX;
    for(unsigned binding=0;binding<4;binding++)for(unsigned tail=0;tail<2;tail++){
        unsigned bytes=tail?GC_TAIL_BYTES:GC_PREFIX_BYTES,offset=tail?GC_VIEW_BYTES-GC_TAIL_BYTES:0;
        struct pipe_transfer *transfer=NULL;
        const uint32_t *data=map(context,buffers[binding],offset,bytes,PIPE_MAP_READ,&transfer);
        for(unsigned word=0;word<bytes/4;word++){
            unsigned absolute=offset+4*word,item=absolute/4;uint32_t want=GC_GUARD;
            if(absolute==GC_VIEW_BYTES-4)item=total-1;
            if((!binding || consumed) && (item<total-1 || absolute==GC_VIEW_BYTES-4))want=gc_expected(binding,item,seed);
            if(data[word]!=want){
                fprintf(stderr,"gallium-compute mismatch epoch=%u binding=%u offset=%u got=%08x want=%08x\n",epoch,binding,absolute,data[word],want);
                exit(1);
            }
        }
        context->buffer_unmap(context,transfer);
    }
}

static void run_epoch(int fd,struct pipe_screen *screen,struct pipe_resource *buffers[4],unsigned epoch)
{
    uint32_t seed=epoch==1?UINT32_C(0x12345):UINT32_MAX;
    struct pipe_context *producer=screen->context_create(screen,NULL,0),*consumer=screen->context_create(screen,NULL,0);
    require(producer && consumer && producer!=consumer,"two independent pipe contexts");
    void *ps=state(producer,seed,0),*cs=state(consumer,seed,1);
    views(producer,buffers,0,0);views(consumer,buffers,1,0);
    uint64_t start=ns();initialize(producer,buffers);
    printf("GALLIUM_LINUX_UPLOAD epoch=%u bytes=%u lineEnvelopes=516 ns=%llu\n",epoch,4*(GC_PREFIX_BYTES+GC_TAIL_BYTES),(unsigned long long)(ns()-start));
    struct pipe_grid_info grid;shape(epoch,&grid);
    if(epoch==1){
        struct pipe_grid_info invalid=grid;invalid.block[0]=129;errno=0;
        producer->launch_grid(producer,&invalid);require(errno==EINVAL,"oversize local block rejected");
        struct pipe_shader_buffer bad={buffers[0],0,GC_VIEW_BYTES+1};errno=0;
        producer->set_shader_buffers(producer,PIPE_SHADER_COMPUTE,0,1,&bad,1);
        require(errno==EINVAL,"logical view cannot expand");views(producer,buffers,0,0);
    }
    start=ns();producer->launch_grid(producer,&grid);check(consumer,buffers,epoch,0);
    printf("GALLIUM_LINUX_PRODUCER epoch=%u items=%u lastOffset=%u ns=%llu PASS\n",epoch,
        2*grid.block[0]*grid.block[1]*grid.block[2],GC_VIEW_BYTES-4,(unsigned long long)(ns()-start));
    /* No CPU source write or invalidate between producer and consumer. Both
     * calls use the real synchronous completion contract, not a forced wait. */
    start=ns();consumer->launch_grid(consumer,&grid);check(consumer,buffers,epoch,1);
    printf("GALLIUM_LINUX_CONSUMER epoch=%u items=%u originalGEMs=true resultGuardWords=%u ns=%llu PASS\n",epoch,
        2*grid.block[0]*grid.block[1]*grid.block[2],4*(GC_PREFIX_BYTES+GC_TAIL_BYTES)/4,(unsigned long long)(ns()-start));
    if(epoch==1){
        struct drm_opengpu_fault previous={0};
        require(!ioctl(fd,DRM_IOCTL_OPENGPU_GET_FAULT,&previous),"fault sequence before negative");
        views(consumer,buffers,1,1);grid.grid[0]=1;errno=0;
        consumer->launch_grid(consumer,&grid);require(errno==EIO,"real exact three-byte completion error");
        struct drm_opengpu_fault fault={0};require(!ioctl(fd,DRM_IOCTL_OPENGPU_GET_FAULT,&fault),"public fault query");
        require(fault.flags==(OPENGPU_FAULT_VALID|OPENGPU_FAULT_COMPLETION_ERROR) && fault.error==-EIO &&
            fault.status && fault.sequence>previous.sequence && fault.command_id==fault.expected_command_id &&
            fault.opcode==0 && fault.expected_opcode==0,"exact public compute fault identity");
        check(consumer,buffers,epoch,1);
        puts("GALLIUM_LINUX_FAULT epoch=1 inputBytes=3 opcode=0 completion=EIO guardsIntact=true PASS");
    }
    producer->delete_compute_state(producer,ps);consumer->delete_compute_state(consumer,cs);
    producer->destroy(producer);consumer->destroy(consumer);
}

int main(int argc,char **argv)
{
    setvbuf(stdout,NULL,_IOLBF,0);
    if(argc==3 && !strcmp(argv[1],"--preflight"))return preflight(argv[2]);
    require(argc==1 || (argc==3 && !strcmp(argv[1],"--device")),"usage: gallium_compute [--preflight NEW_DIRECTORY | --device DRM_PATH]");
    int fd=open(argc==3?argv[2]:"/dev/dri/card0",O_RDWR|O_CLOEXEC);require(fd>=0,"real DRM device open");
    struct drm_opengpu_param feature={.param=OPENGPU_PARAM_DRIVER_FEATURES};
    require(!ioctl(fd,DRM_IOCTL_OPENGPU_GET_PARAM,&feature),"driver features");
    uint64_t need=OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE|OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BYTE_VIEWS;
    require((feature.value&need)==need,"checked storage and exact byte-view feature negotiation");
    struct pipe_screen *screen=opengpu_drm_screen_create(fd,NULL);require(screen!=NULL,"actual Gallium screen");
    require(!screen->get_param(screen,PIPE_CAP_COMPUTE),"bounded callbacks do not enable GL compute");
    uint64_t limits[3]={0};require(screen->get_compute_param(screen,PIPE_SHADER_IR_TGSI,PIPE_COMPUTE_CAP_MAX_BLOCK_SIZE,limits)==24 && limits[0]==128,"native 128-item limit");
    if(getenv("OPENGPU_GALLIUM_INDIRECT_ONLY")){
        indirect_runtime(screen);screen->destroy(screen);require(!close(fd),"DRM close");return 0;
    }
    struct pipe_resource *buffers[4]={0};unsigned handles[4];uint64_t start=ns();
    for(unsigned i=0;i<4;i++){
        struct pipe_resource t={.target=PIPE_BUFFER,.format=PIPE_FORMAT_R32_UINT,.width0=GC_VIEW_BYTES,
            .height0=1,.depth0=1,.array_size=1,.bind=PIPE_BIND_SHADER_BUFFER};
        buffers[i]=screen->resource_create(screen,&t);require(buffers[i]!=NULL && buffers[i]->width0==GC_VIEW_BYTES,"full-size independent buffer");
        struct winsys_handle handle={.type=WINSYS_HANDLE_TYPE_KMS};
        require(screen->resource_get_handle(screen,NULL,buffers[i],&handle,0),"original GEM handle");
        handles[i]=handle.handle;for(unsigned j=0;j<i;j++)require(handles[i]!=handles[j],"distinct GEM backings");
    }
    printf("GALLIUM_LINUX_ALLOC buffers=4 bytesPerBuffer=%u cpuZeroExtent=%llu ns=%llu\n",GC_VIEW_BYTES,
        (unsigned long long)4*GC_VIEW_BYTES,(unsigned long long)(ns()-start));
    run_epoch(fd,screen,buffers,1);run_epoch(fd,screen,buffers,3);
    for(unsigned i=0;i<4;i++)pipe_resource_reference(&buffers[i],NULL);
    screen->destroy(screen);require(!close(fd),"DRM close");
    puts("GALLIUM_LINUX_RUNTIME computeParents=5 metadataGroups=9 metadataPhysicalWarps=72 healthyItemExecutions=1012 resultItems=506 lastWarpActive=13 CAP_COMPUTE=0 PASS");
    return 0;
}
