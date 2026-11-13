/* Actual Mesa image/barrier words plus real immutable kernel resource records.
 * The independent CPU oracle checks compiler/publication, not GPU coherence. */
static void image_ordering_write(const char *directory,const char *name,const char *suffix,
    const void *data,size_t bytes)
{
    char path[4096];assert(snprintf(path,sizeof(path),"%s/%s.%s",directory,name,suffix)>0);
    FILE *f=fopen(path,"wx");assert(f);assert(fwrite(data,1,bytes,f)==bytes&&!fclose(f));
}

static void image_ordering_export(const char *directory,const char *name,const struct buffer_job *j)
{
    image_ordering_write(directory,name,"shader",j->records.code,j->records.code_bytes);
    if(j->records.texture_bytes)image_ordering_write(directory,name,"textures",j->records.textures,j->records.texture_bytes);
    if(j->records.image_bytes)image_ordering_write(directory,name,"images",j->records.images,j->records.image_bytes);
    image_ordering_write(directory,name,"storage",j->records.storage,j->records.storage_bytes);
    char text[16384];unsigned used=0;
#define IMAGE_FIELD(...) do {int n=snprintf(text+used,sizeof(text)-used,__VA_ARGS__); \
    assert(n>0&&(unsigned)n<sizeof(text)-used);used+=(unsigned)n;}while(0)
    IMAGE_FIELD("context=%u\ntexture.root=%u\nimage.root=%u\nstorage.root=%u\n",
        j->records.context,j->records.texture_root,j->records.image_root,j->records.storage_root);
    unsigned segments=0;
    for(unsigned slot=2;slot<43;slot++) {
        const struct drm_opengpu_resource *b=&j->input->bindings[slot];if(!b->handle)continue;
        assert(b->size<=512&&b->offset+b->size<=bos[b->handle].size);
        char suffix[64];assert(snprintf(suffix,sizeof(suffix),"memory%u",segments)>0);
        image_ordering_write(directory,name,suffix,bos[b->handle].data+b->offset,b->size);
        IMAGE_FIELD("memory.%u.va=%llu\nmemory.%u.bytes=%llu\nmemory.%u.slot=%u\n"
            "memory.%u.handle=%u\nmemory.%u.offset=%llu\n",segments,
            (unsigned long long)j->records.binding_va[slot],segments,(unsigned long long)b->size,
            segments,slot,segments,b->handle,segments,(unsigned long long)b->offset);
        segments++;
    }
    IMAGE_FIELD("segments=%u\n",segments);
    image_ordering_write(directory,name,"properties",text,used);
#undef IMAGE_FIELD
}

