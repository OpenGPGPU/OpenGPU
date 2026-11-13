/* Actual GNJ graph -> actual private owner/MMU. Kernel primitives alone are
 * mocked. Literal published offsets independently check the composed links.
 * CEASE programs qualify graph ownership, never rendered output or GPU work. */
#define OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN private_graph_owner_regression_main
#include "opengpu_private_snapshot_test.c"
#undef OPENGPU_PRIVATE_SNAPSHOT_TEST_MAIN
#include "../opengpu_native_frame.h"
#include "../opengpu_native_frame_snapshot.c"
#include "../opengpu_native_input_snapshot.c"
#include "../opengpu_native_program_snapshot.c"
/* Production files have private static helpers with this same spelling. */
#define identity job_graph_identity
#include "../opengpu_native_job_snapshot.c"
#undef identity
#include "../opengpu_native_graph_alias.c"

enum { GRAPH_DRAWS=3, GRAPH_STATES=2, GRAPH_STRIDE=2112,
       GRAPH_CAPACITY=128, GRAPH_FS_STRIDE=88*128*4, GRAPH_VS_BYTES=135*128*4 };
static struct opengpu_buffer graph_original[96];
static u32 graph_handles, graph_ubo[26], graph_code[2], graph_inputs[16], graph_vs[3];
static unsigned graph_copies, graph_resolves, graph_copy_failure, graph_resolve_failure;
static u32 graph_rejection_case;
static bool graph_producers_complete;
struct graph_records {
    u32 job[128], draws[GRAPH_DRAWS*GRAPH_STRIDE/4], states[GRAPH_STATES*128];
    struct drm_opengpu_native_draw_plan plans[GRAPH_DRAWS];
    struct opengpu_native_frame_request request;
    struct opengpu_native_frame_encoding encoded;
    struct opengpu_native_job_source source;
};
static unsigned char source_byte(u32 handle,u32 offset,u32 revision)
{ return (unsigned char)(handle*37u+offset*13u+(offset>>8)*19u+revision*71u); }
static u32 source_word(u32 handle,u32 offset,u32 revision)
{
    u32 result=0;
    for(u32 b=0;b<4;b++)result|=(u32)source_byte(handle,offset+b,revision)<<(8*b);
    return result;
}
static u32 graph_alloc(struct opengpu_device *gpu,size_t bytes)
{
    u32 handle=++graph_handles;assert(handle<96);
    assert(!opengpu_buffer_alloc(gpu,&graph_original[handle],(bytes+4095u)&~4095u));
    return handle;
}
static void graph_original_contents(u32 revision)
{
    for(u32 h=1;h<=graph_handles;h++)for(u32 b=0;b<graph_original[h].size;b++)
        ((u8 *)graph_original[h].cpu)[b]=source_byte(h,b,revision);
    for(u32 stage=0;stage<2;stage++){
        word(graph_original[graph_code[stage]].cpu,64,0x93000000u+revision);
        word(graph_original[graph_code[stage]].cpu,68,0x30500073u);
    }
}
static int graph_copy(void *opaque,const struct drm_opengpu_compute_uniform_view *view,void *out)
{
    assert(opaque==graph_original && graph_producers_complete);++graph_copies;
    if(graph_copy_failure==graph_copies)return -EIO;
    if(!view->handle || view->handle>graph_handles)return -ENOENT;
    const struct opengpu_buffer *b=&graph_original[view->handle];
    if(view->offset>b->size || view->bytes>b->size-view->offset)return -ERANGE;
    memcpy(out,(const u8 *)b->cpu+view->offset,view->bytes);return 0;
}
static int graph_resolve(void *opaque,u32 handle,struct opengpu_native_frame_backing *out)
{
    assert(opaque==graph_original && graph_producers_complete);++graph_resolves;
    if(graph_resolve_failure==graph_resolves)return -EIO;
    if(!handle || handle>graph_handles)return -ENOENT;
    *out=(struct opengpu_native_frame_backing){graph_original[handle].dma,graph_original[handle].size};return 0;
}
static const struct opengpu_native_job_copy graph_callbacks={graph_copy,graph_resolve,graph_original};
static void graph_init(struct graph_records *g,struct opengpu_device *gpu)
{
    static const struct opengpu_native_binned_host_caps caps={{
        0x474e4303,8,0x474e4a01,0x08000200,0xc2100800,0x08000800,0xe4908000,0x902}};
    memset(g,0,sizeof(*g));
    g->request=(struct opengpu_native_frame_request){.caps=&caps,.width=4,.height=4,.samples=4,
        .color_mask=15,.context=7,.pass=8,.generation=9,.state_count=GRAPH_STATES,
        .scratch_stride=GRAPH_FS_STRIDE,.spill_count=8};
    for(u32 a=0;a<6;a++){
        struct opengpu_native_frame_attachment *v=&g->request.attachments[a];
        v->format=a<4?26:(a==4?1:6);v->channel_mask=a<4?15:0;
        v->image=(struct opengpu_native_frame_image){.gem={graph_alloc(gpu,4096),3,1024},
            .row_pitch=a<4?256:64,.store=1};
        v->resolve=(struct opengpu_native_frame_image){.gem={graph_alloc(gpu,4096),5,1024},
            .row_pitch=64,.store=1};
    }
    g->request.primitives=(struct opengpu_native_frame_gem){graph_alloc(gpu,4096),64,256};
    g->request.directory=(struct opengpu_native_frame_gem){graph_alloc(gpu,4096),64,64};
    for(u32 p=0;p<9;p++)g->request.pool[p]=(struct opengpu_native_frame_gem){graph_alloc(gpu,4096),0,4096};
    for(u32 e=0;e<4;e++)g->request.scratch[e]=(struct opengpu_native_frame_gem){
        graph_alloc(gpu,GRAPH_STATES*GRAPH_FS_STRIDE+64),64,GRAPH_STATES*GRAPH_FS_STRIDE};
    assert(!opengpu_native_frame_encode(&g->request,&g->encoded) && g->encoded.resource_count==27);
    for(u32 stage=0;stage<2;stage++)graph_code[stage]=graph_alloc(gpu,4096);
    for(u32 b=0;b<26;b++)graph_ubo[b]=graph_alloc(gpu,b<24?16384+64:4096+4);
    for(u32 b=0;b<16;b++)graph_inputs[b]=graph_alloc(gpu,4096);
    for(u32 d=0;d<GRAPH_DRAWS;d++)graph_vs[d]=graph_alloc(gpu,GRAPH_VS_BYTES+64);
    const u32 jheader[]={0x474e4a01,512,1,16,16,16,4,4};memcpy(g->job,jheader,sizeof(jheader));
    g->job[17]=GRAPH_DRAWS;g->job[20]=GRAPH_STRIDE;g->job[24]=7;g->job[25]=9;g->job[26]=8;
    for(u32 s=0;s<GRAPH_STATES;s++){
        u32 *f=g->states+s*128;
        f[0]=0x47465301;f[1]=512;f[2]=1;f[3]=4;f[4]=7;f[5]=8;f[6]=9;f[7]=s;
        f[28]=4;f[30]=15;f[31]=4;f[36]=7;f[37]=9;f[38]=8;f[39]=2;f[55]=16;f[56]=1;
    }
    for(u32 d=0;d<GRAPH_DRAWS;d++){
        u32 *w=g->draws+d*GRAPH_STRIDE/4;
        const u32 header[]={0x474e4401,2048,1,16,16,16,4,4};memcpy(w,header,sizeof(header));
        w[29]=d==2?1:0;w[31]=1;w[35]=16;w[311]=3;w[313]=1;
        w[314]=7;w[315]=9;w[316]=8;w[327]=2;w[345]=16;w[346]=1;
        for(u32 a=0;a<16;a++){
            w[36+a*9]=a;w[37+a*9]=4;w[42+a*9]=1;w[43+a*9]=0;
            w[binding_words[a].stride]=16;
        }
        for(u32 b=2048;b<GRAPH_STRIDE;b++)((u8 *)w)[b]=(u8)(0xb0+d);
    }
    /* Intentionally permuted plans exercise state-first and draw-index joins. */
    const u32 order[]={2,0,1};
    for(u32 p=0;p<GRAPH_DRAWS;p++){
        struct drm_opengpu_native_draw_plan *plan=&g->plans[p];u32 d=order[p];
        plan->draw_index=d;plan->state_handle=d==2?1:0;
        for(u32 stage=0;stage<2;stage++){
            struct drm_opengpu_native_program_view *code=stage?&plan->fragment_program:&plan->vertex_program;
            *code=(struct drm_opengpu_native_program_view){.handle=graph_code[stage],.offset=64,.bytes=8,.entry_offset=4};
            struct drm_opengpu_native_uniform_stage *uniforms=stage?&plan->fragment_uniforms:&plan->vertex_uniforms;
            uniforms->active_mask=0xfff;
            for(u32 b=0;b<12;b++)uniforms->blocks[b]=(struct drm_opengpu_compute_uniform_view){
                .handle=graph_ubo[stage*12+b],.offset=b&1?4:64,.bytes=16384};
            uniforms->defaults=(struct drm_opengpu_compute_uniform_view){.handle=graph_ubo[24+stage],.offset=4,.bytes=4096};
        }
        for(u32 b=0;b<16;b++)plan->bindings[b]=(struct drm_opengpu_compute_uniform_view){
            .handle=graph_inputs[b],.offset=64,.bytes=512};
        plan->vertex_scratch=(struct drm_opengpu_compute_uniform_view){.handle=graph_vs[d],.offset=64,.bytes=GRAPH_VS_BYTES};
    }
    g->source=(struct opengpu_native_job_source){.job=g->job,.frame=&g->encoded.frame,
        .draws=g->draws,.states=g->states,.draws_bytes=sizeof(g->draws),.states_bytes=sizeof(g->states),
        .plans=g->plans,.plan_count=GRAPH_DRAWS,.resources=g->encoded.resources,.resource_count=27,
        .context=7,.pass=8,.generation=9,.capacity=GRAPH_CAPACITY,.engines=4,.scalar_fpu=true};
    graph_original_contents(0);graph_producers_complete=true;
}
static u32 graph_translate(const struct opengpu_vm *vm,u32 va,u32 permissions)
{
    const struct opengpu_buffer *l1=vm_live_l1(vm,va/MMU_SUPERPAGE_SIZE);assert(l1);
    u32 leaf=((u32 *)l1->cpu)[(va%MMU_SUPERPAGE_SIZE)/MMU_PAGE_SIZE];assert((leaf&15u)==permissions);
    return ((leaf>>10)<<12)+(va&4095u);
}
static u32 graph_read(const struct opengpu_vm *vm,u32 va,u32 permissions)
{
    assert(!(va&3u));u32 pa=graph_translate(vm,va,permissions);
    for(const struct fixture_buffer_record *b=fixture_buffers;b;b=b->next)if(pa>=b->dma && (u64)pa+4<=b->dma+b->size){
        const u8 *p=(const u8 *)b->cpu+pa-b->dma;
        return (u32)p[0]|(u32)p[1]<<8|(u32)p[2]<<16|(u32)p[3]<<24;
    }
    assert(!"translated address has no actual backing allocation");return 0;
}
static void graph_uniform_oracle(struct opengpu_vm *vm,u32 root,u32 stage,u32 revision)
{
    assert(graph_read(vm,root,3)==0x47555201 && graph_read(vm,root+4,3)==64);
    assert(graph_read(vm,root+12,3)==7 && graph_read(vm,root+16,3)==8 && graph_read(vm,root+20,3)==9);
    assert(graph_read(vm,root+24,3)==stage && graph_read(vm,root+28,3)==0xfff);
    u32 table=graph_read(vm,root+40,3),end=graph_read(vm,root+44,3);
    assert(end==table+24*64 && !graph_read(vm,root+48,3));
    for(u32 b=0;b<13;b++){
        u32 record=b==12?graph_read(vm,root+52,3):table+(stage*12+b)*64;
        u32 handle=graph_ubo[b==12?24+stage:stage*12+b],offset=b==12?4:(b&1?4:64),bytes=b==12?4096:16384;
        assert(graph_read(vm,record,3)==0x47554201 && graph_read(vm,record+24,3)==(b==12?31:stage*12+b));
        assert(graph_read(vm,record+28,3)==(1u<<stage) && graph_read(vm,record+56,3)==1);
        u32 base=graph_read(vm,record+32,3);assert(graph_read(vm,record+36,3)==base+bytes);
        assert(!graph_read(vm,record+40,3) && graph_read(vm,record+44,3)==base);
        for(u32 w=0;w<bytes;w+=4)assert(graph_read(vm,base+w,3)==source_word(handle,offset+w,revision));
    }
}
static void graph_oracle(struct opengpu_private_snapshot *owner,u32 segment,const struct graph_records *g,u32 revision)
{
    struct opengpu_vm *vm=&owner->context->vm;u32 job;
    assert(!opengpu_private_snapshot_address(owner,segment,&job));
    assert(graph_read(vm,job,3)==0x474e4a01 && graph_read(vm,job+17*4,3)==GRAPH_DRAWS);
    u32 draws=graph_read(vm,job+16*4,3),frame=graph_read(vm,job+21*4,3);
    for(u32 w=0;w<128;w++)if(w!=16 && w!=18 && w!=19 && w!=21 && w!=22 && w!=23)
        assert(graph_read(vm,job+w*4,3)==g->job[w]);
    assert(graph_read(vm,job+18*4,3)==draws+sizeof(g->draws) && !graph_read(vm,job+19*4,3));
    assert(graph_read(vm,job+22*4,3)==frame+2048 && !graph_read(vm,job+23*4,3));
    u32 states=graph_read(vm,frame+292*4,3);assert(graph_read(vm,frame+296*4,3)==states+1024);
    assert(graph_read(vm,frame+291*4,3)==GRAPH_FS_STRIDE);
    const u32 bindings[16][4]={{185,187,182,183},{194,196,190,192},{202,204,199,200},{210,212,207,208},
        {218,220,215,216},{226,228,223,224},{234,236,231,232},{242,244,239,240},
        {250,252,247,248},{258,260,255,256},{266,268,263,264},{274,276,271,272},
        {282,284,279,280},{290,292,287,288},{298,300,295,296},{306,308,303,304}};
    for(u32 d=0;d<GRAPH_DRAWS;d++){
        u32 draw=draws+d*GRAPH_STRIDE,pc=graph_read(vm,draw+342*4,3);
        assert(graph_read(vm,pc-4,11)==0x93000000u+revision && graph_read(vm,pc,11)==0x30500073u);
        assert(graph_read(vm,draw+506*4,3)==1);graph_uniform_oracle(vm,graph_read(vm,draw+507*4,3),0,revision);
        for(u32 b=0;b<16;b++){
            u32 base=graph_read(vm,draw+bindings[b][0]*4,3);
            assert(graph_translate(vm,base,3)==graph_original[graph_inputs[b]].dma+64);
            assert(graph_read(vm,draw+bindings[b][1]*4,3)==base+512);
            assert(!graph_read(vm,draw+(bindings[b][1]+1)*4,3));
            assert(graph_read(vm,draw+bindings[b][2]*4,3)==base && graph_read(vm,draw+bindings[b][3]*4,3)==base+512);
            assert(!graph_read(vm,draw+(bindings[b][3]+1)*4,3));
        }
        u32 scratch=graph_read(vm,draw+339*4,3);
        assert(graph_translate(vm,scratch,7)==graph_original[graph_vs[d]].dma+64);
        assert(graph_read(vm,draw+340*4,3)==scratch+GRAPH_VS_BYTES);
        for(u32 w=2048;w<GRAPH_STRIDE;w+=4)assert(graph_read(vm,draw+w,3)==(0xb0u+d)*0x01010101u);
        assert(graph_read(vm,draw+29*4,3)==(d==2?1u:0u));
        for(u32 w=0;w<512;w++){
            bool patch=w==342 || w==506 || w==507 || (w>=336 && w<=341);
            for(u32 b=0;b<16;b++)for(u32 f=0;f<4;f++)
                patch|=w==bindings[b][f] || ((f==1 || f==3) && w==bindings[b][f]+1);
            if(!patch)assert(graph_read(vm,draw+w*4,3)==g->draws[d*GRAPH_STRIDE/4+w]);
        }
    }
    const u32 scratch_fields[4][4]={{51,52,48,49},{60,61,57,58},{66,67,63,64},{72,73,69,70}};
    for(u32 s=0;s<GRAPH_STATES;s++){
        u32 state=states+s*512,pc=graph_read(vm,state+54*4,3);
        assert(graph_read(vm,pc-4,11)==0x93000000u+revision && graph_read(vm,pc,11)==0x30500073u);
        assert(graph_read(vm,state+118*4,3)==1);graph_uniform_oracle(vm,graph_read(vm,state+119*4,3),1,revision);
        for(u32 e=0;e<4;e++){
            const u32 *f=scratch_fields[e];u32 first=graph_read(vm,frame+(279+e*3)*4,3),base=graph_read(vm,state+f[0]*4,3);
            assert(base==first+s*GRAPH_FS_STRIDE && graph_read(vm,state+f[1]*4,3)==base+GRAPH_FS_STRIDE);
            assert(graph_read(vm,state+f[2]*4,3)==base && graph_read(vm,state+f[3]*4,3)==base+GRAPH_FS_STRIDE);
            assert(graph_translate(vm,base,7)==graph_original[g->request.scratch[e].handle].dma+64+s*GRAPH_FS_STRIDE);
        }
        for(u32 w=0;w<128;w++){
            bool patch=w==54 || w==118 || w==119;
            for(u32 e=0;e<4;e++){const u32 *f=scratch_fields[e];
                patch|=w==f[0] || w==f[1] || w==f[1]+1 || w==f[2] || w==f[3] || w==f[3]+1;}
            if(!patch)assert(graph_read(vm,state+w*4,3)==g->states[s*128+w]);
        }
    }
    assert(graph_translate(vm,graph_read(vm,frame+210*4,3),7)==graph_original[g->request.attachments[0].image.gem.handle].dma+3);
}
static void graph_retired(struct opengpu_device *gpu,struct opengpu_render_context *context)
{ assert(context->references==1 && !gpu->mmu.snapshot_leases && !context->vm.reservations); }
static void graph_reject(struct opengpu_device *gpu,struct opengpu_render_context *context,
                         const struct opengpu_native_job_source *source,int expected)
{
    u32 output=0xfeed1234;struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(gpu,context);
    assert(!IS_ERR(owner));graph_copies=graph_resolves=0;
    int actual=opengpu_native_job_snapshot_build(owner,source,&graph_callbacks,&output);
    if(actual!=expected)fprintf(stderr,"graph rejection case %u, copy failure %u, resolve failure %u: expected %d, got %d\n",
        graph_rejection_case,graph_copy_failure,graph_resolve_failure,expected,actual);
    assert(actual==expected);
    assert(output==0xfeed1234 && !gpu->mmu.snapshot_leases && !context->vm.reservations);
    assert(!opengpu_private_snapshot_retire(owner));graph_retired(gpu,context);
}
static void graph_alias_expect(const struct opengpu_native_job_source *s,int expected)
{
    graph_resolves=0;
    assert(opengpu_native_graph_alias_check(s,graph_resolve,graph_original)==expected);
}
static void graph_alias_matrix(struct graph_records *g,struct opengpu_device *gpu,
                               struct opengpu_render_context *context)
{
    graph_alias_expect(&g->source,0);assert(graph_resolves==78);
    struct drm_opengpu_compute_uniform_view saved=g->plans[1].bindings[15];
    /* Different handles may name the same actual original allocation. Do not
     * invent a second PA table or weaken comparison to handle equality. */
    u32 alias=++graph_handles;assert(alias<96);
    graph_original[alias]=graph_original[g->plans[0].vertex_scratch.handle];
    g->plans[1].bindings[15]=(struct drm_opengpu_compute_uniform_view){.handle=alias,.offset=64,.bytes=512};
    graph_alias_expect(&g->source,-EINVAL);
    /* Exclusive endpoints and real 64-byte read/write ownership, including a
     * one-byte read immediately before a writable line and a crossing read. */
    g->plans[1].bindings[15].offset=63;g->plans[1].bindings[15].bytes=1;graph_alias_expect(&g->source,0);
    g->plans[1].bindings[15].bytes=2;graph_alias_expect(&g->source,-EINVAL);
    memset(&graph_original[alias],0,sizeof(graph_original[alias]));graph_handles--;
    g->plans[1].bindings[15]=saved;
    /* Keep legal RO sharing and sequential VS scratch reuse across draws. */
    g->plans[1].bindings[15]=g->plans[0].bindings[0];graph_alias_expect(&g->source,0);
    g->plans[1].bindings[15]=saved;
    struct drm_opengpu_compute_uniform_view scratch=g->plans[1].vertex_scratch;
    g->plans[1].vertex_scratch=g->plans[0].vertex_scratch;graph_alias_expect(&g->source,0);
    g->plans[1].vertex_scratch=scratch;
    /* Inputs, index control and indirect control all have the same protection
     * from every other draw's writable VS scratch. */
    struct drm_opengpu_compute_uniform_view *controls[]={&g->plans[1].indices,&g->plans[1].indirect};
    for(u32 c=0;c<2;c++){
        *controls[c]=(struct drm_opengpu_compute_uniform_view){.handle=graph_vs[2],.offset=64,.bytes=64};
        graph_alias_expect(&g->source,-EINVAL);memset(controls[c],0,sizeof(*controls[c]));
    }
    for(u32 r=0;r<g->source.resource_count;r++){
        struct opengpu_native_frame_resource *resource=&g->encoded.resources[r],old=*resource;
        if(old.role>=3 && old.role<=5){
            resource->gem_handle=graph_inputs[0];resource->offset=64;resource->bytes=64;
            graph_alias_expect(&g->source,-EINVAL);
            resource->gem_handle=graph_vs[2];graph_alias_expect(&g->source,-EINVAL);
        }else if(old.role==1 || old.role==2){
            resource->gem_handle=graph_vs[2];resource->offset=64;resource->bytes=64;
            graph_alias_expect(&g->source,-EINVAL);
            resource->gem_handle=graph_inputs[0];graph_alias_expect(&g->source,0);
        }else{
            assert(old.role==6);resource->gem_handle=graph_vs[2];resource->offset=64;resource->bytes=64;
            graph_alias_expect(&g->source,0);
            resource->gem_handle=graph_inputs[0];graph_alias_expect(&g->source,0);
        }
        *resource=old;
    }
    /* Exercise legal phase reuse through the entire actual graph/MMU, keeping
     * full capacity and frame sizes, rather than accepting only a helper call. */
    for(u32 reuse=0;reuse<3;reuse++){
        struct drm_opengpu_compute_uniform_view *view=reuse==0?&g->plans[1].vertex_scratch:&g->plans[1].bindings[15],old=*view;
        view->handle=reuse==1?g->request.attachments[0].image.gem.handle:g->request.scratch[0].handle;
        graph_alias_expect(&g->source,0);
        u32 segment;struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(gpu,context);
        assert(!IS_ERR(owner));assert(!opengpu_native_job_snapshot_build(owner,&g->source,&graph_callbacks,&segment));
        assert(!opengpu_private_snapshot_prepare(owner) && !opengpu_private_snapshot_publish(owner));
        assert(!opengpu_private_snapshot_retire(owner));graph_retired(gpu,context);*view=old;
    }
    /* The sort/sweep must retain an older long interval after nested ranges,
     * and must not impose a small artificial maximum number of draw plans. */
    enum { MANY=4097 };
    struct drm_opengpu_native_draw_plan *plans=calloc(MANY,sizeof(*plans));assert(plans);
    struct opengpu_native_job_source large={.plans=plans,.plan_count=MANY};
    for(u32 i=0;i<MANY;i++)plans[i].bindings[0]=(struct drm_opengpu_compute_uniform_view){
        .handle=graph_inputs[i%16],.offset=(i%32)*4,.bytes=4};
    graph_alias_expect(&large,0);assert(graph_resolves==MANY);
    plans[0].bindings[0]=(struct drm_opengpu_compute_uniform_view){.handle=graph_vs[0],.offset=0,.bytes=73728};
    plans[MANY-1].vertex_scratch=(struct drm_opengpu_compute_uniform_view){.handle=graph_vs[0],.offset=70000,.bytes=64};
    graph_alias_expect(&large,-EINVAL);free(plans);
    private_alloc_fail_after=0;graph_alias_expect(&g->source,-ENOMEM);private_alloc_fail_after=-1;
    graph_resolve_failure=17;graph_alias_expect(&g->source,-EIO);graph_resolve_failure=0;
    g->plans[1].bindings[15].offset=~(u64)0;graph_alias_expect(&g->source,-EINVAL);g->plans[1].bindings[15]=saved;
    g->plans[1].bindings[15].handle=0;graph_alias_expect(&g->source,-EINVAL);g->plans[1].bindings[15]=saved;
    g->plans[1].bindings[15].offset=4096;graph_alias_expect(&g->source,-EINVAL);g->plans[1].bindings[15]=saved;
    assert(opengpu_native_graph_alias_check(NULL,graph_resolve,graph_original)==-EINVAL);
    assert(opengpu_native_graph_alias_check(&g->source,NULL,graph_original)==-EINVAL);
}
int main(void)
{
    struct opengpu_device gpu={0};struct opengpu_render_context context={.gpu=&gpu,.references=1,.id=7};
    struct graph_records g;gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
    assert(!opengpu_mmu_init(&gpu));assert(!opengpu_mmu_vm_create(&gpu,&context.vm));copy_gpu=&gpu;
    graph_init(&g,&gpu);
    graph_alias_matrix(&g,&gpu,&context);
    for(u32 kind=0;kind<35;kind++){
        struct opengpu_native_job_source s=g.source;u32 *changed=NULL,saved=0;
        switch(kind){
        case 0:s.capacity=256;break;case 1:s.engines=0;break;case 2:s.plan_count--;break;
        case 3:s.states_bytes--;break;case 4:s.draws_bytes=2*GRAPH_STRIDE+2047;break;
        case 5:s.context++;break;case 6:s.pass++;break;case 7:s.generation++;break;
        case 8:changed=&g.job[3];break;case 9:changed=&g.job[16];break;
        case 10:changed=&g.job[127];break;case 11:changed=&g.draws[31];break;
        case 12:changed=&g.draws[342];break;case 13:changed=&g.draws[507];break;
        case 14:changed=&g.draws[345];break;case 15:changed=&g.states[7];break;
        case 16:changed=&g.states[36];break;case 17:changed=&g.states[54];break;
        case 18:changed=&g.states[119];break;case 19:changed=&g.states[28];break;
        case 20:changed=&g.states[128+72];break;case 21:changed=&g.plans[2].draw_index;break;
        case 22:changed=&g.plans[2].state_handle;break;
        case 23:changed=&g.plans[2].fragment_program.entry_offset;break;
        case 24:changed=&g.plans[2].fragment_uniforms.blocks[11].handle;break;
        case 25:changed=&g.plans[0].vertex_program.pad;break;
        case 26:changed=&g.plans[0].vertex_uniforms.pad;break;
        case 27:changed=&g.plans[0].bindings[15].bytes;break;
        case 28:changed=&g.plans[0].vertex_scratch.bytes;break;
        case 29:changed=&g.draws[191];break;
        case 30:changed=&g.draws[316];break;
        case 31:changed=&g.states[126];break;
        case 32:changed=&g.states[13];break;
        case 33:s.resource_count--;break;
        default:changed=&g.encoded.frame.words[0];break;
        }
        graph_rejection_case=kind;
        if(changed){saved=*changed;*changed=kind==27?0:saved+1;}
        graph_reject(&gpu,&context,&s,-EINVAL);
        if(changed)*changed=saved;
    }
    /* Each real producer-copy/lookup failure must leave the output untouched
     * and the entire unpublished graph safely discardable. */
    u32 successful_copies,successful_resolves;
    {struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(&gpu,&context);u32 segment;
     assert(!IS_ERR(owner));graph_copies=graph_resolves=0;
     assert(!opengpu_native_job_snapshot_build(owner,&g.source,&graph_callbacks,&segment));
     successful_copies=graph_copies;successful_resolves=graph_resolves;
     /* Three complete 26-source uniform copies, three VS, two shared FS. */
     assert(successful_copies==83 && successful_resolves==156);
     assert(!opengpu_private_snapshot_retire(owner));graph_retired(&gpu,&context);}
    for(u32 fail=1;fail<=successful_copies;fail++){graph_copy_failure=fail;graph_reject(&gpu,&context,&g.source,-EIO);}
    graph_copy_failure=0;
    for(u32 fail=1;fail<=successful_resolves;fail++){graph_resolve_failure=fail;graph_reject(&gpu,&context,&g.source,-EIO);}
    graph_resolve_failure=0;
    unsigned failures=0;
    for(int fail=0;;fail++){
        struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(&gpu,&context);u32 output=0xfeed1234;
        assert(fail<2048 && !IS_ERR(owner));private_alloc_fail_after=fail;
        int ret=opengpu_native_job_snapshot_build(owner,&g.source,&graph_callbacks,&output);
        private_alloc_fail_after=-1;
        if(ret){assert(ret==-ENOMEM && output==0xfeed1234);failures++;}
        assert(!opengpu_private_snapshot_retire(owner));graph_retired(&gpu,&context);
        if(!ret)break;
    }
    assert(failures>500);
    /* An empty job owns the actual frame/state mapping without inventing a
     * program, uniform root or draw-array pointer. */
    {struct opengpu_native_job_source empty=g.source;u32 saved=g.job[17],segment,address;
     g.job[17]=0;empty.plan_count=0;empty.draws_bytes=0;empty.plans=NULL;empty.draws=NULL;
     struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(&gpu,&context);
     assert(!IS_ERR(owner));graph_copies=graph_resolves=0;
     assert(!opengpu_native_job_snapshot_build(owner,&empty,&graph_callbacks,&segment));
     assert(!graph_copies && graph_resolves==54);
     assert(!opengpu_private_snapshot_prepare(owner) && !opengpu_private_snapshot_publish(owner));
     assert(!opengpu_private_snapshot_address(owner,segment,&address));
     assert(!graph_read(&context.vm,address+16*4,3) && !graph_read(&context.vm,address+18*4,3) &&
         !graph_read(&context.vm,address+19*4,3));
     assert(!opengpu_private_snapshot_retire(owner));graph_retired(&gpu,&context);g.job[17]=saved;}
    struct opengpu_private_snapshot *old=opengpu_private_snapshot_create(&gpu,&context),*fresh;
    u32 old_segment,fresh_segment;assert(!IS_ERR(old));
    assert(!opengpu_native_job_snapshot_build(old,&g.source,&graph_callbacks,&old_segment));
    /* Overwrite all caller metadata before prepare: only copied staging may
     * be published. Restore the independent expected bytes after publication. */
    struct graph_records expected=g;
    memset(g.job,0xa7,sizeof(g.job));memset(g.draws,0xb8,sizeof(g.draws));
    memset(g.states,0xc9,sizeof(g.states));memset(&g.encoded.frame,0xda,sizeof(g.encoded.frame));
    assert(!opengpu_private_snapshot_prepare(old));assert(!opengpu_private_snapshot_publish(old));
    g=expected;
    graph_oracle(old,old_segment,&g,0);
    graph_original_contents(1);fresh=opengpu_private_snapshot_create(&gpu,&context);assert(!IS_ERR(fresh));
    assert(!opengpu_native_job_snapshot_build(fresh,&g.source,&graph_callbacks,&fresh_segment));
    assert(!opengpu_private_snapshot_prepare(fresh));assert(!opengpu_private_snapshot_publish(fresh));
    graph_oracle(old,old_segment,&g,0);graph_oracle(fresh,fresh_segment,&g,1);
    assert(context.references==3);
    assert(!opengpu_private_snapshot_retire(old));graph_oracle(fresh,fresh_segment,&g,1);
    assert(!opengpu_private_snapshot_retire(fresh));graph_retired(&gpu,&context);
    for(u32 h=1;h<=graph_handles;h++)opengpu_buffer_free(&gpu,&graph_original[h]);
    opengpu_mmu_vm_destroy(&gpu,&context.vm);opengpu_mmu_fini(&gpu);
    assert(!live_allocations && !fixture_buffers);
    printf("native graph: actual GNJ/GND/GFS/program/GUR/frame/input owner/MMU, full16/24x16KiB/4MRT/4engine,3draw sharedstate, %u allocation failures and simultaneous copied owners PASS (CPU only)\n",failures);
    return 0;
}
