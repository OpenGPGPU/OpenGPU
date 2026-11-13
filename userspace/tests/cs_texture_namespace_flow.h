/* Actual Mesa texture state tracker, real callbacks and kernel publication.
 * Included by the existing buffer fixture to share its independent byte VM. */
#include "tgsi/tgsi_text.h"
static void namespace_write(const char *directory, const char *name, const char *suffix,
    const void *data, size_t bytes)
{
    char path[4096];
    assert(snprintf(path,sizeof(path),"%s/%s.%s",directory,name,suffix)>0);
    FILE *f=fopen(path,"wx");assert(f);
    assert(fwrite(data,1,bytes,f)==bytes&&!fclose(f));
}

static void namespace_export(const char *directory, const char *name, const struct buffer_job *j,
    void *frontend, unsigned count, unsigned epoch)
{
    namespace_write(directory,name,"textures",j->records.textures,j->records.texture_bytes);
    namespace_write(directory,name,"storage",j->records.storage,j->records.storage_bytes);
    namespace_write(directory,name,"shader",j->records.code,j->records.code_bytes);
    char text[16384];unsigned used=0;
#define NS_FIELD(...) do { int n=snprintf(text+used,sizeof(text)-used,__VA_ARGS__); \
    assert(n>0&&(unsigned)n<sizeof(text)-used);used+=(unsigned)n; } while(0)
    NS_FIELD("context=%u\ntexture.root=%u\nstorage.root=%u\nactive=%u\nepoch=%u\n",
        j->records.context,j->records.texture_root,j->records.storage_root,count,epoch);
    for(unsigned native=0;native<count;native++)
        NS_FIELD("sampler.%u.api=%u\n",native,cs_frontend_sampler_binding(frontend,native));
    for(unsigned native=0;native<4;native++)
        NS_FIELD("storage.%u.api=%u\n",native,cs_frontend_storage_binding(frontend,native));
    unsigned segments=0;
    for(unsigned slot=2;slot<43;slot++) {
        const struct drm_opengpu_resource *b=&j->input->bindings[slot];if(!b->handle)continue;
        assert(b->size<=1024);
        char suffix[64];assert(snprintf(suffix,sizeof(suffix),"memory%u",segments)>0);
        namespace_write(directory,name,suffix,bos[b->handle].data+b->offset,b->size);
        NS_FIELD("memory.%u.va=%llu\nmemory.%u.bytes=%llu\nmemory.%u.slot=%u\n",
            segments,(unsigned long long)j->records.binding_va[slot],segments,
            (unsigned long long)b->size,segments,slot);segments++;
    }
    NS_FIELD("segments=%u\n",segments);
    namespace_write(directory,name,"properties",text,used);
#undef NS_FIELD
}

static uint32_t namespace_expected(unsigned epoch, const unsigned *units, unsigned count, unsigned item)
{
    uint32_t value=0x713a2b09u;
    for(unsigned i=0;i<count;i++)value=(count==32?value:value*33u)^
        (count==32?(units[i]+8)<<(i%3==0?1+i%7:0):sample(epoch,units[i],item));
    if(count==32)value=value*33u^sample(epoch,units[31],item);
    return value;
}

struct namespace_components { unsigned seen, calls; };
static void namespace_reject(const struct tgsi_token *tokens)
{
    struct opengpu_cs_program output,before;memset(&output,0xa5,sizeof(output));before=output;
    assert(opengpu_cs_emit(tokens,&output)&&!memcmp(&output,&before,sizeof(output)));
}
static void namespace_tgsi(const char *text,struct tgsi_token tokens[1024])
{
    assert(tgsi_text_translate(text,tokens,1024));
    struct tgsi_header h;memcpy(&h,tokens,4);
    for(unsigned pos=2;pos<2u+h.BodySize;) {
        struct tgsi_token t;memcpy(&t,tokens+pos,4);
        if(t.Type==TGSI_TOKEN_TYPE_INSTRUCTION) {
            struct tgsi_instruction i;memcpy(&i,tokens+pos,4);
            /* Mesa's text assembler omits the typed TXF return field. The
             * real ESSL/NTT path supplies UINT; use that canonical encoding. */
            if(i.Opcode==TGSI_OPCODE_TXF) {
                assert(i.Texture&&!i.Label);
                struct tgsi_instruction_texture *texture=(struct tgsi_instruction_texture *)(tokens+pos+1);
                texture->ReturnType=TGSI_RETURN_TYPE_UINT;
            }
            pos+=1+i.NrTokens;
        } else pos+=t.NrTokens;
    }
}
static uint32_t namespace_component(void *opaque,unsigned unit,uint32_t index,unsigned component,int query)
{
    struct namespace_components *c=opaque;assert(!unit&&!index&&!query&&component<4);
    c->seen|=1u<<component;c->calls++;return (component+1)*11;
}

