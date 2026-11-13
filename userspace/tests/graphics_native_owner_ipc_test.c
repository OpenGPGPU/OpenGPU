/* CPU protocol test only; no GPU, owner or observed completion is produced. */
#include "graphics_native_owner_ipc.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

int main(int argc,char **argv)
{
    assert(argc==2);char path[4096];
    struct graphics_native_probe probe,unchanged;
    memset(&probe,0x53,sizeof(probe));unchanged=probe;
    snprintf(path,sizeof(path),"%s/bad.profile",argv[1]);
    assert(graphics_native_probe_read(path,&probe)<0 && !memcmp(&probe,&unchanged,sizeof(probe)));
    snprintf(path,sizeof(path),"%s/profile.bin",argv[1]);
    assert(!graphics_native_probe_read(path,&probe));
    assert(probe.words[95][0]==380 && probe.words[95][1]==95 && probe.registers[3][0]==0x320);
    struct graphics_native_observation held,first,final[2],guard;
    memset(&held,0x5a,sizeof(held));guard=held;
    assert(graphics_native_held_wait(argv[1],1,1,&held)<0 && !memcmp(&held,&guard,sizeof(held)));
    assert(!graphics_native_held_wait(argv[1],0,1,&held));
    assert(held.record==0 && held.command_id==12 && held.append_read_bytes==UINT64_C(0x100000000));
    assert(held.gnx[11]==0x8000000bu && held.held_irq_asserted==1);
    assert(!held.external_drained && !held.retired_status && !held.retired_irq);
    assert(!graphics_native_execution_wait(argv[1],0,1,&first));
    assert(first.append_read_bytes==held.append_read_bytes && first.external_drained==1);
    assert(!graphics_native_final_wait(argv[1],1,final));
    assert(final[0].command_id==12 && final[1].command_id==13 &&
        final[1].append_read_bytes==UINT64_C(0x100000001));
    assert(!memcmp(first.gnx,final[0].gnx,sizeof(first.gnx)));
    puts("Native owner IPC C codec PASS (CPU protocol only)");return 0;
}
