/* SPDX-License-Identifier: MIT */
/* Explicit CPU-only real Mesa frontend/callback/lib acceptance. Kernel OS/GEM
 * primitives are modeled; this is not a GL context or GPU execution proof. */
#define main storage_callback_main
#define OPENGPU_CS_REAL_TGSI_DUMP
#include "cs_callback_flow.c"
#undef main
#include "cs_glsl_frontend.h"
#include "tgsi/tgsi_text.h"

static uint32_t value(unsigned epoch, unsigned slot, unsigned word)
{
    /* Nonlinear in slot/offset so folding two endpoints cannot cancel every
     * block's independent identity or epoch. */
    return (0x4ab319d7u + epoch * 0x1020304u + slot * 0x718293u + word * 0x9e3779b9u) ^
        ((slot + 13u) << (word % 23));
}
static const char *uniform_dir;
static unsigned captured_default, captured_new_default;
static void capture_uniform(const struct drm_opengpu_compute_uniform_resources *gum,
    const struct job *j,const struct drm_opengpu_resource *shader)
{
    char path[4096];
    snprintf(path,sizeof(path),"%s/submission-%u.gum1",uniform_dir,uniform_submissions);
    FILE *f=fopen(path,"wx");assert(f && fwrite(gum,1,sizeof(*gum),f)==sizeof(*gum) && !fclose(f));
    snprintf(path,sizeof(path),"%s/submission-%u.words",uniform_dir,uniform_submissions);
    f=fopen(path,"wx");assert(f);
    const uint32_t *words=(const uint32_t *)(bos[shader->handle].data+shader->offset);
    for(unsigned i=0;i<shader->size/4;i++)assert(fprintf(f,"%08x\n",words[i])==9);
    assert(!fclose(f));
    for(unsigned i=0;i<13;i++)if(j->uniforms[i].data){
        snprintf(path,sizeof(path),"%s/submission-%u.cb%u",uniform_dir,uniform_submissions,i);
        f=fopen(path,"wx");assert(f && fwrite(j->uniforms[i].data,1,j->uniforms[i].bytes,f)==j->uniforms[i].bytes && !fclose(f));
    }
    if(!uniform_submissions)captured_default=gum->defaults.handle;
    if(uniform_submissions==1){captured_new_default=gum->defaults.handle;assert(captured_new_default!=captured_default);}
    if(gum->active_mask==4095u){
        unsigned handles[13]={gum->defaults.handle};
        for(unsigned i=1;i<13;i++)handles[i]=gum->blocks[i-1].handle;
        for(unsigned i=0;i<13;i++)for(unsigned k=0;k<i;k++)assert(handles[i]!=handles[k]);
    }
}
static uint32_t expected(unsigned epoch, unsigned producer)
{
    uint32_t a=value(epoch,0,2)^value(epoch,0,1023);
    for(unsigned i=1;i<=12;i++)a=(a*33u)^(producer && i==12?producer:value(epoch,i,0))^value(epoch,i,4095);
    return a;
}
static void *front(const char *dir,const char *name)
{
    char source[4096],dump[4096];
    assert(snprintf(source,sizeof(source),"%s/%s.comp",dir,name)>0);
    assert(snprintf(dump,sizeof(dump),"%s/%s",dir,name)>0);
    void *f=cs_frontend_compile(source,dump);assert(f);return f;
}
static void fill_uniforms(struct pipe_resource *ub[36],unsigned epoch)
{
    for(unsigned i=0;i<12;i++) {
        uint32_t *p=pipe_opengpu_resource_map(opengpu_resource(ub[24+i])->gpu);
        for(unsigned w=0;w<4096;w++)p[w]=value(epoch,i+1,w);
    }
}
static void bind_defaults(void *f,struct pipe_context *p,unsigned epoch)
{
    uint32_t v[1024];for(unsigned i=0;i<1024;i++)v[i]=value(epoch,0,i);
    assert(cs_frontend_default_components(f)==1024);
    cs_frontend_defaults(f,p,v,1024);memset(v,0x77,sizeof(v));
}
static void check_output(struct pipe_resource *out[4],unsigned epoch,unsigned items,unsigned producer)
{
    uint32_t a=expected(epoch,producer);
    for(unsigned b=0;b<4;b++){
        const uint32_t *m=pipe_opengpu_resource_map(opengpu_resource(out[b])->gpu);
        for(unsigned i=0;i<512;i++) {
            uint32_t want=i<2*items?(a ^ i ^ (b*0x10203u)):0xdeadbeefu;
            if(m[i]!=want)fprintf(stderr,"uniform result mismatch epoch=%u binding=%u word=%u got=%08x expected=%08x\n",epoch,b,i,m[i],want);
            assert(m[i]==want);
        }
    }
}
static void clear_output(struct pipe_resource *out[4])
{
    for(unsigned b=0;b<4;b++){
        uint32_t *m=pipe_opengpu_resource_map(opengpu_resource(out[b])->gpu);
        for(unsigned i=0;i<512;i++)m[i]=0xdeadbeefu;
    }
}
static void expect_uniform_reject(struct pipe_context *p,const struct pipe_grid_info *g)
{ unsigned before=computes;p->launch_grid(p,g);assert(computes==before); }
static void cb(struct pipe_context *p,unsigned slot,struct pipe_resource *r,unsigned offset,unsigned size,bool owned)
{
    struct pipe_constant_buffer b={.buffer=r,.buffer_offset=offset,.buffer_size=size};
    p->set_constant_buffer(p,PIPE_SHADER_COMPUTE,slot,owned,&b);
}
static void malformed_constants(struct pipe_context *p,const void *tokens)
{
    struct tgsi_header h;memcpy(&h,tokens,4);unsigned n=h.HeaderSize+h.BodySize;
    struct tgsi_token *copy=malloc(n*4);assert(copy);
    unsigned found_decl=0,found_src=0;
    for(unsigned pos=2;pos<n;){
        struct tgsi_token t;memcpy(&t,(const struct tgsi_token *)tokens+pos,4);
        unsigned count=t.NrTokens;
        if(t.Type==TGSI_TOKEN_TYPE_DECLARATION){
            struct tgsi_declaration d;memcpy(&d,(const struct tgsi_token *)tokens+pos,4);
            if(d.File==TGSI_FILE_CONSTANT && !found_decl){
                struct tgsi_declaration_dimension dim;memcpy(&dim,(const struct tgsi_token *)tokens+pos+2,4);
                for(unsigned which=0;which<3;which++){
                    memcpy(copy,tokens,n*4);struct tgsi_declaration_dimension bad=dim;
                    if(which==0)bad.Index2D=13;else if(which==1)bad.Padding=1;else bad.Index2D=0;
                    memcpy(copy+pos+2,&bad,4);
                    if(which==2){struct tgsi_declaration_range range;memcpy(&range,copy+pos+1,4);range.Last=256;memcpy(copy+pos+1,&range,4);}
                    struct opengpu_cs_program output,original;memset(&output,0x73,sizeof(output));original=output;
                    assert(opengpu_cs_emit(copy,&output)<0 && !memcmp(&output,&original,sizeof(output)));
                    struct pipe_compute_state s={.ir_type=PIPE_SHADER_IR_TGSI,.prog=copy};assert(!p->create_compute_state(p,&s));
                }found_decl=1;
            }
        }else if(t.Type==TGSI_TOKEN_TYPE_INSTRUCTION){
            struct tgsi_instruction ins;memcpy(&ins,(const struct tgsi_token *)tokens+pos,4);count++;
            unsigned q=pos+1+ins.Memory+ins.NumDstRegs;
            for(unsigned k=0;k<ins.NumSrcRegs;k++,q++){
                struct tgsi_src_register s;memcpy(&s,(const struct tgsi_token *)tokens+q,4);
                if(s.File==TGSI_FILE_CONSTANT && !found_src){
                    for(unsigned which=0;which<5;which++){
                        memcpy(copy,tokens,n*4);struct tgsi_dimension dim;memcpy(&dim,copy+q+1,4);
                        if(which==0)dim.Index=13;else if(which==1)dim.Indirect=1;
                        else if(which==2)dim.Dimension=1;else if(which==3)dim.Padding=1;
                        else {s.Indirect=1;memcpy(copy+q,&s,4);}
                        memcpy(copy+q+1,&dim,4);
                        struct opengpu_cs_program output,original;memset(&output,0x73,sizeof(output));original=output;
                        assert(opengpu_cs_emit(copy,&output)<0 && !memcmp(&output,&original,sizeof(output)));
                    }found_src=1;
                }
                q+=s.Dimension;
            }
        }
        assert(count);pos+=count;
    }
    assert(found_decl&&found_src);free(copy);
}

