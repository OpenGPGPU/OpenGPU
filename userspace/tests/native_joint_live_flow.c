/* SPDX-License-Identifier: MIT */
/* Original ESSL linked by Mesa, with Gallium creating every original GEM and
 * the production joint source builder issuing the protected ioctl. */
#define OPENGPU_CS_NATIVE_LAYOUT
/* All original buffers from the three recovery sequences remain leased until
 * observed retirement and cleanup. */
#define OPENGPU_CS_CALLBACK_BO_SLOTS 2048u
#include "native_graphics_v2_resources.c"
#include "opengpu_native_joint_build_source.h"
#include "opengpu_native_joint_internal.h"
#include "../../driver/opengpu_tessellation_uniform_abi.h"
#include "../../scripts/native_joint_live_bridge.h"

static struct native_kernel_profile *joint_profile;
static struct native_kernel_session *joint_session;

static int joint_parameter(unsigned param,uint64_t *value)
{
    return native_kernel_profile_query(joint_profile,param,value);
}
static int joint_ioctl(unsigned long request,void *argument)
{
    assert(request==DRM_IOCTL_OPENGPU_SUBMIT_TESSELLATION_JOINT);
    int error=native_kernel_submit_joint(joint_session,argument,NULL,NULL);
    if (!error) return 0;
    errno=-error;
    return -1;
}
static void *joint_allocate(unsigned handle,uint64_t bytes)
{
    return native_kernel_original_allocate(joint_session,handle,bytes);
}
static int joint_close(unsigned handle)
{
    return native_kernel_original_close(joint_session,handle);
}
static int joint_references(unsigned handle)
{
    return native_kernel_original_refcount(joint_session,handle);
}
static struct opengpu_native_joint_view joint_view(struct pipe_screen *screen,unsigned bytes,unsigned offset,
    struct pipe_resource **resources,unsigned *count,void **mapped)
{
    assert(*count<12);
    struct pipe_resource *resource=buffer(screen,bytes+offset);
    assert(resource);
    resources[(*count)++]=resource;
    unsigned char *base=pipe_opengpu_resource_map(opengpu_resource(resource)->gpu);
    assert(base);
    memset(base,0xa5,bytes+offset);
    if (mapped) *mapped=base+offset;
    return (struct opengpu_native_joint_view){resource,offset,bytes};
}
static void joint_source_path(char path[4096],const char *directory,const char *name)
{
    int length=snprintf(path,4096,"%s/%s",directory,name);
    assert(length>0 && length<4096);
}
enum joint_mode {
    JOINT_SMALL, JOINT_VGA_QUAD, JOINT_PATCH_ENDPOINTS, JOINT_FULL_PATCH_ENDPOINTS,
    JOINT_FULL_PATCH_MUTATION, JOINT_TESS_UNIFORMS, JOINT_TESS_UNIFORM_RECOVERY
};

enum { JOINT_API_BINDINGS=72, JOINT_UBO_STRIDE=OPENGPU_TESSELLATION_UNIFORM_BLOCK_BYTES+64 };

static struct pipe_resource *joint_tess_uniform_buffer(struct pipe_screen *screen,unsigned record)
{
    const unsigned words_per_block=OPENGPU_TESSELLATION_UNIFORM_BLOCK_BYTES/4;
    struct pipe_resource *resource=buffer(screen,JOINT_API_BINDINGS*JOINT_UBO_STRIDE);
    assert(resource);
    unsigned char *base=pipe_opengpu_resource_map(opengpu_resource(resource)->gpu);
    assert(base);
    for (unsigned binding=0;binding<JOINT_API_BINDINGS;++binding) {
        uint32_t *words=(void *)(base+4+binding*JOINT_UBO_STRIDE);
        for (unsigned i=0;i<words_per_block;++i)
            words[i]=0x12000000u+0x10000u*binding+(i+record*words_per_block)*(13+binding);
    }
    return resource;
}

