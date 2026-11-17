/* CPU compiler/operand inspection only. No image operation executes here. */
#include "opengpu_image_views.h"
#include "opengpu_shader_validator.h"

static void typed_image_operands(const struct opengpu_cs_program *p,unsigned target,unsigned format)
{
    uint32_t scalar[32]={0},vector[32]={0},sk=1,vk=0;
    unsigned loads=0,stores=0,controls=0,zs=0;
    unsigned kind=format==14 || format==22 || format==24 || format==26 ? 1 :
                  format==13 || format==21 || format==23 || format==25 ? 2 : 0;
    /* Inspect literal constant definitions, rather than executing a shader or
     * manufacturing load/store results. Image destinations become unknown. */
    for(unsigned n=0;n<p->words;n++){
        uint32_t w=p->code[n];unsigned op=w&127,rd=w>>7&31,a=w>>15&31,b=w>>20&31;
        unsigned fn=w>>26,f=w>>12&7;
        if(op==0x37){scalar[rd]=w&0xfffff000u;sk|=1u<<rd;}
        else if(op==0x13 && !f){
            if(sk&(1u<<a)){scalar[rd]=scalar[a]+(uint32_t)((int32_t)w>>20);sk|=1u<<rd;}
            else sk&=~(1u<<rd);
        }else if(op==0x57 && fn==0x17 && (f==0 || f==3 || f==4)){
            unsigned known=f==3 || (f==4 ? sk : vk)&(1u<<a);
            if(known){vector[rd]=f==3?(uint32_t)((int32_t)(a<<27)>>27):f==4?scalar[a]:vector[a];vk|=1u<<rd;}
            else vk&=~(1u<<rd);
        }else if(op==0x2b){
            if(fn==8 && f<2){
                assert((vk&(1u<<a)) && (vk&(1u<<b)));
                if(!f){assert(vector[a]==0 && vector[b]==(target|(kind<<3)|(1u<<5)|(format<<6)));controls++;}
                else {assert(vector[a]==(target==0 || target==5?0:11) && !vector[b]);zs++;}
            }
            if(fn==9 || fn==10){
                assert((vk&(1u<<a)) && (vk&(1u<<b)) && vector[a]==3 && vector[b]==(target==5?0:7));
                if(fn==9)loads++;else stores++;
            }
            if(fn==9 || fn==11)vk&=~(1u<<rd);
        }else if(op==0x57)vk&=~(1u<<rd);
        else if(op==0x03)sk&=~(1u<<rd);
        scalar[0]=0;sk|=1;
    }
    assert(loads==1 && stores==1 && controls==2 && zs==2);
}

