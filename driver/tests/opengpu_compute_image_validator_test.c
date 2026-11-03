// SPDX-License-Identifier: MIT
#include <assert.h>
#include <stdint.h>
#include "../opengpu_shader_validator.h"
#include "opengpu_compute_counter_resources_program.h"

static uint32_t image(unsigned fn, unsigned sub, unsigned rd, unsigned a, unsigned b)
{
    return fn << 26 | 1u << 25 | b << 20 | a << 15 | sub << 12 | rd << 7 | 0x2bu;
}
static uint32_t vecimm(unsigned rd, unsigned value)
{ return 23u<<26 | 1u<<25 | value<<15 | 3u<<12 | rd<<7 | 0x57u; }
static bool valid(const uint32_t *p, unsigned n)
{
    return opengpu_compute_shader_validate_words_images(p, n, 64, 4, false, true);
}
int main(void)
{
    const uint32_t vl4 = 0xc1027057u;
    uint32_t p[] = { vl4, image(8,0,0,1,1), image(8,1,0,1,1),
        image(9,0,2,1,1), image(11,1,3,0,0), image(8,2,0,2,3),
        image(8,3,0,2,3), image(10,0,0,1,1), OPENGPU_SHADER_CEASE };
    assert(valid(p, 9));
    assert(!opengpu_compute_shader_validate_words(p, 9, 64, 4));
    assert(!opengpu_compute_shader_validate_words_storage(p, 9, 64, 4, false));
    p[1] = image(8,0,1,1,1); assert(!valid(p, 9));
    p[1] = image(8,4,0,1,1); assert(!valid(p, 9));
    p[1] = image(8,0,0,3,1); assert(!valid(p, 9));
    p[1] = image(8,0,0,1,1);
    p[2] = vl4; assert(!valid(p, 9));
    p[2] = image(8,1,0,1,1);
    p[3] = image(9,1,2,1,1); assert(!valid(p, 9));
    p[3] = image(9,0,2,1,1) & ~(1u << 25); assert(!valid(p, 9));
    p[3] = image(9,0,2,1,1);
    p[4] = image(11,0,3,0,0); assert(!valid(p, 9));
    p[4] = image(11,1,3,1,0); assert(!valid(p, 9));
    p[4] = image(11,1,3,0,0);
    p[6] = image(8,0,0,2,3); assert(!valid(p, 9));
    p[6] = image(8,3,0,2,3);
    p[7] = image(10,0,1,1,1); assert(!valid(p, 9));
    p[7] = image(10,0,0,1,1); assert(valid(p, 9));
    {
        uint32_t branch[] = { vl4, image(8,0,0,1,1), 0x00000463u,
            image(8,1,0,1,1), image(9,0,2,1,1), OPENGPU_SHADER_CEASE };
        assert(!valid(branch, 6));
    }
    {
        uint32_t stale[] = { vl4, image(8,0,0,1,1), image(8,1,0,1,1),
            image(9,0,2,1,1), image(8,0,0,1,1), image(11,1,3,0,0), OPENGPU_SHADER_CEASE };
        assert(!valid(stale, 7));
    }
    {
        uint32_t sync[] = { 0x0330000fu, OPENGPU_SHADER_CEASE };
        assert(valid(sync, 2));
        sync[0] ^= 1u << 20;
        assert(!valid(sync, 2));
        sync[0] = 0x0000000bu;
        assert(valid(sync, 2));
        uint32_t divergent[] = { vl4, 0x00000463u, 0x0000000bu,
                                 0x00000013u, OPENGPU_SHADER_CEASE };
        assert(!valid(divergent, 5));
    }
    {
        uint32_t guest[] = {
            0xc100f057u, (23u<<26)|(1u<<25)|(3u<<12)|(2u<<7)|0x57u,
            0x3a800513u, (23u<<26)|(1u<<25)|(10u<<15)|(4u<<12)|(3u<<7)|0x57u,
            image(8,0,0,2,3), image(8,1,0,2,2),
            (23u<<26)|(1u<<25)|(7u<<15)|(3u<<12)|(4u<<7)|0x57u,
            image(8,2,0,4,2), image(8,3,0,2,2),
            image(10,0,0,2,2), 0x0330000fu, image(9,0,5,2,2),
            0x0330000fu, 0x02006027u|(1u<<15)|(5u<<7), OPENGPU_SHADER_CEASE,
        };
        assert(opengpu_compute_shader_validate_words_images(guest,15,64,1,false,true));
    }
    {
        uint32_t barrier[] = {
            0xc100f057u, (23u<<26)|(1u<<25)|(3u<<12)|(2u<<7)|0x57u,
            0x3a800513u, (23u<<26)|(1u<<25)|(10u<<15)|(4u<<12)|(3u<<7)|0x57u,
            image(8,0,0,2,3), image(8,1,0,2,2),
            (23u<<26)|(1u<<25)|(7u<<15)|(3u<<12)|(4u<<7)|0x57u,
            image(8,2,0,4,2), image(8,3,0,2,2), image(10,0,0,1,2),
            0x0000000bu, image(9,0,5,2,2), 0x00241293u, 0x005082b3u,
            0x0202e2a7u, 0x0000000bu, OPENGPU_SHADER_CEASE,
        };
        assert(opengpu_compute_shader_validate_words_images(barrier,17,64,8,false,true));
    }
    {
        uint32_t alias[] = {
            0xc100f057u, (23u<<26)|(1u<<25)|(3u<<12)|(2u<<7)|0x57u,
            0x3a800513u, (23u<<26)|(1u<<25)|(10u<<15)|(4u<<12)|(3u<<7)|0x57u,
            image(8,0,0,2,3), image(8,1,0,2,2),
            (23u<<26)|(1u<<25)|(7u<<15)|(3u<<12)|(4u<<7)|0x57u,
            image(8,2,0,4,2), image(8,3,0,2,2), image(10,0,0,2,2),
            0x0330000fu, (23u<<26)|(1u<<25)|(1u<<15)|(3u<<12)|(6u<<7)|0x57u,
            image(8,0,0,6,3), image(8,1,0,2,2), image(9,0,5,2,2),
            0x0330000fu, 0x02006027u|(1u<<15)|(5u<<7), OPENGPU_SHADER_CEASE,
        };
        assert(opengpu_compute_shader_validate_words_images(alias,18,64,1,false,true));
    }
    {
        uint32_t atomic[] = { vl4, image(8,0,0,1,1), image(8,1,0,1,1),
            image(8,2,0,1,1), image(23,0,2,1,1), OPENGPU_SHADER_CEASE };
        for (unsigned op=0; op<8; ++op) {
            atomic[4]=image(23,op,2,1,1);
            assert(opengpu_compute_shader_validate_words_image_atomics(atomic,6,64,4,false,true,false,false,true));
            assert(opengpu_compute_shader_validate_words_image_atomics(atomic,6,64,4,false,true,true,true,true));
            assert(!opengpu_compute_shader_validate_words_image_atomics(atomic,6,64,4,false,true,false,false,false));
            assert(!valid(atomic,6));
        }
        atomic[4]=image(23,1,2,1,1) & ~(1u<<25);
        assert(!opengpu_compute_shader_validate_words_image_atomics(atomic,6,64,4,false,true,false,false,true));
        atomic[4]=image(23,1,2,3,1);
        assert(!opengpu_compute_shader_validate_words_image_atomics(atomic,6,64,4,false,true,false,false,true));
        atomic[4]=image(23,1,2,1,1); atomic[3]=0x00000013u;
        assert(!opengpu_compute_shader_validate_words_image_atomics(atomic,6,64,4,false,true,false,false,true));
        atomic[3]=image(8,2,0,1,1); atomic[0]=0x00000013u;
        assert(!opengpu_compute_shader_validate_words_image_atomics(atomic,6,64,4,false,true,false,false,true));
    }
    {
        const uint32_t ordered[] = {
            0xc100f057u, (23u<<26)|(1u<<25)|(3u<<12)|(2u<<7)|0x57u,
            0x3a800513u, (23u<<26)|(1u<<25)|(10u<<15)|(4u<<12)|(3u<<7)|0x57u,
            image(8,0,0,2,3), image(8,1,0,2,2),
            (23u<<26)|(1u<<25)|(7u<<15)|(3u<<12)|(4u<<7)|0x57u,
            image(8,2,0,4,2), image(8,3,0,2,2), image(10,0,0,2,2),
            0x0330000fu, image(23,1,5,2,2), 0x0330000fu, image(9,0,6,2,2),
            0x0330000fu, 0x02006027u|(1u<<15)|(5u<<7), 0x00408493u,
            0x02006027u|(9u<<15)|(6u<<7), OPENGPU_SHADER_CEASE,
        };
        assert(opengpu_compute_shader_validate_words_image_atomics(ordered,
            sizeof(ordered)/sizeof(ordered[0]),64,1,false,true,false,false,true));
    }
    {
        const uint32_t alias[] = {
            0xc100f057u, (23u<<26)|(1u<<25)|(3u<<12)|(2u<<7)|0x57u,
            (23u<<26)|(1u<<25)|(7u<<15)|(3u<<12)|(3u<<7)|0x57u,
            (23u<<26)|(1u<<25)|(3u<<12)|(4u<<7)|0x57u,
            (14u<<26)|(1u<<25)|(3u<<15)|0x2bu,
            (16u<<26)|(1u<<25)|(4u<<20)|(2u<<15)|0x2bu, 0x0330000fu,
            0x3a800513u, (23u<<26)|(1u<<25)|(10u<<15)|(4u<<12)|(3u<<7)|0x57u,
            image(8,0,0,2,3), image(8,1,0,2,2), image(9,0,5,2,2),
            0x0330000fu, 0x02006027u|(1u<<15)|(5u<<7),
            (23u<<26)|(1u<<25)|(9u<<15)|(3u<<12)|(7u<<7)|0x57u,
            image(8,2,0,7,2), image(8,3,0,2,2), image(10,0,0,2,2),
            0x0330000fu, (15u<<26)|(1u<<25)|(4u<<20)|(2u<<15)|(6u<<7)|0x2bu,
            0x0330000fu, 0x00408493u, 0x02006027u|(9u<<15)|(6u<<7), OPENGPU_SHADER_CEASE,
        };
        assert(opengpu_compute_shader_validate_words_image_atomics(alias,
            sizeof(alias)/sizeof(alias[0]),64,1,false,true,false,true,false));
    }
    {
        uint32_t alias[64]={0}; unsigned n=0;
        alias[n++]=0x00100313u;
        for (unsigned pair=0;pair<8;++pair) { unsigned reg=pair==0 ? 6 : 0;
            alias[n++]=(3u<<25)|(reg<<20)|(reg<<15)|(pair<<12)|0x0bu; }
        alias[n++]=(5u<<25)|(7u<<7)|0x0bu; alias[n++]=0x0070a023u;
        alias[n++]=0xc100f057u; alias[n++]=(23u<<26)|(1u<<25)|(3u<<12)|(2u<<7)|0x57u;
        alias[n++]=0x3a800513u; alias[n++]=(23u<<26)|(1u<<25)|(10u<<15)|(4u<<12)|(3u<<7)|0x57u;
        alias[n++]=(23u<<26)|(1u<<25)|(7u<<15)|(3u<<12)|(4u<<7)|0x57u;
        alias[n++]=(23u<<26)|(1u<<25)|(1u<<15)|(3u<<12)|(6u<<7)|0x57u;
        alias[n++]=image(8,0,0,2,3); alias[n++]=image(8,1,0,2,2);
        alias[n++]=image(8,2,0,4,2); alias[n++]=image(8,3,0,2,2);
        alias[n++]=image(10,0,0,6,6); alias[n++]=0x0330000fu;
        for (unsigned pair=0;pair<8;++pair) { unsigned reg=pair==0 ? 6 : 0;
            alias[n++]=(3u<<25)|(reg<<20)|(reg<<15)|(pair<<12)|0x0bu; }
        alias[n++]=(5u<<25)|(10u<<7)|0x0bu; alias[n++]=0x00a0a223u; alias[n++]=OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_shader_validate_words_image_atomics(alias,n,64,1,false,true,true,false,false));
    }
    {
        uint32_t range[64]={0}; unsigned n=0;
        range[n++]=0x00100313u;
        for (unsigned pair=0;pair<8;++pair) { unsigned reg=pair==0 ? 6 : 0;
            range[n++]=(3u<<25)|(reg<<20)|(reg<<15)|(pair<<12)|0x0bu; }
        range[n++]=(5u<<25)|(7u<<7)|0x0bu; range[n++]=0x0070a023u;
        range[n++]=0xc100f057u; range[n++]=(23u<<26)|(1u<<25)|(3u<<12)|(2u<<7)|0x57u;
        range[n++]=(23u<<26)|(1u<<25)|(7u<<15)|(3u<<12)|(3u<<7)|0x57u;
        range[n++]=0x000011b7u; range[n++]=0x01418193u;
        range[n++]=(23u<<26)|(1u<<25)|(3u<<15)|(4u<<12)|(4u<<7)|0x57u;
        range[n++]=(14u<<26)|(1u<<25)|(3u<<15)|0x2bu;
        range[n++]=(16u<<26)|(1u<<25)|(4u<<20)|(2u<<15)|0x2bu; range[n++]=0x0330000fu;
        range[n++]=0x3a800513u; range[n++]=(23u<<26)|(1u<<25)|(10u<<15)|(4u<<12)|(3u<<7)|0x57u;
        range[n++]=(23u<<26)|(1u<<25)|(1u<<15)|(3u<<12)|(6u<<7)|0x57u;
        range[n++]=image(8,0,0,2,3); range[n++]=image(8,1,0,2,2);
        range[n++]=image(9,0,5,6,6); range[n++]=0x0330000fu;
        range[n++]=0x00408493u; range[n++]=0x02006027u|(9u<<15)|(5u<<7);
        range[n++]=(23u<<26)|(1u<<25)|(9u<<15)|(3u<<12)|(4u<<7)|0x57u;
        range[n++]=image(8,2,0,4,2); range[n++]=image(8,3,0,2,2);
        range[n++]=image(10,0,0,6,6); range[n++]=0x0330000fu;
        for (unsigned pair=0;pair<8;++pair) { unsigned reg=pair==0 ? 6 : 0;
            range[n++]=(3u<<25)|(reg<<20)|(reg<<15)|(pair<<12)|0x0bu; }
        range[n++]=(5u<<25)|(10u<<7)|0x0bu; range[n++]=0x00a0a423u; range[n++]=OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_shader_validate_words_image_atomics(range,n,64,1,false,true,true,true,false));
    }
    {
        const uint32_t ranges[] = {
            0xc100f057u, (23u<<26)|(1u<<25)|(3u<<12)|(2u<<7)|0x57u,
            (23u<<26)|(1u<<25)|(7u<<15)|(3u<<12)|(3u<<7)|0x57u,
            0x000011b7u, 0x01418193u, (23u<<26)|(1u<<25)|(3u<<15)|(4u<<12)|(4u<<7)|0x57u,
            (14u<<26)|(1u<<25)|(3u<<15)|0x2bu,
            (16u<<26)|(1u<<25)|(4u<<20)|(2u<<15)|0x2bu, 0x0330000fu,
            0x3a800513u, (23u<<26)|(1u<<25)|(10u<<15)|(4u<<12)|(3u<<7)|0x57u,
            (23u<<26)|(1u<<25)|(1u<<15)|(3u<<12)|(6u<<7)|0x57u,
            image(8,0,0,2,3), image(8,1,0,2,2), image(9,0,5,6,6),
            0x0330000fu, 0x02006027u|(1u<<15)|(5u<<7),
            (23u<<26)|(1u<<25)|(9u<<15)|(3u<<12)|(7u<<7)|0x57u,
            image(8,2,0,7,2), image(8,3,0,2,2), image(10,0,0,6,6),
            0x0330000fu, (15u<<26)|(1u<<25)|(4u<<20)|(2u<<15)|(6u<<7)|0x2bu,
            0x0330000fu, 0x00408493u, 0x02006027u|(9u<<15)|(6u<<7), OPENGPU_SHADER_CEASE,
        };
        assert(opengpu_compute_shader_validate_words_image_atomics(ranges,
            sizeof(ranges)/sizeof(ranges[0]),64,1,false,true,false,true,false));
    }
    {
        uint32_t p[64]={0}; unsigned n=0;
        p[n++]=0xc100f057u; p[n++]=vecimm(2,0); p[n++]=vecimm(3,7);
        p[n++]=0x000011b7u; p[n++]=0x01418193u;
        p[n++]=(23u<<26)|(1u<<25)|(3u<<15)|(4u<<12)|(4u<<7)|0x57u;
        p[n++]=(14u<<26)|(1u<<25)|(3u<<15)|0x2bu;
        p[n++]=(16u<<26)|(1u<<25)|(4u<<20)|(2u<<15)|0x2bu; p[n++]=0x0330000fu;
        p[n++]=vecimm(8,1); p[n++]=0x01400193u;
        p[n++]=(23u<<26)|(1u<<25)|(3u<<15)|(4u<<12)|(10u<<7)|0x57u;
        p[n++]=(15u<<26)|(1u<<25)|(10u<<20)|(8u<<15)|(11u<<7)|0x2bu;
        p[n++]=0x0330000fu; p[n++]=0x02006027u|(1u<<15)|(11u<<7);
        p[n++]=0x3a800513u; p[n++]=(23u<<26)|(1u<<25)|(10u<<15)|(4u<<12)|(3u<<7)|0x57u;
        p[n++]=vecimm(6,1); p[n++]=image(8,0,0,2,3); p[n++]=image(8,1,0,2,2);
        p[n++]=image(9,0,5,6,6); p[n++]=0x0330000fu; p[n++]=0x00408493u;
        p[n++]=0x02006027u|(9u<<15)|(5u<<7); p[n++]=vecimm(7,9);
        p[n++]=image(8,0,0,8,3); p[n++]=image(8,1,0,2,2);
        p[n++]=image(8,2,0,7,2); p[n++]=image(8,3,0,2,2); p[n++]=image(10,0,0,6,6);
        p[n++]=0x0330000fu; p[n++]=(15u<<26)|(1u<<25)|(4u<<20)|(2u<<15)|(12u<<7)|0x2bu;
        p[n++]=0x0330000fu; p[n++]=0x00808493u; p[n++]=0x02006027u|(9u<<15)|(12u<<7);
        p[n++]=image(8,0,0,2,3); p[n++]=image(8,1,0,2,2); p[n++]=image(9,0,13,6,6);
        p[n++]=0x0330000fu; p[n++]=0x00c08493u; p[n++]=0x02006027u|(9u<<15)|(13u<<7);
        p[n++]=OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_shader_validate_words_image_atomics(p,n,64,1,false,true,false,true,false));
    }
    {
        const uint32_t *combined=opengpu_counter_resources_program;
        const unsigned count=sizeof(opengpu_counter_resources_program)/sizeof(combined[0]);
        assert(combined[OPENGPU_COUNTER_RESOURCE_INDEX_WORD]==0x5e003157u);
        assert(opengpu_compute_shader_validate_words_counter_resources(
            combined,count,64,1,false,false,true,true,true,false,false));
        assert(!opengpu_compute_shader_validate_words_counter_resources(
            combined,count,64,1,false,false,false,true,true,false,false));
        assert(!opengpu_compute_shader_validate_words_counter_resources(
            combined,count,64,1,false,false,true,false,true,false,false));
        assert(!opengpu_compute_shader_validate_words_counter_resources(
            combined,count,64,1,false,false,true,true,false,false,false));
        assert(!opengpu_compute_shader_validate_words_all_resources(
            combined,count,64,1,false,false,true,true,true,false,false));
    }
    return 0;
}
