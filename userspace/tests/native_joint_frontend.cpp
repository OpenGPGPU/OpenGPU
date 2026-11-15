/* SPDX-License-Identifier: MIT */
#include "opengpu_native_joint.h"
#include "graphics_uniform_frontend.h"
#include <cassert>
#include <cstdlib>
extern "C" void native_joint_test(opengpu_native_joint_link *,const char *,unsigned,unsigned,unsigned);
extern "C" void native_graphics_v2_legacy_test(void *);
int main(int argc,char **argv){
 if(argc==4){auto *f=graphics_uniform_compile(argv[1],argv[2],nullptr);assert(f);native_graphics_v2_legacy_test(f);graphics_uniform_destroy(f);return 0;}
 if(argc!=9)return 2;
 const char *files[4]={argv[1],argv[2],argv[3],argv[4]};
 auto *p=opengpu_native_joint_link_create(files);assert(p);
 native_joint_test(p,argv[5],strtoul(argv[6],nullptr,0),strtoul(argv[7],nullptr,0),strtoul(argv[8],nullptr,0));
 opengpu_native_joint_link_destroy(p);return 0;
}
