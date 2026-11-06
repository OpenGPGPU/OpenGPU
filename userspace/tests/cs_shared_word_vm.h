/* SPDX-License-Identifier: MIT */
/* Independent CPU arithmetic/scheduling oracle for the bounded shared slice.
 * This does not model RTL timing, caches, faults, cancellation or GPU execution. */
#ifndef OPENGPU_CS_SHARED_WORD_VM_H
#define OPENGPU_CS_SHARED_WORD_VM_H
#include <assert.h>
#include <stdint.h>
#include <string.h>

static void shared_word_oracle(const uint32_t *code, unsigned words, uint32_t address,
                               uint32_t output[256], unsigned group, uint32_t seed, unsigned reverse)
{
    struct warp { uint32_t x[32], v[32][16], store[16]; unsigned pc, wait, done, vl; } w[8] = {0};
    uint32_t backing[4096+32], visible[4096], initialized[4096] = {0};
    unsigned barriers = 0, completed = 0;
    unsigned read_ordinal[128] = {0}, read_visits[4096] = {0};
    for (unsigned i=0;i<4096+32;i++) backing[i]=0xdead1234u;
    for(unsigned k=0;k<8;k++) {
        w[k].x[2]=group;w[k].x[5]=128;w[k].x[6]=w[k].x[7]=1;w[k].x[8]=k*16;w[k].x[9]=128;
        for(unsigned l=0;l<16;l++)w[k].v[1][l]=k*16+l;
    }
    for(unsigned epoch=0;completed!=8;epoch++) {
        assert(epoch<4);unsigned waiting=0;completed=0;
        for(unsigned it=0;it<8;it++) {
            struct warp *s=&w[reverse?7-it:it];
            for(unsigned steps=0;!s->done&&!s->wait;steps++) {
                assert(s->pc<words&&steps<words);uint32_t word=code[s->pc++],op=word&127;
                unsigned d=word>>7&31,a=word>>15&31,z=word>>20&31,f=word>>12&7,fn=word>>26;
                int32_t imm=(int32_t)word>>20;
                if(word==0x30500073u){s->done=1;break;}
                if(word==0x0000000bu){s->wait=1;break;}
                if(word==0x0330000fu)continue;
                if(op==0x37){s->x[d]=word&0xfffff000u;continue;}
                if(op==0x13){
                    if(f==0)s->x[d]=s->x[a]+(uint32_t)imm;
                    else if(f==1)s->x[d]=s->x[a]<<(z&31);
                    else {assert(f==4);s->x[d]=s->x[a]^(uint32_t)imm;}
                    continue;
                }
                if(op==0x33){assert(!f&&!(word>>25));s->x[d]=s->x[a]+s->x[z];continue;}
                if(op==0x57){
                    if(f==7){
                        if(word==0x01007557u){s->x[10]=16;s->vl=16;}
                        else {assert((word&0xfff07fffu)==0xc1007057u&&a==16);s->vl=a;}
                        continue;
                    }
                    assert(s->vl==16);
                    assert(word&(1u<<25));
                    for(unsigned l=0;l<16;l++){
                        uint32_t left=s->v[z][l],right=f==4||f==6?s->x[a]:f==3?a:s->v[a][l],value;
                        if(fn==0x17&&f==4){assert(!z);value=s->x[a];}
                        else if(!fn&&(f==0||f==4))value=left+right;
                        else if(fn==0x25&&(f==0||f==3||f==4))value=left<<(right&31);
                        else if(fn==0x25&&f==2)value=left*right;
                        else if(fn==0x22&&f==6){assert(right);value=left%right;}
                        else if(fn==11&&(f==0||f==4))value=left^right;
                        else {assert(!"unexpected shared compiler vector instruction");value=0;}
                        s->v[d][l]=value;
                    }
                    continue;
                }
                if(op==0x07||op==0x27){
                    assert(f==6&&(word>>25)==1&&s->vl==16);
                    for(unsigned l=0;l<16;l++){
                        uint64_t byte=(uint64_t)s->x[a]+4*l;
                        assert(byte>=address&&byte+4<=(uint64_t)address+16384&&!((byte-address)&3));
                        unsigned index=(unsigned)(byte-address)/4;
                        if(op==0x27){backing[16+index]=s->v[d][l];initialized[index]=1;}
                        else {
                            unsigned local=(unsigned)(s-w)*16+l, ordinal=read_ordinal[local]++;
                            unsigned expected=ordinal*128+(local^16u);
                            assert(ordinal<32&&index==expected&&initialized[index]&&barriers);
                            assert(visible[index]==seed+(group<<16)+expected);
                            read_visits[index]++;s->v[d][l]=visible[index];
                        }
                    }
                    continue;
                }
                assert(op==0x2b&&(word&(1u<<25))&&!f);
                for(unsigned l=0;l<16;l++){
                    if(fn==14){assert(!d&&!z);s->store[l]=s->v[a][l];}
                    else {assert(fn==16&&!d&&s->v[a][l]==0&&s->v[z][l]<256*4&&!(s->v[z][l]&3));
                        output[s->v[z][l]/4]=s->store[l];}
                }
            }
            waiting+=s->wait;completed+=s->done;
        }
        assert(waiting==8||completed==8);
        if(waiting){
            barriers++;for(unsigned i=0;i<4096;i++)visible[i]=backing[16+i];
            for(unsigned k=0;k<8;k++)w[k].wait=0;
        }
    }
    assert(barriers==2);
    for(unsigned i=0;i<128;i++)assert(read_ordinal[i]==32);
    for(unsigned i=0;i<4096;i++)assert(read_visits[i]==1);
    for(unsigned i=0;i<16;i++)assert(backing[i]==0xdead1234u&&backing[4112+i]==0xdead1234u);
    for(unsigned i=0;i<4096;i++)assert(initialized[i]&&backing[16+i]==seed+(group<<16)+i);
}
#endif
