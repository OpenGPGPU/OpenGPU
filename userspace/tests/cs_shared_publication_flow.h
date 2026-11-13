#include "cs_shared_word_vm.h"
/* Actual Mesa image/barrier words plus real immutable kernel resource records.
 * The independent CPU oracle checks compiler/publication, not GPU coherence. */
static void shared_publication_write(const char *directory,const char *name,const char *suffix,
    const void *data,size_t bytes)
{
    char path[4096];assert(snprintf(path,sizeof(path),"%s/%s.%s",directory,name,suffix)>0);
    FILE *f=fopen(path,"wx");assert(f);assert(fwrite(data,1,bytes,f)==bytes&&!fclose(f));
}

static void shared_publication_export(const char *directory,const char *name,const struct buffer_job *j)
{
    shared_publication_write(directory,name,"shader",j->records.code,j->records.code_bytes);
    if(j->records.texture_bytes)shared_publication_write(directory,name,"textures",j->records.textures,j->records.texture_bytes);
    if(j->records.image_bytes)shared_publication_write(directory,name,"images",j->records.images,j->records.image_bytes);
    shared_publication_write(directory,name,"storage",j->records.storage,j->records.storage_bytes);
    char text[16384];unsigned used=0;
#define IMAGE_FIELD(...) do {int n=snprintf(text+used,sizeof(text)-used,__VA_ARGS__); \
    assert(n>0&&(unsigned)n<sizeof(text)-used);used+=(unsigned)n;}while(0)
    IMAGE_FIELD("context=%u\ntexture.root=%u\nimage.root=%u\nstorage.root=%u\n",
        j->records.context,j->records.texture_root,j->records.image_root,j->records.storage_root);
    unsigned segments=0;
    for(unsigned slot=2;slot<43;slot++) {
        const struct drm_opengpu_resource *b=&j->input->bindings[slot];if(!b->handle)continue;
        assert(b->size<=1536&&b->offset+b->size<=bos[b->handle].size);
        char suffix[64];assert(snprintf(suffix,sizeof(suffix),"memory%u",segments)>0);
        shared_publication_write(directory,name,suffix,bos[b->handle].data+b->offset,b->size);
        IMAGE_FIELD("memory.%u.va=%llu\nmemory.%u.bytes=%llu\nmemory.%u.slot=%u\n"
            "memory.%u.handle=%u\nmemory.%u.offset=%llu\n",segments,
            (unsigned long long)j->records.binding_va[slot],segments,(unsigned long long)b->size,
            segments,slot,segments,b->handle,segments,(unsigned long long)b->offset);
        segments++;
    }
    IMAGE_FIELD("segments=%u\n",segments);
    shared_publication_write(directory,name,"properties",text,used);
#undef IMAGE_FIELD
}

