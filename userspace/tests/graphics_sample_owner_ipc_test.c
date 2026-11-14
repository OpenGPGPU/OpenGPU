/* CPU wire codec only. No owner, IRQ or observed device result is produced. */
#include "graphics_sample_owner_ipc.h"
#include <assert.h>
#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void dump(const struct graphics_native_observation *v)
{
    printf("{\"record\":%u,\"commandId\":%u,\"completion\":%u,\"gnx\":[",v->record,v->command_id,v->completion);
    for(unsigned i=0;i<12;i++)printf("%s%u",i?",":"",v->gnx[i]);
    printf("],\"appendReadBytes\":%" PRIu64 ",\"heldLegacyStatus\":%u,\"heldStatus\":%u,\"heldIrq\":%u,\"heldIrqAsserted\":%u,"
        "\"retiredStatus\":%u,\"retiredIrq\":%u,\"retiredIrqAsserted\":%u,\"externalDrained\":%u}\n",
        v->append_read_bytes,v->held_legacy_status,v->held_status,v->held_irq,v->held_irq_asserted,
        v->retired_status,v->retired_irq,v->retired_irq_asserted,v->external_drained);
}
static void arguments(const char *directory)
{
    struct graphics_native_observation out[2],guard[2];struct opengpu_native_sample_runtime runtime,old;
    memset(out,0xa5,sizeof(out));memcpy(guard,out,sizeof(out));memset(&runtime,0x5a,sizeof(runtime));old=runtime;
    assert(graphics_sample_graphics_profile_read(NULL,&runtime)==-EINVAL && !memcmp(&runtime,&old,sizeof(old)));
    assert(graphics_sample_graphics_profile_read("missing",NULL)==-EINVAL);
    assert(graphics_sample_graphics_held_wait(directory,2,1,out)==-EINVAL);
    assert(graphics_sample_graphics_execution_wait(directory,2,1,out)==-EINVAL);
    assert(graphics_sample_graphics_held_wait(NULL,0,1,out)==-EINVAL);
    assert(graphics_sample_graphics_execution_wait(NULL,0,1,out)==-EINVAL);
    assert(graphics_sample_graphics_held_wait(directory,0,0,out)==-EINVAL);
    assert(graphics_sample_graphics_execution_wait(directory,0,0,out)==-EINVAL);
    assert(graphics_sample_graphics_cleanup_wait(directory,0)==-EINVAL);
    assert(graphics_sample_graphics_final_wait(directory,0,out)==-EINVAL);
    assert(graphics_sample_graphics_held_wait(directory,0,1,NULL)==-EINVAL);
    assert(graphics_sample_graphics_execution_wait(directory,0,1,NULL)==-EINVAL);
    assert(graphics_sample_graphics_final_wait(directory,1,NULL)==-EINVAL);
    assert(graphics_sample_graphics_final_wait(NULL,1,out)==-EINVAL);
    assert(graphics_sample_graphics_cleanup_wait(NULL,1)==-EINVAL);
    char long_path[4097];memset(long_path,'x',sizeof(long_path)-1);long_path[sizeof(long_path)-1]=0;
    assert(graphics_sample_graphics_held_wait(long_path,0,1,out)==-ENAMETOOLONG);
    assert(graphics_sample_graphics_execution_wait(long_path,0,1,out)==-ENAMETOOLONG);
    assert(graphics_sample_graphics_final_wait(long_path,1,out)==-ENAMETOOLONG);
    assert(graphics_sample_graphics_cleanup_wait(long_path,1)==-ENAMETOOLONG);
    assert(!memcmp(out,guard,sizeof(out)));
}
int main(int argc,char **argv)
{
    /* This explicit test binary accepts externally generated Python/C++ files.
     * codec held|retired|final|cleanup|profile DIRECTORY RECORD ok|bad */
    assert(argc==5);unsigned record=(unsigned)strtoul(argv[3],NULL,10);
    if(!strcmp(argv[1],"arguments")){arguments(argv[2]);return 0;}
    struct graphics_native_observation out[2],guard[2];struct opengpu_native_sample_runtime runtime,old;
    memset(out,0xa5,sizeof(out));memcpy(guard,out,sizeof(out));memset(&runtime,0x5a,sizeof(runtime));old=runtime;
    int ret;unsigned count=1;
    if(!strcmp(argv[1],"held"))ret=graphics_sample_graphics_held_wait(argv[2],record,1,out);
    else if(!strcmp(argv[1],"retired"))ret=graphics_sample_graphics_execution_wait(argv[2],record,1,out);
    else if(!strcmp(argv[1],"final")){ret=graphics_sample_graphics_final_wait(argv[2],1,out);count=2;}
    else if(!strcmp(argv[1],"cleanup"))ret=graphics_sample_graphics_cleanup_wait(argv[2],1);
    else {assert(!strcmp(argv[1],"profile"));char path[4096];int n=snprintf(path,sizeof(path),"%s/profile.bin",argv[2]);
        assert(n>0 && (size_t)n<sizeof(path));ret=graphics_sample_graphics_profile_read(path,&runtime);}
    if(!strcmp(argv[4],"bad")){
        assert(ret<0 && !memcmp(out,guard,sizeof(out)) && !memcmp(&runtime,&old,sizeof(runtime)));return 0;
    }
    assert(!strcmp(argv[4],"ok") && !ret);
    if(!strcmp(argv[1],"cleanup"))return 0;
    if(!strcmp(argv[1],"profile")){assert(!memcmp(out,guard,sizeof(out)));
        assert(!opengpu_native_sample_runtime_validate(&runtime));puts("{\"profileWords\":112,\"registers\":5}");return 0;}
    assert(!memcmp(&runtime,&old,sizeof(runtime)));
    if(count==1)assert(!memcmp(out+1,guard+1,sizeof(out[1])));
    for(unsigned i=0;i<count;i++){
        if(!strcmp(argv[1],"held"))assert(!out[i].retired_status && !out[i].retired_irq && !out[i].retired_irq_asserted && !out[i].external_drained);
        dump(out+i);
    }
    return 0;
}
