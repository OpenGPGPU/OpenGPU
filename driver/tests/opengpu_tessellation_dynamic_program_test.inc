/* Version-2 permission/dataflow tests, not a patch service or shader VM. */
static struct opengpu_tessellation_program_profile dynamic_policy(void)
{
    struct opengpu_tessellation_program_profile p=policy(5);
    p.version=2;p.patch_words=0;p.output_components=8;p.owned_patch_reads=true;
    p.tes_patch_layout=(struct opengpu_tessellation_patch_layout){32,64,32,60,120};
    return p;
}
static void dynamic_constant(u32 *words,u32 *n,u32 reg,u32 value)
{
    words[(*n)++]=((value+2048u)&~4095u)|reg<<7|0x37;
    words[(*n)++]=ti(reg,reg,(int)(value&4095u));
}
static u32 dynamic_program(u32 *words,u32 vertex,u32 address,bool unknown_vertex,bool unknown_address,
    bool unmasked,bool mask_defined,bool destination_defined,u32 rd)
{
    u32 n=0;words[n++]=0x0104f057;
    if(unknown_vertex)words[n++]=tv(23,0,2,1,0,true);
    else {dynamic_constant(words,&n,10,vertex);words[n++]=tv(23,4,2,10,0,true);}
    if(unknown_address)words[n++]=tv(23,0,3,1,0,true);
    else {dynamic_constant(words,&n,10,address);words[n++]=tv(23,4,3,10,0,true);}
    if(mask_defined)words[n++]=tv(23,3,0,31,0,true);
    if(destination_defined)words[n++]=tv(23,3,rd,0,0,true);
    words[n++]=tp(33,rd,2,3,unmasked);
    words[n++]=tv(16,2,12,0,rd,true); /* The read must actually define a usable result. */
    words[n++]=ti(10,0,0);words[n++]=tg(33,0,10,12);
    words[n++]=tg(35,0,0,0);words[n++]=tg(36,0,0,0);words[n++]=OPENGPU_SHADER_CEASE;
    assert(n<64);return n;
}
static void dynamic_program_tests(void)
{
    struct opengpu_tessellation_program_profile p=dynamic_policy(),bad;
    u32 words[64],n;
    const u32 publication[]={tg(35,0,0,0),tg(36,0,0,0),OPENGPU_SHADER_CEASE};
    assert(opengpu_tessellation_program_profile_valid(&p));
    assert(opengpu_tessellation_shader_validate_words(publication,3,&p));
    /* V1's new layout and permission fields must ALL be zero, even when no
     * new instruction appears; TCS cannot opt into the TES-only dialect. */
    for(u32 stage=4;stage<=5;++stage){
        for(u32 field=0;field<6;++field){
            bad=policy(stage);
            switch(field){
            case 0:bad.owned_patch_reads=true;break;
            case 1:bad.tes_patch_layout.input_vertices=1;break;
            case 2:bad.tes_patch_layout.input_components=1;break;
            case 3:bad.tes_patch_layout.output_vertices=1;break;
            case 4:bad.tes_patch_layout.output_components=1;break;
            case 5:bad.tes_patch_layout.patch_components=1;break;
            }
            assert(!opengpu_tessellation_program_profile_valid(&bad));
        }
    }
    for(u32 field=0;field<19;++field){
        bad=p;
        switch(field){
        case 0:bad.version=1;break;case 1:bad.version=3;break;case 2:bad.stage=4;break;
        case 3:bad.stage=1;break;case 4:bad.available=false;break;case 5:bad.owned_patch_reads=false;break;
        case 6:bad.patch_words=1;break;case 7:bad.input_vertices=1;break;case 8:bad.input_components=1;break;
        case 9:bad.output_vertices=1;break;case 10:bad.patch_components=1;break;
        case 11:bad.tes_patch_layout.input_vertices=0;break;case 12:bad.tes_patch_layout.input_vertices=33;break;
        case 13:bad.tes_patch_layout.input_components=65;break;case 14:bad.tes_patch_layout.output_vertices=0;break;
        case 15:bad.tes_patch_layout.output_vertices=33;break;case 16:bad.tes_patch_layout.output_components=65;break;
        case 17:bad.tes_patch_layout.patch_components=121;break;
        case 18:bad.tes_patch_layout.output_components=61;break; /* 2072 coupled words */
        }
        assert(!opengpu_tessellation_program_profile_valid(&bad));
        assert(!opengpu_tessellation_shader_validate_words(publication,3,&bad));
    }
    bad=p;bad.tes_patch_layout.output_components=64;bad.tes_patch_layout.patch_components=0;
    assert(opengpu_tessellation_program_profile_valid(&bad)); /* 2048 */
    bad.tes_patch_layout.patch_components=1;assert(!opengpu_tessellation_program_profile_valid(&bad));
    bad.tes_patch_layout.output_components=61;bad.tes_patch_layout.patch_components=96;
    assert(opengpu_tessellation_program_profile_valid(&bad)); /* 2048, separate patch tail */
    bad.tes_patch_layout.patch_components=97;assert(!opengpu_tessellation_program_profile_valid(&bad));
    /* TES export count is unrelated to the owned TCS output layout. */
    n=dynamic_program(words,31,(59u<<3)|1,false,false,true,false,false,4);
    assert(opengpu_tessellation_shader_validate_words(words,n,&p)); /* comp59 > TES exports8 */
    bad=p;bad.output_components=64;bad.tes_patch_layout.output_components=4;
    n=dynamic_program(words,0,(4u<<3)|1,false,false,true,false,false,4);
    assert(!opengpu_tessellation_shader_validate_words(words,n,&bad));
    const struct {u32 vertex,address;bool valid;} cases[]={
        {0,1,true},{31,473,true},{32,1,false},{64,1,false},{0x80000000u,1,false},
        {0,0,false},{0,2,false},{0,4,false},{0,6,false},{0,5,false},
        {0,3,true},{0,(119u<<3)|3,true},{0,(120u<<3)|3,false},{1,3,false},
        {0,7,true},{0,47,true},{0,55,false},{1,7,false},
        {0,1025,false},{0,0x80000001u,false},{0,1023,false},{0,481,false},
    };
    for(u32 i=0;i<sizeof(cases)/sizeof(cases[0]);++i){
        n=dynamic_program(words,cases[i].vertex,cases[i].address,false,false,true,false,false,4);
        assert(opengpu_tessellation_shader_validate_words(words,n,&p)==cases[i].valid);
    }
    /* All zero components is legal, but only levels remain readable. */
    bad=p;bad.tes_patch_layout=(struct opengpu_tessellation_patch_layout){1,0,1,0,0};
    n=dynamic_program(words,0,47,false,false,true,false,false,4);assert(opengpu_tessellation_shader_validate_words(words,n,&bad));
    n=dynamic_program(words,0,1,false,false,true,false,false,4);assert(!opengpu_tessellation_shader_validate_words(words,n,&bad));
    n=dynamic_program(words,0,3,false,false,true,false,false,4);assert(!opengpu_tessellation_shader_validate_words(words,n,&bad));
    /* Unknown full-width indices are defined, not truncated or statically
     * authorized; the actual owned service must check every selected lane. */
    n=dynamic_program(words,0,0,true,true,true,false,false,4);assert(opengpu_tessellation_shader_validate_words(words,n,&p));
    bad=policy(5);assert(!opengpu_tessellation_shader_validate_words(words,n,&bad));
    bad=policy(4);assert(!opengpu_tessellation_shader_validate_words(words,n,&bad));
    n=dynamic_program(words,32,0,false,true,true,false,false,4);assert(!opengpu_tessellation_shader_validate_words(words,n,&p));
    n=dynamic_program(words,0,47,true,false,true,false,false,4);assert(opengpu_tessellation_shader_validate_words(words,n,&p));
    /* Find the real fn33 read, then mutate its encoding/sources/lifetime. */
    n=dynamic_program(words,0,1,false,false,true,false,false,4);u32 read=0;
    for(u32 i=0;i<n;++i)if((words[i]&127)==0x2b){read=i;break;}
    assert(read);u32 saved=words[read];
    words[read]=saved|1u<<12;assert(!opengpu_tessellation_shader_validate_words(words,n,&p));
    words[read]=tp(34,4,2,3,true);assert(!opengpu_tessellation_shader_validate_words(words,n,&p));
    words[read]=tp(33,4,31,3,true);assert(!opengpu_tessellation_shader_validate_words(words,n,&p));
    words[read]=tp(33,4,2,31,true);assert(!opengpu_tessellation_shader_validate_words(words,n,&p));
    words[read]=saved;words[0]=0x13;assert(!opengpu_tessellation_shader_validate_words(words,n,&p));
    /* No other vector operation can accidentally enforce this read's VL rule. */
    const u32 no_vl[]={tp(33,2,1,1,true),tg(33,0,0,0),tg(35,0,0,0),tg(36,0,0,0),OPENGPU_SHADER_CEASE};
    assert(!opengpu_tessellation_shader_validate_words(no_vl,5,&p));
    const u32 after_emit[]={0x0104f057,tg(35,0,0,0),tp(33,2,1,1,true),tg(36,0,0,0),OPENGPU_SHADER_CEASE};
    const u32 after_end[]={0x0104f057,tg(35,0,0,0),tg(36,0,0,0),tp(33,2,1,1,true),OPENGPU_SHADER_CEASE};
    assert(!opengpu_tessellation_shader_validate_words(after_emit,5,&p));
    assert(!opengpu_tessellation_shader_validate_words(after_end,5,&p));
    /* Masked loads preserve inactive old vd: both v0 and old destination are
     * required. Masked vd=v0 is forbidden by the existing definedness model. */
    n=dynamic_program(words,0,1,false,false,false,false,true,4);assert(!opengpu_tessellation_shader_validate_words(words,n,&p));
    n=dynamic_program(words,0,1,false,false,false,true,false,4);assert(!opengpu_tessellation_shader_validate_words(words,n,&p));
    n=dynamic_program(words,0,1,false,false,false,true,true,4);assert(opengpu_tessellation_shader_validate_words(words,n,&p));
    n=dynamic_program(words,0,1,false,false,false,true,true,0);assert(!opengpu_tessellation_shader_validate_words(words,n,&p));
    n=dynamic_program(words,0,1,false,false,true,false,false,0);assert(opengpu_tessellation_shader_validate_words(words,n,&p));
    /* The new result may define v0, but must not fabricate constant mask bits. */
    u32 mask_result[]={0x0104f057,tv(23,3,2,0,0,true),tv(23,3,3,1,0,true),tv(23,3,4,0,0,true),
        tp(33,0,2,3,true),tp(33,4,2,3,false),tv(16,2,12,0,4,true),tg(33,0,0,12),tg(35,0,0,0),tg(36,0,0,0),OPENGPU_SHADER_CEASE};
    assert(opengpu_tessellation_shader_validate_words(mask_result,11,&p));
    /* CUSTOM0 fn32 version2 exposes only coords; output publication fn33 has
     * its independent TES-export bound rather than the patch layout. */
    u32 coords[]={ti(10,0,0),ti(11,0,2),tg(32,12,10,11),tg(33,0,0,12),tg(35,0,0,0),tg(36,0,0,0),OPENGPU_SHADER_CEASE};
    assert(opengpu_tessellation_shader_validate_words(coords,7,&p));
    coords[1]=ti(11,0,3);assert(!opengpu_tessellation_shader_validate_words(coords,7,&p));
    coords[1]=ti(11,0,2);coords[3]=tg(33,0,11,12);assert(opengpu_tessellation_shader_validate_words(coords,7,&p));
    coords[1]=ti(11,0,8);coords[2]=ti(12,0,0);assert(!opengpu_tessellation_shader_validate_words(coords,7,&p));
}
static void dynamic_snapshot_tests(void)
{
    struct opengpu_device gpu={0};struct opengpu_render_context ctx={.gpu=&gpu,.references=1,.id=7};
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
    assert(!opengpu_mmu_init(&gpu) && !opengpu_mmu_vm_create(&gpu,&ctx.vm));copy_gpu=&gpu;
    struct opengpu_tessellation_program_profile p=dynamic_policy();u32 words[64];
    u32 count=dynamic_program(words,31,473,false,false,true,false,false,4);
    code_init();word(tc_code,64,0xffffffff);
    for(u32 i=0;i<count;++i)word(tc_code,68+4*i,words[i]);
    word(tc_code,68+4*count,0xffffffff); /* Unreachable suffix is copied unchanged. */
    struct drm_opengpu_native_program_view view={.handle=71,.offset=64,.bytes=4*(count+2),.entry_offset=4};
    u8 expected[264];assert(view.bytes<=sizeof(expected));memcpy(expected,tc_code+64,view.bytes);
    struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(owner));
    struct opengpu_native_program_snapshot out={0x55,0x66},saved=out;
    p.owned_patch_reads=false;
    assert(opengpu_tessellation_program_snapshot_append(owner,&view,&p,program_copy,tc_code,&out)==-EINVAL &&
        !tc_code_copies && !memcmp(&out,&saved,sizeof(out)) && !owner->segments);
    p.owned_patch_reads=true;struct opengpu_tessellation_program_profile captured=p;
    tc_policy_change=&p;tc_view_change=&view;
    assert(!opengpu_tessellation_program_snapshot_append(owner,&view,&p,program_copy,tc_code,&out) && tc_code_copies==1);
    assert(!memcmp(owner->segments->staging,expected,4*(count+2)) && out.entry_offset==4);
    assert(!opengpu_private_snapshot_prepare(owner) && !opengpu_private_snapshot_publish(owner));
    assert(owner->segments->executable && !memcmp(owner->segments->backing.cpu,expected,4*(count+2)));
    assert(!opengpu_private_snapshot_retire(owner));
    /* Fresh profile copies still reject the same v2 bytes under old v1. */
    owner=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(owner));
    view=(struct drm_opengpu_native_program_view){.handle=71,.offset=64,.bytes=4*(count+2),.entry_offset=4};
    p=policy(5);out=saved;
    assert(opengpu_tessellation_program_snapshot_append(owner,&view,&p,program_copy,tc_code,&out)==-EINVAL &&
        !memcmp(&out,&saved,sizeof(out)) && !owner->segments);
    assert(captured.tes_patch_layout.output_components==60 && captured.output_components==8);
    assert(!opengpu_private_snapshot_retire(owner));
    opengpu_mmu_vm_destroy(&gpu,&ctx.vm);opengpu_mmu_fini(&gpu);
    assert(ctx.references==1 && !live_allocations && !fixture_buffers);
}
