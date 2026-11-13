#include "tgsi/tgsi_text.h"
/* Actual Mesa HW_ATOMIC + independent original GEM + actual kernel records.
 * CPU results are an arithmetic/publication oracle, not GPU execution. */
static void counter_write(const char *directory,const char *name,const char *suffix,const void *data,size_t bytes)
{
    char path[4096];assert(snprintf(path,sizeof(path),"%s/%s.%s",directory,name,suffix)>0);
    FILE *f=fopen(path,"wx");assert(f);assert(fwrite(data,1,bytes,f)==bytes&&!fclose(f));
}
static void counter_export(const char *directory,const char *name,const struct buffer_job *j)
{
    counter_write(directory,name,"shader",j->records.code,j->records.code_bytes);
    counter_write(directory,name,"storage",j->records.storage,j->records.storage_bytes);
    counter_write(directory,name,"counters",j->records.counters,j->records.counter_bytes);
    if(j->records.image_bytes)counter_write(directory,name,"images",j->records.images,j->records.image_bytes);
    if(j->records.uniform_bytes)counter_write(directory,name,"uniforms",j->records.uniforms,j->records.uniform_bytes);
    const struct drm_opengpu_compute_counter_source *source=&j->input->counter_source.source;
    counter_write(directory,name,"counter-data",bos[source->handle].data,bos[source->handle].size);
    char text[16384];unsigned used=0;
#define COUNTER_FIELD(...) do{int n=snprintf(text+used,sizeof(text)-used,__VA_ARGS__); \
    assert(n>0&&(unsigned)n<sizeof(text)-used);used+=(unsigned)n;}while(0)
    COUNTER_FIELD("context=%u\nstorage.root=%u\ncounter.root=%u\ncounter.handle=%u\ncounter.offset=%llu\n"
        "counter.count=%u\ncounter.GEMbytes=%llu\n",j->records.context,j->records.storage_root,j->records.counter_root,
        source->handle,(unsigned long long)source->offset,source->count,(unsigned long long)bos[source->handle].size);
    COUNTER_FIELD("image.root=%u\nuniform.root=%u\n",j->records.image_root,j->records.uniform_root);
    unsigned segments=0;
    for(unsigned slot=2;slot<=42;slot++) {
        const struct drm_opengpu_resource *b=&j->input->bindings[slot];if(!b->handle)continue;
        assert(b->size<=512&&b->offset+b->size<=bos[b->handle].size&&b->handle!=source->handle);
        char suffix[64];assert(snprintf(suffix,sizeof(suffix),"memory%u",segments)>0);
        counter_write(directory,name,suffix,bos[b->handle].data+b->offset,b->size);
        COUNTER_FIELD("memory.%u.va=%llu\nmemory.%u.bytes=%llu\nmemory.%u.slot=%u\n",segments,
            (unsigned long long)j->records.binding_va[slot],segments,(unsigned long long)b->size,segments,slot);
        segments++;
    }
    COUNTER_FIELD("segments=%u\n",segments);counter_write(directory,name,"properties",text,used);
