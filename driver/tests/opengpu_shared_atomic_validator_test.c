// SPDX-License-Identifier: MIT
#include <assert.h>
#include <stdint.h>
#include <string.h>
#include "../opengpu_shader_validator.h"

static uint32_t vector(unsigned fn,unsigned form,unsigned rd,unsigned a,unsigned b)
{ return fn<<26|1u<<25|b<<20|a<<15|form<<12|rd<<7|0x57u; }
static uint32_t resource(unsigned fn,unsigned op,unsigned rd,unsigned a,unsigned b)
{ return fn<<26|1u<<25|b<<20|a<<15|op<<12|rd<<7|0x2bu; }
static bool validate(const uint32_t *p,unsigned n,uint32_t base,bool enabled)
{
    return opengpu_compute_shader_validate_words_shared_atomics(p,n,512,128,
        false,false,false,false,false,false,false,false,false,
        base,16384,16,false,enabled);
}
int main(void)
{
    const uint32_t bases[]={0,0x10000000u,0xffffc000u};
    for(unsigned b=0;b<3;b++) for(unsigned op=0;op<10;op++) {
        uint32_t p[]={
            0xc1087057u, // vsetivli zero,16,e32,m1
            2u<<20|8u<<15|1u<<12|11u<<7|0x13u, // warp byte index
            bases[b]|10u<<7|0x37u,
            10u<<20|11u<<15|11u<<7|0x33u,
            1u<<25|11u<<15|6u<<12|1u<<7|0x27u, // real full-stripe initializer
            0x0330000fu,0x0000000bu,
            vector(0x25,3,2,2,1), // trusted localID bytes
            resource(30,0,0,1,1),
            resource(op<8?31:32,op%8,3,10,0), // all invocations contend
            0x30500073u
        };
        const unsigned n=sizeof(p)/sizeof(p[0]);
        assert(validate(p,n,bases[b],true));
        assert(!validate(p,n,bases[b],false));
        assert(!opengpu_compute_shader_validate_words_shared_resources(p,n,512,128,
            false,false,false,false,false,false,false,false,false,bases[b],16384,16));
        uint32_t q[n];memcpy(q,p,sizeof(p));
        q[4]=0x00000013u;assert(!validate(q,n,bases[b],true)); // no actual initializer
        memcpy(q,p,sizeof(p));q[6]=0x00000013u;
        assert(!validate(q,n,bases[b],true)); // written prefix is not publication
        q[9]=resource(op<8?31:32,op%8,3,10,2);
        assert(validate(q,n,bases[b],true)); // each invocation's exact own stripe
        memcpy(q,p,sizeof(p));q[8]=0x00000013u;
        assert(!validate(q,n,bases[b],true)); // parameter owner required
        memcpy(q,p,sizeof(p));q[9]&=~(1u<<25);
        assert(!validate(q,n,bases[b],true)); // masked software profile stays off
        memcpy(q,p,sizeof(p));q[9]=resource(32,2,3,10,0);
        assert(!validate(q,n,bases[b],true)); // reserved operation
        memcpy(q,p,sizeof(p));q[9]=resource(op<8?31:32,op%8,3,1,0);
        assert(!validate(q,n,bases[b],true)); // kernarg cannot authorize shared
        memcpy(q,p,sizeof(p));q[9]=resource(op<8?31:32,op%8,3,10,1);
        assert(!validate(q,n,bases[b],true)); // ID is not an aligned byte-offset proof
        memcpy(q,p,sizeof(p));q[8]=resource(30,0,1,1,1);
        assert(!validate(q,n,bases[b],true)); // parameter destination reserved
        memcpy(q,p,sizeof(p));q[8]=0xc1087057u;
        assert(!validate(q,n,bases[b],true)); // reconfiguration cannot retain params
        memcpy(q,p,sizeof(p));q[2]=(bases[b]+16384u)|10u<<7|0x37u;
        assert(!validate(q,n,bases[b],true)); // first out-of-range or wrapped base
        memcpy(q,p,sizeof(p));q[9]=resource(op<8?31:32,op%8,3,10,2);
        q[6]=0x00000013u;q[7]=vector(0,3,2,1,1);
        assert(!validate(q,n,bases[b],true)); // computed/unproven offset
    }
    return 0;
}
