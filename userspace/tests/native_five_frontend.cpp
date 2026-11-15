/* SPDX-License-Identifier: MIT */
#include "opengpu_native_five.h"
#include <cassert>
#include <cstdlib>
extern "C" void native_five_test(opengpu_native_five_link *,const char *,unsigned);
extern "C" void native_five_mask_reject(opengpu_native_five_link *);
int main(int argc,char **argv){
    assert(argc==8);const char *files[5]={argv[1],argv[2],argv[3],argv[4],argv[5]};
    auto *link=opengpu_native_five_link_create(files);assert(link);
    unsigned mode=strtoul(argv[7],nullptr,0);
    if(mode==2)native_five_mask_reject(link);else native_five_test(link,argv[6],mode);opengpu_native_five_link_destroy(link);
}
