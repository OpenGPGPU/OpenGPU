/* Independent literal MMIO profile oracle; no hardware execution claim. */
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "../opengpu_native_geometry_transport_features.h"
static u32 registers[0x520/4],reads,optional_reads;
static u32 read_word(void *context,u32 address)
{
    assert(context==registers && !(address&3) && address<sizeof(registers));
    ++reads;if(address>=0x500)++optional_reads;
    return registers[address/4];
}
static void valid(void)
{
    const u32 gnc[]={0x474e4305,8,0x474e4a02,0x08000200,0xc2100800,0x08000800,0xe4908000,0x902};
    const u32 gnp[]={0x474e5001,8,512,2048,2048,512,15,0};
    const u32 guc[]={0x47554301,16,1,127,3,12,24,16384,1024,1024,256,64,0x0e00000b,0x1c00002b,0x1e00002b,31};
    const u32 gsl[]={0x47534c01,8,31,32,1048576,0x04051918,0x320,0x138};
    const u32 ggs[]={0x47475301,8,31,0x474e4a02,0x474e4603,0x474e5902,0xff840206,0x04001000};
    memset(registers,0,sizeof(registers));reads=optional_reads=0;
    registers[0x68/4]=0x160;
    memcpy(registers+0x160/4,gnc,sizeof(gnc));memcpy(registers+0x4e0/4,gnp,sizeof(gnp));
    memcpy(registers+0x3a0/4,guc,sizeof(guc));memcpy(registers+0x300/4,gsl,sizeof(gsl));
    memcpy(registers+0x500/4,ggs,sizeof(ggs));registers[0x320/4]=1048576;
    registers[0x36c/4]=2;registers[0x370/4]=16;registers[0x374/4]=8;
    registers[0x378/4]=registers[0x37c/4]=128;
}
static bool probe(u32 id,u32 flags,u32 caps,unsigned long long bytes,
    struct opengpu_native_source_limits *out,bool *enabled)
{return opengpu_read_native_geometry_transport_limits(id,flags,caps,bytes,registers,read_word,out,enabled);}
int main(void)
{
    const u32 flags=0xb000f,caps=GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE;
    struct opengpu_native_source_limits out,before;bool enabled;
    valid();enabled=false;assert(probe(0x47550002,flags,caps,0x520,&out,&enabled));
    assert(enabled && out.available && out.capacity==128 && out.engines==4 && optional_reads==8);
    for(unsigned i=0;i<8;++i)assert(out.geometry_words[i]==registers[0x500/4+i]);
    before=out;memset(registers+0x500/4,0,32);assert(!memcmp(&before,&out,sizeof(out)));
    assert(!opengpu_read_native_source_limits(0x47550002,flags,caps,0x520,registers,read_word,&out));
    assert(!opengpu_native_source_frame_valid(&out.frame));
    for(unsigned block=0;block<4;++block){
        const u32 bases[]={0x500,0x4e0,0x3a0,0x300};const u32 counts[]={8,8,16,8};
        for(unsigned word=0;word<counts[block];++word)for(unsigned bit=0;bit<32;++bit){
            valid();registers[bases[block]/4+word]^=1u<<bit;
            memset(&out,0x5a,sizeof(out));before=out;enabled=true;
            assert(!probe(0x47550002,flags,caps,0x520,&out,&enabled));
            assert(!memcmp(&out,&before,sizeof(out)) && enabled);
        }
    }
    for(unsigned test=0;test<14;++test){
        u32 id=0x47550002,f=flags,c=caps;unsigned long long bytes=0x520;
        valid();memset(&out,0x5a,sizeof(out));before=out;enabled=true;
        switch(test){
        case 0:id=0x47550001;break;case 1:f&=~0x10000u;break;case 2:f&=~0x20000u;break;
        case 3:f|=1u<<20;break;case 4:c=0;break;case 5:bytes=0x51f;break;
        case 6:registers[0x160/4]=0x474e4303;break;case 7:registers[0x168/4]=0x474e4a01;break;
        case 8:registers[0x170/4]^=1u<<16;break;case 9:registers[0x378/4]=127;break;
        case 10:registers[0x37c/4]=127;break;case 11:registers[0x36c/4]=1;break;
        case 12:registers[0x320/4]=0;break;default:registers[0x68/4]=0;
        }
        assert(!probe(id,f,c,bytes,&out,&enabled));assert(!memcmp(&out,&before,sizeof(out)) && enabled);
        if(test<6)assert(!reads);
    }
    for(unsigned size=0;size<0x520;++size){
        valid();enabled=true;
        assert(probe(0x47550001,flags&~0x80000u,0,size,&out,&enabled));
        assert(!enabled && !out.available && !reads);
    }
    /* Shared-atomic presence is a separate known namespace bit. */
    valid();assert(probe(0x47550002,flags|(1u<<18),caps,0x520,&out,&enabled));
    /* Full owner composition: all twenty GCR bits plus the real layered
     * 2048-ID renderer and unchanged five-owner 512 KiB payload pool. */
    valid();registers[0x17c/4]=0x8813db02;
    assert(probe(0x47550002,0xfffff,caps,0x520,&out,&enabled));
    assert(enabled && out.capacity==128 && out.engines==4 && out.frame.words[7]==0x8813db02);
    assert(((out.frame.words[7]>>8)&15)==11 && ((out.frame.words[7]>>12)&7)==5);
    valid();assert(!probe(2,flags,caps,0x520,0,&enabled) && !reads);
    assert(!probe(2,flags,caps,0x520,&out,0) && !reads);
    puts("GGS1: exact 8-word cache, 1280 bit corruptions, old-profile isolation, safe absent/truncated BAR and atomic failure outputs PASS");
}