static uint32_t index_result(unsigned kind,unsigned epoch,uint32_t a,uint32_t b)
{
    unsigned word=kind==0?a*4+1:(kind==3 || kind==6)?(a*64+b)*4+2:
        kind==4?(a+3)*4+3:kind==7?(a&255)*4+3:(kind==2?(a&1023):a)*4+1;
    return value(epoch,kind==0 || kind>=6?0:12,word)^value(epoch,0,1023);
}
static void index_inputs(uint32_t data[4][512],unsigned kind,unsigned limit)
{
    for(unsigned b=0;b<4;b++)for(unsigned i=0;i<512;i++)data[b][i]=0xdeadbeefu;
    for(unsigned i=0;i<128;i++){
        data[0][i]=(i*37)%limit;data[1][i]=(i*13)%64;
        if(kind==2 || kind==7)data[0][i]|=i&1?0xf0000000u:0;
    }
    data[0][0]=0;data[0][127]=limit-1;data[1][0]=0;data[1][127]=63;
}
static void check_index_words(const uint32_t data[4][512],const uint32_t original[4][512],
    unsigned kind,unsigned epoch,unsigned faulted)
{
    for(unsigned b=0;b<4;b++)for(unsigned i=0;i<512;i++){
        uint32_t want=i>=128 || (faulted && i<16)?original[b][i]:
            index_result(kind,epoch,original[0][i],original[1][i])^(b*0x10203u);
        if(data[b][i]!=want)fprintf(stderr,"index mismatch kind=%u epoch=%u binding=%u item=%u got=%08x want=%08x\n",kind,epoch,b,i,data[b][i],want);
        assert(data[b][i]==want);
    }
}
static void dynamic_indices(const char *dir,struct pipe_context *p,struct pipe_resource *ub[36],
    struct pipe_resource *out[4],unsigned offsets[36],unsigned sizes[36])
{
    const char *names[]={"index-default","index-block","index-masked","index-nested","index-base",
        "index-signed","index-default-nested","index-default-masked"};
    const unsigned limits[]={256,1024,1024,16,1021,1024,4,256};
    const uint32_t bad[]={0x10000000u,0x40000000u,0xffffffffu,0x80000000u};
    const uint32_t grid[3]={1,1,1},group[3]={0,0,0},local[3]={8,4,4};
    for(unsigned k=0;k<sizeof(names)/sizeof(*names);k++){
        void *f=front(dir,names[k]);const void *tokens=cs_frontend_tokens(f);
        struct opengpu_cs_program code;assert(!opengpu_cs_emit(tokens,&code));
        assert(code.words<=512 && code.reads && code.writes==15 && code.constant_bytes[0]==4096);
        assert(opengpu_compute_shader_validate_words_uniform_resources(code.code,512,12,128,
            false,false,false,true,false,false,false,false));
        struct pipe_compute_state st={.ir_type=PIPE_SHADER_IR_TGSI,.prog=tokens};
        void *cs=p->create_compute_state(p,&st);assert(cs);p->bind_compute_state(p,cs);
        cs_frontend_storage(f,p,out);sizes[35]=16384;
        uint32_t data[4][512],original[4][512],defaults[1024],block[4096];
        struct cs_vm_view m[4],u[13]={{0}};
        for(unsigned b=0;b<4;b++){
            unsigned api=cs_frontend_storage_binding(f,b);assert(api<4);
            m[b]=(struct cs_vm_view){data[api],sizeof(data[api]),3};
        }
        u[0]=(struct cs_vm_view){defaults,sizeof(defaults),1};
        u[1]=(struct cs_vm_view){block,sizeof(block),1};
        /* Each actual callback dispatch goes through GUM1, the real kernel
         * word validator and a fresh immutable modeled snapshot. Fault probes
         * below use exactly these compiler words with a checked CPU decoder. */
        for(unsigned epoch=11;epoch<=13;epoch+=2){
            fill_uniforms(ub,epoch);bind_defaults(f,p,epoch);cs_frontend_ubos(f,p,ub,offsets,sizes);
            index_inputs(data,k,limits[k]);memcpy(original,data,sizeof(data));
            for(unsigned b=0;b<4;b++)memcpy(pipe_opengpu_resource_map(opengpu_resource(out[b])->gpu),data[b],sizeof(data[b]));
            struct pipe_grid_info g={.grid={1,1,1},.block={8,4,4}};
            unsigned before=computes;uniform_mutate_after_snapshot=1;p->launch_grid(p,&g);
            uniform_mutate_after_snapshot=0;assert(computes==before+1);
            for(unsigned b=0;b<4;b++)memcpy(data[b],pipe_opengpu_resource_map(opengpu_resource(out[b])->gpu),sizeof(data[b]));
            check_index_words(data,original,k,epoch,0);
            for(unsigned i=0;i<1024;i++)defaults[i]=value(epoch,0,i);
            for(unsigned i=0;i<4096;i++)block[i]=value(epoch,12,i);
            for(unsigned n=0;n<5u+(k==3 || k==6);n++){
                index_inputs(data,k,limits[k]);
                unsigned axis=(k==3 || k==6) && n==5;
                data[axis][0]=n?bad[(n-1)%4]:limits[k];
                data[axis][7]=data[axis][0];
                if(axis)data[axis][0]=data[axis][7]=64;
                memcpy(original,data,sizeof(data));struct cs_vm_uniform_trace trace={0};
                cs_vm_group_uniforms_checked(code.code,code.words,grid,group,local,m,u,&trace);
                assert(trace.fault_warps==(k==2 || k==7?0u:1u));
                assert(trace.fault_mask[0]==(k==2 || k==7?0u:0x81u));
                for(unsigned w=1;w<8;w++)assert(!trace.fault_mask[w]);
                assert(trace.reads);check_index_words(data,original,k,epoch,k!=2 && k!=7);
            }
        }
        printf("Actual dynamic ESSL/Mesa %s:%u words/full-width bounds,full views,128 items,mixed lanes,fault and fresh snapshot PASS\n",names[k],code.words);
        p->delete_compute_state(p,cs);cs_frontend_destroy(f);
    }
}

