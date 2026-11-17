/* Actual Mesa ESSL/NIR/TGSI + native admission. No OS/GEM or shader execution. */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <sys/types.h>
#include "cs_glsl_frontend.h"
#include "opengpu_cs_emit.h"
#include "opengpu_shader_validator.h"

int cs_fixture_ioctl(int fd,unsigned long request,...){(void)fd;(void)request;abort();}
int cs_fixture_dup(int fd){(void)fd;abort();}
int cs_fixture_close(int fd){(void)fd;abort();}
void *cs_fixture_mmap(void *p,size_t n,int prot,int flags,int fd,off_t off)
{(void)p;(void)n;(void)prot;(void)flags;(void)fd;(void)off;abort();}
int cs_fixture_munmap(void *p,size_t n){(void)p;(void)n;abort();}

int main(int argc,char **argv)
{
    assert(argc==2);
    static const char *targets[]={"buffer","2d","array","cube","3d"};
    static const unsigned native_targets[]={5,0,1,2,3};
    static const char *operations[]={"Exchange","Add","Xor","Or","And","Min","Max","CompSwap"};
    unsigned cases=0;
    for(unsigned target=0;target<5;target++)for(unsigned signedness=0;signedness<3;signedness++)
    for(unsigned operation=0;operation<(signedness==2?1u:8u);operation++){
        char source[4096],dump[4096];
        const char *format=signedness==2?"r32f":signedness?"r32i":"r32ui";
        assert(snprintf(source,sizeof(source),"%s/%s-%s-%s.comp",argv[1],targets[target],format,operations[operation])>0);
        assert(snprintf(dump,sizeof(dump),"%s/%s-%s-%s",argv[1],targets[target],format,operations[operation])>0);
        void *frontend=cs_frontend_compile_buffers(source,dump);
        if(!frontend)fprintf(stderr,"original Mesa image atomic compile failed: %s\n",source);
        assert(frontend && !cs_frontend_shared_bytes(frontend));
        struct opengpu_cs_program p,legacy;struct opengpu_cs_image_info info;
        int error=opengpu_cs_emit_image_info(cs_frontend_tokens(frontend),NULL,&p,&info);
        if(error)fprintf(stderr,"image atomic native lowering failed: %s\n",source);
        assert(!error && p.fixed_block[0]==4 && p.fixed_block[1]==1 && p.fixed_block[2]==1);
        assert(!opengpu_cs_emit(cs_frontend_tokens(frontend),&legacy) && !memcmp(&p,&legacy,sizeof(p)));
        assert(info.atomics==1 && info.float_exchange==(signedness==2?1u:0u) && info.targets[0]==native_targets[target] &&
            p.image_reads==1 && p.image_writes==1 && !p.image_queries && p.image_format[0]==(signedness==2?29u:signedness?13u:14u));
        assert(cs_frontend_image_binding(frontend,0)==3 && cs_frontend_storage_binding(frontend,0)==2);
        assert(opengpu_compute_shader_validate_words_image_atomics(p.code,p.words,64,4,false,false,false,true,true));
        assert(!opengpu_compute_shader_validate_words_image_atomics(p.code,p.words,64,4,false,false,false,true,false));
        unsigned atomics=0;
        for(unsigned i=0;i<p.words;i++)if((p.code[i]&127)==0x2b && (p.code[i]>>26)==23){
            assert(((p.code[i]>>12)&7)==operation);atomics++;
        }
        assert(atomics==1);cases++;cs_frontend_destroy(frontend);
    }
    assert(cases==85);
    puts("real Mesa image atomics: 80 integer/5 float-exchange original programs, reflection/native admission/capability rejection PASS (CPU compile only)");
    return 0;
}