#undef COUNTER_FIELD
}
static void counter_reject_tokens(const char *body)
{
    struct tgsi_token tokens[512];struct opengpu_cs_program code,original;char text[2048];
    assert(snprintf(text,sizeof(text),"COMP\nDCL HWATOMIC[0][0..7]\nDCL TEMP[0..1]\n"
        "IMM[0] UINT32 {28,1,4294967295,2}\n%sEND\n",body)>0);
    assert(tgsi_text_translate(text,tokens,512));memset(&code,0x71,sizeof(code));original=code;
    assert(opengpu_cs_emit(tokens,&code)<0&&!memcmp(&code,&original,sizeof(code)));
}
static void counter_negative_tokens(void)
{
    counter_reject_tokens("ATOMUADD TEMP[0].x, HWATOMIC[0][8], IMM[0].xxxx, IMM[0].yyyy\n");
    counter_reject_tokens("ATOMUADD TEMP[0].x, HWATOMIC[1][7], IMM[0].xxxx, IMM[0].yyyy\n");
    counter_reject_tokens("ATOMUADD TEMP[0].x, HWATOMIC[0][7], IMM[0].xxxx, IMM[0].wwww\n");
    counter_reject_tokens("ATOMXCHG TEMP[0].x, HWATOMIC[0][7], IMM[0].xxxx, IMM[0].yyyy\n");
    counter_reject_tokens("ATOMCAS TEMP[0].x, HWATOMIC[0][7], IMM[0].xxxx, IMM[0].yyyy, IMM[0].zzzz\n");
    counter_reject_tokens("LOAD TEMP[0].xy, HWATOMIC[0][7], IMM[0].xxxx\n");
    counter_reject_tokens("LOAD TEMP[0].x, HWATOMIC[0][7], TEMP[1].xxxx\n");
    counter_reject_tokens("LOAD TEMP[0].x, HWATOMIC[0][7], IMM[1].xxxx\n");
    counter_reject_tokens("DCL ADDR[0..2]\nLOAD TEMP[0].x, HWATOMIC[0][ADDR[2].x], IMM[0].xxxx\n");
    counter_reject_tokens("DCL ADDR[0..2]\nUARL ADDR[0].x, IMM[0].yyyy\n"
        "LOAD TEMP[0].x, HWATOMIC[0][ADDR[0].x], IMM[0].xxxx\n");
}
static void counter_feature_gates(const struct pipe_compute_state *state)
{
    const uint64_t gates[]={OPENGPU_DRIVER_FEATURE_COMPUTE_ATOMIC_COUNTERS,OPENGPU_DRIVER_FEATURE_COMPUTE_COUNTER_SOURCE};
    for(unsigned i=0;i<2;i++) {
        uint64_t saved=features;features&=~gates[i];
        struct pipe_screen *screen=opengpu_drm_screen_create(7,NULL);assert(screen);
        struct pipe_context *pipe=screen->context_create(screen,NULL,0);assert(pipe);
        unsigned attempts=resource_request_attempts;assert(!pipe->create_compute_state(pipe,state));
        assert(resource_request_attempts==attempts);pipe->destroy(pipe);screen->destroy(screen);features=saved;
    }
}
static void atomic_counter_flow(const char *directory)
{
    counter_negative_tokens();
    features|=OPENGPU_DRIVER_FEATURE_COMPUTE_ATOMIC_COUNTERS|OPENGPU_DRIVER_FEATURE_COMPUTE_COUNTER_SOURCE;
    struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
    struct pipe_context *p=s->context_create(s,NULL,0);assert(p&&p->set_hw_atomic_buffers);
    struct pipe_resource *counter=new_buffer(s,8192),*outputs[4],*images[4],*ubo=new_buffer(s,512);
    struct pipe_image_view image_views[4];
    for(unsigned i=0;i<4;i++) {
        outputs[i]=new_buffer(s,512);images[i]=new_buffer(s,512);
        image_views[i]=(struct pipe_image_view){.resource=images[i],.format=PIPE_FORMAT_R32_UINT,
            .access=PIPE_IMAGE_ACCESS_WRITE,.shader_access=PIPE_IMAGE_ACCESS_WRITE};
        image_views[i].u.buf.size=512;
    }
    const unsigned array_offset=4080;const uint32_t seed[]={UINT32_MAX-1,1,0xfedcba98u};
    const char *names[]={"increment","decrement","read","dynamic-increment","dynamic-decrement","dynamic-read","coexist","scalar"};
    for(unsigned test=0;test<8;test++) {
        unsigned operation=test<6?test%3:0,dynamic=test>=3&&test<6,coexist=test==6,scalar=test==7;
        unsigned offset=scalar?8188:array_offset,bytes=scalar?4:32;
        char path[4096],dump[4096];const char *name=names[test];
        assert(snprintf(path,sizeof(path),"%s/%s.comp",directory,name)>0);
        assert(snprintf(dump,sizeof(dump),"%s/%s",directory,name)>0);
        void *front=cs_frontend_compile_atomic_counters(path,dump);assert(front);
        struct opengpu_cs_program code;assert(!opengpu_cs_emit(cs_frontend_tokens(front),&code));
        assert(code.counter_reads==1&&code.counter_writes==(operation!=2)&&code.counter_bytes==bytes&&
            code.writes==15&&!code.reads&&!code.storage_atomics);
        for(unsigned i=0;i<4;i++){memset(pipe_opengpu_resource_map(opengpu_resource(images[i])->gpu),0x69,512);memset(pipe_opengpu_resource_map(opengpu_resource(outputs[i])->gpu),0xa5,512);}
        uint8_t *data=pipe_opengpu_resource_map(opengpu_resource(counter)->gpu);memset(data,0x5a,8192);
        uint32_t *words=(uint32_t *)(data+offset);
        for(unsigned i=0;i<bytes/4;i++)words[i]=seed[operation]+i*0x10203u;
        if(!dynamic)words[scalar?0:7]=seed[operation];
        cs_frontend_storage(front,p,outputs);cs_frontend_counters(front,p,counter,offset,bytes);
        p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,coexist?4:0,coexist?0:4,coexist?image_views:NULL);
        if(dynamic) {
            struct pipe_resource *blocks[36]={0};unsigned offsets[36]={0},sizes[36]={0};
            memset(pipe_opengpu_resource_map(opengpu_resource(ubo)->gpu),0,512);
            blocks[35]=ubo;sizes[35]=16;cs_frontend_ubos(front,p,blocks,offsets,sizes);
        } else p->set_constant_buffer(p,PIPE_SHADER_COMPUTE,1,false,NULL);
        struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=cs_frontend_tokens(front)};
        if(!test)counter_feature_gates(&state);
        void *cs=p->create_compute_state(p,&state);assert(cs);p->bind_compute_state(p,cs);
        const struct pipe_grid_info grid={.work_dim=1,.block={4,1,1},.grid={1,1,1}};
        unsigned before=buffer_submissions;wait_error=1;p->launch_grid(p,&grid);
        assert(buffer_submissions==before+1&&jobs[next_sync-1].live);
        unsigned sync=next_sync-1;struct buffer_job *j=&buffer_jobs[sync];
        assert(j->input->with_counter_source&&j->records.counter_bytes==64&&j->records.retained_objects==(coexist?10:dynamic?7:6));
        assert(j->input->counter_source.source.handle==pipe_opengpu_resource_handle(opengpu_resource(counter)->gpu));
        counter_export(directory,name,j);
        /* Rebinding the Gallium counter while the original command is pending
         * must leave its original GEM/record and all write owners retained. */
        cs_frontend_counters(front,p,ubo,0,32);
        assert(jobs[sync].live&&j->input->counter_source.source.handle==
            pipe_opengpu_resource_handle(opengpu_resource(counter)->gpu));
        unsigned publication=invalidates;wait_error=0;p->memory_barrier(p,PIPE_BARRIER_ALL);
        assert(!jobs[sync].live&&invalidates==publication+(coexist?9:5));
        for(unsigned api=0;api<4;api++) {
            const uint32_t *result=pipe_opengpu_resource_map(opengpu_resource(outputs[api])->gpu);
            for(unsigned i=0;i<128;i++) {
                uint32_t initial=seed[operation]+(dynamic?i*0x10203u:0);
                uint32_t step=dynamic?0:i;
                uint32_t want=operation==0?initial+step:operation==1?initial-1-step:initial;
                assert(result[i]==(i<4?want:0xa5a5a5a5u));
                const uint32_t *image_data=pipe_opengpu_resource_map(opengpu_resource(images[api])->gpu);
                assert(image_data[i]==(coexist&&i<4?want:0x69696969u));
            }
        }
        for(unsigned byte=0;byte<8192;byte++) {
            unsigned want=0x5a;
            if(byte>=offset&&byte<offset+bytes) {
                unsigned index=(byte-offset)/4;
                uint32_t value=seed[operation]+index*0x10203u;
                if(dynamic&&index<4)value+=operation==0?1:operation==1?UINT32_MAX:0;
                if(!dynamic&&index==(scalar?0:7))value=operation==0?seed[operation]+4:
                    operation==1?seed[operation]-4:seed[operation];
                want=value>>((byte-offset)%4*8)&255;
            }
            assert(data[byte]==want);
        }
        cs_frontend_counters(front,p,NULL,0,0);unsigned submissions=buffer_submissions;
        p->launch_grid(p,&grid);assert(buffer_submissions==submissions);
        cs_frontend_counters(front,p,counter,offset,bytes-4);
        p->launch_grid(p,&grid);assert(buffer_submissions==submissions);
        /* Invalid callback ranges poison the active binding until a valid
         * bind, and active resource aliases fail before the source ioctl. */
        const struct pipe_shader_buffer bad[]={
            {.buffer=counter,.buffer_offset=offset+1,.buffer_size=bytes},
            {.buffer=counter,.buffer_offset=offset,.buffer_size=bytes-1},
            {.buffer=counter,.buffer_offset=8192,.buffer_size=4},
            {.buffer=counter,.buffer_offset=offset,.buffer_size=0}};
        for(unsigned b=0;b<sizeof(bad)/sizeof(bad[0]);b++) {
            unsigned requests=resource_request_attempts;
            p->set_hw_atomic_buffers(p,0,1,&bad[b]);p->launch_grid(p,&grid);
            assert(buffer_submissions==submissions&&resource_request_attempts==requests);
            cs_frontend_counters(front,p,counter,offset,bytes);
        }
        struct pipe_shader_buffer alias={.buffer=outputs[0],.buffer_size=32};
        p->set_hw_atomic_buffers(p,0,1,&alias);unsigned requests=resource_request_attempts;
        p->launch_grid(p,&grid);assert(buffer_submissions==submissions&&resource_request_attempts==requests);
        cs_frontend_counters(front,p,counter,offset,bytes);
        if(coexist||dynamic) {
            alias.buffer=coexist?images[0]:ubo;p->set_hw_atomic_buffers(p,0,1,&alias);
            requests=resource_request_attempts;p->launch_grid(p,&grid);
            assert(buffer_submissions==submissions&&resource_request_attempts==requests);
            cs_frontend_counters(front,p,counter,offset,bytes);
        }
        p->delete_compute_state(p,cs);cs_frontend_destroy(front);
    }
    p->set_hw_atomic_buffers(p,0,1,NULL);p->set_shader_buffers(p,PIPE_SHADER_COMPUTE,0,4,NULL,0);
    p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,0,4,NULL);
    p->set_constant_buffer(p,PIPE_SHADER_COMPUTE,1,false,NULL);
    for(unsigned i=0;i<4;i++){pipe_resource_reference(&outputs[i],NULL);pipe_resource_reference(&images[i],NULL);}
    pipe_resource_reference(&ubo,NULL);
    pipe_resource_reference(&counter,NULL);p->destroy(p);s->destroy(s);no_leaks();
    puts("ATOMIC_COUNTER_CPU_PASS realMesaHwAtomic=true operations=3 programs=8 returnedWords=128 nineWriteOwners=true dynamicIndex=true retainedRebind=true allFourSsbo=true independentOriginalGEM=true crossPageWindow=true wraparound=true noGPU=true");
}
