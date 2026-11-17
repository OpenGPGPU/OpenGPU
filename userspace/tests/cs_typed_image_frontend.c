/* Actual Mesa GLSL/NIR/TGSI compilation and independent native admission.
 * No pipe context, GEM, shader execution or GPU qualification is created. */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>
#include "cs_glsl_frontend.h"
#include "opengpu_cs_emit.h"
#include "opengpu_shader_validator.h"

/* A compiler-only process must never reach a modeled OS/GEM primitive. */
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
    static const struct {const char *name;unsigned native;} formats[]={
        {"rgba32f",35},{"rgba16f",34},{"r32f",29},{"rgba32ui",26},{"rgba16ui",24},{"rgba8ui",22},
        {"r32ui",14},{"rgba32i",25},{"rgba16i",23},{"rgba8i",21},{"r32i",13},{"rgba8",3},{"rgba8_snorm",39}};
    for(unsigned t=0;t<5;t++)for(unsigned f=0;f<13;f++){
        char source[4096],dump[4096];
        assert(snprintf(source,sizeof(source),"%s/%s-%s.comp",argv[1],targets[t],formats[f].name)>0);
        assert(snprintf(dump,sizeof(dump),"%s/%s-%s",argv[1],targets[t],formats[f].name)>0);
        void *frontend=cs_frontend_compile_buffers(source,dump);
        if(!frontend)fprintf(stderr,"typed Mesa compile failed: %s\n",source);
        assert(frontend && !cs_frontend_shared_bytes(frontend));
        struct opengpu_cs_program program;
        int error=opengpu_cs_emit(cs_frontend_tokens(frontend),&program);
        if(error)fprintf(stderr,"typed native lowering failed: %s\n",source);
        assert(!error && program.fixed_block[0]==4 && program.fixed_block[1]==1 && program.fixed_block[2]==1);
        unsigned read=0,write=0;
        for(unsigned unit=0;unit<4;unit++){
            if(program.image_reads&(1u<<unit)){
                assert(cs_frontend_image_binding(frontend,unit)==3 && program.image_format[unit]==formats[f].native);++read;
            }
            if(program.image_writes&(1u<<unit)){
                assert(cs_frontend_image_binding(frontend,unit)==1 && program.image_format[unit]==formats[f].native);++write;
            }
        }
        assert(read==1 && write==1 && !(program.image_reads&program.image_writes) && !program.image_queries);
        assert(opengpu_compute_shader_validate_words_images(program.code,program.words,64,4,false,true));
        cs_frontend_destroy(frontend);
    }
    puts("real Mesa: 65 original typed ESSL programs -> native words/reflection/production validator PASS (CPU compile only)");
    return 0;
}