static void joint_bind_tess_uniforms(struct opengpu_native_joint_link *link,struct pipe_context *sink,
    struct pipe_resource *resource,unsigned record,bool rebind)
{
    struct pipe_resource *views[JOINT_API_BINDINGS]={0};
    unsigned offsets[JOINT_API_BINDINGS]={0},sizes[JOINT_API_BINDINGS]={0};
    for (unsigned stage=OPENGPU_TESSELLATION_UNIFORM_CONTROL_STAGE;
            stage<=OPENGPU_TESSELLATION_UNIFORM_EVALUATION_STAGE;++stage) {
        assert(opengpu_native_joint_blocks(link,stage)==OPENGPU_TESSELLATION_UNIFORM_BLOCKS_PER_STAGE);
        for (unsigned block=0;block<OPENGPU_TESSELLATION_UNIFORM_BLOCKS_PER_STAGE;++block) {
            unsigned binding=opengpu_native_joint_binding(link,stage,block);
            assert(binding<JOINT_API_BINDINGS && !views[binding]);
            /* Odd records share one exact original window between the stages. */
            unsigned window=(record&1) && binding==1?71:binding;
            views[binding]=resource;
            offsets[binding]=4+(rebind?0:window)*JOINT_UBO_STRIDE;
            sizes[binding]=OPENGPU_TESSELLATION_UNIFORM_BLOCK_BYTES;
        }
        uint32_t defaults[OPENGPU_TESSELLATION_UNIFORM_DEFAULT_COMPONENTS];
        assert(opengpu_native_joint_defaults(link,stage)==OPENGPU_TESSELLATION_UNIFORM_DEFAULT_COMPONENTS);
        for (unsigned i=0;i<OPENGPU_TESSELLATION_UNIFORM_DEFAULT_COMPONENTS;++i)
            defaults[i]=rebind?0xa5a5a5a5u:(0x64000000u|(stage<<20)|(record<<12)|i);
        assert(!opengpu_native_joint_bind_defaults(link,sink,stage,defaults,
            OPENGPU_TESSELLATION_UNIFORM_DEFAULT_COMPONENTS));
    }
    assert(!opengpu_native_joint_bind_ubos(link,sink,views,offsets,sizes));
}

