/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "../opengpu_shader_validator.h"

struct program { uint32_t w[512]; unsigned n; };
static unsigned checks;
static uint32_t v(unsigned fn,unsigned form,unsigned d,unsigned a,unsigned b)
{ return fn<<26|1u<<25|b<<20|a<<15|form<<12|d<<7|0x57u; }
static uint32_t branch(unsigned f,unsigned a,unsigned b,int bytes)
{
    unsigned n=(unsigned)bytes&8191u;
    return (n>>12&1u)<<31|(n>>5&63u)<<25|b<<20|a<<15|f<<12|
        (n>>1&15u)<<8|(n>>11&1u)<<7|0x5bu;
}
static uint32_t jump(unsigned rd, int bytes)
{
    uint32_t displacement = (uint32_t)bytes & 0x1fffffu;
    return ((displacement >> 20) & 1u) << 31 |
        ((displacement >> 1) & 1023u) << 21 |
        ((displacement >> 11) & 1u) << 20 |
        ((displacement >> 12) & 255u) << 12 | rd << 7 | 0x6fu;
}
static void emit(struct program *p,uint32_t w) { assert(p->n<512);p->w[p->n++]=w; }
static struct program start(void)
{
    struct program p={{0},0};emit(&p,0x01007557u);emit(&p,v(0x17,4,2,0,0));return p;
}
static void body(struct program *p,unsigned kind)
{
    switch(kind){
    case 0: break;
    case 1: emit(p,v(0,4,3,0,1));break;
    case 2: emit(p,7u<<20|10u<<7|0x13u);emit(p,v(0x17,4,3,10,0));break;
    case 3: emit(p,v(0x17,4,3,10,0));break; /* inherited warp scratch forbidden */
    case 4: emit(p,7u<<20|11u<<7|0x13u);break;
    case 5: emit(p,0x01007557u);break;
    case 6: emit(p,OPENGPU_SHADER_SET_VXRM(0));break;
    case 7: emit(p,OPENGPU_SHADER_CEASE);break;
    case 8: emit(p,0x0000000bu);break;
    case 9: emit(p,14u<<26|1u<<25|1u<<15|0x2bu);emit(p,16u<<26|1u<<25|2u<<20|2u<<15|0x2bu);break;
    case 10: emit(p,16u<<26|1u<<25|2u<<20|2u<<15|0x2bu);break;
    case 11: emit(p,4u<<20|1u<<15|2u<<12|10u<<7|3u);emit(p,v(0x17,4,3,10,0));break;
    case 12: emit(p,12u<<20|1u<<15|2u<<12|10u<<7|3u);break;
    case 13: emit(p,v(0,4,3,0,1));emit(p,v(0x19,4,0,0,1));emit(p,v(0x17,3,3,1,1)&~(1u<<25));break;
    case 14: emit(p,0x0000006fu);break;
    case 15: emit(p,0x00000063u);break;
    case 16: emit(p,7u<<26|1u<<25|2u<<20|2u<<15|3u<<7|0x2bu);break;
    default: assert(0);
    }
}
static unsigned scope(struct program *p,unsigned left,unsigned right,unsigned nesting)
{
    unsigned at=p->n;emit(p,0);emit(p,0);
    if(nesting)scope(p,left,right,nesting-1);else body(p,left);
    emit(p,OPENGPU_SHADER_SIMT_JOIN);unsigned taken=p->n;
    if(nesting)scope(p,left,right,nesting-1);else body(p,right);
    emit(p,OPENGPU_SHADER_SIMT_JOIN);unsigned marker=p->n;emit(p,OPENGPU_SHADER_SIMT_JOIN);
    p->w[at]=branch(3,0,0,(int)(marker-at)*4);
    p->w[at+1]=branch(0,1,2,(int)(taken-at-1)*4);
    return at;
}
static void check(struct program p,int want,unsigned uniforms)
{
    emit(&p,OPENGPU_SHADER_CEASE);
    int got=uniforms?opengpu_compute_shader_validate_words_uniform_resources(p.w,512,12,128,
        false,false,false,true,false,false,false,false):
        opengpu_compute_shader_validate_words_storage(p.w,512,12,128,false);
    if(got!=want)fprintf(stderr,"SIMT validator case%u expected%d got%d words%u\n",checks,want,got,p.n);
    assert(got==want);checks++;
    /* Adding the complete shared-engine discovery must preserve this exact
     * legacy storage dialect, including every rejected branch/VL envelope. */
    const unsigned local_items[]={4,8,16,128};
    for(unsigned n=0;n<sizeof(local_items)/sizeof(local_items[0]);n++) {
        got=opengpu_compute_shader_validate_words_shared_atomics(p.w,512,12,local_items[n],
            false,false,false,true,false,false,false,false,uniforms,
            0x10000000u,16384,16,false,true);
        if(got!=want)fprintf(stderr,"discovered-shared SIMT case%u local%u expected%d got%d\n",
            checks,local_items[n],want,got);
        assert(got==want);checks++;
    }
    const unsigned malformed[][3]={{0x10000001u,16384,16},{0x10000000u,16383,16},
        {0x10000000u,16384,0},{0xffffe000u,16384,16}};
    for(unsigned n=0;n<sizeof(malformed)/sizeof(malformed[0]);n++) {
        assert(!opengpu_compute_shader_validate_words_shared_atomics(p.w,512,12,4,
            false,false,false,true,false,false,false,false,uniforms,
            malformed[n][0],malformed[n][1],malformed[n][2],false,true));checks++;
    }
}
int main(void)
{
    struct program p=start();scope(&p,1,1,0);emit(&p,v(0,4,4,0,3));check(p,1,0);
    p=start();scope(&p,0,0,0);check(p,1,0);
    p=start();scope(&p,1,0,0);emit(&p,v(0,4,4,0,3));check(p,0,0);
    p=start();body(&p,1);scope(&p,1,0,0);emit(&p,v(0,4,4,0,3));check(p,1,0);
    p=start();scope(&p,1,1,3);check(p,1,0); /*4 scopes,8 physical entries*/
    p=start();scope(&p,1,1,4);check(p,0,0);
    for(unsigned k=2;k<=16;k++){
        p=start();scope(&p,k,k,0);
        check(p,k==2 || k==9 || k==11 || k==13 || k==16,k==16);
    }
    p=start();scope(&p,2,3,0);check(p,0,0);
    p=start();scope(&p,2,2,0);emit(&p,v(0x17,4,4,10,0));check(p,0,0);
    p=start();scope(&p,2,2,0);body(&p,2);check(p,1,0);
    p=start();body(&p,9);scope(&p,10,10,0);check(p,0,0);
    p=start();scope(&p,9,10,0);check(p,0,0);
    p=start();body(&p,9);scope(&p,0,0,0);body(&p,10);check(p,0,0);
    p=start();unsigned s=scope(&p,1,1,0),end=s+(unsigned)opengpu_shader_simt_displacement(p.w[s])/4;
    struct program good=p;
    p.w[s]|=1u<<15;check(p,0,0);p=good;
    p.w[s]=branch(3,0,0,2);check(p,0,0);p=good;
    p.w[s+1]=branch(0,1,2,-4);check(p,0,0);p=good;
    p.w[s+1]=branch(0,31,2,opengpu_shader_simt_displacement(p.w[s+1]));check(p,0,0);p=good;
    unsigned taken=s+1+(unsigned)opengpu_shader_simt_displacement(p.w[s+1])/4;
    p.w[taken-1]=0x13u;check(p,0,0);p=good;
    p.w[end-1]=0x13u;check(p,0,0);p=good;
    p.w[end]=0x13u;check(p,0,0);p=good;
    p.w[end]|=1u<<15;check(p,0,0);p=good;
    p.w[s+2]=OPENGPU_SHADER_SIMT_JOIN;check(p,0,0);
    p=start();emit(&p,OPENGPU_SHADER_SIMT_JOIN);check(p,0,0);
    p=start();emit(&p,branch(0,1,2,4));check(p,0,0);
    p=good;emit(&p,0x00000263u);check(p,0,0);
    p=start();emit(&p,0x00000263u);scope(&p,1,1,0);check(p,0,0);
    /* A full-lane branch must not read definitions created with a shorter VL,
     * even if VLMAX is restored before the first scope. Legacy VL is intact. */
    p=start();emit(&p,0xc100f057u);scope(&p,1,1,0);check(p,0,0);
    p=start();emit(&p,0xc100f057u);emit(&p,0x01007557u);scope(&p,1,1,0);check(p,0,0);
    p=good;emit(&p,0xc100f057u);check(p,0,0);
    p=start();emit(&p,v(0x10,6,3,0,0));scope(&p,1,1,0);check(p,0,0);
    p=start();emit(&p,0xc100f057u);check(p,1,0);
    p=start();body(&p,1);scope(&p,1,1,0);emit(&p,0x01007557u);check(p,1,0);
    p=good;emit(&p,OPENGPU_SHADER_CEASE);
    assert(!opengpu_compute_shader_validate_words(p.w,512,12,128)); /* no storage profile */
    checks++;
    p=start();emit(&p,OPENGPU_SHADER_CEASE);p.w[100]=0x5bu;
    assert(opengpu_compute_shader_validate_words_storage(p.w,512,12,128,false));checks++;
    /* Lanes at their local-ID limit park at the marker. Only the remaining
     * lanes increment v2 and take the backedge to the condition. */
    p = start();
    emit(&p, 1u << 20 | 10u << 7 | 0x13u);
    emit(&p, v(0x17, 4, 3, 10, 0));
    unsigned loop = p.n;
    emit(&p, branch(3, 0, 0, 16));
    emit(&p, branch(0, 2, 1, 12));
    emit(&p, v(0, 0, 2, 3, 2));
    emit(&p, jump(0, -8));
    emit(&p, OPENGPU_SHADER_SIMT_JOIN);
    emit(&p, v(0, 0, 4, 2, 2));
    struct program loop_program = p;
    p.w[loop + 3] = jump(0, -12); /* Re-entering sync would leak a scope. */
    check(p, 0, 0);
    p = loop_program;
    p.w[loop + 3] = jump(1, -8); /* A backedge must preserve scalar ABI state. */
    check(p, 0, 0);
    p = loop_program;
    p.w[loop + 2] = v(0, 0, 5, 3, 2);
    p.w[loop + 5] = v(0, 0, 4, 5, 2); /* A lane can exit on the first test. */
    check(p, 0, 0);
    check(loop_program, 1, 0);
    p = start();
    emit(&p, 1u << 20 | 10u << 7 | 0x13u);
    emit(&p, v(0x17, 4, 3, 10, 0));
    loop = p.n;
    emit(&p, branch(3, 0, 0, 24));
    emit(&p, v(0, 0, 4, 2, 2));
    emit(&p, branch(0, 4, 1, 16));
    emit(&p, v(0, 0, 2, 3, 2));
    emit(&p, branch(0, 2, 1, 8));
    emit(&p, jump(0, -16));
    emit(&p, OPENGPU_SHADER_SIMT_JOIN);
    check(p, 1, 0);
    p.w[loop + 4] = branch(0, 2, 1, 4); /* Every exit must park at the marker. */
    check(p, 0, 0);
    p = start();
    loop = p.n;
    emit(&p, branch(3, 0, 0, 20));
    emit(&p, branch(0, 2, 1, 16));
    body(&p, 9); /* Capture and store on the same continuing path. */
    emit(&p, jump(0, -12));
    emit(&p, OPENGPU_SHADER_SIMT_JOIN);
    check(p, 1, 0);
    uint32_t capture = p.w[loop + 2];
    p.w[loop + 2] = branch(0, 2, 1, 12);
    p.w[loop + 1] = capture;
    check(p, 0, 0); /* The exiting lanes must not leave a usable capture. */
    p = start();
    loop = p.n;
    emit(&p, branch(3, 0, 0, 36));
    emit(&p, branch(0, 2, 1, 32));
    emit(&p, branch(3, 0, 0, 16));
    emit(&p, branch(0, 2, 1, 12));
    emit(&p, v(0, 0, 2, 1, 2));
    emit(&p, jump(0, -8));
    emit(&p, OPENGPU_SHADER_SIMT_JOIN);
    emit(&p, v(0, 0, 2, 1, 2));
    emit(&p, jump(0, -28));
    emit(&p, OPENGPU_SHADER_SIMT_JOIN);
    check(p, 1, 0);
    struct program nested_loop = p;
    p.w[loop + 3] = branch(0, 2, 1, 24); /* The inner exit must join its own scope. */
    check(p, 0, 0);
    p = nested_loop;
    p.w[loop + 5] = jump(0, -16); /* The inner backedge must stay in its own scope. */
    check(p, 0, 0);
    printf("Compute canonical SIMT validator:%u actual-function cases PASS;4 snapshots/8 physical entries;old profile unchanged\n",checks);
    return 0;
}