static void shared_publication_execute(struct buffer_job *j)
{
    assert(j->input->command.launch.local[0]==128&&limit_shared_bytes==16384&&limit_shared_lanes==16);
    const void *binding=j->records.storage;assert(get32(binding,7)&&get32(binding,9));
    unsigned base=get32(binding,10);assert(get64(binding,11)==(uint64_t)base+1024);
    uint32_t expected[256],reverse[256];
    for(unsigned i=0;i<256;i++)expected[i]=reverse[i]=0xdead5678u;
    unsigned words=(unsigned)j->records.code_bytes/4;
    shared_word_oracle_barriers(j->records.code,words,limit_shared_base,expected,0,shared_publication_seed,0,3);
    shared_word_oracle_barriers(j->records.code,words,limit_shared_base,reverse,0,shared_publication_seed,1,3);
    assert(!memcmp(expected,reverse,sizeof(expected)));
    memcpy(data_address(j,base,sizeof(expected)),expected,sizeof(expected));
}
static void shared_publication_flow(const char *directory)
{
    limit_shared_base=0x10000000u;limit_shared_bytes=16384;limit_shared_lanes=16;
    struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
    struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
    struct pipe_resource *global=new_buffer(s,1536),*output=new_buffer(s,512);
    struct pipe_sampler_view description={.format=PIPE_FORMAT_R32_UINT,.target=PIPE_BUFFER,
        .swizzle_r=0,.swizzle_g=1,.swizzle_b=2,.swizzle_a=3};description.u.buf.offset=256;description.u.buf.size=1024;
    struct pipe_sampler_view *sampler=p->create_sampler_view(p,global,&description);assert(sampler);
    struct pipe_image_view image={.resource=global,.format=PIPE_FORMAT_R32_UINT,
        .access=PIPE_IMAGE_ACCESS_READ,.shader_access=PIPE_IMAGE_ACCESS_READ};image.u.buf.offset=256;image.u.buf.size=1024;
    const char *names[]={"producer-1","producer-2","image-consumer","texture-consumer"};
    for(unsigned test=0;test<4;test++) {
        unsigned producer=test<2,texture=test==3;char path[4096],prefix[4096];const char *name=names[test];
        assert(snprintf(path,sizeof(path),"%s/%s.comp",directory,name)>0);
        assert(snprintf(prefix,sizeof(prefix),"%s/%s",directory,name)>0);
        void *front=cs_frontend_compile_buffers(path,prefix);assert(front);
        unsigned shared=cs_frontend_shared_bytes(front);assert(shared==(producer?16384:0));
        struct opengpu_cs_shared profile={.base=limit_shared_base,.bytes=limit_shared_bytes,
            .lanes=limit_shared_lanes,.required_bytes=shared};
        struct opengpu_cs_program code;
        assert(!(producer?opengpu_cs_emit_shared(cs_frontend_tokens(front),&profile,&code):
            opengpu_cs_emit(cs_frontend_tokens(front),&code)));
        assert(code.words<=512&&code.textures==texture&&!code.image_writes&&code.image_reads==(test==2));
        assert(code.writes==1&&code.reads==!producer);
        unsigned barriers=0;for(unsigned i=0;i<code.words;i++)barriers+=code.code[i]==0x0000000bu;
        assert(barriers==(producer?3:0));
        printf("SHARED_PUBLICATION_PROGRAM name=%s words=%u barriers=%u sharedBytes=%u\n",name,code.words,barriers,shared);
        uint32_t *data=pipe_opengpu_resource_map(opengpu_resource(global)->gpu);
        for(unsigned i=0;i<384;i++)data[i]=0x5a5a5a5au;
        data[64]=0;data[192]=0;
        uint32_t *result=pipe_opengpu_resource_map(opengpu_resource(output)->gpu);
        for(unsigned i=0;i<128;i++)result[i]=0xa5a5a5a5u;
        result[2]=0;
        struct pipe_shader_buffer storage={.buffer=producer?global:output,.buffer_offset=producer?256:0,.buffer_size=producer?1024:512};
        p->set_shader_buffers(p,PIPE_SHADER_COMPUTE,0,1,&storage,1);
        p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,test==2?1:0,test==2?0:1,test==2?&image:NULL);
        p->set_sampler_views(p,PIPE_SHADER_COMPUTE,0,texture?1:0,texture?0:1,false,texture?&sampler:NULL);
        struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=cs_frontend_tokens(front),.req_local_mem=shared};
        void *cs=p->create_compute_state(p,&state);assert(cs);p->bind_compute_state(p,cs);
        const struct pipe_grid_info grid={.work_dim=1,.block={producer?128:1,1,1},.grid={1,1,1}};
        shared_publication_seed=test==1?0xa2000000u:0xa1000000u;
        unsigned before=buffer_submissions;wait_error=1;p->launch_grid(p,&grid);
        assert(buffer_submissions==before+1&&jobs[next_sync-1].live);
        unsigned sync=next_sync-1;shared_publication_export(directory,name,&buffer_jobs[sync]);
        wait_error=0;p->memory_barrier(p,PIPE_BARRIER_ALL);assert(!jobs[sync].live);
        for(unsigned i=0;i<384;i++) {
            uint32_t expected=i==64||i==192?0:0x5a5a5a5au;
            if(producer&&i>=64&&i<320) {
                unsigned local=i-64;
                if(local>=128)expected=test+1;
                else {expected=0;for(unsigned stripe=0;stripe<32;stripe++)expected+=shared_publication_seed+(local^16u)+stripe*128;}
            }
            assert(data[i]==expected);
        }
        for(unsigned i=0;i<128;i++)assert(result[i]==(i==2||(!producer&&i<2)?0:0xa5a5a5a5u));
        p->delete_compute_state(p,cs);cs_frontend_destroy(front);
    }
    p->set_shader_buffers(p,PIPE_SHADER_COMPUTE,0,1,NULL,0);
    p->set_sampler_views(p,PIPE_SHADER_COMPUTE,0,0,1,false,NULL);
    p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,0,1,NULL);
    pipe_sampler_view_reference(&sampler,NULL);pipe_resource_reference(&global,NULL);pipe_resource_reference(&output,NULL);
    p->destroy(p);s->destroy(s);no_leaks();
    puts("SHARED_PUBLICATION_CPU_PASS actualMesaPrograms=4 full16KiB=true all128threads=true eightWarpsBothOrders=true threeConvergedBarriers=true actualKernelRecords=true noGPU=true");
}