static void joint_case(struct pipe_context *pipe,const char *sources,unsigned index,unsigned engines,
    enum joint_mode mode,FILE *layout)
{
    static const char *evaluation[]={"isoline.tese","triangle.tese","quad.tese"};
    static const char *patch_evaluation[]={"patch-isoline.tese","patch-triangle.tese","patch-quad.tese"};
    static const char *uniform_evaluation[]={"uniform-isoline.tese","uniform-triangle.tese","uniform-quad.tese"};
    static const uint32_t seed[4]={7,19,43,61};
    const bool vga_quad=mode==JOINT_VGA_QUAD;
    const bool source_mutation=mode==JOINT_FULL_PATCH_MUTATION;
    const bool full_patch=mode==JOINT_FULL_PATCH_ENDPOINTS || source_mutation;
    const bool patch_endpoints=mode==JOINT_PATCH_ENDPOINTS || full_patch;
    const bool tess_uniforms=mode==JOINT_TESS_UNIFORMS || mode==JOINT_TESS_UNIFORM_RECOVERY;
    unsigned domain=index<JOINT_LIVE_BASE_RECORDS?index/2:1;
    if (mode==JOINT_TESS_UNIFORM_RECOVERY) domain=index/JOINT_LIVE_RECOVERY_PER_DOMAIN;
    if (vga_quad || full_patch) domain=2;
    bool mutation=!patch_endpoints && !tess_uniforms && index<JOINT_LIVE_BASE_RECORDS && (index&1);
    assert(domain<3 && engines>=1 && engines<=4);
    const char *control=full_patch?"patch-full.tesc":patch_endpoints?"patch-capacity.tesc":"shared.tesc";
    const char *tes=full_patch?(source_mutation && index&1?"patch-full-quad-mutation.tese":"patch-full-quad.tese"):
        patch_endpoints?patch_evaluation[domain]:evaluation[domain];
    if (tess_uniforms) {
        control="uniform-control.tesc";
        tes=uniform_evaluation[domain];
    }
    const char *names[]={mutation?"mutation.vert":"original.vert",control,tes,"shared.frag"};
    char paths[4][4096];
    const char *files[4];
    for (unsigned i=0;i<4;++i) {
        joint_source_path(paths[i],sources,names[i]);
        files[i]=paths[i];
    }
    fprintf(stderr,"joint linked submission %u (%s, %s)\n",index,names[2],names[0]);
    struct opengpu_native_joint_link *link=opengpu_native_joint_link_create(files);
    assert(link);
    struct opengpu_native_joint_program *program=opengpu_native_joint_program_create(pipe,link,2);
    assert(program);
    struct opengpu_native_joint_constants *constants=opengpu_native_joint_constants_create(pipe);
    assert(constants);
    struct pipe_context *sink=opengpu_native_joint_constants_pipe(constants);
    assert(opengpu_native_joint_defaults(link,PIPE_SHADER_VERTEX)==4);
    assert(!opengpu_native_joint_bind_defaults(link,sink,PIPE_SHADER_VERTEX,seed,4));
    struct pipe_resource *tess_ubos=NULL;
    if (tess_uniforms) {
        tess_ubos=joint_tess_uniform_buffer(pipe->screen,index);
        joint_bind_tess_uniforms(link,sink,tess_ubos,index,false);
    }
    if (patch_endpoints) {
        const uint32_t indices[2][4]={{0,0,0,0},{31,3,full_patch?2u:29u,3}};
        const unsigned stage=OPENGPU_TESSELLATION_UNIFORM_EVALUATION_STAGE;
        assert(opengpu_native_joint_defaults(link,stage)==4);
        assert(!opengpu_native_joint_bind_defaults(link,sink,stage,indices[source_mutation || index&1],4));
    }
    struct opengpu_native_joint_source *source=opengpu_native_joint_source_create(program,constants);
    assert(source);
    struct opengpu_native_joint_description shader;
    assert(!opengpu_native_joint_source_describe(source,&shader));
    assert(shader.vertex_layout.attributes==2);
    assert(shader.fragment.metadata.abi_version==8 && shader.fragment.metadata.color_types[0]==1);
    if (tess_uniforms) {
        assert(shader.uniforms[1].active_mask==0xfff && shader.uniforms[2].active_mask==0xfff);
        /* Replace the state-tracker bindings before protected BUILD consumes
         * the captured source. Each captured stage must keep its own views. */
        joint_bind_tess_uniforms(link,sink,tess_ubos,index,true);
        pipe_resource_reference(&tess_ubos,NULL);
    }
    if (patch_endpoints) {
        const struct opengpu_native_tess_layout *control=&shader.tess[0].layout;
        assert(control->output_vertices==32 && control->output_components==(full_patch?64u:60u) &&
            control->patch_components==(full_patch?0u:120u));
        assert(shader.tess[1].layout.owned_patch_reads && shader.tess[1].selection_count==0);
    }

