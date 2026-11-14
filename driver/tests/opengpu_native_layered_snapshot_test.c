/* CPU-only actual GNJ2/GNF3/GND1+2/GNG/GUR2/RX/GNY owner and MMU graph.
 * Literal independent offsets check publication; no GPU completion is invented. */
#include <stddef.h>
#define OPENGPU_NATIVE_JOB_SNAPSHOT_TEST_MAIN layered_legacy_graph_regression
#include "opengpu_native_job_snapshot_test.c"
#undef OPENGPU_NATIVE_JOB_SNAPSHOT_TEST_MAIN
_Static_assert(sizeof(struct drm_opengpu_native_draw_plan)==792,"old plan ABI");
_Static_assert(sizeof(struct drm_opengpu_native_layered_draw_plan)==2056,"new plan ABI");
_Static_assert(offsetof(struct drm_opengpu_native_layered_draw_plan,draw)==0,"prefix ABI");
_Static_assert(sizeof(struct drm_opengpu_native_completion_view)==24,"completion source ABI");
_Static_assert(sizeof(struct drm_opengpu_submit_native_binned)==128,"submit ABI");
static u32 layered_code;
static void layered_loop(u32 *w)
{
    w[0]=0x00000113u; /* addi x2,x0,0 */
    for(u32 c=0;c<4;c++){
        w[1+c*2]=c<<20|(10+c)<<7|0x13u;
        w[2+c*2]=32u<<25|(10+c)<<20|2u<<15|(14+c)<<7|0x0bu;
        w[10+c]=33u<<25|(14+c)<<20|(10+c)<<15|0x0bu;
    }
    w[9]=256u<<20|20u<<7|0x13u;w[14]=35u<<25|0x0bu;
    w[15]=0xfffu<<20|20u<<15|20u<<7|0x13u;
    /* bne x20,x0,-24 */
    w[16]=0xfe0a14e3u;w[17]=36u<<25|0x0bu;w[18]=0x30500073u;
}
static void layered_original_code(u32 revision)
{
    u32 program[19];layered_loop(program);
    word(graph_original[layered_code].cpu,64,0x8a000013u+revision*0x100000u);
    for(u32 w=0;w<19;w++)word(graph_original[layered_code].cpu,68+w*4,program[w]);
}
static void layered_init(struct graph_records *g,struct drm_opengpu_native_layered_draw_plan *plans,
                         struct opengpu_device *gpu)
{
    graph_init(g,gpu);memset(plans,0,3*sizeof(*plans));
    g->job[0]=0x474e4a02;g->job[2]=2;g->job[54]=1;
    u32 *frame=g->encoded.frame.words;frame[0]=0x474e4603;frame[2]=3;frame[299]=3;frame[320]=1;frame[321]=256;
    frame[291]=92*128*4;
    for(u32 r=0;r<g->source.resource_count;r++){
        struct opengpu_native_frame_resource *v=&g->encoded.resources[r];
        if(v->role==1 || v->role==2){
            opengpu_buffer_free(gpu,&graph_original[v->gem_handle]);
            assert(!opengpu_buffer_alloc(gpu,&graph_original[v->gem_handle],1024*1024+16384));
            v->bytes=1024*1024;
            frame[(v->role==1?322:334)+2*v->index]=4096;
        }else if(v->role==6){
            opengpu_buffer_free(gpu,&graph_original[v->gem_handle]);v->bytes=GRAPH_STATES*92*128*4;
            assert(!opengpu_buffer_alloc(gpu,&graph_original[v->gem_handle],v->bytes+4096));
        }
    }
    layered_code=graph_alloc(gpu,20480);
    u32 output=graph_alloc(gpu,16384);
    for(u32 i=0;i<3;i++){
        plans[i].draw=g->plans[i];u32 draw=plans[i].draw.draw_index;
        if(!draw)continue;
        u32 *d=g->draws+draw*GRAPH_STRIDE/4;
        plans[i].draw.state_handle=1;d[29]=1;d[0]=0x474e4402;d[2]=2;d[34]=12;d[311]=6;d[508]=1;
        plans[i].geometry_program=(struct drm_opengpu_native_program_view){.handle=layered_code,.offset=64,.bytes=80,.entry_offset=4};
        plans[i].geometry_uniforms=plans[i].draw.vertex_uniforms;
        u32 *m=plans[i].geometry_metadata;
        const u32 initial[]={0x474e4701,1024,1,7,8,9,6,32,0,256,4,4,0,16,16,1};
        memcpy(m,initial,sizeof(initial));
        for(u32 c=0;c<4;c++)m[32+c]=c;
        for(u32 c=0;c<68;c++){m[96+2*c]=1;m[97+2*c]=c%4;}
    }
    g->states[128+56]=5;
    g->source.layered=true;g->source.plans=NULL;g->source.layered_plans=plans;
    g->source.completion=(struct drm_opengpu_compute_uniform_view){.handle=output,.bytes=16384};
    graph_original_contents(0);layered_original_code(0);
}
static void layered_oracle(struct opengpu_private_snapshot *owner,u32 segment,
                           const struct graph_records *g,u32 revision)
{
    u32 job;assert(!opengpu_private_snapshot_address(owner,segment,&job));
    struct opengpu_vm *vm=&owner->context->vm;
    assert(graph_read(vm,job,3)==0x474e4a02 && graph_read(vm,job+8,3)==2);
    u32 completion=graph_read(vm,job+48*4,3);
    assert(graph_read(vm,job+49*4,3)==completion+16384 && !graph_read(vm,job+50*4,3));
    assert(graph_read(vm,job+51*4,3)==completion && graph_read(vm,job+52*4,3)==completion+16384);
    assert(graph_translate(vm,completion,7)==graph_original[g->source.completion.handle].dma);
    assert(graph_translate(vm,completion+16383,7)==graph_original[g->source.completion.handle].dma+16383);
    u32 frame=graph_read(vm,job+21*4,3),draws=graph_read(vm,job+16*4,3);
    assert(graph_read(vm,frame,3)==0x474e4603 && graph_read(vm,frame+8,3)==3);
    assert(graph_read(vm,frame+320*4,3)==1 && graph_read(vm,frame+321*4,3)==256);
    for(u32 i=0;i<12;i++)assert(graph_read(vm,frame+(322+2*i)*4,3)==4096);
    u32 states=graph_read(vm,frame+292*4,3);
    for(u32 d=0;d<3;d++){
        u32 draw=draws+d*GRAPH_STRIDE;
        assert(graph_read(vm,draw,3)==(d?0x474e4402u:0x474e4401u));
        if(!d){assert(!graph_read(vm,draw+508*4,3) && !graph_read(vm,draw+509*4,3));continue;}
        u32 node=graph_read(vm,draw+509*4,3);assert(graph_read(vm,draw+510*4,3)==node+1024);
        assert(graph_read(vm,node,3)==0x474e4701 && graph_read(vm,node+6*4,3)==6);
        assert(graph_read(vm,node+7*4,3)==32 && graph_read(vm,node+9*4,3)==256);
        u32 pc=graph_read(vm,node+16*4,3),code=graph_read(vm,node+17*4,3),root=graph_read(vm,node+24*4,3);
        assert(pc==code+4 && graph_read(vm,node+18*4,3)==code+80);
        assert(graph_read(vm,code,11)==0x8a000013u+revision*0x100000u);
        u32 words[19];layered_loop(words);for(u32 w=0;w<19;w++)assert(graph_read(vm,pc+w*4,11)==words[w]);
        assert(graph_read(vm,node+23*4,3)==2 && graph_read(vm,root,3)==0x47555202);
        assert(graph_read(vm,root+6*4,3)==3 && graph_read(vm,root+7*4,3)==0xfff);
        u32 table=graph_read(vm,root+40,3);
        assert(graph_read(vm,root+44,3)==table+24*64);
        for(u32 b=0;b<13;b++){
            u32 record=b==12?graph_read(vm,root+52,3):table+b*64;
            u32 handle=graph_ubo[b==12?24:b],offset=b==12?4:(b&1?4:64),bytes=b==12?4096:16384;
            assert(graph_read(vm,record,3)==0x47554202 && graph_read(vm,record+24,3)==(b==12?31:b));
            assert(graph_read(vm,record+28,3)==8 && graph_read(vm,record+56,3)==1);
            u32 data=graph_read(vm,record+32,3);
            assert(graph_read(vm,record+36,3)==data+bytes);
            for(u32 w=0;w<bytes;w+=4)assert(graph_read(vm,data+w,3)==source_word(handle,offset+w,revision));
        }
    }
    assert(graph_read(vm,states+512+56*4,3)==5);
}
static void layered_matrix(struct graph_records *g,struct opengpu_device *gpu,
                           struct opengpu_render_context *context)
{
    u32 a=0,b=0;
    for(u32 i=0;i<g->source.resource_count;i++)if(g->encoded.resources[i].role==1){
        if(!g->encoded.resources[i].index)a=i;
        if(g->encoded.resources[i].index==1)b=i;
    }
    struct opengpu_native_frame_resource saved=g->encoded.resources[b];
    g->encoded.resources[b].gem_handle=g->encoded.resources[a].gem_handle;
    g->encoded.resources[b].offset=2051;
    struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(gpu,context);u32 segment;
    assert(!IS_ERR(owner) && !opengpu_native_job_snapshot_build(owner,&g->source,&graph_callbacks,&segment));
    assert(!opengpu_private_snapshot_prepare(owner) && !opengpu_private_snapshot_publish(owner));
    u32 job,frame;assert(!opengpu_private_snapshot_address(owner,segment,&job));frame=graph_read(&context->vm,job+21*4,3);
    u32 image=graph_read(&context->vm,frame+OPENGPU_NATIVE_BINNED_FRAME_FRAME_IMAGE_VIEWS1_BASE_WORD*4,3);
    assert(graph_translate(&context->vm,image+255*4096,7)==graph_original[g->encoded.resources[a].gem_handle].dma+2051+255*4096);
    assert(!opengpu_private_snapshot_retire(owner));graph_retired(gpu,context);
    /* One cache-line collision in the same otherwise interleaved layout. */
    g->encoded.resources[b].offset=963;graph_reject(gpu,context,&g->source,-EINVAL);
    g->encoded.resources[b]=saved;
    unsigned copies,resolves;
    owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));graph_copies=graph_resolves=0;
    assert(!opengpu_native_job_snapshot_build(owner,&g->source,&graph_callbacks,&segment));
    copies=graph_copies;resolves=graph_resolves;
    assert(!opengpu_private_snapshot_retire(owner));graph_retired(gpu,context);
    assert(copies==111 && resolves>300);
    for(unsigned fail=1;fail<=copies;fail++){graph_copy_failure=fail;graph_reject(gpu,context,&g->source,-EIO);}
    graph_copy_failure=0;
    for(unsigned fail=1;fail<=resolves;fail++){graph_resolve_failure=fail;graph_reject(gpu,context,&g->source,-EIO);}
    graph_resolve_failure=0;
    unsigned failures=0;
    for(int fail=0;;fail++){
        owner=opengpu_private_snapshot_create(gpu,context);u32 out=0xfeed1234;
        assert(!IS_ERR(owner) && fail<4096);private_alloc_fail_after=fail;
        int ret=opengpu_native_job_snapshot_build(owner,&g->source,&graph_callbacks,&out);private_alloc_fail_after=-1;
        if(ret){assert(ret==-ENOMEM && out==0xfeed1234);failures++;}
        assert(!opengpu_private_snapshot_retire(owner));graph_retired(gpu,context);
        if(!ret)break;
    }
    assert(failures>850);
    printf("layered graph failure gates: %u copies, %u resolves, %u allocations and legal interleaved physical layer views PASS\n",copies,resolves,failures);
}
static void layered_sample_inputs(struct graph_records *g,struct opengpu_device *gpu,
                                  struct opengpu_render_context *context)
{
    const u32 stride=95u*128*4;
    g->encoded.frame.words[291]=stride;
    for(u32 r=0;r<g->source.resource_count;r++){
        struct opengpu_native_frame_resource *v=&g->encoded.resources[r];
        if(v->role!=6)continue;
        opengpu_buffer_free(gpu,&graph_original[v->gem_handle]);v->bytes=GRAPH_STATES*stride;
        assert(!opengpu_buffer_alloc(gpu,&graph_original[v->gem_handle],(v->bytes+8191u)&~4095u));
    }
    g->states[56]=6;g->states[128+56]=7;
    /* Neither the raw version nor enough backing grants sample admission. */
    graph_reject(gpu,context,&g->source,-EINVAL);
    g->source.sample_inputs=true;
    g->states[56]=7;graph_reject(gpu,context,&g->source,-EINVAL);g->states[56]=6;
    struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(gpu,context);
    u32 segment,job;assert(!IS_ERR(owner));
    assert(!opengpu_native_job_snapshot_build(owner,&g->source,&graph_callbacks,&segment));
    assert(!opengpu_private_snapshot_prepare(owner) && !opengpu_private_snapshot_publish(owner));
    assert(!opengpu_private_snapshot_address(owner,segment,&job));
    u32 frame=graph_read(&context->vm,job+21*4,3),states=graph_read(&context->vm,frame+292*4,3);
    assert(graph_read(&context->vm,frame+291*4,3)==stride);
    for(u32 state=0;state<2;state++){
        assert(graph_read(&context->vm,states+state*512+56*4,3)==6+state);
        u32 pc=graph_read(&context->vm,states+state*512+54*4,3);
        assert(graph_read(&context->vm,pc,11)==OPENGPU_SHADER_CEASE);
        for(u32 engine=0;engine<4;engine++){
            const u32 base_words[]={51,60,66,72};
            u32 scratch=graph_read(&context->vm,states+state*512+base_words[engine]*4,3);
            /* Actual relocated scratch mapping contains the full 95-array prefix. */
            assert(graph_translate(&context->vm,scratch,7));
            assert(graph_translate(&context->vm,scratch+stride-1,7));
        }
    }
    assert(!opengpu_private_snapshot_retire(owner));graph_retired(gpu,context);
    g->source.sample_inputs=false;
    g->states[56]=1;g->states[128+56]=5;g->encoded.frame.words[291]=92u*128*4;
    for(u32 r=0;r<g->source.resource_count;r++)
        if(g->encoded.resources[r].role==6)g->encoded.resources[r].bytes=GRAPH_STATES*92u*128*4;
    puts("sample graph: explicit gate, ordinary ABI6/GS ABI7, full16/4MRT/128 threads and actual RX/RW mappings PASS (CPU only)");
}
int main(void)
{
    struct opengpu_device gpu={0};struct opengpu_render_context context={.gpu=&gpu,.id=7,.references=1};
    struct graph_records *g=calloc(1,sizeof(*g));
    struct drm_opengpu_native_layered_draw_plan *plans=calloc(3,sizeof(*plans));
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
    assert(g && plans && !opengpu_mmu_init(&gpu) && !opengpu_mmu_vm_create(&gpu,&context.vm));
    copy_gpu=&gpu;layered_init(g,plans,&gpu);
    struct opengpu_private_snapshot *first=opengpu_private_snapshot_create(&gpu,&context);u32 a;
    assert(!IS_ERR(first));int result=opengpu_native_job_snapshot_build(first,&g->source,&graph_callbacks,&a);
    if(result)fprintf(stderr,"layered initial build %d copies %u resolves %u\n",result,graph_copies,graph_resolves);
    assert(!result && !opengpu_private_snapshot_prepare(first) && !opengpu_private_snapshot_publish(first));
    layered_oracle(first,a,g,0);
    graph_original_contents(1);layered_original_code(1);
    struct opengpu_private_snapshot *second=opengpu_private_snapshot_create(&gpu,&context);u32 b;
    assert(!IS_ERR(second) && !opengpu_native_job_snapshot_build(second,&g->source,&graph_callbacks,&b));
    assert(!opengpu_private_snapshot_prepare(second) && !opengpu_private_snapshot_publish(second));
    layered_oracle(first,a,g,0);layered_oracle(second,b,g,1);
    assert(!opengpu_private_snapshot_retire(first));layered_oracle(second,b,g,1);
    assert(!opengpu_private_snapshot_retire(second));graph_retired(&gpu,&context);
    /* A completion line overlapping the GS code suffix (past PC+4), an
     * inactive default, original input or only the final layer is forbidden. */
    struct drm_opengpu_compute_uniform_view saved=g->source.completion;
    for(u32 test=0;test<4;test++){
        if(test==0)g->source.completion=(struct drm_opengpu_compute_uniform_view){.handle=layered_code,.offset=128,.bytes=16384};
        if(test==1)g->source.completion=(struct drm_opengpu_compute_uniform_view){.handle=graph_ubo[0],.bytes=16384};
        if(test==2)g->source.completion=(struct drm_opengpu_compute_uniform_view){.handle=graph_ubo[24],.bytes=16384};
        if(test==3)g->source.completion=(struct drm_opengpu_compute_uniform_view){.handle=g->encoded.resources[0].gem_handle,.offset=255*4096,.bytes=16384};
        /* Default originals can be smaller than the complete output. Both
         * alias and backing-bound rejection must leave the graph unpublished. */
        struct opengpu_private_snapshot *o=opengpu_private_snapshot_create(&gpu,&context);u32 out=0xfeed1234;
        assert(!IS_ERR(o) && opengpu_native_job_snapshot_build(o,&g->source,&graph_callbacks,&out)<0 && out==0xfeed1234);
        assert(!opengpu_private_snapshot_retire(o));graph_retired(&gpu,&context);
    }
    g->source.completion=saved;
    /* A different original handle for the same actual physical allocation
     * cannot evade complete-suffix exclusion. No alias allocation is freed. */
    u32 alias=++graph_handles;assert(alias<96);graph_original[alias]=graph_original[layered_code];
    g->source.completion=(struct drm_opengpu_compute_uniform_view){.handle=alias,.offset=128,.bytes=16384};
    graph_reject(&gpu,&context,&g->source,-EINVAL);g->source.completion=saved;
    memset(&graph_original[alias],0,sizeof(graph_original[alias]));--graph_handles;
    u32 saved_pitch=g->encoded.frame.words[322];g->encoded.frame.words[322]=0;graph_reject(&gpu,&context,&g->source,-EINVAL);g->encoded.frame.words[322]=saved_pitch;
    /* Ordinary and new headers cannot acquire one another's interpretation. */
    g->job[0]=0x474e4a01;graph_reject(&gpu,&context,&g->source,-EINVAL);g->job[0]=0x474e4a02;
    plans[1].geometry_metadata[0]=1;graph_reject(&gpu,&context,&g->source,-EINVAL);plans[1].geometry_metadata[0]=0;
    layered_sample_inputs(g,&gpu,&context);
    layered_matrix(g,&gpu,&context);
    for(u32 h=1;h<=graph_handles;h++)opengpu_buffer_free(&gpu,&graph_original[h]);
    assert(!opengpu_mmu_vm_destroy(&gpu,&context.vm) && !opengpu_mmu_fini(&gpu));
    assert(!live_allocations && !fixture_buffers);free(plans);free(g);
    puts("layered graph: full16/4MRT/4engine, 256 layers/12 planes, mixed GND1/GND2/GNG32inv/256vertices/1024components, actual private RX/GUR2/GNY RW/MMU and two copied owners PASS (CPU only)");
    return 0;
}