static void address_lifetime(void)
{
    const char *head="COMP\nDCL BUFFER[0]\nDCL CONST[0][0..255]\nDCL TEMP[0..1]\nDCL ADDR[0]\nIMM[0] UINT32 {17,255,0,0}\n";
    const char *body="MOV TEMP[0].x, IMM[0].xxxx\nUARL ADDR[0].x, TEMP[0].xxxx\n"
        "MOV TEMP[0].x, IMM[0].yyyy\nMOV TEMP[1].x, CONST[0][ADDR[0].x].yyyy\n"
        "UARL ADDR[0].x, TEMP[0].xxxx\nMOV TEMP[1].y, CONST[0][ADDR[0].x].wwww\n"
        "STORE BUFFER[0].xy, IMM[0].zzzz, TEMP[1]\nEND\n";
    char text[2048];struct tgsi_token t[512];struct opengpu_cs_program code;
    assert(snprintf(text,sizeof(text),"%s%s",head,body)>0 && tgsi_text_translate(text,t,512));
    assert(!opengpu_cs_emit(t,&code));
    assert(opengpu_compute_shader_validate_words_uniform_resources(code.code,512,12,128,
        false,false,false,true,false,false,false,false));
    uint32_t defaults[1024],data[512];
    for(unsigned i=0;i<1024;i++)defaults[i]=value(19,0,i);
    for(unsigned i=0;i<512;i++)data[i]=0xdeadbeefu;
    struct cs_vm_view u[13]={{defaults,sizeof(defaults),1}},m[4]={{data,sizeof(data),3}};
    uint32_t grid[3]={1,1,1},group[3]={0},local[3]={8,4,4};
    cs_vm_group_uniforms(code.code,code.words,grid,group,local,m,u);
    assert(data[0]==defaults[17*4+1] && data[1]==defaults[255*4+3]);
    for(unsigned i=2;i<512;i++)assert(data[i]==0xdeadbeefu);
    const char *bad[]={
        "MOV TEMP[0].x, CONST[0][ADDR[0].x]\nEND\n", /* undefined address */
        "UARL ADDR[0].y, IMM[0]\nEND\n",
        "ARL ADDR[0].x, IMM[0]\nEND\n",
        "UARL ADDR[1].x, IMM[0]\nEND\n",
        "UARL ADDR[0].x, ADDR[0]\nEND\n",
        "UARL ADDR[0].x, IMM[0]\nMOV TEMP[0].x, TEMP[ADDR[0].x]\nEND\n",
        "UARL ADDR[0].x, IMM[0]\nMOV TEMP[0].x, CONST[ADDR[0].x][0]\nEND\n",
        "UARL ADDR[0].x, IMM[0]\nMOV TEMP[0].x, CONST[0][ADDR[0].y]\nEND\n",
        "UARL ADDR[0].x, IMM[0]\nMOV TEMP[0].x, CONST[0][TEMP[0].x]\nEND\n",
    };
    for(unsigned i=0;i<sizeof(bad)/sizeof(*bad);i++){
        assert(snprintf(text,sizeof(text),"%s%s",head,bad[i])>0 && tgsi_text_translate(text,t,512));
        struct opengpu_cs_program original;memset(&code,0x37,sizeof(code));original=code;
        assert(opengpu_cs_emit(t,&code)<0 && !memcmp(&code,&original,sizeof(code)));
    }
    puts("TGSI ADDR0 full-width capture/TEMP overwrite/UARL replacement,guarded source alias,undefined/unsupported address and dimension negatives PASS");
}