    struct pipe_resource *resources[12]={0};
    unsigned resource_count=0;
    void *data;
    struct pipe_screen *screen=pipe->screen;
    struct opengpu_native_joint_view vertices=joint_view(screen,256*16,64,resources,&resource_count,&data);
    float first[4]={-1,-1,0,1};
    const float second[4]={4,4,0,1};
    if (domain==0) first[1]=-0.25f;
    if (domain==1) first[0]=first[1]=-1.5f;
    memcpy((unsigned char *)data+254*16,first,sizeof(first));
    memcpy((unsigned char *)data+2*16,second,sizeof(second));
    const struct opengpu_native_joint_view per_instance=joint_view(screen,192,64,resources,&resource_count,&data);
    const uint32_t first_pair[]={1,2,4,8}, wrong_divisor[]={128,64,32,16};
    memcpy((unsigned char *)data+7*16,first_pair,sizeof(first_pair));
    memcpy((unsigned char *)data+8*16,wrong_divisor,sizeof(wrong_divisor));
    const struct opengpu_native_joint_view indices=joint_view(screen,4,2,resources,&resource_count,&data);
    /* The fixed marker is a real index for ES PATCHES, even when the original
     * draw has global fixed-index restart enabled. Base vertex -1 applies. */
    const uint8_t index_words[]={4,0xff,3,0};
    memcpy(data,index_words,sizeof(index_words));
    const unsigned vertex_scratch=(7+4*shader.vertex_layout.attributes+4*shader.vertex_layout.varyings)*512;
    struct opengpu_native_joint_catalog_draw draw={
        .count=2,.instances=2,.base_instance=7,.token=41+index,
        .indexed=1,.index_format=1,.first_index=1,.base_vertex=-1,.indices=indices,.primitive_restart=1,
        .scratch=joint_view(screen,vertex_scratch,64,resources,&resource_count,NULL),
    };
    draw.attributes[0]=(struct opengpu_native_draw_attribute){.enabled=1,.binding=15,.components=4};
    draw.attributes[1]=(struct opengpu_native_draw_attribute){.enabled=1,.binding=0,.components=4,.format=1};
    draw.bindings[0]=(struct opengpu_native_joint_binding){.view=per_instance,.stride=16,.divisor=2};
    draw.bindings[15]=(struct opengpu_native_joint_binding){.view=vertices,.stride=16};
    struct opengpu_native_joint_invocation invocation={(UINT64_C(1)<<32)+index+3,0,2};
    struct opengpu_native_joint_range_request range={
        .flags=3,.task_id_base=100+8*index,.patch_count=2,.patches_per_instance=1,.instances=2,
    };
    const unsigned width=vga_quad?640:4, height=vga_quad?480:4;
    const unsigned tile_log2=vga_quad?4:3, pitch=width*16;
    const unsigned tile=1u<<tile_log2;
    const unsigned tiles=((width+tile-1)/tile)*((height+tile-1)/tile);
    struct opengpu_native_joint_build_request request={
        .context=pipe,.source=source,.pass=11+index,.generation=41+index,.state_count=1,
        .draws=&draw,.draw_count=1,.invocations=&invocation,.invocation_count=1,.ranges=&range,.range_count=1,
        .frame={.width=width,.height=height,.samples=1,.color_mask=1,.tile_log2=tile_log2},
    };
    const struct opengpu_native_joint_view image=joint_view(screen,pitch*height,64,resources,&resource_count,NULL);
    request.frame.attachments[0]=(struct opengpu_native_joint_attachment){
        .format=26,.load_op=1,.channel_mask=15,.image={.view=image,.row_pitch=pitch,.store=1},
    };
    request.frame.primitives=joint_view(screen,65536,64,resources,&resource_count,NULL);
    request.frame.directory=joint_view(screen,vga_quad?tiles*64:4096,64,resources,&resource_count,NULL);
    request.frame.pool[0]=joint_view(screen,vga_quad?tiles*4096:65536,0,resources,&resource_count,NULL);
    for (unsigned e=0;e<engines;++e)
        request.frame.scratch[e]=joint_view(screen,shader.fragment.metadata.prefix_bytes,64,resources,&resource_count,NULL);
    struct opengpu_native_joint_build_source *owner=opengpu_native_joint_build_source_create(&request);
    assert(owner);
    struct opengpu_native_joint_build_description original;
    assert(!opengpu_native_joint_build_source_describe(owner,&original));
    assert(original.draws[326]==1);
    unsigned handle=pipe_opengpu_resource_handle(opengpu_resource(image.resource)->gpu);
    uint64_t physical,allocation;
    assert(!native_kernel_original_physical(joint_session,handle,&physical,&allocation));
    uint64_t fence=0;
    assert(!opengpu_native_joint_build_source_submit(owner,0,0,&fence) && fence);
    fprintf(layout,"%s{\"record\":%u,\"domain\":%u,\"pass\":%u,\"generation\":%u,"
        "\"allocation\":%llu,\"allocationBytes\":%llu,\"imageOffset\":%llu,\"imageBytes\":%llu}",
        index?",":"",index,domain,request.pass,request.generation,
        (unsigned long long)physical,(unsigned long long)allocation,
        (unsigned long long)image.offset,(unsigned long long)image.bytes);

    opengpu_native_joint_build_source_destroy(owner);
    opengpu_native_joint_source_destroy(source);
    opengpu_native_joint_constants_destroy(constants);
    opengpu_native_joint_program_destroy(program);
    opengpu_native_joint_link_destroy(link);
    for (unsigned i=0;i<resource_count;++i) pipe_resource_reference(resources+i,NULL);
    for (unsigned stage=0;stage<4;++stage)
        assert(joint_references(shader.code[stage].handle)>0);
    if (tess_uniforms) {
        assert(joint_references(shader.uniforms[1].blocks[0].handle)>0);
        for (unsigned stage=1;stage<=2;++stage)
            assert(joint_references(shader.uniforms[stage].defaults.handle)>0);
    }
}

