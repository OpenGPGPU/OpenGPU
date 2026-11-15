/* SPDX-License-Identifier: MIT */
/* Offline GS-linked FS CPU byte consumer. Reuse the production validator/private RX/MMU
 * fixture, not a shader interpreter or a replacement admission policy. */
#define OPENGPU_NATIVE_PROGRAM_SNAPSHOT_TEST_MAIN native_program_original_regression_main
#include "opengpu_native_program_snapshot_test.c"
#undef OPENGPU_NATIVE_PROGRAM_SNAPSHOT_TEST_MAIN

static unsigned fragment_decimal(const char *text,unsigned maximum)
{
    unsigned value=0;assert(text&&*text);
    for(const char *p=text;*p;p++){
        assert(*p>='0'&&*p<='9');unsigned digit=(unsigned)(*p-'0');
        assert(value<=maximum/10&&(value<maximum/10||digit<=maximum%10));
        value=value*10+digit;
    }
    return value;
}
static void fragment_original_artifact(struct opengpu_device *gpu,
    struct opengpu_render_context *context,const char *path,unsigned version,
    unsigned varyings,unsigned colors,bool uniforms,bool geometry,bool sample)
{
    unsigned char code[2049];FILE *file=fopen(path,"rb");assert(file);
    size_t bytes=fread(code,1,sizeof(code),file);
    assert(!ferror(file)&&feof(file)&&!fclose(file)&&bytes&&bytes<=2048&&!(bytes&3));
    struct drm_opengpu_native_program_view view={.offset=64,.handle=71,.bytes=(u32)bytes};
    assert((version==5||version==7)&&geometry&&sample==(version==7));
    struct opengpu_native_program_profile profile={.stage=1,.version=version,.capacity=128,
        .varyings=varyings,.colors=colors,.owned_uniforms=uniforms,.scalar_fpu=true,
        .geometry_inputs=geometry,.sample_inputs=sample,
        .staging_bytes=(u64)(12+4*varyings+4*colors+3*sample)*512};
    producer_complete=true;memcpy(original_code+64,code,bytes);
    struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(gpu,context);
    assert(!IS_ERR(owner));struct opengpu_native_program_profile wrong=profile;
    unsigned copies=code_copies;
    wrong.geometry_inputs=false;reject(owner,view,wrong,-EINVAL);assert(code_copies==copies);
    if(sample){wrong=profile;wrong.sample_inputs=false;reject(owner,view,wrong,-EINVAL);assert(code_copies==copies);}
    wrong=profile;wrong.staging_bytes--;reject(owner,view,wrong,-EINVAL);
    if(uniforms){wrong=profile;wrong.owned_uniforms=false;reject(owner,view,wrong,-EINVAL);}
    struct drm_opengpu_native_program_view bad=view;unsigned before=code_copies;
    bad.entry_offset=2;reject(owner,bad,profile,-EINVAL);
    bad=view;bad.entry_offset=view.bytes;reject(owner,bad,profile,-EINVAL);
    assert(code_copies==before); /* Invalid entries fail before original copy. */
    code_copy_error=-EIO;reject(owner,view,profile,-EIO);code_copy_error=0;
    assert(!opengpu_private_snapshot_retire(owner));
    accepted(gpu,context,view,profile);
    /* Do not reduce the512-word original capacity to make room for a prefix.
     * Full512-word files use entry0; shorter originals additionally exercise
     * a nonzero entry and an opaque prefix without altering any code byte. */
    if(bytes<=2044){
        literal_word(64,0xffffffffu);memcpy(original_code+68,code,bytes);
        view.bytes=(u32)bytes+4;view.entry_offset=4;
        owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
        bad=view;bad.entry_offset=0;reject(owner,bad,profile,-EINVAL);
        assert(!opengpu_private_snapshot_retire(owner));accepted(gpu,context,view,profile);
    }
    printf("offline GS-linked FS: words=%u ABI%u varyings=%u colors=%u capacity128 prefix=%llu ownedUniforms=%u geometry=%u sample=%u actual RX/MMU PASS; GPU=false\n",
        (unsigned)bytes/4,version,varyings,colors,(unsigned long long)profile.staging_bytes,uniforms,geometry,sample);
}
static void fragment_prefix_guards(struct opengpu_device *gpu,struct opengpu_render_context *context,unsigned version)
{
    const unsigned first=version==7?77u:74u,last=first+18;
    struct opengpu_native_program_profile profile={.stage=1,.version=version,.capacity=128,.varyings=16,.colors=4,
        .owned_uniforms=true,.geometry_inputs=true,.sample_inputs=version==7,.staging_bytes=(u64)(last+4)*512};
    for(unsigned slice=0;slice<last+4;slice++){
        unsigned n=64;
        literal_word(n,0x01007557u);n+=4;
        literal_word(n,7u<<25|5u<<7|0x0bu);n+=4;
        literal_word(n,23u<<26|1u<<25|5u<<15|4u<<12|2u<<7|0x57u);n+=4;
        literal_word(n,1u<<15|6u<<7|0x13u);n+=4;
        unsigned offset=slice*512;
        while(offset){unsigned step=offset>2047?2047:offset;literal_word(n,step<<20|6u<<15|6u<<7|0x13u);n+=4;offset-=step;}
        literal_word(n,1u<<25|6u<<15|6u<<12|2u<<7|0x27u);n+=4;
        literal_word(n,OPENGPU_SHADER_CEASE);n+=4;
        struct drm_opengpu_native_program_view view={.offset=64,.handle=71,.bytes=n-64};
        if(slice>=first&&slice<last)accepted(gpu,context,view,profile);
        else{struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
            reject(owner,view,profile,-EINVAL);assert(!opengpu_private_snapshot_retire(owner));}
    }
}
int main(int argc,char **argv)
{
    assert(argc>=1&&(argc-1)%8==0);
    /* All original no-argument fixture behavior remains exercised unchanged. */
    assert(!native_program_original_regression_main(1,argv));
    struct opengpu_device gpu={0};struct opengpu_render_context context={.gpu=&gpu,.references=1,.id=7};
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
    assert(!opengpu_mmu_init(&gpu));assert(!opengpu_mmu_vm_create(&gpu,&context.vm));copy_gpu=&gpu;
    fragment_prefix_guards(&gpu,&context,5);fragment_prefix_guards(&gpu,&context,7);
    for(int at=1;at<argc;at+=8){
        assert(!strcmp(argv[at],"--fragment-artifact"));
        unsigned version=fragment_decimal(argv[at+2],7),varyings=fragment_decimal(argv[at+3],16);
        unsigned colors=fragment_decimal(argv[at+4],4),uniforms=fragment_decimal(argv[at+5],1);
        unsigned geometry=fragment_decimal(argv[at+6],1),sample=fragment_decimal(argv[at+7],1);
        fragment_original_artifact(&gpu,&context,argv[at+1],version,varyings,colors,uniforms!=0,geometry!=0,sample!=0);
    }
    opengpu_mmu_vm_destroy(&gpu,&context.vm);opengpu_mmu_fini(&gpu);
    assert(context.references==1&&!live_allocations&&!fixture_buffers);return 0;
}
