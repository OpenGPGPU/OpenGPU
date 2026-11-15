// SPDX-License-Identifier: GPL-2.0
/* Independent checked-patch/one-point dialect. The legacy validator is intact;
 * reuse only its pure instruction legality and structured-SIMT decoding helpers. */
#include <linux/errno.h>
#include <linux/slab.h>
#include <linux/vmalloc.h>
#include <linux/string.h>
#include "opengpu_tessellation_program_snapshot.h"
#include "opengpu_shader_validator.h"

#define TESS_BRANCHES 4u
#define TESS_ALL_PARAMS (~(u32)0)
struct tess_value { u32 value; bool defined,known; };
struct tess_state {
    struct tess_value x[32],v[32];
    u32 fp,mask_version,params;
    bool full_vl,emitted,ended,invalid;
};
struct tess_branch { struct tess_state state; u32 target; bool used; };
struct tess_workspace { struct tess_state state,swap; struct tess_branch branches[TESS_BRANCHES]; };

static bool tess_layout_zero(const struct opengpu_tessellation_patch_layout *l)
{ return !l->input_vertices && !l->input_components && !l->output_vertices && !l->output_components && !l->patch_components; }
static bool tess_evaluation_layout_valid(const struct opengpu_tessellation_patch_layout *l)
{
    return l->input_vertices>=1 && l->input_vertices<=32 && l->output_vertices>=1 && l->output_vertices<=32 &&
        l->input_components<=64 && l->output_components<=64 && l->patch_components<=120 &&
        l->input_vertices*l->input_components<=2048 && l->output_vertices*l->output_components+l->patch_components<=2048;
}
bool opengpu_tessellation_program_profile_valid(const struct opengpu_tessellation_program_profile *p)
{
    if(!p || !p->available || (p->version!=1 && p->version!=2 && p->version!=3) || (p->stage!=4 && p->stage!=5) ||
        !p->lanes || p->lanes>16 || (p->lanes&(p->lanes-1)) ||
        p->warps<2 || p->warps>8 || (p->warps&(p->warps-1)))return false;
    if(p->version!=3 && p->owned_tessellation_info)return false;
    if(p->version==3 && !p->owned_tessellation_info)return false;
    if(p->version==1 && (p->owned_patch_reads || !tess_layout_zero(&p->tes_patch_layout)))return false;
    if(p->version==3 && p->stage==4 &&
        (p->owned_patch_reads || !tess_layout_zero(&p->tes_patch_layout)))return false;
    if(p->stage==4)return (p->version==1 || p->version==3) && p->input_vertices>=1 && p->input_vertices<=32 &&
        p->output_vertices>=1 && p->output_vertices<=32 && p->output_vertices<=p->lanes*p->warps &&
        p->input_components<=64 && p->output_components<=64 && p->patch_components<=120 &&
        p->input_vertices*p->input_components<=2048 &&
        p->output_vertices*p->output_components+p->patch_components<=2048 && !p->patch_words;
    if(p->input_vertices || p->input_components || p->output_vertices || p->patch_components || p->output_components>64)return false;
    if(p->version==1)return p->patch_words<=61;
    return p->owned_patch_reads && !p->patch_words && tess_evaluation_layout_valid(&p->tes_patch_layout);
}
static struct tess_value tess_unknown(void)
{ return (struct tess_value){.defined=true}; }
static struct tess_value tess_constant(u32 value)
{ return (struct tess_value){.value=value,.defined=true,.known=true}; }
static void tess_merge(struct tess_state *to,const struct tess_state *from)
{
    for(u32 i=0;i<32;i++){
        struct tess_value *a[2]={&to->x[i],&to->v[i]};
        const struct tess_value *b[2]={&from->x[i],&from->v[i]};
        for(u32 k=0;k<2;k++){
            a[k]->defined&=b[k]->defined;
            a[k]->known=a[k]->defined && a[k]->known && b[k]->known && a[k]->value==b[k]->value;
        }
    }
    to->fp&=from->fp;to->full_vl&=from->full_vl;to->invalid|=from->invalid;
    if(to->mask_version!=from->mask_version)to->mask_version=0;
    if(to->params==TESS_ALL_PARAMS)to->params=from->params;
    else if(from->params!=TESS_ALL_PARAMS && to->params!=from->params)to->params=0;
    /* Rejoining paths must agree on their single-point publication phase. */
    if(to->emitted!=from->emitted || to->ended!=from->ended)to->invalid=true;
}
/* Scalar/FP RF and capture protocols are warp-shared. SIMT restores lane
 * masks, not these registers. Only x10 may be a scalar scratch in a scope;
 * every arm and continuation must rematerialize it and any mask/parameters. */
