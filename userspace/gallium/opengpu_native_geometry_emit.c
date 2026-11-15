/* SPDX-License-Identifier: MIT */
#include "opengpu_native_geometry_emit.h"
#include "pipe/p_defines.h"
#include "tgsi/tgsi_parse.h"
#include <stdlib.h>
#include <string.h>
#define LIMIT 64u
struct value {unsigned known;uint32_t value;};
struct sem {unsigned name,index,mask;};
struct build {
    struct opengpu_native_geometry_program p;
    struct opengpu_native_tess_input_map maps[2];
    struct opengpu_native_tess_packing packed[2];
    struct sem sem[2][LIMIT],sys[32];
    unsigned declared[32],reg[32][4],defined[32];
    struct value values[32][4];
    unsigned output_needed[64],output_reg[64][4],output_defined[64];
    struct value output_values[64][4];
    unsigned addr_decl[2],addr_reg[2],addr_defined[2],next;
    struct value addr_value[2];
    unsigned constants[13],first[13],last[13];
    uint32_t immediate[64][4];unsigned nimm,failed,properties;
    struct tgsi_full_instruction *instructions;unsigned count;
};
static void put(struct build *b,uint32_t w){if(b->p.words==512)b->failed=1;else b->p.code[b->p.words++]=w;}
static uint32_t alu(unsigned fn,unsigned f3,unsigned rd,unsigned a,unsigned z){return fn<<25|z<<20|a<<15|f3<<12|rd<<7|0x33u;}
static uint32_t geo(unsigned fn,unsigned rd,unsigned a,unsigned z){return fn<<25|z<<20|a<<15|rd<<7|0x0bu;}
static void move(struct build *b,unsigned d,unsigned s){put(b,s<<15|d<<7|0x13u);}
static void constant(struct build *b,unsigned r,uint32_t value){unsigned hi=(value+0x800u)&0xfffff000u,lo=value&4095u;
    if(hi)put(b,hi|r<<7|0x37u);
    if(lo||!hi)put(b,lo<<20|(hi?r:0)<<15|r<<7|0x13u);
    }
static unsigned reg(struct build *b){if(b->next>21){b->failed=1;return 2;}return b->next++;}
static unsigned pop(unsigned n){unsigned v=0;for(;n;n>>=1)v+=n&1;return v;}
static unsigned swizzle(const struct tgsi_src_register *s,unsigned c){return c==0?s->SwizzleX:c==1?s->SwizzleY:c==2?s->SwizzleZ:s->SwizzleW;}
static uint32_t branch(unsigned f,unsigned a,unsigned z,int bytes){uint32_t n=(uint32_t)bytes&8191u;
    return (n>>12)<<31|((n>>11)&1u)<<7|((n>>5)&63u)<<25|((n>>1)&15u)<<8|z<<20|a<<15|f<<12|0x63u;}
static void patch_branch(struct build *b,unsigned at,unsigned f,unsigned a,unsigned z){unsigned delta=(b->p.words-at)*4u;
    if(at>=b->p.words||delta>4092u)b->failed=1;else b->p.code[at]=branch(f,a,z,(int)delta);}
/* Unknown high/full-width indices are checked BEFORE adding a static base.
 * The failure operand stays UINT32_MAX, never a wrapped in-range address. */
