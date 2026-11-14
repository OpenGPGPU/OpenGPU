/* SPDX-License-Identifier: MIT */
#include "opengpu_native_tess_uniform_emit.h"
#include "pipe/p_defines.h"
#include "tgsi/tgsi_parse.h"
#include <string.h>
#define REGS 32u
struct semantic {unsigned mask,name,index;
};
struct builder {
    struct opengpu_native_tess_program p;
    struct semantic input[2][REGS],output[2][REGS],sys[REGS];
    unsigned temp[REGS],reg[REGS][4],defined[REGS],next,addr_decl,addr_defined;
    unsigned constants[13],first[13],last[13];
    uint32_t imm[64][4];
     unsigned nimm,failed,ended,depth,properties;
};
static void word(struct builder *b,uint32_t w) {if(b->p.words==512)b->failed=1;
else b->p.code[b->p.words++]=w;
}
static uint32_t vec(unsigned f,unsigned f3,unsigned d,unsigned a,unsigned z)
{return f<<26|1u<<25|z<<20|a<<15|f3<<12|d<<7|0x57u;
}
static void copy(struct builder *b,unsigned d,unsigned s){word(b,vec(0,4,d,0,s));
}
static void scalar(struct builder *b,unsigned r,uint32_t v){unsigned hi=(v+0x800)&0xfffff000u,lo=v&4095;
    if(hi)word(b,hi|r<<7|0x37);
    if(lo||!hi)word(b,lo<<20|(hi?r:0)<<15|r<<7|0x13);
    }
static void splat(struct builder *b,unsigned d,uint32_t v){scalar(b,10,v);
word(b,vec(23,4,d,10,0));
}
static uint32_t geo(unsigned f,unsigned d,unsigned a,unsigned z){return f<<25|z<<20|a<<15|d<<7|0x0b;
}
static uint32_t patch(unsigned f,unsigned d,unsigned a,unsigned z){return f<<26|1u<<25|z<<20|a<<15|d<<7|0x2b;
}
static unsigned swizzle(const struct tgsi_src_register *r,unsigned c){return c==0?r->SwizzleX:c==1?r->SwizzleY:c==2?r->SwizzleZ:r->SwizzleW;
}
/* Validate lengths before invoking any optional token parser. No texture,
 * memory, nested dimensions or indirect register-file offsets are admitted. */
