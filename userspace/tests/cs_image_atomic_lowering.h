/* CPU compiler/metadata/operand checks. No image atomic operation executes. */
static void image_atomic_operands(const struct opengpu_cs_program *p,unsigned target,
    unsigned format,unsigned operation,unsigned operand,unsigned compare)
{
    uint32_t scalar[32]={0},vector[32]={0},sk=1,vk=0;
    unsigned pairs=0,atomics=0;
    for(unsigned n=0;n<p->words;n++){
        uint32_t w=p->code[n];unsigned op=w&127,rd=w>>7&31,a=w>>15&31,b=w>>20&31;
        unsigned fn=w>>26,f=w>>12&7;
        if(op==0x37){scalar[rd]=w&0xfffff000u;sk|=1u<<rd;}
        else if(op==0x13 && !f){
            if(sk&(1u<<a)){scalar[rd]=scalar[a]+(uint32_t)((int32_t)w>>20);sk|=1u<<rd;}
            else sk&=~(1u<<rd);
        }else if(op==0x57 && fn==0x17 && (f==0 || f==3 || f==4)){
            unsigned known=f==3 || (f==4?sk:vk)&(1u<<a);
            if(known){vector[rd]=f==3?(uint32_t)((int32_t)(a<<27)>>27):f==4?scalar[a]:vector[a];vk|=1u<<rd;}
            else vk&=~(1u<<rd);
        }else if(op==0x57 && fn==0 && f==4 && a==0 && (w&(1u<<25))){
            /* vadd.vx with x0 is the emitter's literal-preserving copy. */
            if(vk&(1u<<b)){vector[rd]=vector[b];vk|=1u<<rd;}
            else vk&=~(1u<<rd);
        }else if(op==0x2b){
            if(fn==8){
                assert(f<3 && (vk&(1u<<a)) && (vk&(1u<<b)) && !(pairs&(1u<<f)));
                if(!f)assert(vector[a]==0 && vector[b]==(target|((format==14?1u:format==13?2u:0u)<<3)|(1u<<5)|(format<<6)));
                if(f==1)assert(vector[a]==(target==0 || target==5?0:11) && !vector[b]);
                if(f==2)assert(vector[a]==operand && vector[b]==compare);
                pairs|=1u<<f;
            }else if(fn==23){
                assert(f==operation && pairs==7 && (vk&(1u<<a)) && (vk&(1u<<b)) &&
                    vector[a]==3 && vector[b]==(target==5?0:7));
                atomics++;vk&=~(1u<<rd);
            }
        }else if(op==0x57)vk&=~(1u<<rd);
        else if(op==0x03)sk&=~(1u<<rd);
        scalar[0]=0;sk|=1;
    }
    assert(pairs==7 && atomics==1);
}

