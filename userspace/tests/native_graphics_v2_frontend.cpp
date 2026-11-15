/* SPDX-License-Identifier: MIT */
#include "graphics_uniform_frontend.h"
#include <cassert>
#include <cstdio>
#include <cstdlib>
extern "C" void native_graphics_v2_test(void *,unsigned,const char *);
extern "C" void native_graphics_v2_legacy_test(void *);
int main(int argc,char **argv)
{
    if(argc==4){
        void *h=graphics_uniform_compile(argv[1],argv[2],nullptr);assert(h);
        native_graphics_v2_legacy_test(h);graphics_uniform_destroy(h);return 0;
    }
    if(argc!=5)return 2;
    void *h=graphics_uniform_compile_no_gs_v2(argv[1],argv[2],nullptr);assert(h);
    native_graphics_v2_test(h,strtoul(argv[4],nullptr,0),argv[3]);graphics_uniform_destroy(h);return 0;
}
