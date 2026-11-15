/* Internal program-v3 language authorization. No shader VM or HW probe. */
static struct opengpu_tessellation_program_profile info_policy(u32 stage)
{
    struct opengpu_tessellation_program_profile p=stage==4?policy(4):dynamic_policy();
    p.version=3;p.owned_tessellation_info=true;return p;
}
static void info_program_tests(void)
{
    struct opengpu_tessellation_program_profile c=info_policy(4),e=info_policy(5),bad;
    u32 control[]={ti(10,0,6),tg(37,11,10,0),ti(12,11,0),OPENGPU_SHADER_CEASE};
    u32 evaluation[]={ti(10,0,6),tg(37,12,10,0),tg(33,0,0,12),tg(35,0,0,0),tg(36,0,0,0),OPENGPU_SHADER_CEASE};
    VALID(control,c);VALID(evaluation,e);
    for(u32 stage=4;stage<=5;stage++){
        bad=info_policy(stage);bad.owned_tessellation_info=false;
        assert(!opengpu_tessellation_program_profile_valid(&bad));
        for(u32 version=1;version<=2;version++){
            bad=version==2?dynamic_policy():policy(stage);
            bad.stage=stage;bad.owned_tessellation_info=true;
            assert(!opengpu_tessellation_program_profile_valid(&bad));
        }
        bad=info_policy(stage);bad.version=4;assert(!opengpu_tessellation_program_profile_valid(&bad));
    }
    /* V3 TCS retains exactly v1 patch capacities and zero TES-only fields. */
    for(u32 field=0;field<7;field++){
        bad=c;
        switch(field){
        case 0:bad.owned_patch_reads=true;break;
        case 1:bad.tes_patch_layout.input_vertices=1;break;
        case 2:bad.tes_patch_layout.input_components=1;break;
        case 3:bad.tes_patch_layout.output_vertices=1;break;
        case 4:bad.tes_patch_layout.output_components=1;break;
        case 5:bad.tes_patch_layout.patch_components=1;break;
        case 6:bad.patch_words=1;break;
        }
        assert(!opengpu_tessellation_program_profile_valid(&bad));
    }
    bad=c;bad.output_components=64;bad.patch_components=0;VALID(control,bad);
    bad.patch_components=1;INVALID(control,bad);
    bad=e;bad.owned_patch_reads=false;INVALID(evaluation,bad);
    bad=e;bad.patch_words=1;INVALID(evaluation,bad);
    bad=e;bad.tes_patch_layout.output_components=61;INVALID(evaluation,bad);
    /* V1/V2 do not gain the new selector, while their TES selectors0..5 stay. */
    bad=policy(4);INVALID(control,bad);
    bad=policy(5);INVALID(evaluation,bad);bad=dynamic_policy();INVALID(evaluation,bad);
    for(u32 selector=0;selector<6;selector++){
        control[0]=ti(10,0,(int)selector);INVALID(control,c);
        evaluation[0]=ti(10,0,(int)selector);
        bad=policy(5);VALID(evaluation,bad);bad=dynamic_policy();VALID(evaluation,bad);VALID(evaluation,e);
    }
    control[0]=ti(10,0,6);evaluation[0]=ti(10,0,6);
    control[0]=ti(10,0,7);INVALID(control,c);control[0]=ti(10,0,6);
    evaluation[0]=ti(10,0,7);INVALID(evaluation,e);evaluation[0]=ti(10,0,6);
    control[1]=tg(37,11,10,1);INVALID(control,c);control[1]=tg(37,11,10,0)|(1u<<12);INVALID(control,c);
    control[1]=tg(37,11,31,0);INVALID(control,c);control[1]=tg(37,11,1,0);INVALID(control,c);control[1]=tg(37,11,10,0);
    evaluation[1]=tg(37,12,10,1);INVALID(evaluation,e);evaluation[1]=tg(37,12,10,0)|(1u<<12);INVALID(evaluation,e);
    evaluation[1]=tg(37,12,31,0);INVALID(evaluation,e);evaluation[1]=tg(37,12,10,0);
    for(u32 fn=32;fn<=38;fn++)if(fn!=37){
        control[1]=tg(fn,11,10,0);INVALID(control,c);
        if(fn==34 || fn==38){evaluation[1]=tg(fn,12,10,0);INVALID(evaluation,e);}
    }
    control[1]=tg(37,11,10,0);evaluation[1]=tg(37,12,10,0);
    u32 reused_result[]={ti(10,0,6),tg(37,10,10,0),tg(37,11,10,0),OPENGPU_SHADER_CEASE};
    INVALID(reused_result,c); /* Language data cannot prove a selector constant. */
    u32 simt[]={0x0104f057,ti(10,0,6),tb(0x5b,3,0,0,40),tb(0x5b,0,1,1,20),
        ti(10,0,6),tg(37,10,10,0),tv(23,4,2,10,0,true),OPENGPU_SHADER_SIMT_JOIN,
        ti(10,0,6),tg(37,10,10,0),tv(23,4,2,10,0,true),OPENGPU_SHADER_SIMT_JOIN,OPENGPU_SHADER_SIMT_JOIN,
        ti(10,0,6),tg(37,10,10,0),OPENGPU_SHADER_CEASE};
    VALID(simt,c);INVALID(simt,e);
    simt[4]=0x13;INVALID(simt,c);simt[4]=ti(10,0,6);
    simt[8]=0x13;INVALID(simt,c);simt[8]=ti(10,0,6);
    for(u32 rd=1;rd<32;rd++)if(rd!=10){simt[5]=tg(37,rd,10,0);INVALID(simt,c);}
    simt[5]=tg(37,10,10,0);
    /* A safe immutable entry constant is not the chosen arm-local selector ABI. */
    simt[1]=ti(11,0,6);simt[5]=tg(37,10,11,0);INVALID(simt,c);
    simt[1]=ti(10,0,6);simt[5]=tg(37,10,10,0);
    simt[5]=tg(37,0,10,0);simt[9]=tg(37,0,10,0);simt[13]=0x13;INVALID(simt,c);
    simt[13]=ti(10,0,6);VALID(simt,c); /* rd0 does not establish persistent authority. */
    /* Dynamic TES keeps coords-only fn32 and the full owned patch dialect. */
    u32 words[64],n=dynamic_program(words,31,473,false,false,true,false,false,4);
    assert(opengpu_tessellation_shader_validate_words(words,n,&e));
    u32 coords[]={ti(10,0,0),ti(11,0,3),tg(32,12,10,11),tg(33,0,0,12),tg(35,0,0,0),tg(36,0,0,0),OPENGPU_SHADER_CEASE};
    INVALID(coords,e);coords[1]=ti(11,0,2);VALID(coords,e);
}
static struct opengpu_tessellation_program_profile *info_profile_change;
static int info_program_copy(void *opaque,const struct drm_opengpu_compute_uniform_view *view,void *out)
{
    int ret=program_copy(opaque,view,out);
    if(!ret && info_profile_change){
        info_profile_change->owned_tessellation_info=false;
        info_profile_change->version=1;
        info_profile_change=NULL;
    }
    return ret;
}
static void info_snapshot_tests(void)
{
    struct opengpu_device gpu={0};struct opengpu_render_context ctx={.gpu=&gpu,.references=1,.id=7};
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
    assert(!opengpu_mmu_init(&gpu) && !opengpu_mmu_vm_create(&gpu,&ctx.vm));copy_gpu=&gpu;
    for(u32 stage=4;stage<=5;stage++){
        struct opengpu_tessellation_program_profile p=info_policy(stage);
        u32 words[]={ti(10,0,6),tg(37,12,10,0),tg(33,0,0,12),tg(35,0,0,0),tg(36,0,0,0),OPENGPU_SHADER_CEASE};
        u32 count=6;
        if(stage==4){words[2]=OPENGPU_SHADER_CEASE;count=3;}
        code_init();word(tc_code,64,0xffffffff);
        for(u32 i=0;i<count;i++)word(tc_code,68+4*i,words[i]);
        word(tc_code,68+4*count,0xffffffff);
        struct drm_opengpu_native_program_view view={.handle=71,.offset=64,.bytes=4*(count+2),.entry_offset=4};
        u8 expected[32];memcpy(expected,tc_code+64,view.bytes);
        struct opengpu_native_program_snapshot out={0x55,0x66},saved=out;
        struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(owner));
        p.owned_tessellation_info=false;
        assert(opengpu_tessellation_program_snapshot_append(owner,&view,&p,info_program_copy,tc_code,&out)==-EINVAL &&
            !tc_code_copies && !memcmp(&out,&saved,sizeof(out)) && !owner->segments);
        /* The fixture follows the submitter contract even for pre-copy denial:
         * retire the whole failed owner, then construct a fresh healthy owner. */
        assert(!opengpu_private_snapshot_retire(owner) && ctx.references==1);
        owner=opengpu_private_snapshot_create(&gpu,&ctx);assert(!IS_ERR(owner));
        p.owned_tessellation_info=true;
        info_profile_change=&p;tc_view_change=&view;
        assert(!opengpu_tessellation_program_snapshot_append(owner,&view,&p,info_program_copy,tc_code,&out));
        assert(tc_code_copies==1 && out.entry_offset==4);
        assert(!memcmp(owner->segments->staging,expected,4*(count+2)));
        memset(tc_code,0x91,sizeof(tc_code));tc_code_retained=false;
        assert(!opengpu_private_snapshot_prepare(owner) && !opengpu_private_snapshot_publish(owner));
        struct snapshot_segment *segment=owner->segments;
        assert(segment->executable && !memcmp(segment->backing.cpu,expected,4*(count+2)));
        const struct opengpu_buffer *l1=vm_live_l1(&ctx.vm,segment->va/MMU_SUPERPAGE_SIZE);assert(l1);
        assert((((u32 *)l1->cpu)[(segment->va%MMU_SUPERPAGE_SIZE)/MMU_PAGE_SIZE]&15)==(MMU_PTE_V|MMU_PTE_R|MMU_PTE_X));
        assert(!opengpu_private_snapshot_retire(owner));
    }
    opengpu_mmu_vm_destroy(&gpu,&ctx.vm);opengpu_mmu_fini(&gpu);
    assert(ctx.references==1 && !live_allocations && !fixture_buffers);
}