static void image_ordering_flow(const char *directory)
{
    struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
    struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
    struct pipe_resource *shared=new_buffer(s,512),*output=new_buffer(s,512);
    struct pipe_sampler_view description={.format=PIPE_FORMAT_R32_UINT,.target=PIPE_BUFFER,
        .swizzle_r=0,.swizzle_g=1,.swizzle_b=2,.swizzle_a=3};
    description.u.buf.size=512;
    struct pipe_sampler_view *sampler=p->create_sampler_view(p,shared,&description);assert(sampler);
    struct pipe_image_view image={.resource=shared,.format=PIPE_FORMAT_R32_UINT,
        .access=PIPE_IMAGE_ACCESS_READ|PIPE_IMAGE_ACCESS_WRITE,
        .shader_access=PIPE_IMAGE_ACCESS_READ|PIPE_IMAGE_ACCESS_WRITE};
    image.u.buf.size=512;
    const char *names[]={"producer-1","producer-2","image-consumer","texture-consumer"};
    for(unsigned test=0;test<4;test++) {
        char path[4096],prefix[4096];const char *name=names[test];
        assert(snprintf(path,sizeof(path),"%s/%s.comp",directory,name)>0);
        assert(snprintf(prefix,sizeof(prefix),"%s/%s",directory,name)>0);
        void *front=cs_frontend_compile_buffers(path,prefix);assert(front);
        struct opengpu_cs_program code;assert(!opengpu_cs_emit(cs_frontend_tokens(front),&code));
        if(!test) {
            const struct tgsi_token *tokens=cs_frontend_tokens(front);
            unsigned count=tgsi_num_tokens(tokens),store=0;
            for(unsigned pos=2;pos<count;) {
                struct tgsi_token t;memcpy(&t,tokens+pos,4);
                if(t.Type==TGSI_TOKEN_TYPE_INSTRUCTION) {
                    struct tgsi_instruction i;memcpy(&i,tokens+pos,4);
                    if(i.Opcode==TGSI_OPCODE_STORE) {
                        assert(i.Memory&&!i.Texture&&!i.Label);store=pos+1;break;
                    }
                    pos+=1+i.NrTokens;
                } else pos+=t.NrTokens;
            }
            assert(store&&count<2048);
            for(unsigned mask=2;mask<8;mask++) {
                struct tgsi_token copy[2048];memcpy(copy,tokens,count*4);
                struct tgsi_instruction_memory m;memcpy(&m,copy+store,4);m.Qualifier=mask;
                memcpy(copy+store,&m,4);
                struct opengpu_cs_program rejected,before;memset(&rejected,0xa5,sizeof(rejected));before=rejected;
                assert(opengpu_cs_emit(copy,&rejected)<0&&!memcmp(&rejected,&before,sizeof(rejected)));
            }
        }
        unsigned producer=test<2,texture=test==3;
        assert(code.words<=512&&code.textures==texture&&code.image_writes==producer&&
            code.image_reads==(test==2)&&code.writes==!producer&&code.reads==!producer);
        unsigned fences=0;for(unsigned i=0;i<code.words;i++)fences+=code.code[i]==0x0330000fu;
        assert(fences>=4);
        memset(pipe_opengpu_resource_map(opengpu_resource(shared)->gpu),0x5a,512);
        uint32_t *data=pipe_opengpu_resource_map(opengpu_resource(shared)->gpu);
        data[1]=test==1?7:0;data[17]=test==1?1:0;
        uint32_t *result=pipe_opengpu_resource_map(opengpu_resource(output)->gpu);
        for(unsigned i=0;i<128;i++)result[i]=0xa5a5a5a5u;
        result[2]=1;
        struct pipe_shader_buffer storage={.buffer=output,.buffer_size=512};
        p->set_shader_buffers(p,PIPE_SHADER_COMPUTE,0,1,&storage,1);
        p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,1,0,&image);
        p->set_sampler_views(p,PIPE_SHADER_COMPUTE,0,1,0,false,&sampler);
        struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=cs_frontend_tokens(front)};
        void *cs=p->create_compute_state(p,&state);assert(cs);p->bind_compute_state(p,cs);
        const struct pipe_grid_info grid={.work_dim=1,.block={1,1,1},.grid={1,1,1}};
        unsigned before=buffer_submissions;wait_error=1;p->launch_grid(p,&grid);
        assert(buffer_submissions==before+1&&jobs[next_sync-1].live);
        unsigned sync=next_sync-1;image_ordering_export(directory,name,&buffer_jobs[sync]);
        wait_error=0;p->memory_barrier(p,PIPE_BARRIER_ALL);assert(!jobs[sync].live);
        for(unsigned i=0;i<128;i++) {
            uint32_t expected=0x5a5a5a5au;
            if(i==1)expected=producer?(test+1)*7:0;
            if(i==17)expected=producer?test+1:0;
            assert(data[i]==expected);
            assert(result[i]==(i==2?1:!producer&&i<2?0:0xa5a5a5a5u));
        }
        p->delete_compute_state(p,cs);cs_frontend_destroy(front);
    }
    p->set_sampler_views(p,PIPE_SHADER_COMPUTE,0,0,1,false,NULL);
    p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,0,1,NULL);
    p->set_shader_buffers(p,PIPE_SHADER_COMPUTE,0,1,NULL,0);
    pipe_sampler_view_reference(&sampler,NULL);
    pipe_resource_reference(&shared,NULL);pipe_resource_reference(&output,NULL);
    p->destroy(p);s->destroy(s);no_leaks();
    puts("IMAGE_ORDERING_CPU_PASS actualMesaPrograms=4 retainedImageTextureStorageRecords=true repeatedConsumerAccess=true guards=true noGPU=true");
}