static void namespace_liveness(const char *directory)
{
    static const struct {const char *body;unsigned mask;uint32_t result[4];} tests[]={
        {"TXF TEMP[0], IMM[0].xxxx, SAMP[0], BUFFER\nSTORE BUFFER[0].xyzw, IMM[0].xxxx, TEMP[0]\n",15,{11,22,33,44}},
        {"MOV TEMP[0], IMM[0].xxxx\nTXF TEMP[0], TEMP[0], SAMP[0], BUFFER\nSTORE BUFFER[0].xyzw, IMM[0].xxxx, TEMP[0]\n",15,{11,22,33,44}},
        {"TXF TEMP[0], IMM[0].xxxx, SAMP[0], BUFFER\nMOV TEMP[0].y, IMM[0].yyyy\nSTORE BUFFER[0].xyzw, IMM[0].xxxx, TEMP[0]\n",13,{11,7,33,44}},
        {"TXF TEMP[0], IMM[0].xxxx, SAMP[0], BUFFER\nMOV TEMP[1], TEMP[0].wzyx\nSTORE BUFFER[0].xyzw, IMM[0].xxxx, TEMP[1]\n",15,{44,33,22,11}},
        {"TXF TEMP[0], IMM[0].xxxx, SAMP[0], BUFFER\nMOV TEMP[1], TEMP[0].yyyy\nSTORE BUFFER[0].xyzw, IMM[0].xxxx, TEMP[1]\n",2,{22,22,22,22}},
        {"TXF TEMP[0], IMM[0].xxxx, SAMP[0], BUFFER\nMOV TEMP[0], IMM[0]\nSTORE BUFFER[0].xyzw, IMM[0].xxxx, TEMP[0]\n",1,{0,7,9,0}},
    };
    for(unsigned test=0;test<sizeof(tests)/sizeof(tests[0]);test++) {
        char text[4096];struct tgsi_token tokens[1024];struct opengpu_cs_program code;
        assert(snprintf(text,sizeof(text),"COMP\nDCL SAMP[0]\nDCL SVIEW[0], BUFFER, UINT\n"
            "DCL BUFFER[0]\nDCL TEMP[0..1]\nIMM[0] UINT32 {0,7,9,0}\n%sEND\n",tests[test].body)>0);
        namespace_tgsi(text,tokens);assert(!opengpu_cs_emit(tokens,&code));
        assert(opengpu_compute_shader_validate_words_all_resources(code.code,code.words,
            12,128,false,false,true,true,false,false,false));
        uint32_t output[8];for(unsigned i=0;i<8;i++)output[i]=0xa5a5a5a5u;
        struct cs_vm_view views[4]={{output,sizeof(output),3},{0},{0},{0}};
        struct namespace_components components={0};
        const struct cs_vm_resources resources={&components,namespace_component,NULL};
        const uint32_t one[3]={1,1,1},group[3]={0};
        cs_vm_group_resources_checked(code.code,code.words,one,group,one,views,NULL,NULL,&resources);
        assert(components.seen==tests[test].mask&&components.calls==__builtin_popcount(tests[test].mask));
        for(unsigned i=0;i<8;i++)assert(output[i]==(i<4?tests[test].result[i]:0xa5a5a5a5u));
        if(test==5) {
            struct tgsi_token copy[1024];unsigned count=tgsi_num_tokens(tokens),fetch=0;
            for(unsigned pos=2;pos<count;) {
                struct tgsi_token t;memcpy(&t,tokens+pos,4);
                if(t.Type==TGSI_TOKEN_TYPE_INSTRUCTION) {
                    struct tgsi_instruction i;memcpy(&i,tokens+pos,4);
                    if(i.Opcode==TGSI_OPCODE_TXF){fetch=pos;break;}
                    pos+=1+i.NrTokens;
                } else pos+=t.NrTokens;
            }
            assert(fetch);
            for(unsigned bad=0;bad<3;bad++) {
                memcpy(copy,tokens,count*4);
                struct tgsi_dst_register *dst=(struct tgsi_dst_register *)(copy+fetch+2);
                if(!bad)dst->WriteMask=0;
                else if(bad==1)dst->Index=2;
                else dst->File=TGSI_FILE_BUFFER;
                namespace_reject(copy);
            }
            namespace_write(directory,"unused-fetch","shader",code.code,code.words*4);
            /* The actual CU must fault the retained fetch even though every
             * TGSI result channel is overwritten before any source reads it. */
            assert(snprintf(text,sizeof(text),"COMP\nDCL SAMP[0]\nDCL SVIEW[0], BUFFER, UINT\n"
                "DCL BUFFER[0]\nDCL TEMP[0..1]\nIMM[0] UINT32 {9999,7,9,0}\n%sEND\n",tests[test].body)>0);
            namespace_tgsi(text,tokens);assert(!opengpu_cs_emit(tokens,&code));
            namespace_write(directory,"unused-fault","shader",code.code,code.words*4);
        }
    }
}