int main(int argc,char **argv)
{
    enum joint_mode mode=JOINT_SMALL;
    if (argc==5 && !strcmp(argv[4],"--vga-quad")) mode=JOINT_VGA_QUAD;
    if (argc==5 && !strcmp(argv[4],"--patch-endpoints")) mode=JOINT_PATCH_ENDPOINTS;
    if (argc==5 && !strcmp(argv[4],"--full-patch-endpoints")) mode=JOINT_FULL_PATCH_ENDPOINTS;
    if (argc==5 && !strcmp(argv[4],"--full-patch-mutation")) mode=JOINT_FULL_PATCH_MUTATION;
    if (argc==5 && !strcmp(argv[4],"--tess-uniforms")) mode=JOINT_TESS_UNIFORMS;
    if (argc==5 && !strcmp(argv[4],"--tess-uniform-recovery")) mode=JOINT_TESS_UNIFORM_RECOVERY;
    if (argc!=4 && mode==JOINT_SMALL) {
        fprintf(stderr,"usage: native-joint ESSL_DIR PROFILE_OR_--cpu-preflight SESSION_DIR "
            "[--vga-quad|--patch-endpoints|--full-patch-endpoints|--full-patch-mutation|--tess-uniforms|--tess-uniform-recovery]\n");
        return 2;
    }
    const bool cpu_preflight=!strcmp(argv[2],"--cpu-preflight");
    joint_profile=joint_live_profile(cpu_preflight?NULL:argv[2]);
    assert(joint_profile);
    native_parameter_query=joint_parameter;
    struct pipe_screen *screen=opengpu_drm_screen_create(7,NULL);
    assert(screen);
    struct pipe_context *pipe=screen->context_create(screen,NULL,0);
    assert(pipe && !opengpu_native_joint_context_enable(pipe));
    struct opengpu_native_joint_runtime runtime;
    bool driver_profile;
    unsigned context;
    assert(!opengpu_native_joint_source_runtime(pipe,&runtime,&context,&driver_profile) && driver_profile);
    assert((mode!=JOINT_VGA_QUAD && mode!=JOINT_TESS_UNIFORM_RECOVERY) || runtime.engines==4);
    joint_session=native_kernel_session_create_profile(context,joint_profile);
    assert(joint_session);
    native_original_allocate=joint_allocate;
    native_original_close=joint_close;
    native_original_refcount=joint_references;
    resource_ioctl=joint_ioctl;
    char path[4096];
    joint_source_path(path,argv[3],"original-inputs.json");
    FILE *layout=fopen(path,"wx");
    assert(layout);
    fprintf(layout,"{\"context\":%u,\"engines\":%u,\"cpuPreflight\":%s,\"records\":[",
        context,runtime.engines,cpu_preflight?"true":"false");
    unsigned records=JOINT_LIVE_RECORDS;
    if (mode==JOINT_PATCH_ENDPOINTS || mode==JOINT_TESS_UNIFORMS) records=JOINT_LIVE_BASE_RECORDS;
    if (mode==JOINT_VGA_QUAD || mode==JOINT_FULL_PATCH_ENDPOINTS || mode==JOINT_FULL_PATCH_MUTATION) records=2;
    if (mode==JOINT_TESS_UNIFORM_RECOVERY) records=JOINT_LIVE_UNIFORM_RECOVERY_RECORDS;
    for (unsigned i=0;i<records;++i) joint_case(pipe,argv[1],i,runtime.engines,mode,layout);
    fputs("]}\n",layout);
    assert(!fclose(layout));
    joint_live_export(joint_session,argv[3]);
    joint_live_run(joint_session,records,cpu_preflight,mode==JOINT_TESS_UNIFORM_RECOVERY);
    assert(!native_kernel_session_destroy(joint_session));
    pipe->destroy(pipe);
    screen->destroy(screen);
    native_kernel_profile_destroy(joint_profile);
    puts("Original Mesa tessellation submissions retired with acknowledged cleanup");
    return 0;
}