static void bounded_add(struct build *b,unsigned rd,unsigned src,unsigned base,unsigned limit){
    unsigned check=b->p.words;constant(b,31,limit-base);put(b,0);
    if(base){constant(b,31,base);put(b,alu(0,0,rd,src,31));}else move(b,rd,src);
    unsigned jump=b->p.words;put(b,0);b->p.code[check+1]=branch(7,src,31,(int)((b->p.words-check-1)*4));
    constant(b,rd,~0u);patch_branch(b,jump,0,0,0);
}
static int packets(const struct tgsi_token *tokens,unsigned n){
    struct tgsi_header h;struct tgsi_processor p;if(!tokens||n<3||n>4098)return -1;
    memcpy(&h,tokens,4);memcpy(&p,tokens+1,4);
    if(h.HeaderSize!=2||h.BodySize!=n-2||p.Padding||p.Processor!=PIPE_SHADER_GEOMETRY)return -1;
    for(unsigned at=2;at<n;){struct tgsi_token t;memcpy(&t,tokens+at,4);unsigned size=t.NrTokens;
        if(t.Type==TGSI_TOKEN_TYPE_DECLARATION){struct tgsi_declaration d;memcpy(&d,tokens+at,4);
            if(d.Atomic||d.MemType||d.Padding||d.Array||d.Local||d.Interpolate||size!=2u+d.Dimension+d.Semantic)return -1;
        }else if(t.Type==TGSI_TOKEN_TYPE_IMMEDIATE){struct tgsi_immediate i;memcpy(&i,tokens+at,4);size=i.NrTokens;
            if(size!=5||i.Padding||(i.DataType!=TGSI_IMM_UINT32&&i.DataType!=TGSI_IMM_INT32&&i.DataType!=TGSI_IMM_FLOAT32))return -1;
        }else if(t.Type==TGSI_TOKEN_TYPE_PROPERTY){struct tgsi_property p2;memcpy(&p2,tokens+at,4);if(size!=2||p2.Padding)return -1;
        }else if(t.Type==TGSI_TOKEN_TYPE_INSTRUCTION){struct tgsi_instruction i;memcpy(&i,tokens+at,4);size=i.NrTokens+1;
            if(i.Texture||i.Memory||i.Padding||i.Saturate||i.NumDstRegs>1||i.NumSrcRegs>3||size>n-at)return -1;
            unsigned pos=at+1+i.Label;
            for(unsigned k=0;k<(unsigned)i.NumDstRegs+i.NumSrcRegs;k++){
                if(pos>=at+size)return -1;
                unsigned dim;

                if(k<i.NumDstRegs){struct tgsi_dst_register d;memcpy(&d,tokens+pos++,4);if(d.Padding||d.Indirect||d.Dimension)return -1;dim=0;}
                else {struct tgsi_src_register s;memcpy(&s,tokens+pos++,4);if(s.Absolute||s.Negate)return -1;dim=s.Dimension;
                    if(s.Indirect){struct tgsi_ind_register x;if(pos>=at+size)return -1;memcpy(&x,tokens+pos++,4);
                        if((s.File!=TGSI_FILE_INPUT&&s.File!=TGSI_FILE_CONSTANT)||x.File!=TGSI_FILE_ADDRESS||x.Index<0||x.Index>1||x.Swizzle||x.ArrayID)return -1;}}
                if(dim){struct tgsi_dimension d;if(pos>=at+size)return -1;memcpy(&d,tokens+pos++,4);
                    if(d.Dimension||d.Padding||d.Index<0)return -1;
                    if(d.Indirect){struct tgsi_ind_register x;if(pos>=at+size)return -1;memcpy(&x,tokens+pos++,4);
                        if(x.File!=TGSI_FILE_ADDRESS||x.Index<0||x.Index>1||x.Swizzle||x.ArrayID)return -1;}}
            }if(pos!=at+size)return -1;
        }else return -1;
        if(!size||size>n-at)return -1;
        at+=size;

    }return 0;
}
static int pack(struct build *b,unsigned output){
    struct opengpu_native_tess_input_map copy=b->maps[output];unsigned count=0,builtins=0;
    for(unsigned i=copy.count;i<64;i++){
        const struct opengpu_native_tess_input_span zero={0};
        if(memcmp(copy.spans+i,&zero,sizeof(zero)))return -1;
    }
    for(unsigned i=0;i<copy.count;i++){
        struct opengpu_native_tess_input_span s=copy.spans[i];
        if(s.first>=64||!s.slots||s.slots>64-s.first||!s.mask||s.mask>15||s.per_vertex>1||s.compact>1)return -1;
        if(s.semantic==TGSI_SEMANTIC_LAYER||s.semantic==TGSI_SEMANTIC_PRIMID){
            if(s.index||s.slots!=1||s.mask!=1||s.compact||s.per_vertex)return -1;
            if(output){unsigned bit=s.semantic==TGSI_SEMANTIC_LAYER?1u:2u;if(builtins&bit)return -1;builtins|=bit;}
            continue;
        }
        if(s.per_vertex!=!output)return -1;
        if(s.semantic!=TGSI_SEMANTIC_POSITION&&s.semantic!=TGSI_SEMANTIC_GENERIC)return -1;
        copy.spans[count++]=s;
    }
    memset(copy.spans+count,0,(64-count)*sizeof(copy.spans[0]));copy.count=count;
    if(opengpu_native_tess_pack(&copy,b->packed+output)||b->packed[output].patch_components)return -1;
    unsigned n=b->packed[output].vertex_components;
    if(output){b->p.layout.output_components=n;b->p.layout.builtin_output_mask=builtins;if(!b->packed[1].position)return -1;}
    else b->p.layout.input_components=n;
    for(unsigned i=0;i<16;i++)if(b->packed[output].vertex[i].mask){
        if(output)b->p.layout.output_varyings=i+1;else b->p.layout.input_varyings=i+1;
    }
    return n+pop(builtins)<=64?0:-1;
}
static int component(struct build *b,unsigned output,struct sem s,unsigned c,unsigned *result){
    if(!(s.mask&(1u<<c)))return -1;
    if(s.name==TGSI_SEMANTIC_POSITION){if(s.index||!b->packed[output].position)return -1;*result=c;return 0;}
    if(s.name!=TGSI_SEMANTIC_GENERIC||s.index>=16)return -1;
    const struct opengpu_native_tess_packed_slot *p=b->packed[output].vertex+s.index;
    if(!(p->mask&(1u<<c)))return -1;
    *result=p->base+pop(p->mask&((1u<<c)-1));
    return 0;

}
static int declaration(struct build *b,const struct tgsi_full_declaration *d){
    unsigned f=d->Range.First,l=d->Range.Last,m=d->Declaration.UsageMask,file=d->Declaration.File;
    if(!m||f>l||(d->Declaration.Semantic&&(d->Semantic.StreamX||d->Semantic.StreamY||d->Semantic.StreamZ||d->Semantic.StreamW)))return -1;
    if(file==TGSI_FILE_CONSTANT){unsigned slot=d->Dim.Index2D;
        if(!d->Declaration.Dimension||d->Declaration.Semantic||d->Dim.Padding||slot>12||b->constants[slot]||l>=(slot?1024u:256u))return -1;
        b->constants[slot]=m;b->first[slot]=f;b->last[slot]=l;return 0;}
    if(file==TGSI_FILE_TEMPORARY){if(l>=32||d->Declaration.Dimension||d->Declaration.Semantic)return -1;
        for(unsigned i=f;i<=l;i++){if(b->declared[i])return -1;b->declared[i]=m;}return 0;}
    if(file==TGSI_FILE_ADDRESS){if(l>1||!(m&1u)||d->Declaration.Dimension||d->Declaration.Semantic)return -1;
        for(unsigned i=f;i<=l;i++){if(b->addr_decl[i])return -1;b->addr_decl[i]=1;}return 0;}
    if(file==TGSI_FILE_SYSTEM_VALUE){if(l>=32||!d->Declaration.Semantic||d->Declaration.Dimension||f!=l||!(m&1u)||d->Semantic.Index)return -1;
        if(d->Semantic.Name!=TGSI_SEMANTIC_INVOCATIONID&&d->Semantic.Name!=TGSI_SEMANTIC_PRIMID)return -1;
        if(b->sys[f].mask)return -1;
        b->sys[f]=(struct sem){d->Semantic.Name,0,m};
        return 0;
        }
    if(file!=TGSI_FILE_INPUT&&file!=TGSI_FILE_OUTPUT)return -1;
    unsigned out=file==TGSI_FILE_OUTPUT;
    /* GS input arrays are implicit in the GEOM processor declaration.
     * The source operand, not DCL's constant-buffer dimension bit, carries
     * the actual vertex dimension (Mesa22.3.6 IN[][slot]). */
    if(l>=LIMIT||!d->Declaration.Semantic||d->Declaration.Dimension)return -1;
    for(unsigned i=f;i<=l;i++){
        if(b->sem[out][i].mask)return -1;
        b->sem[out][i]=(struct sem){d->Semantic.Name,d->Semantic.Index+i-f,m};
        /* nir_to_tgsi represents the scalar GS PrimitiveID input as an IN
         * declaration, after its original NIR variable became a sysval. It
         * is never a per-vertex payload span, regardless of the dump's IN[]. */
        if(!out&&d->Semantic.Name==TGSI_SEMANTIC_PRIMID){
            if(f!=l||d->Semantic.Index||!(m&1u))return -1;
            b->sem[0][i].mask=1;continue;
        }
        unsigned original=0;
        for(unsigned q=0;q<b->maps[out].count;q++){
            const struct opengpu_native_tess_input_span *v=b->maps[out].spans+q;
            if(v->semantic==d->Semantic.Name&&i>=v->first&&i-v->first<v->slots&&
                v->index+i-v->first==b->sem[out][i].index)original++;
        }
        if(original!=1)return -1;
        for(unsigned c=0;c<4;c++)if(m&(1u<<c)){
            unsigned k;if(component(b,out,b->sem[out][i],c,&k)){
                if(out&&(d->Semantic.Name==TGSI_SEMANTIC_LAYER||d->Semantic.Name==TGSI_SEMANTIC_PRIMID)&&!c)continue;
                /* vec4 TGSI declaration padding is legal only with real NIR
                 * span proof; operands must still pass component(). */
                unsigned covered=0;for(unsigned q=0;q<b->maps[out].count;q++){
                    const struct opengpu_native_tess_input_span *s=b->maps[out].spans+q;
                    if(s->semantic==d->Semantic.Name&&b->sem[out][i].index>=s->index&&b->sem[out][i].index-s->index<s->slots&&s->mask<15)covered++;}
                if(covered!=1)return -1;
                continue;

            }
            unsigned raw=d->Semantic.Name==TGSI_SEMANTIC_POSITION?c:4+4*b->sem[out][i].index+c;
            if(out)b->p.layout.output_sources[raw].valid=1,b->p.layout.output_sources[raw].source=k;
            else b->p.layout.input_sources[k]=raw;
        }
    }return 0;
}
static int property(struct build *b,const struct tgsi_full_property *p){
    unsigned name=p->Property.PropertyName,v=p->u[0].Data,bit=0;
    switch(name){
    case TGSI_PROPERTY_GS_INPUT_PRIM:bit=1;b->p.layout.input_primitive=v;
        switch(v){case PIPE_PRIM_POINTS:b->p.layout.input_vertices=1;break;case PIPE_PRIM_LINES:b->p.layout.input_vertices=2;break;
        case PIPE_PRIM_TRIANGLES:b->p.layout.input_vertices=3;break;case PIPE_PRIM_LINES_ADJACENCY:b->p.layout.input_vertices=4;break;
        case PIPE_PRIM_TRIANGLES_ADJACENCY:b->p.layout.input_vertices=6;break;default:return -1;}break;
    case TGSI_PROPERTY_GS_OUTPUT_PRIM:bit=2;
        if(v==PIPE_PRIM_POINTS)b->p.layout.output_topology=0;else if(v==PIPE_PRIM_LINE_STRIP)b->p.layout.output_topology=1;
        else if(v==PIPE_PRIM_TRIANGLE_STRIP)b->p.layout.output_topology=2;
        else return -1;
        break;

    case TGSI_PROPERTY_GS_MAX_OUTPUT_VERTICES:bit=4;if(!v||v>256)return -1;b->p.layout.max_vertices=v;break;
    case TGSI_PROPERTY_GS_INVOCATIONS:bit=8;if(!v||v>32)return -1;b->p.layout.invocations=v;break;
    case TGSI_PROPERTY_NEXT_SHADER:bit=16;if(v!=PIPE_SHADER_FRAGMENT)return -1;break;
    default:return -1;
    }if(b->properties&bit)return -1;b->properties|=bit;return 0;
}
static int source(struct build *b,const struct tgsi_full_src_register *s,unsigned channel,unsigned to,struct value *known){
    unsigned c=swizzle(&s->Register,channel),file=s->Register.File;int index=s->Register.Index;*known=(struct value){0};
    if(index<0)return -1;
    if(file==TGSI_FILE_IMMEDIATE){if((unsigned)index>=b->nimm||s->Register.Dimension||s->Register.Indirect)return -1;
        known->known=1;known->value=b->immediate[index][c];constant(b,to,known->value);return 0;}
    if(file==TGSI_FILE_TEMPORARY){if(index>=32||s->Register.Dimension||s->Register.Indirect||!(b->defined[index]&(1u<<c)))return -1;
        *known=b->values[index][c];move(b,to,b->reg[index][c]);return 0;}
    if(file==TGSI_FILE_OUTPUT){
        if(index>=64||s->Register.Dimension||s->Register.Indirect||!(b->output_defined[index]&(1u<<c)))return -1;
        *known=b->output_values[index][c];move(b,to,b->output_reg[index][c]);return 0;
    }
    if(file==TGSI_FILE_SYSTEM_VALUE){if(index>=32||c||!b->sys[index].mask||s->Register.Dimension||s->Register.Indirect)return -1;
        constant(b,30,b->sys[index].name==TGSI_SEMANTIC_INVOCATIONID?0:1);put(b,geo(37,to,30,0));return 0;}
    if(file==TGSI_FILE_CONSTANT){unsigned slot=s->Dimension.Index;
        if(!s->Register.Dimension||s->Dimension.Indirect||slot>12||!(b->constants[slot]&(1u<<c))||
            (unsigned)index<b->first[slot]||(unsigned)index>b->last[slot])return -1;
        unsigned words=(unsigned)index*4+c;
        if(s->Register.Indirect){unsigned a=s->Indirect.Index;if(!b->addr_defined[a])return -1;
            /* Keep the full original register index until after checking the
             * declared uniform range; fn7 independently checks the real view. */
            bounded_add(b,28,b->addr_reg[a],(unsigned)index,b->last[slot]+1);
            constant(b,31,b->first[slot]);unsigned bad=b->p.words;put(b,0);
            put(b,4u<<20|28u<<15|1u<<12|28u<<7|0x13u); /* byte stride16 */
            if(c)put(b,(c*4u)<<20|28u<<15|28u<<7|0x13u);
            unsigned jump=b->p.words;put(b,0);b->p.code[bad]=branch(6,28,31,(int)((b->p.words-bad)*4));
            constant(b,28,~0u);patch_branch(b,jump,0,0,0);move(b,31,28);
            words=(b->last[slot]+1)*4;
        }else constant(b,31,words*4);
        constant(b,30,slot?slot-1:31);put(b,geo(7,to,30,31));
        unsigned bytes=s->Register.Indirect?words*4:words*4+4;
        if(b->p.constant_bytes[slot]<bytes)b->p.constant_bytes[slot]=bytes;
        return 0;
    }
    if(file==TGSI_FILE_INPUT&&index<64&&b->sem[0][index].name==TGSI_SEMANTIC_PRIMID){
        if(c||s->Register.Dimension||s->Register.Indirect||b->sem[0][index].index)return -1;
        constant(b,30,1);put(b,geo(37,to,30,0));return 0;
    }
    if(file!=TGSI_FILE_INPUT||index>=64||!s->Register.Dimension)return -1;
    struct sem sem=b->sem[0][index];unsigned component_index;
    if(component(b,0,sem,c,&component_index))return -1;
    if(s->Register.Indirect){
        const struct opengpu_native_tess_input_span *span=NULL;
        for(unsigned i=0;i<b->maps[0].count;i++){
            const struct opengpu_native_tess_input_span *q=b->maps[0].spans+i;
            if(q->semantic==sem.name&&sem.index>=q->index&&sem.index-q->index<q->slots){if(span)return -1;span=q;}
        }
        unsigned a=s->Indirect.Index;
        if(!span||span->semantic!=TGSI_SEMANTIC_GENERIC||!span->per_vertex||!b->addr_defined[a])return -1;
        unsigned base=sem.index-span->index,width=pop(span->mask);
        bounded_add(b,28,b->addr_reg[a],base,span->slots);
        /* UINT32_MAX must not wrap during scale/offset. Branch around all
         * arithmetic on the sentinel path. Width3 is exact multiply, not SHL. */
        constant(b,31,~0u);unsigned skip=b->p.words;put(b,0);
        constant(b,31,width);put(b,alu(1,0,28,28,31));
        unsigned packed=b->packed[0].vertex[span->index].base+pop(span->mask&((1u<<c)-1));
        if(packed){constant(b,31,packed);put(b,alu(0,0,28,28,31));}
        patch_branch(b,skip,0,28,31);
        /* Comparison must use UINT32_MAX, before successful arithmetic changes
         * x31. Its dynamic value is read at the branch's actual issue epoch. */
        move(b,29,28);
    }else constant(b,29,component_index);
    unsigned v=s->Dimension.Index;
    if(v>=b->p.layout.input_vertices)return -1;
    if(s->Dimension.Indirect){unsigned a=s->DimIndirect.Index;if(!b->addr_defined[a])return -1;
        bounded_add(b,28,b->addr_reg[a],v,b->p.layout.input_vertices);}
    else constant(b,28,v);
    put(b,geo(32,to,28,29));return 0;
}
static int assign(struct build *b,const struct tgsi_dst_register *d,unsigned c,unsigned from,struct value value){
    unsigned file=d->File;int i=d->Index;if(i<0||d->Indirect||d->Dimension)return -1;
    if(file==TGSI_FILE_ADDRESS){if(i>1||c||!b->addr_decl[i])return -1;
        if(!b->addr_reg[i])b->addr_reg[i]=reg(b);
        move(b,b->addr_reg[i],from);
        b->addr_defined[i]=1;
        b->addr_value[i]=value;
        return 0;
        }
    if(file==TGSI_FILE_TEMPORARY){if(i>=32||!(b->declared[i]&(1u<<c)))return -1;
        if(!b->reg[i][c])b->reg[i][c]=reg(b);
        move(b,b->reg[i][c],from);
        b->defined[i]|=1u<<c;
        b->values[i][c]=value;
        return 0;
        }
    if(file!=TGSI_FILE_OUTPUT||i>=64||!(b->sem[1][i].mask&(1u<<c)))return -1;
    struct sem sem=b->sem[1][i];
    if(b->output_needed[i]&(1u<<c)){
        if(!b->output_reg[i][c])b->output_reg[i][c]=reg(b);
        move(b,b->output_reg[i][c],from);b->output_defined[i]|=1u<<c;b->output_values[i][c]=value;
    }
    if(sem.name==TGSI_SEMANTIC_LAYER||sem.name==TGSI_SEMANTIC_PRIMID){if(c||!(b->p.layout.builtin_output_mask&(sem.name==TGSI_SEMANTIC_LAYER?1u:2u)))return -1;put(b,geo(sem.name==TGSI_SEMANTIC_LAYER?34:38,0,from,0));return 0;}
    unsigned comp;if(component(b,1,sem,c,&comp))return -1;constant(b,30,comp);put(b,geo(33,0,30,from));return 0;
}
static int arithmetic(struct build *b,const struct tgsi_full_instruction *in){
    const struct tgsi_dst_register *d=&in->Dst[0].Register;unsigned op=in->Instruction.Opcode,mask=d->WriteMask,n=1;
    if(in->Instruction.NumDstRegs!=1||!mask)return -1;
    switch(op){case TGSI_OPCODE_MOV:case TGSI_OPCODE_UARL:break;
    case TGSI_OPCODE_UCMP:n=3;break;
    case TGSI_OPCODE_UADD:case TGSI_OPCODE_UMUL:case TGSI_OPCODE_XOR:case TGSI_OPCODE_AND:case TGSI_OPCODE_OR:
    case TGSI_OPCODE_SHL:case TGSI_OPCODE_USHR:case TGSI_OPCODE_ISHR:case TGSI_OPCODE_ISLT:case TGSI_OPCODE_ISGE:
    case TGSI_OPCODE_USLT:case TGSI_OPCODE_USGE:case TGSI_OPCODE_USEQ:case TGSI_OPCODE_USNE:
    case TGSI_OPCODE_ADD:case TGSI_OPCODE_MUL:n=2;break;
    default:return -1;}
    if(in->Instruction.NumSrcRegs!=n)return -1;
    struct value values[4]={{0}};
    for(unsigned c=0;c<4;c++)if(mask&(1u<<c)){
        struct value a={0},z={0},q={0};if(source(b,in->Src,c,26,&a))return -1;
        if(n>1&&source(b,in->Src+1,c,27,&z))return -1;
        unsigned r=22+c;
        if(n==1){move(b,r,26);values[c]=a;continue;}
        if(op==TGSI_OPCODE_UCMP){
            /* false value is initialized before a conditional overwrite. */
            if(source(b,in->Src+2,c,r,&q))return -1;
            unsigned skip=b->p.words;
            put(b,0);
            move(b,r,27);
            patch_branch(b,skip,0,26,0);

            if(a.known)values[c]=a.value?z:q;
            continue;

        }
        unsigned f=0,f3=0;
        switch(op){
        case TGSI_OPCODE_UADD:break;case TGSI_OPCODE_UMUL:f=1;break;
        case TGSI_OPCODE_XOR:f3=4;break;case TGSI_OPCODE_AND:f3=7;break;case TGSI_OPCODE_OR:f3=6;break;
        case TGSI_OPCODE_SHL:f3=1;break;case TGSI_OPCODE_USHR:f3=5;break;case TGSI_OPCODE_ISHR:f=32;f3=5;break;
        case TGSI_OPCODE_ISLT:case TGSI_OPCODE_ISGE:f3=2;break;
        case TGSI_OPCODE_USLT:case TGSI_OPCODE_USGE:f3=3;break;
        case TGSI_OPCODE_USEQ:case TGSI_OPCODE_USNE:put(b,alu(0,4,r,26,27));
            if(op==TGSI_OPCODE_USEQ)put(b,1u<<20|r<<15|3u<<12|r<<7|0x13u);
            else put(b,alu(0,3,r,0,r));
            break;

        case TGSI_OPCODE_ADD:case TGSI_OPCODE_MUL:
            put(b,0x78000053u|26u<<15|26u<<7);put(b,0x78000053u|27u<<15|27u<<7);
            put(b,(op==TGSI_OPCODE_MUL?0x10000053u:0x00000053u)|27u<<20|26u<<15|r<<7);
            put(b,0xe0000053u|r<<15|r<<7);break;
        default:return -1;
        }
        if(op!=TGSI_OPCODE_USEQ&&op!=TGSI_OPCODE_USNE&&op!=TGSI_OPCODE_ADD&&op!=TGSI_OPCODE_MUL)put(b,alu(f,f3,r,26,27));
        if(op==TGSI_OPCODE_ISGE||op==TGSI_OPCODE_USGE)put(b,1u<<20|r<<15|4u<<12|r<<7|0x13u);
        if(op==TGSI_OPCODE_ISLT||op==TGSI_OPCODE_ISGE||op==TGSI_OPCODE_USLT||op==TGSI_OPCODE_USGE||op==TGSI_OPCODE_USEQ||op==TGSI_OPCODE_USNE)
            put(b,alu(32,0,r,0,r)); /* TGSI true is all ones. */
        if(a.known&&z.known){uint32_t x=a.value,y=z.value,v=0;unsigned valid=1;
            switch(op){case TGSI_OPCODE_UADD:v=x+y;break;case TGSI_OPCODE_UMUL:v=x*y;break;
            case TGSI_OPCODE_XOR:v=x^y;break;case TGSI_OPCODE_AND:v=x&y;break;case TGSI_OPCODE_OR:v=x|y;break;
            case TGSI_OPCODE_SHL:v=x<<(y&31);break;case TGSI_OPCODE_USHR:v=x>>(y&31);break;case TGSI_OPCODE_ISHR:v=(uint32_t)((int32_t)x>>(y&31));break;
            case TGSI_OPCODE_ISLT:v=-(uint32_t)((int32_t)x<(int32_t)y);break;case TGSI_OPCODE_ISGE:v=-(uint32_t)((int32_t)x>=(int32_t)y);break;
            case TGSI_OPCODE_USLT:v=-(uint32_t)(x<y);break;case TGSI_OPCODE_USGE:v=-(uint32_t)(x>=y);break;
            case TGSI_OPCODE_USEQ:v=-(uint32_t)(x==y);break;case TGSI_OPCODE_USNE:v=-(uint32_t)(x!=y);break;default:valid=0;}
            if(valid)values[c]=(struct value){1,v};
        }
    }
    for(unsigned c=0;c<4;c++)if(mask&(1u<<c))if(assign(b,d,c,22+c,values[c]))return -1;
    return b->failed?-1:0;
}
/* Compile-time loop control is evaluated only when its condition is proven
 * from literal induction/ALU facts. Runtime values remain machine operations.
 * No arbitrary backedge is emitted into the existing scalar GS v1 profile. */
