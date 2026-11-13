/* SPDX-License-Identifier: MIT */
/* Real ESSL/Mesa -> TGSI -> production emitter/validator. CPU evidence only. */
#define OPENGPU_CS_REAL_TGSI_DUMP 1
#define main old_callback_corpus
#include "cs_callback_flow.c"
#undef main
#include "cs_glsl_frontend.h"
#include "cs_shared_word_vm.h"
#include "cs_shared_atomic_word_vm.h"

static void *front(const char *directory, const char *name, const char *dump)
{
    char source[4096], prefix[4096];
    assert(snprintf(source,sizeof(source),"%s/%s.comp",directory,name)>0);
    assert(snprintf(prefix,sizeof(prefix),"%s/%s-%s",directory,name,dump)>0);
    void *f=cs_frontend_compile(source,prefix);assert(f);return f;
}
static int admitted(const struct opengpu_cs_program *p,uint32_t base,uint32_t bytes,unsigned lanes)
{
    return opengpu_compute_shader_validate_words_shared_resources(p->code,p->words,
        12,128,false,false,false,true,false,false,false,false,false,base,bytes,lanes);
}
static void preserve_program(const char *directory,const char *name,const struct opengpu_cs_program *p)
{
    char path[4096];assert(snprintf(path,sizeof(path),"%s/%s.native.words",directory,name)>0);
    FILE *f=fopen(path,"wx");assert(f);
    for(unsigned i=0;i<512;i++)assert(fprintf(f,"%08x\n",p->code[i])==9);
    assert(!fclose(f));
}
static void reject_shared(const void *tokens,const struct opengpu_cs_shared *profile)
{
    struct opengpu_cs_program untouched,actual;memset(&untouched,0xc7,sizeof(untouched));actual=untouched;
    assert(opengpu_cs_emit_shared(tokens,profile,&actual)<0&&!memcmp(&actual,&untouched,sizeof(actual)));
}
static unsigned token_at(const struct tgsi_token *tokens,unsigned opcode)
{
    struct tgsi_header h;memcpy(&h,tokens,4);
    unsigned end=(unsigned)h.HeaderSize+(unsigned)h.BodySize;
    for(unsigned p=h.HeaderSize;p<end;){
        struct tgsi_token t;memcpy(&t,tokens+p,4);
        if(t.Type==TGSI_TOKEN_TYPE_INSTRUCTION){struct tgsi_instruction i;memcpy(&i,tokens+p,4);
            if(i.Opcode==opcode)return p;
            p+=1+i.NrTokens;
        }else p+=t.NrTokens;
    }
    assert(!"missing actual Mesa instruction");return 0;
}
static void memory_binding(const void *tokens,unsigned expected)
{
    struct tgsi_parse_context p;unsigned declarations=0,accesses=0;
    assert(tgsi_parse_init(&p,tokens)==TGSI_PARSE_OK);
    while(!tgsi_parse_end_of_tokens(&p)){
        tgsi_parse_token(&p);
        if(p.FullToken.Token.Type==TGSI_TOKEN_TYPE_DECLARATION){
            const struct tgsi_full_declaration *d=&p.FullToken.FullDeclaration;
            if(d->Declaration.File==TGSI_FILE_MEMORY){
                assert(d->Declaration.MemType==TGSI_MEMORY_TYPE_SHARED&&
                    d->Range.First==TGSI_MEMORY_TYPE_SHARED&&d->Range.Last==TGSI_MEMORY_TYPE_SHARED);declarations++;
            }
        }else if(p.FullToken.Token.Type==TGSI_TOKEN_TYPE_INSTRUCTION){
            const struct tgsi_full_instruction *i=&p.FullToken.FullInstruction;
            if(i->Instruction.Opcode==TGSI_OPCODE_STORE&&i->Dst[0].Register.File==TGSI_FILE_MEMORY){
                assert((unsigned)i->Dst[0].Register.Index==expected);accesses++;
            }
            if(i->Instruction.Opcode==TGSI_OPCODE_LOAD&&i->Src[0].Register.File==TGSI_FILE_MEMORY){
                assert((unsigned)i->Src[0].Register.Index==expected);accesses++;
            }
        }
    }
    tgsi_parse_free(&p);assert(declarations==1&&accesses==64);
    printf("ACTUAL_MESA_SHARED_BINDING declaration=%u accesses=%u accessIndex=%u\n",
        TGSI_MEMORY_TYPE_SHARED,accesses,expected);
}
int main(int argc,char **argv)
{
    assert(argc==2||argc==3);
    if(argc==3){
        assert(!strcmp(argv[2],"--diagnose-original-memory-index"));
        void *f=front(argv[1],"full","original");assert(cs_frontend_shared_bytes(f)==16384);
        memory_binding(cs_frontend_tokens(f),0);cs_frontend_destroy(f);return 0;
    }
    assert(!old_callback_corpus()); /* Original callback/guard corpus remains intact. */
    void *f=front(argv[1],"full","corrected");const void *tokens=cs_frontend_tokens(f);
    assert(cs_frontend_shared_bytes(f)==16384);memory_binding(tokens,TGSI_MEMORY_TYPE_SHARED);
    struct opengpu_cs_shared profile={.base=0x10000000u,.bytes=16384,.lanes=16,
        .required_bytes=cs_frontend_shared_bytes(f)};
    struct opengpu_cs_program code,legacy;memset(&legacy,0x7a,sizeof(legacy));code=legacy;
    assert(opengpu_cs_emit(tokens,&code)<0&&!memcmp(&code,&legacy,sizeof(code)));
    assert(!opengpu_cs_emit_shared(tokens,&profile,&code)&&code.words<=512&&code.writes==1&&!code.reads);
    assert(admitted(&code,profile.base,profile.bytes,profile.lanes));
    assert(!admitted(&code,profile.base,profile.bytes-4,profile.lanes));
    assert(!admitted(&code,profile.base,0,profile.lanes));
    struct opengpu_cs_program bad_words=code;
    for(unsigned i=0;i<bad_words.words;i++)if(bad_words.code[i]==0x0000000bu){bad_words.code[i]=0x00000013u;break;}
    assert(!admitted(&bad_words,profile.base,profile.bytes,profile.lanes));
    bad_words=code;
    for(unsigned i=0;i<bad_words.words;i++)if((bad_words.code[i]&127u)==0x27u){bad_words.code[i]&=~(1u<<25);break;}
    assert(!admitted(&bad_words,profile.base,profile.bytes,profile.lanes));
    for(unsigned epoch=0;epoch<2;epoch++) {
        struct opengpu_cs_program actual=code;
        void *healthy=NULL;
        if(epoch){healthy=front(argv[1],"epoch-b","corrected");
            assert(cs_frontend_shared_bytes(healthy)==16384);
            assert(!opengpu_cs_emit_shared(cs_frontend_tokens(healthy),&profile,&actual)&&
                actual.words<=512&&admitted(&actual,profile.base,profile.bytes,profile.lanes));}
        uint32_t seed=epoch?0xa2000000u:0xa1000000u;
        preserve_program(argv[1],epoch?"epoch-b":"full",&actual);
        for(unsigned order=0;order<2;order++){
            uint32_t output[288];for(unsigned i=0;i<288;i++)output[i]=0xfade9876u;
            for(unsigned it=0;it<2;it++){
                unsigned group=order?1-it:it;
                shared_word_oracle(actual.code,actual.words,profile.base,output+16,group,seed,order);
            }
            for(unsigned group=0;group<2;group++)for(unsigned i=0;i<128;i++){
                uint32_t sum=0;for(unsigned r=0;r<32;r++)sum+=seed+(group<<16)+(i^16u)+r*128;
                assert(output[16+group*128+i]==sum);
            }
            for(unsigned i=0;i<16;i++)assert(output[i]==0xfade9876u&&output[272+i]==0xfade9876u);
        }
        printf("SHARED_HEALTHY_EPOCH epoch=%u groups=2 all8warpsBothOrders=true nativeWords=%u\n",epoch+1,actual.words);
        if(healthy)cs_frontend_destroy(healthy);
    }
    unsigned shared_words=code.words;
    for(unsigned edge=0;edge<2;edge++){
        profile.base=edge?0xffffc000u:0;
        assert(!opengpu_cs_emit_shared(tokens,&profile,&code)&&admitted(&code,profile.base,profile.bytes,profile.lanes));
    }
    profile.base=0x10000000u;
    struct opengpu_cs_shared bad=profile;bad.required_bytes=16385;reject_shared(tokens,&bad);
    bad=profile;bad.bytes=16380;reject_shared(tokens,&bad);
    bad=profile;bad.bytes=0;reject_shared(tokens,&bad);
    bad=profile;bad.lanes=0;reject_shared(tokens,&bad);
    bad=profile;bad.lanes=8;reject_shared(tokens,&bad); /* XOR16 needs the proven16-lane permutation. */
    bad=profile;bad.lanes=32;reject_shared(tokens,&bad); /* vsetivli has a five-bit AVL immediate. */
    bad=profile;bad.base=0xffffc004u;reject_shared(tokens,&bad);
    unsigned count=tgsi_num_tokens(tokens);struct tgsi_token *copy=malloc(count*4);assert(copy);
    memcpy(copy,tokens,count*4);
    unsigned pos=token_at(copy,TGSI_OPCODE_LOAD);struct tgsi_instruction *load=(struct tgsi_instruction *)(copy+pos);
    ((struct tgsi_src_register *)(copy+pos+2+load->Memory))->Index=0;
    reject_shared(copy,&profile); /* Actual Memory declaration cannot authorize index0/global. */
    memcpy(copy,tokens,count*4);pos=token_at(copy,TGSI_OPCODE_BARRIER);
    ((struct tgsi_instruction *)(copy+pos))->Opcode=TGSI_OPCODE_NOP;reject_shared(copy,&profile);
    free(copy);
    for(unsigned n=0;n<4;n++){
        const char *name[]={"uninitialized","no-barrier","divergent","peer-store"};
        void *negative=front(argv[1],name[n],"negative");
        struct opengpu_cs_shared cfg=profile;cfg.required_bytes=cs_frontend_shared_bytes(negative);
        reject_shared(cs_frontend_tokens(negative),&cfg);cs_frontend_destroy(negative);
    }
    /* A memory fence does not rendezvous with other invocations. These loads
     * are admitted only because each invocation reads its exact own stripe. */
    for(unsigned n=0;n<3;n++) {
        const char *name[]={"own","own-end","multi-own"};
        void *own=front(argv[1],name[n],"own-stripe");
        struct opengpu_cs_shared cfg=profile;cfg.required_bytes=cs_frontend_shared_bytes(own);
        struct opengpu_cs_program own_code;
        assert(cfg.required_bytes==16384);
        assert(!opengpu_cs_emit_shared(cs_frontend_tokens(own),&cfg,&own_code));
        assert(admitted(&own_code,cfg.base,cfg.bytes,cfg.lanes));
        unsigned reads=0,writes=0,barriers=0;
        for(unsigned i=0;i<own_code.words;i++) {
            reads+=(own_code.code[i]&127u)==0x07u;writes+=(own_code.code[i]&127u)==0x27u;
            barriers+=own_code.code[i]==0x0000000bu;
        }
        assert(reads==(n==2?32u:1u)&&writes==(n==2?32u:1u)&&!barriers&&own_code.words<=512);
        /* Reject each actual missing producer, even after the full array was
         * otherwise initialized. Do not infer own visibility from that prefix. */
        for(unsigned i=0;i<own_code.words;i++)if((own_code.code[i]&127u)==0x27u) {
            uint32_t saved=own_code.code[i];own_code.code[i]=0x13u;
            assert(!admitted(&own_code,cfg.base,cfg.bytes,cfg.lanes));own_code.code[i]=saved;
        }
        printf("ESSL %s: %u words, %u shared stores/%u loads, no workgroup barrier (CPU only)\n",
               name[n],own_code.words,writes,reads);
        if(n==2)for(unsigned order=0;order<2;order++) {
            uint32_t output[288];for(unsigned j=0;j<288;j++)output[j]=0xfade9876u;
            for(unsigned group=0;group<2;group++)
                shared_word_oracle_own(own_code.code,own_code.words,cfg.base,output+16,group,0xa1000000u,order);
            for(unsigned group=0;group<2;group++)for(unsigned id=0;id<128;id++) {
                uint32_t sum=0;for(unsigned stripe=0;stripe<32;stripe++)sum+=0xa1000000u+(group<<16)+id+stripe*128;
                assert(output[16+group*128+id]==sum);
            }
            for(unsigned j=0;j<16;j++)assert(output[j]==0xfade9876u&&output[272+j]==0xfade9876u);
        }
        cs_frontend_destroy(own);
    }
    void *shifted=front(argv[1],"own-shifted","negative");
    struct opengpu_cs_shared shifted_cfg=profile;
    shifted_cfg.required_bytes=cs_frontend_shared_bytes(shifted);
    reject_shared(cs_frontend_tokens(shifted),&shifted_cfg);cs_frontend_destroy(shifted);
    limit_shared_base=profile.base;limit_shared_bytes=profile.bytes;limit_shared_lanes=profile.lanes;
    for(unsigned variant=0;variant<16;variant++) {
        const unsigned signed_operations[]={0,1,2,3,4,9};
        unsigned operation=variant<10?variant:signed_operations[variant-10];
        char name[64];assert(snprintf(name,sizeof(name),variant<10?"shared-atomic-%u":"shared-atomic-i-%u",operation)>0);
        void *atomic=front(argv[1],name,"shared-atomic");
        struct opengpu_cs_shared cfg=profile;cfg.required_bytes=cs_frontend_shared_bytes(atomic);
        assert(cfg.required_bytes==16384);
        struct opengpu_cs_program actual;
        assert(!opengpu_cs_emit_shared(cs_frontend_tokens(atomic),&cfg,&actual));
        assert(actual.words<=512&&actual.shared_atomics);
        assert(opengpu_compute_shader_validate_words_shared_atomics(actual.code,actual.words,12,128,
            false,false,false,true,false,false,false,false,false,cfg.base,cfg.bytes,cfg.lanes,false,true));
        assert(!admitted(&actual,cfg.base,cfg.bytes,cfg.lanes));
        unsigned captures=0,operations=0,stores=0,barriers=0;
        for(unsigned i=0;i<actual.words;i++) {
            unsigned fn=actual.code[i]>>26;
            captures+=(actual.code[i]&127)==0x2b&&fn==30;
            operations+=(actual.code[i]&127)==0x2b&&(fn==31||fn==32);
            stores+=(actual.code[i]&127)==0x27;
            barriers+=actual.code[i]==0x0000000bu;
        }
        assert(captures==2&&operations==2&&stores==32&&barriers==1);
        for(unsigned order=0;order<2;order++) {
            uint32_t output[544];for(unsigned i=0;i<544;i++)output[i]=0xfade9876u;
            for(unsigned group=0;group<2;group++)
                shared_atomic_word_vm(actual.code,actual.words,cfg.base,output+16,group,order,operation);
            for(unsigned i=0;i<16;i++)assert(output[i]==0xfade9876u&&output[528+i]==0xfade9876u);
        }
        printf("SHARED_ATOMIC_CPU actualESSL=310 operation=%u words=%u items=128 sharedBytes=16384 oldWords=512 noGPU=true\n",operation,actual.words);
        uint64_t original_features=features;
        for(unsigned enabled=0;enabled<2;enabled++) {
            features=original_features|(enabled?OPENGPU_DRIVER_FEATURE_COMPUTE_SHARED_ATOMICS:0);
            struct pipe_screen *screen=opengpu_drm_screen_create(7,NULL);assert(screen);
            struct pipe_context *context=screen->context_create(screen,NULL,0);assert(context);
            struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,
                .prog=cs_frontend_tokens(atomic),.req_local_mem=16384};
            void *created=context->create_compute_state(context,&state);
            assert(!!created==!!enabled);
            if(created)context->delete_compute_state(context,created);
            context->destroy(context);screen->destroy(screen);no_leaks();
        }
        features=original_features;
        cs_frontend_destroy(atomic);
    }
    /* Real Gallium state creation and screen query consume production discovery.
     * No shared submit is routed through the legacy no-shared CPU word VM. */
    limit_shared_base=profile.base;limit_shared_bytes=profile.bytes;limit_shared_lanes=profile.lanes;
    struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
    uint64_t bytes=0;assert(s->get_compute_param(s,PIPE_SHADER_IR_TGSI,PIPE_COMPUTE_CAP_MAX_LOCAL_SIZE,&bytes)==8&&bytes==16384);
    struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
    struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=tokens,.req_local_mem=16384};
    void *cs=p->create_compute_state(p,&state);assert(cs);p->delete_compute_state(p,cs);
    state.req_local_mem=0;assert(!p->create_compute_state(p,&state));
    state.req_local_mem=16385;assert(!p->create_compute_state(p,&state));
    state.req_local_mem=16384;state.req_private_mem=4;assert(!p->create_compute_state(p,&state));state.req_private_mem=0;
    shared_query_error=1;bytes=0xfeed;assert(!s->get_compute_param(s,PIPE_SHADER_IR_TGSI,PIPE_COMPUTE_CAP_MAX_LOCAL_SIZE,&bytes)&&bytes==0xfeed);
    assert(!p->create_compute_state(p,&state));shared_query_error=0;
    limit_shared_bytes=8192;assert(!p->create_compute_state(p,&state));
    p->destroy(p);s->destroy(s);no_leaks();cs_frontend_destroy(f);
    printf("SHARED_FRONTEND_CPU_PASS actualESSL310 sharedBytes=16384 initializedWords=4096 items=128 barriers=2 nativeWords=%u limit=512 exactOracle=true epochs=2 groups=2 all8warpsBothOrders=true queryAndCallback=true noGPU=true\n",shared_words);
    return 0;
}