static void tess_simt_boundary(struct tess_state *s)
{
    s->x[10]=(struct tess_value){0};s->fp=0;
    s->v[0]=(struct tess_value){0};s->mask_version=0;s->params=0;
}
static bool tess_queue(struct tess_branch *b,u32 target,const struct tess_state *s)
{
    for(u32 i=0;i<TESS_BRANCHES;i++)if(b[i].used && b[i].target==target){tess_merge(&b[i].state,s);return true;}
    for(u32 i=0;i<TESS_BRANCHES;i++)if(!b[i].used){b[i].used=true;b[i].target=target;b[i].state=*s;return true;}
    return false;
}
static bool tess_pending(const struct tess_branch *b)
{ for(u32 i=0;i<TESS_BRANCHES;i++)if(b[i].used)return true;return false; }
static void tess_vector_write(struct tess_state *s,u32 rd,u32 pc,struct tess_value value)
{ s->v[rd]=value;if(!rd)s->mask_version=pc+1; }
static bool tess_masked(const struct tess_state *s,u32 insn,u32 rd,bool destination)
{ return (insn&(1u<<25)) || (s->v[0].defined && (!destination || (rd!=0 && s->v[rd].defined))); }
static bool tess_patch_access(const struct tess_state *s,u32 vertex,u32 address,bool store,
    const struct opengpu_tessellation_program_profile *p)
{
    if(!s->v[vertex].defined || !s->v[address].defined)return false;
    /* Runtime owned patch access checks all dynamic indices and layout bounds.
     * Constant malformed/unauthorized accesses are rejected before publication. */
    if(s->v[vertex].known && s->v[vertex].value>=64)return false;
    if(s->v[address].known){
        u32 a=s->v[address].value,component=a>>3;
        bool output=a&1,per_patch=a&2,level=a&4;
        if(a>=1024 || (store && !output))return false;
        if(per_patch || level){
            if(!output || !per_patch || component>=(level?6:p->patch_components) ||
                (s->v[vertex].known && s->v[vertex].value))return false;
        }else if(component>=(output?p->output_components:p->input_components) ||
            (s->v[vertex].known && s->v[vertex].value>=(output?p->output_vertices:p->input_vertices)))return false;
    }
    return true;
}
/* CUSTOM1 TES fn33 is read-only output inspection, not CUSTOM0 fn33 output
 * publication or TCS fn21's input/output access. Preserve full uint32 operands;
 * dynamic values remain subject to the actual retained task's owned service. */