static void texture_namespace_flow(const char *directory)
{
    namespace_liveness(directory);
    const char *names[]={"full32","low16","middle16","high16","mixed16","rgba"};
    struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
    struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
    struct pipe_resource *textures[2][48],*outputs[4],*rgba_sources[2];
    unsigned offsets[48],sizes[48];
    for(unsigned i=0;i<48;i++) {
        offsets[i]=64;sizes[i]=(i+8)*4;
        for(unsigned epoch=0;epoch<2;epoch++) {
            textures[epoch][i]=new_buffer(s,512);
            uint8_t *data=pipe_opengpu_resource_map(opengpu_resource(textures[epoch][i])->gpu);
            memset(data,0x5a,512);
            for(unsigned item=0;item<64;item++) {
                uint32_t value=sample(epoch+1,i,item);memcpy(data+64+item*4,&value,4);
            }
        }
    }
    for(unsigned epoch=0;epoch<2;epoch++) {
        rgba_sources[epoch]=new_buffer(s,512);
        uint8_t *data=pipe_opengpu_resource_map(opengpu_resource(rgba_sources[epoch])->gpu);
        memset(data,0x5a,512);
        for(unsigned item=0;item<8;item++)for(unsigned c=0;c<4;c++) {
            uint32_t value=sample(epoch+1,47,item)^(c*0x11557799u);
            memcpy(data+60+item*16+c*4,&value,4);
        }
    }
    for(unsigned i=0;i<4;i++)outputs[i]=new_buffer(s,1024);
    unsigned bound=0,checked=0;
    for(unsigned program=0;program<6;program++) {
        char path[4096],prefix[4096];const char *name=names[program];
        unsigned rgba=program==5;
        struct pipe_resource *chosen[2][48];memcpy(chosen,textures,sizeof(chosen));
        if(rgba)for(unsigned epoch=0;epoch<2;epoch++)chosen[epoch][47]=rgba_sources[epoch];
        sizes[47]=rgba?128:55*4;
        offsets[47]=rgba?60:64;
        assert(snprintf(path,sizeof(path),"%s/%s.comp",directory,name)>0);
        assert(snprintf(prefix,sizeof(prefix),"%s/%s",directory,name)>0);
        void *f=cs_frontend_compile_texture_namespace(path,prefix);assert(f);
        struct opengpu_cs_program code;assert(!opengpu_cs_emit(cs_frontend_tokens(f),&code));
        unsigned count=rgba?1:program?16:32,units[32];
        for(unsigned i=0;i<count;i++)units[i]=rgba?47:!program?16+i:program==1?i:program==2?16+i:program==3?32+i:(17*i)%48;
        assert(code.textures==(count==32?UINT32_MAX:(1u<<count)-1)&&code.writes==15&&code.words<=512);
        uint64_t seen=0;
        for(unsigned native=0;native<count;native++) {
            unsigned api=cs_frontend_sampler_binding(f,native);assert(api<48);seen|=UINT64_C(1)<<api;
        }
        uint64_t wanted=0;for(unsigned i=0;i<count;i++)wanted|=UINT64_C(1)<<units[i];assert(seen==wanted);
        struct pipe_shader_buffer storage[4];
        for(unsigned native=0;native<4;native++) {
            unsigned api=cs_frontend_storage_binding(f,native);assert(api<4);
            storage[native]=(struct pipe_shader_buffer){.buffer=outputs[api],.buffer_size=1024};
        }
        p->set_shader_buffers(p,PIPE_SHADER_COMPUTE,0,4,storage,15);
        struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=cs_frontend_tokens(f)};
        void *cs=p->create_compute_state(p,&state);assert(cs);p->bind_compute_state(p,cs);
        const struct pipe_grid_info grid={.work_dim=1,.block={4,1,1},.grid={1,1,1}};
        for(unsigned epoch=0;epoch<2;epoch++) {
            for(unsigned i=0;i<4;i++)memset(pipe_opengpu_resource_map(opengpu_resource(outputs[i])->gpu),0xa5,1024);
            bound=cs_frontend_textures(f,p,chosen[epoch],offsets,sizes,bound,rgba?47:48);assert(bound==count);
            /* Keep the first job pending while the actual state tracker
             * creates a different set of native views for the same pipe. */
            unsigned before=buffer_submissions;wait_error=1;p->launch_grid(p,&grid);
            assert(buffer_submissions==before+1&&jobs[next_sync-1].live);
            unsigned sync=next_sync-1;
            char capture[64];assert(snprintf(capture,sizeof(capture),"%s-epoch%u",name,epoch+1)>0);
            namespace_export(directory,capture,&buffer_jobs[sync],f,count,epoch+1);
            unsigned refs=buffer_jobs[sync].records.retained_objects;
            assert(refs==count+5); /* shader + four outputs + active sources only */
            bound=cs_frontend_textures(f,p,chosen[1-epoch],offsets,sizes,bound,rgba?47:48);
            wait_error=0;p->memory_barrier(p,PIPE_BARRIER_ALL);assert(!jobs[sync].live);
            for(unsigned b=0;b<4;b++) {
                const uint32_t *data=pipe_opengpu_resource_map(opengpu_resource(outputs[b])->gpu);
                for(unsigned item=0;item<256;item++)assert(data[item]==(item<4?
                    rgba?sample(epoch+1,47,item)^(b*0x11557799u):
                    namespace_expected(epoch+1,units,count,item)^(b*0x10203u):0xa5a5a5a5u));
            }
            checked+=4*4;
        }
        /* An absent active global binding must not inherit a stale native
         * view. Other unbound global units do not become task inputs. */
        struct pipe_resource *missing[48];memcpy(missing,chosen[0],sizeof(missing));
        unsigned active=cs_frontend_sampler_binding(f,0);missing[active]=NULL;
        bound=cs_frontend_textures(f,p,missing,offsets,sizes,bound,rgba?47:48);reject_launch(p,&grid);
        for(unsigned i=0;i<48;i++)if(!(wanted&(UINT64_C(1)<<i)))missing[i]=NULL;
        missing[active]=chosen[0][active];
        bound=cs_frontend_textures(f,p,missing,offsets,sizes,bound,rgba?47:48);
        unsigned before=buffer_submissions;p->launch_grid(p,&grid);assert(buffer_submissions==before+1);
        p->memory_barrier(p,PIPE_BARRIER_ALL);
        p->delete_compute_state(p,cs);cs_frontend_destroy(f);
    }
    for(unsigned negative=0;negative<2;negative++) {
        char path[4096],prefix[4096];const char *name=negative?"too-many":"invalid48";
        assert(snprintf(path,sizeof(path),"%s/%s.comp",directory,name)>0);
        assert(snprintf(prefix,sizeof(prefix),"%s/%s",directory,name)>0);
        assert(!cs_frontend_compile_texture_namespace(path,prefix));
    }
    p->set_sampler_views(p,PIPE_SHADER_COMPUTE,0,0,32,false,NULL);
    p->set_shader_buffers(p,PIPE_SHADER_COMPUTE,0,4,NULL,0);
    for(unsigned i=0;i<48;i++)for(unsigned epoch=0;epoch<2;epoch++)pipe_resource_reference(&textures[epoch][i],NULL);
    for(unsigned epoch=0;epoch<2;epoch++)pipe_resource_reference(&rgba_sources[epoch],NULL);
    for(unsigned i=0;i<4;i++)pipe_resource_reference(&outputs[i],NULL);
    p->destroy(p);s->destroy(s);no_leaks();
    printf("TEXTURE_NAMESPACE_CPU_PASS globalUnits=48 nativeCapacity=32 selections=6 returnedWords=%u realMesaStateTracker=true retainedRebind=true missingActiveRejected=true unusedGlobalUnitsExcluded=true fullRGBA=true componentDefUse=true noGPU=true\n",checked);
}