static int block(struct build *b,unsigned first,unsigned last,unsigned depth,unsigned in_loop){
    if(depth>16)return -1;
    for(unsigned at=first;at<last;at++){
        const struct tgsi_full_instruction *in=b->instructions+at;unsigned op=in->Instruction.Opcode;
        if(op==TGSI_OPCODE_END){if(at+1!=last||in_loop)return -1;put(b,0x30500073u);return b->failed?-1:0;}
        if(op==TGSI_OPCODE_EMIT||op==TGSI_OPCODE_ENDPRIM){
            struct value stream;if(in->Instruction.NumDstRegs||in->Instruction.NumSrcRegs!=1||source(b,in->Src,0,26,&stream)||!stream.known||stream.value)return -1;
            put(b,geo(op==TGSI_OPCODE_EMIT?35:36,0,0,0));
            if(op==TGSI_OPCODE_EMIT){memset(b->output_defined,0,sizeof(b->output_defined));memset(b->output_values,0,sizeof(b->output_values));}
            continue;}
        if(op==TGSI_OPCODE_BGNLOOP){unsigned end=at+1,nest=1;
            for(;end<last;end++){unsigned o=b->instructions[end].Instruction.Opcode;if(o==TGSI_OPCODE_BGNLOOP)nest++;else if(o==TGSI_OPCODE_ENDLOOP&&!--nest)break;}
            if(end==last)return -1;
            unsigned exited=0;

            for(unsigned n=0;n<=256;n++){int r=block(b,at+1,end,depth+1,1);if(r==1){exited=1;break;}if(r<0||b->failed)return -1;}
            if(!exited)return -1;
            at=end;
            continue;

        }
        if(op==TGSI_OPCODE_UIF){unsigned end=at+1,other=~0u,nest=1;
            for(;end<last;end++){unsigned o=b->instructions[end].Instruction.Opcode;
                if(o==TGSI_OPCODE_UIF)nest++;else if(o==TGSI_OPCODE_ENDIF){if(!--nest)break;}else if(o==TGSI_OPCODE_ELSE&&nest==1){if(other!=~0u)return -1;other=end;}}
            struct value condition;if(end==last||in->Instruction.NumDstRegs||in->Instruction.NumSrcRegs!=1||source(b,in->Src,0,26,&condition))return -1;
            if(condition.known){unsigned a=condition.value?at+1:(other==~0u?end:other+1),z=condition.value?(other==~0u?end:other):end;
                int r=block(b,a,z,depth+1,in_loop);if(r)return r;
            }else{
                if(in_loop)return -1; /* runtime loop exits need a new proof */
                unsigned branch_at=b->p.words;put(b,0);
                /* Stable allocation/definedness intersection across the arms. */
                struct value old[32][4],old_out[64][4];unsigned defined[32],out_defined[64];
                memcpy(old,b->values,sizeof(old));memcpy(defined,b->defined,sizeof(defined));
                memcpy(old_out,b->output_values,sizeof(old_out));memcpy(out_defined,b->output_defined,sizeof(out_defined));
                unsigned old_addr_defined[2];struct value old_addr_value[2];
                memcpy(old_addr_defined,b->addr_defined,sizeof(old_addr_defined));memcpy(old_addr_value,b->addr_value,sizeof(old_addr_value));
                if(block(b,at+1,other==~0u?end:other,depth+1,0))return -1;
                struct value then_values[32][4],then_out[64][4];unsigned then_defined[32],then_out_defined[64];
                memcpy(then_values,b->values,sizeof(then_values));memcpy(then_defined,b->defined,sizeof(then_defined));
                memcpy(then_out,b->output_values,sizeof(then_out));memcpy(then_out_defined,b->output_defined,sizeof(then_out_defined));
                unsigned then_addr_defined[2];struct value then_addr_value[2];
                memcpy(then_addr_defined,b->addr_defined,sizeof(then_addr_defined));memcpy(then_addr_value,b->addr_value,sizeof(then_addr_value));
                unsigned jump=b->p.words;put(b,0);patch_branch(b,branch_at,0,26,0);
                memcpy(b->values,old,sizeof(old));memcpy(b->defined,defined,sizeof(defined));
                memcpy(b->output_values,old_out,sizeof(old_out));memcpy(b->output_defined,out_defined,sizeof(out_defined));
                /* The physical else path never executed then. Restore the
                 * incoming compile facts before analyzing any else operand. */
                memcpy(b->addr_defined,old_addr_defined,sizeof(old_addr_defined));memcpy(b->addr_value,old_addr_value,sizeof(old_addr_value));
                if(other!=~0u&&block(b,other+1,end,depth+1,0))return -1;
                patch_branch(b,jump,0,0,0);

                for(unsigned i=0;i<32;i++){b->defined[i]&=then_defined[i];for(unsigned c=0;c<4;c++)
                    if(!then_values[i][c].known||!b->values[i][c].known||then_values[i][c].value!=b->values[i][c].value)b->values[i][c].known=0;}
                for(unsigned i=0;i<64;i++){b->output_defined[i]&=then_out_defined[i];for(unsigned c=0;c<4;c++)
                    if(!then_out[i][c].known||!b->output_values[i][c].known||then_out[i][c].value!=b->output_values[i][c].value)b->output_values[i][c].known=0;}
                for(unsigned i=0;i<2;i++){
                    b->addr_defined[i]&=then_addr_defined[i];
                    if(!then_addr_value[i].known||!b->addr_value[i].known||then_addr_value[i].value!=b->addr_value[i].value)b->addr_value[i].known=0;
                }
            }at=end;continue;
        }
        if(op==TGSI_OPCODE_BRK)return in_loop?1:-1;
        if(op==TGSI_OPCODE_ELSE||op==TGSI_OPCODE_ENDIF||op==TGSI_OPCODE_ENDLOOP)return -1;
        if(arithmetic(b,in))return -1;
    }return b->failed?-1:0;
}
int opengpu_native_geometry_compile(const void *tokens,unsigned words,const struct opengpu_native_tess_input_map *input,
    const struct opengpu_native_tess_input_map *output,struct opengpu_native_geometry_program *out){
    if(!input||!output||!out||input->version!=1||output->version!=1||input->count>64||output->count>64||packets(tokens,words))return -1;
    struct build *b=calloc(1,sizeof(*b));if(!b)return -1;b->next=2;b->maps[0]=*input;b->maps[1]=*output;b->p.layout.version=1;
    int result=-1;struct tgsi_parse_context p;if(pack(b,0)||pack(b,1))goto done;
    b->instructions=calloc(words,sizeof(*b->instructions));if(!b->instructions)goto done;
    if(tgsi_parse_init(&p,tokens)!=TGSI_PARSE_OK)goto done;
    unsigned saw_instruction=0;
    while(!tgsi_parse_end_of_tokens(&p)){
        tgsi_parse_token(&p);unsigned type=p.FullToken.Token.Type;
        if(type==TGSI_TOKEN_TYPE_INSTRUCTION){saw_instruction=1;b->instructions[b->count++]=p.FullToken.FullInstruction;continue;}
        if(saw_instruction)goto parsed;
        if(type==TGSI_TOKEN_TYPE_DECLARATION){if(declaration(b,&p.FullToken.FullDeclaration))goto parsed;}
        else if(type==TGSI_TOKEN_TYPE_PROPERTY){if(property(b,&p.FullToken.FullProperty))goto parsed;}
        else if(type==TGSI_TOKEN_TYPE_IMMEDIATE){if(b->nimm==64)goto parsed;for(unsigned c=0;c<4;c++)b->immediate[b->nimm][c]=p.FullToken.FullImmediate.u[c].Uint;b->nimm++;}
        else goto parsed;
    }
    if((b->properties&15u)!=15u||!b->count||b->instructions[b->count-1].Instruction.Opcode!=TGSI_OPCODE_END||
        b->p.layout.max_vertices*(b->p.layout.output_components+pop(b->p.layout.builtin_output_mask))>1024u)goto parsed;
    /* Only outputs read by original TGSI need RF shadows. Definedness is
     * intersected across branches and cleared after EmitVertex. */
    for(unsigned i=0;i<b->count;i++)for(unsigned j=0;j<b->instructions[i].Instruction.NumSrcRegs;j++){
        const struct tgsi_src_register *r=&b->instructions[i].Src[j].Register;
        if(r->File==TGSI_FILE_OUTPUT){if(r->Index<0||r->Index>=64)goto parsed;
            for(unsigned c=0;c<4;c++)b->output_needed[r->Index]|=1u<<swizzle(r,c);}
    }
    if(block(b,0,b->count,0,0)||b->failed)goto parsed;
    *out=b->p;result=0;
parsed:tgsi_parse_free(&p);
done:free(b->instructions);free(b);return result;
}