int main(int argc,char **argv)
{
    assert(argc==2);
    setvbuf(stdout,NULL,_IONBF,0);
    uniform_dir=argv[1];uniform_capture=capture_uniform;
    /* The unchanged default corpus still executes its actual 51/54 contract. */
    assert(!storage_callback_main());
    features|=OPENGPU_DRIVER_FEATURE_COMPUTE_UNIFORMS;
    struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
    assert(!s->get_param(s,PIPE_CAP_COMPUTE));
    assert(s->get_shader_param(s,PIPE_SHADER_COMPUTE,PIPE_SHADER_CAP_MAX_CONST_BUFFERS)==13);
    assert(s->get_shader_param(s,PIPE_SHADER_COMPUTE,PIPE_SHADER_CAP_MAX_CONST_BUFFER0_SIZE)==4096);
    struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
    void *f=front(argv[1],"full128");const void *tokens=cs_frontend_tokens(f);
    struct opengpu_cs_program code;assert(!opengpu_cs_emit(tokens,&code));
    for(unsigned i=0;i<13;i++)assert(code.constant_bytes[i]==(i?16384u:4096u));
    assert(code.words<=512 && code.writes==15);
    malformed_constants(p,tokens);
    struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=tokens};
    void *cs=p->create_compute_state(p,&state);assert(cs);p->bind_compute_state(p,cs);
    struct pipe_resource *ub[36]={0},*out[4];unsigned offsets[36]={0},sizes[36]={0};
    for(unsigned i=24;i<36;i++){ub[i]=buffer(s,16384);assert(ub[i]);sizes[i]=16384;}
    for(unsigned i=0;i<4;i++){out[i]=buffer(s,0x8000000u);assert(out[i]);}
    cs_frontend_storage(f,p,out);
    fill_uniforms(ub,1);bind_defaults(f,p,1);cs_frontend_ubos(f,p,ub,offsets,sizes);
    for(unsigned i=24;i<36;i++)assert(ub[i]->reference.count==2);
    struct pipe_grid_info g={.grid={2,1,1},.block={8,4,4}};
    clear_output(out);uniform_mutate_after_snapshot=1;
    unsigned before=computes;p->launch_grid(p,&g);assert(computes==before+1);
    uniform_mutate_after_snapshot=0;check_output(out,1,128,0);
    assert(uniform_submissions==1 && uniform_snapshots==13);
    /* Replaced defaults must use a fresh owned GEM, without reusing a CPU map
     * retained by any previous job. Original GEM-backed UBOs are copied only at
     * the submission boundary, after the modeled producer publication. */
    fill_uniforms(ub,3);bind_defaults(f,p,3);clear_output(out);
    uniform_producer_handle=pipe_opengpu_resource_handle(opengpu_resource(ub[35])->gpu);
    uniform_producer_value=0xd94a7321u;
    p->launch_grid(p,&g);check_output(out,3,128,uniform_producer_value);
    assert(!uniform_producer_handle && uniform_submissions==2 && uniform_snapshots==26);
    /* Logical and physical bounds are checked before submission. Bad bindings
     * are sticky for that slot until corrected; no stale old range is used. */
    unsigned bad[][3]={{1,0,16388},{0,0,4100},{1,2,16380},{1,0,16383},{13,0,16},{1,16384,4},{1,0xffffffffu,4}};
    for(unsigned k=0;k<sizeof(bad)/sizeof(bad[0]);k++){
        cb(p,bad[k][0],ub[24],bad[k][1],bad[k][2],false);expect_uniform_reject(p,&g);
        cs_frontend_ubos(f,p,ub,offsets,sizes);bind_defaults(f,p,3);
    }
    cb(p,1,NULL,0,0,false);expect_uniform_reject(p,&g);cs_frontend_ubos(f,p,ub,offsets,sizes);
    for(unsigned k=0;k<2;k++){
        uint32_t user[1024]={0};struct pipe_constant_buffer b={.user_buffer=user,.buffer_size=4096};
        if(k){map_error=1;}else{fail_request=DRM_IOCTL_OPENGPU_CREATE_BUFFER;fail_nth=1;fail_seen=0;}
        p->set_constant_buffer(p,PIPE_SHADER_COMPUTE,0,false,&b);expect_uniform_reject(p,&g);
        fail_request=0;bind_defaults(f,p,3);
    }
    /* Same-object READ views are legal and refs deduplicate in the kernel. */
    int refs=ub[24]->reference.count;
    cb(p,2,ub[24],0,16384,false);assert(ub[24]->reference.count==refs+1);
    cs_frontend_ubos(f,p,ub,offsets,sizes);assert(ub[24]->reference.count==refs);
    /* Caller-provided ownership is consumed exactly once, even on failure. */
    struct pipe_resource *owned=buffer(s,16384);assert(owned);
    unsigned handle=pipe_opengpu_resource_handle(opengpu_resource(owned)->gpu);
    cb(p,13,owned,0,16384,true);assert(!bos[handle].data);
    cs_frontend_ubos(f,p,ub,offsets,sizes);
    owned=buffer(s,16384);assert(owned);handle=pipe_opengpu_resource_handle(opengpu_resource(owned)->gpu);
    cb(p,1,owned,0,16384,true);assert(owned->reference.count==1);
    cs_frontend_ubos(f,p,ub,offsets,sizes);assert(!bos[handle].data);
    /* GEM-backed default values also retain their original exact source. */
    struct pipe_resource *default_gem=buffer(s,4100);assert(default_gem);
    uint32_t *dm=pipe_opengpu_resource_map(opengpu_resource(default_gem)->gpu);
    for(unsigned i=0;i<1024;i++)dm[1+i]=value(3,0,i);
    cb(p,0,default_gem,4,4096,false);assert(default_gem->reference.count==2);
    cb(p,0,default_gem,8,4096,false);expect_uniform_reject(p,&g);
    cb(p,0,default_gem,4,4096,false);
    fill_uniforms(ub,3);clear_output(out);p->launch_grid(p,&g);check_output(out,3,128,0);
    /* Failure injection covers prepare, allocation, accepted wait and terminal
     * status. Each rejected/aborted context is destroyed before healthy reuse. */
    for(unsigned k=0;k<4;k++){
        fill_uniforms(ub,3);bind_defaults(f,p,3);clear_output(out);
        fail_request=k==0?DRM_IOCTL_OPENGPU_COMPUTE_UNIFORM_RESOURCES:k==1?DRM_IOCTL_SYNCOBJ_CREATE:0;
        fail_nth=1;fail_seen=0;wait_error=k==2;status_error=k==3;
        before=computes;p->launch_grid(p,&g);
        assert(computes==before+(k>=2));
        fail_request=wait_error=status_error=0;
        fill_uniforms(ub,3);bind_defaults(f,p,3);clear_output(out);before=computes;
        p->launch_grid(p,&g);assert(computes==before+1);check_output(out,3,128,0);
    }
    for(unsigned i=0;i<13;i++)p->set_constant_buffer(p,PIPE_SHADER_COMPUTE,i,false,NULL);
    for(unsigned i=24;i<36;i++)assert(ub[i]->reference.count==1);
    assert(default_gem->reference.count==1);pipe_resource_reference(&default_gem,NULL);
    p->delete_compute_state(p,cs);cs_frontend_destroy(f);
    /* All four CONST swizzles/parallel components, with smaller exact views.
     * Previously bound unused UBOs must not create extra snapshot inputs. */
    f=front(argv[1],"swizzles");state.prog=cs_frontend_tokens(f);
    cs=p->create_compute_state(p,&state);assert(cs);p->bind_compute_state(p,cs);
    uint32_t defaults[4];for(unsigned i=0;i<4;i++)defaults[i]=value(9,0,i);
    fill_uniforms(ub,9);sizes[35]=16;
    cs_frontend_defaults(f,p,defaults,4);cs_frontend_ubos(f,p,ub,offsets,sizes);
    cs_frontend_storage(f,p,out);g.grid[0]=1;g.block[0]=8;g.block[1]=g.block[2]=4;
    clear_output(out);unsigned snapshots_before=uniform_snapshots;p->launch_grid(p,&g);
    assert(uniform_snapshots==snapshots_before+2);
    for(unsigned b=0;b<4;b++){
        uint32_t *m=pipe_opengpu_resource_map(opengpu_resource(out[b])->gpu);
        for(unsigned i=0;i<512;i++)assert(m[i]==(i<128?(value(9,0,3-b)^value(9,12,b)):0xdeadbeefu));
    }
    p->delete_compute_state(p,cs);cs_frontend_destroy(f);
    /* Real frontend partial tail: the product keeps eight physical warps but
     * this CPU oracle executes exactly125 local items from actual metadata. */
    f=front(argv[1],"full125");tokens=cs_frontend_tokens(f);
    state.prog=tokens;cs=p->create_compute_state(p,&state);assert(cs);p->bind_compute_state(p,cs);
    sizes[35]=16384;g.grid[0]=2;
    fill_uniforms(ub,7);bind_defaults(f,p,7);cs_frontend_ubos(f,p,ub,offsets,sizes);
    cs_frontend_storage(f,p,out);
    g.block[0]=g.block[1]=g.block[2]=5;clear_output(out);p->launch_grid(p,&g);check_output(out,7,125,0);
    p->delete_compute_state(p,cs);cs_frontend_destroy(f);
    dynamic_indices(argv[1],p,ub,out,offsets,sizes);address_lifetime();
    char path[4096];snprintf(path,sizeof(path),"%s/dynamic-block.comp",argv[1]);
    /* ESSL310 itself requires a constant UBO-array index; the actual frontend
     * rejects this before it can reach any driver callback. */
    assert(!cs_frontend_compile(path,NULL));
    const char *negative[]={"too-many-defaults","too-many-blocks","too-large-block"};
    for(unsigned i=0;i<3;i++){
        snprintf(path,sizeof(path),"%s/%s.comp",argv[1],negative[i]);
        printf("Actual ESSL frontend capacity negative:%s\n",negative[i]);
        assert(!cs_frontend_compile(path,NULL));
    }
    f=front(argv[1],"too-many-words");state.prog=cs_frontend_tokens(f);
    assert(!p->create_compute_state(p,&state));cs_frontend_destroy(f);
    p->destroy(p);
    for(unsigned i=24;i<36;i++)pipe_resource_reference(&ub[i],NULL);
    for(unsigned i=0;i<4;i++)pipe_resource_reference(&out[i],NULL);
    s->destroy(s);
    /* Old feature profile remains closed to CONST; no new ioctl or cap. */
    features=OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE;
    s=opengpu_drm_screen_create(7,NULL);assert(s);p=s->context_create(s,NULL,0);assert(p);
    assert(!s->get_shader_param(s,PIPE_SHADER_COMPUTE,PIPE_SHADER_CAP_MAX_CONST_BUFFERS));
    assert(!s->get_shader_param(s,PIPE_SHADER_COMPUTE,PIPE_SHADER_CAP_MAX_CONST_BUFFER0_SIZE));
    f=front(argv[1],"old-profile");state.prog=cs_frontend_tokens(f);
    before=computes;assert(!p->create_compute_state(p,&state));assert(computes==before);
    cs_frontend_destroy(f);p->destroy(p);s->destroy(s);
    for(unsigned i=1;i<next_bo;i++)assert(!bos[i].handle&&!bos[i].maps&&!bos[i].binds&&!bos[i].jobs&&!bos[i].data);
    for(unsigned i=1;i<next_sync;i++)assert(!live_sync[i]&&!exported[i]&&!jobs[i].live);
    printf("Real ESSL310/Mesa CONST:13 distinct source GEMs+4x128MiB,full1024/12x16KiB exact views,128/125 locals,immutable per-dispatch snapshots,actual binding35 mapping,range/indirect/feature/ownership/failure recovery PASS (%u uniform jobs,%u source snapshots)\n",uniform_submissions,uniform_snapshots);
    return 0;
}
