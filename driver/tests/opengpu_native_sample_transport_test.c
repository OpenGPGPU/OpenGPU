/* Independent literal discovery/layout oracle. This is not a GPU execution. */
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "../opengpu_native_sample_transport_features.h"
static u32 registers[0x540/4],reads,sample_reads;
static u32 read_word(void *context,u32 address)
{
    assert(context==registers && !(address&3) && address<sizeof(registers));
    ++reads;if(address>=0x520)++sample_reads;return registers[address/4];
}
static void valid(void)
{
    const u32 gnc[]={0x474e4305,8,0x474e4a02,0x08000200,0xc2100800,0x08000800,0xe4908000,0x8813db02};
    const u32 gnp[]={0x474e5001,8,512,2048,2048,512,15,0};
    const u32 guc[]={0x47554301,16,1,127,3,12,24,16384,1024,1024,256,64,0x0e00000b,0x1c00002b,0x1e00002b,31};
    const u32 gsl[]={0x47534c01,8,31,32,1048576,0x04051918,0x320,0x138};
    const u32 ggs[]={0x47475301,8,31,0x474e4a02,0x474e4603,0x474e5902,0xff840206,0x04001000};
    const u32 gss[]={0x47535301,8,1,31,192,0x4d041004,0x7ff,7};
    memset(registers,0,sizeof(registers));reads=sample_reads=0;
    registers[0x68/4]=0x160;registers[0x158/4]=0x02200810;
    memcpy(registers+0x160/4,gnc,sizeof(gnc));memcpy(registers+0x4e0/4,gnp,sizeof(gnp));
    memcpy(registers+0x3a0/4,guc,sizeof(guc));memcpy(registers+0x300/4,gsl,sizeof(gsl));
    memcpy(registers+0x500/4,ggs,sizeof(ggs));memcpy(registers+0x520/4,gss,sizeof(gss));
    registers[0x320/4]=1048576;registers[0x36c/4]=2;registers[0x370/4]=16;
    registers[0x374/4]=8;registers[0x378/4]=registers[0x37c/4]=128;
}
static bool probe(u32 id,u32 flags,u32 caps,unsigned long long bytes,
    struct opengpu_native_source_limits *out,bool *layered,bool *sample)
{return opengpu_read_native_sample_transport_limits(id,flags,caps,bytes,registers,read_word,out,layered,sample);}
int main(void)
{
    const u32 caps=GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE|
        GPU_CAP_COMPUTE_SCALAR_FPU|GPU_CAP_SHADER_CONFIG_INFO|GPU_CAP_NATIVE_PIXEL_FORMATS;
    struct opengpu_native_source_limits out,before;bool layered=false,sample=false;
    valid();assert(probe(2,0x1fffff,caps,0x540,&out,&layered,&sample));
    assert(layered && sample && out.available && out.capacity==128 && out.engines==4 && sample_reads==8);
    for(unsigned i=0;i<8;++i)assert(out.sample_words[i]==registers[0x520/4+i]);
    for(unsigned word=0;word<8;++word)for(unsigned bit=0;bit<32;++bit){
        valid();registers[0x520/4+word]^=1u<<bit;memset(&out,0x5a,sizeof(out));before=out;
        layered=false;sample=true;
        assert(!probe(2,0x1fffff,caps,0x540,&out,&layered,&sample));
        assert(!memcmp(&before,&out,sizeof(out)) && !layered && sample);
    }
    for(unsigned size=0;size<0x540;++size){
        valid();memset(&out,0x5a,sizeof(out));before=out;layered=true;sample=false;
        assert(!probe(2,0x1fffff,caps,size,&out,&layered,&sample) && !reads);
        assert(!memcmp(&before,&out,sizeof(out)) && layered && !sample);
    }
    for(unsigned test=0;test<32;++test){
        valid();memset(&out,0x5a,sizeof(out));before=out;layered=false;sample=true;
        const u32 flags=0x1fffff^(1u<<test);
        if(test==20)continue; /* absence follows the original contract below */
        assert(!probe(2,flags,caps,0x540,&out,&layered,&sample) && !reads);
        assert(!memcmp(&before,&out,sizeof(out)) && !layered && sample);
    }
    for(unsigned bit=0;bit<32;++bit)if(caps&(1u<<bit)){
        valid();assert(!probe(2,0x1fffff,caps^(1u<<bit),0x540,&out,&layered,&sample) && !reads);
    }
    for(u32 address=0x500;address<0x520;address+=4){
        valid();registers[address/4]^=1;assert(!probe(2,0x1fffff,caps,0x540,&out,&layered,&sample));
    }
    for(unsigned test=0;test<8;++test){
        valid();
        switch(test){
        case 0:registers[0x36c/4]=1;break;case 1:registers[0x370/4]=8;break;
        case 2:registers[0x374/4]=4;break;case 3:registers[0x17c/4]=0x8813d902;break;
        case 4:registers[0x158/4]=0;break;case 5:registers[0x158/4]^=1u<<16;break;
        case 6:registers[0x320/4]=0;break;default:registers[0x170/4]^=1u<<16;
        }
        assert(!probe(2,0x1fffff,caps,0x540,&out,&layered,&sample));
    }
    valid();assert(!probe(1,0x1fffff,caps,0x540,&out,&layered,&sample) && !reads);
    valid();assert(!probe(2,0x1fffff,caps,0x540,0,&layered,&sample) && !reads);
    assert(!probe(2,0x1fffff,caps,0x540,&out,0,&sample) && !reads);
    assert(!probe(2,0x1fffff,caps,0x540,&out,&layered,0) && !reads);
    /* No presence means no optional read and a zero sample cache. The old
     * GGS entry still rejects the new flag; it cannot authorize ABI6/7. */
    valid();memset(registers+0x520/4,0,32);sample=true;
    assert(probe(2,0xfffff,caps,0x520,&out,&layered,&sample) && layered && !sample && !sample_reads);
    for(unsigned i=0;i<8;++i)assert(!out.sample_words[i]);
    valid();assert(!opengpu_read_native_geometry_transport_limits(2,0x1fffff,caps,0x540,
        registers,read_word,&out,&layered) && !reads);
    /* Literal SoA offsets independently match the actual FS layout: colors
     * first, then depth/control; sample input always follows point/GS inputs. */
    for(u32 version=0;version<10;++version)for(u32 varying=0;varying<18;++varying)
        for(u32 colors=0;colors<6;++colors)for(u32 capacity=0;capacity<130;++capacity){
            struct opengpu_native_sample_shader_layout layout,previous;
            memset(&layout,0x6b,sizeof(layout));previous=layout;
            const bool legal=(version==6 || version==7) && varying<=16 && colors<=4 && capacity>=1 && capacity<=128;
            assert(opengpu_native_sample_shader_layout(version,varying,colors,capacity,&layout)==legal);
            if(!legal){assert(!memcmp(&layout,&previous,sizeof(layout)));continue;}
            const u32 first=8+4*varying+(version==7?2:0),inputs=first+3;
            assert(layout.sample_mask_in_array==5 && layout.sample_id_array==first &&
                layout.sample_position_x_array==first+1 && layout.sample_position_y_array==first+2);
            assert(layout.color_array==inputs && layout.depth_array==inputs+4*colors &&
                layout.control_array==inputs+4*colors+1 && layout.input_arrays==inputs &&
                layout.prefix_bytes==(inputs+4*colors+2)*capacity*4);
        }
    struct opengpu_native_sample_shader_layout full;
    assert(opengpu_native_sample_shader_layout(7,16,4,128,&full) && full.input_arrays==77 && full.prefix_bytes==48640);
    for(u32 color=0;color<16;++color)for(u32 mask=0;mask<16;++mask)for(u32 flags=0;flags<8;++flags){
        u32 control=0;assert(opengpu_native_sample_control_word(color,flags&1,flags&2,flags&4,mask,&control));
        assert(control==((flags&1?2u:0)|(flags&2?1u:0)|(color<<2)|(flags&4?64u:0)|(mask<<7)));
    }
    u32 unchanged=0xabcdef01;
    assert(!opengpu_native_sample_control_word(16,false,false,false,0,&unchanged) && unchanged==0xabcdef01);
    assert(!opengpu_native_sample_control_word(0,false,false,false,16,&unchanged) && unchanged==0xabcdef01);
    puts("GSS1: exact discovery, safe optional reads, atomic errors, frozen GGS1 and complete sample SoA/control contract PASS");
}
