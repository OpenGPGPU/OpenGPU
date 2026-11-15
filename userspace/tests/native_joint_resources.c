/* SPDX-License-Identifier: MIT */
/* Opt-in real Mesa/Gallium joint-program CPU test. Only OS/GEM primitives are
 * modeled. Never executes a shader, submits work, or grants a capability. */
#include "native_graphics_v2_resources.c"
#include "opengpu_native_joint_internal.h"
#include "tgsi/tgsi_parse.h"
#include "tgsi/tgsi_dump.h"
static const unsigned joint_stages[4]={0,4,5,1};
static void original_indirect_guards(const struct opengpu_native_joint_input *input)
{
    struct opengpu_native_tess_packing pin,pout;struct opengpu_native_tess_layout l;
    assert(!opengpu_native_tess_pack(input->maps+1,&pin)&&!opengpu_native_tess_pack(input->maps+2,&pout));
    assert(!opengpu_native_tess_linked_reflect(input->tokens[1],input->words[1],4,128,2,&pin,&pout,&l));
    struct opengpu_native_tess_program out,saved;memset(&saved,0xa5,sizeof(saved));out=saved;
    assert(opengpu_native_tess_language_emit(input->tokens[1],input->words[1],&l,input->maps+2,&out));assert(!memcmp(&out,&saved,sizeof(out)));
    struct opengpu_native_tess_layout old=l;old.version=1;old.owned_tessellation_info=0;
    assert(opengpu_native_tess_uniform_emit(input->tokens[1],input->words[1],&old,&out));assert(!memcmp(&out,&saved,sizeof(out)));
    assert(opengpu_native_tess_control_emit(input->tokens[1],input->words[1],&old,input->maps+2,&out));assert(!memcmp(&out,&saved,sizeof(out)));
    assert(!opengpu_native_tess_linked_emit(input->tokens[1],input->words[1],&l,input->maps+2,&pin,&pout,&out));
    unsigned found=0,at=0,definition=0;struct tgsi_parse_context parser;assert(tgsi_parse_init(&parser,input->tokens[1])==TGSI_PARSE_OK);
    while(!tgsi_parse_end_of_tokens(&parser)&&!found){
        unsigned first=parser.Position;tgsi_parse_token(&parser);
        if(parser.FullToken.Token.Type==TGSI_TOKEN_TYPE_INSTRUCTION&&parser.FullToken.FullInstruction.Instruction.Opcode==TGSI_OPCODE_UARL&&parser.FullToken.FullInstruction.Dst[0].Register.Index==0)
            definition=first+1;
        if(parser.FullToken.Token.Type==TGSI_TOKEN_TYPE_INSTRUCTION&&parser.FullToken.FullInstruction.Instruction.NumDstRegs&&parser.FullToken.FullInstruction.Dst[0].Register.Indirect){
            at=first+1+parser.FullToken.FullInstruction.Instruction.Label;found=1;
        }
    }
    tgsi_parse_free(&parser);assert(found&&definition);
    for(unsigned bad=0;bad<10;bad++){
        uint32_t *t=malloc(input->words[1]*4);assert(t);memcpy(t,input->tokens[1],input->words[1]*4);
        struct tgsi_dst_register dst;struct tgsi_ind_register addr;
        memcpy(&dst,t+at,4);memcpy(&addr,t+at+1,4);
        if(bad==0)addr.File=TGSI_FILE_TEMPORARY;
        if(bad==1)addr.Index=2;
        if(bad==2)addr.Swizzle=1;
        if(bad==3)addr.ArrayID=1;
        if(bad==4)dst.File=TGSI_FILE_TEMPORARY;
        if(bad==5)dst.Index=-1;
        if(bad==6)dst.Dimension=1;
        if(bad==7){struct tgsi_dst_register d;memcpy(&d,t+definition,4);d.Index=1;memcpy(t+definition,&d,4);} /* Original ADDR0 now undefined. */
        if(bad==8)dst.Padding=1;
        memcpy(t+at,&dst,4);memcpy(t+at+1,&addr,4);out=saved;
        unsigned n=input->words[1]-(bad==9);
        assert(opengpu_native_tess_linked_emit(t,n,&l,input->maps+2,&pin,&pout,&out));
        assert(!memcmp(&out,&saved,sizeof(out)));free(t);
    }
}
void native_joint_test(struct opengpu_native_joint_link *link,const char *output,unsigned full,unsigned vs_slot,unsigned fs_slot)
{
    unsigned indirect=full==2,levels=full==3;if(indirect||levels)full=0;
    struct pipe_screen *screen=opengpu_drm_screen_create(7,NULL);assert(screen);
    struct pipe_context *pipe=screen->context_create(screen,NULL,0);assert(pipe);
    assert(!pipe->create_tcs_state&&!pipe->create_tes_state);
    assert(!screen->get_shader_param(screen,PIPE_SHADER_TESS_CTRL,PIPE_SHADER_CAP_MAX_CONST_BUFFERS));
    struct opengpu_native_sample_runtime runtime;assert(!opengpu_native_sample_runtime_decode(cpu_profile,117,&runtime));
    assert(!opengpu_native_sample_context_bootstrap(pipe,&runtime));
    struct opengpu_native_tess_input_map capacity={.version=1,.count=3};
    capacity.spans[0]=(struct opengpu_native_tess_input_span){.slots=1,.semantic=TGSI_SEMANTIC_POSITION,.mask=15};
    capacity.spans[1]=(struct opengpu_native_tess_input_span){.slots=14,.semantic=TGSI_SEMANTIC_GENERIC,.mask=15};
    capacity.spans[2]=(struct opengpu_native_tess_input_span){.slots=30,.semantic=TGSI_SEMANTIC_PATCH,.mask=15};
    struct opengpu_native_tess_packing packed,unchanged;
    assert(!opengpu_native_tess_pack(&capacity,&packed)&&packed.vertex_components==60&&packed.patch_components==120);
    capacity.count=2;memset(capacity.spans+2,0,sizeof(capacity.spans[2]));capacity.spans[1].slots=15;
    assert(!opengpu_native_tess_pack(&capacity,&packed)&&packed.vertex_components==64&&packed.patch_components==0);
    unchanged=packed;capacity.spans[1].slots=16;
    assert(opengpu_native_tess_pack(&capacity,&packed)&&!memcmp(&packed,&unchanged,sizeof(packed)));
    struct opengpu_native_joint_input input;assert(!opengpu_native_joint_link_capture(link,&input));
    if(indirect)original_indirect_guards(&input);
    if(levels){
        const struct opengpu_native_tess_input_span *outer=NULL,*inner=NULL,*patch=NULL;
        for(unsigned i=0;i<input.maps[3].count;i++){
            const struct opengpu_native_tess_input_span *s=input.maps[3].spans+i;
            if(s->semantic==TGSI_SEMANTIC_TESSOUTER)outer=s;
            if(s->semantic==TGSI_SEMANTIC_TESSINNER)inner=s;
            if(s->semantic==TGSI_SEMANTIC_PATCH)patch=s;
        }
        assert(outer&&outer->compact&&outer->slots==4&&!outer->per_vertex);
        assert(inner&&inner->compact&&inner->slots==2&&!inner->per_vertex);
        assert(patch&&!patch->compact&&patch->slots==2&&!patch->per_vertex);
        unsigned outerRead=0,innerRead=0,indirectPatch=0;
        struct tgsi_parse_context parser;assert(tgsi_parse_init(&parser,input.tokens[2])==TGSI_PARSE_OK);
        while(!tgsi_parse_end_of_tokens(&parser)){
            tgsi_parse_token(&parser);if(parser.FullToken.Token.Type!=TGSI_TOKEN_TYPE_INSTRUCTION)continue;
            const struct tgsi_full_instruction *in=&parser.FullToken.FullInstruction;
            unsigned mask=in->Instruction.NumDstRegs?in->Dst[0].Register.WriteMask:15;
            for(unsigned k=0;k<in->Instruction.NumSrcRegs;k++){
                struct tgsi_src_register s=in->Src[k].Register;if(s.File!=TGSI_FILE_INPUT||s.Dimension)continue;
                if(s.Indirect&&(unsigned)s.Index>=patch->first&&(unsigned)s.Index-patch->first<2)indirectPatch++;
                unsigned read=0,sw[4]={s.SwizzleX,s.SwizzleY,s.SwizzleZ,s.SwizzleW};
                for(unsigned c=0;c<4;c++)if(mask&(1u<<c))read|=1u<<sw[c];
                if((unsigned)s.Index==outer->first)outerRead|=read;
                if((unsigned)s.Index==inner->first)innerRead|=read;
            }
        }
        tgsi_parse_free(&parser);assert(outerRead==3&&innerRead==1&&indirectPatch);
    }
    struct opengpu_native_joint_program *p=opengpu_native_joint_program_create(pipe,link,2);
    if(!p){
        struct opengpu_native_tess_packing a,b,c;struct opengpu_native_tess_layout t[2];struct opengpu_native_tess_program q;
        fprintf(stderr,"pack=%d/%d/%d\n",opengpu_native_tess_pack(input.maps+1,&a),opengpu_native_tess_pack(input.maps+2,&b),opengpu_native_tess_pack(input.maps+4,&c));
        fprintf(stderr,"reflect=%d/%d\n",opengpu_native_tess_linked_reflect(input.tokens[1],input.words[1],4,128,2,&a,&b,t),
            opengpu_native_tess_linked_reflect(input.tokens[2],input.words[2],5,128,2,&b,&c,t+1));
        t[1].output_vertices=t[0].output_vertices;t[1].owned_patch_reads=1;
        t[1].tes_patch_layout=(struct opengpu_native_tess_patch_layout){2,a.vertex_components,t[0].output_vertices,b.vertex_components,b.patch_components};
        fprintf(stderr,"emit=%d/%d\n",opengpu_native_tess_linked_emit(input.tokens[1],input.words[1],t,input.maps+2,&a,&b,&q),
            opengpu_native_tess_linked_emit(input.tokens[2],input.words[2],t+1,input.maps+3,&b,&c,&q));
        for(unsigned s=0;s<6;s++){
            fprintf(stderr,"map%u count%u\n",s,input.maps[s].count);
            for(unsigned k=0;k<input.maps[s].count;k++){
                const struct opengpu_native_tess_input_span *v=input.maps[s].spans+k;
                fprintf(stderr," first%u slots%u pv%u sem%u idx%u mask%x compact%u\n",v->first,v->slots,v->per_vertex,v->semantic,v->index,v->mask,v->compact);
            }
        }
        for(unsigned s=0;s<4;s++)tgsi_dump_to_file(input.tokens[s],0,stderr);
    }
    assert(p);
    /* Actual original map mutation rejection, before any code GEM callback. */
    for(unsigned bad=0;bad<8;bad++){
        struct opengpu_native_joint_input wrong=input;
        if(bad==0)wrong.maps[1].version=2;
        if(bad==1)wrong.maps[2].spans[0].mask=16;
        if(bad==2)wrong.maps[4].spans[0].index=99;
        if(bad==3)wrong.maps[5].spans[0].semantic=TGSI_SEMANTIC_PATCH;
        if(bad==4)wrong.fragment.essl_version=310;
        if(bad==5)wrong.words[1]--;
        if(bad==6)wrong.maps[0].count=65;
        if(bad==7)wrong.maps[5].spans[63].mask=1;
        unsigned before=next_bo;assert(!opengpu_native_joint_compile(pipe,&wrong,2));assert(next_bo==before);
    }
    if(!full){
        /* Independent vec3/mat2x3 recipe: position4 + two columns*3 and two
         * per-patch vec3s. A vec4 declaration must not authorize padding.w. */
        const struct opengpu_native_tess_input_span *span=NULL;
        for(unsigned k=0;k<input.maps[3].count;k++)if(input.maps[3].spans[k].semantic==TGSI_SEMANTIC_GENERIC&&input.maps[3].spans[k].mask==7)span=input.maps[3].spans+k;
        assert(span&&span->slots==2);
        struct tgsi_parse_context parser;assert(tgsi_parse_init(&parser,input.tokens[2])==TGSI_PARSE_OK);unsigned found=0;
        while(!tgsi_parse_end_of_tokens(&parser)&&!found){
            unsigned first=parser.Position;tgsi_parse_token(&parser);
            if(parser.FullToken.Token.Type!=TGSI_TOKEN_TYPE_INSTRUCTION)continue;
            const struct tgsi_full_instruction *in=&parser.FullToken.FullInstruction;
            for(unsigned k=0;k<in->Instruction.NumSrcRegs&&!found;k++){
                struct tgsi_src_register src=in->Src[k].Register;
                if(src.File!=TGSI_FILE_INPUT||!src.Dimension||src.Index!=(int)span->first)continue;
                for(unsigned at=first+1;at<parser.Position;at++)if(!memcmp((const uint32_t *)input.tokens[2]+at,&src,4)){
                    uint32_t *words=malloc(input.words[2]*4);assert(words);memcpy(words,input.tokens[2],input.words[2]*4);
                    src.SwizzleX=3;memcpy(words+at,&src,4);struct opengpu_native_joint_input wrong=input;wrong.tokens[2]=words;
                    unsigned before=next_bo;assert(!opengpu_native_joint_compile(pipe,&wrong,2));assert(next_bo==before);free(words);found=1;break;
                }
            }
        }
        tgsi_parse_free(&parser);assert(found);
    }
    struct opengpu_native_uniform_layout vl;struct opengpu_native_uniform_program vp;
    assert(opengpu_native_uniform_compile(input.tokens[0],0,128,&vl,&vp)); /* NEXT_TCS is not VS->FS */
    struct opengpu_native_joint_constants *cache=opengpu_native_joint_constants_create(pipe);assert(cache);
    struct pipe_context *sink=opengpu_native_joint_constants_pipe(cache);
    unsigned allocation=72*16448+256;sparse_next=1;struct pipe_resource *original=buffer(screen,allocation);assert(original);
    unsigned handle=pipe_opengpu_resource_handle(opengpu_resource(original)->gpu);
    for(unsigned i=0;i<allocation/4;i++)((uint32_t *)bos[handle].data)[i]=0x13579bdfu+i*33u;
    struct pipe_resource *resources[72]={0};unsigned offsets[72]={0},sizes[72]={0};
    for(unsigned s=0;s<4;s++)for(unsigned i=0;i<opengpu_native_joint_blocks(link,joint_stages[s]);i++){
        unsigned b=opengpu_native_joint_binding(link,joint_stages[s],i);assert(b<72&&!resources[b]);
        pipe_resource_reference(&resources[b],original);offsets[b]=4+b*16448;sizes[b]=16384;
    }
    assert(!opengpu_native_joint_bind_ubos(link,sink,resources,offsets,sizes));
    uint32_t defaults[1024];
    for(unsigned s=0;s<4;s++){
        for(unsigned i=0;i<1024;i++)defaults[i]=0x76543210u-i-s*4096;
        unsigned n=opengpu_native_joint_defaults(link,joint_stages[s]);assert(n<=1024);
        if(n)assert(!opengpu_native_joint_bind_defaults(link,sink,joint_stages[s],defaults,n));
    }
    memset(defaults,0xee,sizeof(defaults));
    struct opengpu_native_joint_source *lease=opengpu_native_joint_source_create(p,cache);assert(lease);
    struct opengpu_native_joint_description d,saved;
    assert(!opengpu_native_joint_source_describe(lease,&d));saved=d;
    d.fragment.metadata.abi_version=1;d.tess[1].layout.output_components=0;
    assert(!opengpu_native_joint_source_describe(lease,&d)&&!memcmp(&d,&saved,sizeof(d)));
    struct pipe_context *other=screen->context_create(screen,NULL,0);assert(other);
    struct opengpu_native_joint_constants *foreign=opengpu_native_joint_constants_create(other);assert(foreign);
    assert(!opengpu_native_joint_source_create(p,foreign));opengpu_native_joint_constants_destroy(foreign);other->destroy(other);
    /* A failed foreign-stage update consumes transferred references but never
     * grants a GS path or contaminates this program's valid cache. */
    foreign=opengpu_native_joint_constants_create(pipe);assert(foreign);
    struct pipe_resource *transferred=NULL;pipe_resource_reference(&transferred,original);
    unsigned refs=original->reference.count;struct pipe_constant_buffer invalid={.buffer=transferred,.buffer_size=16};
    struct pipe_context *bad_sink=opengpu_native_joint_constants_pipe(foreign);
    bad_sink->set_constant_buffer(bad_sink,PIPE_SHADER_GEOMETRY,1,true,&invalid);
    assert(original->reference.count==refs-1&&!opengpu_native_joint_source_create(p,foreign));
    opengpu_native_joint_constants_destroy(foreign);
    if(!full){
        assert(d.tess[0].layout.input_components==8&&d.tess[0].layout.output_components==10&&d.tess[0].layout.patch_components==6);
        unsigned vec3Stride=0;
        for(unsigned i=2;i<d.tess[1].words;i++){
            uint32_t first=d.tess[1].code[i-2],add=d.tess[1].code[i-1],last=d.tess[1].code[i];
            unsigned address=(first>>20)&31;
            vec3Stride+=first==(0x25u<<26|1u<<25|address<<20|1u<<15|3u<<12|29u<<7|0x57u)&&
                add==(1u<<25|29u<<20|address<<15|29u<<7|0x57u)&&
                last==(0x25u<<26|1u<<25|29u<<20|3u<<15|3u<<12|29u<<7|0x57u);
        }
        assert(vec3Stride>=6); /* Actual opcode chain; arithmetic also has RTL coverage. */
    }
    if(indirect){
        unsigned pairs=0,storeBound2=0;
        for(unsigned i=1;i<d.tess[0].words;i++){
            uint32_t insn=d.tess[0].code[i];
            if(insn==(0x1au<<26|1u<<25|20u<<20|10u<<15|4u<<12|0x57u)){
                assert(d.tess[0].code[i-1]==0x00200513u);storeBound2++;
            }
            if((insn&0xfe007fffu)==(22u<<26|1u<<25|0x2bu)){
                uint32_t params=d.tess[0].code[i-1];
                assert((params&0xfe007fffu)==(20u<<26|1u<<25|0x2bu));
                assert(((params>>15)&31)>=22&&((params>>15)&31)<=25);
                assert(((insn>>15)&31)==28&&((insn>>20)&31)==29);pairs++;
            }
        }
        assert(pairs>=3&&storeBound2>=3); /* Original two-element PP array, xyz. */
    }
    assert(d.vertex_layout.varyings==vs_slot+1&&d.fragment.metadata.varying_slots==fs_slot+1);
    assert(d.vertex_components[4].kind==1&&d.vertex_components[4].component==4*vs_slot);
    assert(d.fragment_components[4+4*fs_slot].valid&&d.fragment_components[4+4*fs_slot].component==4);
    assert(d.tess[0].layout.version==3&&d.tess[1].layout.version==3); /* layout owns requirement */
    assert(d.tess[1].layout.owned_patch_reads&&d.tess[1].layout.owned_tessellation_info);
    assert(!d.tess[1].selection_count);
    for(unsigned s=0;s<4;s++){
        const uint32_t *code=s==0?d.vertex.code:s==3?d.fragment.code:d.tess[s-1].code;
        unsigned words=s==0?d.vertex.words:s==3?d.fragment.words:d.tess[s-1].words;
        assert(d.code[s].bytes==4*words&&!memcmp(bos[d.code[s].handle].data,code,4*words));
        unsigned blocks=opengpu_native_joint_blocks(link,joint_stages[s]);if(full)assert(blocks==12);
        for(unsigned i=0;i<blocks;i++){
            unsigned b=opengpu_native_joint_binding(link,joint_stages[s],i);
            assert(d.uniforms[s].blocks[i].handle==handle&&d.uniforms[s].blocks[i].offset==offsets[b]&&d.uniforms[s].blocks[i].bytes==16384);
        }
        if(full){assert(d.uniforms[s].defaults.bytes==4096);
            for(unsigned i=0;i<1024;i++)assert(((uint32_t *)bos[d.uniforms[s].defaults.handle].data)[i]==0x76543210u-i-s*4096);
        }
    }
    if(full){
        unsigned b=opengpu_native_joint_binding(link,0,0);
        struct pipe_constant_buffer cb={.buffer=resources[b],.buffer_offset=offsets[b]+64,.buffer_size=16384};
        sink->set_constant_buffer(sink,PIPE_SHADER_VERTEX,1,false,&cb);
        for(unsigned s=0;s<4;s++){
            for(unsigned i=0;i<1024;i++)defaults[i]=0xaabbcc00u+s+i;
            assert(!opengpu_native_joint_bind_defaults(link,sink,joint_stages[s],defaults,1024));
            assert(bos[saved.uniforms[s].defaults.handle].handle);
        }
        struct opengpu_native_joint_source *second=opengpu_native_joint_source_create(p,cache);assert(second);
        struct opengpu_native_joint_description changed;assert(!opengpu_native_joint_source_describe(second,&changed));
        assert(changed.uniforms[0].blocks[0].offset==offsets[b]+64);
        for(unsigned s=0;s<4;s++)assert(changed.uniforms[s].defaults.handle!=d.uniforms[s].defaults.handle);
        assert(!opengpu_native_joint_source_describe(lease,&changed)&&!memcmp(&changed,&saved,sizeof(saved)));
        opengpu_native_joint_source_destroy(second);
        /* Short original view fails atomically without leaking any staged refs. */
        cb.buffer_size=16380;sink->set_constant_buffer(sink,PIPE_SHADER_VERTEX,1,false,&cb);
        unsigned refs=original->reference.count;assert(!opengpu_native_joint_source_create(p,cache));assert(original->reference.count==refs);
    }
    struct opengpu_native_joint_description sentinel,untouched;memset(&sentinel,0xa5,sizeof(sentinel));untouched=sentinel;
    assert(opengpu_native_joint_source_describe(NULL,&sentinel)&&!memcmp(&sentinel,&untouched,sizeof(sentinel)));
    opengpu_native_joint_program_destroy(p);opengpu_native_joint_constants_destroy(cache);
    for(unsigned i=0;i<72;i++)pipe_resource_reference(&resources[i],NULL);
    if(full)assert(original->reference.count==49);
    pipe_resource_reference(&original,NULL);
    if(full)assert(bos[handle].handle);
    for(unsigned s=0;s<4;s++)assert(bos[d.code[s].handle].handle);
    if(full)for(unsigned s=0;s<4;s++){
        assert(bos[d.uniforms[s].defaults.handle].handle);
        for(unsigned i=0;i<1024;i++)assert(((uint32_t *)bos[d.uniforms[s].defaults.handle].data)[i]==0x76543210u-i-s*4096);
    }
    char path[4096];assert(snprintf(path,sizeof(path),"%s.ognsf",output)>0);assert(!opengpu_native_fragment_write_v2(path,&d.fragment));
    for(unsigned s=0;s<3;s++){
        assert(snprintf(path,sizeof(path),"%s.%s.words",output,s==0?"vs":s==1?"tcs":"tes")>0);
        FILE *f=fopen(path,"wb");assert(f);assert(fwrite(bos[d.code[s].handle].data,1,d.code[s].bytes,f)==d.code[s].bytes);assert(!fclose(f));
    }
    assert(snprintf(path,sizeof(path),"%s.layout",output)>0);FILE *f=fopen(path,"w");assert(f);
    fprintf(f,"%u %u %u %u %u %u %u %u %u\n",d.tess[0].layout.input_vertices,d.tess[0].layout.input_components,d.tess[0].layout.output_vertices,
        d.tess[0].layout.output_components,d.tess[0].layout.patch_components,d.tess[1].layout.output_components,d.vertex.words,d.tess[0].words,d.tess[1].words);assert(!fclose(f));
    assert(snprintf(path,sizeof(path),"%s.source.json",output)>0);f=fopen(path,"w");assert(f);
    fprintf(f,"{\"schema\":\"opengpu-native-joint-compiler-source-v1\",\"context\":%u,\"stages\":[",d.context_id);
    for(unsigned s=0;s<4;s++){
        fprintf(f,"%s{\"stage\":%u,\"code\":{\"handle\":%u,\"offset\":%llu,\"bytes\":%u},\"views\":[",s?",":"",joint_stages[s],d.code[s].handle,(unsigned long long)d.code[s].offset,d.code[s].bytes);
        for(unsigned k=0;k<13;k++){
            const struct drm_opengpu_compute_uniform_view *v=k?d.uniforms[s].blocks+k-1:&d.uniforms[s].defaults;
            fprintf(f,"%s{\"slot\":%u,\"handle\":%u,\"offset\":%llu,\"bytes\":%u}",k?",":"",k,v->handle,(unsigned long long)v->offset,v->bytes);
        }fprintf(f,"]}");
    }fprintf(f,"],\"vertexComponents\":[");
    for(unsigned k=0;k<64;k++)fprintf(f,"%s[%u,%u]",k?",":"",d.vertex_components[k].kind,d.vertex_components[k].component);
    fprintf(f,"],\"fragmentComponents\":[");for(unsigned k=0;k<68;k++)fprintf(f,"%s[%u,%u]",k?",":"",d.fragment_components[k].valid,d.fragment_components[k].component);
    fprintf(f,"]}\n");assert(!fclose(f));
    opengpu_native_joint_source_destroy(lease);
    for(unsigned s=0;s<4;s++){assert(!bos[d.code[s].handle].handle);if(full)assert(!bos[d.uniforms[s].defaults.handle].handle);}
    assert(!bos[handle].handle);
    for(unsigned k=0;k<5;k++){
        if(k<4){fail_request=DRM_IOCTL_OPENGPU_CREATE_BUFFER;fail_nth=k+1;fail_seen=0;}else map_error=1;
        assert(!opengpu_native_joint_program_create(pipe,link,2));fail_request=fail_nth=fail_seen=map_error=0;
    }
    pipe->destroy(pipe);screen->destroy(screen);for(unsigned i=1;i<next_bo;i++)assert(!bos[i].handle&&!bos[i].jobs&&!bos[i].maps);
    puts("NATIVE_JOINT_CPU linked-four-stage retained-source PASS; GPU=false submit=false");
}