static void image_atomic_lowering(void)
{
    static const struct {unsigned tgsi,native;} targets[]={
        {TGSI_TEXTURE_BUFFER,5},{TGSI_TEXTURE_2D,0},{TGSI_TEXTURE_2D_ARRAY,1},
        {TGSI_TEXTURE_CUBE,2},{TGSI_TEXTURE_3D,3}};
    static const unsigned ops[]={TGSI_OPCODE_ATOMXCHG,TGSI_OPCODE_ATOMUADD,TGSI_OPCODE_ATOMXOR,
        TGSI_OPCODE_ATOMOR,TGSI_OPCODE_ATOMAND,TGSI_OPCODE_ATOMIMIN,TGSI_OPCODE_ATOMIMAX,TGSI_OPCODE_ATOMCAS};
    unsigned cases=0,boundaries=0,floating=0;
    for(unsigned target=0;target<5;target++)for(unsigned signedness=0;signedness<2;signedness++)
    for(unsigned operation=0;operation<8;operation++)for(unsigned alias=0;alias<2;alias++){
        struct builder b;begin(&b,PIPE_SHADER_COMPUTE);
        append_decl(&b,TGSI_FILE_TEMPORARY,0,0,-1);
        struct tgsi_full_declaration decl=tgsi_default_full_declaration();
        decl.Declaration.File=TGSI_FILE_IMAGE;decl.Range.First=decl.Range.Last=0;
        decl.Image.Resource=targets[target].tgsi;
        decl.Image.Format=signedness?PIPE_FORMAT_R32_SINT:PIPE_FORMAT_R32_UINT;decl.Image.Writable=1;
        unsigned declaration=b.h->HeaderSize+b.h->BodySize;
        assert(tgsi_build_full_declaration(&decl,b.t+b.h->HeaderSize+b.h->BodySize,b.h,TOKENS-b.h->HeaderSize-b.h->BodySize));
        immediate(&b,3,7,11,0);
        unsigned value_position=b.h->HeaderSize+b.h->BodySize;
        immediate(&b,0x87654321,0x12345678,0,0);
        struct tgsi_src_register coord=alias?T(0,0):I(0);
        coord.SwizzleY=1;coord.SwizzleZ=2;coord.SwizzleW=3;
        if(alias){
            struct tgsi_src_register initial=I(0);initial.SwizzleY=1;initial.SwizzleZ=2;initial.SwizzleW=3;
            insn(&b,TGSI_OPCODE_MOV,TGSI_FILE_TEMPORARY,0,15,initial,I(0));
        }
        struct tgsi_full_instruction i=tgsi_default_full_instruction();
        i.Instruction.Opcode=operation==5 && !signedness?TGSI_OPCODE_ATOMUMIN:
            operation==6 && !signedness?TGSI_OPCODE_ATOMUMAX:ops[operation];
        i.Instruction.NumDstRegs=1;i.Instruction.NumSrcRegs=operation==7?4:3;i.Instruction.Memory=1;
        i.Memory.Texture=targets[target].tgsi;i.Memory.Format=decl.Image.Format;
        i.Dst[0].Register.File=TGSI_FILE_TEMPORARY;i.Dst[0].Register.WriteMask=1;
        i.Src[0].Register=src(TGSI_FILE_IMAGE,0,0);i.Src[1].Register=coord;
        i.Src[2].Register=alias?T(0,operation==7?2:1):src(TGSI_FILE_IMMEDIATE,1,0);
        if(operation==7)i.Src[3].Register=alias?T(0,3):src(TGSI_FILE_IMMEDIATE,1,1);
        unsigned atomic_position=b.h->HeaderSize+b.h->BodySize;
        assert(tgsi_build_full_instruction(&i,b.t+atomic_position,b.h,TOKENS-atomic_position));
        insn(&b,TGSI_OPCODE_END,0,0,0,I(0),I(0));
        struct opengpu_cs_program p,legacy;struct opengpu_cs_image_info info;
        int result=opengpu_cs_emit_image_info(b.t,NULL,&p,&info);
        if(result)fprintf(stderr,"atomic compile rejected target=%u signed=%u operation=%u alias=%u\n",target,signedness,operation,alias);
        assert(!result && !opengpu_cs_emit(b.t,&legacy));
        assert(!memcmp(&p,&legacy,sizeof(p)) && info.atomics==1 && !info.float_exchange && info.targets[0]==targets[target].native);
        assert(p.image_reads==1 && p.image_writes==1 && !p.image_queries);
        assert(opengpu_compute_shader_validate_words_image_atomics(p.code,p.words,64,4,false,false,false,false,true));
        assert(!opengpu_compute_shader_validate_words_image_atomics(p.code,p.words,64,4,false,false,false,false,false));
        image_atomic_operands(&p,targets[target].native,signedness?13:14,operation,
            alias?(operation==7?0:7):operation==7?0x12345678:0x87654321,
            operation==7?(alias?11:0x87654321):0);
        struct opengpu_cs_program saved=p;struct opengpu_cs_image_info saved_info=info;
        struct tgsi_instruction_memory *memory=(struct tgsi_instruction_memory *)(b.t+atomic_position+1);
        memory->Texture=target?TGSI_TEXTURE_BUFFER:TGSI_TEXTURE_2D;
        assert(opengpu_cs_emit_image_info(b.t,NULL,&p,&info)<0 && !memcmp(&p,&saved,sizeof(p)) &&
            !memcmp(&info,&saved_info,sizeof(info)));
        memory->Texture=targets[target].tgsi;memory->Qualifier=TGSI_MEMORY_VOLATILE;
        assert(opengpu_cs_emit_image_info(b.t,NULL,&p,&info)<0 && !memcmp(&p,&saved,sizeof(p)));
        memory->Qualifier=0;
        ((struct tgsi_declaration_image *)(b.t+declaration+2))->Writable=0;
        assert(opengpu_cs_emit_image_info(b.t,NULL,&p,&info)<0);
        ((struct tgsi_declaration_image *)(b.t+declaration+2))->Writable=1;
        ((struct tgsi_declaration_image *)(b.t+declaration+2))->Format=PIPE_FORMAT_R32_FLOAT;
        memory->Format=PIPE_FORMAT_R32_FLOAT;
        if(operation)assert(opengpu_cs_emit_image_info(b.t,NULL,&p,&info)<0);
        else{
            struct opengpu_cs_program fp;struct opengpu_cs_image_info fp_info;
            struct tgsi_immediate *literal=(struct tgsi_immediate *)(b.t+value_position);
            unsigned datatype=literal->DataType;literal->DataType=TGSI_IMM_FLOAT32;
            assert(!opengpu_cs_emit_image_info(b.t,NULL,&fp,&fp_info) && fp_info.float_exchange==1 && fp_info.atomics==1);
            image_atomic_operands(&fp,targets[target].native,29,0,alias?7:0x87654321,0);floating++;
            if(!target && !signedness && !alias)for(unsigned mask=0;mask<16;mask++){
                features=OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE|OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_BACKINGS|
                    (mask&1?OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGES:0)|
                    (mask&2?OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_IMAGES:0)|
                    (mask&4?OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_ATOMICS:0)|
                    (mask&8?OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_FLOAT_EXCHANGE:0);
                struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
                assert(!!pipe_opengpu_screen_compute_image_float_exchange(opengpu_screen_gpu(s))==((mask&13)==13));
                struct pipe_context *pipe=s->context_create(s,NULL,0);assert(pipe);
                struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=b.t};
                void *cs=pipe->create_compute_state(pipe,&state);assert(!!cs==(mask==15));
                if(cs)pipe->delete_compute_state(pipe,cs);
                pipe->destroy(pipe);s->destroy(s);no_leaks();
            }
            literal->DataType=datatype;
        }
        ((struct tgsi_declaration_image *)(b.t+declaration+2))->Format=decl.Image.Format;
        memory->Format=decl.Image.Format;
        if(operation==5 || operation==6){
            struct tgsi_instruction *atomic=(struct tgsi_instruction *)(b.t+atomic_position);
            atomic->Opcode=signedness?(operation==5?TGSI_OPCODE_ATOMUMIN:TGSI_OPCODE_ATOMUMAX):ops[operation];
            assert(opengpu_cs_emit_image_info(b.t,NULL,&p,&info)<0);
            atomic->Opcode=i.Instruction.Opcode;
        }
        if(!target && !signedness && operation==1 && !alias){
            static const uint32_t literals[]={15,16,0xfffffff0u,0xffffffefu};
            for(unsigned k=0;k<4;k++){
                struct opengpu_cs_program literal_program;struct opengpu_cs_image_info literal_info;
                memcpy(b.t+value_position+1,&literals[k],sizeof(literals[k]));
                assert(!opengpu_cs_emit_image_info(b.t,NULL,&literal_program,&literal_info));
                assert(opengpu_compute_shader_validate_words_image_atomics(literal_program.code,literal_program.words,64,4,false,false,false,false,true));
                image_atomic_operands(&literal_program,5,14,1,literals[k],0);boundaries++;
            }
            const uint32_t original=0x87654321;memcpy(b.t+value_position+1,&original,sizeof(original));
            for(unsigned mask=0;mask<8;mask++){
                features=OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE|OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_BACKINGS|
                    (mask&1?OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGES:0)|
                    (mask&2?OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_IMAGES:0)|
                    (mask&4?OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_ATOMICS:0);
                struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
                assert(!!pipe_opengpu_screen_compute_image_atomics(opengpu_screen_gpu(s))==((mask&5)==5));
                struct pipe_context *pipe=s->context_create(s,NULL,0);assert(pipe);
                struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=b.t};
                void *cs=pipe->create_compute_state(pipe,&state);assert(!!cs==(mask==7));
                if(cs)pipe->delete_compute_state(pipe,cs);
                pipe->destroy(pipe);s->destroy(s);no_leaks();
            }
        }
        cases++;
    }
    /* Keep XYZ and the raw float operand live while more than twenty other
     * components are defined and consumed. Their dead slots may be recycled;
     * the final exchange aliases its coordinate destination and must still
     * receive the original four components. */
    {
        struct builder b;begin(&b,PIPE_SHADER_COMPUTE);
        append_decl(&b,TGSI_FILE_TEMPORARY,0,6,-1);
        append_decl(&b,TGSI_FILE_BUFFER,0,0,-1);
        struct tgsi_full_declaration decl=tgsi_default_full_declaration();
        decl.Declaration.File=TGSI_FILE_IMAGE;decl.Range.First=decl.Range.Last=0;
        decl.Image.Resource=TGSI_TEXTURE_2D_ARRAY;decl.Image.Format=PIPE_FORMAT_R32_FLOAT;decl.Image.Writable=1;
        assert(tgsi_build_full_declaration(&decl,b.t+b.h->HeaderSize+b.h->BodySize,b.h,TOKENS-b.h->HeaderSize-b.h->BodySize));
        immediate(&b,3,7,11,0);immediate(&b,0x87654321,0x12345678,0,0);
        struct tgsi_src_register coord=I(0);coord.SwizzleY=1;coord.SwizzleZ=2;
        insn(&b,TGSI_OPCODE_MOV,TGSI_FILE_TEMPORARY,0,7,coord,I(0));
        insn(&b,TGSI_OPCODE_MOV,TGSI_FILE_TEMPORARY,0,8,src(TGSI_FILE_IMMEDIATE,1,0),I(0));
        for(unsigned t=1;t<=6;t++)for(unsigned c=0;c<4;c++){
            insn(&b,TGSI_OPCODE_MOV,TGSI_FILE_TEMPORARY,t,1u<<c,src(TGSI_FILE_IMMEDIATE,1,1),I(0));
            insn(&b,TGSI_OPCODE_STORE,TGSI_FILE_BUFFER,0,1,I(3),T(t,c));
        }
        struct tgsi_full_instruction i=tgsi_default_full_instruction();
        i.Instruction.Opcode=TGSI_OPCODE_ATOMXCHG;i.Instruction.Memory=1;
        i.Instruction.NumDstRegs=1;i.Instruction.NumSrcRegs=3;
        i.Memory.Texture=TGSI_TEXTURE_2D_ARRAY;i.Memory.Format=PIPE_FORMAT_R32_FLOAT;
        i.Dst[0].Register.File=TGSI_FILE_TEMPORARY;i.Dst[0].Register.WriteMask=1;
        i.Src[0].Register=src(TGSI_FILE_IMAGE,0,0);i.Src[1].Register=T(0,0);
        i.Src[1].Register.SwizzleY=1;i.Src[1].Register.SwizzleZ=2;i.Src[1].Register.SwizzleW=3;
        i.Src[2].Register=T(0,3);
        assert(tgsi_build_full_instruction(&i,b.t+b.h->HeaderSize+b.h->BodySize,b.h,TOKENS-b.h->HeaderSize-b.h->BodySize));
        insn(&b,TGSI_OPCODE_END,0,0,0,I(0),I(0));
        struct opengpu_cs_program p;struct opengpu_cs_image_info info;
        assert(!opengpu_cs_emit_image_info(b.t,NULL,&p,&info) && info.float_exchange==1);
        assert(opengpu_compute_shader_validate_words_image_atomics(p.code,p.words,64,4,false,false,false,true,true));
        image_atomic_operands(&p,1,29,0,0x87654321,0);
        puts("image float register lifetime: 24 consumed components, preserved live XYZ/value and aliased exchange destination PASS (CPU only)");
    }
    assert(cases==160 && boundaries==4 && floating==20);
    puts("image atomic compiler: 160 integer/20 float-exchange target/alias programs, 4 immediate boundaries, literal XYZ/value/CAS order, unsupported float ops and feature gates PASS (CPU only)");
}
