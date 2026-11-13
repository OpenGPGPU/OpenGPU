/* Independent native integer/uniform decoder; no scheduling or RTL claim. */
#ifndef OPENGPU_GRAPHICS_UNIFORM_WORD_VM_H
#define OPENGPU_GRAPHICS_UNIFORM_WORD_VM_H
#include <assert.h>
#include <stdint.h>
#include <string.h>
struct graphics_uniform_view { const uint32_t *words; unsigned bytes; };
static unsigned graphics_uniform_word_vm(const uint32_t *code, unsigned words,
    uint32_t *payload, unsigned payload_words, unsigned capacity, unsigned count,
    const struct graphics_uniform_view uniforms[13])
{
    const uint32_t virtual_base = 0x12000000u;
    unsigned uniform_reads = 0;
    assert(count && count <= capacity);
    for (unsigned base=0;base<count;base+=16) {
        uint32_t x[32]={0},v[32][16]={{0}};
        unsigned active=count-base<16?count-base:16,ended=0;
        x[1]=virtual_base;x[8]=base;x[9]=capacity;
        for(unsigned pc=0;pc<words;pc++) {
            uint32_t w=code[pc],op=w&127u,rd=w>>7&31u;
            unsigned a=w>>15&31u,z=w>>20&31u,f=w>>12&7u,fn=w>>26;
            int32_t immediate=(int32_t)w>>20;
            if(w==0x30500073u){ended=1;break;}
            if(op==0x37){assert(rd);x[rd]=w&0xfffff000u;continue;}
            if(op==0x13){assert(rd);
                if(f==0)x[rd]=x[a]+immediate;
                else {assert(f==1);x[rd]=x[a]<<(z&31);}
                continue;
            }
            if(op==0x33){assert(rd&&!f&&(w>>25)<=1);x[rd]=(w>>25)?x[a]*x[z]:x[a]+x[z];continue;}
            if(op==0x0f){assert(w==0x0330000fu);continue;}
            if(op==0x0b){
                assert((w>>25)==7&&!f&&rd);
                unsigned selector=x[a],slot=selector==31?0:selector+1,offset=x[z];
                assert((selector<12||selector==31)&&uniforms[slot].words&&!(offset&3));
                assert(offset<=uniforms[slot].bytes&&4<=uniforms[slot].bytes-offset);
                x[rd]=uniforms[slot].words[offset/4];uniform_reads++;continue;
            }
            if(op==7||op==0x27){
                assert(f==6&&(w>>25)==1&&x[a]>=virtual_base&&!(x[a]&3));
                unsigned offset=(x[a]-virtual_base)/4;
                assert(offset<=payload_words&&active<=payload_words-offset);
                for(unsigned lane=0;lane<active;lane++)
                    if(op==7)v[rd][lane]=payload[offset+lane];else payload[offset+lane]=v[rd][lane];
                continue;
            }
            assert(op==0x57);
            if(f==7){assert(w==0x01007557u);x[rd]=16;continue;}
            assert((w>>25)&1);
            for(unsigned lane=0;lane<active;lane++){
                uint32_t left=v[z][lane],right=f==4?x[a]:v[a][lane],value=0;
                if(fn==0x17){assert(f==4&&!z);value=x[a];}
                else if(fn==0){assert(f==0||f==4);value=left+right;}
                else if(fn==0x25&&f==2)value=left*right;
                else if(fn==0x25&&f==0)value=left<<(right&31);
                else if(fn==0x28&&f==0)value=left>>(right&31);
                else if(fn==9&&f==0)value=left&right;
                else if(fn==10&&f==0)value=left|right;
                else {assert(fn==11&&!f);value=left^right;}
                v[rd][lane]=value;
            }
            assert(x[0]==0);
        }
        assert(ended);
    }
    return uniform_reads;
}
#endif
