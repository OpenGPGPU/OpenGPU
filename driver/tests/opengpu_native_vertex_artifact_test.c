/* SPDX-License-Identifier: MIT */
/* Offline CPU byte consumer. Reuse the production validator/private RX/MMU
 * fixture, not a shader interpreter or a replacement admission policy. */
#define OPENGPU_NATIVE_PROGRAM_SNAPSHOT_TEST_MAIN native_program_original_regression_main
#include "opengpu_native_program_snapshot_test.c"
#undef OPENGPU_NATIVE_PROGRAM_SNAPSHOT_TEST_MAIN

static unsigned vertex_decimal(const char *text,unsigned maximum)
{
    unsigned value=0;assert(text&&*text);
    for(const char *p=text;*p;p++){
        assert(*p>='0'&&*p<='9');unsigned digit=(unsigned)(*p-'0');
        assert(value<=maximum/10&&(value<maximum/10||digit<=maximum%10));
        value=value*10+digit;
    }
    return value;
}
static void vertex_original_artifact(struct opengpu_device *gpu,
    struct opengpu_render_context *context,const char *path,unsigned attributes,
    unsigned varyings,bool uniforms)
{
    unsigned char code[2049];FILE *file=fopen(path,"rb");assert(file);
    size_t bytes=fread(code,1,sizeof(code),file);
    assert(!ferror(file)&&feof(file)&&!fclose(file)&&bytes&&bytes<=2048&&!(bytes&3));
    struct drm_opengpu_native_program_view view={.offset=64,.handle=71,.bytes=(u32)bytes};
    struct opengpu_native_program_profile profile={.stage=0,.version=1,.capacity=128,
        .attributes=attributes,.varyings=varyings,.owned_uniforms=uniforms,.scalar_fpu=true,
        .staging_bytes=(u64)(7+4*attributes+4*varyings)*512};
    producer_complete=true;memcpy(original_code+64,code,bytes);
    struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(gpu,context);
    assert(!IS_ERR(owner));struct opengpu_native_program_profile wrong=profile;
    wrong.staging_bytes--;reject(owner,view,wrong,-EINVAL);
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
    printf("offline VS artifact: words=%u attrs=%u varyings=%u capacity128 ABI1 scratch=%llu ownedUniforms=%u actual RX/MMU PASS; GPU=false\n",
        (unsigned)bytes/4,attributes,varyings,(unsigned long long)profile.staging_bytes,uniforms);
}
int main(int argc,char **argv)
{
    assert(argc>=1&&(argc-1)%5==0);
    /* All original no-argument fixture behavior remains exercised unchanged. */
    assert(!native_program_original_regression_main(1,argv));
    struct opengpu_device gpu={0};struct opengpu_render_context context={.gpu=&gpu,.references=1,.id=7};
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
    assert(!opengpu_mmu_init(&gpu));assert(!opengpu_mmu_vm_create(&gpu,&context.vm));copy_gpu=&gpu;
    for(int at=1;at<argc;at+=5){
        assert(!strcmp(argv[at],"--vertex-artifact"));
        unsigned attributes=vertex_decimal(argv[at+2],16),varyings=vertex_decimal(argv[at+3],16);
        unsigned uniforms=vertex_decimal(argv[at+4],1);
        vertex_original_artifact(&gpu,&context,argv[at+1],attributes,varyings,uniforms!=0);
    }
    opengpu_mmu_vm_destroy(&gpu,&context.vm);opengpu_mmu_fini(&gpu);
    assert(context.references==1&&!live_allocations&&!fixture_buffers);return 0;
}
