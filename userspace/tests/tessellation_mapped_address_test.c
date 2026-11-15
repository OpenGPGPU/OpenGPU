/* SPDX-License-Identifier: MIT */
/* Test-only literal address blocks from the actual shared emitter helper.
 * These are not ESSL programs, shader interpretation or GPU qualification. */
#ifndef OPENGPU_TESS_MAPPED_SOURCE
#define OPENGPU_TESS_MAPPED_SOURCE "../gallium/opengpu_native_tess_uniform_emit.c"
#endif
#include OPENGPU_TESS_MAPPED_SOURCE
#include <assert.h>
#include <stdio.h>
int main(int argc,char **argv)
{
    assert(argc==2);
    for(unsigned width=1;width<=4;width++){
        struct builder b={0};mapped_descriptor(&b,20,width,31,43);
        assert(!b.failed&&b.p.words&&b.p.words<=512);
        char path[4096];int n=snprintf(path,sizeof(path),"%s/width%u.words",argv[1],width);
        assert(n>0&&(size_t)n<sizeof(path));FILE *f=fopen(path,"wb");assert(f);
        for(unsigned i=0;i<b.p.words;i++)for(unsigned j=0;j<4;j++)assert(fputc(b.p.code[i]>>(8*j)&255,f)!=EOF);
        assert(!fclose(f));
    }
    return 0;
}
