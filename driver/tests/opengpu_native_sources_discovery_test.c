/* Literal runtime source-profile discovery oracle; no MMIO/RTL execution. */
#include <assert.h>
#include <string.h>
#include <stdio.h>
#include "../opengpu_native_sources_features.h"
static u32 registers[0x500/4],reads;
static u32 read_word(void *context,u32 address)
{ assert(context==registers && address<0x500 && !(address&3));++reads;return registers[address/4]; }
static void valid(void)
{
    const u32 gnc[]={0x474e4303,8,0x474e4a01,0x08000200,0xc2100800,0x08000800,0xe4908000,0x902};
    const u32 gnp[]={0x474e5001,8,512,2048,2048,512,15,0};
    const u32 guc[]={0x47554301,16,1,127,3,12,24,16384,1024,1024,256,64,0x0e00000b,0x1c00002b,0x1e00002b,31};
    const u32 gsl[]={0x47534c01,8,31,32,1048576,0x04051918,0x320,0x138};
    memset(registers,0,sizeof(registers));reads=0;
    registers[0x68/4]=0x160;
    memcpy(&registers[0x160/4],gnc,sizeof(gnc));memcpy(&registers[0x4e0/4],gnp,sizeof(gnp));
    memcpy(&registers[0x3a0/4],guc,sizeof(guc));memcpy(&registers[0x300/4],gsl,sizeof(gsl));
    registers[0x320/4]=1048576;
    registers[0x36c/4]=2;registers[0x370/4]=16;registers[0x374/4]=8;
    registers[0x378/4]=128;registers[0x37c/4]=128;
}
static bool probe(u32 id,u32 flags,u32 caps,unsigned long long size,struct opengpu_native_source_limits *out)
{return opengpu_read_native_source_limits(id,flags,caps,size,registers,read_word,out);}
int main(void)
{
    const u32 caps=GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE,flags=0x3000f;
    struct opengpu_native_source_limits out,before;
    valid();assert(probe(0x47550002,flags,caps,0x500,&out));
    assert(out.available && out.capacity==128 && out.engines==4 && out.frame.words[7]==0x902);
    assert(out.lanes==16 && out.warps==8);
    assert(opengpu_native_source_shader_config_valid(&out,caps|GPU_CAP_SHADER_CONFIG_INFO,0x02020810));
    assert(!opengpu_native_source_shader_config_valid(&out,caps,0x02020810));
    assert(!opengpu_native_source_shader_config_valid(&out,caps|GPU_CAP_SHADER_CONFIG_INFO,0x02021008)); /* same product, swapped dimensions */
    for(unsigned byte=0;byte<4;byte++)for(unsigned value=0;value<256;value++){
        u32 shader=(0x02020810u&~(255u<<(8*byte)))|(value<<(8*byte));
        bool expected=byte==2?value!=0:value==((0x02020810u>>(8*byte))&255);
        assert(opengpu_native_source_shader_config_valid(&out,caps|GPU_CAP_SHADER_CONFIG_INFO,shader)==expected);
    }
    assert(!opengpu_native_source_shader_config_valid(NULL,caps,0));
    struct opengpu_native_source_limits disabled={0};
    assert(opengpu_native_source_shader_config_valid(&disabled,0,0));
    for(unsigned which=0;which<3;++which){
        u32 base=which==0?0x4e0:which==1?0x3a0:0x300,count=which==1?16:8;
        for(unsigned word=0;word<count;++word)for(unsigned bit=0;bit<32;++bit){
            valid();registers[base/4+word]^=1u<<bit;memset(&out,0x5a,sizeof(out));before=out;
            assert(!probe(0x47550002,flags,caps,0x500,&out));assert(!memcmp(&out,&before,sizeof(out)));
        }
    }
    for(unsigned kind=0;kind<21;++kind){
        u32 id=0x47550002,f=flags,c=caps;unsigned long long size=0x500;
        valid();memset(&out,0x5a,sizeof(out));before=out;
        switch(kind){
        case 0:id=0x47550001;break;case 1:f&=~0x10000u;break;case 2:f|=1u<<18;break;
        case 3:c&=~GPU_CAP_PROTECTED_VM_STATE;break;case 4:c&=~GPU_CAP_UNIFIED_COMMANDS;break;
        case 5:size=0x4ff;break;case 6:registers[0x68/4]=0;break;
        case 7:registers[0x160/4]=0x474e4304;break;
        case 8:registers[0x160/4]=0x474e4301;break;
        case 9:registers[0x170/4]&=~0x80000000u;break;
        case 10:registers[0x17c/4]=2;break;case 11:registers[0x174/4]=0;break;
        case 12:registers[0x178/4]&=~0xc0000000u;break;
        case 13:registers[0x178/4]&=~0x7000000u;break;
        case 14:registers[0x370/4]=0;break;case 15:registers[0x378/4]=127;break;
        case 16:registers[0x37c/4]=129;break;case 17:registers[0x36c/4]=1;break;
        case 18:registers[0x320/4]=0;break;
        case 19:registers[0x17c/4]|=1u<<12;break;
        default:registers[0x170/4]=(registers[0x170/4]&~0x1f0000u)|(17u<<16);
        }
        assert(!probe(id,f,c,size,&out));assert(!memcmp(&out,&before,sizeof(out)));
        if(kind<6)assert(!reads);
    }
    valid();for(unsigned flags_without=0;flags_without<2;++flags_without){
        reads=0;assert(probe(0x47550001,flags_without?0x1000f:0,0,0,&out));
        assert(!out.available && !out.capacity && !out.engines && !reads);
    }
    valid();registers[0x160/4]=0x474e4302;
    registers[0x170/4]=0x80000800u|(4u<<16)|(4u<<21)|(4u<<26);
    registers[0x174/4]=(240u<<16)|320u;
    registers[0x178/4]=(registers[0x178/4]&~0xe00000u)|(2u<<21);
    registers[0x370/4]=4;registers[0x374/4]=2;registers[0x378/4]=8;registers[0x37c/4]=8;
    registers[0x320/4]=1;
    assert(probe(0x47550002,flags,caps,0x500,&out));assert(out.available && out.capacity==8);
    valid();registers[0x17c/4]=0x8813d902;
    assert(probe(0x47550002,flags,caps,0x500,&out));
    assert(!opengpu_read_native_source_limits(2,flags,caps,0x500,registers,read_word,0));
    puts("native source profile: safe no-probe fallback, 1024 exact-record bit corruptions, full/reduced GNC/GCR/GUC/GSL and unchanged failure outputs PASS");
    return 0;
}
