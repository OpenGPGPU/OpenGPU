/* SPDX-License-Identifier: MIT */
/* Independent arithmetic/scheduling decoder, never a GPU/timing/fault model. */
#ifndef OPENGPU_CS_SHARED_ATOMIC_WORD_VM_H
#define OPENGPU_CS_SHARED_ATOMIC_WORD_VM_H
#include <assert.h>
#include <stdint.h>
#include <string.h>

static uint32_t shared_atomic_reference(unsigned operation,uint32_t old,uint32_t value,uint32_t compare)
{
    switch(operation) {
    case 0:return value;case 1:return old+value;case 2:return old^value;
    case 3:return old|value;case 4:return old&value;
    case 5:return (int32_t)old<(int32_t)value?old:value;
    case 6:return (int32_t)old>(int32_t)value?old:value;
    case 7:return old<value?old:value;case 8:return old>value?old:value;
    case 9:return old==compare?value:old;
    default:assert(!"unknown shared atomic operation");return 0;
    }
}

static void shared_atomic_word_vm(const uint32_t *code,unsigned words,uint32_t base,
    uint32_t output[512],unsigned group,unsigned reverse,unsigned operation)
{
    struct warp {uint32_t x[32],v[32][16],value[16],compare[16],store[16];unsigned pc,wait,done,vl,params;} w[8]={0};
    uint32_t data[4096+32],initialized[4096]={0};unsigned barriers=0,complete=0,atomic_count=0;
    for(unsigned i=0;i<4096+32;i++)data[i]=0xdead1234u;
    for(unsigned k=0;k<8;k++) {
        w[k].x[2]=group;w[k].x[5]=128;w[k].x[6]=w[k].x[7]=1;w[k].x[8]=k*16;w[k].x[9]=128;
        for(unsigned l=0;l<16;l++)w[k].v[1][l]=k*16+l;
    }
    for(unsigned epoch=0;complete!=8;epoch++) {
        assert(epoch<3);unsigned waiting=0;complete=0;
        for(unsigned it=0;it<8;it++) {
            struct warp *s=&w[reverse?7-it:it];
            for(unsigned steps=0;!s->done&&!s->wait;steps++) {
                assert(s->pc<words&&steps<words);uint32_t insn=code[s->pc++],op=insn&127;
                unsigned d=insn>>7&31,a=insn>>15&31,z=insn>>20&31,f=insn>>12&7,fn=insn>>26;
                int32_t imm=(int32_t)insn>>20;
                if(insn==0x30500073u){s->done=1;break;}
                if(insn==0x0000000bu){s->wait=1;break;}
                if(insn==0x0330000fu)continue;
                if(op==0x37){s->x[d]=insn&0xfffff000u;continue;}
                if(op==0x13) {
                    if(f==0)s->x[d]=s->x[a]+(uint32_t)imm;
                    else if(f==1)s->x[d]=s->x[a]<<(z&31);
                    else {assert(f==4);s->x[d]=s->x[a]^(uint32_t)imm;}continue;
                }
                if(op==0x33){assert(!f&&!(insn>>25));s->x[d]=s->x[a]+s->x[z];continue;}
                if(op==0x57) {
                    if(f==7) {
                        if(insn==0x01007557u){s->x[10]=16;s->vl=16;}
                        else {assert((insn&0xfff07fffu)==0xc1007057u&&a==16);s->vl=a;}
                        continue;
                    }
                    assert(s->vl==16&&(insn&(1u<<25)));
                    for(unsigned l=0;l<16;l++) {
                        uint32_t left=s->v[z][l],right=f==4||f==6?s->x[a]:f==3?a:s->v[a][l],v;
                        if(fn==0x17&&f==4){assert(!z);v=s->x[a];}
                        else if(!fn&&(f==0||f==4))v=left+right;
                        else if(fn==0x25&&(f==0||f==3||f==4))v=left<<(right&31);
                        else if(fn==0x25&&f==2)v=left*right;
                        else if(fn==0x22&&f==6){assert(right);v=left%right;}
                        else {assert(!"unexpected shared atomic compiler ALU");v=0;}
                        s->v[d][l]=v;
                    }continue;
                }
                if(op==0x27) {
                    assert(f==6&&(insn>>25)==1&&s->vl==16);
                    for(unsigned l=0;l<16;l++) {
                        uint64_t address=(uint64_t)s->x[a]+4*l;
                        assert(address>=base&&address+4<=(uint64_t)base+16384&&!((address-base)&3));
                        unsigned index=(unsigned)(address-base)/4;
                        data[16+index]=s->v[d][l];initialized[index]=1;
                    }continue;
                }
                assert(op==0x2b&&(insn&(1u<<25)));
                if(fn==30) {
                    assert(!d&&!f);for(unsigned l=0;l<16;l++){s->value[l]=s->v[a][l];s->compare[l]=s->v[z][l];}
                    s->params=1;continue;
                }
                if(fn==31||fn==32) {
                    assert(barriers==1&&s->params&&s->vl==16&&operation==(fn==31?f:8+f));
                    for(unsigned l=0;l<16;l++) {
                        uint64_t address=(uint64_t)s->x[a]+(z?s->v[z][l]:0);
                        assert(address>=base&&address+4<=(uint64_t)base+16384&&!((address-base)&3));
                        unsigned index=(unsigned)(address-base)/4;assert(initialized[index]);
                        uint32_t old=data[16+index];
                        data[16+index]=shared_atomic_reference(operation,old,s->value[l],s->compare[l]);
                        s->v[d][l]=old;atomic_count++;
                    }continue;
                }
                assert(!f);
                for(unsigned l=0;l<16;l++) {
                    if(fn==14){assert(!d&&!z);s->store[l]=s->v[a][l];}
                    else {assert(fn==16&&!d&&s->v[a][l]==0&&s->v[z][l]<512*4&&!(s->v[z][l]&3));output[s->v[z][l]/4]=s->store[l];}
                }
            }
            waiting+=s->wait;complete+=s->done;
        }
        assert(waiting==8||complete==8);
        if(waiting){barriers++;for(unsigned k=0;k<8;k++)w[k].wait=0;}
    }
    assert(barriers==1&&atomic_count==256);
    uint32_t expected=0x80000000u;
    for(unsigned ordinal=0;ordinal<128;ordinal++) {
        unsigned id=(reverse?7-ordinal/16:ordinal/16)*16+ordinal%16;
        assert(output[group*256+id]==expected&&output[group*256+128+id]==expected);
        expected=shared_atomic_reference(operation,expected,0xffffffffu,0x80000000u);
    }
    for(unsigned i=0;i<4096;i++)assert(initialized[i]&&data[16+i]==((i==0||i==4095)?expected:0x80000000u));
    for(unsigned i=0;i<16;i++)assert(data[i]==0xdead1234u&&data[4112+i]==0xdead1234u);
}
#endif
