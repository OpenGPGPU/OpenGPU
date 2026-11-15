/* SPDX-License-Identifier: MIT */
/* Real original compiler/Gallium resources. Only OS/GEM primitives are peers;
 * this fixture never runs its inherited compute word VM or submits a shader. */
#define main unused_cs_callback_main
#define OPENGPU_CS_REAL_TGSI_DUMP
#define OPENGPU_CS_REAL_MESA_SURFACES
#include "cs_callback_flow.c"
#undef main
#include "opengpu_native_five_internal.h"
#include "opengpu_native_joint_internal.h"
#include "native_graphics_v2_profile.inc"
#include "tgsi/tgsi_dump.h"
static const unsigned five_stages[5]={0,4,5,3,1};
static void write_words(const char *prefix,const char *stage,const uint32_t *code,unsigned words){
    char path[4096];assert(snprintf(path,sizeof(path),"%s.%s.words",prefix,stage)>0);
    FILE *f=fopen(path,"wb");assert(f);
    for(unsigned i=0;i<words;i++)for(unsigned k=0;k<4;k++)assert(fputc((code[i]>>(8*k))&255,f)!=EOF);
    assert(!fclose(f));
}
void native_five_test(struct opengpu_native_five_link *link,const char *output,unsigned mode){
    unsigned sample=mode==1,centroid=mode==3,depth=mode==4,pixel_mask=mode==5;
    struct pipe_screen *screen=opengpu_drm_screen_create(7,NULL);assert(screen);
    struct pipe_context *pipe=screen->context_create(screen,NULL,0);assert(pipe);
    struct opengpu_native_sample_runtime runtime;assert(!opengpu_native_sample_runtime_decode(cpu_profile,117,&runtime));
    assert(!opengpu_native_sample_context_bootstrap(pipe,&runtime));
    assert(!pipe->create_gs_state&&!pipe->create_tcs_state&&!pipe->create_tes_state);
    for(unsigned s=0;s<5;s++)assert(opengpu_native_five_blocks(link,five_stages[s])==12&&opengpu_native_five_defaults(link,five_stages[s])==1024);
    struct opengpu_native_five_input input;assert(!opengpu_native_five_link_capture(link,&input));
    struct opengpu_native_joint_input old;
    assert(opengpu_native_joint_link_capture((const struct opengpu_native_joint_link *)link,&old));
    for(unsigned bad=0;bad<10;bad++){
        struct opengpu_native_five_input wrong=input;
        if(bad<8)wrong.maps[bad].spans[0].per_vertex^=1;
        else if(bad==8)wrong.fragment.linked_geometry=0;
        else wrong.fragment.version=2;
        unsigned before=next_bo;
        assert(!opengpu_native_five_compile(pipe,&wrong,2)&&next_bo==before);
    }
    struct opengpu_native_tess_packing pin,pout;struct opengpu_native_tess_layout untouched_layout;
    assert(!opengpu_native_tess_pack(input.maps+3,&pin)&&!opengpu_native_tess_pack(input.maps+4,&pout));
    memset(&untouched_layout,0xa5,sizeof(untouched_layout));struct opengpu_native_tess_layout before_layout=untouched_layout;
    assert(opengpu_native_tess_linked_reflect(input.tokens[2],input.words[2],5,128,2,&pin,&pout,&untouched_layout));
    assert(!memcmp(&untouched_layout,&before_layout,sizeof(untouched_layout))); /* NEXT_GS never accepted as NEXT_FS */
    struct opengpu_native_five_compile_options selected={1,pixel_mask,{0,0}};
    for(unsigned bad=0;bad<4;bad++){
        struct opengpu_native_five_compile_options wrong=selected;
        if(bad==0)wrong.version=0;
        if(bad==1)wrong.geometry_pixel_mask_transport=2;
        if(bad==2)wrong.reserved[0]=1;
        if(bad==3)wrong.reserved[1]=1;
        unsigned before=next_bo;
        assert(!opengpu_native_five_program_create_options(pipe,link,2,&wrong)&&next_bo==before);
    }
    if(sample){
        struct opengpu_native_five_compile_options incompatible={1,1,{0,0}};unsigned before=next_bo;
        assert(!opengpu_native_five_program_create_options(pipe,link,2,&incompatible)&&next_bo==before);
    }
    struct opengpu_native_five_program *p=pixel_mask?
        opengpu_native_five_program_create_options(pipe,link,2,&selected):opengpu_native_five_program_create(pipe,link,2);
    if(!p){
        struct opengpu_native_fragment_config_v3 cfg={128,16,4,1,1,0};struct opengpu_native_fragment_program_v3 fs;
        fprintf(stderr,"five-stage FS compile=%d static=%u\n",opengpu_native_fragment_uniform_compile_v3(input.tokens[4],input.words[4],&input.fragment,&cfg,&fs),input.fragment.static_inputs);
        tgsi_dump_to_file(input.tokens[4],0,stderr);
    }
    assert(p);
    struct opengpu_native_five_constants *cache=opengpu_native_five_constants_create(pipe);assert(cache);
    struct pipe_context *sink=opengpu_native_five_constants_pipe(cache);
    sparse_next=1;unsigned allocation=72*16448+256;struct pipe_resource *original=buffer(screen,allocation);assert(original);
    unsigned handle=pipe_opengpu_resource_handle(opengpu_resource(original)->gpu);
    for(unsigned i=0;i<allocation/4;i++)((uint32_t *)bos[handle].data)[i]=0x13579bdfu+i*33u;
    struct pipe_resource *resources[72]={0};unsigned offsets[72]={0},sizes[72]={0};
    for(unsigned s=0;s<5;s++)for(unsigned i=0;i<12;i++){
        unsigned b=opengpu_native_five_binding(link,five_stages[s],i);assert(b<72&&!resources[b]);
        pipe_resource_reference(&resources[b],original);offsets[b]=4+b*16448;sizes[b]=16384;
    }
    assert(resources[71]);
    assert(!opengpu_native_five_bind_ubos(link,sink,resources,offsets,sizes));
    uint32_t defaults[1024];
    for(unsigned s=0;s<5;s++){
        for(unsigned i=0;i<1024;i++)defaults[i]=0x76543210u-i-s*4096;
        assert(!opengpu_native_five_bind_defaults(link,sink,five_stages[s],defaults,1024));
    }
    memset(defaults,0xee,sizeof(defaults));
    struct opengpu_native_five_source *lease=opengpu_native_five_source_create(p,cache);assert(lease);
    struct opengpu_native_five_description d,saved;assert(!opengpu_native_five_source_describe(lease,&d));saved=d;
    assert(d.fragment.metadata.abi_version==(pixel_mask?10u:sample?7u:5u)&&d.fragment.metadata.requires_geometry_owner==1&&!d.fragment.metadata.requires_primitive_owner);
    assert(d.fragment.metadata.requires_sample_owner==sample&&d.fragment.metadata.color_slots==4&&d.fragment.metadata.varying_slots==16);
    assert(d.fragment.metadata.flat_mask==(centroid?0:UINT64_C(15)<<60));
    assert(d.fragment.metadata.centroid_mask==(centroid?UINT64_C(15)<<60:0));
    assert(d.fragment.metadata.writes_depth==depth&&d.fragment.metadata.writes_sample_mask==pixel_mask);
    if(pixel_mask){
        assert(d.fragment.metadata.input_arrays==74&&d.fragment.metadata.prefix_bytes==47104);
        unsigned shift=0,enable=0;
        for(unsigned i=0;i<d.fragment.words;i++){
            shift+=d.fragment.code[i]==0x97e3bf57u; /* vsll.vi v30,v30,7 */
            enable+=d.fragment.code[i]==0x07d00513u; /* x10=1|(15<<2)|mask-enable64 */
        }
        assert(shift==1&&enable==1); /* Actual pixel-mask control word, no sample planes. */
    }
    assert(d.vertex_layout.varyings==16&&d.tess[0].layout.version==3&&d.tess[1].layout.version==3);
    assert(d.tess[0].layout.input_components==8&&d.tess[0].layout.output_components==8&&d.tess[1].layout.output_components==8);
    assert(d.vertex_components[4].kind==1&&d.vertex_components[4].component==60);
    assert(d.geometry.layout.input_sources[4]==56&&d.evaluation_components[4].valid);
    assert(d.evaluation_components[4].component==4);
    unsigned found_layer=0;
    for(unsigned i=0;i+3<d.fragment.words;i++)if(d.fragment.code[i]==((73u<<20)|(6u<<7)|0x13u)&&
        d.fragment.code[i+1]==(1u<<25|6u<<20|5u<<15|6u<<7|0x33u)&&
        d.fragment.code[i+2]==(6u<<20|7u<<15|6u<<7|0x33u)&&
        (d.fragment.code[i+3]&0x03fff07fu)==(1u<<25|6u<<15|6u<<12|0x07u))found_layer++;
    assert(found_layer); /* Actual GS Layer array73, never no-GS constant zero. */
    assert(d.fragment_components[64].valid&&d.fragment_components[64].component==4);
    for(unsigned s=0;s<5;s++){
        const uint32_t *code=s==0?d.vertex.code:s==4?d.fragment.code:s==3?d.geometry.code:d.tess[s-1].code;
        unsigned words=s==0?d.vertex.words:s==4?d.fragment.words:s==3?d.geometry.words:d.tess[s-1].words;
        assert(words&&words<=512&&d.code[s].bytes==4*words&&!memcmp(bos[d.code[s].handle].data,code,4*words));
        for(unsigned k=0;k<12;k++){
            unsigned b=opengpu_native_five_binding(link,five_stages[s],k);
            assert(d.uniforms[s].blocks[k].handle==handle&&d.uniforms[s].blocks[k].offset==offsets[b]&&d.uniforms[s].blocks[k].bytes==16384);
            uint32_t *data=(uint32_t *)(bos[handle].data+offsets[b]);
            assert(data[0]==0x13579bdfu+(offsets[b]/4)*33u&&data[4095]==0x13579bdfu+(offsets[b]/4+4095)*33u);
        }
        assert(d.uniforms[s].defaults.bytes==4096);
        for(unsigned i=0;i<1024;i++)assert(((uint32_t *)bos[d.uniforms[s].defaults.handle].data)[i]==0x76543210u-i-s*4096);
    }
    d.fragment.metadata.abi_version=8;d.geometry.layout.input_components=0;
    assert(!opengpu_native_five_source_describe(lease,&d)&&!memcmp(&d,&saved,sizeof(d)));
    unsigned binding=opengpu_native_five_binding(link,3,0);
    struct pipe_constant_buffer cb={.buffer=resources[binding],.buffer_offset=offsets[binding]+64,.buffer_size=16384};
    sink->set_constant_buffer(sink,PIPE_SHADER_GEOMETRY,1,false,&cb);
    for(unsigned s=0;s<5;s++){
        for(unsigned i=0;i<1024;i++)defaults[i]=i+s;
        assert(!opengpu_native_five_bind_defaults(link,sink,five_stages[s],defaults,1024));
        if(s==3&&!bos[saved.uniforms[s].defaults.handle].handle)fputs("GS_DEFAULT_LEASE_LOST\n",stderr);
        assert(bos[saved.uniforms[s].defaults.handle].handle);
    }
    struct opengpu_native_five_source *second=opengpu_native_five_source_create(p,cache);assert(second);
    struct opengpu_native_five_description changed;assert(!opengpu_native_five_source_describe(second,&changed));
    assert(changed.uniforms[3].blocks[0].offset==offsets[binding]+64);
    for(unsigned s=0;s<5;s++)assert(changed.uniforms[s].defaults.handle!=d.uniforms[s].defaults.handle);
    assert(!opengpu_native_five_source_describe(lease,&changed)&&!memcmp(&changed,&saved,sizeof(saved)));
    opengpu_native_five_source_destroy(second);
    cb.buffer_size=16380;sink->set_constant_buffer(sink,PIPE_SHADER_GEOMETRY,1,false,&cb);
    unsigned refs=original->reference.count;assert(!opengpu_native_five_source_create(p,cache)&&original->reference.count==refs);
    struct pipe_context *other=screen->context_create(screen,NULL,0);assert(other);
    struct opengpu_native_five_constants *foreign=opengpu_native_five_constants_create(other);assert(foreign);
    assert(!opengpu_native_five_source_create(p,foreign));opengpu_native_five_constants_destroy(foreign);other->destroy(other);
    opengpu_native_five_program_destroy(p);opengpu_native_five_constants_destroy(cache);
    for(unsigned i=0;i<72;i++)pipe_resource_reference(&resources[i],NULL);
    assert(original->reference.count==61);pipe_resource_reference(&original,NULL);
    for(unsigned s=0;s<5;s++)assert(bos[d.code[s].handle].handle&&bos[d.uniforms[s].defaults.handle].handle);
    char path[4096];assert(snprintf(path,sizeof(path),"%s.ognsf",output)>0);assert(!opengpu_native_fragment_write_v3(path,&d.fragment));
    struct opengpu_native_fragment_program_v3 decoded;assert(!opengpu_native_fragment_read_v3(path,&decoded)&&!memcmp(&decoded,&d.fragment,sizeof(decoded)));
    unsigned char extra=0;FILE *artifact=fopen(path,"ab");assert(artifact&&fwrite(&extra,1,1,artifact)==1&&!fclose(artifact));
    struct opengpu_native_fragment_program_v3 unchanged=decoded;
    assert(opengpu_native_fragment_read_v3(path,&decoded)&&!memcmp(&decoded,&unchanged,sizeof(decoded)));
    assert(!remove(path)&&!opengpu_native_fragment_write_v3(path,&d.fragment));
    /* An old no-GS artifact remains old even when its raw code is opaque. */
    struct opengpu_native_fragment_reflection_v2 r2={.version=2,.essl_version=320};
    struct opengpu_native_fragment_config_v2 c2={128,0,0,1,0};
    struct opengpu_native_fragment_program_v2 old_artifact={.words=1,.code={0x30500073u}};
    assert(!opengpu_native_fragment_metadata_init_v2(&r2,&c2,&old_artifact.metadata));
    char old_path[4096];assert(snprintf(old_path,sizeof(old_path),"%s.old.ognsf",output)>0);
    assert(!opengpu_native_fragment_write_v2(old_path,&old_artifact));
    assert(opengpu_native_fragment_read_v3(old_path,&decoded)&&!memcmp(&decoded,&unchanged,sizeof(decoded)));
    assert(!remove(old_path));
    artifact=fopen(path,"r+b");assert(artifact&&!fseek(artifact,8+41*4,SEEK_SET)&&fputc(1,artifact)!=EOF&&!fclose(artifact));
    assert(opengpu_native_fragment_read_v3(path,&decoded)&&!memcmp(&decoded,&unchanged,sizeof(decoded)));
    assert(!remove(path)&&!opengpu_native_fragment_write_v3(path,&d.fragment));
    struct opengpu_native_fragment_program_v2 rejected,untouched;memset(&rejected,0xa5,sizeof(rejected));untouched=rejected;
    assert(opengpu_native_fragment_read_v2(path,&rejected)&&!memcmp(&rejected,&untouched,sizeof(rejected)));
    write_words(output,"vs",d.vertex.code,d.vertex.words);write_words(output,"tcs",d.tess[0].code,d.tess[0].words);
    write_words(output,"tes",d.tess[1].code,d.tess[1].words);write_words(output,"gs",d.geometry.code,d.geometry.words);write_words(output,"fs",d.fragment.code,d.fragment.words);
    printf("NATIVE_FIVE_CPU words=%u/%u/%u/%u/%u refs65 code5 ABI%u GPU=false submit=false\n",d.vertex.words,d.tess[0].words,d.tess[1].words,d.geometry.words,d.fragment.words,d.fragment.metadata.abi_version);
    opengpu_native_five_source_destroy(lease);assert(!bos[handle].handle);
    for(unsigned s=0;s<5;s++)assert(!bos[d.code[s].handle].handle&&!bos[d.uniforms[s].defaults.handle].handle);
    for(unsigned k=0;k<6;k++){
        if(k<5){fail_request=DRM_IOCTL_OPENGPU_CREATE_BUFFER;fail_nth=k+1;fail_seen=0;}else map_error=1;
        assert(!(pixel_mask?opengpu_native_five_program_create_options(pipe,link,2,&selected):
            opengpu_native_five_program_create(pipe,link,2)));fail_request=fail_nth=fail_seen=map_error=0;
    }
    pipe->destroy(pipe);screen->destroy(screen);
    for(unsigned i=1;i<next_bo;i++)assert(!bos[i].handle&&!bos[i].jobs&&!bos[i].maps);
}