static int packets(const struct tgsi_token *t,unsigned n,unsigned stage){
    struct tgsi_header h;
    struct tgsi_processor p;
    if(!t||n<3||n>4098)return -1;
    memcpy(&h,t,4);
    memcpy(&p,t+1,4);
    if(h.HeaderSize!=2||h.BodySize!=n-2||p.Padding||p.Processor!=(stage==4?PIPE_SHADER_TESS_CTRL:PIPE_SHADER_TESS_EVAL))return -1;
    for(unsigned at=2;at<n;){struct tgsi_token tok;
    memcpy(&tok,t+at,4);
    unsigned size=tok.NrTokens;
        if(tok.Type==TGSI_TOKEN_TYPE_DECLARATION){struct tgsi_declaration d;
        memcpy(&d,t+at,4);
            if(d.Atomic||d.MemType||d.Padding||d.Interpolate||d.Array||d.Local||size!=2u+d.Dimension+d.Semantic)return -1;
        }else if(tok.Type==TGSI_TOKEN_TYPE_IMMEDIATE){struct tgsi_immediate i;
        memcpy(&i,t+at,4);
        size=i.NrTokens;
            if(size!=5||i.Padding||(i.DataType!=TGSI_IMM_UINT32&&i.DataType!=TGSI_IMM_INT32&&i.DataType!=TGSI_IMM_FLOAT32))return -1;
        }else if(tok.Type==TGSI_TOKEN_TYPE_PROPERTY){struct tgsi_property p2;
        memcpy(&p2,t+at,4);
        if(size!=2||p2.Padding)return -1;
        }else if(tok.Type==TGSI_TOKEN_TYPE_INSTRUCTION){struct tgsi_instruction i;
        memcpy(&i,t+at,4);
        size=i.NrTokens+1;
            if(i.Texture||i.Memory||i.Saturate||i.Padding||i.NumDstRegs>1||i.NumSrcRegs>2||size>n-at||
                (i.Label&&i.Opcode!=TGSI_OPCODE_UIF&&i.Opcode!=TGSI_OPCODE_ELSE))return -1;
            unsigned pos=at+1+i.Label;
            for(unsigned k=0;k<(unsigned)i.NumDstRegs+i.NumSrcRegs;k++){
                unsigned dim;
                if(pos>=at+size)return -1;
                if(k<i.NumDstRegs){struct tgsi_dst_register d;
                memcpy(&d,t+pos++,4);
                if(d.Indirect||d.Padding)return -1;
                dim=d.Dimension;
                }
                else {struct tgsi_src_register s;
                memcpy(&s,t+pos++,4);
                if(s.Indirect||s.Absolute||s.Negate)return -1;
                dim=s.Dimension;
                }
                if(dim){struct tgsi_dimension d;
                if(pos>=at+size)return -1;
                memcpy(&d,t+pos++,4);
                    if(d.Dimension||d.Padding||d.Index<0)return -1;
                    if(d.Indirect){struct tgsi_ind_register x;
                    if(pos>=at+size)return -1;
                    memcpy(&x,t+pos++,4);
                        if(x.File!=TGSI_FILE_ADDRESS||x.Index<0||x.Index>1||x.Swizzle||x.ArrayID)return -1;
                        }
                }
            }if(pos!=at+size)return -1;
        }else return -1;
        if(!size||size>n-at)return -1;
        at+=size;
    }return 0;
}
static int declaration(struct builder *b,const struct tgsi_full_declaration *d){
    unsigned f=d->Range.First,l=d->Range.Last,m=d->Declaration.UsageMask,dim=d->Declaration.Dimension;
    if(!m||f>l)return -1;
    if(d->Declaration.File==TGSI_FILE_CONSTANT){unsigned slot=d->Dim.Index2D;
        if(!dim||d->Dim.Padding||slot>12||b->constants[slot]||l>=(slot?1024u:256u))return -1;
        b->constants[slot]=m;
        b->first[slot]=f;
        b->last[slot]=l;
        return 0;
        }
    if(l>=REGS)return -1;
    if(d->Declaration.File==TGSI_FILE_TEMPORARY){if(dim||d->Declaration.Semantic)return -1;
        for(unsigned i=f;i<=l;i++){if(b->temp[i])return -1;
        b->temp[i]=m;
        }return 0;
        }
    if(d->Declaration.File==TGSI_FILE_ADDRESS){if(dim||d->Declaration.Semantic||l>1||!(m&1))return -1;
        for(unsigned i=f;i<=l;i++){if(b->addr_decl&(1u<<i))return -1;
        b->addr_decl|=1u<<i;
        }return 0;
        }
    /* TGSI per-vertex declarations have an implicit array dimension inferred
     * from stage and semantic; Declaration.Dimension is not that dimension.
     * In particular OUT[][2] GENERIC may coexist with OUT[2] TESSOUTER. */
    unsigned pervertex=d->Semantic.Name==TGSI_SEMANTIC_POSITION||d->Semantic.Name==TGSI_SEMANTIC_GENERIC;
    dim=pervertex&&(d->Declaration.File==TGSI_FILE_INPUT||
        (d->Declaration.File==TGSI_FILE_OUTPUT&&b->p.layout.stage==4));
    struct semantic *dest;
    if(d->Declaration.File==TGSI_FILE_INPUT)dest=b->input[dim];
    else if(d->Declaration.File==TGSI_FILE_OUTPUT)dest=b->output[dim];
    else if(d->Declaration.File==TGSI_FILE_SYSTEM_VALUE&&!dim)dest=b->sys;
    else return -1;
    if(!d->Declaration.Semantic||d->Semantic.StreamX||d->Semantic.StreamY||d->Semantic.StreamZ||d->Semantic.StreamW)return -1;
    for(unsigned i=f;i<=l;i++){if(dest[i].mask)return -1;
    dest[i]=(struct semantic){m,d->Semantic.Name,d->Semantic.Index+i-f};
    }
    return 0;
}
static int component(struct semantic s,unsigned position,unsigned c,unsigned *out,unsigned *pp,unsigned *tl){
    if(!(s.mask&(1u<<c)))return -1;
    *pp=0;
    *tl=0;
    if(s.name==TGSI_SEMANTIC_POSITION&&!s.index&&position)*out=c;
    else if(s.name==TGSI_SEMANTIC_GENERIC&&s.index<(position?15u:16u))*out=4*position+4*s.index+c;
    else if(s.name==TGSI_SEMANTIC_PATCH&&s.index<30){*pp=1;
    *out=4*s.index+c;
    }
    else if((s.name==TGSI_SEMANTIC_TESSOUTER||s.name==TGSI_SEMANTIC_TESSINNER)&&!s.index){
        *tl=1;
        *pp=1;
        *out=(s.name==TGSI_SEMANTIC_TESSINNER?4:0)+c;
        if(*out>=6)return -1;
    }else return -1;
    return 0;
}
static int vertex(struct builder *b,unsigned has,const struct tgsi_dimension *d,const struct tgsi_ind_register *ind,unsigned to){
    if(!has){splat(b,to,0);
    return 0;
    }
    if(d->Indirect){if(!(b->addr_defined&(1u<<ind->Index)))return -1;
    copy(b,to,20+ind->Index);
        if(d->Index){scalar(b,10,d->Index);
        word(b,vec(0,4,to,10,to));
        }}
    else splat(b,to,d->Index);
    return 0;
}
static int source(struct builder *b,const struct tgsi_full_src_register *r,unsigned c,unsigned to){
    const struct tgsi_src_register *s=&r->Register;
    unsigned ch=swizzle(s,c);
    int i=s->Index;
    if(i<0||(s->File!=TGSI_FILE_CONSTANT&&i>=32))return -1;
    switch(s->File){
    case TGSI_FILE_TEMPORARY:if(!(b->defined[i]&(1u<<ch)))return -1;
    copy(b,to,b->reg[i][ch]);
    return 0;
    case TGSI_FILE_IMMEDIATE:if((unsigned)i>=b->nimm)return -1;
    splat(b,to,b->imm[i][ch]);
    return 0;
    case TGSI_FILE_CONSTANT:{unsigned slot=r->Dimension.Index,bytes=(unsigned)i*16+ch*4+4;
        /* constant vec4 indices span0..1023, so do not apply register-file bound. */
        if(!s->Dimension||r->Dimension.Indirect||slot>12||!(b->constants[slot]&(1u<<ch))||(unsigned)i<b->first[slot]||(unsigned)i>b->last[slot])return -1;
        if(b->depth){
            /* SIMT paths share scalar registers. Materialize only scratchx10,
             * then use the real checked per-lane uniform provider; no scalar
             * register restoration is assumed at path switches. */
            splat(b,to,slot?slot-1:31);splat(b,29,bytes-4);
            word(b,7u<<26|1u<<25|29u<<20|to<<15|to<<7|0x2b);
        }else{
            scalar(b,11,slot?slot-1:31);scalar(b,12,bytes-4);
            word(b,geo(7,13,11,12));word(b,vec(23,4,to,13,0));
        }
        if(b->p.constant_bytes[slot]<bytes)b->p.constant_bytes[slot]=bytes;
        return 0;
        }
    case TGSI_FILE_SYSTEM_VALUE:{struct semantic d=b->sys[i];
    if(!(d.mask&(1u<<ch))||d.index)return -1;
        if(b->p.layout.stage==4&&d.name==TGSI_SEMANTIC_INVOCATIONID&&!ch){copy(b,to,1);
        return 0;
        }
        if(b->p.layout.stage==5&&d.name==TGSI_SEMANTIC_TESSCOORD&&ch<3){scalar(b,11,ch);
        word(b,geo(32,12,0,11));
        word(b,vec(23,4,to,12,0));
        return 0;
        }
        return -1;
        }
    case TGSI_FILE_INPUT:case TGSI_FILE_OUTPUT:{unsigned off,pp,tl;
    unsigned dim=s->Dimension;
        struct semantic d=s->File==TGSI_FILE_INPUT?b->input[dim][i]:b->output[dim][i];
        if(component(d,s->File==TGSI_FILE_INPUT?b->p.layout.input_position:b->p.layout.output_position,ch,&off,&pp,&tl))return -1;
        if(b->p.layout.stage==4){if(vertex(b,dim,&r->Dimension,&r->DimIndirect,28))return -1;
            splat(b,29,(off<<3)|(s->File==TGSI_FILE_OUTPUT)|pp<<1|tl<<2);
            word(b,patch(21,to,28,29));
            if(s->File==TGSI_FILE_INPUT){if(off>=b->p.layout.input_components)return -1;
            b->p.input_mask|=UINT64_C(1)<<off;
            }return 0;
            }
        if(s->File!=TGSI_FILE_INPUT||r->Dimension.Indirect||
            (dim&&(unsigned)r->Dimension.Index>=b->p.layout.output_vertices)||
            (pp&&!tl&&off>=b->p.layout.patch_components)||(!pp&&!tl&&off>=b->p.layout.input_components))return -1;
        struct opengpu_native_tess_selection q={pp,tl,dim?(unsigned)r->Dimension.Index:0,off};
        unsigned k;
        for(k=0;k<b->p.selection_count;k++)if(!memcmp(&q,&b->p.selections[k],sizeof(q)))break;
        if(k==b->p.selection_count){if(k==61)return -1;
        b->p.selections[k]=q;
        b->p.selection_count++;
        }
        scalar(b,11,3+k);
        word(b,geo(32,12,0,11));
        word(b,vec(23,4,to,12,0));
        return 0;
        }
    default:return -1;
    }
}
static int instruction(struct builder *b,const struct tgsi_full_instruction *in){
    unsigned op=in->Instruction.Opcode;
    if(op==TGSI_OPCODE_BARRIER){if(b->p.layout.stage!=4||b->depth||in->Instruction.NumSrcRegs||in->Instruction.NumDstRegs)return -1;
        word(b,0x0b);
        b->p.barriers++;
        return 0;
        }
    unsigned count=1,fn=0,f3=0,compare=0;
    switch(op){case TGSI_OPCODE_MOV:case TGSI_OPCODE_UARL:break;
    case TGSI_OPCODE_UADD:count=2;
    break;
    case TGSI_OPCODE_UMUL:count=2;
    fn=0x25;
    f3=2;
    break;
    case TGSI_OPCODE_XOR:count=2;
    fn=11;
    break;
    case TGSI_OPCODE_AND:count=2;
    fn=9;
    break;
    case TGSI_OPCODE_OR:count=2;
    fn=10;
    break;
    case TGSI_OPCODE_SHL:count=2;
    fn=0x25;
    break;
    case TGSI_OPCODE_USHR:count=2;
    fn=0x28;
    break;
    case TGSI_OPCODE_ADD:count=2;
    f3=1;
    break;
    case TGSI_OPCODE_MUL:count=2;
    fn=0x24;
    f3=1;
    break;
    case TGSI_OPCODE_ISLT:count=2;
    fn=0x1b;
    compare=1;
    break;
    case TGSI_OPCODE_USLT:count=2;
    fn=0x1a;
    compare=1;
    break;
    case TGSI_OPCODE_USEQ:count=2;
    fn=0x18;
    compare=1;
    break;
    default:return -1;
    }
    const struct tgsi_dst_register *d=&in->Dst[0].Register;
    int i=d->Index;
    unsigned mask=d->WriteMask;
    if(in->Instruction.NumSrcRegs!=count||in->Instruction.NumDstRegs!=1||i<0||i>=32||!mask)return -1;
    if(op==TGSI_OPCODE_UARL){if(d->File!=TGSI_FILE_ADDRESS||mask!=1||!(b->addr_decl&(1u<<i))||d->Dimension)return -1;
        if(source(b,&in->Src[0],0,20+i))return -1;
        b->addr_defined|=1u<<i;
        return 0;
        }
    if(d->File!=TGSI_FILE_TEMPORARY&&d->File!=TGSI_FILE_OUTPUT)return -1;
    unsigned declared=d->File==TGSI_FILE_TEMPORARY?b->temp[i]:b->output[d->Dimension][i].mask;
    if(mask&~declared)return -1;
    for(unsigned c=0;c<4;c++)if(mask&(1u<<c)){
        if(source(b,&in->Src[0],c,26))return -1;
        if(op==TGSI_OPCODE_MOV)copy(b,22+c,26);
        else {if(source(b,&in->Src[1],c,27))return -1;
            if(compare){word(b,vec(fn,0,0,27,26));
            splat(b,22+c,0);
            word(b,vec(23,3,22+c,31,22+c)&~(1u<<25));
            }
            else word(b,vec(fn,f3,22+c,27,26));
            }
    }
    for(unsigned c=0;c<4;c++)if(mask&(1u<<c)){
        if(d->File==TGSI_FILE_TEMPORARY){if(!b->reg[i][c]){if(b->next>=20)return -1;
        b->reg[i][c]=b->next++;
        }
            copy(b,b->reg[i][c],22+c);
            b->defined[i]|=1u<<c;
        }else{unsigned off,pp,tl;
        if(component(b->output[d->Dimension][i],b->p.layout.output_position,c,&off,&pp,&tl))return -1;
            if(b->p.layout.stage==4){if(vertex(b,d->Dimension,&in->Dst[0].Dimension,&in->Dst[0].DimIndirect,28))return -1;
                if((pp&&!tl&&off>=b->p.layout.patch_components)||(!pp&&!tl&&off>=b->p.layout.output_components))return -1;
                splat(b,29,(off<<3)|1|pp<<1|tl<<2);
                word(b,patch(20,0,22+c,0));
                word(b,patch(22,0,28,29));
            }else{if(pp||tl||d->Dimension||b->depth||off>=b->p.layout.output_components)return -1;
                word(b,vec(16,2,12,0,22+c));
                scalar(b,11,off);
                word(b,geo(33,0,11,12));
                }
            if(!pp&&!tl)b->p.output_mask|=UINT64_C(1)<<off;
        }
    }return 0;
}
static uint32_t branch(unsigned f,unsigned a,unsigned z,unsigned bytes){return ((bytes>>12)&1)<<31|((bytes>>5)&63)<<25|z<<20|a<<15|f<<12|((bytes>>1)&15)<<8|((bytes>>11)&1)<<7|0x5b;
}
struct node {struct tgsi_full_instruction in;
unsigned alt,end;
};
static int span(struct builder *b,const struct node *nodes,unsigned start,unsigned end){
    for(unsigned i=start;i<end;i++){
        if(nodes[i].in.Instruction.Opcode!=TGSI_OPCODE_UIF){if(instruction(b,&nodes[i].in))return -1;
        continue;
        }
        if(b->p.layout.stage!=4||b->depth>=4||source(b,&nodes[i].in.Src[0],0,26))return -1;
        splat(b,27,0);
        unsigned sync=b->p.words;
        word(b,0);
        word(b,0);
        unsigned before[32],yes[32],addr=b->addr_defined;
        memcpy(before,b->defined,sizeof(before));
        unsigned a=nodes[i].alt,e=nodes[i].end;
        b->depth++;
        if(span(b,nodes,i+1,a<e?a:e))return -1;
        word(b,0x205b);
        unsigned taken=b->p.words;
        memcpy(yes,b->defined,sizeof(yes));
        unsigned addr_yes=b->addr_defined;
        memcpy(b->defined,before,sizeof(before));
        b->addr_defined=addr;
        if(a<e&&span(b,nodes,a+1,e))return -1;
        word(b,0x205b);
        unsigned marker=b->p.words;
        word(b,0x205b);
        b->depth--;
        if(b->failed)return -1;
        b->p.code[sync]=branch(3,0,0,(marker-sync)*4);
        b->p.code[sync+1]=branch(0,26,27,(taken-sync-1)*4);
        for(unsigned k=0;k<32;k++)b->defined[k]&=yes[k];
        b->addr_defined&=addr_yes;
        i=e;
    }return 0;
}
int opengpu_native_tess_uniform_emit(const void *tokens,unsigned n,const struct opengpu_native_tess_layout *layout,
    struct opengpu_native_tess_program *out){
    if(!layout||!out||layout->version!=1||(layout->stage!=4&&layout->stage!=5)||!layout->capacity||layout->capacity>128||
        !layout->input_vertices||layout->input_vertices>32||!layout->output_vertices||layout->output_vertices>32||
        layout->output_vertices>layout->capacity||layout->input_components>64||layout->output_components>64||
        layout->input_position>1||layout->output_position>1||(layout->stage==5&&!layout->output_position)||layout->patch_components>120||layout->output_vertices*layout->output_components+layout->patch_components>2048||
        packets(tokens,n,layout->stage))return -1;
    struct builder b={0};
    b.p.layout=*layout;
    b.next=2;
    struct tgsi_parse_context parse;
    struct node nodes[256];
    unsigned count=0,stack[4],depth=0;
    word(&b,0x0104f057);
     /* full e32m1 using actual x9 */
    if(tgsi_parse_init(&parse,tokens)!=TGSI_PARSE_OK)return -1;
    while(!tgsi_parse_end_of_tokens(&parse)){
        if(b.ended){b.failed=1;
        break;
        }tgsi_parse_token(&parse);
        int ret=0;
        switch(parse.FullToken.Token.Type){
        case TGSI_TOKEN_TYPE_DECLARATION:ret=declaration(&b,&parse.FullToken.FullDeclaration);
        break;
        case TGSI_TOKEN_TYPE_IMMEDIATE:if(b.nimm==64){ret=-1;
        break;
        }for(unsigned c=0;c<4;c++)b.imm[b.nimm][c]=parse.FullToken.FullImmediate.u[c].Uint;
        b.nimm++;
        break;
        case TGSI_TOKEN_TYPE_PROPERTY:{unsigned name=parse.FullToken.FullProperty.Property.PropertyName,v=parse.FullToken.FullProperty.u[0].Data;
            unsigned bit=0;
            switch(name){
            case TGSI_PROPERTY_TCS_VERTICES_OUT:bit=1;break;
            case TGSI_PROPERTY_TES_PRIM_MODE:bit=2;break;
            case TGSI_PROPERTY_TES_SPACING:bit=4;break;
            case TGSI_PROPERTY_TES_VERTEX_ORDER_CW:bit=8;break;
            case TGSI_PROPERTY_TES_POINT_MODE:bit=16;break;
            case TGSI_PROPERTY_NEXT_SHADER:bit=32;break;
            default:ret=-1;break;
            }
            if(b.properties&bit)ret=-1;
            b.properties|=bit;
            if(layout->stage==4){if(name!=TGSI_PROPERTY_TCS_VERTICES_OUT||v!=layout->output_vertices)ret=-1;
            }
            else {switch(name){case TGSI_PROPERTY_TES_PRIM_MODE:if(v!=layout->domain)ret=-1;
            break;
                case TGSI_PROPERTY_TES_SPACING:if(v!=layout->spacing)ret=-1;
                break;
                case TGSI_PROPERTY_TES_VERTEX_ORDER_CW:if(v!=layout->clockwise)ret=-1;
                break;
                case TGSI_PROPERTY_TES_POINT_MODE:if(v!=layout->point_mode)ret=-1;
                break;
                case TGSI_PROPERTY_NEXT_SHADER:if(v!=PIPE_SHADER_FRAGMENT)ret=-1;
                break;
                default:ret=-1;
                }}
            break;
            }
        case TGSI_TOKEN_TYPE_INSTRUCTION:{const struct tgsi_full_instruction *in=&parse.FullToken.FullInstruction;
        unsigned op=in->Instruction.Opcode;
            if(op==TGSI_OPCODE_END){if(depth||in->Instruction.NumSrcRegs||in->Instruction.NumDstRegs)ret=-1;
            else b.ended=1;
            break;
            }
            if(count==256){ret=-1;
            break;
            }nodes[count]=(struct node){.in=*in,.alt=256,.end=256};
            if(op==TGSI_OPCODE_UIF){if(depth==4||in->Instruction.NumSrcRegs!=1||in->Instruction.NumDstRegs){ret=-1;
            break;
            }stack[depth++]=count;
            }
            else if(op==TGSI_OPCODE_ELSE){if(!depth||in->Instruction.NumSrcRegs||in->Instruction.NumDstRegs||nodes[stack[depth-1]].alt!=256){ret=-1;
            break;
            }nodes[stack[depth-1]].alt=count;
            }
            else if(op==TGSI_OPCODE_ENDIF){if(!depth||in->Instruction.NumSrcRegs||in->Instruction.NumDstRegs){ret=-1;
            break;
            }nodes[stack[--depth]].end=count;
            }
            count++;
            break;
            }
        default:ret=-1;
        }
        if(ret){b.failed=1;
        break;
        }
    }tgsi_parse_free(&parse);
    if(b.failed||b.properties!=(layout->stage==4?1u:62u)||!b.ended||depth||span(&b,nodes,0,count))return -1;
    if(layout->stage==5){if((b.p.output_mask&15)!=15)return -1;
    word(&b,geo(35,0,0,0));
    word(&b,geo(36,0,0,0));
    }
    word(&b,0x30500073);
    if(b.failed)return -1;
    *out=b.p;
    return 0;
}

