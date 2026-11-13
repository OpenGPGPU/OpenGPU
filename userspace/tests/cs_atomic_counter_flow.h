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
    const struct drm_opengpu_compute_counter_source *source=&j->input->counter_source.source;
    counter_write(directory,name,"counter-data",bos[source->handle].data,bos[source->handle].size);
    char text[16384];unsigned used=0;
#define COUNTER_FIELD(...) do{int n=snprintf(text+used,sizeof(text)-used,__VA_ARGS__); \
    assert(n>0&&(unsigned)n<sizeof(text)-used);used+=(unsigned)n;}while(0)
    COUNTER_FIELD("context=%u\nstorage.root=%u\ncounter.root=%u\ncounter.handle=%u\ncounter.offset=%llu\n"
        "counter.count=%u\ncounter.GEMbytes=%llu\n",j->records.context,j->records.storage_root,j->records.counter_root,
        source->handle,(unsigned long long)source->offset,source->count,(unsigned long long)bos[source->handle].size);
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
static void atomic_counter_flow(const char *directory)
{
    features|=OPENGPU_DRIVER_FEATURE_COMPUTE_ATOMIC_COUNTERS|OPENGPU_DRIVER_FEATURE_COMPUTE_COUNTER_SOURCE;
    struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
    struct pipe_context *p=s->context_create(s,NULL,0);assert(p&&p->set_hw_atomic_buffers);
    struct pipe_resource *counter=new_buffer(s,8192),*outputs[4];
    for(unsigned i=0;i<4;i++)outputs[i]=new_buffer(s,512);
    const unsigned offset=4080;const uint32_t seed[]={UINT32_MAX-1,1,0xfedcba98u};
    const char *names[]={"increment","decrement","read"};
    for(unsigned operation=0;operation<3;operation++) {
        char path[4096],dump[4096];const char *name=names[operation];
        assert(snprintf(path,sizeof(path),"%s/%s.comp",directory,name)>0);
        assert(snprintf(dump,sizeof(dump),"%s/%s",directory,name)>0);
        void *front=cs_frontend_compile_atomic_counters(path,dump);assert(front);
        struct opengpu_cs_program code;assert(!opengpu_cs_emit(cs_frontend_tokens(front),&code));
        assert(code.counter_reads==1&&code.counter_writes==(operation!=2)&&code.counter_bytes==32&&
            code.writes==15&&!code.reads&&!code.storage_atomics);
        for(unsigned i=0;i<4;i++)memset(pipe_opengpu_resource_map(opengpu_resource(outputs[i])->gpu),0xa5,512);
        uint8_t *data=pipe_opengpu_resource_map(opengpu_resource(counter)->gpu);memset(data,0x5a,8192);
        uint32_t *words=(uint32_t *)(data+offset);
        for(unsigned i=0;i<8;i++)words[i]=seed[operation]+i*0x10203u;
        words[7]=seed[operation];
        cs_frontend_storage(front,p,outputs);cs_frontend_counters(front,p,counter,offset,32);
        struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=cs_frontend_tokens(front)};
        void *cs=p->create_compute_state(p,&state);assert(cs);p->bind_compute_state(p,cs);
        const struct pipe_grid_info grid={.work_dim=1,.block={4,1,1},.grid={1,1,1}};
        unsigned before=buffer_submissions;wait_error=1;p->launch_grid(p,&grid);
        assert(buffer_submissions==before+1&&jobs[next_sync-1].live);
        unsigned sync=next_sync-1;struct buffer_job *j=&buffer_jobs[sync];
        assert(j->input->with_counter_source&&j->records.counter_bytes==64&&j->records.retained_objects==6);
        assert(j->input->counter_source.source.handle==pipe_opengpu_resource_handle(opengpu_resource(counter)->gpu));
        counter_export(directory,name,j);
        wait_error=0;p->memory_barrier(p,PIPE_BARRIER_ALL);assert(!jobs[sync].live);
        for(unsigned api=0;api<4;api++) {
            const uint32_t *result=pipe_opengpu_resource_map(opengpu_resource(outputs[api])->gpu);
            for(unsigned i=0;i<128;i++) {
                uint32_t want=operation==0?seed[operation]+i:operation==1?seed[operation]-1-i:seed[operation];
                assert(result[i]==(i<4?want:0xa5a5a5a5u));
            }
        }
        for(unsigned byte=0;byte<8192;byte++) {
            unsigned want=0x5a;
            if(byte>=offset&&byte<offset+32) {
                unsigned index=(byte-offset)/4;
                uint32_t value=index==7?(operation==0?seed[operation]+4:operation==1?seed[operation]-4:seed[operation]):
                    seed[operation]+index*0x10203u;
                want=value>>((byte-offset)%4*8)&255;
            }
            assert(data[byte]==want);
        }
        cs_frontend_counters(front,p,NULL,0,0);unsigned submissions=buffer_submissions;
        p->launch_grid(p,&grid);assert(buffer_submissions==submissions);
        cs_frontend_counters(front,p,counter,offset,28);
        p->launch_grid(p,&grid);assert(buffer_submissions==submissions);
        cs_frontend_counters(front,p,counter,offset,32);
        p->delete_compute_state(p,cs);cs_frontend_destroy(front);
    }
    p->set_hw_atomic_buffers(p,0,1,NULL);p->set_shader_buffers(p,PIPE_SHADER_COMPUTE,0,4,NULL,0);
    for(unsigned i=0;i<4;i++)pipe_resource_reference(&outputs[i],NULL);
    pipe_resource_reference(&counter,NULL);p->destroy(p);s->destroy(s);no_leaks();
    puts("ATOMIC_COUNTER_CPU_PASS realMesaHwAtomic=true operations=3 returnedWords=48 allFourSsbo=true independentOriginalGEM=true crossPageWindow=true wraparound=true noGPU=true");
}
