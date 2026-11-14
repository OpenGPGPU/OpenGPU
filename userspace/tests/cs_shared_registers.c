/* SPDX-License-Identifier: MIT */
/* Actual instruction lowering and independent native-word arithmetic/counter
 * oracle. CPU only: no shader publication, MMU, timing or GPU execution. */
#include <assert.h>
#include <stdio.h>
#include "../gallium/opengpu_cs_emit.c"
#include "opengpu_shader_validator.h"
#include "tgsi/tgsi_build.h"
#include "cs_word_vm.h"

static struct tgsi_full_src_register operand(unsigned file,unsigned index,unsigned c)
{
    struct tgsi_full_src_register s={0};s.Register.File=file;s.Register.Index=index;
    s.Register.SwizzleX=s.Register.SwizzleY=s.Register.SwizzleZ=s.Register.SwizzleW=c;return s;
}
static void alu(struct build *b,unsigned op,unsigned t,unsigned mask,
                struct tgsi_full_src_register a,struct tgsi_full_src_register z)
{
    struct tgsi_full_instruction i=tgsi_default_full_instruction();
    i.Instruction.Opcode=op;i.Instruction.NumDstRegs=1;
    i.Instruction.NumSrcRegs=op==TGSI_OPCODE_MOV||op==TGSI_OPCODE_UARL?1:2;
    i.Dst[0].Register.File=op==TGSI_OPCODE_UARL?TGSI_FILE_ADDRESS:TGSI_FILE_TEMPORARY;
    i.Dst[0].Register.Index=t;i.Dst[0].Register.WriteMask=mask;i.Src[0]=a;i.Src[1]=z;
    assert(!instruction(b,&i)&&!b->failed);
}
static void counter_read(struct build *b)
{
    struct tgsi_full_instruction i=tgsi_default_full_instruction();
    i.Instruction.Opcode=TGSI_OPCODE_LOAD;i.Instruction.NumSrcRegs=2;i.Instruction.NumDstRegs=1;
    i.Instruction.Memory=1;i.Dst[0].Register.File=TGSI_FILE_TEMPORARY;
    i.Dst[0].Register.Index=3;i.Dst[0].Register.WriteMask=1;
    i.Src[0]=operand(TGSI_FILE_HW_ATOMIC,0,0);
    i.Src[0].Register.Dimension=i.Src[0].Register.Indirect=1;
    i.Src[0].Indirect.File=TGSI_FILE_ADDRESS;i.Src[0].Indirect.Index=2;
    i.Src[1]=operand(TGSI_FILE_IMMEDIATE,0,0);
    assert(!instruction(b,&i));
}
static void output(struct build *b,unsigned plane,unsigned t,unsigned c)
{
    /* Actual affine expression is materialized by STORE lowering; it is not
     * an unchecked global pointer or a CPU-filled output value. */
    b->expr[63][0]=(struct shared_expr){EXPR_LOCAL,4,plane*512};
    struct tgsi_full_instruction i=tgsi_default_full_instruction();
    i.Instruction.Opcode=TGSI_OPCODE_STORE;i.Instruction.NumSrcRegs=2;i.Instruction.NumDstRegs=1;
    i.Instruction.Memory=1;i.Memory.Texture=TGSI_TEXTURE_BUFFER;
    i.Dst[0].Register.File=TGSI_FILE_BUFFER;i.Dst[0].Register.WriteMask=1;
    i.Src[0]=operand(TGSI_FILE_TEMPORARY,63,0);i.Src[1]=operand(TGSI_FILE_TEMPORARY,t,c);
    assert(!instruction(b,&i));
}
int main(void)
{
    struct opengpu_cs_shared shared={0x10000000,16384,16,16384};
    struct build b={0};b.shared=&shared;b.next_reg=2;b.buffers=1;
    b.p.fixed_block[0]=128;b.p.fixed_block[1]=b.p.fixed_block[2]=1;
    b.sys_mask[0]=b.sys_mask[1]=7;b.sys_name[0]=TGSI_SEMANTIC_THREAD_ID;b.sys_name[1]=TGSI_SEMANTIC_BLOCK_ID;
    for(unsigned t=0;t<64;t++)b.declared[t]=15;
    b.nimm=2;b.imm[0][0]=0;b.imm[0][1]=7;b.imm[0][2]=11;b.imm[0][3]=19;
    b.imm[1][0]=3;b.address_declared=5;b.counters=255;
    word(&b,0x01007557u);
#define I(c) operand(TGSI_FILE_IMMEDIATE,0,c)
#define T(t,c) operand(TGSI_FILE_TEMPORARY,t,c)
#define LOCAL operand(TGSI_FILE_SYSTEM_VALUE,0,0)
#define GROUP operand(TGSI_FILE_SYSTEM_VALUE,1,0)
    struct tgsi_full_src_register constants=I(1);constants.Register.SwizzleY=2;
    alu(&b,TGSI_OPCODE_UADD,2,3,GROUP,constants); /* (group+7,group+11) */
    struct tgsi_full_src_register swapped=T(2,1);swapped.Register.SwizzleY=0;
    struct tgsi_full_src_register original=T(2,0);original.Register.SwizzleY=1;
    alu(&b,TGSI_OPCODE_SHL,2,3,swapped,original); /* simultaneous alias/swizzle */
    alu(&b,TGSI_OPCODE_UARL,2,1,operand(TGSI_FILE_IMMEDIATE,1,0),I(0));
    unsigned counter_slot=b.counter_address;assert(counter_slot>=2&&counter_slot<=21);
    /* Partial replacement releases only x; y and the captured counter address
     * must survive. The expression itself must never read a reused hint. */
    alu(&b,TGSI_OPCODE_UADD,2,1,LOCAL,I(3));
    for(unsigned t=4;t<=42;t++) {
        alu(&b,TGSI_OPCODE_UADD,t,1,GROUP,I(1));
        alu(&b,TGSI_OPCODE_UADD,t,1,LOCAL,I(1));
    }
    assert(b.counter_address==counter_slot);
    alu(&b,TGSI_OPCODE_UADD,2,1,T(2,0),T(2,1)); /* expr -> resident, src == dst */
    counter_read(&b);
    output(&b,0,2,0);output(&b,1,2,1);output(&b,2,3,0);output(&b,3,42,0);
    word(&b,0x0330000fu);word(&b,OPENGPU_SHADER_CEASE);assert(!b.failed&&b.p.words<=512);
    assert(opengpu_compute_shader_validate_words_shared_resources(b.p.code,b.p.words,12,128,
        false,false,false,true,false,false,false,true,false,shared.base,shared.bytes,shared.lanes));
    uint32_t bytes[16+512+16],counters[8];
    for(unsigned i=0;i<544;i++)bytes[i]=0xfeedabcd;
    for(unsigned i=0;i<8;i++)counters[i]=0xc0000000u+i;
    struct cs_vm_view views[4]={{bytes+16,2048,3}},counter={counters,32,3};
    const uint32_t grid[3]={4,1,1},group[3]={3,0,0},local[3]={128,1,1};
    cs_vm_group_resources_counters_checked(b.p.code,b.p.words,grid,group,local,views,NULL,NULL,NULL,&counter);
    for(unsigned id=0;id<128;id++) {
        assert(bytes[16+id]==163859u+id);assert(bytes[16+128+id]==163840u);
        assert(bytes[16+256+id]==0xc0000003u);assert(bytes[16+384+id]==id+7);
    }
    for(unsigned i=0;i<16;i++)assert(bytes[i]==0xfeedabcd&&bytes[528+i]==0xfeedabcd);
    for(unsigned i=0;i<8;i++)assert(counters[i]==0xc0000000u+i);
    /* Twenty genuinely resident values still exhaust the exact old physical
     * pool. Symbolic reuse does not invent more physical registers. */
    struct build capacity={0};capacity.shared=&shared;capacity.next_reg=2;
    capacity.p.fixed_block[0]=128;capacity.p.fixed_block[1]=capacity.p.fixed_block[2]=1;
    capacity.sys_mask[1]=1;capacity.sys_name[1]=TGSI_SEMANTIC_BLOCK_ID;
    capacity.nimm=1;capacity.imm[0][1]=7;
    for(unsigned t=0;t<64;t++)capacity.declared[t]=1;
    for(unsigned t=0;t<20;t++)alu(&capacity,TGSI_OPCODE_UADD,t,1,GROUP,I(1));
    struct tgsi_full_instruction excess=tgsi_default_full_instruction();
    excess.Instruction.Opcode=TGSI_OPCODE_UADD;excess.Instruction.NumSrcRegs=2;
    excess.Instruction.NumDstRegs=1;excess.Dst[0].Register.File=TGSI_FILE_TEMPORARY;
    excess.Dst[0].Register.Index=20;excess.Dst[0].Register.WriteMask=1;
    excess.Src[0]=GROUP;excess.Src[1]=I(1);assert(instruction(&capacity,&excess)<0);
    (void)cs_vm_group;
    printf("shared symbolic-register reuse: partial/swizzle/alias/39 replacements/ADDR2, %u words PASS (CPU only)\n",b.p.words);
    return 0;
}
