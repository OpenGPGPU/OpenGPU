/* SPDX-License-Identifier: MIT */
#include "graphics_sample_owner_ipc.h"
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static uint32_t le32(const uint8_t *p)
{return (uint32_t)p[0]|(uint32_t)p[1]<<8|(uint32_t)p[2]<<16|(uint32_t)p[3]<<24;}
static uint64_t le64(const uint8_t *p)
{return le32(p)|(uint64_t)le32(p+4)<<32;}
static int read_exact(const char *path,uint8_t *bytes,size_t count)
{
    if(!path)return -EINVAL;
    FILE *f=fopen(path,"rb");if(!f)return -errno;
    size_t n=fread(bytes,1,count,f);int extra=fgetc(f),ret=0;
    if(ferror(f))ret=-EIO;
    else if(n!=count || extra!=EOF)ret=-EINVAL;
    if(fclose(f) && !ret)ret=-EIO;
    return ret;
}
static int wait_exact(const char *directory,const char *name,unsigned seconds,uint8_t *bytes,size_t count)
{
    char path[4096],error[4096];
    if(!directory || !seconds)return -EINVAL;
    int n=snprintf(path,sizeof(path),"%s/%s",directory,name);
    if(n<0 || (size_t)n>=sizeof(path))return -ENAMETOOLONG;
    n=snprintf(error,sizeof(error),"%s/error",directory);
    if(n<0 || (size_t)n>=sizeof(error))return -ENAMETOOLONG;
    struct timespec start,now,pause={0,10000000};
    if(clock_gettime(CLOCK_MONOTONIC,&start))return -errno;
    for(;;){
        if(!access(error,F_OK))return -ECANCELED;
        int ret=read_exact(path,bytes,count);if(ret!=-ENOENT)return ret;
        if(clock_gettime(CLOCK_MONOTONIC,&now))return -errno;
        time_t elapsed=now.tv_sec-start.tv_sec;
        if(elapsed>(time_t)seconds || (elapsed==(time_t)seconds && now.tv_nsec>=start.tv_nsec))return -ETIMEDOUT;
        if(nanosleep(&pause,NULL) && errno!=EINTR)return -errno;
    }
}
int graphics_sample_graphics_profile_read(const char *path,struct opengpu_native_sample_runtime *output)
{
    uint8_t bytes[12+112*8+4+5*8];struct opengpu_native_sample_word words[117];
    struct opengpu_native_sample_runtime value;
    if(!output)return -EINVAL;
    int ret=read_exact(path,bytes,sizeof(bytes));if(ret)return ret;
    if(memcmp(bytes,"OGNP0002",8) || le32(bytes+8)!=112 || le32(bytes+12+112*8)!=5)return -EINVAL;
    for(unsigned i=0;i<112;i++)words[i]=(struct opengpu_native_sample_word){le32(bytes+12+i*8),le32(bytes+16+i*8)};
    for(unsigned i=0;i<5;i++)words[112+i]=(struct opengpu_native_sample_word){le32(bytes+16+112*8+i*8),le32(bytes+20+112*8+i*8)};
    ret=opengpu_native_sample_runtime_decode(words,117,&value);
    if(!ret)*output=value;
    return ret;
}
static int observation(const uint8_t *bytes,unsigned record,int retired,struct graphics_native_observation *output)
{
    struct graphics_native_observation value={0};
    value.record=le32(bytes);value.command_id=le32(bytes+4);value.completion=le32(bytes+8);
    for(unsigned i=0;i<12;i++)value.gnx[i]=le32(bytes+12+4*i);
    value.append_read_bytes=le64(bytes+60);
    value.held_legacy_status=le32(bytes+68);value.held_status=le32(bytes+72);
    value.held_irq=le32(bytes+76);value.held_irq_asserted=le32(bytes+80);
    if(value.record!=record || !value.command_id || value.command_id>255 ||
        value.completion!=(0x8700u|value.command_id) || value.gnx[0]!=0x474e5801u ||
        value.gnx[1] || value.gnx[4] || value.gnx[5] || value.gnx[9] || value.gnx[10] || value.gnx[11] ||
        (value.append_read_bytes&63) || value.append_read_bytes>(UINT64_C(1)<<33) ||
        value.held_legacy_status || !(value.held_status&2) || (value.held_status&~0x23u) ||
        value.held_irq!=3 || value.held_irq_asserted!=1)return -EINVAL;
    if(retired){
        value.retired_status=le32(bytes+84);value.retired_irq=le32(bytes+88);
        value.retired_irq_asserted=le32(bytes+92);value.external_drained=le32(bytes+96);
        if(value.retired_status!=1 || value.retired_irq!=1 || value.retired_irq_asserted || value.external_drained!=1)return -EINVAL;
    }
    *output=value;return 0;
}
int graphics_sample_graphics_held_wait(const char *directory,unsigned record,unsigned seconds,
    struct graphics_native_observation *output)
{
    uint8_t bytes[12+84];char name[64];struct graphics_native_observation value;
    if(!output || record>1)return -EINVAL;
    snprintf(name,sizeof(name),"record-%u.held.bin",record);
    int ret=wait_exact(directory,name,seconds,bytes,sizeof(bytes));if(ret)return ret;
    if(memcmp(bytes,"OGNGH001",8) || le32(bytes+8)!=1)return -EINVAL;
    ret=observation(bytes+12,record,0,&value);if(!ret)*output=value;return ret;
}
static int retired(const char *directory,const char *name,const char *magic,unsigned record,unsigned count,
    unsigned seconds,struct graphics_native_observation *output)
{
    uint8_t bytes[12+2*100];struct graphics_native_observation values[2];
    if(!output)return -EINVAL;
    int ret=wait_exact(directory,name,seconds,bytes,12+count*100);if(ret)return ret;
    if(memcmp(bytes,magic,8) || le32(bytes+8)!=count)return -EINVAL;
    for(unsigned i=0;i<count;i++){ret=observation(bytes+12+i*100,record+i,1,&values[i]);if(ret)return ret;}
    memcpy(output,values,count*sizeof(*output));return 0;
}
int graphics_sample_graphics_execution_wait(const char *directory,unsigned record,unsigned seconds,
    struct graphics_native_observation *output)
{
    char name[64];if(record>1)return -EINVAL;
    snprintf(name,sizeof(name),"record-%u.response.bin",record);
    return retired(directory,name,"OGNGR001",record,1,seconds,output);
}
int graphics_sample_graphics_cleanup_wait(const char *directory,unsigned seconds)
{
    uint8_t bytes[12];int ret=wait_exact(directory,"cleanup.authorize.bin",seconds,bytes,sizeof(bytes));
    if(ret)return ret;
    return memcmp(bytes,"OGNGB001",8) || le32(bytes+8)!=2?-EINVAL:0;
}
int graphics_sample_graphics_final_wait(const char *directory,unsigned seconds,struct graphics_native_observation output[2])
{return retired(directory,"response.bin","OGNGF001",0,2,seconds,output);}
