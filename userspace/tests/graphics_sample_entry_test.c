/* SPDX-License-Identifier: MIT */
/* Entry admission only: no Mesa compiler, shader, driver completion or DUT. */
#include <assert.h>
#include <stdio.h>
#include "graphics_sample_entry.h"

static const char *const names[]={"OPENGPU_NATIVE_HOST_DIRECTORY","OPENGPU_NATIVE_HOST_PROFILE",
    "OPENGPU_SAMPLE_GRAPHICS_HOST_DIRECTORY","OPENGPU_SAMPLE_GRAPHICS_HOST_PROFILE"};
static void clean(void){for(unsigned i=0;i<4;i++)assert(!unsetenv(names[i]));}
static void rejected(int argc,char *const argv[])
{
    struct graphics_sample_entry value,before;memset(&value,0xa5,sizeof(value));before=value;
    assert(graphics_sample_entry_options(argc,argv,&value)==-EINVAL && !memcmp(&value,&before,sizeof(value)));
}
int main(void)
{
    char *cpu[]={"bridge","original-essl","strict112-profile"},*live[]={"bridge","original-essl"};
    clean();rejected(0,NULL);assert(graphics_sample_entry_options(0,NULL,NULL)==-EINVAL);
#ifdef OPENGPU_NATIVE_SAMPLE_GRAPHICS_LIVE
    rejected(2,live);rejected(3,cpu);
    for(unsigned i=2;i<4;i++){
        clean();assert(!setenv(names[i],"present",1));rejected(2,live);
    }
    assert(!setenv(names[2],"controller-ipc",1) && !setenv(names[3],"strict112-profile",1));
    for(unsigned i=0;i<2;i++){
        assert(!setenv(names[i],"",1));rejected(2,live);assert(!unsetenv(names[i]));
    }
    for(unsigned i=2;i<4;i++){
        const char *valid=i==2?"controller-ipc":"strict112-profile";
        assert(!setenv(names[i],"",1));rejected(2,live);assert(!setenv(names[i],valid,1));
    }
    struct graphics_sample_entry value;assert(!graphics_sample_entry_options(2,live,&value));
    assert(!strcmp(value.sources,"original-essl") && !strcmp(value.output,"controller-ipc") &&
        !strcmp(value.profile,"strict112-profile"));
    rejected(3,cpu);char *empty[]={"bridge",""};rejected(2,empty);
    char too_long[4097];memset(too_long,'x',sizeof(too_long)-1);too_long[sizeof(too_long)-1]=0;
    assert(!setenv(names[2],too_long,1));rejected(2,live);assert(!setenv(names[2],"controller-ipc",1));
    assert(!setenv(names[3],too_long,1));rejected(2,live);
    puts("ordinary112 LIVE entry isolation PASS; no execution");
#else
    struct graphics_sample_entry value;assert(!graphics_sample_entry_options(3,cpu,&value));
    assert(value.sources==cpu[1] && value.output==cpu[1] && value.profile==cpu[2]);
    rejected(2,live);
    for(unsigned i=0;i<4;i++)for(unsigned empty=0;empty<2;empty++){
        assert(!setenv(names[i],empty?"":"forbidden",1));rejected(3,cpu);assert(!unsetenv(names[i]));
    }
    char *empty[]={"bridge","","profile"};rejected(3,empty);
    empty[1]="sources";empty[2]="";rejected(3,empty);
    puts("ordinary112 CPU entry rejects all live environments PASS");
#endif
    clean();return 0;
}
