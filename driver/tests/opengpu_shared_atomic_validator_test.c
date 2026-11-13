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

static void range_oracle(void)
{
    struct opengpu_shared_own_proof proof={0};
    struct opengpu_shader_state source={0},destination={0};
    destination.shared_own=&proof;proof.count=1;proof.span=512;
    proof.vectors[2]=opengpu_range_constant(508);
    opengpu_shader_copy_state(&destination,&source);
    assert(destination.shared_own==&proof && !proof.count && !proof.span && !proof.vectors[2].valid);
    uint32_t seed=0x8a731b91u;
    for(unsigned test=0;test<5000;test++) {
        uint32_t a[4],b[4];
        struct opengpu_shared_range x={0},y={0};
        for(unsigned i=0;i<4;i++) {
            seed=seed*1664525u+1013904223u;a[i]=test<2500?seed&255:seed;
            seed=seed*1664525u+1013904223u;b[i]=test<2500?seed&255:seed;
            x=i?opengpu_range_join(x,opengpu_range_constant(a[i])):opengpu_range_constant(a[i]);
            y=i?opengpu_range_join(y,opengpu_range_constant(b[i])):opengpu_range_constant(b[i]);
        }
        for(unsigned op=0;op<=OPENGPU_RANGE_SHR;op++) {
            struct opengpu_shared_range z=y;
            if(op==OPENGPU_RANGE_SHL || op==OPENGPU_RANGE_SHR) z=opengpu_range_constant(test%32);
            struct opengpu_shared_range result=opengpu_range_eval(op,x,z);
            for(unsigned i=0;i<4;i++) for(unsigned j=0;j<4;j++) {
                uint64_t value;
                switch(op) {
                case OPENGPU_RANGE_ADD:value=(uint64_t)a[i]+b[j];break;
                case OPENGPU_RANGE_MUL:value=(uint64_t)a[i]*b[j];break;
                case OPENGPU_RANGE_AND:value=a[i]&b[j];break;
                case OPENGPU_RANGE_OR:value=a[i]|b[j];break;
                case OPENGPU_RANGE_XOR:value=a[i]^b[j];break;
                case OPENGPU_RANGE_SHL:value=(uint64_t)a[i]<<z.first;break;
                default:value=a[i]>>z.first;break;
                }
                if(result.valid) {
                    assert(value<=UINT32_MAX && value>=result.first && value<=result.last);
                    assert(!(value&result.zero));
                }
            }
        }
        struct opengpu_shared_range bounded=opengpu_range_eval(OPENGPU_RANGE_AND,
            (struct opengpu_shared_range){0},opengpu_range_constant(508));
        assert(bounded.valid && bounded.last==508 && (bounded.zero&3)==3);
    }
    assert(!opengpu_range_eval(OPENGPU_RANGE_ADD,opengpu_range_constant(UINT32_MAX),
        opengpu_range_constant(1)).valid);
    assert(!opengpu_range_eval(OPENGPU_RANGE_SHL,opengpu_range_constant(0x80000000),
        opengpu_range_constant(1)).valid);
}

