// SPDX-License-Identifier: MIT
#include <assert.h>
#include <stdint.h>
#include "../opengpu_shader_validator.h"
#include "../../userspace/examples/compute_counter_program.h"
#include "opengpu_compute_uniform_counter_source_program.h"

static uint32_t storage(unsigned fn, unsigned rd, unsigned vs1, unsigned vs2)
{
    return fn << 26 | 1u << 25 | vs2 << 20 | vs1 << 15 | rd << 7 | 0x2bu;
}
static bool valid(const uint32_t *p, unsigned n)
{
    return opengpu_compute_shader_validate_words_storage(p, n, 64, 4, false);
}
int main(void)
{
    {
        uint32_t full[512];unsigned n=opg_full_program(full,false);
#define VALID_FULL(counter) opengpu_compute_shader_validate_words_uniform_resources( \
        full,n,4096,128,true,true,true,true,true,true,true,counter)
        assert(n==281 && ((n*4+63)&~63u)<=2048 && VALID_FULL(true));
        assert(!VALID_FULL(false));
        assert(!opengpu_compute_shader_validate_words_counter_resources(
            full,n,4096,128,true,true,true,true,true,true,true));
        for(unsigned i=0;i<n;i++)if((full[i]&127)==0x2b && (full[i]>>26)==7){
            full[i]&=~(1u<<25);break;
        }
        assert(!VALID_FULL(true));
        n=opg_full_program(full,true);assert(n && VALID_FULL(true));
        n=opg_default_producer(full,0xd1100000u);
        assert(n && opengpu_compute_shader_validate_words_features(full,n,4096,128,true,true));
        n=opg_counter_consumer(full);
        assert(n && opengpu_compute_shader_validate_words_disjoint_resources(full,n,4096,128,true,true));
#undef VALID_FULL
    }
    const uint32_t vl4 = 0xc1027057u;
    uint32_t p[] = { vl4, storage(14, 0, 1, 0), storage(16, 0, 1, 1),
                     storage(15, 2, 1, 1), OPENGPU_SHADER_CEASE };
    assert(valid(p, 5));
    {
        uint32_t shared[] = { 0x00641293u, 0xc100f057u, 0x5e003157u,
            0x5e02c257u, 0x5e03b1d7u, 0x3a01802bu, 0x4241002bu,
            0x00040463u, 0x00000013u, 0x0000000bu, 0x5e003257u,
            0x3e4102abu, 0x00241293u, 0x005082b3u, 0x0202e2a7u,
            0x0000000bu, OPENGPU_SHADER_CEASE };
        assert(opengpu_compute_shader_validate_words_storage(shared,17,64,8,false));
        assert(opengpu_compute_shader_validate_words_disjoint_resources(shared,17,64,8,false,true));
        shared[7] = 0x00040663u; /* skip the first barrier */
        assert(!opengpu_compute_shader_validate_words_storage(shared,17,64,8,false));
    }
    {
        uint32_t fenced[] = { vl4, storage(14,0,1,0), storage(16,0,1,1),
            0x0330000fu, storage(15,2,1,1), 0x0020a223u, 0x0330000fu,
            OPENGPU_SHADER_CEASE };
        assert(valid(fenced, 8));
        assert(opengpu_compute_shader_validate_words_disjoint_resources(
            fenced, 8, 64, 4, false, true));
        fenced[3] ^= 1u << 20;
        assert(!valid(fenced, 8));
    }
    {
        uint32_t mixed[32]; unsigned n=0;
        mixed[n++]=0x00100313u;
        for (unsigned pair=0; pair<8; ++pair) {
            unsigned reg=pair==0 ? 6 : 0;
            mixed[n++]=(3u<<25)|(reg<<20)|(reg<<15)|(pair<<12)|0x0bu;
        }
        mixed[n++]=(5u<<25)|(7u<<7)|0x0bu;
        mixed[n++]=0x0000000bu; mixed[n++]=0xc100f057u;
        mixed[n++]=0x5e003157u; mixed[n++]=0x00641293u; mixed[n++]=0x5e02c257u;
        mixed[n++]=(23u<<26)|(1u<<25)|(7u<<15)|(4u<<12)|(3u<<7)|0x57u;
        mixed[n++]=0x3a01802bu; mixed[n++]=0x4241002bu;
        mixed[n++]=0x00040463u; mixed[n++]=0x00000013u; mixed[n++]=0x0000000bu;
        mixed[n++]=0x5e003257u; mixed[n++]=0x3e4102abu;
        mixed[n++]=0x00241293u; mixed[n++]=0x005082b3u; mixed[n++]=0x0202e2a7u;
        mixed[n++]=0x0000000bu; mixed[n++]=OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_shader_validate_words_disjoint_resources(mixed,n,64,8,false,true));
        mixed[18]=0x00040663u; /* branch skips the following barrier */
        assert(!opengpu_compute_shader_validate_words_disjoint_resources(mixed,n,64,8,false,true));
        mixed[18]=0x00040463u;
        mixed[10]=mixed[20]=mixed[26]=0x0330000fu;
        mixed[21]=0x00000013u; /* preserve the per-warp storage offset */
        assert(opengpu_compute_shader_validate_words_disjoint_resources(mixed,n,64,8,false,true));
        mixed[20]^=1u<<20;
        assert(!opengpu_compute_shader_validate_words_disjoint_resources(mixed,n,64,8,false,true));
    }
    {
        uint32_t alias[32]; unsigned n=0;
        alias[n++]=0x00100313u;
        for(unsigned pair=0;pair<8;++pair){unsigned reg=pair==0?6:0;
            alias[n++]=(3u<<25)|(reg<<20)|(reg<<15)|(pair<<12)|0x0bu;}
        alias[n++]=(5u<<25)|(7u<<7)|0x0bu;alias[n++]=0x0070a023u;alias[n++]=0x0000000bu;
        alias[n++]=0xc100f057u;alias[n++]=0x5e003157u;alias[n++]=0x5e03b1d7u;
        alias[n++]=0x01400193u;alias[n++]=0x5e01c257u;alias[n++]=0x3a01802bu;
        alias[n++]=0x4241002bu;alias[n++]=0x0330000fu;alias[n++]=0x0000000bu;
        for(unsigned pair=0;pair<8;++pair){unsigned reg=pair==0?6:0;
            alias[n++]=(3u<<25)|(reg<<20)|(reg<<15)|(pair<<12)|0x0bu;}
        alias[n++]=(5u<<25)|(10u<<7)|0x0bu;alias[n++]=0x00a0a223u;
        alias[n++]=OPENGPU_SHADER_CEASE;
        assert(n==32 && opengpu_compute_shader_validate_words_disjoint_resources(alias,n,64,8,false,true));
    }
    for (unsigned fn = 0; fn < 64; ++fn) {
        uint32_t load[] = { vl4, storage(fn, 2, 1, 1), OPENGPU_SHADER_CEASE };
        assert(valid(load, 3) == (fn == 15));
    }
    {
        uint32_t use[] = { vl4, storage(15, 2, 1, 1),
                           storage(14, 0, 2, 0), storage(16, 0, 1, 1),
                           OPENGPU_SHADER_CEASE };
        assert(valid(use, 5));
    }
    assert(!opengpu_compute_shader_validate_words(p, 5, 64, 4));
    assert(!opengpu_compute_shader_validate_words_disjoint_textures(p, 5, 64, 4, false, true));
    p[1] = storage(14, 0, 3, 0); assert(!valid(p, 5));
    p[1] = storage(14, 1, 1, 0); assert(!valid(p, 5));
    p[1] = storage(14, 0, 1, 0);
    p[2] = storage(16, 0, 1, 3); assert(!valid(p, 5));
    p[2] = storage(16, 1, 1, 1); assert(!valid(p, 5));
    p[2] = storage(16, 0, 1, 1) & ~(1u << 25); assert(!valid(p, 5));
    p[2] = storage(16, 0, 1, 1) | (1u << 12); assert(!valid(p, 5));
    p[2] = storage(16, 0, 1, 1);
    p[3] = storage(15, 2, 3, 1); assert(!valid(p, 5));
    p[3] = storage(15, 2, 1, 3); assert(!valid(p, 5));
    p[3] = storage(15, 2, 1, 1);
    p[1] = vl4; assert(!valid(p, 5)); /* no store-value capture */
    p[1] = storage(14, 0, 1, 0); p[2] = vl4;
    p[3] = storage(16, 0, 1, 1); assert(!valid(p, 5)); /* VL reset */
    {
        uint32_t branch[] = { vl4, 0x00000463u, storage(14, 0, 1, 0),
                              storage(16, 0, 1, 1), OPENGPU_SHADER_CEASE };
        assert(!valid(branch, 5)); /* capture occurs on only one path */
    }
    {
        uint32_t atomic[] = { vl4, storage(24,0,1,1), storage(25,2,1,1), OPENGPU_SHADER_CEASE };
#define VALID_ATOMIC(enabled) opengpu_compute_shader_validate_words_all_resources(atomic,4,64,4,false,false,false,true,false,false,(enabled))
        for (unsigned fn=24;fn<=26;++fn) for (unsigned form=0;form<8;++form) {
            atomic[2]=storage(fn,2,1,1)|(form<<12);
            assert(VALID_ATOMIC(true)==(fn==25 || (fn==26 && form<2)));
            assert(!VALID_ATOMIC(false));
        }
        atomic[2]=storage(25,2,1,1); atomic[1]=vl4;
        assert(!VALID_ATOMIC(true)); /* capture missing */
        atomic[1]=storage(24,0,1,1)&~(1u<<25);
        assert(!VALID_ATOMIC(true)); /* masked capture */
        atomic[1]=storage(24,0,1,3); assert(!VALID_ATOMIC(true));
        atomic[1]=storage(24,0,1,1); atomic[2]&=~(1u<<25);
        assert(!VALID_ATOMIC(true)); /* masked atomic */
        atomic[2]=storage(25,2,1,1); atomic[1]=storage(24,0,1,1);
        assert(!opengpu_compute_shader_validate_words_all_resources(atomic,4,64,4,false,false,false,false,false,false,true));
        {
            uint32_t branch[] = { vl4, 0x00000463u, storage(24,0,1,1),
                                  storage(25,2,1,1), OPENGPU_SHADER_CEASE };
            assert(!opengpu_compute_shader_validate_words_all_resources(branch,5,64,4,false,false,false,true,false,false,true));
            branch[1]=storage(24,0,1,1); branch[2]=vl4;
            assert(!opengpu_compute_shader_validate_words_all_resources(branch,5,64,4,false,false,false,true,false,false,true));
        }
#undef VALID_ATOMIC
    }
    {
        uint32_t mixed[] = { vl4, storage(14,0,1,0), storage(16,0,1,1),
            0x00808493u, 0x00100513u, 0x00000593u, 0, OPENGPU_SHADER_CEASE };
        for (unsigned op=0; op<32; ++op)
            for (unsigned width=0; width<8; ++width)
                for (unsigned order=0; order<4; ++order) {
                    bool supported = op==0 || op==1 || op==4 || op==5 ||
                        op==8 || op==12 || op==16 || op==20 || op==24 || op==28;
                    mixed[6]=(op<<27)|(order<<25)|(10u<<20)|(9u<<15)|(width<<12)|(11u<<7)|0x2fu;
                    assert(opengpu_compute_shader_validate_words_storage_disjoint_atomics(
                        mixed,8,64,4,false,true) == (supported && width==2 && order==0));
                    assert(!opengpu_compute_shader_validate_words_storage_disjoint_atomics(
                        mixed,8,64,4,false,false));
                    assert(!opengpu_compute_shader_validate_words_storage(mixed,8,64,4,false));
                }
    }
    {
        uint32_t counter[] = { vl4, storage(17,2,1,0), OPENGPU_SHADER_CEASE };
#define VALID_COUNTER() opengpu_compute_shader_validate_words_counter_resources(counter,3,64,4,false,false,false,false,false,false,false)
        for (unsigned fn=0; fn<64; ++fn) {
            counter[1]=storage(fn,2,1,0);
            assert(VALID_COUNTER() == (fn>=17 && fn<=19));
            if (fn>=17 && fn<=19) {
                assert(!opengpu_compute_shader_validate_words(counter,3,64,4));
                assert(!opengpu_compute_shader_validate_words_all_resources(counter,3,64,4,false,true,true,true,true,true,true));
            }
        }
        counter[1]=storage(17,2,1,0)&~(1u<<25); assert(!VALID_COUNTER());
        counter[1]=storage(17,2,1,1); assert(!VALID_COUNTER());
        counter[1]=storage(17,2,1,0)|(1u<<12); assert(!VALID_COUNTER());
        counter[1]=storage(17,2,3,0); assert(!VALID_COUNTER());
        counter[0]=0x00000013u; counter[1]=storage(17,2,1,0); assert(!VALID_COUNTER());
#undef VALID_COUNTER
    }
    {
        const uint32_t *smoke=opengpu_counter_smoke_program;
        const unsigned n=sizeof(opengpu_counter_smoke_program)/sizeof(smoke[0]);
        assert(opengpu_compute_shader_validate_words_counter_resources(
            smoke,n,64,4,false,false,false,false,false,false,false));
        assert(!opengpu_compute_shader_validate_words(smoke,n,64,4));
    }
    return 0;
}
