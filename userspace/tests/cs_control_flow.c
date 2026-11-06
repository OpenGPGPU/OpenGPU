/* SPDX-License-Identifier: MIT */
/* Real Mesa/callback/lib compiler acceptance with modeled OS/GEM primitives.
 * The mask/stack word decoder is an independent CPU arithmetic oracle, not
 * evidence of hardware reconvergence, fault ordering or resource drain. */
#define main storage_callback_main
#define OPENGPU_CS_REAL_TGSI_DUMP
#include "cs_callback_flow.c"
#undef main
#include "cs_glsl_frontend.h"
#include "tgsi/tgsi_text.h"

static const char *directory;
static const char *capture_name;
static const char *cu_directory,*cu_name;
static void *cu_frontend;
static unsigned cu_epoch,cu_captures;

static FILE *cu_file(const char *suffix)
{
    char path[4096];int n=snprintf(path,sizeof(path),"%s/%s.%s",cu_directory,cu_name,suffix);
    assert(n>0 && (size_t)n<sizeof(path));FILE *f=fopen(path,"wx");assert(f);return f;
}
static void capture_submission(const struct drm_opengpu_compute_storage *p,
    const struct context *c,const struct job *j,const struct drm_opengpu_compute_uniform_resources *gum)
{
    if(!cu_name)return;
    assert(cu_directory && cu_frontend);
    const struct drm_opengpu_resource *shader=&c->b[p->launch.shader_slot],*karg=&c->b[p->launch.kernarg_slot];
    const uint32_t *code=(const uint32_t *)(bos[shader->handle].data+shader->offset);
    struct opengpu_cs_program compiled;const struct tgsi_token *tokens=cs_frontend_tokens(cu_frontend);
    assert(!opengpu_cs_emit(tokens,&compiled) && shader->size==2048 &&
        !memcmp(code,compiled.code,compiled.words*4));
    FILE *f=cu_file("words");for(unsigned w=0;w<512;w++)assert(fprintf(f,"%08x\n",code[w])==9);assert(!fclose(f));
    f=cu_file("tgsi");unsigned n=tgsi_num_tokens(tokens);assert(fwrite(tokens,4,n,f)==n && !fclose(f));
    f=cu_file("kernarg");assert(fwrite(bos[karg->handle].data+karg->offset,1,12,f)==12 && !fclose(f));
    f=cu_file("meta");
    fprintf(f,"format=1\nname=%s\ncontext=%u\nepoch=%u\nshader.slot=%u\nshader.handle=%u\nshader.offset=%llu\nshader.bytes=%llu\ncode.words=%u\nkernarg.slot=%u\nkernarg.handle=%u\nkernarg.bytes=%llu\nuniform.enabled=%u\n",
        cu_name,p->launch.context_id,cu_epoch,p->launch.shader_slot,shader->handle,
        (unsigned long long)shader->offset,(unsigned long long)shader->size,compiled.words,
        p->launch.kernarg_slot,karg->handle,(unsigned long long)karg->size,!!gum);
    for(unsigned a=0;a<3;a++)fprintf(f,"grid.%u=%u\nlocal.%u=%u\n",a,p->launch.grid[a],a,p->launch.local[a]);
    for(unsigned b=0;b<4;b++){
        const struct drm_opengpu_resource *v=&c->b[p->bindings[b].slot];
        assert(p->bindings[b].slot==35+b && v->offset==0 && v->size>=4096);
        fprintf(f,"binding.%u.slot=%u\nbinding.%u.handle=%u\nbinding.%u.offset=%llu\nbinding.%u.bytes=%llu\nbinding.%u.access=%u\nbinding.%u.api=%u\n",
            b,p->bindings[b].slot,b,v->handle,b,(unsigned long long)v->offset,b,(unsigned long long)v->size,
            b,p->bindings[b].access,b,cs_frontend_storage_binding(cu_frontend,b));
    }
    for(unsigned slot=0;slot<13;slot++)fprintf(f,"uniform.%u.bytes=%llu\n",slot,(unsigned long long)j->uniforms[slot].bytes);
    assert(!fclose(f));
    for(unsigned b=0;b<4;b++){
        char suffix[32];snprintf(suffix,sizeof(suffix),"input%u",b);f=cu_file(suffix);
        const struct drm_opengpu_resource *v=&c->b[p->bindings[b].slot];
        assert(fwrite(bos[v->handle].data+v->offset,1,4096,f)==4096 && !fclose(f));
    }
    if(gum){f=cu_file("gum1");assert(fwrite(gum,1,sizeof(*gum),f)==sizeof(*gum) && !fclose(f));}
    for(unsigned slot=0;slot<13;slot++)if(j->uniforms[slot].data){
        char suffix[32];snprintf(suffix,sizeof(suffix),"cb%u",slot);f=cu_file(suffix);
        assert(fwrite(j->uniforms[slot].data,1,j->uniforms[slot].bytes,f)==j->uniforms[slot].bytes && !fclose(f));
    }
    cu_captures++;
}
static uint32_t uniform_value(unsigned epoch,unsigned slot,unsigned word)
{
    return (0x4ab319d7u+epoch*0x1020304u+slot*0x718293u+word*0x9e3779b9u)^
        ((slot+13u)<<(word%23));
}
static uint32_t checksum(unsigned epoch)
{
    uint32_t a=uniform_value(epoch,0,2)^uniform_value(epoch,0,1023);
    for(unsigned s=1;s<=12;s++)a=a*33u^uniform_value(epoch,s,0)^uniform_value(epoch,s,4095);
    return a;
}
static void *frontend(const char *name)
{
    char source[4096],dump[4096];
    assert(snprintf(source,sizeof(source),"%s/%s.comp",directory,name)>0);
    assert(snprintf(dump,sizeof(dump),"%s/%s",directory,name)>0);
    void *f=cs_frontend_compile(source,dump);assert(f);return f;
}
static void snapshot_capture(const struct drm_opengpu_compute_uniform_resources *gum,
    const struct job *j,const struct drm_opengpu_resource *shader)
{
    if(!capture_name)return;
    char path[4096];
    assert(gum->active_mask==4095u && gum->defaults.bytes==4096);
    unsigned handles[13]={gum->defaults.handle};
    for(unsigned s=1;s<=12;s++){handles[s]=gum->blocks[s-1].handle;assert(gum->blocks[s-1].bytes==16384);}
    for(unsigned s=0;s<13;s++)for(unsigned k=0;k<s;k++)assert(handles[s]!=handles[k]);
    for(unsigned b=0;b<4;b++){
        const struct drm_opengpu_resource *v=&contexts[j->context].b[j->compute.bindings[b].slot];
        assert(v->size==128u*1024u*1024u && j->compute.bindings[b].access==3);
    }
    snprintf(path,sizeof(path),"%s/%s.gum1",directory,capture_name);
    FILE *file=fopen(path,"wx");assert(file && fwrite(gum,1,sizeof(*gum),file)==sizeof(*gum) && !fclose(file));
    snprintf(path,sizeof(path),"%s/%s.words",directory,capture_name);file=fopen(path,"wx");assert(file);
    const uint32_t *code=(const uint32_t *)(bos[shader->handle].data+shader->offset);
    for(unsigned w=0;w<shader->size/4;w++)assert(fprintf(file,"%08x\n",code[w])==9);
    assert(!fclose(file));
    for(unsigned s=0;s<13;s++){
        snprintf(path,sizeof(path),"%s/%s.cb%u",directory,capture_name,s);file=fopen(path,"wx");
        assert(file && fwrite(j->uniforms[s].data,1,j->uniforms[s].bytes,file)==j->uniforms[s].bytes && !fclose(file));
    }
}
static void initialize_outputs(struct pipe_resource *out[4])
{
    for(unsigned b=0;b<4;b++){
        uint32_t *m=pipe_opengpu_resource_map(opengpu_resource(out[b])->gpu);
        for(unsigned w=0;w<512;w++)m[w]=0xdeadbeefu;
    }
}
static void bind_storage(void *f,struct pipe_context *p,struct pipe_resource *out[4])
{
    struct pipe_shader_buffer b[4];
    /* The full-resource programs above use the actual state-tracker's full
     * 128MiB views. Small branch-shape probes bind only their bounded prefix. */
    for(unsigned i=0;i<4;i++){
        unsigned api=cs_frontend_storage_binding(f,i);assert(api<4);
        b[i]=(struct pipe_shader_buffer){.buffer=out[api],.buffer_size=65536};
    }
    p->set_shader_buffers(p,PIPE_SHADER_COMPUTE,0,4,b,15);
}
static void bind_uniforms(void *f,struct pipe_context *p,struct pipe_resource *ub[36],unsigned epoch)
{
    unsigned offsets[36]={0},sizes[36]={0};uint32_t defaults[1024];
    for(unsigned s=24;s<36;s++){
        uint32_t *m=pipe_opengpu_resource_map(opengpu_resource(ub[s])->gpu);sizes[s]=16384;
        for(unsigned w=0;w<4096;w++)m[w]=uniform_value(epoch,s-23,w);
    }
    for(unsigned w=0;w<1024;w++)defaults[w]=uniform_value(epoch,0,w);
    assert(cs_frontend_default_components(f)==1024);
    cs_frontend_defaults(f,p,defaults,1024);memset(defaults,0x75,sizeof(defaults));
    cs_frontend_ubos(f,p,ub,offsets,sizes);
}
static void full_output(struct pipe_resource *out[4],unsigned epoch,unsigned items)
{
    uint32_t a=checksum(epoch);
    for(unsigned b=0;b<4;b++){
        const uint32_t *m=pipe_opengpu_resource_map(opengpu_resource(out[b])->gpu);
        for(unsigned w=0;w<512;w++){
            uint32_t want=0xdeadbeefu;
            if(w<2*items){
                uint32_t changed=w&1?a+17u:a^0x77u;
                want=b?(changed^w^(b*0x10203u)):(w&1?a^w:a+w);
            }
            if(m[w]!=want)fprintf(stderr,"CF full b%u word%u got%08x want%08x\n",b,w,m[w],want);
            assert(m[w]==want);
        }
    }
}
static void clear_constants(struct pipe_context *p)
{for(unsigned i=0;i<13;i++)p->set_constant_buffer(p,PIPE_SHADER_COMPUTE,i,false,NULL);}
static void simple_output(struct pipe_resource *out[4],unsigned kind)
{
    for(unsigned b=0;b<4;b++){
        const uint32_t *m=pipe_opengpu_resource_map(opengpu_resource(out[b])->gpu);
        for(unsigned i=0;i<512;i++){
            uint32_t want=0xdeadbeefu;
            if(!b && i<128){
                if(kind==0)want=i&1?i*3u:i+7u;
                if(kind==1 && (i&1))want=i*3u;
                if(kind==2)want=!(i&8)?5u:!(i&4)?4u:!(i&2)?3u:!(i&1)?2u:1u;
                if(kind==3)want=i<64?3u:5u;
            }
            if(m[i]!=want)fprintf(stderr,"CF simple kind%u b%u word%u got%08x want%08x\n",kind,b,i,m[i],want);
            assert(m[i]==want);
        }
    }
}
static struct opengpu_cs_program text_program(struct pipe_context *p,const char *body,int accepted)
{
    const char *head="COMP\nDCL SV[0], THREAD_ID\nDCL TEMP[0..5]\nDCL ADDR[0].x\n"
        "DCL CONST[0][0..255]\nDCL BUFFER[0]\nIMM[0] UINT32 {0,1,17,255}\n"
        "AND TEMP[0].x, SV[0].xxxx, IMM[0].yyyy\n";
    char text[4096];struct tgsi_token t[1024];struct opengpu_cs_program code,unchanged;
    assert(snprintf(text,sizeof(text),"%s%s",head,body)>0 && tgsi_text_translate(text,t,1024));
    memset(&code,0x73,sizeof(code));unchanged=code;
    int result=opengpu_cs_emit(t,&code);
    if(accepted){
        assert(!result && opengpu_compute_shader_validate_words_uniform_resources(code.code,512,12,128,
            false,false,false,true,false,false,false,false));
    }else assert(result<0 && !memcmp(&code,&unchanged,sizeof(code)));
    struct pipe_compute_state st={.ir_type=PIPE_SHADER_IR_TGSI,.prog=t};
    void *cs=p->create_compute_state(p,&st);assert(!!cs==!!accepted);
    if(cs)p->delete_compute_state(p,cs);
    return code;
}
static void definition_tests(struct pipe_context *p)
{
    text_program(p,"UIF TEMP[0].xxxx\nUARL ADDR[0].x, IMM[0].zzzz\nELSE\nUARL ADDR[0].x, IMM[0].wwww\nENDIF\nMOV TEMP[1].x, CONST[0][ADDR[0].x].yyyy\nEND\n",1);
    text_program(p,"UARL ADDR[0].x, IMM[0].zzzz\nUIF TEMP[0].xxxx\nUARL ADDR[0].x, IMM[0].wwww\nENDIF\nMOV TEMP[1].x, CONST[0][ADDR[0].x].yyyy\nEND\n",1);
    text_program(p,"UIF TEMP[0].xxxx\nUARL ADDR[0].x, IMM[0].zzzz\nENDIF\nMOV TEMP[1].x, CONST[0][ADDR[0].x].yyyy\nEND\n",0);
    text_program(p,"UIF TEMP[0].xxxx\nMOV TEMP[1].x, IMM[0]\nELSE\nMOV TEMP[1].x, IMM[0]\nENDIF\nMOV TEMP[2].x, TEMP[1]\nEND\n",1);
    text_program(p,"UIF TEMP[0].xxxx\nMOV TEMP[1].x, IMM[0]\nENDIF\nMOV TEMP[2].x, TEMP[1]\nEND\n",0);
    text_program(p,"UIF TEMP[1].xxxx\nENDIF\nEND\n",0);
    text_program(p,"UIF TEMP[0].xxxx\nELSE\nELSE\nENDIF\nEND\n",0);
    text_program(p,"ELSE\nEND\n",0);
    text_program(p,"UIF TEMP[0].xxxx\nEND\n",0);
    text_program(p,"ENDIF\nEND\n",0);
    puts("CF actual TGSI definitions:branch TEMP/ADDR0 intersections,prior values,malformed structure fail-closed PASS");
    char body[2048]="UIF TEMP[0].xxxx\n";
    for(unsigned arm=0;arm<2;arm++){
        for(unsigned temp=0;temp<5;temp++){
            char line[80];snprintf(line,sizeof(line),"MOV TEMP[%u], IMM[0]\n",temp);strcat(body,line);
        }
        strcat(body,arm?"ENDIF\n":"ELSE\n");
    }
    size_t end=strlen(body);strcat(body,"MOV TEMP[1].x, TEMP[4].wwww\nEND\n");
    (void)text_program(p,body,1); /* all20 native TEMP components retained */
    body[end]=0;strcat(body,"MOV TEMP[5].x, IMM[0]\nEND\n");
    (void)text_program(p,body,0); /* 21st component, no output publication */
    for(unsigned prior=0;prior<2;prior++){
        const char *both="UIF TEMP[0].xxxx\nUARL ADDR[0].x, IMM[0].zzzz\nELSE\nUARL ADDR[0].x, IMM[0].wwww\nENDIF\n";
        const char *replace="UARL ADDR[0].x, IMM[0].zzzz\nUIF TEMP[0].xxxx\nUARL ADDR[0].x, IMM[0].wwww\nENDIF\n";
        snprintf(body,sizeof(body),"%sMOV TEMP[1].x, CONST[0][ADDR[0].x].yyyy\n"
            "UADD TEMP[2].x, SV[0].xxxx, SV[0].xxxx\nUADD TEMP[2].x, TEMP[2].xxxx, TEMP[2].xxxx\n"
            "STORE BUFFER[0].x, TEMP[2].xxxx, TEMP[1].xxxx\nEND\n",prior?replace:both);
        struct opengpu_cs_program code=text_program(p,body,1);
        uint32_t data[160],defaults[1024];
        for(unsigned i=0;i<160;i++)data[i]=0xdeadbeefu;
        for(unsigned i=0;i<1024;i++)defaults[i]=uniform_value(23,0,i);
        struct cs_vm_view memory[4]={{data+16,512,3}},uniforms[13]={{defaults,sizeof(defaults),1}};
        uint32_t grid[3]={1,1,1},group[3]={0},local[3]={128,1,1};
        cs_vm_group_uniforms(code.code,code.words,grid,group,local,memory,uniforms);
        for(unsigned i=0;i<160;i++){
            unsigned item=i-16,idx=prior?(item&1?255:17):(item&1?17:255);
            assert(data[i]==(i<16 || i>=144?0xdeadbeefu:uniform_value(23,0,idx*4+1)));
        }
    }
    puts("CF all20 native TEMP components/21st rejection and ADDR0 per-lane merge/replacement/full128word guards PASS");
}
static void malformed_labels(struct pipe_context *p,const void *tokens)
{
    const struct tgsi_token *t=tokens;unsigned n=tgsi_num_tokens(t),labels=0;
    struct tgsi_token *copy=malloc(n*4);assert(copy);
    for(unsigned pos=2;pos<n;){
        struct tgsi_token token;memcpy(&token,t+pos,4);unsigned count=token.NrTokens;
        if(token.Type==TGSI_TOKEN_TYPE_INSTRUCTION){
            struct tgsi_instruction in;memcpy(&in,t+pos,4);count++;
            if(in.Label){
                for(unsigned kind=0;kind<2;kind++){
                    memcpy(copy,t,n*4);struct tgsi_instruction_label label;memcpy(&label,copy+pos+1,4);
                    if(kind)label.Padding=1;else label.Label++;
                    memcpy(copy+pos+1,&label,4);
                    struct opengpu_cs_program code,original;memset(&code,0x91,sizeof(code));original=code;
                    assert(opengpu_cs_emit(copy,&code)<0 && !memcmp(&code,&original,sizeof(code)));
                    struct pipe_compute_state st={.ir_type=PIPE_SHADER_IR_TGSI,.prog=copy};
                    assert(!p->create_compute_state(p,&st));
                }
                labels++;
            }
        }
        assert(count && count<=n-pos);pos+=count;
    }
    assert(labels==2);free(copy);
}
int main(int argc,char **argv)
{
    assert(argc==2);setvbuf(stdout,NULL,_IONBF,0);directory=argv[1];
    assert(!storage_callback_main()); /* unchanged default51 submits/54waits */
    cu_directory=getenv("OPENGPU_CF_ARTIFACTS");submission_capture=capture_submission;
    features|=OPENGPU_DRIVER_FEATURE_COMPUTE_UNIFORMS;
    struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s && !s->get_param(s,PIPE_CAP_COMPUTE));
    struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
    definition_tests(p);
    struct pipe_resource *out[4],*ub[36]={0};
    for(unsigned b=0;b<4;b++){out[b]=buffer(s,128u*1024u*1024u);assert(out[b]);}
    for(unsigned u=24;u<36;u++){ub[u]=buffer(s,16384);assert(ub[u]);}
    uniform_capture=snapshot_capture;
    for(unsigned k=0;k<2;k++){
        const char *name=k?"full125":"full128";void *f=frontend(name);
        struct opengpu_cs_program code;assert(!opengpu_cs_emit(cs_frontend_tokens(f),&code));
        assert(code.writes==15 && code.words<=512);
        for(unsigned slot=0;slot<13;slot++)assert(code.constant_bytes[slot]==(slot?16384u:4096u));
        struct pipe_compute_state st={.ir_type=PIPE_SHADER_IR_TGSI,.prog=cs_frontend_tokens(f)};
        void *cs=p->create_compute_state(p,&st);assert(cs);p->bind_compute_state(p,cs);
        cs_frontend_storage(f,p,out);
        struct pipe_grid_info g={.grid={2,1,1},.block={8,4,4}};
        if(k)g.block[0]=g.block[1]=g.block[2]=5;
        unsigned epoch=k?3:1;bind_uniforms(f,p,ub,epoch);initialize_outputs(out);capture_name=name;
        if(cu_directory && !k){cu_name="full128";cu_frontend=f;cu_epoch=epoch;}
        unsigned before=computes;uniform_mutate_after_snapshot=1;p->launch_grid(p,&g);
        uniform_mutate_after_snapshot=0;capture_name=cu_name=NULL;assert(computes==before+1);
        full_output(out,epoch,k?125:128);
        /* Actual failure cleanup is tested at the modeled ioctl boundary,
         * followed by a new immutable dispatch and complete result oracle. */
        if(!k)for(unsigned failure=0;failure<4;failure++){
            bind_uniforms(f,p,ub,5);initialize_outputs(out);before=computes;
            fail_request=failure==0?DRM_IOCTL_OPENGPU_COMPUTE_UNIFORM_RESOURCES:failure==1?DRM_IOCTL_SYNCOBJ_CREATE:0;
            fail_nth=1;fail_seen=0;wait_error=failure==2;status_error=failure==3;
            p->launch_grid(p,&g);assert(computes==before+(failure>=2));
            fail_request=wait_error=status_error=0;
            bind_uniforms(f,p,ub,7);initialize_outputs(out);before=computes;p->launch_grid(p,&g);
            assert(computes==before+1);full_output(out,7,128);
        }
        printf("CF actual Mesa %s:%u words,13 distinct CB+4x128MiB,2groups,full word/guard oracle PASS\n",name,code.words);
        clear_constants(p);p->delete_compute_state(p,cs);cs_frontend_destroy(f);
    }
    const char *simple[]={"store-if","if-only","nested","early-return"};
    for(unsigned k=0;k<4;k++){
        void *f=frontend(simple[k]);struct opengpu_cs_program code;assert(!opengpu_cs_emit(cs_frontend_tokens(f),&code));
        if(!k)malformed_labels(p,cs_frontend_tokens(f));
        struct pipe_compute_state st={.ir_type=PIPE_SHADER_IR_TGSI,.prog=cs_frontend_tokens(f)};
        void *cs=p->create_compute_state(p,&st);assert(cs);p->bind_compute_state(p,cs);bind_storage(f,p,out);
        initialize_outputs(out);struct pipe_grid_info g={.grid={1,1,1},.block={8,4,4}};
        if(cu_directory && k==2){cu_name="nested";cu_frontend=f;cu_epoch=0;}
        unsigned before=computes;p->launch_grid(p,&g);cu_name=NULL;assert(computes==before+1);simple_output(out,k);
        printf("CF actual Mesa %s:%u words/full128item branch oracle PASS\n",simple[k],code.words);
        p->delete_compute_state(p,cs);cs_frontend_destroy(f);
    }
    void *f=frontend("masked-uniform");struct opengpu_cs_program code;
    assert(!opengpu_cs_emit(cs_frontend_tokens(f),&code));
    struct pipe_compute_state st={.ir_type=PIPE_SHADER_IR_TGSI,.prog=cs_frontend_tokens(f)};
    void *cs=p->create_compute_state(p,&st);assert(cs);p->bind_compute_state(p,cs);bind_storage(f,p,out);
    uint32_t defaults[1024];for(unsigned w=0;w<1024;w++)defaults[w]=uniform_value(19,0,w);
    cs_frontend_defaults(f,p,defaults,1024);initialize_outputs(out);
    uint32_t *input=pipe_opengpu_resource_map(opengpu_resource(out[0])->gpu);
    for(unsigned i=0;i<128;i++)input[i]=i&1?i:0xffffffffu;
    struct pipe_grid_info g={.grid={1,1,1},.block={8,4,4}};
    if(cu_directory){cu_name="masked";cu_frontend=f;cu_epoch=19;}
    p->launch_grid(p,&g);cu_name=NULL;
    for(unsigned b=0;b<4;b++){
        const uint32_t *m=pipe_opengpu_resource_map(opengpu_resource(out[b])->gpu);
        for(unsigned i=0;i<512;i++){
            uint32_t a=uniform_value(19,0,i*4+1),want=0xdeadbeefu;
            if(i<128){if(b==0)want=i&1?i:0xffffffffu;
                if(b==1)want=i&1?(a<0x80000000u?a+3u:a^7u):i+11u;
                if(b==2)want=i<31?0x12345678u:0x89abcdefu;}
            assert(m[i]==want);
        }
    }
    /* Invalid selected lane is only a checked CPU fault probe. No assertion
     * here infers actual device cancellation, other-warp execution or drain. */
    uint32_t data[4][512];struct cs_vm_view memory[4],uniforms[13]={{defaults,sizeof(defaults),1}};
    for(unsigned b=0;b<4;b++){
        for(unsigned i=0;i<512;i++)data[b][i]=0xdeadbeefu;
        unsigned api=cs_frontend_storage_binding(f,b);assert(api<4);
        memory[b]=(struct cs_vm_view){data[api],sizeof(data[api]),3};
    }
    for(unsigned i=0;i<128;i++)data[0][i]=i&1?i:0xffffffffu;
    data[0][1]=0x40000000u;struct cs_vm_uniform_trace trace={0};
    uint32_t group[3]={0},grid[3]={1,1,1},local[3]={8,4,4};
    cs_vm_group_uniforms_checked(code.code,code.words,grid,group,local,memory,uniforms,&trace);
    assert(trace.fault_warps==1 && trace.fault_mask[0]==2);
    for(unsigned w=1;w<8;w++)assert(!trace.fault_mask[w]);
    puts("CF dynamic CONST/UCMP packed-v0 independent of outer lane mask,inactive OOB suppressed,selected-OOB CPU-only guard PASS");
    if(cu_directory){
        /* An additional real callback submission captures selected-invalid inputs.
         * The modeled status skips CPU execution; actual word admission and
         * immutable snapshots still run. Only the future CU can prove the
         * predicted device fault/partial earlier stores/drain behavior. */
        initialize_outputs(out);for(unsigned i=0;i<128;i++)input[i]=i&1?i:0xffffffffu;
        for(unsigned w=0;w<8;w++)input[w*16+1]=0x40000000u;
        for(unsigned b=0;b<4;b++)for(unsigned i=0;i<512;i++)data[b][i]=0xdeadbeefu;
        for(unsigned i=0;i<128;i++)data[0][i]=input[i];
        memset(&trace,0,sizeof(trace));
        cs_vm_group_uniforms_checked(code.code,code.words,grid,group,local,memory,uniforms,&trace);
        assert(trace.fault_warps==8 && !trace.reads);
        for(unsigned w=0;w<8;w++)assert(trace.fault_mask[w]==2);
        cs_frontend_defaults(f,p,defaults,1024);cu_name="selected-fault";cu_frontend=f;cu_epoch=19;
        unsigned before=computes;status_error=1;p->launch_grid(p,&g);status_error=0;cu_name=NULL;
        assert(computes==before+1);
    }
    clear_constants(p);p->delete_compute_state(p,cs);cs_frontend_destroy(f);
    if(cu_directory){
        char source[4096];snprintf(source,sizeof(source),"%s/full125.comp",directory);
        f=cs_frontend_compile(source,NULL);assert(f);st.prog=cs_frontend_tokens(f);
        cs=p->create_compute_state(p,&st);assert(cs);p->bind_compute_state(p,cs);
        cs_frontend_storage(f,p,out);bind_uniforms(f,p,ub,3);initialize_outputs(out);
        g.grid[0]=2;g.block[0]=g.block[1]=g.block[2]=5;
        cu_name="fresh125";cu_frontend=f;cu_epoch=3;
        unsigned before=computes;uniform_mutate_after_snapshot=1;p->launch_grid(p,&g);
        uniform_mutate_after_snapshot=0;cu_name=NULL;assert(computes==before+1);full_output(out,3,125);
        clear_constants(p);p->delete_compute_state(p,cs);cs_frontend_destroy(f);
        assert(cu_captures==5);puts("CF native exports:5 actual frontend/callback/validator parents with original grid/local/view/snapshot/input identities PASS (GPU UNRUN)");
    }
    const char *negative[]={"constant-loop","dynamic-loop","loop-break","too-deep"};
    for(unsigned k=0;k<4;k++){
        f=frontend(negative[k]);st.prog=cs_frontend_tokens(f);unsigned before=computes;
        assert(!p->create_compute_state(p,&st) && computes==before);cs_frontend_destroy(f);
        printf("CF actual Mesa unsupported:%s fail-closed PASS\n",negative[k]);
    }
    p->destroy(p);for(unsigned i=24;i<36;i++)pipe_resource_reference(&ub[i],NULL);
    for(unsigned b=0;b<4;b++)pipe_resource_reference(&out[b],NULL);
    s->destroy(s);
    no_leaks();puts("Real ESSL/Mesa/callback/lib forward SIMT if/else CPU acceptance PASS;CAP0,loops/break/hardware proof still open");
    return 0;
}
