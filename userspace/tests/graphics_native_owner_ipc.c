/* SPDX-License-Identifier: MIT */
#include "graphics_native_owner_ipc.h"
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static uint32_t read32(const uint8_t *p)
{
    return (uint32_t)p[0] | (uint32_t)p[1]<<8 | (uint32_t)p[2]<<16 | (uint32_t)p[3]<<24;
}
static uint64_t read64(const uint8_t *p)
{
    return read32(p) | (uint64_t)read32(p+4)<<32;
}
static int read_exact(const char *path,uint8_t *output,size_t bytes)
{
    if(!path)return -EINVAL;
    FILE *stream=fopen(path,"rb");
    if(!stream)return -errno;
    size_t count=fread(output,1,bytes,stream);
    int extra=fgetc(stream),ret=0;
    if(ferror(stream))ret=-EIO;
    else if(count!=bytes || extra!=EOF)ret=-EINVAL;
    if(fclose(stream) && !ret)ret=-EIO;
    return ret;
}
static int wait_exact(const char *directory,const char *name,unsigned timeout_seconds,
    uint8_t *output,size_t bytes)
{
    char path[4096],error_path[4096];
    if(!directory || !name || !timeout_seconds)return -EINVAL;
    int length=snprintf(path,sizeof(path),"%s/%s",directory,name);
    if(length<0 || (size_t)length>=sizeof(path))return -ENAMETOOLONG;
    length=snprintf(error_path,sizeof(error_path),"%s/error",directory);
    if(length<0 || (size_t)length>=sizeof(error_path))return -ENAMETOOLONG;
    struct timespec started,now,pause={0,100000000};
    if(clock_gettime(CLOCK_MONOTONIC,&started))return -errno;
    for(;;) {
        if(!access(error_path,F_OK))return -ECANCELED;
        int ret=read_exact(path,output,bytes);
        if(ret!=-ENOENT)return ret;
        if(clock_gettime(CLOCK_MONOTONIC,&now))return -errno;
        if(now.tv_sec-started.tv_sec>=(time_t)timeout_seconds)return -ETIMEDOUT;
        if(nanosleep(&pause,NULL) && errno!=EINTR)return -errno;
    }
}
int graphics_native_probe_read(const char *path,struct graphics_native_probe *output)
{
    uint8_t bytes[12+96*8+4+5*8];struct graphics_native_probe decoded;
    if(!output)return -EINVAL;
    int ret=read_exact(path,bytes,sizeof(bytes));if(ret)return ret;
    if(memcmp(bytes,"OGPF0001",8) || read32(bytes+8)!=96 || read32(bytes+12+96*8)!=5)return -EINVAL;
    for(unsigned i=0;i<96;i++) {
        decoded.words[i][0]=read32(bytes+12+i*8);decoded.words[i][1]=read32(bytes+16+i*8);
        if(decoded.words[i][0]&3)return -EINVAL;
        for(unsigned j=0;j<i;j++)if(decoded.words[i][0]==decoded.words[j][0])return -EINVAL;
    }
    for(unsigned i=0;i<5;i++) {
        decoded.registers[i][0]=read32(bytes+12+96*8+4+i*8);
        decoded.registers[i][1]=read32(bytes+12+96*8+8+i*8);
        uint32_t address=decoded.registers[i][0];
        if(address!=0 && address!=0x60 && address!=0x68 && address!=0x320 && address!=0x158)return -EINVAL;
        for(unsigned j=0;j<i;j++)if(address==decoded.registers[j][0])return -EINVAL;
    }
    *output=decoded;return 0;
}
static int observation(const uint8_t *bytes,unsigned record,int retired,
    struct graphics_native_observation *output)
{
    struct graphics_native_observation value={0};
    value.record=read32(bytes);value.command_id=read32(bytes+4);value.completion=read32(bytes+8);
    if(value.record!=record)return -EINVAL;
    for(unsigned i=0;i<12;i++)value.gnx[i]=read32(bytes+12+i*4);
    value.append_read_bytes=read64(bytes+60);
    value.held_legacy_status=read32(bytes+68);value.held_status=read32(bytes+72);
    value.held_irq=read32(bytes+76);value.held_irq_asserted=read32(bytes+80);
    if(value.held_irq_asserted>1)return -EINVAL;
    if(retired) {
        value.retired_status=read32(bytes+84);value.retired_irq=read32(bytes+88);
        value.retired_irq_asserted=read32(bytes+92);value.external_drained=read32(bytes+96);
        if(value.retired_irq_asserted || value.external_drained!=1)return -EINVAL;
    }
    *output=value;return 0;
}
static int response(const char *directory,const char *name,unsigned record,unsigned count,
    unsigned timeout_seconds,struct graphics_native_observation *output)
{
    uint8_t bytes[12+2*100];struct graphics_native_observation values[2];
    if(!output || !count || count>2 || record+count>2)return -EINVAL;
    int ret=wait_exact(directory,name,timeout_seconds,bytes,12+count*100);if(ret)return ret;
    if(memcmp(bytes,"OGRSP001",8) || read32(bytes+8)!=count)return -EINVAL;
    for(unsigned i=0;i<count;i++) {
        ret=observation(bytes+12+i*100,record+i,1,&values[i]);if(ret)return ret;
    }
    memcpy(output,values,count*sizeof(*output));return 0;
}
int graphics_native_held_wait(const char *directory,unsigned record,unsigned timeout_seconds,
    struct graphics_native_observation *output)
{
    uint8_t bytes[12+84];char name[64];
    if(!output || record>1)return -EINVAL;
    snprintf(name,sizeof(name),"record-%u.held.bin",record);
    int ret=wait_exact(directory,name,timeout_seconds,bytes,sizeof(bytes));if(ret)return ret;
    if(memcmp(bytes,"OGHLD001",8) || read32(bytes+8)!=1)return -EINVAL;
    return observation(bytes+12,record,0,output);
}
int graphics_native_execution_wait(const char *directory,unsigned record,unsigned timeout_seconds,
    struct graphics_native_observation *output)
{
    char name[64];
    if(record>1)return -EINVAL;
    snprintf(name,sizeof(name),"record-%u.response.bin",record);
    return response(directory,name,record,1,timeout_seconds,output);
}
int graphics_native_cleanup_wait(const char *directory,unsigned timeout_seconds)
{
    uint8_t bytes[12];
    int ret=wait_exact(directory,"cleanup.authorize.bin",timeout_seconds,bytes,sizeof(bytes));
    if(ret)return ret;
    return memcmp(bytes,"OGCBA001",8) || read32(bytes+8)!=2?-EINVAL:0;
}
int graphics_native_final_wait(const char *directory,unsigned timeout_seconds,
    struct graphics_native_observation output[2])
{
    return response(directory,"response.bin",0,2,timeout_seconds,output);
}