/* Reflection is bounded independently of emission. Pair construction owns the
 * linked TCS-output -> TES-input map; this never grants task permissions. */
int opengpu_native_tess_uniform_reflect(const void *tokens,unsigned n,unsigned stage,unsigned capacity,
    unsigned patch_vertices,struct opengpu_native_tess_layout *out)
{
    struct opengpu_native_tess_layout l={.version=1,.stage=stage,.capacity=capacity,
        .input_vertices=patch_vertices,.output_vertices=1};
    struct tgsi_parse_context p;
    unsigned properties=0;
    if(!out||(stage!=4&&stage!=5)||!capacity||capacity>128||!patch_vertices||patch_vertices>32||packets(tokens,n,stage))return -1;
    /* Position presence changes the generic packing base, independent of
     * declaration order. Count applicable builtins inside the64 components. */
    if(tgsi_parse_init(&p,tokens)!=TGSI_PARSE_OK)return -1;
    while(!tgsi_parse_end_of_tokens(&p)){
        tgsi_parse_token(&p);
        if(p.FullToken.Token.Type!=TGSI_TOKEN_TYPE_DECLARATION)continue;
        const struct tgsi_full_declaration *d=&p.FullToken.FullDeclaration;
        if(d->Declaration.Semantic&&d->Semantic.Name==TGSI_SEMANTIC_POSITION){
            if(d->Declaration.File==TGSI_FILE_INPUT)l.input_position=1;
            if(d->Declaration.File==TGSI_FILE_OUTPUT)l.output_position=1;
        }
    }
    tgsi_parse_free(&p);
    if(tgsi_parse_init(&p,tokens)!=TGSI_PARSE_OK)return -1;
    int bad=0;
    while(!tgsi_parse_end_of_tokens(&p)&&!bad){
        tgsi_parse_token(&p);
        if(p.FullToken.Token.Type==TGSI_TOKEN_TYPE_PROPERTY){
            unsigned name=p.FullToken.FullProperty.Property.PropertyName,v=p.FullToken.FullProperty.u[0].Data,bit;
            switch(name){
            case TGSI_PROPERTY_TCS_VERTICES_OUT:bit=1;
            l.output_vertices=v;
            if(stage!=4)bad=1;
            break;
            case TGSI_PROPERTY_TES_PRIM_MODE:bit=2;
            l.domain=v;
            if(stage!=5)bad=1;
            break;
            case TGSI_PROPERTY_TES_SPACING:bit=4;
            l.spacing=v;
            if(stage!=5)bad=1;
            break;
            case TGSI_PROPERTY_TES_VERTEX_ORDER_CW:bit=8;
            l.clockwise=v;
            if(stage!=5||v>1)bad=1;
            break;
            case TGSI_PROPERTY_TES_POINT_MODE:bit=16;
            l.point_mode=v;
            if(stage!=5||v>1)bad=1;
            break;
            case TGSI_PROPERTY_NEXT_SHADER:bit=32;
            if(stage!=5||v!=PIPE_SHADER_FRAGMENT)bad=1;
            break;
            default:bad=1;
            bit=0;
            }
            if(properties&bit)bad=1;
            properties|=bit;
        }else if(p.FullToken.Token.Type==TGSI_TOKEN_TYPE_DECLARATION){
            const struct tgsi_full_declaration *d=&p.FullToken.FullDeclaration;
            unsigned file=d->Declaration.File;
            if(file!=TGSI_FILE_INPUT&&file!=TGSI_FILE_OUTPUT)continue;
            if(!d->Declaration.Semantic||d->Range.Last>=32||d->Range.First>d->Range.Last){bad=1;
            break;
            }
            for(unsigned i=d->Range.First;i<=d->Range.Last;i++)for(unsigned c=0;c<4;c++)if(d->Declaration.UsageMask&(1u<<c)){
                struct semantic sem={d->Declaration.UsageMask,d->Semantic.Name,d->Semantic.Index+i-d->Range.First};
                /* TGSI reserves unused tess-level registers/channels; actual
                 * operand validation still rejects unsupported level indices. */
                if(sem.name==TGSI_SEMANTIC_TESSOUTER||sem.name==TGSI_SEMANTIC_TESSINNER)continue;
                unsigned off,pp,tl,position=file==TGSI_FILE_INPUT?l.input_position:l.output_position;
                if(component(sem,position,c,&off,&pp,&tl)){bad=1;
                break;
                }
                unsigned *count=pp?&l.patch_components:file==TGSI_FILE_INPUT?&l.input_components:&l.output_components;
                if(*count<off+1)*count=off+1;
            }
        }
    }
    tgsi_parse_free(&p);
    if(bad||properties!=(stage==4?1u:62u)||!l.output_vertices||l.output_vertices>32||l.output_vertices>capacity||
        l.input_components>64||l.output_components>64||l.patch_components>120||
        l.output_vertices*l.output_components+l.patch_components>2048)return -1;
    *out=l;
    return 0;
}
