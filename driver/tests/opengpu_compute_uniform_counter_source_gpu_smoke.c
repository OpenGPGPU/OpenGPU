/* SPDX-License-Identifier: MIT */
/* Explicit full-view Linux/RTL compute qualification. Driver probe retains its
 * normal tiny render self-test; this workload submits only compute commands. */
#include "opengpu_compute_smoke_util.h"
#include "opengpu_compute_uniform_counter_source_program.h"
#include <stdlib.h>

#define GUARD 0xa5a5a5a5u
#define COUNTER_OFFSET 4080u
#define TRY(x) do{if((x)<0){perror(#x);return -1;}}while(0)
#define REQUIRE(x) do{if(!(x)){fprintf(stderr,"failed: %s line%d\n",#x,__LINE__);return -1;}}while(0)
struct resources {
    struct buffer shader,kernarg,uniforms[12],defaults,textures[32],storage[4],counter;
};
struct peer {
    struct drm_opengpu_context context;
    struct drm_syncobj_create sync;
    struct buffer shader,kernarg;
    struct drm_opengpu_compute_texture_view view;
};
static uint32_t load32(const struct buffer *b,uint32_t byte)
{
    return ((volatile uint32_t *)b->map)[byte/4];
}
static void fill(struct buffer *b,uint32_t byte,uint32_t bytes,uint32_t value)
{
    uint32_t *p=(uint32_t *)((uint8_t *)b->map+byte);
    for(unsigned i=0;i<bytes/4;i++)p[i]=value;
}
static uint32_t block_value(unsigned e,unsigned b,unsigned offset)
{
    return 0xa1000000u^(e<<20)^(b<<16)^offset;
}
static uint32_t default_base(unsigned e){return 0xd1000000u+(e<<20);}
static uint32_t texel(unsigned e,unsigned u,unsigned x){return 0x11000000u+(e<<20)+(u<<12)+x;}
static uint32_t first(unsigned e,unsigned u){return 0x21000000u+(e<<20)+u;}
static uint32_t last(unsigned e,unsigned u){return 0x22000000u+(e<<20)+u;}
static uint32_t initial_counter(unsigned e,unsigned c){return e*1000+c*100;}
static int buffer_close(int fd,struct buffer *b)
{
    if(!b->handle)return 0;
    TRY(munmap(b->map,b->size));
    struct drm_gem_close close={.handle=b->handle};TRY(ioctl(fd,DRM_IOCTL_GEM_CLOSE,&close));
    memset(b,0,sizeof(*b));return 0;
}
static int sync_close(int fd,struct drm_syncobj_create *s)
{
    struct drm_syncobj_destroy d={.handle=s->handle};TRY(ioctl(fd,DRM_IOCTL_SYNCOBJ_DESTROY,&d));
    s->handle=0;return 0;
}
static struct drm_opengpu_compute launch(uint32_t context,uint32_t sync,unsigned groups)
{
    return (struct drm_opengpu_compute){.context_id=context,.shader_slot=1,.kernarg_slot=2,
        .grid={groups,1,1},.local={128,1,1},.out_syncobj=sync};
}
static struct drm_opengpu_compute_texture_view texture_view(unsigned slot,unsigned width)
{
    struct drm_opengpu_compute_texture_view v={.slot=slot,.format=14,.width=width,.height=1,
        .depth=1,.layers=1,.level_count=1};
    v.levels[0]=(struct drm_opengpu_compute_texture_level){.width=width,.height=1,.depth=1,
        .layers=1,.row_pitch=4u*width,.block_bytes=4};return v;
}
static int code_publish(struct buffer *shader,const uint32_t *code,unsigned words)
{
    REQUIRE(words && words<=512);memset(shader->map,0,2048);memcpy(shader->map,code,words*4);return 0;
}
static int peer_create(int fd,struct peer *p,struct buffer *kernarg)
{
    TRY(create_buffer(fd,4096,&p->shader));TRY(ioctl(fd,DRM_IOCTL_OPENGPU_CONTEXT_CREATE,&p->context));
    TRY(ioctl(fd,DRM_IOCTL_SYNCOBJ_CREATE,&p->sync));
    TRY(bind_buffer(fd,p->context.id,1,&p->shader,OPENGPU_RESOURCE_COMPUTE_SHADER,2048));
    TRY(bind_buffer(fd,p->context.id,2,kernarg,OPENGPU_RESOURCE_COMPUTE_KERNARG,kernarg->size));return 0;
}
static int peer_close(int fd,struct peer *p)
{
    TRY(ioctl(fd,DRM_IOCTL_OPENGPU_CONTEXT_DESTROY,&p->context));TRY(sync_close(fd,&p->sync));
    TRY(buffer_close(fd,&p->shader));TRY(buffer_close(fd,&p->kernarg));return 0;
}
static int fence_status(int fd,uint32_t handle)
{
    struct drm_syncobj_handle h={.handle=handle,.flags=DRM_SYNCOBJ_HANDLE_TO_FD_FLAGS_EXPORT_SYNC_FILE};
    struct sync_file_info info={0};TRY(ioctl(fd,DRM_IOCTL_SYNCOBJ_HANDLE_TO_FD,&h));
    int ret=ioctl(h.fd,SYNC_IOC_FILE_INFO,&info);int saved=errno;close(h.fd);errno=saved;TRY(ret);return info.status;
}
static int allocate(int fd,struct resources *r)
{
    TRY(create_buffer(fd,4096,&r->shader));TRY(create_buffer(fd,4096,&r->kernarg));
    for(unsigned i=0;i<12;i++)TRY(create_buffer(fd,16384,&r->uniforms[i]));
    TRY(create_buffer(fd,4096,&r->defaults));
    for(unsigned i=0;i<32;i++)TRY(create_buffer(fd,4096,&r->textures[i]));
    for(unsigned i=0;i<4;i++){
        TRY(create_buffer(fd,OPG_STORAGE_BYTES,&r->storage[i]));
        REQUIRE(r->storage[i].size>=OPG_STORAGE_BYTES);
        for(unsigned j=0;j<i;j++)REQUIRE(r->storage[j].handle!=r->storage[i].handle);
    }
    TRY(create_buffer(fd,8192,&r->counter));return 0;
}
static void prepare(struct resources *r,unsigned epoch)
{
    /* Called only before a new parent, after all preceding fences completed. */
    fill(&r->kernarg,0,4096,GUARD);fill(&r->defaults,0,4096,GUARD);
    for(unsigned i=0;i<12;i++)for(unsigned offset=0;offset<16384;offset+=4)
        ((uint32_t *)r->uniforms[i].map)[offset/4]=block_value(epoch,i,offset);
    for(unsigned i=0;i<32;i++){
        fill(&r->textures[i],0,4096,GUARD);
        for(unsigned x=0;x<4;x++)((uint32_t *)r->textures[i].map)[x]=texel(epoch,i,x);
    }
    for(unsigned i=0;i<4;i++){
        fill(&r->storage[i],0,8192,GUARD);fill(&r->storage[i],OPG_STORAGE_BYTES-4096,4096,GUARD);
        ((uint32_t *)r->storage[i].map)[0]=first(epoch,i);
        ((uint32_t *)r->storage[i].map)[OPG_STORAGE_BYTES/4-1]=last(epoch,i);
    }
    for(unsigned g=0;g<2;g++)fill(&r->storage[0],OPG_RESULT_BASE+g*OPG_GROUP_STRIDE,OPG_GROUP_STRIDE,GUARD);
    fill(&r->counter,0,8192,GUARD);
    for(unsigned c=0;c<8;c++)((uint32_t *)r->counter.map)[COUNTER_OFFSET/4+c]=initial_counter(epoch,c);
}
static int bind_all(int fd,struct resources *r,uint32_t context,
                    struct drm_opengpu_compute_counter_source_resources *s,
                    struct drm_opengpu_compute_texture_view views[32])
{
    TRY(bind_buffer(fd,context,1,&r->shader,OPENGPU_RESOURCE_COMPUTE_SHADER,2048));
    TRY(bind_buffer(fd,context,2,&r->kernarg,OPENGPU_RESOURCE_COMPUTE_KERNARG,4096));
    *s=(struct drm_opengpu_compute_counter_source_resources){.version=1,
        .resources={.version=1,.active_mask=0xfff},
        .source={.handle=r->counter.handle,.offset=COUNTER_OFFSET,.count=8}};
    struct drm_opengpu_compute_uniform_resources *u=&s->resources;
    u->resources.launch=launch(context,0,2);u->resources.texture_count=32;u->resources.textures=(uintptr_t)views;
    u->resources.resource_flags=OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE|OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES;
    for(unsigned i=0;i<12;i++)u->blocks[i]=(struct drm_opengpu_compute_uniform_view){.handle=r->uniforms[i].handle,.bytes=16384};
    u->defaults=(struct drm_opengpu_compute_uniform_view){.handle=r->defaults.handle,.bytes=4096};
    for(unsigned i=0;i<32;i++){
        TRY(bind_buffer(fd,context,3+i,&r->textures[i],OPENGPU_RESOURCE_COMPUTE_TEXTURE,4096));
        views[i]=texture_view(3+i,4);
    }
    for(unsigned i=0;i<4;i++){
        TRY(bind_buffer(fd,context,35+i,&r->storage[i],OPENGPU_RESOURCE_COMPUTE_STORAGE,OPG_STORAGE_BYTES));
        u->resources.bindings[i]=(struct drm_opengpu_compute_storage_binding){35+i,
            OPENGPU_COMPUTE_STORAGE_READ|OPENGPU_COMPUTE_STORAGE_WRITE};
        u->resources.images[i]=(struct drm_opengpu_compute_image_view){.slot=35+i,.offset=4096,
            .flags=OPENGPU_COMPUTE_IMAGE_READ|OPENGPU_COMPUTE_IMAGE_WRITE,.storage_format=14,.image_format=14,
            .width=64,.height=1,.depth=1,.layers=1,.row_pitch=256};
    }
    return 0;
}
static int check_all(const struct resources *r,unsigned epoch)
{
    bool old[8][32]={{false}};
    for(unsigned g=0;g<2;g++)for(unsigned p=0;p<OPG_FULL_PLANES;p++)for(unsigned i=0;i<128;i++){
        uint32_t got=load32(&r->storage[0],OPG_RESULT_BASE+g*OPG_GROUP_STRIDE+p*512+4*i),want=0;
        if(p==0)want=i;else if(p==1)want=g;else if(p==2)want=0x40400000u;
        else if(p<27)want=block_value(epoch,(p-3)/2,(p-3)%2?16380:0);
        else if(p<35)want=default_base(epoch)+(p-27)*128+i;
        else if(p==35)want=texel(epoch,i&31,i>>5);
        else if(p==36)want=first(epoch,i&3);else if(p==37)want=last(epoch,i&3);
        else if(p==38)want=last(epoch,i&3)+i;
        else{
            unsigned c=i&7;uint32_t n=got-initial_counter(epoch,c);
            REQUIRE(n<32 && !old[c][n]);old[c][n]=true;want=got;
        }
        if(got!=want){fprintf(stderr,"epoch%u group%u plane%u item%u got=%08x want=%08x\n",epoch,g,p,i,got,want);return -1;}
    }
    for(unsigned c=0;c<8;c++)for(unsigned n=0;n<32;n++)REQUIRE(old[c][n]);
    for(unsigned g=0;g<2;g++)for(unsigned i=OPG_FULL_PLANES*128;i<OPG_GROUP_STRIDE/4;i++)
        REQUIRE(load32(&r->storage[0],OPG_RESULT_BASE+g*OPG_GROUP_STRIDE+4*i)==GUARD);
    for(unsigned u=0;u<4;u++){
        for(unsigned i=0;i<1024;i++){
            REQUIRE(load32(&r->storage[u],4*i)==(i?GUARD:first(epoch,u)));
            REQUIRE(load32(&r->storage[u],OPG_STORAGE_BYTES-4096+4*i)==(i==1023?last(epoch,u):GUARD));
            uint32_t want=i<64?last(epoch,u)+(i%32)*4+u:GUARD;
            REQUIRE(load32(&r->storage[u],4096+4*i)==want);
        }
    }
    for(unsigned u=0;u<32;u++)for(unsigned i=0;i<1024;i++)
        REQUIRE(load32(&r->textures[u],4*i)==(i<4?texel(epoch,u,i):GUARD));
    for(unsigned b=0;b<12;b++)for(unsigned o=0;o<16384;o+=4)REQUIRE(load32(&r->uniforms[b],o)==block_value(epoch,b,o));
    for(unsigned i=0;i<1024;i++){
        REQUIRE(load32(&r->defaults,4*i)==default_base(epoch)+i);REQUIRE(load32(&r->kernarg,4*i)==GUARD);
    }
    for(unsigned i=0;i<2048;i++){
        bool selected=i>=COUNTER_OFFSET/4 && i<COUNTER_OFFSET/4+8;
        uint32_t want=selected?initial_counter(epoch,i-COUNTER_OFFSET/4)+32:GUARD;
        REQUIRE(load32(&r->counter,4*i)==want);
    }
    return 0;
}
static int run_epoch(int fd,struct resources *r,unsigned epoch)
{
    struct drm_opengpu_context context={0};struct drm_syncobj_create done={0};
    struct drm_opengpu_compute_counter_source_resources submit;
    struct drm_opengpu_compute_texture_view views[32];struct peer producer={0},consumer={0};
    uint32_t code[512];prepare(r,epoch);
    TRY(ioctl(fd,DRM_IOCTL_OPENGPU_CONTEXT_CREATE,&context));TRY(ioctl(fd,DRM_IOCTL_SYNCOBJ_CREATE,&done));
    TRY(code_publish(&r->shader,code,opg_full_program(code,false)));TRY(bind_all(fd,r,context.id,&submit,views));
    submit.resources.resources.launch.out_syncobj=done.handle;
    TRY(peer_create(fd,&producer,&r->defaults));
    TRY(code_publish(&producer.shader,code,opg_default_producer(code,default_base(epoch))));
    TRY(create_buffer(fd,4096,&consumer.kernarg));fill(&consumer.kernarg,0,4096,GUARD);
    TRY(peer_create(fd,&consumer,&consumer.kernarg));
    TRY(code_publish(&consumer.shader,code,opg_counter_consumer(code)));
    struct drm_opengpu_resource binding={.context_id=consumer.context.id,.slot=3,.handle=r->counter.handle,
        .type=OPENGPU_RESOURCE_COMPUTE_TEXTURE,.size=8192,.flags=OPENGPU_RESOURCE_UNCACHED};
    TRY(ioctl(fd,DRM_IOCTL_OPENGPU_RESOURCE_BIND,&binding));consumer.view=texture_view(3,2048);
    struct drm_opengpu_compute produce=launch(producer.context.id,producer.sync.handle,1);
    TRY(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE,&produce));
    submit.resources.resources.launch.in_syncobj=producer.sync.handle;
    puts("native Linux full resources: submitting");fflush(stdout);
    TRY(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE_COUNTER_SOURCE_RESOURCES,&submit));
    int pending=fence_status(fd,done.handle);REQUIRE(pending>=0);
    struct drm_opengpu_compute_textures consume={.launch=launch(consumer.context.id,consumer.sync.handle,1),
        .views=(uintptr_t)&consumer.view,.view_count=1};
    TRY(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE_TEXTURES,&consume));
    TRY(wait_fence(fd,done.handle));TRY(wait_fence(fd,producer.sync.handle));TRY(wait_fence(fd,consumer.sync.handle));
    TRY(check_all(r,epoch));
    for(unsigned i=0;i<1024;i++){
        uint32_t want=i>=16 && i<144?initial_counter(epoch,(i-16)&7)+32:GUARD;
        REQUIRE(load32(&consumer.kernarg,4*i)==want);
    }
    printf("NATIVE_LINUX_RESOURCES epoch=%u groups=2 items=128 blocks=12 blockBytes=16384 defaults=1024 textures=32 images=4 ssbo=4 ssboBytes=134217728 counters=8 resultWords=10240 counterTwoPages=true originalCounterTexture=true pendingBeforeConsumer=%s PASS\n",epoch,pending==0?"true":"false");
    TRY(peer_close(fd,&producer));TRY(peer_close(fd,&consumer));
    /* Full resources remain published; the first operation faults, before any
     * result store or atomic. No CPU writes occur while a prior job owns them. */
    if(epoch==1){
        TRY(code_publish(&r->shader,code,opg_full_program(code,true)));
        submit.resources.resources.launch.in_syncobj=0;TRY(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE_COUNTER_SOURCE_RESOURCES,&submit));
        errno=0;REQUIRE(wait_fence(fd,done.handle)==-1 && errno==EIO);
        struct drm_opengpu_fault fault={0};TRY(ioctl(fd,DRM_IOCTL_OPENGPU_GET_FAULT,&fault));
        REQUIRE(fault.flags==(OPENGPU_FAULT_VALID|OPENGPU_FAULT_COMPLETION_ERROR) &&
            fault.error==-EIO && fault.status!=0 && fault.sequence!=0 &&
            fault.command_id==fault.expected_command_id && fault.opcode==fault.expected_opcode);
        TRY(check_all(r,epoch));printf("NATIVE_LINUX_RESOURCES_FAULT epoch=%u defaultOffset=4096 intactResults=true intactCounter=true PASS\n",epoch);
    }
    TRY(ioctl(fd,DRM_IOCTL_OPENGPU_CONTEXT_DESTROY,&context));TRY(sync_close(fd,&done));return 0;
}
int main(void)
{
    int fd=open("/dev/dri/card0",O_RDWR|O_CLOEXEC);if(fd<0){perror("DRM open");return 1;}
    struct drm_opengpu_param features={.param=OPENGPU_PARAM_DRIVER_FEATURES};
    struct drm_opengpu_param items={.param=OPENGPU_PARAM_COMPUTE_LOCAL_ITEMS},axis={.param=OPENGPU_PARAM_COMPUTE_LOCAL_AXIS};
    const uint64_t need=OPENGPU_DRIVER_FEATURE_COMPUTE_UNIFORMS|OPENGPU_DRIVER_FEATURE_COMPUTE_COUNTER_SOURCE|
        OPENGPU_DRIVER_FEATURE_COMPUTE_ATOMIC_COUNTERS|OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_TEXTURE;
    TRY(ioctl(fd,DRM_IOCTL_OPENGPU_GET_PARAM,&features));TRY(ioctl(fd,DRM_IOCTL_OPENGPU_GET_PARAM,&items));
    TRY(ioctl(fd,DRM_IOCTL_OPENGPU_GET_PARAM,&axis));REQUIRE((features.value&need)==need && items.value==128 && axis.value==128);
    struct resources r={0};TRY(allocate(fd,&r));TRY(run_epoch(fd,&r,1));
    /* All first-epoch fences/contexts are retired before original mutable
     * storage and source bytes change. The new counter has a fresh GEM handle. */
    TRY(buffer_close(fd,&r.counter));TRY(create_buffer(fd,8192,&r.counter));TRY(run_epoch(fd,&r,3));
    TRY(buffer_close(fd,&r.shader));TRY(buffer_close(fd,&r.kernarg));TRY(buffer_close(fd,&r.defaults));
    TRY(buffer_close(fd,&r.counter));
    for(unsigned i=0;i<12;i++)TRY(buffer_close(fd,&r.uniforms[i]));
    for(unsigned i=0;i<32;i++)TRY(buffer_close(fd,&r.textures[i]));
    for(unsigned i=0;i<4;i++)TRY(buffer_close(fd,&r.storage[i]));
    close(fd);puts("OPENGPU Linux uniform counter source: full views, actual instructions, fault and fresh reuse PASS");return 0;
}