void native_five_mask_reject(struct opengpu_native_five_link *link){
    struct pipe_screen *screen=opengpu_drm_screen_create(7,NULL);assert(screen);
    struct pipe_context *pipe=screen->context_create(screen,NULL,0);assert(pipe);
    struct opengpu_native_sample_runtime runtime;assert(!opengpu_native_sample_runtime_decode(cpu_profile,117,&runtime));
    assert(!opengpu_native_sample_context_bootstrap(pipe,&runtime));
    struct opengpu_native_five_input input;assert(!opengpu_native_five_link_capture(link,&input));
    assert((input.fragment.static_inputs&(OPENGPU_FRAGMENT_SAMPLE_MASK_IN|OPENGPU_FRAGMENT_HELPER))==(OPENGPU_FRAGMENT_SAMPLE_MASK_IN|OPENGPU_FRAGMENT_HELPER));
    assert(!(input.fragment.static_inputs&(OPENGPU_FRAGMENT_SAMPLE_ID|OPENGPU_FRAGMENT_SAMPLE_POSITION|OPENGPU_FRAGMENT_SAMPLE_QUALIFIER)));
    struct opengpu_native_fragment_config_v3 cfg={128,16,4,1,1,0};struct opengpu_native_fragment_program_v3 output,saved;
    struct opengpu_native_fragment_metadata_v2 metadata;
    assert(!opengpu_native_fragment_metadata_init_v3(&input.fragment,&cfg,&metadata)&&metadata.abi_version==5&&!metadata.requires_sample_owner);
    memset(&output,0xa5,sizeof(output));saved=output;
    assert(opengpu_native_fragment_uniform_compile_v3(input.tokens[4],input.words[4],&input.fragment,&cfg,&output));
    assert(!memcmp(&output,&saved,sizeof(output)));
    unsigned before=next_bo;assert(!opengpu_native_five_program_create(pipe,link,2)&&next_bo==before);
    cfg.geometry_pixel_mask_transport=1;cfg.sample_transport=0;
    assert(!opengpu_native_fragment_uniform_compile_v3(input.tokens[4],input.words[4],&input.fragment,&cfg,&output));
    assert(output.metadata.abi_version==10&&output.metadata.requires_geometry_owner==1&&
        !output.metadata.requires_sample_owner&&!output.metadata.requires_primitive_owner&&output.metadata.writes_sample_mask);
    assert(output.metadata.input_arrays==74&&output.metadata.prefix_bytes==47104);
    struct opengpu_native_fragment_metadata_v2 valid=output.metadata;
    metadata=valid;metadata.abi_version=5;assert(!opengpu_native_fragment_metadata_valid_v3(&metadata));
    metadata=valid;metadata.requires_sample_owner=1;assert(!opengpu_native_fragment_metadata_valid_v3(&metadata));
    metadata=valid;metadata.requires_primitive_owner=1;assert(!opengpu_native_fragment_metadata_valid_v3(&metadata));
    metadata=valid;metadata.requires_geometry_owner=0;assert(!opengpu_native_fragment_metadata_valid_v3(&metadata));
    cfg.geometry_pixel_mask_transport=2;metadata=valid;
    assert(opengpu_native_fragment_metadata_init_v3(&input.fragment,&cfg,&metadata)&&!memcmp(&metadata,&valid,sizeof(metadata)));
    pipe->destroy(pipe);screen->destroy(screen);
    for(unsigned i=1;i<next_bo;i++)assert(!bos[i].handle&&!bos[i].jobs&&!bos[i].maps);
    puts("Original GS pixel-rate SampleMask requires new transport; rejected without planes");
}