static uint32_t branch(unsigned f,unsigned a,unsigned b,unsigned words)
{
    uint32_t bytes=words*4;
    return ((bytes>>12)&1)<<31|((bytes>>5)&63)<<25|b<<20|a<<15|f<<12|
        ((bytes>>1)&15)<<8|((bytes>>11)&1)<<7|0x5b;
}
static bool dynamic_validate(const uint32_t *p,unsigned n)
{
    return opengpu_compute_shader_validate_words_shared_atomics(p,n,512,128,
        false,false,false,true,false,false,false,false,false,0x10000000,16384,16,false,true);
}
static void dynamic_scopes(void)
{
    for(unsigned op=0;op<10;op++) {
        uint32_t p[]={0xc1087057,
            2u<<20|8u<<15|1u<<12|11u<<7|0x13u,
            0x10000000u|10u<<7|0x37u,
            10u<<20|11u<<15|11u<<7|0x33u,
            1u<<25|11u<<15|6u<<12|1u<<7|0x27u,
            0x0330000fu,0x0000000bu,
            vector(0x17,3,4,31,0),vector(0x17,3,5,15,0),
            vector(9,0,2,5,1),vector(0x25,3,2,2,2),
            branch(3,0,0,10),branch(0,1,5,5),
            0x10000000u|10u<<7|0x37u,
            resource(30,0,0,4,1),resource(op<8?31:32,op%8,3,10,2),0x205bu,
            0x10000000u|10u<<7|0x37u,
            resource(30,0,0,1,4),resource(op<8?31:32,op%8,3,10,2),0x205bu,
            0x205bu,0x30500073u};
        unsigned n=sizeof(p)/sizeof(*p);uint32_t q[sizeof(p)/sizeof(*p)];
        assert(dynamic_validate(p,n));
        memcpy(q,p,sizeof(p));q[4]=0x13;assert(!dynamic_validate(q,n));
        memcpy(q,p,sizeof(p));q[6]=0x13;assert(!dynamic_validate(q,n));
        memcpy(q,p,sizeof(p));q[10]=vector(0,3,2,1,2);assert(!dynamic_validate(q,n)); // unaligned
        memcpy(q,p,sizeof(p));q[9]=vector(0,0,2,4,1);assert(!dynamic_validate(q,n)); // signed/wrap
        memcpy(q,p,sizeof(p));q[9]=vector(0,3,2,31,1);assert(!dynamic_validate(q,n)); // vi -1 is not +31
        memcpy(q,p,sizeof(p));q[10]=vector(0x25,3,2,6,2);assert(!dynamic_validate(q,n)); // beyond init
        memcpy(q,p,sizeof(p));q[10]=vector(0x25,3,2,31,2);assert(!dynamic_validate(q,n)); // overflow
        memcpy(q,p,sizeof(p));q[14]=0x13;assert(!dynamic_validate(q,n)); // arm-local capture
        memcpy(q,p,sizeof(p));q[18]=0x13;assert(!dynamic_validate(q,n)); // other arm capture
        memcpy(q,p,sizeof(p));q[14]=0x0b;assert(!dynamic_validate(q,n)); // divergent barrier
        memcpy(q,p,sizeof(p));q[15]&=~(1u<<25);assert(!dynamic_validate(q,n)); // explicit VM masked
        uint32_t short_vl[sizeof(p)/sizeof(*p)+3];
        memcpy(short_vl,p,7*sizeof(*p));
        short_vl[7]=0xc1027057; // VL4
        short_vl[8]=vector(0,4,6,0,1); // defined only in the short prefix
        short_vl[9]=0xc1087057; // restore VL16 cannot authorize stale lanes
        memcpy(short_vl+10,p+7,(n-7)*sizeof(*p));
        short_vl[15]=branch(0,6,5,5);
        assert(!dynamic_validate(short_vl,n+3));
        uint32_t captures[64];unsigned nc=11;memcpy(captures,p,nc*sizeof(*p));
        captures[nc++]=vector(0,4,0,0,2); // bounded ordinary value in v0
        captures[nc++]=resource(14,0,0,4,0); // no vector writeback
        captures[nc++]=resource(30,0,0,4,1); // no vector writeback
        captures[nc++]=vector(0,4,2,0,0); // copy v0; rs2=0 means v0 for ADD
        captures[nc++]=resource(op<8?31:32,op%8,3,10,2);
        captures[nc++]=0x30500073;
        assert(dynamic_validate(captures,nc));
        captures[nc-2]=resource(op<8?31:32,op%8,2,10,2); // alias kills the address range
        captures[nc-1]=resource(op<8?31:32,op%8,3,10,2);
        captures[nc]=0x30500073;
        assert(!dynamic_validate(captures,nc+1));
        /* Both arms define distinct ranges in the SAME physical destination.
         * The post-join AMO requires their union, never their intersection or
         * merely the arm visited last by the validator. */
        uint32_t joined[64];unsigned k=11;memcpy(joined,p,k*sizeof(*p));
        unsigned sync=k++;unsigned condition=k++;
        joined[k++]=64u<<20|10u<<7|0x13u;
        joined[k++]=vector(0,4,2,10,2);
        joined[k++]=0x205b;unsigned taken=k;
        unsigned other=k;
        joined[k++]=400u<<20|10u<<7|0x13u;
        joined[k++]=vector(0,4,2,10,2);
        joined[k++]=0x205b;unsigned marker=k;joined[k++]=0x205b;
        joined[sync]=branch(3,0,0,marker-sync);
        joined[condition]=branch(0,1,5,taken-condition);
        joined[k++]=0x10000000u|10u<<7|0x37u;
        joined[k++]=resource(30,0,0,4,1);
        joined[k++]=resource(op<8?31:32,op%8,3,10,2);
        joined[k++]=0x30500073;
        assert(dynamic_validate(joined,k));
        joined[other]=512u<<20|10u<<7|0x13u;
        assert(!dynamic_validate(joined,k));
        joined[other]=0xfff00513u; // negative ADD offset cannot wrap into a proof
        assert(!dynamic_validate(joined,k));
        joined[other]=400u<<20|10u<<7|0x13u;
        joined[other+1]=vector(0x17,0,2,4,0); // UINT_MAX, one invalid arm
        assert(!dynamic_validate(joined,k));
    }
}
int main(void)
{
    range_oracle();dynamic_scopes();
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