static void typed_image_lowering(void)
{
    static const struct {unsigned tgsi,native;} targets[]={
        {TGSI_TEXTURE_BUFFER,5},{TGSI_TEXTURE_2D,0},{TGSI_TEXTURE_2D_ARRAY,1},
        {TGSI_TEXTURE_CUBE,2},{TGSI_TEXTURE_3D,3}};
    for(unsigned target=0;target<5;target++)for(unsigned format=0;format<13;format++){
        struct builder b;begin(&b,PIPE_SHADER_COMPUTE);
        append_decl(&b,TGSI_FILE_TEMPORARY,0,0,-1);
        struct tgsi_full_declaration decl=tgsi_default_full_declaration();
        decl.Declaration.File=TGSI_FILE_IMAGE;decl.Range.First=decl.Range.Last=0;
        decl.Image.Resource=targets[target].tgsi;decl.Image.Format=image_formats[format].pipe;decl.Image.Writable=1;
        assert(tgsi_build_full_declaration(&decl,b.t+b.h->HeaderSize+b.h->BodySize,b.h,TOKENS-b.h->HeaderSize-b.h->BodySize));
        immediate(&b,3,7,11,0);
        struct tgsi_src_register coord=I(0),data=T(0,0);
        coord.SwizzleY=data.SwizzleY=1;coord.SwizzleZ=data.SwizzleZ=2;coord.SwizzleW=data.SwizzleW=3;
        for(unsigned store=0;store<2;store++){
            struct tgsi_full_instruction i=tgsi_default_full_instruction();
            i.Instruction.Opcode=store?TGSI_OPCODE_STORE:TGSI_OPCODE_LOAD;
            i.Instruction.NumDstRegs=1;i.Instruction.NumSrcRegs=2;i.Instruction.Memory=1;
            i.Memory.Texture=targets[target].tgsi;i.Memory.Format=image_formats[format].pipe;
            i.Dst[0].Register.File=store?TGSI_FILE_IMAGE:TGSI_FILE_TEMPORARY;i.Dst[0].Register.WriteMask=15;
            i.Src[0].Register=store?coord:src(TGSI_FILE_IMAGE,0,0);i.Src[1].Register=store?data:coord;
            assert(tgsi_build_full_instruction(&i,b.t+b.h->HeaderSize+b.h->BodySize,b.h,TOKENS-b.h->HeaderSize-b.h->BodySize));
        }
        insn(&b,TGSI_OPCODE_END,0,0,0,I(0),I(0));
        struct opengpu_cs_program p,legacy;unsigned image_targets[4];
        assert(!opengpu_cs_emit(b.t,&legacy) && !opengpu_cs_emit_resources(b.t,NULL,&p,image_targets));
        assert(!memcmp(&p,&legacy,sizeof(p)) && image_targets[0]==targets[target].native);
        assert(p.image_reads==1 && p.image_writes==1 && p.image_format[0]==image_formats[format].native);
        assert(opengpu_compute_shader_validate_words_images(p.code,p.words,64,4,false,true));
        typed_image_operands(&p,targets[target].native,image_formats[format].native);
        unsigned pos=find_token(&b,TGSI_TOKEN_TYPE_INSTRUCTION,TGSI_OPCODE_LOAD);
        ((struct tgsi_instruction_memory *)(b.t+pos+1))->Texture=target?TGSI_TEXTURE_BUFFER:TGSI_TEXTURE_2D;
        memset(&p,0x73,sizeof(p));struct opengpu_cs_program saved=p;
        assert(opengpu_cs_emit(b.t,&p)<0 && !memcmp(&p,&saved,sizeof(p)));
    }
    puts("typed image compiler: 65 target/format load/store programs, literal XYZ/control operands and target mismatch rejection PASS (CPU only)");
}

static void typed_image_mappings(void)
{
    struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
    struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
    static const enum pipe_texture_target targets[]={PIPE_TEXTURE_2D,PIPE_TEXTURE_2D_ARRAY,PIPE_TEXTURE_CUBE,PIPE_TEXTURE_3D};
    for(unsigned target=0;target<4;target++)for(unsigned format=0;format<13;format++){
        struct pipe_resource t={.target=targets[target],.format=image_formats[format].pipe,.width0=8,
            .height0=target==2?8:4,.depth0=target==3?4:1,.array_size=target==1?3:target==2?6:1,
            .last_level=2,.bind=PIPE_BIND_SHADER_IMAGE};
        struct pipe_resource *r=s->resource_create(s,&t);assert(r);
        struct pipe_sampler_view unsupported={.target=PIPE_TEXTURE_2D,.format=t.format};
        assert(!p->create_sampler_view(p,r,&unsupported));
        struct opengpu_resource *res=opengpu_resource(r);
        unsigned handle=pipe_opengpu_resource_handle(res->gpu),plane=res->image.level[1].planes-1;
        struct pipe_image_view view={.resource=r,.format=t.format,.access=PIPE_IMAGE_ACCESS_READ_WRITE};
        view.u.tex.level=1;view.u.tex.first_layer=view.u.tex.last_layer=plane;
        p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,1,0,&view);assert(r->reference.count==2);
        struct pipe_box box={.x=1,.y=1,.z=(int)plane,.width=1,.height=1,.depth=1};
        struct pipe_transfer *transfer=NULL;
        uint8_t *base=pipe_opengpu_resource_map(res->gpu);
        uint8_t *map=p->texture_map(p,r,1,PIPE_MAP_WRITE,&box,&transfer);assert(map && transfer);
        assert(map==base+res->image.level[1].offset+plane*res->image.level[1].plane_pitch+64+image_formats[format].bytes);
        assert(transfer->stride==64 && transfer->layer_stride==res->image.level[1].plane_pitch && r->reference.count==3);
        memset(map,0x35,image_formats[format].bytes);
        pipe_resource_reference(&r,NULL);
        p->set_shader_images(p,PIPE_SHADER_COMPUTE,0,0,4,NULL);
        assert(transfer->resource->reference.count==1 && bos[handle].data);
        p->texture_unmap(p,transfer);assert(!bos[handle].data);
    }
    p->destroy(p);s->destroy(s);no_leaks();
    puts("typed image callbacks: 52 mip/plane mappings and source/transfer lifetime cases PASS (CPU only)");
}