static bool tess_evaluation_access(const struct tess_state *s,u32 vertex,u32 address,
    const struct opengpu_tessellation_program_profile *p)
{
    const struct opengpu_tessellation_patch_layout *l=&p->tes_patch_layout;
    if(!s->v[vertex].defined || !s->v[address].defined)return false;
    if(s->v[vertex].known && s->v[vertex].value>=l->output_vertices)return false;
    if(s->v[address].known){
        u32 a=s->v[address].value,component=a>>3;
        bool per_patch=a&2,level=a&4;
        if(a>=1024 || !(a&1))return false;
        if(per_patch || level){
            if(!per_patch || component>=(level?6:l->patch_components) ||
                (s->v[vertex].known && s->v[vertex].value))return false;
        }else if(component>=l->output_components)return false;
    }
    return true;
}
static struct tess_value tess_integer(u32 fn,u32 a,u32 b)
{
    switch(fn){
    case 0:return tess_constant(a+b);case 1:return tess_constant(a<<(b&31));
    case 2:return tess_constant((opengpu_shader_s32)a<(opengpu_shader_s32)b);
    case 3:return tess_constant(a<b);case 4:return tess_constant(a^b);
    case 5:return tess_constant(a>>(b&31));case 6:return tess_constant(a|b);case 7:return tess_constant(a&b);
    }return tess_unknown();
}
static bool tess_vector(struct tess_state *s,u32 insn,u32 pc,const struct opengpu_tessellation_program_profile *p)
{
    u32 rd=insn>>7&31,a=insn>>15&31,b=insn>>20&31,f=insn>>12&7,fn=insn>>26;
    /* Full E32/M1 only. x9 is physical capacity, never outputVertices. */
    if((insn&0xfff0707fu)==0x01007057u){
        if(!((!a && rd) || (a==9 && s->x[9].known && s->x[9].value==p->lanes*p->warps)))return false;
        s->full_vl=true;if(rd)s->x[rd]=tess_constant(p->lanes);return true;
    }
    if(!s->full_vl || !opengpu_shader_vector_alu_valid(insn) || !tess_masked(s,insn,rd,true))return false;
    bool fp=f==1 || f==5,unary=f==1 && (fn==0x12 || fn==0x13);
    bool move=fn==0x17 && (insn&(1u<<25)) && (f==0 || f==3 || f==4);
    bool fmove=fn==0x17 && (insn&(1u<<25)) && f==5;
    bool xs=fn==0x10 && f==2 && a==0 && (insn&(1u<<25));
    bool fs=fn==0x10 && f==1 && a==0 && (insn&(1u<<25));
    bool sx=fn==0x10 && f==6 && b==0 && (insn&(1u<<25));
    bool sf=fn==0x10 && f==5 && b==0 && (insn&(1u<<25));
    bool scan=fn==0x14 && f==2,vid=scan && a==17;
    bool pop=fn==0x10 && f==2 && (a==16 || a==17);
    /* Original active masks can leave a partial final warp. No generic
     * cross-lane read may turn inactive/stale RF lanes into defined values.
     * Lane-zero extraction is separately safe outside SIMT (lane0 is live). */
    if(((f==0 || f==3 || f==4) && fn==0x0c) || fn==0x0e || fn==0x0f ||
       (f==2 && (fn<=7 || fn==0x17 || (scan && !vid))) ||
       (f==1 && (fn==1 || fn==3)) || pop)return false;
    /* Widen/narrow multi-register groups await a separate definedness proof. */
    if(fn>=0x30 || ((f==0 || f==3 || f==4) && fn>=0x2c))return false;
    if(!move && !fmove && !sx && !sf && !vid && !s->v[b].defined)return false;
    if((f==0 || f==2) && !move && !xs && !scan && !pop && !s->v[a].defined)return false;
    if(f==1 && !unary && !fs && !s->v[a].defined)return false;
    if((f==4 || f==6) && !s->x[a].defined)return false;
    if(f==5 && (!p->scalar_fpu || !(s->fp&(1u<<a))))return false;
    if((sx || sf || (f==2 && fn<=7) || (f==1 && (fn==1 || fn==3)) || fn==0x0e ||
       ((f==2 || f==6) && (fn==0x29 || fn==0x2b || fn==0x2d || fn==0x2f)) ||
       (fp && fn>=0x28 && fn<=0x2f)) && !s->v[rd].defined)return false;
    if(xs || pop){if(rd)s->x[rd]=tess_unknown();
        return true;}
    if(fs){if(!p->scalar_fpu)return false;s->fp|=1u<<rd;return true;}
    struct tess_value v=tess_unknown();
    if(move){
        if(f==0){if(!s->v[a].defined)return false;v=s->v[a];}
        if(f==4)v=s->x[a];
        if(f==3)v=tess_constant((u32)((opengpu_shader_s32)(a<<27)>>27));
    }else if((f==0 || f==3 || f==4) && (fn==0 || fn==2)){
        struct tess_value operand=f==0?s->v[a]:f==4?s->x[a]:tess_constant((u32)((opengpu_shader_s32)(a<<27)>>27));
        if(s->v[b].known && operand.known)v=tess_constant(fn==0?s->v[b].value+operand.value:s->v[b].value-operand.value);
    }
    if(!(insn&(1u<<25)))v=tess_unknown();
    tess_vector_write(s,rd,pc,v);return true;
}
static bool tess_fp(struct tess_state *s,u32 insn,const struct opengpu_tessellation_program_profile *p)
{
    u32 rd=insn>>7&31,a=insn>>15&31,b=insn>>20&31,rm=insn>>12&7,op=insn&127,fn=insn>>27;
    if(!p->scalar_fpu || ((insn>>25)&3))return false;
    if(op!=0x53){
        if(!opengpu_shader_fp_rounding_valid(rm) || !(s->fp&(1u<<a)) || !(s->fp&(1u<<b)) || !(s->fp&(1u<<fn)))return false;
        s->fp|=1u<<rd;return true;
    }
    bool round=opengpu_shader_fp_rounding_valid(rm);
    bool binary=(fn<=3 && round) || (fn==4 && rm<=2) || (fn==5 && rm<=1);
    bool sqrt=fn==0xb && !b && round,compare=fn==0x14 && rm<=2;
    bool to_int=(fn==0x18 && b<=1 && round) || (fn==0x1c && !b && rm<=1);
    bool to_fp=(fn==0x1a && b<=1 && round) || (fn==0x1e && !b && !rm);
    if(binary || sqrt){
        if(!(s->fp&(1u<<a)) || (binary && !(s->fp&(1u<<b))))return false;
        s->fp|=1u<<rd;return true;
    }
    if(compare || to_int){
        if(!(s->fp&(1u<<a)) || (compare && !(s->fp&(1u<<b))))return false;
        if(rd)s->x[rd]=tess_unknown();
        return true;
    }
    if(to_fp){if(!s->x[a].defined)return false;s->fp|=1u<<rd;return true;}
    return false;
}
bool opengpu_tessellation_shader_validate_words(const u32 *words,u32 count,
    const struct opengpu_tessellation_program_profile *p)
{
    if(!words || !count || count>OPENGPU_SHADER_MAX_INSTRUCTIONS || !opengpu_tessellation_program_profile_valid(p))return false;
    struct tess_workspace *w=kvzalloc(sizeof(*w),GFP_KERNEL);if(!w)return false;
    struct tess_state *s=&w->state;struct tess_branch *branches=w->branches;
    bool reachable=true,ceased=false,early_exit=false,scalar_branch=false,simt_program=false,valid=false;
    u32 depth=0;
    s->x[0]=tess_constant(0);for(u32 r=1;r<=9;r++)s->x[r]=tess_unknown();
    for(u32 r=2;r<=4;r++)s->x[r]=tess_constant(0);
    s->x[5]=tess_constant(p->stage==4?p->output_vertices:1);s->x[6]=s->x[7]=tess_constant(1);
    s->x[9]=tess_constant(p->lanes*p->warps);s->v[1]=tess_unknown();
#define TESS_REQUIRE(condition) do {if(!(condition))goto done;}while(0)
    for(u32 pc=0;pc<count;pc++){
        for(u32 k=0;k<TESS_BRANCHES;k++)if(branches[k].used){
            TESS_REQUIRE(branches[k].target>=pc);
            if(branches[k].target==pc){if(reachable)tess_merge(s,&branches[k].state);else *s=branches[k].state;reachable=true;branches[k].used=false;}
        }
        if(!reachable)continue;
        u32 insn=words[pc],op=insn&127,rd=insn>>7&31,a=insn>>15&31,b=insn>>20&31,f=insn>>12&7,fn=insn>>25;
        if(depth && op!=0x5b){
            if(op==0x13 || op==0x33 || op==0x37 || op==0x17)
                TESS_REQUIRE((!rd || rd==10) && (op==0x37 || (op==0x13 && !f && (!a || a==10))));
            if(op==0x43 || op==0x47 || op==0x4b || op==0x4f || op==0x53 || op==0x73)goto done;
            if(op==0x0b && fn==7)TESS_REQUIRE(!rd || rd==10);
        }
        if(insn==OPENGPU_SHADER_CEASE){
            TESS_REQUIRE(!depth && (p->stage==4 || (!s->invalid && s->emitted && s->ended)));
            ceased=true;early_exit=true;reachable=false;continue;
        }
        if(op==0x5b){
            u32 branch_pc,taken,marker;
            TESS_REQUIRE(p->stage==4 && !scalar_branch && s->full_vl);
            simt_program=true;
            if(f==3){
                opengpu_shader_s32 distance=opengpu_shader_simt_displacement(insn);u32 bound=count;
                TESS_REQUIRE(!a && !b && distance>0 && !(distance&3) && depth<TESS_BRANCHES && pc+1<count);
                marker=pc+(u32)distance/4;u32 branch=words[pc+1];
                TESS_REQUIRE((branch&127)==0x5b && ((branch>>12)&7)<=1);
                distance=opengpu_shader_simt_displacement(branch);TESS_REQUIRE(distance>0 && !(distance&3));taken=pc+1+(u32)distance/4;
                if(depth){TESS_REQUIRE(opengpu_shader_simt_scope_at(words,pc,&branch_pc,&taken,&bound));
                    bound=pc<taken?taken-1:bound-1;distance=opengpu_shader_simt_displacement(branch);taken=pc+1+(u32)distance/4;}
                TESS_REQUIRE(taken>pc+2 && marker>taken && marker<bound && marker<count &&
                    words[taken-1]==OPENGPU_SHADER_SIMT_JOIN && words[marker-1]==OPENGPU_SHADER_SIMT_JOIN && words[marker]==OPENGPU_SHADER_SIMT_JOIN);
                depth++;tess_simt_boundary(s);
            }else if(f<=1){
                TESS_REQUIRE(depth && opengpu_shader_simt_scope_at(words,pc,&branch_pc,&taken,&marker) && pc==branch_pc && s->v[a].defined && s->v[b].defined);
                u32 k;for(k=0;k<TESS_BRANCHES && branches[k].used;k++);
                TESS_REQUIRE(k<TESS_BRANCHES);branches[k].used=true;branches[k].target=taken;branches[k].state=*s;
            }else if(insn==OPENGPU_SHADER_SIMT_JOIN){
                TESS_REQUIRE(depth && opengpu_shader_simt_scope_at(words,pc,&branch_pc,&taken,&marker));
                tess_simt_boundary(s);
                u32 target=pc<taken?taken:marker+1,k;
                for(k=0;k<TESS_BRANCHES;k++)if(branches[k].used && branches[k].target==target)break;
                TESS_REQUIRE(k<TESS_BRANCHES);
                if(pc==taken-1){w->swap=*s;*s=branches[k].state;branches[k].state=w->swap;branches[k].target=marker+1;tess_simt_boundary(s);}
                else if(pc==marker)depth--;else TESS_REQUIRE(pc==marker-1);
            }else goto done;
        }else if(op==0x63 || op==0x6f){
            TESS_REQUIRE(!simt_program && !depth);scalar_branch=true;
            opengpu_shader_s32 displacement;bool take=true,fall=op==0x63;
            if(op==0x63){
                TESS_REQUIRE(f!=2 && f!=3 && s->x[a].defined && s->x[b].defined);
                u32 raw=((insn>>31)<<12)|((insn>>7&1)<<11)|((insn>>25&63)<<5)|((insn>>8&15)<<1);
                displacement=(opengpu_shader_s32)(raw<<19)>>19;
                if(s->x[a].known && s->x[b].known){u32 x=s->x[a].value,y=s->x[b].value;
                    take=f==0?x==y:f==1?x!=y:f==4?(opengpu_shader_s32)x<(opengpu_shader_s32)y:
                        f==5?(opengpu_shader_s32)x>=(opengpu_shader_s32)y:f==6?x<y:x>=y;fall=!take;}
            }else{
                TESS_REQUIRE(!rd);u32 raw=((insn>>31)<<20)|((insn>>12&255)<<12)|((insn>>20&1)<<11)|((insn>>21&1023)<<1);
                displacement=(opengpu_shader_s32)(raw<<11)>>11;
            }
            TESS_REQUIRE(displacement>0 && !(displacement&3) && (u32)displacement/4<count-pc);
            if(take)TESS_REQUIRE(tess_queue(branches,pc+(u32)displacement/4,s));
            reachable=fall;
        }else if(op==0x13 || op==0x33 || op==0x37 || op==0x17){
            struct tess_value value=tess_unknown();
            if(op==0x37)value=tess_constant(insn&0xfffff000u);
            else if(op==0x17){} /* Actual private PC is a value, never authority. */
            else{
                TESS_REQUIRE(s->x[a].defined && (op!=0x33 || s->x[b].defined));
                TESS_REQUIRE(op==0x13?((f!=1 || !fn) && (f!=5 || fn==0 || fn==0x20)):
                    (fn==0 || fn==1 || (fn==0x20 && (f==0 || f==5))));
                struct tess_value rhs=op==0x13?tess_constant((u32)((opengpu_shader_s32)insn>>20)):s->x[b];
                if(s->x[a].known && rhs.known){u32 x=s->x[a].value,y=rhs.value;
                    if(op==0x33 && fn==1){if(!f)value=tess_constant((u32)((u64)x*y));}
                    else if(f==0 && op==0x33 && fn==0x20)value=tess_constant(x-y);
                    else if(f==5 && fn==0x20)value=tess_constant((u32)((opengpu_shader_s32)x>>(y&31)));
                    else value=tess_integer(f,x,y);
                }
            }
            if(rd)s->x[rd]=value;
        }else if(op==0x57){
            /* No lane-zero extract/insert or VL changes under a divergent
             * mask: a newly defined vector may not contain lane zero. */
            TESS_REQUIRE(!depth || ((insn>>12&7)!=7 && !((insn>>26)==0x10)));
            TESS_REQUIRE(tess_vector(s,insn,pc,p));
        }
        else if(op==0x43 || op==0x47 || op==0x4b || op==0x4f || op==0x53){TESS_REQUIRE(tess_fp(s,insn,p));}
        else if(op==0x0b){
            if(insn==0x0000000b){TESS_REQUIRE(p->stage==4 && !depth && !early_exit && !tess_pending(branches));}
            else if(fn==7){
                TESS_REQUIRE(p->owned_uniforms && !f && s->x[a].defined && s->x[b].defined);
                if(rd)s->x[rd]=tess_unknown();
            }else if(fn==37){
                TESS_REQUIRE(!f && !b && s->x[a].defined && s->x[a].known);
                if(p->stage==4){
                    TESS_REQUIRE(p->version==3 && p->owned_tessellation_info && s->x[a].value==6);
                    /* x10 is the only writable shared scratch in a SIMT arm.
                     * Boundary clearing forces selector rematerialization;
                     * the returned language value is defined, never constant
                     * or a new mask/parameter authorization. */
                    TESS_REQUIRE(!depth || (a==10 && (!rd || rd==10)));
                }else{
                    TESS_REQUIRE(!depth && !s->invalid && !s->ended);
                    TESS_REQUIRE(s->x[a].value<6 || (s->x[a].value==6 &&
                        p->version==3 && p->owned_tessellation_info));
                }
                if(rd)s->x[rd]=tess_unknown();
            }else{
                TESS_REQUIRE(p->stage==5 && !depth && !f && !s->invalid && !s->ended);
                if(fn==32){TESS_REQUIRE(s->x[a].known && !s->x[a].value && s->x[b].known && s->x[b].value<((p->version==2 || p->version==3)?3u:3u+p->patch_words) && !s->emitted);if(rd)s->x[rd]=tess_unknown();}
                else if(fn==33){TESS_REQUIRE(!rd && !s->emitted && s->x[a].known && s->x[a].value<p->output_components && s->x[b].defined);}
                else if(fn==35){TESS_REQUIRE(!rd && !a && !b && !s->emitted);s->emitted=true;}
                else if(fn==36){TESS_REQUIRE(!rd && !a && !b && s->emitted);s->ended=true;}
                else goto done; /* No GS layer or shader PrimitiveID privilege. */
            }
        }else if(op==0x2b){
            u32 function=insn>>26;bool unmasked=insn&(1u<<25);
            TESS_REQUIRE(!f && s->full_vl && tess_masked(s,insn,rd,function==7 || function==21 || function==33));
            if(function==7){TESS_REQUIRE(p->owned_uniforms && s->v[a].defined && s->v[b].defined);tess_vector_write(s,rd,pc,tess_unknown());}
            else if(function==33){
                TESS_REQUIRE(p->stage==5 && (p->version==2 || p->version==3) && p->owned_patch_reads && !depth && !s->invalid && !s->emitted && !s->ended);
                TESS_REQUIRE(tess_evaluation_access(s,a,b,p));
                tess_vector_write(s,rd,pc,tess_unknown());
            }else{
                TESS_REQUIRE(p->stage==4 && (function==20 || function==21 || function==22));
                if(function==20){TESS_REQUIRE(!rd && !b && s->v[a].defined);s->params=unmasked?TESS_ALL_PARAMS:s->mask_version;}
                else{
                    TESS_REQUIRE(tess_patch_access(s,a,b,function==22,p));
                    if(function==22)TESS_REQUIRE(!rd && (s->params==TESS_ALL_PARAMS || (!unmasked && s->params && s->params==s->mask_version)));
                    else tess_vector_write(s,rd,pc,tess_unknown());
                }
            }
        }else if(op==0x0f){TESS_REQUIRE(insn==0x0330000f && !depth);} /* Not a patch barrier. */
        else if(op==0x73){TESS_REQUIRE((insn&0xfff07fffu)==0x00a05073u && !(a&~3u));}
        else goto done; /* No raw memory, indirect control, services or atomics. */
        s->x[0]=tess_constant(0);
    }
    valid=ceased && !reachable && !depth && !tess_pending(branches);
done:
#undef TESS_REQUIRE
    kvfree(w);return valid;
}
